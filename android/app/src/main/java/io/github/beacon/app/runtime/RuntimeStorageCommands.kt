package io.github.beacon.app.runtime

import android.os.Parcel
import io.github.beacon.app.domain.model.MessageMutationResult

/** IPC payload handling; invoked only after the service validates caller UID and descriptor. */
internal fun RuntimeProbeService.handleStorageCommands(code: Int, data: Parcel, reply: Parcel?): Boolean {
    if (code == io.github.beacon.app.ipc.RuntimeProtocol.PREVIEW_CLEANUP) {
        val policy =
            io.github.beacon.app.domain.model.CleanupPolicy(
                data.readInt(),
                data.readInt(),
                data.readInt().also { require(it in 0..1) } == 1,
            )
        policy.validate()
        if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
        val preview =
            worker
                .submit<io.github.beacon.app.domain.model.CleanupPreview> {
                    store.previewCleanup(policy)
                }
                .get(45, java.util.concurrent.TimeUnit.SECONDS)
        requireNotNull(reply).apply {
            writeNoException()
            writeString(preview.token)
            writeLong(preview.total)
            writeLong(preview.unread)
            writeLong(preview.pending)
            writeLong(preview.databaseBytes)
            writeLong(preview.eligible)
            writeLong(preview.protected)
            writeInt(preview.planned)
            writeLong(preview.reusableBytes)
            writeLong(preview.pendingNotifications)
            writeLong(preview.tombstones)
            writeLong(preview.walBytes)
            writeLong(preview.availableBytes)
            writeLong(preview.sampledAtMs)
        }
        return true
    }
    if (code == io.github.beacon.app.ipc.RuntimeProtocol.MARK_ALL_READ) {
        if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
        val changed = worker.submit<Long> { store.markAllRead() }
            .get(45, java.util.concurrent.TimeUnit.SECONDS)
        requireNotNull(reply).apply {
            writeNoException()
            writeLong(changed)
        }
        return true
    }
    if (code == io.github.beacon.app.ipc.RuntimeProtocol.CHECKPOINT_STORAGE) {
        if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
        val complete = worker.submit<Boolean> { store.checkpointWal() }
            .get(45, java.util.concurrent.TimeUnit.SECONDS)
        requireNotNull(reply).apply {
            writeNoException()
            writeInt(if (complete) 1 else 0)
        }
        return true
    }
    if (code == io.github.beacon.app.ipc.RuntimeProtocol.APPLY_CLEANUP) {
        val token =
            requireNotNull(data.readString()).also { require(it.length in 1..64) }
        if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
        val result =
            worker
                .submit<MessageMutationResult> { store.applyCleanup(token) }
                .get(45, java.util.concurrent.TimeUnit.SECONDS)
        requireNotNull(reply).apply {
            writeNoException()
            writeInt(result.changed)
            writeString(result.undo?.token ?: "")
            writeLong(result.undo?.expiresAtMs ?: 0)
            writeInt(result.undo?.count ?: 0)
        }
        return true
    }

    return false
}
