package app.dozecam.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * What a refused local-network request leaves the app able to do about it.
 * Android draws no prompt at all once a permission is permanently denied, so
 * a request can come back refused without the user seeing anything — which is
 * indistinguishable from a dead control unless the app says so itself.
 */
enum class LocalNetworkDenial {
    /** Said no to a prompt that asking again will put back on screen. */
    RETRIABLE,

    /** Android will not prompt again; only its own settings can grant it now. */
    PERMANENT,
}

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

    /**
     * Which explanation a refusal calls for. Only meaningful immediately after
     * a request came back denied: Android also reports no rationale *before*
     * the first ask, where it means "never asked" rather than "will not ask
     * again", and reading it then would call a fresh install permanently
     * refused.
     */
    fun denial(activity: Activity): LocalNetworkDenial =
        denial(ActivityCompat.shouldShowRequestPermissionRationale(activity, name))

    internal fun denial(willPromptAgain: Boolean): LocalNetworkDenial =
        if (willPromptAgain) LocalNetworkDenial.RETRIABLE else LocalNetworkDenial.PERMANENT

    /** This app's own page in Android settings: where a permanent denial is undone. */
    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))

    // Android 17. The SDK this app compiles against has no named constant yet.
    private const val ENFORCED_SDK = 37
}
