package com.nuvio.app.vieneu;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/** Torch-free VieNeu-TTS v3 Turbo inference ported from onnx_runtime_lite.py. */
public final class VieneuEngine {
    public static final int SR = 48000;

    /** Optional trace sink installed by the app (never throws). */
    public static volatile java.util.function.Consumer<String> TRACE = null;
    private static void tr(String msg) {
        java.util.function.Consumer<String> t = TRACE;
        if (t != null) { try { t.accept(msg); } catch (Throwable ignored) {} }
    }
    private static void mem(String tag) {
        Runtime r = Runtime.getRuntime();
        long usedMb = (r.totalMemory() - r.freeMemory()) / 1048576L;
        long maxMb = r.maxMemory() / 1048576L;
        tr(tag + " mem=" + usedMb + "/" + maxMb + "MB");
    }

    private static final int H = 768, NVQ = 16, L = 12, NH_LOC = 8, HD_LOC = 96;
    private static final long AUDIO_PAD = 1024, TPS = 3, TPE = 4, SGS = 5, EOS_SPEECH = 6, REF_SLOT = 7;
    private static final int TEXT_VOCAB = 419, AUDIO_VOCAB = 1024, STYLE_ID = 16;

    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession sPre, sDec, sAc, sCodec;
    private final BpeTokenizer tok;
    private final G2P g2p;
    private final Random rnd = new Random(20261016L);
    private boolean lastEos;
    private volatile boolean closed;

    private final float[] textEmb = new float[TEXT_VOCAB * H];
    private final float[] audioEmb = new float[NVQ * AUDIO_VOCAB * H];
    private final float[] xvecW = new float[H * 192];
    private final float[] xvecB = new float[H];
    private final float[] lnW = new float[H];
    private final float[] lnB = new float[H];
    private float lnEps;

    private final float[] idxBuf = new float[1024];
    private final int[] candBuf = new int[1024];
    private final float[] heapV = new float[64];
    private final int[] heapI = new int[64];

    public VieneuEngine(File modelDir, File headsBin, String tokenizerJson,
                        String g2pBin, int threads) throws Exception {
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(Math.max(1, threads));
        o.setInterOpNumThreads(1);
        File onnx = new File(modelDir, "onnx_int8");
        File codec = new File(modelDir, "codec");
        sPre = env.createSession(new File(onnx, "vieneu_prefill.onnx").getAbsolutePath(), o);
        sDec = env.createSession(new File(onnx, "vieneu_decode_step.onnx").getAbsolutePath(), o);
        sAc = env.createSession(new File(onnx, "vieneu_acoustic_cached.onnx").getAbsolutePath(), o);
        sCodec = env.createSession(new File(codec, "moss_audio_tokenizer_decode_full.onnx").getAbsolutePath(), o);

        loadHeads(headsBin);
        tok = new BpeTokenizer(tokenizerJson);
        g2p = new G2P(g2pBin);
    }

