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
  qr-app-apk.png   — сразу скачивает файл текущей стабильной версии (10.14);
  qr-listovka.png  — лист для печати: два кода с подписями.

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
from qrcode.constants import ERROR_CORRECT_Q
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "assets")

SITE = "https://eclipsru.github.io/Arenda-Nvk"
APP_PAGE = SITE + "/app.html"
APK = SITE + "/ProkatInstrumenta-10.14.apk"

TARGETS = [
    ("qr-site.png", "Сайт: каталог и цены", SITE),
    ("qr-app.png", "Приложение: страница скачивания", APP_PAGE),
    ("qr-app-apk.png", "Приложение: сразу скачать 10.14", APK),
]
BOX = 16          # размер «точки» в пикселях — хватает для печати ~7×7 см
BORDER = 4        # обязательное пустое поле вокруг кода


def make_image(url):
    qr = qrcode.QRCode(error_correction=ERROR_CORRECT_Q, box_size=BOX, border=BORDER)
    qr.add_data(url)
    qr.make(fit=True)
    return qr.make_image(fill_color="#101418", back_color="white").convert("RGB")


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

    if verify:
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
