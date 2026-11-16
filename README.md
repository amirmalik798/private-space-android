# Private Space

## Interface, thumbnails and session controls (0.10.0)

- The dashboard and full-screen dialogs now account for Android system-bar insets. The password screen adjusts for the on-screen keyboard and remains vertically scrollable on compact displays.
- The login screen displays the developer credit: **Developed by: Amir Malik (2026)**.
- Opening a decrypted file launches Android's normal `ACTION_VIEW` resolver instead of forcing a temporary chooser. Android can therefore offer **Just once** and **Always**, and its saved default viewer is respected on later opens.
- Switching to another app no longer automatically locks the vault. Use the visible in-app **Lock** control to lock immediately; closing the app/task locks it, and locking the Android device still locks the vault.
- Image rows in topics and the Recycle Bin show downsampled previews. Previews are generated only after successful AES-GCM authentication, from a short-lived app-private staging file that is deleted immediately; stored originals and their encrypted bytes are not changed.


## Reliability hardening (0.9.1)

- Authentication completes only if the app remains in the same foreground lifecycle generation. If the app is interrupted while the password is being checked, the vault stays locked and the password must be entered again.
- A decrypted file prepared for opening or sharing is handed to another app only while the vault is unlocked and Private Space is resumed. If the app is backgrounded or locked during preparation, the temporary plaintext session is removed instead.
- Vault repository construction, encrypted-index authentication, and startup recovery run off the main thread. Initialization failures show a retryable error state instead of blocking app launch. One process-wide repository serializes index and file operations across Activity recreation; failed initialization is not cached as a permanent success state.
- Vault refresh failures are caught, reported, and fail closed by clearing unlocked UI state instead of escaping from a UI coroutine.
- The encrypted index now writes schema version 3 for Trash metadata and continues to read version 2 indexes. Unknown future versions are rejected without rewriting them, protecting data from older/incompatible app builds.

## Recycle Bin and recoverable deletion (0.9.1)

- Deleting an individual photo/document moves its encrypted record into the in-app Recycle Bin instead of immediately removing its encrypted payload.
- Deleting a topic removes it from the active topic list and moves all of its files into the Recycle Bin. Empty topics are removed immediately.
- Restore returns a file to its original topic. If that topic was deleted, it is recreated; if a topic with the same name already exists, the file is restored there instead of creating a duplicate.
- Items in the Recycle Bin can be permanently deleted individually after a separate confirmation. Permanent deletion uses the same crash-safe encrypted-payload tombstone and authenticated-index commit process.
- Trash metadata remains inside the authenticated encrypted vault index, and the files remain encrypted in app-private storage. Moving to or restoring from Trash does not decrypt, recompress, convert, resize, or rewrite file contents.
- Encrypted files in Trash continue to count toward storage usage until permanently deleted.

## Topic and file actions (0.9.0)

- Create named topics and move a topic and its files into the Recycle Bin; restore its files later or permanently delete them from Trash.
- Add multiple photos and documents to a topic using the selected Android file manager; move an individual file to Trash from its topic.
- Share one file, or share every file in a topic through Android's share sheet.
- Export one topic into a newly created folder with the same name as the topic.
- Export the complete vault into `private-space-data/<topic name>/<files>`. Every topic gets its own subfolder, including empty topics. If the selected destination already contains a `private-space-data` folder, the app stops and asks you to choose another location rather than overwrite existing content.
- File-name collisions inside an export are resolved by adding a numbered suffix so no exported file silently replaces another.

## Security, crash recovery and file sizes (0.9.1)

