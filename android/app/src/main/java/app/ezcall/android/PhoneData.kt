package app.ezcall.android

import android.Manifest.permission
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Telephony.Sms
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest

internal enum class Feature(val title: Int, val permissions: Array<String>) {
    CALLS(R.string.s_call_alerts, arrayOf(permission.READ_PHONE_STATE)),
    CALLER(R.string.s_caller_number, arrayOf(permission.READ_CALL_LOG)),
    CONTROL(R.string.s_call_control, arrayOf(permission.ANSWER_PHONE_CALLS, permission.CALL_PHONE)),
    SMS(R.string.s_text_messages, arrayOf(permission.READ_SMS, permission.RECEIVE_SMS)),
    SEND_SMS(R.string.s_send_from_receiving, arrayOf(permission.SEND_SMS)),
    CONTACTS(R.string.ez_contacts, arrayOf(permission.READ_CONTACTS));

    fun optedIn(context: Context): Boolean =
        context.getSharedPreferences("sharing", Context.MODE_PRIVATE).getBoolean(name, false)

    fun allow(context: Context, enabled: Boolean) {
        context.getSharedPreferences("sharing", Context.MODE_PRIVATE).edit().putBoolean(name, enabled).apply()
    }

    fun active(context: Context): Boolean = optedIn(context) && permissions.any { context.hasPermission(it) }
}

internal fun Context.hasPermission(name: String): Boolean = checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED

internal class PhoneData(private val context: Context) {
    fun snapshot(id: String): List<JSONObject> {
        val begin = packet("sync_begin", id)
        val country = context.getSystemService(TelephonyManager::class.java)?.simCountryIso
        if (country?.length == 2) begin.put("country", country)
        val result = mutableListOf(begin)
        if (Feature.SMS.active(context) && context.hasPermission(permission.READ_SMS)) {
            batch("sms_batch", id, readSms(), result)
        }
        if (Feature.CONTACTS.active(context)) {
            batch("contacts_batch", id, readContacts(), result)
        }
        result.add(packet("sync_end", id))
        return result
    }

    private fun readSms(): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        context.contentResolver.query(
            Sms.CONTENT_URI,
            arrayOf(Sms.ADDRESS, Sms.BODY, Sms.DATE, Sms.DATE_SENT, Sms.TYPE),
            "${Sms.TYPE} IN (?, ?)",
            arrayOf(Sms.MESSAGE_TYPE_INBOX.toString(), Sms.MESSAGE_TYPE_SENT.toString()),
            "${Sms.DATE} DESC"
        )?.use { cursor ->
            while (result.size < 100 && cursor.moveToNext()) {
                val address = cursor.getString(0).orEmpty()
                val body = cursor.getString(1).orEmpty()
                val date = cursor.getLong(2)
                val direction = if (cursor.getInt(4) == Sms.MESSAGE_TYPE_INBOX) "in" else "out"
                val originalTime = if (direction == "in") cursor.getLong(3).takeIf { it > 0 } ?: date else date
                result.add(message(address, body, date, direction, originalTime))
            }
        } ?: error("SMS provider unavailable")
        return result
    }

    private fun readContacts(): List<JSONObject> {
        data class Entry(val name: String, val numbers: MutableSet<String> = linkedSetOf())
        val contacts = linkedMapOf<String, Entry>()
        context.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER),
            null, null, "${Phone.CONTACT_ID} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                if (id !in contacts && contacts.size == 5_000) break
                val number = cursor.getString(2).orEmpty().limitText(80).trim()
                if (number.isEmpty()) continue
                val entry = contacts.getOrPut(id) { Entry(cursor.getString(1).orEmpty().limitText(160)) }
                if (entry.numbers.size < 24) entry.numbers.add(number)
            }
        } ?: error("Contacts provider unavailable")
        return contacts.map { (id, contact) ->
            JSONObject().put("id", id).put("name", contact.name).put("numbers", JSONArray(contact.numbers))
        }
    }

    private fun batch(type: String, id: String, items: List<JSONObject>, result: MutableList<JSONObject>) {
        var values = JSONArray()
        var size = 0
        for (item in items) {
            val bytes = item.toString().toByteArray().size + 1
            if (values.length() > 0 && size + bytes > 12_000) {
                result.add(packet(type, id).put("items", values))
                values = JSONArray()
                size = 0
            }
            values.put(item)
            size += bytes
        }
        if (values.length() > 0) result.add(packet(type, id).put("items", values))
    }

    companion object {
        fun packet(type: String, id: String): JSONObject = JSONObject().put("type", type).put("id", id)

        fun message(address: String, body: String, date: Long, direction: String, originalTime: Long): JSONObject {
            val digest = MessageDigest.getInstance("SHA-256").run {
                update(ByteBuffer.allocate(8).putLong(originalTime).array())
                update(direction.toByteArray())
                update(address.toByteArray())
                update(0.toByte())
                digest(body.toByteArray())
            }
            val id = digest.joinToString("") { "%02x".format(it) }
            return JSONObject().put("id", id).put("address", address.limitText(80)).put("body", body.limitText(4_096))
                .put("date", date).put("direction", direction)
        }
    }
}

private fun String.limitText(limit: Int): String {
    if (length <= limit) return this
    val end = if (Character.isHighSurrogate(this[limit - 1])) limit - 1 else limit
    return substring(0, end)
}
