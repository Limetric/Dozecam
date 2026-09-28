package app.dozecam.audio

import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class MediaAudioFocusTest {

    private var viewerLost = 0
    private var listenLost = 0

    private val audioManager: AudioManager =
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(AudioManager::class.java)

    private fun focus() = MediaAudioFocus(ApplicationProvider.getApplicationContext())

    private fun MediaAudioFocus.requestViewer() =
        request(MediaAudioFocus.Client.VIEWER) { viewerLost++ }

    private fun MediaAudioFocus.requestListen() =
        request(MediaAudioFocus.Client.LISTEN) { listenLost++ }

    @Test
    fun `focus is granted and the viewer may play`() {
        val focus = focus()

        assertTrue(focus.requestViewer())
        assertTrue(focus.granted.value)
    }

    @Test
    fun `nothing plays before focus has been asked for`() {
        // The switch being on is not permission; the system's answer is.
        assertFalse(focus().granted.value)
    }

    @Test
    fun `the system is asked to say when it ducks us`() {
        focus().requestViewer()

        // Without this the framework quietly ducks the stream itself and never
        // calls the listener, so the duck handling below would be dead code.
        val request = shadowOf(audioManager).lastAudioFocusRequest.audioFocusRequest
        assertTrue(request.willPauseWhenDucked())
    }

    @Test
    fun `a passing interruption silences without ending the sound`() {
        val focus = focus()
        focus.requestViewer()

        focus.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        assertFalse(focus.granted.value)
        // The user's switch is theirs; a call coming in does not get to flip it.
        assertEquals(0, viewerLost)
    }

    @Test
    fun `sound comes back on its own when the interruption ends`() {
        val focus = focus()
        focus.requestViewer()
        focus.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        focus.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertTrue(focus.granted.value)
        assertEquals(0, viewerLost)
    }

    @Test
    fun `being asked to duck is treated as silence, not as quiet`() {
        val focus = focus()
        focus.requestViewer()

        focus.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        // A room that sounds quiet under someone else's audio is
        // indistinguishable from a quiet room, which is the one mistake a baby
        // monitor cannot make.
        assertFalse(focus.granted.value)
    }

    @Test
    fun `losing focus for good silences immediately and ends the sound`() {
        val focus = focus()
        focus.requestViewer()

        focus.onFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        // Silenced on the spot rather than once a stored setting has come back
        // round: that gap is what headphones being pulled out cannot have.
        assertFalse(focus.granted.value)
        assertEquals(1, viewerLost)
    }

    @Test
    fun `a gain after letting go does not hand the speaker back`() {
        val focus = focus()
        focus.requestViewer()
        focus.release(MediaAudioFocus.Client.VIEWER)

        focus.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertFalse(focus.granted.value)
    }

    @Test
    fun `releasing twice is harmless`() {
        val focus = focus()
        focus.requestViewer()

        focus.release(MediaAudioFocus.Client.VIEWER)
        focus.release(MediaAudioFocus.Client.VIEWER)

        // Losing focus releases too, and so does the viewer going away; the
        // second call must not unregister a receiver that is already gone.
        assertFalse(focus.granted.value)
    }

    @Test
    fun `requesting twice keeps the one focus it already holds`() {
        val focus = focus()

        assertTrue(focus.requestViewer())
        assertTrue(focus.requestViewer())
        assertTrue(focus.granted.value)
    }

    @Test
    fun `listen mode joining the viewer does not ask the system a second time`() {
        val focus = focus()
        focus.requestViewer()
        val first = shadowOf(audioManager).lastAudioFocusRequest

        assertTrue(focus.requestListen())

        // A second request from this same process arrives at the first as a
        // loss: two owners would mean listen mode silenced the viewer on its
        // way in, and the viewer silenced the nursery on its way back.
        assertEquals(first, shadowOf(audioManager).lastAudioFocusRequest)
        assertTrue(focus.granted.value)
    }

    @Test
    fun `the viewer letting go leaves listen mode holding the speaker`() {
        val focus = focus()
        focus.requestViewer()
        focus.requestListen()

        focus.release(MediaAudioFocus.Client.VIEWER)

        // The whole promise of listen mode is that it outlives the viewer.
        assertTrue(focus.granted.value)
        assertEquals(0, listenLost)
    }

    @Test
    fun `the last holder out abandons the focus`() {
        val focus = focus()
        focus.requestViewer()
        focus.requestListen()

        focus.release(MediaAudioFocus.Client.VIEWER)
        focus.release(MediaAudioFocus.Client.LISTEN)

        assertFalse(focus.granted.value)
    }

    @Test
    fun `losing focus for good tells every holder`() {
        val focus = focus()
        focus.requestViewer()
        focus.requestListen()

        focus.onFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        // The speaker is one device: a holder left believing it still had it
        // would show a switch that is on next to a phone that is silent.
        assertEquals(1, viewerLost)
        assertEquals(1, listenLost)
        assertFalse(focus.granted.value)
    }

    @Test
    fun `a holder releasing from inside its own loss callback is harmless`() {
        val focus = MediaAudioFocus(ApplicationProvider.getApplicationContext())
        // What both real callers do: losing the speaker turns their switch off,
        // and turning a switch off comes straight back through release().
        focus.request(MediaAudioFocus.Client.LISTEN) {
            listenLost++
            focus.release(MediaAudioFocus.Client.LISTEN)
        }

        focus.onFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        assertEquals(1, listenLost)
        assertFalse(focus.granted.value)
    }
}
