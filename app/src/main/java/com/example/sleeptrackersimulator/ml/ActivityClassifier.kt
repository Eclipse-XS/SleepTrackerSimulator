package com.example.sleeptrackersimulator.ml

import android.content.Context
import com.example.sleeptrackersimulator.core.model.SensorSample
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface MovementInference : Closeable {
    fun classify(samples: List<SensorSample>): MlPrediction
}

class ActivityClassifier(context: Context) : MovementInference {
    private val modelBuffer = context.assets.open(MODEL_FILENAME).use { stream ->
        val bytes = stream.readBytes()
        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
            put(bytes)
            rewind()
        }
    }
    private val interpreter = Interpreter(
        modelBuffer,
        Interpreter.Options().apply { setNumThreads(2) },
    )

    init {
        val input = interpreter.getInputTensor(0)
        val output = interpreter.getOutputTensor(0)
        require(input.shape().contentEquals(intArrayOf(1, SensorWindowBuffer.WINDOW_SIZE, 3)))
        require(output.shape().contentEquals(intArrayOf(1, MovementClass.entries.size)))
    }

    override fun classify(samples: List<SensorSample>): MlPrediction {
        val input = MovementPreprocessor.transform(samples)
        val output = Array(1) { FloatArray(MovementClass.entries.size) }
        val started = System.nanoTime()
        interpreter.run(input, output)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000.0
        val scores = output[0]
        require(scores.all { it.isFinite() }) { "Model returned non-finite scores" }
        val classIndex = scores.indices.maxByOrNull(scores::get) ?: error("Empty model output")
        return MlPrediction(
            predictedClass = MovementClass.entries[classIndex],
            probabilities = scores.toList(),
            inferenceMillis = elapsedMillis,
            timestampNanos = samples.last().timestampNanos,
        )
    }

    override fun close() = interpreter.close()

    companion object { const val MODEL_FILENAME = "rest_movement_classifier.tflite" }
}
