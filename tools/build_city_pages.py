#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Страницы городов (этап П3, первый шаг): генератор статических страниц.

Что делает: из справочника прокатов собирает страницы вида city/<ссылка>.html —
«Аренда инструмента — <город>»: контакты пунктов проката, телефоны, переход в каталог
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
import json
import os
import re
import sys
import urllib.parse

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from export_cities import slugify  # та же транслитерация, что в справочнике городов

with open(os.path.join(ROOT, "tools", "city_aliases.json"), encoding="utf-8") as _f:
    CITY_ALIASES = {k: v for k, v in json.load(_f).items() if not k.startswith("_")}
CITIES_JSON = os.path.join(ROOT, "tools", "cities.json")
EXISTING_JSON = os.path.join(ROOT, "tools", "directory_existing.json")
TEMPLATE = os.path.join(ROOT, "tools", "city_template.html")
OUT_DIR = os.path.join(ROOT, "city")
SITE = "https://eclipsru.github.io/Arenda-Nvk"
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
    """Карточка пункта проката: данные, телефон и приглашение подключиться."""
    phone = fmt_phone(entry["phone"])
    tel = "+7" + re.sub(r"\D", "", entry["phone"])[-10:]
    source = esc(entry.get("source") or "открытые справочники")
    address = esc(entry.get("address") or "")
    addr_line = (f'<div style="font-size:13px;color:var(--gr);margin-top:4px">{address}</div>'
                 if address.strip() else '')
    return (
        '<div style="margin-top:12px;padding:14px 16px;background:var(--card);'
        'border:1px solid var(--line);border-radius:var(--r);display:flex;align-items:flex-start;'
        'justify-content:space-between;gap:14px;flex-wrap:wrap">'
          '<div style="min-width:240px">'
            f'<b style="font-size:15px">{esc(entry["name"])}</b>'
            + addr_line +
            f'<div style="font-size:12px;color:var(--g2);margin-top:6px">источник: {source}</div>'
          '</div>'
          '<div style="display:flex;gap:10px;flex-wrap:wrap;align-items:center">'
            f'<a href="tel:{tel}" style="font-size:14.5px;font-weight:600;color:var(--tx);white-space:nowrap">{phone}</a>'
            f'<a class="btn hot sm" href="tel:{tel}" style="white-space:nowrap">Позвонить</a>'
          '</div>'
          '<div style="flex-basis:100%;margin-top:2px">'
            '<a href="landlord-register.html" style="font-size:12.5px;color:var(--or)">'
              'Это ваш прокат? Подключитесь — разместим объявление бесплатно'
            '</a>'
          '</div>'
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
        items.append({
            "@type": "ListItem",
            "position": i,
            "item": {
                "@type": "LocalBusiness",
                "name": e["name"],
                "telephone": fmt_phone(e["phone"]),
                "address": {"@type": "PostalAddress", "addressLocality": e.get("city", "")},
            },
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
<meta name="description" content="Пункты проката инструмента в {len(targets)} городах России: телефоны, адреса, источники. Выберите город и позвоните напрямую.">
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
        {plural(total_contacts, "пункт", "пункта", "пунктов")} проката с телефонами из открытых источников.
        Выберите город — увидите пункты проката и сможете позвонить напрямую.
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


def build_page(city, entries, template, generated_slugs, cities, by_city, allow_index=False):
    count = len(entries)
    title = f"Аренда инструмента — {city['name']} | Ива"
    if count:
        description = (
            f"{city['name']}: {count} {plural(count, 'пункт', 'пункта', 'пунктов')} проката инструмента "
            "с телефонами. Возьмите инструмент в аренду или сдайте свой — Ива."
        )
        lead = (
            f"Собрали пункты и базы проката инструмента в этом городе — телефоны из открытых источников. "
            f"Позвоните напрямую, чтобы уточнить наличие, цену и залог."
        )
        count_line = f"{count} {plural(count, 'пункт', 'пункта', 'пунктов')}"
        # В поиск страницы отдаём только после приёмки владельцем (флаг --index).
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

    html = template
    repl = {
        "{{TITLE}}": esc(title),
        "{{DESCRIPTION}}": esc(description),
        "{{ROBOTS}}": robots,
        "{{CANONICAL}}": f"{SITE}/city/{city['slug']}.html",
        "{{JSONLD}}": jsonld(city["name"], city["slug"], entries),
        "{{CITY}}": esc(city["name"]),
        "{{LEAD}}": lead,
        "{{COUNT_LINE}}": count_line,
        "{{CARDS}}": cards,
        "{{NEIGHBORS}}": neighbors_html(city["slug"], cities, by_city, generated_slugs),
        "{{CITY_CATALOG_URL}}": "catalog.html?city=" + urllib.parse.quote(city["name"]),
        "{{SOURCE_DATE}}": SOURCE_DATE,
    }
    for key, value in repl.items():
        html = html.replace(key, value)
    leftover = re.findall(r"\{\{[A-Z_]+\}\}", html)
    if leftover:
        raise SystemExit(f"в шаблоне остались незаполненные места: {leftover}")
    return html


def main():
    check_only = "--check" in sys.argv
    include_empty = "--all" in sys.argv
    allow_index = "--index" in sys.argv
    only = None
    for arg in sys.argv:
        if arg.startswith("--only"):
            value = arg.split("=", 1)[1] if "=" in arg else sys.argv[sys.argv.index(arg) + 1]
            only = {s.strip() for s in value.split(",") if s.strip()}

    cities, snapshot = load()
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
        pages[city["slug"]] = build_page(city, entries, template, generated_slugs, cities, by_city, allow_index)
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
        if problems:
            for p in problems:
                print("РАСХОЖДЕНИЕ: " + p)
            print("Починить: python3 tools/build_city_pages.py")
            return 1
        print(f"Страницы городов совпадают с данными: {len(pages)}")
        return 0

    os.makedirs(OUT_DIR, exist_ok=True)
    written = 0
    for slug, html in pages.items():
        with open(os.path.join(OUT_DIR, slug + ".html"), "w", encoding="utf-8") as f:
            f.write(html)
        written += 1
    with_entries = sum(1 for _, e in targets if e)
    mode = "отдаются в поиск" if allow_index else "пока не отдаются в поиск (приёмка)"
    print(f"Готово страниц городов: {written - 1} (с контактами: {with_entries}); "
          f"плюс страница-хаб city/index.html; режим: {mode}")
    if written <= 12:
        for city, entries in targets:
            print(f"   {city['name']}: {len(entries)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
