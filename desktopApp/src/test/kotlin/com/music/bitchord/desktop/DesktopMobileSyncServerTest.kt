package com.music.bitchord.desktop

import com.music.bitchord.data.lyrics.LyricAlignment
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricWord
import com.music.bitchord.data.model.Song
import com.music.bitchord.desktop.sync.DesktopHandoffState
import com.music.bitchord.desktop.sync.DesktopLyricsSnapshot
import com.music.bitchord.desktop.sync.DesktopMobileSyncServer
import com.music.bitchord.desktop.sync.DesktopNowPlayingInfo
import com.music.bitchord.desktop.sync.DesktopQueueSnapshot
import com.music.bitchord.desktop.sync.LibrarySyncData
import com.music.bitchord.desktop.sync.SyncRemoteCommand
import com.music.bitchord.desktop.sync.SyncedSongDto
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopMobileSyncServerTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun testPairSyncWithUtf8AndRemoteCommands() {
        val port = freePort()
        DesktopMobileSyncServer.stopServer()
        DesktopMobileSyncServer.setRequirePairingCode(true)
        DesktopMobileSyncServer.setCustomPairingCode("1234")
        DesktopMobileSyncServer.setPort(port)

        var remoteReceived: Pair<String, String?>? = null
        var syncedIncoming: LibrarySyncData? = null

        DesktopMobileSyncServer.nowPlayingProvider = {
            DesktopNowPlayingInfo(
                title = "What is Love? — 밤편지",
                artist = "TWICE (트와이스)",
                durationMs = 200_000L,
                positionMs = 45_000L,
                isPlaying = true,
                volume = 0.75f,
            )
        }
        DesktopMobileSyncServer.onRemoteCommand = { action, value ->
            remoteReceived = action to value
            true
        }
        DesktopMobileSyncServer.onLibraryMergeReceived = { incoming ->
            syncedIncoming = incoming
            LibrarySyncData(
                likedIds = incoming.likedIds + "desktop_fav_1",
                playlists = incoming.playlists + DesktopPlaylist(
                    id = "pc_pl",
                    title = "Playlist PC",
                    songs = listOf(Song(videoId = "pc1", title = "PC Song", artist = "Artist", thumbnailUrl = null)),
                ),
            )
        }

        DesktopMobileSyncServer.startServer()

        // Wait up to 3s for server socket to bind
        var online = false
        for (i in 0 until 30) {
            if (DesktopMobileSyncServer.isRunning.value) {
                online = true
                break
            }
            Thread.sleep(100)
        }
        assertTrue(online, "Server should start cleanly")

        try {
            // 1. Check /api/status
            val statusConn = URL("http://127.0.0.1:$port/api/status").openConnection() as HttpURLConnection
            assertEquals(200, statusConn.responseCode)
            val statusBody = statusConn.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            assertTrue(statusBody.contains("\"app\":\"LuminaChord\""))
            assertTrue(statusBody.contains("What is Love?"))

            // 2. Wrong PIN rejected with 403
            val badPair = URL("http://127.0.0.1:$port/api/pair").openConnection() as HttpURLConnection
            badPair.requestMethod = "POST"
            badPair.doOutput = true
            badPair.setRequestProperty("Content-Type", "application/json")
            OutputStreamWriter(badPair.outputStream, StandardCharsets.UTF_8).use {
                it.write("""{"device":"Pixel 9","code":"9999"}""")
            }
            assertEquals(403, badPair.responseCode)

            // 3. Correct PIN succeeds with 200 and registers client
            val goodPair = URL("http://127.0.0.1:$port/api/pair").openConnection() as HttpURLConnection
            goodPair.requestMethod = "POST"
            goodPair.doOutput = true
            goodPair.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            OutputStreamWriter(goodPair.outputStream, StandardCharsets.UTF_8).use {
                it.write("""{"device":"Samsung S24 Ultra — 日本語","code":"1234"}""")
            }
            assertEquals(200, goodPair.responseCode)
            assertTrue(DesktopMobileSyncServer.connectedClients.value.any { it.deviceName.contains("Samsung S24") })

            // 4. Sync with multi-byte UTF-8 body MUST NOT hang or truncate!
            val syncConn = URL("http://127.0.0.1:$port/api/sync").openConnection() as HttpURLConnection
            syncConn.requestMethod = "POST"
            syncConn.connectTimeout = 3000
            syncConn.readTimeout = 3000
            syncConn.doOutput = true
            syncConn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            val utf8Payload = """{
                "device": "Android",
                "syncPlaylists": true,
                "syncFavorites": true,
                "syncHistory": true,
                "likedIds": ["mob1"],
                "playlists": [{
                    "id": "m_pl",
                    "title": "Favorit HP — 🎵",
                    "songs": [{"videoId":"vid_1","title":"봄 눈 — Spring Snow","artist":"10CM · 십센치"}]
                }]
            }"""
            OutputStreamWriter(syncConn.outputStream, StandardCharsets.UTF_8).use { it.write(utf8Payload) }
            assertEquals(200, syncConn.responseCode)
            val syncRes = syncConn.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            assertTrue(syncRes.contains("desktop_fav_1"))
            assertTrue(syncRes.contains("Playlist PC"))
            assertTrue(syncedIncoming?.likedIds?.contains("mob1") == true)
            assertEquals("봄 눈 — Spring Snow", syncedIncoming?.playlists?.firstOrNull()?.songs?.firstOrNull()?.title)

            // 5. Remote command
            val remConn = URL("http://127.0.0.1:$port/api/remote").openConnection() as HttpURLConnection
            remConn.requestMethod = "POST"
            remConn.doOutput = true
            remConn.setRequestProperty("Content-Type", "application/json")
            OutputStreamWriter(remConn.outputStream, StandardCharsets.UTF_8).use {
                it.write("""{"action":"volume","value":"0.5"}""")
            }
            assertEquals(200, remConn.responseCode)
            assertEquals("volume" to "0.5", remoteReceived)

            // 6. Local Music list, merge diff, and file streaming
            val tempAudio = java.nio.file.Files.createTempFile("lumina_sync_test_", ".mp3").toFile()
            val sampleBytes = byteArrayOf(0x49, 0x44, 0x33, 0x03, 0x00, 0x01, 0x02, 0x03)
            tempAudio.writeBytes(sampleBytes)
            try {
                DesktopMobileSyncServer.localSongsProvider = {
                    listOf(
                        Song(
                            videoId = "local:test1",
                            title = "Lagu PC",
                            artist = "Penyanyi PC",
                            thumbnailUrl = null,
                            localPath = tempAudio.absolutePath,
                        ),
                    )
                }

                val mergeConn = URL("http://127.0.0.1:$port/api/sync/local-music/merge").openConnection() as HttpURLConnection
                mergeConn.requestMethod = "POST"
                mergeConn.doOutput = true
                mergeConn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                OutputStreamWriter(mergeConn.outputStream, StandardCharsets.UTF_8).use {
                    it.write("""{"mobileCount":1,"mobileSongs":[{"title":"Lagu HP","artist":"Penyanyi HP","fileName":"hp.mp3","sizeBytes":123}]}""")
                }
                assertEquals(200, mergeConn.responseCode)
                val mergeBody = mergeConn.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
                assertTrue(mergeBody.contains("\"missingOnMobileCount\":1"))
                assertTrue(mergeBody.contains("\"missingOnDesktopCount\":1"))
                assertTrue(mergeBody.contains("Lagu PC"))

                val encodedPath = java.net.URLEncoder.encode(tempAudio.absolutePath, StandardCharsets.UTF_8.name())
                val fileConn = URL("http://127.0.0.1:$port/api/sync/local-music/file?path=$encodedPath").openConnection() as HttpURLConnection
                assertEquals(200, fileConn.responseCode)
                val downloadedBytes = fileConn.inputStream.readBytes()
                assertTrue(downloadedBytes.contentEquals(sampleBytes), "Downloaded audio bytes should match exact file content")
            } finally {
                DesktopMobileSyncServer.localSongsProvider = null
                tempAudio.delete()
            }

            // 7. Network IP list should never prefer 169.254.x.x link-local
            val ips = DesktopMobileSyncServer.getLocalIpAddresses()
            assertFalse(ips.any { it.startsWith("169.254.") }, "Link-local IPs should be excluded: $ips")
        } finally {
            DesktopMobileSyncServer.stopServer()
        }
    }

    @Test
    fun testQueueLyricsDiffAndBidirectionalOutputHandoff() {
        val port = freePort()
        DesktopMobileSyncServer.stopServer()
        DesktopMobileSyncServer.disconnectAllClients()
        DesktopMobileSyncServer.setRequirePairingCode(false)
        DesktopMobileSyncServer.setPort(port)

        val commandsReceived = mutableListOf<SyncRemoteCommand>()
        var handoffToDesktopSongs: List<SyncedSongDto>? = null
        var handoffToDesktopPos: Long = -1L

        val queueSongs = listOf(
            Song(videoId = "v1", title = "The Night We Met", artist = "Lord Huron", thumbnailUrl = null),
            Song(videoId = "v2", title = "Cupid", artist = "FIFTY FIFTY", thumbnailUrl = null),
        )
        DesktopMobileSyncServer.nowPlayingProvider = {
            DesktopNowPlayingInfo(
                videoId = "v1",
                title = "The Night We Met",
                artist = "Lord Huron",
                durationMs = 208_000L,
                positionMs = 42_000L,
                isPlaying = true,
                volume = 0.8f,
                shuffle = true,
                repeatMode = 2,
            )
        }
        DesktopMobileSyncServer.queueSnapshotProvider = {
            DesktopQueueSnapshot(
                version = 10L,
                queueIndex = 0,
                hasPrevious = false,
                hasNext = true,
                songs = queueSongs,
            )
        }
        DesktopMobileSyncServer.lyricsSnapshotProvider = {
            DesktopLyricsSnapshot(
                version = 5L,
                videoId = "v1",
                source = "lrc.red",
                loading = false,
                unavailable = false,
                lines = listOf(
                    LyricLine(
                        timeMs = 40_000L,
                        text = "I had all and then most of you",
                        words = listOf(LyricWord(40_000L, 41_000L, "I")),
                        alignment = LyricAlignment.Start,
                    ),
                ),
            )
        }
        DesktopMobileSyncServer.onStructuredRemoteCommand = { cmd ->
            commandsReceived.add(cmd)
            true
        }
        DesktopMobileSyncServer.onPrepareHandoffToMobile = {
            DesktopHandoffState(
                songs = queueSongs,
                queueIndex = 0,
                positionMs = 43_210L,
                isPlaying = true,
                shuffle = true,
                repeatMode = 2,
            )
        }
        DesktopMobileSyncServer.onHandoffToDesktop = { songs, _, posMs, _, _, _ ->
            handoffToDesktopSongs = songs
            handoffToDesktopPos = posMs
            true
        }

        DesktopMobileSyncServer.startServer()
        var online = false
        for (i in 0 until 30) {
            if (DesktopMobileSyncServer.isRunning.value) {
                online = true
                break
            }
            Thread.sleep(100)
        }
        assertTrue(online)

        try {
            // 1. Pair client
            val pairConn = URL("http://127.0.0.1:$port/api/pair").openConnection() as HttpURLConnection
            pairConn.requestMethod = "POST"
            pairConn.doOutput = true
            pairConn.setRequestProperty("Content-Type", "application/json")
            OutputStreamWriter(pairConn.outputStream, StandardCharsets.UTF_8).use {
                it.write("""{"device":"Pixel 9 Pro","code":""}""")
            }
            assertEquals(200, pairConn.responseCode)

            // 2. First status poll with stale qRev/lRev includes queue & synced lyrics
            val st1 = (URL("http://127.0.0.1:$port/api/status?qRev=-1&lRev=-1").openConnection() as HttpURLConnection)
            assertEquals(200, st1.responseCode)
            val body1 = st1.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            assertTrue(body1.contains("\"videoId\":\"v1\""))
            assertTrue(body1.contains("I had all and then most of you"))
            assertTrue(body1.contains("\"songs\":["))

            // 3. Subsequent poll with matching versions omits heavy queue & lyrics objects
            val st2 = (URL("http://127.0.0.1:$port/api/status?qRev=10&lRev=5").openConnection() as HttpURLConnection)
            assertEquals(200, st2.responseCode)
            val body2 = st2.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            assertTrue(body2.contains("\"queue\":null"))
            assertTrue(body2.contains("\"lyrics\":null"))

            // 4. Structured queue edit (move_queue) reaches callback
            val moveConn = (URL("http://127.0.0.1:$port/api/remote").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(moveConn.outputStream, StandardCharsets.UTF_8).use {
                it.write("""{"action":"move_queue","from":1,"to":0}""")
            }
            assertEquals(200, moveConn.responseCode)
            assertTrue(commandsReceived.any { it.action == "move_queue" && it.from == 1 && it.to == 0 })

            // 5. Desktop -> Mobile prepare_handoff_to_mobile returns exact atomic playback state
            val handoffConn = (URL("http://127.0.0.1:$port/api/remote").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(handoffConn.outputStream, StandardCharsets.UTF_8).use {
                it.write("""{"action":"prepare_handoff_to_mobile"}""")
            }
            assertEquals(200, handoffConn.responseCode)
            val handoffBody = handoffConn.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            assertTrue(handoffBody.contains("\"positionMs\":43210"))
            assertTrue(handoffBody.contains("\"repeatMode\":2"))
            assertEquals("mobile", DesktopMobileSyncServer.activeAudioOutput.value)

            // 6. Mobile -> Desktop handoff restores track & position on desktop
            val backConn = (URL("http://127.0.0.1:$port/api/remote").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(backConn.outputStream, StandardCharsets.UTF_8).use {
                it.write("""{"action":"handoff_to_desktop","index":0,"positionMs":99000,"isPlaying":true,"songs":[{"videoId":"v2","title":"Cupid","artist":"FIFTY FIFTY"}]}""")
            }
            assertEquals(200, backConn.responseCode)
            assertEquals("desktop", DesktopMobileSyncServer.activeAudioOutput.value)
            assertEquals(99_000L, handoffToDesktopPos)
            assertEquals("v2", handoffToDesktopSongs?.firstOrNull()?.videoId)

            // 7. Desktop initiating switchOutputToMobile queues pending command and ack clears it
            assertTrue(DesktopMobileSyncServer.switchOutputToMobile())
            assertEquals("mobile", DesktopMobileSyncServer.activeAudioOutput.value)
            val stPending = (URL("http://127.0.0.1:$port/api/status?qRev=10&lRev=5").openConnection() as HttpURLConnection)
                .inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            assertTrue(stPending.contains("\"action\":\"handoff_play\""))
            val cmdId = Regex("\"id\":(\\d+)").find(stPending)?.groupValues?.get(1)?.toLong() ?: 0L
            assertTrue(cmdId > 0L)

            val stAcked = (URL("http://127.0.0.1:$port/api/status?qRev=10&lRev=5&ack=$cmdId").openConnection() as HttpURLConnection)
                .inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            assertTrue(stAcked.contains("\"pendingCommands\":[]"))
        } finally {
            DesktopMobileSyncServer.nowPlayingProvider = null
            DesktopMobileSyncServer.queueSnapshotProvider = null
            DesktopMobileSyncServer.lyricsSnapshotProvider = null
            DesktopMobileSyncServer.onStructuredRemoteCommand = null
            DesktopMobileSyncServer.onPrepareHandoffToMobile = null
            DesktopMobileSyncServer.onHandoffToDesktop = null
            DesktopMobileSyncServer.stopServer()
        }
    }

    @Test
    fun testMobileStatePushWithLyricsAutoSwitchAndDesktopControls() {
        val port = freePort()
        DesktopMobileSyncServer.stopServer()
        DesktopMobileSyncServer.setRequirePairingCode(false)
        DesktopMobileSyncServer.setPort(port)
        DesktopMobileSyncServer.setActiveOutputLocal("desktop")
        DesktopMobileSyncServer.nowPlayingProvider = {
            DesktopNowPlayingInfo(title = "Idle Song", artist = "Artist", isPlaying = false)
        }

        DesktopMobileSyncServer.startServer()
        var online = false
        for (i in 0 until 30) {
            if (DesktopMobileSyncServer.isRunning.value) {
                online = true
                break
            }
            Thread.sleep(100)
        }
        assertTrue(online)

        try {
            // 1. Mobile pushes playback state with synced lyrics while desktop is paused
            val push1 = (URL("http://127.0.0.1:$port/api/mobile/state").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(push1.outputStream, StandardCharsets.UTF_8).use {
                it.write(
                    """
                    {
                      "deviceName": "Pixel 9",
                      "videoId": "mob1",
                      "title": "Cupid",
                      "artist": "FIFTY FIFTY",
                      "durationMs": 174000,
                      "positionMs": 12500,
                      "isPlaying": true,
                      "volume": 0.65,
                      "shuffle": true,
                      "repeatMode": 2,
                      "queueIndex": 0,
                      "songs": [{"videoId": "mob1", "title": "Cupid", "artist": "FIFTY FIFTY"}],
                      "lyrics": {
                        "version": 1,
                        "videoId": "mob1",
                        "source": "LrcLib",
                        "loading": false,
                        "unavailable": false,
                        "lines": [{"timeMs": 12000, "text": "A hopeless romantic all my life", "words": [{"startMs": 12000, "endMs": 12400, "text": "A"}]}]
                      }
                    }
                    """.trimIndent(),
                )
            }
            assertEquals(200, push1.responseCode)
            assertEquals("mobile", DesktopMobileSyncServer.activeAudioOutput.value)
            val snap1 = DesktopMobileSyncServer.mobileSession.value
            assertEquals("Cupid", snap1?.title)
            assertEquals(1, snap1?.lyrics?.lines?.size)
            assertEquals("A hopeless romantic all my life", snap1?.lyrics?.lines?.first()?.text)

            // 2. Subsequent lightweight position push omits lyrics, server preserves cached lyrics for same track
            val push2 = (URL("http://127.0.0.1:$port/api/mobile/state").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(push2.outputStream, StandardCharsets.UTF_8).use {
                it.write("""{"deviceName":"Pixel 9","videoId":"mob1","title":"Cupid","artist":"FIFTY FIFTY","durationMs":174000,"positionMs":14500,"isPlaying":true}""")
            }
            assertEquals(200, push2.responseCode)
            val snap2 = DesktopMobileSyncServer.mobileSession.value
            assertEquals(14_500L, snap2?.positionMs)
            assertEquals(1, snap2?.lyrics?.lines?.size)

            // 3. Desktop queues control commands (play_pause, set_shuffle, set_repeat, seek) for Android to pick up
            DesktopMobileSyncServer.sendCommandToMobile("play_pause")
            DesktopMobileSyncServer.sendCommandToMobile("set_shuffle", shuffle = false, value = "false")
            DesktopMobileSyncServer.sendCommandToMobile("seek", positionMs = 42_000L)
            val st = (URL("http://127.0.0.1:$port/api/status").openConnection() as HttpURLConnection)
                .inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            assertTrue(st.contains("\"action\":\"play_pause\""))
            assertTrue(st.contains("\"action\":\"set_shuffle\""))
            assertTrue(st.contains("\"action\":\"seek\""))
            assertTrue(st.contains("\"positionMs\":42000"))

            // 4. Switching to mobile while mobile is already playing attaches instead of overwriting mobile's queue
            DesktopMobileSyncServer.setActiveOutputLocal("desktop")
            assertTrue(DesktopMobileSyncServer.switchOutputToMobile())
            val stAttach = (URL("http://127.0.0.1:$port/api/status").openConnection() as HttpURLConnection)
                .inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            assertTrue(stAttach.contains("\"action\":\"select_mobile_output\""))
            assertFalse(stAttach.contains("\"action\":\"handoff_play\""))
        } finally {
            DesktopMobileSyncServer.nowPlayingProvider = null
            DesktopMobileSyncServer.setActiveOutputLocal("desktop")
            DesktopMobileSyncServer.stopServer()
        }
    }

    @Test
    fun testUsbTunnelPairingRequiresOpenAppOnPhone() {
        val port = freePort()
        DesktopMobileSyncServer.setEnabled(false)
        DesktopMobileSyncServer.disconnectAllClients()
        DesktopMobileSyncServer.setRequirePairingCode(true)
        DesktopMobileSyncServer.setCustomPairingCode("5678")
        DesktopMobileSyncServer.setPort(port)
        DesktopMobileSyncServer.setEnabled(true)

        var online = false
        for (i in 0 until 40) {
            if (DesktopMobileSyncServer.isRunning.value) {
                online = true
                break
            }
            Thread.sleep(100)
        }
        assertTrue(online, "Sync server should listen on $port")

        // Pure PID parser tests for Android LuminaChord process check
        val adbCls = com.music.bitchord.desktop.sync.AdbSyncManager
        assertFalse(adbCls.isPidofOutputRunning(""))
        assertFalse(adbCls.isPidofOutputRunning("   \n"))
        assertFalse(adbCls.isPidofOutputRunning("/system/bin/sh: pidof: inaccessible"))
        assertTrue(adbCls.isPidofOutputRunning("18422"))
        assertTrue(adbCls.isPidofOutputRunning("18422 18490\n"))
        assertFalse(adbCls.hasActiveAppTunnel())

        val originalValidator = DesktopMobileSyncServer.usbTunnelValidator
        try {
            // 1. USB loopback request without active LuminaChord USB tunnel must still be rejected (403)
            DesktopMobileSyncServer.usbTunnelValidator = { false }
            val rejectedConn = (URL("http://127.0.0.1:$port/api/pair").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(rejectedConn.outputStream, StandardCharsets.UTF_8).use {
                it.write("""{"device":"Unknown USB Phone","code":"","transport":"usb"}""")
            }
            assertEquals(403, rejectedConn.responseCode)

            // 2. When Sync is active AND LuminaChord is confirmed running on the USB phone, auto-pair succeeds
            DesktopMobileSyncServer.usbTunnelValidator = { true }
            val acceptedConn = (URL("http://127.0.0.1:$port/api/pair").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(acceptedConn.outputStream, StandardCharsets.UTF_8).use {
                it.write("""{"device":"Galaxy S24","code":"","transport":"usb"}""")
            }
            assertEquals(200, acceptedConn.responseCode)
            assertTrue(DesktopMobileSyncServer.connectedClients.value.any { it.deviceName == "Galaxy S24" })
        } finally {
            DesktopMobileSyncServer.usbTunnelValidator = originalValidator
            DesktopMobileSyncServer.stopServer()
        }
    }

    @Test
    fun testUnifiedSingleActivePlaybackRouting() {
        val port = freePort()
        DesktopMobileSyncServer.setEnabled(false)
        DesktopMobileSyncServer.disconnectAllClients()
        DesktopMobileSyncServer.setRequirePairingCode(false)
        DesktopMobileSyncServer.setPort(port)
        DesktopMobileSyncServer.setEnabled(true)

        var online = false
        for (i in 0 until 40) {
            if (DesktopMobileSyncServer.isRunning.value) {
                online = true
                break
            }
            Thread.sleep(100)
        }
        assertTrue(online)

        var desktopPausedCount = 0
        var desktopPlaying = false
        DesktopMobileSyncServer.nowPlayingProvider = {
            DesktopNowPlayingInfo(title = "Track PC", artist = "Artist", isPlaying = desktopPlaying)
        }
        DesktopMobileSyncServer.onPauseDesktopPlayback = { desktopPausedCount++ }

        try {
            // Pair mobile
            val pairConn = (URL("http://127.0.0.1:$port/api/pair").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(pairConn.outputStream, StandardCharsets.UTF_8).use {
                it.write("""{"device":"OPPO CPH2565"}""")
            }
            assertEquals(200, pairConn.responseCode)

            // 1. Mobile starts playing "ocean eyes" while Desktop is paused -> Desktop follows "mobile" output & ensures PC is paused
            val pushConn = (URL("http://127.0.0.1:$port/api/mobile/state").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(pushConn.outputStream, StandardCharsets.UTF_8).use {
                it.write("""{"deviceName":"OPPO CPH2565","videoId":"ocean_id","title":"ocean eyes","artist":"Billie Eilish","isPlaying":true}""")
            }
            assertEquals(200, pushConn.responseCode)
            assertEquals("mobile", DesktopMobileSyncServer.activeAudioOutput.value)
            assertTrue(desktopPausedCount >= 1)

            // 2. Picking a track on Desktop while in mobile output queues "play_song" / "enqueue_song" for Mobile instead of switching output
            DesktopMobileSyncServer.sendCommandToMobile(
                action = "play_song",
                isPlaying = true,
                song = SyncedSongDto(videoId = "back_id", title = "Backburner", artist = "NIKI"),
            )
            DesktopMobileSyncServer.sendCommandToMobile(
                action = "enqueue_song",
                playNext = true,
                song = SyncedSongDto(videoId = "next_id", title = "Next Track", artist = "Artist"),
            )
            assertEquals("mobile", DesktopMobileSyncServer.activeAudioOutput.value)
            val st1 = (URL("http://127.0.0.1:$port/api/status").openConnection() as HttpURLConnection)
                .inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            assertTrue(st1.contains("\"action\":\"play_song\""))
            assertTrue(st1.contains("\"title\":\"Backburner\""))
            assertTrue(st1.contains("\"action\":\"enqueue_song\""))
            assertTrue(st1.contains("\"playNext\":true"))

            // 3. Starting local playback on Desktop pauses paired Mobile so both devices never play separate songs
            desktopPlaying = true
            DesktopMobileSyncServer.notifyDesktopPlaybackStarted()
            assertEquals("desktop", DesktopMobileSyncServer.activeAudioOutput.value)
            val st2 = (URL("http://127.0.0.1:$port/api/status").openConnection() as HttpURLConnection)
                .inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            assertTrue(st2.contains("\"action\":\"pause_for_desktop_handoff\""))
        } finally {
            DesktopMobileSyncServer.nowPlayingProvider = null
            DesktopMobileSyncServer.onPauseDesktopPlayback = null
            DesktopMobileSyncServer.disconnectAllClients()
            DesktopMobileSyncServer.setActiveOutputLocal("desktop")
            DesktopMobileSyncServer.stopServer()
        }
    }
}
