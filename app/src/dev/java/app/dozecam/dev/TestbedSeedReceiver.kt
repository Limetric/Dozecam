package app.dozecam.dev

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.dozecam.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Adds cameras over adb so an agent can point the dev build at the local RTSP
 * testbed without driving the settings UI:
 *
 * ```
 * adb shell am broadcast \
 *   -a app.dozecam.dev.action.SEED_CAMERAS \
 *   -n app.dozecam.dev/app.dozecam.dev.TestbedSeedReceiver \
 *   --es cameras '[{"name":"Testbed nursery","url":"rtsp://10.0.2.2:8554/nursery"}]'
 * ```
 *
 * `tools/testbed.sh seed` sends exactly this. Dev flavor only.
 */
class TestbedSeedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val cameras = TestbedSeed.parse(intent.getStringExtra(EXTRA_CAMERAS).orEmpty())
        if (cameras.isEmpty()) {
            Log.w(TAG, "Nothing seeded: no valid cameras in '$EXTRA_CAMERAS' extra")
            return
        }
        // upsert is durable-before-return, so the broadcast must stay alive
        // past onReceive while the writes run off the main thread. goAsync()
        // is a platform type and actually null when the receiver is invoked
        // directly rather than through a broadcast (Robolectric tests).
        val pending: PendingResult? = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = context.appContainer.cameras
                cameras.forEach { store.upsert(it) }
                Log.i(TAG, "Seeded ${cameras.size} camera(s): ${cameras.joinToString { it.name }}")
            } finally {
                pending?.finish()
            }
        }
    }

    companion object {
        const val EXTRA_CAMERAS = "cameras"
        private const val TAG = "TestbedSeed"
    }
}
