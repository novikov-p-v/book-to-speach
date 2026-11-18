"""usage: sample_audio.py <espeak-ng-data> <voice dir> <text file> <out.wav> : synthesize with sherpa-onnx (one data dir per process)"""
import glob, sys
import numpy as np, sherpa_onnx as so, soundfile as sf
data, voice, text, out = sys.argv[1:5]
t = so.OfflineTts(so.OfflineTtsConfig(model=so.OfflineTtsModelConfig(num_threads=4, vits=so.OfflineTtsVitsModelConfig(
    model=glob.glob(f"{voice}/*.onnx")[0], tokens=f"{voice}/tokens.txt", data_dir=data)), max_num_sentences=1))
a = t.generate(open(text, encoding="utf-8").read(), sid=0, speed=1.0)
sf.write(out, np.array(a.samples), a.sample_rate)
