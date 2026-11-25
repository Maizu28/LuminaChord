package com.music.bitchord.desktop

import com.music.bitchord.data.listentogether.ApiError
import com.music.bitchord.data.listentogether.JoinRequest
import com.music.bitchord.data.listentogether.JamInvite
import com.music.bitchord.data.listentogether.PartyActivity
import com.music.bitchord.data.listentogether.PartyMember
import com.music.bitchord.data.listentogether.PartyMembership
import com.music.bitchord.data.listentogether.PartyPlayback
import com.music.bitchord.data.listentogether.PartyPreview
import com.music.bitchord.data.listentogether.PartyQueue
import com.music.bitchord.data.listentogether.PartySnapshot
import com.music.bitchord.data.listentogether.PartyTrack
import com.music.bitchord.data.listentogether.ServerClock
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Listening together: this device's half of a party, ported from Android's `ListenTogether`.
 *
 * The protocol is the server's, documented in `backend/README.md`, and the wire types are the same
 * ones Android uses — both compile `:shared`'s `PartyModels`. What differs here is only the
 * plumbing: CIO rather than OkHttp, [DesktopPersistence] rather than `SharedPreferences`, and a
 * monotonic clock from `System.nanoTime`.
 */
internal object DesktopListenTogether {

    enum class Connection { OFFLINE, CONNECTING, LIVE }
    enum class Health { CHECKING, ONLINE, OFFLINE }

    data class ServerStatus(
        val health: Health = Health.CHECKING,
        val latencyMs: Long = 0,
        val isFallback: Boolean = false,
    )

    data class State(
        val code: String? = null,
        val you: PartyMember? = null,
        val members: List<PartyMember> = emptyList(),
        val maxMembers: Int = 5,
        val hostOnlyControl: Boolean = false,
        val playback: PartyPlayback = PartyPlayback(),
        /** Held apart from [playback]: the state frame carries only a sequence number for it. */
        val queue: PartyQueue = PartyQueue(),
        val connection: Connection = Connection.OFFLINE,
        /** False until the first round trip; the playhead is a guess until then. */
        val clockSynced: Boolean = false,
        val roundTripMs: Long = 0,
        val error: String? = null,
    ) {
        val inParty: Boolean get() = code != null
        val isFull: Boolean get() = members.size >= maxMembers
        val controlsLocked: Boolean get() = inParty && hostOnlyControl && you?.isHost != true
    }

    /** A refusal from the server, carrying the machine-readable half. */
    class PartyException(
        val code: String,
        message: String,
        val statusCode: Int? = null,
    ) : Exception(message)

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    /** Official Listen Together server used by BitChord / LuminaChord Mobile (`app/build.gradle.kts`). */
    const val OFFICIAL_SERVER = "https://bitchord-listen-together.onrender.com"
    /** Secondary official API server (used by release APKs built against the Kushagra domain & fallback). */
    const val BACKUP_SERVER = "https://api.bitchord.kushagrasingh.in"

    /** Where the party server lives, defaulting to the mobile backend if unspecified. */
    val DEFAULT_SERVER: String by lazy {
        val sys = System.getProperty("bitchord.listentogether.server").orEmpty()
        normalizeServerUrl(sys).getOrNull()?.takeIf { it.isNotBlank() } ?: OFFICIAL_SERVER
    }

    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        // No global requestTimeoutMillis: WebSocket frames stay open indefinitely while
        // REST / healthz calls bound themselves via per-request `timeout { ... }`.
        install(HttpTimeout)
        install(WebSockets) {
            pingIntervalMillis = 20_000L
        }
        // Disabled so 404/409/429/5xx JSON errors arrive in `toPartyException()` and
        // trigger server fallback cleanly instead of surfacing as generic transport errors.
        expectSuccess = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clock = ServerClock()
    private val persistence by lazy { DesktopPersistence() }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _activity = MutableStateFlow<List<PartyActivity>>(emptyList())
    val activity: StateFlow<List<PartyActivity>> = _activity.asStateFlow()

    private val _customServer = MutableStateFlow(DesktopPersistence().string(KEY_SERVER))
    val customServerUrl: StateFlow<String> = _customServer.asStateFlow()

    private val _serverStatus = MutableStateFlow(ServerStatus())
    val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

    /** Whether there is an address to talk to at all. */
    val hasServer: Boolean get() = DEFAULT_SERVER.isNotBlank() || _customServer.value.isNotBlank()

    private var socketJob: Job? = null
    private var healthJob: Job? = null
    private var session: DefaultClientWebSocketSession? = null
    private var token: String? = null
    @Volatile private var effectiveIdleServerBase: String = DEFAULT_SERVER
    @Volatile private var activePartyServerBase: String? = null

