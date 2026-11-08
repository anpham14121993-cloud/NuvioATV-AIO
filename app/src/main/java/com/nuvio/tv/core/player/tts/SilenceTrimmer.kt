package com.nuvio.tv.core.player.tts

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min

/**
 * Trims leading and trailing silence from synthesized PCM audio with cosine-taper smoothing
 * matching Vax l26.e.
 *
 * Removes dead air before and after speech to ensure narration starts instantly with
 * zero clicking or pop.
 */
object SilenceTrimmer {
    private const val SILENCE_THRESHOLD = 0.0052f

    fun trimSilence(samples: FloatArray, sampleRate: Int): FloatArray {
        val windowSize = (sampleRate / 100).coerceAtLeast(1) // 10ms window
        val numWindows = samples.size / windowSize
        if (numWindows <= 2) return samples

        // 1. Find start index of speech
        var startWindow = -1
        for (i in 0 until numWindows) {
            val offset = i * windowSize
            var sum = 0f
            for (j in 0 until windowSize) {
                sum += abs(samples[offset + j])
            }
            if ((sum / windowSize) > SILENCE_THRESHOLD) {
                startWindow = i
                break
            }
        }
        if (startWindow < 0) return samples // Entirely silent

        // 2. Find end index of speech
        var endWindow = numWindows - 1
        for (i in (numWindows - 1) downTo startWindow) {
            val offset = i * windowSize
            var sum = 0f
            for (j in 0 until windowSize) {
                sum += abs(samples[offset + j])
            }
            if ((sum / windowSize) > SILENCE_THRESHOLD) {
                endWindow = i
                break
            }
        }

        // Add 45ms safety margin before start and after end
        val marginSamples = (sampleRate * 45) / 1000
        val startIndex = ((startWindow * windowSize) - marginSamples).coerceAtLeast(0)
        val endIndex = (((endWindow + 1) * windowSize) + marginSamples).coerceAtMost(samples.size)

        if (startIndex >= endIndex) return samples

        val trimmed = samples.copyOfRange(startIndex, endIndex)

        // 3. Apply 12ms cosine fade-in and fade-out taper
        val fadeSamples = min((sampleRate * 12) / 1000, trimmed.size / 4)
        if (fadeSamples > 1) {
            val pi = Math.PI
            for (i in 0 until fadeSamples) {
                val factor = (0.5 - 0.5 * cos(i * pi / fadeSamples)).toFloat()
                trimmed[i] *= factor
                val endIdx = trimmed.size - 1 - i
                trimmed[endIdx] *= factor
            }
        }

        return trimmed
    }
}
