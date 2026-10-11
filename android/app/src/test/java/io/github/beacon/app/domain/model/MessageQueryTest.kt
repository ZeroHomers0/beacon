package io.github.beacon.app.domain.model

import io.github.beacon.app.data.local.sql.MessageQuerySql
import io.github.beacon.app.data.local.sql.filterArgs
import io.github.beacon.app.data.local.sql.pageArgs
import org.junit.Assert.*
import org.junit.Test

class MessageQueryTest {
    @Test
    fun searchAndServerRemainBoundParameters() {
        val query = MessageQuery("%' OR 1=1 -- 中文", "server-a", 42)
        query.validate()
        assertArrayEquals(
            arrayOf(
                "server-a",
                "server-a",
                query.search,
                query.search,
                query.search,
                query.search,
                "0",
                "0",
                "0",
            ),
            query.filterArgs(),
        )
        assertArrayEquals(query.filterArgs() + arrayOf("42", "21"), query.pageArgs())
        assertFalse(MessageQuerySql.PAGE.contains(query.search))
    }

    @Test
    fun rejectsUnboundedSearchAndInvalidCursor() {
        MessageQuery("中".repeat(256), before = 1).validate()
        assertThrows(IllegalArgumentException::class.java) {
            MessageQuery("x".repeat(257)).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            MessageQuery(serverId = "x".repeat(129)).validate()
        }
        assertThrows(IllegalArgumentException::class.java) { MessageQuery(before = 0).validate() }
        assertThrows(IllegalArgumentException::class.java) { MessageQuery(before = -1).validate() }
    }
}
