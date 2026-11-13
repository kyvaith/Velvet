/*
 * SPDX-License-Identifier: MIT
 * Netflix manifest field mapping adapted from converter.py, CastagnaIT/plugin.video.netflix,
 * commit 7accc24cfbf2bf798c037996119e08e6334d64cb.
 * Copyright (C) 2017 Sebastian Golasch; 2018 Caphm. See root THIRD_PARTY_NOTICES.md.
 * MPEG-DASH generation, bounds checks and Media3 compatibility implemented for Velvet.
 */
package pl.kyvaith.velvet.playback

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID

internal data class ConvertedManifest(val mpd: String, val durationMs: Long, val subtitles: List<SubtitleTrack>)

/** Converts the actual licensed manifest response; there are no sample/fallback media URLs. */
internal object NetflixManifestConverter {
    private const val WV_UUID = "edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"

    fun convert(manifest: JSONObject, preferredLanguage: String): ConvertedManifest {
        if (manifest.optString("streamingType", "VOD") != "VOD") fail("Ta wersja odtwarzacza nie obsługuje transmisji Netflix na żywo.")
        val adBreaks = manifest.optJSONObject("adverts")?.optJSONArray("adBreaks")
        val adManifests = manifest.optJSONArray("auxiliaryManifests")?.objects().orEmpty()
        if ((adBreaks?.length() ?: 0) > 0 || adManifests.any { it.optBoolean("isAd") }) {
            fail("Ten materiał wymaga obsługi przerw reklamowych, której ta wersja odtwarzacza jeszcze nie zapewnia.")
        }
        val duration = manifest.optLong("duration", 0)
        if (duration <= 0) fail("Manifest Netflix nie zawiera prawidłowego czasu trwania materiału.")
        val videoTracks = manifest.optJSONArray("video_tracks")?.objects().orEmpty()
        val audioTracks = manifest.optJSONArray("audio_tracks")?.objects().orEmpty()
        if (videoTracks.isEmpty()) fail("Netflix nie udostępnił żadnej ścieżki obrazu dla tego urządzenia.")
        val globalDrm = videoTracks.firstNotNullOfOrNull { it.optJSONObject("drmHeader") }
        val xml = DashXml()
        xml.open("MPD", mapOf("xmlns" to "urn:mpeg:dash:schema:mpd:2011", "xmlns:cenc" to "urn:mpeg:cenc:2013",
            "type" to "static", "profiles" to "urn:mpeg:dash:profile:isoff-on-demand:2011",
            "minBufferTime" to "PT1.500S", "mediaPresentationDuration" to durationValue(duration)))
        xml.open("Period", mapOf("id" to manifest.optString("movieId", "0"), "start" to "PT0S", "duration" to durationValue(duration)))
        var adaptationId = 0
        var videoCount = 0
        for (track in videoTracks) {
            val streams = track.optJSONArray("streams")?.objects().orEmpty()
            if (streams.isEmpty()) continue
            val encrypted = track.optBoolean("hasDrmStreams", streams.any { it.optBoolean("isDrm") })
            val selected = streams.filter { it.optBoolean("isDrm", encrypted) == encrypted }
            if (selected.isEmpty()) continue
            xml.open("AdaptationSet", mapOf("id" to (adaptationId++).toString(), "mimeType" to "video/mp4",
                "contentType" to "video", "segmentAlignment" to "true"))
            if (encrypted) addProtection(xml, track.optJSONObject("drmHeader") ?: globalDrm,
                selected.first().optString("content_profile"), true)
            for (stream in selected) {
                val id = stream.optString("downloadable_id")
                val width = stream.optInt("res_w", 0)
                val height = stream.optInt("res_h", 0)
                if (id.isBlank() || width <= 0 || height <= 0) fail("Niekompletna reprezentacja obrazu w manifeście Netflix.")
                val attrs = linkedMapOf("id" to id, "width" to width.toString(), "height" to height.toString(),
                    "bandwidth" to bandwidth(stream).toString(), "codecs" to videoCodec(stream.optString("content_profile")))
                val frameRate = stream.optInt("framerate_value", 0)
                val frameScale = stream.optInt("framerate_scale", 1)
                if (frameRate > 0 && frameScale > 0) attrs["frameRate"] = "$frameRate/$frameScale"
                xml.open("Representation", attrs)
                xml.text("BaseURL", mediaUrl(stream))
                addSegmentBase(xml, stream)
                xml.close("Representation")
                videoCount++
            }
            xml.close("AdaptationSet")
        }
        if (videoCount == 0) fail("W manifeście nie ma odtwarzalnej reprezentacji obrazu.")

        for (track in audioTracks) {
            val streams = track.optJSONArray("streams")?.objects().orEmpty()
            if (streams.isEmpty()) continue
            val language = track.optString("language", "und")
            xml.open("AdaptationSet", mapOf("id" to (adaptationId++).toString(), "mimeType" to "audio/mp4",
                "contentType" to "audio", "lang" to language, "segmentAlignment" to "true"))
            xml.text("Label", track.optString("languageDescription", language))
            xml.empty("Role", mapOf("schemeIdUri" to "urn:mpeg:dash:role:2011", "value" to
                if (language.substringBefore('-') == preferredLanguage.substringBefore('-')) "main" else "alternate"))
            if (track.optString("trackType") == "ASSISTIVE") {
                xml.empty("Accessibility", mapOf("schemeIdUri" to "urn:mpeg:dash:role:2011", "value" to "description"))
            }
            val encrypted = track.optBoolean("hasDrmStreams", streams.any { it.optBoolean("isDrm") })
            if (encrypted) {
                val audioHeader = track.optJSONObject("drmHeader")
                addProtection(xml, audioHeader ?: globalDrm, streams.first().optString("content_profile"), audioHeader != null)
            }
            for (stream in streams) {
                val profile = stream.optString("content_profile")
                val codec = when {
                    profile.startsWith("ddplus") -> "ec-3"
                    profile.startsWith("dd-") -> "ac-3"
                    profile.startsWith("heaac") -> "mp4a.40.5"
                    profile.startsWith("aac") -> "mp4a.40.2"
                    else -> fail("Nieobsługiwany format ścieżki dźwiękowej w manifeście Netflix.")
                }
                xml.open("Representation", mapOf("id" to stream.get("downloadable_id").toString(),
                    "codecs" to codec, "bandwidth" to bandwidth(stream).toString()))
                val channels = when (stream.optString("channels", track.optString("channels", "2.0"))) {
                    "1.0" -> "1"; "2.0" -> "2"; "5.1" -> "6"; "7.1" -> "8"
                    else -> null
                }
                if (channels != null) xml.empty("AudioChannelConfiguration",
                    mapOf("schemeIdUri" to "urn:mpeg:dash:23003:3:audio_channel_configuration:2011", "value" to channels))
                xml.text("BaseURL", mediaUrl(stream))
                addSegmentBase(xml, stream)
                xml.close("Representation")
            }
            xml.close("AdaptationSet")
        }

        val subtitles = mutableListOf<SubtitleTrack>()
        for (track in manifest.optJSONArray("timedtexttracks")?.objects().orEmpty()) {
            if (track.optBoolean("isNoneTrack")) continue
            val downloadables = track.optJSONObject("ttDownloadables") ?: continue
            val profiles = downloadables.keys().asSequence().toList()
            val profile = profiles.firstOrNull { it.startsWith("webvtt") }
                ?: profiles.firstOrNull { it == "imsc1.1" }
                ?: profiles.firstOrNull { it == "simplesdh" } ?: continue
            val downloadable = downloadables.optJSONObject(profile) ?: continue
            val language = track.optString("language", "und")
            val forced = track.optBoolean("isForcedNarrative")
            val impaired = track.optString("trackType") == "ASSISTIVE"
            val trackId = track.optString("new_track_id", track.optString("id", "text$adaptationId"))
            val label = track.optString("languageDescription", language)
            val mime = if (profile.startsWith("webvtt")) "text/vtt" else "application/ttml+xml"
            xml.open("AdaptationSet", mapOf("id" to (adaptationId++).toString(), "lang" to language,
                "contentType" to "text", "mimeType" to mime))
            xml.text("Label", label)
            xml.empty("Role", mapOf("schemeIdUri" to "urn:mpeg:dash:role:2011", "value" to if (forced) "forced-subtitle" else "subtitle"))
            if (impaired) xml.empty("Accessibility", mapOf("schemeIdUri" to "urn:mpeg:dash:role:2011", "value" to "caption"))
            xml.open("Representation", mapOf("id" to trackId, "bandwidth" to "256"))
            xml.text("BaseURL", mediaUrl(downloadable))
            xml.close("Representation")
            xml.close("AdaptationSet")
            subtitles.add(SubtitleTrack(trackId, language, label, forced, impaired))
        }
        xml.close("Period")
        xml.close("MPD")
        return ConvertedManifest(xml.toString(), duration, subtitles)
    }

