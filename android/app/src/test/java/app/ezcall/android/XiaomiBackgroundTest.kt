package app.ezcall.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiBackgroundTest {
    @Test fun matchesXiaomiRedmiAndPocoOnly() {
        assertTrue(XiaomiBackground.applies("Xiaomi", "Xiaomi"))
        assertTrue(XiaomiBackground.applies("Xiaomi", "Redmi"))
        assertTrue(XiaomiBackground.applies("unknown", "POCO"))
        assertFalse(XiaomiBackground.applies("samsung", "samsung"))
        assertFalse(XiaomiBackground.applies("Google", "google"))
    }
}
