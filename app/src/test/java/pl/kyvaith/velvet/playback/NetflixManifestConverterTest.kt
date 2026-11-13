package pl.kyvaith.velvet.playback

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

class NetflixManifestConverterTest {
    @Test fun `MPD preserves signed CDN query ampersands and inclusive byte ranges`() {
        val converted = NetflixManifestConverter.convert(fixture(), "pl-PL")
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(converted.mpd.toByteArray()))
        assertEquals("https://cdn.example.test/video?first=1&second=2", document.getElementsByTagName("BaseURL").item(0).textContent)
        assertEquals("100-149", document.getElementsByTagName("SegmentBase").item(0).attributes.getNamedItem("indexRange").nodeValue)
        assertEquals("0-99", document.getElementsByTagName("Initialization").item(0).attributes.getNamedItem("range").nodeValue)
        assertEquals("PT123.456S", document.documentElement.getAttribute("mediaPresentationDuration"))
        assertEquals(123456L, converted.durationMs)
        assertEquals(1, converted.subtitles.size)
        assertTrue(converted.subtitles.single().forced)
    }

    @Test fun `DRM protection and actual KID survive conversion`() {
        val mpd = NetflixManifestConverter.convert(fixture(), "pl").mpd
        assertTrue(mpd.contains("cenc:default_KID=\"00000000-0000-0000-0000-000000000000\""))
        assertTrue(mpd.contains("urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"))
        assertTrue(mpd.contains("<cenc:pssh>"))
        assertFalse(mpd.contains("widevine:license"))
        assertTrue(mpd.contains("avc1.640028"))
    }

    @Test fun `native IMSC subtitles become TTML tracks with the signed download URL preserved`() {
        val manifest = fixture()
        val track = manifest.getJSONArray("timedtexttracks").getJSONObject(0)
        track.put("ttDownloadables", JSONObject().put("imsc1.1", JSONObject()
            .put("downloadUrls", JSONObject().put("cdn", "https://cdn.example.test/sub.ttml?a=1&b=2"))))
        val converted = NetflixManifestConverter.convert(manifest, "pl-PL")
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(converted.mpd.toByteArray()))
        val sets = document.getElementsByTagName("AdaptationSet")
        val textSet = (0 until sets.length).map { sets.item(it) }
            .single { it.attributes.getNamedItem("contentType").nodeValue == "text" }
        assertEquals("application/ttml+xml", textSet.attributes.getNamedItem("mimeType").nodeValue)
        assertTrue(textSet.textContent.contains("https://cdn.example.test/sub.ttml?a=1&b=2"))
        assertEquals("pl", converted.subtitles.single().language)
        assertTrue(converted.subtitles.single().forced)
    }

    @Test fun `protected streams without DRM initialization fail rather than playing a preview`() {
        val manifest = fixture()
        manifest.getJSONArray("video_tracks").getJSONObject(0).remove("drmHeader")
        fails { NetflixManifestConverter.convert(manifest, "pl") }
    }

    @Test fun `ad plans are rejected rather than silently stripping ad periods`() {
        val manifest = fixture().put("adverts", JSONObject().put("adBreaks", JSONArray().put(JSONObject().put("start", 100))))
        val failure = fails { NetflixManifestConverter.convert(manifest, "pl") }
        assertTrue(failure.message!!.contains("reklam"))
    }

    @Test fun `live manifests and invalid ranges are rejected`() {
        fails { NetflixManifestConverter.convert(fixture().put("streamingType", "LIVE"), "pl") }
        val manifest = fixture()
        manifest.getJSONArray("video_tracks").getJSONObject(0).getJSONArray("streams").getJSONObject(0)
            .put("sidx", JSONObject().put("offset", 0).put("size", 10))
        fails { NetflixManifestConverter.convert(manifest, "pl") }
    }

    @Test fun `unsafe media URL schemes are rejected`() {
        fails { NetflixManifestConverter.mediaUrl(JSONObject().put("urls", JSONArray().put(JSONObject().put("url", "http://cdn.example.test/file")))) }
        fails { NetflixManifestConverter.mediaUrl(JSONObject().put("urls", JSONArray().put(JSONObject().put("url", "file:///private/file")))) }
    }

    @Test fun `VP9 codec level is derived from the actual requested representation`() {
        assertEquals("vp09.00.31.08", NetflixManifestConverter.videoCodec("vp9-profile0-L31-dash-cenc"))
        assertEquals("avc1.4d001f", NetflixManifestConverter.videoCodec("playready-h264mpl31-dash"))
    }

    @Test fun `complete PSSH boxes are preserved and protobuf payloads wrapped once`() {
        val payload = byteArrayOf(0x12, 0x10) + ByteArray(16)
        val box = NetflixManifestConverter.normalizePssh(payload)
        assertEquals(32 + payload.size, box.size)
        assertArrayEquals(box, NetflixManifestConverter.normalizePssh(box))
    }

    private fun fixture(): JSONObject {
        val stream = JSONObject().put("downloadable_id", "video1").put("res_w", 1920).put("res_h", 1080)
            .put("bitrate", 2000).put("content_profile", "playready-h264hpl40-dash").put("isDrm", true)
            .put("framerate_value", 24000).put("framerate_scale", 1001)
            .put("urls", JSONArray().put(JSONObject().put("url", "https://cdn.example.test/video?first=1&second=2")))
            .put("sidx", JSONObject().put("offset", 100).put("size", 50))
        val header = JSONObject().put("keyId", MslEncoding.encode(ByteArray(16)))
            .put("bytes", MslEncoding.encode(byteArrayOf(0x12, 0x10) + ByteArray(16)))
        val video = JSONObject().put("hasDrmStreams", true).put("drmHeader", header).put("streams", JSONArray().put(stream))
        val text = JSONObject().put("id", "sub1").put("language", "pl").put("languageDescription", "Polski & English")
            .put("isForcedNarrative", true).put("ttDownloadables", JSONObject().put("webvtt-lssdh-ios8",
                JSONObject().put("downloadUrls", JSONObject().put("cdn", "https://cdn.example.test/sub.vtt"))))
        return JSONObject().put("movieId", 123).put("duration", 123456).put("video_tracks", JSONArray().put(video))
            .put("audio_tracks", JSONArray()).put("timedtexttracks", JSONArray().put(text))
    }

    private fun fails(block: () -> Unit): NetflixPlaybackException {
        try { block(); fail("Expected invalid manifest to fail") } catch (expected: NetflixPlaybackException) { return expected }
        throw AssertionError()
    }
}
