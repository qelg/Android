package dev.qelg.harnessandroid

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class MainActivityTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun connectionScreenExposesNativeComposeAuthenticationFlow() {
        composeRule.onNodeWithText("Connect to Hermes").assertIsDisplayed()
        composeRule.onNodeWithText("Server URL").assertIsDisplayed()
        composeRule.onNodeWithText("Session token (optional)").assertIsDisplayed()
        composeRule.onNodeWithText("Connect").assertIsDisplayed()
    }
}
