package com.lagradost.quicknovel

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.quicknovel.util.Apis
import com.lagradost.quicknovel.ReadActivity2
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderActivitySmokeTest {
    @Test
    fun quickStreamLaunchRendersAChapterFromItsRegisteredProvider() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val provider = object : MainAPI() {
            override val name = "QuickNovelReaderSmoke"
            override val mainUrl = "https://reader-smoke.invalid"

            override suspend fun loadHtml(url: String): String =
                "<p>Reader smoke chapter body</p>"
        }
        val bookTitle = "Reader smoke ${System.currentTimeMillis()}"
        val sessionId = QuickStreamSessionStore.create(
            QuickStreamData(
                meta = QuickStreamMetaData(
                    author = "Smoke Test",
                    name = bookTitle,
                    apiName = provider.name,
                ),
                poster = null,
                data = mutableListOf(
                    ChapterData(
                        name = "Chapter 1",
                        url = "https://reader-smoke.invalid/chapter-1",
                        dateOfRelease = null,
                    )
                ),
            )
        )
        Apis.testApisOverride = listOf(provider)

        val intent = Intent(appContext, ReadActivity2::class.java).apply {
            putExtra(QUICK_STREAM_SESSION_EXTRA, sessionId)
            type = "quickstream"
        }

        try {
            ActivityScenario.launch<ReadActivity2>(intent).use { scenario ->
                val deadline = SystemClock.uptimeMillis() + 20_000L
                var rendered = false
                while (!rendered && SystemClock.uptimeMillis() < deadline) {
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    scenario.onActivity { activity ->
                        val recycler = activity.findViewById<RecyclerView>(R.id.real_text)
                        if (recycler != null) {
                            for (index in 0 until recycler.childCount) {
                                if (containsText(recycler.getChildAt(index), "Reader smoke chapter body")) {
                                    rendered = true
                                    break
                                }
                            }
                        }
                    }
                    if (!rendered) SystemClock.sleep(100)
                }
                assertTrue(
                    "QuickStream should render provider chapter content in the reader",
                    rendered,
                )
            }
        } finally {
            Apis.testApisOverride = null
            QuickStreamSessionStore.release(sessionId)
        }
    }

    private fun containsText(view: View, expected: String): Boolean = when (view) {
        is TextView -> view.text?.contains(expected) == true
        is ViewGroup -> (0 until view.childCount).any { index ->
            containsText(view.getChildAt(index), expected)
        }
        else -> false
    }
}
