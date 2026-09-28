package app.dozecam.monitoring

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ReadinessRemediesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun actions(remedy: ReadinessRemedy): List<String?> =
        ReadinessRemedies.intents(context, remedy).map { it.action }

    @Test
    fun `a disabled channel opens the channel itself, not a page listing several`() {
        val first = ReadinessRemedies.intents(context, ReadinessRemedy.NOTIFICATION_SETTINGS).first()

        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, first.action)
        assertEquals(
            MonitoringNotifications.ALERT_CHANNEL_ID,
            first.getStringExtra(Settings.EXTRA_CHANNEL_ID),
        )
        assertEquals(context.packageName, first.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun `battery optimisation goes to the list, which needs no restricted permission`() {
        // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS would need a permission
        // Google Play restricts, and an app that cannot be published keeps
        // nobody's night.
        assertEquals(
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            actions(ReadinessRemedy.BATTERY_SETTINGS).first(),
        )
    }

    @Test
    @Config(sdk = [34])
    fun `full-screen-intent access opens the screen that holds the switch`() {
        val first =
            ReadinessRemedies.intents(context, ReadinessRemedy.FULL_SCREEN_INTENT_SETTINGS).first()

        assertEquals(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, first.action)
        assertEquals(context.packageName, first.data?.schemeSpecificPart)
    }

    @Test
    @Config(sdk = [33])
    fun `there is nowhere to send a phone with no full-screen-intent gate`() {
        assertEquals(
            emptyList<String?>(),
            actions(ReadinessRemedy.FULL_SCREEN_INTENT_SETTINGS),
        )
    }

    /**
     * The page that turns the mode off leads. Priority exceptions edit what
     * gets through a filter, which is no help at all to someone whose phone is
     * on total silence — the one Do Not Disturb state this check reports.
     */
    @Test
    fun `Do Not Disturb leads with the page that can switch it off`() {
        assertEquals(
            listOf(
                "android.settings.ZEN_MODE_SETTINGS",
                Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS,
                Settings.ACTION_SOUND_SETTINGS,
            ),
            actions(ReadinessRemedy.DO_NOT_DISTURB_SETTINGS),
        )
    }

    @Test
    fun `every system remedy ends somewhere every Android has`() {
        // The last resort is this app's own details page, from which all of
        // these are reachable by hand.
        listOf(ReadinessRemedy.NOTIFICATION_SETTINGS, ReadinessRemedy.BATTERY_SETTINGS)
            .forEach { remedy ->
                assertEquals(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    actions(remedy).last(),
                )
            }
    }

    @Test
    fun `the remedies the app carries out itself send nobody anywhere`() {
        listOf(
            ReadinessRemedy.NONE,
            ReadinessRemedy.REQUEST_NOTIFICATIONS,
            ReadinessRemedy.TURN_ALERTS_ON,
            ReadinessRemedy.TURN_CHIME_ON,
            ReadinessRemedy.START_MONITORING,
            ReadinessRemedy.CAMERA_SETTINGS,
        ).forEach { remedy ->
            assertTrue(remedy.name, ReadinessRemedies.intents(context, remedy).isEmpty())
        }
    }
}
