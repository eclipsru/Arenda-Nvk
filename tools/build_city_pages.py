#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Страницы городов (этап П3, первый шаг): генератор статических страниц.

Что делает: из справочника прокатов собирает страницы вида city/<ссылка>.html —
«Аренда инструмента — <город>»: пункты проката, заявка на подбор, переход в каталог
и предложение сдать инструмент. Дизайн — существующий (assets/theme.css), без новых
стилей: страницы используют те же переменные и компоненты, что и остальной сайт.

Принципы (из плана этапа):
  • генерируем только города, где есть содержимое; остальные — по флагу --all и
    с запретом индексации (защита от «тонких страниц»);
  • ничего не выдумываем: только то, что есть в справочнике (снимок базы);
  • скрытые по просьбе владельца карточки (status hidden/declined) на страницы не попадают.

Запуск:
    python3 tools/build_city_pages.py                       # все города с содержимым
    python3 tools/build_city_pages.py --only rostov-na-donu,moscow   # только указанные
    python3 tools/build_city_pages.py --check               # проверить, ничего не писать
    python3 tools/build_city_pages.py --all                 # включая города без данных (noindex)
"""
import datetime
import json
import os
import re
import sys
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from export_cities import slugify  # та же транслитерация, что в справочнике городов

with open(os.path.join(ROOT, "tools", "city_aliases.json"), encoding="utf-8") as _f:
    CITY_ALIASES = {k: v for k, v in json.load(_f).items() if not k.startswith("_")}
CITIES_JSON = os.path.join(ROOT, "tools", "cities.json")
EXISTING_JSON = os.path.join(ROOT, "tools", "directory_existing.json")
TEMPLATE = os.path.join(ROOT, "tools", "city_template.html")
# Куда писать страницы (переопределяется для проверок: IVA_OUT=/tmp/...)
OUT_DIR = os.environ.get("IVA_OUT") or os.path.join(ROOT, "city")
SITE = "https://eclipsru.github.io/Arenda-Nvk"
LISTINGS_JSON = os.environ.get("IVA_LISTINGS") or os.path.join(ROOT, "tools", "listings_by_city.json")
SITEMAP = os.environ.get("IVA_SITEMAP") or os.path.join(ROOT, "sitemap.xml")
# Публичные значения (те же, что в коде сайта assets/sb.js). Только чтение.
SUPABASE_URL = "https://wdxdeatphizclskfmfxi.supabase.co"
SUPABASE_KEY = "sb_publishable_dtRaEHNNPBFbHFvg8hw9iA_FqJSz9BE"

# Наш телефон (тот же, что в подвале сайта). Через него идёт заявка на подбор.
OUR_PHONE_TEL = "+79081732475"
OUR_PHONE_SHOW = "+7 (908) 173-24-75"

# Показывать телефоны пунктов проката?
#   False (по умолчанию) — решение владельца (вариант 5): телефоны пунктов НЕ публикуем,
#                          вместо них кнопка «Узнать наличие и цену», которая ведёт к нам.
#   True  — вернуть телефоны: python3 tools/build_city_pages.py --with-phones
SHOW_PHONES = False
SOURCE_DATE = "26.09.2026"
LANGLE = {"new": "новая", "verified": "проверена"}


def plural(n, one, few, many):
    n = abs(int(n)) % 100
    n1 = n % 10
    if 11 <= n <= 14:
        return many
    if n1 == 1:
        return one
    if 2 <= n1 <= 4:
        return few
    return many


def fmt_phone(p):
    """79001295454 -> +7 (900) 129-54-54"""
    d = re.sub(r"\D", "", p or "")
    if len(d) == 11 and d[0] in "78":
        d = "7" + d[1:]
    if len(d) != 11:
        return (p or "").strip()
    return f"+7 ({d[1:4]}) {d[4:7]}-{d[7:9]}-{d[9:11]}"


def esc(v):
    return (str(v or "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace('"', "&quot;"))


def clickable_status(status):
    return status not in ("hidden", "declined")


def load():
    with open(CITIES_JSON, encoding="utf-8") as f:
        cities = json.load(f)["cities"]
    with open(EXISTING_JSON, encoding="utf-8") as f:
        snapshot = json.load(f)
    return cities, snapshot


def refresh_listings():
    """Сколько активных объявлений арендодателей по городам (только чтение).

    Город объявления — поле pickup_city (так же считает и сайт в assets/sb.js)."""
    url = (f"{SUPABASE_URL}/rest/v1/tools"
           "?select=id,name,pickup_city,delivery&status=eq.active&active=eq.true&limit=1000")
    req = urllib.request.Request(url, headers={"apikey": SUPABASE_KEY,
                                               "Authorization": f"Bearer {SUPABASE_KEY}"})
    with urllib.request.urlopen(req, timeout=30) as r:
        rows = json.load(r)
    by_city = {}
    for row in rows:
        city = (row.get("pickup_city") or "").strip()
        if not city:
            continue
        by_city[city] = by_city.get(city, 0) + 1
    payload = {
        "_note": "Сколько активных объявлений арендодателей по городам. Обновлять: "
                 "python3 tools/build_city_pages.py --refresh-listings",
        "taken_at": __import__("datetime").date.today().isoformat(),
        "total": len(rows),
        "by_city": by_city,
    }
    with open(LISTINGS_JSON, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=1)
    print(f"Объявления арендодателей: всего {len(rows)}, городов с объявлениями — {len(by_city)}")
    return payload


def load_listings():
    if not os.path.exists(LISTINGS_JSON):
        return {"total": 0, "by_city": {}, "taken_at": ""}
    with open(LISTINGS_JSON, encoding="utf-8") as f:
        return json.load(f)


def directory_by_city(snapshot):
    by_city = {}
    for r in snapshot["rows"]:
        if not clickable_status(r.get("status", "new")):
            continue
        name = CITY_ALIASES.get(r["city"].strip(), r["city"].strip())
        by_city.setdefault(name, []).append(r)
    for city in by_city:
        by_city[city].sort(key=lambda r: r["name"].lower())
    return by_city


def card_html(entry):
    """Карточка пункта проката: данные, путь к заявке и приглашение подключиться.

    Телефоны пунктов НЕ публикуем (решение владельца, вариант 5): клиент не уходит
    к прокату «мимо кассы», а получает помощь в подборе. Вернуть телефоны:
    python3 tools/build_city_pages.py --with-phones
    """
    source = esc(entry.get("source") or "открытые справочники")
    address = esc(entry.get("address") or "")
    addr_line = (f'<div style="font-size:13px;color:var(--gr);margin-top:4px">{address}</div>'
                 if address.strip() else '')
    invite = (
        '<div style="flex-basis:100%;margin-top:6px">'
          '<a href="landlord-register.html" style="font-size:12.5px;color:var(--or)">'
            'Это ваш прокат? Подключитесь — разместим объявление бесплатно'
          '</a>'
        '</div>'
    )
    if SHOW_PHONES:
        phone = fmt_phone(entry["phone"])
        tel = "+7" + re.sub(r"\D", "", entry["phone"])[-10:]
        action = (
            '<div style="display:flex;gap:10px;flex-wrap:wrap;align-items:center">'
              f'<a href="tel:{tel}" style="font-size:14.5px;font-weight:600;color:var(--tx);white-space:nowrap">{phone}</a>'
              f'<a class="btn hot sm" href="tel:{tel}" style="white-space:nowrap">Позвонить</a>'
            '</div>'
        )
        note = ''
    else:
        action = (
            '<div style="display:flex;gap:10px;flex-wrap:wrap;align-items:center">'
              f'<a class="btn hot sm" href="tel:{OUR_PHONE_TEL}" data-lead="1" '
              'style="white-space:nowrap">Узнать наличие и цену</a>'
            '</div>'
        )
        note = (
            '<div style="flex-basis:100%;margin-top:2px">'
              '<span style="font-size:12.5px;color:var(--g2)">'
              'Позвоните нам — уточним наличие, цену и залог за вас.'
              '</span>'
            '</div>'
        )
    return (
        '<div data-prokat-card="1" style="margin-top:12px;padding:14px 16px;background:var(--card);'
        'border:1px solid var(--line);border-radius:var(--r);display:flex;align-items:flex-start;'
        'justify-content:space-between;gap:14px;flex-wrap:wrap">'
          '<div style="min-width:240px">'
            f'<b style="font-size:15px">{esc(entry["name"])}</b>'
            + addr_line +
            f'<div style="font-size:12px;color:var(--g2);margin-top:6px">источник: {source}</div>'
          '</div>'
          + action + note + invite +
        '</div>'
    )


def neighbors_html(current_slug, cities, by_city, generated_slugs):
    """Ссылки на другие города: сначала свой регион, потом по числу контактов."""
    current = next((c for c in cities if c["slug"] == current_slug), None)
    others = [c for c in cities if c["slug"] in generated_slugs and c["slug"] != current_slug]
    region = current["region"] if current else ""
    same = [c for c in others if c["region"] == region and by_city.get(c["name"])]
    rest = [c for c in others if c not in same and by_city.get(c["name"])]
    rest.sort(key=lambda c: -len(by_city.get(c["name"], [])))
    picked = (same + rest)[:12]
    if not picked:
        return '<span style="font-size:13px;color:var(--g2)">Пока не добавлены</span>'
    return "".join(
        f'<a class="btn sec sm" href="city/{c["slug"]}.html">{esc(c["name"])}</a>' for c in picked
    )


def jsonld(city_name, slug, entries):
    items = []
    for i, e in enumerate(entries, 1):
        item = {
            "@type": "LocalBusiness",
            "name": e["name"],
            "address": {"@type": "PostalAddress", "addressLocality": e.get("city", "")},
        }
        if SHOW_PHONES:
            # Телефон в микроразметке — это тоже публикация телефона: только в режиме --with-phones
            item["telephone"] = fmt_phone(e["phone"])
        items.append({
            "@type": "ListItem",
            "position": i,
            "item": item,
        })
    payload = {
        "@context": "https://schema.org",
        "@graph": [
            {
                "@type": "BreadcrumbList",
                "itemListElement": [
                    {"@type": "ListItem", "position": 1, "name": "Главная", "item": SITE + "/"},
                    {"@type": "ListItem", "position": 2, "name": "Каталог", "item": SITE + "/catalog.html"},
                    {"@type": "ListItem", "position": 3, "name": city_name, "item": f"{SITE}/city/{slug}.html"},
                ],
            },
            {"@type": "ItemList", "name": f"Пункты проката — {city_name}", "itemListElement": items},
        ],
    }
    return '<script type="application/ld+json">' + json.dumps(payload, ensure_ascii=False) + "</script>"


def hub_page(targets, cities_by_slug, allow_index=False):
    """Страница «Аренда инструмента в городах России» — список городов с данными."""
    total_contacts = sum(len(e) for _, e in targets)
    groups = {}
    for city, entries in targets:
        groups.setdefault(city["region"], []).append((city, entries))
    order = ["Ростовская область", "Юг России", "Миллионники и крупные города РФ", "Другие города"]
    names = sorted(groups, key=lambda r: (order.index(r) if r in order else len(order), r))

    blocks = []
    for region in names:
        items = sorted(groups[region], key=lambda c: c[0]["name"])
        links = "".join(
            f'<a class="btn sec sm" href="city/{c["slug"]}.html">{esc(c["name"])}'
            f'<span style="color:var(--g2);font-weight:400"> · {len(e)}</span></a>'
            for c, e in items
        )
        blocks.append(
            '<div class="sect" style="padding-top:10px">'
            f'<div class="sect-h"><h2 style="font-size:17px">{esc(region)}</h2>'
            f'<span style="font-size:13px;color:var(--g2)">{len(items)} '
            f'{plural(len(items), "город", "города", "городов")}</span></div>'
            f'<div style="display:flex;flex-wrap:wrap;gap:8px">{links}</div>'
            "</div>"
        )

    robots = "index,follow" if allow_index else "noindex,follow"
    jsonld_payload = {
        "@context": "https://schema.org",
        "@type": "CollectionPage",
        "name": "Аренда инструмента в городах России",
        "url": f"{SITE}/city/",
    }
    return f"""<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="utf-8">
