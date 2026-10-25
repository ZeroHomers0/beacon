package io.github.beacon.app.widget

import android.content.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

/** Package-scoped invalidation works across the UI and reception processes without polling. */
object StateChanges {
    const val ACTION=io.github.beacon.app.core.TopicChanges.UI_CHANGED
    fun ui(context:Context)=io.github.beacon.app.core.TopicChanges.ui(context)
    fun data(context:Context,source:String,identities:List<String>?)=
        io.github.beacon.app.core.TopicChanges.data(context,source,identities)
}

/** A single suspended deadline for visible content; cancellation on STOP leaves no UI timer. */
@Composable internal fun ObserveStateDeadline(rows:List<org.json.JSONObject>,onChange:()->Unit){
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val latest by rememberUpdatedState(onChange)
    LaunchedEffect(lifecycle,rows){
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){
            val now=System.currentTimeMillis()
            val next=rows.flatMap{listOf(it.optLong("stale_at_ms"),it.optLong("expires_at_ms"))}.filter{it>now}.minOrNull()
            if(next!=null){kotlinx.coroutines.delay((next-now).coerceAtLeast(1));latest()}
        }
    }
}

@Composable
internal fun ObserveStateChanges(onChange:()->Unit){
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val latest by rememberUpdatedState(onChange)
    DisposableEffect(context,lifecycle){
        var registered=false
        val receiver=object:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){latest()}}
        fun start(){if(!registered){ContextCompat.registerReceiver(context,receiver,IntentFilter(StateChanges.ACTION),ContextCompat.RECEIVER_NOT_EXPORTED);registered=true;WidgetPerformance.observers.incrementAndGet()};StateRefreshReceiver.request(context);latest()}
        fun stop(){if(registered){context.unregisterReceiver(receiver);registered=false;WidgetPerformance.observers.decrementAndGet()}}
        val observer=LifecycleEventObserver{_,event->when(event){Lifecycle.Event.ON_START->start();Lifecycle.Event.ON_STOP->stop();else->Unit}}
        lifecycle.addObserver(observer)
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))start()
        onDispose{stop();lifecycle.removeObserver(observer)}
    }
}
