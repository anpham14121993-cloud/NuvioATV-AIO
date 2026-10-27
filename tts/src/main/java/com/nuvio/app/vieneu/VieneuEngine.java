package com.nuvio.app.vieneu;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
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
import java.util.function.BooleanSupplier;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/** Torch-free VieNeu-TTS v3 Turbo inference ported from onnx_runtime_lite.py. */
public final class VieneuEngine {
    public static final int SR = 48000;

    /** Optional trace sink installed by the app (never throws). */
    public static volatile java.util.function.Consumer<String> TRACE = null;
    /** CPU feature string from the app, repeated on every prefill heartbeat. */
    public static volatile String CPU = "?";
    static void tr(String msg) {
        java.util.function.Consumer<String> t = TRACE;
        if (t != null) { try { t.accept(msg); } catch (Throwable ignored) {} }
    }
    private static void mem(String tag) {
        Runtime r = Runtime.getRuntime();
        long usedMb = (r.totalMemory() - r.freeMemory()) / 1048576L;
        long maxMb = r.maxMemory() / 1048576L;
        tr(tag + " mem=" + usedMb + "/" + maxMb + "MB" + procMem());
    }

    /**
     * Real resident and virtual size of the whole process from /proc/self/statm.
     *
     * The Java heap numbers above say nothing about the ONNX runtime's own
     * mappings, and Debug.getNativeHeapAllocatedSize only counts malloc'd native
     * memory - neither sees the mmap'd 99 MB backbone or ORT's arenas. On a
     * 32-bit box that is exactly where the room runs out, so the real RSS and
     * VmSize have to be in the log to tell "out of memory" from "native crash".
     */
    private static String procMem() {
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.FileReader("/proc/self/statm"))) {
            String[] f = br.readLine().trim().split("\\s+");
            long vszMb = Long.parseLong(f[0]) * 4L / 1024L;
            long rssMb = Long.parseLong(f[1]) * 4L / 1024L;
            sb.append(" rss=").append(rssMb).append("MB vsz=").append(vszMb).append("MB");
        } catch (Throwable ignored) {}
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.FileReader("/proc/meminfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemAvailable:")) {
                    long kb = Long.parseLong(line.replaceAll("[^0-9]", ""));
                    sb.append(" avail=").append(kb / 1024L).append("MB");
                    break;
                }
            }
        } catch (Throwable ignored) {}
        return sb.toString();
    }

    private static String phaseName(int p) {
        return p == 1 ? "load" : p == 2 ? "run" : "kv";
    }

    private static final int H = 768, NVQ = 16, L = 12, NH_LOC = 8, HD_LOC = 96;
    private static final long AUDIO_PAD = 1024, TPS = 3, TPE = 4, SGS = 5, EOS_SPEECH = 6, REF_SLOT = 7;
    private static final int TEXT_VOCAB = 419, AUDIO_VOCAB = 1024, STYLE_ID = 16;

    private final OrtEnvironment env;
    // Every graph is opened once and kept for the engine's lifetime. Session
    // setup is 5 s for the 99 MB prefill backbone on this box, so paying it per
    // subtitle line was most of the "reads one sentence, then goes silent"
    // behaviour: each new cue also had to reload the graph before it could
    // start. A 32-bit process can hold all four (weights are mmap-backed).
    private OrtSession sPre, sDec, sAc, sCodec;
    private final OrtSession.SessionOptions opts;
    private final File onnxDir, codecDir;
    private final BpeTokenizer tok;
    // The G2P dictionary maps 63 MB. It is only needed while phonemizing, so it
    // is closed again before the ONNX weights load - a 32-bit process cannot
    // hold both at once.
    private G2P g2p;
    private final String g2pPath;
    private final Random rnd = new Random(20261016L);
    private boolean lastEos;
    private volatile boolean closed;

    // heads.bin is mapped, never copied into the Java heap: audio_emb alone is
    // 16*1024*768 floats (48 MB), which a 32-bit box cannot afford on top of the
    // ONNX weights. The kernel pages in only what a synthesis actually touches.
    private MappedByteBuffer headsMap;
    private FloatBuffer textEmb, audioEmb, xvecW, xvecB, lnW, lnB;
    // Sequential row views reused by the sampling loops; every synthesis is
    // serialized on the caller's lock, so one mutable position is safe.
    private FloatBuffer textEmbView, audioEmbView;
    private float lnEps;

    private final float[] idxBuf = new float[1024];
    private final int[] candBuf = new int[1024];
    private final float[] rowBuf = new float[H];
    private final float[] heapV = new float[64];
    private final int[] heapI = new int[64];
    // Per-line stage timings, reset at the start of the AR loop (synthesis is
    // serialized on the caller's lock, so these need no synchronization).
    private long tAcNs, tDecNs, tSmpNs;
    private int acRuns, decRuns;

    public VieneuEngine(File modelDir, File headsBin, String tokenizerJson,
                        String g2pBin, int threads) throws Exception {
        tr("ctor: env start");
        env = OrtEnvironment.getEnvironment();
        tr("ctor: env ok " + env);
        tr("ctor: options start");
        opts = newOptions(threads);
        tr("ctor: options ok threads=" + Math.max(1, threads) + " opt=no_opt");
        onnxDir = new File(modelDir, "onnx_int8");
        codecDir = new File(modelDir, "codec");
        // No ONNX session is opened here: the engine load itself must stay small
        // on a 32-bit box. Each graph is opened on its first real use and then
        // kept for every later cue.
        mem("ctor: sessions");

        tr("ctor: heads start");
        loadHeads(headsBin);
        tr("ctor: heads ok");
        tr("ctor: tokenizer start");
        tok = new BpeTokenizer(tokenizerJson);
        tr("ctor: tokenizer ok");
        tr("ctor: g2p start");
        g2pPath = g2pBin;
        g2p = new G2P(g2pBin);
        tr("ctor: g2p ok");
        tr("ctor: done");
    }

    private void loadHeads(File f) throws Exception {
        long len;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            len = raf.length();
            long expected = (long) (TEXT_VOCAB * H + NVQ * AUDIO_VOCAB * H + H * 192 + 3 * H + 1) * 4L;
            if (len < expected) {
                throw new IllegalStateException("heads.bin too short: " + len + " < " + expected);
            }
            headsMap = raf.getChannel().map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, len);
        }
        headsMap.order(ByteOrder.LITTLE_ENDIAN);
        FloatBuffer fb = headsMap.asFloatBuffer();
        int p = 0;
        textEmb = slice(fb, p, TEXT_VOCAB * H); p += TEXT_VOCAB * H;
        audioEmb = slice(fb, p, NVQ * AUDIO_VOCAB * H); p += NVQ * AUDIO_VOCAB * H;
        textEmbView = textEmb.duplicate();
        audioEmbView = audioEmb.duplicate();
        xvecW = slice(fb, p, H * 192); p += H * 192;
        xvecB = slice(fb, p, H); p += H;
        lnW = slice(fb, p, H); p += H;
        lnB = slice(fb, p, H); p += H;
        lnEps = fb.get(p);
        tr("heads: mapped " + (len / 1048576L) + "MB");
    }

    /** A read-only view over a region of the mapped file; no heap copy. */
    private static FloatBuffer slice(FloatBuffer src, int offset, int length) {
        FloatBuffer d = src.duplicate();
        d.position(offset);
        d.limit(offset + length);
        return d.slice();
    }

    /** Copies one H-long row out of a mapped table into dst at dstOff. */
    private static void getRow(FloatBuffer table, int row, float[] dst, int dstOff) {
        FloatBuffer d = table.duplicate();
        d.position(row * H);
        d.get(dst, dstOff, H);
    }

    private static void row(FloatBuffer view, int row, float[] dst) {
        view.position(row * H);
        view.get(dst, 0, H);
    }

    private static void getRow(FloatBuffer table, int row, float[] dst) {
        getRow(table, row, dst, 0);
    }

    /**
     * Same dot over a heap row. The 16 sampling heads read the whole 48 MB
     * audio_emb table every frame; copying each row once and running a plain
     * array loop is measurably faster than 12.6 M direct-buffer get() calls.
     */
    private static float dotArr(float[] a, float[] b) {
        float s0 = 0, s1 = 0, s2 = 0, s3 = 0;
        for (int h = 0; h < H; h += 4) {
            s0 += a[h] * b[h];
            s1 += a[h + 1] * b[h + 1];
            s2 += a[h + 2] * b[h + 2];
            s3 += a[h + 3] * b[h + 3];
        }
        return (s0 + s1) + (s2 + s3);
    }

    /**
     * Graph optimization stays at NO_OPT. Both attempts to enable ORT's
     * optimizer on this box (7.5.41: all four graphs at movie start with
     * BASIC/EXTENDED; 7.5.43: only the 7 MB acoustic graph, opened lazily on
     * the first cue) took the process down, so the packed int8 kernels are
     * unusable on this ARMv7 core. Keep the plain kernels and the arena that
     * 7.5.42 proved stable; the speed work left is session reuse, which is
     * already done.
     */
    private static OrtSession.SessionOptions newOptions(int threads) throws Exception {
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(Math.max(1, threads));
        o.setInterOpNumThreads(1);
        // Weak TV boxes run out of memory long before the weights fit: keep the
        // allocator small instead of letting ORT reserve big arenas.
        o.setMemoryPatternOptimization(false);
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
        o.addConfigEntry("session.disable_prepacking", "1");
        // The CPU arena stays enabled. Disabling it frees the initializers
        // through the default allocator, which crashed while loading the int8
        // graph on a 32-bit Android box (see dc4e97f).
        o.setCPUArenaAllocator(true);
        return o;
    }

    /**
     * Breadcrumbs on both sides of the native session creation: if a graph
     * still kills the process, narration_prev.log names the one that did and
     * the last line shows whether the death happened during load.
     */
    private OrtSession openSession(String name, OrtSession.SessionOptions options) throws Exception {
        File f = new File(onnxDir, name);
        if (!f.isFile()) f = new File(codecDir, name);
        tr("open: " + name + " start");
        long t = System.nanoTime();
        OrtSession s = env.createSession(f.getAbsolutePath(), options);
        tr("open: " + name + " done " + (System.nanoTime() - t) / 1000000L + "ms");
        mem("open: " + name);
        return s;
    }

    private static void closeQuietly(OrtSession s) {
        if (s != null) try { s.close(); } catch (Exception ignored) {}
    }

    /** Aborts a synthesis whose cue was stopped or replaced by a newer one. */
    public static final class Cancelled extends Exception {
        Cancelled(String reason) { super(reason, null, false, false); }
    }

    private static void ensureActive(BooleanSupplier cancelled, BooleanSupplier superseded) throws Cancelled {
        if (cancelled != null && cancelled.getAsBoolean()) throw new Cancelled("cancelled");
        if (superseded != null && superseded.getAsBoolean()) throw new Cancelled("superseded");
    }

    private static void ensureNotCancelled(BooleanSupplier cancelled) throws Cancelled {
        if (cancelled != null && cancelled.getAsBoolean()) throw new Cancelled("cancelled");
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
            float s = xvecB.get(h);
            int off = h * 192;
            for (int k = 0; k < 192; k++) s += xvecW.get(off + k) * emb192[k];
            f[h] = s;
        }
        double mean = 0; for (float x : f) mean += x; mean /= H;
        double var = 0; for (float x : f) { double d = x - mean; var += d * d; } var /= H;
        double inv = 1.0 / Math.sqrt(var + lnEps);
        float[] a = new float[H];
        for (int h = 0; h < H; h++) a[h] = (float) ((f[h] - mean) * inv) * lnW.get(h) + lnB.get(h);
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
        getRow(textEmb, (int) row[0], e);
        for (int ch = 0; ch < NVQ; ch++) {
            long id = row[ch + 1];
            if (id != AUDIO_PAD) {
                int base = (ch * AUDIO_VOCAB + (int) id) * H;
                for (int h = 0; h < H; h++) e[h] += audioEmb.get(base + h);
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
        int rowBase = ch * AUDIO_VOCAB;
        float[] row = rowBuf;
        for (int v = 0; v < AUDIO_VOCAB; v++) {
            row(audioEmbView, rowBase + v, row);
            idxBuf[v] = dotArr(row, vec);
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
            // k is 25 by default; sorting the primitive candidate arrays in
            // place avoids boxing 25 Integers and a comparator per channel.
            for (int i = 1; i < n; i++) {
                float v = heapV[i];
                int ci = heapI[i];
                int j = i - 1;
                while (j >= 0 && heapV[j] < v) {
                    heapV[j + 1] = heapV[j];
                    heapI[j + 1] = heapI[j];
                    j--;
                }
                heapV[j + 1] = v;
                heapI[j + 1] = ci;
            }
            float max = heapV[0];
            float sum = 0;
            for (int i = 0; i < n; i++) { float e = (float) Math.exp(heapV[i] - max); heapV[i] = e; sum += e; }
            for (int i = 0; i < n; i++) heapV[i] /= sum;
            int keep = n;
            if (p.topP > 0 && p.topP < 1f) {
                float cum = 0; keep = n;
                for (int i = 0; i < n; i++) {
                    float pi = heapV[i];
                    if (cum >= p.topP) { keep = i; break; }
                    cum += pi;
                }
                if (keep < 1) keep = 1;
            }
            float total = 0; for (int i = 0; i < keep; i++) total += heapV[i];
            double r = rnd.nextDouble() * total;
            float acc = 0; code = heapI[keep - 1];
            for (int i = 0; i < keep; i++) { acc += heapV[i]; if (r < acc) { code = heapI[i]; break; } }
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
        getRow(textEmb, (int) SGS, tok2, H);
        Map<String, OnnxTensor> in = new HashMap<>();
        OnnxTensor epk = emptyPast(), epv = emptyPast();
        in.put("token_emb", ft(tok2, 1, 2, H));
        in.put("position_ids", lt(new long[]{0, 1}, 1, 2));
        in.put("past_k_0", epk); in.put("past_v_0", epv);
        long t0 = System.nanoTime();
        OrtSession.Result ar = sAc.run(in);
        tAcNs += System.nanoTime() - t0; acRuns++;
        closeAll(in);
        float[] ah = readF(T(ar, "hidden"));
        float[] slot0 = Arrays.copyOfRange(ah, 0, H);
        float[] condOut = Arrays.copyOfRange(ah, H, 2 * H);
        OnnxTensor npk = T(ar, "present_k_0");
        OnnxTensor npv = T(ar, "present_v_0");
        long s0 = System.nanoTime();
        codes[0] = sampleChannel(condOut, 0, p, hist);
        tSmpNs += System.nanoTime() - s0;

        for (int ch = 1; ch < NVQ; ch++) {
            float[] emb = new float[H];
            getRow(audioEmb, (ch - 1) * AUDIO_VOCAB + codes[ch - 1], emb);
            Map<String, OnnxTensor> in2 = new HashMap<>();
            in2.put("token_emb", ft(emb, 1, 1, H));
            in2.put("position_ids", lt(new long[]{ch + 1}, 1, 1));
            in2.put("past_k_0", npk); in2.put("past_v_0", npv);
            long t2 = System.nanoTime();
            OrtSession.Result ar2 = sAc.run(in2);
            tAcNs += System.nanoTime() - t2; acRuns++;
            closeAll(in2);
            float[] ah2 = readF(T(ar2, "hidden"));
            OnnxTensor npk2 = T(ar2, "present_k_0");
            OnnxTensor npv2 = T(ar2, "present_v_0");
            ar.close();
            ar = ar2; npk = npk2; npv = npv2;
            long s2 = System.nanoTime();
            codes[ch] = sampleChannel(ah2, ch, p, hist);
            tSmpNs += System.nanoTime() - s2;
        }
        ar.close();

        float[] tl = new float[TEXT_VOCAB];
        for (int v = 0; v < TEXT_VOCAB; v++) {
            row(textEmbView, v, rowBuf);
            tl[v] = dotArr(rowBuf, slot0);
        }
        int am = 0; for (int v = 1; v < TEXT_VOCAB; v++) if (tl[v] > tl[am]) am = v;
        lastEos = am == (int) EOS_SPEECH;
        return codes;
    }

    // ── public synthesis ────────────────────────────────────────────────────────
    /**
     * @param cancelled  set when the user stopped narration or a background
     *                   prefetch must yield; checked between stages and on
     *                   every AR frame
     * @param superseded set when a newer cue replaced this one; checked before
     *                   prefill and before AR. Production callers pass null:
     *                   once a line starts it finishes and plays, because
     *                   aborting it left every line dropped on slow hardware.
     */
    public float[] synthesize(String text, float[] speakerEmb, int[][] refCodes, Params p,
                              BooleanSupplier cancelled, BooleanSupplier superseded) throws Exception {
        ensureActive(cancelled, superseded);
        mem("synth: before-g2p");
        if (g2p == null) g2p = new G2P(g2pPath);
        String phonemes = g2p.phonemize(text);
        tr("synth: g2p ok len=" + (phonemes == null ? -1 : phonemes.length()));
        // Release the 63 MB dictionary before the ONNX weights load.
        g2p.close();
        g2p = null;
        mem("synth: after-g2p");
        return synthesizePhonemes(phonemes, speakerEmb, refCodes, p, cancelled, superseded);
    }

    public float[] synthesizePhonemes(String phonemes, float[] speakerEmb, int[][] refCodes, Params p,
                                      BooleanSupplier cancelled, BooleanSupplier superseded) throws Exception {
        if (closed) throw new IllegalStateException("engine closed");
        ensureActive(cancelled, superseded);
        float[] anchor = speakerAnchor(speakerEmb);
        long[][] rows = buildRows(phonemes, refCodes);
        tr("synth: rows=" + rows.length);
        float[] prompt = embedRows(rows, anchor);
        int T = rows.length;

        Map<String, OnnxTensor> pin = new HashMap<>();
        pin.put("inputs_embeds", ft(prompt, 1, T, H));
        long synthStart = System.nanoTime();
        tr("synth: prefill session open");
        final long prefillStart = System.nanoTime();
        final java.util.concurrent.atomic.AtomicInteger phase =
                new java.util.concurrent.atomic.AtomicInteger(1);
        final java.util.concurrent.atomic.AtomicBoolean hbDone =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        // Nothing is written between "prefill session open" and the first result
        // of the run, so a slow load and a slow run look identical on screen.
        // This beat keeps the narration dialog's tail moving with the phase.
        Thread hb = new Thread(() -> {
            for (int i = 1; i <= 60 && !hbDone.get(); i++) {
                try { Thread.sleep(5000); } catch (InterruptedException ie) { return; }
                if (hbDone.get()) return;
                long ms = (System.nanoTime() - prefillStart) / 1000000L;
                tr("hb " + (ms / 1000) + "s " + phaseName(phase.get()) + " " + CPU);
            }
        }, "vieneu-prefill-hb");
        hb.setDaemon(true);
        hb.start();
        OrtSession.Result cur = null;
        float[] h = null;
        // The prefill outputs are copied out so the Result (and its KV tensors)
        // can be freed, while the session itself stays open for the next cue.
        long[][] kvShape = new long[L][];
        float[][] kvData = new float[L * 2][];
        try {
            ensureActive(cancelled, superseded);
            if (sPre == null) sPre = openSession("vieneu_prefill.onnx", opts);
            phase.set(2);
            long runStart = System.nanoTime();
            cur = sPre.run(pin);
            tr("synth: prefill run " + (System.nanoTime() - runStart) / 1000000L + "ms rows=" + T);
            float[] hid = readF(T(cur, "hidden"));
            h = Arrays.copyOfRange(hid, (T - 1) * H, T * H);
            for (int i = 0; i < L; i++) {
                OnnxTensor k = T(cur, "present_k_" + i);
                OnnxTensor v = T(cur, "present_v_" + i);
                kvShape[i] = k.getInfo().getShape();
                kvData[i * 2] = readF(k);
                kvData[i * 2 + 1] = readF(v);
            }
        } finally {
            closeAll(pin);
            hbDone.set(true);
            hb.interrupt();
            if (cur != null) try { cur.close(); } catch (Exception ignored) {}
        }
        if (h == null) throw new IllegalStateException("prefill produced no hidden state");
        long prefillMs = (System.nanoTime() - prefillStart) / 1000000L;
        tr("synth: prefill done " + prefillMs + "ms");
        mem("synth: after-prefill");

        // Do not start the expensive autoregressive loop for a cue that is
        // already obsolete; the next one is waiting behind the synth lock.
        ensureActive(cancelled, superseded);

        // Both hot graphs opened on the first cue survive later cues: their
        // session setup was paid again on every subtitle line before.
        if (sAc == null) sAc = openSession("vieneu_acoustic_cached.onnx", opts);
        if (sDec == null) sDec = openSession("vieneu_decode_step.onnx", opts);
        OnnxTensor[] pk = new OnnxTensor[L], pv = new OnnxTensor[L];
        for (int i = 0; i < L; i++) {
            pk[i] = OnnxTensor.createTensor(env, FloatBuffer.wrap(kvData[i * 2]), kvShape[i]);
            pv[i] = OnnxTensor.createTensor(env, FloatBuffer.wrap(kvData[i * 2 + 1]), kvShape[i]);
        }
        kvData = null;

        Hist hist = (p.repPen != 1f) ? new Hist(NVQ, p.repWindow) : null;
        List<int[]> frames = new ArrayList<>();
        int cap = frameCap(phonemes, p.maxFrames);
        long arStart = System.nanoTime();
        tAcNs = 0; tDecNs = 0; tSmpNs = 0; acRuns = 0; decRuns = 0;
        boolean eos = false;
        tr("synth: ar start cap=" + cap);
        // The first pk/pv are copies of the prefill cache, not owned by a
        // Result; the ones created by decode runs are owned by cur.
        boolean ownKv = true;
        try {
            for (int t = 0; t < cap; t++) {
                // A volatile read per frame is free next to a frame's ONNX work;
                // at every 8 frames a stop could take seconds to reach.
                ensureNotCancelled(cancelled);
                if (t > 0 && t % 32 == 0) {
                    long ms = (System.nanoTime() - arStart) / 1000000L;
                    tr("synth: ar t=" + t + " " + ms + "ms avg=" + (ms / t) + "ms/frame");
                }
                int[] codes = acousticFrame(h, p, hist);
                frames.add(codes);
                if (lastEos) { eos = true; break; }

                long[] slot = new long[NVQ + 1];
                Arrays.fill(slot, AUDIO_PAD);
                slot[0] = SGS;
                for (int i = 0; i < NVQ; i++) slot[i + 1] = codes[i];
                float[] se = embedRow(slot, anchor);
                Map<String, OnnxTensor> din = new HashMap<>();
                din.put("inputs_embeds", ft(se, 1, 1, H));
                din.put("position_ids", lt(new long[]{T + t}, 1, 1));
                for (int i = 0; i < L; i++) { din.put("past_k_" + i, pk[i]); din.put("past_v_" + i, pv[i]); }
                OrtSession.Result dr;
                long td = System.nanoTime();
                try {
                    dr = sDec.run(din);
                } finally {
                    tDecNs += System.nanoTime() - td;
                    decRuns++;
                    // close only inputs we created (inputs_embeds/position_ids); past owned by cur
                    try { din.get("inputs_embeds").close(); } catch (Exception ignored) {}
                    try { din.get("position_ids").close(); } catch (Exception ignored) {}
                }
                float[] dh = readF(T(dr, "hidden"));
                h = dh;
                OnnxTensor[] npk = new OnnxTensor[L], npv = new OnnxTensor[L];
                for (int i = 0; i < L; i++) {
                    npk[i] = T(dr, "present_k_" + i);
                    npv[i] = T(dr, "present_v_" + i);
                }
                cur.close();
                if (ownKv) {
                    for (int i = 0; i < L; i++) { pk[i].close(); pv[i].close(); }
                    ownKv = false;
                }
                cur = dr; pk = npk; pv = npv;
            }
        } finally {
            if (ownKv) {
                for (int i = 0; i < L; i++) {
                    try { pk[i].close(); } catch (Exception ignored) {}
                    try { pv[i].close(); } catch (Exception ignored) {}
                }
            }
            if (cur != null) try { cur.close(); } catch (Exception ignored) {}
        }
        // Once the AR loop has run, finish the codec so the line is heard: a
        // newer cue will cut it when its own audio is ready, and dropping it
        // here would leave silence whenever cues arrive faster than the synth.
        ensureNotCancelled(cancelled);
        tr("synth: ar done frames=" + frames.size() + " eos=" + eos + " cap=" + cap
                + " " + (System.nanoTime() - arStart) / 1000000L + "ms");
        // One compact line per cue so the 12-line diag tail still shows the
        // split between acoustic runs, decode runs and Java sampling.
        tr("line f=" + frames.size() + " pre=" + prefillMs
                + " ac=" + tAcNs / 1000000L + "/" + acRuns
                + " dec=" + tDecNs / 1000000L + "/" + decRuns
                + " smp=" + tSmpNs / 1000000L
                + " ar=" + (System.nanoTime() - arStart) / 1000000L + "ms");
        mem("synth: before-codec");
        float[] out = decodeCodes(frames);
        tr("synth: total " + (System.nanoTime() - synthStart) / 1000000L + "ms frames=" + frames.size()
                + " samples=" + out.length);
        mem("synth: after-codec");
        return out;
    }

    /** Releases the ONNX sessions. The engine cannot be used afterwards. */
    public void close() {
        closed = true;
        closeQuietly(sPre); sPre = null;
        closeQuietly(sDec); sDec = null;
        closeQuietly(sAc); sAc = null;
        closeQuietly(sCodec); sCodec = null;
        try { opts.close(); } catch (Exception ignored) {}
        if (g2p != null) try { g2p.close(); } catch (Exception ignored) {}
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
        try {
            in.put("audio_codes", it(codes, 1, T, NVQ));
            in.put("audio_code_lengths", it(lens, 1));
            if (sCodec == null) sCodec = openSession("moss_audio_tokenizer_decode_full.onnx", opts);
            long codecStart = System.nanoTime();
            OrtSession.Result r = sCodec.run(in);
            try {
                tr("synth: codec run " + (System.nanoTime() - codecStart) / 1000000L + "ms");
                float[] audio = readF(T(r, "audio"));
                int n = readI(T(r, "audio_lengths"))[0];
                int ch = audio.length / n;
                float[] wav = new float[n];
                for (int i = 0; i < n; i++) {
                    float s = 0;
                    for (int c = 0; c < ch; c++) s += audio[c * n + i];
                    wav[i] = s / ch;
                }
                return wav;
            } finally {
                try { r.close(); } catch (Exception ignored) {}
            }
        } finally {
            closeAll(in);
        }
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
