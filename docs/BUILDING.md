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

The included Android build workflow runs the same assembly, JVM test, and Lint tasks on pushes to `main`, pull requests, and manual runs. It publishes a debug APK and verification reports as workflow artifacts. Actions are pinned to specific upstream commit hashes.

The workflow uses the runner's debug signing key. Its APK is a development artifact and is not signed with the private key used for separately distributed builds. Production release signing is not configured.

## Signing

By default, Android tooling creates or uses your local debug signing key. The private key used to sign previously distributed APKs is not part of the source repository.

The debug configuration optionally accepts `VELVET_SIGNING_KEYSTORE` and `VELVET_SIGNING_STORE_PASSWORD`. When provided, the key alias is `velvet-testing`, and the key password is the supplied store password. Keep signing files and environment values private; do not put them into source, build logs, issues, or repository workflow text.

This override supports maintainers retaining their own test signing identity. It does not configure production release signing, distribution certificates, or an app-store release pipeline.

Android only updates an installed application when the application ID and signing identity are compatible. A self-built APK may require a fresh installation if the existing package was signed with a different key.

## Package transition in 0.1.12

The application ID and Kotlin namespace are now `pl.kyvaith.velvet`. Earlier builds used a different application ID. Even with the same signing certificate, these are separate Android applications.

Installing the new package leaves the old installation intact. The new package cannot automatically read the old package's encrypted session or Android Keystore entry. Complete the single Netflix sign-in in the new app; local resume positions and diagnostic history start separately.

## Runtime verification

JVM tests use synthetic fixtures, a simulated signed/encrypted peer, and local HTTPS servers. They do not authenticate against Netflix or emulate the Pixel's CDM.

For an end-to-end test, use your own supported Android device and valid Netflix subscription. Confirm a protected title reaches a rendered first frame and actual playing state, then close the player and inspect the exported diagnostic report. Do not treat compilation, a successful manifest response, or an HTTP status alone as proof of working playback.

The current evidence and limits are recorded in [VERIFICATION.md](VERIFICATION.md).

