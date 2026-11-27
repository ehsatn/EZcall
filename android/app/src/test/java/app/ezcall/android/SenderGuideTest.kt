package app.ezcall.android

import app.ezcall.android.MainActivity.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SenderGuideTest {
    @Test fun onlyUnpairedPhonesNeedQrEnrollment() {
        assertEquals(Step.LINK, Step.afterSharing(paired = false))
        assertEquals(Step.AUDIO, Step.afterSharing(paired = true))
    }

    @Test fun freshInstallAndUnpairedUpgradeHaveNoSavedSteps() {
        assertNull(Step.restore(null, null, false))
        assertNull(Step.restore(null, "done", false))
    }

    @Test fun upgradesResumeOldStepsAndShowAudioToPreviouslyPairedPhones() {
        assertEquals(Step.LINK, Step.restore(null, "LINK", false))
        assertEquals(Step.AUDIO, Step.restore(null, "WIRELESS", false))
        assertEquals(Step.AUDIO, Step.restore(null, "AUDIO", false))
        assertEquals(Step.AUDIO, Step.restore(null, "LINK", true))
        assertEquals(Step.AUDIO, Step.restore(null, "done", true))
        assertEquals(Step.AUDIO, Step.restore(null, null, true))
    }

    @Test fun savedProgressAndExplicitDismissalSurviveRestart() {
        for (step in Step.entries) {
            assertEquals(step, Step.restore(step.name, "done", true))
        }
        assertNull(Step.restore("done", "SHARING", false))
        assertNull(Step.restore("done", "WIRELESS", true))
    }
}
