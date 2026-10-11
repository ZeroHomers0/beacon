package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.beacon.app.ui.components.BeaconPagerToolbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PagerToolbarTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun toolbarAndContentShareIntermediatePagerPosition() {
        lateinit var pager: PagerState
        lateinit var scope: CoroutineScope
        rule.runOnUiThread {
            rule.activity.setContent {
                pager = rememberPagerState { 2 }
                scope = rememberCoroutineScope()
                Column {
                    BeaconPagerToolbar(pager) { page ->
                        Box(Modifier.fillMaxWidth().height(64.dp).testTag("toolbar-$page"))
                    }
                    HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 1) { page
                        ->
                        Box(Modifier.fillMaxSize().testTag("content-$page"))
                    }
                }
            }
        }
        val beforeToolbar = rule.onNodeWithTag("toolbar-0").fetchSemanticsNode().positionInRoot.x
        val beforeContent = rule.onNodeWithTag("content-0").fetchSemanticsNode().positionInRoot.x
        rule.runOnIdle { scope.launch { pager.scrollToPage(0, 0.25f) } }
        rule.waitForIdle()
        val toolbarDelta =
            rule.onNodeWithTag("toolbar-0").fetchSemanticsNode().positionInRoot.x - beforeToolbar
        val contentDelta =
            rule.onNodeWithTag("content-0").fetchSemanticsNode().positionInRoot.x - beforeContent
        assertTrue("Both elements must have moved", toolbarDelta < -1f)
        assertEquals(contentDelta, toolbarDelta, 1f)
    }
}
