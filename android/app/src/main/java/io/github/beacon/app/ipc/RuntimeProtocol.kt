package io.github.beacon.app.ipc

import android.os.IBinder

object RuntimeProtocol {

    const val DESCRIPTOR = "io.github.beacon.runtime.probe.v1"
    const val RECONCILE_POLLING = IBinder.FIRST_CALL_TRANSACTION + 42
    const val SCHEDULE_TEST = IBinder.FIRST_CALL_TRANSACTION + 43
    const val PING = IBinder.FIRST_CALL_TRANSACTION
    const val CHECKPOINT_STORAGE = PING + 44
    const val MARK_ALL_READ = PING + 45
    const val RECENT = PING + 1
    const val INJECT_TEST = PING + 2
    const val DELETE = PING + 3
    const val CONFIGURE = PING + 4
    const val SYNC = PING + 5
    const val NOTIFY = PING + 6
    const val GET_CONTROL = PING + 7
    const val SET_POLLING = PING + 8
    const val AUTO_SYNC = PING + 9
    const val STOP_RUN = PING + 10
    // Transaction 12 was TEST_SCHEDULE: the runtime never handled it, and the settings test
    // now schedules through SCHEDULE_TEST. Retired, not reassigned, so that
    // installed clients and the service keep agreeing on every other number.
    // Previous QUERY (+12) and SAVE_SOURCE (+16) are retired; never reuse their numbers.
    const val CHECK_SERVER = PING + 19
    const val BIND_DEVICE = PING + 20
    const val COPY_PUSH_KEY = PING + 21
    const val PREVIEW_CLEANUP = PING + 22
    const val APPLY_CLEANUP = PING + 23
    const val CLEANUP_SETTINGS = PING + 24
    const val RECEIVE_PLAN = PING + 25
    const val QUIET_HOURS = PING + 26
    const val NOTIFICATION_SETTINGS = PING + 27
    const val REMIND_MESSAGE = PING + 28
    const val CLEAR_CONNECTION = PING + 29
    const val GET_EVENTS = PING + 30
    const val CLEAR_EVENTS = PING + 31
    const val RECONNECT_REALTIME = PING + 32
    const val ROTATE_CREDENTIALS = PING + 33
    const val REVOKE_DEVICE = PING + 34
    const val EXPORT_SETTINGS = PING + 35
    const val IMPORT_SETTINGS = PING + 36
    const val CLEAR_ALL_MESSAGES = PING + 37
    const val BARK_ENCRYPTION = PING + 41
    const val BARK_ENCRYPTION_STATUS = PING + 47
    /** One-shot status read: cached probe verdict plus live realtime liveness. */
    const val GET_STATUS = PING + 46
    const val TEST_DELIVERY = PING + 40
    const val HISTORY_ARCHIVE = PING + 39
    const val PENDING_REMINDERS = PING + 38
    const val QUERY = PING + 18
    const val DETAIL = PING + 13
    const val MUTATE = PING + 14
    const val UNDO_DELETE = PING + 15
    const val GET_CONNECTION = PING + 17
}
