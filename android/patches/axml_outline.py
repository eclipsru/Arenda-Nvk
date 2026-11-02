#!/usr/bin/env python3
"""Ива 10.96 — единый стиль «рамкой» прямо в ресурсах APK (без пересборки ресурсов).

Решение владельца 16.10.2026: все залитые оранжевым кнопки и элементы — как «Добавить» в «Пункт выдачи»:
тёмный фон (card #1A1D24), оранжевая рамка 1,5dp, оранжевый текст. Так — кнопки, выбранные чипы,
кружки-счётчики, «×» на фото, кружок отправки, свои пузыри в чате, аватарки с буквой.

Запуск: python3 axml_outline.py <папка apktool d -r>

1. Двоичные drawable из res/drawable/ заменяются копией штатного шаблона той же формы, у которой
   меняются только значения (цвет заливки, ширина и цвет рамки); скругления — как были:
     bg_btn_orange_shape   ← bg_field  (14dp)  card + рамка orange
     bg_btn_orange_pressed ← bg_field  (14dp)  оранжевый 20% + рамка orange (нажатие)
     bg_chip_on            ← bg_chip   (20dp)  card + рамка orange
     bg_bubble_me          ← bg_bubble_other (16dp) card + рамка orange
     bg_nav_circle_on, bg_badge, bg_avatar ← bg_badge (круг) card + рамка orange
2. В res/layout*/activity_main.xml у элементов на этих фонах (и у букв внутри аватарок)
   textColor @color/white → @color/orange. Размер файла не меняется: правится только значение атрибута.
Строго: каждый шаблон и каждое место должны найтись ровно так, как ожидается, иначе — ошибка.
"""
import os
import struct
import sys

CARD = 0xFF1A1D24
ORANGE = 0xFFFF8F1F
PRESSED = 0x33FF8F1F
STROKE_1_5DP = 0x0000C011          # 1.5dp: мантисса 192, radix 16p7, единица dp
WHITE_REF, ORANGE_REF = 0x7F01000F, 0x7F01000A
T_REF, T_DIM, T_ARGB8 = 0x01, 0x05, 0x1C

# цель ← (шаблон, {(тег, атрибут): (тип, значение)})
DRAWABLES = {
    'bg_btn_orange_shape': ('bg_field', {('solid', 'color'): (T_ARGB8, CARD), ('stroke', 'width'): (T_DIM, STROKE_1_5DP), ('stroke', 'color'): (T_ARGB8, ORANGE)}),
    'bg_btn_orange_pressed': ('bg_field', {('solid', 'color'): (T_ARGB8, PRESSED), ('stroke', 'width'): (T_DIM, STROKE_1_5DP), ('stroke', 'color'): (T_ARGB8, ORANGE)}),
    'bg_chip_on': ('bg_chip', {('solid', 'color'): (T_ARGB8, CARD), ('stroke', 'width'): (T_DIM, STROKE_1_5DP), ('stroke', 'color'): (T_ARGB8, ORANGE)}),
    'bg_bubble_me': ('bg_bubble_other', {('solid', 'color'): (T_ARGB8, CARD), ('stroke', 'width'): (T_DIM, STROKE_1_5DP), ('stroke', 'color'): (T_ARGB8, ORANGE)}),
    'bg_nav_circle_on': ('bg_badge', {('solid', 'color'): (T_ARGB8, CARD), ('stroke', 'width'): (T_DIM, STROKE_1_5DP), ('stroke', 'color'): (T_ARGB8, ORANGE)}),
    'bg_badge': ('bg_badge', {('solid', 'color'): (T_ARGB8, CARD), ('stroke', 'width'): (T_DIM, STROKE_1_5DP), ('stroke', 'color'): (T_ARGB8, ORANGE)}),
    'bg_avatar': ('bg_badge', {('solid', 'color'): (T_ARGB8, CARD), ('stroke', 'width'): (T_DIM, STROKE_1_5DP), ('stroke', 'color'): (T_ARGB8, ORANGE)}),
}
# шаблоны: какая форма у них должна быть (защита от чужого APK)
TEMPLATE_SHAPE = {'bg_field': (0, 0xE01), 'bg_chip': (0, 0x1401), 'bg_bubble_other': (0, 0x1001), 'bg_badge': (1, None)}
OUTLINED = ('bg_btn_orange', 'bg_chip_on', 'bg_nav_circle_on', 'bg_badge', 'bg_bubble_me', 'bg_avatar')
LAYOUT_TEXTS = 13                   # 11 кнопок bg_btn_orange + 2 буквы в аватарках


