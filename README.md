# Private Space

Private Space is a local-only Android vault for organizing documents and photos into topics. The project uses Kotlin, Jetpack Compose, Android Keystore, Gradle 8.13, a Windows PowerShell APK script, and GitHub Actions.

## Security changes in 0.2.0

- File contents use AES-256-GCM encryption with a random nonce. New encrypted records authenticate their record ID and purpose as additional authenticated data, preventing unnoticed file swapping.
- The vault index (topic names, file names, MIME types, sizes, and dates) is now encrypted with AES-256-GCM and committed using Android AtomicFile.
- The first version's plaintext index is migrated automatically. Its old index entries are removed only after the encrypted index has been committed. Existing encrypted files and old password hashes remain readable.
- Decryption verifies the GCM authentication tag before a temporary plaintext file is handed to another app or copied to an export destination. Failed decryptions remove their incomplete staging file.
- A password is required at first launch. New passwords must be 10–128 characters and are stored as PBKDF2-HMAC-SHA256 hashes with 600,000 iterations. Existing password hashes without a saved iteration count continue using their original 310,000 iterations until the password is changed.
- Failed unlock attempts trigger a persisted lockout. The app locks when it leaves the foreground, except while Android's document picker is active. Screenshots and recent-task previews are blocked with FLAG_SECURE.
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
