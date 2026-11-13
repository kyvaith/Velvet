/*
 * SPDX-License-Identifier: MIT
 * Playback HTTP profile adapted from resources/lib/common/device_utils.py,
 * atrHusK/plugin.video.netflix bf91b55089b06bd892eb6cd1457b3a87341fb5bf.
 * Copyright (C) 2017 Sebastian Golasch; 2020 Stefano Gottardo.
 * See THIRD_PARTY_NOTICES.md.
 */
package pl.kyvaith.velvet.playback

/**
 * The pinned Kodi ARM64 playback profile. It selects protocol behavior; Velvet does not run
 * Chrome OS. Kodi independently overrides the MSL User-Agent after website login. Keep the
 * server-supplied player/build/browser metadata and the catalog's browser ESN in their owner.
 */
internal object KodiAndroidPlaybackProfile {
    const val ID = "kodi_android_l1_v1"
    const val USER_AGENT = "Mozilla/5.0 (X11; CrOS aarch64 15183.69.0) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36"
}
