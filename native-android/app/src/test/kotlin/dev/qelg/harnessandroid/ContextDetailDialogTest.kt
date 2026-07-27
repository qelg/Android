package dev.qelg.harnessandroid

import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.qelg.harnessandroid.data.ChatItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContextDetailDialogTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun contextDetailUsesExactSafeHostBounds() {
        assertUsesSafeHost { FullScreenContextDetailScreen("Detail", {}) {} }
    }

    @Test
    fun toolCallDetailUsesExactSafeHostBounds() {
        assertUsesSafeHost {
            ToolCallScreen(
                ChatItem.Tool(id = "tool-1", name = "read_file", state = "completed"),
                onDismiss = {},
            )
        }
    }

    @Test
    fun toolValueDetailUsesExactSafeHostBounds() {
        assertUsesSafeHost {
            ToolValueScreen(
                toolName = "read_file",
                timestamp = null,
                value = "result",
                onDismiss = {},
            )
        }
    }

    @Test
    fun missingContextDataKeepsDismissibleOverlayVisible() {
        setTestContent {
            ContextDetailScreen(page = ContextDetailPage.SystemPrompt, usage = null, onDismiss = {})
        }

        composeRule.onNodeWithTag("full-screen-detail").assertIsDisplayed()
        composeRule.onNodeWithText("Details currently unavailable.").assertIsDisplayed()
    }

    @Test
    fun fullScreenDetailHidesUnderlyingChatFromAccessibility() {
        setTestContent {
            Box(Modifier.size(360.dp, 640.dp)) {
                Box(Modifier.fillMaxSize().fullScreenDetailBackground(active = true)) {
                    Text("Underlying chat action")
                }
                FullScreenContextDetailScreen("Detail", {}) {}
            }
        }

        composeRule.onNodeWithText("Underlying chat action").assertDoesNotExist()
    }

    @Test
    fun liveToolDetailResolvesLatestTopLevelAndNestedState() {
        val opened = ChatItem.Tool("tool-1", "terminal", "running")
        val updated = ChatItem.Tool("tool-1", "terminal", "completed", result = "finished")
        val nested = ChatItem.Tool("tool-2", "read_file", "failed", error = "failed")
        val items = listOf(updated, ChatItem.ParallelToolGroup("batch", listOf(nested)))

        assertEquals(updated, currentToolForDetail(items, opened))
        assertEquals(
            nested,
            currentToolForDetail(items, ChatItem.Tool("tool-2", "read_file", "running")),
        )
    }

    @Test
    fun fullScreenDetailBlocksTouchesFromReachingChatBehindIt() {
        var backgroundClicks = 0
        setTestContent {
            Box(Modifier.size(360.dp, 640.dp)) {
                Box(Modifier.fillMaxSize().clickable { backgroundClicks++ })
                FullScreenContextDetailScreen("Detail", {}) {}
            }
        }

        composeRule.onNodeWithTag("full-screen-detail").performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(0, backgroundClicks) }
    }

    @Test
    fun finalDetailLineCanBeScrolledFullyIntoView() {
        setTestContent {
            FullScreenContextDetailScreen(title = "Detail", onDismiss = {}) {
                repeat(120) { Text("Detail line $it") }
                Text("Final detail line")
            }
        }

        composeRule.onNodeWithText("Final detail line").assertIsNotDisplayed()
        composeRule.onNodeWithText("Final detail line").performScrollTo().assertIsDisplayed()
    }

    private fun assertUsesSafeHost(content: @androidx.compose.runtime.Composable () -> Unit) {
        setTestContent {
            Box(Modifier.size(360.dp, 640.dp)) {
                Box(
                    Modifier.fillMaxSize()
                        .padding(start = 24.dp, end = 32.dp, bottom = 48.dp)
                        .testTag("safe-host")
                ) {
                    content()
                }
            }
        }

        val safeBounds = composeRule.onNodeWithTag("safe-host").fetchSemanticsNode().boundsInRoot
        val detailBounds =
            composeRule.onNodeWithTag("full-screen-detail").fetchSemanticsNode().boundsInRoot
        assertEquals(safeBounds, detailBounds)
    }

    private fun setTestContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.setContent { MaterialTheme { content() } }
        }
    }
}
