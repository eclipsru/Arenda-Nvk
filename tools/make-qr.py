#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
QR-коды проекта «Ива»: на живой сайт и на скачивание приложения.

Коды рисуются здесь же, библиотекой qrcode — никакие внешние сервисы не
используются (адреса никуда не уходят, коды не «протухнут» и не перестанут
работать, если сторонний сервис закроется).

Что получается (файлы кладутся в assets/):
  qr-site.png      — сайт (главная, каталог и цены);
  qr-app.png       — страница приложения: кнопка «Скачать Android» сама подставляет
                     текущую версию из базы, поэтому код не устаревает;
  qr-app-apk.png   — сразу скачивает файл подтверждённой версии (10.15);
  qr-listovka.png  — лист для печати: два кода с подписями;
  qr-app-logo.png  — ПОСТОЯННЫЙ код на скачивание приложения с цветным логотипом в центре
                     (16.10.2026, просьба владельца). Ведёт на страницу app.html: она всегда
                     отдаёт версию, выпущенную для всех (из базы app_releases), поэтому сам код
                     не меняется никогда — перерисовывать его при новых версиях не нужно;
  qr-site-logo.png — ПОСТОЯННЫЙ код на сайт (главная, открыта всем) с цветным логотипом
                     и надписью «Ива» в центре (16.10.2026, просьба владельца).

Запуск:
    python3 tools/make-qr.py             # перерисовать файлы
    python3 tools/make-qr.py --check     # проверить, что файлы совпадают с адресами
    python3 tools/make-qr.py --verify    # то же + прочитать коды обратно (нужен opencv)

