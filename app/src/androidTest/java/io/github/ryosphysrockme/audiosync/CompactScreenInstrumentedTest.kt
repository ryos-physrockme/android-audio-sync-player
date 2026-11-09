package io.github.ryosphysrockme.audiosync

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android UI startup smoke test. No microphone permission, audio source
 * or Internet access is needed to validate the screen structure.
 */
@RunWith(AndroidJUnit4::class)
class CompactScreenInstrumentedTest {
    private fun allViews(root: View): List<View> {
        val result = mutableListOf(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) result.addAll(allViews(root.getChildAt(i)))
        }
        return result
    }

    @Test
    fun syncActionPlayerAndCollapsedSettingsAreAvailableOnLaunch() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                val widgets = allViews(decor)
                val buttons = widgets.filterIsInstance<Button>()
                val labels = widgets.filterIsInstance<TextView>()

                assertTrue(buttons.any { it.text.toString() == "変更" })
                val sync = buttons.single { it.text.toString() == "同期を開始" }
                assertTrue("Cannot sync before selecting a file", !sync.isEnabled)

                for (label in listOf("−100", "−50", "+50", "+100")) {
                    assertEquals(1, buttons.count { it.text.toString() == label })
                }
                assertTrue(labels.any { it.text.toString() == "補正 +0 ms" })
                assertTrue(buttons.any { it.text.toString() == "再生" })

                val detailButton = buttons.single {
                    it.text.toString().contains("詳細設定・診断")
                }
                assertTrue("Details should be collapsed initially",
                    labels.none {
                        it.isShown &&
                            it.text.toString().contains("再生開始の一致度閾値")
                    })
                detailButton.performClick()

                assertTrue("Threshold should appear when details are expanded",
                    labels.any {
                        it.isShown &&
                            it.text.toString().contains("再生開始の一致度閾値")
                    })
                assertNotNull("Adaptive app icon must exist",
                    activity.packageManager.getApplicationIcon(activity.packageName))
            }
        } finally {
            scenario.close()
        }
    }
}
