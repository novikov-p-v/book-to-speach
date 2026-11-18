"""usage: ph_file.py <espeak-ng-data> <words.txt> <out.tsv> : phonemize each word (one data dir per process)"""
import sys
from piper.phonemize_espeak import EspeakPhonemizer
p = EspeakPhonemizer(sys.argv[1])
with open(sys.argv[3], "w", encoding="utf-8") as out:
    for w in open(sys.argv[2], encoding="utf-8").read().split("\n"):
        out.write(w + "\t" + "".join(sum(p.phonemize("ru", w), [])) + "\n")
