# Вторая, «завораживающая» версия рекламы «1 ₽ · 1% · 1 ₽» (4:5, 1080x1350).
# Фон: promo/coins-bg-2.png (сгенерирован), логотип: iva_logo.png.
# Шрифт Montserrat (OFL) из npm-пакета @fontsource/montserrat, собран в /tmp/fnt/M*.ttf.
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageChops

ROOT = '/home/user/Arenda-Nvk/'
W, H = 1360, 1700
SHIFT = 148                                   # поднимаем сцену, чтобы влез текст
K = W / 1122                                  # масштаб исходного фона
OR, TX, GR = (255, 143, 31), (246, 242, 234), (206, 198, 184)
F = lambda w, s: ImageFont.truetype('/tmp/fnt/M%d.ttf' % w, s)

src = Image.open(ROOT + 'promo/coins-bg-2.png').convert('RGB').resize((W, round(1402 * K)), Image.LANCZOS)
bg = Image.new('RGB', (W, H))
bg.paste(src.crop((0, SHIFT, W, src.height)), (0, 0))
floor = src.crop((0, src.height - SHIFT - 40, W, src.height)).transpose(Image.FLIP_TOP_BOTTOM)
bg.paste(floor, (0, src.height - SHIFT - 40))
img = bg.convert('RGBA')

# мягкое затемнение под текстом (свет по краям остаётся)
shade = Image.new('L', (W, H), 0)
sd = ImageDraw.Draw(shade)
for y in range(780, H):
    sd.line([(0, y), (W, y)], fill=int(min(185, (y - 780) * 0.62)))
img = Image.composite(Image.new('RGBA', (W, H), (8, 9, 13, 255)), img, shade)


def gold_fill(size, top=(255, 246, 214), mid=(255, 200, 92), bot=(214, 128, 26)):
    g = Image.new('RGBA', size)
    gd = ImageDraw.Draw(g)
    for y in range(size[1]):
        t = y / max(1, size[1] - 1)
        a, b, u = (top, mid, t * 2) if t < .5 else (mid, bot, (t - .5) * 2)
        gd.line([(0, y), (size[0], y)], fill=tuple(int(a[i] + (b[i] - a[i]) * u) for i in range(3)) + (255,))
    return g


def glow(layer, radius, times=1):
    global img
    for _ in range(times):
        img = Image.alpha_composite(img, layer.filter(ImageFilter.GaussianBlur(radius)))