def die(m):
    print('ОШИБКА СТИЛЯ: ' + m, file=sys.stderr)
    if os.environ.get('GITHUB_ACTIONS'):
        print('::error title=Стиль рамкой::' + m)
    sys.exit(1)


def strings(b, pos):
    _t, hsz, _s = struct.unpack_from('<HHI', b, pos)
    cnt, _sty, flags, ss, _so = struct.unpack_from('<IIIII', b, pos + 8)
    utf8, offs, out = bool(flags & 0x100), struct.unpack_from('<%dI' % cnt, b, pos + hsz), []
    for o in offs:
        p = pos + ss + o
        if utf8:
            n = b[p]; p += 1
            if n & 0x80: p += 1
            n = b[p]; p += 1
            if n & 0x80: n = ((n & 0x7F) << 8) | b[p]; p += 1
            out.append(bytes(b[p:p + n]).decode('utf8', 'replace'))
        else:
            n = struct.unpack_from('<H', b, p)[0]; p += 2
            if n & 0x8000: n = ((n & 0x7FFF) << 16) | struct.unpack_from('<H', b, p)[0]; p += 2
            out.append(bytes(b[p:p + 2 * n]).decode('utf-16le', 'replace'))
    return out


def elements(b):
    """[(тег, {атрибут: (смещение Res_value.dataType, тип, данные)})] в порядке файла; None — закрывающий тег."""
    if struct.unpack_from('<HHI', b, 0)[0] != 0x0003:
        die('не двоичный XML')
    pos, st, out = 8, [], []
    while pos < len(b):
        t, _h, s = struct.unpack_from('<HHI', b, pos)
        if s <= 0: die('битый XML')
        if t == 0x0001:
            st = strings(b, pos)
        elif t == 0x0102:
            name, ast, asz, ac = struct.unpack_from('<IHHH', b, pos + 20)
            attrs = {}
            for i in range(ac):
                a = pos + 16 + ast + i * asz
                _ns, an, _raw, _vs, _r0, vt, vd = struct.unpack_from('<IIIHBBI', b, a)
                attrs[st[an]] = (a + 15, vt, vd)
            out.append((st[name], attrs))
        elif t == 0x0103:
            out.append(None)
        pos += s
    return out


def set_val(b, at, vt, vd):
    b[at] = vt
    struct.pack_into('<I', b, at + 1, vd)


def drawable(res, name):
    for d in ('drawable', ):
        p = os.path.join(res, d, name + '.xml')
        if os.path.exists(p):
            return p
    die(f'нет res/drawable/{name}.xml')


def outline_drawables(root):
    res = os.path.join(root, 'res')
    tpl = {}
    for t, (shape, radius) in TEMPLATE_SHAPE.items():
        b = bytearray(open(drawable(res, t), 'rb').read())
        els = [e for e in elements(b) if e]
        tags = [e[0] for e in els]
        if tags[:1] != ['shape'] or 'solid' not in tags or 'stroke' not in tags:
            die(f'шаблон {t}: ожидалась форма с заливкой и рамкой, а не {tags}')
        sh = els[0][1].get('shape')
        if (sh[2] if sh else 0) != shape:
            die(f'шаблон {t}: другая форма ({sh})')
        if radius is not None:
            rad = [e[1]['radius'][2] for e in els if e[0] == 'corners' and 'radius' in e[1]]
            if rad != [radius]:
                die(f'шаблон {t}: скругление {rad}, ожидалось {hex(radius)}')
        tpl[t] = bytes(b)
    done = []
    for target, (t, vals) in DRAWABLES.items():
        drawable(res, target)                         # цель обязана быть в APK
        b = bytearray(tpl[t])
        els = [e for e in elements(b) if e]
        for (tag, attr), (vt, vd) in vals.items():
            hit = [e[1][attr] for e in els if e[0] == tag and attr in e[1]]
            if len(hit) != 1:
                die(f'{target}: {tag}/{attr} найдено {len(hit)}')
            set_val(b, hit[0][0], vt, vd)
        open(os.path.join(res, 'drawable', target + '.xml'), 'wb').write(bytes(b))
        done.append(target)
    return done


