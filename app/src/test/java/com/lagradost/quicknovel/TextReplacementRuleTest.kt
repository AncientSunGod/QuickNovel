package com.lagradost.quicknovel

import org.junit.Assert.assertEquals
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



}
