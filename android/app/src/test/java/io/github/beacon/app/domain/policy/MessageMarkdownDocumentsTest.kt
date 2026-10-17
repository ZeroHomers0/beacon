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
}
