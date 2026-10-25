package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.settings.TemporaryMuteAction
import io.github.beacon.app.data.local.TemporaryMuteStore
import io.github.beacon.app.data.local.StateCache
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TemporaryMuteTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private fun verify(style:BeaconUiStyle) {
        val context=rule.activity.applicationContext
        for(mode in listOf(BeaconThemeMode.LIGHT,BeaconThemeMode.DARK))for(font in listOf(1f,2f)) {
        TemporaryMuteStore(context).save(MuteScope.GLOBAL,0)
        rule.runOnUiThread{rule.activity.setContent{BeaconTheme(style,mode){
            CompositionLocalProvider(LocalDensity provides Density(context.resources.displayMetrics.density,font)){
            BeaconSettingsList(androidx.compose.foundation.lazy.rememberLazyListState(),Modifier.fillMaxSize()){
                item{SettingsGroup("通知"){TemporaryMuteAction(MuteScope.GLOBAL)}}
            }
        }}}}
        rule.onNodeWithText(rule.activity.getString(R.string.mute_title)).performClick()
        rule.waitUntil(10000){rule.onAllNodesWithText(rule.activity.getString(R.string.mute_duration)).fetchSemanticsNodes().isNotEmpty()}
        rule.waitUntil(10000){rule.onAllNodesWithText(rule.activity.getString(R.string.mute_not_active)).fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithText(rule.activity.getString(R.string.mute_not_active)).performScrollTo()
        capture("mute_${style}_${mode}_$font")
        rule.onNodeWithText(rule.activity.getString(R.string.mute_not_active)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.mute_one_hour)).performClick()
        rule.waitUntil(10000){TemporaryMuteStore(context).snapshot(MuteScope.GLOBAL).localUntilMs>System.currentTimeMillis()}
        rule.waitForIdle()
        assertTrue(TemporaryMuteStore(context).rules().muted("any",nowMs=System.currentTimeMillis()))
        rule.onNodeWithText(rule.activity.getString(R.string.action_close)).performClick()
        TemporaryMuteStore(context).save(MuteScope.GLOBAL,0)
        }
    }
    private fun capture(name:String) {
        val file=java.io.File(rule.activity.getExternalFilesDir("ui-qa"),"$name.png")
        val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        val bitmap=requireNotNull(automation.takeScreenshot())
        file.outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
        for(command in listOf("mkdir -p /data/local/tmp/beacon-ntfy-ui", "cp ${file.absolutePath} /data/local/tmp/beacon-ntfy-ui/${file.name}"))
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use{it.readBytes()}
    }
    @Test fun globalMuteUsesExistingControlsInMaterial()=verify(BeaconUiStyle.MATERIAL3)
    @Test fun globalMuteUsesExistingControlsInMiuix()=verify(BeaconUiStyle.MIUIX)
    @Test fun mutedStateDoesNotReplayAfterRestoringReminders(){
        val context=rule.activity.applicationContext
        val source="mute-state-test";val identity=":quota:test";val key="$source:$identity"
        val mutes=TemporaryMuteStore(context)
        val manager=context.getSystemService(android.app.NotificationManager::class.java)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName,android.Manifest.permission.POST_NOTIFICATIONS)
        val row=org.json.JSONObject().put("topic_key","quota:test").put("application_id","")
            .put("revision",1).put("title","Mute regression").put("body","saved snapshot")
            .put("accepted_at_ms",System.currentTimeMillis()).put("blocks",org.json.JSONArray())
        try{
            StateCache(context).use{cache->
                cache.apply(source,listOf(row),"1",false)
                cache.preference("notify:$key","1");cache.preference("dismiss:$key","")
                mutes.save(MuteScope.GLOBAL,System.currentTimeMillis()+3600000)
                io.github.beacon.app.widget.StateNotifications.refresh(context)
                assertTrue(cache.preference("muted-snapshot:$key").isNotEmpty())
                mutes.save(MuteScope.GLOBAL,0)
                io.github.beacon.app.widget.StateNotifications.refresh(context)
                assertFalse(manager.activeNotifications.any{it.tag=="state:$key"})
                row.put("revision",2).put("body","new snapshot")
                cache.apply(source,listOf(row),"2",false)
                io.github.beacon.app.widget.StateNotifications.refresh(context)
                assertTrue(manager.activeNotifications.any{it.tag=="state:$key"})
            }
        }finally{
            mutes.save(MuteScope.GLOBAL,0)
            StateCache(context).use{cache->cache.forget(source);for(prefix in listOf("notify","dismiss","posted","posted-at","muted-snapshot"))cache.preference("$prefix:$key","")}
            manager.cancel("state:$key",1)
        }
    }
}
