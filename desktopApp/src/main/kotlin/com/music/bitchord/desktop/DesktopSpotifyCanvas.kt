package com.music.bitchord.desktop

import com.music.bitchord.data.canvas.SpotifyCanvasQuery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import netscape.javascript.JSObject

/**
 * Spotify's Canvas: the short looping clip shown behind a track in their own app.
 *
 * Needs the listener's `sp_dc` cookie, which is the one thing here that identifies an account —
 * see [DesktopSpotifyToken] for how a bearer is minted from it, and why that is the fragile part
 * on a desktop with no embedded browser.
 */
internal object DesktopSpotifyCanvas {

    private const val SEARCH_URL = "https://api.spotify.com/v1/search"
    private const val ALBUM_TRACKS_URL = "https://api.spotify.com/v1/albums"
    private const val CANVAS_URL = "https://spclient.wg.spotify.com/canvaz-cache/v0/canvases"

    /** spclient gates this path to Spotify's own apps by user agent; the web player's is turned
     * away, so this wears a mobile client's instead. */
    private const val SPOTIFY_APP_UA = "Spotify/9.0.34.593 iOS/18.4 (iPhone15,3)"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val CANVAS_URL_REGEX = Regex("""https://[^"'\s\x00-\x1F]+\.cnvs\.mp4""")

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private data class TrackHit(val uri: String, val title: String, val artist: String, val album: String?)

    fun search(title: String, artist: String, album: String?): DesktopCanvasArtwork? {
        val token = DesktopSpotifyToken.accessToken() ?: return null
        val hit = searchTrack(title, artist, album, token) ?: return null
        val canvasUrl = fetchCanvasUrl(hit.uri, token) ?: return null
        return DesktopCanvasArtwork(
            url = canvasUrl,
            title = hit.title,
            artist = hit.artist,
            album = hit.album,
            source = DesktopCanvasSource.SPOTIFY,
        )
    }

    /** A release's canvas, read off its first track — Spotify hangs Canvas off tracks, not
     * releases, so there is no album-level lookup to make directly. */
    fun searchAlbum(album: String, artist: String): DesktopCanvasArtwork? {
        val token = DesktopSpotifyToken.accessToken() ?: return null
        val items = get(
            url("$SEARCH_URL", listOf("q" to "$album $artist", "type" to "album", "limit" to "10")),
            token,
        )?.get("albums")?.jsonObject?.get("items")?.jsonArray ?: return null

        for (item in items) {
            val record = item as? JsonObject ?: continue
            val recordTitle = record.text("name") ?: continue
            val artists = record["artists"]?.jsonArray
                ?.mapNotNull { it.jsonObject.text("name") }
                .orEmpty()
            if (!canvasMatches(recordTitle, artists, album, artist)) continue
            val albumId = record.text("id") ?: continue
            val trackUri = firstTrackUri(albumId, token) ?: continue
            val canvasUrl = fetchCanvasUrl(trackUri, token) ?: continue
            return DesktopCanvasArtwork(
                url = canvasUrl,
                title = recordTitle,
                artist = artists.joinToString(", ").ifBlank { null },
                album = recordTitle,
                source = DesktopCanvasSource.SPOTIFY,
            )
        }
        return null
    }

    private fun searchTrack(title: String, artist: String, album: String?, token: String): TrackHit? {
        val query = listOfNotNull(title, artist, album).joinToString(" ")
        val items = get(url(SEARCH_URL, listOf("q" to query, "type" to "track", "limit" to "10")), token)
            ?.get("tracks")?.jsonObject?.get("items")?.jsonArray
            ?: return null
        for (item in items) {
            val track = item as? JsonObject ?: continue
            val trackTitle = track.text("name") ?: continue
            val artists = track["artists"]?.jsonArray
                ?.mapNotNull { it.jsonObject.text("name") }
                .orEmpty()
            if (!canvasMatches(trackTitle, artists, title, artist)) continue
            val uri = track.text("uri") ?: continue
            return TrackHit(
                uri = uri,
                title = trackTitle,
                artist = artists.joinToString(", ").ifBlank { artist },
                album = track["album"]?.jsonObject?.text("name"),
            )
        }
        return null
    }

