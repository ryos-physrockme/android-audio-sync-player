package io.github.ryosphysrockme.audiosync

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Confirms the player's speed API can be used on an Android emulator without
 * invoking seekTo() or explicitly pausing playback. Audible continuity is an
 * independent physical-device requirement, not established by this test.
 */
@RunWith(AndroidJUnit4::class)
class ManualSpeedInstrumentedTest {
    @Test
    fun speedAdjustmentsKeepPlayRequestedAndRestoreUnity() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context: Context = inst.targetContext
        val file = File(context.cacheDir, "test-manual-speed.mp3")
        inst.context.assets.open("fixture-cbr.mp3").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }

        val ready = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val player = AtomicReference<LocalPlayback?>(null)

        try {
            inst.runOnMainSync {
                val playback = LocalPlayback(context)
                player.set(playback)
                playback.setDataSource(Uri.fromFile(file))
                playback.setOnPreparedListener { ready.countDown() }
                playback.setOnErrorListener { p, _, _ ->
                    failure.set(p.errorDetail)
                    ready.countDown()
                    true
                }
                playback.prepareAsync()
            }
            assertTrue("Media3 failed to prepare", ready.await(30, TimeUnit.SECONDS))
            assertTrue("Media3 error: " + failure.get(), failure.get() == null)
            inst.runOnMainSync {
                val playback = player.get()!!
                assertFalse(playback.playWhenReady)
                playback.start()
                assertTrue(playback.playWhenReady)

                playback.setPlaybackSpeed(1.05f)
                assertEquals(1.05f, playback.playbackSpeed, 0.001f)
                assertTrue(playback.playWhenReady)

                playback.setPlaybackSpeed(0.95f)
                assertEquals(0.95f, playback.playbackSpeed, 0.001f)
                assertTrue(playback.playWhenReady)

                playback.setPlaybackSpeed(1f)
                assertEquals(1f, playback.playbackSpeed, 0.001f)
                assertTrue(playback.playWhenReady)
            }
        } finally {
            inst.runOnMainSync { player.get()?.release() }
            file.delete()
        }
    }
}
