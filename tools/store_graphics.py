"""Generates Google Play store graphics into docs/store/: icon-512.png and feature-1024x500.png.

The artwork mirrors the launcher icon (app/src/main/res/drawable/ic_launcher.xml): an open book with
sound waves. Drawn at 4x and downsampled for smooth edges.   usage: python3 tools/store_graphics.py
"""
import os
from PIL import Image, ImageDraw, ImageFont

OUT = os.path.join(os.path.dirname(__file__), "..", "docs", "store")
FONT = "/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf"
FONT_REG = "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf"
INDIGO, INDIGO_DARK, WHITE, AMBER = (63, 81, 181), (48, 63, 159), (255, 255, 255), (255, 193, 7)


def draw_mark(d: ImageDraw.ImageDraw, x: float, y: float, s: float):
    """Book + waves in a 108x108 design grid at (x, y), scale s per unit."""
    P = lambda pts: [(x + px * s, y + py * s) for px, py in pts]
    # left and right pages (curved tops approximated by polygons)
    left = [(24, 38), (31, 35.5), (41, 35), (52, 40), (52, 74), (42, 69.5), (31, 69.5), (24, 72)]
    right = [(56, 40), (66, 34.5), (77, 34.5), (84, 38), (84, 72), (77, 69.5), (66, 69.5), (56, 74)]
    d.polygon(P(left), fill=WHITE)
    d.polygon(P(right), fill=WHITE)
    # sound waves
    w = max(1, int(3.2 * s))
    for r in (7, 14):
        cx, cy = 86, 36
        d.arc([x + (cx - r) * s, y + (cy - r) * s, x + (cx + r) * s, y + (cy + r) * s], start=-60, end=60, fill=AMBER, width=w)


def icon(size=512):
    k = 4
    img = Image.new("RGB", (size * k, size * k), INDIGO)
    d = ImageDraw.Draw(img)
    # artwork bounds in the 108 grid: x 24..100, y 22..74 -> centre (62, 48); keep inside Play's safe zone
    f = 0.82
    u = size * k / 108
    draw_mark(d, (54 - 62 * f) * u, (54 - 48 * f) * u, u * f)
    return img.resize((size, size), Image.LANCZOS)


def feature(w=1024, h=500):
    k = 3
    img = Image.new("RGB", (w * k, h * k), INDIGO)
    d = ImageDraw.Draw(img)
    # soft diagonal band for depth
    d.polygon([(w * k * 0.55, 0), (w * k, 0), (w * k, h * k), (w * k * 0.38, h * k)], fill=INDIGO_DARK)
    s = h * k / 108 * 0.9
    draw_mark(d, 20 * k, 10 * k, s)
    tx = 450 * k
    size = 76
    while ImageFont.truetype(FONT, size * k).getlength("Book to Speech") > (w - 450 - 40) * k:
        size -= 2
    title = ImageFont.truetype(FONT, size * k)
    sub = ImageFont.truetype(FONT_REG, 34 * k)
    d.text((tx, 150 * k), "Book to Speech", font=title, fill=WHITE)
    d.text((tx, 260 * k), "Your books, read aloud.", font=sub, fill=WHITE)
    d.text((tx, 310 * k), "Natural voices, fully offline.", font=sub, fill=AMBER)
    return img.resize((w, h), Image.LANCZOS)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    icon().save(os.path.join(OUT, "icon-512.png"))
    feature().save(os.path.join(OUT, "feature-1024x500.png"))
    print("written to", os.path.abspath(OUT))
