# Playback

Velvet's active playback path uses a Netflix-issued native account session, Android Widevine, and Media3. The native compatibility profile is `native_nftoken_v3`. Its pinned API metadata is an interoperability choice, not a claim that Velvet is the official Netflix app.

## Flow

| Stage | Behavior |
| --- | --- |
| Web sign-in | One sign-in on Netflix's website inside Velvet |
| Token creation | The authenticated web session requests `CreateAutoLoginToken` with native mobile scope |
| MSL handshake | The device CDM performs the `WIDEVINE` / `CDM_GENERIC` exchange |
| Native activation | Consume the token, obtain configuration, and verify the account and selected profile |
| Manifest | Use native Android routing and profile-switch authentication; validate the returned profile token |
| Conversion | Select the requested movie ID and create a DRM-preserving DASH manifest |
| License | Send the real Media3 challenge using the profile token; return license bytes to the device CDM |
| Playback | Media3 retrieves the signed media streams and renders through Android's DRM and decoder pipeline |
| Close | Release local player, DRM, MSL, and native-account resources |

## Manifest contract

The outer native payload contains `version`, `url`, `languages`, and `params`. A manifest request uses `/licensedManifest` and an array of request objects. Each object contains a string `viewableId`, supported stream profiles, `manifestVersion=v2`, `method=licensedManifest`, `flavor=STANDARD`, and `cellularCap=auto`.

The Android metadata uses `application=samurai`, `uiPlatform=android`, `osName=android`, and `player=streaming`. The compatibility versions are pinned to the observed 8.143.5 contract; this is not a statement that 8.143.5 is the newest Netflix app. `osVersion` uses the actual SDK API. Optional hardware metadata uses the device's `ro.board.platform` property; an unavailable property is omitted rather than replaced with a fabricated value.

The pre-license manifest does not include a fabricated DRM challenge or session ID. Its result must be an array containing exactly one matching `movieId`. Missing, ambiguous, or malformed results fail explicitly.

`NativeAccountHttp` sends both playback operations to a fixed HTTPS endpoint under `android.prod.cloud.netflix.com`, with the native `msl_v1` content encoding, request name, and routing header. Netflix's license `href` is validated as an operation path inside the encrypted payload; it cannot redirect the HTTP request or receive cookies at another destination.

## Content DRM and subtitles

`NetflixManifestConverter` preserves PSSH, key IDs, signed stream URLs, and index ranges when generating DASH. It does not replace a protected title with a preview or an unrelated public video. Live streams and manifests requiring advertising periods are rejected as unsupported.

Media3 generates the actual Widevine challenge. Native license parameters are an object containing `clientTime` in seconds, `challengeBase64`, and the playback attempt's `xid`. The response is an object; only `licenseResponseBase64` is decoded and returned to the CDM. `providerSessionToken` and numeric `drmSessionId` values are metadata, not extra license bytes or a text-session echo.

The converter handles WebVTT and supported TTML profiles, including `imsc1.1`. Device logs have confirmed subtitle-track discovery; the successful reported trials did not select a subtitle track, so they do not establish correct subtitle rendering.

## Lifetime and renewal

The account, profile, master token, and user-ID token remain bound throughout an attempt. Invalid or missing profile tokens prevent playback state from being committed. A source-session change during a request invalidates its response.

An expired native master token ends the attempt with an instruction to reopen the title. Reopening uses the saved single sign-in for fresh native activation. It does not invoke the legacy WWW password/bootstrap flow.

Native Netflix PDS start/stop/progress events are a separate protocol and are not implemented. The player does not send a legacy WWW `releaseLicense` bundle as a substitute. Resume positions are local and are not claimed to synchronize with Netflix. Long-duration playback and renewal remain areas requiring device testing.

## Public implementation references

These mappings are credited in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md); exact pinned sources are in [provenance](PROVENANCE.md).

| Velvet area | Reference basis |
| --- | --- |
| Legacy MSL envelopes, requests, validation mappings | MIT-licensed Kodi Netflix add-on `msl_request_builder.py`, `msl_requests.py`, `msl_utils.py`, and related modules |
| Android MSL exchange and ESN field mapping | MIT-licensed atrHusK fork `android_crypto.py` and `esn.py` |
| Manifest field mapping | MIT-licensed Kodi `converter.py` and `profiles.py`; original Kotlin DASH generation and bounds checks |
| Catalog/session operation mappings | MIT-licensed Kodi website/session mappings and Breezyslasher directory requests |
| CDM integration | Android `MediaDrm` APIs and publicly documented/referenced configuration behavior |

The bounded diagnostic challenge observer reports structural presence only. It does not modify a challenge, decode identifying payloads, extract keys, or implement a CDM.

