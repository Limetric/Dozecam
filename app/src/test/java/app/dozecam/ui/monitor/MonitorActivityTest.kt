package app.dozecam.ui.monitor

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MonitorActivityTest {

    @Test
    fun `finishes immediately when launched without a stream url`() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MonitorActivity::class.java)

        val controller = Robolectric.buildActivity(MonitorActivity::class.java, intent).create()

        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun `a wake alert for another camera retargets the reused activity`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val controller = Robolectric.buildActivity(
            MonitorActivity::class.java,
            MonitorActivity.intent(context, "rtsp://cam-a:7447/a"),
        ).create()

        controller.newIntent(MonitorActivity.intent(context, "rtsp://cam-b:7447/b"))

        assertEquals(
            "rtsp://cam-b:7447/b",
            controller.get().intent.getStringExtra("stream_url"),
        )
    }
}
