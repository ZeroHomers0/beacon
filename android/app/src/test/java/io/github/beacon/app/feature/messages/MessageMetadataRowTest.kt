package io.github.beacon.app.feature.messages

import io.github.beacon.app.domain.policy.metadataLabelWidths

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageMetadataRowTest {
    @Test
    fun shortLabelGivesUnusedSpaceToLongLabel() {
        assertEquals(listOf(20, 180), metadataLabelWidths(listOf(20, 300), 200))
        assertEquals(listOf(180, 20), metadataLabelWidths(listOf(300, 20), 200))
    }

    @Test
    fun longLabelsShareSpaceAndShortLabelsKeepNaturalWidth() {
        assertEquals(listOf(100, 100), metadataLabelWidths(listOf(300, 400), 200))
        assertEquals(listOf(20, 30), metadataLabelWidths(listOf(20, 30), 200))
    }

    @Test
    fun handlesMissingLabelsAndNoAvailableSpace() {
        assertEquals(emptyList<Int>(), metadataLabelWidths(emptyList(), 200))
        assertEquals(listOf(0, 0), metadataLabelWidths(listOf(20, 30), -1))
        assertEquals(listOf(100), metadataLabelWidths(listOf(200), 100))
    }
}
