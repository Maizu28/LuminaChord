package com.music.bitchord.ui

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundStateTest {
    @Test
    fun unfocusedVisibleDesktopWindowRemainsForegroundForLyricsAndMotion() {
        assertTrue("Focused desktop window must animate lyrics", isDesktopForegroundLifecycle(Lifecycle.State.RESUMED))
        assertTrue(
            "Visible unfocused desktop window (STARTED) must keep advancing lyrics",
            isDesktopForegroundLifecycle(Lifecycle.State.STARTED),
        )
    }

    @Test
    fun minimizedOrHiddenDesktopWindowStopsAnimating() {
        assertFalse(isDesktopForegroundLifecycle(Lifecycle.State.CREATED))
        assertFalse(isDesktopForegroundLifecycle(Lifecycle.State.INITIALIZED))
        assertFalse(isDesktopForegroundLifecycle(Lifecycle.State.DESTROYED))
    }
}
