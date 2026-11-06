package com.nuvio.tv.core.player.tts.piper

import java.text.Normalizer
import java.util.Locale

/**
 * Pure Kotlin grapheme-to-phoneme (G2P) converter for Vietnamese Piper TTS.
 * Converts Vietnamese text into the exact IPA phoneme format expected by
 * the piper-vits Vietnamese models (e.g. dinhphuongvn/ttsvn).
 */
object PiperPhonemizer {

    private val HTML_TAG_REGEX = Regex("<[^>]*>")
    private val BRACKET_CUE_REGEX = Regex("\\[.*?\\]|\\(.*?\\)|\\{.*?\\}")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val UNWANTED_SYMBOLS_REGEX = Regex("[=~*`^|<>…%$\"'“”]+")
    private val PUNCT_CLEAN_REGEX = Regex("^[,\\.\\s]+|[,\\.\\s]+$")

    private val INITIALS = mapOf(
        "ngh" to "ŋ", "nh" to "ɲ", "ng" to "ŋ", "gh" to "ɣ", "gi" to "z",
        "ch" to "tʃ", "kh" to "x", "ph" to "f", "th" to "t", "tr" to "tʃ",
        "qu" to "kw", "b" to "b", "c" to "k", "d" to "z", "đ" to "ɗ",
        "g" to "ɣ", "h" to "h", "k" to "k", "l" to "l", "m" to "m",
        "n" to "n", "p" to "p", "q" to "k", "r" to "z", "s" to "s",
        "t" to "t̪", "v" to "v", "x" to "s"
    )

    private val VOWELS = mapOf(
        "a" to "aː", "ă" to "a", "â" to "ə", "e" to "ɛ", "ê" to "e",
        "i" to "i", "o" to "ɔ", "ô" to "o", "ơ" to "əː", "u" to "u",
        "ư" to "y", "y" to "i"
    )

    private val DIPHTHONGS = mapOf(
        "ươi" to "yəj", "ười" to "yəj", "uôi" to "uəj", "uối" to "uəj",
        "iêu" to "iɛw", "yêu" to "iɛw", "ươu" to "yəw", "uây" to "wəɪ",
        "oai" to "waːj", "oay" to "waj", "oeo" to "wɛw", "uêu" to "wew",
        "uya" to "wiə", "uyê" to "wiɛ", "ươ" to "yə", "uô" to "uə",
        "iê" to "iɛ", "yê" to "iɛ", "ia" to "iə", "ua" to "uə",
        "ưa" to "yə", "ai" to "aːj", "ao" to "aːw", "au" to "aw",
        "âu" to "əw", "ay" to "aj", "ây" to "əɪ", "eo" to "ɛw",
        "êu" to "ew", "iu" to "iw", "oa" to "waː", "oă" to "wa",
        "oe" to "wɛ", "oi" to "ɔj", "ôi" to "oj", "ơi" to "əːj",
        "oo" to "ɔː", "ou" to "ɔw", "ôô" to "oː", "ui" to "uj",
        "ưi" to "yj", "uy" to "wi", "uê" to "we", "uơ" to "wəː",
        "uâ" to "wə", "ưu" to "iw", "oao" to "waːw", "uyu" to "wiw"
    )

    private val FINALS = mapOf(
        "nh" to "ɲ", "ng" to "ŋ", "ch" to "c", "c" to "c",
        "m" to "m", "n" to "n", "p" to "p", "t" to "t̪"
    )

    // Unicode Combining Diacritical Marks for Vietnamese tones
    private val TONE_MARKS = mapOf(
        '\u0300' to "2", // huyền
        '\u0301' to "ɜ", // sắc
        '\u0309' to "4", // hỏi
        '\u0303' to "5", // ngã
        '\u0323' to "6"  // nặng
    )

    // Function words with weak stress
    private val WEAK_STRESS_WORDS = setOf(
        "và", "là", "thì", "mà", "của", "cho", "để", "với", "như", "nếu",
        "vì", "bởi", "từ", "tới", "khi", "nhưng", "hoặc", "ở", "bị", "sẽ"
    )

