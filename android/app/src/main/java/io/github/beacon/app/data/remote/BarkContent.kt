package io.github.beacon.app.data.remote

import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.receive.ReceiveMessageValidation
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/** Bark-compatible AES-CBC/PKCS7; the key stays on the receiving device. */
object BarkContent {
    fun resolve(message: IncomingMessage, secret: Pair<String, String>?): IncomingMessage {
        if (message.bark.isEmpty()) return message
        val metadata = JSONObject(message.bark)
        validate(metadata)
        if (!metadata.has("ciphertext")) return message
        if (secret == null)
            return message.copy(title = "加密消息", body = "请在设置 → 服务器配置 → Bark 消息解密中配置密钥", priority = 2)
        return runCatching {
            val key = secret.first.toByteArray(Charsets.UTF_8)
            val iv = metadata.optString("iv").ifEmpty { secret.second }.toByteArray(Charsets.UTF_8)
            require(key.size in listOf(16, 24, 32) && iv.size == 16)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val ciphertext = Base64.getDecoder().decode(metadata.getString("ciphertext"))
            require(ciphertext.size in 16..65552)
            val bytes = cipher.doFinal(ciphertext)
            val text =
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString()
            val content = JSONObject(text)
            for (name in listOf("title", "subtitle", "body", "image", "icon", "url", "sound")) if (
                content.has(name)
            )
                require(content.get(name) is String)
            val presentation = JSONObject()
            for (name in listOf("image", "icon", "url", "sound")) if (content.has(name))
                presentation.put(name, content.getString(name))
            val result =
                message.copy(
                    title = content.optString("title"),
                    subtitle = content.optString("subtitle"),
                    body = content.getString("body"),
                    bark = presentation.toString(),
                )
            validate(presentation)
            ReceiveMessageValidation.validate(result)
            result
        }
            .getOrElse { message.copy(title = "加密消息", body = "解密失败，请检查 Bark 加密密钥与默认 IV", priority = 2) }
    }

    fun validate(metadata: JSONObject) {
        require(metadata.toString().toByteArray(Charsets.UTF_8).size <= 100000)
        require(
            metadata.keys().asSequence().all {
                it in setOf("image", "icon", "url", "sound", "ciphertext", "iv")
            }
        )
        metadata.keys().forEach { require(metadata.get(it) is String) }
        if (metadata.has("ciphertext")) {
            val cipher = Base64.getDecoder().decode(metadata.getString("ciphertext"))
            require(cipher.size in 16..65552 && cipher.size % 16 == 0)
        }
        if (metadata.has("iv"))
            require(metadata.getString("iv").toByteArray(Charsets.UTF_8).size == 16)
        for (name in listOf("image", "icon", "url")) if (metadata.has(name)) {
            val value = metadata.getString(name)
            val uri = java.net.URI(value)
            require(
                value.length <= 2048 &&
                    uri.scheme == "https" &&
                    !uri.host.isNullOrEmpty() &&
                    uri.userInfo == null
            )
        }
        if (metadata.has("sound")) require(metadata.getString("sound").length <= 128)
    }
}
