package io.github.beacon.app.domain.model

import io.github.beacon.app.data.local.sql.MessageQuerySql
import java.io.*
import org.junit.Assert.*
import org.junit.Test

class MessageManagementTest {
    @Test
    fun identityIncludesServerAndSupportsSavedSelection() {
        val keys = arrayListOf(MessageKey("a", "same"), MessageKey("b", "same"))
        MessageMutation(MessageAction.DELETE, keys).validate()
        assertNotEquals(keys[0], keys[1])
        val bytes =
            ByteArrayOutputStream()
                .also { ObjectOutputStream(it).use { out -> out.writeObject(keys) } }
                .toByteArray()
        assertEquals(keys, ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() })
    }

    @Test
    fun batchRejectsEmptyDuplicateAndOversizedSelection() {
        fun rejected(keys: List<MessageKey>) =
            assertThrows(IllegalArgumentException::class.java) {
                MessageMutation(MessageAction.MARK_READ, keys).validate()
            }
        rejected(emptyList())
        rejected(listOf(MessageKey("a", "1"), MessageKey("a", "1")))
        rejected((1..101).map { MessageKey("a", "$it") })
        rejected(listOf(MessageKey(" ", "1")))
        rejected(listOf(MessageKey("a", "x".repeat(129))))
        MessageMutation(MessageAction.DELETE, (1..100).map { MessageKey("a", "$it") }).validate()
    }

    @Test
    fun readFilterUsesStringParametersAsAndroidRawQueryDoes() {
        val query = MessageQuery("needle", "source", readFilter = MessageReadFilter.UNREAD)
        val (where, args) = MessageQuerySql.filter(query)
        // Read state is now a literal conjunct, so only the bound values remain as parameters.
        assertArrayEquals(
            arrayOf("source", "needle", "needle", "needle", "needle", "needle", "needle"),
            args,
        )
        assertTrue(where.contains("is_read=0"))
        assertFalse(where.contains("? = '1'"))
    }
}
