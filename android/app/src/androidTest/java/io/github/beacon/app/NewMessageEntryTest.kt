package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.messages.MESSAGE_LIST_TAG
import io.github.beacon.app.feature.messages.MessagesPage
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.BeaconUiMetrics
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NewMessageEntryTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private fun verify(style:BeaconUiStyle,mode:BeaconThemeMode,font:Float) {
        val repository=RuntimeRepository()
        val rows=(1..20).map{IncomingMessage("arrivals","row-$it","历史消息 $it","正文",receivedAtMs=1792900000000)}
        val state=mutableStateOf(RuntimeState(messages=rows,messagesLoaded=true,messageTotal=20,runtimeConnected=true))
        var reading=false;var requests=0
        var expectedBackground = androidx.compose.ui.graphics.Color.Unspecified
        lateinit var scroll:LazyListState
        val actions=object:RuntimeActions by repository {
            override fun setMessageReading(readingHistory:Boolean){reading=readingHistory}
            override fun readMessages(injectTest:Boolean,delete:IncomingMessage?,search:String,serverId:String,
                readFilter:MessageReadFilter,showStatus:Boolean){
                requests++
                state.value=state.value.copy(newMessageCount=0,messages=listOf(
                    IncomingMessage("arrivals","new","刚收到的消息","正文"))+rows)
            }
        }
        try {
            rule.runOnUiThread{rule.activity.setContent{BeaconTheme(style,mode){
                expectedBackground = androidx.compose.material3.MaterialTheme.colorScheme.background
                val density=androidx.compose.ui.platform.LocalDensity.current
                CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density,font)){
                scroll=rememberLazyListState()
                MessagesPage(scroll,Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background).safeDrawingPadding(),PaddingValues(BeaconUiMetrics.PageInset),
                    state.value,actions,"",MessageReadFilter.ALL,false,emptyList(),"",
                    onSearch={},onApplyFilter={_,_->},onConnectionSettings={},onEnterSelection={},
                    onSelectionChange={},onSelectionHint={},onRequestDelete={},onCopyText={_,_->})
            }}}}
            val dateLabel = io.github.beacon.app.domain.policy.MessagePresentation.receivedDate(rows.first(), java.time.ZoneId.systemDefault())
                ?.toString() ?: rule.activity.getString(R.string.message_unknown_date)
            val firstDate = rule.onNodeWithText(dateLabel).getUnclippedBoundsInRoot()
            val initialHeader = rule.onNodeWithTag("message-pinned-header").getUnclippedBoundsInRoot()
            val dateGap = firstDate.top - initialHeader.bottom
            assertTrue("The blur tail must not reserve extra space above the first date", dateGap.value >= 0f && dateGap <= BeaconUiMetrics.PageInset)
            val root = rule.onRoot().captureToImage().toPixelMap()
            val listBounds = rule.onNodeWithTag(MESSAGE_LIST_TAG).fetchSemanticsNode().boundsInRoot
            val scale = rule.activity.resources.displayMetrics.density
            val y = (listBounds.top + 2 * scale).toInt()
            for (dp in 20..120 step 10) {
                val pixel = root[(listBounds.left + dp * scale).toInt(), y]
                assertEquals("No blurred date stripe at the top", expectedBackground.red, pixel.red, .02f)
                assertEquals(expectedBackground.green, pixel.green, .02f)
                assertEquals(expectedBackground.blue, pixel.blue, .02f)
            }
            rule.onNodeWithTag(MESSAGE_LIST_TAG).performScrollToIndex(8)
            rule.waitUntil(5000){reading}
            val viewport=rule.onNodeWithTag(MESSAGE_LIST_TAG).getUnclippedBoundsInRoot()
            val pinnedHeader=rule.onNodeWithTag("message-pinned-header").getUnclippedBoundsInRoot()
            assertTrue("Messages must scroll behind the pinned header for backdrop blur to work", viewport.top <= pinnedHeader.top)
            assertTrue("The pinned header overlaps the list viewport", pinnedHeader.bottom > viewport.top)
            var index=0;var offset=0
            rule.runOnIdle{index=scroll.firstVisibleItemIndex;offset=scroll.firstVisibleItemScrollOffset;state.value=state.value.copy(newMessageCount=2)}
            rule.onNodeWithTag("message-new-arrivals").assertIsDisplayed()
            rule.onNodeWithText(rule.activity.getString(R.string.msg_new_arrivals,2L)).assertIsDisplayed()
            val listNode=rule.onNodeWithTag(MESSAGE_LIST_TAG).fetchSemanticsNode()
            val entryNode=rule.onNodeWithTag("message-new-arrivals").fetchSemanticsNode()
            assertEquals("Floating entry must not resize the list viewport",viewport,
                rule.onNodeWithTag(MESSAGE_LIST_TAG).getUnclippedBoundsInRoot())
            rule.runOnIdle{
                assertTrue("Entry floats inside the list",entryNode.boundsInRoot.bottom<listNode.boundsInRoot.bottom)
                assertTrue("Only the pill occupies the lower area",entryNode.boundsInRoot.width<listNode.boundsInRoot.width)
            }
            val buttonBounds=rule.onNodeWithTag("message-new-arrivals").getUnclippedBoundsInRoot()
            assertTrue("Floating action is larger than a standard button",buttonBounds.bottom-buttonBounds.top>=BeaconUiMetrics.NewMessagesMinHeight)
            val file=java.io.File(rule.activity.getExternalFilesDir(null),"new_arrivals_${style}_${mode}_$font.png")
            rule.waitForIdle()
            Thread.sleep(200) // Allow the platform/MIUIX entrance animation to settle for capture.
            val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            val bitmap=requireNotNull(automation.takeScreenshot())
            file.outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
            for(command in listOf("mkdir -p /data/local/tmp/beacon-new-arrivals","cp ${file.absolutePath} /data/local/tmp/beacon-new-arrivals/${file.name}"))
                android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use{it.readBytes()}
            rule.runOnIdle{assertEquals(index,scroll.firstVisibleItemIndex);assertEquals(offset,scroll.firstVisibleItemScrollOffset);state.value=state.value.copy(dataBusy=true)}
            rule.onNodeWithTag("message-new-arrivals").assertIsNotEnabled()
            rule.runOnIdle{state.value=state.value.copy(dataBusy=false)}
            val lastIndex=rule.runOnIdle{scroll.layoutInfo.totalItemsCount-1}
            rule.onNodeWithTag(MESSAGE_LIST_TAG).performScrollToIndex(lastIndex)
            val footer=rule.onNodeWithText(rule.activity.getString(R.string.msg_history_exhausted)).fetchSemanticsNode().boundsInRoot
            assertTrue("The last row can scroll fully above the floating button",footer.bottom<entryNode.boundsInRoot.top)
            rule.onNodeWithTag("message-new-arrivals").performClick()
            rule.waitUntil(5000){scroll.firstVisibleItemIndex==0}
            rule.onNodeWithTag("message-new-arrivals").assertDoesNotExist()
            rule.onNodeWithText("刚收到的消息").assertIsDisplayed()
            assertEquals(1,requests)
        }finally{repository.close()}
    }
    @Test fun materialEntryKeepsReadingPositionUntilClicked(){
        for(mode in listOf(BeaconThemeMode.LIGHT,BeaconThemeMode.DARK))for(font in listOf(1f,2f))verify(BeaconUiStyle.MATERIAL3,mode,font)
    }
    @Test fun miuixEntryKeepsReadingPositionUntilClicked(){
        for(mode in listOf(BeaconThemeMode.LIGHT,BeaconThemeMode.DARK))for(font in listOf(1f,2f))verify(BeaconUiStyle.MIUIX,mode,font)
    }
}
