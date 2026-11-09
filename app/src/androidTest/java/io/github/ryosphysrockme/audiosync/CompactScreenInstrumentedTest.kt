package io.github.ryosphysrockme.audiosync

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke tests for the compact sync-first layout.
 * No microphone permission, selected audio, or network access required.
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
    fun launcherHasAnInactiveSyncButtonVisibleOffsetAndSecondaryPlayer() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                val root = content.getChildAt(0)
                val widgets = allViews(root)
                val buttons = widgets.filterIsInstance<Button>()
                val labels = widgets.filterIsInstance<TextView>()
                val sliders = widgets.filterIsInstance<SeekBar>()

                assertTrue(buttons.any { it.text.toString() == "変更" })
                val sync = buttons.single { it.text.toString() == "同期を開始" }
                assertTrue("Cannot start before selecting audio", !sync.isEnabled)

                for (label in listOf("−100", "−50", "+50", "+100")) {
                    assertEquals(1, buttons.count { it.text.toString() == label })
                }
                assertTrue(labels.any { it.text.toString() == "補正 +0 ms" })
                assertTrue(labels.any {
                    it.isShown && it.text.toString().contains("同期オフセット")
                })
                assertTrue(buttons.any { it.text.toString() == "再生" })

                val detailButton = buttons.single {
                    it.text.toString().contains("設定・診断")
                }
                assertTrue("Threshold should be collapsed initially",
                    labels.none {
                        it.isShown && it.text.toString().contains("一致度閾値")
                    })
                detailButton.performClick()
                assertTrue("Threshold should appear after expanding details",
                    labels.any {
                        it.isShown && it.text.toString().contains("一致度閾値")
                    })
                assertTrue("Seek bar and two adjustment sliders must exist",
                    sliders.size >= 3
                )
                assertNotNull(
                    "Adaptive app icon must be present",
                    activity.packageManager.getApplicationIcon(activity.packageName)
                )
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun systemBarInsetsAreAppliedToRootInsteadOfCoveringBottomPlayer() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                val root = content.getChildAt(0)
                val windowInsets = ViewCompat.getRootWindowInsets(root)
                assertNotNull("Window insets must be available", windowInsets)

                val safe = windowInsets!!.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout()
                )
                assertTrue("Top controls must clear the status bar",
                    root.paddingTop >= safe.top)
                assertTrue("Bottom player must clear the navigation bar",
                    root.paddingBottom >= safe.bottom)
                assertTrue("Left controls must clear the cutout", root.paddingLeft >= safe.left)
                assertTrue("Right controls must clear the cutout", root.paddingRight >= safe.right)
            }
        } finally {
            scenario.close()
        }
    }
}
