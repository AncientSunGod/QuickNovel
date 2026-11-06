package com.lagradost.quicknovel

import kotlin.math.roundToInt

/**
 * Converts a character offset in the current chapter into a continuous 0..1000
 * whole-book progress value. Keeping this independent of RecyclerView positions
 * avoids jumps when the reader replaces its bounded set of adapter rows.
 */
internal fun calculateReaderProgress(
    chapterIndex: Int,
    chapterCount: Int,
    characterPosition: Int,
    chapterLength: Int,
): Int {
    if (chapterCount <= 0) return 0

    val safeChapterIndex = chapterIndex.coerceIn(0, chapterCount - 1)
    val chapterFraction = if (chapterLength <= 0) {
        0f
    } else {
        characterPosition.coerceIn(0, chapterLength).toFloat() / chapterLength.toFloat()
    }
    val bookFraction = (safeChapterIndex + chapterFraction) / chapterCount.toFloat()
    return (bookFraction * 1000f).roundToInt().coerceIn(0, 1000)
}
