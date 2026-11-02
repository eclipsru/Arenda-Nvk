#!/usr/bin/env python3
"""Листы для просмотра картинок плиток (только для разработки, на сайт не идёт).
  python3 tools/cat-sheet.py out.jpg assets/cat/src/sub/a.jpg ...        исходники, 4 в ряд
  python3 tools/cat-sheet.py out.jpg assets/cat/sub/a-cut.webp ...       вырезанные, на тёмном фоне сайта, 6 в ряд
"""
import sys, os
from PIL import Image, ImageDraw
out, files = sys.argv[1], sys.argv[2:]
cut = all(f.endswith('.webp') for f in files)
W, H, cols = (220, 300, 6) if cut else (400, 300, 4)
rows = (len(files) + cols - 1) // cols
S = Image.new('RGB', (W*cols, (H+20)*rows), (26, 29, 36) if cut else (40, 40, 40)); d = ImageDraw.Draw(S)
for i, f in enumerate(files):
    im = Image.open(f).convert('RGBA'); im.thumbnail((W-10, H-10))
    x, y = (i % cols)*W, (i // cols)*(H+20)
    S.paste(im, (x+5, y+5), im); d.text((x+4, y+H+4), os.path.basename(f)[:32], fill=(255, 255, 0))
S.save(out, quality=82)
