#!/usr/bin/env python3
"""
Generates short 2-second audio preview samples for all 17 Vietnamese Piper TTS voices.
Outputs compact WAV audio files to app/src/main/assets/voice_samples/{voice_id}.wav
so users can listen to each voice offline before downloading the full ~60MB model.
"""

import os
import sys
import json
import wave
import unicodedata
import urllib.request
import numpy as np

try:
    import onnxruntime as ort
except ImportError:
    print("onnxruntime is required to generate samples. Install via: pip install onnxruntime")
    sys.exit(0)

REPO_BASE = "https://huggingface.co/dinhphuongvn/ttsvn/resolve/main"
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS_DIR = os.path.join(REPO_ROOT, "app", "src", "main", "assets", "voice_samples")
os.makedirs(ASSETS_DIR, exist_ok=True)

VOICES = [
    ("ban_mai", "Xin chào, tôi là Ban Mai."),
    ("ngoc_ngan", "Xin chào, tôi là Nguyễn Ngọc Ngạn."),
    ("tran_thanh", "Xin chào, tôi là Trấn Thành."),
    ("mai_phuong", "Xin chào, tôi là Mai Phương."),
    ("duy_oryx", "Xin chào, tôi là Duy."),
    ("chieu_thanh", "Xin chào, tôi là Chiêu Thanh."),
    ("ngoc_huyen_new", "Xin chào, tôi là Ngọc Huyền."),
    ("manh_dung", "Xin chào, tôi là Mạnh Dũng."),
    ("thanh_phuong", "Xin chào, tôi là Thanh Phương."),
    ("minh_quang", "Xin chào, tôi là Minh Quang."),
    ("phuong_trang", "Xin chào, tôi là Phương Trang."),
    ("minh_khang", "Xin chào, tôi là Minh Khang."),
    ("tai_an", "Xin chào, tôi là Tài An."),
    ("lac_phi", "Xin chào, tôi là Lạc Phi."),
    ("thien_tam", "Xin chào, tôi là Thiện Tâm."),
    ("my_tam", "Xin chào, tôi là Mỹ Tâm."),
    ("viet_thao", "Xin chào, tôi là Việt Thảo.")
]

INITIALS = {
    "ngh": "ŋ", "nh": "ɲ", "ng": "ŋ", "gh": "ɣ", "gi": "z",
    "ch": "tʃ", "kh": "x", "ph": "f", "th": "t", "tr": "tʃ",
    "qu": "kw", "b": "b", "c": "k", "d": "z", "đ": "ɗ",
    "g": "ɣ", "h": "h", "k": "k", "l": "l", "m": "m",
    "n": "n", "p": "p", "q": "k", "r": "z", "s": "s",
    "t": "t̪", "v": "v", "x": "s"
}

VOWELS = {
    "a": "aː", "ă": "a", "â": "ə", "e": "ɛ", "ê": "e",
    "i": "i", "o": "ɔ", "ô": "o", "ơ": "əː", "u": "u",
    "ư": "y", "y": "i"
}

DIPHTHONGS = {
    "ươi": "yəj", "ười": "yəj", "uôi": "uəj", "uối": "uəj",
    "iêu": "iɛw", "yêu": "iɛw", "ươu": "yəw", "uây": "wəɪ",
    "oai": "waːj", "oay": "waj", "oeo": "wɛw", "uêu": "wew",
    "uya": "wiə", "uyê": "wiɛ", "ươ": "yə", "uô": "uə",
    "iê": "iɛ", "yê": "iɛ", "ia": "iə", "ua": "uə",
    "ưa": "yə", "ai": "aːj", "ao": "aːw", "au": "aw",
    "âu": "əw", "ay": "aj", "ây": "əɪ", "eo": "ɛw",
    "êu": "ew", "iu": "iw", "oa": "waː", "oă": "wa",
    "oe": "wɛ", "oi": "ɔj", "ôi": "oj", "ơi": "əːj",
    "oo": "ɔː", "ou": "ɔw", "ôô": "oː", "ui": "uj",
    "ưi": "yj", "uy": "wi", "uê": "we", "uơ": "wəː",
    "uâ": "wə", "ưu": "iw", "oao": "waːw", "uyu": "wiw"
}

FINALS = {
    "nh": "ɲ", "ng": "ŋ", "ch": "c", "c": "c",
    "m": "m", "n": "n", "p": "p", "t": "t̪"
}

TONE_MARKS = {
    '\u0300': "2",  # huyền
    '\u0301': "ɜ",  # sắc
    '\u0309': "4",  # hỏi
    '\u0303': "5",  # ngã
    '\u0323': "6"   # nặng
}

