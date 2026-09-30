# Feiyu Notes

[中文](README.md) | English

<img src="app/src/main/res/drawable-nodpi/whale_02_01.webp" width="128" alt="Feiyu Notes icon">

Photograph a whiteboard or slide, ask DeepSeek about it, and turn the explanation into local study notes. Organize conversations by course and session, or use practice notebooks to track mistakes and mastery.

This project is at an early stage. We aim to keep it small and focused. [Issues and feature suggestions](https://github.com/Yongzhaooo/FeiyuNote/issues) are welcome. **We are not accepting pull requests for now.**

## Download and get started

Download the APK from [Releases](https://github.com/Yongzhaooo/FeiyuNote/releases). Requires Android 8.0 or later. Preview builds are still evolving; export important notes as HTML for safekeeping.

1. Open Settings after installation.
2. Sign in to the [DeepSeek platform](https://platform.deepseek.com/api_keys), create an API key, and save it in the app. API usage is billed separately; check your platform balance and pricing.
3. Create a course and a session. Type a question, take a photo, or choose an image, then tap Send. Use Summarize session to create a study note.

Notes, photos, and conversations are stored on your device. API keys are encrypted with Android Keystore. When you request an answer, the selected question, context, and images are sent to DeepSeek.

## Interface

- Uses Chinese when the primary system language is Chinese, and English otherwise.
- Single-pane phone and two-pane large-screen layouts, with light and dark themes.
- A random whale-girl portrait stays with each session. You can select a custom avatar in Settings.
- Noto Sans SC font; offline note reading, editing, HTML export, and sharing.

## Build and verify

Requires JDK 21, Android SDK, and an internet connection. Open this directory in Android Studio, or run:

```bash
# Linux / macOS
bash ./gradlew testDebugUnitTest assembleDebug

# Windows: unit tests, app/test APK builds, and a dist artifact
pwsh -NoProfile -File scripts/ci.ps1

# Full instrumented and UI tests on emulator-5554
pwsh -NoProfile -File scripts/ci.ps1 -Full
```

Full local CI uses isolated data and fake responses, without paid API calls. Screenshots are saved to `build/ci/screenshots/`. Enable the optional Git hooks with `git config core.hooksPath .githooks`.

GitHub Actions runs unit tests and produces a debug APK on pushes to main. A `v*` tag builds a release APK using signing credentials from repository Secrets and publishes a prerelease. See the [workflow](.github/workflows/android.yml). For a signed local release build, set `FEIYU_KEYSTORE` and `FEIYU_KEY_PASSWORD` with key alias `feiyu`. Never commit signing keys.

## License and credits

Project code is licensed under [GPL-3.0-or-later](LICENSE).

Whale-girl character design: **上善无形 and ZipZipPipe**. Stickers: **Hidcote** on Xiaohongshu. The app icon is image 02-01. The font uses SIL OFL 1.1. Artwork is separate from the code license; see [third-party credits](THIRD_PARTY_NOTICES.md).

## Project documents

[Product specification](docs/spec.md) · [Implementation and verification](docs/plan.md) · [Current status](docs/wayfinder.md) · [Contributing](CONTRIBUTING.md). Development documents are currently in Chinese.