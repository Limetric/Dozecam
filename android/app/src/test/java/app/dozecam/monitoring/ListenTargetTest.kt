package app.dozecam.monitoring

import app.dozecam.testing.Fixtures
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Test

/** The truth tables live in `shared/fixtures/listen-target/`, one file per rule. */
class ListenTargetTest {

    @Serializable
    private data class AloudCase(
        val name: String,
        val requested: Boolean,
        val speakerGranted: Boolean,
        val viewerAudible: Boolean,
        val monitored: List<String>,
        val expected: Set<String>,
    )

    @Serializable
    private data class AlertCase(val name: String, val cameraId: String, val aloud: Set<String>, val expected: Boolean)

    @Serializable
    private data class HeardCase(val name: String, val aloud: Set<String>, val mediaSilenced: Boolean, val expected: Set<String>)

    @Serializable
    private data class YieldsCase(
        val name: String,
        val cameraId: String,
        val aloud: Set<String>,
        val alarmingCameraId: String? = null,
        val expected: Boolean,
    )

    @Serializable
    private data class Fixture<T>(val cases: List<T>)

    private val aloud = Fixtures.decode<Fixture<AloudCase>>("listen-target/aloud.json").cases
    private val wakesScreen = Fixtures.decode<Fixture<AlertCase>>("listen-target/alert-wakes-screen.json").cases
    private val sounds = Fixtures.decode<Fixture<AlertCase>>("listen-target/alert-sounds.json").cases
    private val heard = Fixtures.decode<Fixture<HeardCase>>("listen-target/heard.json").cases
    private val yields = Fixtures.decode<Fixture<YieldsCase>>("listen-target/alert-yields.json").cases

    private fun <T> List<T>.case(name: String, nameOf: (T) -> String): T =
        singleOrNull { nameOf(it) == name } ?: error("no fixture case \"$name\"")

    private fun checkAloud(name: String) {
        val case = aloud.case(name) { it.name }
        assertEquals(
            case.name,
            case.expected,
            ListenTarget.of(case.requested, case.speakerGranted, case.viewerAudible, case.monitored),
        )
    }

    private fun checkWakesScreen(vararg names: String) = names.forEach { name ->
        val case = wakesScreen.case(name) { it.name }
        assertEquals(case.name, case.expected, ListenTarget.alertWakesScreen(case.cameraId, case.aloud))
    }

    private fun checkSounds(vararg names: String) = names.forEach { name ->
        val case = sounds.case(name) { it.name }
        assertEquals(case.name, case.expected, ListenTarget.alertSounds(case.cameraId, case.aloud))
    }

    private fun checkHeard(vararg names: String) = names.forEach { name ->
        val case = heard.case(name) { it.name }
        assertEquals(case.name, case.expected, ListenTarget.heard(case.aloud, case.mediaSilenced))
    }

    private fun checkYields(vararg names: String) = names.forEach { name ->
        val case = yields.case(name) { it.name }
        assertEquals(case.name, case.expected, ListenTarget.alertYields(case.cameraId, case.aloud, case.alarmingCameraId))
    }

    @Test
    fun `every room the monitor can hear plays, together`() = checkAloud("every room the monitor can hear plays, together")

    @Test
    fun `nothing asked for is nothing played`() = checkAloud("nothing asked for is nothing played")

    // Every camera switched off, or gone with the console that issued it.
    @Test
    fun `a house with nothing monitored has nothing to play`() =
        checkAloud("a house with nothing monitored has nothing to play")

    // The ask stands — a call has the speaker, not the user's mind.
    @Test
    fun `losing the speaker silences it without waiting for the switch`() =
        checkAloud("losing the speaker silences it without waiting for the switch")

    // Otherwise the same nursery comes out of one speaker twice, a second or
    // so apart.
    @Test
    fun `listen mode stands down while the viewer is making noise`() =
        checkAloud("listen mode stands down while the viewer is making noise")

    // Whoever switched listen mode on is being told about that room
    // continuously; lighting a bedroom at 3am on top of it wakes the parent
    // who is already listening, and the one beside them.
    @Test
    fun `the only room playing aloud needs no naming`() =
        checkWakesScreen("the only room playing aloud needs no naming")

    // A cry out of a mix of rooms does not say whose it was, and the name is
    // the one thing the speaker cannot supply.
    @Test
    fun `one room among several is named on screen`() = checkWakesScreen("one room among several is named on screen")

    @Test
    fun `a room nobody can hear always wakes the screen`() = checkWakesScreen(
        "a room nobody can hear wakes the screen while another plays",
        "a room nobody can hear wakes the screen with nothing aloud",
    )

    // Whoever switched the speaker on is awake and hearing the cry itself;
    // the alarm is for a person whose eyes are shut.
    @Test
    fun `a room playing aloud does not sound the alarm`() = checkSounds(
        "the only room playing aloud does not sound the alarm",
        "a room playing aloud among several does not sound the alarm",
    )

    // Decoding is not hearing: at volume zero the speaker is saying nothing,
    // so nothing may be withheld on its account.
    @Test
    fun `a mix playing into a silenced media stream is heard by nobody`() = checkHeard(
        "a mix playing into a silenced media stream is heard by nobody",
        "a mix playing into an audible media stream is heard",
    )

    // One alert card; clearing it acknowledges the alarm. B, being heard, must
    // not paper over A, which is not.
    @Test
    fun `a withheld alert does not displace a sounding alarm for another room`() =
        checkYields("a withheld alert does not displace a sounding alarm for another room")

    @Test
    fun `an alert that sounds, or names the alarm's own room, is never withheld`() = checkYields(
        "an alert that sounds is never withheld",
        "an alert for the alarm's own room is never withheld",
        "with no alarm sounding nothing is withheld",
    )

    // With nothing aloud — or this room dropped from the mix by a lost speaker
    // or a downed stream — nobody is hearing it, so it must wake.
    @Test
    fun `a room nobody can hear sounds the alarm`() = checkSounds(
        "a room nobody can hear sounds the alarm with nothing aloud",
        "a room dropped from the mix sounds the alarm",
    )
}
