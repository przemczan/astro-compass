package com.astrocompass

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.astrocompass.sensors.AndroidOrientationSensor
import com.astrocompass.sensors.SensorSource
import com.astrocompass.location.AndroidLocationProvider
import com.astrocompass.location.AndroidMagneticDeclinationProvider
import com.astrocompass.platesolve.AndroidCameraCapture
import com.russhwolf.settings.SharedPreferencesSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var container: AppContainer

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val locationGranted = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (locationGranted) container.locationProvider.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val settings = SharedPreferencesSettings(getSharedPreferences("astro_guider", MODE_PRIVATE))
        val sensorOverrideName = settings.getStringOrNull("sensor_source_override")
        val sensorOverride = sensorOverrideName?.let { runCatching { SensorSource.valueOf(it) }.getOrNull() }

        container = AppContainer(
            scope = scope,
            orientationSensor = AndroidOrientationSensor(applicationContext, sensorOverride),
            locationProvider = AndroidLocationProvider(applicationContext),
            cameraCapture = AndroidCameraCapture(applicationContext),
            magneticDeclinationProvider = AndroidMagneticDeclinationProvider(),
            settings = settings,
        )

        requestPermissions.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.CAMERA,
            ),
        )

        setContent {
            GuiderApp(container)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        container.orientationSensor.stop()
        container.locationProvider.stop()
        scope.cancel()
    }
}