# ---- логотип-медальон того же размера, что монеты ----
D = round(318 * K)
CX, CY = round(566 * K), round(668 * K) - SHIFT
TOP = CY - D // 2
halo = Image.new('RGBA', (W, H), (0, 0, 0, 0))
ImageDraw.Draw(halo).ellipse((CX - D // 2 - 40, TOP - 40, CX + D // 2 + 40, TOP + D + 40), fill=(255, 160, 40, 170))
glow(halo, 40)
logo = Image.open(ROOT + 'iva_logo.png').convert('RGBA')
s = int(min(logo.size) * 0.80)
l0 = ((logo.width - s) // 2, (logo.height - s) // 2)
logo = logo.crop((l0[0], l0[1], l0[0] + s, l0[1] + s)).resize((D, D), Image.LANCZOS)
mask = Image.new('L', (D * 4, D * 4), 0)
ImageDraw.Draw(mask).ellipse((0, 0, D * 4 - 1, D * 4 - 1), fill=255)
mask = mask.resize((D, D), Image.LANCZOS)
img.paste(logo, (CX - D // 2, TOP), mask)
# толстый золотой ободок с градиентом, как у монет
R = 2
rim_m = Image.new('L', (W * R, H * R), 0)
rmd = ImageDraw.Draw(rim_m)
rmd.ellipse(((CX - D // 2 - 16) * R, (TOP - 16) * R, (CX + D // 2 + 16) * R, (TOP + D + 16) * R), fill=255)
rmd.ellipse(((CX - D // 2 + 4) * R, (TOP + 4) * R, (CX + D // 2 - 4) * R, (TOP + D - 4) * R), fill=0)
rim_m = rim_m.resize((W, H), Image.LANCZOS)
rim = Image.new('RGBA', (W, H), (0, 0, 0, 0))
rim.paste(gold_fill((D + 40, D + 40), (255, 250, 220), (240, 176, 60), (150, 90, 12)), (CX - D // 2 - 20, TOP - 20))
rim.putalpha(ImageChops.multiply(rim.getchannel('A'), rim_m))
img = Image.alpha_composite(img, rim)
hl = Image.new('RGBA', (W * R, H * R), (0, 0, 0, 0))             # тонкие линии рельефа ободка
hd = ImageDraw.Draw(hl)
hd.ellipse(((CX - D // 2 - 16) * R, (TOP - 16) * R, (CX + D // 2 + 16) * R, (TOP + D + 16) * R), outline=(255, 236, 170, 230), width=3 * R)
hd.ellipse(((CX - D // 2 + 4) * R, (TOP + 4) * R, (CX + D // 2 - 4) * R, (TOP + D - 4) * R), outline=(110, 64, 6, 255), width=3 * R)
img = Image.alpha_composite(img, hl.resize((W, H), Image.LANCZOS))

d = ImageDraw.Draw(img)


def text_layer(parts, font, y, fills, center=True, x=None, anchor='la'):
    """Рисует строку из кусков: fills — 'gold' (градиент) или цвет. Возвращает ширину."""
    total = sum(d.textlength(t, font=font) for t, _ in parts)
    x = (W - total) / 2 if center else x
    asc, desc = font.getmetrics()
    sh = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    gl = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    out = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    xx = x
    for t, c in parts:
        ImageDraw.Draw(sh).text((xx, y + 5), t, font=font, fill=(0, 0, 0, 235), anchor=anchor)
        m = Image.new('L', (W, H), 0)
        ImageDraw.Draw(m).text((xx, y), t, font=font, fill=255, anchor=anchor)
        if c == 'gold' or c == 'fire':
            g = Image.new('RGBA', (W, H), (0, 0, 0, 0))
            pal = ((255, 248, 220), (255, 205, 100), (222, 136, 30)) if c == 'gold' else ((255, 226, 150), (255, 150, 40), (232, 92, 10))
            g.paste(gold_fill((int(d.textlength(t, font=font)) + 4, asc + desc), *pal), (int(xx), int(y)))
            g.putalpha(m)
            out = Image.alpha_composite(out, g)
            ImageDraw.Draw(gl).text((xx, y), t, font=font, fill=(255, 150, 30, 150), anchor=anchor)
        else:
            col = Image.new('RGBA', (W, H), c + (255,))
            col.putalpha(m)
            out = Image.alpha_composite(out, col)
        xx += d.textlength(t, font=font)
    return sh, gl, out, total


def put(parts, font, y, **kw):
    global img, d
    sh, gl, out, total = text_layer(parts, font, y, None, **kw)
    img = Image.alpha_composite(img, sh.filter(ImageFilter.GaussianBlur(7)))
    img = Image.alpha_composite(img, gl.filter(ImageFilter.GaussianBlur(16)))
    img = Image.alpha_composite(img, out)
    d = ImageDraw.Draw(img)
    return total


# ---- заголовок (текст владельца без изменений) ----
y = TOP + D + 36
hf = F(900, 60)
put([('Успей стать ', 'gold'), ('первым', 'fire')], hf, y); y += 66
put([('арендодателем в своём городе', 'gold')], hf, y); y += 66
put([('на самых выгодных условиях', 'gold')], hf, y); y += 80

put([('Ваш инструмент простаивает? ', GR), ('Пусть зарабатывает!', (255, 206, 110))], F(700, 36), y)
y += 62

# ---- стеклянная карточка с тремя монетками ----
rows = [('1₽', 'клиент написал вам'), ('1%', 'с аренды нашего клиента'), ('1₽', 'клиент позвонил вам')]
rf, cf = F(700, 39), F(900, 26)
inner = max(d.textlength(t, font=rf) for _, t in rows) + 100
note_w = d.textlength('Без абонплаты. Платите, только когда пришёл клиент.', font=F(500, 29))
cw, ch = max(inner + 120, note_w + 80), 3 * 70 + 90
cx0, cy0 = (W - cw) / 2, y
blur = img.crop((int(cx0), int(cy0), int(cx0 + cw), int(cy0 + ch))).filter(ImageFilter.GaussianBlur(18))
cm = Image.new('L', blur.size, 0)
ImageDraw.Draw(cm).rounded_rectangle((0, 0, blur.width - 1, blur.height - 1), radius=34, fill=255)
img.paste(blur, (int(cx0), int(cy0)), cm)
glass = Image.new('RGBA', (W, H), (0, 0, 0, 0))
gd = ImageDraw.Draw(glass)
gd.rounded_rectangle((cx0, cy0, cx0 + cw, cy0 + ch), radius=34, fill=(14, 12, 10, 150), outline=(255, 190, 90, 140), width=2)
img = Image.alpha_composite(img, glass)
d = ImageDraw.Draw(img)
ry = cy0 + 30
x0 = (W - inner) / 2
for coin, t in rows:
    c = ry + 26
    cg = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(cg).ellipse((x0 - 6, c - 40, x0 + 74, c + 40), fill=(255, 170, 50, 160))
    glow(cg, 12)
    coinimg = gold_fill((70, 70), (255, 240, 190), (236, 172, 56), (170, 104, 18))
    cmask = Image.new('L', (280, 280), 0)
    ImageDraw.Draw(cmask).ellipse((0, 0, 279, 279), fill=255)
    img.paste(coinimg, (int(x0), int(c - 35)), cmask.resize((70, 70), Image.LANCZOS))
    d = ImageDraw.Draw(img)
    d.ellipse((x0 + 5, c - 30, x0 + 65, c + 30), outline=(150, 92, 14), width=2)
    d.text((x0 + 35, c + 1), coin, font=cf, fill=(70, 40, 4), anchor='mm')
    put([(t, TX)], rf, c, center=False, x=x0 + 100, anchor='lm')
    ry += 70
put([('Без абонплаты. ', TX), ('Платите, только когда пришёл клиент.', GR)], F(500, 29), ry + 8)
y = cy0 + ch + 26

put([('Эти условия — ', TX), ('навсегда', 'fire'), (', но только для первых.', TX)], F(800, 37), y)
y += 62

# ---- кнопка-призыв ----
cta, cfnt = 'Стать арендодателем', F(800, 38)
tw = d.textlength(cta, font=cfnt)
bw, bh = tw + 170, 80
bx, by = (W - bw) / 2, y
gl = Image.new('RGBA', (W, H), (0, 0, 0, 0))
ImageDraw.Draw(gl).rounded_rectangle((bx - 6, by - 6, bx + bw + 6, by + bh + 6), radius=bh, fill=(255, 150, 30, 200))
glow(gl, 22)
pill = Image.new('RGBA', (W, H), (0, 0, 0, 0))
pill.paste(gold_fill((int(bw) + 2, bh), (255, 214, 120), (255, 150, 34), (226, 104, 10)), (int(bx), int(by)))
pm = Image.new('L', (W, H), 0)
ImageDraw.Draw(pm).rounded_rectangle((bx, by, bx + bw, by + bh), radius=bh // 2, fill=255)
pill.putalpha(pm)
img = Image.alpha_composite(img, pill)
d = ImageDraw.Draw(img)
tx = bx + 55
d.text((tx, by + bh / 2 + 1), cta, font=cfnt, fill=(20, 14, 6), anchor='lm')
ax, ay = tx + tw + 22, by + bh / 2
d.line([(ax, ay), (ax + 34, ay)], fill=(20, 14, 6), width=6)
d.polygon([(ax + 46, ay), (ax + 28, ay - 14), (ax + 28, ay + 14)], fill=(20, 14, 6))
y = by + bh + 16
put([('eclipsru.github.io/Arenda-Nvk', GR)], F(500, 27), y)
print('низ контента:', y + 34, 'из', H, '| медальон', D, 'px')

out = img.convert('RGB').resize((1080, 1350), Image.LANCZOS)
out.save(ROOT + 'promo/iva-promo-1-1-1-v2.png', optimize=True)
out.save(ROOT + 'promo/iva-promo-1-1-1-v2.jpg', quality=93)
