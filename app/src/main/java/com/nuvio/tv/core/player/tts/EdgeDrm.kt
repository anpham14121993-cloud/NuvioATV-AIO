package com.nuvio.tv.core.player.tts

import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Edge TTS v2 DRM token generator.
 *
 * Computes Sec-MS-GEC token combining CS64 and SHA-256 custom schedule digests
 * based on the current Unix timestamp with 5-minute window quantization.
 */
object EdgeDrm {
    private const val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    const val CHROMIUM_VERSION = "2-143.0.3650.75"

    private val H_INIT = intArrayOf(
        0x6A09E667, -0x4498517B, 0x3C6EF372, -0x5AB00AC6,
        0x510E527F, -0x64FA9774, 0x1F83D9AB, 0x5BE0CD19
    )
    private val H_STATE = intArrayOf(
        -0x260415E, -0x6EDBC319, -0x41B66F6C, -0x32D1E1F6,
        0x6C006F49, 0x04BD35AB, -0x14F581C, 0x03EFFBD2
    )
    private val SCHED = intArrayOf(
        -0x14DB4062, 0x1558691F, -0x5F3F611E, 0x0CFA32BA,
        -0x59DD1174, -0x2E226B6F, -0x3F22DA60, -0x618F54A,
        -0x0F3CEACB, 0x7058CE4E, 0x7188E606, 0x00E5AB33,
        -0x2DA74FA0, 0x72766564
    )
    private val RC = intArrayOf(
        0x428A2F98, 0x71374491, -0x4A3F0431, -0x164A245B,
        0x3956C25B, 0x59F111F1, -0x6DC07D5C, -0x54E3A12B,
        -0x27F85568, 0x12835B01, 0x243185BE, 0x550C7DC3,
        0x72BE5D74, -0x7F214E02, -0x6423F959, -0x3E640E8C,
        -0x1B64963F, -0x1041B87A, 0x0FC19DC6, 0x240CA1CC,
        0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
        -0x67C1AEA2, -0x57CE3993, -0x4FFC3838, -0x40A68039,
        -0x391FF40D, -0x2A586EB9, 0x06CA6351, 0x14292967,
        0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13,
        0x650A7354, 0x766A0ABB, -0x7E3D36D2, -0x6D8DD37B,
        -0x5D40175F, -0x57E599B5, -0x3DB47490, -0x3893AE5D,
        -0x2E6D17E7, -0x2966F9DC, -0x0BF1CA7B, 0x106AA070,
        0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5,
        0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
        0x748F82EE, 0x78A5636F, -0x7B3787EC, -0x7338FDF8,
        -0x6F410006, -0x5BAF9315, -0x41065C09, -0x398E870E
    )
    private const val ADJ = -0x3623F959 // 0xC9DC06A7
    private val BLOCK_RC = IntArray(64).apply {
        for (i in 0 until 14) this[i] = RC[i]
        this[14] = ADJ
        for (i in 15 until 64) this[i] = RC[i]
    }
    private const val SIG0_ADJ = 0x059C0B80
    private const val SIG1_ADJ = 0x000B92C0

    private val CS64_COEF = arrayOf(
        intArrayOf(-0x17D16E4F, -0x4CD8FF30), intArrayOf(0x44DE24C6, -0x77200AE3),
        intArrayOf(0x3BEB65BC, 0x29514038), intArrayOf(-0x3763A6CD, -0x4CD73421),
        intArrayOf(-0x72E13DEF, -0x2BFA4B2B), intArrayOf(0x314EF5D5, 0x26E55F3C),
        intArrayOf(-0x16DB6E4F, 0x607801C6), intArrayOf(-0x0F5A462B, -0x7D4D3E05),
        intArrayOf(0x06064CF3, -0x05AE5C26), intArrayOf(-0x258605A0, 0x207CC464),
        intArrayOf(-0x412DFBA0, 0x3C3C74F3), intArrayOf(-0x76063608, -0x2D2EB49F),
        intArrayOf(0x35FA38C6, -0x44FB5D3F), intArrayOf(-0x34B61C3A, -0x3A9D9E99)
    )
    private const val CS64_F = -0x7D76C6CD // 0x82893933
    private const val CS64_A0 = -0x0D06F4E6 // 0xF2F90B1A
    private const val CS64_A1 = 0x0B1A0000

    private fun rol16(v: Int): Int = (v shl 16) or (v ushr 16)

