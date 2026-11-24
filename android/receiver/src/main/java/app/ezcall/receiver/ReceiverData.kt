package app.ezcall.receiver

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject

internal data class ReceivedCall(val id: String, val address: String, val date: Long, val state: String, val simulated: Boolean)
internal data class ReceivedSms(val id: String, val address: String, val body: String, val date: Long, val direction: String)
internal data class ReceivedContact(val id: String, val name: String, val numbers: List<String>)

internal fun normalizedNumber(value: String) = value.trim().map {
    it.digitToIntOrNull()?.let { digit -> '0' + digit } ?: it
}.joinToString("")

internal fun smsRecipient(address: String): String? = normalizedNumber(address)
    .filterNot { Character.isSpaceChar(it) || it in "-()." }.takeIf { smsNumber.matches(it) }

private val smsNumber = Regex("\\+?[0-9]{7,15}")
internal fun conversationKey(address: String, country: String = ""): String {
    val number = smsRecipient(address) ?: return address.trim()
    if (number.startsWith('+') || country.isEmpty()) return number
    return PhoneNumberUtils.formatNumberToE164(number, country) ?: number
}

internal data class SmsAttempt(val sms: ReceivedSms, val detail: String, val syncedID: String? = null)

internal fun reconcileAttempts(attempts: List<SmsAttempt>, before: List<ReceivedSms>, after: List<ReceivedSms>,
                              country: String = ""): List<SmsAttempt> {
    if (attempts.isEmpty()) return attempts
    val known = before.mapTo(mutableSetOf()) { it.id }
    val added = after.filter { it.direction == "out" && it.id !in known }.sortedBy { it.date }.toMutableList()
    return attempts.map { attempt ->
        val awaitingCopy = attempt.detail == "Sending SMS…" || attempt.detail == "SMS sent." ||
            attempt.detail == "No SMS confirmation received. Check the sending phone before retrying."
        val match = if (attempt.syncedID == null && awaitingCopy) added.firstOrNull {
            conversationKey(it.address, country) == conversationKey(attempt.sms.address, country) && it.body == attempt.sms.body
        } else null
        if (match == null) attempt else {
            added.remove(match)
            attempt.copy(syncedID = match.id)
        }
    }
}

internal fun mergeSms(latest: Collection<ReceivedSms>, earlier: Collection<ReceivedSms>) =
    (latest + earlier).distinctBy { it.id }.sortedByDescending { it.date }.take(100)

internal data class ReceiverData(
    val calls: List<ReceivedCall> = emptyList(),
    val messages: List<ReceivedSms> = emptyList(),
    val contacts: List<ReceivedContact> = emptyList(),
    val country: String = ""
) {
    private val contactNames by lazy {
        buildMap {
            for (contact in contacts) if (contact.name.isNotBlank()) {
                for (number in contact.numbers) putIfAbsent(conversationKey(number, country), contact.name)
            }
        }
    }

    fun name(number: String, unknown: String): String {
        if (number.isBlank()) return unknown
        return contactNames[conversationKey(number, country)] ?: number
    }
}

internal fun JSONObject.call(): ReceivedCall? {
    val id = optString("id").takeIf { it.length in 1..128 } ?: return null
    val state = optString("state").takeIf { it in setOf("ringing", "active", "ended") } ?: return null
    return ReceivedCall(id, optString("address").take(128), optLong("date"), state, optBoolean("simulated"))
}

internal fun JSONObject.sms(): ReceivedSms? {
    val id = optString("id").takeIf { it.length in 1..128 } ?: return null
    val direction = optString("direction").takeIf { it == "in" || it == "out" } ?: return null
    return ReceivedSms(id, optString("address").take(128), optString("body").take(16_384), optLong("date"), direction)
}

internal fun JSONObject.contact(): ReceivedContact? {
    val id = optString("id").takeIf { it.length in 1..128 } ?: return null
    val values = optJSONArray("numbers") ?: return null
    val numbers = (0 until minOf(values.length(), 32)).mapNotNull {
        (values.opt(it) as? String)?.take(128)?.takeIf(String::isNotBlank)
    }
    return ReceivedContact(id, optString("name").take(256), numbers)
}

internal fun <T> JSONArray.read(limit: Int, parse: (JSONObject) -> T?): List<T> =
    (0 until minOf(limit, length())).mapNotNull { optJSONObject(it)?.let(parse) }

internal class ReceiverVault(context: Context) {
    private val file = AtomicFile(context.noBackupFilesDir.resolve("relay.json"))

    fun load(): ReceiverData = runCatching {
        val value = JSONObject(file.readFully().toString(Charsets.UTF_8))
        ReceiverData(
            value.getJSONArray("calls").read(100, JSONObject::call),
            value.getJSONArray("messages").read(100, JSONObject::sms),
            value.getJSONArray("contacts").read(5_000, JSONObject::contact), value.optString("country")
        )
    }.getOrDefault(ReceiverData())

    fun save(data: ReceiverData) {
        val value = JSONObject()
            .put("country", data.country)
            .put("calls", JSONArray(data.calls.map {
                JSONObject().put("id", it.id).put("address", it.address)
                    .put("date", it.date).put("state", "ended")
            }))
            .put("messages", JSONArray(data.messages.map {
                JSONObject().put("id", it.id).put("address", it.address)
                    .put("date", it.date).put("direction", it.direction).put("body", it.body)
            }))
            .put("contacts", JSONArray(data.contacts.map {
                JSONObject().put("id", it.id).put("name", it.name).put("numbers", JSONArray(it.numbers))
            }))
        val stream = file.startWrite()
        try {
            stream.write(value.toString().toByteArray())
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    fun clear() = file.delete()
}
