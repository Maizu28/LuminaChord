package com.music.bitchord.desktop.sync

import com.music.bitchord.desktop.DesktopLocalMusic
import com.music.bitchord.desktop.DesktopPersistence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.extension

data class AdbDevice(
    val serial: String,
    val model: String,
    val product: String,
    val state: String,
) {
    val displayName: String
        get() {
            val cleanModel = model.replace('_', ' ').trim()
            return if (cleanModel.isNotBlank()) cleanModel else serial
        }
}

enum class AdbSyncDirection {
    PUSH_TO_MOBILE,
    PULL_FROM_MOBILE,
    TWO_WAY_MERGE,
}

/**
 * Manages USB / Wi-Fi ADB interactions for LuminaChord Windows:
 * - Detects ADB and connected Android devices
 * - Sets up `adb reverse tcp:<port> tcp:<port>` so Android connects directly to PC over USB via 127.0.0.1
 * - Syncs and merges local audio files between Windows PC and Android storage
 */
object AdbSyncManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var persistence: DesktopPersistence? = null
    private var reversedSerials: Set<String> = emptySet()

    private const val KEY_ADB_AUTO_SYNC = "adb_auto_sync_enabled"
    private const val ANDROID_MUSIC_DIR = "/sdcard/Music/LuminaChord"

    private val _adbPath = MutableStateFlow<String?>(null)
    val adbPath: StateFlow<String?> = _adbPath.asStateFlow()

    private val _isAdbAvailable = MutableStateFlow(false)
    val isAdbAvailable: StateFlow<Boolean> = _isAdbAvailable.asStateFlow()

    private val _connectedDevices = MutableStateFlow<List<AdbDevice>>(emptyList())
    val connectedDevices: StateFlow<List<AdbDevice>> = _connectedDevices.asStateFlow()

    private val _isReverseActive = MutableStateFlow(false)
    val isReverseActive: StateFlow<Boolean> = _isReverseActive.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastMessage = MutableStateFlow<String?>("ADB siap digunakan")
    val lastMessage: StateFlow<String?> = _lastMessage.asStateFlow()

    private val _autoSyncOnConnect = MutableStateFlow(false)
    val autoSyncOnConnect: StateFlow<Boolean> = _autoSyncOnConnect.asStateFlow()

    private val supportedExtensions = setOf("mp3", "m4a", "flac", "ogg", "opus", "wav", "aac", "webm")

    fun init(savedPersistence: DesktopPersistence) {
        persistence = savedPersistence
        _autoSyncOnConnect.value = savedPersistence.boolean(KEY_ADB_AUTO_SYNC, false)
        probeAdb()
        startMonitor()
    }

    fun setAutoSyncOnConnect(enabled: Boolean) {
        _autoSyncOnConnect.value = enabled
        persistence?.saveBoolean(KEY_ADB_AUTO_SYNC, enabled)
    }

    fun probeAdb() {
        scope.launch {
            val foundPath = findAdbExecutable()
            _adbPath.value = foundPath
            _isAdbAvailable.value = foundPath != null
            if (foundPath != null) {
                refreshDevicesNow()
            } else {
                _lastMessage.value = "ADB executable tidak ditemukan di PATH atau Android SDK"
            }
        }
    }

    private fun findAdbExecutable(): String? {
        runCatching {
            val p = ProcessBuilder("where.exe", "adb").redirectErrorStream(true).start()
            val line = p.inputStream.bufferedReader().readLine()?.trim()
            if (p.waitFor() == 0 && !line.isNullOrBlank() && File(line).canExecute()) {
                return line
            }
        }

        val localAppData = System.getenv("LOCALAPPDATA") ?: ""
        val userHome = System.getProperty("user.home") ?: ""
        val candidateLocations = listOf(
            "$localAppData\\Android\\Sdk\\platform-tools\\adb.exe",
            "$localAppData\\Programs\\Android\\Sdk\\platform-tools\\adb.exe",
            "$userHome\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe",
            System.getenv("ProgramFiles") + "\\Android\\platform-tools\\adb.exe",
            System.getenv("ProgramFiles(x86)") + "\\Android\\platform-tools\\adb.exe",
        )

        for (candidate in candidateLocations) {
            val f = File(candidate)
            if (f.isFile && f.canExecute()) {
                return candidate
            }
        }

        if (localAppData.isNotBlank()) {
            val winGetDir = File("$localAppData\\Microsoft\\WinGet\\Packages")
            if (winGetDir.isDirectory) {
                val matches = winGetDir.walkTopDown().maxDepth(4).filter { it.name.equals("adb.exe", ignoreCase = true) }
                val first = matches.firstOrNull()
                if (first != null && first.isFile && first.canExecute()) {
                    return first.absolutePath
                }
            }
        }

        return null
    }

    fun refreshDevices() {
        scope.launch {
            refreshDevicesNow()
        }
    }

    private fun refreshDevicesNow(): List<AdbDevice> {
        val adb = _adbPath.value ?: return emptyList()
        val list = mutableListOf<AdbDevice>()
        runCatching {
            val p = ProcessBuilder(adb, "devices", "-l").redirectErrorStream(true).start()
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line?.trim() ?: continue
                if (l.isEmpty() || l.startsWith("List of devices") || l.startsWith("* daemon")) continue
                val parts = l.split(Regex("\\s+"))
                if (parts.size >= 2) {
                    val serial = parts[0]
                    val state = parts[1]
                    var model = ""
                    var product = ""
                    for (i in 2 until parts.size) {
                        val part = parts[i]
                        if (part.startsWith("model:")) model = part.removePrefix("model:")
                        if (part.startsWith("product:")) product = part.removePrefix("product:")
                    }
                    if (state.equals("device", ignoreCase = true)) {
                        list.add(AdbDevice(serial, model, product, state))
                    }
                }
            }
            p.waitFor()
        }
        _connectedDevices.value = list
        val currentSerials = list.map { it.serial }.toSet()
        if (list.isEmpty()) {
            _isReverseActive.value = false
            reversedSerials = emptySet()
        } else {
            // Automatically establish USB reverse tunnel whenever an Android device is present
            if (currentSerials != reversedSerials || !_isReverseActive.value) {
                val ok = setupReverseInternal(adb, list, DesktopMobileSyncServer.port.value)
                _isReverseActive.value = ok
                if (ok) reversedSerials = currentSerials
            }
            if (!_isSyncing.value) {
                val port = DesktopMobileSyncServer.port.value
                _lastMessage.value = "${list.size} perangkat ADB terdeteksi (${list.joinToString { it.displayName }}) · Tunnel USB 127.0.0.1:$port aktif"
            }
        }
        return list
    }

    /**
     * Uses `adb reverse tcp:<port> tcp:<port>` so the Android app can connect directly
     * to `127.0.0.1:<port>` on the PC over USB without Wi-Fi or router restrictions.
     */
    fun setupPortForwarding(port: Int = DesktopMobileSyncServer.port.value) {
        scope.launch {
            val adb = _adbPath.value ?: return@launch
            val devices = refreshDevicesNow()
            if (devices.isEmpty()) {
                _isReverseActive.value = false
                _lastMessage.value = "Tidak ada perangkat ADB untuk USB tunnel"
                return@launch
            }
            val success = setupReverseInternal(adb, devices, port)
            _isReverseActive.value = success
            _lastMessage.value = if (success) {
                "USB Tunnel aktif (adb reverse tcp:$port). Di HP Android, gunakan IP 127.0.0.1 atau Auto-Discover!"
            } else {
                "Gagal mengaktifkan adb reverse tcp:$port"
            }
        }
    }

    private fun setupReverseInternal(adb: String, devices: List<AdbDevice>, port: Int): Boolean {
        if (devices.isEmpty()) return false
        var success = true
        for (dev in devices) {
            runCatching {
                val p = ProcessBuilder(adb, "-s", dev.serial, "reverse", "tcp:$port", "tcp:$port")
                    .redirectErrorStream(true)
                    .start()
                val exit = p.waitFor()
                if (exit != 0) success = false
            }.onFailure { success = false }
        }
        return success
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    /**
     * Executes Local Music sync over ADB with the specified device and direction.
     */
    fun syncMusic(
        direction: AdbSyncDirection = AdbSyncDirection.TWO_WAY_MERGE,
        targetDevice: AdbDevice? = null,
        onStatus: (String) -> Unit = {},
    ) {
        if (_isSyncing.value) return
        val adb = _adbPath.value ?: return
        val dev = targetDevice ?: _connectedDevices.value.firstOrNull() ?: run {
            _lastMessage.value = "Tidak ada perangkat Android yang terhubung via ADB"
            return
        }

        _isSyncing.value = true
        scope.launch {
            try {
                _lastMessage.value = "Menyiapkan sinkronisasi musik via ADB ke ${dev.displayName}..."
                onStatus("Memeriksa folder musik...")

                executeCommand(adb, "-s", dev.serial, "shell", "mkdir -p ${shellQuote(ANDROID_MUSIC_DIR)}")
                setupReverseInternal(adb, listOf(dev), DesktopMobileSyncServer.port.value)

                val localFolder = DesktopLocalMusic.folder()
                    ?: Paths.get(System.getProperty("user.home")).resolve("Music")
                val localFiles = if (Files.isDirectory(localFolder)) {
                    Files.walk(localFolder, 3)
                        .filter(Files::isRegularFile)
                        .filter { it.extension.lowercase() in supportedExtensions }
                        .toList()
                } else emptyList()

                val androidFilesOutput = executeCommand(adb, "-s", dev.serial, "shell", "ls -1 ${shellQuote(ANDROID_MUSIC_DIR)}")
                val androidFiles = androidFilesOutput.lines().map(String::trim).filter {
                    it.isNotBlank() && !it.startsWith("ls:")
                }.toSet()

                var pushedCount = 0
                var pulledCount = 0

                if (direction == AdbSyncDirection.PUSH_TO_MOBILE || direction == AdbSyncDirection.TWO_WAY_MERGE) {
                    for (file in localFiles) {
                        val fileName = file.fileName.toString()
                        if (fileName !in androidFiles) {
                            onStatus("Mengirim: $fileName...")
                            val remotePath = "$ANDROID_MUSIC_DIR/$fileName"
                            val pushResult = executeCommand(adb, "-s", dev.serial, "push", file.toAbsolutePath().toString(), remotePath)
                            if (!pushResult.contains("error", ignoreCase = true) && !pushResult.contains("failed", ignoreCase = true)) {
                                pushedCount++
                                val encodedFile = URLEncoder.encode(fileName, StandardCharsets.UTF_8.name()).replace("+", "%20")
                                val uriStr = "file://$ANDROID_MUSIC_DIR/$encodedFile"
                                executeCommand(
                                    adb,
                                    "-s",
                                    dev.serial,
                                    "shell",
                                    "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d ${shellQuote(uriStr)}",
                                )
                            }
                        }
                    }
                }

                if (direction == AdbSyncDirection.PULL_FROM_MOBILE || direction == AdbSyncDirection.TWO_WAY_MERGE) {
                    val localNames = localFiles.map { it.fileName.toString() }.toSet()
                    for (remoteName in androidFiles) {
                        if (remoteName !in localNames && supportedExtensions.any { remoteName.endsWith(".$it", ignoreCase = true) }) {
                            onStatus("Mengunduh: $remoteName...")
                            val targetLocalFile = localFolder.resolve(remoteName).toAbsolutePath().toString()
                            val pullResult = executeCommand(adb, "-s", dev.serial, "pull", "$ANDROID_MUSIC_DIR/$remoteName", targetLocalFile)
                            if (!pullResult.contains("error", ignoreCase = true) && !pullResult.contains("failed", ignoreCase = true)) {
                                pulledCount++
                            }
                        }
                    }
                    if (pulledCount > 0) {
                        DesktopMobileSyncServer.onLocalMusicUpdated?.invoke()
                    }
                }

                val summary = buildString {
                    append("Sync ADB selesai: ")
                    if (pushedCount > 0) append("$pushedCount lagu dikirim ke HP. ")
                    if (pulledCount > 0) append("$pulledCount lagu diunduh ke PC. ")
                    if (pushedCount == 0 && pulledCount == 0) append("Koleksi musik sudah sinkron.")
                }
                _lastMessage.value = summary
                onStatus(summary)
            } catch (e: Exception) {
                _lastMessage.value = "Error saat sinkronisasi ADB: ${e.message}"
                onStatus("Gagal: ${e.message}")
            } finally {
                _isSyncing.value = false
            }
        }
    }

    private fun executeCommand(vararg command: String): String {
        return try {
            val p = ProcessBuilder(*command).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor()
            text
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            var lastDeviceCount = 0
            while (isActive) {
                delay(5000)
                if (_isAdbAvailable.value) {
                    val list = refreshDevicesNow()
                    val currentCount = list.size
                    if (currentCount > lastDeviceCount && _autoSyncOnConnect.value) {
                        syncMusic(AdbSyncDirection.TWO_WAY_MERGE)
                    }
                    lastDeviceCount = currentCount
                }
            }
        }
    }
}
