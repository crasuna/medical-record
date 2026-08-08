package com.loveluke.medicalrecord.core.privacy

import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class PrivacyWindowControllerTest {
    @Test
    @Config(sdk = [32])
    fun `android 8 through 12L secures only the non-foreground window`() {
        val activityController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = activityController.get()
        val privacy = PrivacyWindowController(activity)
        privacy.install(activity)

        activityController.pause()

        assertTrue(privacy.isShieldVisible.value)
        assertTrue(activity.hasSecureFlag())

        activityController.resume()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(privacy.isShieldVisible.value)
        assertFalse(activity.hasSecureFlag())
    }

    @Test
    @Config(sdk = [33])
    fun `android 13 foreground remains screenshot-capable while neutral shield follows lifecycle`() {
        val activityController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = activityController.get()
        val privacy = PrivacyWindowController(activity)
        privacy.install(activity)

        activityController.pause()
        assertTrue(privacy.isShieldVisible.value)
        assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)

        activityController.resume()
        assertFalse(privacy.isShieldVisible.value)
        assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun ComponentActivity.hasSecureFlag(): Boolean =
        window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
}
