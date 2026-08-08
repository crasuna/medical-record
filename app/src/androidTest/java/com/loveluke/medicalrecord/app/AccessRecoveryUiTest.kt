package com.loveluke.medicalrecord.app

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loveluke.medicalrecord.R
import com.loveluke.medicalrecord.core.designsystem.MedicalRecordTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessRecoveryUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun clearCallbackRunsOnlyAfterTwoExplicitConfirmations() {
        val clearCalls = AtomicInteger(0)
        composeRule.setContent {
            MedicalRecordTheme {
                LockedAccessScreen(
                    clearPreviouslyFailed = false,
                    onRetry = {},
                    onClearConfirmed = { clearCalls.incrementAndGet() },
                )
            }
        }

        composeRule
            .onNodeWithText(context.getString(R.string.database_clear_action))
            .performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.database_clear_confirm_title))
            .assertIsDisplayed()
        assertEquals(0, clearCalls.get())

        composeRule
            .onNodeWithText(context.getString(R.string.database_clear_confirm_action))
            .performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.database_clear_second_title))
            .assertIsDisplayed()
        assertEquals(0, clearCalls.get())

        composeRule
            .onNodeWithText(context.getString(R.string.database_clear_second_action))
            .performClick()
        composeRule.waitForIdle()

        assertEquals(1, clearCalls.get())
    }
}
