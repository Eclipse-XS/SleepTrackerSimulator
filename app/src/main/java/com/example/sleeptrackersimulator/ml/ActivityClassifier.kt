package com.example.sleeptrackersimulator.ml

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface ActivityClassifier : Closeable {
    fun classify(window: Array<FloatArray>, windowEndTimestampNanos: Long = System.nanoTime()): ActivityPrediction

    companion object {
        operator fun invoke(context: Context): ActivityClassifier = TfliteActivityClassifier(context)
    }
}

class TfliteActivityClassifier(context: Context) : ActivityClassifier {
    private val interpreterLock = Any()
    @Volatile private var closed = false
    private val model = context.assets.open("activity_classifier.tflite").use { stream ->
        stream.readBytes().let { bytes ->
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes)
                rewind()
            }
        }
    }
    private val interpreter = Interpreter(model)
    private val labels = context.assets.open("activity_labels.txt").bufferedReader().useLines {
        lines -> lines.filter(String::isNotBlank).map(String::trim).toList()
    }

    init {
        require(labels == ActivityClass.entries.map(Enum<*>::name)) { "Model labels do not match application classes" }
        require(interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1, 20, 3))) {
            "Unexpected model input tensor"
        }
        require(interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1, labels.size))) {
            "Unexpected model output tensor"
        }
    }

    override fun classify(window: Array<FloatArray>, windowEndTimestampNanos: Long): ActivityPrediction {
        require(window.size == 20 && window.all { it.size == 3 && it.all(Float::isFinite) })
        val input = Array(1) { Array(20) { FloatArray(3) } }
        window.forEachIndexed { index, sample -> sample.copyInto(input[0][index]) }
        val output = Array(1) { FloatArray(labels.size) }
        val elapsed = synchronized(interpreterLock) {
            check(!closed) { "Activity classifier is closed" }
            val started = System.nanoTime()
            interpreter.run(input, output)
            System.nanoTime() - started
        }
        require(output[0].all(Float::isFinite)) { "Model returned non-finite probabilities" }
        require(output[0].all { it in 0f..1f }) { "Model returned invalid probabilities" }
        require(output[0].sum() in 0.999f..1.001f) { "Model probabilities are not normalized" }
        val probabilities = ActivityClass.entries.associateWith { output[0][it.ordinal] }
        val best = probabilities.maxBy { it.value }
        return ActivityPrediction(
            predictionId = "prediction-$windowEndTimestampNanos",
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
