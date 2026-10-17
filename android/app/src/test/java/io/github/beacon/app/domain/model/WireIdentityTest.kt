package io.github.beacon.app.domain.model

import io.github.beacon.app.ipc.RuntimeProtocol
import org.junit.Assert.*
import org.junit.Test

/** Protects installed preferences and IPC from enum/package reorganization. */
class WireIdentityTest {
    @Test
    fun filtersActionsAndOutcomesRetainTheirWireIds() {
        listOf(MessageReadFilter.ALL, MessageReadFilter.UNREAD, MessageReadFilter.READ)
            .forEachIndexed { id, value ->
                assertEquals(value, MessageReadFilter.fromWireId(id))
                assertEquals(id, value.wireId)
            }
        listOf(MessageAction.MARK_READ, MessageAction.MARK_UNREAD, MessageAction.DELETE)
            .forEachIndexed { id, value ->
                assertEquals(value, MessageAction.fromWireId(id))
                assertEquals(id, value.wireId)
            }
        listOf(
                SyncOutcome.OK,
                SyncOutcome.STOPPED,
                SyncOutcome.TRANSIENT,
                SyncOutcome.AUTH,
                SyncOutcome.TLS,
                SyncOutcome.PROTOCOL,
                SyncOutcome.STORAGE,
                SyncOutcome.CONFIG,
                SyncOutcome.BUSY,
            )
            .forEachIndexed { id, value ->
                assertEquals(value, SyncOutcome.fromWireId(id))
                assertEquals(id, value.wireId)
            }
        assertNull(MessageAction.fromWireId(3))
        assertNull(MessageReadFilter.fromWireId(3))
        assertNull(SyncOutcome.fromWireId(9))
    }

    @Test
    fun binderDescriptorAndOperationNumbersStayStable() {
        assertEquals("io.github.beacon.runtime.probe.v1", RuntimeProtocol.DESCRIPTOR)
        // 12 is retired (TEST_SCHEDULE); the gap is deliberate, so a future operation may
        // only take it back by an explicit decision, never by inserting a declaration.
        assertEquals(
            (1..11).toList() + listOf(19, 14, 15, 16, 18),
            listOf(
                RuntimeProtocol.PING,
                RuntimeProtocol.RECENT,
                RuntimeProtocol.INJECT_TEST,
                RuntimeProtocol.DELETE,
                RuntimeProtocol.CONFIGURE,
                RuntimeProtocol.SYNC,
                RuntimeProtocol.NOTIFY,
                RuntimeProtocol.GET_CONTROL,
                RuntimeProtocol.SET_POLLING,
                RuntimeProtocol.AUTO_SYNC,
                RuntimeProtocol.STOP_RUN,
                RuntimeProtocol.QUERY,
                RuntimeProtocol.DETAIL,
                RuntimeProtocol.MUTATE,
                RuntimeProtocol.UNDO_DELETE,
                RuntimeProtocol.GET_CONNECTION,
            ),
        )
    }
}
