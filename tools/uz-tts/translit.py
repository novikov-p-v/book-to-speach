"""Uzbek Latin -> Cyrillic (reference implementation for the app's Kotlin version)."""
import re

APOS = "ʻʼ'‘’`´"  # oʻ/gʻ marks and the tutuq belgisi (ʼ) are written with many look-alike characters

DIGRAPHS = [
    ("o" + a, "ў") for a in APOS
] + [
    ("g" + a, "ғ") for a in APOS
] + [
    ("s" + a + "h", "сҳ") for a in APOS  # Is'hoq: s + h, not sh
] + [
    ("sh", "ш"), ("ch", "ч"), ("yo", "ё"), ("yu", "ю"), ("ya", "я"), ("ye", "е"),
]
SINGLE = {
    "a": "а", "b": "б", "c": "с", "d": "д", "f": "ф", "g": "г", "h": "ҳ", "i": "и", "j": "ж", "k": "к",
    "l": "л", "m": "м", "n": "н", "o": "о", "p": "п", "q": "қ", "r": "р", "s": "с", "t": "т", "u": "у",
    "v": "в", "w": "в", "x": "х", "y": "й", "z": "з",
}
VOWELS = set("аеёиоуўэюя")
LATIN = re.compile(r"[A-Za-z]")


def to_cyrillic(text: str) -> str:
    out = []
    t = text
    i = 0
    while i < len(t):
        c = t[i]
        lc = c.lower()
        if not ("a" <= lc <= "z"):
            out.append("ъ" if c in APOS and out and out[-1].isalpha() else c)
            i += 1
            continue
        for lat, cyr in DIGRAPHS:
            if t[i:i + len(lat)].lower() == lat:
                break
        else:
            lat, cyr = lc, SINGLE.get(lc, "")
            if lc == "e":
                prev = out[-1].lower() if out else " "
                cyr = "э" if not prev.isalpha() or prev in VOWELS else "е"
        out.append(cyr.upper() if c.isupper() and len(cyr) == 1 else (cyr[0].upper() + cyr[1:] if c.isupper() else cyr))
        i += len(lat)
    return "".join(out)


def is_latin_uzbek(text: str) -> bool:
    return bool(LATIN.search(text))
