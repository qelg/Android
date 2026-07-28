package dev.qelg.harnessandroid

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.qelg.harnessandroid.data.HarnessSession
import dev.qelg.harnessandroid.data.SessionEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionEventDetailTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun navigatesFromSessionDetailsToEventsAndCompleteJson() {
        val event =
            SessionEvent.fromJson(
                Json.parseToJsonElement(
                        """{"id":9,"name":"llm.run.started","created_at_ms":1752757200123,"producer":"llm-provider-runner","tags":{"session":"sess_1"},"payload":{"model":"test-model","future":{"kept":true}}}"""
                    )
                    .jsonObject
            )
        var page by mutableStateOf("details")
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MaterialTheme {
                    when (page) {
                        "details" ->
                            SessionDetailScreen(
                                session = HarnessSession("sess_1", "Demo session"),
                                eventCount = 1,
                                onOpenEvents = { page = "events" },
                                onDismiss = {},
                            )
                        "events" ->
                            SessionEventsScreen(
                                session = HarnessSession("sess_1", "Demo session"),
                                events = listOf(event),
                                loading = false,
                                error = null,
                                onLoad = {},
                                onRetry = {},
                                onOpenEvent = { page = "payload" },
                                onDismiss = { page = "details" },
                            )
                        else -> SessionEventPayloadScreen(event) { page = "events" }
                    }
                }
            }
        }

        composeRule.onNodeWithText("Events").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("llm.run.started").assertIsDisplayed()
        composeRule.onNodeWithText("Originator: llm-provider-runner").assertIsDisplayed()
        composeRule.onNodeWithText("llm.run.started").performClick()
        composeRule.onNodeWithText("\"future\": {", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("\"kept\": true", substring = true).assertIsDisplayed()
    }

    @Test
    fun causationCanvasConnectsRowsWithoutShowingInternalIds() {
        val cause =
            SessionEvent.fromJson(
                Json.parseToJsonElement(
                        """{"id":1,"name":"llm.run.requested","payload":{"request":"cause"}}"""
                    )
                    .jsonObject
            )
        val effect =
            SessionEvent.fromJson(
                Json.parseToJsonElement(
                        """{"id":2,"name":"llm.run.started","causation_id":1,"payload":{}}"""
                    )
                    .jsonObject
            )
        val unresolved =
            SessionEvent.fromJson(
                Json.parseToJsonElement(
                        """{"id":3,"name":"llm.delta","causation_id":99,"payload":{}}"""
                    )
                    .jsonObject
            )
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MaterialTheme {
                    SessionEventsScreen(
                        session = HarnessSession("sess_1", "Demo"),
                        events = listOf(cause, effect, unresolved),
                        loading = false,
                        error = null,
                        onLoad = {},
                        onRetry = {},
                        onOpenEvent = {},
                        onDismiss = {},
                    )
                }
            }
        }

        composeRule
            .onNodeWithContentDescription("llm.run.started caused by llm.run.requested")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Caused by", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("#1", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("#99", substring = true).assertDoesNotExist()
    }

    @Test
    fun eventsScreenShowsLoadingEmptyAndErrorStates() {
        val session = HarnessSession("sess_1", "Demo")
        var mode by mutableStateOf("loading")
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MaterialTheme {
                    SessionEventsScreen(
                        session = session,
                        events = emptyList(),
                        loading = mode == "loading",
                        error = if (mode == "error") ErrorMessage("Harness HTTP 404") else null,
                        onLoad = {},
                        onRetry = {},
                        onOpenEvent = {},
                        onDismiss = {},
                    )
                }
            }
        }

        composeRule.runOnIdle { mode = "empty" }
        composeRule.onNodeWithText("No events for this session.").assertIsDisplayed()
        composeRule.runOnIdle { mode = "error" }
        composeRule.onNodeWithText("Events could not be loaded").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
    }
}
