package io.github.beacon.app

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.StateCache
import io.github.beacon.app.widget.WidgetConfigRules
import io.github.beacon.app.widget.WidgetRenderer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StructuredStateTest {
    @Test fun highDensityWidgetsRenderAtPhysicalResolutionWithoutChangingLayout(){
        val config=JSONObject().put("template","single_ring").put("metrics",JSONArray(listOf("short")))
        val low=WidgetRenderer.render(row(),config,180,180,false,1f)
        val high=WidgetRenderer.render(row(),config,180,180,false,3f)
        assertEquals(540,high.width);assertEquals(540,high.height)
        assertEquals(android.graphics.Bitmap.DENSITY_NONE,high.density)
        assertEquals(0,android.graphics.Color.alpha(high.getPixel(5,5)))
        // A rounded corner extends well beyond the former 8.6%-radius geometry.
        assertEquals(0,android.graphics.Color.alpha(high.getPixel(20,20)))
        assertEquals(low.getPixel(90,20),high.getPixel(270,60))
        val bounded=WidgetRenderer.render(row(),config,300,250,false,4f,300000)
        assertTrue(bounded.byteCount<=300000*4)
        val folder=requireNotNull(context.getExternalFilesDir("widget-qa"));folder.mkdirs()
        java.io.File(folder,"high_density_single_ring_3x.png").outputStream().use{high.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun xiaomiMinimumAndNarrowHostSizesPreserveAspectAndSafeEdges(){
        val r=row()
        val sizes=listOf(110 to 110,300 to 110,300 to 250,148 to 174,183 to 183,356 to 174)
        val folder=requireNotNull(context.getExternalFilesDir("widget-qa"));folder.mkdirs()
        for((width,height) in sizes)for(dark in listOf(false,true)){
            val config=JSONObject().put("template",if(width>height*2)"horizontal_bars" else "single_ring").put("metrics",JSONArray(listOf("short")))
            val bitmap=WidgetRenderer.render(r,config,width,height,dark)
            assertTrue("host aspect ratio",kotlin.math.abs(bitmap.width.toDouble()/bitmap.height-width.toDouble()/height)<.025)
            assertTrue("bitmap budget",bitmap.byteCount<=2097152*4)
            assertEquals("transparent rounded corner",0,android.graphics.Color.alpha(bitmap.getPixel(0,0)))
            java.io.File(folder,"xiaomi_${width}_${height}_${if(dark)"dark"else"light"}.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
    }
    @Test fun hostSizesUseResponsiveDimensionsAndLegacyOrientation(){
        val options=android.os.Bundle().apply{
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,150)
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,300)
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,110)
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,180)
        }
        val resolver=io.github.beacon.app.widget.WidgetHostSizes
        assertEquals(android.util.SizeF(150f,180f),resolver.resolve(options,android.content.res.Configuration.ORIENTATION_PORTRAIT).single())
        assertEquals(android.util.SizeF(300f,110f),resolver.resolve(options,android.content.res.Configuration.ORIENTATION_LANDSCAPE).single())
        if(android.os.Build.VERSION.SDK_INT>=31){
            val sizes=arrayListOf(android.util.SizeF(148f,174f),android.util.SizeF(300f,110f))
            options.putParcelableArrayList(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_SIZES,sizes)
            assertEquals(sizes,resolver.resolve(options,android.content.res.Configuration.ORIENTATION_PORTRAIT))
        }
    }
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun row(revision:Int=1)=JSONObject().put("topic_key","quota:test").put("application_id","").put("schema_version",1).put("revision",revision).put("title","账户额度").put("body","短周期 24%，长周期 62%").put("accepted_at_ms",System.currentTimeMillis()).put("observed_at_ms",System.currentTimeMillis()).put("expires_at_ms",System.currentTimeMillis()+600000).put("blocks",JSONArray("""[{"kind":"progress","id":"short","label":"短周期剩余","value":24,"max":100,"unit":"%"},{"kind":"progress","id":"long","label":"长周期剩余","value":62,"max":100,"unit":"%"},{"kind":"progress","id":"monthly","label":"月度剩余","value":76,"max":100,"unit":"%"},{"kind":"progress","id":"yearly","label":"年度剩余","value":89,"max":100,"unit":"%"}]"""))
    @Test fun longCaptionsAndNumbersNeverPaintOverProgressTracks(){
        val r=row().put("title","这是一个非常长而且绝对不应该撑开布局的账户额度标题")
        val blocks=r.getJSONArray("blocks")
        for(i in 0 until blocks.length())blocks.getJSONObject(i).put("label","非常长的窗口额度说明文字绝对不能覆盖右侧数字").put("value",999999999999999.0).put("max",999999999999999.0).put("unit","requests/second")
        val palette=setOf(0xff3478f6.toInt(),0xff7466d9.toInt(),0xff16754d.toInt(),0xffa45a06.toInt())
        val folder=requireNotNull(context.getExternalFilesDir("widget-qa"));folder.mkdirs()
        for(size in listOf(180 to 180,360 to 220,480 to 320)){
            val bitmap=WidgetRenderer.render(r,JSONObject().put("template","horizontal_bars"),size.first,size.second,false)
            var tracks=0
            for(y in 0 until bitmap.height){
                val colored=(0 until bitmap.width).count{x->bitmap.getPixel(x,y) in palette}
                if(colored>bitmap.width*.65){
                    tracks++
                    for(x in bitmap.width/12 until bitmap.width*11/12){val pixel=bitmap.getPixel(x,y);assertFalse("text overlaps track at $x,$y",android.graphics.Color.red(pixel)<90&&android.graphics.Color.green(pixel)<90&&android.graphics.Color.blue(pixel)<100)}
                }
            }
            assertTrue("progress tracks must remain visible",tracks>0)
            java.io.File(folder,"long_text_${size.first}_${size.second}.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
    }
    @Test fun nativeTemplatesRenderAcrossSizesAndThemes(){
        val directory=requireNotNull(context.getExternalFilesDir("widget-qa"));directory.mkdirs()
        for(count in 1..3)for(template in listOf("horizontal_bars","vertical_bars","nested_rings","ring_grid")){
            if(count==1&&template!="horizontal_bars")continue
            val ids=listOf("short","long","monthly").take(count)
            val config=JSONObject().put("template",template).put("metrics",JSONArray(ids))
            assertNull(WidgetConfigRules.error(row(),config))
            val bitmap=WidgetRenderer.render(row(),config,360,260,false)
            java.io.File(directory,"bold_${template}_${count}.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
        for(template in WidgetRenderer.templates){
            val r=row();val ids=when(template){"single_ring"->listOf("short");"nested_rings"->listOf("short","long","monthly");else->listOf("short","long","monthly","yearly")}
            if(template=="segmented_ring")r.put("blocks",JSONArray("""[{"kind":"segments","id":"disk","label":"磁盘分布","total":100,"unit":"GB","items":[{"id":"images","label":"图片","value":30},{"id":"video","label":"视频","value":40},{"id":"other","label":"其他","value":10}]}]"""))
            val config=JSONObject().put("template",template).put("metrics",JSONArray(if(template=="segmented_ring")listOf("disk")else ids))
            assertNull(template,WidgetConfigRules.error(r,config))
            for(dark in listOf(false,true))for(size in listOf(180 to 180,360 to 220,480 to 320)){
                val bitmap=WidgetRenderer.render(r,config,size.first,size.second,dark)
                assertTrue("bitmap budget",bitmap.byteCount<=2097152*4)
                java.io.File(directory,"${template}_${size.first}_${size.second}_${if(dark)"dark"else"light"}.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            }
        }
    }
    @Test fun stateCachePreservesVersionsAndAppliesSnapshotDeletion(){
        StateCache(context).use{cache->val source="test-state-source";cache.forget(source)
            try{cache.apply(source,listOf(row(2)),"cursor-2",true);cache.apply(source,listOf(row(1)),"cursor-3",false)
                assertEquals(2L,cache.all().first{it.optString("local_source")==source}.getLong("revision"))
                cache.apply(source,listOf(row(3).put("deleted",true)),"cursor-4",false)
                assertFalse(cache.all().any{it.optString("local_source")==source})
                assertEquals("cursor-4",cache.cursor(source))
            }finally{cache.forget(source)}
        }
    }
    @Test fun bindingsKeepMetricOrderAndRejectIncompatibleCharts(){
        val r=row();val config=JSONObject().put("template","nested_rings").put("metrics",JSONArray(listOf("long","short")))
        assertEquals("long",WidgetConfigRules.selected(r,config).first().getString("id"))
        assertNull(WidgetConfigRules.error(r,config))
        config.put("template","single_ring");assertNotNull(WidgetConfigRules.error(r,config))
        config.put("template","segmented_ring");assertNotNull(WidgetConfigRules.error(r,config))
    }
    @Test fun importedSnapshotsCannotPoisonLiveRevisionWatermarks(){
        val source="a".repeat(64)
        StateCache(context).use{cache->
            cache.forget(source)
            val offline=java.security.MessageDigest.getInstance("SHA-256").digest(("offline:"+source).toByteArray()).joinToString(""){"%02x".format(it.toInt()and 255)}
            cache.forget(offline)
            try{
                cache.apply(source,listOf(row(1)),"live-cursor",true)
                val raw=io.github.beacon.app.widget.StateDataArchive.export(cache)
                io.github.beacon.app.widget.StateDataArchive.restore(cache,raw)
                cache.apply(source,listOf(row(2)),"live-next",false)
                assertEquals(2L,cache.all().first{it.optString("local_source")==source}.getLong("revision"))
                assertTrue(cache.all().any{it.optString("local_source")==offline&&it.optBoolean("local_imported")})
                assertEquals("live-next",cache.cursor(source))
            }finally{cache.forget(source);cache.forget(offline)}
        }
    }
}
