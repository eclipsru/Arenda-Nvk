"""Vector lettering engine for the rear-window decal (vinyl plotter friendly).

Text -> glyph outlines (fontTools + HarfBuzz kerning) -> pathops (overlaps merged per colour)
-> PDF (reportlab, exact size in mm) + SVG + PNG preview.
Coordinates: millimetres, y axis up.
"""
import io
import math
import os

import pathops
import uharfbuzz as hb
from fontTools.pens.basePen import BasePen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

HERE = os.path.dirname(os.path.abspath(__file__))
FONT_DIR = os.environ.get("DECAL_FONTS", os.path.join(HERE, "fonts"))

ORANGE = (0xFF, 0x8F, 0x1F)   # site --or
WHITE = (0xF2, 0xF4, 0xF8)    # site --tx
GLASS = (0x14, 0x16, 0x1B)    # preview background only (tinted glass)

_cache = {}


class Face:
    """One font = cyrillic subset + latin subset (Fontsource splits them)."""

    def __init__(self, family, weight=400, style="normal"):
        self.subs = []
        for sub in ("cyrillic", "latin"):
            p = os.path.join(FONT_DIR, f"{family}-{sub}-{weight}-{style}.woff")
            if not os.path.exists(p):
                continue
            tt = TTFont(p)
            buf = io.BytesIO()
            tt.flavor = None
            tt.save(buf)
            data = buf.getvalue()
            tt = TTFont(io.BytesIO(data))
            hbf = hb.Font(hb.Face(hb.Blob(data)))
            self.subs.append((tt, hbf, set(tt.getBestCmap().keys())))
        if not self.subs:
            raise FileNotFoundError(f"{family} {weight} {style}")
        tt = self.subs[0][0]
        self.upem = tt["head"].unitsPerEm
        os2 = tt["OS/2"]
        self.cap = getattr(os2, "sCapHeight", 0) or int(self.upem * 0.7)
        self.xh = getattr(os2, "sxHeight", 0) or int(self.upem * 0.5)

    def _sub_for(self, ch):
        for s in self.subs:
            if ord(ch) in s[2]:
                return s
        return self.subs[0]

    def runs(self, text):
        out, cur, buf = [], None, ""
        for ch in text:
            s = self._sub_for(ch)
            if s is not cur and buf:
                out.append((cur, buf))
                buf = ""
            cur = s
            buf += ch
        if buf:
            out.append((cur, buf))
        return out


def face(family, weight=400, style="normal"):
    k = (family, weight, style)
    if k not in _cache:
        _cache[k] = Face(*k)
    return _cache[k]


def text_path(fc, text, x, y, cap_mm, tracking=0.0, skew=0.0, sx=1.0):
    """Outline of `text` with cap height `cap_mm`, baseline at (x, y).
    tracking in em fractions, skew = tan(angle) (positive leans right), sx = horizontal stretch.
    Returns (pathops.Path, advance_mm)."""
    s = cap_mm / fc.cap
    path = pathops.Path()
    pen_x = 0.0
    for (tt, hbf, _), chunk in fc.runs(text):
        buf = hb.Buffer()
        buf.add_str(chunk)
        buf.guess_segment_properties()
        hb.shape(hbf, buf, {"kern": True, "liga": True})
        gs = tt.getGlyphSet()
        pen = path.getPen(glyphSet=gs)
        order = tt.getGlyphOrder()
        for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
            name = order[info.codepoint]
            gx = pen_x + pos.x_offset
            t = (s * sx, 0, s * skew, s, x + gx * s * sx, y + pos.y_offset * s)
            gs[name].draw(TransformPen(pen, t))
            pen_x += pos.x_advance + tracking * fc.upem
    pen_x -= tracking * fc.upem
    path = union([path])
    return path, pen_x * s * sx


def measure(fc, text, cap_mm, tracking=0.0, sx=1.0):
    return text_path(fc, text, 0, 0, cap_mm, tracking, 0, sx)[1]


def poly(points):
    p = pathops.Path()
    p.moveTo(*points[0])
    for pt in points[1:]:
        p.lineTo(*pt)
    p.close()
    return p


def rrect(x, y, w, h, r):
    """Rounded rectangle (r clipped)."""
    r = min(r, w / 2, h / 2)
    k = 0.5523 * r
    p = pathops.Path()
    p.moveTo(x + r, y)
    p.lineTo(x + w - r, y)
    p.cubicTo(x + w - r + k, y, x + w, y + r - k, x + w, y + r)
    p.lineTo(x + w, y + h - r)
    p.cubicTo(x + w, y + h - r + k, x + w - r + k, y + h, x + w - r, y + h)
    p.lineTo(x + r, y + h)
    p.cubicTo(x + r - k, y + h, x, y + h - r + k, x, y + h - r)
    p.lineTo(x, y + r)
    p.cubicTo(x, y + r - k, x + r - k, y, x + r, y)
    p.close()
    return p


