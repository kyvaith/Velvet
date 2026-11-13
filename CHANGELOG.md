# Changelog

## 0.1.12 — 2026-11-13

- Change the Android application ID and Kotlin namespace to `pl.kyvaith.velvet`.
- Prepare the public `kyvaith/Velvet` project with English documentation, build instructions, provenance, and contribution/security guidance.
- Record the successful 0.1.11 Pixel playback results and the remaining unverified features.

The package change creates a separate Android application. Existing sessions and local resume positions from the earlier package are not automatically migrated; sign in once in the new package.

## 0.1.11 — 2026-11-13

- Use the native Android playback endpoint, routing, manifest array, and license object after native account activation.
- Send the real Media3 Widevine challenge when acquiring the content license.
- Validate the returned profile token and select the requested manifest explicitly.
- Add native IMSC/TTML subtitle mapping.
- Recognize additional native service-error categories while excluding arbitrary server text from exported diagnostics.
- Prevent expired native sessions from falling into the historical WWW renewal path.

The user subsequently confirmed real playback on Pixel 9 Pro XL with Android 17. Two attempts completed the manifest, license, DRM, and rendering flow without logged warnings or errors. See [verification](docs/VERIFICATION.md).

## Earlier development

Earlier versions introduced authenticated catalog browsing, single in-app website sign-in, local resume storage, native Widevine MSL support, Netflix-issued token activation, scoped native cookies, and bounded session diagnostics. Legacy protocol helpers remain with their upstream attributions and regression tests. Private development reports and user logs are not included in the public repository.

