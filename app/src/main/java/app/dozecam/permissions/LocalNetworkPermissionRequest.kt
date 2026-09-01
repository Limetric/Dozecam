package app.dozecam.permissions

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Asking for local-network access from a screen, and owning what happens when
 * the answer is no. Shared by the viewer and settings because both offer the
 * same control — start monitoring — and both used to let a refusal pass in
 * silence, leaving the control looking broken.
 *
 * Construct during activity initialisation; it registers an activity result
 * and observes the activity's lifecycle.
 */
class LocalNetworkPermissionRequest(private val activity: ComponentActivity) {

    private val _granted = MutableStateFlow(true)

    /** Whether the app can reach the LAN right now, refreshed on every resume. */
    val granted: StateFlow<Boolean> = _granted.asStateFlow()

    private val _denial = MutableStateFlow<LocalNetworkDenial?>(null)

    /** The refusal still owed an explanation, or null when nothing is. */
    val denial: StateFlow<LocalNetworkDenial?> = _denial.asStateFlow()

    /**
     * Whether the refusal in flight is one the user asked for by tapping
     * something. Kept across process death because the system prompt is an
     * activity of its own: this one can be destroyed behind it and rebuilt to
     * take the answer, and a lost flag would take the explanation with it.
     */
    private var explainRefusal = false

    private val permission = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        _granted.value = granted
        // Nothing to explain about a yes, and an ask the user did not make —
        // the viewer's one on launch — has no tap to account for. Explaining
        // that one would mean a dialog on every cold start for as long as the
        // denial stands, which is how an explanation turns into nagging.
        _denial.value = if (granted || !explainRefusal) {
            null
        } else {
            LocalNetworkPermission.denial(activity)
        }
        explainRefusal = false
    }

    init {
        activity.savedStateRegistry.registerSavedStateProvider(SAVED_STATE_KEY) {
            Bundle().apply { putBoolean(EXPLAIN_KEY, explainRefusal) }
        }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                explainRefusal = activity.savedStateRegistry
                    .consumeRestoredStateForKey(SAVED_STATE_KEY)
                    ?.getBoolean(EXPLAIN_KEY) == true
                refresh()
            }

            // Granting from Android's own settings happens outside this app
            // entirely; coming back is the only sign that it did.
            override fun onResume(owner: LifecycleOwner) = refresh()
        })
    }

    /**
     * Asks for the grant. [explainRefusal] says whether a no is worth a dialog:
     * true when the user tapped a control that cannot work without it, false
     * for an ask the app made on its own behalf.
     */
    fun ask(explainRefusal: Boolean = true) {
        this.explainRefusal = explainRefusal
        permission.launch(LocalNetworkPermission.name)
    }

    /** Acts on the explanation being shown: ask again, or hand over to Android. */
    fun resolve() {
        val denial = _denial.value ?: return
        _denial.value = null
        when (denial) {
            LocalNetworkDenial.RETRIABLE -> ask()
            // Not every device ships an app-details screen. The dialog has
            // already said what needs turning on, so a missing one costs the
            // shortcut rather than the explanation.
            LocalNetworkDenial.PERMANENT -> runCatching {
                activity.startActivity(LocalNetworkPermission.appSettingsIntent(activity))
            }
        }
    }

    /** Dismisses the explanation without acting on it. */
    fun dismiss() {
        _denial.value = null
    }

    private fun refresh() {
        val granted = LocalNetworkPermission.isGranted(activity)
        _granted.value = granted
        if (granted) _denial.value = null
    }

    private companion object {
        const val SAVED_STATE_KEY = "local-network-permission"
        const val EXPLAIN_KEY = "explain-refusal"
    }
}
