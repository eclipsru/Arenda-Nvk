#!/usr/bin/env python3
"""Ива — размеры картинок плиток категорий и подкатегорий для index.html (блок ART:BEGIN … ART:END).

  python3 tools/cat-art-map.py        — найти assets/cat/<ключ>-cut.webp и assets/cat/sub/<ключ>-cut.webp и вписать размеры

Ключи категорий берутся из имён файлов (служебные *-preview, home-*, cleaning.jpg — не *-cut.webp и не попадают).
"""
import glob, os, re
from PIL import Image
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
def sizes(pattern):
    out = {}
    for f in sorted(glob.glob(os.path.join(ROOT, pattern))):
        k = os.path.basename(f)[:-len('-cut.webp')]
        with Image.open(f) as im: out[k] = list(im.size)
    return out
def js(name, d):
    items = ["'%s':[%d,%d]" % (k, v[0], v[1]) for k, v in d.items()]
    lines, cur = [], ''
    for it in items:
        if len(cur) + len(it) > 100: lines.append(cur.rstrip()); cur = ''
        cur += it + ', '
    if cur: lines.append(cur.rstrip(', '))
    return '  var %s = {\n    %s\n  };' % (name, ',\n    '.join(l.rstrip(',') for l in lines)) if items else '  var %s = {};' % name
cats, subs = sizes('assets/cat/*-cut.webp'), sizes('assets/cat/sub/*-cut.webp')
p = os.path.join(ROOT, 'index.html'); s = open(p, encoding='utf-8').read()
block = '  /* ART:BEGIN */\n' + js('CAT_ART', cats) + '\n' + js('SUB_ART', subs) + '\n  /* ART:END */'
s2, n = re.subn(r'  /\* ART:BEGIN \*/.*?  /\* ART:END \*/', lambda m: block, s, flags=re.S)
assert n == 1, 'нет блока ART:BEGIN/END в index.html'
open(p, 'w', encoding='utf-8').write(s2)
print('категорий с картинкой:', len(cats), '·', ', '.join(cats))
print('подкатегорий с картинкой:', len(subs), '·', ', '.join(subs))
