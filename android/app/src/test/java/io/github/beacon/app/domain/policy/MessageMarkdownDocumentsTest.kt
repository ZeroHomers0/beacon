package io.github.beacon.app.domain.policy

import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class MessageMarkdownDocumentsTest {
    @Test fun reopeningReusesParsingButChangedContentDoesNot() {
        val cache = MessageMarkdownDocuments()
        val body = "# Report\n[Open](https://example.com/report)\nhttps://example.com/plain"
        val first = cache.get(body)
        assertSame(first, cache.get(body))
        assertEquals(listOf("https://example.com/report", "https://example.com/plain"), first.links)
        assertEquals(listOf("https://example.com/plain"), first.bareLinks)
        assertNotSame(first, cache.get(body + "\nChanged"))
        assertEquals(first.links, MessageMarkdown.links(first.node))
    }

    @Test fun entryLimitEvictsLeastRecentlyUsed() {
        val cache = MessageMarkdownDocuments(maxEntries = 2)
        val first = cache.get("first")
        val second = cache.get("second")
        assertSame(first, cache.get("first"))
        cache.get("third")
        assertSame(first, cache.get("first"))
        assertNotSame(second, cache.get("second"))
    }

    @Test fun characterBudgetEvictsAndOversizedDocumentsDoNotDisplaceTheCache() {
        val cache = MessageMarkdownDocuments(maxSourceChars = 10)
        val first = cache.get("123456")
        val second = cache.get("abcdef")
        val oversized = cache.get("x".repeat(11))
        assertNotSame(oversized, cache.get("x".repeat(11)))
        assertSame(second, cache.get("abcdef"))
        assertNotSame(first, cache.get("123456"))
    }

    @Test fun concurrentRequestsShareOneDocument() {
        val cache = MessageMarkdownDocuments()
        val workers = Executors.newFixedThreadPool(4)
        try {
            val requests = (1..16).map { workers.submit<MessageMarkdownDocuments.Document> { cache.get("**same**") } }
            val first = requests.first().get()
            requests.forEach { assertSame(first, it.get()) }
        } finally {
            workers.shutdownNow()
        }
    }

    @Test fun memoryPressureReleasesCachedDocuments() {
        val cache = MessageMarkdownDocuments()
        val first = cache.get("message")
        cache.clear()
        assertNotSame(first, cache.get("message"))
    }

    /**
     * Markwon's list-item visitor advances `OrderedList.startNumber` while rendering, exactly
     * as simulated here. Rendering a cached document twice must restart at the parsed number
     * instead of continuing past the previous visit.
     */
    @Test fun renderingTheSameDocumentTwiceRestartsOrderedListNumbering() {
        val cache = MessageMarkdownDocuments()
        val document = cache.get("1. 一\n2. 二\n3. 三\n\n4. 四\n5. 五")

        fun renderNumbers(): List<Int> = document.render { root ->
            val numbers = mutableListOf<Int>()
            var item = root.firstChild
            while (item != null) {
                val list = item
                if (list is org.commonmark.node.OrderedList) {
                    var child = list.firstChild
                    while (child != null) {
                        numbers += list.startNumber
                        list.startNumber = list.startNumber + 1
                        child = child.next
                    }
                }
                item = item.next
            }
            numbers
        }

        assertEquals(listOf(1, 2, 3, 4, 5), renderNumbers())
        assertEquals(listOf(1, 2, 3, 4, 5), renderNumbers())
        assertSame(document, cache.get("1. 一\n2. 二\n3. 三\n\n4. 四\n5. 五"))
    }

    @Test fun explicitStartNumberSurvivesRepeatedRendering() {
        val cache = MessageMarkdownDocuments()
        val document = cache.get("7. 七\n8. 八")

        fun firstNumber(): Int = document.render { root ->
            val list = root.firstChild as org.commonmark.node.OrderedList
            list.startNumber.also { list.startNumber = it + 1 }
        }

        assertEquals(7, firstNumber())
        assertEquals(7, firstNumber())
    }
}
