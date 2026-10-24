package io.github.beacon.app.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.beacon.app.BuildConfig
import io.github.beacon.app.domain.model.BoundDevice
import io.github.beacon.app.domain.model.RotatedDeviceCredentials
import io.github.beacon.app.domain.model.TransportSettings
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

class TransportSecretStore(
    context: Context,
    preferenceName: String = "transport_secret",
    private val keyAlias: String = "beacon_transport_v1",
) {
    private val prefs = context.getSharedPreferences(preferenceName, 0)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            keyAlias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }
            .generateKey()
    }

    /** Remove only local transport credentials; history and Android Keystore identity remain. */
    fun clear() {
        check(prefs.edit().remove("data").remove("iv").commit())
    }

    fun isConfigured(): Boolean = prefs.contains("data") && prefs.contains("iv")

    fun save(settings: TransportSettings) {
        settings.validate(BuildConfig.DEBUG)
        val old = if (isConfigured()) runCatching { unseal() }.getOrNull() else null
        val value =
            if (
                old?.optString("endpoint") == settings.endpoint &&
                    old.optString("token") == settings.token
            )
                old
            else JSONObject().put("endpoint", settings.endpoint).put("token", settings.token)
        seal(value)
    }

    fun saveBound(device: BoundDevice) {
        device.settings.validate(BuildConfig.DEBUG)
        seal(
            JSONObject()
                .put("endpoint", device.settings.endpoint)
                .put("token", device.settings.token)
                .put("push_key", device.pushKey)
                .put("device_id", device.deviceId)
                .put("server_id", device.serverId)
        )
    }

    /** Verify before a single atomic encrypted preference commit; no new device is created. */
    fun rotate(credentials: RotatedDeviceCredentials, verify: (BoundDevice) -> Unit) {
        credentials.validate()
        val saved = unseal()
        require(
            saved.optString("server_id") == credentials.serverId &&
                saved.optString("device_id") == credentials.deviceId
        ) {
            "轮换结果不属于当前绑定设备；手工配置请先使用绑定码绑定"
        }
        require(
            saved.getString("token") != credentials.receiveToken &&
                saved.optString("push_key") != credentials.pushKey
        ) {
            "这组凭证已保存，请使用服务器最新轮换结果"
        }
        val device =
            BoundDevice(
                TransportSettings(saved.getString("endpoint"), credentials.receiveToken),
                credentials.serverId,
                credentials.deviceId,
                credentials.pushKey,
            )
        device.settings.validate(BuildConfig.DEBUG)
        verify(device)
        saveBound(device)
    }

    /** Read only on an explicit copy action; credentials never enter RuntimeState. */
    fun pushKey(): String {
        val body = unseal()
        val key = body.optString("push_key")
        check(key.matches(Regex("[0-9a-f]{64}")))
        return key
    }

    fun saveBarkSecret(key: String, iv: String) {
        require(
            key.toByteArray(Charsets.UTF_8).size in listOf(16, 24, 32) &&
                iv.toByteArray(Charsets.UTF_8).size == 16
        )
        seal(unseal().put("bark_key", key).put("bark_iv", iv))
    }

    fun clearBarkSecret() {
        val body = unseal()
        body.remove("bark_key")
        body.remove("bark_iv")
        seal(body)
    }

    fun barkSecret(): Pair<String, String>? {
        val body = unseal()
        return if (body.has("bark_key")) body.getString("bark_key") to body.getString("bark_iv")
        else null
    }

    private fun seal(value: JSONObject) {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val body = value.toString().toByteArray(Charsets.UTF_8)
        val sealed = cipher.doFinal(body)
        check(
            prefs
                .edit()
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("data", Base64.encodeToString(sealed, Base64.NO_WRAP))
                .commit()
        )
    }

    /** Bound identity is required for online revocation; manual token settings are insufficient. */
    fun loadBound(): BoundDevice {
        val body = unseal()
        val device =
            BoundDevice(
                TransportSettings(body.getString("endpoint"), body.getString("token")),
                body.optString("server_id"),
                body.optString("device_id"),
                body.optString("push_key"),
            )
        device.settings.validate(BuildConfig.DEBUG)
        require(
            listOf(device.serverId, device.deviceId, device.pushKey).all {
                it.matches(Regex("[0-9a-f]{64}"))
            }
        )
        return device
    }

    fun load(): TransportSettings {
        val body = unseal()
        return TransportSettings(body.getString("endpoint"), body.getString("token")).also {
            it.validate(BuildConfig.DEBUG)
        }
    }

    private fun unseal(): JSONObject {
        val data = prefs.getString("data", null) ?: error("Transport not configured")
        val iv = prefs.getString("iv", null) ?: error("Incomplete transport configuration")
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
                )
            }
        return JSONObject(
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        )
    }
}
