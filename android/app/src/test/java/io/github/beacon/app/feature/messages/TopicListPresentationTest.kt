package io.github.beacon.app.feature.messages

import io.github.beacon.app.domain.model.DataStatus
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TopicListPresentationTest {
    private fun row(block: String) = JSONObject("""{"body":"普通正文","blocks":[$block]}""")
    @Test fun finiteProgressUsesActualValueAndRange() {
        val preview = topicListPreview(row("""{"kind":"progress","label":"上传","value":75,"min":50,"max":100,"unit":"%"}"""), DataStatus.ACTIVE)
        assertEquals("上传 · 75%", preview.summary)
        assertEquals(.5f, preview.progress!!, .001f)
    }
    @Test fun missingOrIndeterminateValueFallsBackToBodyWithoutFakeZero() {
        for (block in listOf("""{"kind":"metric","label":"任务"}""", """{"kind":"progress","value":0,"indeterminate":true}""")) {
            val preview = topicListPreview(row(block), DataStatus.ACTIVE)
            assertEquals("普通正文", preview.summary)
            assertNull(preview.progress)
        }
    }
    @Test fun waitingClearedAndMissingNeverExposeCachedNumbers() {
        for (state in listOf(DataStatus.WAITING, DataStatus.CLEARED, DataStatus.MISSING)) {
            assertEquals("", topicListPreview(row("""{"kind":"metric","value":42}"""), state).summary)
        }
    }
    @Test fun metricWithoutRangeKeepsZeroButHasNoInventedProgress() {
        val preview = topicListPreview(row("""{"kind":"metric","label":"待处理","value":0,"unit":"项"}"""), DataStatus.ACTIVE)
        assertEquals("待处理 · 0项", preview.summary)
        assertNull(preview.progress)
    }
    @Test fun smallNonzeroValueAndDecimalPrecisionAreNotRoundedAway() {
        for (value in listOf("0.0001", "123.456")) {
            val preview = topicListPreview(row("""{"kind":"metric","value":$value}"""), DataStatus.ACTIVE)
            assertEquals(value, preview.summary)
        }
    }

}
