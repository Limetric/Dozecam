package app.dozecam.audio

import app.dozecam.data.DetectorSettings
import app.dozecam.testing.Fixtures
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The level timelines live in `shared/fixtures/sound-detector/detector.json`,
 * one case per test, so the iOS detector is held to the same trigger and
 * re-arm points.
 */
class SoundDetectorTest {

    @Serializable
    private data class Settings(val threshold: Float, val sustainMs: Long, val quietMs: Long) {
        fun toDetectorSettings() = DetectorSettings(threshold = threshold, sustainMs = sustainMs, quietMs = quietMs)
    }

    @Serializable
    private data class Sample(
        val newSettings: Settings? = null,
        val atMs: Long,
        val rms: Float,
        val triggers: Boolean,
        val phase: String? = null,
    )

    @Serializable
    private data class Case(val name: String, val settings: Settings, val samples: List<Sample>)

    @Serializable
    private data class Fixture(val cases: List<Case>)

    private val cases = Fixtures.decode<Fixture>("sound-detector/detector.json").cases

    private fun phaseOf(value: String) = when (value) {
        "armed" -> SoundDetector.Phase.ARMED
        "building" -> SoundDetector.Phase.BUILDING
        "triggered" -> SoundDetector.Phase.TRIGGERED
        else -> error("unknown phase \"$value\"")
    }

    private fun play(name: String) {
        val case = cases.singleOrNull { it.name == name } ?: error("no fixture case \"$name\"")
        val detector = SoundDetector(case.settings.toDetectorSettings())
        case.samples.forEach { sample ->
            sample.newSettings?.let { detector.updateSettings(it.toDetectorSettings()) }
            val at = "${case.name}: rms ${sample.rms} at ${sample.atMs}ms"
            assertEquals("$at triggers", sample.triggers, detector.onLevel(sample.rms, sample.atMs))
            sample.phase?.let { assertEquals("$at phase", phaseOf(it), detector.phase) }
        }
    }

    @Test
    fun `sustained loud sound triggers exactly once`() = play("sustained loud sound triggers exactly once")

    @Test
    fun `a short thud does not trigger`() = play("a short thud does not trigger")

    @Test
    fun `quiet levels below threshold never trigger`() = play("quiet levels below threshold never trigger")

    @Test
    fun `re-arms only after the full quiet period`() = play("re-arms only after the full quiet period")

    @Test
    fun `loud sound during the quiet period restarts the quiet timer`() =
        play("loud sound during the quiet period restarts the quiet timer")

    @Test
    fun `updated settings apply to subsequent samples`() = play("updated settings apply to subsequent samples")
}
