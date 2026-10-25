package io.github.beacon.app
import android.appwidget.AppWidgetManager
import android.graphics.Bitmap
import android.content.ComponentName
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.widget.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/** Export deterministic samples through the real renderer; no user data or credentials. */
class WidgetPickerPreviewTest {
 @Test fun exportAccuratePreviewsForEveryProvider(){
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val folder=requireNotNull(context.getExternalFilesDir("picker-previews"));folder.mkdirs()
  val row=JSONObject().put("schema_version",1).put("title","任务进度").put("body","示例数据").put("blocks",JSONArray("""[{"kind":"progress","id":"download","label":"下载进度","value":72,"max":100,"unit":"%"},{"kind":"progress","id":"backup","label":"备份进度","value":48,"max":100,"unit":"%"},{"kind":"progress","id":"storage","label":"存储使用","value":36,"max":100,"unit":"%"},{"kind":"progress","id":"sync","label":"同步进度","value":85,"max":100,"unit":"%"}]"""))
  for(format in WidgetFormat.entries){
   val template=when(format){WidgetFormat.SQUARE->"single_ring";WidgetFormat.LARGE->"ring_grid";else->"horizontal_bars"}
   val ids=when(format){WidgetFormat.SQUARE->listOf("download");WidgetFormat.LARGE->listOf("download","backup","storage","sync");else->listOf("download","backup")}
   val config=JSONObject().put("template",template).put("metrics",JSONArray(ids)).put("format",format.name).put("updated",false).put("background","light")
   assertNull(WidgetConfigRules.layoutError(row,config,format.width,format.height))
   val bitmap=WidgetRenderer.render(row,config,format.width,format.height,false,3f)
   val file=java.io.File(folder,"${format.name.lowercase()}.png");file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
  }
 }
 @Test fun providerPreviewsAreDistinctAndScalable(){
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val manager=AppWidgetManager.getInstance(context)
  val providers=WidgetFormat.entries.map{format->manager.installedProviders.first{it.provider==format.component(context)}}
  assertEquals(4,providers.map{it.previewImage}.toSet().size)
  assertEquals(4,providers.map{it.previewLayout}.toSet().size)
  assertTrue(providers.all{it.previewLayout!=0})
  for((index,info) in providers.withIndex()){
   val image=info.loadPreviewImage(context,context.resources.displayMetrics.densityDpi);assertNotNull(image);assertTrue(image!!.intrinsicWidth>0&&image.intrinsicHeight>0)
   val format=WidgetFormat.entries[index]
   assertEquals(format.width.toDouble()/format.height,image.intrinsicWidth.toDouble()/image.intrinsicHeight,.01)
   InstrumentationRegistry.getInstrumentation().runOnMainSync{
    val view=android.widget.RemoteViews(context.packageName,info.previewLayout).apply(context,android.widget.FrameLayout(context)) as android.widget.ImageView
    assertEquals(android.widget.ImageView.ScaleType.FIT_XY,view.scaleType)
    view.measure(android.view.View.MeasureSpec.makeMeasureSpec(format.width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(format.height,android.view.View.MeasureSpec.EXACTLY))
    assertEquals(format.width,view.measuredWidth);assertEquals(format.height,view.measuredHeight)
   }
  }
 }
}
