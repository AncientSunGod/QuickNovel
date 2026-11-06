package com.lagradost.quicknovel

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(
            "Debug application ID should use the QuickNovel package namespace",
            appContext.packageName.startsWith("com.lagradost.quicknovel"),
        )
    }
}
