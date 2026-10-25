package io.github.beacon.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.LocalActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons

/** Explicit immediate command in the existing settings/menu system, not a second save form. */
@Composable
fun TemporaryMuteAction(scope:MuteScope,label:String=stringResource(R.string.mute_title),enabled:Boolean=true){
    var open by rememberSaveable(scope){mutableStateOf(false)}
    SettingsRow(label,stringResource(R.string.mute_action_hint),BeaconIcons.QuietHours,
        iconTint=androidx.compose.material3.MaterialTheme.colorScheme.onSurface,enabled=enabled,onClick={open=true})
    if(open)TemporaryMuteDialog(scope,label){open=false}
}

@Composable
fun TemporaryMuteDialog(scope:MuteScope,title:String,onDismiss:()->Unit){
    val owner=requireNotNull(LocalActivity.current) as ViewModelStoreOwner
    val model=remember(owner,scope){ViewModelProvider(owner)["mute:${scope.kind}:${scope.source}:${scope.identity}",TemporaryMuteViewModel::class.java]}
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle,scope){
        val observer=LifecycleEventObserver{_,event->when(event){
            Lifecycle.Event.ON_START->model.start(scope)
            Lifecycle.Event.ON_STOP->model.stop()
            else->Unit
        }}
        lifecycle.addObserver(observer)
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))model.start(scope)
        onDispose{lifecycle.removeObserver(observer);model.stop()}
    }
    var now by remember{mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(lifecycle,state.value){
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){
            while(true){
                now=System.currentTimeMillis()
                val next=listOf(state.value.localUntilMs,state.value.globalUntilMs).filter{it>now}.minOrNull()?:break
                kotlinx.coroutines.delay((next-now).coerceAtLeast(1))
            }
        }
    }
    val current=if(state.value.localUntilMs>now)stringResource(R.string.mute_until,
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(state.value.localUntilMs)))
        else stringResource(R.string.mute_not_active)
    BeaconAlertDialog(onDismissRequest={if(!state.busy)onDismiss()},title={BeaconText(title)},text={
        Column(verticalArrangement=Arrangement.spacedBy(BeaconUiMetrics.SectionGap)){
            SettingsHint(stringResource(R.string.mute_explanation))
            if(state.loaded){
                BeaconDropdownPreference(stringResource(R.string.mute_duration),listOf(current,
                    stringResource(R.string.mute_restore),stringResource(R.string.mute_one_hour),
                    stringResource(R.string.mute_eight_hours),stringResource(R.string.mute_morning)),0,enabled=!state.busy){index->
                    if(index>0)model.apply(listOf(0,0,1,8,-1)[index])
                }
                if(scope.kind!=MuteKind.GLOBAL&&state.value.globalUntilMs>now)SettingsHint(stringResource(R.string.mute_global_still_active))
            }else if(!state.failed)SettingsHint(stringResource(R.string.settings_data_loading))
            if(state.failed){
                SettingsHint(stringResource(R.string.mute_failed))
                BeaconTextButton(onClick=model::retry,enabled=!state.busy){BeaconText(stringResource(R.string.action_retry))}
            }
        }
    },confirmButton={BeaconButton(onClick=onDismiss,enabled=!state.busy){BeaconText(stringResource(R.string.action_close))}})
}
