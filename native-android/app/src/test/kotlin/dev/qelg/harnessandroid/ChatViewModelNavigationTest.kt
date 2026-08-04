package dev.qelg.harnessandroid

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import dev.qelg.harnessandroid.data.ConnectionConfig
import dev.qelg.harnessandroid.data.HarnessClient
import dev.qelg.harnessandroid.data.HarnessContainer
import dev.qelg.harnessandroid.data.HarnessSession
import dev.qelg.harnessandroid.data.SessionId
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.buildJsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatViewModelNavigationTest {
    @Test
    fun containerOnlySessionSelectsAndHydratesWithoutInventingRootAuthority() {
        val requests = ConcurrentLinkedQueue<String>()
        server { request ->
                requests += request.path.orEmpty()
                when (request.path) {
                    "/sessions/child/messages" ->
                        MockResponse()
                            .setBody("""[{"id":1,"role":"user","content":"from storage"}]""")
                    "/sessions/child/events" -> MockResponse().setBody("[]")
                    "/providers" -> MockResponse().setBody("""{"providers":[]}""")
                    "/sessions/child/model-selection" -> MockResponse().setBody("{}")
                    else -> MockResponse().setBody("{}")
                }
            }
            .use { fixture ->
                val viewModel = viewModelWithClient(fixture.client)
                val container =
                    HarnessContainer("container", "workspace", "child", 1024, "Stored child")

                viewModel.selectContainerSession(container)

                val selected = viewModel.state.value
                val record = selected.harness.sessionsById.getValue(SessionId("child"))
                assertEquals(SessionId("child"), selected.ui.selectedSessionId)
                assertFalse(record.resolved)
                assertEquals("Stored child", record.name.value)
                assertNull(record.parentSessionId.value)
                assertTrue(overviewSessions(selected).none { it.id == SessionId("child") })

                awaitMain {
                    "/sessions/child/messages" in requests && "/sessions/child/events" in requests
                }
                val hydrated =
                    viewModel.state.value.harness.sessionsById.getValue(SessionId("child"))
                assertTrue(hydrated.content is SynchronizedData.Complete)
                assertNull(sessionContent(hydrated)?.loadBarrier)
                assertEquals(
                    listOf("from storage"),
                    sessionTimeline(viewModel.state.value, SessionId("child")).map {
                        (it as dev.qelg.harnessandroid.data.ChatItem.Message).text
                    },
                )
            }
    }

    @Test
    fun websocketCapabilityFallbackRestartsSseForTheNewSelectedRuntime() {
        val requests = ConcurrentLinkedQueue<String>()
        server { request ->
                requests += request.path.orEmpty()
                when (request.path) {
                    "/sessions/runtime-one/messages/updates",
                    "/sessions/runtime-two/messages/updates" ->
                        MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    "/providers" -> MockResponse().setBody("""{"providers":[]}""")
                    else -> MockResponse().setBody("[]")
                }
            }
            .use { fixture ->
                val first = SessionId("stored-one")
                val second = SessionId("stored-two")
                val initial =
                    ChatReducer.mergeSessions(
                        ChatUiState(
                            ui = LocalUiState(configured = true, selectedSessionId = first),
                            harness = HarnessState(lastAppliedEventId = EventId(41)),
                        ),
                        listOf(
                            sessionDataFromTransport(
                                HarnessSession(first.value, "One", runtimeId = "runtime-one")
                            ),
                            sessionDataFromTransport(
                                HarnessSession(second.value, "Two", runtimeId = "runtime-two")
                            ),
                        ),
                    )
                val viewModel = viewModelWithClient(fixture.client, initial)
                setPrivateField(viewModel, "appStarted", true)

                handleTransport(
                    viewModel,
                    dev.qelg.harnessandroid.data.GatewayEvent(
                        "connection.events_unsupported",
                        null,
                        emptyMap(),
                    ),
                )
                awaitMain { "/sessions/runtime-one/messages/updates" in requests }
                assertTrue(viewModel.state.value.harness.connection is ConnectionState.Connected)
                assertEquals(41L, viewModel.state.value.harness.lastAppliedEventId?.value)

                viewModel.select(second)
                awaitMain { "/sessions/runtime-two/messages/updates" in requests }
                assertEquals(second, viewModel.state.value.ui.selectedSessionId)
                assertEquals(41L, viewModel.state.value.harness.lastAppliedEventId?.value)
            }
    }

    private fun viewModelWithClient(
        client: HarnessClient,
        initialState: ChatUiState = ChatUiState(ui = LocalUiState(configured = true)),
    ): ChatViewModel =
        ChatViewModel(RuntimeEnvironment.getApplication(), SavedStateHandle(), initialState) {
                _,
                _,
                _,
                _ ->
                buildJsonObject {}
            }
            .also { setPrivateField(it, "client", client) }

    private fun handleTransport(
        viewModel: ChatViewModel,
        event: dev.qelg.harnessandroid.data.GatewayEvent,
    ) {
        ChatViewModel::class
            .java
            .getDeclaredMethod(
                "handleTransport",
                dev.qelg.harnessandroid.data.GatewayEvent::class.java,
            )
            .apply { isAccessible = true }
            .invoke(viewModel, event)
    }

    private fun setPrivateField(instance: Any, name: String, value: Any?) {
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
    }

    private fun awaitMain(condition: () -> Boolean) {
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(25)
        }
        fail("Timed out waiting for expected request")
    }

    private fun server(dispatch: (RecordedRequest) -> MockResponse): Fixture {
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = dispatch(request)
            }
        server.start()
        val client =
            HarnessClient(
                ConnectionConfig(server.url("/").toString()),
                CoroutineScope(Dispatchers.Unconfined),
            )
        return Fixture(server, client)
    }

    private class Fixture(val server: MockWebServer, val client: HarnessClient) : AutoCloseable {
        override fun close() {
            client.close()
            server.shutdown()
        }
    }
}
