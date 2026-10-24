package io.github.beacon.app.data.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class StructuredBlocksCodecTest {
    private fun decode(blocks: String, version: Int = 1) = StructuredBlocksCodec.decode(
        JSONObject().put("schema_version", version).put("blocks", JSONArray(blocks))
    )
    @Test fun preservesNumericBlocksAndSkipsUnknownOrMalformedOnes() {
        val raw = decode("""[{"kind":"progress","id":"download","value":40,"max":100},{"kind":"html","id":"unsafe"},{"kind":"progress","id":"bad","value":101,"max":100}]""")
        assertEquals(1, JSONArray(raw).length())
        assertEquals("download", JSONArray(raw).getJSONObject(0).getString("id"))
    }
    @Test fun futureSchemaFallsBackToText() {
        assertEquals("[]", decode("""[{"kind":"metric","id":"x","value":1}]""", 2))
    }
    @Test fun unknownProgressIsNotZero() {
        val raw = decode("""[{"kind":"progress","id":"download","indeterminate":true}]""")
        assertEquals(false, JSONArray(raw).getJSONObject(0).has("value"))
    }
}
