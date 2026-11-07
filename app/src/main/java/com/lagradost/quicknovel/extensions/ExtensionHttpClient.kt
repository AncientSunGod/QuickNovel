package com.lagradost.quicknovel.extensions

import android.webkit.CookieManager
import com.lagradost.quicknovel.network.WebViewResolver
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Bounded HTTP helper used by the restricted extension host bridge.
 *
 * This deliberately has no Android Context or app-network objects. The JS bridge limits
 * calls and restricts requests to the site host plus public API hosts explicitly referenced
 * by the downloaded bundle; this remains a compatibility boundary, not process isolation.
 */
object ExtensionHttpClient {
    data class TextResponse(
        val url: String,
        val statusCode: Int,
        val contentType: String?,
        val body: String,
        val statusText: String = "",
        val headers: Map<String, String> = emptyMap(),
        val bodyBytes: ByteArray = body.toByteArray(Charsets.UTF_8),
    )

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_REDIRECTS = 5
    private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
    private const val MAX_REQUEST_BYTES = 256 * 1024
    private const val MAX_HEADER_VALUE_LENGTH = 2_000

    private val allowedRequestHeaders = setOf(
        "accept",
        "accept-language",
        "user-agent",
        "referer",
        "referrer",
        "authorization",
        "cookie",
        "origin",
        "content-type",
        "x-requested-with",
        "x-csrf-token",
        "sec-ch-ua",
        "sec-ch-ua-mobile",
        "sec-ch-ua-platform",
        "sec-fetch-dest",
        "sec-fetch-mode",
        "sec-fetch-site",
        "sec-fetch-user",
        "upgrade-insecure-requests",
    )

    /**
     * Fetches text from HTTP(S), enforcing a redirect count and response-size limit.
     * Call from an IO/worker thread. Only a small set of ordinary request headers
     * are accepted; hop-by-hop and arbitrary headers are blocked. Credential headers
     * are only forwarded when the extension bundle explicitly asks for them.
     */
    fun getText(
        rawUrl: String,
        headers: Map<String, String> = emptyMap(),
        allowedHosts: Set<String>? = null,
    ): Result<TextResponse> = requestText(
        rawUrl = rawUrl,
        method = "GET",
        headers = headers,
        allowedHosts = allowedHosts,
    )

