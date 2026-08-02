# Harness Android

A native Kotlin/Jetpack Compose Android client for [qelg/harness](https://github.com/qelg/harness).

**[Download the newest signed Android APK](https://github.com/qelg/Android/releases/download/latest/harness-android-latest.apk)**

## Features

- List, search, create, and resume persistent Harness sessions with live sessions first, then ordered by server activity
- Show server-authoritative running, finished, unread, read, and archived session states
- Archive chats with a left swipe or from session details; archived chats return automatically after new activity
- Load direct child sessions from the Harness child-session API, browse them from session details, and open their chats
- Load durable message history and stream assistant/tool activity over Harness SSE
- Select a provider/model and thinking level (none, low, medium, or high) for a session
- Optionally request ChatGPT Codex reasoning summaries and show available reasoning in the timeline
- Keep per-session timelines, drafts, and read state while switching between chats
- Store an optional bearer token using Android Keystore
- Discover providers and tools exposed by Harness
- Record, progressively transcribe, and send voice messages entirely on-device with a configurable Whisper model (verified model downloads on first use, live progress and partial text)
- Receive end-to-end encrypted finished top-level session notifications through UnifiedPush, with notification taps opening the session directly

Capabilities qelg/harness does not expose—approval responses, run cancellation, and detailed token/context
accounting—stay unavailable rather than using a separate legacy backend. Voice transcription is local and does
not upload microphone audio. A recording remains bound to the chat where it was started, even when switching chats before transcription finishes. Choose Tiny, Base, Small, Medium, or Large v3 Turbo from the voice-settings
button in a chat; larger models improve quality at the cost of storage, memory, and transcription time. ARM64 devices select optimized dot-product, FP16, and i8mm CPU kernels at runtime, and the same dialog lets you choose Automatic or 1–8 transcription threads. For recordings of at least 30 seconds, completed chunks are queued for final-model transcription while capture continues; that work begins as soon as the model is ready and is reused after you stop. Recordings automatically stop at ten minutes to keep queued on-device audio bounded.

## Setup

Run qelg/harness and connect to its API (default `http://127.0.0.1:8000`). For a phone, use a
trusted encrypted network and HTTPS, for example `https://harness.example.ts.net:8000`. Plain HTTP
is accepted only for localhost, private-network addresses, and Tailscale hosts.

## Build and test

```bash
nix run .#gradle -- spotlessCheck lintDebug testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

The Nix runner provides JDK 17 and the Android SDK components required by this
project (API 35, build-tools 34.0.0 and 35.0.0, NDK 27.2.12479018, and CMake 3.22.1).
Pass any Gradle tasks or options after `--`.

The `whisperNative` Nix derivation prebuilds the whisper.cpp native libraries
(`nix build .#whisperNative`) and caches them in the Nix store. The `gradle`
wrapper points at those prebuilt libraries, so local Kotlin-only builds skip the
entire C++ toolchain and NDK compile. Building through `./gradlew` directly
(without `-PwhisperNativeDir=...`) falls back to the in-tree CMake build used by CI.

GitHub Actions builds `.#whisperNative` and serves it through the shared
`qelg` Cachix binary cache, so the C++ toolchain is only compiled once per
native source change. Set the `CACHIX_AUTH_TOKEN` repository secret (a token
with write access to the `qelg` cache) to push; pulling is public and needs no
token.

## UnifiedPush

Install and configure a UnifiedPush distributor (for example ntfy or Sunup) on the phone. After the app connects to a compatible Harness server, it asks the selected distributor for an endpoint and registers that endpoint with Harness. Android 13 and newer also ask for notification permission. No Google Play Services or FCM configuration is used. On Android 12 and newer, Harness encrypts each notification using an ephemeral P-256 key agreement and AES-GCM; the long-lived private key is non-exportable in Android Keystore, so the distributor receives no message metadata or content.
