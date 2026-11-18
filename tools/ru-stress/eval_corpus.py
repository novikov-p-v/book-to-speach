"""How much does the patch affect real prose?  usage: eval_corpus.py <wrong.tsv> <corpus dir>"""
import collections, glob, re, sys

wrong = {}
for line in open(sys.argv[1], encoding="utf-8"):
    w, n, _ = line.rstrip("\n").split("\t")
    wrong[w] = int(n)
text = ""
for f in glob.glob(sys.argv[2] + "/*.wiki"):
    t = open(f, encoding="utf-8").read()
    t = re.sub(r"<noinclude>.*?</noinclude>|\{\{[^{}]*\}\}|<[^>]+>|\[\[[^\]|]*\||\]\]|\[\[", " ", t, flags=re.S)
    text += t + "\n"
tokens = [w.lower() for w in re.findall(r"[А-Яа-яЁё]+", text)]
hits = collections.Counter(w for w in tokens if w in wrong)
V = "аеёиоуыэюя"
def show(w):  # mark the corrected stressed vowel with uppercase
    n, k = wrong[w], 0
    for i, c in enumerate(w):
        if c in V:
            k += 1
            if k == n:
                return w[:i] + c.upper() + w[i + 1:]
    return w
print(f"{len(tokens)} word tokens, {sum(hits.values())} corrected ({100 * sum(hits.values()) / len(tokens):.2f}%), "
      f"{len(hits)} distinct words; ~1 correction every {len(tokens) // max(1, sum(hits.values()))} words")
print(", ".join(f"{show(w)}×{c}" for w, c in hits.most_common(60)))
