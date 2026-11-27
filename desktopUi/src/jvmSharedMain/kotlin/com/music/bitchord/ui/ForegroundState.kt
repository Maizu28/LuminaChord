package com.music.bitchord.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Whether the desktop window is currently visible on screen (even when another window has focus).
 *
 * On Compose Multiplatform Desktop, losing window focus (e.g. clicking another app or moving
 * the cursor away) transitions the window's lifecycle from RESUMED to STARTED while keeping it
 * visible. Only minimizing or hiding the window to tray drops below STARTED (CREATED/DESTROYED),
 * so gating on STARTED keeps synced lyrics advancing when unfocused while still pausing
 * off-screen animations when minimized or closed to tray.
 */
internal fun isDesktopForegroundLifecycle(state: Lifecycle.State): Boolean =
    state.isAtLeast(Lifecycle.State.STARTED)

@Composable
fun rememberIsForeground(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) {
        mutableStateOf(isDesktopForegroundLifecycle(lifecycle.currentState))
    }
    DisposableEffect(lifecycle) {
        // Every event rather than ON_START/ON_STOP alone: the state is read
        // back off the lifecycle instead of inferred from which event arrived,
        // so there is no transition this can be left out of step by.
        val observer = LifecycleEventObserver { owner, _ ->
            foreground = isDesktopForegroundLifecycle(owner.lifecycle.currentState)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return foreground
}
