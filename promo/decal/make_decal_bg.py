"""Decal variant on a solid black background plate (for printed film / banner).

Usage:  python3 promo/decal/make_decal_bg.py [variant=15] [margin_x_mm=20] [margin_y_mm=5]
Output: promo/decal/files/iva-steklo-<N>-<slug>-chyorny-fon.pdf / .svg / -300dpi.png / -preview.png
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
args = sys.argv[1:]
num = args[0] if args else "15"
mx = float(args[1]) if len(args) > 1 else 20.0
my = float(args[2]) if len(args) > 2 else 5.0
sys.argv = sys.argv[:1]
import make_decal2 as m2
import make_decal3  # noqa: F401  (registers variants 11-15 in m2.VARIANTS)
from decal import bounds, transform, save_pdf, save_svg, rrect

BLACK = (0, 0, 0)
W, H = 1150.0, 90.0
ALL = {}
import importlib
for mod in ("make_decal3",):
    for v in importlib.import_module(mod).m2.VARIANTS:
        ALL[v[0]] = v
v = ALL[num]
_, slug, title, fn = v

layers = fn()
x0, y0, x1, y1 = bounds(layers)
k = min((W - 2 * mx) / (x1 - x0), (H - 2 * my) / (y1 - y0))
dx = (W - (x1 - x0) * k) / 2 - x0 * k
dy = (H - (y1 - y0) * k) / 2 - y0 * k
layers = [(c, transform(p, k, 0, 0, k, dx, dy)) for c, p in layers]
# colours of the design must not overlap each other (background is underneath everything)
for i in range(len(layers)):
    for j in range(i + 1, len(layers)):
        a = m2.inter(layers[i][1], layers[j][1]).area
        assert a < 0.01, f"colours overlap {a:.2f} mm2"
bg = rrect(0, 0, W, H, 0)
full = [(BLACK, bg)] + layers
b = bounds(layers)
base = os.path.join(m2.OUT, f"iva-steklo-{num}-{slug}-chyorny-fon")
save_pdf(full, W, H, base + ".pdf", f"Ива — инструмент в аренду · вариант {num} «{title}» на чёрном фоне · {W:g}×{H:g} мм")
save_svg(full, W, H, base + ".svg")

import pymupdf
from PIL import Image
doc = pymupdf.open(base + ".pdf")
pg = doc[0]
for dpi, name in ((300, "-300dpi.png"), (None, "-preview.png")):
    zoom = (dpi / 72) if dpi else 2300 / pg.rect.width
    pix = pg.get_pixmap(matrix=pymupdf.Matrix(zoom, zoom), alpha=False)
    im = Image.frombytes("RGB", (pix.width, pix.height), pix.samples)
    im.save(base + name, dpi=(dpi, dpi) if dpi else (72, 72))
    print(name, im.size)
print(f"design {b[2]-b[0]:.1f} x {b[3]-b[1]:.1f} mm, scale {k:.3f}, margins x {b[0]:.1f}/{W-b[2]:.1f} y {b[1]:.1f}/{H-b[3]:.1f}")
