# Native token activation

Native account activation was introduced in Velvet 0.1.9. The current flow uses a Netflix-issued auto-login token from the existing in-app web session, a separate native cookie jar, account verification, and owner/profile user-ID tokens. It does not ask for a second password.

See [Architecture](ARCHITECTURE.md) for session ownership, [Playback](PLAYBACK.md) for the active transport, and [Provenance](PROVENANCE.md) for the public interoperability references and retained attributions. The successful device results are recorded in [Verification](VERIFICATION.md).
