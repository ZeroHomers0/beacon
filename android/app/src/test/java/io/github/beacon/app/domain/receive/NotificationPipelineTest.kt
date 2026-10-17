package io.github.beacon.app.domain.receive

import org.junit.Assert.*
import org.junit.Test

class NotificationPipelineTest {
    private val now = 1_000_000L

    @Test
    fun onlyMoreThanThreeMessagesProduceASummary() {
        for (count in 1..5) {
            val pending = (1..count).map { NotificationIntent(it.toLong(), now) }.toMutableList()
            val store = object : NotificationIntentStore {
                override fun pendingNotifications() = pending.toList()

                override fun notificationsPosted(intents: List<NotificationIntent>) {
                    pending.removeAll(intents.toSet())
                }
            }
            val posted = mutableListOf<NotificationBatch>()
            NotificationPipeline(store) { posted.add(it); true }.flush(now)
            assertEquals(if (count <= 3) count else 1, posted.size)
            assertEquals(if (count <= 3) 1 else count, posted.first().count)
            assertTrue(pending.isEmpty())
        }
    }

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

    @Test
    fun batchCarriesEveryMessageKeySoACardCanActOnThem() {
        val keys =
            (1L..3L).map { io.github.beacon.app.domain.model.MessageKey("srv", "id$it") }
        val batch =
            NotificationPolicy.batch(
                listOf(
                    NotificationIntent(1, now, key = keys[0]),
                    NotificationIntent(2, now, key = keys[1]),
                    NotificationIntent(3, now, key = keys[2]),
                ),
                now,
            )
        // The summary carries all three; only a single-message batch also names one message.
        assertEquals(keys, batch.keys)
        assertNull(batch.key)
        assertEquals(keys.first(), NotificationPolicy.batch(listOf(NotificationIntent(1, now, key = keys[0])), now).key)
        val withoutKey = NotificationPolicy.batch(listOf(NotificationIntent(1, now)), now)
        assertNull(withoutKey.key)
        assertTrue(withoutKey.keys.isEmpty())
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
        val result = NotificationPipeline(store) { false }.flush(now)
        // The message must stay pending; the text only has to promise a retry.
        assertTrue(result, result.contains("稍后重试"))
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
        assertEquals(2, posts)
        pipeline.flush(now)
        assertEquals(2, posts)
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
        // The retried intent reuses its original tag instead of minting a new notification.
        assertEquals(listOf("beacon.batch.1", "beacon.batch.1", "beacon.batch.2"), tags)
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
