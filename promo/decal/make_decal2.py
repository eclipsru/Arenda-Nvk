"""Rear-window decal, set 2 (variants 6-10): tuning-brand logo styles
(TENZO R badge, AROSPEED/SLP custom letters, BBS overlapping outlines, KVR/KYB checkers + knocked-out bar,
Racing speed lines). 1150 x 90 mm, 2 colours max, no colour overlaps -> plotter ready.

Usage:  python3 promo/decal/make_decal2.py [W_mm H_mm] [variant numbers...]
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pathops
from decal import (ORANGE, WHITE, face, text_path, para, poly, rrect, union, diff, transform,
                   bounds, fit, save_pdf, save_svg, render_png)

args = [a for a in sys.argv[1:]]
if len(args) >= 2 and all(a.replace(".", "").isdigit() and float(a) > 50 for a in args[:2]):
    W, H = float(args[0]), float(args[1])
    args = args[2:]
else:
    W, H = 1150.0, 90.0
ONLY = set(args)
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "files")
os.makedirs(OUT, exist_ok=True)

MITER = pathops.LineJoin.MITER_JOIN


def inter(a, b):
    return pathops.op(a, b, pathops.PathOp.INTERSECTION)


def outset(p, d, join=MITER):
    """Grow a shape by d mm (sharp corners)."""
    q = pathops.Path(p)
    q.stroke(2 * d, pathops.LineCap.BUTT_CAP, join, 4)
    q.convertConicsToQuads()
    return union([p, q])


def ring(p, gap, width):
    """Outline ring around p: starts `gap` mm away from the shape, `width` mm thick."""
    return diff(outset(p, gap + width), outset(p, gap))


def skew(p, k, y0=0.0):
    return transform(p, 1, 0, k, 1, -k * y0, 0)


def box(x0, y0, x1, y1):
    return poly([(x0, y0), (x1, y0), (x1, y1), (x0, y1)])


# ------------------------------------------------ custom geometric letters «И», «В», «Λ» (upright, later skewed)
def L_I(x, C, T, w):
    stems = union([box(x, 0, x + T, C), box(x + w - T, 0, x + w, C)])
    dg = poly([(x, 0), (x + T * 1.35, 0), (x + w, C), (x + w - T * 1.35, C)])
    return union([stems, inter(dg, box(x, 0, x + w, C))])


def L_V(x, C, T, w, c=None):
    c = c if c is not None else T * 0.9
    w2 = w - T * 0.35
    m = C * 0.52
    outer = poly([(x, 0), (x + w - c, 0), (x + w, c), (x + w, m - c * 0.6), (x + w - c * 0.6, m),
                  (x + w2, m + c * 0.6), (x + w2, C - c), (x + w2 - c, C), (x, C)])
    cc = c * 0.45
    lo = poly([(x + T, T * 0.82), (x + w - T - cc, T * 0.82), (x + w - T, T * 0.82 + cc), (x + w - T, m - T * 0.4), (x + T, m - T * 0.4)])
    up = poly([(x + T, m + T * 0.4), (x + w2 - T, m + T * 0.4), (x + w2 - T, C - T * 0.82 - cc), (x + w2 - T - cc, C - T * 0.82), (x + T, C - T * 0.82)])
    return diff(outer, union([lo, up]))


def L_A(x, C, T, w, bar=True):
    """Λ-style A (no crossbar, like AROSPEED/ADR) + optional floating bar."""
    a = T * 0.55
    outer = poly([(x, 0), (x + w / 2 - a, C), (x + w / 2 + a, C), (x + w, 0)])
    tb = T * 1.25
    inner = poly([(x + tb, 0), (x + w / 2, C - T * 1.05), (x + w - tb, 0)])
    out = diff(outer, inner)
    if bar:
        # small detached bar inside the counter (keeps it readable as «А»)
        h0, h1 = C * 0.16, C * 0.16 + T * 0.62
        g = 2.2
        # inner edges at height y: left x_l(y), right x_r(y)
        def xl(y): return x + tb + (w / 2 - tb) * y / (C - T * 1.05)
        def xr(y): return x + w - tb - (w / 2 - tb) * y / (C - T * 1.05)
        out = union([out, poly([(xl(h0) + g, h0), (xr(h0) - g, h0), (xr(h1) - g, h1), (xl(h1) + g, h1)])])
    return out


def drill_icon(cx, cy, length, d):
    """Drill bit pointing right, centred at (cx, cy)."""
    from make_decal import drill_bit
    return drill_bit(cx - length / 2, cy, length, d, turns=4)


# ================================================= 6. «Тензо» — extended wordmark + slanted badge + subtitle
def v_tenzo():
    f = face("russo-one")
    C = 74
    K = 0.20
    o, w = text_path(f, "ИВА", 0, 0, C, 0.03, 0.0, 1.32)
    o = skew(o, 0.0)
    # badge: slanted parallelogram with a knocked-out drill
    bx, bh = w + 12, C
    bw = 96
    lean = bh * K * 2.2
    badge = para(bx, 0, bw, bh, lean)
    import math
    cxb, cyb = bx + lean / 2 + bw / 2, bh / 2
    ang = math.atan2(bh, bw + lean)          # along the badge diagonal
    diag = math.hypot(bw + lean, bh)
    bit = drill_icon(cxb, cyb, diag * 0.66, 18)
    ca, sa = math.cos(ang), math.sin(ang)
    bit = transform(bit, ca, sa, -sa, ca, cxb - ca * cxb + sa * cyb, cyb - sa * cxb - ca * cyb)
    badge = diff(badge, outset(bit, 0.01))
    # subtitle in two lines, like «RACING SPORTS»
    tx = bx + bw + lean + 14
    lh = (C - 9) / 2
    t1, w1 = text_path(f, "ИНСТРУМЕНТ", tx, lh + 9, lh, 0.06, 0.0, 1.12)
    t2, w2 = text_path(f, "В АРЕНДУ", tx, 0, lh, 0.06, 0.0, 1.12)
    # stretch the 2nd line to the width of the 1st with a speed bar after it
    bar_x0 = tx + w2 + 9
    bar = para(bar_x0, 0, tx + w1 - bar_x0, lh * 0.42, 0)
    bar2 = para(bar_x0 + 14, lh * 0.62, tx + w1 - bar_x0 - 14, lh * 0.2, 0)
    return [(ORANGE, union([o, badge])), (WHITE, union([t1, t2, bar, bar2]))]


# ================================================= 7. «Аэро» — custom letters (AROSPEED / SLP / ADR) + blade
def v_aero():
    C, T = 76, 17
    K = 0.30
    g = 9
    wI, wV, wA = 70, 64, 92
    x = 0
    I = L_I(x, C, T, wI)
    x += wI + g
    V = L_V(x, C, T, wV)
    x += wV + g * 0.4
    A = L_A(x, C, T, wA)
    word = union([I, V, A])
    # stencil cut through all letters (ADR / SLP look)
    cut = box(-50, C * 0.60, x + wA + 50, C * 0.60 + 3.2)
    word = diff(word, cut)
    word = skew(word, K)
    wend = x + wA
    # blade: from the foot of «Λ» far to the right, tapering to a point
    bx0 = wend - T * 0.9
    bx1 = wend + 760
    blade = poly([(bx0, 0), (bx1, 0), (bx1 - 40, 6.5), (bx0 + 6.5 * K, 11)])
    blade = skew(blade, K)
    # subtitle above the blade, extended, same lean
    f = face("russo-one")
    tc = 33
    t, tw = text_path(f, "ИНСТРУМЕНТ В АРЕНДУ", wend + 34, 20, tc, 0.08, K, 1.18)
    # end the blade a bit after the text
    endx = wend + 34 + tw + 30
    blade = inter(blade, box(-100, -10, endx + 200, 20))
    blade = poly([(bx0, 0), (endx + 70, 0), (endx + 20, 6.5), (bx0 + 6.5, 11)])
    blade = skew(blade, K)
    # small speed marks in front of «И»
    marks = union([skew(box(-72, C * 0.60 + 8, -14, C * 0.60 + 14), K), skew(box(-52, C * 0.60 - 6, -14, C * 0.60), K)])
    return [(ORANGE, union([word, blade, marks])), (WHITE, t)]


# ================================================= 8. «BBS» — heavy letters overlapping, cut by outlines + outer ring
def overlap_word(f, text, x, y, C, track, sk, sx, gap):
    """Each next letter lies on top of the previous one, separated by a gap (BBS style)."""
    letters = []
    pen = x
    for ch in text:
        p, adv = text_path(f, ch, pen, y, C, 0, sk, sx)
        letters.append(p)
        pen += adv + track * C
    out = []
    for i, p in enumerate(letters):
        q = p
        for j in range(i + 1, len(letters)):
            q = diff(q, outset(letters[j], gap))
        out.append(q)
    return union(out), union(letters), pen - track * C


def v_bbs():
    f = face("exo-2", 900, "italic")
    C = 64
    fill, solid, w = overlap_word(f, "ИВА", 0, 0, C, -0.20, 0.12, 1.12, 2.4)
    rim = ring(solid, 2.6, 3.6)
    # subtitle: white, heavy italic, with an orange ring too
    tx = w + 26
    t, tw = text_path(f, "ИНСТРУМЕНТ В АРЕНДУ", tx, 6, C * 0.60, 0.03, 0.12, 1.0)
    trim = ring(t, 2.0, 2.6)
    return [(WHITE, union([fill, t])), (ORANGE, union([rim, trim]))]


# ================================================= 9. «Финиш» — checkered flag + wordmark + knocked-out bar (KVR / KYB / Eibach)
def v_finish():
    f = face("exo-2", 900, "italic")
    K = 0.22
    C = 70
    # checkered flag: 3 rows x 5 columns, fading to the right edge (like KVR)
    sq = C / 3
    cells = []
    for r in range(3):
        for c in range(6):
            if (r + c) % 2 == 0:
                s = sq * (1 - 0.0 * c)
                cells.append(box(c * sq, r * sq, c * sq + s, r * sq + s))
    flag = skew(union(cells), K)
    fx1 = 6 * sq + C * K
    o, w = text_path(f, "ИВА", fx1 + 6, 0, C, 0.02, 0.0, 1.08)
    # white bar with the subtitle cut out
    bx = fx1 + 6 + w + 14
    bh = C * 0.66
    by = (C - bh) / 2
    ft = face("exo-2", 800, "italic")
    tc = bh * 0.62
    t, tw = text_path(ft, "ИНСТРУМЕНТ В АРЕНДУ", bx + 16, by + (bh - tc) / 2, tc, 0.07)
    bar = para(bx, by, tw + 34 + bh * K * 0.0, bh, bh * K)
    bar = diff(bar, t)
    # thin orange lines above/below the bar ends (accent)
    acc = union([para(bx + 24, by + bh + 5, tw - 30, 3.2, 3.2 * K), para(bx - 6, by - 8.2, tw - 30, 3.2, 3.2 * K)])
    return [(ORANGE, union([flag, o, acc])), (WHITE, bar)]


# ================================================= 10. «Скорость» — speed lines run into the letters (Racing / injen)
def v_speed():
    f = face("dela-gothic-one")
    K = 0.24
    C = 70
    o, w = text_path(f, "ИВА", 0, 0, C, 0.04, K, 1.05)
    # 3 horizontal slits cut through «ИВА», continuing to the left as orange lines
    ys = [C * 0.22, C * 0.44, C * 0.66]
    th = 3.6
    slits = union([box(-20, y, w + 40, y + th) for y in ys])
    o = diff(o, slits)
    lines = []
    for i, y in enumerate(ys):
        L = 150 - i * 35
        lines.append(box(-L - 8 + y * K - 10, y + th + 2.4, -6 + y * K, y + th + 2.4 + 5.2))
    # line from the foot of «А» runs under the subtitle (injen style), ending in a point
    ft = face("exo-2", 900, "italic")
    tx = w + 18
    tc = 36
    t, tw = text_path(ft, "ИНСТРУМЕНТ В АРЕНДУ", tx + 14, 22, tc, 0.05)
    x_end = tx + 14 + tw + 22
    tail = poly([(w - 34, 0), (x_end + 40, 0), (x_end, 9), (w - 30, 9)])
    tail = diff(tail, box(-10, 0, w - 8, 9.5))   # keep the tail out of the letters
    tail = union([tail, box(w - 8 - 0.1, 0, w + 6, 9)])
    return [(ORANGE, union([o] + lines + [tail])), (WHITE, t)]


VARIANTS = [
    ("6", "tenzo", "Шильд", v_tenzo),
    ("7", "aero", "Аэро", v_aero),
    ("8", "kontur", "Контур", v_bbs),
    ("9", "finish", "Финиш", v_finish),
    ("10", "skorost", "Скорость", v_speed),
]


def build(only=None, sheet_name="overview-2.png"):
    from PIL import Image, ImageDraw, ImageFont
    rows = []
    for num, slug, title, fn in VARIANTS:
        if only and num not in only:
            continue
        layers = fit(fn(), W, H, 0.0)
        cols = [p for _, p in layers]
        for i in range(len(cols)):
            for j in range(i + 1, len(cols)):
                a = inter(cols[i], cols[j]).area
                assert a < 0.01, f"variant {num}: colours overlap ({a:.2f} mm2)"
        b = bounds(layers)
        base = os.path.join(OUT, f"iva-steklo-{num}-{slug}")
        save_pdf(layers, W, H, base + ".pdf", f"Ива — инструмент в аренду · вариант {num} «{title}» · {W:g}×{H:g} мм")
        save_svg(layers, W, H, base + ".svg")
        im = render_png(base + ".pdf", base + "-preview.png", 2300)
        rows.append((num, title, im))
        print(num, slug, f"bbox {b[2]-b[0]:.1f} x {b[3]-b[1]:.1f} mm")
    pw = 1400
    hdr = 46
    rs = [(n, t, im.resize((pw, int(im.height * pw / im.width)), Image.LANCZOS)) for n, t, im in rows]
    sheet = Image.new("RGB", (pw + 60, sum(r[2].height + hdr for r in rs) + 30), (36, 38, 44))
    d = ImageDraw.Draw(sheet)
    try:
        fnt = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 26)
    except OSError:
        fnt = ImageFont.load_default()
    y = 20
    for n, t, im in rs:
        d.text((30, y + 6), f"Вариант {n} — «{t}»", fill=ORANGE, font=fnt)
        sheet.paste(im, (30, y + hdr - 6))
        y += im.height + hdr
    sheet.save(os.path.join(OUT, sheet_name))


if __name__ == "__main__":
    build(ONLY or None)
