package app.dozecam.protect

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SecurePrefsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `fallback entries fold into the target and the fallback is cleared`() {
        val target = context.getSharedPreferences("target", Context.MODE_PRIVATE)
        val fallback = context.getSharedPreferences("fallback", Context.MODE_PRIVATE)
        fallback.edit()
            .putString("cameras", "[{}]")
            .putString("active_monitoring_url", "rtsp://cam:7447/a")
            .commit()

        reconcileFallback(target, fallback)

        assertEquals("[{}]", target.getString("cameras", null))
        assertEquals("rtsp://cam:7447/a", target.getString("active_monitoring_url", null))
        assertTrue(fallback.all.isEmpty())
    }

    @Test
    fun `an empty fallback leaves the target untouched`() {
        val target = context.getSharedPreferences("target2", Context.MODE_PRIVATE)
        target.edit().putString("cameras", "existing").commit()
        val fallback = context.getSharedPreferences("fallback2", Context.MODE_PRIVATE)

        reconcileFallback(target, fallback)

        assertEquals("existing", target.getString("cameras", null))
    }

    @Test
    fun `fallback entries written during a degraded run win conflicts`() {
        // Fallback data is strictly newer: it can only be written while
        // encryption is down, and every healthy startup empties the file.
        val target = context.getSharedPreferences("target3", Context.MODE_PRIVATE)
        target.edit().putString("cameras", "old-encrypted-list").commit()
        val fallback = context.getSharedPreferences("fallback3", Context.MODE_PRIVATE)
        fallback.edit()
            .putString("cameras", "degraded-run-list")
            .putString("other", "value")
            .commit()

        reconcileFallback(target, fallback)

        assertEquals("degraded-run-list", target.getString("cameras", null))
        assertEquals("value", target.getString("other", null))
        assertTrue(fallback.all.isEmpty())
    }
}
