# Private Space

A separate native Android project using the same Kotlin, Jetpack Compose, Gradle 8.13, Windows PowerShell APK script, and GitHub Actions build workflow used in Download Studio.

This branch begins the Private Space implementation. The initial foundation includes first-run password setup, PBKDF2-HMAC-SHA256 password verification, AES-256-GCM encrypted app-private file copies protected by an Android Keystore key, topic/file metadata, multi-file import, file deletion, opening with Android app selection, sharing a file or complete topic, and export of a file/topic/all topics.

**Security boundary:** The password gates access, file contents are encrypted at rest in app-private storage, and the AES key remains in Android Keystore. To open/share/export, the app temporarily decrypts a selected file into a cache staging location. Receiving apps can retain shared content. Exported files are plain copies outside the vault. This version is local-only and does not support password recovery or cross-device restore. Android app-data clearing or uninstall removes the Keystore key and vault.

Package ID: `com.privatespace.android`.

Build: JDK 17, Android SDK Platform 36, Gradle 8.13, then run `build-apk.ps1` or use the included GitHub Actions workflow. The generated APK must still be built and tested on a real Android device before calling this feature complete.
