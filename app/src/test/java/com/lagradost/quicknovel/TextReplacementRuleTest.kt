package com.lagradost.quicknovel

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.graphics.Typeface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextReplacementRuleTest {
    @Test
    fun convertsLegacyLinkedHashMapValuesWithoutUnsafeCasts() {
        val legacy = listOf(
            linkedMapOf<String, Any>("find" to "foo", "replace" to "bar"),
            linkedMapOf<String, Any>("find" to "hello", "replace" to "world"),
        )

        assertEquals(
            listOf(
                TextReplacementRule("foo", "bar"),
                TextReplacementRule("hello", "world"),
            ),
            normalizeTextReplacementRules(legacy),
        )
    }

    @Test
    fun keepsAlreadyTypedRules() {
        val rules = listOf(TextReplacementRule("old", "new"))
        assertEquals(rules, normalizeTextReplacementRules(rules))
    }

    @Test
    fun ignoresMalformedOrBlankLegacyEntries() {
        val legacy = listOf(
            mapOf("find" to "valid", "replace" to "rule"),
            mapOf("find" to "   ", "replace" to "ignored"),
            mapOf("replace" to "missing find"),
            "not a rule",
        )

        assertEquals(
            listOf(TextReplacementRule("valid", "rule")),
            normalizeTextReplacementRules(legacy),
        )
    }

    @Test
    fun appliesLiteralCorrectionsAndRebuildsChapterOffsetsWithoutDroppingStyles() {
        val originalText = SpannableStringBuilder("A foo and FOO.")
        originalText.setSpan(
            StyleSpan(Typeface.BOLD),
            2,
            5,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        val original = TextSpan(
            text = originalText,
            start = 0,
            end = originalText.length,
            index = 7,
            innerIndex = 3,
        )

        val (rendered, spans) = applyTextReplacementRules(
            rendered = originalText,
            input = arrayListOf(original),
            rules = listOf(TextReplacementRule("foo", "longer")),
        )

        assertEquals("A longer and longer.\n", rendered.toString())
        assertEquals(1, spans.size)
        assertEquals(0, spans.single().start)
        assertEquals(rendered.length - 1, spans.single().end)
        assertEquals(7, spans.single().index)
        assertEquals(3, spans.single().innerIndex)
        assertTrue(
            spans.single().text.getSpans(
                0,
                spans.single().text.length,
                StyleSpan::class.java,
            ).isNotEmpty()
        )
    }

}
