package io.github.beacon.app.domain.model

/**
 * How the last operation should be presented. Local classification only: it never crosses Binder
 * and is never persisted, so unlike [SyncOutcome] it needs no stable wire id.
 */
enum class OperationKind {
    /** Binding the runtime process; replaces the status with neutral progress copy. */
    CONNECTING,

    /** Progress or diagnostics the user does not need in the snackbar, such as a page read. */
    DIAGNOSTIC,

    /** The runtime link is gone and needs an explicit reconnect from the user. */
    CONNECTION_LOST,

    /** Work that is still running and must not raise a snackbar. */
    PENDING,

    /** A finished operation whose [OperationStatus.text] is the user-facing result. */
    RESULT,
}

/**
 * Runtime status text paired with the classification the UI needs. [text] stays free-form because
 * the runtime process still answers with prose over protocol 1.
 */
data class OperationStatus(
    val kind: OperationKind = OperationKind.CONNECTION_LOST,
    val text: String = "",
) {
    val firstLine: String
        get() = text.lineSequence().first()

    /** Progress and diagnostics stay out of the snackbar; results and failures show up there. */
    val showsSnackbar: Boolean
        get() =
            when (kind) {
                OperationKind.CONNECTING,
                OperationKind.DIAGNOSTIC,
                OperationKind.PENDING -> false

                else -> true
            }

    companion object {
        fun connecting(text: String) = OperationStatus(OperationKind.CONNECTING, text)

        fun diagnostic(text: String) = OperationStatus(OperationKind.DIAGNOSTIC, text)

        fun connectionLost(text: String) = OperationStatus(OperationKind.CONNECTION_LOST, text)

        fun pending(text: String) = OperationStatus(OperationKind.PENDING, text)

        fun result(text: String) = OperationStatus(OperationKind.RESULT, text)
    }
}
