# Architecture

Velvet is a single-module Android application written in Kotlin. Jetpack Compose provides the interface; Media3 provides playback. Catalog access and playback activation use distinct session contexts while remaining bound to the same signed-in account and selected profile.

## Components

| Component | Responsibility |
| --- | --- |
| `NetflixLoginActivity` | Displays Netflix's HTTPS website and captures the confirmed signed-in session |
| `SessionStore` | Encrypts the catalog session and local resume positions with AES-GCM and an Android Keystore key |
| `NetflixRepository` | Catalog, search, profiles, title details, seasons, episodes, and My List |
| `NativeAccountHttp` | Fixed native endpoints, scoped cookies, token creation, account activation, and native playback transport |
| `NativeAccountSessionProvider` | Memory-only native credentials and account/profile ownership checks |
| `AndroidMslCrypto` | Device-backed Widevine MSL transport operations through Android `MediaDrm` |
| `NetflixMslSession` | Authenticated message envelopes, token validation, signatures, response sequencing, and profile switching |
| `NativePlaybackRequests` | Separate native manifest and license request/response contracts |
| `NetflixManifestConverter` | Converts the licensed response into DASH while preserving DRM and signed media URLs |
| `NetflixDrmCallback` | Routes the real Media3 Widevine challenge to the native license operation |
| `PreparedPlayback` | Owns the player session and closes playback, DRM, MSL, and native account resources |
| `SessionLog` / `DiagnosticPrivacy` | Persistent bounded diagnostics and an explicit export-field allowlist |

The active package is `pl.kyvaith.velvet`. The source tree uses the corresponding `pl/kyvaith/velvet` namespace.

## Sign-in and storage

The embedded WebView displays the real Netflix sign-in page. The app does not read password fields, inject a JavaScript credential bridge, or ask for the password a second time. After the user confirms the session, the repository verifies it before exposing captured catalog data.

The catalog session is encrypted at rest using a non-exportable Android Keystore key. Android backup is disabled for sensitive application state. Account-generation counters invalidate in-flight work after sign-out or a session change. Each playback attempt is bound to its account generation, selected profile, and browser identity; network completion does not waive that ownership check.

Changing the application ID creates a new Android sandbox and Keystore scope. The package rename does not transfer the previous package's encrypted state.

## Native account activation

The existing web session requests a Netflix-issued auto-login token. Widevine establishes a protected MSL transport session on the device. The native token is consumed, followed by configuration and account queries. The selected profile must belong to the verified account, and the returned owner user-ID token must be valid and bound to the current master token.

The native MSL authentication pair is separate from the HTTP cookie jar. HTTP cookies retain their domain, path, host-only flag, Secure flag, and expiry. Matching uses OkHttp's cookie rules plus expiry checks. A complete credential rotation replaces the old authentication scope; an incomplete or contradictory pair is rejected without partially committing state.

Native activation credentials remain in the current playback session's memory. The app does not submit them to a third-party conversion service.

## Playback ownership and transport

The first native manifest uses profile-switch authentication. A valid profile user-ID token must be returned before committing the playback owner record. Later license requests use the verified profile token. MSL processing checks ownership again after network I/O, alongside signatures, message IDs, sequence numbers, and message termination.

Manifest and license operations have distinct payload shapes but the same fixed native transport and routing policy. The license operation path supplied by Netflix remains inside the encrypted MSL body and cannot replace the physical HTTP destination. Native requests do not inherit arbitrary interceptors, authentication handlers, automatic cookie jars, or redirects from the catalog client.

Legacy web/Kodi helpers remain in the source and regression tests. The active native manifest/license path does not fall back to the legacy WWW renewal flow when its master token expires.

## Player and cleanup

Media3 opens the content DRM session and generates the challenge. The challenge stays bound to the real Android key-request object. The license response supplies only the decoded license bytes to the CDM; provider metadata is not appended to them.

CDN and provisioning requests use a public transport without account cookies. Closing playback releases local player and DRM resources, then closes MSL and the private native provider. Netflix's separate PDS start/stop/progress protocol is not implemented, so local cleanup is not represented as a confirmed server-side stop event.

The native MSL exchange can create a protected offline transport key set inside the platform CDM. Normal cleanup requests its removal. Process death or a removal failure can leave an orphaned platform entry; the app does not serialize its handle or enumerate other stored licenses. These transport keys are distinct from content keys and do not implement offline movie downloads.

See [playback](PLAYBACK.md), [diagnostics](DIAGNOSTICS.md), and [provenance](PROVENANCE.md) for details.

