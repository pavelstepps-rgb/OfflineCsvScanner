# Offline CSV Scanner

Kotlin Android Studio project: Android 12+, CameraX, bundled Google ML Kit Latin OCR, Material 3 Compose UI.

## Build
1. Unzip and open this folder in Android Studio Ladybug or newer.
2. Use JDK 17 and allow Gradle sync (AGP 8.7.3; Gradle 8.9).
3. Run on an Android 12+ device and grant camera permission.

## CSV
- First line is the header. Comma, semicolon and tab delimiters are detected.
- The code column is auto-detected from a header containing `kod`, `kód` or `code`; otherwise the first column is selected. You can change it in the app.
- A `Stav` column is detected (case-insensitive) or added automatically.
- Example code formats: `473-5845 P20`, `442-1245 P115`.
- A successful exact normalized match writes `OK` to `Stav`. An unmatched OCR result opens a manual correction dialog.
- Export writes the modified CSV with its original delimiter.

## Offline
The ML Kit Text Recognition dependency uses the bundled Latin model, so OCR runs without network access. Internet is needed only for the initial Gradle dependency download.


## Build APK automatically with GitHub Actions
1. Create a GitHub repository and upload the contents of this project folder to its `main` branch.
2. Open the repository's **Actions** tab. The workflow **Build Android APK** starts automatically after a push. You can also start it with **Run workflow**.
3. Open the completed workflow run, find **Artifacts**, and download `OfflineCsvScanner-debug-apk`.
4. Unzip the artifact and install `app-debug.apk` on your Android phone. You may need to allow installation from your browser or file manager.

The workflow builds a debug APK. It is suitable for testing and direct installation; it is not a Play Store release-signed APK.
