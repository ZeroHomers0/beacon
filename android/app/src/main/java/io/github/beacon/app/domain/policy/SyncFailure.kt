package io.github.beacon.app.domain.policy

import io.github.beacon.app.domain.model.SyncOutcome
import io.github.beacon.app.domain.receive.HttpRejectedException
import io.github.beacon.app.domain.receive.HttpTransientException
import io.github.beacon.app.domain.receive.SyncAuthenticationException
import io.github.beacon.app.domain.receive.SyncCancelledException
import io.github.beacon.app.domain.receive.SyncResponseException
import io.github.beacon.app.domain.receive.SyncStage

/** Only fixed text and typed HTTP metadata may cross into UI/persistent diagnostics. */
object SyncFailure {
    private fun certificateFailure(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(10).any {
            it is java.security.cert.CertificateException ||
                it is java.security.cert.CertPathValidatorException ||
                it is javax.net.ssl.SSLPeerUnverifiedException
        }

    fun outcome(error: Exception, storage: Boolean = false): SyncOutcome =
        when {
            error is SyncCancelledException -> SyncOutcome.STOPPED
            error is SyncAuthenticationException -> SyncOutcome.AUTH
            certificateFailure(error) -> SyncOutcome.TLS
            error is javax.net.ssl.SSLException -> SyncOutcome.TRANSIENT
            error is HttpRejectedException || error is SyncResponseException -> SyncOutcome.PROTOCOL
            storage -> SyncOutcome.STORAGE
            error is java.io.IOException -> SyncOutcome.TRANSIENT
            else -> SyncOutcome.PROTOCOL
        }

    fun describe(error: Exception, outcome: SyncOutcome): String {
        val stage =
            when (error) {
                is SyncAuthenticationException -> error.stage
                is HttpRejectedException -> error.stage
                is HttpTransientException -> error.stage
                is SyncResponseException -> error.stage
                else -> null
            }
        val location =
            when (stage) {
                SyncStage.CAPABILITIES -> "能力握手"
                SyncStage.MESSAGES -> "消息读取"
                SyncStage.ACK -> "向服务器确认接收"
                null -> "同步"
            }
        val reason =
            when (error) {
                is SyncAuthenticationException ->
                    "HTTP ${error.statusCode}：请检查接收凭证及反代是否保留 Authorization"
                is HttpRejectedException ->
                    when (error.statusCode) {
                        in 300..399 -> "HTTP ${error.statusCode}：服务器发生重定向；请填写最终 HTTPS 根地址并检查反代"
                        404 -> "HTTP 404：请检查域名根路径、反代后端与服务版本"
                        else -> "HTTP ${error.statusCode}：服务器拒绝请求，请检查协议与反代配置"
                    }
                is HttpTransientException ->
                    if (error.statusCode == 429) "HTTP 429：请求过于频繁，请稍后重试"
                    else "HTTP ${error.statusCode}：服务暂不可用，请检查服务与反代后端"
                is SyncResponseException -> "响应不是有效协议 JSON 或超过大小限制，请检查反代与服务版本"
                is java.net.UnknownHostException -> "域名解析失败，请检查 DNS 与网络"
                is java.net.SocketTimeoutException -> "连接或读取超时，请检查网络与服务器"
                is java.net.ConnectException -> "连接失败，请检查服务器端口与反代服务"
                is javax.net.ssl.SSLException ->
                    if (outcome == SyncOutcome.TLS) "证书校验失败，请检查证书链、域名和设备时间"
                    else "安全连接中断，将自动重试；请检查网络与代理"
                else ->
                    when (outcome) {
                        SyncOutcome.STOPPED -> "任务已停止"
                        SyncOutcome.STORAGE -> "本地存储未完成，请检查可用空间后重试"
                        SyncOutcome.TLS -> "证书校验失败，请检查证书链、域名和设备时间"
                        SyncOutcome.TRANSIENT -> "网络请求未完成，请稍后重试"
                        else -> "协议数据不兼容，请检查服务版本"
                    }
            }
        return "$location 未完成（${outcome.name}）：$reason；已保存的消息保留，接收确认稍后重试"
    }
}
