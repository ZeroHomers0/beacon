package io.github.beacon.app.domain.policy

import io.github.beacon.app.domain.model.MessageLinkAction
import io.github.beacon.app.domain.model.MessageLimits
import java.net.URI

/** Link-action invariants apply equally to reception, storage and archives. */
object MessageActionValidation {
    fun validate(actions: List<MessageLinkAction>) {
        require(actions.size <= MessageLimits.ACTION_COUNT)
        actions.forEach { action ->
            require(
                action.title.isNotBlank() && MessageLimits.textFits(action.title, 64)
            )
            require(action.title.none { Character.isISOControl(it) })
            require(MessageLimits.textFits(action.url, 2048))
            require(
                action.url.none {
                    Character.isISOControl(it) || it.isWhitespace() || Character.isSpaceChar(it)
                }
            )
            val uri =
                try {
                    URI(action.url)
                } catch (e: java.net.URISyntaxException) {
                    throw IllegalArgumentException("Invalid action URL", e)
                }
            require(uri.isAbsolute && uri.scheme.equals("https", ignoreCase = true))
            require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null)
            val host = requireNotNull(uri.host)
            if (!host.startsWith("[")) {
                val hostname = host.removeSuffix(".")
                require(hostname.length in 1..253)
                require(
                    hostname.split('.').all { label ->
                        label.length in 1..63 &&
                            label.first().isLetterOrDigit() &&
                            label.last().isLetterOrDigit() &&
                            label.all {
                                it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-'
                            }
                    }
                )
            }
            require(uri.port == -1 || uri.port in 1..65535)
            require(uri.rawAuthority != null && !uri.rawAuthority.endsWith(":"))
        }
    }

}
