#!/usr/bin/env python3
"""Вырезка предмета для плиток категорий главной (стиль — docs/ПЛИТКИ-КАТЕГОРИЙ.md).

  python3 tools/cat-cut.py power garden ...        assets/cat/src/<ключ>.jpg -> assets/cat/<ключ>-cut.webp
  python3 tools/cat-cut.py --trim-right welding    + отрезать тонкий «хвост» справа (кабель сварочника)
  python3 tools/cat-cut.py --keep-all sub/x         оставить все крупные части предмета (ручку на тонкой шейке)
  python3 tools/cat-cut.py --crop=0.25,0,0.68,0.95 sub/cut-chasers   сначала обрезать кадр (доли), если нейросеть теряет часть предмета
  python3 tools/cat-cut.py --keep-all --extra=0,0,0.5,0.3 sub/gen-light   часть кадра посмотреть ещё раз отдельно (можно несколько --extra)

Нужно: pip install onnxruntime numpy pillow scipy  и модель ISNet (медленная, ~88 МБ, в репозиторий НЕ кладём):
  npm pack @imgly/background-removal-node@1.4.5 && tar xzf imgly-*.tgz
  python3 tools/cat-cut.py --extract-model package/dist     # соберёт /tmp/bg/medium.onnx из кусков
Путь к модели можно задать переменной CAT_MODEL. Всё работает локально, без внешних сервисов.
"""
import os, sys, json, numpy as np
from PIL import Image
from scipy import ndimage as nd
MODEL = os.environ.get('CAT_MODEL', '/tmp/bg/medium.onnx')
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
if len(sys.argv) == 3 and sys.argv[1] == '--extract-model':
    d = sys.argv[2]; res = json.load(open(os.path.join(d, 'resources.json')))['/models/medium']
    os.makedirs(os.path.dirname(MODEL), exist_ok=True)
    with open(MODEL, 'wb') as o:
        for c in res['chunks']: o.write(open(os.path.join(d, c['hash']), 'rb').read())
    print('модель:', MODEL, os.path.getsize(MODEL)); sys.exit(0)
import onnxruntime as ort
CROP = None
EXTRA = []
KEEP_ALL = False   # --keep-all: оставить все крупные части (ручка штробореза отделена тонкой шейкой)
s = ort.InferenceSession(MODEL)
def mask(im):
    x = np.asarray(im.resize((1024,1024), Image.BILINEAR)).astype(np.float32)
    y = s.run(['output'], {'input': ((x-128)/256).transpose(2,0,1)[None]})[0][0,0]
    return np.asarray(Image.fromarray((np.clip(y,0,1)*255).astype(np.uint8)).resize(im.size, Image.BILINEAR)).astype(np.float32)/255