- Vault files, topics and metadata stay under app-private internal storage. The vault, plaintext staging area and temporary outgoing-share folders are restricted to the app's Linux UID with owner-only directory/file permissions.
- The Android FileProvider is not exported and exposes only the temporary `cache/outgoing/` directory. It does not expose the encrypted vault directory or plaintext export-staging directory. Read-only URI grants are created only after the user chooses an explicit Open, Share, or Share topic action.
- Opening a file hands a temporary decrypted copy to the external viewer selected by the user. Sharing and export likewise create plaintext outside the encrypted vault only after the corresponding user action. Receiving apps and export destinations can retain those copies.
- Android app backup and device transfer remain disabled. The app requests no network permission and has no cloud upload/sync pathway.
- Imports stream AES-256-GCM into a temporary encrypted file, sync it, atomically rename it, and then commit the encrypted index. Interrupted imports are reconciled on next startup.
- Logical file/topic deletion commits a Trash entry to the encrypted index while keeping the encrypted payload in app-private storage. Restoring an item updates only the index and does not rewrite file bytes. Permanent deletion stages the payload under a recoverable tombstone, commits its removal from the index, then purges it; startup recovery reconciles interrupted permanent deletions using both active and trashed index entries.
- Metadata decryption is bounded to the index-size limit. Temporary clear metadata and cipher buffers are wiped after use where the runtime permits.
- When a document provider reports a file size, import verifies the completed stream byte count against it before committing the encrypted record. A mismatch discards the temporary encrypted file instead of saving a possibly truncated import; providers that report an unknown size remain supported.
- **No application-level file-size ceiling is imposed.** Imports use bounded-memory streaming and a 64-bit byte counter. Practical limits still depend on available storage, the filesystem, Android's document provider and cryptographic provider. Photos/documents are never resized, recompressed, converted or stripped of metadata; new encrypted records add 36 bytes of container overhead.

## Modern user interface and usability (0.7.0)

The dashboard uses a refined dark visual system, a gradient vault overview, clearer topic and storage metrics, and quieter topic/file cards. Topic and file details use familiar file-type icons. Actions throughout the app use consistent, meaningful icons with readable labels, including create, import, share, export, lock, password, and destructive actions. Redundant helper copy, repeated status text, and low-value metadata have been removed while retaining essential security and recovery warnings.

## Usability heuristics

The vault has a redesigned, accessibility-conscious interface with a searchable topic dashboard, at-a-glance topic/file/storage totals, a clearer empty state, a full-height topic view, and streamlined per-file actions. Password fields can be revealed while typing, destructive actions explain what will be removed, and security guidance is available from the dashboard.

The UI applies Jakob Nielsen's ten usability heuristics:

1. **Visibility of system status:** visible topic/file/storage totals, encryption status, lockout countdowns, and progress feedback.
2. **Match between system and real world:** familiar topics, folders, documents, photos, and plain-language actions.
3. **User control and freedom:** clear cancel/close actions, a one-tap lock action, and a searchable dashboard.
4. **Consistency and standards:** shared Material 3 colors, typography, surfaces, shapes, and action patterns.
5. **Error prevention:** disabled unavailable actions, password confirmation, clear password guidance, and explicit permanent-delete confirmation.
6. **Recognition rather than recall:** visible labels, familiar icons, file metadata, and contextual action menus.
7. **Flexibility and efficiency:** topic search, quick-create action, and direct file-row opening with secondary actions grouped in a menu.
8. **Aesthetic and minimalist design:** fewer competing file buttons, stronger visual hierarchy, and more breathing room.
9. **Help users recognize, diagnose, and recover from errors:** readable inline authentication errors, lockout timing, and safe document-picker fallback behavior.
10. **Help and documentation:** an in-app security explainer describes encryption, sharing/export boundaries, and the lack of password recovery.

Private Space is a local-only Android vault for organizing documents and photos into topics. The project uses Kotlin, Jetpack Compose, Android Keystore, Gradle 8.13, a Windows PowerShell APK script, and GitHub Actions.

## File-manager import preference (0.5.0)

When adding documents or photos, Private Space lists compatible Android apps that can return selectable files. Choose **Just once** to use an app for the current import, or **Always** to remember that app as the import picker. The saved choice is kept in Private Space app preferences. If that app is removed or stops handling file selection, Private Space clears the stale choice and lets you select another app. When no compatible app is discoverable, the standard Android multi-document picker is used as a fallback. Multi-selection is requested where the selected app supports it.

## Storage reporting and file format support (0.4.0)

The vault dashboard displays the combined encrypted storage used by saved documents and photos. Each topic shows the combined encrypted size of its files. Values are calculated from the actual encrypted container lengths under app-private vault storage, including encryption framing. They exclude the APK, encrypted index, and temporary cache. New AES-GCM containers add 36 bytes per file.

