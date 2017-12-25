package com.moez.QKSMS.data.model

import io.realm.RealmList
import io.realm.RealmObject
import io.realm.annotations.PrimaryKey

open class Conversation : RealmObject() {

    @PrimaryKey var id: Long = 0
    var recipients: RealmList<Recipient> = RealmList()
    var archived: Boolean = false

    fun getTitle(): String {
        var title = ""
        recipients.forEachIndexed { index, recipient ->
            val name = recipient.contact?.name
            title += if (name.isNullOrBlank()) recipient.address else name
            if (index < recipients.size - 1) {
                title += ", "
            }
        }

        return title
    }
}
