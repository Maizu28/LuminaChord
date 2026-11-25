package com.music.bitchord.desktop

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Enforces a single running instance of LuminaChord on Desktop (Windows/macOS/Linux).
 *
 * Uses an OS-level file lock ([FileChannel.tryLock]) to detect whether an instance is
 * already running, paired with a local loopback IPC socket so a secondary launch brings
 * the existing window to the foreground and exits immediately.
 */
internal object SingleInstanceLock {

    private var randomAccessFile: RandomAccessFile? = null
    private var lockChannel: FileChannel? = null
    private var fileLock: FileLock? = null
    private var serverSocket: ServerSocket? = null
    private val isPrimaryInstance = AtomicBoolean(false)

    private val appDir: File by lazy {
        val path = if (DesktopPlatform.isWindows) {
            val localAppData = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
                ?: "${System.getProperty("user.home")}/AppData/Local"
            File(localAppData, "LuminaChord")
        } else {
            val configHome = System.getenv("XDG_CONFIG_HOME")?.takeIf(String::isNotBlank)
                ?: "${System.getProperty("user.home")}/.config"
            File(configHome, "luminachord")
        }
        path.apply { mkdirs() }
    }

    private val lockFile by lazy { File(appDir, "luminachord.lock") }
    private val portFile by lazy { File(appDir, "luminachord.port") }

    /**
     * Tries to acquire the single-instance lock.
     *
     * @return `true` if this process is the primary instance (can continue execution);
     *         `false` if another instance is already running (notified and should exit).
     */
    fun acquire(): Boolean {
        try {
            val raf = RandomAccessFile(lockFile, "rw")
            randomAccessFile = raf
            val channel = raf.channel
            lockChannel = channel

            val lock = channel.tryLock()
            if (lock != null) {
                fileLock = lock
                isPrimaryInstance.set(true)

                startIpcServer()

                Runtime.getRuntime().addShutdownHook(Thread {
                    release()
                })
                return true
            }
        } catch (e: Exception) {
            DesktopTrackLog.log("SingleInstanceLock: lock acquisition attempt failed: ${e.message}")
        }

        // Another instance holds the lock: notify it to show itself
        notifyRunningInstance()
        return false
    }

    private fun startIpcServer() {
        try {
            // Bind to loopback on an ephemeral free port
            val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
            serverSocket = server
            val port = server.localPort

            portFile.writeText(port.toString())
            DesktopTrackLog.log("SingleInstanceLock: primary instance listening on loopback port $port")

            thread(name = "LuminaChord-SingleInstance-IPC", isDaemon = true) {
                while (!server.isClosed) {
                    try {
                        val client = server.accept()
                        thread(isDaemon = true) {
                            try {
                                client.soTimeout = 3000
                                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                                val writer = PrintWriter(client.getOutputStream(), true)
                                val command = reader.readLine()
                                if (command == "SHOW") {
                                    DesktopTrackLog.log("SingleInstanceLock: received SHOW command from secondary launch")
                                    DesktopWindowVisibility.show()
                                    writer.println("OK")
                                }
                            } catch (_: Exception) {
                            } finally {
                                runCatching { client.close() }
                            }
                        }
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            DesktopTrackLog.log("SingleInstanceLock: failed to start IPC server: ${e.message}")
        }
    }

    private fun notifyRunningInstance() {
        try {
            if (!portFile.exists()) {
                DesktopTrackLog.log("SingleInstanceLock: port file not found, cannot send IPC command")
                return
            }
            val portStr = portFile.readText().trim()
            val port = portStr.toIntOrNull() ?: return

            DesktopTrackLog.log("SingleInstanceLock: contacting primary instance on port $port to bring window to front")
            Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
                socket.soTimeout = 2000
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                writer.println("SHOW")
                val response = reader.readLine()
                DesktopTrackLog.log("SingleInstanceLock: primary instance response: $response")
            }
        } catch (e: Exception) {
            DesktopTrackLog.log("SingleInstanceLock: could not contact running instance: ${e.message}")
        }
    }

    fun release() {
        if (!isPrimaryInstance.get()) return
        runCatching { serverSocket?.close() }
        runCatching { fileLock?.release() }
        runCatching { lockChannel?.close() }
        runCatching { randomAccessFile?.close() }
        runCatching { portFile.delete() }
        runCatching { lockFile.delete() }
    }
}
