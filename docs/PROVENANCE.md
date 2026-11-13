# Provenance and implementation boundaries

Velvet is an independent Kotlin implementation of a functional Android client for the Netflix service. Its original code is MIT-licensed. Selected protocol and catalog mappings were adapted from MIT-licensed community projects with their notices retained. Runtime dependencies retain their own licenses.

## What the project claims

Velvet is not Netflix's official application and is not affiliated with, sponsored by, endorsed by, or maintained by Netflix. It requires the user's own valid Netflix account, active subscription, and entitlement to the chosen content. Its license does not grant rights to Netflix's service, trademarks, catalog artwork, videos, or other proprietary materials.

The Velvet APK uses the device's Widevine implementation. It contains no official Netflix application code, videos, private device credentials, content decryption keys, or CDM. A public Widevine service certificate, credited below, is not a private device certificate or a content key.

## Open-source sources and adaptations

| Source | Pinned revision / role |
| --- | --- |
| [CastagnaIT/plugin.video.netflix](https://github.com/CastagnaIT/plugin.video.netflix/tree/7accc24cfbf2bf798c037996119e08e6334d64cb) | `7accc24c…`; MIT protocol, catalog, and manifest mappings |
| [atrHusK/plugin.video.netflix](https://github.com/atrHusK/plugin.video.netflix/tree/bf91b55089b06bd892eb6cd1457b3a87341fb5bf) | `bf91b550…`; MIT Android MSL exchange, authentication, and identity-field mappings |
| [Breezyslasher/plugin.video.netflix](https://github.com/Breezyslasher/plugin.video.netflix/tree/e888205306e9c3beef4652ca4fb4d2c5057de532) | `e8882053…`; MIT GraphQL operation and catalog-field mappings |
| [jankofron/plugin.video.netflix](https://github.com/jankofron/plugin.video.netflix/commit/c41d488658ce76994a1516c28456c4200bd83a7b) | H.264 PRK-QC profile exclusion |
| [CastagnaIT player.py](https://github.com/CastagnaIT/plugin.video.netflix/blob/de0db546d9b8ba8d79dbc08fe088b91fc3bcacb7/resources/lib/navigation/player.py) | Public Widevine service certificate, unchanged, from an MIT-licensed file |
| [Netflix-NFToken-Generator](https://github.com/mehedidec/Netflix-NFToken-Generator/tree/50cad4088efb4f0b7166508bbda2963655dfd97b) | MIT endpoint, persisted-operation identifier, and token-response mapping reference |

Complete copyright and permission notices are reproduced in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md). The attributed Kodi adaptations remain attributed even though the active manifest/license transport now uses the native Android contract.

## Playback error classification

An earlier error-classification investigation also inspected the public Netflix-hosted [player artifact `cadmium-playercore-6.0065.492.911.js`](https://assets.nflxext.com/player/html/ffe/cadmium-playercore-6.0065.492.911.js) to identify the 205032 user/entity binding error namespace. The inspected artifact's SHA-256 was `8af8272e0808fda1651786367cf0060c313167683b8b762158911d38dd1a9abe`. It is not distributed with Velvet. Its mapping was a reference observation, not a grant of source redistribution rights.

## Platform and structural references

- [Android MediaDrm.CryptoSession](https://developer.android.com/reference/android/media/MediaDrm.CryptoSession) and the referenced [Kodi Android bridge](https://github.com/xbmc/xbmc/blob/313acbeaa33dc29bdf379685bf91359e2f534893/xbmc/platform/android/media/drm/MediaDrmCryptoSession.cpp); no Kodi C++ source or binary is included.
- Netflix's public [Widevine client configuration](https://github.com/Netflix/msl/wiki/Widevine-Client-Configuration), [key exchange](https://github.com/Netflix/msl/wiki/Widevine-Key-Exchange), and [MSL implementation revision](https://github.com/Netflix/msl/tree/63721e8fa2fee08b1e194b15cf906933590b6467).
- [Chromium MediaDrmBridge](https://chromium.googlesource.com/chromium/src/media/+/fc69f2181c67940d3d962a2ea0de14611020ad54/base/android/java/src/org/chromium/media/MediaDrmBridge.java) for privacy-mode and service-certificate configuration; no implementation copied.
- The public community [pywidevine license schema](https://github.com/devine-dl/pywidevine/blob/e58510279825bd3422b2d9c123a034e633742e3e/pywidevine/license_protocol.proto) for bounded structural diagnostics. Neither pywidevine, the complete schema, generated classes, nor its CDM implementation are dependencies.
- [RFC 6265](https://www.rfc-editor.org/rfc/rfc6265.html) and [OkHttp 4.12.0 Cookie](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/Cookie.kt) for cookie matching semantics.

