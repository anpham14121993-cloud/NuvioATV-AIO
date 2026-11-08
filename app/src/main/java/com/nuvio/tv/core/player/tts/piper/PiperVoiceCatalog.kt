package com.nuvio.tv.core.player.tts.piper

data class PiperVoice(
    val id: String,
    val displayName: String,
    val gender: String, // "F" or "M"
    val description: String = ""
)

object PiperVoiceCatalog {
    const val HF_BASE_URL = "https://huggingface.co/dinhphuongvn/ttsvn/resolve/main"

    val VOICES = listOf(
        PiperVoice("google_tts", "Google TTS (Mặc định)", "F", "Trực tuyến nhanh, tự nhiên, không cần tải"),
        PiperVoice("ban_mai", "Ban Mai", "F", "Nữ miền Bắc, nhẹ nhàng"),
        PiperVoice("ngoc_ngan", "Ngọc Ngạn", "M", "Nam kể chuyện (Nguyễn Ngọc Ngạn)"),
        PiperVoice("tran_thanh", "Trần Thành", "M", "Nam hoạt bát (Trấn Thành)"),
        PiperVoice("mai_phuong", "Mai Phương", "F", "Nữ miền Nam, dịu dàng"),
        PiperVoice("duy_oryx", "Duy Oryx", "M", "Nam miền Bắc, rõ ràng"),
        PiperVoice("chieu_thanh", "Chiêu Thanh", "M", "Nam lồng tiếng"),
        PiperVoice("ngoc_huyen_new", "Ngọc Huyền", "F", "Nữ truyền cảm"),
        PiperVoice("manh_dung", "Mạnh Dũng", "M", "Nam trầm ấm"),
        PiperVoice("thanh_phuong", "Thanh Phương", "F", "Nữ dẫn chuyện"),
        PiperVoice("minh_quang", "Minh Quang", "M", "Nam thanh"),
        PiperVoice("phuong_trang", "Phương Trang", "F", "Nữ miền Nam"),
        PiperVoice("minh_khang", "Minh Khang", "M", "Nam miền Nam"),
        PiperVoice("tai_an", "Tài An", "M", "Nam trầm"),
        PiperVoice("lac_phi", "Lạc Phi", "M", "Nam hào sảng"),
        PiperVoice("thien_tam", "Thiện Tâm", "M", "Nam điềm đạm"),
        PiperVoice("my_tam", "Mỹ Tâm", "F", "Nữ miền Trung"),
        PiperVoice("viet_thao", "Việt Thảo", "M", "Nam hoạt náo (Việt Thảo)")
    )

    val DEFAULT_VOICE = VOICES[0] // Google TTS (Mặc định)

    fun findVoice(nameOrId: String?): PiperVoice {
        if (nameOrId == null) return DEFAULT_VOICE
        val clean = nameOrId.trim()
        return VOICES.firstOrNull {
            it.id.equals(clean, ignoreCase = true) || it.displayName.equals(clean, ignoreCase = true)
        } ?: when (clean.lowercase()) {
            "google_tts", "google", "mặc định", "online" -> VOICES.first { it.id == "google_tts" }
            "ban mai" -> VOICES.first { it.id == "ban_mai" }
            "hoài my", "thùy dung" -> VOICES.first { it.id == "ban_mai" }
            "nam minh", "adam", "ngọc ngạn" -> VOICES.first { it.id == "ngoc_ngan" }
            else -> DEFAULT_VOICE
        }
    }

    fun isOnlineVoice(voiceId: String): Boolean = voiceId == "google_tts"

    fun getModelUrl(voiceId: String): String = "$HF_BASE_URL/$voiceId.onnx"
    fun getConfigUrl(voiceId: String): String = "$HF_BASE_URL/$voiceId.onnx.json"
}
