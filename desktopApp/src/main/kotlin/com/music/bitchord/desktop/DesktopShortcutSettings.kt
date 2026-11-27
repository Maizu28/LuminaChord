package com.music.bitchord.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Supported shortcut actions that users can customize in LuminaChord Windows Desktop.
 */
internal enum class DesktopShortcutAction(
    val title: String,
    val description: String,
    val category: String,
    val defaultBinding: ShortcutBinding,
) {
    PLAY_PAUSE(
        title = "Putar / Jeda (Play / Pause)",
        description = "Memutar atau menjeda lagu yang sedang diputar",
        category = "Pemutaran",
        defaultBinding = ShortcutBinding(keyCode = AwtKeyEvent.VK_SPACE),
    ),
    NEXT_TRACK(
        title = "Lagu Berikutnya (Next Track)",
        description = "Beralih ke lagu berikutnya dalam antrean",
        category = "Pemutaran",
        defaultBinding = ShortcutBinding(keyCode = AwtKeyEvent.VK_RIGHT, ctrl = true),
    ),
    PREVIOUS_TRACK(
        title = "Lagu Sebelumnya (Previous Track)",
        description = "Beralih ke lagu sebelumnya",
        category = "Pemutaran",
        defaultBinding = ShortcutBinding(keyCode = AwtKeyEvent.VK_LEFT, ctrl = true),
    ),
    SEEK_FORWARD(
        title = "Maju 5 Detik (Seek +5s)",
        description = "Mempercepat posisi lagu 5 detik ke depan",
        category = "Pemutaran",
        defaultBinding = ShortcutBinding(keyCode = AwtKeyEvent.VK_RIGHT),
    ),
    SEEK_BACKWARD(
        title = "Mundur 5 Detik (Seek -5s)",
        description = "Memundurkan posisi lagu 5 detik ke belakang",
        category = "Pemutaran",
        defaultBinding = ShortcutBinding(keyCode = AwtKeyEvent.VK_LEFT),
    ),
    VOLUME_UP(
        title = "Naikkan Volume (Volume Up +5%)",
        description = "Menaikkan volume audio aplikasi sebesar 5%",
        category = "Audio & Suara",
        defaultBinding = ShortcutBinding(keyCode = AwtKeyEvent.VK_UP),
    ),
    VOLUME_DOWN(
        title = "Turunkan Volume (Volume Down -5%)",
        description = "Menurunkan volume audio aplikasi sebesar 5%",
        category = "Audio & Suara",
        defaultBinding = ShortcutBinding(keyCode = AwtKeyEvent.VK_DOWN),
    ),
    BACK_OR_UNDO(
        title = "Kembali / Undo (Back / Undo)",
        description = "Kembali ke halaman sebelumnya atau batalkan dialog/aksi",
        category = "Tampilan & Navigasi",
        defaultBinding = ShortcutBinding(keyCode = AwtKeyEvent.VK_ESCAPE),
    ),
    REFRESH(
        title = "Muat Ulang (Refresh)",
        description = "Memuat ulang konten atau halaman saat ini",
        category = "Tampilan & Navigasi",
        defaultBinding = ShortcutBinding(keyCode = AwtKeyEvent.VK_R, ctrl = true),
    ),
    TOGGLE_MUTE(
        title = "Bisukan Suara (Mute / Unmute)",
        description = "Mematikan atau mengembalikan volume audio",
        category = "Audio & Suara",
        defaultBinding = ShortcutBinding(keyCode = AwtKeyEvent.VK_M, ctrl = true),
    ),
    FOCUS_SEARCH(
        title = "Cari Lagu / Artis (Focus Search)",
        description = "Fokuskan cursor ke kolom pencarian",
        category = "Tampilan & Navigasi",
        defaultBinding = ShortcutBinding(keyCode = AwtKeyEvent.VK_F, ctrl = true),
    ),
    TOGGLE_LYRICS(
        title = "Buka / Tutup Lirik (Lyrics)",
        description = "Menampilkan atau menyembunyikan panel lirik sinkron",
        category = "Tampilan & Navigasi",
        defaultBinding = ShortcutBinding(keyCode = AwtKeyEvent.VK_L, ctrl = true),
    ),
    TOGGLE_QUEUE(
        title = "Buka / Tutup Antrean (Queue)",
        description = "Menampilkan atau menyembunyikan panel antrean pemutaran",
        category = "Tampilan & Navigasi",
        defaultBinding = ShortcutBinding(keyCode = AwtKeyEvent.VK_Q, ctrl = true),
    ),
    TOGGLE_FULLSCREEN(
        title = "Layar Penuh (Toggle Fullscreen)",
        description = "Mengaktifkan atau keluar dari mode layar penuh",
        category = "Tampilan & Navigasi",
        defaultBinding = ShortcutBinding(keyCode = AwtKeyEvent.VK_F11),
    ),
}

