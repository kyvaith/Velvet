// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet.playback

import android.os.Build
import org.json.JSONArray
import java.net.URLEncoder

/**
 * Independent implementation of the native Android protocol fields, not Netflix application
 * code. Version fields select the API contract observed in the cited client snapshot.
 * Device fields and the MSL identity always describe this installation's own Android device.
 * See docs/NFTOKEN_0_1_9.md for sources and the limits of this experimental profile.
 */
internal object NativeAndroidProfile {
    const val ID = "native_nftoken_v3"
    const val APP_VERSION = "8.143.5"
    const val APP_BUILD = "52400"
    const val TOKEN_ENDPOINT = "https://android13.prod.ftl.netflix.com/graphql"
    const val GRAPHQL_ENDPOINT = "https://android.prod.cloud.netflix.com/graphql"
    const val CONFIG_ENDPOINT = "https://android.prod.cloud.netflix.com/nq/androidui/samurai/v1/config"
    const val HANDSHAKE_ENDPOINT = "https://android.prod.ftl.netflix.com/nq/androidui/pbo_license/~1.0.0/router"
    const val PLAYBACK_ENDPOINT = "https://android.prod.cloud.netflix.com/nq/androidui/samurai/~8.143.0/api"
    const val PLAYBACK_ROUTING = "{\"path\":\"/nq/android/playback/~1.0.0/router\"}"

    data class Device(
        val manufacturer: String,
        val model: String,
        val release: String,
        val api: Int,
        val board: String,
        val device: String,
        val display: String,
        val hardware: String,
        val boardPlatform: String = "",
    ) {
        override fun toString(): String = "NativeAndroidDevice(metadata)"

        companion object {
            fun current() = Device(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty(),
                Build.VERSION.RELEASE.orEmpty(), Build.VERSION.SDK_INT, Build.BOARD.orEmpty(),
                Build.DEVICE.orEmpty(), Build.DISPLAY.orEmpty(), Build.HARDWARE.orEmpty(), readBoardPlatform())

            // The native manifest's hardware field is ro.board.platform. Android may deny
            // this hidden property API; absence must not be replaced with a different field.
            private fun readBoardPlatform(): String = try {
                Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                    .invoke(null, "ro.board.platform") as? String ?: ""
            } catch (_: Exception) { "" }
        }
    }

    fun userAgent(device: Device, locale: String): String =
        "com.netflix.mediaclient/$APP_BUILD (Linux; U; Android ${headerField(device.release)}; " +
            "${headerField(locale.replace('-', '_'))}; ${headerField(device.model)}; Build/${headerField(device.display)})"

    /** No account/profile identifier, cookies or tokens are part of these device-only headers. */
    fun headers(device: Device): Map<String, String> = mapOf(
        "X-Netflix.appVer" to APP_VERSION,
        "X-Netflix.clientType" to "samurai",
        "X-Netflix.androidApi" to device.api.toString(),
        "X-Netflix.deviceFormFactor" to "PHONE",
        "X-Netflix.Request.Client.Context" to "{\"appState\":\"foreground\"}",
        "X-Netflix.msl-header-friendly-client" to "true",
    )

    /**
     * Native config uses a form encoded multimap inside MSL. Repeated path keys must survive.
     * Values remain inside the encrypted application payload, not the HTTP URL or diagnostics.
     */
    fun configPayload(device: Device, systemId: String, locale: String): ByteArray {
        require(device.api >= 28 && systemId.matches(Regex("[1-9][0-9]{0,9}")))
        val fields = mutableListOf<Pair<String, String>>()
        fields += "method" to "get"
        configPaths().forEach { fields += "path" to it.toString() }
        fields += listOf(
            "responseFormat" to "json", "progressive" to "false", "pathFormat" to "hierarchical",
            "appType" to "samurai", "netflixClientPlatform" to "androidNative", "platform" to "android",
            "appVer" to APP_BUILD, "appVersion" to APP_VERSION, "api" to device.api.toString(),
            "deviceLocale" to locale, "languages" to locale,
            "mnf" to device.manufacturer, "model" to device.model,
            "devmod" to "${device.manufacturer}_${device.model}",
            "osBoard" to device.board, "osDevice" to device.device, "osDisplay" to device.display,
            "chipsetHardware" to device.hardware, "roBspVer" to device.display,
            "sid" to systemId, "ctgr" to "phone", "ffbc" to "phone",
            "clientAppState" to "FOREGROUND", "clientAppVersionState" to "NORMAL",
        )
        return fields.joinToString(separator = "&", postfix = "&") { (name, value) ->
            URLEncoder.encode(name, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }.toByteArray(Charsets.UTF_8)
    }

    // Native authenticated FetchConfigData paths from the cited 8.143 protocol snapshot.
    // Each array is one path parameter, including the nested streaming-config keyset.
    private fun configPaths(): List<JSONArray> = listOf(
        JSONArray().put("deviceConfig"), JSONArray().put("hendrixConfig"),
        JSONArray().put("networkScoreConfig"), JSONArray().put("accountConfig"),
        JSONArray().put("featureConfig"),
        JSONArray().put(JSONArray().put("streamingConfig2")).put("8505"),
        JSONArray().put("synth"),
    )

    private fun headerField(value: String): String = value.take(128).map {
        if (it.code in 0x20..0x7e && it != '\r' && it != '\n') it else '_'
    }.joinToString("").ifBlank { "unknown" }
}
