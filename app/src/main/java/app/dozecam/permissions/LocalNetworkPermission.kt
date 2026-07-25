package app.dozecam.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Local-network access, which Android 17 (API 37) split out of `INTERNET`:
 * apps targeting 37 or higher must hold `ACCESS_LOCAL_NETWORK`, and until the
 * user grants it every connection to a LAN address is dropped — surfacing as a
 * connect timeout rather than a permission error, so it does not look like a
 * permission problem at all.
 *
 * Dozecam only ever talks to the LAN, so this gates the console, the video
 * stream and the wake-on-sound monitor alike.
 */
object LocalNetworkPermission {

    val name: String = Manifest.permission.ACCESS_LOCAL_NETWORK

    /** Older releases grant local-network access implicitly with `INTERNET`. */
    fun isRequired(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= ENFORCED_SDK

    fun isGranted(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        !isRequired(sdkInt) ||
            ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED

    // Android 17. The SDK this app compiles against has no named constant yet.
    private const val ENFORCED_SDK = 37
}
