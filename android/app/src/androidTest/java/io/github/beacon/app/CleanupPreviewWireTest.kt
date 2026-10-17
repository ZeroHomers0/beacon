package io.github.beacon.app

import android.os.Binder
import android.os.Parcel
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.ipc.*
import org.junit.Assert.*
import org.junit.Test

class CleanupPreviewWireTest {
    private fun client(extra: List<Long>) =
        RuntimeMessageClient(
            object : Binder() {
                override fun onTransact(
                    code: Int,
                    data: Parcel,
                    reply: Parcel?,
                    flags: Int,
                ): Boolean {
                    assertEquals(RuntimeProtocol.PREVIEW_CLEANUP, code)
                    data.enforceInterface(RuntimeProtocol.DESCRIPTOR)
                    requireNotNull(reply).apply {
                        writeNoException()
                        writeString("test-token")
                        listOf(4L, 1L, 1L, 8192L, 1L, 2L).forEach(::writeLong)
                        writeInt(1)
                        writeLong(4096L)
                        extra.forEach(::writeLong)
                    }
                    return true
                }
            }
        )

    @Test
    fun legacyReplyDoesNotInventCurrentSpaceMeasurement() {
        val result = client(emptyList()).previewCleanup(CleanupPolicy())
        assertEquals(4L, result.total)
        assertEquals(-1L, result.availableBytes)
        assertEquals(0L, result.sampledAtMs)
    }

    @Test
    fun newReplyReadsCountsAndRejectsOutOfRangeNotificationCount() {
        val result = client(listOf(1L, 7L, 512L, 99L, 100L)).previewCleanup(CleanupPolicy())
        assertEquals(1L, result.pendingNotifications)
        assertEquals(7L, result.tombstones)
        assertEquals(99L, result.availableBytes)
        assertThrows(IllegalStateException::class.java) {
            client(listOf(5L, 7L, 512L, 99L, 100L)).previewCleanup(CleanupPolicy())
        }
    }

    @Test
    fun truncatedSnapshotCannotAppearValid() {
        assertThrows(IllegalStateException::class.java) {
            client(listOf(1L)).previewCleanup(CleanupPolicy())
        }
    }
}
