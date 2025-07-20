package com.example.potholesdetector

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    // UI Elements
    private lateinit var statusTextView: TextView
    private lateinit var detectionsTextView: TextView
    private lateinit var startButton: Button
    private lateinit var clearButton: Button
    private lateinit var sensitivitySeekBar: SeekBar
    private lateinit var sensitivityValueText: TextView
    private lateinit var lockCheckBox: CheckBox

    // SharedPreferences
    private lateinit var sharedPrefs: SharedPreferences

    // Detection variables
    private var detectionCount = 0
    private val detectionsList = mutableListOf<String>()

    // File to save detections
    private lateinit var detectionsFile: File

    // Broadcast receiver for pothole detections
    private val potholeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val detectionInfo = it.getStringExtra("detection_info") ?: return
                detectionCount = it.getIntExtra("detection_count", detectionCount)

                // Add to list and update UI
                detectionsList.add(0, detectionInfo)
                updateDetectionsDisplay()
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1
        private const val PREFS_NAME = "PotholeDetectorPrefs"
        private const val PREF_SENSITIVITY = "sensitivity_threshold"
        private const val PREF_LOCKED = "sensitivity_locked"

        // Sensitivity range
        private const val MIN_THRESHOLD = 1000f
        private const val MAX_THRESHOLD = 5000f
        private const val DEFAULT_THRESHOLD = 2500f

        private val REQUIRED_PERMISSIONS = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.VIBRATE)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI elements
        statusTextView = findViewById(R.id.statusTextView)
        detectionsTextView = findViewById(R.id.detectionsTextView)
        startButton = findViewById(R.id.startButton)
        clearButton = findViewById(R.id.clearButton)
        sensitivitySeekBar = findViewById(R.id.sensitivitySeekBar)
        sensitivityValueText = findViewById(R.id.sensitivityValueText)
        lockCheckBox = findViewById(R.id.lockCheckBox)

        // Initialize SharedPreferences
        sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Create file for saving detections
        createDetectionsFile()

        // Load previous detections
        loadDetections()

        // Initialize sensitivity settings
        setupSensitivityControls()

        // Update UI based on service state
        updateUIState()

        // Set button listeners
        startButton.setOnClickListener {
            toggleDetection()
        }

        clearButton.setOnClickListener {
            clearDetections()
        }

        // Request permissions
        requestPermissions()

        // Register broadcast receiver
        val filter = IntentFilter("com.example.potholesdetector.POTHOLE_DETECTED")
        registerReceiver(potholeReceiver, filter, RECEIVER_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        updateUIState()
        loadDetections()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(potholeReceiver)
    }

    private fun updateUIState() {
        if (PotholeDetectionService.isRunning) {
            startButton.text = "Stop Detection"
            statusTextView.text = "Status: Detecting (Background Service Active)"

            // Disable sensitivity controls when service is running and locked
            if (lockCheckBox.isChecked) {
                sensitivitySeekBar.isEnabled = false
            }
        } else {
            startButton.text = "Start Detection"
            statusTextView.text = "Status: Stopped"

            // Re-enable sensitivity controls based on lock state
            sensitivitySeekBar.isEnabled = !lockCheckBox.isChecked
        }
    }

    private fun toggleDetection() {
        if (PotholeDetectionService.isRunning) {
            stopDetectionService()
        } else {
            if (checkPermissions()) {
                startDetectionService()
            } else {
                requestPermissions()
                showToast("Please grant all permissions first")
            }
        }
    }

    private fun startDetectionService() {
        val serviceIntent = Intent(this, PotholeDetectionService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

        startButton.text = "Stop Detection"
        statusTextView.text = "Status: Detecting (Background Service Active)"

        val threshold = sharedPrefs.getFloat(PREF_SENSITIVITY, DEFAULT_THRESHOLD)
        showToast("Detection started (Threshold: ${threshold.toInt()}) - Works in background!")
    }

    private fun stopDetectionService() {
        val serviceIntent = Intent(this, PotholeDetectionService::class.java)
        stopService(serviceIntent)

        startButton.text = "Start Detection"
        statusTextView.text = "Status: Stopped"
        showToast("Detection stopped")
    }

    private fun createDetectionsFile() {
        val documentsDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        detectionsFile = File(documentsDir, "pothole_detections.txt")

        if (!detectionsFile.exists()) {
            detectionsFile.createNewFile()
        }
    }

    private fun updateDetectionsDisplay() {
        val displayText = StringBuilder()
        displayText.append("Total Detections: $detectionCount\n\n")

        // Show last 10 detections
        val recentDetections = detectionsList.take(10)
        recentDetections.forEach { detection ->
            displayText.append(detection)
            displayText.append("-------------------\n")
        }

        detectionsTextView.text = displayText.toString()
    }

    private fun loadDetections() {
        try {
            if (detectionsFile.exists()) {
                val content = detectionsFile.readText()
                val detections = content.split("===================\n")
                    .filter { it.isNotBlank() }

                detectionsList.clear()
                detectionsList.addAll(detections)
                detectionCount = detectionsList.size

                updateDetectionsDisplay()
            }
        } catch (e: Exception) {
            showToast("Error loading detections: ${e.message}")
        }
    }

    private fun clearDetections() {
        // Stop service if running
        if (PotholeDetectionService.isRunning) {
            stopDetectionService()
        }

        detectionsList.clear()
        detectionCount = 0
        detectionsFile.writeText("")
        updateDetectionsDisplay()
        showToast("All detections cleared")
    }

    private fun checkPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            REQUIRED_PERMISSIONS,
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                showToast("All permissions granted")
            } else {
                showToast("Some permissions denied - app may not work properly")
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun setupSensitivityControls() {
        // Load saved settings
        val savedThreshold = sharedPrefs.getFloat(PREF_SENSITIVITY, DEFAULT_THRESHOLD)
        val isLocked = sharedPrefs.getBoolean(PREF_LOCKED, false)

        // Convert threshold to seekbar progress (MIN_THRESHOLD-MAX_THRESHOLD range to 0-100)
        val range = MAX_THRESHOLD - MIN_THRESHOLD
        val progress = ((savedThreshold - MIN_THRESHOLD) / range * 100).toInt()
        sensitivitySeekBar.progress = progress
        updateSensitivityText(savedThreshold)

        // Set lock state
        lockCheckBox.isChecked = isLocked
        sensitivitySeekBar.isEnabled = !isLocked

        // SeekBar listener
        sensitivitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Convert progress (0-100) to threshold (MIN_THRESHOLD-MAX_THRESHOLD)
                val range = MAX_THRESHOLD - MIN_THRESHOLD
                val threshold = MIN_THRESHOLD + (progress / 100f * range)
                updateSensitivityText(threshold)

                // Save to preferences
                sharedPrefs.edit().putFloat(PREF_SENSITIVITY, threshold).apply()

                // If service is running, notify it to update threshold
                if (PotholeDetectionService.isRunning) {
                    val intent = Intent("com.example.simplepotholedetector.UPDATE_SENSITIVITY")
                    intent.putExtra("threshold", threshold)
                    sendBroadcast(intent)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Lock checkbox listener
        lockCheckBox.setOnCheckedChangeListener { _, isChecked ->
            // Don't allow changing sensitivity when service is running and locked
            if (PotholeDetectionService.isRunning && isChecked) {
                sensitivitySeekBar.isEnabled = false
                showToast("Sensitivity locked - Stop detection to adjust")
            } else {
                sensitivitySeekBar.isEnabled = !isChecked
                sharedPrefs.edit().putBoolean(PREF_LOCKED, isChecked).apply()

                if (isChecked) {
                    showToast("Sensitivity locked")
                } else {
                    showToast("Sensitivity unlocked")
                }
            }
        }
    }

    private fun updateSensitivityText(threshold: Float) {
        val sensitivity = when {
            threshold < 1800 -> "Very High"
            threshold < 2200 -> "High"
            threshold < 2800 -> "Moderate"
            threshold < 3500 -> "Low"
            else -> "Very Low"
        }
        sensitivityValueText.text = "Threshold: ${threshold.toInt()} ($sensitivity)"
    }
}