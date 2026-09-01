package app.dozecam.permissions

import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalNetworkPermissionTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `local network access is implicit before android 17`() {
        assertFalse(LocalNetworkPermission.isRequired(sdkInt = 31))
        assertFalse(LocalNetworkPermission.isRequired(sdkInt = 36))
        // No permission is held in this test, but pre-37 releases do not need one.
        assertTrue(LocalNetworkPermission.isGranted(context, sdkInt = 36))
    }

    @Test
    fun `android 17 requires an explicit grant`() {
        assertTrue(LocalNetworkPermission.isRequired(sdkInt = 37))
        assertFalse(LocalNetworkPermission.isGranted(context, sdkInt = 37))
    }

    @Test
    fun `a refusal Android will prompt about again is retriable`() {
        assertEquals(
            LocalNetworkDenial.RETRIABLE,
            LocalNetworkPermission.denial(willPromptAgain = true),
        )
    }

    @Test
    fun `a refusal Android will not prompt about again is permanent`() {
        // The state the bug lived in: the request returns denied immediately,
        // nothing is drawn, and only this tells the app to say so itself.
        assertEquals(
            LocalNetworkDenial.PERMANENT,
            LocalNetworkPermission.denial(willPromptAgain = false),
        )
    }

    @Test
    fun `the way out points at this app's own settings page`() {
        val intent = LocalNetworkPermission.appSettingsIntent(context)

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.data?.schemeSpecificPart)
    }
}
