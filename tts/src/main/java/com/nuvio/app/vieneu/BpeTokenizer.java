package com.nuvio.app.vieneu;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** ByteLevel (GPT-2 style) BPE, matching the bundled tokenizer.json exactly. */
public final class BpeTokenizer {
    private final HashMap<String, Integer> vocab = new HashMap<>();
    private final HashMap<String, Integer> ranks = new HashMap<>();
    private final Pattern split;
    private final int unkId;
    private final String[] byteToChar = new String[256];

    public BpeTokenizer(String tokenizerJson) throws Exception {
        JSONObject root = new JSONObject(tokenizerJson);
        JSONObject model = root.getJSONObject("model");
        JSONObject v = model.getJSONObject("vocab");
        for (java.util.Iterator<String> it = v.keys(); it.hasNext(); ) {
            String k = it.next();
            vocab.put(k, v.getInt(k));
        }
        JSONArray merges = model.getJSONArray("merges");
        for (int i = 0; i < merges.length(); i++) {
            JSONArray m = merges.getJSONArray(i);
            ranks.put(m.getString(0) + "\u0000" + m.getString(1), i);
        }
        Integer unk = vocab.get(model.optString("unk_token", "<|unk|>"));
        unkId = unk != null ? unk : 0;

        String p = null;
        JSONObject pre = root.getJSONObject("pre_tokenizer");
        JSONArray seq = pre.optJSONArray("pretokenizers");
        if (seq != null) {
            for (int i = 0; i < seq.length(); i++) {
                JSONObject s = seq.getJSONObject(i);
                if ("Split".equals(s.optString("type"))) {
                    p = s.getJSONObject("pattern").optString("Regex", null);
                }
            }
        }
        if (p == null) {
            p = "(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}| ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+";
        }
        split = Pattern.compile(p);

        buildByteMap();
    }

    private void buildByteMap() {
        List<Integer> bs = new ArrayList<>();
        List<Integer> cs = new ArrayList<>();
        for (int b = '!'; b <= '~'; b++) { bs.add(b); cs.add(b); }
        for (int b = 0xA1; b <= 0xAC; b++) { bs.add(b); cs.add(b); }
        for (int b = 0xAE; b <= 0xFF; b++) { bs.add(b); cs.add(b); }
        int n = 0;
        for (int b = 0; b < 256; b++) {
            if (!bs.contains(b)) { bs.add(b); cs.add(256 + n); n++; }
        }
        for (int i = 0; i < bs.size(); i++) {
            byteToChar[bs.get(i)] = String.valueOf((char) (int) cs.get(i));
        }
    }

    public int[] encode(String text) {
        String norm = Normalizer.normalize(text, Normalizer.Form.NFC);
        List<Integer> ids = new ArrayList<>();
        Matcher m = split.matcher(norm);
        while (m.find()) {
            String piece = m.group();
            if (vocab.containsKey(piece)) {
                ids.add(vocab.get(piece));
                continue;
            }
            byte[] bytes = piece.getBytes(StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder(bytes.length);
            for (byte b : bytes) sb.append(byteToChar[b & 0xFF]);
            for (String sym : bpe(sb.toString())) {
                Integer id = vocab.get(sym);
                ids.add(id != null ? id : unkId);
            }
        }
        int[] out = new int[ids.size()];
        for (int i = 0; i < out.length; i++) out[i] = ids.get(i);
        return out;
    }

    private List<String> bpe(String chars) {
        List<String> w = new ArrayList<>(chars.length());
        for (int i = 0; i < chars.length(); i++) w.add(String.valueOf(chars.charAt(i)));
        while (w.size() > 1) {
            int best = Integer.MAX_VALUE, bi = -1;
            for (int i = 0; i < w.size() - 1; i++) {
                Integer r = ranks.get(w.get(i) + "\u0000" + w.get(i + 1));
                if (r != null && r < best) { best = r; bi = i; }
            }
            if (bi < 0) break;
            w.set(bi, w.get(bi) + w.get(bi + 1));
            w.remove(bi + 1);
        }
        return w;
    }
}
