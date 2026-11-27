package com.music.bitchord.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.LastPlayerScreen
import com.music.bitchord.playback.PlayerDeepLink
import com.music.bitchord.sync.DesktopSyncManager
import com.music.bitchord.sync.DesktopSyncStatus
import com.music.bitchord.ui.components.PillTextField
import com.music.bitchord.ui.icons.SfIcons
import com.music.bitchord.ui.player.RepeatModes
import java.util.Locale

/**
 * Settings entry row for LuminaChord for Desktop: placed directly below
 * LuminaChord for Wear OS.
 */
@Composable
internal fun DesktopSyncSettingsRow(onClick: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { DesktopSyncManager.init(context) }
    val status by DesktopSyncManager.status.collectAsStateWithLifecycle()
    val syncEnabled by DesktopSyncManager.syncEnabled.collectAsStateWithLifecycle()

    SettingsRow(
        icon = SfIcons.Desktop,
        title = stringResource(R.string.desktop_sync_section),
        value = when {
            !syncEnabled -> "Nonaktif"
            status is DesktopSyncStatus.Connected -> stringResource(R.string.desktop_sync_status_connected)
            status is DesktopSyncStatus.Searching -> stringResource(R.string.desktop_sync_status_searching)
            status is DesktopSyncStatus.Connecting -> stringResource(R.string.desktop_sync_status_searching)
            else -> stringResource(R.string.desktop_sync_status_disconnected)
        },
        onClick = onClick,
    )
}

/**
 * Settings -> LuminaChord for Desktop: page for pairing, remote control, library sync,
 * and local music merge with Windows desktop.
 */
