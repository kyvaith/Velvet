# Contributing

Contributions should improve a real Netflix account's viewing flow while preserving session ownership, device DRM boundaries, and diagnostic privacy.

## Before changing code

Read the [architecture](docs/ARCHITECTURE.md), [playback](docs/PLAYBACK.md), and [provenance](docs/PROVENANCE.md) documents. Use the pinned dependency versions unless the change needs an upgrade. Keep public descriptions, commit messages, issue reports, and documentation in English. The existing user interface is primarily Polish; localization can be addressed separately.

For a bug report, include the app version, device/Android version, reproduction steps, and expected and actual behavior. A reviewed Velvet diagnostic export is useful. Do not post passwords, cookies, account tokens, device certificates, content keys, raw license bodies, or stream URLs.

## Code and verification

- Preserve account-generation and profile ownership checks before and after network requests.
- Keep authentication cookies away from CDN, provisioning, and arbitrary destinations.
- Use real device DRM requests. Do not introduce extracted credentials, borrowed device identities, modified challenges, or a replacement CDM.
- Extend tests for meaningful behavior changes, using synthetic data and local test peers.
- Keep protocol parsing bounded and diagnostics restricted to explicit known fields.
- Preserve required third-party copyright and license notices. Document any new reference or dependency accurately.

Run the relevant regression tests and, before proposing a release, the standard verification tasks:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Explain the concrete problem, the resulting behavior, how it was verified, and any remaining device/service uncertainty in the pull request. A synthetic server result is not a real Netflix test; label both accurately. Do not commit SDKs, build outputs, signing keys, account material, or raw user logs.

## Licensing

The project's original code is MIT-licensed. Contributions must be compatible with that license and retain required upstream notices. Publicly viewable code is not automatically licensed for redistribution. Protocol reference material and adapted licensed code must be distinguished in the provenance record.