    private fun addProtection(xml: DashXml, header: JSONObject?, profile: String, includeKid: Boolean) {
        if (header == null) fail("Zaszyfrowany strumień nie zawiera danych inicjalizacji Widevine.")
        val bytes = header.optString("bytes")
        if (bytes.isBlank()) fail("Manifest nie zawiera danych PSSH wymaganych przez Widevine.")
        val keyId = header.optString("keyId")
        val protection = linkedMapOf("schemeIdUri" to "urn:mpeg:dash:mp4protection:2011", "value" to if (profile.contains("cbcs")) "cbcs" else "cenc")
        if (includeKid && keyId.isNotBlank()) {
            val decoded = MslEncoding.decode(keyId)
            if (decoded.size != 16) fail("Manifest zawiera nieprawidłowy identyfikator klucza DRM.")
            val buffer = ByteBuffer.wrap(decoded)
            protection["cenc:default_KID"] = UUID(buffer.long, buffer.long).toString()
        }
        xml.empty("ContentProtection", protection)
        xml.open("ContentProtection", mapOf("schemeIdUri" to "urn:uuid:$WV_UUID", "value" to "Widevine"))
        xml.text("cenc:pssh", MslEncoding.encode(normalizePssh(MslEncoding.decode(bytes))))
        xml.close("ContentProtection")
    }

