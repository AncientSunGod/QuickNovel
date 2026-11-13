package com.lagradost.quicknovel

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderProgressTest {
    @Test
    fun progressUsesCharacterPositionWithinEachChapter() {
        assertEquals(0, calculateReaderProgress(0, 4, 0, 100))
        assertEquals(63, calculateReaderProgress(0, 4, 25, 100))
        assertEquals(250, calculateReaderProgress(1, 4, 0, 100))
        assertEquals(313, calculateReaderProgress(1, 4, 25, 100))
    }

    @Test
    fun progressIsContinuousAcrossChapterBoundaries() {
        val endOfFirstChapter = calculateReaderProgress(0, 2, 100, 100)
        val startOfSecondChapter = calculateReaderProgress(1, 2, 0, 100)
        assertEquals(500, endOfFirstChapter)
        assertEquals(endOfFirstChapter, startOfSecondChapter)
    }

    @Test
    fun progressClampsInvalidPositionsAndChapterIndexes() {
        assertEquals(0, calculateReaderProgress(-4, 5, -20, 100))
        assertEquals(1000, calculateReaderProgress(9, 5, 200, 100))
        assertEquals(0, calculateReaderProgress(0, 0, 50, 100))
        assertEquals(250, calculateReaderProgress(1, 4, 50, 0))
    }
}
