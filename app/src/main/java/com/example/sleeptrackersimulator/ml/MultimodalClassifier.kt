package com.example.sleeptrackersimulator.ml

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface MultimodalClassifier : Closeable {
    fun classify(
        accelerometerWindow: Array<FloatArray>,
        heartRateBpm: Float?,
        isHeartRateFresh: Boolean,
        windowEndTimestampNanos: Long = System.nanoTime(),
    ): ActivityPrediction

    companion object {
        operator fun invoke(context: Context): MultimodalClassifier = TfliteMultimodalClassifier(context)
    }
}

class TfliteMultimodalClassifier(context: Context) : MultimodalClassifier {
    private val interpreterLock = Any()
    @Volatile private var closed = false
    private val model = context.assets.open("multimodal_classifier.tflite").use { stream ->
        stream.readBytes().let { bytes ->
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes)
                rewind()
            }
        }
    }
    private val interpreter = Interpreter(model)
    private val labels = context.assets.open("activity_labels.txt").bufferedReader().useLines { lines ->
        lines.filter(String::isNotBlank).map(String::trim).toList()
    }

    private val accelInputIndex: Int
    private val hrInputIndex: Int

    init {
        require(labels == ActivityClass.entries.map(Enum<*>::name)) { "Model labels do not match application classes" }
        require(interpreter.inputTensorCount == 2) { "Multimodal model must have exactly 2 input tensors" }
        require(interpreter.outputTensorCount == 1) { "Multimodal model must have 1 output tensor" }

        val tensor0Shape = interpreter.getInputTensor(0).shape()
        val tensor1Shape = interpreter.getInputTensor(1).shape()

        if (tensor0Shape.contentEquals(intArrayOf(1, 20, 3))) {
            accelInputIndex = 0
            hrInputIndex = 1
            require(tensor1Shape.contentEquals(intArrayOf(1, 2))) { "Expected HR input tensor of shape [1, 2]" }
        } else if (tensor1Shape.contentEquals(intArrayOf(1, 20, 3))) {
            accelInputIndex = 1
            hrInputIndex = 0
            require(tensor0Shape.contentEquals(intArrayOf(1, 2))) { "Expected HR input tensor of shape [1, 2]" }
        } else {
            throw IllegalArgumentException("Could not find accelerometer input [1, 20, 3] in model")
        }

        require(interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1, labels.size))) {
            "Unexpected model output tensor"
        }
    }

    override fun classify(
        accelerometerWindow: Array<FloatArray>,
        heartRateBpm: Float?,
        isHeartRateFresh: Boolean,
        windowEndTimestampNanos: Long,
    ): ActivityPrediction {
        require(accelerometerWindow.size == 20 && accelerometerWindow.all { it.size == 3 && it.all(Float::isFinite) }) {
            "Accelerometer window must contain 20 finite 3D samples"
        }

        val accelInput = Array(1) { Array(20) { FloatArray(3) } }
        accelerometerWindow.forEachIndexed { index, sample -> sample.copyInto(accelInput[0][index]) }

        val hrInput = Array(1) { FloatArray(2) }
        if (heartRateBpm != null && isHeartRateFresh && heartRateBpm.isFinite() && heartRateBpm in 30f..240f) {
            val normBpm = (heartRateBpm - 60.0f) / 40.0f
            hrInput[0][0] = normBpm
            hrInput[0][1] = 1.0f
        } else {
            hrInput[0][0] = 0.0f
            hrInput[0][1] = 0.0f
        }

        val outputArray = Array(1) { FloatArray(labels.size) }

        val inputs = arrayOfNulls<Any>(2)
        inputs[accelInputIndex] = accelInput
        inputs[hrInputIndex] = hrInput

        val outputs = mapOf(0 to outputArray)

        val elapsed = synchronized(interpreterLock) {
            check(!closed) { "Multimodal classifier is closed" }
            val started = System.nanoTime()
            interpreter.runForMultipleInputsOutputs(inputs, outputs)
            System.nanoTime() - started
        }

        require(outputArray[0].all(Float::isFinite)) { "Model returned non-finite probabilities" }
        require(outputArray[0].all { it in 0f..1f }) { "Model returned invalid probabilities" }
        require(outputArray[0].sum() in 0.999f..1.001f) { "Model probabilities are not normalized" }

        val probabilities = ActivityClass.entries.associateWith { outputArray[0][it.ordinal] }
        val best = probabilities.maxBy { it.value }

        return ActivityPrediction(
            predictionId = "prediction-multi-$windowEndTimestampNanos",
            windowEndTimestampNanos = windowEndTimestampNanos,
            activity = best.key,
            confidence = best.value,
            probabilities = probabilities,
            inferenceNanos = elapsed,
        )
    }

    override fun close() = synchronized(interpreterLock) {
        if (!closed) {
            closed = true
            interpreter.close()
        }
    }
}
