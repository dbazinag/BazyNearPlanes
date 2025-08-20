package com.example.bazynearplanesandroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.snackbar.Snackbar

//TODO: Fix these broken ass permissions

class MainActivity : AppCompatActivity() {

    private lateinit var txtStatus: TextView
    private lateinit var btnGetLocation: Button
    private lateinit var btnTestNotification: Button

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }


    private val requestLocationPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        when {
            fine -> {
                txtStatus.text = "Precise location granted."
                maybeNudgeDeviceLocation()
            }
            coarse -> {
                txtStatus.text = "Approximate location granted."
                maybeNudgeForPrecise()
            }
            else -> {
                txtStatus.text = "Location permission denied."
            }
        }
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        NotificationHelper.lastStatus = if (granted) "Notifications enabled" else "Notifications disabled by user"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtStatus = findViewById(R.id.txtStatus)
        btnGetLocation = findViewById(R.id.btnGetLocation)
        btnTestNotification = findViewById(R.id.btnTestNotification)

        NotificationHelper.createChannel(this)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        ensureLocationPermission()

        btnGetLocation.setOnClickListener { getCurrentLocation() }
        btnTestNotification.setOnClickListener {
            NotificationHelper.notify(this, "Test notification", "This is a test from NearPlanes")
            txtStatus.text = "Tried to send notification. ${NotificationHelper.lastStatus}"
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasAnyLocation()) {
            if (hasPreciseLocation()) {
                txtStatus.text = if (isDeviceLocationEnabled()) "Location ready." else "Precise granted, device location OFF."
            } else {
                txtStatus.text = "Approximate location active."
            }
        }
    }


    private fun hasAnyLocation(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun hasPreciseLocation(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureLocationPermission() {
        if (hasAnyLocation()) {
            if (hasPreciseLocation()) {
                txtStatus.text = if (isDeviceLocationEnabled()) "Location ready." else "Precise granted, device location OFF."
                maybeNudgeDeviceLocation()
            } else {
                txtStatus.text = "Approximate location granted."
                maybeNudgeForPrecise()
            }
            return
        }
        requestLocationPermissions.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,  
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }


    private fun maybeNudgeForPrecise() {
        val allowApprox = prefs.getBoolean("allow_approx", false)
        if (allowApprox) return

        Snackbar.make(
            findViewById(android.R.id.content),
            "Precise location improves accuracy for nearby plane alerts.",
            Snackbar.LENGTH_INDEFINITE
        )
            .setAction("Enable precise") {
                val uri = Uri.fromParts("package", packageName, null)
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
            }
            .setDuration(8000)
            .addCallback(object : Snackbar.Callback() {})
            .show()

        Snackbar.make(
            findViewById(android.R.id.content),
            "Or continue with approximate (reduced accuracy).",
            Snackbar.LENGTH_LONG
        ).setAction("Use approx") {
            prefs.edit().putBoolean("allow_approx", true).apply()
            txtStatus.text = "Using approximate location."
        }.show()
    }

    private fun maybeNudgeDeviceLocation() {
        if (!isDeviceLocationEnabled()) {
            Snackbar.make(
                findViewById(android.R.id.content),
                "Device location is OFF. Turn it on for accurate position.",
                Snackbar.LENGTH_LONG
            ).setAction("Open Location") {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }.show()
        }
    }

    private fun isDeviceLocationEnabled(): Boolean {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val gps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val net = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        return gps || net
    }


    private fun getCurrentLocation() {
        if (!hasAnyLocation()) {
            txtStatus.text = "Grant location permission first."
            ensureLocationPermission()
            return
        }

        if (!hasPreciseLocation() && !prefs.getBoolean("allow_approx", false)) {
            txtStatus.text = "Precise recommended for this feature."
            maybeNudgeForPrecise()
            return
        }

        if (!isDeviceLocationEnabled()) {
            txtStatus.text = "Device location OFF. Turn it on and try again."
            maybeNudgeDeviceLocation()
            return
        }

        fused.lastLocation
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    txtStatus.text = "Last known: lat=${"%.5f".format(loc.latitude)}, lon=${"%.5f".format(loc.longitude)}"
                } else {
                    txtStatus.text = "No last known fix. Trying active read…"
                    val tokenSrc = CancellationTokenSource()
                    val timeoutMs = 10_000L
                    val handler = android.os.Handler(mainLooper)
                    val timeout = Runnable {
                        tokenSrc.cancel()
                        txtStatus.text = "Active read timed out. Listening for one update…"
                        requestSingleUpdate()
                    }
                    handler.postDelayed(timeout, timeoutMs)

                    val priority = if (hasPreciseLocation()) Priority.PRIORITY_HIGH_ACCURACY
                                   else Priority.PRIORITY_BALANCED_POWER_ACCURACY

                    fused.getCurrentLocation(priority, tokenSrc.token)
                        .addOnSuccessListener { cur ->
                            handler.removeCallbacks(timeout)
                            if (cur != null) {
                                txtStatus.text = "Current: lat=${"%.5f".format(cur.latitude)}, lon=${"%.5f".format(cur.longitude)}"
                            } else {
                                txtStatus.text = "Active read returned null. Listening for one update…"
                                requestSingleUpdate()
                            }
                        }
                        .addOnFailureListener { e ->
                            handler.removeCallbacks(timeout)
                            txtStatus.text = "getCurrentLocation error: ${e.message}. Listening for one update…"
                            requestSingleUpdate()
                        }
                }
            }
            .addOnFailureListener { e ->
                txtStatus.text = "lastLocation error: ${e.message}"
            }
    }

    private fun requestSingleUpdate() {
        val request = com.google.android.gms.location.LocationRequest
            .Builder(
                if (hasPreciseLocation()) Priority.PRIORITY_HIGH_ACCURACY
                else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                5_000L
            )
            .setMaxUpdates(1)
            .build()

        val callback = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                val loc = result.lastLocation
                if (loc != null) {
                    txtStatus.text = "One-shot: lat=${"%.5f".format(loc.latitude)}, lon=${"%.5f".format(loc.longitude)}"
                } else {
                    txtStatus.text = "No location from one-shot listener."
                }
                fused.removeLocationUpdates(this)
            }
        }
        fused.requestLocationUpdates(request, callback, mainLooper)
    }
}