    private fun firstTrackUri(albumId: String, token: String): String? =
        get(url("$ALBUM_TRACKS_URL/$albumId/tracks", listOf("limit" to "1")), token)
            ?.get("items")?.jsonArray?.firstOrNull()?.jsonObject?.text("uri")

    // ── Pathfinder: the `canvas` GraphQL query ───────────────────────────

    /** The live `canvas` query hash, read off the web player's own scripts. */
    private val queryHashes = SpotifyCanvasQuery.QueryHashes(fetch = { url -> canvasGet(url) })

    /** The track's canvas, asked for the way the current web player does; `canvaz-cache` is the
     * fallback when the query fails or answers with nothing playable. */
    private fun fetchCanvasUrl(trackUri: String, token: String): String? =
        when (val answer = fetchCanvasViaPathfinder(trackUri, token)) {
            is SpotifyCanvasQuery.Answer.Found -> answer.url
            is SpotifyCanvasQuery.Answer.NoCanvas -> {
                DesktopTrackLog.log("canvas: Pathfinder has no playable canvas (${answer.detail}); trying canvaz-cache")
                fetchCanvasViaCanvaz(trackUri, token)
            }
            is SpotifyCanvasQuery.Answer.Failed -> {
                DesktopTrackLog.log("canvas: Spotify's canvas query failed (${answer.reason}); trying canvaz-cache")
                fetchCanvasViaCanvaz(trackUri, token)
            }
        }

