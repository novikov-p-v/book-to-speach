# Book to Speech (Android, offline)

Reads TXT, FB2 (`.fb2`, `.fb2.zip`) and PDF books aloud **on-device** using open
[Piper](https://github.com/rhasspy/piper) voices run by [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx).
Internet is used only to download a voice once (Voices screen); after that everything works in airplane mode.

## Features
- Library with reading position per book; tap any sentence to start from it; current sentence highlighted.
- Background playback with notification / lock-screen / headset controls (Media3 session), chapter skip, speed.
- Export a book to one `.m4a` (AAC) file per chapter into any folder (resumable).
- Voices (model + speaker, default per language, per-book override, ▶ preview):
  - **Supertonic 3** (128 MB): most natural, 31 languages incl. Russian/English, 10 speakers; quality setting (3/5/8 steps).
  - **Kokoro 1.0** (349 MB): best English, 28 speakers. The full-precision model is used on purpose: the int8
    version measured 2.5–3× *slower* (RTF ~0.9 desktop / ~1.5 emulator vs 0.33 / 0.5).
  - **Piper** (21–67 MB): light voices for ru, en, uk, de, fr, es, it, pl.
  - Import of any sherpa-onnx Piper/Kokoro/Supertonic `.tar.bz2` archive.
- Warns when a voice is slower than real time on the device (suggests a lighter voice or export).
- Russian: restores «ё» before synthesis (еще → ещё, шел → шёл), using RUAccent's ё dictionary (MIT, bundled in
  `assets/ru`, compiled on first use into a memory-mapped table). Ambiguous words (все/всё) are left as written.
  Stress marks were evaluated and rejected: sherpa-onnx 1.13.8's espeak-ng ignores U+0301 for many words and the
  mark disables espeak's own dictionary (e.g. «счастли́вые» gains a pronounced «т»); `[[phonemes]]` input is disabled.
- Language auto-detection (FB2 `<lang>` tag, otherwise script/stop-word heuristics).

## Build
Requires Android SDK (compileSdk 36) and JDK 17–21 (`org.gradle.java.home` in `gradle.properties` points to a local JDK 21 — adjust or remove).

```
./gradlew testDebugUnitTest   # parser / text-pipeline tests
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk (~38 MB, arm64 phones, R8-shrunk)
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk (arm64 + x86_64 emulator, unshrunk)
./gradlew -Pemulator assembleRelease   # release build that also runs on an x86_64 emulator
```
The release APK is signed with the local debug key so it installs directly; use a proper signing key before publishing.
The sherpa-onnx AAR (~48 MB) is downloaded from GitHub releases into `app/libs/` on first build (git-ignored).

**Note:** sherpa-onnx is pinned to **1.13.8** — version 2.x removed the built-in espeak-ng phonemizer that Piper voices require.

## Layout (`app/src/main/java/app/booktts/`)
- `parse/` — TxtParser (charset detection, chapter headings), Fb2Parser, PdfParser (bookmarks → chapters)
- `text/` — normalization, sentence chunking, language detection; `data/BookBuilder` turns a book into chunks
- `voice/` — catalog (`assets/voices.json`), download worker (SHA-256 verified), installer
- `tts/TtsEngine` — sherpa-onnx wrapper; `playback/` — PlaybackController (synthesize-ahead → AudioTrack), Media3 service
- `export/` — ExportWorker + AacEncoder (MediaCodec/MediaMuxer)
