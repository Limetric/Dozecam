package app.dozecam.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.dozecam.data.StreamSettings
import app.dozecam.data.StreamUrlValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val settings: StreamSettings) : ViewModel() {

    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput

    val canWatch: StateFlow<Boolean> = _urlInput
        .map { StreamUrlValidator.isValid(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch {
            val saved = settings.streamUrl.first()
            if (_urlInput.value.isEmpty()) {
                _urlInput.value = saved
            }
        }
    }

    fun onUrlChange(value: String) {
        _urlInput.value = value
    }

    /** Persists the trimmed URL and returns it for immediate playback. */
    fun commitUrl(): String {
        val url = _urlInput.value.trim()
        viewModelScope.launch { settings.setStreamUrl(url) }
        return url
    }

    companion object {
        fun factory(settings: StreamSettings): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(settings) }
        }
    }
}