@Composable
fun DesktopSyncScreen(contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { DesktopSyncManager.init(context) }

    val status by DesktopSyncManager.status.collectAsStateWithLifecycle()
    val hostIp by DesktopSyncManager.hostIp.collectAsStateWithLifecycle()
    val port by DesktopSyncManager.port.collectAsStateWithLifecycle()
    val pairingCode by DesktopSyncManager.pairingCode.collectAsStateWithLifecycle()
    val autoDiscover by DesktopSyncManager.autoDiscover.collectAsStateWithLifecycle()
    val syncPlaylists by DesktopSyncManager.syncPlaylists.collectAsStateWithLifecycle()
    val syncFavorites by DesktopSyncManager.syncFavorites.collectAsStateWithLifecycle()
    val syncHistory by DesktopSyncManager.syncHistory.collectAsStateWithLifecycle()
    val shareNowPlaying by DesktopSyncManager.shareNowPlaying.collectAsStateWithLifecycle()
    val isSyncing by DesktopSyncManager.isSyncing.collectAsStateWithLifecycle()
    val transferProgress by DesktopSyncManager.transferProgress.collectAsStateWithLifecycle()
    val lastSyncedTime by DesktopSyncManager.lastSyncedTime.collectAsStateWithLifecycle()
    val syncMessage by DesktopSyncManager.syncMessage.collectAsStateWithLifecycle()

    var inputIp by rememberSaveable { mutableStateOf(hostIp) }
    var inputPort by rememberSaveable { mutableStateOf(port.toString()) }
    var inputCode by rememberSaveable { mutableStateOf(pairingCode) }
    var showScanner by remember { mutableStateOf(false) }

    LaunchedEffect(hostIp) { if (hostIp.isNotBlank()) inputIp = hostIp }
    LaunchedEffect(port) { inputPort = port.toString() }
    LaunchedEffect(pairingCode) { if (pairingCode.isNotBlank()) inputCode = pairingCode }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        if (isGranted) {
            showScanner = true
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        if (isGranted) {
            DesktopSyncManager.mergeLocalMusicWithDesktop(context, twoWay = true)
        }
    }

    val playlistAllFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        com.music.bitchord.data.spotify.LocalPlaylistStore.refreshFromStorage(context)
        com.music.bitchord.data.OfflinePlaylists.refreshFromStorage(context)
        DesktopSyncManager.syncNow(context)
    }

    val playlistWritePermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        com.music.bitchord.data.spotify.LocalPlaylistStore.refreshFromStorage(context)
        com.music.bitchord.data.OfflinePlaylists.refreshFromStorage(context)
        DesktopSyncManager.syncNow(context)
    }

    fun startPlaylistAwareSync() {
        if (syncPlaylists && !com.music.bitchord.data.PlaylistStorage.hasWriteAccess(context)) {
            if (com.music.bitchord.data.PlaylistStorage.needsAllFilesPermission() &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            ) {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}"),
                )
                val launched = runCatching { playlistAllFilesLauncher.launch(intent) }.isSuccess
                if (!launched) {
                    runCatching {
                        playlistAllFilesLauncher.launch(
                            android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                        )
                    }.onFailure { DesktopSyncManager.syncNow(context) }
                }
            } else {
                playlistWritePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            return
        }
        DesktopSyncManager.syncNow(context)
    }

    fun startLocalMusicMerge() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED) {
            DesktopSyncManager.mergeLocalMusicWithDesktop(context, twoWay = true)
        } else {
            audioPermissionLauncher.launch(perm)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        SettingsPageTitle(stringResource(R.string.desktop_sync_page_title))

        // Connection Group
        SettingsGroup(header = stringResource(R.string.desktop_sync_connection_group)) {
            when (val s = status) {
                is DesktopSyncStatus.Connected -> {
                    SettingsRow(
                        icon = SfIcons.Desktop,
                        title = s.deviceName,
                        subtitle = "${s.host}:${s.port} · " + stringResource(R.string.desktop_sync_status_connected),
                        trailing = {
                            ActionPill(
                                text = stringResource(R.string.desktop_sync_disconnect),
                                onClick = { DesktopSyncManager.disconnect() },
                            )
                        },
                    )
                }
                is DesktopSyncStatus.Connecting -> {
                    SettingsRow(
                        icon = SfIcons.Desktop,
                        title = stringResource(R.string.desktop_sync_section),
                        subtitle = "Menghubungkan ke desktop...",
                        trailing = {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        },
                    )
                }
                is DesktopSyncStatus.Searching -> {
                    SettingsRow(
                        icon = SfIcons.Desktop,
                        title = stringResource(R.string.desktop_sync_section),
                        subtitle = stringResource(R.string.desktop_sync_status_searching),
                        trailing = {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        },
                    )
                }
                DesktopSyncStatus.Disconnected -> {
                    SettingsRow(
                        icon = SfIcons.Desktop,
                        title = stringResource(R.string.desktop_sync_section),
                        subtitle = stringResource(R.string.desktop_sync_subtitle),
                        trailing = {
                            ActionPill(
                                text = stringResource(R.string.desktop_sync_connect),
                                onClick = {
                                    val p = inputPort.toIntOrNull() ?: 8688
                                    DesktopSyncManager.connect(inputIp, p, inputCode)
                                },
                            )
                        },
                    )
                }
            }

            RowDivider()

            val syncEnabled by DesktopSyncManager.syncEnabled.collectAsStateWithLifecycle()

            SettingsSubRow(
                title = "Aktifkan LuminaChord Sync",
                subtitle = "Pairing & sinkronisasi otomatis dengan LuminaChord Desktop",
                checked = syncEnabled,
                onCheckedChange = { enabled ->
                    DesktopSyncManager.setSyncEnabled(enabled)
                },
            )

            RowDivider()

            SettingsSubRow(
                title = "Pairing Otomatis via Wi-Fi",
                subtitle = "Terhubung otomatis saat berada di jaringan Wi-Fi yang sama",
                checked = autoDiscover,
                enabled = syncEnabled,
                onCheckedChange = { enabled ->
                    DesktopSyncManager.setAutoDiscover(enabled)
                    if (enabled && syncEnabled && status is DesktopSyncStatus.Disconnected) {
                        DesktopSyncManager.autoDiscover()
                    }
                },
            )

            RowDivider()

            SettingsRow(
                icon = Icons.Rounded.Search,
                title = "Cari PC Otomatis (Wi-Fi)",
                subtitle = "Pindai jaringan Wi-Fi lokal secara otomatis",
                trailing = {
                    ActionPill(
                        text = "Cari PC",
                        onClick = {
                            if (syncEnabled) DesktopSyncManager.autoDiscover()
                        },
                    )
                },
                onClick = {
                    if (syncEnabled) DesktopSyncManager.autoDiscover()
                },
            )

            RowDivider()

            SettingsRow(
                icon = Icons.Rounded.QrCodeScanner,
                title = "Pindai QR Code Desktop (Kamera)",
                subtitle = "Scan QR Code di layar Windows dengan kamera untuk pairing instan",
                trailing = {
                    ActionPill(
                        text = "Scan QR",
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                showScanner = true
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                    )
                },
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        showScanner = true
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
            )
        }

        // Live Remote Controller, Audio Output Switcher, Synced Lyrics & Queue when Connected
        if (status is DesktopSyncStatus.Connected) {
            val nowPlaying by DesktopSyncManager.desktopNowPlaying.collectAsStateWithLifecycle()
            val desktopOutputActive by DesktopSyncManager.desktopOutputActive.collectAsStateWithLifecycle()
            val desktopQueue by DesktopSyncManager.desktopQueue.collectAsStateWithLifecycle()
            val desktopLyrics by DesktopSyncManager.desktopLyrics.collectAsStateWithLifecycle()

            Spacer(Modifier.height(16.dp))
            SettingsGroup(header = "Audio Output Aktif") {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val isDesktop = desktopOutputActive
                        Button(
                            onClick = { DesktopSyncManager.switchOutputToDesktop(handoffIfMobilePlaying = true) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDesktop) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (isDesktop) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            ),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Rounded.Computer, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Desktop (PC)", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Button(
                            onClick = {
                                if (desktopOutputActive) {
                                    DesktopSyncManager.switchOutputToMobile(handoffFromDesktop = true)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (!isDesktop) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (!isDesktop) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            ),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Rounded.PhoneAndroid, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("HP Android", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (desktopOutputActive) {
                            "Player & Audio Output HP terhubung ke PC Windows — kontrol pemutaran, lirik tersinkron, dan antrean PC langsung dari Player."
                        } else {
                            "Ketuk Desktop (PC) untuk mengontrol musik, melihat lirik tersinkron, dan memindahkan pemutaran antara PC & HP."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (nowPlaying != null) {
                val track = nowPlaying!!
                Spacer(Modifier.height(16.dp))
                SettingsGroup(header = "Now Playing di Desktop (Windows)") {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (!track.thumbnailUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = track.thumbnailUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Icon(
                                        SfIcons.Desktop,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = track.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            ActionPill(
                                text = "Player",
                                onClick = {
                                    DesktopSyncManager.setDesktopOutputActive(true)
                                    AppSettings.setLastPlayerScreen(LastPlayerScreen.MAIN)
                                    PlayerDeepLink.open()
                                },
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        DesktopLiveSeekbar(
                            durationMs = track.durationMs,
                            isPlaying = track.isPlaying,
                        )

                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { DesktopSyncManager.toggleShuffleOnDesktop() }) {
                                Icon(
                                    Icons.Rounded.Shuffle,
                                    "Shuffle",
                                    tint = if (track.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { DesktopSyncManager.sendRemoteCommand("previous") }) {
                                Icon(Icons.Rounded.SkipPrevious, "Previous Track")
                            }
                            FilledIconButton(
                                onClick = { DesktopSyncManager.sendRemoteCommand("play_pause") },
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                            ) {
                                Icon(
                                    if (track.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    if (track.isPlaying) "Pause" else "Play",
                                )
                            }
                            IconButton(onClick = { DesktopSyncManager.sendRemoteCommand("next") }) {
                                Icon(Icons.Rounded.SkipNext, "Next Track")
                            }
                            IconButton(onClick = { DesktopSyncManager.cycleRepeatOnDesktop() }) {
                                Icon(
                                    if (track.repeatMode == RepeatModes.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                                    "Repeat",
                                    tint = if (track.repeatMode != RepeatModes.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                        var sliderVol by remember(track.volume) { mutableFloatStateOf(track.volume.coerceIn(0f, 1f)) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.AutoMirrored.Rounded.VolumeUp,
                                contentDescription = "Volume Desktop",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Slider(
                                value = sliderVol,
                                onValueChange = { sliderVol = it },
                                onValueChangeFinished = {
                                    DesktopSyncManager.setDesktopVolume(sliderVol)
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        // Synced Lyrics inline preview + quick button to open Full Synced Lyrics on Mobile
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    DesktopSyncManager.setDesktopOutputActive(true)
                                    AppSettings.setLastPlayerScreen(LastPlayerScreen.LYRICS)
                                    PlayerDeepLink.open()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Icon(Icons.Rounded.Lyrics, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Lirik Penuh", maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = {
                                    DesktopSyncManager.setDesktopOutputActive(true)
                                    AppSettings.setLastPlayerScreen(LastPlayerScreen.QUEUE)
                                    PlayerDeepLink.open()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Antrean Penuh", maxLines = 1)
                            }
                        }

                        val activeLyrics = desktopLyrics.lines.takeIf {
                            it.isNotEmpty() && (desktopLyrics.videoId.isBlank() || desktopLyrics.videoId == track.videoId)
                        }
                        if (activeLyrics != null || desktopLyrics.loading) {
                            Spacer(Modifier.height(10.dp))
                            DesktopInlineSyncedLyricsCard(
                                lines = activeLyrics.orEmpty(),
                                loading = desktopLyrics.loading && activeLyrics == null,
                                isPlaying = track.isPlaying,
                                onSeekLine = { line -> DesktopSyncManager.sendSeek(line.timeMs) },
                            )
                        }
                    }
                }
            }

            if (desktopQueue.songs.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                SettingsGroup(header = "Antrean Desktop (${desktopQueue.songs.size} lagu)") {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Ketuk lagu untuk putar, gunakan panah untuk ubah urutan.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            ActionPill(
                                text = "Bersihkan",
                                onClick = { DesktopSyncManager.clearDesktopQueue() },
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        val visibleStart = (desktopQueue.queueIndex - 2).coerceAtLeast(0)
                        val visibleEnd = (visibleStart + 18).coerceAtMost(desktopQueue.songs.size)
                        for (idx in visibleStart until visibleEnd) {
                            val qSong = desktopQueue.songs[idx]
                            val isCurrent = idx == desktopQueue.queueIndex
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    )
                                    .clickable { DesktopSyncManager.jumpDesktopQueue(idx) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = if (isCurrent) "▶" else "${idx + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(24.dp),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = qSong.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = qSong.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (idx > 0 && !isCurrent) {
                                    IconButton(
                                        onClick = { DesktopSyncManager.moveInDesktopQueue(idx, idx - 1) },
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(Icons.Rounded.ArrowUpward, "Naikkan", modifier = Modifier.size(16.dp))
                                    }
                                }
                                if (idx < desktopQueue.songs.lastIndex && !isCurrent) {
                                    IconButton(
                                        onClick = { DesktopSyncManager.moveInDesktopQueue(idx, idx + 1) },
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(Icons.Rounded.ArrowDownward, "Turunkan", modifier = Modifier.size(16.dp))
                                    }
                                }
                                if (!isCurrent) {
                                    IconButton(
                                        onClick = { DesktopSyncManager.removeFromDesktopQueue(idx) },
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(Icons.Rounded.Close, "Hapus dari antrean", modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                            if (idx + 1 < visibleEnd) Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
        }

        // Manual Connection Inputs if disconnected
        if (status is DesktopSyncStatus.Disconnected) {
            Spacer(Modifier.height(16.dp))
            SettingsGroup(header = "Koneksi Manual (IP & PIN)") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = stringResource(R.string.desktop_sync_ip_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    PillTextField(
                        value = inputIp,
                        onValueChange = { inputIp = it },
                        placeholder = stringResource(R.string.desktop_sync_ip_hint),
                        container = MaterialTheme.colorScheme.surfaceVariant,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )

                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.desktop_sync_port_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            PillTextField(
                                value = inputPort,
                                onValueChange = { inputPort = it.filter(Char::isDigit).take(5) },
                                placeholder = "8688",
                                container = MaterialTheme.colorScheme.surfaceVariant,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.desktop_sync_pairing_code_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            PillTextField(
                                value = inputCode,
                                onValueChange = { inputCode = it.filter(Char::isDigit).take(6) },
                                placeholder = stringResource(R.string.desktop_sync_pairing_code_hint),
                                container = MaterialTheme.colorScheme.surfaceVariant,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    PrimaryButton(
                        text = stringResource(R.string.desktop_sync_connect),
                        enabled = inputIp.isNotBlank(),
                        onClick = {
                            val p = inputPort.toIntOrNull() ?: 8688
                            DesktopSyncManager.connect(inputIp, p, inputCode)
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Sync Options Group
        SettingsGroup(header = stringResource(R.string.desktop_sync_options_header)) {
            SettingsSubRow(
                title = stringResource(R.string.desktop_sync_playlists),
                subtitle = "${stringResource(R.string.desktop_sync_playlists_sub)} · ${com.music.bitchord.data.PlaylistStorage.DEFAULT_PUBLIC_PATH_LABEL}",
                checked = syncPlaylists,
                onCheckedChange = DesktopSyncManager::setSyncPlaylists,
            )
            RowDivider()
            SettingsSubRow(
                title = stringResource(R.string.desktop_sync_favorites),
                subtitle = stringResource(R.string.desktop_sync_favorites_sub),
                checked = syncFavorites,
                onCheckedChange = DesktopSyncManager::setSyncFavorites,
            )
            RowDivider()
            SettingsSubRow(
                title = stringResource(R.string.desktop_sync_history),
                subtitle = stringResource(R.string.desktop_sync_history_sub),
                checked = syncHistory,
                onCheckedChange = DesktopSyncManager::setSyncHistory,
            )
            RowDivider()
            SettingsSubRow(
                title = stringResource(R.string.desktop_sync_now_playing),
                subtitle = stringResource(R.string.desktop_sync_now_playing_sub),
                checked = shareNowPlaying,
                onCheckedChange = DesktopSyncManager::setShareNowPlaying,
            )
        }

        // Local Music Sync & Merge Group
        Spacer(Modifier.height(16.dp))
        SettingsGroup(header = "Sinkronisasi Musik Lokal (Local Music)") {
            val desktopCount by DesktopSyncManager.desktopLocalMusicCount.collectAsStateWithLifecycle()
            SettingsRow(
                icon = Icons.Rounded.LibraryMusic,
                title = "Merge Koleksi Musik Lokal (2 Arah)",
                subtitle = if (desktopCount != null) "$desktopCount lagu di PC Windows · Satukan file audio antara HP & PC"
                else "Gabungkan dan salin file musik lokal antara HP dan PC Windows",
                trailing = {
                    ActionPill(
                        text = if (isSyncing) "..." else "Merge",
                        onClick = { if (!isSyncing) startLocalMusicMerge() },
                    )
                },
                onClick = { if (!isSyncing) startLocalMusicMerge() },
            )
        }

        Spacer(Modifier.height(20.dp))

        // Sync Now Button & Timestamp & Live Diagnostics
        Column(Modifier.padding(horizontal = 20.dp)) {
            val lastSyncedStr = if (lastSyncedTime != null) {
                stringResource(R.string.desktop_sync_last_synced, DesktopSyncManager.formatLastSynced(lastSyncedTime))
            } else {
                stringResource(R.string.desktop_sync_last_synced, stringResource(R.string.desktop_sync_never))
            }

            PrimaryButton(
                text = if (isSyncing) "Menyinkronkan…" else stringResource(R.string.desktop_sync_sync_now),
                enabled = !isSyncing && status is DesktopSyncStatus.Connected,
                onClick = ::startPlaylistAwareSync,
            )

            if (isSyncing) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                )
            }

            if (transferProgress != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = transferProgress.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = lastSyncedStr,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            if (syncMessage != null) {
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = syncMessage.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        if (showScanner) {
            DesktopQrCameraScanner(
                onCodeScanned = { qr -> DesktopSyncManager.parseAndConnectFromQr(qr) },
                onDismiss = { showScanner = false },
            )
        }
    }
}

/** The trailing action pill: small filled capsule. */
@Composable
private fun ActionPill(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun DesktopLiveSeekbar(durationMs: Long, isPlaying: Boolean) {
    val desktopPos = DesktopSyncManager.desktopPosition
    var livePosMs by remember { mutableLongStateOf(desktopPos.positionMs) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isPlaying, desktopPos.sampledAtNanos, desktopPos.positionMs, durationMs) {
        if (!isPlaying) {
            livePosMs = desktopPos.positionMs
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { frameNanos ->
                val base = desktopPos.positionMs
                val sampledAt = desktopPos.sampledAtNanos
                val elapsedMs = if (sampledAt > 0L) ((frameNanos - sampledAt) / 1_000_000L).coerceAtLeast(0L) else 0L
                val est = base + elapsedMs
                livePosMs = if (durationMs > 0L) est.coerceAtMost(durationMs) else est
            }
        }
    }

    val displayedMs = if (dragging && durationMs > 0L) (dragFraction * durationMs).toLong() else livePosMs
    val sliderVal = if (dragging) {
        dragFraction
    } else if (durationMs > 0L) {
        (livePosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = sliderVal,
            onValueChange = {
                if (durationMs > 0L) {
                    dragging = true
                    dragFraction = it
                }
            },
            onValueChangeFinished = {
                if (dragging && durationMs > 0L) {
                    DesktopSyncManager.sendSeek((dragFraction * durationMs).toLong())
                }
                dragging = false
            },
            enabled = durationMs > 0L,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatSyncTime(displayedMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatSyncTime(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DesktopInlineSyncedLyricsCard(
    lines: List<LyricLine>,
    loading: Boolean,
    isPlaying: Boolean,
    onSeekLine: (LyricLine) -> Unit,
) {
    val desktopPos = DesktopSyncManager.desktopPosition
    var livePosMs by remember { mutableLongStateOf(desktopPos.positionMs) }

    LaunchedEffect(isPlaying, desktopPos.sampledAtNanos, desktopPos.positionMs) {
        if (!isPlaying) {
            livePosMs = desktopPos.positionMs
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { frameNanos ->
                val elapsedMs = if (desktopPos.sampledAtNanos > 0L) {
                    ((frameNanos - desktopPos.sampledAtNanos) / 1_000_000L).coerceAtLeast(0L)
                } else 0L
                livePosMs = desktopPos.positionMs + elapsedMs
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(12.dp),
    ) {
        Text(
            text = "Lirik Tersinkron",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        if (loading) {
            Text(
                text = "Memuat lirik dari Desktop…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val activeIdx = lines.indexOfLast { it.timeMs <= livePosMs }.coerceAtLeast(0)
            val start = (activeIdx - 1).coerceAtLeast(0)
            val end = (activeIdx + 3).coerceAtMost(lines.size)
            for (i in start until end) {
                val line = lines[i]
                if (line.text.isBlank()) continue
                val active = i == activeIdx
                Text(
                    text = line.text,
                    style = if (active) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onSeekLine(line) }
                        .padding(vertical = 3.dp, horizontal = 4.dp),
                )
            }
        }
    }
}

private fun formatSyncTime(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return String.format(Locale.ROOT, "%d:%02d", min, sec)
}
