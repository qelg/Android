# Harness Android

A native Kotlin/Jetpack Compose Android client. The backend migration from Hermes Agent to qelg/harness is tracked in the follow-up change.

## Download

**[Download the newest signed Android APK](https://github.com/qelg/Android/releases/download/latest/harness-android-latest.apk)**

This stable link is updated automatically after every successful CI build on `main`.

The app uses Hermes Agent's authenticated API Server for the coherent session/chat runtime. Optional voice transcription can be routed separately through the Dashboard/Web backend without mixing session IDs, history, or event streams.

## MVP features

- Responsive, fully paginated session list including root, compression-child, and `delegate_task` sessions
- Create and resume persistent Hermes sessions
- Search sessions by title, preview, source, or ID
- Stream assistant responses and structured tool activity through controllable API runs
- Review dangerous tool requests and allow once, always allow, or deny
- Stop a running response through the server-side run control endpoint
- Optionally transcribe voice input through the Dashboard/Web backend
- Group four or more consecutive tool calls with expandable details
- Store API and optional Dashboard credentials in platform secure storage
- Show cumulative token usage and enabled API-server toolsets

The API Server currently does not expose audio transcription, interactive clarify responses, detailed context composition, or per-session model switching. Audio transcription can be restored safely through the optional Dashboard route; the other Dashboard RPCs remain disabled to keep the API session/runtime path coherent.

## Hermes setup

Enable Hermes Agent's authenticated API Server and connect using its URL and `API_SERVER_KEY`. The default local endpoint is:

```text
http://127.0.0.1:8642
```

For a phone, publish it through a trusted encrypted network such as Tailscale and use HTTPS, for example `https://host.example.ts.net:8643`. Never expose the API key or an unencrypted public endpoint.

HTTPS is required for public hosts. Plain HTTP is accepted only for localhost, private-network addresses, and Tailscale hosts because the underlying tunnel already encrypts tailnet traffic.

## Build and test

```bash
./native-android/gradlew -p native-android spotlessCheck lintDebug testDebugUnitTest assembleDebug assembleDebugAndroidTest
```
