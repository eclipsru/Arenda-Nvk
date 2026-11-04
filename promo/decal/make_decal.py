"""Rear-window decal «Ива — инструмент в аренду», 1150 x 90 mm, several styles.

Usage:  python3 promo/decal/make_decal.py [W_mm H_mm]
Output: promo/decal/files/iva-steklo-<N>-<name>.pdf / .svg / -preview.png + overview.png
Every variant is at most 2 colours (orange + white film), overlaps merged -> ready for plotter cutting.
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from decal import (ORANGE, WHITE, GLASS, face, text_path, measure, para, poly, rrect, union, diff,
                   stroke, fit, save_pdf, save_svg, render_png)

W, H = (float(sys.argv[1]), float(sys.argv[2])) if len(sys.argv) > 2 else (1150.0, 90.0)
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "files")
os.makedirs(OUT, exist_ok=True)

LEAN = 0.22  # common forward lean (tan) for the sporty variants


def drill_bit(x, y, length, d, lean=0.0, turns=None):
    """Horizontal drill bit pointing right: shank + fluted body + point. (x, y) = left end, centre line."""
    r = d / 2
    shank = length * 0.22
    tip = d * 1.1
    body = length - shank - tip
    parts = [rrect(x, y - r * 0.78, shank + 1, r * 1.56, r * 0.3)]
    # fluted body: slanted segments separated by gaps (gives the spiral look, all parts >= 1.5 mm wide)
    n = turns or max(4, int(body / (d * 0.9)))
    step = body / n
    gap = max(step * 0.16, 1.6)
    sl = d * 0.55
    for i in range(n):
        x0 = x + shank + i * step
        parts.append(poly([(x0, y - r), (x0 + step - gap, y - r), (x0 + step - gap + sl, y + r), (x0 + sl, y + r)]))
    # clip flutes to the body outline
    core = rrect(x + shank - 1, y - r * 0.2, body + sl * 0.5, r * 0.4, 0)   # thin core joins the flutes
    flutes = union(parts[1:] + [core])
    body_box = rrect(x + shank, y - r, body + sl, 2 * r, 0)
    from decal import pathops
    flutes = pathops.op(flutes, body_box, pathops.PathOp.INTERSECTION)
    xb = x + shank + body - gap - 0.5   # point overlaps the last flute -> one solid piece
    point = poly([(xb, y - r), (xb + sl + gap, y - r), (x + length, y), (xb + sl + gap, y + r), (xb + sl, y + r)])
    out = union([parts[0], flutes, point])
    if lean:
        from decal import transform
        out = transform(out, 1, 0, lean, 1, -lean * y, 0)
    return out


# ---------------------------------------------------------------- 1. «Гонка»
def v_race():
    f = face("exo-2", 900, "italic")
    cap = 60
    o, w = text_path(f, "ИВА", 0, 0, cap, 0.02)
    gap = 18
    t, tw = text_path(f, "ИНСТРУМЕНТ В АРЕНДУ", w + gap, 0, cap * 0.62, 0.035)
    # speed stripes in front of «ИВА»
    sh = 7.0
    stripes = []
    for i, ln in enumerate((150, 115, 80)):
        yy = cap - 8 - i * (sh + 6.5)
        stripes.append(para(-14 - ln - i * 10, yy, ln, sh, sh * LEAN))
    # long underline under the white words, ends in an angled cut
    ux0 = w + gap - 4
    ux1 = w + gap + tw + 6
    uline = poly([(ux0, -16), (ux1, -16), (ux1 + 4.5, -10), (ux0 + 1.3, -10)])
    return [(ORANGE, union([o] + stripes + [uline])), (WHITE, t)]


# ---------------------------------------------------------------- 2. «Шильдик»
def v_badge():
    f = face("russo-one")
    cap = 46
    sk = LEAN
    word, ww = text_path(f, "ИВА", 0, 0, cap, 0.06, sk)
    padx, pady = 18, 13
    bx0, by0 = -padx, -pady
    bw, bh = ww + 2 * padx, cap + 2 * pady
    lean = bh * sk
    cut = 10
    badge = poly([(bx0, by0), (bx0 + bw - cut * 0.4, by0), (bx0 + bw + lean, by0 + bh - cut),
                  (bx0 + bw + lean - cut * 0.6, by0 + bh), (bx0 + lean + cut * 0.4, by0 + bh), (bx0 + lean * 0.0 - 0.0, by0 + cut)])
    badge = poly([(bx0, by0), (bx0 + bw, by0), (bx0 + bw + lean, by0 + bh), (bx0 + lean, by0 + bh)])
    plate = diff(badge, word)          # letters cut out of the orange plate
    x = bx0 + bw + lean + 16
    t, tw = text_path(f, "ИНСТРУМЕНТ В АРЕНДУ", x, (cap - cap * 0.78) / 2, cap * 0.78, 0.05, sk)
    # two thin trailing lines after the text
    tx1 = x + tw + 14
    tail = [para(tx1, 16, 70, 5, 5 * sk), para(tx1 + 6, 4, 46, 5, 5 * sk)]
    return [(ORANGE, union([plate] + tail)), (WHITE, t)]


# ---------------------------------------------------------------- 3. «Росчерк»
def v_script():
    fs = face("lobster")
    cap = 70
    s, sw = text_path(fs, "Ива", 0, 0, cap, 0.0, 0.0)
    fe = face("exo-2", 800, "italic")
    tcap = 35
    tx = sw + 16
    t, tw = text_path(fe, "ИНСТРУМЕНТ В АРЕНДУ", tx, 10, tcap, 0.19)
    # swoosh: tapered curved stroke from under «Ива» to the end of the text
    x0, x1 = sw * 0.55, tx + tw + 10
    from decal import pathops
    p = pathops.Path()
    p.moveTo(x0, -2)
    p.cubicTo(x0 + (x1 - x0) * 0.35, -16, x0 + (x1 - x0) * 0.75, -6, x1, 2.5)       # lower edge
    p.cubicTo(x0 + (x1 - x0) * 0.75, -1.5, x0 + (x1 - x0) * 0.35, -9.5, x0 - 6, 1.5)  # upper edge (thicker in the middle)
    p.close()
    sw_path = union([p])
    return [(ORANGE, union([s, sw_path])), (WHITE, t)]


# ---------------------------------------------------------------- 4. «Сверло»
def v_drill():
    f = face("sofia-sans-extra-condensed", 900, "italic")
    cap = 66
    o, w = text_path(f, "ИВА", 0, 0, cap, 0.04)
    bit_len, d = 200, 24
    bx = w + 18
    bit = drill_bit(bx, cap / 2, bit_len, d, lean=0.0, turns=5)
    t, tw = text_path(f, "ИНСТРУМЕНТ В АРЕНДУ", bx + bit_len + 16, 0, cap, 0.035)
    return [(ORANGE, union([o, bit])), (WHITE, t)]


# ---------------------------------------------------------------- 5. «Трек»
def v_wide():
    f = face("unbounded", 900)
    f2 = face("unbounded", 600)
    cap = 50
    o, w = text_path(f, "ИВА", 0, 0, cap, 0.02, LEAN * 0.8)
    x = w + 30
    t, tw = text_path(f2, "ИНСТРУМЕНТ В АРЕНДУ", x, 0, cap * 0.5, 0.06, LEAN * 0.8)
    # «racing stripes» above and below the white words: thick + thin
    x1 = x + tw + cap * 0.5 * LEAN * 0.8
    top = [para(x - 6, cap * 0.5 + 9, x1 - x + 18, 8, 8 * LEAN), para(x + 4, cap * 0.5 + 22, x1 - x + 8, 3.2, 3.2 * LEAN)]
    bot = [para(x - 14, -16, x1 - x + 18, 8, 8 * LEAN)]
    # chevrons before «ИВА»
    ch = []
    for i in range(3):
        cx = -22 - i * 22
        th = 7.5
        ch.append(poly([(cx - 14, 0), (cx - 14 + th, 0), (cx + th, cap / 2), (cx - 14 + th, cap), (cx - 14, cap), (cx, cap / 2)]))
    return [(ORANGE, union([o] + top + bot + ch)), (WHITE, t)]


VARIANTS = [
    ("1", "gonka", "Гонка", v_race),
    ("2", "shildik", "Шильдик", v_badge),
    ("3", "roscherk", "Росчерк", v_script),
    ("4", "sverlo", "Сверло", v_drill),
    ("5", "trek", "Трек", v_wide),
]


def main(only=None):
    from PIL import Image, ImageDraw, ImageFont
    previews = []
    for num, slug, title, fn in VARIANTS:
        if only and num not in only:
            continue
        layers = fit(fn(), W, H, 0.0)
        from decal import pathops
        cols = [p for _, p in layers]
        for i in range(len(cols)):
            for j in range(i + 1, len(cols)):
                inter = pathops.op(cols[i], cols[j], pathops.PathOp.INTERSECTION)
                assert inter.area < 0.01, f"variant {num}: colours overlap ({inter.area:.2f} mm2)"
        from decal import bounds
        b = bounds(layers)
        print(f"  bbox {b[2]-b[0]:.1f} x {b[3]-b[1]:.1f} mm")
        base = os.path.join(OUT, f"iva-steklo-{num}-{slug}")
        save_pdf(layers, W, H, base + ".pdf", f"Ива — инструмент в аренду · вариант {num} «{title}» · {W:g}×{H:g} мм")
        save_svg(layers, W, H, base + ".svg")
        im = render_png(base + ".pdf", base + "-preview.png", 2300)
        previews.append((num, title, im))
        print(num, slug, "ok")
    # overview sheet
    pw = 1400
    rows = []
    for num, title, im in previews:
        k = pw / im.width
        rows.append((num, title, im.resize((pw, int(im.height * k)), Image.LANCZOS)))
    hdr = 46
    sheet = Image.new("RGB", (pw + 60, sum(r[2].height + hdr for r in rows) + 30), (36, 38, 44))
    d = ImageDraw.Draw(sheet)
    try:
        fnt = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 26)
    except OSError:
        fnt = ImageFont.load_default()
    y = 20
    for num, title, im in rows:
        d.text((30, y + 6), f"Вариант {num} — «{title}»", fill=ORANGE, font=fnt)
        sheet.paste(im, (30, y + hdr - 6))
        y += im.height + hdr
    sheet.save(os.path.join(OUT, "overview.png"))


if __name__ == "__main__":
    main()