/**
 * Key combination definition for a shortcut.
 */
internal data class ShortcutBinding(
    val keyCode: Int,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
) {
    fun displayText(): String {
        if (keyCode == 0) return "Tidak ada (None)"
        val parts = mutableListOf<String>()
        if (ctrl) parts.add(if (DesktopPlatform.isMac) "Cmd" else "Ctrl")
        if (alt) parts.add(if (DesktopPlatform.isMac) "Opt" else "Alt")
        if (shift) parts.add("Shift")
        if (meta && !DesktopPlatform.isMac) parts.add("Win")

        val keyLabel = when (keyCode) {
            AwtKeyEvent.VK_SPACE -> "Space"
            AwtKeyEvent.VK_RIGHT -> "Right →"
            AwtKeyEvent.VK_LEFT -> "Left ←"
            AwtKeyEvent.VK_UP -> "Up ↑"
            AwtKeyEvent.VK_DOWN -> "Down ↓"
            AwtKeyEvent.VK_ESCAPE -> "Esc"
            AwtKeyEvent.VK_ENTER -> "Enter"
            AwtKeyEvent.VK_BACK_SPACE -> "Backspace"
            AwtKeyEvent.VK_TAB -> "Tab"
            AwtKeyEvent.VK_R -> "R"
            else -> {
                val txt = AwtKeyEvent.getKeyText(keyCode)
                if (txt.isNotBlank()) txt else "Key $keyCode"
            }
        }
        parts.add(keyLabel)
        return parts.joinToString(" + ")
    }

    fun matches(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val rawCode = DesktopShortcutSettings.extractRawKeyCode(event)
        if (rawCode != keyCode) return false

        val ctrlMatch = if (DesktopPlatform.isMac) {
            event.isMetaPressed == ctrl
        } else {
            event.isCtrlPressed == ctrl
        }
        val altMatch = event.isAltPressed == alt
        val shiftMatch = event.isShiftPressed == shift
        val metaMatch = DesktopPlatform.isMac || event.isMetaPressed == meta

        return ctrlMatch && altMatch && shiftMatch && metaMatch
    }

    fun serialize(): String = "$keyCode:$ctrl:$alt:$shift:$meta"

    companion object {
        fun deserialize(str: String): ShortcutBinding? {
            val parts = str.split(":")
            if (parts.size < 5) return null
            val code = parts[0].toIntOrNull() ?: return null
            val c = parts[1].toBooleanStrictOrNull() ?: false
            val a = parts[2].toBooleanStrictOrNull() ?: false
            val s = parts[3].toBooleanStrictOrNull() ?: false
            val m = parts[4].toBooleanStrictOrNull() ?: false
            return ShortcutBinding(code, c, a, s, m)
        }
    }
}

/**
 * Singleton managing desktop shortcuts persistence and live bindings.
 */
internal object DesktopShortcutSettings {
    private const val KEY_PREFIX = "custom_shortcut_"
    private const val SHORTCUTS_VERSION_KEY = "shortcuts_version"
    private const val CURRENT_SHORTCUTS_VERSION = 2

    private val _bindings = MutableStateFlow<Map<DesktopShortcutAction, ShortcutBinding>>(loadInitialBindings())
    val bindings: StateFlow<Map<DesktopShortcutAction, ShortcutBinding>> = _bindings.asStateFlow()

    var isRecording: Boolean = false

    /**
     * Window-level preview key dispatcher hooked directly into [androidx.compose.ui.window.Window]
     * so shortcuts still fire even when no child composable currently holds Compose focus.
     */
    @Volatile
    var windowKeyHandler: ((KeyEvent) -> Boolean)? = null

