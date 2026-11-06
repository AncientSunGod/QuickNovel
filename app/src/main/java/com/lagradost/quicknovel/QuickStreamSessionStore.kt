package com.lagradost.quicknovel

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Intent extra used to hand a live QuickStream manifest directly to ReadActivity2. */
internal const val QUICK_STREAM_SESSION_EXTRA = "com.lagradost.quicknovel.extra.QUICK_STREAM_SESSION"

/**
 * In-process hand-off for temporary online-reader sessions.
 *
 * A serialized FileProvider manifest remains attached to the intent as a recovery path,
 * but the normal path avoids a disk write/read plus Jackson conversion between two
 * activities in the same process.
 */
internal object QuickStreamSessionStore {
    private const val MAX_SESSIONS = 8
    private val sessions = ConcurrentHashMap<String, QuickStreamData>()

    fun create(data: QuickStreamData): String {
        require(data.data.isNotEmpty()) { "Cannot open a QuickStream with no chapters." }
        val id = UUID.randomUUID().toString()
        sessions[id] = data
        if (sessions.size > MAX_SESSIONS) {
            sessions.keys.firstOrNull { it != id }?.let(sessions::remove)
        }
        return id
    }

    fun get(id: String?): QuickStreamData? =
        id?.let(sessions::get)

    fun release(id: String?) {
        if (!id.isNullOrBlank()) sessions.remove(id)
    }

    internal fun clearForTests() {
        sessions.clear()
    }
}
