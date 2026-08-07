package com.astroguider

import androidx.compose.ui.window.ComposeUIViewController
import com.astroguider.location.StubLocationProvider
import com.astroguider.sensors.StubOrientationSensor
import com.russhwolf.settings.NSUserDefaultsSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import platform.Foundation.NSUserDefaults

fun MainViewController() = ComposeUIViewController {
    val container = AppContainer(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
        orientationSensor = StubOrientationSensor(),
        locationProvider = StubLocationProvider(),
        settings = NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults),
    )
    GuiderApp(container)
}
