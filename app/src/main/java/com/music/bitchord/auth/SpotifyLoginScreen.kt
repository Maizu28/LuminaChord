package com.music.bitchord.auth

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.music.bitchord.R
import com.music.bitchord.data.web.QuietWebChromeClient

private const val LOGIN_URL =
    "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"

private val SPOTIFY_COOKIE_URLS = listOf(
    "https://open.spotify.com",
    "https://accounts.spotify.com",
    "https://www.spotify.com",
    "https://spotify.com",
)

private val LOGIN_HOST_SUFFIXES = listOf(
    "spotify.com",
    "spotifycdn.com",
    "scdn.co",
    "google.com",
    "googleapis.com",
    "gstatic.com",
    "recaptcha.net",
    "hcaptcha.com",
    "facebook.com",
    "apple.com",
)

private fun hostMatches(uri: Uri?, suffixes: List<String>): Boolean {
    if (uri?.scheme != "https") return false
    val host = uri.host?.lowercase() ?: return false
    return suffixes.any { host == it || host.endsWith(".$it") }
}

private fun isLoginHost(uri: Uri?) = hostMatches(uri, LOGIN_HOST_SUFFIXES)

private fun extractSpDcCookie(): String? {
    val manager = CookieManager.getInstance()
    for (url in SPOTIFY_COOKIE_URLS) {
        val raw = manager.getCookie(url) ?: continue
        val token = raw.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("sp_dc=") }
            ?.substringAfter("=")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (token != null) return token
    }
    return null
}

private fun sanitizeSpDcInput(raw: String): String {
    val trimmed = raw.trim().trim('"').trim('\'')
    val withoutPrefix = if (trimmed.startsWith("sp_dc=", ignoreCase = true)) {
        trimmed.substringAfter("=")
    } else {
        trimmed
    }
    return withoutPrefix.substringBefore(";").trim()
}

/**
 * Signs the login WebView out of Spotify. Disconnecting only forgot the
 * stored cookie, and the WebView's own jar would have signed the next
 * "Sign in" straight back in. Only Spotify's cookies go — the same jar holds
 * the YouTube Music sign-in.
 */
fun clearSpotifyWebSession() {
    val cookies = CookieManager.getInstance()
    for (url in SPOTIFY_COOKIE_URLS) {
        val names = cookies.getCookie(url)?.split(";")
            ?.map { it.substringBefore("=").trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        for (name in names) {
            val expired = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"
            cookies.setCookie(url, expired)
            cookies.setCookie(url, "$expired; Domain=.spotify.com")
        }
    }
    cookies.flush()
}

/**
 * In-app Spotify sign-in. Lets the real accounts.spotify.com page handle the
 * login, then hands the `sp_dc` cookie to [onConnected] once authenticated.
 *
 * Uses authentic WebView User-Agent matching Chromium Client Hints (`Sec-CH-UA`)
 * so Spotify/reCAPTCHA do not reject mismatched headers, and provides a clean
 * fallback to paste `sp_dc` directly when reCAPTCHA blocks embedded WebViews.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginScreen(
    onConnected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var manualMode by remember { mutableStateOf(false) }
    var spdcInput by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF121212)),
    ) {
        Surface(
            color = Color(0xFF1A1A1A),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = { manualMode = !manualMode },
                ) {
                    Text(
                        text = if (manualMode) "Kembali ke Web Login" else "Gunakan Cookie sp_dc",
                        color = Color(0xFF1ED760),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (!manualMode) {
                    TextButton(
                        onClick = {
                            clearSpotifyWebSession()
                            webView?.loadUrl(LOGIN_URL)
                        },
                    ) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "Muat ulang sesi",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(4.dp))
                        Text("Reset & Muat Ulang", color = Color.White.copy(alpha = 0.85f))
                    }
                }
            }
        }

        if (manualMode) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Login Spotify via Cookie sp_dc",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Jika halaman login Spotify menampilkan \"Oops! Something went wrong\" akibat proteksi reCAPTCHA pada WebView, Anda dapat masuk melalui browser (Chrome/PC) lalu menempelkan nilai cookie `sp_dc` dari `open.spotify.com`:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.78f),
                )
                Text(
                    text = "1. Buka open.spotify.com di browser dan login seperti biasa (Email, Google, Facebook, atau Apple).\n" +
                        "2. Buka Developer Tools (F12 / Ctrl+Shift+I) → Application → Cookies → https://open.spotify.com.\n" +
                        "3. Salin nilai cookie bernama `sp_dc` lalu tempel di bawah.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.68f),
                )
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(LOGIN_URL)),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Buka Spotify Login di Browser HP", color = Color.White)
                }
                OutlinedTextField(
                    value = spdcInput,
                    onValueChange = {
                        spdcInput = it
                        inputError = null
                    },
                    label = { Text(stringResource(R.string.spdc_token)) },
                    placeholder = { Text("Tempel nilai cookie sp_dc (atau sp_dc=...) di sini") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF1ED760),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                        focusedLabelColor = Color(0xFF1ED760),
                        unfocusedLabelColor = Color.White.copy(alpha = 0.65f),
                    ),
                )
                inputError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF6B6B),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        val cleaned = sanitizeSpDcInput(spdcInput)
                        if (cleaned.length < 16) {
                            inputError = "Cookie sp_dc tidak valid atau terlalu pendek."
                        } else {
                            onConnected(cleaned)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1ED760),
                        contentColor = Color.Black,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.save),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.javaScriptCanOpenWindowsAutomatically = true
                            setBackgroundColor(0xFF121212.toInt())
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                            webChromeClient = QuietWebChromeClient()
                            var sent = false
                            fun checkAndComplete(view: WebView, currentUrl: String?) {
                                canGoBack = view.canGoBack()
                                if (sent) return
                                val landedOnOpenSpotify = currentUrl?.startsWith("https://open.spotify.com") == true
                                val onStatusPage = currentUrl?.startsWith("https://accounts.spotify.com") == true &&
                                    currentUrl.contains("/status")
                                if (!landedOnOpenSpotify && !onStatusPage) return
                                val token = extractSpDcCookie() ?: return
                                sent = true
                                CookieManager.getInstance().flush()
                                view.visibility = View.GONE
                                onConnected(token)
                            }

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): Boolean {
                                    if (!request.isForMainFrame) return false
                                    return !isLoginHost(request.url)
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    checkAndComplete(view, url)
                                }

                                override fun doUpdateVisitedHistory(
                                    view: WebView,
                                    url: String?,
                                    isReload: Boolean,
                                ) {
                                    checkAndComplete(view, url)
                                }
                            }
                            webView = this
                            loadUrl(LOGIN_URL)
                        }
                    },
                    onRelease = { it.destroy() },
                )
            }
        }
    }

    BackHandler(enabled = !manualMode && canGoBack) {
        webView?.goBack()
    }
}
