# Security

## Reporting a vulnerability

Please report security issues privately to **kyvaith@gmail.com**. If GitHub private vulnerability reporting is enabled for this repository, it can also be used.

Include the affected version, a concise description, reproduction steps using synthetic or your own authorized test data, expected impact, and any proposed fix. Do not send passwords, active cookies or tokens, private device certificates, content keys, or other people's data. A redacted diagnostic export is normally enough for an initial report.

Do not publish an issue containing an account takeover path, an unredacted credential, or a working secret. No response-time or remediation-time guarantee is currently offered.

## Sensitive boundaries

Velvet handles an authenticated Netflix session. The catalog session and local resume positions are encrypted with Android Keystore-backed storage. Native activation credentials stay in the playback session's memory. The login WebView uses Netflix's real HTTPS page; the app does not read password fields or provide a second password form.

Native account requests use fixed HTTPS destinations, explicit cookie matching, and no automatic redirects. Playback checks account/profile ownership across network completion. CDN and provisioning transport do not receive account cookies. Exported diagnostics omit raw protocol bodies and credential values.

These are implemented controls, not a claim of a formal security audit. Review [architecture](docs/ARCHITECTURE.md), [diagnostics](docs/DIAGNOSTICS.md), and the regression tests for their scope and limitations.

## Supported development line

Security fixes target the current development branch and current release. Earlier experimental builds are not maintained as separate security branches. Service or device compatibility may also change independently of Velvet releases.

