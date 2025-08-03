package com.ahmad.vigilanteye

/**
 * Data class to represent a bounding box with detection details.
 *
 * @property x1 The normalized x-coordinate of the top-left corner (0-1).
 * @property y1 The normalized y-coordinate of the top-left corner (0-1).
 * @property x2 The normalized x-coordinate of the bottom-right corner (0-1).
 * @property y2 The normalized y-coordinate of the bottom-right corner (0-1).
 * @property cx The normalized x-coordinate of the center (0-1).
 * @property cy The normalized y-coordinate of the center (0-1).
 * @property w The normalized width (0-1).
 * @property h The normalized height (0-1).
 * @property cnf The confidence score of the detection.
 * @property cls The class index of the detected object.
 * @property clsName The class name of the detected object.
 */
data class BoundingBox(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val cnf: Float,
    val cls: Int,
    val clsName: String
)