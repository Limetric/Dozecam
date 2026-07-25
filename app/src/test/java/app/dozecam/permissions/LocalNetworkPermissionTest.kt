package app.dozecam.permissions

import androidx.test.core.app.ApplicationProvider
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
}
