package io.github.beacon.app.domain.receive

class SyncCancelledException : RuntimeException()

enum class SyncStage {
    CAPABILITIES,
    MESSAGES,
    ACK,
}

class SyncAuthenticationException(
    val statusCode: Int = 401,
    val stage: SyncStage = SyncStage.CAPABILITIES,
) : RuntimeException()

class HttpRejectedException(
    val statusCode: Int = 400,
    val stage: SyncStage = SyncStage.CAPABILITIES,
) : java.io.IOException()

class HttpTransientException(val statusCode: Int, val stage: SyncStage) : java.io.IOException()

class SyncResponseException(val stage: SyncStage) : RuntimeException()
