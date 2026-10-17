package io.github.beacon.app

import android.os.Binder
import android.os.Parcel
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.ipc.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeEventWireTest {
    private fun client(
        count: Int,
        kind: Int = 2,
        failure: String = "NONE",
        trailing: Boolean = false,
    ) =
        RuntimeEventClient(
            object : Binder() {
                override fun onTransact(
                    code: Int,
                    data: Parcel,
                    reply: Parcel?,
                    flags: Int,
                ): Boolean {
                    assertEquals(RuntimeProtocol.GET_EVENTS, code)
                    data.enforceInterface(RuntimeProtocol.DESCRIPTOR)
                    requireNotNull(reply).apply {
                        writeNoException()
                        writeInt(count)
                        if (count in 0..100)
                            repeat(count) {
                                writeLong(1)
                                writeInt(kind)
                                writeInt(SyncOutcome.OK.wireId)
                                writeString(failure)
                            }
                        if (trailing) writeInt(1)
                    }
                    return true
                }
            }
        )

    @Test
    fun boundedReplyDecodesTypedEvents() {
        val events = client(100).read()
        assertEquals(100, events.size)
        assertTrue(
            events.all { it.kind == RuntimeEventKind.MANUAL_SYNC && it.outcome == SyncOutcome.OK }
        )
    }

    @Test
    fun unknownValuesAndOversizedReplyAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { client(101).read() }
        assertThrows(IllegalArgumentException::class.java) { client(-1).read() }
        assertThrows(IllegalArgumentException::class.java) { client(1, kind = 999).read() }
        assertThrows(IllegalArgumentException::class.java) {
            client(1, failure = "SECRET_SENTINEL").read()
        }
    }

    @Test
    fun unexpectedTrailingDataIsRejected() {
        assertThrows(IllegalStateException::class.java) { client(0, trailing = true).read() }
    }
}
