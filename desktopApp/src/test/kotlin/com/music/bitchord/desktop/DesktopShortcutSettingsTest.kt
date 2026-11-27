package com.music.bitchord.desktop

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import java.awt.Panel
import java.awt.event.InputEvent
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(InternalComposeUiApi::class)
class DesktopShortcutSettingsTest {

    private val awtSource = Panel()

    @BeforeTest
    fun resetBefore() {
        DesktopShortcutSettings.clearTypingFocus()
        DesktopShortcutSettings.isRecording = false
        DesktopShortcutSettings.windowKeyHandler = null
        DesktopShortcutSettings.resetToDefaults()
    }

    @AfterTest
    fun resetAfter() {
        DesktopShortcutSettings.clearTypingFocus()
        DesktopShortcutSettings.isRecording = false
        DesktopShortcutSettings.windowKeyHandler = null
        DesktopShortcutSettings.resetToDefaults()
    }

    private fun composeKeyDown(
        vkCode: Int,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        meta: Boolean = false,
    ): KeyEvent {
        var mods = 0
        if (ctrl) mods = mods or InputEvent.CTRL_DOWN_MASK
        if (alt) mods = mods or InputEvent.ALT_DOWN_MASK
        if (shift) mods = mods or InputEvent.SHIFT_DOWN_MASK
        if (meta) mods = mods or InputEvent.META_DOWN_MASK
        val awt = AwtKeyEvent(
            awtSource,
            AwtKeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            mods,
            vkCode,
            AwtKeyEvent.CHAR_UNDEFINED,
        )
        return KeyEvent(
            key = Key(vkCode),
            type = KeyEventType.KeyDown,
            codePoint = 0,
            isCtrlPressed = ctrl,
            isMetaPressed = meta,
            isAltPressed = alt,
            isShiftPressed = shift,
            nativeEvent = awt,
        )
    }

    @Test
    fun defaultShortcutsTriggerAllActionsAndWindowDispatchWorks() {
        val triggered = mutableListOf<String>()
        val handler: (KeyEvent) -> Boolean = { ev ->
            DesktopShortcutSettings.handleKeyEvent(
                event = ev,
                onPlayPause = { triggered.add("play_pause") },
                onNextTrack = { triggered.add("next") },
                onPreviousTrack = { triggered.add("previous") },
                onVolumeUp = { triggered.add("vol_up") },
                onVolumeDown = { triggered.add("vol_down") },
                onToggleMute = { triggered.add("mute") },
                onSeekForward = { triggered.add("seek_fwd") },
                onSeekBackward = { triggered.add("seek_back") },
                onFocusSearch = { triggered.add("search") },
                onToggleLyrics = { triggered.add("lyrics") },
                onToggleQueue = { triggered.add("queue") },
                onToggleFullscreen = { triggered.add("fullscreen") },
                onBack = {
                    triggered.add("back")
                    true
                },
                onRefresh = { triggered.add("refresh") },
            )
        }
        DesktopShortcutSettings.windowKeyHandler = handler

        assertTrue(DesktopShortcutSettings.dispatchWindowKeyEvent(composeKeyDown(AwtKeyEvent.VK_SPACE)))
        assertTrue(DesktopShortcutSettings.dispatchWindowKeyEvent(composeKeyDown(AwtKeyEvent.VK_RIGHT, ctrl = !DesktopPlatform.isMac, meta = DesktopPlatform.isMac)))
        assertTrue(DesktopShortcutSettings.dispatchWindowKeyEvent(composeKeyDown(AwtKeyEvent.VK_LEFT, ctrl = !DesktopPlatform.isMac, meta = DesktopPlatform.isMac)))
        assertTrue(DesktopShortcutSettings.dispatchWindowKeyEvent(composeKeyDown(AwtKeyEvent.VK_RIGHT)))
        assertTrue(DesktopShortcutSettings.dispatchWindowKeyEvent(composeKeyDown(AwtKeyEvent.VK_LEFT)))
        assertTrue(DesktopShortcutSettings.dispatchWindowKeyEvent(composeKeyDown(AwtKeyEvent.VK_KP_RIGHT)))
        assertTrue(DesktopShortcutSettings.dispatchWindowKeyEvent(composeKeyDown(AwtKeyEvent.VK_UP)))
        assertTrue(DesktopShortcutSettings.dispatchWindowKeyEvent(composeKeyDown(AwtKeyEvent.VK_DOWN)))
        assertTrue(DesktopShortcutSettings.dispatchWindowKeyEvent(composeKeyDown(AwtKeyEvent.VK_M, ctrl = !DesktopPlatform.isMac, meta = DesktopPlatform.isMac)))
        assertTrue(DesktopShortcutSettings.dispatchWindowKeyEvent(composeKeyDown(AwtKeyEvent.VK_F, ctrl = !DesktopPlatform.isMac, meta = DesktopPlatform.isMac)))
        assertTrue(DesktopShortcutSettings.dispatchWindowKeyEvent(composeKeyDown(AwtKeyEvent.VK_L, ctrl = !DesktopPlatform.isMac, meta = DesktopPlatform.isMac)))
        assertTrue(DesktopShortcutSettings.dispatchWindowKeyEvent(composeKeyDown(AwtKeyEvent.VK_Q, ctrl = !DesktopPlatform.isMac, meta = DesktopPlatform.isMac)))
        assertTrue(DesktopShortcutSettings.dispatchWindowKeyEvent(composeKeyDown(AwtKeyEvent.VK_F11)))
        assertTrue(DesktopShortcutSettings.dispatchWindowKeyEvent(composeKeyDown(AwtKeyEvent.VK_R, ctrl = !DesktopPlatform.isMac, meta = DesktopPlatform.isMac)))
        assertTrue(DesktopShortcutSettings.dispatchWindowKeyEvent(composeKeyDown(AwtKeyEvent.VK_ESCAPE)))

        assertEquals(
            listOf(
                "play_pause",
                "next",
                "previous",
                "seek_fwd",
                "seek_back",
                "seek_fwd",
                "vol_up",
                "vol_down",
                "mute",
                "search",
                "lyrics",
                "queue",
                "fullscreen",
                "refresh",
                "back",
            ),
            triggered,
        )
    }