    init {
        // A process cannot resume the old socket safely, and leaving its token around occupies a
        // party slot until the server's grace period expires. Android hands that stale slot back
        // during init; desktop does the same before offering a new room.
        val staleCode = persistence.string(KEY_CODE)
        val staleToken = persistence.string(KEY_TOKEN)
        val staleServer = persistence.string(KEY_ACTIVE_SERVER).ifBlank { defaultHttpBase() }
        persistence.saveString(KEY_CODE, "")
        persistence.saveString(KEY_TOKEN, "")
        persistence.saveString(KEY_ACTIVE_SERVER, "")
        if (staleCode.isNotBlank() && staleToken.isNotBlank()) {
            scope.launch {
                runCatching {
                    http.post("$staleServer/api/parties/$staleCode/leave") {
                        header("Authorization", "Bearer $staleToken")
                    }
                }
            }
        }
        refreshServerHealth()
    }

    fun setCustomServerUrl(value: String): Result<Unit> = runCatching {
        check(!_state.value.inParty) { "Leave the current party before changing servers." }
        val normalized = normalizeServerUrl(value).getOrThrow()
        persistence.saveString(KEY_SERVER, normalized)
        _customServer.value = normalized
        refreshServerHealth()
    }

    /** Canonicalises a user-entered server without ever exposing the build's default address. */
    fun normalizeServerUrl(value: String): Result<String> = runCatching {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return@runCatching ""
        if (trimmed.any(Char::isWhitespace)) throw PartyException("bad_server", "Remove spaces from the server address.")
        val candidate = if ("://" in trimmed) trimmed else "https://$trimmed"
        val uri = runCatching { URI(candidate) }.getOrElse {
            throw PartyException("bad_server", "Enter a valid party server address.")
        }
        val rawHost = uri.host
        if (uri.scheme?.lowercase() !in setOf("http", "https") || rawHost.isNullOrBlank()) {
            throw PartyException("bad_server", "Use an http or https party server address.")
        }
        if (candidate.contains('?') || candidate.contains('#') || uri.userInfo != null) {
            throw PartyException("bad_server", "The server address can't include credentials, a query, or a fragment.")
        }
        if (uri.port != -1 && uri.port !in 1..65535) {
            throw PartyException("bad_server", "The server address has an invalid port.")
        }
        val path = uri.rawPath.orEmpty().trimEnd('/')
        if (path.split('/').any { it == "." || it == ".." }) {
            throw PartyException("bad_server", "The server address has an invalid path.")
        }
        var host = rawHost.lowercase()
        // If user enters bitchord.kushagrasingh.in (the website), automatically redirect to the party API server
        if (host == "bitchord.kushagrasingh.in") {
            host = "api.bitchord.kushagrasingh.in"
        }
        if (host != "localhost" && ':' !in host && '.' !in host) {
            throw PartyException("bad_server", "Enter a complete party server hostname.")
        }
        if (':' !in host && host != "localhost") {
            val validLabel = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$")
            if (host.split('.').any { it.isEmpty() || it.length > 63 || !validLabel.matches(it) }) {
                throw PartyException("bad_server", "Enter a valid party server hostname.")
            }
        }
        URI(uri.scheme.lowercase(), null, host, uri.port, path.ifBlank { null }, null, null)
            .toASCIIString()
            .trimEnd('/')
    }

    fun refreshServerHealth() {
        healthJob?.cancel()
        _serverStatus.value = ServerStatus(Health.CHECKING)
        healthJob = scope.launch { resolveIdleServer(forceProbe = true) }
    }

    fun nickname(): String = persistence.string(KEY_NICKNAME)

    fun setNickname(value: String) = persistence.saveString(KEY_NICKNAME, value.trim().take(80))

    fun myAvatarUrl(): String? = identity()?.avatar

    private val discoveredServerByCode = ConcurrentHashMap<String, String>()

    data class ParsedPartyInput(
        val code: String,
        val serverUrl: String? = null,
    )

    /**
     * Parses any six-character party code OR shared invite URL from Android/Web/Desktop:
     * - `ABC123`
     * - `bitchord://party/ABC123?server=https%3A%2F%2F...`
     * - `https://bitchord.kushagrasingh.in/invite/ABC123?server=...`
     * - `https://bitchord-listen-together.onrender.com/invite/ABC123`
     * - `https://custom.example.com/invite/ABC123`
     */
    fun parsePartyInput(raw: String?): ParsedPartyInput? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (!trimmed.contains("://")) {
            val cleaned = cleanCode(trimmed)
            return if (cleaned.length == CODE_LENGTH && trimmed.all { it.isLetterOrDigit() || it.isWhitespace() || it == '-' }) {
                ParsedPartyInput(cleaned, serverUrl = null)
            } else {
                null
            }
        }
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val explicitParam = extractQueryParam(uri.rawQuery, "server")
            ?.let { normalizeServerUrl(it).getOrNull() }
            ?.takeIf { it.isNotBlank() }

        if (scheme == "bitchord" && host == "party") {
            val pathCode = uri.path.orEmpty().trim('/').substringBefore('/').takeIf { it.isNotBlank() }
            val candidate = pathCode ?: extractQueryParam(uri.rawQuery, "code") ?: return null
            val cleaned = cleanCode(candidate)
            if (cleaned.length != CODE_LENGTH) return null
            return ParsedPartyInput(cleaned, serverUrl = explicitParam)
        }