    private fun wordsLe4Even(data: ByteArray): IntArray {
        var pad = (-data.size) % 4
        if (pad < 0) pad += 4
        val p = ByteArray(data.size + pad)
        System.arraycopy(data, 0, p, 0, data.size)
        if (p.isEmpty()) return IntArray(0)
        val c = p.size / 4
        var w = IntArray(c)
        for (i in 0 until c) {
            w[i] = (p[4 * i].toInt() and 0xFF) or
                ((p[4 * i + 1].toInt() and 0xFF) shl 8) or
                ((p[4 * i + 2].toInt() and 0xFF) shl 16) or
                ((p[4 * i + 3].toInt() and 0xFF) shl 24)
        }
        if (w.size % 2 == 1) {
            val w2 = IntArray(w.size + 1)
            System.arraycopy(w, 0, w2, 0, w.size)
            w = w2
        }
        return w
    }

    private fun sigma0(v: Int): Int = (v ushr 7) xor (v shl 25) xor (v ushr 18) xor (v shl 14) xor (v ushr 3)
    private fun sigma1(v: Int): Int = (v ushr 17) xor (v shl 15) xor (v ushr 19) xor (v shl 13) xor (v ushr 10)

    private fun e1(x: Long): Int = (((x ushr 6) xor (x ushr 11) xor (x ushr 25)) and 0xFFFFFFFFL).toInt()
    private fun e0(x: Long): Int = (((x ushr 2) xor (x ushr 13) xor (x ushr 22)) and 0xFFFFFFFFL).toInt()

    private fun rounds(st: IntArray, words: IntArray, first: Int): IntArray {
        var a = st[0]
        var b = st[1]
        var c = st[2]
        var d = st[3]
        var e = st[4]
        var f = st[5]
        var g = st[6]
        var h = st[7]
        val kArr = if (first == 14) BLOCK_RC else RC
        for (i in first until 64) {
            val re = (e.toLong() and 0xFFFFFFFFL) * 0x100000001L
            val ra = (a.toLong() and 0xFFFFFFFFL) * 0x100000001L
            val t1 = h + e1(re) + (g xor (e and (f xor g))) + kArr[i] + words[i]
            val t2 = e0(ra) + ((a and b) or (c and (a or b)))
            val na = t1 + t2
            val ne = d + t1
            h = g; g = f; f = e; e = ne; d = c; c = b; b = a; a = na
        }
        return intArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun expandSchedule(words: ArrayList<Int>, firstWord: Int) {
        for (index in firstWord until 64) {
            val x = words[index - 2]
            val y = words[index - 15]
            val s1 = (x ushr 17) xor (x shl 15) xor (x ushr 19) xor (x shl 13) xor (x ushr 10)
            val s0 = (y ushr 7) xor (y shl 25) xor (y ushr 18) xor (y shl 14) xor (y ushr 3)
            words.add(s1 + words[index - 7] + s0 + words[index - 16])
        }
    }

    private fun be3(d: ByteArray, o: Int): Int =
        ((d[o].toInt() and 0xFF) shl 16) or ((d[o + 1].toInt() and 0xFF) shl 8) or (d[o + 2].toInt() and 0xFF)

    private fun be4(d: ByteArray, o: Int): Int =
        ((d[o].toInt() and 0xFF) shl 24) or ((d[o + 1].toInt() and 0xFF) shl 16) or
            ((d[o + 2].toInt() and 0xFF) shl 8) or (d[o + 3].toInt() and 0xFF)

    private fun sha256Init(data: ByteArray): IntArray {
        val words = ArrayList<Int>()
        for (i in 0 until 14) words.add(0)
        words.add(be3(data, 0))
        words.add(be4(data, 3))
        for (index in 16 until 30) {
            var value = SCHED[index - 16]
            if (index - 2 >= 14) {
                value += if (index == 16) sigma1(words[index - 2]) xor SIG1_ADJ else sigma1(words[index - 2])
            }
            if (index - 7 >= 14) value += words[index - 7]
            if (index - 15 >= 14) {
                value += if (index == 29) sigma0(words[index - 15]) xor SIG0_ADJ else sigma0(words[index - 15])
            }
            words.add(value)
        }
        words.add(sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] + ADJ - RC[14])
        expandSchedule(words, 31)
        val wa = IntArray(64)
        for (i in 0 until 64) wa[i] = words[i]
        val working = rounds(H_STATE, wa, 14)
        val out = IntArray(8)
        for (i in 0 until 8) out[i] = H_INIT[i] + working[i]
        return out
    }

