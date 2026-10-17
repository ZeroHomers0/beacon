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
        assertEquals("服务状态异常，请重试", OperationFailure.describe(IllegalStateException()))
        assertEquals("输入有误，请检查后重试", OperationFailure.describe(IllegalArgumentException()))
        assertEquals("权限不足，请检查应用权限", OperationFailure.describe(SecurityException()))
        assertEquals(
            "连接中断，请重试",
            OperationFailure.describe(java.net.UnknownHostException()),
        )
        assertEquals("操作超时，请重试", OperationFailure.describe(java.util.concurrent.TimeoutException()))
        assertEquals("操作已停止", OperationFailure.describe(SyncCancelledException()))
    }

    @Test
    fun crossProcessFailuresGetADistinctReasonWithoutImportingAndroidTypes() {
        val text = OperationFailure.describe(RuntimeException("binder died"), crossProcess = true)
        assertEquals("接收服务未响应，请重新打开应用", text)
        // The flag must not mask a more specific classification.
        assertEquals(
            "服务状态异常，请重试",
            OperationFailure.describe(IllegalStateException(), crossProcess = true),
        )
    }

    @Test
    fun unknownFailuresStillProduceActionableText() {
        val text = OperationFailure.describe(RuntimeException())
        assertTrue(text, text.contains("重试"))
    }
}