def para(x, y, w, h, lean):
    """Parallelogram, bottom-left at (x, y), top shifted right by `lean` mm."""
    return poly([(x, y), (x + w, y), (x + w + lean, y + h), (x + lean, y + h)])


def union(paths):
    out = pathops.Path()
    for p in paths:
        out = pathops.op(out, p, pathops.PathOp.UNION)
    return out


def diff(a, b):
    return pathops.op(a, b, pathops.PathOp.DIFFERENCE)


def stroke(p, w, join=pathops.LineJoin.ROUND_JOIN):
    q = pathops.Path(p)
    q.stroke(w, pathops.LineCap.ROUND_CAP, join, 4)
    q.convertConicsToQuads()
    return union([q])


def transform(p, a, b, c, d, e, f):
    q = pathops.Path()
    p.draw(TransformPen(q.getPen(), (a, b, c, d, e, f)))
    return union([q])


def bounds(layers):
    xs0, ys0, xs1, ys1 = [], [], [], []
    for _, p in layers:
        if len(list(p.segments)) == 0:
            continue
        b = p.bounds
        xs0.append(b[0]); ys0.append(b[1]); xs1.append(b[2]); ys1.append(b[3])
    return min(xs0), min(ys0), max(xs1), max(ys1)


def fit(layers, W, H, margin=0.0):
    """Scale uniformly so the design's bounding box fits W x H (minus margin), centred."""
    x0, y0, x1, y1 = bounds(layers)
    k = min((W - 2 * margin) / (x1 - x0), (H - 2 * margin) / (y1 - y0))
    dx = (W - (x1 - x0) * k) / 2 - x0 * k
    dy = (H - (y1 - y0) * k) / 2 - y0 * k
    return [(c, transform(p, k, 0, 0, k, dx, dy)) for c, p in layers]


class _RLPen(BasePen):
    def __init__(self, rlpath):
        super().__init__(None)
        self.p = rlpath

    def _moveTo(self, pt):
        self.p.moveTo(*pt)

    def _lineTo(self, pt):
        self.p.lineTo(*pt)

    def _curveToOne(self, a, b, c):
        self.p.curveTo(*a, *b, *c)

    def _closePath(self):
        self.p.close()


class _SVGPen(BasePen):
    def __init__(self, H):
        super().__init__(None)
        self.H = H
        self.d = []

    def _f(self, pt):
        return f"{pt[0]:.3f} {self.H - pt[1]:.3f}"

    def _moveTo(self, pt):
        self.d.append("M" + self._f(pt))

    def _lineTo(self, pt):
        self.d.append("L" + self._f(pt))

    def _curveToOne(self, a, b, c):
        self.d.append("C" + " ".join(self._f(q) for q in (a, b, c)))

    def _closePath(self):
        self.d.append("Z")


def hexc(c):
    return "#%02X%02X%02X" % c


def save_pdf(layers, W, H, path, title):
    from reportlab.lib.units import mm
    from reportlab.pdfgen import canvas

    cv = canvas.Canvas(path, pagesize=(W * mm, H * mm))
    cv.setTitle(title)
    cv.setAuthor("Ива — инструмент в аренду")
    cv.scale(mm, mm)
    for col, p in layers:
        rp = cv.beginPath()
        p.draw(_RLPen(rp))
        cv.setFillColorRGB(*(v / 255 for v in col))
        cv.drawPath(rp, stroke=0, fill=1, fillMode=0)  # non-zero winding (pathops output)
    cv.showPage()
    cv.save()


def save_svg(layers, W, H, path):
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}mm" height="{H}mm" viewBox="0 0 {W} {H}">']
    for col, p in layers:
        pen = _SVGPen(H)
        p.draw(pen)
        out.append(f'  <path fill="{hexc(col)}" d="{" ".join(pen.d)}"/>')
    out.append("</svg>\n")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(out))


def render_png(pdf_path, png_path, px_w, bg=GLASS, pad=0.04):
    import pymupdf as fitz
    from PIL import Image

    doc = fitz.open(pdf_path)
    page = doc[0]
    zoom = px_w / page.rect.width
    pix = page.get_pixmap(matrix=fitz.Matrix(zoom, zoom), alpha=True)
    im = Image.frombytes("RGBA", (pix.width, pix.height), pix.samples)
    p = int(px_w * pad)
    canvas_im = Image.new("RGB", (pix.width + 2 * p, pix.height + 2 * p), bg)
    canvas_im.paste(im, (p, p), im)
    canvas_im.save(png_path)
    return canvas_im
