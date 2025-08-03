package com.ahmad.vigilanteye

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log // Import Log for error reporting
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.max


/**
 * Detector class to perform object detection using a TensorFlow Lite model.
 *
 * @param context The application context.
 * @param modelPath The path to the TensorFlow Lite model file in the assets folder.
 * @param labelPath The path to the labels file in the assets folder.
 * @param detectorListener Listener to receive detection results.
 */
class Detector(
    private val context: Context,
    private val modelPath: String,
    private val labelPath: String,
    private val detectorListener: DetectorListener
) {

    private var interpreter: Interpreter? = null
    private var labels = mutableListOf<String>()

    private var tensorWidth = 0
    private var tensorHeight = 0
    private var numChannel = 0
    private var numElements = 0

    private val imageProcessor = ImageProcessor.Builder()
        .add(NormalizeOp(INPUT_MEAN, INPUT_STANDARD_DEVIATION))
        .add(CastOp(INPUT_IMAGE_TYPE))
        .build()


    /**
     * Sets up the TensorFlow Lite interpreter and loads the labels.
     */
    fun setup() {
        try {
            val model = FileUtil.loadMappedFile(context, modelPath)
            val options = Interpreter.Options()
            options.numThreads = 4
            interpreter = Interpreter(model, options)

            val inputShape = interpreter?.getInputTensor(0)?.shape() ?: run {
                Log.e(TAG, "Failed to get input tensor shape.")
                return
            }
            val outputShape = interpreter?.getOutputTensor(0)?.shape() ?: run {
                Log.e(TAG, "Failed to get output tensor shape.")
                return
            }

            tensorWidth = inputShape[1]
            tensorHeight = inputShape[2]
            numChannel = outputShape[1]
            numElements = outputShape[2]

            loadLabels()

        } catch (e: IOException) {
            Log.e(TAG, "Error setting up detector: ${e.message}")
            e.printStackTrace()
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Error setting up interpreter: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Loads labels from the labels file in the assets folder.
     */
    private fun loadLabels() {
        try {
            val inputStream: InputStream = context.assets.open(labelPath)
            val reader = BufferedReader(InputStreamReader(inputStream))

            var line: String? = reader.readLine()
            while (line != null && line != "") {
                labels.add(line)
                line = reader.readLine()
            }

            reader.close()
            inputStream.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error loading labels: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Clears the resources used by the detector.
     */
    fun clear() {
        interpreter?.close()
        interpreter = null
    }

    /**
     * Performs object detection on the given bitmap frame.
     *
     * @param frame The bitmap frame to detect objects in.
     */
    fun detect(frame: Bitmap) {
        if (interpreter == null) {
            Log.e(TAG, "Detector not set up yet.")
            return
        }
        if (tensorWidth == 0 || tensorHeight == 0 || numChannel == 0 || numElements == 0) {
            Log.e(TAG, "Tensor dimensions not initialized.")
            return
        }

        var inferenceTime = SystemClock.uptimeMillis()

        val resizedBitmap = Bitmap.createScaledBitmap(frame, tensorWidth, tensorHeight, false)

        val tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(resizedBitmap)
        val processedImage = imageProcessor.process(tensorImage)
        val imageBuffer = processedImage.buffer

        val output = TensorBuffer.createFixedSize(intArrayOf(1 , numChannel, numElements), OUTPUT_IMAGE_TYPE)
        interpreter?.run(imageBuffer, output.buffer)


        val bestBoxes = bestBox(output.floatArray)
        inferenceTime = SystemClock.uptimeMillis() - inferenceTime


        if (bestBoxes == null || bestBoxes.isEmpty()) {
            detectorListener.onEmptyDetect()
            return
        }

        detectorListener.onDetect(bestBoxes, inferenceTime)
    }

    /**
     * Processes the raw model output to extract the best bounding boxes.
     *
     * @param array The float array containing the model output.
     * @return A list of detected BoundingBox objects, or null if none are found above the confidence threshold.
     */


    // applying NMS algorithm

    private fun bestBox(array: FloatArray) : List<BoundingBox>? {

        val boundingBoxes = mutableListOf<BoundingBox>()

        for (c in 0 until numElements) {
            var maxConf = -1.0f
            var maxIdx = -1
            var j = 4 // Start from index 4 for class probabilities
            var arrayIdx = c + numElements * j
            while (j < numChannel){
                if (array[arrayIdx] > maxConf) {
                    maxConf = array[arrayIdx]
                    maxIdx = j - 4 // Class index is relative to the start of class probabilities
                }
                j++
                arrayIdx += numElements
            }

            if (maxConf > Constants.CONFIDENCE_THRESHOLD) { // Use constant
                if (maxIdx < 0 || maxIdx >= labels.size) {
                    Log.e(TAG, "Invalid class index: $maxIdx")
                    continue
                }
                val clsName = labels[maxIdx]
                val cx = array[c] // 0
                val cy = array[c + numElements] // 1
                val w = array[c + numElements * 2] // 2
                val h = array[c + numElements * 3] // 3
                val x1 = cx - (w/2F)
                val y1 = cy - (h/2F)
                val x2 = cx + (w/2F)
                val y2 = cy + (h/2F)

                // Basic boundary checks
                if (x1 < 0F || x1 > 1F || y1 < 0F || y1 > 1F || x2 < 0F || x2 > 1F || y2 < 0F || y2 > 1F) {
                    // Log.w(TAG, "Bounding box coordinates out of bounds: x1=$x1, y1=$y1, x2=$x2, y2=$y2")
                    continue
                }


                boundingBoxes.add(
                    BoundingBox(
                        x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                        cx = cx, cy = cy, w = w, h = h,
                        cnf = maxConf, cls = maxIdx, clsName = clsName
                    )
                )
            }
        }

        if (boundingBoxes.isEmpty()) return null

        return applyNMS(boundingBoxes)
    }

    /**
     * Applies Non-Maximum Suppression (NMS) to the detected bounding boxes.
     *
     * @param boxes The list of detected bounding boxes.
     * @return A mutable list of bounding boxes after applying NMS.
     */
    private fun applyNMS(boxes: List<BoundingBox>) : MutableList<BoundingBox> {
        val sortedBoxes = boxes.sortedByDescending { it.cnf }.toMutableList()
        val selectedBoxes = mutableListOf<BoundingBox>()

        while(sortedBoxes.isNotEmpty()) {
            val first = sortedBoxes.removeAt(0) // Remove the first element
            selectedBoxes.add(first)

            val iterator = sortedBoxes.iterator()
            while (iterator.hasNext()) {
                val nextBox = iterator.next()
                val iou = calculateIoU(first, nextBox)
                if (iou >= Constants.IOU_THRESHOLD) { // Use constant
                    iterator.remove()
                }
            }
        }

        return selectedBoxes
    }

    /**
     * Calculates the Intersection over Union (IoU) of two bounding boxes.
     *
     * @param box1 The first bounding box.
     * @param box2 The second bounding box.
     * @return The IoU value.
     */
    private fun calculateIoU(box1: BoundingBox, box2: BoundingBox): Float {
        val x1 = maxOf(box1.x1, box2.x1)
        val y1 = maxOf(box1.y1, box2.y1)
        val x2 = minOf(box1.x2, box2.x2)
        val y2 = minOf(box1.y2, box2.y2)

        val intersectionArea = max(0F, x2 - x1) * max(0F, y2 - y1)
        val box1Area = (box1.x2 - box1.x1) * (box1.y2 - box1.y1) // Calculate area from x1,y1,x2,y2
        val box2Area = (box2.x2 - box2.x1) * (box2.y2 - box2.y1) // Calculate area from x1,y1,x2,y2

        if (box1Area <= 0F || box2Area <= 0F) return 0F // Handle zero area cases

        return intersectionArea / (box1Area + box2Area - intersectionArea)
    }

    /**
     * Listener interface for detector events.
     */
    interface DetectorListener {
        /**
         * Called when no objects are detected.
         */
        fun onEmptyDetect()

        /**
         * Called when objects are detected.
         *
         * @param boundingBoxes The list of detected bounding boxes.
         * @param inferenceTime The inference time in milliseconds.
         */
        fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long)
    }

    companion object {
        private const val TAG = "Detector" // Added TAG for logging
        private const val INPUT_MEAN = 0f
        private const val INPUT_STANDARD_DEVIATION = 255f
        private val INPUT_IMAGE_TYPE = DataType.FLOAT32
        private val OUTPUT_IMAGE_TYPE = DataType.FLOAT32
        // CONFIDENCE_THRESHOLD and IOU_THRESHOLD moved to Constants.kt
    }
}