    internal fun normalizePssh(data: ByteArray): ByteArray {
        if (data.isEmpty()) fail("Puste dane inicjalizacji Widevine.")
        if (data.size >= 32 && String(data, 4, 4, Charsets.US_ASCII) == "pssh") {
            if (ByteBuffer.wrap(data).int != data.size) fail("Nieprawidłowa długość bloku PSSH.")
            return data
        }
        // The service can provide either a complete PSSH box or its Widevine protobuf payload.
        val uuid = UUID.fromString(WV_UUID)
        return ByteBuffer.allocate(32 + data.size).putInt(32 + data.size).putInt(0x70737368)
            .putInt(0).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits)
            .putInt(data.size).put(data).array()
    }

    private fun addSegmentBase(xml: DashXml, stream: JSONObject) {
        val sidx = stream.optJSONObject("sidx") ?: return
        val offset = sidx.optLong("offset", -1)
        val size = sidx.optLong("size", -1)
        if (offset <= 0 || size <= 0 || offset > Long.MAX_VALUE - size) fail("Nieprawidłowy indeks segmentów w manifeście Netflix.")
        // HTTP/DASH ranges are inclusive; offset + size - 1 is the final index byte.
        xml.open("SegmentBase", mapOf("indexRange" to "$offset-${offset + size - 1}", "indexRangeExact" to "true"))
        xml.empty("Initialization", mapOf("range" to "0-${offset - 1}"))
        xml.close("SegmentBase")
    }

    internal fun videoCodec(profile: String): String = when {
        "h264" in profile -> {
            val match = Regex("h264(mpl|hpl)([0-9]{2})").find(profile)
                ?: fail("Nieobsługiwany profil H.264 w manifeście Netflix.")
            val profileId = if (match.groupValues[1] == "hpl") 100 else 77
            "avc1.%02x00%02x".format(Locale.ROOT, profileId, match.groupValues[2].toInt())
        }
        profile.startsWith("vp9-profile0-") -> "vp09.00.${vp9Level(profile)}.08"
        profile.startsWith("vp9-profile2-") -> "vp09.02.${vp9Level(profile)}.10"
        profile.startsWith("hevc-dv") -> "dvhe"
        profile.startsWith("hevc-") -> "hvc1"
        profile.startsWith("av1-") -> "av01.0.08M.08"
        else -> fail("Nieobsługiwany kodek wideo w manifeście Netflix.")
    }

    private fun vp9Level(profile: String): String = Regex("-L([0-9]{2})-").find(profile)?.groupValues?.get(1)
        ?: fail("Nieprawidłowy poziom profilu VP9 w manifeście Netflix.")

    private fun bandwidth(stream: JSONObject): Long {
        val rate = stream.optLong("bitrate", 0)
        if (rate <= 0 || rate > Int.MAX_VALUE / 1024) fail("Nieprawidłowy bitrate ścieżki Netflix.")
        return rate * 1024
    }

    internal fun mediaUrl(source: JSONObject): String {
        val urls = source.optJSONArray("urls")
        var url: String? = null
        if (urls != null) {
            for (index in 0 until urls.length()) {
                val candidate = urls.optJSONObject(index)?.optString("url") ?: (urls.opt(index) as? String)
                if (!candidate.isNullOrBlank()) { url = candidate; break }
            }
        }
        if (url.isNullOrBlank()) source.optJSONObject("downloadUrls")?.let { map ->
            val key = map.keys().asSequence().firstOrNull()
            if (key != null) url = map.optString(key)
        }
        val result = url ?: fail("Ścieżka Netflix nie ma adresu strumienia.")
        val uri = try { URI(result) } catch (_: Exception) { fail("Nieprawidłowy adres strumienia Netflix.") }
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) fail("Strumień Netflix nie udostępnia prawidłowego adresu HTTPS.")
        return result
    }

    private fun durationValue(ms: Long): String = "PT" + String.format(Locale.ROOT, "%.3f", ms / 1000.0) + "S"
    private fun fail(message: String): Nothing = throw NetflixPlaybackException(PlaybackStage.MANIFEST, message = message)
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

internal class DashXml {
    private val result = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
    fun open(tag: String, attrs: Map<String, String> = emptyMap()) { result.append('<').append(tag); attributes(attrs); result.append('>') }
    fun empty(tag: String, attrs: Map<String, String> = emptyMap()) { result.append('<').append(tag); attributes(attrs); result.append("/>") }
    fun close(tag: String) { result.append("</").append(tag).append('>') }
    fun text(tag: String, value: String) { open(tag); result.append(escape(value)); close(tag) }
    private fun attributes(attrs: Map<String, String>) { attrs.forEach { (name, value) -> result.append(' ').append(name).append("=\"").append(escape(value)).append('"') } }
    override fun toString(): String = result.toString()
    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