def ids_of(root):
    """Имена drawable → id из public-номеров resources.arsc (тип drawable)."""
    b = open(os.path.join(root, 'resources.arsc'), 'rb').read()
    pos = 12 + struct.unpack_from('<I', b, 16)[0]
    pkg = pos
    _t, hsz, size = struct.unpack_from('<HHI', b, pkg)
    pid = struct.unpack_from('<I', b, pkg + 8)[0]
    ts, ks = struct.unpack_from('<I', b, pkg + 268)[0], struct.unpack_from('<I', b, pkg + 276)[0]
    tn, kn = strings(b, pkg + ts), strings(b, pkg + ks)
    out, p = {}, pkg + hsz
    while p < pkg + size:
        t, h, s = struct.unpack_from('<HHI', b, p)
        if t == 0x0201:
            tid = b[p + 8]
            ec, es = struct.unpack_from('<II', b, p + 12)
            for i, o in enumerate(struct.unpack_from('<%dI' % ec, b, p + h)):
                if o != 0xFFFFFFFF and tn[tid - 1] == 'drawable':
                    out[kn[struct.unpack_from('<I', b, p + es + o + 4)[0]]] = (pid << 24) | (tid << 16) | i
        p += s
    return out


def recolor_layouts(root):
    ids = ids_of(root)
    want = {ids[n] for n in OUTLINED if n in ids}
    if len(want) != len(OUTLINED):
        die('в resources.arsc нет части фонов: ' + ', '.join(n for n in OUTLINED if n not in ids))
    avatar = ids['bg_avatar']
    total = []
    for d in sorted(os.listdir(os.path.join(root, 'res'))):
        p = os.path.join(root, 'res', d, 'activity_main.xml')
        if not d.startswith('layout') or not os.path.exists(p):
            continue
        b = bytearray(open(p, 'rb').read())
        stack, n = [], 0
        for e in elements(b):
            if e is None:
                stack.pop(); continue
            _tag, at = e
            bg = at.get('background')
            on_outline = bg is not None and bg[1] == T_REF and bg[2] in want
            in_avatar = bool(stack) and stack[-1] == avatar
            tc = at.get('textColor')
            if (on_outline or in_avatar) and tc is not None and tc[1] == T_REF and tc[2] == WHITE_REF:
                set_val(b, tc[0], T_REF, ORANGE_REF); n += 1
            stack.append(bg[2] if bg is not None and bg[1] == T_REF else None)
        if n != LAYOUT_TEXTS:
            die(f'{d}/activity_main.xml: белый текст на залитых фонах найден {n} раз, ожидалось {LAYOUT_TEXTS}')
        open(p, 'wb').write(bytes(b))
        total.append(f'{d}: {n}')
    if not total:
        die('нет res/layout*/activity_main.xml')
    return total


def main():
    if len(sys.argv) != 2:
        die('нужно: axml_outline.py <папка apktool d -r>')
    root = sys.argv[1]
    d = outline_drawables(root)
    l = recolor_layouts(root)
    msg = 'рамкой: ' + ', '.join(d) + '; текст оранжевый — ' + '; '.join(l)
    print(msg)
    if os.environ.get('GITHUB_ACTIONS'):
        print('::notice title=Стиль рамкой::' + msg)


if __name__ == '__main__':
    main()
