package app.dozecam.permissions

import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class LocalNetworkPermissionRequestTest {
    @Test
    fun `observing permission state never launches a prompt`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()
        LocalNetworkPermissionRequest(activity)
        controller.setup()

        assertNull(shadowOf(activity).lastRequestedPermission)
        assertNull(shadowOf(activity).nextStartedActivity)
        controller.pause().stop().destroy()
    }

    @Test
    fun `checklist refusal stays inline and next explicit tap opens app settings`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()
        val request = LocalNetworkPermissionRequest(activity)
        controller.setup()

        request.requestFromChecklist()
        val prompt = shadowOf(activity).lastRequestedPermission
        assertNotNull(prompt)
        assertArrayEquals(arrayOf(LocalNetworkPermission.name), prompt.requestedPermissions)
        assertEquals(
            "android.content.pm.action.REQUEST_PERMISSIONS",
            shadowOf(activity).nextStartedActivity.action,
        )
        activity.onRequestPermissionsResult(
            prompt.requestCode,
            prompt.requestedPermissions,
            intArrayOf(PackageManager.PERMISSION_DENIED),
        )

        assertFalse(request.granted.value)
        assertNull("The checklist already explains the refusal", request.denial.value)
        assertNull("A refusal must not navigate automatically", shadowOf(activity).nextStartedActivity)

        request.requestFromChecklist()
        val settings = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settings.action)
        assertEquals(activity.packageName, settings.data?.schemeSpecificPart)
        assertNull(request.denial.value)
        controller.pause().stop().destroy()
    }

    @Test
    fun `granting a checklist request updates access without an app dialog`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()
        val request = LocalNetworkPermissionRequest(activity)
        controller.setup()

        request.requestFromChecklist()
        val prompt = shadowOf(activity).lastRequestedPermission
        assertEquals(
            "android.content.pm.action.REQUEST_PERMISSIONS",
            shadowOf(activity).nextStartedActivity.action,
        )
        activity.onRequestPermissionsResult(
            prompt.requestCode,
            prompt.requestedPermissions,
            intArrayOf(PackageManager.PERMISSION_GRANTED),
        )

        assertTrue(request.granted.value)
        assertNull(request.denial.value)
        assertNull(shadowOf(activity).nextStartedActivity)
        controller.pause().stop().destroy()
    }

    @Test
    fun `explicit legacy ask still exposes refusal for its caller`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()
        val request = LocalNetworkPermissionRequest(activity)
        controller.setup()

        request.ask()
        val prompt = shadowOf(activity).lastRequestedPermission
        assertEquals(
            "android.content.pm.action.REQUEST_PERMISSIONS",
            shadowOf(activity).nextStartedActivity.action,
        )
        activity.onRequestPermissionsResult(
            prompt.requestCode,
            prompt.requestedPermissions,
            intArrayOf(PackageManager.PERMISSION_DENIED),
        )

        assertNotNull(request.denial.value)
        assertNull(shadowOf(activity).nextStartedActivity)
        controller.pause().stop().destroy()
    }
}
