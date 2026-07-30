package app.dozecam.monitoring

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * This is the one part of the alarm that changes something belonging to the
 * user, so the tests are mostly about what it declines to touch — and about it
 * always giving back what it took, including after a process death.
 */
@RunWith(RobolectricTestRunner::class)
class AlertDndTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val manager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private fun grantAccess(granted: Boolean) {
        shadowOf(manager).setNotificationPolicyAccessGranted(granted)
    }

    private fun setFilter(filter: Int) {
        grantAccess(true)
        manager.setInterruptionFilter(filter)
    }

    @Test
    fun `total silence is the one mode it steps around`() {
        setFilter(NotificationManager.INTERRUPTION_FILTER_NONE)

        AlertDnd(context).beginBypass(enabled = true)

        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_ALARMS,
            manager.currentInterruptionFilter,
        )
    }

    /**
     * Alarm-usage audio already passes every other filter, so touching Do Not
     * Disturb in those modes would be a change made for nothing.
     */
    @Test
    fun `an ordinary priority filter is left exactly as the user set it`() {
        setFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)

        AlertDnd(context).beginBypass(enabled = true)

        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            manager.currentInterruptionFilter,
        )
    }

    @Test
    fun `nothing is touched when the setting is off`() {
        setFilter(NotificationManager.INTERRUPTION_FILTER_NONE)

        AlertDnd(context).beginBypass(enabled = false)

        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_NONE,
            manager.currentInterruptionFilter,
        )
    }

    @Test
    fun `nothing is touched without the grant`() {
        setFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        grantAccess(false)

        AlertDnd(context).beginBypass(enabled = true)

        grantAccess(true)
        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_NONE,
            manager.currentInterruptionFilter,
        )
    }

    @Test
    fun `the mode the user chose is put back when the alarm ends`() {
        setFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        val dnd = AlertDnd(context)

        dnd.beginBypass(enabled = true)
        dnd.endBypass()

        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_NONE,
            manager.currentInterruptionFilter,
        )
    }

    @Test
    fun `ending a bypass that never began changes nothing`() {
        setFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)

        AlertDnd(context).endBypass()

        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            manager.currentInterruptionFilter,
        )
    }

    /**
     * The reason the prior mode is written to disk before it is changed: a
     * process killed mid-alarm must not leave someone's phone permanently out of
     * the mode they put it in.
     */
    @Test
    fun `a process killed mid-alarm restores the mode on the next start`() {
        setFilter(NotificationManager.INTERRUPTION_FILTER_NONE)

        // The instance that raised the bypass then dies without ending it.
        AlertDnd(context).beginBypass(enabled = true)
        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_ALARMS,
            manager.currentInterruptionFilter,
        )

        // A fresh process, reading the record the dead one left behind.
        AlertDnd(context).endBypass()

        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_NONE,
            manager.currentInterruptionFilter,
        )
    }

    /**
     * The other direction, and the worse one. A record left behind by a process
     * death is only good while nothing else has touched the filter: writing it
     * back after the user has moved on would drop them into total silence and
     * mute their calls and their own alarm clock.
     */
    @Test
    fun `a stale record does not force the phone back into total silence`() {
        setFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        AlertDnd(context).beginBypass(enabled = true)

        // Morning: the user takes the phone out of Do Not Disturb themselves,
        // with the dead process's record still on disk.
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)

        AlertDnd(context).endBypass()

        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_ALL,
            manager.currentInterruptionFilter,
        )
    }

    /** And having been found void once, it must not be waiting to fire later. */
    @Test
    fun `a void record is dropped rather than kept for the next start`() {
        setFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        AlertDnd(context).beginBypass(enabled = true)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        AlertDnd(context).endBypass()

        // Back into total silence by the user's own hand, then another start.
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        AlertDnd(context).endBypass()

        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_ALARMS,
            manager.currentInterruptionFilter,
        )
    }

    /**
     * Revoking the grant mid-alarm leaves us owing a mode we cannot give back
     * yet. The debt has to survive until we can.
     */
    @Test
    fun `a restore blocked by a revoked grant is retried once access returns`() {
        setFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        val dnd = AlertDnd(context)
        dnd.beginBypass(enabled = true)

        grantAccess(false)
        dnd.endBypass()

        grantAccess(true)
        dnd.endBypass()

        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_NONE,
            manager.currentInterruptionFilter,
        )
    }

    @Test
    fun `restoring twice does not put the phone back into total silence`() {
        setFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        val dnd = AlertDnd(context)
        dnd.beginBypass(enabled = true)
        dnd.endBypass()

        // The user has since chosen priority-only for themselves.
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        dnd.endBypass()

        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            manager.currentInterruptionFilter,
        )
    }
}
