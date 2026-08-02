package dev.qelg.harnessandroid

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.qelg.harnessandroid.data.MessageQueueMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MessageSendButtonTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun activeSendOffersBothExplicitQueueChoices() {
        val queued = mutableListOf<MessageQueueMode>()
        setContent {
            MessageSendButton(active = true, enabled = true, onSend = {}, onQueue = queued::add)
        }

        composeRule.onNodeWithContentDescription("Choose when to send").performClick()
        composeRule.onNodeWithText("After next tool response").assertIsDisplayed()
        composeRule.onNodeWithText("After response").assertIsDisplayed().performClick()

        composeRule.runOnIdle { assertEquals(listOf(MessageQueueMode.AfterResponse), queued) }
    }

    @Test
    fun inactiveSendRemainsOneTap() {
        var sent = false
        setContent {
            MessageSendButton(
                active = false,
                enabled = true,
                onSend = { sent = true },
                onQueue = {},
            )
        }

        composeRule.onNodeWithContentDescription("Send").performClick()

        composeRule.runOnIdle { assertTrue(sent) }
        composeRule.onNodeWithText("After response").assertDoesNotExist()
    }

    private fun setContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.setContent { MaterialTheme { content() } }
        }
    }
}
