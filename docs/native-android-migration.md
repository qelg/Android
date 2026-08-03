# Native Android client

Harness Android is a native Kotlin/Jetpack Compose client for qelg/harness.

- `HarnessClient`: OkHttp transport for Harness REST, SSE compatibility, and the account event WebSocket.
- `ChatViewModel`: lifecycle-aware connection, session, history, and streaming state.
- `MainActivity`: responsive Compose session and chat UI.
- `SecureCredentials`: optional bearer-token storage backed by Android Keystore.

The client talks only to qelg/harness. Unsupported capabilities are disabled instead of routing
data through another backend. Release signing is documented in `native-android/SIGNING.md`.

## Account event stream

The client loads `/sessions` once and uses its `X-Harness-Event-Cursor` to start the
account-wide `/events` WebSocket. It subscribes to the event types needed by the
session list, chat timeline, children, and event-detail view. Message events also carry the server-side `message` projection,
while the raw event remains available for event details. Durable events carry a
`cursor`; transient deltas are delivered live but do not advance that cursor.

The socket is stopped while the app is in the background and resumed from the last
durable cursor when it returns. A reconnect refreshes the `/sessions` snapshot once,
then resumes event delivery. The old per-session SSE watcher remains in the transport
only as a compatibility path for older Harness servers.
