package app.dozecam.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {

    private val volume = SettingSearchEntry(
        id = "alert-volume",
        category = SettingsCategory.ALERTS,
        label = "Alert volume: 80% of your alarm volume",
    )
    private val chime = SettingSearchEntry(
        id = "chime",
        category = SettingsCategory.ALERTS,
        label = "Alert chime",
        description = "Play a sound when a wake alert fires",
    )
    private val nightTheme = SettingSearchEntry(
        id = "night-theme",
        category = SettingsCategory.DISPLAY,
        label = "Night theme",
        description = "Dim red palette that preserves night vision",
    )
    private val entries = listOf(volume, chime, nightTheme)

    @Test
    fun `a blank query matches nothing rather than everything`() {
        assertTrue(searchSettings("", entries).isEmpty())
        assertTrue(searchSettings("   ", entries).isEmpty())
    }

    @Test
    fun `matching ignores case`() {
        assertEquals(listOf(nightTheme), searchSettings("NIGHT", entries))
    }

    @Test
    fun `whitespace around the query is not part of it`() {
        assertEquals(listOf(nightTheme), searchSettings(" night ", entries))
    }

    @Test
    fun `descriptions are searched too`() {
        // "wake" appears only in the chime's description.
        assertEquals(listOf(chime), searchSettings("wake", entries))
    }

    @Test
    fun `label hits outrank description-only hits`() {
        // "volume" is in the volume slider's label but only implied elsewhere;
        // "sound" is in the chime's description and nobody's label.
        val hits = searchSettings("alert", entries)
        assertEquals(listOf(volume, chime), hits)

        // A query hitting one label and another description lists the label first.
        val mixed = searchSettings("sound", entries)
        assertEquals(listOf(chime), mixed)
    }

    @Test
    fun `an unmatched query returns nothing`() {
        assertTrue(searchSettings("zebra", entries).isEmpty())
    }
}
