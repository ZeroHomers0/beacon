package io.github.beacon.app.domain.policy

import java.net.URI

object PushExamples {
    fun create(address: String, powershell: Boolean): String {
        validate(address)
        val url = address.trimEnd('/') + "/Beacon/Hello%20from%20Beacon"
        return if (powershell) "Invoke-RestMethod -Method Get -Uri '" + url.replace("'", "''") + "'"
        else "curl --fail-with-body '" + url.replace("'", "'\"'\"'") + "'"
    }

    private fun validate(address: String) {
        val uri = URI(address)
        require(
            uri.scheme in listOf("https", "http") &&
                uri.host != null &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null
        )
        require(
            address.length <= 2048 &&
                uri.path.substringAfterLast('/').matches(Regex("[0-9a-f]{64}"))
        )
    }

    fun json(address: String, powershell: Boolean): String {
        validate(address)
        val key = URI(address).path.substringAfterLast('/')
        val endpoint = address.substringBeforeLast('/') + "/push"
        return if (powershell)
            "\$payload = @{ device_key = '$key'; title = 'Beacon'; subtitle = 'JSON example'; body = 'Hello from Beacon' } | ConvertTo-Json\nInvoke-RestMethod -Method Post -Uri '" +
                endpoint.replace("'", "''") +
                "' -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes(\$payload))"
        else
            "curl --fail-with-body -H 'Content-Type: application/json' --data '{\"device_key\":\"$key\",\"title\":\"Beacon\",\"subtitle\":\"JSON example\",\"body\":\"Hello from Beacon\"}' '" +
                endpoint.replace("'", "'\"'\"'") +
                "'"
    }
}
