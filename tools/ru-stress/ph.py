"""usage: ph.py <espeak-ng-data> word... -> phonemes per word (one espeak data dir per process!)"""
import sys
from piper.phonemize_espeak import EspeakPhonemizer
p = EspeakPhonemizer(sys.argv[1])
for w in sys.argv[2:]:
    print(w, "".join(sum(p.phonemize("ru", w), [])))
