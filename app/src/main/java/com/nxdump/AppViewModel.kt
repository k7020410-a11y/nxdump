package com.nxdump

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Screen {
    LIBRARY,
    UPDATES,
    SETTINGS,
}

class AppViewModel(
    app: Application,
) : AndroidViewModel(app) {

    private val ctx: Application
        get() = getApplication()

    val prefs = Prefs(app)

    var setupDone by mutableStateOf(prefs.setupDone)
        private set

    var screen by mutableStateOf(Screen.LIBRARY)

    var keysSummary by mutableStateOf<String?>(null)
        private set

    var titleKeyCount by mutableIntStateOf(0)
        private set

    var gamesName by mutableStateOf<String?>(null)
        private set

    var outputName by mutableStateOf<String?>(null)
        private set

    var extraCount by mutableIntStateOf(0)
        private set

    var games by mutableStateOf<List<Game>>(emptyList())
        private set

    var updates by mutableStateOf<List<Entry>>(emptyList())
        private set

    var problems by mutableStateOf<List<Entry>>(emptyList())
        private set

    var scanning by mutableStateOf(false)
        private set

    var scanDone by mutableIntStateOf(0)
        private set

    var scanTotal by mutableIntStateOf(0)
        private set

    var message by mutableStateOf<String?>(null)

    private var scanJob: Job? = null

    init {
        refreshState()

        if (setupDone) {
            rescan()
        }
    }

    private fun refreshState() {
        val keys = KeyStore.load(ctx)

        keysSummary = keys?.let {
            KeyStore.summary(it)
        }

        titleKeyCount = keys?.titleKeyCount() ?:
