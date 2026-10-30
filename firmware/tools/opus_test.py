#!/usr/bin/env python3
"""How much Opus compression can the speech model take?

For each recording (raw 16 kHz, 16-bit, mono .pcm, like the board and the app
store them), this transcribes the raw audio as the reference, then encodes it
with Opus at several bitrates and encoder efforts ("complexity"), decodes it
again and transcribes that. It prints the size and the word error rate (WER)
against the raw transcript for every setting, then a summary.

Transcription uses onnx-asr, which runs the same Parakeet model as the phone
(the phone uses Cactus; same model, different runtime). Encoding uses ffmpeg's
libopus.

    .venv/bin/python opus_test.py recordings/phone/*.pcm
    .venv/bin/python opus_test.py --bitrates 12,16 --complexities 0,10 a.pcm
"""

import argparse
import re
import subprocess
import sys
from pathlib import Path

import numpy as np
import onnx_asr

SAMPLE_RATE = 16000
MODELS = {"v3": "nemo-parakeet-tdt-0.6b-v3", "v2": "nemo-parakeet-tdt-0.6b-v2"}


def read_pcm(path):
    return np.fromfile(path, dtype="<i2")


def to_float(samples):
    return samples.astype(np.float32) / 32768.0


def opus_round_trip(pcm_path, kbps, complexity, frame_ms, application):
    """Encode with Opus, decode back to 16 kHz PCM. Returns (samples, opus bytes)."""
    encode = [
        "ffmpeg", "-v", "error", "-f", "s16le", "-ar", str(SAMPLE_RATE), "-ac", "1",
        "-i", str(pcm_path), "-c:a", "libopus", "-b:a", f"{kbps}k",
        "-application", application, "-frame_duration", str(frame_ms),
        "-compression_level", str(complexity), "-f", "ogg", "-",
    ]
    opus = subprocess.run(encode, check=True, capture_output=True).stdout
    decode = [
        "ffmpeg", "-v", "error", "-f", "ogg", "-i", "-",
        "-f", "s16le", "-ar", str(SAMPLE_RATE), "-ac", "1", "-",
    ]
    pcm = subprocess.run(decode, input=opus, check=True, capture_output=True).stdout
    return np.frombuffer(pcm, dtype="<i2"), len(opus)


def words(text):
    return re.sub(r"[^\w\s']", " ", text.lower()).split()


def word_error_rate(reference, hypothesis):
    """Word-level edit distance (substitutions + insertions + deletions) / reference words."""
    ref, hyp = words(reference), words(hypothesis)
    if not ref:
        return 0.0 if not hyp else 1.0
    previous = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        current = [i]
        for j, h in enumerate(hyp, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (r != h)))
        previous = current
    return previous[-1] / len(ref)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("files", nargs="+", type=Path, help="raw 16 kHz 16-bit mono .pcm files")
    parser.add_argument("--model", choices=MODELS, default="v3", help="Parakeet v3 (multilingual) or v2 (English)")
    parser.add_argument("--bitrates", default="8,12,16,24", help="kbps, comma-separated")
    parser.add_argument("--complexities", default="0,5,10", help="Opus encoder effort 0-10, comma-separated")
    parser.add_argument("--frame", type=int, default=20, help="Opus frame length in ms")
    parser.add_argument("--application", choices=["voip", "audio"], default="voip",
                        help="Opus mode: voip (tuned for speech) or audio")
    args = parser.parse_args()

    bitrates = [int(b) for b in args.bitrates.split(",")]
    complexities = [int(c) for c in args.complexities.split(",")]
    settings = [(b, c) for b in bitrates for c in complexities]

    print(f"Loading {MODELS[args.model]} (int8; first run downloads it) ...", file=sys.stderr)
    model = onnx_asr.load_model(MODELS[args.model], quantization="int8")

    totals = {s: {"errors": 0.0, "words": 0, "bytes": 0} for s in settings}
    raw_bytes = 0
    seconds = 0.0

    for path in args.files:
        samples = read_pcm(path)
        duration = len(samples) / SAMPLE_RATE
        reference = model.recognize(to_float(samples))
        raw_bytes += len(samples) * 2
        seconds += duration
        print(f"\n== {path.name}  {duration:.1f} s  raw {len(samples) * 2 // 1024} KB")
        print(f"   raw            : {reference}")
        reference_words = len(words(reference))
        for kbps, complexity in settings:
            decoded, size = opus_round_trip(path, kbps, complexity, args.frame, args.application)
            text = model.recognize(to_float(decoded))
            wer = word_error_rate(reference, text)
            total = totals[(kbps, complexity)]
            total["errors"] += wer * reference_words
            total["words"] += reference_words
            total["bytes"] += size
            changed = "" if wer == 0 else f"  -> {text}"
            print(f"   {kbps:2d} kbps c{complexity:<2d}   : {size / 1024:5.1f} KB  WER {wer:5.1%}{changed}")

    print(f"\n== Summary ({args.application}, {args.frame} ms frames): {len(args.files)} recordings, {seconds:.0f} s, raw {raw_bytes // 1024} KB")
    print("   setting       size     smaller  WER (all words)")
    for (kbps, complexity), total in totals.items():
        wer = total["errors"] / total["words"] if total["words"] else 0.0
        print(f"   {kbps:2d} kbps c{complexity:<2d}  {total['bytes'] / 1024:6.1f} KB  "
              f"{raw_bytes / total['bytes']:5.1f}x   {wer:5.1%}")


if __name__ == "__main__":
    main()
