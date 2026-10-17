package io.github.beacon.app.domain.receive

import io.github.beacon.app.domain.model.IncomingMessage
import org.junit.Assert.*
import org.junit.Test

class MessageFilterTest {
    private val rows =
        listOf(
            IncomingMessage("a", "1", "Backup complete", "nightly job finished"),
            IncomingMessage("b", "2", "Alert", "disk nearly full"),
            IncomingMessage("a", "3", "Deploy", "service restarted"),
        )

    private fun filter(query: String, source: String) = rows.filter {
        (source == "全部来源" || it.server == source) &&
            (query.isBlank() || it.title.contains(query, true) || it.body.contains(query, true))
    }

    @Test
    fun searchMatchesTitleOrBodyCaseInsensitively() {
        assertEquals(listOf("1"), filter("BACKUP", "全部来源").map { it.id })
        assertEquals(listOf("2"), filter("FULL", "全部来源").map { it.id })
        assertEquals(0, filter("missing", "全部来源").size)
    }

    @Test
    fun serverIdentityScopeAndSearchComposeWithAnd() {
        assertEquals(listOf("3"), filter("service", "a").map { it.id })
        assertEquals(0, filter("disk", "a").size)
    }
}
