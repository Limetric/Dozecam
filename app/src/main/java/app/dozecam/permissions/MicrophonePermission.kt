package app.dozecam.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * The microphone, wanted for one thing: talking back to a camera.
 *
 * Unlike [LocalNetworkPermission] this gates a feature rather than the app, so
 * it is asked for at the moment somebody first reaches for it and never at
 * startup. A baby monitor that opens by asking for the microphone has some
 * explaining to do; one that asks when you hold a button marked "talk" does
 * not.
 */
object MicrophonePermission {

    val name: String = Manifest.permission.RECORD_AUDIO

    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
}