    // Common loanwords mapped to Vietnamese pronunciation
    private val LOANWORDS = mapOf(
        "ok" to "ô kê", "okay" to "ô kê", "vip" to "víp", "ceo" to "xi i ô",
        "game" to "gêm", "gamer" to "gêm mơ", "team" to "tim", "boss" to "bốt",
        "idol" to "ai đồ", "video" to "vi đê ô", "online" to "on lai",
        "offline" to "óp lai", "livestream" to "lai trym", "facebook" to "phây búc",
        "youtube" to "diu túp", "tiktok" to "tíc tóc", "wifi" to "oai phai",
        "email" to "i mêu", "review" to "ri viu", "show" to "sâu", "club" to "clắp",
        "bar" to "ba", "party" to "pa ti", "fan" to "phan", "cute" to "kiu",
        "drama" to "đờ ra ma", "tv" to "ti vi", "tivi" to "ti vi", "virus" to "vi rút"
    )

    private val NUMBER_UNITS = arrayOf("", "nghìn", "triệu", "tỷ", "nghìn tỷ", "triệu tỷ")
    private val DIGITS = arrayOf("không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín")

    /**
     * Cleans subtitle text: strips HTML tags, removes bracketed audio cues [cười],
     * expands numbers, and cleans unwanted symbols.
     */
    fun cleanSubtitleText(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var text = raw.replace(HTML_TAG_REGEX, " ")
        text = text.replace(BRACKET_CUE_REGEX, " ")
        text = text.replace(UNWANTED_SYMBOLS_REGEX, " ")
        text = expandNumbers(text)
        text = text.replace(PUNCT_CLEAN_REGEX, "")
        text = text.replace(WHITESPACE_REGEX, " ").trim()
        return text
    }

    private fun expandNumbers(text: String): String {
        return Regex("\\b\\d+\\b").replace(text) { match ->
            val numStr = match.value
            runCatching {
                val num = numStr.toLong()
                readNumber(num)
            }.getOrDefault(numStr)
        }
    }

    private fun readNumber(num: Long): String {
        if (num == 0L) return "không"
        if (num < 0L) return "âm " + readNumber(-num)

        var n = num
        val chunks = ArrayList<Int>()
        while (n > 0L) {
            chunks.add((n % 1000L).toInt())
            n /= 1000L
        }

        val result = StringBuilder()
        for (i in chunks.size - 1 downTo 0) {
            val chunk = chunks[i]
            if (chunk > 0) {
                val chunkText = readChunk(chunk, i < chunks.size - 1)
                result.append(chunkText).append(" ").append(NUMBER_UNITS[i]).append(" ")
            }
        }
        return result.toString().trim()
    }

    private fun readChunk(chunk: Int, hasHigherChunk: Boolean): String {
        val tram = chunk / 100
        val chuc = (chunk % 100) / 10
        val donvi = chunk % 10

        val sb = StringBuilder()
        if (tram > 0 || hasHigherChunk) {
            sb.append(DIGITS[tram]).append(" trăm ")
        }
        if (chuc > 1) {
            sb.append(DIGITS[chuc]).append(" mươi ")
            if (donvi == 1) sb.append("mốt")
            else if (donvi == 4) sb.append("tư")
            else if (donvi == 5) sb.append("lăm")
            else if (donvi > 0) sb.append(DIGITS[donvi])
        } else if (chuc == 1) {
            sb.append("mười ")
            if (donvi == 5) sb.append("lăm")
            else if (donvi > 0) sb.append(DIGITS[donvi])
        } else if (donvi > 0) {
            if (tram > 0 || hasHigherChunk) sb.append("linh ")
            sb.append(DIGITS[donvi])
        }
        return sb.toString().trim()
    }