    @Test
    fun typingTokensPreventStuckLockAndIsolateTextInput() {
        val tokenA = Any()
        val tokenB = Any()
        val fired = mutableListOf<String>()

        fun dispatch(ev: KeyEvent): Boolean = DesktopShortcutSettings.handleKeyEvent(
            event = ev,
            onPlayPause = { fired.add("play_pause") },
            onNextTrack = { fired.add("next") },
            onPreviousTrack = { fired.add("previous") },
            onVolumeUp = {},
            onVolumeDown = {},
            onToggleMute = {},
            onSeekForward = { fired.add("seek_fwd") },
            onSeekBackward = {},
            onFocusSearch = {},
            onToggleLyrics = { fired.add("lyrics") },
            onToggleQueue = {},
            onToggleFullscreen = {},
            onBack = { false },
        )

        DesktopShortcutSettings.setTypingFocus(tokenA, true)
        assertTrue(DesktopShortcutSettings.isTypingActive)
        assertFalse(dispatch(composeKeyDown(AwtKeyEvent.VK_SPACE)))
        assertFalse(dispatch(composeKeyDown(AwtKeyEvent.VK_RIGHT)))
        // Modified shortcut like Ctrl+L still works while typing
        assertTrue(dispatch(composeKeyDown(AwtKeyEvent.VK_L, ctrl = !DesktopPlatform.isMac, meta = DesktopPlatform.isMac)))

        DesktopShortcutSettings.setTypingFocus(tokenB, true)
        DesktopShortcutSettings.setTypingFocus(tokenA, false)
        assertTrue(DesktopShortcutSettings.isTypingActive)

        DesktopShortcutSettings.setTypingFocus(tokenB, false)
        assertFalse(DesktopShortcutSettings.isTypingActive)
        assertTrue(dispatch(composeKeyDown(AwtKeyEvent.VK_SPACE)))
        assertTrue(dispatch(composeKeyDown(AwtKeyEvent.VK_RIGHT)))
        assertEquals(listOf("lyrics", "play_pause", "seek_fwd"), fired)
    }
}