        if (scheme == "http" || scheme == "https") {
            val segments = uri.path.orEmpty().trim('/').split('/').filter { it.isNotBlank() }
            val inviteIdx = segments.indexOfFirst { it.equals("invite", ignoreCase = true) }
            val rawCode = segments.getOrNull(inviteIdx + 1) ?: return null
            val cleaned = cleanCode(rawCode)
            if (cleaned.length != CODE_LENGTH) return null

            val inferredOrigin = if (explicitParam != null) {
                explicitParam
            } else if (host == "bitchord.kushagrasingh.in") {
                null
            } else {
                val portSuffix = if (uri.port != -1) ":${uri.port}" else ""
                normalizeServerUrl("$scheme://$host$portSuffix").getOrNull()?.takeIf { it.isNotBlank() }
            }
            return ParsedPartyInput(cleaned, serverUrl = inferredOrigin)
        }
        return null
    }

    internal fun candidateServersForCode(
        code: String,
        explicitServer: String?,
        customServer: String,
        idleServer: String,
    ): List<String> {
        val explicitNorm = explicitServer?.let { normalizeServerUrl(it).getOrNull() }?.takeIf { it.isNotBlank() }
        if (explicitNorm != null) return listOf(explicitNorm)

        val customNorm = normalizeServerUrl(customServer).getOrNull().orEmpty()
        if (customNorm.isNotBlank()) return listOf(customNorm)

        val cleaned = cleanCode(code)
        return listOfNotNull(
            discoveredServerByCode[cleaned],
            idleServer.takeIf { it.isNotBlank() },
            DEFAULT_SERVER,
            OFFICIAL_SERVER,
            BACKUP_SERVER,
        ).map { it.trimEnd('/') }.filter { it.isNotBlank() }.distinct()
    }

    /** Public invite for the server that actually owns the current membership. */
    fun inviteUrl(code: String): String {
        val server = activePartyServerBase?.trimEnd('/')
        return when {
            server.isNullOrBlank() || server == OFFICIAL_SERVER -> JamInvite.url(code)
            server == BACKUP_SERVER -> {
                val encoded = runCatching { URLEncoder.encode(server, "UTF-8") }.getOrDefault(server)
                "${JamInvite.url(code)}?server=$encoded"
            }
            else -> "$server/invite/${code.uppercase()}"
        }
    }

    val isAccountSignedIn: Boolean get() = DesktopYouTubeAuth.isSignedIn

    /** Desktop supports signed-in YT Music identities and nickname guest fallback on Android's backend. */
    fun canJoin(): Boolean = identity() != null

    suspend fun createParty(
        nickname: String = nickname(),
        maxMembers: Int = 5,
        autoplayEnabled: Boolean = false,
    ): Result<String> = enter(
        nickname = nickname,
        fallbackOnNotFound = false,
        explicitServer = null,
    ) { who, server ->
        post(
            "$server/api/parties",
            JoinRequest(
                who.userId,
                who.deviceId,
                who.name,
                who.avatar,
                maxMembers.coerceIn(2, 10),
                autoplayEnabled,
            ),
        )
    }

    suspend fun joinParty(
        code: String,
        nickname: String = nickname(),
        explicitServer: String? = null,
    ): Result<String> {
        val trimmed = cleanCode(code)
        if (trimmed.length != CODE_LENGTH) {
            return Result.failure(PartyException("bad_code", "A party code is six letters or digits."))
        }
        return runCatching { refuseIfRecentlyKicked(trimmed) }.fold(
            onSuccess = {
                val isOfficialSearch = explicitServer.isNullOrBlank() && _customServer.value.isBlank()
                enter(
                    nickname = nickname,
                    codeForDiscovery = trimmed,
                    fallbackOnNotFound = isOfficialSearch,
                    explicitServer = explicitServer,
                ) { who, server ->
                    post(
                        "$server/api/parties/$trimmed/join",
                        JoinRequest(who.userId, who.deviceId, who.name, who.avatar),
                    )
                }
            },
            onFailure = { Result.failure(it) },
        )
    }

    private suspend fun enter(
        nickname: String,
        codeForDiscovery: String? = null,
        fallbackOnNotFound: Boolean,
        explicitServer: String?,
        request: suspend (Identity, String) -> PartyMembership,
    ): Result<String> = withContext(Dispatchers.IO) {
        val who = identity(nickname)
            ?: return@withContext Result.failure(
                PartyException("not_signed_in", "Sign in or set a nickname to listen together."),
            )
        if (!hasServer && explicitServer.isNullOrBlank()) {
            return@withContext Result.failure(
                PartyException("no_server", "Set the party server address first."),
            )
        }

        val candidates = if (codeForDiscovery != null) {
            candidateServersForCode(
                code = codeForDiscovery,
                explicitServer = explicitServer,
                customServer = _customServer.value,
                idleServer = resolveIdleServer(),
            )
        } else {
            val primary = resolveIdleServer()
            listOf(primary, DEFAULT_SERVER, OFFICIAL_SERVER, BACKUP_SERVER)
                .map { it.trimEnd('/') }
                .filter { it.isNotBlank() }
                .distinct()
        }

        var winningServer: String? = null
        var winningMembership: PartyMembership? = null
        var lastFailure: Throwable? = null

        for ((idx, server) in candidates.withIndex()) {
            val result = runCatching { request(who, server) }
            if (result.isSuccess) {
                winningServer = server
                winningMembership = result.getOrThrow()
                break
            }
            val err = result.exceptionOrNull()!!
            lastFailure = err
            val isNotFound = (err as? PartyException)?.statusCode == 404
            val canTryNext = idx < candidates.lastIndex &&
                (isEligibleForFallback(err) || (fallbackOnNotFound && isNotFound))
            if (!canTryNext) break
        }

        if (winningMembership != null && winningServer != null) {
            val matchedServer = winningServer
            val membership = winningMembership
            activePartyServerBase = matchedServer
            effectiveIdleServerBase = matchedServer
            if (codeForDiscovery != null) {
                discoveredServerByCode[cleanCode(membership.code)] = matchedServer
            }
            val latencyCheck = _serverStatus.value
            if (matchedServer != _customServer.value && _customServer.value.isNotBlank()) {
                _serverStatus.value = ServerStatus(Health.ONLINE, latencyCheck.latencyMs, isFallback = true)
            } else if (latencyCheck.health != Health.ONLINE) {
                _serverStatus.value = ServerStatus(Health.ONLINE, latencyCheck.latencyMs)
            }
            token = membership.token
            persistence.saveString(KEY_CODE, membership.code)
            persistence.saveString(KEY_TOKEN, membership.token)
            persistence.saveString(KEY_ACTIVE_SERVER, matchedServer)
            clock.reset()
            _state.value = State(
                code = membership.code,
                you = membership.you,
                members = membership.party.members,
                maxMembers = membership.party.maxMembers,
                hostOnlyControl = membership.party.hostOnlyControl,
                playback = membership.party.playback,
                queue = membership.party.queue,
                connection = Connection.CONNECTING,
            )
            connect()
            Result.success(membership.code)
        } else {
            val failure = lastFailure ?: PartyException("unreachable", UNREACHABLE)
            DesktopTrackLog.log("listen together: could not enter a party: ${redact(failure.message)}")
            _state.update { it.copy(error = failure.displayMessage()) }
            Result.failure(failure)
        }
    }

    suspend fun leaveParty() = withContext(Dispatchers.IO) {
        val code = _state.value.code
        val held = token
        val server = activePartyServerBase ?: effectiveIdleServerBase
        socketJob?.cancel()
        socketJob = null
        session = null
        clock.reset()
        token = null
        activePartyServerBase = null
        persistence.saveString(KEY_CODE, "")
        persistence.saveString(KEY_TOKEN, "")
        persistence.saveString(KEY_ACTIVE_SERVER, "")
        _activity.value = emptyList()
        _state.value = State()
        if (code != null && held != null && server.isNotBlank()) {
            runCatching {
                http.post("$server/api/parties/$code/leave") {
                    header("Authorization", "Bearer $held")
                }
            }
        }
        Unit
    }

    fun play(positionMs: Long? = null) = control("play") { positionMs?.let { put("positionMs", it) } }

    fun pause(positionMs: Long? = null) = control("pause") { positionMs?.let { put("positionMs", it) } }

    fun seek(positionMs: Long) = control("seek") { put("positionMs", positionMs) }

    fun next() = control("next") {}

    fun previous() = control("previous") {}

    fun setTrack(track: PartyTrack, positionMs: Long = 0, isPlaying: Boolean = true) =
        control("setTrack") {
            put("track", json.encodeToJsonElement(PartyTrack.serializer(), track))
            put("positionMs", positionMs)
            put("isPlaying", isPlaying)
        }

    fun setQueue(queue: List<PartyTrack>, index: Int) = control("setQueue") {
        put("queue", json.encodeToJsonElement(ListSerializer(PartyTrack.serializer()), queue))
        put("queueIndex", index)
    }

    fun queueAdd(tracks: List<PartyTrack>, playNext: Boolean = false) = control("queueAdd") {
        put("tracks", json.encodeToJsonElement(ListSerializer(PartyTrack.serializer()), tracks))
        put("playNext", playNext)
    }

    fun queueRemove(videoId: String) = control("queueRemove") { put("videoId", videoId) }

    fun queueClear() = control("queueClear") {}

    fun queueMove(fromIndex: Int, toIndex: Int, videoId: String? = null) = control("queueMove") {
        put("fromIndex", fromIndex)
        put("toIndex", toIndex)
        videoId?.let { put("videoId", it) }
    }

    fun setMaxMembers(value: Int) = control("setMaxMembers") { put("maxMembers", value.coerceIn(2, 10)) }

    fun kick(memberId: String) = control("kick") { put("memberId", memberId) }

    fun setAutoplay(enabled: Boolean) = control("setAutoplay") { put("enabled", enabled) }

    fun setHostOnlyControl(enabled: Boolean) =
        control("setHostOnlyControl") { put("enabled", enabled) }

    private fun control(action: String, body: JsonObjectBuilder.() -> Unit) {
        if (_state.value.controlsLocked) return
        send(buildJsonObject {
            put("type", "control")
            put("action", action)
            body()
        })
    }

    private fun send(frame: JsonObject) {
        val live = session ?: return
        scope.launch { runCatching { live.send(Frame.Text(frame.toString())) } }
    }

    /**
     * Where the party's playhead is right now, on this device's reading of the server's clock.
     *
     * Null when nothing is playing. Before the clock has synced this falls back to the anchor the
     * server stated, which is a guess but a stable one.
     */
    fun partyPositionMs(): Long? {
        val playback = _state.value.playback
        playback.track ?: return null
        if (!playback.isPlaying) return playback.positionMs
        val serverNow = clock.serverNowMs() ?: return playback.effectivePositionMs
        val elapsed = (serverNow - playback.anchorMs).coerceAtLeast(0)
        val position = playback.positionMs + elapsed
        val duration = playback.track?.durationMs
        return if (duration != null) minOf(position, duration) else position
    }

    /** How long until the party's start instant, for a device that arrived early. */
    fun msUntilStart(): Long {
        val playback = _state.value.playback
        if (!playback.isPlaying) return 0
        val serverNow = clock.serverNowMs() ?: return 0
        return (playback.anchorMs - serverNow).coerceAtLeast(0)
    }

    // ------------------------------------------------------------- socket --

    private fun connect() {
        socketJob?.cancel()
        socketJob = scope.launch { runSocketLoop() }
    }

    private suspend fun runSocketLoop() {
        var backoffMs = 1_000L
        var consecutiveFailures = 0
        while (currentCoroutineContext().isActive) {
            val code = _state.value.code ?: return
            val held = token ?: return
            try {
                _state.update { it.copy(connection = Connection.CONNECTING) }
                val server = activePartyServerBase ?: return
                http.webSocket(
                    urlString = "${wsBase(server)}/ws/parties/$code",
                    request = { header("Authorization", "Bearer $held") },
                ) {
                    session = this
                    backoffMs = 1_000L
                    consecutiveFailures = 0
                    _state.update { it.copy(connection = Connection.LIVE, error = null) }
                    launch { pingLoop() }
                    launch { reportLoop() }
                    for (frame in incoming) {
                        if (frame is Frame.Text) onFrame(frame.readText())
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                DesktopTrackLog.log("listen together: socket dropped: ${redact(failure.message)}")
                consecutiveFailures++
                if (consecutiveFailures >= 3) {
                    val server = activePartyServerBase
                    if (!server.isNullOrBlank() && partyGoneOnServer(server, code, held)) {
                        leaveParty()
                        return
                    }
                }
            } finally {
                session = null
            }
            if (!currentCoroutineContext().isActive) return
            _state.update { it.copy(connection = Connection.CONNECTING) }
            clock.reset()
            _state.update { it.copy(clockSynced = false) }
            // Jittered, or every client that lost the socket in the same outage
            // retries at the same instants and a recovering server is hit by the
            // whole crowd at once.
            delay(backoffMs / 2 + Random.nextLong(backoffMs + 1))
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    private suspend fun partyGoneOnServer(server: String, code: String, held: String): Boolean =
        runCatching {
            val response = http.get("$server/api/parties/$code") {
                header("Authorization", "Bearer $held")
                timeout { requestTimeoutMillis = CUSTOM_SERVER_TIMEOUT_MS }
            }
            response.status.value == 404 || response.status.value == 401
        }.getOrDefault(false)

    /** A burst on connect converges the offset faster than a long series would. */
    private suspend fun DefaultClientWebSocketSession.pingLoop() {
        repeat(4) {
            ping()
            delay(300)
        }
        while (true) {
            delay(PING_INTERVAL_MS)
            ping()
        }
    }

    private suspend fun DefaultClientWebSocketSession.ping() {
        val sentAt = clock.nowMs()
        val frame = buildJsonObject {
            put("type", "ping")
            put("clientMs", sentAt)
        }
        runCatching { send(Frame.Text(frame.toString())) }
    }

    private suspend fun DefaultClientWebSocketSession.reportLoop() {
        while (true) {
            delay(REPORT_INTERVAL_MS)
            val position = partyPositionMs() ?: continue
            val frame = buildJsonObject {
                put("type", "report")
                put("positionMs", position)
                put("isPlaying", _state.value.playback.isPlaying)
            }
            runCatching { send(Frame.Text(frame.toString())) }
        }
    }

    private fun onFrame(text: String) {
        val received = clock.nowMs()
        val frame = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (frame["type"]?.jsonPrimitive?.content) {
            "welcome" -> {
                val party = frame["party"]?.let {
                    runCatching { json.decodeFromJsonElement(PartySnapshot.serializer(), it) }.getOrNull()
                } ?: return
                val you = frame["you"]?.let {
                    runCatching { json.decodeFromJsonElement(PartyMember.serializer(), it) }.getOrNull()
                }
                _state.update {
                    it.copy(
                        code = party.code,
                        you = you ?: it.you,
                        members = party.members,
                        maxMembers = party.maxMembers,
                        hostOnlyControl = party.hostOnlyControl,
                        playback = party.playback,
                        queue = party.queue,
                        connection = Connection.LIVE,
                        error = null,
                    )
                }
            }

            "pong" -> {
                val sentAt = frame["clientMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
                val serverMs = frame["serverMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
                clock.record(sentAt, serverMs, received)
                _state.update { it.copy(clockSynced = clock.synced, roundTripMs = clock.roundTripMs) }
            }

            "state" -> {
                val playback = frame["playback"]?.let {
                    runCatching { json.decodeFromJsonElement(PartyPlayback.serializer(), it) }.getOrNull()
                } ?: return
                // An out-of-order frame is older news than what is already held.
                if (playback.seq < _state.value.playback.seq) return
                _state.update { it.copy(playback = playback) }
                if (playback.queueSeq != _state.value.queue.seq) {
                    send(buildJsonObject { put("type", "syncQueue") })
                }
            }

            "queue" -> {
                val queue = frame["queue"]?.let {
                    runCatching { json.decodeFromJsonElement(PartyQueue.serializer(), it) }.getOrNull()
                } ?: return
                if (queue.seq < _state.value.queue.seq) return
                _state.update { it.copy(queue = queue) }
            }

            "members" -> {
                val members = frame["members"]?.let {
                    runCatching {
                        json.decodeFromJsonElement(ListSerializer(PartyMember.serializer()), it)
                    }.getOrNull()
                } ?: return
                val maxMembers = frame["maxMembers"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: _state.value.maxMembers
                val hostOnly = frame["hostOnlyControl"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                    ?: _state.value.hostOnlyControl
                _state.update { current ->
                    current.copy(
                        members = members,
                        maxMembers = maxMembers,
                        hostOnlyControl = hostOnly,
                        you = current.you
                            ?.let { mine -> members.firstOrNull { it.memberId == mine.memberId } }
                            ?: current.you,
                    )
                }
            }

            "activity" -> {
                val action = frame["action"]?.jsonPrimitive?.content ?: return
                val by = frame["by"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) ?: return
                val atMs = frame["atMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: received
                val detail = frame["detail"]?.jsonPrimitive?.content.orEmpty()
                _activity.update { (listOf(PartyActivity(action, by, atMs, detail)) + it).take(100) }
            }

            "error" -> {
                val reason = frame["error"]?.jsonPrimitive?.content
                val message = frame["message"]?.jsonPrimitive?.content
                _state.update { it.copy(error = message) }
                if (reason == "bad_token" || reason == "no_such_party") scope.launch { leaveParty() }
            }

            "bye" -> {
                if (frame["reason"]?.jsonPrimitive?.content == "kicked") {
                    _state.value.code?.let(::recordKick)
                }
                scope.launch { leaveParty() }
            }
        }
    }

    // ----------------------------------------------------------- identity --

    private data class Identity(
        val userId: String,
        val deviceId: String,
        val name: String,
        val avatar: String?,
    )

    /**
     * Who this device jams as, or null when it cannot.
     *
     * A party is joined as an account: the id is derived from the signed-in account and profile so
     * the server never sees either, and a device with no session has nothing to identify itself by.
     */
    private fun identity(nickname: String = nickname()): Identity? {
        val devId = deviceId()
        val explicitNickname = nickname.trim().takeIf { it.isNotBlank() }?.take(80)
        val ytSession = DesktopYouTubeAuth.session
        if (DesktopYouTubeAuth.isSignedIn) {
            val account = DesktopAccounts.active()
            val profile = DesktopAccounts.activeProfile()
            val name = explicitNickname
                ?: profile?.name?.takeIf { it.isNotBlank() }
                ?: account?.name?.takeIf { it.isNotBlank() }
                ?: account?.email?.substringBefore('@')?.takeIf { it.isNotBlank() }
                ?: System.getProperty("user.name")?.trim()?.take(40).orEmpty().ifBlank { "LuminaChord PC" }
            val userSeed = if (account != null) {
                "${account.accountId}:${profile?.profileId.orEmpty()}"
            } else {
                "yt:${ytSession?.authUser.orEmpty()}:${ytSession?.cookie.orEmpty().take(64)}"
            }
            return Identity(
                userId = sha256(userSeed).take(32),
                deviceId = devId,
                name = name,
                avatar = profile?.avatar?.takeIf { it.startsWith("http") },
            )
        }
        val fallbackName = explicitNickname
            ?: System.getProperty("user.name")?.trim()?.take(40).orEmpty().ifBlank { "LuminaChord PC" }
        return Identity(
            userId = sha256("guest:$devId").take(32),
            deviceId = devId,
            name = fallbackName,
            avatar = null,
        )
    }

    /** This installation, remembered so a rejoin is recognised as the same device. */
    private fun deviceId(): String {
        persistence.string(KEY_DEVICE).takeIf { it.isNotBlank() }?.let { return it }
        return UUID.randomUUID().toString().also { persistence.saveString(KEY_DEVICE, it) }
    }

    private suspend fun post(url: String, body: JoinRequest): PartyMembership {
        val response = http.post(url) {
            contentType(ContentType.Application.Json)
            timeout {
                requestTimeoutMillis = DEFAULT_SERVER_TIMEOUT_MS
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
            }
            setBody(body)
        }
        if (!response.status.isSuccess()) throw response.toPartyException()
        return response.body()
    }

    suspend fun previewParty(code: String, explicitServer: String? = null): Result<PartyPreview> =
        withContext(Dispatchers.IO) {
            val cleaned = cleanCode(code)
            if (cleaned.length != CODE_LENGTH) {
                return@withContext Result.failure(
                    PartyException("bad_code", "A party code is six letters or digits."),
                )
            }
            runCatching {
                refuseIfRecentlyKicked(cleaned)
                val isOfficialSearch = explicitServer.isNullOrBlank() && _customServer.value.isBlank()
                val candidates = candidateServersForCode(
                    code = cleaned,
                    explicitServer = explicitServer,
                    customServer = _customServer.value,
                    idleServer = resolveIdleServer(),
                )
                if (candidates.isEmpty()) throw PartyException("no_server", "Set the party server address first.")

                var lastFailure: Throwable? = null
                for ((idx, base) in candidates.withIndex()) {
                    val outcome = runCatching {
                        val response = http.get("$base/api/parties/$cleaned/preview") {
                            timeout {
                                requestTimeoutMillis = DEFAULT_SERVER_TIMEOUT_MS
                                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                            }
                        }
                        if (!response.status.isSuccess()) {
                            val problem = response.toPartyException()
                            // Older self-hosted servers did not expose previews. The confirmation step still
                            // works there; it simply cannot show members until the join completes.
                            if (problem.statusCode == 404 && problem.code == "http_404") {
                                return@runCatching PartyPreview(code = cleaned)
                            }
                            throw problem
                        }
                        response.body<PartyPreview>()
                    }
                    if (outcome.isSuccess) {
                        discoveredServerByCode[cleaned] = base
                        return@runCatching outcome.getOrThrow()
                    }
                    val failure = outcome.exceptionOrNull()!!
                    lastFailure = failure
                    val isNotFound = (failure as? PartyException)?.statusCode == 404
                    val canTryNext = idx < candidates.lastIndex &&
                        (isEligibleForFallback(failure) || (isOfficialSearch && isNotFound))
                    if (!canTryNext) throw failure
                }
                throw lastFailure ?: PartyException("no_such_party", "No party with that code.")
            }.onFailure { failure ->
                DesktopTrackLog.log("listen together: preview failed: ${redact(failure.message)}")
            }
        }

    private fun extractQueryParam(query: String?, paramName: String): String? {
        if (query.isNullOrBlank()) return null
        return query.split('&').asSequence()
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.isNotEmpty() && it[0].equals(paramName, ignoreCase = true) }
            ?.getOrNull(1)
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
    }

    private fun cleanCode(code: String): String =
        code.filter(Char::isLetterOrDigit).uppercase().take(CODE_LENGTH)

    private suspend fun HttpResponse.toPartyException(): PartyException {
        val body = runCatching { bodyAsText() }.getOrDefault("")
        val parsed = runCatching { json.decodeFromString(ApiError.serializer(), body) }.getOrNull()
        return PartyException(
            code = parsed?.code.orEmpty().ifBlank { "http_${status.value}" },
            message = parsed?.message?.takeIf { it.isNotBlank() }
                ?: if (status.value == 422) "This account can't be used to jam."
                else "The party server said ${status.value}.",
            statusCode = status.value,
        )
    }

    private suspend fun resolveIdleServer(forceProbe: Boolean = false): String {
        if (!forceProbe && _serverStatus.value.health == Health.ONLINE) return effectiveIdleServerBase
        val custom = normalizeServerUrl(_customServer.value).getOrNull().orEmpty()
        if (custom.isNotBlank() && custom != DEFAULT_SERVER) {
            val customProbe = probeHealth(custom, CUSTOM_SERVER_TIMEOUT_MS)
            if (customProbe.first) {
                effectiveIdleServerBase = custom
                _serverStatus.value = ServerStatus(Health.ONLINE, customProbe.second)
                return custom
            }
            val fallback = probeHealth(DEFAULT_SERVER, DEFAULT_SERVER_TIMEOUT_MS)
            if (fallback.first) {
                effectiveIdleServerBase = DEFAULT_SERVER
                _serverStatus.value = ServerStatus(Health.ONLINE, fallback.second, isFallback = true)
                return DEFAULT_SERVER
            }
            val backup = probeHealth(BACKUP_SERVER, DEFAULT_SERVER_TIMEOUT_MS)
            if (backup.first) {
                effectiveIdleServerBase = BACKUP_SERVER
                _serverStatus.value = ServerStatus(Health.ONLINE, backup.second, isFallback = true)
                return BACKUP_SERVER
            }
            effectiveIdleServerBase = custom
            _serverStatus.value = ServerStatus(Health.OFFLINE)
            return custom
        }

        val probe = probeHealth(DEFAULT_SERVER, DEFAULT_SERVER_TIMEOUT_MS)
        if (probe.first) {
            effectiveIdleServerBase = DEFAULT_SERVER
            _serverStatus.value = ServerStatus(Health.ONLINE, probe.second)
            return DEFAULT_SERVER
        }
        val backup = probeHealth(BACKUP_SERVER, DEFAULT_SERVER_TIMEOUT_MS)
        if (backup.first) {
            effectiveIdleServerBase = BACKUP_SERVER
            _serverStatus.value = ServerStatus(Health.ONLINE, backup.second, isFallback = true)
            return BACKUP_SERVER
        }

        effectiveIdleServerBase = DEFAULT_SERVER
        _serverStatus.value = ServerStatus(Health.OFFLINE)
        return DEFAULT_SERVER
    }

    private suspend fun probeHealth(server: String, timeoutMs: Long): Pair<Boolean, Long> {
        val started = clock.nowMs()
        val online = runCatching {
            val response = http.get("$server/healthz") {
                timeout { requestTimeoutMillis = timeoutMs }
            }
            response.status.isSuccess() && response.bodyAsText().contains(HEALTH_OK)
        }.getOrElse { failure ->
            DesktopTrackLog.log("listen together: health check failed: ${redact(failure.message)}")
            false
        }
        return online to if (online) (clock.nowMs() - started).coerceAtLeast(0L) else 0L
    }

    private fun isEligibleForFallback(error: Throwable): Boolean = when (error) {
        is UnknownHostException,
        is ConnectException,
        is NoRouteToHostException,
        is PortUnreachableException,
        is SocketTimeoutException,
        is HttpRequestTimeoutException,
        is io.ktor.client.network.sockets.SocketTimeoutException,
        is io.ktor.client.network.sockets.ConnectTimeoutException -> true
        is PartyException -> (error.statusCode ?: 0) in 500..599
        else -> error.cause?.takeIf { it !== error }?.let(::isEligibleForFallback) == true
    }

    private fun defaultHttpBase(): String = normalizeServerUrl(DEFAULT_SERVER).getOrNull().orEmpty()

    private fun wsBase(server: String): String = server.trim().trimEnd('/')
        .replaceFirst("https://", "wss://", ignoreCase = true)
        .replaceFirst("http://", "ws://", ignoreCase = true)

    private fun recentKicks(): Map<String, Long> {
        val encoded = persistence.string(KEY_KICKED)
        if (encoded.isBlank()) return emptyMap()
        val stored = runCatching {
            json.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), encoded)
        }.getOrDefault(emptyMap())
        val live = stored.filterValues { it > System.currentTimeMillis() }
        if (live.size != stored.size) writeKicks(live)
        return live
    }

    private fun writeKicks(entries: Map<String, Long>) {
        persistence.saveString(
            KEY_KICKED,
            if (entries.isEmpty()) "" else json.encodeToString(
                MapSerializer(String.serializer(), Long.serializer()),
                entries,
            ),
        )
    }

    private fun recordKick(code: String) {
        val normalized = cleanCode(code)
        if (normalized.isBlank()) return
        writeKicks(recentKicks() + (normalized to System.currentTimeMillis() + KICK_BLOCK_MS))
    }

    private fun refuseIfRecentlyKicked(code: String) {
        if (cleanCode(code) in recentKicks()) {
            throw PartyException(
                "recently_kicked",
                "Couldn't let you in — you've recently been removed from this party.",
            )
        }
    }

    /**
     * Keeps the server's address out of the log.
     *
     * The same rule the addon client follows: a failing request's URL is not diagnostic enough to
     * be worth printing when it identifies where someone's party lives.
     */
    private fun redact(text: String?): String {
        var out = text.orEmpty()
        if (out.isEmpty()) return out
        listOf(DEFAULT_SERVER, OFFICIAL_SERVER, BACKUP_SERVER, _customServer.value)
            .filter { it.isNotBlank() }
            .forEach { out = out.replace(it, SERVER_PLACEHOLDER) }
        return out.replace(ABSOLUTE_URL, SERVER_PLACEHOLDER)
    }

    private fun Throwable.displayMessage(): String = when (this) {
        is PartyException -> message.orEmpty().ifBlank { UNREACHABLE }
        else -> UNREACHABLE
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private const val KEY_SERVER = "listen_together_server"
    private const val KEY_CODE = "listen_together_code"
    private const val KEY_TOKEN = "listen_together_token"
    private const val KEY_DEVICE = "listen_together_device"
    private const val KEY_NICKNAME = "listen_together_nickname"
    private const val KEY_ACTIVE_SERVER = "listen_together_active_server"
    private const val KEY_KICKED = "listen_together_kicked_until"
    private const val SERVER_PLACEHOLDER = "<party server>"
    private const val UNREACHABLE = "Couldn't reach the party server."
    private const val PING_INTERVAL_MS = 15_000L
    private const val REPORT_INTERVAL_MS = 10_000L
    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val CUSTOM_SERVER_TIMEOUT_MS = 6_000L
    private const val DEFAULT_SERVER_TIMEOUT_MS = 35_000L
    private const val KICK_BLOCK_MS = 24L * 60 * 60 * 1000
    private const val HEALTH_OK = "\"ok\":true"
    const val CODE_LENGTH = 6

    private val ABSOLUTE_URL = Regex("""(?:https?|wss?)://[^\s,;)\]}'"]+""", RegexOption.IGNORE_CASE)
}
