package com.ahmad.vigilanteye

object Constants {
    const val MODEL_PATH = "model.tflite"
    const val LABELS_PATH = "labels.txt"

    // Detector Constants
    const val CONFIDENCE_THRESHOLD = 0.3F
    const val IOU_THRESHOLD = 0.5F

    // MainActivity Constants
    const val LIGHT_TRAFFIC_MAX_OBJECTS = 5
    const val MODERATE_TRAFFIC_MAX_OBJECTS = 8
    const val LOST_TRACK_TIMEOUT_MS = 2000L // 2 seconds
    const val PIXEL_MATCH_THRESHOLD_RATIO = 0.15f // 15% of view width, adjust as needed
    const val DEFAULT_PIXEL_MATCH_THRESHOLD = 50f // Default if view width is 0

    // OverlayView Constant
    const val DANGER_ZONE_HEIGHT_RATIO = 0.2f // Bottom 20%

    // TrackedObject Constant
    const val MAX_TRAIL_POINTS = 30 // Maximum number of points in the trail
}