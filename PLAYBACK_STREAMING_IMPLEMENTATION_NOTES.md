# Encrypted external video playback — implementation status

## Implemented on this feature branch

- Current chunked AES-GCM records can be opened through the existing authenticated random-access reader.
- The repository creates a process-local playback session only for current-format video records. Legacy containers return to the established full-decrypt compatibility path.
- `VaultPlaybackProvider` exposes an opaque, read-only content URI backed by Android proxy file descriptors.
- The provider reports display name, MIME type and plaintext size, and services random-offset reads through the authenticated reader.
- The provider validates temporary URI read grants; URI tokens are random and do not expose vault file IDs or filesystem paths.
- The MainActivity video-open flow prefers streaming playback on Android 8.0+ and retains the original external-player workflow for legacy videos and unsupported Android versions.
- Explicit vault lock revokes the URI grant and closes active playback sessions, wiping the reader's cached plaintext frame.
- The encryption container format, import, export, share, recovery and existing legacy decryption code are unchanged.

## Verification still required

- The first CI attempt found Kotlin integration errors. Those compile errors have been corrected on the branch; a fresh test/lint/build run is still required.
- Test actual playback on Samsung devices and Samsung Video Player, including initial open, metadata reads, random seeks, fast-forward, close/reopen, backgrounding and locking the vault.
- Confirm behavior with other external players and Android API levels supported by the app.
- Add focused provider/session tests for grant enforcement, expired/revoked tokens, concurrent reads, boundary reads, tampered chunks and cleanup.
- Confirm that a player which accepts the intent can actually consume proxy descriptors. Intent launch success alone does not prove playback compatibility.

## Security notes

- Each requested encrypted frame is authenticated before plaintext is returned.
- The provider is read-only and accepts no arbitrary file ID or path.
- A receiver app necessarily receives plaintext bytes while playing. This design avoids a complete plaintext staging copy; it cannot prevent the receiving player from caching, recording or copying data it has been granted.
- Active sessions are revoked on explicit vault lock and process shutdown. Real-device lifecycle testing remains necessary before production release.

## Source-of-truth constraints

This branch was created from `main`. `main` remains unchanged. Do not replace large source files using partial file excerpts; use complete Git blobs when applying edits.
