package com.music.bitchord.desktop.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCode
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.desktop.DesktopAccent
import com.music.bitchord.desktop.DesktopCardEdge
import com.music.bitchord.desktop.DesktopDialogPanel
import com.music.bitchord.desktop.DesktopLocalMusic
import com.music.bitchord.desktop.DesktopSecondary
import com.music.bitchord.desktop.DesktopShortcutSettings
import com.music.bitchord.desktop.LocalDesktopPanelIsPage
import com.music.bitchord.desktop.desktopPanelBody
import com.music.bitchord.desktop.desktopRowClickable
import com.music.bitchord.desktop.desktopSwitchColors
import com.music.bitchord.desktop.panelInset
import kotlinx.coroutines.launch

/**
 * Dedicated settings page for LuminaChord Sync:
 * - Master server toggle & network info
 * - QR code pairing for Android camera scan
 * - Local Music sync & merge
 * - Automatic ADB USB sync and port forwarding
 * - Active mobile connections
 */
@Composable
fun DesktopMobileSyncScreen(onDismiss: () -> Unit) {
    val isEnabled by DesktopMobileSyncServer.isEnabled.collectAsState()
    val isRunning by DesktopMobileSyncServer.isRunning.collectAsState()
    val port by DesktopMobileSyncServer.port.collectAsState()
    val pairingCode by DesktopMobileSyncServer.pairingCode.collectAsState()
    val requireCode by DesktopMobileSyncServer.requirePairingCode.collectAsState()
    val autoBeacon by DesktopMobileSyncServer.autoDiscoverBeacon.collectAsState()
    val clients by DesktopMobileSyncServer.connectedClients.collectAsState()
    val lastSync by DesktopMobileSyncServer.lastSyncTime.collectAsState()
    val statusMsg by DesktopMobileSyncServer.lastActionMessage.collectAsState()
    val activeAudioOutput by DesktopMobileSyncServer.activeAudioOutput.collectAsState()
    val mobileSession by DesktopMobileSyncServer.mobileSession.collectAsState()

    // ADB state
    val isAdbAvailable by AdbSyncManager.isAdbAvailable.collectAsState()
    val adbDevices by AdbSyncManager.connectedDevices.collectAsState()
    val isAdbReverseActive by AdbSyncManager.isReverseActive.collectAsState()
    val isAdbSyncing by AdbSyncManager.isSyncing.collectAsState()
    val adbMessage by AdbSyncManager.lastMessage.collectAsState()
    val autoSyncAdb by AdbSyncManager.autoSyncOnConnect.collectAsState()

    val preferredIp by DesktopMobileSyncServer.preferredIp.collectAsState()
    var availableIps by remember { mutableStateOf(DesktopMobileSyncServer.getLocalIpAddresses()) }
    val primaryIp = remember(preferredIp, availableIps, isRunning) { DesktopMobileSyncServer.getPrimaryIpAddress() }
    val scope = rememberCoroutineScope()

    var firewallStatus by remember { mutableStateOf(DesktopMobileSyncServer.checkFirewallStatus()) }
    var localMusicCount by remember { mutableStateOf(DesktopLocalMusic.scan().size) }
    var localMusicFolder by remember { mutableStateOf(DesktopLocalMusic.folder()?.toString() ?: "Folder Musik Standar") }
    var mergeStatus by remember { mutableStateOf<String?>(null) }
    var isMerging by remember { mutableStateOf(false) }

    DesktopDialogPanel(onDismiss = onDismiss, maxWidth = 860) {
        Column(desktopPanelBody(cardMax = 800.dp, cardMin = 580.dp, fill = true)) {
            // Header Bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = panelInset(24.dp), end = panelInset(18.dp), top = 20.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (LocalDesktopPanelIsPage.current) Icons.Rounded.ArrowBack else Icons.Rounded.Close,
                        "Kembali",
                        tint = Color.White,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "LuminaChord Sync",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Hubungkan PC Windows dengan HP Android (LuminaChord Mobile)",
                        color = DesktopSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            HorizontalDivider(color = DesktopCardEdge)

            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = panelInset(24.dp), vertical = 16.dp),
            ) {
                // Section 1: Master Toggle Card
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                        .padding(16.dp),
                ) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(DesktopAccent.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.PhoneAndroid, null, tint = DesktopAccent, modifier = Modifier.size(22.dp))
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Layanan Sinkronisasi Mobile (LuminaChord Sync)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    if (isRunning) "Server Aktif · Port $port · Menunggu koneksi HP"
                                    else "Nonaktif · Aktifkan untuk menghubungkan dengan HP Android",
                                    color = DesktopSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Spacer(Modifier.width(16.dp))
                            Switch(
                                checked = isEnabled,
                                onCheckedChange = { DesktopMobileSyncServer.setEnabled(it) },
                                colors = desktopSwitchColors(),
                            )
                        }

                        if (isEnabled) {
                            Spacer(Modifier.height(14.dp))
                            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                            Spacer(Modifier.height(12.dp))

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(if (isRunning) Color(0xFF4CAF50) else Color(0xFFFF5252)),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (isRunning) "SERVER ONLINE" else "SERVER OFFLINE",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isRunning) Color(0xFF81C784) else Color(0xFFFF8A80),
                                    )
                                }

                                if (isRunning) {
                                    Text(
                                        "${clients.size} HP Terhubung",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = DesktopSecondary,
                                    )
                                }
                            }
                        }
                    }
                }

                if (isEnabled) {
                    Spacer(Modifier.height(18.dp))

                    // Section 2: QR Code Pairing Card
                    Text(
                        "PAIRING INSTAN VIA QR CODE & PIN",
                        style = MaterialTheme.typography.labelSmall,
                        color = DesktopSecondary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.04f))
                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                            .padding(18.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // QR Code
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White)
                                    .padding(8.dp),
                            ) {
                                DesktopQrCode(
                                    content = remember(primaryIp, port, pairingCode, availableIps) {
                                        DesktopMobileSyncServer.getPairingQrPayload()
                                    },
                                    size = 150.dp,
                                    quietZone = 4.dp,
                                )
                            }

                            Spacer(Modifier.width(20.dp))

                            // Details & Instructions
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Pindai dengan Kamera HP",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Buka menu 'LuminaChord for Desktop' di HP Android, pilih 'Pindai QR Code', lalu arahkan kamera ke QR Code di sebelah kiri.",
                                    color = DesktopSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                )

                                Spacer(Modifier.height(12.dp))

                                // IP & Port Row + Network adapter selector
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Wifi, null, tint = DesktopAccent, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "IP: $primaryIp:$port",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    if (availableIps.size > 1) {
                                        Spacer(Modifier.width(8.dp))
                                        Box(
                                            Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color.White.copy(alpha = 0.08f))
                                                .clickable {
                                                    availableIps = DesktopMobileSyncServer.getLocalIpAddresses()
                                                    val idx = availableIps.indexOf(primaryIp)
                                                    val next = availableIps[(idx + 1) % availableIps.size]
                                                    DesktopMobileSyncServer.setPreferredIp(next)
                                                }
                                                .padding(horizontal = 8.dp, vertical = 3.dp),
                                        ) {
                                            Text("Ganti IP", fontSize = 11.sp, color = DesktopAccent, fontWeight = FontWeight.Medium)
                                        }
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    IconButton(
                                        onClick = { availableIps = DesktopMobileSyncServer.getLocalIpAddresses() },
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(Icons.Rounded.Refresh, "Segarkan IP", tint = DesktopSecondary, modifier = Modifier.size(14.dp))
                                    }
                                }

                                Spacer(Modifier.height(8.dp))

                                // PIN Display
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Shield, null, tint = DesktopAccent, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("PIN Pairing: ", style = MaterialTheme.typography.bodySmall, color = DesktopSecondary)
                                    Text(
                                        pairingCode,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 2.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color.White,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    IconButton(
                                        onClick = { DesktopMobileSyncServer.refreshPairingCode() },
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(Icons.Rounded.Refresh, "Acak PIN", tint = DesktopAccent, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    // Section 3: Local Music Sync & Merge
                    Text(
                        "SINKRONISASI & MERGE MUSIK LOKAL (LOCAL MUSIC)",
                        style = MaterialTheme.typography.labelSmall,
                        color = DesktopSecondary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.04f))
                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                            .padding(16.dp),
                    ) {
                        Column {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(DesktopAccent.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Rounded.LibraryMusic, null, tint = DesktopAccent, modifier = Modifier.size(20.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Koleksi Musik Lokal di PC", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "$localMusicCount lagu ditemukan · $localMusicFolder",
                                        color = DesktopSecondary,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Button(
                                    onClick = {
                                        val chosen = DesktopLocalMusic.chooseFolder()
                                        if (chosen != null) {
                                            DesktopLocalMusic.setFolder(chosen)
                                            localMusicFolder = chosen.toString()
                                            localMusicCount = DesktopLocalMusic.scan().size
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.10f)),
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Icon(Icons.Rounded.FolderOpen, null, modifier = Modifier.size(16.dp), tint = Color.White)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Pilih Folder", fontSize = 12.sp, color = Color.White)
                                }
                            }

                            Spacer(Modifier.height(14.dp))

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = {
                                        localMusicCount = DesktopLocalMusic.scan().size
                                        mergeStatus = "Pustaka lokal dipindai ulang: $localMusicCount lagu siap disinkronkan"
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp), tint = Color.White)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Pindai Ulang", fontSize = 12.sp, color = Color.White)
                                }

                                Button(
                                    onClick = {
                                        isMerging = true
                                        mergeStatus = "Menyiapkan dan memindai pustaka musik lokal..."
                                        scope.launch {
                                            localMusicCount = DesktopLocalMusic.scan().size
                                            if (adbDevices.isNotEmpty() && isAdbAvailable) {
                                                AdbSyncManager.syncMusic(AdbSyncDirection.TWO_WAY_MERGE) { stepMsg ->
                                                    mergeStatus = stepMsg
                                                }
                                            } else if (clients.isNotEmpty()) {
                                                mergeStatus = "Pustaka ($localMusicCount lagu) siap! Tekan tombol 'Merge' di aplikasi Android untuk menyatukan lagu lewat Wi-Fi."
                                            } else {
                                                mergeStatus = "Pustaka ($localMusicCount lagu) siap dimerge. Hubungkan HP via QR Code / ADB terlebih dahulu."
                                            }
                                            isMerging = false
                                        }
                                    },
                                    enabled = !isMerging,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = DesktopAccent),
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    if (isMerging) {
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                                    } else {
                                        Icon(Icons.Rounded.Merge, null, modifier = Modifier.size(16.dp), tint = Color.White)
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text("Merge Musik", fontSize = 12.sp, color = Color.White)
                                }
                            }

                            if (mergeStatus != null) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    mergeStatus.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF81C784),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    // Section 4: ADB Auto-Sync
                    Text(
                        "SINKRONISASI OTOMATIS VIA ADB (USB & WI-FI DEBUGGING)",
                        style = MaterialTheme.typography.labelSmall,
                        color = DesktopSecondary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.04f))
                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                            .padding(16.dp),
                    ) {
                        Column {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(
                                        if (isAdbAvailable) Color(0xFF4CAF50).copy(alpha = 0.18f) else Color(0xFFFF5252).copy(alpha = 0.18f)
                                    ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Rounded.Usb,
                                        null,
                                        tint = if (isAdbAvailable) Color(0xFF81C784) else Color(0xFFFF8A80),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (isAdbAvailable) "ADB Siap Digunakan" else "ADB Belum Terdeteksi",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        if (adbDevices.isNotEmpty()) "${adbDevices.size} perangkat terdeteksi: ${adbDevices.joinToString { it.displayName }}"
                                        else if (isAdbAvailable) "Hubungkan HP via kabel USB (aktifkan USB Debugging)"
                                        else "Install Android SDK platform-tools atau pastikan adb.exe ada di PATH",
                                        color = DesktopSecondary,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                IconButton(onClick = { AdbSyncManager.refreshDevices() }) {
                                    Icon(Icons.Rounded.Refresh, "Refresh Perangkat ADB", tint = DesktopAccent)
                                }
                            }

                            if (isAdbAvailable) {
                                Spacer(Modifier.height(12.dp))
                                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                                Spacer(Modifier.height(10.dp))

                                // Auto-sync switch
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .desktopRowClickable { AdbSyncManager.setAutoSyncOnConnect(!autoSyncAdb) }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Auto-Sync Otomatis saat HP Terhubung via ADB", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                        Text("Otomatis sinkronkan file musik baru ketika HP dicolokkan ke laptop/PC", color = DesktopSecondary, style = MaterialTheme.typography.labelSmall)
                                    }
                                    Switch(
                                        checked = autoSyncAdb,
                                        onCheckedChange = { AdbSyncManager.setAutoSyncOnConnect(it) },
                                        colors = desktopSwitchColors(),
                                    )
                                }

                                Spacer(Modifier.height(10.dp))

                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(
                                        onClick = { AdbSyncManager.setupPortForwarding(port) },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isAdbReverseActive) Color(0xFF4CAF50).copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f),
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Icon(Icons.Rounded.Cable, null, modifier = Modifier.size(15.dp), tint = if (isAdbReverseActive) Color(0xFF81C784) else Color.White)
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            if (isAdbReverseActive) "USB Tunnel Aktif (:$port)" else "Aktifkan USB Tunnel ($port)",
                                            fontSize = 11.sp,
                                            color = if (isAdbReverseActive) Color(0xFF81C784) else Color.White,
                                        )
                                    }

                                    Button(
                                        onClick = { AdbSyncManager.syncMusic(AdbSyncDirection.TWO_WAY_MERGE) },
                                        enabled = !isAdbSyncing && adbDevices.isNotEmpty(),
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = DesktopAccent),
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        if (isAdbSyncing) {
                                            CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp, color = Color.White)
                                        } else {
                                            Icon(Icons.Rounded.Sync, null, modifier = Modifier.size(15.dp), tint = Color.White)
                                        }
                                        Spacer(Modifier.width(6.dp))
                                        Text("Merge 2-Arah via ADB", fontSize = 11.sp, color = Color.White)
                                    }
                                }

                                if (adbMessage != null) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        adbMessage.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = DesktopSecondary,
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    // Section 5: Connected Clients List
                    Text(
                        "PERANGKAT MOBILE AKTIF TERHUBUNG",
                        style = MaterialTheme.typography.labelSmall,
                        color = DesktopSecondary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )

                    if (clients.isEmpty()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.03f))
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Rounded.Devices, null, tint = DesktopSecondary.copy(alpha = 0.6f), modifier = Modifier.size(30.dp))
                                Spacer(Modifier.height(6.dp))
                                Text("Belum ada HP Android yang terhubung", style = MaterialTheme.typography.bodyMedium, color = DesktopSecondary)
                                Text("Gunakan scanner kamera di HP untuk scan QR Code di atas", style = MaterialTheme.typography.bodySmall, color = DesktopSecondary.copy(alpha = 0.7f))
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            clients.forEach { client ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.White.copy(alpha = 0.06f))
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        Modifier.size(32.dp).clip(CircleShape).background(DesktopAccent.copy(alpha = 0.2f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Rounded.PhoneAndroid, null, tint = DesktopAccent, modifier = Modifier.size(18.dp))
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(client.deviceName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        Text("${client.ip} · Terhubung", style = MaterialTheme.typography.bodySmall, color = Color(0xFF81C784))
                                    }
                                    IconButton(
                                        onClick = { DesktopMobileSyncServer.disconnectClient(client.id) },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(Icons.Rounded.Delete, "Putuskan", tint = Color(0xFFFF8A80), modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        // Audio Output Switcher + Live Mobile Playback Controls
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                                .padding(14.dp),
                        ) {
                            Column {
                                Text(
                                    "AUDIO OUTPUT & KONTROL LINTAS PERANGKAT",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = DesktopSecondary,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    val desktopSelected = activeAudioOutput == "desktop"
                                    Button(
                                        onClick = {
                                            if (!desktopSelected) DesktopMobileSyncServer.pullPlaybackFromMobileToDesktop()
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (desktopSelected) DesktopAccent else Color.White.copy(alpha = 0.08f),
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Icon(Icons.Rounded.Computer, null, modifier = Modifier.size(16.dp), tint = Color.White)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Desktop (Windows)", fontSize = 12.sp, color = Color.White)
                                    }
                                    Button(
                                        onClick = {
                                            if (desktopSelected) DesktopMobileSyncServer.switchOutputToMobile()
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (!desktopSelected) DesktopAccent else Color.White.copy(alpha = 0.08f),
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Icon(Icons.Rounded.PhoneAndroid, null, modifier = Modifier.size(16.dp), tint = Color.White)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Mobile (Android)", fontSize = 12.sp, color = Color.White)
                                    }
                                }

                                val mob = mobileSession
                                if (mob != null && mob.title.isNotBlank()) {
                                    Spacer(Modifier.height(12.dp))
                                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        "Diputar di ${mob.deviceName}: ${mob.title} — ${mob.artist}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    if (mob.durationMs > 0L) {
                                        var sliderFrac by remember(mob.videoId, mob.positionMs) {
                                            mutableStateOf((mob.positionMs.toFloat() / mob.durationMs.toFloat()).coerceIn(0f, 1f))
                                        }
                                        Slider(
                                            value = sliderFrac,
                                            onValueChange = { sliderFrac = it },
                                            onValueChangeFinished = {
                                                DesktopMobileSyncServer.sendCommandToMobile(
                                                    "seek",
                                                    positionMs = (sliderFrac * mob.durationMs).toLong(),
                                                )
                                            },
                                        )
                                    }
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(onClick = { DesktopMobileSyncServer.sendCommandToMobile("previous") }) {
                                                Icon(Icons.Rounded.SkipPrevious, "Previous Mobile", tint = Color.White)
                                            }
                                            IconButton(onClick = { DesktopMobileSyncServer.sendCommandToMobile("play_pause") }) {
                                                Icon(
                                                    if (mob.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                                    "Play/Pause Mobile",
                                                    tint = DesktopAccent,
                                                )
                                            }
                                            IconButton(onClick = { DesktopMobileSyncServer.sendCommandToMobile("next") }) {
                                                Icon(Icons.Rounded.SkipNext, "Next Mobile", tint = Color.White)
                                            }
                                        }
                                        var mobVol by remember(mob.volume) { mutableStateOf(mob.volume.coerceIn(0f, 1f)) }
                                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                            Spacer(Modifier.width(8.dp))
                                            Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = DesktopSecondary, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Slider(
                                                value = mobVol,
                                                onValueChange = { mobVol = it },
                                                onValueChangeFinished = {
                                                    DesktopMobileSyncServer.sendCommandToMobile("volume", value = mobVol.toString())
                                                },
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    // Section 6: Network Discovery & Security Options
                    Text(
                        "PENGATURAN LANJUTAN",
                        style = MaterialTheme.typography.labelSmall,
                        color = DesktopSecondary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .desktopRowClickable { DesktopMobileSyncServer.setAutoDiscoverBeacon(!autoBeacon) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.06f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Sensors, null, tint = DesktopAccent, modifier = Modifier.size(17.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Siarkan di Jaringan Lokal (UDP Auto-Discovery)", style = MaterialTheme.typography.bodyMedium)
                            Text("Aplikasi HP dapat menemukan PC otomatis di Wi-Fi lokal", color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = autoBeacon, onCheckedChange = { DesktopMobileSyncServer.setAutoDiscoverBeacon(it) }, colors = desktopSwitchColors())
                    }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .desktopRowClickable { DesktopMobileSyncServer.setRequirePairingCode(!requireCode) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.06f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Shield, null, tint = DesktopAccent, modifier = Modifier.size(17.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Wajibkan Kode Pairing (PIN)", style = MaterialTheme.typography.bodyMedium)
                            Text("Hanya izinkan perangkat yang memasukkan PIN valid untuk terhubung", color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = requireCode, onCheckedChange = { DesktopMobileSyncServer.setRequirePairingCode(it) }, colors = desktopSwitchColors())
                    }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.06f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Security, null, tint = DesktopAccent, modifier = Modifier.size(17.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Windows Defender Firewall", style = MaterialTheme.typography.bodyMedium)
                            Text(firewallStatus, color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.width(12.dp))
                        Button(
                            onClick = {
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    DesktopMobileSyncServer.openWindowsFirewallPort()
                                    firewallStatus = DesktopMobileSyncServer.checkFirewallStatus()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("Izinkan Port", fontSize = 11.sp, color = Color.White)
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    Text(
                        "Terakhir disinkronkan: ${DesktopMobileSyncServer.formatLastSynced(lastSync)} · $statusMsg",
                        style = MaterialTheme.typography.bodySmall,
                        color = DesktopSecondary,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}
