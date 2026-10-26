package com.lagradost.quicknovel.extensions

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Locale

/**
 * Bounded HTTP GET helper used by the restricted extension host bridge.
 *
 * This deliberately has no Android Context or app-network objects. The JS bridge limits
 * calls and restricts requests to the extension's declared site host; this remains a
 * compatibility boundary, not process isolation for untrusted code.
 */
object ExtensionHttpClient {
    data class TextResponse(
        val url: String,
        val statusCode: Int,
        val contentType: String?,
        val body: String,
    )

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_REDIRECTS = 5
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    private const val MAX_HEADER_VALUE_LENGTH = 2_000

    private val allowedRequestHeaders = setOf(
        "accept",
        "accept-language",
        "user-agent",
        "referer",
    )

    /**
     * Fetches text from HTTP(S), enforcing a redirect count and response-size limit.
     * Call from an IO/worker thread. Only a small set of ordinary request headers
     * are accepted; hop-by-hop and credential headers cannot be supplied by a plugin.
     */
    fun getText(
        rawUrl: String,
        headers: Map<String, String> = emptyMap(),
        allowedHosts: Set<String>? = null,
    ): Result<TextResponse> = runCatching {
        var currentUrl = validateUrl(rawUrl, allowedHosts)
        val safeHeaders = headers.entries
            .filter { it.key.lowercase(Locale.ROOT) in allowedRequestHeaders }
            .associate { (key, value) ->
                key to value.take(MAX_HEADER_VALUE_LENGTH)
            }

        repeat(MAX_REDIRECTS + 1) { redirectIndex ->
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection)
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("Accept", "text/html,application/json,text/plain,*/*")
                safeHeaders.forEach { (key, value) ->
                    if (!value.contains('\r') && !value.contains('\n')) {
                        connection.setRequestProperty(key, value)
                    }
                }

                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalStateException("Extension request returned a redirect without a location.")
                    if (redirectIndex >= MAX_REDIRECTS) {
                        throw IllegalStateException("Extension request exceeded the redirect limit.")
                    }
                    currentUrl = validateUrl(URL(currentUrl).toURI().resolve(location).toString(), allowedHosts)
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

                return Result.success(
                    TextResponse(
                        url = currentUrl,
                        statusCode = status,
                        contentType = connection.contentType,
                        body = String(bodyBytes, Charsets.UTF_8),
                    )
                )
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("Extension request could not be completed.")
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
