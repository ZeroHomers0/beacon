package io.github.beacon.app.feature.messages

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.beacon.app.domain.policy.CountdownDisplayPolicy
import androidx.compose.ui.Modifier
import io.github.beacon.app.ui.components.BeaconUiMetrics
import io.github.beacon.app.ui.components.BeaconText
import org.json.JSONArray

@Composable
internal fun StructuredBlocksPanel(payload: String) {
    val blocks = remember(payload) { runCatching { JSONArray(payload) }.getOrElse { JSONArray() } }
    Column(verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap12)) {
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            val kind=block.optString("kind")
            if(kind !in listOf("progress","metric")) {
                RichBlock(block)
                continue
            }
            val unknown = block.optBoolean("indeterminate", false)
            val value = block.optDouble("value")
            val max = block.optDouble("max")
            val min = block.optDouble("min", 0.0)
            val color = when (block.optString("tone")) {
                "critical" -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                BeaconText(block.optString("label", block.optString("id")), modifier = Modifier.weight(1f))
                if (!unknown && value.isFinite()) {
                    val number = if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
                    BeaconText(number + block.optString("unit"),modifier=Modifier.weight(1f),maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            if (unknown) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = color)
            } else if (value.isFinite() && max.isFinite() && max > min) {
                LinearProgressIndicator(progress = { ((value-min)/(max-min)).toFloat().coerceIn(0f,1f) }, modifier = Modifier.fillMaxWidth(), color = color)
            }
        }
    }
}

@Composable
private fun RichBlock(block: org.json.JSONObject) {
    val kind=block.optString("kind")
    if(block.optString("label").isNotBlank()) BeaconText(block.optString("label"),style=MaterialTheme.typography.titleMedium)
    when(kind){
        "alert"->BeaconText(block.optString("text"),color=if(block.optString("tone")=="critical")MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        "countdown" -> CountdownBlock(block.optLong("target_at_ms"))
        "sparkline"->{val points=block.optJSONArray("points")?:org.json.JSONArray();val color=MaterialTheme.colorScheme.primary;androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(BeaconUiMetrics.StatusIconSize*2)){
            if(points.length()>1){val values=(0 until points.length()).map{points.getJSONObject(it).optDouble("value")};val low=values.min();val high=values.max();val span=(high-low).coerceAtLeast(1.0);val path=androidx.compose.ui.graphics.Path();values.forEachIndexed{i,v->val x=size.width*i/(values.size-1);val y=(size.height*(1-(v-low)/span)).toFloat();if(i==0)path.moveTo(x,y)else path.lineTo(x,y)};drawPath(path,color,style=androidx.compose.ui.graphics.drawscope.Stroke(width=4f))}
        }}
        "segments"->{val items=block.optJSONArray("items")?:org.json.JSONArray();for(i in 0 until items.length()){val item=items.getJSONObject(i);BeaconText(item.optString("label")+" · "+item.optDouble("value")+block.optString("unit"));LinearProgressIndicator(progress={ (item.optDouble("value")/block.optDouble("total")).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())}}
        else->{val items=block.optJSONArray("items")?:org.json.JSONArray();for(i in 0 until items.length()){val item=items.getJSONObject(i);val text=when(kind){"kv"->item.optString("label")+" · "+item.optString("text");"list"->"• "+item.optString("text");"timeline"->item.optString("label")+" · "+item.optString("status")+" · "+java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.optLong("at_ms")));else->item.optString("label")+" · "+item.optString("status")};BeaconText(text)}}
    }
}

@Composable
private fun CountdownBlock(target: Long) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now by produceState(System.currentTimeMillis(), target, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = System.currentTimeMillis()
                val delay = CountdownDisplayPolicy.nextDelay(target, value) ?: break
                kotlinx.coroutines.delay(delay)
            }
        }
    }
    val minutes = CountdownDisplayPolicy.minutes(target, now)
    BeaconText(if (minutes == 0L) "已到期" else "约 ${minutes / 60} 小时 ${minutes % 60} 分钟后")
    BeaconText(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(target)))
}
