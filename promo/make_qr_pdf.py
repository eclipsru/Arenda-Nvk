#!/usr/bin/env python3
"""QR-код приложения (assets/qr-app-logo.png → app.html) в чёрной рамке, PDF для печати (просьба владельца 02.11.2026).

Квадратики кода перерисованы векторами (резкие при любом размере печати), логотип в центре — картинка из того же
файла. Рисунок кода не меняется: модули считываются с исходного PNG (41 × 41 модуль по 28 px, поле 4 модуля).

Запуск: python3 promo/make_qr_pdf.py        →  promo/qr-app-ramka.pdf     (104 × 104 мм)
        python3 promo/make_qr_pdf.py 370    →  promo/qr-app-ramka-370.pdf (370 × 370 мм, плакат; 02.11.2026)
Рамка, скругления и модули масштабируются пропорционально — вид одинаковый при любом размере.
Логотип для больших размеров — assets/logo.png 512 px (тот же, что вставлен в QR), а не 276 px из PNG кода.
Нужны: pip install reportlab pillow
"""
import os
import sys
from PIL import Image
from reportlab.pdfgen import canvas
from reportlab.lib.units import mm
from reportlab.lib.utils import ImageReader

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'assets', 'qr-app-logo.png')
LOGO_HI = os.path.join(ROOT, 'assets', 'logo.png')   # исходник логотипа, 512 px (make-qr.py вставляет его в код)
SIZE_MM = float(sys.argv[1]) if len(sys.argv) > 1 else 104.0
OUT = os.path.join(ROOT, 'promo', 'qr-app-ramka.pdf' if SIZE_MM == 104 else 'qr-app-ramka-%d.pdf' % round(SIZE_MM))

PX = 28                      # модуль в исходном PNG, px
QUIET = 4                    # белое поле вокруг кода, модулей
N = 41                       # размер кода, модулей (версия 6)
PLATE = (520, 852, 80)       # белая подложка логотипа в PNG: от, до, радиус углов (px)
LOGO = (548, 824, 55)        # логотип в PNG: от, до, радиус углов (px)

# Всё в модулях: страница = код с полем (49) + рамка 1,5 модуля с двух сторон = 52 модуля.
# 104 мм → модуль 2 мм, рамка 3 мм, скругления 8 и 5 мм; 370 мм → модуль 7,12 мм, рамка 10,7 мм, скругления 28,5 и 17,8 мм.
MOD = SIZE_MM / 52 * mm      # модуль в PDF
FRAME = 1.5 * MOD            # толщина чёрной рамки
R_OUT, R_IN = 4 * MOD, 2.5 * MOD   # скругление рамки снаружи и внутри
INK = (0x11 / 255, 0x12 / 255, 0x17 / 255)   # цвет модулей как в исходнике (#111217)


def main():
    im = Image.open(SRC).convert('RGB')
    side = (N + 2 * QUIET) * MOD           # код с полем
    page = side + 2 * FRAME
    c = canvas.Canvas(OUT, pagesize=(page, page))
    c.setTitle('Ива — QR-код приложения (app.html)')
    c.setAuthor('Ива')

    # чёрная рамка: чёрный скруглённый квадрат, внутри белый
    c.setFillColorRGB(0, 0, 0)
    c.roundRect(0, 0, page, page, R_OUT, stroke=0, fill=1)
    c.setFillColorRGB(1, 1, 1)
    c.roundRect(FRAME, FRAME, side, side, R_IN, stroke=0, fill=1)

    def to_pdf(px):            # координата PNG (px) → PDF (pt), ось Y снизу вверх
        return px / PX * MOD

    x0 = y0 = FRAME
    # модули кода — векторами. Модуль рисуем, если хоть одна его точка вне белой подложки логотипа тёмная
    # (подложка со скруглёнными углами рисуется поверх и закрывает лишнее — как в исходнике).
    c.setFillColorRGB(*INK)
    p0, p1, pr = PLATE

    def under_plate(x, y):     # точка PNG внутри скруглённой подложки?
        if not (p0 <= x <= p1 and p0 <= y <= p1):
            return False
        cx = min(max(x, p0 + pr), p1 - pr)
        cy = min(max(y, p0 + pr), p1 - pr)
        return (x - cx) ** 2 + (y - cy) ** 2 <= pr ** 2

    def dark(q, r):
        mx, my = (QUIET + q) * PX, (QUIET + r) * PX
        pts = ((mx + PX // 2, my + PX // 2), (mx + 3, my + 3), (mx + PX - 4, my + 3), (mx + 3, my + PX - 4), (mx + PX - 4, my + PX - 4))
        return any(not under_plate(x, y) and sum(im.getpixel((x, y))) / 3 < 80 for x, y in pts)

    # Соседние модули строки склеены в полосы, всё — один контур: без светлых щелей между квадратиками в просмотрщиках
    grid = [[dark(q, r) for q in range(N)] for r in range(N)]
    path = c.beginPath()
    for r in range(N):
        q = 0
        while q < N:
            if not grid[r][q]:
                q += 1
                continue
            q1 = q
            while q1 < N and grid[r][q1]:
                q1 += 1
            ov = 0.02 * MOD if r + 1 < N and any(grid[r + 1][q:q1]) else 0   # чуть заходим на строку ниже
            path.rect(x0 + (QUIET + q) * MOD, y0 + side - (QUIET + r + 1) * MOD - ov, (q1 - q) * MOD, MOD + ov)
            q = q1
    c.drawPath(path, stroke=0, fill=1, fillMode=1)

    c.setFillColorRGB(1, 1, 1)
    c.roundRect(x0 + to_pdf(p0), y0 + side - to_pdf(p1), to_pdf(p1 - p0), to_pdf(p1 - p0), to_pdf(PLATE[2]), stroke=0, fill=1)

    # логотип — картинка из исходника, со скруглёнными углами
    l0, l1, lr = LOGO
    logo = im.crop((l0, l0, l1, l1))
    if SIZE_MM > 104 and os.path.exists(LOGO_HI):
        logo = Image.open(LOGO_HI).convert('RGBA')      # плакат: исходный логотип 512 px, а не 276 px из PNG кода
    lx, ly, lw = x0 + to_pdf(l0), y0 + side - to_pdf(l1), to_pdf(l1 - l0)
    c.saveState()
    path = c.beginPath()
    path.roundRect(lx, ly, lw, lw, to_pdf(lr))
    c.clipPath(path, stroke=0, fill=0)
    c.drawImage(ImageReader(logo), lx, ly, lw, lw, mask='auto')
    c.restoreState()

    c.showPage()
    c.save()
    print('PDF:', OUT, '— %.0f × %.0f мм' % (page / mm, page / mm))


if __name__ == '__main__':
    main()
