# Harness Android

A native Kotlin/Jetpack Compose Android client for [qelg/harness](https://github.com/qelg/harness).

**[Download the newest signed Android APK](https://github.com/qelg/Android/releases/download/latest/harness-android-latest.apk)**

## Features

- List, search, create, and resume persistent Harness sessions with live sessions first, then ordered by server activity
- Show server-authoritative running, finished, unread, read, and archived session states
- Archive chats with a left swipe; archived chats return automatically after new activity
- Load durable message history and stream assistant/tool activity over Harness SSE
- Select a provider/model for a session
- Keep per-session timelines, drafts, and read state while switching between chats
- Store an optional bearer token using Android Keystore
- Discover providers and tools exposed by Harness
- Record, transcribe, and send voice messages entirely on-device with a configurable Whisper model (verified model downloads on first use)

Capabilities qelg/harness does not expose—approval responses, run cancellation, and detailed token/context
accounting—stay unavailable rather than using a separate legacy backend. Voice transcription is local and does
not upload microphone audio. A recording remains bound to the chat where it was started, even when switching chats before transcription finishes. Choose Tiny, Base, Small, Medium, or Large v3 Turbo from the voice-settings
button in a chat; larger models improve quality at the cost of storage, memory, and transcription time.

## Setup

Run qelg/harness and connect to its API (default `http://127.0.0.1:8000`). For a phone, use a
trusted encrypted network and HTTPS, for example `https://harness.example.ts.net:8000`. Plain HTTP
is accepted only for localhost, private-network addresses, and Tailscale hosts.

## Build and test

```bash
./native-android/gradlew -p native-android spotlessCheck lintDebug testDebugUnitTest assembleDebug assembleDebugAndroidTest
```
