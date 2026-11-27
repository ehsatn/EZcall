package app.ezcall.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportTest {
    @Test
    fun reminderRequires48HoursAndDoesNotRepeat() {
        val firstOpened = 1_000L
        val due = firstOpened + 48 * 60 * 60 * 1000L
        assertFalse(Support.reminderDue(firstOpened - 1, firstOpened, false))
        assertFalse(Support.reminderDue(due - 1, firstOpened, false))
        assertTrue(Support.reminderDue(due, firstOpened, false))
        assertFalse(Support.reminderDue(due, firstOpened, true))
        assertFalse(Support.reminderDue(due, 0, false))
    }
}
