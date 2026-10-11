package io.github.beacon.app.domain.receive

import org.junit.Assert.*
import org.junit.Test

class NotificationPipelineTest {
    private val now = 1_000_000L

    @Test
    fun singleMessageIncludesContentButSummaryDoesNotLeakOneMessagesBody() {
        val first = NotificationIntent(1, now, "标题", "正文")
        val single = NotificationPolicy.batch(listOf(first), now)
        assertEquals("标题", single.title)
        assertEquals("正文", single.body)
        val summary =
            NotificationPolicy.batch(listOf(first, NotificationIntent(2, now, "另一条", "内容")), now)
        assertEquals(2, summary.count)
        assertEquals("", summary.title)
        assertEquals("", summary.body)
    }

    private class Store : NotificationIntentStore {
        val pending =
            mutableListOf(NotificationIntent(1, 1_000_000), NotificationIntent(2, 1_000_000))
        var failCommit = false

        override fun pendingNotifications() = pending.toList()

        override fun notificationsPosted(intents: List<NotificationIntent>) {
            if (failCommit) throw java.io.IOException("storage")
            pending.removeAll(intents.toSet())
        }
    }

    @Test
    fun unknownOldOrFutureTimeIsSilent() {
        for (time in listOf(0L, -1L, now - 300_001, now + 1, Long.MAX_VALUE)) {
            assertFalse(NotificationPolicy.batch(listOf(NotificationIntent(1, time)), now).alert)
        }
        assertTrue(
            NotificationPolicy.batch(listOf(NotificationIntent(1, now - 300_000)), now).alert
        )
        assertTrue(NotificationPolicy.batch(listOf(NotificationIntent(1, now)), now).alert)
    }

    @Test
    fun serverClockIsBoundedAndFutureOrMissingTimeIsUnverified() {
        assertEquals(now - 100, NotificationPolicy.normalizedTimestamp(now + 900, now + 1000, now))
        assertEquals(0, NotificationPolicy.normalizedTimestamp(now, now - 300_001, now))
        assertEquals(0, NotificationPolicy.normalizedTimestamp(now + 1, now, now))
        assertEquals(0, NotificationPolicy.normalizedTimestamp(now, 0, now))
        assertEquals(0, NotificationPolicy.normalizedTimestamp(Long.MAX_VALUE, now, now))
    }

    @Test
    fun backlogIsOneSummaryWithStableFirstSequence() {
        val batch = NotificationPolicy.batch((1L..200).map { NotificationIntent(it, now) }, now)
        assertEquals(200, batch.count)
        assertEquals("beacon.batch.1", batch.tag)
        assertEquals(
            batch.tag,
            NotificationPolicy.batch(listOf(NotificationIntent(1, now)), now).tag,
        )
    }

    @Test
    fun deniedPermissionPreservesIntentAndDoesNotClaimPosted() {
        val store = Store()
        assertTrue(NotificationPipeline(store) { false }.flush(now).contains("保留"))
        assertEquals(2, store.pending.size)
    }

    @Test
    fun systemPostFailurePreservesIntent() {
        val store = Store()
        assertThrows(SecurityException::class.java) {
            NotificationPipeline(store) { throw SecurityException() }.flush(now)
        }
        assertEquals(2, store.pending.size)
    }

    @Test
    fun completedIntentsAreNotPostedAgain() {
        val store = Store()
        var posts = 0
        val pipeline =
            NotificationPipeline(store) {
                posts++
                true
            }
        pipeline.flush(now)
        pipeline.flush(now)
        assertEquals(1, posts)
        assertTrue(store.pending.isEmpty())
    }

    @Test
    fun commitFailureRetriesWithSameSystemIdentity() {
        val store = Store()
        val tags = mutableListOf<String>()
        store.failCommit = true
        val pipeline =
            NotificationPipeline(store) {
                tags.add(it.tag)
                true
            }
        assertThrows(java.io.IOException::class.java) { pipeline.flush(now) }
        store.failCommit = false
        pipeline.flush(now)
        assertEquals(listOf("beacon.batch.1", "beacon.batch.1"), tags)
    }

    @Test
    fun emptyNotificationQueueDoesNotAddNoiseToSyncFeedback() {
        val empty =
            object : NotificationIntentStore {
                override fun pendingNotifications() = emptyList<NotificationIntent>()

                override fun notificationsPosted(intents: List<NotificationIntent>) = Unit
            }
        assertEquals(
            "",
            NotificationPipeline(empty) { error("No notification should be posted") }.flush(now),
        )
    }

    @Test
    fun invalidOrderingCannotProduceAnAmbiguousIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            NotificationPolicy.batch(
                listOf(NotificationIntent(2, now), NotificationIntent(1, now)),
                now,
            )
        }
    }
}
