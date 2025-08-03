package com.ahmad.vigilanteye

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color // Added import for Color
import android.graphics.Matrix
import android.graphics.RectF
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.animation.Easing
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import com.google.android.material.switchmaterial.SwitchMaterial
import com.ahmad.vigilanteye.Constants.LABELS_PATH
import com.ahmad.vigilanteye.Constants.MODEL_PATH
import com.ahmad.vigilanteye.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList
import kotlin.math.sqrt

// 1. Modify TrafficState enum
enum class TrafficState {
    NO_TRAFFIC, LIGHT, MODERATE, HEAVY // Added NO_TRAFFIC
}

data class HistoricalObjectRecord(
    val id: Int,
    val label: String,
    val firstDetectionTimestamp: Long,
    var lastSeenTimestamp: Long
)

class MainActivity : AppCompatActivity(), Detector.DetectorListener, OverlayView.DangerZoneStateListener {

    private lateinit var binding: ActivityMainBinding
    private val isFrontCamera = false

    private val trackedObjects = mutableListOf<TrackedObject>()
    private val historicalDetections = mutableListOf<HistoricalObjectRecord>()
    private var nextObjectId = 0
    private val historicalWarningCounts = mutableMapOf<String, Int>()

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var detector: Detector
    private lateinit var cameraExecutor: ExecutorService

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private lateinit var sharedPreferences: SharedPreferences

    private var isBeepEnabled = true
    private var isVibrationEnabled = true
    private var isTrailEnabled = true

    // Add a variable to store "No Traffic" duration
    private var cumulativeNoTrafficDurationMs: Long = 0L
    private var cumulativeLightDurationMs: Long = 0L
    private var cumulativeModerateDurationMs: Long = 0L
    private var cumulativeHeavyDurationMs: Long = 0L
    private var lastFrameTimestampMs: Long = 0L
    private var currentTrafficState: TrafficState = TrafficState.NO_TRAFFIC // Initial state

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        loadSettings()

