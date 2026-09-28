package app.dozecam.audio

import app.dozecam.data.DetectorSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundDetectorTest {

    private val settings = DetectorSettings(threshold = 0.1f, sustainMs = 1_500, quietMs = 10_000)

    @Test
    fun `sustained loud sound triggers exactly once`() {
        val detector = SoundDetector(settings)

        assertFalse(detector.onLevel(0.3f, 0))
        assertFalse(detector.onLevel(0.3f, 1_000))
        assertTrue(detector.onLevel(0.3f, 1_500))
        assertEquals(SoundDetector.Phase.TRIGGERED, detector.phase)
        // Still loud: no repeat trigger while in the refractory phase.
        assertFalse(detector.onLevel(0.3f, 2_000))
    }

    @Test
    fun `a short thud does not trigger`() {
        val detector = SoundDetector(settings)

        assertFalse(detector.onLevel(0.5f, 0))
        assertFalse(detector.onLevel(0.02f, 400)) // dropped pacifier, then silence
        assertEquals(SoundDetector.Phase.ARMED, detector.phase)
        assertFalse(detector.onLevel(0.5f, 1_000))
        assertFalse(detector.onLevel(0.5f, 2_000))
        // Building restarted at t=1000; sustain completes at 2500, not before.
        assertTrue(detector.onLevel(0.5f, 2_500))
    }

    @Test
    fun `quiet levels below threshold never trigger`() {
        val detector = SoundDetector(settings)

        for (t in 0..20_000L step 250) {
            assertFalse(detector.onLevel(0.05f, t))
        }
        assertEquals(SoundDetector.Phase.ARMED, detector.phase)
    }

    @Test
    fun `re-arms only after the full quiet period`() {
        val detector = SoundDetector(settings)
        detector.onLevel(0.3f, 0)
        assertTrue(detector.onLevel(0.3f, 1_500))

        // Quiet starts at 2000; not yet re-armed at 11_999.
        assertFalse(detector.onLevel(0.02f, 2_000))
        assertFalse(detector.onLevel(0.02f, 11_999))
        assertEquals(SoundDetector.Phase.TRIGGERED, detector.phase)

        // Quiet for the full 10s: re-armed.
        assertFalse(detector.onLevel(0.02f, 12_000))
        assertEquals(SoundDetector.Phase.ARMED, detector.phase)

        // And a new sustained sound can trigger again.
        detector.onLevel(0.3f, 13_000)
        assertTrue(detector.onLevel(0.3f, 14_500))
    }

    @Test
    fun `loud sound during the quiet period restarts the quiet timer`() {
        val detector = SoundDetector(settings)
        detector.onLevel(0.3f, 0)
        assertTrue(detector.onLevel(0.3f, 1_500))

        assertFalse(detector.onLevel(0.02f, 2_000)) // quiet begins
        assertFalse(detector.onLevel(0.4f, 8_000)) // baby stirs: timer resets
        assertFalse(detector.onLevel(0.02f, 12_500)) // quiet restarts here
        assertEquals(SoundDetector.Phase.TRIGGERED, detector.phase)
        assertFalse(detector.onLevel(0.02f, 22_500))
        assertEquals(SoundDetector.Phase.ARMED, detector.phase)
    }

    @Test
    fun `updated settings apply to subsequent samples`() {
        val detector = SoundDetector(settings)

        detector.updateSettings(settings.copy(threshold = 0.4f))
        detector.onLevel(0.3f, 0) // below the new threshold
        assertEquals(SoundDetector.Phase.ARMED, detector.phase)

        detector.onLevel(0.5f, 1_000)
        assertEquals(SoundDetector.Phase.BUILDING, detector.phase)
        assertTrue(detector.onLevel(0.5f, 2_500))
    }
}
