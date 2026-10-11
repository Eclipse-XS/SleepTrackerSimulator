package com.example.sleeptrackersimulator.ml

import com.example.sleeptrackersimulator.core.model.SensorSample

object MovementPreprocessor {
    const val CHANNELS = 3
    const val CLIP_METERS_PER_SECOND_SQUARED = 4f

    fun transform(samples: List<SensorSample>): Array<Array<FloatArray>> {
        require(samples.size == SensorWindowBuffer.WINDOW_SIZE) {
            "Expected ${SensorWindowBuffer.WINDOW_SIZE} samples, got ${samples.size}"
        }
        val means = floatArrayOf(
            samples.sumOf { it.x.toDouble() }.toFloat() / samples.size,
            samples.sumOf { it.y.toDouble() }.toFloat() / samples.size,
            samples.sumOf { it.z.toDouble() }.toFloat() / samples.size,
        )
        val window = Array(samples.size) { index ->
            val sample = samples[index]
            floatArrayOf(sample.x, sample.y, sample.z).mapIndexed { channel, value ->
                ((value - means[channel]).coerceIn(
                    -CLIP_METERS_PER_SECOND_SQUARED,
                    CLIP_METERS_PER_SECOND_SQUARED,
                ) / CLIP_METERS_PER_SECOND_SQUARED)
            }.toFloatArray()
        }
        return arrayOf(window)
    }
}
