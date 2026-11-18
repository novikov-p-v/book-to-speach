"""Find words whose espeak stress differs from RUAccent's. Resumable.

usage: find_wrong.py <espeak-ng-data> <ruaccent dir> <out.tsv>
out.tsv lines: word <TAB> correct vowel number (1-based) <TAB> espeak's vowel number (0-based index or None)
"""
import gzip, json, os, sys, time
from piper.phonemize_espeak import EspeakPhonemizer

data, ra, out = sys.argv[1:4]
p = EspeakPhonemizer(data)
acc = json.load(gzip.open(f"{ra}/accents.json.gz", "rt", encoding="utf-8"))
omo = json.load(gzip.open(f"{ra}/omographs.json.gz", "rt", encoding="utf-8"))
yo = json.load(gzip.open(f"{ra}/yo_words.json.gz", "rt", encoding="utf-8"))
V = set("аеёиоуыэюя")
PV = set("aeiouyɪʌəɐɨɵæɑɛɔʊɯøœɤ")  # vowel symbols in espeak's Russian IPA


def dict_idx(v):
    i = v.index("+")
    return sum(c in V for c in v[:i])


def esp_idx(ph):
    return sum(c in PV for c in ph[: ph.index("ˈ")]) if "ˈ" in ph else None


# homographs are left to espeak; ё-words are fixed by ё restoration; hyphenated words are skipped
words = sorted(
    k for k, v in acc.items()
    if "+" in v and k not in omo and k not in yo and "-" not in k
    and sum(c in V for c in k) >= 2 and all("а" <= c <= "я" for c in k)
)
done = 0
if os.path.exists(out + ".progress"):
    done = int(open(out + ".progress").read())
print("candidates", len(words), "resuming at", done, flush=True)
t0 = time.time()
B = 400
with open(out, "a") as f:
    for s in range(done, len(words), B):
        batch = words[s : s + B]
        ph = p.phonemize("ru", ". ".join(batch) + ".")
        if len(ph) == len(batch):
            pairs = zip(batch, ("".join(x) for x in ph))
        else:  # sentence split didn't align: phonemize one by one
            pairs = ((w, "".join(sum(p.phonemize("ru", w), []))) for w in batch)
        for w, x in pairs:
            e = esp_idx(x)
            if e != dict_idx(acc[w]):
                f.write(f"{w}\t{dict_idx(acc[w]) + 1}\t{e}\n")
        f.flush()
        with open(out + ".progress", "w") as pf:
            pf.write(str(s + B))
        if s % 200_000 == 0:
            print(f"{s}/{len(words)} {time.time() - t0:.0f}s", flush=True)
print("done", flush=True)