    private void loadHeads(File f) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            ByteBuffer bb = raf.getChannel().map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, raf.length());
            bb.order(ByteOrder.LITTLE_ENDIAN);
            FloatBuffer fb = bb.asFloatBuffer();
            int p = 0;
            fb.get(textEmb, 0, textEmb.length); p += textEmb.length;
            fb.get(audioEmb, 0, audioEmb.length); p += audioEmb.length;
            fb.get(xvecW, 0, xvecW.length); p += xvecW.length;
            fb.get(xvecB, 0, xvecB.length); p += xvecB.length;
            fb.get(lnW, 0, lnW.length); p += lnW.length;
            fb.get(lnB, 0, lnB.length); p += lnB.length;
            lnEps = fb.get(p);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────
    private OnnxTensor ft(float[] d, long... shape) throws Exception {
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(d), shape);
    }
    private OnnxTensor lt(long[] d, long... shape) throws Exception {
        return OnnxTensor.createTensor(env, LongBuffer.wrap(d), shape);
    }
    private OnnxTensor it(int[] d, long... shape) throws Exception {
        return OnnxTensor.createTensor(env, IntBuffer.wrap(d), shape);
    }
    private static float[] readF(OnnxTensor t) {
        FloatBuffer b = t.getFloatBuffer();
        float[] a = new float[b.remaining()];
        b.get(a);
        return a;
    }
    private static int[] readI(OnnxTensor t) {
        IntBuffer b = t.getIntBuffer();
        int[] a = new int[b.remaining()];
        b.get(a);
        return a;
    }
    private static OnnxTensor T(OrtSession.Result res, String name) {
        return (OnnxTensor) res.get(name).get();
    }
    private static void closeAll(Map<String, OnnxTensor> m) {
        for (OnnxTensor t : m.values()) try { t.close(); } catch (Exception ignored) {}
    }

    private OnnxTensor emptyPast() throws Exception {
        return OnnxTensor.createTensor(env, FloatBuffer.allocate(0), new long[]{1, NH_LOC, 0, HD_LOC});
    }

    private float[] speakerAnchor(float[] emb192) {
        float[] f = new float[H];
        for (int h = 0; h < H; h++) {
            float s = xvecB[h];
            int off = h * 192;
            for (int k = 0; k < 192; k++) s += xvecW[off + k] * emb192[k];
            f[h] = s;
        }
        double mean = 0; for (float x : f) mean += x; mean /= H;
        double var = 0; for (float x : f) { double d = x - mean; var += d * d; } var /= H;
        double inv = 1.0 / Math.sqrt(var + lnEps);
        float[] a = new float[H];
        for (int h = 0; h < H; h++) a[h] = (float) ((f[h] - mean) * inv) * lnW[h] + lnB[h];
        return a;
    }

    private long[][] buildRows(String phonemes, int[][] refCodes) {
        int[] phoneIds = tok.encode(phonemes);
        int t = 2 + phoneIds.length + 1;
        int rt = refCodes == null ? 0 : refCodes.length;
        long[][] rows = new long[t + rt][NVQ + 1];
        for (long[] row : rows) Arrays.fill(row, AUDIO_PAD);
        rows[0][0] = STYLE_ID;
        rows[1][0] = TPS;
        for (int i = 0; i < phoneIds.length; i++) rows[2 + i][0] = phoneIds[i];
        rows[2 + phoneIds.length][0] = TPE;
        if (refCodes != null) {
            for (int j = 0; j < rt; j++) {
                rows[t + j][0] = REF_SLOT;
                for (int c = 0; c < NVQ; c++) rows[t + j][c + 1] = refCodes[j][c];
            }
        }
        return rows;
    }

    private float[] embedRow(long[] row, float[] anchor) {
        float[] e = new float[H];
        System.arraycopy(textEmb, (int) row[0] * H, e, 0, H);
        for (int ch = 0; ch < NVQ; ch++) {
            long id = row[ch + 1];
            if (id != AUDIO_PAD) {
                int base = (ch * AUDIO_VOCAB + (int) id) * H;
                for (int h = 0; h < H; h++) e[h] += audioEmb[base + h];
            }
        }
        if (anchor != null) for (int h = 0; h < H; h++) e[h] += anchor[h];
        return e;
    }

    private float[] embedRows(long[][] rows, float[] anchor) {
        float[] out = new float[rows.length * H];
        for (int i = 0; i < rows.length; i++) System.arraycopy(embedRow(rows[i], anchor), 0, out, i * H, H);
        return out;
    }

    // ── sampling heads over audio_emb[ch] ───────────────────────────────────────
    private int sampleChannel(float[] vec, int ch, Params p, Hist hist) {
        int base = ch * AUDIO_VOCAB * H;
        for (int v = 0; v < AUDIO_VOCAB; v++) {
            int off = base + v * H;
            float s = 0;
            for (int h = 0; h < H; h++) s += vec[h] * audioEmb[off + h];
            idxBuf[v] = s;
        }
        if (p.repPen != 1f && hist != null && hist.size(ch) > 0) {
            for (int code : hist.codes(ch)) {
                float s = idxBuf[code];
                idxBuf[code] = s < 0 ? s * p.repPen : s / p.repPen;
            }
        }
        int code;
        if (!(p.temperature > 0)) {
            int bi = 0; for (int v = 1; v < AUDIO_VOCAB; v++) if (idxBuf[v] > idxBuf[bi]) bi = v;
            code = bi;
        } else {
            float inv = 1f / p.temperature;
            int k = (p.topK > 0 && p.topK < AUDIO_VOCAB) ? p.topK : AUDIO_VOCAB;
            int cnt = 0;
            for (int v = 0; v < AUDIO_VOCAB; v++) {
                float val = idxBuf[v] * inv;
                if (cnt < k) { heapV[cnt] = val; heapI[cnt] = v; cnt++; if (cnt == k) heapify(k); }
                else if (val > heapV[0]) { heapV[0] = val; heapI[0] = v; siftDown(0, k); }
            }
            int n = cnt;
            // sort candidates desc by val (simple insertion, n<=1024) using heap arrays then full sort
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) order[i] = i;
            final float[] vv = heapV;
            Arrays.sort(order, (a, b) -> Float.compare(vv[b], vv[a]));
            float max = heapV[order[0]];
            float sum = 0;
            for (int i = 0; i < n; i++) { float e = (float) Math.exp(heapV[order[i]] - max); heapV[order[i]] = e; sum += e; }
            for (int i = 0; i < n; i++) heapV[order[i]] /= sum;
            int keep = n;
            if (p.topP > 0 && p.topP < 1f) {
                float cum = 0; keep = n;
                for (int i = 0; i < n; i++) {
                    float pi = heapV[order[i]];
                    if (cum >= p.topP) { keep = i; break; }
                    cum += pi;
                }
                if (keep < 1) keep = 1;
            }
            float total = 0; for (int i = 0; i < keep; i++) total += heapV[order[i]];
            double r = rnd.nextDouble() * total;
            float acc = 0; code = heapI[order[keep - 1]];
            for (int i = 0; i < keep; i++) { acc += heapV[order[i]]; if (r < acc) { code = heapI[order[i]]; break; } }
        }
        if (hist != null) hist.add(ch, code);
        return code;
    }

    private void heapify(int n) {
        for (int i = n / 2 - 1; i >= 0; i--) siftDown(i, n);
    }
    private void siftDown(int i, int n) {
        while (true) {
            int l = 2 * i + 1, r = 2 * i + 2, m = i;
            if (l < n && heapV[l] < heapV[m]) m = l;
            if (r < n && heapV[r] < heapV[m]) m = r;
            if (m == i) break;
            float tv = heapV[i]; heapV[i] = heapV[m]; heapV[m] = tv;
            int ti = heapI[i]; heapI[i] = heapI[m]; heapI[m] = ti;
            i = m;
        }
    }

    // ── one acoustic frame (16 codes) ───────────────────────────────────────────
    private int[] acousticFrame(float[] h, Params p, Hist hist) throws Exception {
        int[] codes = new int[NVQ];
        float[] tok2 = new float[2 * H];
        System.arraycopy(h, 0, tok2, 0, H);
        System.arraycopy(textEmb, (int) SGS * H, tok2, H, H);
        Map<String, OnnxTensor> in = new HashMap<>();
        OnnxTensor epk = emptyPast(), epv = emptyPast();
        in.put("token_emb", ft(tok2, 1, 2, H));
        in.put("position_ids", lt(new long[]{0, 1}, 1, 2));
        in.put("past_k_0", epk); in.put("past_v_0", epv);
        OrtSession.Result ar = sAc.run(in);
        closeAll(in);
        float[] ah = readF(T(ar, "hidden"));
        float[] slot0 = Arrays.copyOfRange(ah, 0, H);
        float[] condOut = Arrays.copyOfRange(ah, H, 2 * H);
        OnnxTensor npk = T(ar, "present_k_0");
        OnnxTensor npv = T(ar, "present_v_0");
        codes[0] = sampleChannel(condOut, 0, p, hist);

        for (int ch = 1; ch < NVQ; ch++) {
            float[] emb = new float[H];
            System.arraycopy(audioEmb, (ch - 1) * AUDIO_VOCAB * H + codes[ch - 1] * H, emb, 0, H);
            Map<String, OnnxTensor> in2 = new HashMap<>();
            in2.put("token_emb", ft(emb, 1, 1, H));
            in2.put("position_ids", lt(new long[]{ch + 1}, 1, 1));
            in2.put("past_k_0", npk); in2.put("past_v_0", npv);
            OrtSession.Result ar2 = sAc.run(in2);
            closeAll(in2);
            float[] ah2 = readF(T(ar2, "hidden"));
            OnnxTensor npk2 = T(ar2, "present_k_0");
            OnnxTensor npv2 = T(ar2, "present_v_0");
            ar.close();
            ar = ar2; npk = npk2; npv = npv2;
            codes[ch] = sampleChannel(ah2, ch, p, hist);
        }
        ar.close();

        float[] tl = new float[TEXT_VOCAB];
        for (int v = 0; v < TEXT_VOCAB; v++) {
            int off = v * H; float s = 0;
            for (int i = 0; i < H; i++) s += slot0[i] * textEmb[off + i];
            tl[v] = s;
        }
        int am = 0; for (int v = 1; v < TEXT_VOCAB; v++) if (tl[v] > tl[am]) am = v;
        lastEos = am == (int) EOS_SPEECH;
        return codes;
    }

    // ── public synthesis ────────────────────────────────────────────────────────
    public float[] synthesize(String text, float[] speakerEmb, int[][] refCodes, Params p) throws Exception {
        mem("synth: before-g2p");
        String phonemes = g2p.phonemize(text);
        tr("synth: g2p ok len=" + (phonemes == null ? -1 : phonemes.length()));
        mem("synth: after-g2p");
        return synthesizePhonemes(phonemes, speakerEmb, refCodes, p);
    }

    public float[] synthesizePhonemes(String phonemes, float[] speakerEmb, int[][] refCodes, Params p) throws Exception {
        if (closed) throw new IllegalStateException("engine closed");
        float[] anchor = speakerAnchor(speakerEmb);
        long[][] rows = buildRows(phonemes, refCodes);
        tr("synth: rows=" + rows.length);
        float[] prompt = embedRows(rows, anchor);
        int T = rows.length;

        Map<String, OnnxTensor> pin = new HashMap<>();
        pin.put("inputs_embeds", ft(prompt, 1, T, H));
        OrtSession.Result cur = sPre.run(pin);
        closeAll(pin);
        tr("synth: prefill done");
        float[] hid = readF(T(cur, "hidden"));
        float[] h = Arrays.copyOfRange(hid, (T - 1) * H, T * H);
        OnnxTensor[] pk = new OnnxTensor[L], pv = new OnnxTensor[L];
        for (int i = 0; i < L; i++) {
            pk[i] = T(cur, "present_k_" + i);
            pv[i] = T(cur, "present_v_" + i);
        }

        Hist hist = (p.repPen != 1f) ? new Hist(NVQ, p.repWindow) : null;
        List<int[]> frames = new ArrayList<>();
        int cap = frameCap(phonemes, p.maxFrames);
        tr("synth: ar start cap=" + cap);
        for (int t = 0; t < cap; t++) {
            if (t > 0 && t % 32 == 0) tr("synth: ar t=" + t);
            int[] codes = acousticFrame(h, p, hist);
            frames.add(codes);
            if (lastEos) break;

            long[] slot = new long[NVQ + 1];
            Arrays.fill(slot, AUDIO_PAD);
            slot[0] = SGS;
            for (int i = 0; i < NVQ; i++) slot[i + 1] = codes[i];
            float[] se = embedRow(slot, anchor);
            Map<String, OnnxTensor> din = new HashMap<>();
            din.put("inputs_embeds", ft(se, 1, 1, H));
            din.put("position_ids", lt(new long[]{T + t}, 1, 1));
            for (int i = 0; i < L; i++) { din.put("past_k_" + i, pk[i]); din.put("past_v_" + i, pv[i]); }
            OrtSession.Result dr = sDec.run(din);
            // close only inputs we created (inputs_embeds/position_ids); past owned by cur
            try { din.get("inputs_embeds").close(); } catch (Exception ignored) {}
            try { din.get("position_ids").close(); } catch (Exception ignored) {}
            float[] dh = readF(T(dr, "hidden"));
            h = dh;
            OnnxTensor[] npk = new OnnxTensor[L], npv = new OnnxTensor[L];
            for (int i = 0; i < L; i++) {
                npk[i] = T(dr, "present_k_" + i);
                npv[i] = T(dr, "present_v_" + i);
            }
            cur.close();
            cur = dr; pk = npk; pv = npv;
        }
        cur.close();
        tr("synth: ar done frames=" + frames.size());
        mem("synth: before-codec");
        float[] out = decodeCodes(frames);
        mem("synth: after-codec");
        return out;
    }

    /** Releases the ONNX sessions. The engine cannot be used afterwards. */
    public void close() {
        closed = true;
        try { sPre.close(); } catch (Exception ignored) {}
        try { sDec.close(); } catch (Exception ignored) {}
        try { sAc.close(); } catch (Exception ignored) {}
        try { sCodec.close(); } catch (Exception ignored) {}
        try { g2p.close(); } catch (Exception ignored) {}
    }

    private static int frameCap(String phonemes, int maxFrames) {
        int len = phonemes == null ? 0 : phonemes.length();
        int cap = 24 + (int) Math.ceil(3.0 * len);
        return Math.min(maxFrames, cap);
    }

    private float[] decodeCodes(List<int[]> frames) throws Exception {
        int T = frames.size();
        tr("synth: decode start T=" + T);
        int[] codes = new int[T * NVQ];
        for (int i = 0; i < T; i++) System.arraycopy(frames.get(i), 0, codes, i * NVQ, NVQ);
        int[] lens = new int[]{T};
        Map<String, OnnxTensor> in = new HashMap<>();
        in.put("audio_codes", it(codes, 1, T, NVQ));
        in.put("audio_code_lengths", it(lens, 1));
        OrtSession.Result r = sCodec.run(in);
        tr("synth: codec done");
        closeAll(in);
        float[] audio = readF(T(r, "audio"));
        int n = readI(T(r, "audio_lengths"))[0];
        int ch = audio.length / n;
        float[] wav = new float[n];
        for (int i = 0; i < n; i++) {
            float s = 0;
            for (int c = 0; c < ch; c++) s += audio[c * n + i];
            wav[i] = s / ch;
        }
        r.close();
        return wav;
    }

    // ── params / history ────────────────────────────────────────────────────────
    public static final class Params {
        public float temperature = 0.8f;
        public int topK = 25;
        public float topP = 0.95f;
        public float repPen = 1.2f;
        public int repWindow = 64;
        public int maxFrames = 600;
    }

    @SuppressWarnings("unchecked")
    static final class Hist {
        final int w;
        final ArrayDeque<Integer>[] q;
        final HashMap<Integer, Integer>[] cnt;

        Hist(int n, int w) {
            this.w = w;
            q = new ArrayDeque[n];
            cnt = new HashMap[n];
            for (int i = 0; i < n; i++) { q[i] = new ArrayDeque<>(); cnt[i] = new HashMap<>(); }
        }

        void add(int ch, int code) {
            q[ch].addLast(code);
            cnt[ch].merge(code, 1, Integer::sum);
            if (w > 0 && q[ch].size() > w) {
                int old = q[ch].pollFirst();
                int c = cnt[ch].get(old) - 1;
                if (c <= 0) cnt[ch].remove(old); else cnt[ch].put(old, c);
            }
        }

        int size(int ch) { return cnt[ch].size(); }

        Iterable<Integer> codes(int ch) { return cnt[ch].keySet(); }
    }
}