    /**
     * Executes a bounded GET or POST request. Request bodies are UTF-8 strings only;
     * arbitrary binary and multipart uploads are intentionally unsupported.
     */
    fun requestText(
        rawUrl: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        allowedHosts: Set<String>? = null,
        responseEncoding: String? = null,
    ): Result<TextResponse> = runCatching {
        var currentUrl = validateUrl(rawUrl, allowedHosts)
        var currentMethod = method.trim().uppercase(Locale.ROOT)
        var currentBody = body?.toByteArray(Charsets.UTF_8)
        require(currentMethod == "GET" || currentMethod == "POST") {
            "Extension requests currently support GET and POST only."
        }
        require(currentMethod != "GET" || currentBody == null) {
            "GET requests may not include a request body."
        }
        require(currentBody == null || currentBody.size <= MAX_REQUEST_BYTES) {
            "Extension request body exceeds the 256 KB limit."
        }

        val safeHeaders = headers.entries
            .filter {
                val name = it.key.lowercase(Locale.ROOT)
                name in allowedRequestHeaders && name != "user-agent"
            }
            .associate { (key, value) ->
                key to value.take(MAX_HEADER_VALUE_LENGTH)
            }

        // A few sites return 403 to a plain HTTP client but allow a real browser
        // session after its normal page scripts and any interactive verification run.
        // Retry at most once per extension request; never suppress a server denial.
        var webViewRecoveryAttempted = false
        repeat(MAX_REDIRECTS + 2) { redirectIndex ->
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection)
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = currentMethod
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                val requestHost = URL(currentUrl).host.lowercase(Locale.ROOT)
                val browserUserAgent = runCatching {
                    WebViewResolver.getWebViewUserAgent()
                }.getOrNull()
                connection.setRequestProperty(
                    "User-Agent",
                    browserUserAgent?.takeIf { it.isNotBlank() }
                        ?: com.lagradost.quicknovel.USER_AGENT
                )
                // Reuse browser request hints captured from a prior in-app WebView
                // session for this host. Explicit extension headers still take priority.
                val browserHeaders = WebViewResolver.capturedHeaders[requestHost]
                    .orEmpty()
                    .filterKeys {
                        val normalized = it.lowercase(Locale.ROOT)
                        normalized in allowedRequestHeaders &&
                            normalized != "cookie" &&
                            normalized != "user-agent"
                    }
                // CookieManager is authoritative; do not replace its live value with a stale captured header.
                val effectiveHeaders = browserHeaders + safeHeaders
                connection.setRequestProperty("Connection", "keep-alive")
                // Send ordinary navigation context by default. A number of novel sites
                // reject bare programmatic requests when Accept/Referer are absent.
                val requestOrigin = URL(currentUrl).let { "${it.protocol}://${it.host}" }
                connection.setRequestProperty(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
                connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                connection.setRequestProperty("Referer", "$requestOrigin/")
                connection.setRequestProperty("Origin", requestOrigin)
                connection.setRequestProperty("Cache-Control", "max-age=0")
                // Reuse the app's normal WebView cookie jar so extensions can maintain
                // ordinary site sessions across requests. This does not solve or bypass
                // server-side access challenges; it only sends cookies already issued to
                // the app and stores cookies returned by the server.
                val webViewCookies = runCatching {
                    CookieManager.getInstance().getCookie(currentUrl)
                }.getOrNull()
                if (!webViewCookies.isNullOrBlank() &&
                    safeHeaders.keys.none { it.equals("Cookie", ignoreCase = true) }
                ) {
                    connection.setRequestProperty("Cookie", webViewCookies)
                }
                effectiveHeaders.forEach { (key, value) ->
                    if (!value.contains('\r') && !value.contains('\n')) {
                        connection.setRequestProperty(key, value)
                    }
                }
                // Keep the request User-Agent identical to the Android WebView session.
                // Some anti-bot cookies are bound to the browser fingerprint; allowing an
                // extension's Node-style User-Agent to overwrite it breaks every retry.
                connection.setRequestProperty(
                    "User-Agent",
                    browserUserAgent?.takeIf { it.isNotBlank() }
                        ?: com.lagradost.quicknovel.USER_AGENT
                )
                if (currentMethod == "POST") {
                    connection.doOutput = true
                    currentBody?.let { bytes ->
                        connection.setFixedLengthStreamingMode(bytes.size)
                        connection.outputStream.use { output -> output.write(bytes) }
                    }
                }

                val status = connection.responseCode
                // Set-Cookie can arrive on 3xx redirects and 403 challenge responses too.
                // Persist it before handling either branch so the next navigation/retry sees
                // the complete session, not only cookies from successful 2xx responses.
                storeResponseCookies(connection, currentUrl)
                // Cloudflare may surface a browser-verification failure as 520
                // rather than 403. Give the same host-scoped WebView session recovery
                // one bounded attempt; if the origin itself is unavailable, the 520
                // response is returned unchanged after this attempt.
                if ((status == 403 || status == 520) &&
                    !webViewRecoveryAttempted && allowedHosts != null
                ) {
                    webViewRecoveryAttempted = true
                    connection.disconnect()
                    val targetHost = URL(currentUrl).host
                    val browserSessionOpened = runCatching {
                        // This overload preserves existing WebView cookies and uses the
                        // app's browser resolver. The server still decides whether access
                        // is allowed; a second 403 is returned unchanged to the extension.
                        runBlocking {
                            WebViewResolver().resolveUsingWebView(
                                url = currentUrl,
                                requestCallBack = { request ->
                                    request.url.host.equals(targetHost, ignoreCase = true)
                                },
                                // The resolver's default 5 seconds can expire while Cloudflare's
                                // browser-side challenge script is still retrying its verification.
                                completionStabilityDelay = 18.seconds,
                            )
                        }
                    }.map { true }.getOrDefault(false)
                    if (browserSessionOpened) {
                        runCatching { CookieManager.getInstance().flush() }
                        return@repeat
                    }
                }
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalStateException("Extension request returned a redirect without a location.")
                    if (redirectIndex >= MAX_REDIRECTS) {
                        throw IllegalStateException("Extension request exceeded the redirect limit.")
                    }
                    val nextUrl = URL(currentUrl).toURI().resolve(location).toString()
                    require(!(URL(currentUrl).protocol.equals("https", ignoreCase = true) &&
                        !URL(nextUrl).protocol.equals("https", ignoreCase = true))) {
                        "HTTPS extension requests may not redirect to HTTP."
                    }
                    if (status == 303 || ((status == 301 || status == 302) && currentMethod == "POST")) {
                        currentMethod = "GET"
                        currentBody = null
                    }
                    currentUrl = validateUrl(nextUrl, allowedHosts)
                    return@repeat
                }

                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val bodyBytes = stream?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_RESPONSE_BYTES) {
                            "Extension response exceeds the 2 MB limit."
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: ByteArray(0)

                val responseHeaders = connection.headerFields.entries
                    .filter { it.key != null && it.value?.isNotEmpty() == true }
                    .associate { (key, values) ->
                        key.lowercase(Locale.ROOT) to values.first().take(MAX_HEADER_VALUE_LENGTH)
                    }
                return Result.success(
                    TextResponse(
                        url = currentUrl,
                        statusCode = status,
                        contentType = connection.contentType,
                        body = decodeText(bodyBytes, responseEncoding, connection.contentType),
                        statusText = connection.responseMessage.orEmpty(),
                        headers = responseHeaders,
                        bodyBytes = bodyBytes,
                    )
                )
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("Extension request could not be completed.")
    }

