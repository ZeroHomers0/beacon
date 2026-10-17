package io.github.beacon.app.domain.model

import io.github.beacon.app.data.local.sql.MessageQuerySql
import org.junit.Assert.*
import org.junit.Test

class MessageQueryTest {
    @Test
    fun searchAndServerRemainBoundParameters() {
        val query = MessageQuery("%' OR 1=1 -- 中文", "server-a", 42)
        query.validate()
        val (where, args) = MessageQuerySql.filter(query)
        // Every value is bound; the needle never reaches the SQL text.
        assertFalse(where.contains("%' OR 1=1"))
        assertFalse(where.contains("中文"))
        assertEquals("server-a", args[0])
        assertTrue(args.drop(1).all { it == query.search })
        // Six text columns are searched, so the needle is bound six times.
        assertEquals(7, args.size)
    }

    @Test
    fun unfilteredQueryEmitsNoParameterGuards() {
        // Regression guard for the full-table-scan predicate: `(? = '' OR col = ?)` cannot use
        // an index, so an empty filter must collapse to the delete-token conjunct alone.
        val (where, args) = MessageQuerySql.filter(MessageQuery())
        assertEquals("delete_token=''", where)
        assertTrue(args.isEmpty())
    }

    @Test
    fun eachFilterAddsOnlyItsOwnConjunct() {
        val (groupWhere, groupArgs) =
            MessageQuerySql.filter(MessageQuery("group:ops", "srv"))
        assertTrue(groupWhere.contains("message_group=?"))
        assertFalse(groupWhere.contains("instr("))
        assertArrayEquals(arrayOf("srv", "ops"), groupArgs)

        val (appWhere, appArgs) = MessageQuerySql.filter(MessageQuery("app:pkg.name"))
        assertTrue(appWhere.contains("application_id=?"))
        assertFalse(appWhere.contains("instr("))
        assertArrayEquals(arrayOf("pkg.name"), appArgs)

        val (unreadWhere, unreadArgs) =
            MessageQuerySql.filter(MessageQuery(readFilter = MessageReadFilter.UNREAD))
        assertTrue(unreadWhere.contains("is_read=0"))
        assertTrue(unreadArgs.isEmpty())

        val (readWhere, _) = MessageQuerySql.filter(MessageQuery(readFilter = MessageReadFilter.READ))
        assertTrue(readWhere.contains("is_read=1"))
    }

    @Test
    fun pageAppendsCursorAndLimitToTheSameArguments() {
        val query = MessageQuery("needle", "source", readFilter = MessageReadFilter.UNREAD)
        val (where, args) = MessageQuerySql.filter(query)
        val (pageSql, pageArgs) = MessageQuerySql.page(query)
        assertTrue(pageSql.endsWith("AND rowid < ? ORDER BY rowid DESC LIMIT ?"))
        assertArrayEquals(args + arrayOf("42", "21"), MessageQuerySql.page(query.copy(before = 42)).second)
        assertArrayEquals(args + arrayOf("${Long.MAX_VALUE}", "${MessageQuery.PAGE_SIZE + 1}"), pageArgs)
        assertFalse(pageSql.contains("needle"))
        assertFalse(where.contains("OR 1"))
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
