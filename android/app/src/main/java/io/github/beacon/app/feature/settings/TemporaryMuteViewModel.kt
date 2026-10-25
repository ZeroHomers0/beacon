package io.github.beacon.app.feature.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.ipc.RuntimeConnection
import io.github.beacon.app.ipc.RuntimeControlClient
import io.github.beacon.app.runtime.RuntimeProbeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class TemporaryMuteUiState(val loaded:Boolean=false,val busy:Boolean=false,val failed:Boolean=false,
    val value:MuteSnapshot=MuteSnapshot())

/** UI owns no database: all reads/writes use the reception process on the existing IPC executor. */
internal class TemporaryMuteViewModel(application:Application):AndroidViewModel(application) {
    private val mutable=MutableStateFlow(TemporaryMuteUiState())
    val state=mutable.asStateFlow()
    private var scope=MuteScope.GLOBAL
    private var visible=false
    private val connection=RuntimeConnection(RuntimeProbeService::class.java,
        onStatus={status,_->if(visible&&status.kind==OperationKind.CONNECTION_LOST)
            mutable.value=mutable.value.copy(busy=false,failed=true)},
        onConnected={request(null)})
    fun start(value:MuteScope){scope=value;visible=true;mutable.value=mutable.value.copy(busy=true,failed=false);connection.start(getApplication())}
    fun stop(){visible=false;connection.stop(OperationStatus.diagnostic(""));mutable.value=mutable.value.copy(busy=false)}
    fun retry(){start(scope)}
    fun apply(hours:Int){if(!mutable.value.busy)request(TemporaryMute.until(hours,System.currentTimeMillis()))}
    private fun request(until:Long?){
        mutable.value=mutable.value.copy(busy=true,failed=false)
        if(!connection.execute(operation={RuntimeControlClient(it).temporaryMute(scope,until)},
            onResult={mutable.value=TemporaryMuteUiState(loaded=true,value=it)},
            onError={mutable.value=mutable.value.copy(busy=false,failed=true)}))
            mutable.value=mutable.value.copy(busy=false,failed=true)
    }
    override fun onCleared(){connection.close()}
}
