package io.github.beacon.app.feature.settings

import io.github.beacon.app.domain.model.AppRelease
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UpdateCheckStateTest {
    private fun release(version: String = "1.0.4") =
        AppRelease(version, "notes", "https://example.com/app.apk", 100)

    @Test
    fun acknowledgedVersionStaysQuietUntilAnotherRelease() = runBlocking {
        val state = UpdateCheckState()
        state.check(false) { Result.success(release()) }
        assertTrue(state.hasUnreadUpdate)
        state.openDialog()
        assertFalse(state.hasUnreadUpdate)
        state.closeDialog()
        state.check(false) { Result.success(release()) }
        assertFalse(state.hasUnreadUpdate)
        state.check(false) { Result.success(release("1.0.5")) }
        assertTrue(state.hasUnreadUpdate)
    }

    @Test
    fun manualFailureAcknowledgesExistingBadge() = runBlocking {
        val state = UpdateCheckState()
        state.check(false) { Result.success(release()) }
        assertEquals(UpdateCheckReport.FAILED, state.check(true) { Result.failure(Exception()) })
        assertFalse(state.hasUnreadUpdate)
        assertNotNull(state.release)
    }

    @Test
    fun manualDiscoveryOpensDialogWithoutUnreadBadge() = runBlocking {
        val state = UpdateCheckState()
        assertEquals(UpdateCheckReport.AVAILABLE, state.check(true) { Result.success(release()) })
        assertTrue(state.dialogOpen)
        assertFalse(state.hasUnreadUpdate)
    }

    @Test
    fun tappingDuringBackgroundCheckAcknowledgesItsResult() = runBlocking {
        val state = UpdateCheckState()
        state.check(false) {
            state.acknowledge()
            Result.success(release())
        }
        assertFalse(state.hasUnreadUpdate)
    }

    @Test
    fun cancelledCheckCanRetry() = runBlocking {
        val state = UpdateCheckState()
        try {
            state.check(true) { throw kotlinx.coroutines.CancellationException() }
        } catch (_: kotlinx.coroutines.CancellationException) {}
        assertFalse(state.checking)
        state.check(false) { Result.success(release()) }
        assertTrue(state.hasUnreadUpdate)
    }
    @Test
    fun updateActionRejectsDuplicateUntilFinished() {
        val state = UpdateCheckState()
        assertTrue(state.beginDownload())
        assertFalse(state.beginDownload())
        assertTrue(state.downloading)
        state.endDownload()
        assertTrue(state.beginDownload())
        state.endDownload()
        assertFalse(state.downloading)
    }

}
