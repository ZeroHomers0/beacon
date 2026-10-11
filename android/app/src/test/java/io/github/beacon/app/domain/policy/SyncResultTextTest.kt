package io.github.beacon.app.domain.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncResultTextTest {
    @Test
    fun oldSuccessfulResultHidesEmptyQueueAndRetryHint() {
        assertEquals(
            "本轮同步：新增 2 条",
            SyncResultText.normalize("本轮同步：新增 2 条；待 ACK 0 条。有积压时请再次同步\n没有待提醒消息"),
        )
    }

    @Test
    fun oldPendingQueueRetainsItsCountAndExplainsAutomaticRetry() {
        assertEquals(
            "本轮同步：新增 2 条；待回传接收结果 3 条（自动重试）",
            SyncResultText.normalize("本轮同步：新增 2 条；待 ACK 3 条。有积压时请再次同步"),
        )
    }

    @Test
    fun realNotificationFailureRemainsVisible() {
        assertEquals(
            "本轮同步：新增 2 条\n通知被系统权限或渠道阻止；2 条提醒任务保留",
            SyncResultText.normalize("本轮同步：新增 2 条；待 ACK 0 条。有积压时请再次同步\n通知被系统权限或渠道阻止；2 条提醒任务保留"),
        )
    }

    @Test
    fun currentResultIsUnchanged() {
        val text = "本轮同步：新增 5 条；待回传接收结果 1 条（自动重试）"
        assertEquals(text, SyncResultText.normalize(text))
    }
}
