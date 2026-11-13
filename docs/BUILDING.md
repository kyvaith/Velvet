# Building and signing

## Toolchain

| Component | Version / requirement |
| --- | --- |
| JDK used for verified builds | 21 |
| Gradle wrapper | 8.11.1 |
| Android Gradle Plugin | 8.9.2 |
| Kotlin | 2.1.21 |
| Android compile / target SDK | 35 |
| Android Build Tools used for verification | 35.0.0 |
| Application minimum SDK | 28 |
| Native playback minimum API | 29, with working Widevine L1 |

The JVM bytecode target is 17. Gradle resolves dependencies from Google Maven, Maven Central, and the plugin portal as declared in the project. The wrapper distribution has a pinned SHA-256 checksum.

## Local build

Install the toolchain and accept the Android SDK licenses. Configure `ANDROID_HOME` for your SDK, or create an untracked `local.properties` file with `sdk.dir` pointing to it. Use JDK 21 to run Gradle.

```sh
git clone https://github.com/kyvaith/Velvet.git
cd Velvet
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

Useful outputs:

| Output | Location |
| --- | --- |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| JVM test XML | `app/build/test-results/testDebugUnitTest/` |
| JVM test HTML | `app/build/reports/tests/testDebugUnitTest/` |
| Lint report | `app/build/reports/lint-results-debug.html` |

The build requires network access to download the configured dependencies unless a complete Gradle/SDK cache is already present. No Netflix account, cookies, CDM dump, or service secret is needed to compile or run the JVM tests.

## GitHub Actions

On pushes to `main`, pull requests, and manual runs, the Android workflow assembles the debug APK, runs its JVM tests and Lint, and uploads the APK and verification reports as workflow artifacts. Actions are pinned to specific upstream commit hashes.

To publish a release, update `versionName` and `versionCode` in `app/build.gradle.kts`, update the changelog, and push a matching tag from `main`. For example, for `versionName = "0.1.12"`:

```sh
git tag -a v0.1.12 -m "Velvet v0.1.12"
git push origin v0.1.12
```

For a pushed `v*` tag, GitHub Actions assembles the release variant, runs its JVM tests and Lint, and checks that the tag matches the APK version. It then signs and verifies the APK before publishing it with its SHA-256 checksum on [GitHub Releases](https://github.com/kyvaith/Velvet/releases). Verification reports are available in the workflow run.

GitHub Actions uses the `VELVET_RELEASE_KEYSTORE_BASE64` and `VELVET_RELEASE_KEYSTORE_PASSWORD` repository secrets. The keystore is PKCS#12 and uses the `velvet-release` alias with the same password for the store and key. Keep a private backup of the keystore and password. Android updates require the same signing identity, so retain this key for later GitHub Releases. If you have a debug APK with the `pl.kyvaith.velvet` application ID and a different signing key, uninstall it before installing the GitHub Release APK. Uninstalling clears its local session, resume positions, and diagnostics.

## Signing

By default, Android tooling creates or uses your local debug signing key. The private key used to sign previously distributed APKs is not part of the source repository.

The debug configuration optionally accepts `VELVET_SIGNING_KEYSTORE` and `VELVET_SIGNING_STORE_PASSWORD`. When provided, the key alias is `velvet-testing`, and the key password is the supplied store password. Keep signing files and environment values private; do not put them into source, build logs, issues, or repository workflow text.

This override lets maintainers retain their own local test signing identity. GitHub Releases use the dedicated key described above.

Android only updates an installed application when the application ID and signing identity are compatible. A self-built APK may require a fresh installation if the existing package was signed with a different key.

## Package transition in 0.1.12

The application ID and Kotlin namespace are now `pl.kyvaith.velvet`. Earlier builds used `pl.witke.velvet`. Even with the same signing certificate, these are separate Android applications.

Installing the new package leaves the old installation intact. The new package cannot automatically read the old package's encrypted session or Android Keystore entry. Complete the single Netflix sign-in in the new app; local resume positions and diagnostic history start separately.

## Runtime verification

JVM tests use synthetic fixtures, a simulated signed/encrypted peer, and local HTTPS servers. They do not authenticate against Netflix or emulate the Pixel's CDM.

For an end-to-end test, use your own supported Android device and valid Netflix subscription. Confirm a protected title reaches a rendered first frame and actual playing state, then close the player and inspect the exported diagnostic report. Do not treat compilation, a successful manifest response, or an HTTP status alone as proof of working playback.

The current evidence and limits are recorded in [VERIFICATION.md](VERIFICATION.md).

