package app.ezcall.receiver

import app.ezcall.android.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverDataTest {
    @Test fun dialerValidatesInternationalInputAndPreservesLocalShortCodes() {
        assertEquals(null, receiverDialNumber("+1 202 55"))
        assertEquals(null, receiverDialNumber("123phone"))
        assertEquals(null, receiverDialNumber("##"))
        assertEquals("+989123456789", receiverDialNumber("+۹۸ (۹۱۲) ۳۴۵-۶۷۸۹"))
        assertEquals("123", receiverDialNumber("123"))
        assertEquals("*123#", receiverDialNumber("*123#"))
    }

    @Test fun nativePhoneOnlyAcceptsTheCurrentControllableRingingCall() {
        val state = ReceiverState
        try {
            state.paired = true
            state.connected = true
            state.inCall = ReceivedCall("new", "", 0, "ringing", false)
            assertFalse(state.canPresentIncoming("new"))
            state.canControl = true
            assertTrue(state.canPresentIncoming("new"))
            assertFalse(state.canPresentIncoming("old"))
            state.inCall = state.inCall?.copy(state = "active")
            assertFalse(state.canPresentIncoming("new"))
            state.inCall = null
            assertFalse(state.canPresentIncoming(null))
        } finally {
            state.paired = false
            state.connected = false
            state.canControl = false
            state.inCall = null
        }
    }

    @Test fun contactAndConversationUseTheSameRecipientAcrossPersianDigitsAndFormatting() {
        val number = "+۹۸ (۹۱۲) ۳۴۵-۶۷۸۹"
        val contact = ReceivedContact("1", "مریم", listOf(number))
        val data = ReceiverData(contacts = listOf(contact))
        assertEquals("+989123456789", conversationKey(number))
        assertEquals("مریم", data.name("+989123456789", "?"))
        assertEquals("Bank123", conversationKey("Bank123"))
        assertEquals(null, smsRecipient("Bank123"))
        assertEquals(null, smsRecipient("123"))
        assertEquals("?", data.name("", "?"))
    }

    @Test fun repeatedOutgoingTextsMatchOnlyNewOutgoingCopiesOnce() {
        val old = ReceivedSms("old", "09123456789", "Hello", 1, "out")
        val failed = SmsAttempt(old.copy(id = "failed"), "The sending phone could not send this SMS.")
        val first = SmsAttempt(old.copy(id = "a"), "SMS sent.")
        val second = SmsAttempt(old.copy(id = "b"), "Sending SMS…")
        val incoming = old.copy(id = "incoming", direction = "in")
        val saved = old.copy(id = "provider", address = "۰۹۱۲ ۳۴۵ ۶۷۸۹", date = 2)
        val attempts = reconcileAttempts(listOf(failed, first, second), listOf(old), listOf(saved, incoming, old))
        assertEquals(listOf(null, saved.id, null), attempts.map { it.syncedID })
        val state = ReceiverState
        try {
            state.data = ReceiverData(messages = listOf(saved, incoming, old))
            state.attempts = attempts
            assertEquals(setOf("provider", "incoming", "old", "failed", "b"), state.visibleMessages().map { it.id }.toSet())
        } finally {
            state.attempts = emptyList()
            state.data = ReceiverData()
        }
    }

    @Test fun liveSmsWinsOverAnOlderSnapshotAndKeepsTheNewestHundred() {
        val snapshot = (1..100).map { ReceivedSms(it.toString(), "1234567", "SMS", it.toLong(), "in") }
        val changed = snapshot.first().copy(date = 101)
        val newest = changed.copy(id = "101", date = 102)
        val merged = mergeSms(listOf(changed, newest), snapshot)
        assertEquals(listOf(newest, changed), merged.take(2))
        assertEquals(100, merged.size)
        assertFalse(merged.any { it.id == "2" })
    }

    @Test fun smsSizeUsesTheSendersUtf8ByteLimit() {
        assertTrue(Wire.validSmsBody("س".repeat(512)))
        assertFalse(Wire.validSmsBody("س".repeat(513)))
        assertTrue(Wire.validSmsBody("a".repeat(1024)))
        assertFalse(Wire.validSmsBody("   "))
    }

    @Test fun simulatedCallHasControlsOnlyWhileConnected() {
        val state = ReceiverState
        try {
            state.inCall = ReceivedCall("test", "", 0, "ringing", simulated = true)
            state.connected = true
            assertTrue(state.canControlCurrentCall)
            state.connected = false
            assertFalse(state.canControlCurrentCall)
            state.connected = true
            state.inCall = state.inCall?.copy(simulated = false)
            assertFalse(state.canControlCurrentCall)
        } finally {
            state.connected = false
            state.inCall = null
        }
    }
}
