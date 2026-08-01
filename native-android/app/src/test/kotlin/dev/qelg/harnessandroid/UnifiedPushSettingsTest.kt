package dev.qelg.harnessandroid

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UnifiedPushSettingsTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsShowsRegistrationAndEncryptionState() {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MaterialTheme { UnifiedPushSettingsDialog(activity, onDismiss = {}) }
            }
        }

        composeRule.onNodeWithText("UnifiedPush").assertIsDisplayed()
        composeRule.onNodeWithText("Enable UnifiedPush").assertIsDisplayed()
        composeRule.onNodeWithText("Register or refresh").assertIsDisplayed()
    }

    @Test
    fun settingsShowsRegisteredPushUrl() {
        val endpoint = "https://push.example.test/endpoint"
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MaterialTheme {
                    UnifiedPushSettingsDialog(activity, onDismiss = {}, initialEndpoint = endpoint)
                }
            }
        }

        composeRule.onNodeWithText("Push URL").assertIsDisplayed()
        composeRule.onNodeWithText(endpoint).assertIsDisplayed()
    }
}