    fun dispatchWindowKeyEvent(event: KeyEvent): Boolean =
        windowKeyHandler?.invoke(event) == true

    private val typingTokens = java.util.Collections.synchronizedSet(mutableSetOf<Any>())

    /**
     * Set to true whenever any editable text field or search field currently has focus in the application.
     */
    @Volatile
    var isTypingActive: Boolean = false

    fun setTypingFocus(token: Any, focused: Boolean) {
        synchronized(typingTokens) {
            if (focused) typingTokens.add(token) else typingTokens.remove(token)
            isTypingActive = typingTokens.isNotEmpty()
        }
    }

    internal fun clearTypingFocus() {
        synchronized(typingTokens) {
            typingTokens.clear()
            isTypingActive = false
        }
    }

    internal fun extractRawKeyCode(event: KeyEvent): Int {
        val raw = event.awtEventOrNull?.keyCode?.takeIf { it != AwtKeyEvent.VK_UNDEFINED }
            ?: event.key.nativeKeyCode
        return when (raw) {
            AwtKeyEvent.VK_KP_LEFT -> AwtKeyEvent.VK_LEFT
            AwtKeyEvent.VK_KP_RIGHT -> AwtKeyEvent.VK_RIGHT
            AwtKeyEvent.VK_KP_UP -> AwtKeyEvent.VK_UP
            AwtKeyEvent.VK_KP_DOWN -> AwtKeyEvent.VK_DOWN
            else -> raw
        }
    }

    private fun loadInitialBindings(): Map<DesktopShortcutAction, ShortcutBinding> {
        val persistence = DesktopPersistence()
        val version = persistence.string(SHORTCUTS_VERSION_KEY, "0").toIntOrNull() ?: 0
        val result = mutableMapOf<DesktopShortcutAction, ShortcutBinding>()

        if (version < CURRENT_SHORTCUTS_VERSION) {
            for (action in DesktopShortcutAction.entries) {
                result[action] = action.defaultBinding
                persistence.saveString(KEY_PREFIX + action.name, action.defaultBinding.serialize())
            }
            persistence.saveString(SHORTCUTS_VERSION_KEY, CURRENT_SHORTCUTS_VERSION.toString())
            return result
        }

        for (action in DesktopShortcutAction.entries) {
            val saved = persistence.string(KEY_PREFIX + action.name, "")
            val parsed = if (saved.isNotBlank()) ShortcutBinding.deserialize(saved) else null
            result[action] = parsed ?: action.defaultBinding
        }
        return result
    }

    fun getBinding(action: DesktopShortcutAction): ShortcutBinding {
        return _bindings.value[action] ?: action.defaultBinding
    }

    fun setBinding(action: DesktopShortcutAction, binding: ShortcutBinding) {
        val current = _bindings.value.toMutableMap()
        current[action] = binding
        _bindings.value = current
        DesktopPersistence().saveString(KEY_PREFIX + action.name, binding.serialize())
    }

    fun resetToDefaults() {
        val persistence = DesktopPersistence()
        val result = mutableMapOf<DesktopShortcutAction, ShortcutBinding>()
        for (action in DesktopShortcutAction.entries) {
            persistence.saveString(KEY_PREFIX + action.name, "")
            result[action] = action.defaultBinding
        }
        _bindings.value = result
    }