Откат: git revert коммита с этим файлом; сами картинки удалять не обязательно —
на сайт они не влияют, пока на них никто не ссылается.
"""
import os
import sys

import qrcode
from qrcode.constants import ERROR_CORRECT_H, ERROR_CORRECT_Q
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "assets")

SITE = "https://eclipsru.github.io/Arenda-Nvk"
APP_PAGE = SITE + "/app.html"
APK = SITE + "/ProkatInstrumenta-10.15.apk"

TARGETS = [
    ("qr-site.png", "Сайт: каталог и цены", SITE),
    ("qr-app.png", "Приложение: страница скачивания", APP_PAGE),
    ("qr-app-apk.png", "Приложение: сразу скачать 10.15", APK),
]
BOX = 16          # размер «точки» в пикселях — хватает для печати ~7×7 см

# Постоянный код с логотипом: максимальная коррекция ошибок (H — восстанавливает до 30% кода),
# логотип закрывает ~6% площади, поэтому код читается с запасом.
LOGO_NAME = "qr-app-logo.png"
LOGO_URL = APP_PAGE
LOGO_FILE = os.path.join(OUT, "logo.png")      # цветной логотип приложения (тот же, что iva_logo.png)
LOGO_BOX = 28                                  # ~1150 px — печать до 10×10 см без размытия
LOGO_SHARE = 0.24                              # сторона логотипа — доля от стороны самого кода

# Постоянный код на сайт: логотип и надпись «Ива» под ним на одной белой подложке (~9% площади).
SITE_LOGO_NAME = "qr-site-logo.png"
SITE_LOGO_URL = SITE + "/"                     # главная, как в sitemap.xml (без лишнего перенаправления)
SITE_LOGO_SHARE = 0.20
SITE_CAPTION = "Ива"
CAPTION_COLOR = "#FF8F1F"                      # фирменный оранжевый
CAPTION_FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
BORDER = 4        # обязательное пустое поле вокруг кода


def make_image(url):
    qr = qrcode.QRCode(error_correction=ERROR_CORRECT_Q, box_size=BOX, border=BORDER)
    qr.add_data(url)
    qr.make(fit=True)
    return qr.make_image(fill_color="#101418", back_color="white").convert("RGB")


def make_logo_image(url):
    """Постоянный код: тёмные точки на белом, в центре — цветной логотип на белой подложке."""
    qr = qrcode.QRCode(error_correction=ERROR_CORRECT_H, box_size=LOGO_BOX, border=BORDER)
    qr.add_data(url)
    qr.make(fit=True)
    img = qr.make_image(fill_color="#101418", back_color="white").convert("RGB")
    n = qr.modules_count                                   # сторона кода в точках (без поля)
    side = int(n * LOGO_BOX * LOGO_SHARE)
    logo = Image.open(LOGO_FILE).convert("RGBA").resize((side, side), Image.LANCZOS)
    # белая подложка со скруглением — на целое число точек больше логотипа, по центру сетки
    pad = LOGO_BOX
    plate = side + 2 * pad
    x0 = (img.width - plate) // 2
    y0 = (img.height - plate) // 2
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((x0, y0, x0 + plate - 1, y0 + plate - 1), radius=int(plate * 0.22), fill="white")
    img.paste(logo, (x0 + pad, y0 + pad), logo)
    return img


def make_site_logo_image(url):
    """Постоянный код на сайт: в центре — цветной логотип и под ним оранжевая надпись «Ива»."""
    qr = qrcode.QRCode(error_correction=ERROR_CORRECT_H, box_size=LOGO_BOX, border=BORDER)
    qr.add_data(url)
    qr.make(fit=True)
    img = qr.make_image(fill_color="#101418", back_color="white").convert("RGB")
    n = qr.modules_count
    side = int(n * LOGO_BOX * SITE_LOGO_SHARE)
    logo = Image.open(LOGO_FILE).convert("RGBA").resize((side, side), Image.LANCZOS)
    f = ImageFont.truetype(CAPTION_FONT, int(side * 0.34)) if os.path.exists(CAPTION_FONT) else font(int(side * 0.34))
    l, t, r, b = f.getbbox(SITE_CAPTION)
    gap = int(side * 0.06)
    pad = LOGO_BOX
    plate_w = max(side, r - l) + 2 * pad
    plate_h = side + gap + (b - t) + 2 * pad
    x0 = (img.width - plate_w) // 2
    y0 = (img.height - plate_h) // 2
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((x0, y0, x0 + plate_w - 1, y0 + plate_h - 1), radius=int(plate_w * 0.2), fill="white")
    img.paste(logo, (x0 + (plate_w - side) // 2, y0 + pad), logo)
    d.text((img.width // 2 - (r - l) // 2 - l, y0 + pad + side + gap - t), SITE_CAPTION, fill=CAPTION_COLOR, font=f)
    return img


def font(size):
    for path in ("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                 "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"):
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default(size=size)


def make_sheet(rows):
    """Лист для печати: два кода рядом, под каждым подпись и адрес."""
    pad, gap, text_h = 60, 90, 170
    imgs = [make_image(url) for _, _, url in rows[:2]]
    f_title = font(46)
    f_url = font(26)
    # ширина каждой ячейки = максимум из кода и подписей, чтобы ничего не обрезалось
    cell_w, text_h_list = [], []
    for (name, title, url), img in zip(rows[:2], imgs):
        tw = max(f_title.getlength(title), f_url.getlength(url.replace("https://", "")))
        cell_w.append(max(img.width, int(tw) + 20))
    w = pad * 2 + cell_w[0] + gap + cell_w[1]
    h = pad + max(i.height for i in imgs) + text_h + pad
    sheet = Image.new("RGB", (w, h), "white")
    d = ImageDraw.Draw(sheet)
    x = pad
    for (name, title, url), img, cw in zip(rows[:2], imgs, cell_w):
        ix = x + (cw - img.width) // 2
        sheet.paste(img, (ix, pad))
        cx = x + cw // 2
        d.text((cx, pad + img.height + 26), title, fill="#101418", font=f_title, anchor="ma")
        d.text((cx, pad + img.height + 92), url.replace("https://", ""),
               fill="#5b6470", font=f_url, anchor="ma")
        x += cw + gap
    return sheet


def write(path, img):
    img.save(path, "PNG", optimize=True)
    return path


def main():
    check = "--check" in sys.argv
    verify = "--verify" in sys.argv
    bad = 0

    for name, title, url in TARGETS:
        path = os.path.join(OUT, name)
        img = make_image(url)
        if check:
            if not os.path.exists(path):
                print(f"✘ нет файла: assets/{name}")
                bad += 1
                continue
            same = open(path, "rb").read() == _bytes(img)
            print(("✔" if same else "✘"), f"assets/{name}", url, "" if same else "— файл не совпадает, перерисуйте")
            bad += not same
        else:
            write(path, img)
            print(f"✔ assets/{name} — {title} — {url}")

    sheet_path = os.path.join(OUT, "qr-listovka.png")
    sheet = make_sheet(TARGETS)
    if check:
        same = os.path.exists(sheet_path) and open(sheet_path, "rb").read() == _bytes(sheet)
        print(("✔" if same else "✘"), "assets/qr-listovka.png", "" if same else "— лист не совпадает, перерисуйте")
        bad += not same
    else:
        write(sheet_path, sheet)
        print(f"✔ assets/qr-listovka.png — лист для печати ({sheet.width}×{sheet.height})")

    logo_path = os.path.join(OUT, LOGO_NAME)
    logo_img = make_logo_image(LOGO_URL)
    if check:
        same = os.path.exists(logo_path) and open(logo_path, "rb").read() == _bytes(logo_img)
        print(("✔" if same else "✘"), f"assets/{LOGO_NAME}", LOGO_URL, "" if same else "— файл не совпадает, перерисуйте")
        bad += not same
    else:
        write(logo_path, logo_img)
        print(f"✔ assets/{LOGO_NAME} — постоянный код с логотипом ({logo_img.width}×{logo_img.height}) — {LOGO_URL}")

    site_path = os.path.join(OUT, SITE_LOGO_NAME)
    site_img = make_site_logo_image(SITE_LOGO_URL)
    if check:
        same = os.path.exists(site_path) and open(site_path, "rb").read() == _bytes(site_img)
        print(("✔" if same else "✘"), f"assets/{SITE_LOGO_NAME}", SITE_LOGO_URL, "" if same else "— файл не совпадает, перерисуйте")
        bad += not same
    else:
        write(site_path, site_img)
        print(f"✔ assets/{SITE_LOGO_NAME} — постоянный код на сайт с логотипом и «{SITE_CAPTION}» ({site_img.width}×{site_img.height}) — {SITE_LOGO_URL}")

    if verify:
        try:
            import zxingcpp                                   # читает и коды с логотипом
            for name, im, url in ((LOGO_NAME, logo_img, LOGO_URL), (SITE_LOGO_NAME, site_img, SITE_LOGO_URL)):
                for size in (im.width, 600, 300, 180):
                    got = [r.text for r in zxingcpp.read_barcodes(im.resize((size, size), Image.LANCZOS))]
                    ok = got == [url]
                    print(("✔" if ok else "✘"), f"читается обратно: assets/{name} при {size}px -> {got or '(не прочитался)'}")
                    bad += not ok
        except ImportError:
            print("• zxing-cpp не установлен — код с логотипом не прочитан (pip install zxing-cpp)")
        try:
            import cv2
        except ImportError:
            print("• opencv не установлен — читающая проверка пропущена (pip install opencv-python-headless)")
        else:
            det = cv2.QRCodeDetector()
            for name, _, url in TARGETS:
                path = os.path.join(OUT, name)
                got, _, _ = det.detectAndDecode(cv2.imread(path))
                ok = got == url
                print(("✔" if ok else "✘"), f"читается обратно: assets/{name} -> {got or '(не прочитался)'}")
                bad += not ok

    print("ИТОГ ошибок:", bad)
    return 1 if bad else 0


def _bytes(img):
    import io
    buf = io.BytesIO()
    img.save(buf, "PNG", optimize=True)
    return buf.getvalue()


if __name__ == "__main__":
    sys.exit(main())
