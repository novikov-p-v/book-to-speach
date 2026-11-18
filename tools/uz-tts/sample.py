"""Synthesize a text file with a sherpa-onnx VITS model, as the app does (one sentence per call).

usage: sample.py <model dir> <text file> <out.wav>
"""
import os
import re
import sys

import numpy as np
import sherpa_onnx
import soundfile as sf

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from translit import is_latin_uzbek, to_cyrillic  # noqa: E402

model_dir, text_file, out = sys.argv[1:4]
tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
    vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=f"{model_dir}/model.onnx", tokens=f"{model_dir}/tokens.txt"),
    num_threads=4)))
sr = tts.sample_rate
pause = np.zeros(int(sr * 0.35), dtype=np.float32)
chunks = []
for line in open(text_file, encoding="utf-8"):
    line = line.strip()
    if not line:
        continue
    for sent in re.split(r"(?<=[.!?…])\s+", line):
        cyr = to_cyrillic(sent) if is_latin_uzbek(sent) else sent
        # the model's alphabet is lowercase letters, space and dashes; sherpa maps upper case itself
        print(cyr)
        audio = tts.generate(cyr, sid=0, speed=1.0)
        chunks += [np.asarray(audio.samples, dtype=np.float32), pause]
sf.write(out, np.concatenate(chunks), sr)
print(f"{out}: {sum(len(c) for c in chunks) / sr:.1f} s at {sr} Hz")
