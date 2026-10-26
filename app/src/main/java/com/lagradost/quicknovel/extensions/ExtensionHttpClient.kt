package com.lagradost.quicknovel.extensions

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Bounded HTTP GET helper for the future extension host bridge.
 *
 * This deliberately has no Android Context or app-network objects. Do not expose it
 * directly to JavaScript until the runtime can enforce execution and call budgets.
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
    ): Result<TextResponse> = runCatching {
        var currentUrl = validateUrl(rawUrl)
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
                    currentUrl = validateUrl(URL(currentUrl).toURI().resolve(location).toString())
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

    private fun validateUrl(value: String): String {
        val parsed = URL(value)
        require(parsed.protocol == "https" || parsed.protocol == "http") {
            "Extension requests must use HTTP or HTTPS."
        }
        require(!parsed.host.isNullOrBlank() && parsed.userInfo == null) {
            "Extension request URL is invalid."
        }
        return parsed.toURI().normalize().toString()
    }
}