    fun handleKeyEvent(
        event: KeyEvent,
        isSearchTyping: Boolean = false,
        isTyping: Boolean = isTypingActive || isSearchTyping,
        onPlayPause: () -> Unit,
        onNextTrack: () -> Unit,
        onPreviousTrack: () -> Unit,
        onVolumeUp: () -> Unit,
        onVolumeDown: () -> Unit,
        onToggleMute: () -> Unit,
        onSeekForward: () -> Unit,
        onSeekBackward: () -> Unit,
        onFocusSearch: () -> Unit,
        onToggleLyrics: () -> Unit,
        onToggleQueue: () -> Unit,
        onToggleFullscreen: () -> Unit,
        onBack: () -> Boolean = { false },
        onRefresh: () -> Unit = {},
    ): Boolean {
        if (event.type != KeyEventType.KeyDown || isRecording) return false

        // When the user is typing/focused in any text field or search field:
        // Do NOT intercept navigation or editing keys so cursor movement and text typing work normally!
        if (isTyping) {
            val rawCode = extractRawKeyCode(event)
            val isArrowKey = rawCode == AwtKeyEvent.VK_LEFT ||
                rawCode == AwtKeyEvent.VK_RIGHT ||
                rawCode == AwtKeyEvent.VK_UP ||
                rawCode == AwtKeyEvent.VK_DOWN
            val isEditingKey = rawCode == AwtKeyEvent.VK_SPACE ||
                rawCode == AwtKeyEvent.VK_BACK_SPACE ||
                rawCode == AwtKeyEvent.VK_DELETE ||
                rawCode == AwtKeyEvent.VK_ENTER

            // Left/Right arrows (including Ctrl/Shift for word navigation and selection),
            // Up/Down arrows, Space, and Backspace/Enter/Delete must pass through directly to the text field.
            if (isArrowKey || isEditingKey) {
                return false
            }
        }

        val current = _bindings.value
        for ((action, binding) in current) {
            // When user is typing, single-key unmodified shortcuts must also not trigger
            if (isTyping && !binding.ctrl && !binding.alt && !binding.meta) {
                if (action != DesktopShortcutAction.BACK_OR_UNDO) {
                    continue
                }
            }

            if (binding.matches(event)) {
                return when (action) {
                    DesktopShortcutAction.PLAY_PAUSE -> { onPlayPause(); true }
                    DesktopShortcutAction.NEXT_TRACK -> { onNextTrack(); true }
                    DesktopShortcutAction.PREVIOUS_TRACK -> { onPreviousTrack(); true }
                    DesktopShortcutAction.VOLUME_UP -> { onVolumeUp(); true }
                    DesktopShortcutAction.VOLUME_DOWN -> { onVolumeDown(); true }
                    DesktopShortcutAction.TOGGLE_MUTE -> { onToggleMute(); true }
                    DesktopShortcutAction.SEEK_FORWARD -> { onSeekForward(); true }
                    DesktopShortcutAction.SEEK_BACKWARD -> { onSeekBackward(); true }
                    DesktopShortcutAction.FOCUS_SEARCH -> { onFocusSearch(); true }
                    DesktopShortcutAction.TOGGLE_LYRICS -> { onToggleLyrics(); true }
                    DesktopShortcutAction.TOGGLE_QUEUE -> { onToggleQueue(); true }
                    DesktopShortcutAction.TOGGLE_FULLSCREEN -> { onToggleFullscreen(); true }
                    DesktopShortcutAction.BACK_OR_UNDO -> onBack()
                    DesktopShortcutAction.REFRESH -> { onRefresh(); true }
                }
            }
        }
        return false
    }
}

/** Tracks text input focus and guarantees cleanup if the field leaves composition while focused. */
@Composable
internal fun Modifier.trackDesktopTypingFocus(
    onFocusChangedCallback: ((Boolean) -> Unit)? = null,
): Modifier {
    val token = remember { Any() }
    DisposableEffect(token) {
        onDispose {
            DesktopShortcutSettings.setTypingFocus(token, false)
        }
    }
    return onFocusChanged { focusState ->
        DesktopShortcutSettings.setTypingFocus(token, focusState.isFocused)
        onFocusChangedCallback?.invoke(focusState.isFocused)
    }
}

/**
 * Settings UI for viewing and re-binding keyboard shortcuts.
 */
