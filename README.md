# Offline AI (Android)

A fully offline AI assistant for Android: chat, summarize large texts, extract
keywords, fix grammar, and ask questions about your own PDFs and images.
Everything runs on the phone using llama.cpp. No cloud, no account.

## Status

- Milestone 1: llama.cpp compiles and loads on Android. Done.
- Milestone 2: model download/import, streaming chat, app icon. Done.
- Milestone 3: attach a PDF/TXT/MD file and ask questions about it (offline BM25 search), accurate speed stats. Done.
- Milestone 4: Summarize (long text or attached document, section by section), instant Keywords, Fix grammar. Done.
- Next: image OCR, speed work.

## Build (no PC needed)

Push to GitHub. The "Build APK" workflow compiles everything in the cloud.
Download the APK from the finished run's Artifacts.

## Layout

- `app/src/main/java/...` Kotlin app code (Compose UI, view model, native bridge)
- `app/src/main/cpp/` JNI bridge and CMake build for llama.cpp
- `app/src/main/res/` app icon (adaptive vector icon) and themes
- `scripts/fetch_llama.sh` downloads llama.cpp at a pinned version
- `.github/workflows/build.yml` cloud build

## Models

Models are not included in this repo. On first launch the app can download
Qwen2.5 1.5B Instruct (Q4_K_M, about 1 GB) or import any GGUF file you already
have. The internet is only used for that one download.

The chat currently uses the ChatML prompt format (Qwen and similar models).

## License

TBD (llama.cpp is MIT licensed).
