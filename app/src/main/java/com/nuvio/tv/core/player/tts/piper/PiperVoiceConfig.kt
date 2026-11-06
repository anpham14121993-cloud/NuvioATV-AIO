package com.nuvio.tv.core.player.tts.piper

import org.json.JSONObject
import java.io.File
import java.text.Normalizer

data class PiperVoiceConfig(
    val phonemeIdMap: Map<String, List<Int>>,
    val phonemeType: String,
    val sampleRate: Int,
    val numSpeakers: Int,
    val noiseScale: Float,
    val lengthScale: Float,
    val noiseW: Float,
    val espeakVoice: String,
) {
    fun getPhonemeId(key: String): Long? {
        return phonemeIdMap[key]?.firstOrNull()?.toLong()
    }

    /**
     * Converts a raw phonemized string into an array of token IDs suitable
     * for Piper's VITS ONNX model inputs.
     */
    fun textToIds(phonemizedText: String): LongArray {
        val startId = getPhonemeId("^")
        val endId = getPhonemeId("$")
        val padId = getPhonemeId("_")

        val normalized = Normalizer.normalize(phonemizedText, Normalizer.Form.NFD)
        val result = ArrayList<Long>()

        if (startId != null) result.add(startId)
        if (padId != null) result.add(padId)

        var validCount = 0
        for (i in 0 until normalized.length) {
            val ch = normalized[i].toString()
            val id = getPhonemeId(ch)
            if (id != null) {
                result.add(id)
                if (padId != null) result.add(padId)
                validCount++
            }
        }

        if (endId != null) result.add(endId)

        return if (validCount > 0 && result.isNotEmpty()) result.toLongArray() else LongArray(0)
    }

    companion object {
        fun fromFile(file: File): PiperVoiceConfig {
            val jsonStr = file.readText(Charsets.UTF_8)
            val root = JSONObject(jsonStr)

            val idMap = HashMap<String, List<Int>>()
            val optIdMap = root.optJSONObject("phoneme_id_map")
            if (optIdMap != null) {
                val keys = optIdMap.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val arr = optIdMap.optJSONArray(key)
                    if (arr != null) {
                        val ids = ArrayList<Int>(arr.length())
                        for (i in 0 until arr.length()) {
                            ids.add(arr.getInt(i))
                        }
                        idMap[key] = ids
                    }
                }
            }

            val inference = root.optJSONObject("inference")
            val noiseScale = inference?.optDouble("noise_scale", 0.667)?.toFloat() ?: 0.667f
            val lengthScale = inference?.optDouble("length_scale", 1.0)?.toFloat() ?: 1.0f
            val noiseW = inference?.optDouble("noise_w", 0.8)?.toFloat() ?: 0.8f

            val audio = root.optJSONObject("audio")
            val sampleRate = audio?.optInt("sample_rate", 22050) ?: 22050

            val espeak = root.optJSONObject("espeak")
            val espeakVoice = espeak?.optString("voice", "vi") ?: "vi"

            val numSpeakers = root.optInt("num_speakers", 1)
            val phonemeType = root.optString("phoneme_type", "espeak")

            return PiperVoiceConfig(
                phonemeIdMap = idMap,
                phonemeType = phonemeType,
                sampleRate = sampleRate,
                numSpeakers = numSpeakers,
                noiseScale = noiseScale,
                lengthScale = lengthScale,
                noiseW = noiseW,
                espeakVoice = espeakVoice,
            )
        }
    }
}
