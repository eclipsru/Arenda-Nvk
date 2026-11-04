"""Rear-window decal, set 3 (variants 11-15): developments of the owner's favourite №8 «Контур» (BBS style).
1150 x 90 mm, 2 colours max, no colour overlaps -> plotter ready.

Usage:  python3 promo/decal/make_decal3.py [variant numbers...]
"""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
_argv, sys.argv = sys.argv, sys.argv[:1]          # make_decal2 parses argv on import
import make_decal2 as m2
sys.argv = _argv
import pathops
from decal import ORANGE, WHITE, face, text_path, para, poly, union, diff, transform, rrect

ROUND = pathops.LineJoin.ROUND_JOIN
outset, ring, inter, box = m2.outset, m2.ring, m2.inter, m2.box

F = lambda: face("exo-2", 900, "italic")
C = 64          # cap height of «ИВА» (before fitting)
SK = 0.12       # extra lean on top of the italic


def chain(text, x, y, cap, track, gap, sk=SK, sx=1.12):
    """BBS-style chain: every next letter lies on the previous one, separated by `gap`."""
    return m2.overlap_word(F(), text, x, y, cap, track, sk, sx, gap)


def move(p, dx, dy):
    return transform(p, 1, 0, 0, 1, dx, dy)


def extrude(p, depth, ang_deg=-35, steps=None):
    """Solid 3D extrusion of p towards angle (deg, y up)."""
    steps = steps or max(6, int(depth / 0.6))
    a = math.radians(ang_deg)
    dx, dy = math.cos(a) * depth / steps, math.sin(a) * depth / steps
    return union([move(p, dx * i, dy * i) for i in range(1, steps + 1)])


def compose(track_word=-0.20, track_sub=0.035, sub_cap=0.60, gap=2.4, sub_gap=2.0, x_gap=26, sub_dy=6):
    fill, solid, w = chain("ИВА", 0, 0, C, track_word, gap)
    tx = w + x_gap
    sfill, ssolid, sw = chain("ИНСТРУМЕНТ В АРЕНДУ", tx, sub_dy, C * sub_cap, track_sub, sub_gap, SK, 1.10)
    return fill, solid, w, sfill, ssolid, tx + sw


# ================================================= 11. «Объём» — white letters on an orange 3D block
def v_volume():
    fill, solid, w, sfill, ssolid, end = compose(x_gap=30)
    g = 2.2
    ex = diff(extrude(solid, 8.5), outset(solid, g, ROUND))
    sex = diff(extrude(ssolid, 5.5), outset(ssolid, 1.8, ROUND))
    orange = union([ex, sex])
    return [(WHITE, union([fill, sfill])), (ORANGE, orange)]


# ================================================= 12. «Двойной контур» — white / orange ring / thin white ring
def v_double():
    fill, solid, w, sfill, ssolid, end = compose(x_gap=34)
    o1 = ring(solid, 2.4, 4.0)
    o2 = ring(solid, 2.4 + 4.0 + 2.0, 2.4)
    s1 = ring(ssolid, 1.9, 2.8)
    return [(WHITE, union([fill, sfill, o2])), (ORANGE, union([o1, s1]))]


# ================================================= 13. «Инверсия» — orange letters, white outline
def v_inverse():
    fill, solid, w, sfill, ssolid, end = compose()
    rim = ring(solid, 2.6, 3.6)
    srim = ring(ssolid, 2.0, 2.6)
    return [(ORANGE, union([fill, sfill])), (WHITE, union([rim, srim]))]


# ================================================= 14. «Монолит» — the whole phrase in one common sticker contour
def v_mono():
    fill, solid, w, sfill, ssolid, end = compose(x_gap=18, sub_dy=4)
    allp = union([solid, ssolid])
    # common silhouette: grow strongly with round joins, then shrink back a bit -> smooth single shape
    sil = outset(allp, 9.0, ROUND)
    sil = diff(sil, ring(sil, -0.01, 0.01)) if False else sil
    rim = diff(sil, outset(allp, 3.0, ROUND))
    # inner: «ИВА» outline ring as well (separates big letters from the orange field)
    return [(WHITE, union([fill, sfill])), (ORANGE, rim)]


# ================================================= 15. «Контур-скорость» — №8 + speed lines merging into the outline + underline blade
def v_speed():
    fill, solid, w, sfill, ssolid, end = compose(x_gap=28)
    rim = ring(solid, 2.6, 3.6)
    srim = ring(ssolid, 2.0, 2.6)
    # speed lines from the left, ending in the outline of «И»
    lines = []
    ys = [C * 0.70, C * 0.48, C * 0.26]
    lean = (solid.bounds[2] - solid.bounds[0] - w) / C + 0.0   # not used for geometry, kept for clarity
    for i, y in enumerate(ys):
        L = 150 - i * 34
        x_in = 10 + y * 0.33                  # inside the left stem of «И» at that height
        lines.append(para(x_in - L, y, L, 4.4, 0))
    lines = diff(union(lines), outset(solid, 2.6, ROUND))   # cut at the outline -> lines flow into the rim
    # underline blade exactly under the subtitle, outlined like the letters
    sx1 = ssolid.bounds[2]
    bx0, bx1 = w + 4, sx1 + 6
    blade = poly([(bx0, -10), (bx1 + 26, -10), (bx1, -4.2), (bx0 + 1, -4.2)])
    blade = diff(blade, outset(union([solid, ssolid]), 2.0 + 2.6 + 1.8, ROUND))
    orange = union([rim, srim, lines, blade])
    return [(WHITE, union([fill, sfill])), (ORANGE, orange)]


m2.VARIANTS = [
    ("11", "obyom", "Объём", v_volume),
    ("12", "dvoynoy-kontur", "Двойной контур", v_double),
    ("13", "inversiya", "Инверсия", v_inverse),
    ("14", "monolit", "Монолит", v_mono),
    ("15", "kontur-skorost", "Контур-скорость", v_speed),
]

if __name__ == "__main__":
    only = set(a for a in sys.argv[1:])
    m2.build(only or None, "overview-3.png")
