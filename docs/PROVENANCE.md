# Provenance and implementation boundaries

Velvet is an independent Kotlin implementation of a functional Android client for the Netflix service. Its original code is MIT-licensed. Selected protocol and catalog mappings were adapted from MIT-licensed community projects with their notices retained. Runtime dependencies retain their own licenses.

## What the project claims

Velvet is not Netflix's official application and is not affiliated with, sponsored by, endorsed by, or maintained by Netflix. It requires the user's own valid Netflix account, active subscription, and entitlement to the chosen content. Its license does not grant rights to Netflix's service, trademarks, catalog artwork, videos, or other proprietary materials.

The repository does not distribute Netflix application binaries, decompiled source implementations, proprietary classes or resources, videos, private device credentials, content decryption keys, or a CDM. The system's own Widevine implementation processes protected content through Android APIs. A public Widevine service certificate, credited below, is not a private device certificate or a content key.

## Research method

Development used open-source protocol implementations, public platform documentation, and publicly available decompiled Android application snapshots. The snapshots were consulted to identify interoperable operation names, field types, request shapes, endpoint routing, and error categories. Velvet implements those observations in its own Kotlin code; the proprietary snapshot implementations are not redistributed.

**The project does not claim that it was developed without reverse engineering or as a clean-room implementation.** Such a statement would not accurately describe the reference material used. The absence of bundled proprietary application code is a narrower, distinct statement.

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

## Native Android protocol references

The principal playback reference is the public [theapache64/netflix snapshot at `5505b5258513eff2702662bef5ba429d05fb5e5b`](https://github.com/theapache64/netflix/tree/5505b5258513eff2702662bef5ba429d05fb5e5b). A second activation reference is [meltingscales/com.netflix.mediaclient at `5474216a9c7c9027bc3bc992bd9296c3fb4a0489`](https://github.com/meltingscales/com.netflix.mediaclient/tree/5474216a9c7c9027bc3bc992bd9296c3fb4a0489). These decompiled snapshots do not carry an open-source license; they are references, not dependencies or redistributable source components.

The following locations in the first snapshot were used for protocol facts. Paths are relative to `app/src/main/java`.

| Reference | Observation |
| --- | --- |
| `o/C10700ecQ.java` | Manifest parameter array, string viewable ID, pre-challenge profile shape |
| `com/netflix/mediaclient/service/player/bladerunnerclient/ManifestRequestParamBuilderBase.java` | Outer version, licensed-manifest operation, languages, native metadata |
| `com/netflix/mediaclient/service/player/bladerunnerclient/ManifestRequestFlavor.java` | STANDARD serialization |
| `o/C10766edd.java` | Manifest operation name and result-array selection |
| `o/C16968heW.java` | Content type, MSL encoding, and native routing header |
| `o/dZC.java`, `o/AbstractC12029fFg.java`, `o/C8303dSz.java` | Native physical playback endpoint |
| `o/gAJ.java`, `o/C10696ecM.java`, `o/C10763eda.java` | License object parameters, seconds, challenge, and response shape |
| `com/netflix/mediaclient/service/player/drm/LicenseContext.java` | License bytes separated from provider session metadata |
| `o/C16939hdu.java`, `o/C8298dSu.java`, `o/C16974hec.java`, `o/G.java` | SDK API, optional board-platform field, compatibility-version formatting |
| `o/dJD.java`, `o/C11074ejT.java` | Default cellular capability and IMSC subtitle MIME mapping |
| `com/netflix/mediaclient/service/player/bladerunnerclient/volley/NqErrorStatus.java` and `BasePlayErrorStatus.java` | Named errors, nested errors, and device-command error containers |
| `o/C8390dWe.java`, `o/C8401dWp.java`, `o/C11238eni.java` | Separate PDS event protocol; not implemented in Velvet |

The selected API version is a pinned compatibility contract, not a claim about the latest official Netflix release or official client certification.

An earlier error-classification investigation also inspected the public Netflix-hosted [player artifact `cadmium-playercore-6.0065.492.911.js`](https://assets.nflxext.com/player/html/ffe/cadmium-playercore-6.0065.492.911.js) to identify the 205032 user/entity binding error namespace. The inspected artifact's SHA-256 was `8af8272e0808fda1651786367cf0060c313167683b8b762158911d38dd1a9abe`. It is not distributed with Velvet. Its mapping was a reference observation, not a grant of source redistribution rights.

## Platform and structural references

- [Android MediaDrm.CryptoSession](https://developer.android.com/reference/android/media/MediaDrm.CryptoSession) and the referenced [Kodi Android bridge](https://github.com/xbmc/xbmc/blob/313acbeaa33dc29bdf379685bf91359e2f534893/xbmc/platform/android/media/drm/MediaDrmCryptoSession.cpp); no Kodi C++ source or binary is included.
- Netflix's public [Widevine client configuration](https://github.com/Netflix/msl/wiki/Widevine-Client-Configuration), [key exchange](https://github.com/Netflix/msl/wiki/Widevine-Key-Exchange), and [MSL implementation revision](https://github.com/Netflix/msl/tree/63721e8fa2fee08b1e194b15cf906933590b6467).
- [Chromium MediaDrmBridge](https://chromium.googlesource.com/chromium/src/media/+/fc69f2181c67940d3d962a2ea0de14611020ad54/base/android/java/src/org/chromium/media/MediaDrmBridge.java) for privacy-mode and service-certificate configuration; no implementation copied.
- The public community [pywidevine license schema](https://github.com/devine-dl/pywidevine/blob/e58510279825bd3422b2d9c123a034e633742e3e/pywidevine/license_protocol.proto) for bounded structural diagnostics. Neither pywidevine, the complete schema, generated classes, nor its CDM implementation are dependencies.
- [RFC 6265](https://www.rfc-editor.org/rfc/rfc6265.html) and [OkHttp 4.12.0 Cookie](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/Cookie.kt) for cookie matching semantics.

The NFTools conversion page supplied during development was examined as a clue only. Velvet does not use that service or send user credentials to it.

