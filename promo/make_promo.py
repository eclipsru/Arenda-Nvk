# Сборка рекламной картинки «1 ₽ · 1% · 1 ₽» (4:5, 1080x1350).
# Фон с монетами: promo/coins-bg.png (сгенерирован), логотип: iva_logo.png.
# Шрифт Montserrat (OFL) собирается из npm-пакета @fontsource/montserrat в /tmp/fnt/M*.ttf.
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = '/home/user/Arenda-Nvk/'
W, H = 1360, 1700                    # рабочий размер, потом уменьшаем до 1080x1350
OR, GOLD, TX, GR = (255, 143, 31), (255, 200, 87), (242, 244, 248), (190, 196, 208)
F = lambda w, s: ImageFont.truetype('/tmp/fnt/M%d.ttf' % w, s)

SHIFT = 175                          # поднимаем монеты ближе к верху, чтобы влез текст
src = Image.open(ROOT + 'promo/coins-bg.png').convert('RGB')
bg = Image.new('RGB', (W, H), (14, 15, 19))
bg.paste(src.crop((0, SHIFT, W, src.height)), (0, 0))
fade = Image.new('L', (W, H), 0)
fd = ImageDraw.Draw(fade)
for yy in range(src.height - SHIFT - 120, H):
    fd.line([(0, yy), (W, yy)], fill=min(255, (yy - (src.height - SHIFT - 120)) * 2))
bg = Image.composite(Image.new('RGB', (W, H), (14, 15, 19)), bg, fade)
img = bg.convert('RGBA')

# затемнение под текстом, чтобы читалось на любом экране
shade = Image.new('L', (W, H), 0)
sd = ImageDraw.Draw(shade)
for y in range(520, H):
    sd.line([(0, y), (W, y)], fill=int(min(170, (y - 520) * 0.5)))
img = Image.composite(Image.new('RGBA', (W, H), (12, 13, 17, 255)), img, shade)

