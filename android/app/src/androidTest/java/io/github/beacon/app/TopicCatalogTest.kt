package io.github.beacon.app

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.StateCache
import io.github.beacon.app.widget.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class TopicCatalogTest {
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 @Test fun unboundInstructionsWrapWithoutEllipsisAndStayInsideSmallCards(){
  val folder=requireNotNull(context.getExternalFilesDir("widget-qa"));folder.mkdirs()
  for((width,height) in listOf(110 to 110,148 to 174,180 to 180,270 to 180,360 to 180,360 to 360)){
   val scale=kotlin.math.min(width/300f,height/220f).coerceAtLeast(.75f)
   val pad=kotlin.math.max(12f,kotlin.math.min(width,height)*(40f/440f))
   val room=(height-2*pad-26*scale).toInt()
   val layout=WidgetPlaceholder.layout(WidgetPlaceholder.UNBOUND,(width-2*pad).toInt(),room,14f*scale,android.graphics.Color.GRAY)
   assertTrue(layout.height<=room);assertEquals(WidgetPlaceholder.UNBOUND.length,layout.getLineEnd(layout.lineCount-1))
   for(line in 0 until layout.lineCount)assertEquals(0,layout.getEllipsisCount(line))
   if(width<=180)assertTrue(layout.lineCount>2)
   val bitmap=WidgetRenderer.render(null,JSONObject(),width,height,false,3f)
   java.io.File(folder,"unbound_${width}_${height}.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
  }
 }
 @Test fun largeWidgetDeclaresFourByFourAndUsesTheSameCornerAtEverySize(){
  val info=AppWidgetManager.getInstance(context).installedProviders.first{it.provider==ComponentName(context,BeaconLargeStateWidget::class.java)}
  assertEquals(4,info.targetCellWidth);assertEquals(4,info.targetCellHeight)
  val density=context.resources.displayMetrics.density
  assertEquals(250f,info.minWidth/density,1f);assertEquals(250f,info.minHeight/density,1f)
  val config=JSONObject();val small=WidgetRenderer.render(null,config,180,180,false);val large=WidgetRenderer.render(null,config,360,360,false)
  for(y in 0 until 27)for(x in 0 until 27)assertEquals("corner $x/$y",Color.alpha(small.getPixel(x,y)),Color.alpha(large.getPixel(x,y)))
  small.recycle();large.recycle()
 }
 @Test fun catalogSeparatesWaitingFromDataAndHardDeletionRemovesChoices(){
  StateCache(context).use{cache->val source="catalog-test";cache.forget(source)
   try{
    val topic=JSONObject().put("application_id","").put("topic_key","test").put("name","Manual name").put("config_version",1).put("deleted",false)
    cache.applyTopics(source,listOf(topic),"catalog1",true,"")
    val waiting=cache.find(source,":test")!!;assertTrue(waiting.getBoolean("_waiting"));assertEquals(0,waiting.getLong("revision"));assertEquals("Manual name",waiting.getString("title"))
    val config=JSONObject().put("template","auto");assertNull(WidgetConfigRules.layoutError(waiting,config,180,180))
    val data=JSONObject().put("schema_version",1).put("application_id","").put("topic_key","test").put("title","Reported name").put("revision",9).put("body","40%").put("blocks",JSONArray().put(JSONObject().put("kind","progress").put("id","p").put("label","进度").put("value",40).put("max",100)))
    cache.apply(source,listOf(data),"state1",true,"")
    val active=cache.find(source,":test")!!;assertFalse(active.getBoolean("_waiting"));assertEquals("Manual name",active.getString("title"));assertEquals("horizontal_bars",WidgetConfigRules.resolved(active,config).getString("template"))
    cache.applyTopics(source,listOf(JSONObject(topic.toString()).put("deleted",true).put("config_version",2)),"catalog2",false,"catalog1")
    assertNull(cache.find(source,":test"));assertFalse(cache.all().any{it.optString("local_source")==source})
    cache.applyTopics(source,listOf(topic),"catalog3",false,"catalog2")
    data.put("revision",1);cache.apply(source,listOf(data),"state2",true,"")
    assertEquals(1,cache.find(source,":test")!!.getLong("revision"))
   }finally{cache.forget(source)}
  }
 }
}
