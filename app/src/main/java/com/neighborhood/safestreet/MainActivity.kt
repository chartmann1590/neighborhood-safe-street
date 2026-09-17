package com.neighborhood.safestreet

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.neighborhood.safestreet.ui.MainScreen
import com.neighborhood.safestreet.ui.theme.DarkBackground
import com.neighborhood.safestreet.ui.theme.SafeStreetTheme
import com.neighborhood.safestreet.ui.viewmodel.MainViewModel

import android.os.Looper
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationManager: LocationManager? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc ->
                viewModel.updateUserLocation(loc.latitude, loc.longitude)
            }
        }
    }

    private val directLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            viewModel.updateUserLocation(location.latitude, location.longitude)
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        val hasPermission = fineGranted || coarseGranted
        viewModel.setLocationPermissionGranted(hasPermission)

        if (hasPermission) {
            fetchLocation()
            startLocationUpdates()
        }
        viewModel.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager

        checkAndRequestLocationPermissions()
        fetchLocation()
        startLocationUpdates()

        // Check if opened from a push notification
        val openMap = intent?.getBooleanExtra("open_map", false) ?: false
        if (openMap) {
            val focusedId = intent?.getStringExtra("focused_incident_id")
            val target = viewModel.filteredIncidents.value.find { it.id == focusedId }
            viewModel.openFullscreenMap(target)
        }

        setContent {
            SafeStreetTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    MainScreen(
                        viewModel = viewModel,
                        onRequestLocationPermission = { checkAndRequestLocationPermissions() }
                    )
                }
            }
        }
    }

    private fun checkAndRequestLocationPermissions() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasPermission = fine || coarse
        viewModel.setLocationPermissionGranted(hasPermission)

        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun fetchLocation() {
        try {
            val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (fine || coarse) {
                viewModel.setLocationPermissionGranted(true)

                // 1. Direct system LocationManager check for immediate fix without waiting
                locationManager?.let { lm ->
                    val providers = listOf(
                        LocationManager.GPS_PROVIDER,
                        LocationManager.NETWORK_PROVIDER,
                        LocationManager.PASSIVE_PROVIDER
                    )
                    for (provider in providers) {
                        try {
                            lm.getLastKnownLocation(provider)?.let { loc ->
                                viewModel.updateUserLocation(loc.latitude, loc.longitude)
                            }
                        } catch (_: SecurityException) {}
                    }
                }

                // 2. Active high accuracy fix via FusedClient
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { loc ->
                        if (loc != null) {
                            viewModel.updateUserLocation(loc.latitude, loc.longitude)
                        }
                    }

                // 3. Balanced accuracy fix (Wi-Fi / Cell tower)
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                    .addOnSuccessListener { loc ->
                        if (loc != null) {
                            viewModel.updateUserLocation(loc.latitude, loc.longitude)
                        }
                    }

                // 4. Fallback to last known cache
                fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                    if (loc != null) {
                        viewModel.updateUserLocation(loc.latitude, loc.longitude)
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun startLocationUpdates() {
        try {
            val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (fine || coarse) {
                // Immediate continuous updates without requiring 20m physical movement
                val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2500L)
                    .setMinUpdateDistanceMeters(0f)
                    .setMinUpdateIntervalMillis(1500L)
                    .build()
                fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())

                // Also register direct LocationManager listener for instant GPS updates
                locationManager?.let { lm ->
                    try {
                        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, directLocationListener, Looper.getMainLooper())
                        }
                        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, directLocationListener, Looper.getMainLooper())
                        }
                    } catch (_: SecurityException) {}
                }
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun stopLocationUpdates() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            locationManager?.removeUpdates(directLocationListener)
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.wearSyncManager.checkConnection()
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasPermission = fine || coarse
        viewModel.setLocationPermissionGranted(hasPermission)

        if (hasPermission) {
            fetchLocation()
            startLocationUpdates()
        }
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
    }
}
