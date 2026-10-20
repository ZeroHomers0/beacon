package io.github.beacon.app.domain.policy

import org.junit.Assert.*
import org.junit.Test

class MessageTagToneTest {
    @Test
    fun everyBucketStaysInRange() {
        val labels = listOf("", "a", "青龙", "beacon", "推送网关", "daily-news", "x".repeat(200))
        for (label in labels) {
            for (slot in 0..3) {
                val index = tagToneIndex(label, slot)
                assertTrue("$label/$slot -> $index", index in 0 until TAG_TONE_COUNT)
            }
        }
    }

    @Test
    fun theSameLabelAlwaysGetsTheSameBucket() {
        assertEquals(tagToneIndex("青龙", TAG_SLOT_APPLICATION), tagToneIndex("青龙", TAG_SLOT_APPLICATION))
        assertEquals(tagToneIndex("青龙", TAG_SLOT_GROUP), tagToneIndex("青龙", TAG_SLOT_GROUP))
    }

    @Test
    fun adjacentLettersDoNotShareABucket() {
        // FNV-1a's low bits only see the low bits of the input, so `% 8` over the whole hash would
        // put a, i, q and y — and each of their 8 neighbours — in the same bucket.
        val letters = ('a'..'z').map { tagToneIndex(it.toString(), TAG_SLOT_APPLICATION) }.toSet()
        assertTrue("only ${letters.size} buckets used by 26 letters", letters.size >= 6)
    }

    @Test
    fun everyBucketIsReachable() {
        val used =
            (0 until 500).map { tagToneIndex("application $it", TAG_SLOT_APPLICATION) }.toSet()
        assertEquals((0 until TAG_TONE_COUNT).toSet(), used)
    }

    @Test
    fun theTwoSlotsDoNotCollideOnTheSameLabel() {
        val shared =
            listOf("青龙", "beacon", "daily", "推送网关", "news")
                .count { tagToneIndex(it, TAG_SLOT_APPLICATION) == tagToneIndex(it, TAG_SLOT_GROUP) }
        assertTrue("$shared of 5 labels share a colour across slots", shared <= 2)
    }

    @Test
    fun typicalApplicationsAreToldApart() {
        val buckets =
            listOf("青龙", "青龙面板", "beacon", "Beacon 测试", "ci", "服务器", "NAS")
                .map { tagToneIndex(it, TAG_SLOT_APPLICATION) }
        assertTrue("$buckets collapses to ${buckets.toSet().size}", buckets.toSet().size >= 4)
    }
}
