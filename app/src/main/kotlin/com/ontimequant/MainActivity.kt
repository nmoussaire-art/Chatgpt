package com.ontimequant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ontimequant.data.prefs.SettingsRepository
import com.ontimequant.data.prefs.UserSettings
import com.ontimequant.ui.OnTimeQuantApp
import com.ontimequant.ui.theme.OnTimeQuantTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.lifecycleScope
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val settingsFlow = settings.settings
            .stateIn(lifecycleScope, SharingStarted.Eagerly, UserSettings())

        // Hold the splash only until the stored theme is known, so the app never flashes
        // the wrong colour scheme on a cold start.
        var ready = false
        splash.setKeepOnScreenCondition { !ready }

        setContent {
            val userSettings by settingsFlow.collectAsStateWithLifecycle()
            ready = true
            OnTimeQuantTheme(
                themeMode = userSettings.themeMode,
                reducedMotion = userSettings.reducedMotion,
            ) {
                OnTimeQuantApp(
                    startAppointmentId = intent?.getStringExtra(EXTRA_APPOINTMENT_ID),
                    onboardingComplete = userSettings.onboardingComplete,
                )
            }
        }
    }

    companion object {
        const val EXTRA_APPOINTMENT_ID = "appointmentId"
    }
}
