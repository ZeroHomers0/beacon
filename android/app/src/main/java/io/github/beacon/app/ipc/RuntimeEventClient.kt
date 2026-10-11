package io.github.beacon.app.ipc

import android.os.IBinder
import io.github.beacon.app.domain.model.*

internal class RuntimeEventClient(private val binder: IBinder) {
    fun read(): List<RuntimeEvent> =
        binder.request(RuntimeProtocol.GET_EVENTS) {
            val count = it.readInt().also { value -> require(value in 0..RuntimeEvent.MAX_EVENTS) }
            List(count) { _ ->
                RuntimeEvent(
                        it.readLong(),
                        requireNotNull(RuntimeEventKind.fromWireId(it.readInt())),
                        it.readInt().let { id ->
                            if (id == -1) null else requireNotNull(SyncOutcome.fromWireId(id))
                        },
                        RealtimeFailure.valueOf(requireNotNull(it.readString())),
                    )
                    .also { event -> event.validate() }
            }
        }

    fun clear() = binder.request(RuntimeProtocol.CLEAR_EVENTS) { Unit }
}
