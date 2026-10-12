package io.github.beacon.app.ipc

import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import io.github.beacon.app.domain.model.DeleteUndo
import io.github.beacon.app.domain.model.IncomingMessage

/** Owns Parcel lifetimes and the shared bounded wire format. */
internal fun <T> IBinder.request(
    operation: Int,
    write: (Parcel) -> Unit = {},
    read: (Parcel) -> T,
): T {
    // Local Binder dispatch runs on the caller's thread. Never wait for storage/network on UI.
    check(Looper.myLooper() != Looper.getMainLooper()) {
        "Service requests must run on a background thread"
    }
    val data = Parcel.obtain()
    val reply = Parcel.obtain()
    try {
        data.writeInterfaceToken(RuntimeProtocol.DESCRIPTOR)
        write(data)
        check(transact(operation, data, reply, 0)) { "Runtime transaction rejected" }
        reply.readException()
        return read(reply).also { check(reply.dataAvail() == 0) { "Unexpected reply payload" } }
    } finally {
        data.recycle()
        reply.recycle()
    }
}

internal fun Parcel.readFlag(): Boolean {
    val value = readInt()
    check(value in 0..1)
    return value == 1
}

internal fun Parcel.readMessage() =
    IncomingMessage(
        requireNotNull(readString()),
        requireNotNull(readString()),
        requireNotNull(readString()),
        requireNotNull(readString()),
        readLong(),
        readLong(),
        readFlag(),
        requireNotNull(readString()),
        priority = readInt().also { check(it in 0..10) },
        reminderPending = readInt().also { check(it in 0..1) } == 1,
        bark = requireNotNull(readString()).also { require(it.length <= 100000) },
        applicationId =
            requireNotNull(readString()).also {
                require(it.isEmpty() || it.matches(Regex("[0-9a-f]{64}")))
            },
        group =
            requireNotNull(readString()).also {
                require(it.toByteArray(Charsets.UTF_8).size <= 128)
            },
        expiresAtMs = readLong().also { require(it >= 0) },
        applicationName =
            requireNotNull(readString()).also {
                require(it.toByteArray(Charsets.UTF_8).size <= 128)
            },
        actions =
            io.github.beacon.app.data.remote.MessageActionsCodec.decode(
                requireNotNull(readString())
            ),
    )

internal fun Parcel.readUndo(): DeleteUndo? {
    val token = requireNotNull(readString())
    val expires = readLong()
    val count = readInt()
    if (token.isEmpty()) {
        check(expires == 0L && count == 0)
        return null
    }
    check(token.length <= 64 && expires > 0 && count in 1..100)
    return DeleteUndo(token, expires, count)
}