    private fun storeResponseCookies(connection: HttpURLConnection, url: String) {
        runCatching {
            val cookieManager = CookieManager.getInstance()
            val cookies = connection.headerFields.entries
                .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                .flatMap { it.value.orEmpty() }
            if (cookies.isNotEmpty()) {
                // Set all cookies before waiting, with one bounded overall timeout.
                // A per-cookie wait could stall a provider for many seconds if a response
                // contains a large number of Set-Cookie headers.
                val stored = CountDownLatch(cookies.size)
                cookies.forEach { cookie ->
                    cookieManager.setCookie(url, cookie) { stored.countDown() }
                }
                stored.await(2, TimeUnit.SECONDS)
            }
            cookieManager.flush()
        }
    }

    internal fun decodeText(
        bytes: ByteArray,
        requestedEncoding: String? = null,
        contentType: String? = null,
    ): String {
        val contentTypeEncoding = contentType
            ?.let { Regex("""(?i)charset\s*=\s*["']?([^;"'\s]+)""").find(it)?.groupValues?.getOrNull(1) }
        val charset = sequenceOf(requestedEncoding, contentTypeEncoding)
            .filterNotNull()
            .mapNotNull { name -> runCatching { Charset.forName(name.trim()) }.getOrNull() }
            .firstOrNull()
            ?: StandardCharsets.UTF_8
        return String(bytes, charset)
    }

    private fun validateUrl(value: String, allowedHosts: Set<String>? = null): String {
        val parsed = URL(value)
        require(parsed.protocol == "https" || parsed.protocol == "http") {
            "Extension requests must use HTTP or HTTPS."
        }
        require(!parsed.host.isNullOrBlank() && parsed.userInfo == null) {
            "Extension request URL is invalid."
        }
        val host = parsed.host.lowercase(Locale.ROOT).trimEnd('.')
        require(host != "localhost" && !host.endsWith(".localhost")) {
            "Localhost extension requests are blocked."
        }
        require(allowedHosts == null || allowedHosts.any {
            host.equals(it.lowercase(Locale.ROOT).trimEnd('.'), ignoreCase = true)
        }) {
            "Extension request host is outside the approved site."
        }
        if (allowedHosts != null) {
            val addresses = InetAddress.getAllByName(host)
            require(addresses.isNotEmpty() && addresses.all { address ->
                val bytes = address.address
                val privateV4 = bytes.size == 4 && (
                    (bytes[0].toInt() and 0xff) == 10 ||
                    (bytes[0].toInt() and 0xff) == 127 ||
                    ((bytes[0].toInt() and 0xff) == 169 && (bytes[1].toInt() and 0xff) == 254) ||
                    ((bytes[0].toInt() and 0xff) == 172 && (bytes[1].toInt() and 0xff) in 16..31) ||
                    ((bytes[0].toInt() and 0xff) == 192 && (bytes[1].toInt() and 0xff) == 168) ||
                    ((bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xff) in 64..127) ||
                    (bytes[0].toInt() and 0xff) >= 224
                )
                val firstV6 = if (bytes.size == 16) bytes[0].toInt() and 0xff else 0
                val uniqueLocalV6 = bytes.size == 16 && (firstV6 and 0xfe) == 0xfc
                !privateV4 && !uniqueLocalV6 && !address.isAnyLocalAddress &&
                    !address.isLoopbackAddress && !address.isLinkLocalAddress &&
                    !address.isSiteLocalAddress && !address.isMulticastAddress
            }) {
                "Extension site resolves to a non-public network address."
            }
        }
        return parsed.toURI().normalize().toString()
    }
}
