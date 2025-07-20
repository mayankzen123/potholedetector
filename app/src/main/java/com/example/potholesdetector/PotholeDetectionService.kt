package com.example.potholesdetector

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.sqrt

class PotholeDetectionService : Service(), SensorEventListener {

    companion object {
        const val CHANNEL_ID = "PotholeDetectionChannel"
        @Volatile
        var isRunning = false

        // SharedPreferences constants
        private const val PREFS_NAME = "PotholeDetectorPrefs"
        private const val PREF_SENSITIVITY = "sensitivity_threshold"

        // Detection settings
        private const val DEFAULT_THRESHOLD = 2500f
        private const val Z_AXIS_THRESHOLD = 2.0f
        private const val MIN_DETECTION_INTERVAL = 2000L
    }

    private lateinit var sensorManager: SensorManager
    private lateinit var accelerometer: Sensor
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var detectionsFile: File
    private lateinit var vibrator: Vibrator
    private lateinit var sharedPrefs: SharedPreferences

    // Detection variables
    private var lastUpdate: Long = 0
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var lastDetectionTime: Long = 0
    private val zAxisWeight = 2.0f
    private val accelerationHistory = mutableListOf<Float>()
    private val historySize = 10
    private var detectionCount = 0
    private var currentThreshold = DEFAULT_THRESHOLD

    // BroadcastReceiver for sensitivity updates
    private val sensitivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                currentThreshold = it.getFloatExtra("threshold", DEFAULT_THRESHOLD)
                Log.d("PotholeService", "Threshold updated to: $currentThreshold")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()

        // Initialize sensors
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)!!

        // Initialize location client
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Initialize vibrator
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        // Initialize SharedPreferences
        sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentThreshold = sharedPrefs.getFloat(PREF_SENSITIVITY, DEFAULT_THRESHOLD)

        // Create file for saving detections
        createDetectionsFile()

        // Load previous detection count
        loadDetectionCount()

        // Register receiver for sensitivity updates
        val filter = IntentFilter("com.example.potholesdetector.UPDATE_SENSITIVITY")
        registerReceiver(sensitivityReceiver, filter, RECEIVER_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pothole Detection Active")
            .setContentText("Monitoring for potholes (Threshold: ${currentThreshold.toInt()})")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)

        // Start sensor listening
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)

        isRunning = true

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        unregisterReceiver(sensitivityReceiver)
        isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val currentTime = System.currentTimeMillis()

            if ((currentTime - lastUpdate) > 50) {
                val diffTime = currentTime - lastUpdate
                lastUpdate = currentTime

                // Calculate acceleration change with emphasis on Z-axis
                val deltaX = x - lastX
                val deltaY = y - lastY
                val deltaZ = (z - lastZ) * zAxisWeight

                // Calculate the magnitude of acceleration change
                val acceleration = sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ) / diffTime * 10000

                // Add to history for averaging
                accelerationHistory.add(acceleration)
                if (accelerationHistory.size > historySize) {
                    accelerationHistory.removeAt(0)
                }

                // Calculate average acceleration
                val avgAcceleration = if (accelerationHistory.size >= 5) {
                    accelerationHistory.takeLast(5).average().toFloat()
                } else {
                    acceleration
                }

                // Check if this is a pothole
                if (avgAcceleration > currentThreshold &&
                    (currentTime - lastDetectionTime) > MIN_DETECTION_INTERVAL) {

                    // Additional check: Z-axis change should be significant
                    if (abs(z - lastZ) > Z_AXIS_THRESHOLD) {
                        onPotholeDetected(avgAcceleration)
                        lastDetectionTime = currentTime
                        accelerationHistory.clear()
                    }
                }

                lastX = x
                lastY = y
                lastZ = z
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed
    }

    private fun onPotholeDetected(acceleration: Float) {
        detectionCount++
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        // Calculate severity based on how much it exceeds the threshold
        val severityRatio = acceleration / currentThreshold
        val severity = when {
            severityRatio > 1.6 -> "SEVERE"
            severityRatio > 1.3 -> "MODERATE"
            else -> "MILD"
        }

        // Get location if permission granted
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {

            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                val detectionInfo = if (location != null) {
                    "Pothole #$detectionCount detected at $timestamp\n" +
                            "Severity: $severity\n" +
                            "Location: ${location.latitude}, ${location.longitude}\n"
                } else {
                    "Pothole #$detectionCount detected at $timestamp\n" +
                            "Severity: $severity\n" +
                            "Location: Not available\n"
                }

                // Save to file
                saveDetectionToFile(detectionInfo)

                // Update notification
                updateNotification(severity)

                // Vibrate
                vibrateDevice(severity)

                // Send broadcast to update UI if app is open
                sendDetectionBroadcast(detectionInfo)
            }
        } else {
            val detectionInfo = "Pothole #$detectionCount detected at $timestamp\n" +
                    "Severity: $severity\n" +
                    "Location: Permission not granted\n"

            saveDetectionToFile(detectionInfo)
            updateNotification(severity)
            vibrateDevice(severity)
            sendDetectionBroadcast(detectionInfo)
        }
    }

    private fun updateNotification(severity: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pothole Detected!")
            .setContentText("$severity pothole detected (Threshold: ${currentThreshold.toInt()}). Total: $detectionCount")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(2, notification)
    }

    private fun vibrateDevice(severity: String) {
        if (vibrator.hasVibrator()) {
            val vibrationDuration = when(severity) {
                "SEVERE" -> 1000L
                "MODERATE" -> 500L
                else -> 300L
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(vibrationDuration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(vibrationDuration)
            }
        }
    }

    private fun sendDetectionBroadcast(detectionInfo: String) {
        val intent = Intent("com.example.simplepotholedetector.POTHOLE_DETECTED")
        intent.putExtra("detection_info", detectionInfo)
        intent.putExtra("detection_count", detectionCount)
        sendBroadcast(intent)
    }

    private fun createDetectionsFile() {
        val documentsDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        detectionsFile = File(documentsDir, "pothole_detections.txt")

        if (!detectionsFile.exists()) {
            detectionsFile.createNewFile()
        }
    }

    private fun loadDetectionCount() {
        try {
            if (detectionsFile.exists()) {
                val content = detectionsFile.readText()
                val detections = content.split("===================\n")
                    .filter { it.isNotBlank() }
                detectionCount = detections.size
            }
        } catch (e: Exception) {
            Log.e("PotholeService", "Error loading detection count: ${e.message}")
        }
    }

    private fun saveDetectionToFile(detectionInfo: String) {
        try {
            val fileWriter = FileWriter(detectionsFile, true)
            fileWriter.write(detectionInfo)
            fileWriter.write("===================\n")
            fileWriter.close()
        } catch (e: Exception) {
            Log.e("PotholeService", "Error saving detection: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pothole Detection Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when pothole detection is active"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}