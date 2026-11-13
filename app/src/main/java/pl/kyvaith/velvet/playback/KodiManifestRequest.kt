/*
 * SPDX-License-Identifier: MIT
 * Android manifest-v2 contract adapted from MSLHandler._build_manifest_v2,
 * atrHusK/plugin.video.netflix commit bf91b55089b06bd892eb6cd1457b3a87341fb5bf.
 * Copyright (C) 2017 Sebastian Golasch; 2017 Trummerjo.
 * See THIRD_PARTY_NOTICES.md. Pure Kotlin request builder: Velvet contributors.
 */
package pl.kyvaith.velvet.playback

import org.json.JSONArray
import org.json.JSONObject

/** The Android Kodi contract requests a standard manifest before opening the movie DRM session. */
internal object KodiManifestRequest {
    fun create(
        videoId: String,
        xid: String,
        context: PlaybackContext,
        profiles: List<String>,
        hdcp: String,
        clientTimeSeconds: Long = System.currentTimeMillis() / 1000,
    ): JSONObject {
        val profileArray = JSONArray(profiles)
        val hdcpVersions = JSONArray()
        val hdcpLevel = Regex("([0-9]+\\.[0-9]+)").find(hdcp)?.groupValues?.get(1)?.toDoubleOrNull()
        // Match Kodi without its user HDCP override: only a reported level >= 2.2 advertises 2.2.
        if (hdcpLevel != null && hdcpLevel >= 2.2) hdcpVersions.put("2.2")
        return JSONObject()
            .put("type", "standard")
            .put("manifestVersion", "v2")
            .put("viewableId", videoId.toLong())
            .put("profiles", profileArray)
            .put("flavor", "PRE_FETCH")
            .put("drmType", "widevine")
            .put("drmVersion", 25)
            .put("usePsshBox", true)
            .put("isBranching", false)
            .put("useHttpsStreams", true)
            .put("supportsUnequalizedDownloadables", true)
            .put("imageSubtitleHeight", 1080)
            .put("uiVersion", context.uiVersion)
            .put("uiPlatform", "SHAKTI")
            .put("clientVersion", context.clientVersion)
            .put("platform", context.browserVersion)
            .put("osVersion", context.osVersion)
            .put("osName", context.osName)
            .put("supportsPreReleasePin", true)
            .put("supportsWatermark", true)
            .put("showAllSubDubTracks", false)
            .put("videoOutputInfo", JSONArray().put(JSONObject()
                .put("type", "DigitalVideoOutputDescriptor")
                .put("outputType", "unknown")
                .put("supportedHdcpVersions", hdcpVersions)
                .put("isHdcpEngaged", hdcpVersions.length() > 0)))
            .put("titleSpecificData", JSONObject().put(videoId, JSONObject().put("unletterboxed", true)))
            .put("preferAssistiveAudio", false)
            .put("isUIAutoPlay", false)
            .put("isNonMember", false)
            .put("desiredVmaf", "plus_lts")
            .put("desiredSegmentVmaf", "plus_lts")
            .put("requestSegmentVmaf", false)
            .put("supportsPartialHydration", false)
            .put("contentPlaygraph", JSONArray().put("start"))
            .put("supportsAdBreakHydration", true)
            .put("liveMetadataFormat", "INDEXED_SEGMENT_TEMPLATE")
            .put("useBetterTextUrls", true)
            .put("profileGroups", JSONArray().put(JSONObject().put("name", "default").put("profiles", profileArray)))
            // Kodi's Android path sends these empty pre-initialization fields. The placeholder
            // belongs only to this manifest request; license requests use the player's real SID.
            .put("challenge", "")
            .put("challenges", JSONObject().put("default", JSONArray().put(JSONObject()
                .put("drmSessionId", "session")
                .put("clientTime", clientTimeSeconds)
                .put("challengeBase64", ""))))
            .put("licenseType", "standard")
            // Unlike Kodi's Linux path, Android puts xid at the root of the manifest parameters.
            .put("xid", xid)
    }
}