    /**
     * Converts a single Vietnamese word (already lowercase and without punctuation)
     * into Piper IPA phonemes.
     */
    private fun phonemizeWord(rawWord: String): String {
        if (rawWord.isBlank()) return ""
        val word = rawWord.lowercase(Locale.ROOT)

        // Check loanwords dictionary
        LOANWORDS[word]?.let { vietReplacement ->
            val parts = vietReplacement.split(" ")
            return parts.joinToString(" ") { phonemizeWord(it) }
        }

        // Decompose diacritics to find tone
        val nfd = Normalizer.normalize(word, Normalizer.Form.NFD)
        var tone = "7" // default tone (thanh ngang)
        val sbWithoutTone = StringBuilder()

        for (i in 0 until nfd.length) {
            val c = nfd[i]
            val mappedTone = TONE_MARKS[c]
            if (mappedTone != null) {
                tone = mappedTone
            } else {
                sbWithoutTone.append(c)
            }
        }

        // Recompose base word without tone mark
        var base = Normalizer.normalize(sbWithoutTone.toString(), Normalizer.Form.NFC)
        val isWeak = WEAK_STRESS_WORDS.contains(word)

        // Special handling for "gi"
        if (base.startsWith("gi") && base.length > 2) {
            base = "gi" + base.substring(2)
        } else if (base == "gi" || base == "gì" || base == "gí" || base == "gỉ" || base == "gĩ" || base == "gị") {
            base = "gii"
        }

        // 1. Extract initial consonant (match longest first up to 3 chars)
        var initial = ""
        var rest = base
        for (len in 3 downTo 1) {
            if (base.length >= len) {
                val sub = base.substring(0, len)
                if (INITIALS.containsKey(sub)) {
                    initial = sub
                    rest = base.substring(len)
                    break
                }
            }
        }

        // 2. Extract final consonant from rest (match up to 2 chars from the end)
        var finalConsonant = ""
        var vowelPart = rest
        for (len in Math.min(2, rest.length) downTo 1) {
            val sub = rest.substring(rest.length - len)
            if (FINALS.containsKey(sub)) {
                finalConsonant = sub
                vowelPart = rest.substring(0, rest.length - len)
                break
            }
        }

        // 3. Extract diphthong or vowel
        if (initial == "qu" && vowelPart == "ô") {
            initial = "q"
            vowelPart = "uô"
        }

        var ipaVowel = DIPHTHONGS[vowelPart]
        if (ipaVowel == null) {
            val sbV = StringBuilder()
            for (i in 0 until vowelPart.length) {
                val ch = vowelPart[i].toString()
                sbV.append(VOWELS[ch] ?: ch)
            }
            ipaVowel = sbV.toString()
        }

        if (ipaVowel.isBlank() && initial.isNotBlank() && rest.isEmpty()) {
            ipaVowel = "əː"
        }

        // Special vowel adjustment before "ch" and "nh"
        if (finalConsonant == "ch" || finalConsonant == "nh") {
            if (ipaVowel.endsWith("aː")) {
                ipaVowel = ipaVowel.substring(0, ipaVowel.length - 2) + "e-"
            } else if (ipaVowel.endsWith("a")) {
                ipaVowel = ipaVowel.substring(0, ipaVowel.length - 1) + "e-"
            }
        }

        val stress = if (isWeak) "ˌ" else "ˈ"

        // Insert stress after glide "w" if present
        var formattedVowel = if (ipaVowel.startsWith("w")) {
            "w" + stress + ipaVowel.substring(1)
        } else {
            stress + ipaVowel
        }

        var offglide = ""
        if (formattedVowel.endsWith("j")) {
            formattedVowel = formattedVowel.dropLast(1)
            offglide = "j"
        } else if (formattedVowel.endsWith("w")) {
            formattedVowel = formattedVowel.dropLast(1)
            offglide = "w"
        }

        var ipaFinal = FINALS[finalConsonant] ?: finalConsonant
        if (finalConsonant == "c" && (vowelPart == "o" || vowelPart == "ô" || vowelPart == "oo" || vowelPart == "uô")) {
            ipaFinal = "k"
        }

        val ipaInitial = INITIALS[initial] ?: initial

        val res = StringBuilder()
        if (ipaInitial.isNotEmpty()) res.append(ipaInitial)
        res.append(formattedVowel)
        res.append(tone)
        if (offglide.isNotEmpty()) res.append(offglide)
        if (ipaFinal.isNotEmpty()) res.append(ipaFinal)

        return res.toString()
    }

    /**
     * Phonemizes a complete sentence or clause, preserving punctuation.
     */
    fun phonemize(text: String): String {
        val clean = cleanSubtitleText(text)
        if (clean.isBlank()) return ""

        val tokens = clean.split(WHITESPACE_REGEX)
        val result = ArrayList<String>()

        for (token in tokens) {
            if (token.isBlank()) continue

            // Separate word from trailing punctuation
            var wordEnd = token.length
            while (wordEnd > 0 && !token[wordEnd - 1].isLetterOrDigit()) {
                wordEnd--
            }

            val word = token.substring(0, wordEnd)
            val punct = token.substring(wordEnd)

            val ipa = phonemizeWord(word)
            val mappedPunct = cleanPunctuation(punct)

            if (ipa.isNotEmpty()) {
                result.add(ipa + mappedPunct)
            } else if (mappedPunct.isNotEmpty()) {
                result.add(mappedPunct)
            }
        }

        return result.joinToString(" ")
    }

    private fun cleanPunctuation(punct: String): String {
        val sb = StringBuilder()
        for (c in punct) {
            when (c) {
                '!' -> sb.append("!")
                '?' -> sb.append("?")
                ',', ';' -> sb.append(",")
                '.', ':' -> sb.append(".")
            }
        }
        return sb.toString()
    }
}
