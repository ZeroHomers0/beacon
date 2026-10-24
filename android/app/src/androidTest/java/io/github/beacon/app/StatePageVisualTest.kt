package io.github.beacon.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.StateCache
import io.github.beacon.app.domain.model.BeaconThemeMode
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.feature.messages.StatesPage
import io.github.beacon.app.ui.theme.BeaconTheme
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

class StatePageVisualTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    @Test fun statePageMatchesBothExistingStyles(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val source="visual-state-source"
        StateCache(context).use{db->db.apply(source,listOf(JSONObject().put("schema_version",1).put("topic_key","quota:a").put("application_id","").put("revision",1).put("title","账户额度").put("body","短周期剩余 24% · 长周期剩余 62%").put("application_name","额度监控").put("accepted_at_ms",System.currentTimeMillis()).put("expires_at_ms",System.currentTimeMillis()+600000)),"visual",true)}
        try{
            for(style in BeaconUiStyle.entries)for(mode in listOf(BeaconThemeMode.LIGHT,BeaconThemeMode.DARK)){
                rule.runOnUiThread{rule.activity.setContent{BeaconTheme(style,mode){Surface{StatesPage(Modifier.fillMaxSize())}}}}
                rule.waitUntil(10000){rule.onAllNodesWithText("账户额度").fetchSemanticsNodes().isNotEmpty()}
                val bitmap=rule.onRoot().captureToImage().asAndroidBitmap()
                val folder=requireNotNull(context.getExternalFilesDir("state-qa"));folder.mkdirs()
                java.io.File(folder,"state_${style.name}_${mode.name}.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            }
        }finally{StateCache(context).use{it.forget(source)}}
    }
}
