package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.beacon.app.domain.model.BeaconThemeMode
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.ui.components.BeaconSaveAction
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SaveActionAppearanceTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private fun verify(style:BeaconUiStyle){
        for(mode in listOf(BeaconThemeMode.LIGHT,BeaconThemeMode.DARK)){
            val dirty=mutableStateOf(false);var calls=0;var fail=true
            rule.runOnUiThread{rule.activity.setContent{BeaconTheme(style,mode){
                Surface(color=MaterialTheme.colorScheme.background){Box(Modifier.fillMaxSize()){
                    BeaconSaveAction("测试保存",dirty.value,false,true){calls++;if(!fail)dirty.value=false}
                }}
            }}}
            val save=rule.onNodeWithContentDescription("测试保存")
            save.assertIsNotEnabled()
            val clean=save.captureToImage().asAndroidBitmap()
            rule.runOnIdle{dirty.value=true}
            save.assertIsEnabled()
            val changed=save.captureToImage().asAndroidBitmap()
            fun contrast(bitmap:android.graphics.Bitmap):Long {
                val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                val background=pixels[0]
                return pixels.maxOf { color ->
                    kotlin.math.abs(android.graphics.Color.red(color)-android.graphics.Color.red(background)).toLong()+
                    kotlin.math.abs(android.graphics.Color.green(color)-android.graphics.Color.green(background))+
                    kotlin.math.abs(android.graphics.Color.blue(color)-android.graphics.Color.blue(background))
                }
            }
            assertTrue("unsaved icon has stronger contrast: $style $mode",contrast(changed)>contrast(clean)*1.4)
            save.performClick();assertEquals(1,calls);save.assertIsEnabled()
            fail=false;save.performClick();assertEquals(2,calls);save.assertIsNotEnabled()
            rule.mainClock.advanceTimeBy(1500)
            rule.waitForIdle()
            val restored=save.captureToImage().asAndroidBitmap()
            // Material's fading press ripple may remain behind the disabled icon for a frame.
            // Compare gray with the active glyph, rather than requiring identical backgrounds.
            assertTrue("saved icon is gray rather than active: $style $mode",contrast(restored)<contrast(changed)*.72)
            rule.runOnIdle{dirty.value=true};save.assertIsEnabled()
        }
    }
    @Test fun materialSaveColorTracksUnsavedChanges()=verify(BeaconUiStyle.MATERIAL3)
    @Test fun miuixSaveColorTracksUnsavedChanges()=verify(BeaconUiStyle.MIUIX)
}
