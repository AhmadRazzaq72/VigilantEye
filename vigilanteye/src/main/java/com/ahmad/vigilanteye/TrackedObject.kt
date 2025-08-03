package com.ahmad.vigilanteye

import android.graphics.PointF
import android.graphics.RectF

// Define TrackStatus enum here. It can also be defined inside MainActivity if preferred.
enum class TrackStatus { ACTIVE, LOST }

/**
 * Represents a single tracked object with its bounding box, ID, trail, status, and last seen time.
 *
 * @param label The class label of the object.
 * @param boundingBox The bounding box of the object in normalized coordinates (0-1).
 * @param id A unique ID assigned to the tracked object.
 * @param trailPoints A list of normalized points representing the object's movement trail.
 * @param wasInDangerZone A flag indicating if the object was in the danger zone in the previous frame.
 * @param status The current tracking status of the object (ACTIVE or LOST).
 * @param lastSeenTime Timestamp of the last time the object was seen or updated.
 */
data class TrackedObject(
    val label: String,
    var boundingBox: RectF,
    val id: Int,
    val trailPoints: MutableList<PointF> = mutableListOf(),
    var wasInDangerZone: Boolean = false,
    var status: TrackStatus = TrackStatus.ACTIVE, // New: Tracking status
    var lastSeenTime: Long = System.currentTimeMillis() // New: Explicit last seen time
) {
    companion object {
        // MAX_TRAIL_POINTS is now in Constants.kt
    }

    /**
     * Updates the tracked object with a new bounding box and adds the center to the trail.
     * Also updates the lastSeenTime.
     *
     * @param newBox The new bounding box in normalized coordinates (0-1).
     */
    fun update(newBox: RectF) {
        boundingBox = newBox
        lastSeenTime = System.currentTimeMillis() // Update when object is seen/updated

        val centerX = newBox.centerX()
        val centerY = newBox.centerY()
        trailPoints.add(PointF(centerX, centerY))

        while (trailPoints.size > Constants.MAX_TRAIL_POINTS) {
            trailPoints.removeAt(0)
        }
    }

    init {
        // Add initial point on creation and ensure lastSeenTime is set
        trailPoints.add(PointF(boundingBox.centerX(), boundingBox.centerY()))
        lastSeenTime = System.currentTimeMillis()
    }
}