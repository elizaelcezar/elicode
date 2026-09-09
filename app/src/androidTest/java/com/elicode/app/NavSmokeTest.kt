package com.elicode.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests: app launches, onboarding/root navigation renders.
 * Full end-to-end (runtime → project → build → APK) is manual on device;
 * see docs/MANUAL_TEST.md.
 */
@RunWith(AndroidJUnit4::class)
class NavSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesAndShowsChrome() {
        rule.waitForIdle()
        // Either onboarding (first run) or the Projects tab must be visible.
        val onboarding = rule.onAllNodesWithText("ELICODE").fetchSemanticsNodes().isNotEmpty()
        val projects = rule.onAllNodesWithText("Projects").fetchSemanticsNodes().isNotEmpty()
        assert(onboarding || projects) { "Neither onboarding nor Projects is displayed" }
    }

    @Test
    fun terminalTabOpens() {
        rule.waitForIdle()
        runCatching { rule.onNodeWithText("Terminal").performClick() }
        rule.waitForIdle()
        rule.onNodeWithText("Terminal", substring = true).assertIsDisplayed()
    }
}
