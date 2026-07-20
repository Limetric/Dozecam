package app.dozecam

import android.app.Application
import android.content.Context
import app.dozecam.data.StreamSettingsRepository

class DozecamApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(context: Context) {
    val streamSettings = StreamSettingsRepository(context)
}

val Context.appContainer: AppContainer
    get() = (applicationContext as DozecamApp).container
