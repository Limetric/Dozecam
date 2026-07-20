package app.dozecam.data

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** Single app-wide preferences store; repositories receive it via [app.dozecam.AppContainer]. */
fun Context.dozecamDataStore() = settingsDataStore