<base href="../">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Аренда инструмента в городах России — Ива</title>
<meta name="description" content="Пункты проката инструмента в {len(targets)} городах России: названия, адреса, источники. Выберите город — уточним наличие и цену и поможем взять инструмент в аренду.">
<meta name="robots" content="{robots}">
<meta name="theme-color" content="#101217">
<link rel="canonical" href="{SITE}/city/">
<link rel="icon" href="assets/favicon.svg" type="image/svg+xml">
<link rel="stylesheet" href="assets/theme.css?v=20260924-app-banner-v2">
<link rel="stylesheet" href="assets/geo.css?v=20260923-photon">
<script type="application/ld+json">{json.dumps(jsonld_payload, ensure_ascii=False)}</script>
</head>
<body>

<div id="hdr"></div>

<main class="page">
  <div class="wrap">
    <div class="sect" style="padding-bottom:0">
      <nav aria-label="Хлебные крошки" style="font-size:12.5px;color:var(--g2);margin-bottom:10px">
        <a href="index.html" style="color:var(--g2)">Главная</a> ·
        <a href="catalog.html" style="color:var(--g2)">Каталог</a> ·
        <span style="color:var(--gr)">Города</span>
      </nav>
      <div class="sect-h"><h2>Аренда инструмента — города России</h2></div>
      <p style="color:var(--gr);font-size:14px;line-height:1.6;max-width:760px;margin:10px 0 0">
        {len(targets)} {plural(len(targets), "город", "города", "городов")}, {total_contacts}
        {plural(total_contacts, "пункт", "пункта", "пунктов")} проката — данные из открытых источников.
        Выберите город: увидите пункты рядом и сможете попросить нас уточнить наличие, цену и залог.
      </p>
    </div>
    {"".join(blocks)}
    <div class="sect">
      <div style="display:grid;gap:12px;grid-template-columns:repeat(auto-fit,minmax(260px,1fr))">
        <div style="padding:16px;background:var(--card);border:1px solid var(--line);border-radius:var(--r)">
          <b style="font-size:15px">Вашего города нет в списке?</b>
          <div style="font-size:13px;color:var(--gr);margin-top:6px;line-height:1.6">
            Позвоните нам — добавим прокаты из вашего города.
          </div>
          <a class="btn sec sm" href="tel:+79081732475" style="margin-top:12px;display:inline-block">+7 (908) 173-24-75</a>
        </div>
        <div style="padding:16px;background:var(--card);border:1px solid var(--line);border-radius:var(--r)">
          <b style="font-size:15px">Сдаёте инструмент?</b>
          <div style="font-size:13px;color:var(--gr);margin-top:6px;line-height:1.6">
            Разместите объявление бесплатно — его увидят в вашем городе.
          </div>
          <a class="btn hot sm" href="landlord-register.html" style="margin-top:12px;display:inline-block">Разместить объявление</a>
        </div>
      </div>
    </div>
  </div>