Private Space accepts all file types through Android's unrestricted document picker. MIME mappings cover common raster/photo formats (JPEG, PNG, WebP, GIF, BMP, TIFF, HEIC/HEIF, AVIF, SVG, JPEG 2000 and popular camera RAW types), document/text formats (PDF, TXT, RTF, Markdown, CSV, TSV, HTML, XML, JSON, YAML and EPUB), Microsoft Office formats (DOC/DOCX, XLS/XLSX and PPT/PPTX including common macro-enabled variants), OpenDocument formats (ODT, ODS, ODP and related templates), and Apple iWork formats. Unknown extensions are stored as generic binary files. MIME mappings help Android select an external viewer; opening depends on compatible apps installed on the device. Storage, encryption, decryption, sharing and export do not convert or recompress file contents.

## File integrity and fidelity (0.3.0)

Private Space treats imported documents and photos as opaque byte streams. It does not decode, resize, recompress, rotate, strip metadata from, or convert an imported image/document. AES-GCM encryption and decryption operate on the original bytes. After successful decryption, opening/sharing/export passes those recovered bytes through unchanged. The versioned encrypted container adds exactly 36 bytes (8-byte format marker, 12-byte random nonce, and 16-byte authentication tag); this is storage overhead, not a change to the original file. The exported/decrypted file length and SHA-256 digest should match the imported source bytes.

The automated byte-preservation tests cover empty and buffer-boundary payloads, synthetic PDF/JPEG/PNG binary signatures, an 8 MiB payload, nonce uniqueness, rejection of modified ciphertext/wrong record identity, and compatibility with the first release's encrypted container format.

## Security changes in 0.2.0

- File contents use AES-256-GCM encryption with a random nonce. New encrypted records authenticate their record ID and purpose as additional authenticated data, preventing unnoticed file swapping.
- The vault index (topic names, file names, MIME types, sizes, and dates) is now encrypted with AES-256-GCM and committed using Android AtomicFile.
- The first version's plaintext index is migrated automatically. Its old index entries are removed only after the encrypted index has been committed. Existing encrypted files and old password hashes remain readable.
- Decryption verifies the GCM authentication tag before a temporary plaintext file is handed to another app or copied to an export destination. Failed decryptions remove their incomplete staging file.
- A password is required at first launch. New passwords must be 10–128 characters and are stored as PBKDF2-HMAC-SHA256 hashes with 600,000 iterations. Existing password hashes without a saved iteration count continue using their original 310,000 iterations until the password is changed.
- Failed unlock attempts trigger a persisted lockout. The vault remains unlocked when you switch to another app. Use the in-app Lock control to lock immediately; closing the app/task or locking the Android device requires the password again. Screenshots and recent-task previews are blocked with FLAG_SECURE.
- Android app backup and device-transfer backup are disabled. No network permission is requested. FileProvider exposes only the temporary outgoing cache directory.

## Important security boundaries

Private Space encrypts its own stored files and index. Android must temporarily receive a decrypted copy to open or share a file. Recent temporary sharing copies are retained for up to 30 minutes so the receiving app can read them; expired copies are cleaned when the vault is opened. An app receiving a shared file can copy or retain it.

Exported files are ordinary, unencrypted files outside the vault. Anyone with access to the export destination can read them. The app cannot revoke copies after export or sharing.

The app password is an application access gate; the encryption key is device-bound in Android Keystore. Newly generated keys require the Android device to be unlocked where the OS supports that setting. This is not protection against a compromised/rooted device, malicious accessibility software, screen capture by a privileged attacker, or malware running with control of the unlocked app.

There is no password recovery, cloud backup, or cross-device restore. Clearing app data or uninstalling the app removes the Android Keystore key and makes the encrypted vault unrecoverable. Keep independent, secure backups of important documents.

## Data compatibility

The upgrade preserves the first version's encrypted file format and password hashes. On first launch after upgrading, the old metadata index is migrated to encrypted storage. Do not clear app data during this migration. If migration reports an error, stop using the app and preserve its app data for recovery.

## Build

Requirements: JDK 17, Android SDK Platform 36, Android Gradle Plugin 8.13.2, Kotlin 2.2.10, and Gradle 8.13.

Open the folder in Android Studio and build the debug APK, or run build-apk.ps1 in PowerShell. The APK output is app/build/outputs/apk/debug/app-debug.apk.

GitHub Actions runs unit tests, Android lint, and a debug APK build on branch pushes and pull requests. A successful CI build does not replace testing on a physical Android device.
