# Offline AI (Android)

A fully offline AI assistant for Android: chat, summarize large texts, extract
keywords, fix grammar, and ask questions about your own PDFs and images.
Everything runs on the phone using llama.cpp. No cloud, no account.

## Status

Milestone 1: stub app that proves llama.cpp compiles and loads on Android.

Planned: model loading and chat, PDF Q&A, summarize/keywords/grammar, image OCR.

## Build (no PC needed)

Push to GitHub. The "Build APK" workflow compiles everything in the cloud.
Download the APK from the finished run's Artifacts.

## Layout

- `app/src/main/java/...` Kotlin app code
- `app/src/main/cpp/` JNI bridge and CMake build for llama.cpp
- `scripts/fetch_llama.sh` downloads llama.cpp at a pinned version
- `.github/workflows/build.yml` cloud build

## Models

Models are not included in this repo. The app will download or import a GGUF
model (for example a 1.5B Q4_K_M model) on first use.

## License

TBD (llama.cpp is MIT licensed).