</main>

<div id="ftr"></div>
<div id="bnav"></div>

<script src="assets/data.js"></script>
<script src="assets/geo.js?v=20260923-photon"></script>
<script src="assets/email-guard.js?v=20260926-email"></script><script src="assets/sb.js?v=20260926-email"></script>
<script src="assets/app.js?v=20260924-apk-1012"></script>
<script>
(function(){{ mountChrome('catalog', ''); }})();
</script>
</body>
</html>
"""


def partners_block(city, listings_count, catalog_url):
    """Блок про объявления арендодателей — вверху страницы, выше справочника.**

    Логика приоритета: если в городе уже есть объявления «Ивы», человек сначала
    попадает к ним. Если их нет — честно говорим об этом и предлагаем два пути:
    сдать инструмент (для владельцев) или попросить помощь (для искателей).
    """
    if listings_count > 0:
        return (
            '<div style="margin-top:16px;padding:16px;background:var(--card);border:1px solid var(--or);'
            'border-radius:var(--r);display:flex;align-items:center;justify-content:space-between;'
            'gap:14px;flex-wrap:wrap">'
              '<div style="min-width:260px">'
                '<b style="font-size:15px">Инструмент от арендодателей «Ивы»</b>'
                f'<div style="font-size:13px;color:var(--gr);margin-top:6px;line-height:1.6">'
                  f'В этом городе уже {listings_count} '
                  f'{plural(listings_count, "объявление", "объявления", "объявлений")} — с фото, '
                  'ценой за сутки, залогом и доставкой. Смотрите их первыми.'
                '</div>'
              '</div>'
              f'<a class="btn hot sm" href="{catalog_url}" style="white-space:nowrap">Смотреть объявления</a>'
            '</div>'
        )
    return (
        '<div style="margin-top:16px;padding:16px;background:var(--card);border:1px solid var(--line);'
        'border-radius:var(--r);display:flex;align-items:center;justify-content:space-between;'
        'gap:14px;flex-wrap:wrap">'
          '<div style="min-width:260px">'
            '<b style="font-size:15px">Объявлений арендодателей здесь пока нет</b>'
            '<div style="font-size:13px;color:var(--gr);margin-top:6px;line-height:1.6">'
              'Ниже — справочник пунктов проката (они не подключены к «Иве»). '
              'Если сдаёте инструмент — разместите объявление, это бесплатно.'
            '</div>'
          '</div>'
          '<div style="display:flex;gap:8px;flex-wrap:wrap">'
            f'<a class="btn sec sm" href="{catalog_url}" style="white-space:nowrap">Каталог</a>'
            '<a class="btn hot sm" href="landlord-register.html" style="white-space:nowrap">Сдать инструмент</a>'
          '</div>'
        '</div>'
    )


def directory_block(city, entries, collapse):
    """Справочник пунктов проката.

    Когда у города есть объявления «Ивы», справочник свёрнут в раскрывающийся
    блок (приоритет объявлениям), но остаётся в тексте страницы — и для людей,
    которые хотят позвонить, и для поисковых систем.
    """
    count = len(entries)
    if not entries:
        cards = (
            '<div style="margin-top:12px;padding:16px;background:var(--card);border:1px solid var(--line);'
            'border-radius:var(--r);font-size:13.5px;color:var(--gr)">'
            'Знаете прокат в этом городе? Позвоните нам — добавим: '
            '<a href="tel:+79081732475" data-lead="1" style="color:var(--or)">+7 (908) 173-24-75</a>.'
            '</div>'
        )
    else:
        cards = "".join(card_html(e) for e in entries)

    count_line = (f'{count} {plural(count, "пункт", "пункта", "пунктов")}'
                  if count else "данных пока нет")
    head = (
        '<div class="sect-h">'
          f'<h2{" style=\"font-size:17px\"" if collapse else ""}>Пункты проката в городе</h2>'
          f'<span style="font-size:13px;color:var(--g2)">{count_line}</span>'
        '</div>'
        '<p style="font-size:13px;color:var(--g2);line-height:1.6;margin:8px 0 0;max-width:820px">'
          + ('Это справочник: пункты ниже пока не подключены к «Иве», поэтому заказ здесь не оформить — '
             'наличие, цену и залог уточняйте звонком.'
             if SHOW_PHONES else
             'Это справочник пунктов, которые пока не подключены к «Иве». Телефоны пунктов не публикуем: '
             'нажмите «Узнать наличие и цену» — уточним наличие, цену и залог за вас.')
          + '</p>'
    )
    tail = (
        f'<p style="font-size:12.5px;color:var(--g2);line-height:1.6;margin-top:16px;max-width:760px">'
        f'Данные — из открытых источников (сайты прокатов и справочники), собраны {SOURCE_DATE} '
        'и постепенно проверяются. Наличие и цену уточняйте по телефону. Если вы владелец точки '
        'и хотите исправить или убрать данные — позвоните нам: '
        '<a href="tel:+79081732475" data-lead="1" style="color:var(--or)">+7 (908) 173-24-75</a>.'
        '</p>'
        '<div style="margin-top:10px">'
        '<a href="landlord-register.html" style="font-size:12.5px;color:var(--or)">'
        'Добавить свой инструмент в объявления — бесплатно</a>'
        '</div>'
    )

    if collapse and entries:
        return (
            '<div class="sect">'
            + head +
            '<details style="margin-top:10px">'
              '<summary style="cursor:pointer;font-size:14px;color:var(--or);padding:6px 0">'
                f'Показать справочник ({count} {plural(count, "пункт", "пункта", "пунктов")}, не подключены к «Иве»)'
              '</summary>'
            + cards + tail +
            '</details>'
            '</div>'
        )
    return '<div class="sect">' + head + cards + tail + '</div>'


def build_page(city, entries, template, generated_slugs, cities, by_city, allow_index=True,
               listings=None):
    count = len(entries)
    title = f"Аренда инструмента — {city['name']} | Ива"
    if count:
        description = (
            f"{city['name']}: {count} {plural(count, 'пункт', 'пункта', 'пунктов')} проката инструмента. "
            "Уточним наличие и цену и поможем взять инструмент в аренду — Ива."
        )
        lead = (
            f"Собрали пункты и базы проката инструмента в этом городе — названия и адреса "
            f"из открытых источников. Позвоните нам: уточним наличие, цену и залог и подскажем, "
            f"где ближе и дешевле."
        )
        count_line = f"{count} {plural(count, 'пункт', 'пункта', 'пунктов')}"
        # Открыто для поиска по решению владельца; закрыть обратно — флаг --noindex.
        robots = "index,follow" if allow_index else "noindex,follow"
        cards = "".join(card_html(e) for e in entries)
    else:
        description = f"{city['name']}: пункты проката инструмента появятся здесь. А пока — каталог и заявка."
        lead = "Мы постепенно добавляем пункты проката по городам. Пока в этом городе данных нет."
        count_line = "данных пока нет"
        robots = "noindex,follow"  # тонкие страницы не отдаём в поиск
        cards = (
            '<div style="margin-top:12px;padding:16px;background:var(--card);border:1px solid var(--line);'
            'border-radius:var(--r);font-size:13.5px;color:var(--gr)">'
            'Знаете прокат в этом городе? Позвоните нам — добавим: '
            '<a href="tel:+79081732475" style="color:var(--or)">+7 (908) 173-24-75</a>.'
            '</div>'
        )

    listings = listings or {}
    listings_count = int(listings.get(city["name"], 0))
    catalog_url = "catalog.html?city=" + urllib.parse.quote(city["name"])

    html = template
    repl = {
        "{{TITLE}}": esc(title),
        "{{PARTNERS_BLOCK}}": partners_block(city, listings_count, catalog_url),
        "{{DIRECTORY}}": directory_block(city, entries, collapse=listings_count > 0),
        "{{CITY_JS}}": city["name"].replace("\\", "\\\\").replace("'", "\\'"),
        "{{DESCRIPTION}}": esc(description),
        "{{ROBOTS}}": robots,
        "{{CANONICAL}}": f"{SITE}/city/{city['slug']}.html",
        "{{JSONLD}}": jsonld(city["name"], city["slug"], entries),
        "{{CITY}}": esc(city["name"]),
        "{{LEAD}}": lead,
        "{{NEIGHBORS}}": neighbors_html(city["slug"], cities, by_city, generated_slugs),
    }
    for key, value in repl.items():
        html = html.replace(key, value)
    leftover = re.findall(r"\{\{[A-Z_]+\}\}", html)
    if leftover:
        raise SystemExit(f"в шаблоне остались незаполненные места: {leftover}")

    if not SHOW_PHONES:
        # Жёсткая проверка: ни один телефон пункта проката не должен попасть на готовую страницу
        # (ни в текст, ни в разметку, ни в ссылку). Это решение владельца, а не оформление.
        digits_only = re.sub(r"\D", "", html)
        for e in entries:
            p10 = re.sub(r"\D", "", str(e.get("phone") or ""))[-10:]
            if len(p10) == 10 and p10 in digits_only:
                raise SystemExit(
                    f"city/{city['slug']}.html: на странице остался телефон пункта проката — "
                    "публиковать телефоны нельзя (решение владельца). Проверить шаблон и тексты "
                    "или собрать страницы в режиме --with-phones.")
    return html


def write_sitemap(targets):
    """Обновляет sitemap.xml: статические страницы + список городов + каждая страница города.

    Без карты сайта поисковики находят новые страницы неделями; с ней — за дни.
    Файл пересобирается целиком, поэтому расхождений не будет.
    """
    today = datetime.date.today().isoformat()
    rows = [
        ("", "1.0", "daily"),
        ("catalog.html", "0.9", "daily"),
        ("city/", "0.8", "weekly"),
        ("app.html", "0.6", "monthly"),
        ("offer.html", "0.3", "yearly"),
    ]
    urls = [f'{SITE}/{path}' for path, _, _ in rows]
    for city, entries in targets:
        urls.append(f'{SITE}/city/{city["slug"]}.html')
        rows.append((f'city/{city["slug"]}.html', "0.7" if entries else "0.4", "weekly"))

    parts = ['<?xml version="1.0" encoding="UTF-8"?>',
             '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">']
    for path, priority, freq in rows:
        loc = f'{SITE}/{path}'
        parts.append(f'  <url><loc>{loc}</loc><lastmod>{today}</lastmod>'
                     f'<changefreq>{freq}</changefreq><priority>{priority}</priority></url>')
    parts.append('</urlset>')
    with open(SITEMAP, "w", encoding="utf-8") as f:
        f.write("\n".join(parts) + "\n")
    print(f"Карта сайта обновлена: {len(rows)} адресов (включая {len(targets)} страниц городов)")


def main():
    global SHOW_PHONES
    check_only = "--check" in sys.argv
    include_empty = "--all" in sys.argv
    # Решение владельца 01.10.2026: страницы городов открыты для поиска.
    # Закрыть обратно (например, на время правок) — флаг --noindex.
    allow_index = "--noindex" not in sys.argv
    # По умолчанию телефоны пунктов не публикуются (решение владельца, вариант 5).
    SHOW_PHONES = "--with-phones" in sys.argv
    only = None
    for arg in sys.argv:
        if arg.startswith("--only"):
            value = arg.split("=", 1)[1] if "=" in arg else sys.argv[sys.argv.index(arg) + 1]
            only = {s.strip() for s in value.split(",") if s.strip()}

    cities, snapshot = load()
    listings_data = refresh_listings() if "--refresh-listings" in sys.argv else load_listings()
    by_city_listings = listings_data.get("by_city", {})
    by_city = directory_by_city(snapshot)
    # В справочнике есть города, которых нет в списке сайта (например, Киров).
    # Для них тоже делаем страницы — под своим адресом, помечая как «другие города».
    known = {c["name"] for c in cities}
    for name in sorted(by_city):
        if name not in known:
            cities.append({"name": name, "slug": slugify(name), "region": "Другие города"})
            known.add(name)
    with open(TEMPLATE, encoding="utf-8") as f:
        template = f.read()

    targets = []
    for city in cities:
        entries = by_city.get(city["name"], [])
        if entries or include_empty:
            targets.append((city, entries))
    if only:
        targets = [(c, e) for c, e in targets if c["slug"] in only]

    generated_slugs = {c["slug"] for c, _ in targets}
    pages = {}
    for city, entries in targets:
        pages[city["slug"]] = build_page(city, entries, template, generated_slugs, cities, by_city,
                                          allow_index, listings=by_city_listings)
    pages["index"] = hub_page([(c, e) for c, e in targets if e], {c["slug"]: c for c, _ in targets}, allow_index)

    if check_only:
        problems = []
        for slug, html in pages.items():
            path = os.path.join(OUT_DIR, slug + ".html")
            if not os.path.exists(path):
                problems.append(f"нет страницы: city/{slug}.html")
                continue
            with open(path, encoding="utf-8") as f:
                if f.read() != html:
                    problems.append(f"страница устарела: city/{slug}.html")
        # Карта сайта должна содержать все страницы городов и хаб
        if os.path.exists(SITEMAP):
            sitemap = open(SITEMAP, encoding="utf-8").read()
            for slug in list(pages):
                url = f"{SITE}/city/" if slug == "index" else f"{SITE}/city/{slug}.html"
                if url not in sitemap:
                    problems.append(f"нет в карте сайта: {url}")
        if problems:
            for p in problems:
                print("РАСХОЖДЕНИЕ: " + p)
            print("Починить: python3 tools/build_city_pages.py")
            return 1
        print(f"Страницы городов совпадают с данными: {len(pages)}; карта сайта полная")
        return 0

    if not only:
        # Карта сайта — только при полной сборке: при --only она потеряла бы остальные города.
        write_sitemap([(c, e) for c, e in targets if e] if not include_empty else targets)

    os.makedirs(OUT_DIR, exist_ok=True)
    written = 0
    for slug, html in pages.items():
        with open(os.path.join(OUT_DIR, slug + ".html"), "w", encoding="utf-8") as f:
            f.write(html)
        written += 1
    with_entries = sum(1 for _, e in targets if e)
    mode = "отдаются в поиск" if allow_index else "закрыты от поиска (--noindex)"
    phones_mode = "телефоны пунктов показываются" if SHOW_PHONES else "телефоны пунктов не публикуются"
    print(f"Готово страниц городов: {written - 1} (с контактами: {with_entries}); "
          f"плюс страница-хаб city/index.html; режим: {mode}; {phones_mode}")
    if written <= 12:
        for city, entries in targets:
            print(f"   {city['name']}: {len(entries)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
