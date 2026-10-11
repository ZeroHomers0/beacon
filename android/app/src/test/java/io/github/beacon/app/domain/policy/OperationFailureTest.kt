package io.github.beacon.app.domain.policy

import io.github.beacon.app.domain.receive.SyncCancelledException
import org.junit.Assert.*
import org.junit.Test

class OperationFailureTest {
    @Test
    fun textNeverExposesTheRuntimeClassNameOrMessage() {
        val secret = "https://secret.example/token=" + "a".repeat(64)
        val errors =
            listOf(
                IllegalStateException(secret),
                IllegalArgumentException(secret),
                SecurityException(secret),
                java.io.IOException(secret),
                UnsupportedOperationException(secret),
                RuntimeException(secret),
            )
        errors.forEach { error ->
            val text = OperationFailure.describe(error)
            assertFalse(text, text.contains(secret))
            assertFalse(text, text.contains("secret.example"))
            assertFalse(text, text.contains("a".repeat(64)))
            assertFalse(text, text.contains(error.javaClass.simpleName))
            assertTrue(text, text.isNotBlank())
        }
    }

    @Test
    fun eachFailureFamilyMapsToItsOwnRemediation() {
        assertEquals("运行状态或协议数据不符合预期", OperationFailure.describe(IllegalStateException()))
        assertEquals("参数不符合要求", OperationFailure.describe(IllegalArgumentException()))
        assertEquals("系统或跨进程权限不足", OperationFailure.describe(SecurityException()))
        assertEquals(
            "网络或数据传输未完成，请重试",
            OperationFailure.describe(java.net.UnknownHostException()),
        )
        assertEquals("操作超时，请重试", OperationFailure.describe(java.util.concurrent.TimeoutException()))
        assertEquals("操作已停止", OperationFailure.describe(SyncCancelledException()))
    }

    @Test
    fun crossProcessFailuresGetADistinctReasonWithoutImportingAndroidTypes() {
        val text = OperationFailure.describe(RuntimeException("binder died"), crossProcess = true)
        assertEquals("调用未收到响应，请重新连接", text)
        // The flag must not mask a more specific classification.
        assertEquals(
            "运行状态或协议数据不符合预期",
            OperationFailure.describe(IllegalStateException(), crossProcess = true),
        )
    }

    @Test
    fun unknownFailuresStillProduceActionableText() {
        val text = OperationFailure.describe(RuntimeException())
        assertTrue(text, text.contains("重试"))
    }
}