        mediaPlayer = MediaPlayer.create(this, R.raw.beep)
        mediaPlayer?.isLooping = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibrator = vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this)
        detector.setup()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.overlay.setDangerZoneStateListener(this)
        binding.reportButton.setOnClickListener {
            showReportDialog()
        }
        binding.settingsButton.setOnClickListener {
            showSettingsDialog()
        }

        binding.trafficStatusText.text = "Calculating..."
        binding.dangerAlertText.visibility = View.GONE
        binding.inferenceTime.text = ""
        binding.overlay.setDrawTrailsEnabled(isTrailEnabled)

        lastFrameTimestampMs = System.currentTimeMillis()
    }

    private fun loadSettings() {
        isBeepEnabled = sharedPreferences.getBoolean(KEY_BEEP_ENABLED, true)
        isVibrationEnabled = sharedPreferences.getBoolean(KEY_VIBRATION_ENABLED, true)
        isTrailEnabled = sharedPreferences.getBoolean(KEY_TRAIL_ENABLED, true)
        Log.d(TAG, "Loaded settings - Beep: $isBeepEnabled, Vibration: $isVibrationEnabled, Trails: $isTrailEnabled")
    }

    private fun saveSetting(key: String, value: Boolean) {
        with(sharedPreferences.edit()) {
            putBoolean(key, value)
            apply()
        }
        Log.d(TAG, "Saved setting $key = $value")
    }

    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)

        val switchBeep = dialogView.findViewById<SwitchMaterial>(R.id.dialog_switch_beep)
        val switchVibration = dialogView.findViewById<SwitchMaterial>(R.id.dialog_switch_vibration)
        val switchTrails = dialogView.findViewById<SwitchMaterial>(R.id.dialog_switch_trails)

        switchBeep.isChecked = isBeepEnabled
        switchVibration.isChecked = isVibrationEnabled
        switchTrails.isChecked = isTrailEnabled

        switchBeep.setOnCheckedChangeListener { _, isChecked ->
            isBeepEnabled = isChecked
            saveSetting(KEY_BEEP_ENABLED, isChecked)
            if (!isChecked && mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
                mediaPlayer?.seekTo(0)
            }
        }

        switchVibration.setOnCheckedChangeListener { _, isChecked ->
            isVibrationEnabled = isChecked
            saveSetting(KEY_VIBRATION_ENABLED, isChecked)
            if (!isChecked) {
                vibrator?.cancel()
            }
        }

        switchTrails.setOnCheckedChangeListener { _, isChecked ->
            isTrailEnabled = isChecked
            saveSetting(KEY_TRAIL_ENABLED, isChecked)
            binding.overlay.setDrawTrailsEnabled(isChecked)
        }

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Close") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .show()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
            lastFrameTimestampMs = System.currentTimeMillis()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider =
            cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val rotation = binding.viewFinder.display.rotation

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer = Bitmap.createBitmap(
                imageProxy.width,
                imageProxy.height,
                Bitmap.Config.ARGB_8888
            )
            imageProxy.use { image ->
                if (image.planes.isNotEmpty() && image.planes[0].buffer != null) {
                    bitmapBuffer.copyPixelsFromBuffer(image.planes[0].buffer)
                    val matrix = Matrix().apply {
                        postRotate(image.imageInfo.rotationDegrees.toFloat())
                    }
                    val rotatedBitmap = Bitmap.createBitmap(
                        bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                        matrix, true
                    )
                    detector.detect(rotatedBitmap)
                } else {
                    Log.e(TAG, "Image proxy planes or buffer were null/empty.")
                }
            }
        }

        cameraProvider.unbindAll()
        try {
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            startCamera()
        } else {
            Log.e(TAG, "Camera permission not granted.")
            AlertDialog.Builder(this)
                .setTitle("Permission Required")
                .setMessage("Camera permission is needed for object detection.")
                .setPositiveButton("OK") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted() && cameraProvider == null) {
            startCamera()
        } else if (!allPermissionsGranted()) {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
        lastFrameTimestampMs = System.currentTimeMillis()

        if (mediaPlayer != null && mediaPlayer!!.isPlaying) {
            mediaPlayer?.pause()
        }
        mediaPlayer?.seekTo(0)
    }

    override fun onPause() {
        super.onPause()
        updateTrafficDuration(currentTrafficState) // Update with current state before pausing

        if (mediaPlayer != null && mediaPlayer!!.isPlaying) {
            mediaPlayer?.pause()
        }
        vibrator?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        detector.clear()
        cameraExecutor.shutdown()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        vibrator?.cancel()
        vibrator = null
    }

    override fun onEmptyDetect() {
        val now = System.currentTimeMillis()
        val remainingTrackedObjects = mutableListOf<TrackedObject>()
        for (obj in trackedObjects) {
            if (obj.status == TrackStatus.ACTIVE) {
                obj.status = TrackStatus.LOST
            }
            if (now - obj.lastSeenTime < Constants.LOST_TRACK_TIMEOUT_MS) {
                remainingTrackedObjects.add(obj)
            } else {
                Log.d(TAG, "Permanently removing timed-out LOST track ID (onEmptyDetect): ${obj.id}")
            }
        }
        trackedObjects.clear()
        trackedObjects.addAll(remainingTrackedObjects)

        runOnUiThread {
            updateTrafficDuration(TrafficState.NO_TRAFFIC) // Pass NO_TRAFFIC state
            binding.overlay.setResults(emptyList()) // Clear overlay
            binding.inferenceTime.text = ""
            binding.trafficStatusText.text = "🚦No Traffic"
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        updateTrackedObjects(boundingBoxes)

        val activeObjectCount = trackedObjects.count { it.status == TrackStatus.ACTIVE }
        val currentDisplayedText: String
        val newStateForDuration: TrafficState

        if (activeObjectCount == 0) {
            currentDisplayedText =  "🚦No Traffic"
            newStateForDuration = TrafficState.NO_TRAFFIC // Use new state
        } else if (activeObjectCount <= Constants.LIGHT_TRAFFIC_MAX_OBJECTS) {
            currentDisplayedText = "🟢 Light Traffic"
            newStateForDuration = TrafficState.LIGHT
        } else if (activeObjectCount <= Constants.MODERATE_TRAFFIC_MAX_OBJECTS) {
            currentDisplayedText = "🟡 Moderate Traffic"
            newStateForDuration = TrafficState.MODERATE
        } else {
            currentDisplayedText = "🚨 Heavy Traffic"
            newStateForDuration = TrafficState.HEAVY
        }

        runOnUiThread {
            updateTrafficDuration(newStateForDuration)
            binding.inferenceTime.text = "${inferenceTime}ms"
            binding.overlay.setResults(trackedObjects.filter { it.status == TrackStatus.ACTIVE })
            binding.trafficStatusText.text = currentDisplayedText
        }
    }

    private fun updateTrafficDuration(newState: TrafficState? = null) {
        val now = System.currentTimeMillis()
        val timeDeltaMs = if (lastFrameTimestampMs > 0) now - lastFrameTimestampMs else 0L

        if (timeDeltaMs > 0 && timeDeltaMs < 5000) { // Avoid excessively large deltas
            when (currentTrafficState) {
                TrafficState.NO_TRAFFIC -> cumulativeNoTrafficDurationMs += timeDeltaMs
                TrafficState.LIGHT -> cumulativeLightDurationMs += timeDeltaMs
                TrafficState.MODERATE -> cumulativeModerateDurationMs += timeDeltaMs
                TrafficState.HEAVY -> cumulativeHeavyDurationMs += timeDeltaMs
            }
        }

        if (newState != null && newState != currentTrafficState) {
            currentTrafficState = newState
        }
        lastFrameTimestampMs = now
    }


    private fun updateTrackedObjects(currentDetections: List<BoundingBox>) {
        val now = System.currentTimeMillis()
        val nextFrameTrackedObjects = mutableListOf<TrackedObject>()
        val assignedCurrentDetections = BooleanArray(currentDetections.size)
        val pixelMatchThreshold = getPixelMatchThreshold()
        val dangerThreshold = 1.0f - Constants.DANGER_ZONE_HEIGHT_RATIO

        for (trackedObj in trackedObjects) {
            var bestMatchIndex = -1
            var minDistance = Float.MAX_VALUE

            if (trackedObj.status == TrackStatus.ACTIVE ||
                (trackedObj.status == TrackStatus.LOST && now - trackedObj.lastSeenTime < Constants.LOST_TRACK_TIMEOUT_MS)) {

                val trackedPixelRect = convertToPixelRect(trackedObj.boundingBox)
                if (trackedPixelRect == null) {
                    Log.w(TAG, "Skipping tracked object with null pixel rect: ID ${trackedObj.id}")
                    if (trackedObj.status == TrackStatus.LOST && now - trackedObj.lastSeenTime < Constants.LOST_TRACK_TIMEOUT_MS) {
                        nextFrameTrackedObjects.add(trackedObj)
                    } else if (trackedObj.status == TrackStatus.ACTIVE) {
                        trackedObj.status = TrackStatus.LOST
                        nextFrameTrackedObjects.add(trackedObj)
                    }
                    continue
                }

                for ((detectionIndex, bbox) in currentDetections.withIndex()) {
                    if (!assignedCurrentDetections[detectionIndex] && trackedObj.label == bbox.clsName) {
                        val currentPixelRectDetect = convertToPixelRect(RectF(bbox.x1, bbox.y1, bbox.x2, bbox.y2))
                            ?: continue

                        val distance = calculatePixelDistance(trackedPixelRect, currentPixelRectDetect)

                        if (distance < pixelMatchThreshold && distance < minDistance) {
                            minDistance = distance
                            bestMatchIndex = detectionIndex
                        }
                    }
                }
            }

            if (bestMatchIndex != -1) {
                val matchedBBox = currentDetections[bestMatchIndex]
                val newNormRect = RectF(matchedBBox.x1, matchedBBox.y1, matchedBBox.x2, matchedBBox.y2)

                val isInDangerNow = newNormRect.bottom >= dangerThreshold
                if (isInDangerNow && !trackedObj.wasInDangerZone) {
                    val currentCount = historicalWarningCounts[trackedObj.label] ?: 0
                    historicalWarningCounts[trackedObj.label] = currentCount + 1
                    Log.d(TAG, "Danger alert for ${trackedObj.label} (ID ${trackedObj.id})! Total: ${historicalWarningCounts[trackedObj.label]}")
                }
                trackedObj.wasInDangerZone = isInDangerNow

                trackedObj.update(newNormRect)
                trackedObj.status = TrackStatus.ACTIVE
                nextFrameTrackedObjects.add(trackedObj)
                assignedCurrentDetections[bestMatchIndex] = true

                historicalDetections.find { it.id == trackedObj.id }?.lastSeenTimestamp = now
            } else {
                if (trackedObj.status == TrackStatus.ACTIVE) {
                    trackedObj.status = TrackStatus.LOST
                    nextFrameTrackedObjects.add(trackedObj)
                } else if (trackedObj.status == TrackStatus.LOST) {
                    if (now - trackedObj.lastSeenTime < Constants.LOST_TRACK_TIMEOUT_MS) {
                        nextFrameTrackedObjects.add(trackedObj)
                    } else {
                        Log.d(TAG, "Permanently removing timed-out LOST track ID: ${trackedObj.id}")
                    }
                }
            }
        }

        for ((index, bbox) in currentDetections.withIndex()) {
            if (!assignedCurrentDetections[index]) {
                val newNormRect = RectF(bbox.x1, bbox.y1, bbox.x2, bbox.y2)
                val newId = nextObjectId++
                val newObj = TrackedObject(
                    label = bbox.clsName,
                    boundingBox = newNormRect,
                    id = newId,
                    status = TrackStatus.ACTIVE,
                    lastSeenTime = now
                )

                val isInDangerNow = newNormRect.bottom >= dangerThreshold
                if (isInDangerNow) {
                    val currentCount = historicalWarningCounts[newObj.label] ?: 0
                    historicalWarningCounts[newObj.label] = currentCount + 1
                    Log.d(TAG, "Danger alert for new ${newObj.label} (ID ${newObj.id})! Total: ${historicalWarningCounts[newObj.label]}")
                }
                newObj.wasInDangerZone = isInDangerNow

                nextFrameTrackedObjects.add(newObj)
                historicalDetections.add(HistoricalObjectRecord(newObj.id, newObj.label, now, now))
            }
        }
        trackedObjects.clear()
        trackedObjects.addAll(nextFrameTrackedObjects)
    }

    private fun getPixelMatchThreshold(): Float {
        val viewWidth = binding.overlay.width.toFloat()
        return if (viewWidth > 0) {
            viewWidth * Constants.PIXEL_MATCH_THRESHOLD_RATIO
        } else {
            Constants.DEFAULT_PIXEL_MATCH_THRESHOLD
        }
    }

    private fun convertToPixelRect(normalizedRect: RectF): RectF? {
        val viewWidth = binding.overlay.width.toFloat()
        val viewHeight = binding.overlay.height.toFloat()
        if (viewWidth <= 0 || viewHeight <= 0) return null
        return RectF(
            normalizedRect.left * viewWidth,
            normalizedRect.top * viewHeight,
            normalizedRect.right * viewWidth,
            normalizedRect.bottom * viewHeight
        )
    }

    private fun calculatePixelDistance(rect1: RectF, rect2: RectF): Float {
        val dx = rect1.centerX() - rect2.centerX()
        val dy = rect1.centerY() - rect2.centerY()
        return sqrt(dx * dx + dy * dy)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun showReportDialog() {
        Log.d(TAG, "showReportDialog: Generating report with charts.")
        // Ensure durations are updated for the current state right before generating the report
        updateTrafficDuration(currentTrafficState)

        val currentActiveTrackedObjects = trackedObjects.filter { it.status == TrackStatus.ACTIVE }
        val currentClassCountMap = currentActiveTrackedObjects.groupingBy { it.label }.eachCount()

        val historicalClassCountMap = historicalDetections.groupingBy { it.label }.eachCount()
        val totalUniqueObjectsDetected = historicalDetections.size
        val dangerThreshold = 1.0f - Constants.DANGER_ZONE_HEIGHT_RATIO

        // val activeObjectCountForReport = trackedObjects.count { it.status == TrackStatus.ACTIVE } // Already have currentTrafficState
        val currentStatusString = when (currentTrafficState) {
            TrafficState.NO_TRAFFIC -> "🚦No Traffic"
            TrafficState.LIGHT -> "🟢 Light Traffic"
            TrafficState.MODERATE -> "🟡 Moderate Traffic"
            TrafficState.HEAVY -> "🔴 Heavy Traffic"
        }

        val dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_report_chart, null)
        val reportTextView = dialogView.findViewById<TextView>(R.id.report_text_view)
        val durationPieChart = dialogView.findViewById<PieChart>(R.id.duration_pie_chart)
        val objectsBarChartCurrent = dialogView.findViewById<BarChart>(R.id.objects_bar_chart_current)
        val objectChartTitleCurrent = dialogView.findViewById<TextView>(R.id.object_chart_title_current)
        val objectsBarChartHistorical = dialogView.findViewById<BarChart>(R.id.objects_bar_chart)
        val objectChartTitleHistorical = dialogView.findViewById<TextView>(R.id.object_chart_title)
        val warningsBarChart = dialogView.findViewById<BarChart>(R.id.warnings_bar_chart)
        val warningChartTitle = dialogView.findViewById<TextView>(R.id.warning_chart_title)

        val reportBuilder = StringBuilder().apply {
            append("----- Traffic Analysis Report -----\n")
            append("Generated: $dateTime\n\n")
            append("Current Status: $currentStatusString\n")
            append("Objects Currently Active: ${currentActiveTrackedObjects.size}\n")
            append("Objects Currently Active in Danger Zone: ${currentActiveTrackedObjects.count { it.boundingBox.bottom >= dangerThreshold }}\n")
            append("Total Unique Objects Detected Ever: $totalUniqueObjectsDetected\n")
            append("Total Danger Zone Alerts Issued (by class): ${historicalWarningCounts.values.sum()}\n\n")
            append("---------------------------------------\n")
        }
        reportTextView.text = reportBuilder.toString()

        setupDurationChart(durationPieChart)

        if (currentClassCountMap.isNotEmpty()) {
            objectsBarChartCurrent.visibility = View.VISIBLE
            objectChartTitleCurrent.visibility = View.VISIBLE
            setupObjectsChart(objectsBarChartCurrent, currentClassCountMap)
        } else {
            objectsBarChartCurrent.visibility = View.GONE
            objectChartTitleCurrent.visibility = View.GONE
        }

        if (historicalClassCountMap.isNotEmpty()) {
            objectsBarChartHistorical.visibility = View.VISIBLE
            objectChartTitleHistorical.visibility = View.VISIBLE
            setupObjectsChart(objectsBarChartHistorical, historicalClassCountMap)
        } else {
            objectsBarChartHistorical.visibility = View.GONE
            objectChartTitleHistorical.visibility = View.GONE
        }

        if (historicalWarningCounts.isNotEmpty()) {
            warningsBarChart.visibility = View.VISIBLE
            warningChartTitle.visibility = View.VISIBLE
            setupWarningsChart(warningsBarChart, historicalWarningCounts)
        } else {
            warningsBarChart.visibility = View.GONE
            warningChartTitle.visibility = View.GONE
        }

        AlertDialog.Builder(this)
            .setTitle("Detection Report")
            .setView(dialogView)
            .setPositiveButton("OK", null)
            .setCancelable(true)
            .show()
    }

    private fun setupDurationChart(chart: PieChart) {
        val entries = ArrayList<PieEntry>()
        val totalDuration = (cumulativeNoTrafficDurationMs + cumulativeLightDurationMs + cumulativeModerateDurationMs + cumulativeHeavyDurationMs).toFloat()

        if (totalDuration > 0) {
            if (cumulativeNoTrafficDurationMs > 0) entries.add(PieEntry(cumulativeNoTrafficDurationMs.toFloat() / totalDuration * 100f, "No Traffic (${formatDuration(cumulativeNoTrafficDurationMs)})"))
            if (cumulativeLightDurationMs > 0) entries.add(PieEntry(cumulativeLightDurationMs.toFloat() / totalDuration * 100f, "Light (${formatDuration(cumulativeLightDurationMs)})"))
            if (cumulativeModerateDurationMs > 0) entries.add(PieEntry(cumulativeModerateDurationMs.toFloat() / totalDuration * 100f, "Moderate (${formatDuration(cumulativeModerateDurationMs)})"))
            if (cumulativeHeavyDurationMs > 0) entries.add(PieEntry(cumulativeHeavyDurationMs.toFloat() / totalDuration * 100f, "Heavy (${formatDuration(cumulativeHeavyDurationMs)})"))
        } else {
            entries.add(PieEntry(100f, "No Traffic Data Yet"))
        }

        if (entries.isEmpty()) {
            chart.visibility = View.GONE
            return
        }
        chart.visibility = View.VISIBLE


        val dataSet = PieDataSet(entries, "").apply {
            val chartColors = mutableListOf<Int>()
            // Add colors based on which entries are present
            if (entries.any { it.label.startsWith("No Traffic") }) chartColors.add(ColorTemplate.rgb("#AAAAAA")) // Grey for No Traffic
            if (entries.any { it.label.startsWith("Light") }) chartColors.add(ColorTemplate.rgb("#90EE90"))      // Light Green
            if (entries.any { it.label.startsWith("Moderate") }) chartColors.add(ColorTemplate.rgb("#FFD700"))   // Gold/Yellow
            if (entries.any { it.label.startsWith("Heavy") }) chartColors.add(ColorTemplate.rgb("#FF6347"))       // Tomato/Red

            // Fallback if no specific colors were added (e.g., only "No Traffic Data Yet") or not enough colors
            if (chartColors.isEmpty() && entries.isNotEmpty()) {
                chartColors.add(ColorTemplate.rgb("#AAAAAA")) // Default color for "No Traffic Data Yet"
            } else if (chartColors.size < entries.size) {
                // Add more generic colors if specific ones weren't enough for all actual entries
                val remainingNeeded = entries.size - chartColors.size
                ColorTemplate.MATERIAL_COLORS.take(remainingNeeded).forEach { color ->
                    if (!chartColors.contains(color)) chartColors.add(color) // Avoid duplicates if possible
                }
                // If still not enough (highly unlikely with MATERIAL_COLORS), just repeat last
                while(chartColors.size < entries.size) {
                    chartColors.add(chartColors.lastOrNull() ?: Color.LTGRAY)
                }
            }


            colors = chartColors
            valueTextColor = Color.BLACK
            valueTextSize = 12f
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    return if (totalDuration > 0 && entries.any{ e -> e.value == value && !e.label.contains("No Traffic Data Yet")})
                        String.format(Locale.getDefault(), "%.1f%%", value)
                    else "" // Don't show percentage for "No Traffic Data Yet" or if total is 0
                }
            }
        }

        chart.apply {
            data = PieData(dataSet)
            description.isEnabled = false
            isDrawHoleEnabled = true
            holeRadius = 40f
            transparentCircleRadius = 45f
            setEntryLabelColor(Color.BLACK)
            setEntryLabelTextSize(10f)
            legend.apply {
                isEnabled = true
                verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
                horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
                orientation = Legend.LegendOrientation.HORIZONTAL
                setDrawInside(false)
                isWordWrapEnabled = true
            }
            animateY(1000, Easing.EaseInOutQuad)
            invalidate()
        }
    }


    private fun setupObjectsChart(chart: BarChart, dataMap: Map<String, Int>) {
        val entries = ArrayList<BarEntry>()
        val labels = ArrayList<String>()
        var index = 0f

        dataMap.toSortedMap().forEach { (label, count) ->
            entries.add(BarEntry(index, count.toFloat()))
            labels.add(label)
            index++
        }

        if (entries.isEmpty()) {
            chart.visibility = View.GONE; return
        } else {
            chart.visibility = View.VISIBLE
        }

        val dataSet = BarDataSet(entries, "Object Count").apply {
            colors = ColorTemplate.VORDIPLOM_COLORS.toList()
            valueTextColor = Color.BLACK
            valueTextSize = 12f
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String = value.toInt().toString()
            }
        }

        chart.apply {
            data = BarData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.apply {
                valueFormatter = IndexAxisValueFormatter(labels)
                position = XAxis.XAxisPosition.BOTTOM
                granularity = 1f
                setDrawGridLines(false)
                labelRotationAngle = -45f
                textColor = Color.DKGRAY
                textSize = 10f
            }
            axisLeft.apply {
                axisMinimum = 0f
                granularity = 1f
                setDrawGridLines(true)
                textColor = Color.DKGRAY
            }
            axisRight.isEnabled = false
            setFitBars(true)
            animateY(1000, Easing.EaseInOutQuad)
            invalidate()
        }
    }

    private fun setupWarningsChart(chart: BarChart, dataMap: Map<String, Int>) {
        val entries = ArrayList<BarEntry>()
        val labels = ArrayList<String>()
        var index = 0f
        val chartColors = ColorTemplate.JOYFUL_COLORS.toList()

        dataMap.toSortedMap().forEach { (label, count) ->
            entries.add(BarEntry(index, count.toFloat()))
            labels.add(label)
            index++
        }

        if (entries.isEmpty()) {
            chart.visibility = View.GONE; return
        } else {
            chart.visibility = View.VISIBLE
        }

        val dataSet = BarDataSet(entries, "Total Danger Zone Entries").apply {
            colors = chartColors
            valueTextColor = Color.BLACK
            valueTextSize = 12f
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String = value.toInt().toString()
            }
        }

        chart.apply {
            data = BarData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.apply {
                valueFormatter = IndexAxisValueFormatter(labels)
                position = XAxis.XAxisPosition.BOTTOM
                granularity = 1f
                setDrawGridLines(false)
                labelRotationAngle = -45f
                textColor = Color.DKGRAY
                textSize = 10f
            }
            axisLeft.apply {
                axisMinimum = 0f
                granularity = 1f
                setDrawGridLines(true)
                textColor = Color.DKGRAY
            }
            axisRight.isEnabled = false
            setFitBars(true)
            animateY(1000, Easing.EaseInOutQuad)
            invalidate()
        }
    }

    private fun formatDuration(milliseconds: Long): String {
        if (milliseconds < 0) return "N/A"
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds)
        val hours = TimeUnit.SECONDS.toHours(totalSeconds)
        val minutes = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60
        val seconds = totalSeconds % 60

        return when {
            hours > 0 -> String.format(Locale.getDefault(), "%d hr %02d min %02d sec", hours, minutes, seconds)
            minutes > 0 -> String.format(Locale.getDefault(), "%d min %02d sec", minutes, seconds)
            else -> String.format(Locale.getDefault(), "%d sec", seconds)
        }
    }

    override fun onDangerStateChanged(isInDanger: Boolean) {
        runOnUiThread {
            binding.dangerAlertText.visibility = if (isInDanger) View.VISIBLE else View.GONE

            if (isInDanger) {
                if (isBeepEnabled) {
                    if (mediaPlayer?.isPlaying == false) {
                        try {
                            mediaPlayer?.start()
                        } catch (e: IllegalStateException) { Log.e(TAG, "MediaPlayer start failed", e) }
                    }
                } else {
                    if (mediaPlayer?.isPlaying == true) { mediaPlayer?.pause(); mediaPlayer?.seekTo(0) }
                }

                if (isVibrationEnabled && vibrator?.hasVibrator() == true) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator?.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator?.vibrate(300)
                    }
                }
            } else {
                if (mediaPlayer?.isPlaying == true) {
                    mediaPlayer?.pause()
                    mediaPlayer?.seekTo(0)
                }
                vibrator?.cancel()
            }
        }
    }

    companion object {
        private const val TAG = "MainActivityYolo"
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val PREFS_NAME = "DetectorSettings"
        private const val KEY_BEEP_ENABLED = "beepEnabled"
        private const val KEY_VIBRATION_ENABLED = "vibrationEnabled"
        private const val KEY_TRAIL_ENABLED = "trailEnabled"
    }
}