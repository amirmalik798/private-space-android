# Build Private Space on Windows

Use JDK 17, Android SDK Platform 36, Android Gradle Plugin 8.13.2, Kotlin 2.2.10 and Gradle 8.13.

Open this folder in Android Studio and choose Build > Build APK(s), or run `build-apk.ps1` in PowerShell. Output: `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions builds and uploads the `private-space-debug-apk` artifact for branch pushes and manual runs.
