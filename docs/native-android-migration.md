# Native Android client

Harness Android is a native Kotlin/Jetpack Compose client for qelg/harness.

- `HarnessClient`: OkHttp transport for Harness REST and SSE endpoints.
- `ChatViewModel`: lifecycle-aware connection, session, history, and streaming state.
- `MainActivity`: responsive Compose session and chat UI.
- `SecureCredentials`: optional bearer-token storage backed by Android Keystore.

The client talks only to qelg/harness. Unsupported capabilities are disabled instead of routing
data through another backend. Release signing is documented in `native-android/SIGNING.md`.
