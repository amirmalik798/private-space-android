# Encrypted external video playback — implementation status

## Goal

Avoid decrypting an entire multi-gigabyte video into a temporary plaintext file before handing it to an external player. For current chunked AES-GCM records, expose authenticated random-access reads through a short-lived, read-only content URI.

## Existing implementation discovered on main

- Current records use independently authenticated 64 KiB AES-GCM frames with an authenticated terminal frame.
- `VaultCrypto.openChunkedRandomAccessReader` returns a random-access reader for current-format records. Each requested frame is authenticated before plaintext is returned, and the terminal frame is verified when the reader opens.
- Video thumbnail decoding already uses this reader through `MediaDataSource.readAt`.
- The external player open flow is different: `prepareDecryptedFile` decrypts the entire video to an app-private outgoing file and `FileProvider` hands that full plaintext file to `ACTION_VIEW`.
- Sharing/export and legacy encrypted formats must remain unchanged.

## Branch additions

- `VaultPlaybackSessionRegistry.kt`: process-local registry with opaque random session tokens and close/revoke support.
- `VaultPlaybackProvider.kt`: read-only `ContentProvider` prototype backed by Android `StorageManager.openProxyFileDescriptor`, with random-offset reads, size and display-name metadata.
- Manifest registers the provider as exported but URI-grant protected. This is required for an external media player to open it; callers must receive only a temporary read grant for a one-session URI.

## Not yet integrated / not production-ready

- Repository method to create and revoke a playback session has not been added.
- MainActivity still uses full-file decryption and FileProvider. Samsung Video Player has not yet been tested against this provider.
- Fallback to existing full-decrypt playback must remain available for legacy records, unsupported devices/players, and any player that cannot consume proxy descriptors.
- Need explicit playback session lifetime/revocation: revoke on app lock, playback completion/cancel, session timeout, process shutdown, or other safe lifecycle boundary. External players may close/reopen descriptors during seeking, so closing one descriptor alone must not necessarily revoke the session.
- Need enforce caller UID / package identity on every provider entry point in addition to the temporary URI grant and unguessable token.
- Need validate proxy descriptor behavior with Samsung Video Player on real devices, including metadata queries, concurrent/random reads, seek, fast-forward, close/reopen, background, and lock.
- Need tests for read boundaries, concurrent reads, token invalidation, permissions, cleanup, tampered chunks, terminal-frame tampering, and legacy fallback.
- Need compile, lint, unit tests, and device verification before claiming success.

## Security cautions

- Never expose a raw vault file ID or filesystem path in the URI.
- Authenticate each requested frame before returning bytes.
- Do not return partial bytes from a failed authenticated frame.
- Ensure callback buffers and cached plaintext are wiped by the reader/session lifecycle.
- The external player necessarily receives plaintext video bytes and may retain/cache them; this design removes the complete plaintext staging copy but cannot prevent capture/copy by the receiving player.
- A proxy-backed URI is not a guarantee of Samsung Video Player compatibility. The Android API capability and OEM player behavior are separate questions.

## Source-of-truth constraints

This branch was created from `main`. Do not rewrite `VaultRepository.kt` from partial snippets: it is a large file and must be fetched/materialized completely before editing. Do not change `main`.
