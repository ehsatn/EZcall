package app.ezcall.android

import android.Manifest.permission
import app.ezcall.android.MainActivity.Step
import app.ezcall.android.RealCallAudioTest.Mode
import app.ezcall.android.RealCallAudioTest.Result
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

    @Test fun setupUsesListeningResultsFromBothModesToRecommendCallAudio() {
        assertNull(RealCallAudioTest.recommendation(Result.BOTH, null))
        assertEquals(Mode.NORMAL, RealCallAudioTest.recommendation(Result.UNAVAILABLE, Result.BOTH))
        assertEquals(Mode.REDIRECT, RealCallAudioTest.recommendation(Result.BOTH, Result.BOTH))
        assertEquals(Mode.REDIRECT, RealCallAudioTest.recommendation(Result.BOTH, Result.THIRD_PHONE_ONLY))
        assertNull(RealCallAudioTest.recommendation(Result.RECEIVER_ONLY, Result.THIRD_PHONE_ONLY))
    }

    @Test fun sharingBatchIncludesOnlyMissingSelectedAndSetupPermissions() {
        val selected = setOf(Feature.CALLS, Feature.CONTROL, Feature.CONTACTS)
        val alreadyGranted = setOf(permission.READ_PHONE_STATE, permission.BLUETOOTH_CONNECT)
        assertEquals(setOf(permission.ANSWER_PHONE_CALLS, permission.CALL_PHONE, permission.READ_CONTACTS,
            permission.BLUETOOTH_ADVERTISE, permission.POST_NOTIFICATIONS),
            SharingPermissions.missing(selected, includeNearby = true, includeNotifications = true,
                alreadyGranted::contains).toSet())
        assertEquals(setOf(permission.ANSWER_PHONE_CALLS, permission.CALL_PHONE, permission.READ_CONTACTS),
            SharingPermissions.missing(selected, includeNearby = false, includeNotifications = false,
                alreadyGranted::contains).toSet())
    }

    @Test fun deniedCallAlertsKeepDependentSharingOffEvenIfTheirPermissionsWereGranted() {
        val selected = setOf(Feature.CALLS, Feature.CONTROL, Feature.CONTACTS)
        val granted = setOf(permission.CALL_PHONE, permission.READ_CONTACTS)
        assertEquals(setOf(Feature.CONTACTS), SharingPermissions.granted(selected, false, granted::contains))
        assertEquals(setOf(Feature.CONTROL, Feature.CONTACTS), SharingPermissions.granted(selected, true, granted::contains))
    }
}
