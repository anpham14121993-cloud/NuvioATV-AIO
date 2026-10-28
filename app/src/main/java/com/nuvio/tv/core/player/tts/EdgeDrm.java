package com.nuvio.tv.core.player.tts;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class EdgeDrm {
    public static final String CHROMIUM_VERSION = "2-143.0.3650.75";
    static final String TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";

    static final int[] H_INIT = {0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A, 0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19};
    static final int[] H_STATE = {0xFD9FBEA2, 0x91243CE7, 0xBE499094, 0xCD2E1E0A, 0x6C006F49, 0x04BD35AB, 0xFEB0A7E4, 0x03EFFBD2};
    static final int[] SCHED = {0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA, 0xA622EE8C, 0xD1DD9491, 0xC0DD25A0, 0xF9E70AB6, 0xF0C31573, 0x7058CE4E, 0x7188E606, 0x00E5AB33, 0xD258B060, 0x72766564};
    static final int[] RC = {
        0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
        0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
        0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
        0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7, 0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
        0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
        0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
        0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
        0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2
    };
    static final int ADJ = 0xC9DC06A7;
    static final int[] BLOCK_RC = new int[64];
    static final int SIG0_ADJ = 0x059C0B80, SIG1_ADJ = 0x000B92C0;
    static final int[][] CS64_COEF = {
        {0xE82E91B1, 0xB32700D0}, {0x44DE24C6, 0x88DFF51D},
        {0x3BEB65BC, 0x29514038}, {0xC89C5933, 0xB328CBDF},
        {0x8D1EC211, 0xD405B4D5}, {0x314EF5D5, 0x26E55F3C},
        {0xE92491B1, 0x607801C6}, {0xF0A5B9D5, 0x82B2C1FB},
        {0x06064CF3, 0xFA51A3DA}, {0xDA79FA60, 0x207CC464},
        {0xBED20560, 0x3C3C74F3}, {0x89F9C9F8, 0xD2D14B61},
        {0x35FA38C6, 0xBB04A2C1}, {0xCB49E3C6, 0xC5626167}
    };
    static final int CS64_F = 0x82893933, CS64_A0 = 0xF2F90B1A, CS64_A1 = 0x0B1A0000;

    static {
        for (int i = 0; i < 14; i++) BLOCK_RC[i] = RC[i];
        BLOCK_RC[14] = ADJ;
        for (int i = 15; i < 64; i++) BLOCK_RC[i] = RC[i];
    }

    static int rol16(int v) { return (v << 16) | (v >>> 16); }

    static int[] wordsLe4Even(byte[] data) {
        int pad = (-data.length) % 4;
        if (pad < 0) pad += 4;
        byte[] p = new byte[data.length + pad];
        System.arraycopy(data, 0, p, 0, data.length);
        if (p.length == 0) return new int[0];
        int c = p.length / 4;
        int[] w = new int[c];
        for (int i = 0; i < c; i++) {
            w[i] = (p[4 * i] & 0xFF) | ((p[4 * i + 1] & 0xFF) << 8) |
                ((p[4 * i + 2] & 0xFF) << 16) | ((p[4 * i + 3] & 0xFF) << 24);
        }
        if (w.length % 2 == 1) {
            int[] w2 = new int[w.length + 1];
            System.arraycopy(w, 0, w2, 0, w.length);
            w = w2;
        }
        return w;
    }

    static int sigma0(int v) { return (v >>> 7) ^ (v << 25) ^ (v >>> 18) ^ (v << 14) ^ (v >>> 3); }
    static int sigma1(int v) { return (v >>> 17) ^ (v << 15) ^ (v >>> 19) ^ (v << 13) ^ (v >>> 10); }

    static int E1(long x) { return (int) (((x >>> 6) ^ (x >>> 11) ^ (x >>> 25)) & 0xFFFFFFFFL); }
    static int E0(long x) { return (int) (((x >>> 2) ^ (x >>> 13) ^ (x >>> 22)) & 0xFFFFFFFFL); }

    static int[] rounds(int[] st, int[] words, int first) {
        int a = st[0], b = st[1], c = st[2], d = st[3], e = st[4], f = st[5], g = st[6], h = st[7];
        int[] K = (first == 14) ? BLOCK_RC : RC;
        for (int i = first; i < 64; i++) {
            long re = (e & 0xFFFFFFFFL) * 0x100000001L;
            long ra = (a & 0xFFFFFFFFL) * 0x100000001L;
            int t1 = h + E1(re) + (g ^ (e & (f ^ g))) + K[i] + words[i];
            int t2 = E0(ra) + ((a & b) | (c & (a | b)));
            int na = t1 + t2, ne = d + t1;
            h = g; g = f; f = e; e = ne; d = c; c = b; b = a; a = na;
        }
        return new int[]{a, b, c, d, e, f, g, h};
    }

    static void expandSchedule(List<Integer> words, int firstWord) {
        for (int index = firstWord; index < 64; index++) {
            int x = words.get(index - 2), y = words.get(index - 15);
            int s1 = (x >>> 17) ^ (x << 15) ^ (x >>> 19) ^ (x << 13) ^ (x >>> 10);
            int s0 = (y >>> 7) ^ (y << 25) ^ (y >>> 18) ^ (y << 14) ^ (y >>> 3);
            words.add(s1 + words.get(index - 7) + s0 + words.get(index - 16));
        }
    }

    static int be3(byte[] d, int o) { return ((d[o] & 0xFF) << 16) | ((d[o + 1] & 0xFF) << 8) | (d[o + 2] & 0xFF); }
    static int be4(byte[] d, int o) { return ((d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16) | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF); }

    static int[] sha256Init(byte[] data) {
        List<Integer> words = new ArrayList<>();
        for (int i = 0; i < 14; i++) words.add(0);
        words.add(be3(data, 0));
        words.add(be4(data, 3));
        for (int index = 16; index < 30; index++) {
            int value = SCHED[index - 16];
            if (index - 2 >= 14) {
                value += (index == 16) ? (sigma1(words.get(index - 2)) ^ SIG1_ADJ) : sigma1(words.get(index - 2));
            }
            if (index - 7 >= 14) value += words.get(index - 7);
            if (index - 15 >= 14) {
                value += (index == 29) ? (sigma0(words.get(index - 15)) ^ SIG0_ADJ) : sigma0(words.get(index - 15));
            }
            words.add(value);
        }
        words.add(sigma1(words.get(28)) + words.get(23) + sigma0(words.get(15)) + words.get(14) + ADJ - RC[14]);
        expandSchedule(words, 31);
        int[] wa = new int[64];
        for (int i = 0; i < 64; i++) wa[i] = words.get(i);
        int[] working = rounds(H_STATE, wa, 14);
        int[] out = new int[8];
        for (int i = 0; i < 8; i++) out[i] = H_INIT[i] + working[i];
        return out;
    }

    static byte[] sha256Digest(byte[] data) {
        int length = 57 + data.length;
        int padLen = (55 - length) % 64;
        if (padLen < 0) padLen += 64;
        byte[] padded = new byte[data.length - 7 + 1 + padLen + 8];
        System.arraycopy(data, 7, padded, 0, data.length - 7);
        padded[data.length - 7] = (byte) 0x80;
        long bitLen = length * 8L;
        for (int i = 0; i < 8; i++) {
            padded[padded.length - 1 - i] = (byte) ((bitLen >>> (8 * i)) & 0xFF);
        }
        int[] state = sha256Init(slice(data, 0, 7));
        int[] wa = new int[64];
        List<Integer> words = new ArrayList<>(64);
        for (int offset = 0; offset < padded.length; offset += 64) {
            words.clear();
            for (int i = 0; i < 16; i++) words.add(be4(padded, offset + 4 * i));
            expandSchedule(words, 16);
            for (int i = 0; i < 64; i++) wa[i] = words.get(i);
            int[] working = rounds(state, wa, 0);
            for (int i = 0; i < 8; i++) state[i] = state[i] + working[i];
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            out[4 * i] = (byte) (state[i] >>> 24);
            out[4 * i + 1] = (byte) (state[i] >>> 16);
            out[4 * i + 2] = (byte) (state[i] >>> 8);
            out[4 * i + 3] = (byte) state[i];
        }
        return out;
    }

    static int[] cs64Digest(byte[] data, byte[] digest) {
        int keyA = le32(digest, 0) | 1, keyB = le32(digest, 4) | 1;
        int f0 = keyA * CS64_F, f1 = keyB * CS64_F;
        List<Integer> terms = new ArrayList<>(), offsets = new ArrayList<>();
        for (int i = 0; i < CS64_COEF.length; i++) {
            terms.add(CS64_COEF[i][0] * (i % 2 == 0 ? f0 : f1));
            offsets.add(CS64_COEF[i][1]);
        }
        byte[] one = new byte[data.length + 1];
        one[0] = 0;
        System.arraycopy(data, 0, one, 1, data.length);
        int[] words = wordsLe4Even(one);
        for (int index = 0; index < words.length; index++) {
            int word = words[index];
            int k = (index % 2 == 0) ? keyA : keyB;
            int term = word * k;
            int off = rol16(word) * (index % 2 == 0 ? 0xEF0569FB : 0xC31713DB);
            if (index == 0) {
                term += CS64_A0 * f0;
                off += CS64_A1;
            }
            terms.add(term);
            offsets.add(off);
        }
        int swapAcc = 0, swapLast = 0, state = 0, acc = 0;
        for (int index = 0; index < terms.size(); index += 2) {
            int x = terms.get(index), y = terms.get(index + 1);
            int t = rol16((offsets.get(index) + x));
            int q = t * 0x76F50F34;
            swapAcc += (rol16(q) * 0xC3EFEA97 + q);
            t = rol16((offsets.get(index + 1) + y));
            q = (t * 0x1881CE3C);
            swapLast = (rol16(q) * 0x35BD1EC9 + q);
            swapAcc = (swapAcc + swapLast);

            state = (state * keyA + x);
            state = (rol16(state) * 0xCF98B111);
            state = (rol16(state) * 0x87085B9F);
            state = (rol16(state) * 0x12CEB96D);
            state = (rol16(state) * 0x257E1D83);
            acc += state;
            t = (state * keyB + y);
            t = (rol16(t) * 0xA27416F5);
            t = (rol16(t) * 0xD38396FF);
            state = (rol16((rol16(t) * 0x7C932B89)) * 0xBFA49F69);
            acc = (acc + state);
        }
        return new int[]{swapLast ^ state, swapAcc ^ acc};
    }

    static int le32(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8) | ((d[o + 2] & 0xFF) << 16) | ((d[o + 3] & 0xFF) << 24);
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02X", x));
        return sb.toString();
    }

    public static String token() {
        return token(System.currentTimeMillis() / 1000.0);
    }

    public static String token(double unixTs) {
        double ticks = unixTs + 11644473600.0;
        ticks -= ticks % 300;
        ticks *= 1e7;
        String s = String.format(Locale.US, "%.0f", ticks) + TOKEN;
        byte[] data = s.getBytes(StandardCharsets.US_ASCII);
        byte[] digest = sha256Digest(data);
        int[] cs = cs64Digest(data, digest);
        byte[] csBytes = new byte[8];
        for (int i = 0; i < 2; i++) {
            csBytes[4 * i] = (byte) cs[i];
            csBytes[4 * i + 1] = (byte) (cs[i] >>> 8);
            csBytes[4 * i + 2] = (byte) (cs[i] >>> 16);
            csBytes[4 * i + 3] = (byte) (cs[i] >>> 24);
        }
        return hex(csBytes) + "-" + hex(digest);
    }

    static byte[] slice(byte[] d, int o, int n) {
        byte[] r = new byte[n];
        System.arraycopy(d, o, r, 0, n);
        return r;
    }
}
