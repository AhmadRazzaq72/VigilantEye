package com.ahmad.vigilanteye

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Custom View to draw the object detection bounding boxes, danger zone, and object trails.
 */
class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var trackedObjects = listOf<TrackedObject>()
    private var boxPaint = Paint()
    private var textBackgroundPaint = Paint()
    private var textPaint = Paint()
    private var dangerZonePaint = Paint()
    private var dangerBoxPaint = Paint()
    private var trailPaint = Paint()
    private var bounds = Rect()

    private var listener: DangerZoneStateListener? = null

    /**
     * Listener interface for danger zone state changes.
     */
    interface DangerZoneStateListener {
        /**
         * Called when the danger zone state changes.
         *
         * @param isInDanger True if any object is in the danger zone, false otherwise.
         */
        fun onDangerStateChanged(isInDanger: Boolean)
    }

    private var drawTrails: Boolean = true
    private val drawRect = RectF()

    init {
        initPaints()
    }

    /**
     * Initializes the paints used for drawing.
     */
    private fun initPaints() {
        context?.let { ctx ->
            boxPaint.color = Color.BLUE
            boxPaint.strokeWidth = 6F
            boxPaint.style = Paint.Style.STROKE

            textBackgroundPaint.color = Color.BLACK
            textBackgroundPaint.style = Paint.Style.FILL
            textBackgroundPaint.textSize = 30f

            textPaint.color = Color.WHITE
            textPaint.style = Paint.Style.FILL
            textPaint.textSize = 30f
            textPaint.isAntiAlias = true

            dangerZonePaint.color = Color.RED
            dangerZonePaint.strokeWidth = 4f
            dangerZonePaint.style = Paint.Style.STROKE
            dangerZonePaint.alpha = 150

            dangerBoxPaint.color = Color.RED
            dangerBoxPaint.strokeWidth = 8F
            dangerBoxPaint.style = Paint.Style.STROKE

            trailPaint.color = Color.argb(200, 144, 238, 144) // Light green, slightly transparent
            trailPaint.strokeWidth = 5F
            trailPaint.style = Paint.Style.STROKE
            trailPaint.isAntiAlias = true
        }
    }

    /**
     * Clears the overlay by removing all tracked objects.
     */
    fun clear() {
        if (trackedObjects.isNotEmpty()) {
            trackedObjects = emptyList<TrackedObject>()
            listener?.onDangerStateChanged(false)
        }
        postInvalidate()
    }

    /**
     * Sets the list of tracked objects to be drawn on the overlay.
     *
     * @param objects The list of TrackedObject to draw.
     */
    fun setResults(objects: List<TrackedObject>) {
        trackedObjects = objects
        val dangerThreshold = 1.0f - Constants.DANGER_ZONE_HEIGHT_RATIO // Use constant
        val currentlyInDanger = trackedObjects.any { it.boundingBox.bottom >= dangerThreshold }
        listener?.onDangerStateChanged(currentlyInDanger)
        postInvalidate()
    }

    /**
     * Sets whether to draw the trails of tracked objects.
     *
     * @param enabled True to enable trail drawing, false to disable.
     */
    fun setDrawTrailsEnabled(enabled: Boolean) {
        this.drawTrails = enabled
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0 || viewHeight <= 0) return

        // Draw the danger zone
        val dangerThresholdNormalized = 1.0f - Constants.DANGER_ZONE_HEIGHT_RATIO // Use constant
        val dangerZoneTopY = viewHeight * dangerThresholdNormalized
        dangerZonePaint.alpha = 100
        dangerZonePaint.style = Paint.Style.FILL
        canvas.drawRect(0f, dangerZoneTopY, viewWidth, viewHeight, dangerZonePaint)
        dangerZonePaint.style = Paint.Style.STROKE
        dangerZonePaint.strokeWidth = 4f
        dangerZonePaint.alpha = 255
        canvas.drawRect(0f, dangerZoneTopY, viewWidth, viewHeight, dangerZonePaint)

        trackedObjects.forEach { trackedObject ->
            val resultBox = trackedObject.boundingBox
            val isInDangerZone = resultBox.bottom >= dangerThresholdNormalized

            drawRect.left = resultBox.left * viewWidth
            drawRect.top = resultBox.top * viewHeight
            drawRect.right = resultBox.right * viewWidth
            drawRect.bottom = resultBox.bottom * viewHeight

            val currentPaint = if (isInDangerZone) dangerBoxPaint else boxPaint
            canvas.drawRect(drawRect, currentPaint)

            val label = trackedObject.label
            textPaint.getTextBounds(label, 0, label.length, bounds)
            val textWidth = textPaint.measureText(label)
            val textHeight = bounds.height().toFloat()
            val textBgLeft = drawRect.left + currentPaint.strokeWidth / 2f
            val textBgTop = drawRect.top + currentPaint.strokeWidth / 2f
            val textBgRight = textBgLeft + textWidth + BOUNDING_RECT_TEXT_PADDING * 2
            val textBgBottom = textBgTop + textHeight + BOUNDING_RECT_TEXT_PADDING * 2
            textBackgroundPaint.alpha = 180
            canvas.drawRect(textBgLeft, textBgTop, textBgRight, textBgBottom, textBackgroundPaint)
            canvas.drawText(
                label,
                textBgLeft + BOUNDING_RECT_TEXT_PADDING,
                textBgTop + textHeight + BOUNDING_RECT_TEXT_PADDING,
                textPaint
            )

            if (isInDangerZone) {
                val icon = "⚠️"
                val iconWidth = textPaint.measureText(icon)
                canvas.drawText(
                    icon,
                    textBgRight + BOUNDING_RECT_TEXT_PADDING,
                    textBgBottom - BOUNDING_RECT_TEXT_PADDING,
                    textPaint
                )
            }

            if (drawTrails) {
                val trail = trackedObject.trailPoints
                if (trail.size >= 2) {
                    for (i in 0 until trail.size - 1) {
                        val startPoint = trail[i]
                        val endPoint = trail[i + 1]
                        val startX = startPoint.x * viewWidth
                        val startY = startPoint.y * viewHeight
                        val endX = endPoint.x * viewWidth
                        val endY = endPoint.y * viewHeight
                        canvas.drawLine(startX, startY, endX, endY, trailPaint)
                    }
                }
            }
        }
    }

    /**
     * Sets the listener for danger zone state changes.
     *
     * @param listener The listener to set.
     */
    fun setDangerZoneStateListener(listener: DangerZoneStateListener) {
        this.listener = listener
    }

    companion object {
        private const val BOUNDING_RECT_TEXT_PADDING = 8f
        // DANGER_ZONE_HEIGHT_RATIO moved to Constants.kt
    }
}