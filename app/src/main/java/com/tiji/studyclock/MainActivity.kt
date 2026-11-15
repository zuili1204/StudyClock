package com.tiji.studyclock

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tiji.studyclock.domain.model.ThemeMode
import com.tiji.studyclock.ui.MainViewModel
import com.tiji.studyclock.ui.StudyClockApp
import com.tiji.studyclock.ui.theme.StudyClockTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()

    /** Route requested by a widget tap; consumed by the nav graph once data has loaded. */
    private val requestedRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedRoute.value = intent?.getStringExtra(EXTRA_OPEN_ROUTE)
        enableEdgeToEdge()
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val busy by model.busy.collectAsStateWithLifecycle()
            val route by requestedRoute.collectAsStateWithLifecycle()
            var previewTheme by rememberSaveable { mutableStateOf<ThemeMode?>(null) }
            val mode = previewTheme ?: state.settings.theme
            val dark = mode == ThemeMode.DARK ||
                (mode == ThemeMode.SYSTEM && isSystemInDarkTheme())
            SideEffect {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            StudyClockTheme(mode) {
                StudyClockApp(
                    state,
                    busy,
                    model,
                    { previewTheme = it },
                    requestedRoute = route,
                    onRouteHandled = { requestedRoute.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedRoute.value = intent.getStringExtra(EXTRA_OPEN_ROUTE)
    }

    override fun onResume() {
        super.onResume()
        model.refreshDate()
    }

    companion object {
        const val EXTRA_OPEN_ROUTE = "com.tiji.studyclock.extra.OPEN_ROUTE"
    }
}
