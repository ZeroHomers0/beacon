package io.github.beacon.app.data.remote

import io.github.beacon.app.domain.receive.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HttpMessagePageDecoderTest {
    private val server = "a".repeat(64)

    private fun row(sequence: Any = 1, id: String = "b".repeat(64)) =
        JSONObject().put("id", id).put("sequence", sequence).put("title", "T").put("body", "B")

    private fun page(rows: JSONArray = JSONArray().put(row()), cursor: Any = 1, more: Any = false) =
        JSONObject()
            .put("server_id", server)
            .put("messages", rows)
            .put("next_cursor", cursor)
            .put("has_more", more)

    private fun decode(value: JSONObject) =
        HttpMessagePageDecoder.decode(value, server, "namespace", 0, 0, 0)

    @Test
    fun linkActionsArePreservedAndUnsafePageRejected() {
        val actions = JSONArray().put(JSONObject().put("title", "Logs").put("url", "https://example.com/logs"))
        assertEquals("https://example.com/logs", decode(page(JSONArray().put(row().put("actions", actions)))).messages.single().actions.single().url)
        actions.getJSONObject(0).put("url", "http://example.com")
        assertThrows(SyncResponseException::class.java) { decode(page(JSONArray().put(row().put("actions", actions)))) }
        assertThrows(SyncResponseException::class.java) { decode(page(JSONArray().put(row().put("actions", JSONObject.NULL)))) }
    }

    @Test
    fun optionalSubtitleAndUnknownTimeRemainCompatible() {
        val value = decode(page(JSONArray().put(row().put("subtitle", JSONObject.NULL))))
        assertEquals("", value.messages.single().subtitle)
        assertEquals(0L, value.messages.single().createdAtMs)
        assertEquals(1L, value.cursor)
    }

    @Test
    fun numericCoercionAndBooleanStringsAreRejected() {
        for (invalid in listOf<Any>(1.5, "1", true)) {
            assertThrows(SyncResponseException::class.java) {
                decode(page(JSONArray().put(row(invalid))))
            }
            assertThrows(SyncResponseException::class.java) { decode(page(cursor = invalid)) }
        }
        assertThrows(SyncResponseException::class.java) { decode(page(more = "false")) }
    }

    @Test
    fun cursorIdentityEmptyContinuationAndDuplicateIdsAreRejected() {
        assertThrows(SyncResponseException::class.java) { decode(page(cursor = 2)) }
        assertThrows(SyncResponseException::class.java) {
            decode(page().put("server_id", "foreign"))
        }
        assertThrows(SyncResponseException::class.java) { decode(page(JSONArray(), 0, true)) }
        assertThrows(SyncResponseException::class.java) {
            decode(page(JSONArray().put(row()).put(row(2)), 2))
        }
        assertThrows(SyncResponseException::class.java) {
            decode(page(JSONArray().put(row(2)).put(row(1, "c".repeat(64))), 1))
        }
    }

    @Test
    fun pageBoundsAndInvalidLaterBodyAreRejectedTogether() {
        // One row past the negotiated bound. Derived from the constant so raising the page size
        // cannot quietly disable the check.
        val oversize = HttpSyncLimits.PAGE_SIZE + 1
        val rows = JSONArray()
        repeat(oversize) { rows.put(row(it + 1, "%064x".format(it + 1))) }
        assertThrows(SyncResponseException::class.java) { decode(page(rows, oversize)) }
        assertThrows(SyncResponseException::class.java) {
            decode(page(JSONArray().put(row()).put(row(2, "c".repeat(64)).put("body", 123)), 2))
        }
    }
}