def cut(name, src, out):
    im = Image.open(src).convert('RGB')
    if CROP:   # --crop=x0,y0,x1,y1 (доли кадра): нейросеть видит предмет крупнее и не теряет тёмные части
        W0, H0 = im.size; im = im.crop((int(W0*CROP[0]), int(H0*CROP[1]), int(W0*CROP[2]), int(H0*CROP[3])))
    a = mask(im); H, W = a.shape
    for ex in EXTRA:   # --extra=x0,y0,x1,y1: эту часть кадра нейросеть смотрит ещё раз отдельно (тёмные фонари на штативе)
        ex0, ey0, ex1, ey1 = int(W*ex[0]), int(H*ex[1]), int(W*ex[2]), int(H*ex[3])
        a[ey0:ey1, ex0:ex1] = np.maximum(a[ey0:ey1, ex0:ex1], mask(im.crop((ex0, ey0, ex1, ey1))))
    a = np.clip((a-0.12)/0.76, 0, 1)
    solid = a > 0.5
    # «толстая» часть предмета: тонкие кабели и шланги отрезаются открытием
    k = max(5, int(min(H, W)*0.012))
    thick = nd.binary_opening(solid, structure=np.ones((k,k)))
    lab, n = nd.label(thick)
    sizes = nd.sum(thick, lab, range(1, n+1))
    main = lab == (1+int(np.argmax(sizes)))
    ys, xs = np.where(main); y0,y1,x0,x1 = ys.min(), ys.max(), xs.min(), xs.max()
    # соседние крупные части (маска на сварочнике, колёса тележки) — если пересекают рамку главного
    for i,sz in enumerate(sizes):
        if KEEP_ALL and sz > 0.01*sizes.max() and i+1 != 1+int(np.argmax(sizes)):
            yy, xx = np.where(lab == i+1)
            y0,y1,x0,x1 = min(y0,yy.min()),max(y1,yy.max()),min(x0,xx.min()),max(x1,xx.max()); continue
        if sz > 0.03*sizes.max() and i+1 != 1+int(np.argmax(sizes)):
            yy, xx = np.where(lab == i+1)
            if xx.min() < x1 + 0.05*W and xx.max() > x0 - 0.05*W and yy.min() < y1 and yy.max() > y0 - 0.05*H:
                y0,y1,x0,x1 = min(y0,yy.min()),max(y1,yy.max()),min(x0,xx.min()),max(x1,xx.max())
    # всё, что связано с предметом (тонкие ручки, копьё, кабели), оставляем, остальное убираем
    lab2, _ = nd.label(a > 0.05)
    keep = np.isin(lab2, np.unique(lab2[(a > 0.05) & (np.pad(np.ones((y1-y0+1, x1-x0+1),bool),((y0,H-y1-1),(x0,W-x1-1))))]))
    keep &= lab2 > 0
    a = a * keep
    py, px = int((y1-y0)*0.04), int((x1-x0)*0.06)
    Y0, Y1, X0, X1 = max(0,y0-py), min(H,y1+py//2+1), max(0,x0-px), min(W,x1+px+1)
    a = a[Y0:Y1, X0:X1].copy(); rgb = np.asarray(im)[Y0:Y1, X0:X1]
    h, w = a.shape; L = max(8, int(w*0.12))
    ramp = np.ones(w, np.float32); ramp[:L] = np.linspace(0,1,L)**1.5; ramp[-L:] = np.minimum(ramp[-L:], np.linspace(1,0,L)**1.5)
    # плавно гасим только то, что выходит за «толстую» часть по бокам (кабели), сам корпус не трогаем
    body = main[Y0:Y1, X0:X1] | (nd.binary_dilation(thick, iterations=3)[Y0:Y1, X0:X1])
    a = np.where(body, a, a*ramp[None,:])
    o = Image.fromarray(np.dstack([rgb, (a*255).astype(np.uint8)]), 'RGBA')
    bb = o.getchannel('A').point(lambda v: 255 if v > 10 else 0).getbbox(); o = o.crop(bb)
    if o.height > 640: o = o.resize((round(o.width*640/o.height), 640), Image.LANCZOS)
    o.save(out, quality=88, method=6); print(name, o.size)
    return o
def trim_right(o):
    """Отрезать тонкий хвост справа (кабель): оставить «толстую» часть и плавно погасить остаток."""
    a = np.asarray(o.getchannel('A')) > 128
    thick = nd.binary_opening(a, structure=np.ones((31, 31)))
    lab, n = nd.label(thick); sz = nd.sum(thick, lab, range(1, n+1)); main = lab == 1+int(np.argmax(sz))
    W = min(o.width, np.where(main)[1].max() + int(o.width*0.03))
    c = np.asarray(o).copy()[:, :W]; L = int(W*0.08); ramp = np.ones(W); ramp[-L:] = np.linspace(1, 0, L)**1.5
    body = nd.binary_dilation(main, iterations=4)[:, :W]
    c[..., 3] = np.where(body, c[..., 3], (c[..., 3]*ramp[None, :]).astype(np.uint8))
    r = Image.fromarray(c, 'RGBA'); return r.crop(r.getchannel('A').point(lambda v: 255 if v > 10 else 0).getbbox())

if __name__ == '__main__':
    args = sys.argv[1:]; trim = '--trim-right' in args; KEEP_ALL = '--keep-all' in args;
    for x in args:
        if x.startswith('--crop='): CROP = [float(v) for v in x[7:].split(',')]
        if x.startswith('--extra='): EXTRA.append([float(v) for v in x[8:].split(',')])
    names = [a for a in args if not a.startswith('--')]
    for n in names:
        out = os.path.join(ROOT, 'assets', 'cat', n + '-cut.webp')
        o = cut(n, os.path.join(ROOT, 'assets', 'cat', 'src', n + '.jpg'), out)
        if trim:
            o = trim_right(o); o.save(out, quality=88, method=6); print(n, 'после обрезки', o.size)
        print('  размер для CAT_ART в index.html:', list(o.size))
