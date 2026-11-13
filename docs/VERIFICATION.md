# Verification

Verification distinguishes local build/test results from real service and device behavior. Synthetic tests cannot establish Netflix's acceptance of a client or validate a physical Widevine CDM.

## Confirmed device playback — 0.1.11

On **November 13, 2026**, the user confirmed that version **0.1.11 (12)** played Netflix content on a **Google Pixel 9 Pro XL running Android 17 / API 37 with Widevine L1**. The supplied diagnostic export supports two successful playback attempts.

| Observation | Result |
| --- | --- |
| Event severity | 281 INFO, 0 WARN, 0 ERROR |
| Suppression counters | No suppressed events or errors recorded |
| Native activation | Account and selected profile verified in both attempts |
| Manifest | Native manifest selected and profile authentication completed |
| License / DRM | License response accepted and Widevine keys loaded |
| Rendering | First video frame rendered in both attempts |
| Player state | Both reached READY and `isPlaying=true` |
| Media tracks | H.264 video and AAC audio tracks loaded |
| Loads | 96 of 96 and 56 of 56 completed; no failed or canceled loads |
| Exit | Both ended by user action with successful local DRM cleanup |

The first frame arrived approximately 734 ms and 620 ms after player setup, or 2.8 s and 2.7 s after Play. These are observations from two trials, not a performance guarantee. One approximately 0.53 s buffering interval returned to READY without a logged failure; its cause was not recorded.

Catalog/profile restoration, My List, title details, and season/episode retrieval also completed. Subtitle tracks were discovered but none was selected in these trials. The log does not verify subtitle rendering, full-title completion, long-duration renewal, selected resolution, HDR, surround sound, ads, live playback, or offline downloads.

Only aggregate observations are published here. The user's raw log, title/profile identifiers, account data, and playback URLs are not part of the repository.

## Local baseline — 0.1.11

The delivered 0.1.11 package passed `assembleDebug`, `testDebugUnitTest`, and `lintDebug` in one completed build:

- **299 JVM tests in 33 suites**, with 0 failures, errors, or skipped tests.
- Android Lint: **0 errors and 12 warnings**.
- Package, version, signing certificate, license assets, and frozen-source hashes verified.

The build used JDK 21, Gradle 8.11.1, AGP 8.9.2, Kotlin 2.1.21, and SDK/Build Tools 35. The new Lint warning in that baseline concerned reflective access to optional `ro.board.platform` metadata. An unavailable value is omitted; the warning was not suppressed.

## Current package migration — 0.1.12

Version 0.1.12 changes the Android application ID and source namespace to `pl.kyvaith.velvet` and publishes the project with English documentation. The successful device run above belongs to 0.1.11; it must not be presented as a device test of the renamed package.

The renamed package passed all local gates on November 13, 2026, in the first completed build (399.968 seconds):

| Check | Result |
| --- | --- |
| Application | `pl.kyvaith.velvet`, version `0.1.12`, versionCode `13` |
| Build tasks | `assembleDebug`, `testDebugUnitTest`, and `lintDebug` passed |
| JVM tests | **299 tests in 33 suites; 0 failures, errors, or skipped tests** |
| Android Lint | **0 errors, 12 warnings**, with the same categories and counts as 0.1.11 |
| Source consistency | 106 frozen files unchanged throughout the build and verification |
| Package migration | All 86 Kotlin files match 0.1.11 after reversing only the package-name substitution |
| Android manifest | Changes limited to package-derived identifiers and version attributes |
| License assets | Both legal documents match the source assets and packaged APK copies |
| APK signature | Valid, with the same certificate as the previous distributed build |

The warnings comprise `ApplySharedPref` (2), `PrivateApi` (1), `PictureInPictureIssue` (1), `GradleDependency` (2), and `UseKtx` (6). They were not suppressed to produce this result.

The verified APK is 69,646,430 bytes, with SHA-256 `bf7fd09fe5dbe31d762113f943260988cd7fd1d081f18e446fe60ef0beb196f1`. Its signing certificate SHA-256 is `2d430b3b7577a6d2ff17a8a0a60bb8ce0bd8e73f42fc82cdb25218f20f210745`. The [machine-readable build summary](verification/build-summary.json) records the completed gates without private paths or user logs.

Installation and Netflix playback of 0.1.12 have not yet been exercised on the device. The package rename requires one new sign-in; it does not change the working playback implementation. The confirmed device results above belong to 0.1.11.

## What the regression suite covers

Tests cover catalog parsing, account/session ownership, cookie scoping and rotation, native account activation, signed/encrypted MSL messages, profile-token validation, manifest and license request shapes, DRM-preserving DASH conversion, subtitle mapping, bounded error parsing, and diagnostic redaction.

The native integration fixture rejects accidental use of the legacy WWW playback path. It checks missing, expired, malformed, and incorrectly bound profile tokens; response tampering; account changes during I/O; and token-expiry behavior. Local TLS tests verify fixed routing, scoped cookies, protected headers, and redirect refusal.

Passing these tests supports the implementation's internal contracts. It does not certify Netflix compatibility on untested devices or guarantee future service behavior.

