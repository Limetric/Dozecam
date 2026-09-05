package app.dozecam.monitoring

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import app.dozecam.DozecamApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The rework in issue #12: full-screen-intent access used to be asked for by
 * dropping the user into a system settings screen, unannounced, in the middle
 * of arming the monitor. A grant asked for cold is a grant refused.
 */
@RunWith(RobolectricTestRunner::class)
class MonitoringStarterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val application = context.applicationContext as Application
    private val container get() = (context.applicationContext as DozecamApp).container

    @Before
    fun grantNotifications() {
        // Not what these tests are about: without it every arm stops at the
        // notification prompt and never reaches the service.
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        container.monitoringState.explainFullScreenIntent.value = false
        shadowOf(application).clearNextStartedActivities()
    }

    /**
     * Built against an activity that has not been created yet, because an
     * activity result can only be registered before then — which is exactly the
     * contract [MonitoringStarter] documents for its own construction.
     */
    private fun starter(): MonitoringStarter =
        MonitoringStarter(Robolectric.buildActivity(ComponentActivity::class.java).get())

    @Test
    fun `arming starts monitoring`() {
        starter().startWithAlertPermissions()

        val started = shadowOf(application).nextStartedService
        assertNotNull(started)
        assertEquals(MonitoringService::class.java.name, started.component?.className)
    }

    /**
     * The bug this replaces: arming threw the user at a system screen with no
     * explanation. Nothing is launched now — the explanation is raised, and a
     * screen shows it.
     */
    @Test
    @Config(sdk = [34])
    fun `arming never launches a settings screen by itself`() {
        starter().startWithAlertPermissions()

        assertNull(
            "arming must explain before it sends anyone anywhere",
            shadowOf(application).nextStartedActivity,
        )
        // And the explanation really was raised: without it this test would
        // pass on a build that had simply stopped asking at all.
        assertTrue(container.monitoringState.explainFullScreenIntent.value)
    }

    /** And below the gate there is nothing to explain, so nothing is said. */
    @Test
    @Config(sdk = [33])
    fun `a phone with no full-screen-intent gate is not nagged about one`() {
        starter().startWithAlertPermissions()

        assertFalse(container.monitoringState.explainFullScreenIntent.value)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    /**
     * Raised where any screen can see it, not on the starter that raised it:
     * onboarding arms the monitor and finishes itself in the same breath, and a
     * dialog owned by it would never be seen.
     */
    @Test
    fun `the explanation outlives the screen that raised it`() {
        container.monitoringState.explainFullScreenIntent.value = true

        assertTrue(starter().explainFullScreenIntent.value)
    }

    @Test
    fun `reading it and going is what opens Android's screen`() {
        container.monitoringState.explainFullScreenIntent.value = true

        starter().openFullScreenIntentSettings()

        assertFalse(container.monitoringState.explainFullScreenIntent.value)
    }

    /** "Not now" leaves the monitor running and the card in settings to say it. */
    @Test
    fun `declining sends nobody anywhere`() {
        container.monitoringState.explainFullScreenIntent.value = true

        starter().dismissFullScreenIntentExplanation()

        assertFalse(container.monitoringState.explainFullScreenIntent.value)
        assertNull(shadowOf(application).nextStartedActivity)
    }
}