# ---- логотип в круге того же размера, что монеты (~334 px) ----
D, CX, TOP = 334, W // 2, 712 - SHIFT
logo = Image.open(ROOT + 'iva_logo.png').convert('RGBA')
s = int(min(logo.size) * 0.80)                       # центр без скруглённых углов
l0 = ((logo.width - s) // 2, (logo.height - s) // 2)
logo = logo.crop((l0[0], l0[1], l0[0] + s, l0[1] + s)).resize((D, D), Image.LANCZOS)
mask = Image.new('L', (D * 4, D * 4), 0)
ImageDraw.Draw(mask).ellipse((0, 0, D * 4 - 1, D * 4 - 1), fill=255)
mask = mask.resize((D, D), Image.LANCZOS)
glow = Image.new('RGBA', (W, H), (0, 0, 0, 0))
ImageDraw.Draw(glow).ellipse((CX - D // 2 - 26, TOP - 26, CX + D // 2 + 26, TOP + D + 26), fill=OR + (150,))
img = Image.alpha_composite(img, glow.filter(ImageFilter.GaussianBlur(28)))
img.paste(logo, (CX - D // 2, TOP), mask)
ring = Image.new('RGBA', (W * 2, H * 2), (0, 0, 0, 0))   # золотой ободок как у монет
rd = ImageDraw.Draw(ring)
box = lambda p: ((CX - D // 2 - p) * 2, (TOP - p) * 2, (CX + D // 2 + p) * 2, (TOP + D + p) * 2)
rd.ellipse(box(9), outline=(176, 124, 30, 255), width=22)
rd.ellipse(box(6), outline=(255, 214, 110, 255), width=10)
img = Image.alpha_composite(img, ring.resize((W, H), Image.LANCZOS))

d = ImageDraw.Draw(img)


def wrap(text, font, maxw):
    lines, cur = [], ''
    for w in text.split():
        t = (cur + ' ' + w).strip()
        if d.textlength(t, font=font) <= maxw:
            cur = t
        else:
            lines.append(cur); cur = w
    return lines + [cur]


def shadow_text(xy, text, font, fill, anchor='la'):
    sh = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(sh).text((xy[0], xy[1] + 4), text, font=font, fill=(0, 0, 0, 220), anchor=anchor)
    global img, d
    img = Image.alpha_composite(img, sh.filter(ImageFilter.GaussianBlur(6)))
    d = ImageDraw.Draw(img)
    d.text(xy, text, font=font, fill=fill, anchor=anchor)


def rich_center(y, parts, font):
    """Строка по центру из кусков разного цвета."""
    total = sum(d.textlength(t, font=font) for t, _ in parts)
    x = (W - total) / 2
    for t, c in parts:
        shadow_text((x, y), t, font, c)
        x += d.textlength(t, font=font)


# ---- заголовок (текст владельца без изменений) ----
y = TOP + D + 34
hf = F(800, 56)
for line in ['Успей стать первым', 'арендодателем в своём городе', 'на самых выгодных условиях']:
    if 'первым' in line:
        rich_center(y, [('Успей стать ', TX), ('первым', OR)], hf)
    else:
        rich_center(y, [(line, TX)], hf)
    y += 64

# ---- рекламный текст ----
y += 30
rich_center(y, [('Ваш инструмент простаивает? ', GR), ('Пусть зарабатывает!', GOLD)], F(700, 36))
y += 60

rows = [('1₽', 'клиент написал вам'), ('1%', 'с аренды нашего клиента'), ('1₽', 'клиент позвонил вам')]
rf, cf = F(700, 38), F(900, 27)
rw = max(d.textlength(t, font=rf) for _, t in rows) + 96
x0 = (W - rw) / 2
for coin, t in rows:
    cy = y + 22
    d.ellipse((x0, cy - 34, x0 + 68, cy + 34), fill=(201, 146, 40), outline=(255, 214, 110), width=4)
    d.text((x0 + 34, cy + 1), coin, font=cf, fill=(60, 36, 4), anchor='mm')
    shadow_text((x0 + 96, cy), t, rf, TX, anchor='lm')
    y += 74

y += 0
rich_center(y, [('Без абонплаты. ', TX), ('Платите, только когда пришёл клиент.', GR)], F(500, 32))
y += 50
rich_center(y, [('Эти условия — ', TX), ('навсегда', OR), (', но только для первых.', TX)], F(800, 36))
y += 64

# ---- кнопка-призыв ----
cta, cfnt = 'Стать арендодателем', F(800, 38)
tw = d.textlength(cta, font=cfnt)
bw, bh = tw + 170, 78
bx, by = (W - bw) / 2, y
pill = Image.new('RGBA', (W, H), (0, 0, 0, 0))
ImageDraw.Draw(pill).rounded_rectangle((bx, by, bx + bw, by + bh), radius=bh // 2, fill=OR + (170,))
img = Image.alpha_composite(img, pill.filter(ImageFilter.GaussianBlur(18)))
d = ImageDraw.Draw(img)
d.rounded_rectangle((bx, by, bx + bw, by + bh), radius=bh // 2, fill=OR)
tx = bx + 55
d.text((tx, by + bh / 2 + 1), cta, font=cfnt, fill=(16, 18, 23), anchor='lm')
ax, ay = tx + tw + 22, by + bh / 2     # стрелка рисуется линиями (в шрифте нет →)
d.line([(ax, ay), (ax + 34, ay)], fill=(16, 18, 23), width=6)
d.polygon([(ax + 46, ay), (ax + 28, ay - 14), (ax + 28, ay + 14)], fill=(16, 18, 23))
y = by + bh + 18
rich_center(y, [('eclipsru.github.io/Arenda-Nvk', GR)], F(500, 28))
print('низ контента:', y + 34, 'из', H)

out = img.convert('RGB').resize((1080, 1350), Image.LANCZOS)
out.save(ROOT + 'promo/iva-promo-1-1-1.png', optimize=True)
out.save(ROOT + 'promo/iva-promo-1-1-1.jpg', quality=93)