    private fun sha256Digest(data: ByteArray): ByteArray {
        val length = 57 + data.size
        var padLen = (55 - length) % 64
        if (padLen < 0) padLen += 64
        val padded = ByteArray(data.size - 7 + 1 + padLen + 8)
        System.arraycopy(data, 7, padded, 0, data.size - 7)
        padded[data.size - 7] = 0x80.toByte()
        val bitLen = length.toLong() * 8L
        for (i in 0 until 8) {
            padded[padded.size - 1 - i] = ((bitLen ushr (8 * i)) and 0xFFL).toByte()
        }
        val state = sha256Init(data.copyOfRange(0, 7))
        val wa = IntArray(64)
        val words = ArrayList<Int>(64)
        for (offset in padded.indices step 64) {
            words.clear()
            for (i in 0 until 16) words.add(be4(padded, offset + 4 * i))
            expandSchedule(words, 16)
            for (i in 0 until 64) wa[i] = words[i]
            val working = rounds(state, wa, 0)
            for (i in 0 until 8) state[i] = state[i] + working[i]
        }
        val out = ByteArray(32)
        for (i in 0 until 8) {
            out[4 * i] = (state[i] ushr 24).toByte()
            out[4 * i + 1] = (state[i] ushr 16).toByte()
            out[4 * i + 2] = (state[i] ushr 8).toByte()
            out[4 * i + 3] = state[i].toByte()
        }
        return out
    }

    private fun le32(d: ByteArray, o: Int): Int =
        (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8) or
            ((d[o + 2].toInt() and 0xFF) shl 16) or ((d[o + 3].toInt() and 0xFF) shl 24)

    private fun cs64Digest(data: ByteArray, digest: ByteArray): IntArray {
        val keyA = le32(digest, 0) or 1
        val keyB = le32(digest, 4) or 1
        val f0 = keyA * CS64_F
        val f1 = keyB * CS64_F
        val terms = ArrayList<Int>()
        val offsets = ArrayList<Int>()
        for (i in CS64_COEF.indices) {
            terms.add(CS64_COEF[i][0] * (if (i % 2 == 0) f0 else f1))
            offsets.add(CS64_COEF[i][1])
        }
        val one = ByteArray(data.size + 1)
        one[0] = 0
        System.arraycopy(data, 0, one, 1, data.size)
        val words = wordsLe4Even(one)
        for (index in words.indices) {
            val word = words[index]
            val k = if (index % 2 == 0) keyA else keyB
            var term = word * k
            var off = rol16(word) * (if (index % 2 == 0) -0x10FA9605 else -0x3CE8EC25)
            if (index == 0) {
                term += CS64_A0 * f0
                off += CS64_A1
            }
            terms.add(term)
            offsets.add(off)
        }
        var swapAcc = 0
        var swapLast = 0
        var state = 0
        var acc = 0
        for (index in 0 until terms.size step 2) {
            val x = terms[index]
            val y = terms[index + 1]
            var t = rol16(offsets[index] + x)
            var q = t * 0x76F50F34
            swapAcc += rol16(q) * -0x3C101569 + q
            t = rol16(offsets[index + 1] + y)
            q = t * 0x1881CE3C
            swapLast = rol16(q) * 0x35BD1EC9 + q
            swapAcc += swapLast

            state = (state * keyA + x)
            state = rol16(state) * -0x30674EEF
            state = rol16(state) * -0x78F7A461
            state = rol16(state) * 0x12CEB96D
            state = rol16(state) * 0x257E1D83
            acc += state
            t = (state * keyB + y)
            t = rol16(t) * -0x5D8BE90B
            t = rol16(t) * -0x2C7C6901
            state = rol16(rol16(t) * 0x7C932B89) * -0x405B6097
            acc += state
        }
        return intArrayOf(swapLast xor state, swapAcc xor acc)
    }

    private fun hex(b: ByteArray): String {
        val sb = StringBuilder()
        for (x in b) sb.append(String.format(Locale.US, "%02X", x))
        return sb.toString()
    }

    fun token(unixTs: Double = System.currentTimeMillis() / 1000.0): String {
        var ticks = unixTs + 11644473600.0
        ticks -= ticks % 300
        ticks *= 1e7
        val s = String.format(Locale.US, "%.0f", ticks) + TRUSTED_TOKEN
        val data = s.toByteArray(StandardCharsets.US_ASCII)
        val digest = sha256Digest(data)
        val cs = cs64Digest(data, digest)
        val csBytes = ByteArray(8)
        for (i in 0 until 2) {
            csBytes[4 * i] = cs[i].toByte()
            csBytes[4 * i + 1] = (cs[i] ushr 8).toByte()
            csBytes[4 * i + 2] = (cs[i] ushr 16).toByte()
            csBytes[4 * i + 3] = (cs[i] ushr 24).toByte()
        }
        return hex(csBytes) + "-" + hex(digest)
    }
}
