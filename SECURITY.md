# Security policy and design notes

## Supported security properties

- Encrypted file contents and encrypted vault metadata at rest inside app-private storage.
- AES-GCM authenticated encryption, per-record associated data for newly written records, and rejection of failed integrity checks.
- AES keys held in Android Keystore.
- Password-based app access with PBKDF2-HMAC-SHA256, persisted failed-attempt lockout, and in-memory password-array clearing after verification/derivation.
- App lock on foreground exit, screenshot/recent-task protection, backup exclusion, and narrow FileProvider cache exposure.

## Storage isolation and data-release boundary

- The encrypted vault and metadata live beneath app-private internal storage. The vault, plaintext staging and temporary outgoing directories are owner-only (0700); files created for temporary plaintext or encrypted payload work are owner-only (0600).
- The FileProvider is non-exported. Its only configured path is `cache/outgoing/`; it does not expose the vault directory or the separate export-staging directory. URI grants are read-only and generated only for user-initiated open/share actions.
- Import, indexing, encryption and decryption do not use network APIs. The app does not request the INTERNET permission. Android backup and device transfer remain disabled.
- Opening a document uses the external viewer selected by the user and therefore sends that specific decrypted file to the selected app. Sharing and export release only the item(s) the user selects. External apps and export destinations may retain plaintext copies.
- Open is an intentional external hand-off because Private Space does not include a built-in document/photo renderer. No other vault content is exposed by that hand-off.

## Crash recovery and file-size handling

- The encrypted index uses schema version 3 for Recycle Bin metadata. Current readers continue to accept schema version 2, including earlier 0.9.0 indexes that already contain a Trash array. Unknown versions are rejected before any write so an incompatible older build cannot silently drop fields.
- Authentication is bound to a foreground lifecycle generation. A password verification finishing after the Activity is interrupted cannot unlock the vault in the background.
- Prepared plaintext is handed to an external viewer or share target only while the vault remains unlocked and the Activity is resumed. If the lifecycle changes during decryption, the prepared temporary session is deleted.

- Imports stream to a temporary encrypted file, sync it, atomically rename it, and only then commit the encrypted index. Startup recovery removes interrupted temporary imports and unindexed payloads only after an authenticated index is available.
- If a document provider reports an input size, import compares it with the actual streamed byte count before committing the file. A mismatch fails the import and removes the temporary encrypted container; unknown provider sizes are still accepted.
- Logical file deletion and topic deletion atomically move file records into the encrypted Recycle Bin in the authenticated index. The original encrypted payload remains in app-private storage; original topic metadata is retained so a restored file can recreate its topic if needed.
- Restoring a Trash item changes only the authenticated index. It does not decrypt, re-encrypt, or rewrite the original file bytes.
- Permanent deletion from Trash stages the encrypted payload under a recoverable tombstone, commits the authenticated index removal, and only then purges the staged payload. Startup restores a tombstone if the authenticated index still references the item (including Trash) and completes permanent deletion when it does not.
- Metadata decryption is bounded before JSON parsing. Transient plaintext metadata and cipher working buffers are cleared where the runtime permits.
- There is no application-defined per-file byte ceiling. Data is processed with bounded-memory streams and a 64-bit counter. The practical maximum is limited by available storage, filesystem/provider behavior and Android's cryptographic implementation.
- Permanent deletion removes an encrypted container where the filesystem allows; it is not a forensic secure-erase guarantee on flash storage.

## File fidelity

Vault import, encryption, decryption, opening, sharing, and export use byte-stream copies rather than image or document conversion. The expected property is byte-for-byte equality: the decrypted/exported file has the same length and SHA-256 content digest as the selected source bytes. The encrypted container adds 36 bytes of framing/authentication overhead. Automated JVM tests exercise the shared AES-GCM stream implementation across file sizes and binary-format samples.

## Storage reporting and format support

Displayed storage values are the actual encrypted document/photo container lengths, grouped by topic. They exclude the APK, encrypted metadata index and temporary cache. Unknown file extensions remain accepted and are stored as opaque bytes. Common extension-to-MIME mappings help Android select an external viewer; a compatible viewer must be installed to open a particular format.

## Explicit limitations

- Opening, sharing, or exporting requires plaintext to exist temporarily or at the destination.
- External apps may retain shared content; exported copies are not encrypted by Private Space.
- The app password is not used as the Android Keystore encryption key and is not a recovery mechanism.
- Device compromise, root access, malicious system components, and attacks against an already-unlocked device are outside the protection boundary.
- There is no account, sync, cloud storage, password reset, or recovery key.

## Reporting

Do not include private documents, passwords, or unredacted vault metadata in bug reports. Provide reproduction steps and sanitized logs only.
