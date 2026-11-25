package app.ezcall.receiver

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiverConnectionActionTest {
    @Test fun recoveryShowsTheFirstActionTheUserCanTake() {
        assertEquals(R.string.r_allow_nearby, receiverConnectionAction(false, false, false, false))
        assertEquals(R.string.r_enable_bluetooth, receiverConnectionAction(true, false, false, true))
        assertEquals(R.string.r_reconnect_now, receiverConnectionAction(true, true, false, false))
    }

    @Test fun aRunningRelayCanReconnectWithoutIntervention() {
        assertEquals(0, receiverConnectionAction(true, true, false, true))
        assertEquals(0, receiverConnectionAction(true, true, true, true))
    }
}
