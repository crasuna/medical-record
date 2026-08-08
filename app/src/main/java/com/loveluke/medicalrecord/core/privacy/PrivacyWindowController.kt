package com.loveluke.medicalrecord.core.privacy

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Keeps medical content out of Overview/Recents while leaving user-initiated screenshots and
 * screen sharing available when the activity is in the foreground.
 *
 * Android 13 and newer provide a dedicated Recents API. On Android 8-12L, a neutral Compose
 * shield is shown as the activity loses focus and FLAG_SECURE is applied only while the activity
 * is not in the foreground. FLAG_SECURE is deliberately not permanent.
 */
class PrivacyWindowController(
    private val activity: Activity,
) : DefaultLifecycleObserver {
    private val shieldState = mutableStateOf(false)
    val isShieldVisible: State<Boolean> = shieldState

    fun install(owner: LifecycleOwner) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(false)
        }
        owner.lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        shieldState.value = false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Post the clear so the first resumed frame remains protected while Compose replaces
            // the neutral shield with medical content.
            activity.window.decorView.post {
                if (owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        shieldState.value = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        owner.lifecycle.removeObserver(this)
    }
}

/** Places a neutral surface over all app content while the host activity is not foregrounded. */
@Composable
fun PrivacyShieldHost(
    shieldVisible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        content()
        if (shieldVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
            ) {}
        }
    }
}
