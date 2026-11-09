package com.lagradost.quicknovel

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ProgressBar
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.quicknovel.util.Apis
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderActivitySmokeTest {
    @Test
    fun quickStreamLaunchRendersAChapterFromItsRegisteredProvider() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val providerWasCalled = AtomicBoolean(false)
        val provider = object : MainAPI() {
            override val name = "QuickNovelReaderSmoke"
            override val mainUrl = "https://reader-smoke.invalid"

            override suspend fun loadHtml(url: String): String {
                providerWasCalled.set(true)
                return buildString {
                    repeat(80) { index ->
                        append("<p>Reader smoke chapter body $index. ")
                        append("A long passage for reader progress testing. ".repeat(30))
                        append("</p>")
                    }
                }
            }
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
                // Emulator cold-start and first-run setup can take longer than a normal
                // UI frame. Wait for the actual provider callback and rendered body.
                val deadline = SystemClock.uptimeMillis() + 60_000L
                var rendered = false
                var adapterItems = 0
                var readerDiagnostic = "reader views not inspected"
                while (!rendered && SystemClock.uptimeMillis() < deadline) {
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    scenario.onActivity { activity ->
                        val recycler = activity.findViewById<RecyclerView>(R.id.real_text)
                        if (recycler != null) {
                            adapterItems = recycler.adapter?.itemCount ?: 0
                            readerDiagnostic = "adapter=$adapterItems, children=${recycler.childCount}, " +
                                "recyclerVisibility=${recycler.visibility}, " +
                                "recyclerMeasured=${recycler.width}x${recycler.height}"
                            for (index in 0 until recycler.childCount) {
                                if (containsText(recycler.getChildAt(index), "Reader smoke chapter body")) {
                                    rendered = true
                                    break
                                }
                            }
                        }
                    }
                    if (!rendered) SystemClock.sleep(150)
                }
                assertTrue(
                    "QuickStream provider was never called; reader initialization did not reach chapter download",
                    providerWasCalled.get(),
                )
                assertTrue(
                    "Provider returned chapter HTML but reader did not render it ($readerDiagnostic)",
                    rendered,
                )

                var progressVisible = false
                scenario.onActivity { activity ->
                    val recycler = activity.findViewById<RecyclerView>(R.id.real_text)
                    val progress = activity.findViewById<ProgressBar>(R.id.read_overflow_progress)
                    progressVisible = progress?.visibility == View.VISIBLE
                    val lastPosition = (recycler.adapter?.itemCount ?: 1) - 1
                    if (lastPosition >= 0) recycler.scrollToPosition(lastPosition)
                    recycler.scrollBy(0, 100_000)
                }
                SystemClock.sleep(500)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                var progressAfterScroll = 0
                scenario.onActivity { activity ->
                    progressAfterScroll = activity
                        .findViewById<ProgressBar>(R.id.read_overflow_progress)
                        ?.progress ?: 0
                }
                assertTrue(
                    "Reader progress overlay stayed hidden after a chapter rendered",
                    progressVisible,
                )
                assertTrue(
                    "Reader progress did not advance after scrolling (progress=$progressAfterScroll)",
                    progressAfterScroll > 0,
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
