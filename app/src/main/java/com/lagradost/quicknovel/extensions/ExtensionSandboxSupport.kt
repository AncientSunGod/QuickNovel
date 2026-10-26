package com.lagradost.quicknovel.extensions

import android.content.Context
import androidx.javascriptengine.JavaScriptSandbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Checks whether this device's WebView sandbox exposes the controls required before
 * QuickNovel considers executing third-party extension code.
 *
 * This is deliberately only a capability probe: it does not evaluate a bundle, and
 * it does not make LNReader bundles compatible with QuickNovel by itself.
 */
object ExtensionSandboxSupport {
    data class Status(
        val safeRuntimeAvailable: Boolean,
        val sandboxSupported: Boolean,
        val isolateTerminationSupported: Boolean,
        val isolateHeapLimitSupported: Boolean,
        val message: String,
    )

    suspend fun inspect(context: Context): Status = withContext(Dispatchers.IO) {
        val supported = try {
            JavaScriptSandbox.isSupported()
        } catch (_: Exception) {
            false
        }
        if (!supported) {
            return@withContext Status(
                safeRuntimeAvailable = false,
                sandboxSupported = false,
                isolateTerminationSupported = false,
                isolateHeapLimitSupported = false,
                message = "This device does not support Android's JavaScript sandbox.",
            )
        }

        try {
            val sandbox = JavaScriptSandbox.createConnectedInstanceAsync(
                context.applicationContext
            ).get(15, TimeUnit.SECONDS)
            try {
                val termination = sandbox.isFeatureSupported(
                    JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION
                )
                val heapLimit = sandbox.isFeatureSupported(
                    JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE
                )
                val ready = termination && heapLimit
                Status(
                    safeRuntimeAvailable = ready,
                    sandboxSupported = true,
                    isolateTerminationSupported = termination,
                    isolateHeapLimitSupported = heapLimit,
                    message = if (ready) {
                        "Sandbox supports isolate termination and heap limits; provider compatibility still needs implementation."
                    } else {
                        "Sandbox is present, but this WebView lacks required execution controls."
                    },
                )
            } finally {
                sandbox.close()
            }
        } catch (_: Exception) {
            Status(
                safeRuntimeAvailable = false,
                sandboxSupported = true,
                isolateTerminationSupported = false,
                isolateHeapLimitSupported = false,
                message = "Could not initialize or inspect the JavaScript sandbox.",
            )
        }
    }
}