@Composable
internal fun DesktopKeyboardShortcutsDialog(onDismiss: () -> Unit) {
    val bindings by DesktopShortcutSettings.bindings.collectAsState()
    var recordingAction by remember { mutableStateOf<DesktopShortcutAction?>(null) }

    LaunchedEffect(recordingAction) {
        DesktopShortcutSettings.isRecording = (recordingAction != null)
    }
    DisposableEffect(Unit) {
        onDispose { DesktopShortcutSettings.isRecording = false }
    }

    DesktopDialogPanel(onDismiss = onDismiss, maxWidth = 640) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = panelInset(20.dp), vertical = 16.dp),
        ) {
            // Header
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Pintasan Keyboard (Shortcuts)",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        "Sesuaikan tombol pintas untuk mengontrol musik dengan cepat",
                        style = MaterialTheme.typography.bodySmall,
                        color = DesktopSecondary,
                    )
                }

                TextButton(
                    onClick = { DesktopShortcutSettings.resetToDefaults() },
                    colors = ButtonDefaults.textButtonColors(contentColor = DesktopAccent),
                ) {
                    Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reset Default", style = MaterialTheme.typography.labelMedium)
                }
            }

            // Windows Info Pill
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Keyboard,
                        contentDescription = null,
                        tint = DesktopAccent,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Tips Windows: Shortcut desktop tersedia di Desktop & Start Menu. Anda juga dapat mengatur tombol pintas global Windows via Klik kanan shortcut → Properties → Shortcut key.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DesktopSecondary,
                        lineHeight = 18.sp,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Grouped by Category
            val categories = remember {
                DesktopShortcutAction.entries.groupBy { it.category }
            }

            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                categories.forEach { (category, actions) ->
                    item(key = category) {
                        Text(
                            text = category.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = DesktopAccent,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }

                    items(actions, key = { it.name }) { action ->
                        val binding = bindings[action] ?: action.defaultBinding
                        val isCustom = binding != action.defaultBinding

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = if (isCustom) 0.07f else 0.03f))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    action.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                )
                                Text(
                                    action.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = DesktopSecondary,
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                // Key badge
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isCustom) DesktopAccent.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.1f))
                                        .border(
                                            1.dp,
                                            if (isCustom) DesktopAccent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.2f),
                                            RoundedCornerShape(6.dp),
                                        )
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                ) {
                                    Text(
                                        text = binding.displayText(),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (isCustom) Color.White else Color(0xFFE0E0E0),
                                    )
                                }

                                // Edit button
                                IconButton(
                                    onClick = { recordingAction = action },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.Edit,
                                        contentDescription = "Ubah pintasan",
                                        tint = DesktopAccent,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal when capturing / recording key press
    recordingAction?.let { action ->
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        Dialog(onDismissRequest = { recordingAction = null }) {
            Box(
                Modifier
                    .width(420.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF1E1C24))
                    .border(1.5.dp, DesktopAccent.copy(alpha = 0.7f), RoundedCornerShape(18.dp))
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            if (event.key == Key.Escape) {
                                recordingAction = null
                                return@onPreviewKeyEvent true
                            }

                            val rawCode = DesktopShortcutSettings.extractRawKeyCode(event)
                            // Wait for a non-modifier key
                            if (rawCode != AwtKeyEvent.VK_CONTROL &&
                                rawCode != AwtKeyEvent.VK_SHIFT &&
                                rawCode != AwtKeyEvent.VK_ALT &&
                                rawCode != AwtKeyEvent.VK_META &&
                                rawCode != AwtKeyEvent.VK_WINDOWS &&
                                rawCode != 0
                            ) {
                                val ctrl = if (DesktopPlatform.isMac) event.isMetaPressed else event.isCtrlPressed
                                val alt = event.isAltPressed
                                val shift = event.isShiftPressed
                                val meta = event.isMetaPressed

                                val newBinding = ShortcutBinding(rawCode, ctrl, alt, shift, meta)
                                DesktopShortcutSettings.setBinding(action, newBinding)
                                recordingAction = null
                                return@onPreviewKeyEvent true
                            }
                        }
                        false
                    }
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(DesktopAccent.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Keyboard,
                            contentDescription = null,
                            tint = DesktopAccent,
                            modifier = Modifier.size(28.dp),
                        )
                    }

                    Text(
                        text = "Atur Pintasan Baru",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )

                    Text(
                        text = action.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = DesktopAccent,
                        fontWeight = FontWeight.SemiBold,
                    )

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black.copy(alpha = 0.4f))
                            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                            .padding(vertical = 16.dp, horizontal = 20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Tekan kombinasi tombol pada keyboard Anda...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFFFD54F),
                            textAlign = TextAlign.Center,
                        )
                    }

                    Text(
                        text = "Contoh: Space, Ctrl + Right, Shift + F, F11\nTekan Esc untuk membatalkan",
                        style = MaterialTheme.typography.bodySmall,
                        color = DesktopSecondary,
                        textAlign = TextAlign.Center,
                    )

                    OutlinedButton(
                        onClick = { recordingAction = null },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    ) {
                        Text("Batal")
                    }
                }
            }
        }
    }
}
