#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Единый справочник городов проекта «Ива» (этап П2).

Задача: убрать главный источник будущих ошибок — «два списка городов».
Источник правды по-прежнему один: массив CITIES в assets/data.js (его видят
страницы сайта). Этот скрипт делает из него машинно-читаемый tools/cities.json
для серверных задач (база прокатов, выгрузки, проверки).

Запуск:
    python3 tools/export_cities.py           # перезаписать tools/cities.json
    python3 tools/export_cities.py --check   # только проверить, ничего не писать
                                             # (ненулевой код возврата = расхождение)

Принципы:
  * никаких выдуманных данных: население/координаты/СНТ не заполняются,
    пока не взяты из открытого источника (этап П11);
  * файл детерминированный — один и тот же вход даёт байт в байт тот же выход,
    поэтому расхождение всегда видно в diff.
"""
import json
import os
import re
import sys
import unicodedata

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA_JS = os.path.join(ROOT, "assets", "data.js")
OUT_JSON = os.path.join(ROOT, "tools", "cities.json")

# Первые города списка идут до первого заголовка-комментария; все они —
# города Ростовской области (домашний регион проекта), поэтому подписываем честно.
DEFAULT_REGION = "Ростовская область"


def read_cities_with_regions():
    """Возвращает список пар (город, регион) в порядке следования в data.js."""
    with open(DATA_JS, encoding="utf-8") as f:
        src = f.read()

    m = re.search(r"CITIES\s*=\s*\[", src)
    if not m:
        raise SystemExit("Не найден массив CITIES в assets/data.js")

    # ищем закрывающую скобку массива
    start = m.end() - 1
    depth = 0
    end = None
    for i in range(start, len(src)):
        if src[i] == "[":
            depth += 1
        elif src[i] == "]":
            depth -= 1
            if depth == 0:
                end = i
                break
    if end is None:
        raise SystemExit("Не найден конец массива CITIES в assets/data.js")

    block = src[start + 1:end]
    pairs = []
    region = DEFAULT_REGION
    for line in block.splitlines():
        stripped = line.strip()
        if stripped.startswith("//"):
            region = stripped.lstrip("/").strip()
            continue
        for name in re.findall(r"'([^']+)'", stripped):
            pairs.append((name, region))
    return pairs


# Транслитерация для мягких ссылок (slug). Только латиница, цифры и дефис.
TRANSLIT = {
    "а": "a", "б": "b", "в": "v", "г": "g", "д": "d", "е": "e", "ё": "e",
    "ж": "zh", "з": "z", "и": "i", "й": "y", "к": "k", "л": "l", "м": "m",
    "н": "n", "о": "o", "п": "p", "р": "r", "с": "s", "т": "t", "у": "u",
    "ф": "f", "х": "h", "ц": "c", "ч": "ch", "ш": "sh", "щ": "sch",
    "ъ": "", "ы": "y", "ь": "", "э": "e", "ю": "yu", "я": "ya",
}


def slugify(name):
    """«Санкт-Петербург» -> sankt-peterburg, «Орёл» -> orel."""
    s = name.lower().replace("ё", "е")
    out = []
    for ch in s:
        if ch in TRANSLIT:
            out.append(TRANSLIT[ch])
        elif ch.isalnum() and ord(ch) < 128:
            out.append(ch)
        else:
            out.append("-")
    slug = re.sub(r"-{2,}", "-", "".join(out)).strip("-")
    # страховка от экзотики: если после чистки пусто — берём нормализованную форму
    if not slug:
        slug = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode().lower()
    return slug


def build():
    pairs = read_cities_with_regions()
    cities = []
    seen = {}
    for name, region in pairs:
        slug = slugify(name)
        if slug in seen:
            raise SystemExit(f"Совпадение ссылок (slug): «{name}» и «{seen[slug]}» -> {slug}")
        seen[slug] = name
        cities.append({
            "name": name,
            "slug": slug,
            "region": region,
            # Поля ниже намеренно пустые: заполняются только из открытых источников
            # на этапе П11. Выдумывать числа запрещено.
            "population": None,
            "lat": None,
            "lon": None,
            "has_snt": None,
        })
    return {
        "version": 1,
        "source": "assets/data.js:CITIES",
        "note": "Сгенерировано tools/export_cities.py — править руками нельзя. "
                "Единственный источник правды по городам — assets/data.js.",
        "cities": cities,
    }


def dump(payload):
    return json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=False) + "\n"


def main():
    check_only = "--check" in sys.argv
    payload = build()
    text = dump(payload)
    current = None
    if os.path.exists(OUT_JSON):
        with open(OUT_JSON, encoding="utf-8") as f:
            current = f.read()

    if check_only:
        if current != text:
            print("РАСХОЖДЕНИЕ: tools/cities.json не совпадает с assets/data.js")
            print("Починить: python3 tools/export_cities.py")
            return 1
        print(f"Списки городов совпадают: {len(payload['cities'])} городов")
        return 0

    if current == text:
        print(f"Без изменений: {len(payload['cities'])} городов")
        return 0

    with open(OUT_JSON, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"Записано: {OUT_JSON} ({len(payload['cities'])} городов)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