    private fun fetchCanvasViaPathfinder(trackUri: String, token: String, isRetry: Boolean = false): SpotifyCanvasQuery.Answer {
        val hash = queryHashes.canvasHash(forceRefresh = isRetry)
        val response = runCatching {
            val builder = HttpRequest.newBuilder(URI.create(SpotifyCanvasQuery.ENDPOINT))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Accept-Language", "en")
                .header("App-platform", "WebPlayer")
                .header("User-Agent", CANVAS_UA)
            authHeaders(token).forEach { (name, value) -> builder.header(name, value) }
            http.send(
                builder.POST(HttpRequest.BodyPublishers.ofString(SpotifyCanvasQuery.requestBody(trackUri, hash))).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }.getOrElse { return SpotifyCanvasQuery.Answer.Failed("request threw: ${it.message}") }
        if (response.statusCode() !in 200..299) return SpotifyCanvasQuery.Answer.Failed("http ${response.statusCode()}")

        val answer = SpotifyCanvasQuery.parse(response.body())
        // A rebuilt web player retires the old hash: look it up again once.
        if (answer is SpotifyCanvasQuery.Answer.Failed && answer.staleHash && !isRetry) {
            return fetchCanvasViaPathfinder(trackUri, token, isRetry = true)
        }
        return answer
    }

    // ── canvaz-cache: protobuf in, protobuf out (fallback) ───────────────

    internal data class CanvasHit(val url: String, val trackUri: String?)

    private fun fetchCanvasViaCanvaz(trackUri: String, token: String): String? {
        val bytes = runCatching {
            val builder = HttpRequest.newBuilder(URI.create(CANVAS_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/protobuf")
                .header("Content-Type", "application/protobuf")
                .header("Accept-Language", "en")
                .header("User-Agent", SPOTIFY_APP_UA)
            authHeaders(token).forEach { (name, value) -> builder.header(name, value) }
            val response = http.send(
                builder.POST(HttpRequest.BodyPublishers.ofByteArray(encodeCanvasRequest(trackUri))).build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
            if (response.statusCode() in 200..299) response.body() else null
        }.getOrNull() ?: return null

        // The structured parse first — it can tell this track's own clip apart from another one
        // bundled into the same response. The regex is the fallback, needing only a *.cnvs.mp4 URL
        // to be sitting in the bytes as plain text.
        val hits = decodeCanvasResponse(bytes)
        hits.firstOrNull { it.trackUri == trackUri }?.url?.let { return it }
        hits.firstOrNull()?.url?.let { return it }
        return CANVAS_URL_REGEX.find(String(bytes, Charsets.ISO_8859_1))?.value
    }

    /** `CanvasRequest { repeated Track tracks = 1; Track { string track_uri = 1; } }` */
    internal fun encodeCanvasRequest(trackUri: String): ByteArray {
        val track = ByteArrayOutputStream().apply { writeLengthDelimited(1, trackUri.toByteArray()) }
        return ByteArrayOutputStream().apply { writeLengthDelimited(1, track.toByteArray()) }.toByteArray()
    }

    /**
     * `CanvasResponse { repeated Canvas canvases = 1; }`, `Canvas { id = 1; canvas_url = 2;
     * track_uri = 5; }` — only the fields this needs are read.
     */
    internal fun decodeCanvasResponse(bytes: ByteArray): List<CanvasHit> = runCatching {
        val hits = mutableListOf<CanvasHit>()
        val reader = ProtoReader(bytes)
        while (reader.hasMore()) {
            val tag = reader.readTag() ?: break
            if (tag shr 3 == 1 && tag and 7 == 2) {
                decodeCanvas(reader.readBytes())?.let(hits::add)
            } else {
                reader.skip(tag)
            }
        }
        hits
    }.getOrElse { emptyList() }

    private fun decodeCanvas(bytes: ByteArray): CanvasHit? = runCatching {
        var url: String? = null
        var trackUri: String? = null
        val reader = ProtoReader(bytes)
        while (reader.hasMore()) {
            val tag = reader.readTag() ?: break
            when {
                tag shr 3 == 2 && tag and 7 == 2 -> url = String(reader.readBytes(), StandardCharsets.UTF_8)
                tag shr 3 == 5 && tag and 7 == 2 -> trackUri = String(reader.readBytes(), StandardCharsets.UTF_8)
                else -> reader.skip(tag)
            }
        }
        url?.let { CanvasHit(it, trackUri) }
    }.getOrNull()

    // ── Plumbing ─────────────────────────────────────────────────────────

    /** The client token on top of the bearer: both endpoints turn away a bearer-only request with
     * a 429 that reads exactly like rate limiting. Omitted rather than fatal when minting fails. */
    private fun authHeaders(token: String): Map<String, String> = buildMap {
        put("Authorization", "Bearer $token")
        DesktopSpotifyToken.clientToken()?.let { put("Client-Token", it) }
    }

    private fun get(url: String, token: String): JsonObject? =
        canvasGet(url, authHeaders(token))
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

    private fun url(base: String, params: List<Pair<String, String>>): String =
        params.joinToString("&", prefix = "$base?") { (name, value) ->
            "$name=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }

    /** A string field, or null when Spotify put something other than a string there. */
    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
}

// ── A protobuf reader, small enough not to be worth a dependency ────────────

private class ProtoReader(private val bytes: ByteArray) {
    private var at = 0

    fun hasMore(): Boolean = at < bytes.size

    fun readTag(): Int? = if (hasMore()) readVarint().toInt() else null

    fun readBytes(): ByteArray {
        val length = readVarint().toInt()
        val end = (at + length).coerceAtMost(bytes.size)
        val slice = bytes.copyOfRange(at, end)
        at = end
        return slice
    }

    fun skip(tag: Int) {
        when (tag and 7) {
            0 -> readVarint()
            1 -> at += 8
            2 -> readBytes()
            5 -> at += 4
            else -> at = bytes.size
        }
    }

    private fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (at < bytes.size && shift < 64) {
            val byte = bytes[at++].toInt()
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) break
            shift += 7
        }
        return result
    }
}

private fun ByteArrayOutputStream.writeLengthDelimited(field: Int, payload: ByteArray) {
    writeVarint((field shl 3 or 2).toLong())
    writeVarint(payload.size.toLong())
    write(payload)
}

private fun ByteArrayOutputStream.writeVarint(value: Long) {
    var remaining = value
    while (true) {
        if (remaining and 0x7FL.inv() == 0L) {
            write(remaining.toInt())
            return
        }
        write((remaining and 0x7F or 0x80).toInt())
        remaining = remaining ushr 7
    }
}

/**
 * The bearer Spotify's own web player mints for itself.
 *
 * Android loads the real player in an offscreen WebView and reads the token it mints, because a
 * request signed here is answered with a token the downstream endpoints then refuse. A desktop
 * build has no embedded browser to do that in, so this takes the only route left: the web player's
 * own `/api/token`, signed with the TOTP it derives from a secret published in its bundle. When
 * Spotify declines, the canvas chain simply falls through to its other three sources.
 */
internal object DesktopSpotifyToken {

    private const val TOKEN_URL = "https://open.spotify.com/api/token"
    private const val SERVER_TIME_URL = "https://open.spotify.com/api/server-time"
    private const val WEBVIEW_BRIDGE = "BitChordSpotifyTokenBridge"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val BUNDLE_URL_REGEX = Regex("""https://open\.spotifycdn\.com/cdn/build/web-player/web-player\.[a-f0-9]+\.js""")
    private val SECRET_SINGLE_REGEX = Regex("""secret:'((?:\\.|[^'])*)',\s*version:(\d+)""")
    private val SECRET_DOUBLE_REGEX = Regex("""secret:"((?:\\.|[^"])*)",\s*version:(\d+)""")

    @Volatile private var cachedAccessToken: String? = null
    @Volatile private var accessTokenExpiresAtMs = 0L
    @Volatile private var cachedClientId: String? = null
    @Volatile private var cachedClientToken: String? = null
    @Volatile private var clientTokenExpiresAtMs = 0L
    @Volatile private var retryAfterMs = 0L
    @Volatile private var forceWebViewNext = false
    @Volatile private var dynamicSecrets: Map<Int, IntArray> = emptyMap()
    @Volatile private var session: Pair<String, String>? = null

    /** Sanitizes a user-pasted or captured `sp_dc` cookie value (strips prefix, quotes, attributes). */
    internal fun sanitizeCookie(raw: String): String {
        val trimmed = raw.trim().trim('"', '\'')
        val withoutPrefix = if (trimmed.startsWith("sp_dc=", ignoreCase = true)) {
            trimmed.substringAfter('=')
        } else {
            trimmed
        }
        return withoutPrefix.substringBefore(';').trim()
    }

    /** The listener's `sp_dc` cookie. Nothing here works without it. */
    fun cookie(): String = sanitizeCookie(DesktopPersistence().string(KEY_SPDC))

    fun setCookie(value: String): String {
        val clean = sanitizeCookie(value)
        DesktopPersistence().saveString(KEY_SPDC, clean)
        cachedAccessToken = null
        accessTokenExpiresAtMs = 0L
        cachedClientToken = null
        clientTokenExpiresAtMs = 0L
        cachedClientId = null
        retryAfterMs = 0L
        forceWebViewNext = false
        return clean
    }

    @Synchronized
    fun markTokenRejected() {
        cachedAccessToken = null
        accessTokenExpiresAtMs = 0L
        cachedClientToken = null
        clientTokenExpiresAtMs = 0L
        retryAfterMs = 0L
        forceWebViewNext = true
    }

    private data class MintedToken(
        val accessToken: String,
        val expiresAtMs: Long,
        val clientId: String?,
    )

    @Synchronized
    fun accessToken(): String? {
        val cookie = cookie().ifBlank { return null }
        val now = System.currentTimeMillis()
        cachedAccessToken?.let { if (now < accessTokenExpiresAtMs - 30_000) return it }
        if (now < retryAfterMs) return null

        val minted = if (forceWebViewNext && DesktopPoTokenWebView.available) {
            harvestViaWebView(cookie) ?: mintViaTotp(cookie, now)
        } else {
            mintViaTotp(cookie, now) ?: harvestViaWebView(cookie)
        }

        if (minted == null) {
            retryAfterMs = now + RETRY_MS
            DesktopTrackLog.log("spotify: could not mint an access token from sp_dc")
            return null
        }
        forceWebViewNext = false
        cachedAccessToken = minted.accessToken
        accessTokenExpiresAtMs = minted.expiresAtMs
        minted.clientId?.let { cachedClientId = it }
        return minted.accessToken
    }

    private fun mintViaTotp(cookie: String, nowMs: Long): MintedToken? {
        val localSec = nowMs / 1000L
        val serverSec = fetchServerTimeSeconds() ?: localSec
        val known = (TOTP_SECRETS + dynamicSecrets).entries
            .sortedByDescending { it.key }
            .map { it.key to it.value }

        for ((ver, secret) in known) {
            requestTokenWithTotp(cookie, ver, secret, localSec, serverSec)?.let { return it }
        }

        // Known secret versions rejected by Spotify (rotation) — extract the live secret from Spotify's bundle.
        val refreshed = refreshDynamicSecrets()
        for ((ver, secret) in refreshed.entries.sortedByDescending { it.key }) {
            if (ver in TOTP_SECRETS || ver in dynamicSecrets) continue
            requestTokenWithTotp(cookie, ver, secret, localSec, serverSec)?.let { return it }
        }
        return null
    }

    private fun requestTokenWithTotp(
        cookie: String,
        ver: Int,
        secret: IntArray,
        localSec: Long,
        serverSec: Long,
    ): MintedToken? {
        val codeLocal = totp(secret, localSec) ?: return null
        val codeServer = totp(secret, serverSec) ?: codeLocal
        val query = listOf(
            "reason" to "init",
            "productType" to "web-player",
            "totp" to codeLocal,
            "totpServer" to codeServer,
            "totpVer" to ver.toString(),
        ).joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, StandardCharsets.UTF_8)}" }

        val body = canvasGet(
            "$TOKEN_URL?$query",
            mapOf(
                "Cookie" to "sp_dc=$cookie",
                "Referer" to "https://open.spotify.com/",
                "Origin" to "https://open.spotify.com",
                "App-Platform" to "WebPlayer",
            ),
        ) ?: return null
        return parseTokenResponse(body, allowAnonymous = false)
    }

    private fun parseTokenResponse(body: String, allowAnonymous: Boolean): MintedToken? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val isAnon = root["isAnonymous"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        if (isAnon && !allowAnonymous) return null
        val token = root["accessToken"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val expiresAt = root["accessTokenExpirationTimestampMs"]
            ?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?.takeIf { it > System.currentTimeMillis() }
            ?: (System.currentTimeMillis() + 3_600_000L)
        val clientId = root["clientId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        return MintedToken(token, expiresAt, clientId)
    }

    private fun fetchServerTimeSeconds(): Long? {
        val body = canvasGet(
            SERVER_TIME_URL,
            mapOf("Referer" to "https://open.spotify.com/"),
        ) ?: return null
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        return root["serverTime"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
    }

    private fun refreshDynamicSecrets(): Map<Int, IntArray> {
        val html = canvasGet("https://open.spotify.com/") ?: return emptyMap()
        val bundleUrl = BUNDLE_URL_REGEX.find(html)?.value ?: return emptyMap()
        val js = canvasGet(bundleUrl) ?: return emptyMap()
        val parsed = parseSecretsFromBundleJs(js)
        if (parsed.isNotEmpty()) {
            dynamicSecrets = dynamicSecrets + parsed
        }
        return parsed
    }

    internal fun parseSecretsFromBundleJs(js: String): Map<Int, IntArray> {
        val found = linkedMapOf<Int, IntArray>()
        for (regex in listOf(SECRET_SINGLE_REGEX, SECRET_DOUBLE_REGEX)) {
            for (match in regex.findAll(js)) {
                val raw = unescapeJsString(match.groupValues[1])
                val ver = match.groupValues[2].toIntOrNull() ?: continue
                if (raw.isNotEmpty()) {
                    found[ver] = raw.map { it.code }.toIntArray()
                }
            }
        }
        return found
    }

    private fun unescapeJsString(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val next = s[i + 1]
                when (next) {
                    '\\', '\'', '"' -> { sb.append(next); i += 2; continue }
                    'n' -> { sb.append('\n'); i += 2; continue }
                    'r' -> { sb.append('\r'); i += 2; continue }
                    't' -> { sb.append('\t'); i += 2; continue }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    class WebViewTokenBridge(private val result: CompletableFuture<String?>) {
        fun onTokenPayload(payload: String?) {
            if (payload.isNullOrBlank() || result.isDone) return
            if (parseTokenResponse(payload, allowAnonymous = false) != null) {
                result.complete(payload)
            }
        }
    }

    private val HOOK_SCRIPT = """
        (function () {
          if (window.__bitchordTokenHook) return;
          window.__bitchordTokenHook = true;
          var report = function (body) {
            try { if (window.$WEBVIEW_BRIDGE) window.$WEBVIEW_BRIDGE.onTokenPayload(body); } catch (e) {}
          };
          var isToken = function (u) {
            try { return String(u).indexOf('/api/token') !== -1; } catch (e) { return false; }
          };
          var origFetch = window.fetch;
          if (origFetch) {
            window.fetch = function (input, init) {
              var url = (input && input.url) ? input.url : input;
              var res = origFetch.apply(this, arguments);
              if (isToken(url)) {
                try { res.then(function (r) { r.clone().text().then(report).catch(function () {}); }).catch(function () {}); } catch (e) {}
              }
              return res;
            };
          }
          var origOpen = XMLHttpRequest.prototype.open;
          XMLHttpRequest.prototype.open = function (method, url) {
            this.__bitchordUrl = url;
            return origOpen.apply(this, arguments);
          };
          var origSend = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.send = function () {
            var xhr = this;
            try {
              xhr.addEventListener('load', function () {
                if (isToken(xhr.__bitchordUrl)) report(xhr.responseText);
              });
            } catch (e) {}
            return origSend.apply(this, arguments);
          };
        })();
    """.trimIndent()

    private fun harvestViaWebView(cookie: String): MintedToken? {
        if (!DesktopPoTokenWebView.available) return null
        val future = CompletableFuture<String?>()
        var holderView: WebView? = null
        var storageDir: Path? = null
        val bridge = WebViewTokenBridge(future)

        runCatching {
            val cookieHeaders = mapOf("Set-Cookie" to listOf("sp_dc=$cookie; Domain=.spotify.com; Path=/; Secure"))
            DesktopWebCookies.put(URI("https://open.spotify.com/"), cookieHeaders)
            DesktopWebCookies.put(URI("https://accounts.spotify.com/"), cookieHeaders)
        }

        Platform.runLater {
            runCatching {
                val folder = Files.createTempDirectory("luminachord-spotify-token-").also { storageDir = it }
                val webView = WebView().also { holderView = it }
                val engine: WebEngine = webView.engine
                engine.userDataDirectory = folder.toFile()
                fun installHook() {
                    runCatching {
                        val win = engine.executeScript("window") as? JSObject ?: return
                        win.setMember(WEBVIEW_BRIDGE, bridge)
                        engine.executeScript(HOOK_SCRIPT)
                    }
                }
                engine.documentProperty().addListener { _, _, doc -> if (doc != null) installHook() }
                engine.loadWorker.stateProperty().addListener { _, _, state ->
                    if (state == Worker.State.RUNNING || state == Worker.State.SUCCEEDED) installHook()
                    if (state == Worker.State.FAILED && !future.isDone) future.complete(null)
                }
                engine.load("https://open.spotify.com/")
            }.onFailure {
                if (!future.isDone) future.complete(null)
            }
        }

        return try {
            val raw = future.get(15, TimeUnit.SECONDS) ?: return null
            parseTokenResponse(raw, allowAnonymous = false)
        } catch (_: Exception) {
            null
        } finally {
            Platform.runLater {
                runCatching {
                    holderView?.engine?.load("about:blank")
                    holderView = null
                    storageDir?.toFile()?.deleteRecursively()
                }
            }
        }
    }

    /**
     * The second header these endpoints demand alongside the bearer. Best-effort: a caller with no
     * client token sends the bearer alone and takes whatever the endpoint does with that.
     */
    @Synchronized
    fun clientToken(): String? {
        val now = System.currentTimeMillis()
        cachedClientToken?.let { if (now < clientTokenExpiresAtMs - 30_000) return it }
        val clientId = cachedClientId ?: return null
        val (clientVersion, deviceId) = session() ?: return null
        val osName = when {
            DesktopPlatform.isWindows -> "windows"
            DesktopPlatform.isMac -> "macos"
            else -> "linux"
        }

        val payload = buildJsonObject {
            putJsonObject("client_data") {
                put("client_version", clientVersion)
                put("client_id", clientId)
                putJsonObject("js_sdk_data") {
                    put("device_brand", "unknown")
                    put("device_model", "unknown")
                    put("os", osName)
                    put("os_version", System.getProperty("os.version").orEmpty())
                    put("device_id", deviceId)
                    put("device_type", "computer")
                }
            }
        }
        val body = runCatching {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
            val response = client.send(
                HttpRequest.newBuilder(URI.create("https://clienttoken.spotify.com/v1/clienttoken"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    // The bare header, deliberately: clienttoken 400s on a charset suffix.
                    .header("Content-Type", "application/json")
                    .header("User-Agent", CANVAS_UA)
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            if (response.statusCode() in 200..299) response.body() else null
        }.getOrNull() ?: return null

        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        if (root["response_type"]?.jsonPrimitive?.contentOrNull != "RESPONSE_GRANTED_TOKEN_RESPONSE") return null
        val granted = root["granted_token"]?.jsonObject ?: return null
        val token = granted["token"]?.jsonPrimitive?.contentOrNull ?: return null
        val ttl = granted["expires_after_seconds"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
        cachedClientToken = token
        clientTokenExpiresAtMs = now + ttl * 1000
        return token
    }

    /** The web player's build version, read off its own page, plus a device id to call ourselves. */
    private fun session(): Pair<String, String>? {
        session?.let { return it }
        val html = canvasGet("https://open.spotify.com") ?: return null
        val configB64 = Regex("""<script id="appServerConfig" type="text/plain">([^<]+)</script>""")
            .find(html)?.groupValues?.get(1) ?: return null
        val clientVersion = runCatching {
            val decoded = String(Base64.getDecoder().decode(configB64), StandardCharsets.UTF_8)
            json.parseToJsonElement(decoded).jsonObject["clientVersion"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: return null
        return (clientVersion to UUID.randomUUID().toString()).also { session = it }
    }

    /**
     * The six digits the web player sends with a token request.
     *
     * Standard TOTP over SHA-1, thirty-second steps, from a secret the bundle carries obfuscated as
     * a byte array XORed against its own index.
     */
    internal fun totp(secret: IntArray, epochSeconds: Long): String? = runCatching {
        val cleaned = secret.mapIndexed { index, value -> value xor (index % 33 + 9) }
            .joinToString("")
            .toByteArray(StandardCharsets.UTF_8)
        val counter = epochSeconds / 30
        val message = ByteArray(8) { index -> (counter ushr (56 - index * 8)).toByte() }
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(cleaned, "HmacSHA1")) }
        val hash = mac.doFinal(message)
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        "%06d".format(binary % 1_000_000)
    }.getOrNull()

    /**
     * Fallback TOTP secrets shipped by Spotify Web Player. Kept updated + auto-refreshed from the live
     * JS bundle via [refreshDynamicSecrets] when Spotify rotates the version.
     */
    private val TOTP_SECRETS: Map<Int, IntArray> = mapOf(
        61 to """,7/*F("rLJ2oxaKL^f+E1xvP@N""".map { it.code }.toIntArray(),
        60 to """OmE{ZA.J^":0FG\Uz?[@WW""".map { it.code }.toIntArray(),
        59 to """{iOFn;4}<1PFYKPV?5{%u14]M>/V0hDH""".map { it.code }.toIntArray(),
    )

    private const val RETRY_MS = 10_000L

    internal const val KEY_SPDC = "spotify_spdc_token"
}
