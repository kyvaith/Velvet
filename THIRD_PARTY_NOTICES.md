# Third-party notices

Velvet's original Kotlin implementation, interface and tests are licensed under MIT; see [LICENSE](LICENSE). Protocol portions adapted from the following MIT sources retain their original attribution. This project does not contain Kodi, the official Netflix application, Netflix artwork, Netflix videos, credentials, device certificates or content decryption keys. Images and catalog entries are requested at runtime for the authenticated user.

## Netflix add-on for Kodi — MIT

Primary source: [CastagnaIT/plugin.video.netflix](https://github.com/CastagnaIT/plugin.video.netflix), pinned to commit [`7accc24cfbf2bf798c037996119e08e6334d64cb`](https://github.com/CastagnaIT/plugin.video.netflix/tree/7accc24cfbf2bf798c037996119e08e6334d64cb).

Original file notices include:

- Copyright (C) 2017 Sebastian Golasch (plugin.video.netflix).
- Copyright (C) 2017 Trummerjo (original MSL handler implementation).
- Copyright (C) 2018 Caphm (original implementations of session and protocol modules).
- Copyright (C) 2019–2020 Stefano Gottardo — @CastagnaIT (protocol and session modules).

Selected protocol mappings in `playback/` were adapted from `resources/lib/services/nfsession/msl/default_crypto.py`, `base_crypto.py`, `msl_request_builder.py`, `msl_requests.py`, `msl_utils.py`, `msl_handler.py`, `converter.py` and `profiles.py`. `data/` uses session/bootstrap mappings from `resources/lib/utils/website.py`, endpoint definitions, profile operations and My List operation shapes. See [docs/PLAYBACK.md](docs/PLAYBACK.md) for the detailed mapping.

The Android MSL protocol mapping introduced in 0.1.5 is adapted from [`resources/lib/services/nfsession/msl/android_crypto.py`](https://github.com/atrHusK/plugin.video.netflix/blob/bf91b55089b06bd892eb6cd1457b3a87341fb5bf/resources/lib/services/nfsession/msl/android_crypto.py) in the MIT fork [atrHusK/plugin.video.netflix, commit `bf91b55089b06bd892eb6cd1457b3a87341fb5bf`](https://github.com/atrHusK/plugin.video.netflix/tree/bf91b55089b06bd892eb6cd1457b3a87341fb5bf). That file is copyright (C) 2017 Sebastian Golasch (plugin.video.netflix) and (C) 2018 Caphm (original implementation module). The adapted details are the `WIDEVINE` request/response fields, generic request initialization and parameters, protected key identifiers and MSL envelope mapping. The Kotlin facade, lifecycle, validation and tests are original to Velvet.

The native owner-token authentication flow in 0.1.6 is adapted from `msl_requests.py`, `msl_request_builder.py` and `msl_utils.py` in the same atrHusK commit `bf91b55089b06bd892eb6cd1457b3a87341fb5bf`, copyright (C) 2017 Sebastian Golasch, (C) 2018 Caphm and (C) 2019–2020 Stefano Gottardo. Adapted details include EMAIL_PASSWORD, the `/logblob` bootstrap request, SWITCH_PROFILE and ordinary user-ID-token headers. The credential lifecycle, bounded cache serializer, UI and ownership tests are original Kotlin code. Formal USER_ID_TOKEN reauthentication was checked against the public Netflix/msl protocol implementation at commit `63721e8fa2fee08b1e194b15cf906933590b6467`; no Java/JavaScript implementation or proprietary player code is distributed. The public player was inspected only to identify the exact 205032 error namespace; see [provenance](docs/PROVENANCE.md).

The Android interoperability ESN field layout is adapted from [`resources/lib/utils/esn.py` at the same commit](https://github.com/atrHusK/plugin.video.netflix/blob/bf91b55089b06bd892eb6cd1457b3a87341fb5bf/resources/lib/utils/esn.py), copyright (C) 2017 Sebastian Golasch (plugin.video.netflix) and (C) 2020 Stefano Gottardo — @CastagnaIT (original implementation module), SPDX-License-Identifier: MIT. Version 0.1.8 used Kodi's deliberate mobile compatibility category T, including for phones. Version 0.1.9 selects the existing phone category P for the independent native NFToken profile while preserving the same installation suffix. The category is a protocol choice, not a measured tablet size. Manufacturer/model, CDM system ID and security level remain the actual device values, with Velvet's own private persistent installation suffix. Velvet does not adopt upstream security-level overrides, substitute system IDs, periodic identity rotation, or identities taken from another application. This interoperability profile is not an official Netflix certification or an assurance of server acceptance.

The 0.1.8 Android L1 path also follows `common/device_utils.py`, `msl_requests.py`, `msl_utils.py` and `msl_handler.py` in atrHusK commit `bf91b55089b06bd892eb6cd1457b3a87341fb5bf`: the independent ARM64 playback User-Agent, direct NETFLIXID authentication for L1, request query fields, and the standard manifest's empty prefetch-challenge fields. Their original copyright notices are retained above and in the adapted Kotlin files. This direct-cookie Kodi path remains available for historical regression tests. Version 0.1.9 activates a separate native account session with NFToken and uses the owner/profile token machinery after account verification. Original Kotlin cookie validation and transport tests contain no real account data. A synthetic protocol fixture generated from the pinned MIT source documents this comparison in `app/src/test/resources/kodi/android_l1_manifest.json`; its generator is in `app/src/test/oracles/generate_kodi_android_l1.py`. See [provenance](docs/PROVENANCE.md) for the source mapping.

Current GraphQL operation identifiers, variables and cache-field mappings were adapted from the MIT runtime file `resources/lib/services/nfsession/directorybuilder/dir_path_requests.py` of [Breezyslasher/plugin.video.netflix](https://github.com/Breezyslasher/plugin.video.netflix), pinned to [`e888205306e9c3beef4652ca4fb4d2c5057de532`](https://github.com/Breezyslasher/plugin.video.netflix/tree/e888205306e9c3beef4652ca4fb4d2c5057de532). Their presence in a fork is not evidence that the resulting Android client has been accepted by Netflix.

The exclusion of H.264 PRK-QC profiles follows [jankofron's change `c41d488658ce76994a1516c28456c4200bd83a7b`](https://github.com/jankofron/plugin.video.netflix/commit/c41d488658ce76994a1516c28456c4200bd83a7b).

The public Widevine service certificate in `playback/NetflixDrmConfiguration.kt` is taken unchanged from the MIT file [`resources/lib/navigation/player.py`, commit `de0db546d9b8ba8d79dbc08fe088b91fc3bcacb7`](https://github.com/CastagnaIT/plugin.video.netflix/blob/de0db546d9b8ba8d79dbc08fe088b91fc3bcacb7/resources/lib/navigation/player.py), copyright (C) 2017 Sebastian Golasch and (C) 2018 Caphm. Its decoded size is 707 bytes, SHA-256 `f17a427fcff8e3c263d9dde8a6fb227b6a22bc4227c7fb82f05b6f639b104a76`. This public service certificate is distinct from a device certificate, private key or content decryption key. The Android integration uses the platform setter; no InputStream Adaptive C++ source is copied into Velvet.

No GPL-licensed Kodi test harness files were copied. Kotlin tests are original to Velvet. Flixclusive was evaluated as a reference project but its code was not copied; the Compose interface was implemented for this project.

The upstream MIT license is reproduced in full:

```text
MIT License

Copyright (c) 2017 Sebastian Golasch

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Reference-only DRM configuration and message layout

The platform integration was checked against [Android MediaDrm.CryptoSession](https://developer.android.com/reference/android/media/MediaDrm.CryptoSession) and the native bridge in [Kodi `MediaDrmCryptoSession.cpp`, commit `313acbeaa33dc29bdf379685bf91359e2f534893`](https://github.com/xbmc/xbmc/blob/313acbeaa33dc29bdf379685bf91359e2f534893/xbmc/platform/android/media/drm/MediaDrmCryptoSession.cpp). No Kodi C++ source or binary is included. The historical Netflix MSL [Widevine client configuration](https://github.com/Netflix/msl/wiki/Widevine-Client-Configuration) and [Widevine key exchange](https://github.com/Netflix/msl/wiki/Widevine-Key-Exchange) are protocol references, not a grant of client certification. Transport keys remain protected by the device CDM; opaque identifiers are used through Android APIs. The offline transport key set can persist in the platform after process death or a cleanup failure; see `docs/PLAYBACK.md` for that lifecycle limitation.

The `privacyMode=enable` followed by `serviceCertificate` configuration is informed by [Chromium MediaDrmBridge, commit `fc69f2181c67940d3d962a2ea0de14611020ad54`](https://chromium.googlesource.com/chromium/src/media/+/fc69f2181c67940d3d962a2ea0de14611020ad54/base/android/java/src/org/chromium/media/MediaDrmBridge.java). No Chromium source file or implementation was copied.

The bounded, original Kotlin observer uses field numbers, wire types and optional proto2 enum defaults checked against the publicly published [pywidevine `license_protocol.proto`, commit `e58510279825bd3422b2d9c123a034e633742e3e`](https://github.com/devine-dl/pywidevine/blob/e58510279825bd3422b2d9c123a034e633742e3e/pywidevine/license_protocol.proto). This is a community schema, not an official guaranteed Google API. Neither pywidevine, the complete schema, generated protobuf classes nor any CDM implementation are included or used as dependencies. The observer only recognizes structural presence and never processes client-identification contents, modifies a challenge or extracts keys. See `docs/PLAYBACK.md` for the interpretation limits.

## NFToken activation in 0.1.9

The `CreateAutoLoginToken` endpoint, persisted-operation identifier and response-field mapping were checked against the MIT-licensed [`nf-token-generator.py` in mehedidec/Netflix-NFToken-Generator, commit 50cad4088efb4f0b7166508bbda2963655dfd97b](https://github.com/mehedidec/Netflix-NFToken-Generator/tree/50cad4088efb4f0b7166508bbda2963655dfd97b), Copyright (c) 2026 Harshit Kamboj. Velvet's Kotlin HTTP implementation, memory lifecycle, session ownership, parsing and tests are original. The generator's WEBVIEW_MOBILE_STREAMING scope is not used; the native MOBILE_DEVICE_UPGRADE scope was accepted in the successful device trials recorded in [verification](docs/VERIFICATION.md), without implying general device compatibility or certification.

The upstream license notice is retained in full:

```text
MIT License

Copyright (c) 2026 Harshit Kamboj

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

Protocol facts for ConsumeAutoLoginToken, native configuration, AccountQuery, HTTP content types and native token scope were independently analyzed in public snapshots [theapache64/netflix at 5505b5258513eff2702662bef5ba429d05fb5e5b](https://github.com/theapache64/netflix/tree/5505b5258513eff2702662bef5ba429d05fb5e5b) and [meltingscales/com.netflix.mediaclient at 5474216a9c7c9027bc3bc992bd9296c3fb4a0489](https://github.com/meltingscales/com.netflix.mediaclient/tree/5474216a9c7c9027bc3bc992bd9296c3fb4a0489). These are decompiled application snapshots with no open-source license; Velvet does not copy or distribute their source implementations, classes, binaries or resources. Only interoperable names, identifiers, field shapes, endpoint paths and observed operation order informed original Kotlin code. Version/build headers select the observed API contract, not a claim that Velvet is the official application. See [provenance](docs/PROVENANCE.md) for the exact sources and uncertainty.

The user-supplied NFTools page was examined only as a clue to token conversion. No code or service from that site is bundled, no request is made to it by Velvet, and no user's cookies were submitted to it during development. Native credentials are issued only by Netflix and are held in the current playback session's RAM.

## Scoped native cookies in 0.1.10

Original Kotlin code separates the opaque NETFLIXID authentication pair used by MSL from HTTP cookies routed to a specific URL. Cookie semantics were checked against [RFC 6265](https://www.rfc-editor.org/rfc/rfc6265.html) and the existing Apache-2.0 dependency [OkHttp 4.12.0 Cookie.kt](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/Cookie.kt). Velvet calls Cookie.matches and additionally enforces expiry and its fixed HTTPS endpoint policy; no OkHttp implementation is copied into the app's own source.

The observed use of NetflixId without Secure and SecureNetflixId with Secure was verified in RunnableC17116hhL of the same theapache64 snapshot cited above. The dZK, hED and C17352hlp protocol observations support keeping MSL credential values distinct from HTTP domain/path attributes. These reference-only observations do not incorporate decompiled implementation code. Current cookie ownership and transport behavior are described in [architecture](docs/ARCHITECTURE.md), with device-evidence limits in [verification](docs/VERIFICATION.md).

## Android application dependencies

Dependencies are resolved by Gradle from Google Maven and Maven Central. Their own license and copyright notices remain applicable. Build versions are fixed in `app/build.gradle.kts`.

| Component | Version used | License / upstream |
| --- | --- | --- |
| AndroidX, Compose, Material3 and Media3 | Compose BOM 2025.06.00; Media3 1.6.1; WebKit 1.12.1; others in build file | Apache-2.0; [AndroidX](https://github.com/androidx/androidx), [Media3](https://github.com/androidx/media) |
| Kotlin standard library and coroutines | Kotlin 2.1.21; coroutines 1.10.2 | Apache-2.0; [JetBrains](https://github.com/JetBrains/kotlin) |
| OkHttp and Okio | OkHttp 4.12.0; transitive Okio | Apache-2.0; [Square](https://github.com/square/okhttp) |
| Coil | 2.7.0 | Apache-2.0; [Coil](https://github.com/coil-kt/coil) |
| Gradle wrapper | 8.11.1 | Apache-2.0; [Gradle](https://github.com/gradle/gradle) |

The Apache license text is in `licenses/Apache-2.0.txt`. Dependency license resources supplied by AndroidX are also retained in the APK. JUnit, MockWebServer, coroutine-test and the JVM `org.json` artifact are used only by local tests; they are not included as application runtime dependencies. The device supplies Android's `org.json` implementation.

The system Widevine implementation is supplied by the device/platform and is not part of this source distribution. Velvet calls the public Android MediaDrm interfaces; its own MIT license does not change licenses for those system components. Netflix is a trademark of Netflix, Inc. Velvet is an independent experimental client and is not affiliated with Netflix.

## Native Android playback contract in 0.1.11

NativePlaybackRequests, the dedicated native transport integration, error classification and regression tests are original Kotlin implementations under the project MIT license. Protocol facts were independently checked against the pinned public theapache64/netflix snapshot 5505b5258513eff2702662bef5ba429d05fb5e5b: C10700ecQ, ManifestRequestParamBuilderBase, C10766edd, C16968heW, dZC, AbstractC12029fFg, C8303dSz, gAJ, C10696ecM, C10763eda, LicenseContext, NqErrorStatus and BasePlayErrorStatus. These references establish interoperable field names, types, endpoint routing and published error categories; no decompiled implementation or binary is included in Velvet. This project does not assert a license to redistribute that reference application. Full source links and research scope are documented in [provenance](docs/PROVENANCE.md); confirmed device behavior and unverified features are separated in [verification](docs/VERIFICATION.md).

The native IMSC subtitle profile is mapped to the existing Media3 TTML pipeline; Media3's existing Apache-2.0 notice remains applicable. Existing Kodi-derived legacy protocol/converter portions retain the attributions above. The 0.1.11 native playback path does not reuse the legacy WWW manifest/license request format.

## Independent implementation and access requirements

Velvet is not affiliated with, sponsored by, endorsed by, or maintained by Netflix. It requires the user's own valid Netflix account, active subscription, and entitlement to the selected content. It does not bypass Netflix authentication or Widevine protection. Publicly available decompiled application snapshots were consulted for interoperability facts, so the project does not claim development without reverse engineering or a clean-room process. Their proprietary source implementations, classes, binaries, and resources are not redistributed.
