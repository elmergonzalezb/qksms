/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package feature.compose

import android.animation.ObjectAnimator
import android.content.ContentUris
import android.content.Context
import android.graphics.Typeface
import android.telephony.PhoneNumberUtils
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import com.jakewharton.rxbinding2.view.clicks
import com.jakewharton.rxbinding2.view.longClicks
import com.moez.QKSMS.R
import common.Navigator
import common.base.QkRealmAdapter
import common.base.QkViewHolder
import common.util.Colors
import common.util.DateFormatter
import common.util.GlideApp
import common.util.extensions.dpToPx
import common.util.extensions.forwardTouches
import common.util.extensions.setBackgroundTint
import common.util.extensions.setPadding
import common.util.extensions.setTint
import common.util.extensions.setVisible
import common.widget.BubbleImageView.Style.*
import compat.SubscriptionManagerCompat
import ezvcard.Ezvcard
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import io.realm.RealmResults
import kotlinx.android.synthetic.main.message_list_item_in.view.*
import kotlinx.android.synthetic.main.mms_preview_list_item.view.*
import kotlinx.android.synthetic.main.mms_vcard_list_item.view.*
import mapper.CursorToPartImpl
import model.Conversation
import model.Message
import model.Recipient
import util.Preferences
import util.extensions.isImage
import util.extensions.isVCard
import util.extensions.isVideo
import util.extensions.mapNotNull
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class MessagesAdapter @Inject constructor(
        private val context: Context,
        private val colors: Colors,
        private val dateFormatter: DateFormatter,
        private val navigator: Navigator,
        private val prefs: Preferences,
        private val subscriptionManager: SubscriptionManagerCompat
) : QkRealmAdapter<Message>() {

    companion object {
        private const val VIEW_TYPE_MESSAGE_IN = 0
        private const val VIEW_TYPE_MESSAGE_OUT = 1
        private const val TIMESTAMP_THRESHOLD = 10
    }

    val clicks: Subject<Message> = PublishSubject.create<Message>()
    val cancelSending: Subject<Message> = PublishSubject.create<Message>()

    var data: Pair<Conversation, RealmResults<Message>>? = null
        set(value) {
            if (field === value) return

            field = value
            contactCache.clear()

            // Update the theme
            theme = colors.theme(value?.first?.id ?: 0)

            updateData(value?.second)
        }

    /**
     * Safely return the conversation, if available
     */
    private val conversation: Conversation?
        get() = data?.first?.takeIf { it.isValid }

    /**
     * Mark this message as highlighted
     */
    var highlight: Long = -1L
        set(value) {
            if (field == value) return

            field = value
            notifyDataSetChanged()
        }

    private val contactCache = ContactCache()
    private val expanded = HashMap<Long, Boolean>()
    private val subs = subscriptionManager.activeSubscriptionInfoList

    var theme: Colors.Theme = colors.theme()

    /**
     * If the viewType is negative, then the viewHolder has an attachment. We'll consider
     * this a unique viewType even though it uses the same view, so that regular messages
     * don't need clipToOutline set to true, and they don't need to worry about images
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QkViewHolder {

        // Use the parent's context to inflate the layout, otherwise link clicks will crash the app
        val layoutInflater = LayoutInflater.from(parent.context)
        val view: View

        if (viewType == VIEW_TYPE_MESSAGE_OUT) {
            view = layoutInflater.inflate(R.layout.message_list_item_out, parent, false)
            view.findViewById<ImageView>(R.id.cancelIcon).setTint(theme.theme)
            view.findViewById<ProgressBar>(R.id.cancel).setTint(theme.theme)
        } else {
            view = layoutInflater.inflate(R.layout.message_list_item_in, parent, false)
            view.avatar.threadId = conversation?.id ?: 0
            view.body.setTextColor(theme.textPrimary)
            view.body.setBackgroundTint(theme.theme)
        }

        view.body.forwardTouches(view)

        return QkViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: QkViewHolder, position: Int) {
        val message = getItem(position)!!
        val previous = if (position == 0) null else getItem(position - 1)
        val next = if (position == itemCount - 1) null else getItem(position + 1)
        val view = viewHolder.itemView

        view.clicks().subscribe {
            when (toggleSelection(message.id, false)) {
                true -> view.isSelected = isSelected(message.id)
                false -> {
                    clicks.onNext(message)
                    expanded[message.id] = view.status.visibility != View.VISIBLE
                    bindStatus(viewHolder, message, next)
                }
            }
        }
        view.longClicks().subscribe {
            toggleSelection(message.id)
            view.isSelected = isSelected(message.id)
        }


        // Update the selected state
        view.isSelected = isSelected(message.id) || highlight == message.id


        // Bind the cancel view
        view.findViewById<ProgressBar>(R.id.cancel)?.let { cancel ->
            val isCancellable = message.isSending() && message.date > System.currentTimeMillis()
            cancel.setVisible(isCancellable)
            cancel.clicks().subscribe { cancelSending.onNext(message) }
            cancel.progress = 2

            if (isCancellable) {
                val delay = when (prefs.sendDelay.get()) {
                    Preferences.SEND_DELAY_SHORT -> 3000
                    Preferences.SEND_DELAY_MEDIUM -> 5000
                    Preferences.SEND_DELAY_LONG -> 10000
                    else -> 0
                }
                val progress = (1 - (message.date - System.currentTimeMillis()) / delay.toFloat()) * 100

                ObjectAnimator.ofInt(cancel, "progress", progress.toInt(), 100)
                        .setDuration(message.date - System.currentTimeMillis())
                        .start()
            }
        }


        // Bind the message status
        bindStatus(viewHolder, message, next)


        // Bind the timestamp
        val timeSincePrevious = TimeUnit.MILLISECONDS.toMinutes(message.date - (previous?.date ?: 0))
        val simIndex = subs.takeIf { it.size > 1 }?.indexOfFirst { it.subscriptionId == message.subId } ?: -1

        view.timestamp.text = dateFormatter.getMessageTimestamp(message.date)
        view.simIndex.text = "${simIndex + 1}"

        view.timestamp.setVisible(timeSincePrevious >= TIMESTAMP_THRESHOLD || message.subId != previous?.subId && simIndex != -1)
        view.sim.setVisible(message.subId != previous?.subId && simIndex != -1)
        view.simIndex.setVisible(message.subId != previous?.subId && simIndex != -1)


        // Bind the grouping
        val media = message.parts.filter { it.isImage() || it.isVideo() || it.isVCard() }
        view.setPadding(bottom = if (canGroup(message, next)) 0 else 16.dpToPx(context))


        // Bind the avatar
        if (!message.isMe()) {
            view.avatar.threadId = conversation?.id ?: 0
            view.avatar.setContact(contactCache[message.address])
            view.avatar.setVisible(!canGroup(message, next), View.INVISIBLE)
        }


        // Bind the body text
        view.body.text = when (message.isSms()) {
            true -> message.body
            false -> {
                val subject = SpannableStringBuilder(message.getCleansedSubject())
                subject.setSpan(StyleSpan(Typeface.BOLD), 0, subject.length, Spannable.SPAN_INCLUSIVE_EXCLUSIVE)

                val body = message.parts
                        .filter { part -> !part.isVCard() }
                        .mapNotNull { part -> part.text }
                        .filter { part -> part.isNotBlank() }
                        .joinToString("\n")

                when {
                    subject.isNotBlank() && body.isNotBlank() -> subject.append("\n$body")
                    subject.isNotBlank() -> subject
                    else -> body
                }
            }
        }
        view.body.setVisible(message.isSms() || view.body.text.isNotBlank())
        view.body.setBackgroundResource(getBubble(
                canGroupWithPrevious = canGroup(message, previous) || media.isNotEmpty(),
                canGroupWithNext = canGroup(message, next),
                isMe = message.isMe()))


        // Bind the attachments
        view.attachments.setVisible(media.isNotEmpty())
        if (view.attachments.childCount > 0) {
            view.attachments.removeAllViews()
        }

        media.forEachIndexed { index, part ->
            val canMediaGroupWithPrevious = canGroup(message, previous) || index > 0
            val canMediaGroupWithNext = canGroup(message, next) || index < media.size - 1 || view.body.visibility == View.VISIBLE
            val inflater = LayoutInflater.from(view.context)

            when {
                part.isImage() || part.isVideo() -> {
                    val mediaView = inflater.inflate(R.layout.mms_preview_list_item, view.attachments, false)
                    mediaView.video.setVisible(part.isVideo())
                    mediaView.forwardTouches(view)
                    mediaView.setOnClickListener { navigator.showMedia(part.id) }

                    mediaView.thumbnail.bubbleStyle = when {
                        !canMediaGroupWithPrevious && canMediaGroupWithNext -> if (message.isMe()) OUT_FIRST else IN_FIRST
                        canMediaGroupWithPrevious && canMediaGroupWithNext -> if (message.isMe()) OUT_MIDDLE else IN_MIDDLE
                        canMediaGroupWithPrevious && !canMediaGroupWithNext -> if (message.isMe()) OUT_LAST else IN_LAST
                        else -> ONLY
                    }

                    GlideApp.with(context).load(part.getUri()).fitCenter().into(mediaView.thumbnail)
                    view.attachments.addView(mediaView, index)
                }

                part.isVCard() -> {
                    val uri = ContentUris.withAppendedId(CursorToPartImpl.CONTENT_URI, part.id)

                    val mediaView = inflater.inflate(R.layout.mms_vcard_list_item, view.attachments, false)
                    mediaView.forwardTouches(view)
                    mediaView.setOnClickListener { navigator.saveVcard(uri) }
                    mediaView.vCardBackground.setBackgroundResource(getBubble(canMediaGroupWithPrevious, canMediaGroupWithNext, message.isMe()))

                    Observable.just(uri)
                            .map(context.contentResolver::openInputStream)
                            .mapNotNull { inputStream -> inputStream.use { Ezvcard.parse(it).first() } }
                            .subscribeOn(Schedulers.computation())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe { vcard -> mediaView?.name?.text = vcard.formattedName.value }

                    if (!message.isMe()) {
                        mediaView.vCardBackground.setBackgroundTint(theme.theme)
                        mediaView.vCardAvatar.setTint(theme.textPrimary)
                        mediaView.name.setTextColor(theme.textPrimary)
                        mediaView.label.setTextColor(theme.textTertiary)
                    }
                    view.attachments.addView(mediaView, index)
                }
            }
        }
    }

    private fun bindStatus(viewHolder: QkViewHolder, message: Message, next: Message?) {
        val view = viewHolder.itemView

        val age = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - message.date)
        val timestamp = dateFormatter.getTimestamp(message.date)

        view.status.text = when {
            message.isSending() -> context.getString(R.string.message_status_sending)
            message.isDelivered() -> context.getString(R.string.message_status_delivered, timestamp)
            message.isFailedMessage() -> context.getString(R.string.message_status_failed)
            !message.isMe() && conversation?.recipients?.size ?: 0 > 1 -> "${contactCache[message.address]?.getDisplayName()} • $timestamp"
            else -> timestamp
        }

        view.status.setVisible(when {
            expanded[message.id] == true -> true
            message.isSending() -> true
            message.isFailedMessage() -> true
            expanded[message.id] == false -> false
            conversation?.recipients?.size ?: 0 > 1 && !message.isMe() && next?.compareSender(message) != true -> true
            message.isDelivered() && next?.isDelivered() != true && age <= TIMESTAMP_THRESHOLD -> true
            else -> false
        })
    }

    private fun getBubble(canGroupWithPrevious: Boolean, canGroupWithNext: Boolean, isMe: Boolean): Int {
        return when {
            !canGroupWithPrevious && canGroupWithNext -> if (isMe) R.drawable.message_out_first else R.drawable.message_in_first
            canGroupWithPrevious && canGroupWithNext -> if (isMe) R.drawable.message_out_middle else R.drawable.message_in_middle
            canGroupWithPrevious && !canGroupWithNext -> if (isMe) R.drawable.message_out_last else R.drawable.message_in_last
            else -> R.drawable.message_only
        }
    }

    private fun canGroup(message: Message, other: Message?): Boolean {
        if (other == null) return false
        val diff = TimeUnit.MILLISECONDS.toMinutes(Math.abs(message.date - other.date))
        return message.compareSender(other) && diff < TIMESTAMP_THRESHOLD
    }

    override fun getItemViewType(position: Int): Int {
        val message = getItem(position)!!
        return when (message.isMe()) {
            true -> VIEW_TYPE_MESSAGE_OUT
            false -> VIEW_TYPE_MESSAGE_IN
        }
    }

    /**
     * Cache the contacts in a map by the address, because the messages we're binding don't have
     * a reference to the contact.
     */
    private inner class ContactCache : HashMap<String, Recipient?>() {

        override fun get(key: String): Recipient? {
            if (super.get(key)?.isValid != true) {
                set(key, conversation?.recipients?.firstOrNull { PhoneNumberUtils.compare(it.address, key) })
            }

            return super.get(key)?.takeIf { it.isValid }
        }

    }
}