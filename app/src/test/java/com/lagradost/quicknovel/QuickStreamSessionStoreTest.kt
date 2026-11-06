package com.lagradost.quicknovel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class QuickStreamSessionStoreTest {
    private fun sampleData(name: String) = QuickStreamData(
        meta = QuickStreamMetaData(
            author = "Test Author",
            name = name,
            apiName = "Test Provider",
        ),
        poster = null,
        data = mutableListOf(
            ChapterData(
                name = "Chapter 1",
                url = "https://example.com/chapter-1",
                dateOfRelease = null,
                views = null,
            )
        ),
    )

    @Test
    fun inProcessSessionReturnsTheExactManifestWithoutSerialization() {
        QuickStreamSessionStore.clearForTests()
        val data = sampleData("Session A")
        val id = QuickStreamSessionStore.create(data)

        assertSame(data, QuickStreamSessionStore.get(id))

        QuickStreamSessionStore.release(id)
        assertNull(QuickStreamSessionStore.get(id))
    }

    @Test
    fun sessionsAreIsolatedAndUseDifferentIds() {
        QuickStreamSessionStore.clearForTests()
        val first = sampleData("First")
        val second = sampleData("Second")

        val firstId = QuickStreamSessionStore.create(first)
        val secondId = QuickStreamSessionStore.create(second)

        assertNotEquals(firstId, secondId)
        assertSame(first, QuickStreamSessionStore.get(firstId))
        assertSame(second, QuickStreamSessionStore.get(secondId))

        QuickStreamSessionStore.release(firstId)
        assertNull(QuickStreamSessionStore.get(firstId))
        assertSame(second, QuickStreamSessionStore.get(secondId))
        QuickStreamSessionStore.release(secondId)
    }

    @Test
    fun emptyChapterManifestCannotStartAReaderSession() {
        QuickStreamSessionStore.clearForTests()
        val empty = sampleData("Empty").copy(data = mutableListOf())

        val failure = runCatching { QuickStreamSessionStore.create(empty) }.exceptionOrNull()
        assertEquals("Cannot open a QuickStream with no chapters.", failure?.message)
    }
}