def phonemize_word(word: str) -> str:
    if not word:
        return ""
    w = word.lower()
    norm = unicodedata.normalize('NFD', w)
    tone = "7"
    cleaned = []
    for ch in norm:
        if ch in TONE_MARKS:
            tone = TONE_MARKS[ch]
        else:
            cleaned.append(ch)
    base = "".join(cleaned)

    if base.startswith("gi") and len(base) > 2:
        base = "gi" + base[2:]
    elif base in ("gi", "gì", "gí", "gỉ", "gĩ", "gị"):
        base = "gii"

    initial = ""
    rest = base
    for l in (3, 2, 1):
        if len(base) >= l and base[:l] in INITIALS:
            initial = base[:l]
            rest = base[l:]
            break

    final_c = ""
    vowel_part = rest
    for l in (2, 1):
        if len(rest) >= l and rest[-l:] in FINALS:
            final_c = rest[-l:]
            vowel_part = rest[:-l]
            break

    if initial == "qu" and vowel_part == "ô":
        initial = "q"
        vowel_part = "uô"

    ipa_vowel = DIPHTHONGS.get(vowel_part)
    if not ipa_vowel:
        ipa_vowel = "".join(VOWELS.get(c, c) for c in vowel_part)
    if not ipa_vowel and initial and not rest:
        ipa_vowel = "əː"

    if final_c in ("ch", "nh"):
        if ipa_vowel.endswith("aː"):
            ipa_vowel = ipa_vowel[:-2] + "e-"
        elif ipa_vowel.endswith("a"):
            ipa_vowel = ipa_vowel[:-1] + "e-"

    stress = "ˈ"
    if ipa_vowel.startswith("w"):
        formatted = "w" + stress + ipa_vowel[1:]
    else:
        formatted = stress + ipa_vowel

    offglide = ""
    if formatted.endswith("j"):
        formatted = formatted[:-1]
        offglide = "j"
    elif formatted.endswith("w"):
        formatted = formatted[:-1]
        offglide = "w"

    ipa_final = FINALS.get(final_c, final_c)
    if final_c == "c" and vowel_part in ("o", "ô", "oo", "uô"):
        ipa_final = "k"

    ipa_initial = INITIALS.get(initial, initial)
    return f"{ipa_initial}{formatted}{tone}{offglide}{ipa_final}"

def phonemize(text: str) -> str:
    tokens = text.split()
    res = []
    for t in tokens:
        word = t.rstrip(".,!?;:")
        punct = t[len(word):]
        p = phonemize_word(word)
        if punct:
            p += punct
        if p:
            res.append(p)
    return " ".join(res)

def text_to_ids(phonemized: str, phoneme_id_map: dict) -> list:
    start_id = phoneme_id_map.get("^", [None])[0]
    end_id = phoneme_id_map.get("$", [None])[0]
    pad_id = phoneme_id_map.get("_", [None])[0]

    norm = unicodedata.normalize('NFD', phonemized)
    result = []
    if start_id is not None: result.append(start_id)
    if pad_id is not None: result.append(pad_id)

    for ch in norm:
        ids = phoneme_id_map.get(ch)
        if ids:
            result.append(ids[0])
            if pad_id is not None: result.append(pad_id)

    if end_id is not None: result.append(end_id)
    return result

def download_file(url: str, dest: str):
    headers = {"User-Agent": "Mozilla/5.0"}
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=120) as resp, open(dest, "wb") as f:
        f.write(resp.read())

def generate_sample(voice_id: str, sentence: str):
    target_wav = os.path.join(ASSETS_DIR, f"{voice_id}.wav")
    if os.path.exists(target_wav) and os.path.getsize(target_wav) > 1000:
        print(f"[{voice_id}] Already exists: {target_wav} ({os.path.getsize(target_wav)} bytes)")
        return

    print(f"[{voice_id}] Generating sample: '{sentence}'...")
    tmp_json = f"/tmp/{voice_id}.onnx.json"
    tmp_onnx = f"/tmp/{voice_id}.onnx"

    try:
        download_file(f"{REPO_BASE}/{voice_id}.onnx.json", tmp_json)
        download_file(f"{REPO_BASE}/{voice_id}.onnx", tmp_onnx)

        with open(tmp_json, "r", encoding="utf-8") as f:
            cfg = json.load(f)

        id_map = cfg.get("phoneme_id_map", {})
        sample_rate = cfg.get("audio", {}).get("sample_rate", 22050)
        noise_scale = float(cfg.get("inference", {}).get("noise_scale", 0.667))
        length_scale = float(cfg.get("inference", {}).get("length_scale", 1.0))
        noise_w = float(cfg.get("inference", {}).get("noise_w", 0.8))
        num_speakers = int(cfg.get("num_speakers", 1))

        ph = phonemize(sentence)
        ids = text_to_ids(ph, id_map)
        if not ids:
            print(f"[{voice_id}] Failed to convert text to IDs")
            return

        opts = ort.SessionOptions()
        opts.log_severity_level = 3
        opts.intra_op_num_threads = 2
        opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL
        session = ort.InferenceSession(tmp_onnx, opts, providers=["CPUExecutionProvider"])

        feed = {
            "input": np.array([ids], dtype=np.int64),
            "input_lengths": np.array([len(ids)], dtype=np.int64),
            "scales": np.array([noise_scale, length_scale, noise_w], dtype=np.float32)
        }
        if num_speakers > 1:
            feed["sid"] = np.array([0], dtype=np.int64)

        outs = session.run(None, feed)
        audio = np.squeeze(outs[0])
        pcm16 = (np.clip(audio, -1.0, 1.0) * 32767.0).astype(np.int16)

        with wave.open(target_wav, "wb") as wf:
            wf.setnchannels(1)
            wf.setsampwidth(2)
            wf.setframerate(sample_rate)
            wf.writeframes(pcm16.tobytes())

        print(f"[{voice_id}] Successfully generated: {target_wav} ({os.path.getsize(target_wav)} bytes)")
    except Exception as e:
        print(f"[{voice_id}] Error: {e}")
    finally:
        if os.path.exists(tmp_json): os.remove(tmp_json)
        if os.path.exists(tmp_onnx): os.remove(tmp_onnx)

def main():
    print(f"=== Generating Voice Samples for {len(VOICES)} Voices ===")
    for vid, text in VOICES:
        generate_sample(vid, text)
    print("=== Done ===")

if __name__ == "__main__":
    main()
