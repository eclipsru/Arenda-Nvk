#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Справочник прокатов (этап П2): дополнение базы из tools/real_bases.csv.

ВАЖНО (проверено 01.10.2026): таблица directory_landlords уже есть в боевой базе —
259 записей от 24.09.2026, и она используется в кабинете главного («Рассылка по базам
проката»). Поэтому этот инструмент НЕ создаёт таблицу и НЕ ввозит всё подряд, а считает
разницу: какие контакты из CSV в базе отсутствуют. Их и добавляет готовый SQL-файл —
одна вставка в SQL Editor, безопасная при повторном запуске.

Снимок «что уже есть в базе» лежит в tools/directory_existing.json. Обновить его
(только чтение из базы): python3 tools/build_directory_import.py --snapshot

Запуск:
    python3 tools/build_directory_import.py            # собрать файлы
    python3 tools/build_directory_import.py --check    # только проверить, не писать
    python3 tools/build_directory_import.py --report   # показать отчёт и выйти
    python3 tools/build_directory_import.py --snapshot # обновить снимок из базы (чтение)
"""
import csv
import json
import os
import re
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CSV_PATH = os.path.join(ROOT, "tools", "real_bases.csv")
EXISTING_JSON = os.path.join(ROOT, "tools", "directory_existing.json")
ALIASES_JSON = os.path.join(ROOT, "tools", "city_aliases.json")
MIGRATION_SQL = os.path.join(ROOT, "supabase", "migrations", "20261001_directory_landlords.sql")
OUT_SQL = os.path.join(ROOT, "supabase", "seed", "20261001_directory_landlords_import.sql")
OUT_BUNDLE = os.path.join(ROOT, "supabase", "seed", "20261001_directory_ALL_IN_ONE.sql")

# Публичные значения, которые и так лежат в коде сайта (assets/sb.js). Только чтение.
SUPABASE_URL = "https://wdxdeatphizclskfmfxi.supabase.co"
SUPABASE_KEY = "sb_publishable_dtRaEHNNPBFbHFvg8hw9iA_FqJSz9BE"

with open(ALIASES_JSON, encoding="utf-8") as f:
    CITY_ALIASES = {k: v for k, v in json.load(f).items() if not k.startswith("_")}


def digits(raw):
    return re.sub(r"\D", "", str(raw or ""))


def norm_phone_db(raw):
    """Формат базы: 11 цифр, без плюса (79001295454)."""
    d = digits(raw)
    if len(d) == 11 and d[0] in ("7", "8"):
        return "7" + d[1:]
    if len(d) == 10:
        return "7" + d
    return d


def phone10(raw):
    return digits(raw)[-10:]


def sql_str(value):
    return "'" + str(value).replace("'", "''") + "'"


def load_existing():
    with open(EXISTING_JSON, encoding="utf-8") as f:
        return json.load(f)


def refresh_snapshot():
    """Снимок из базы (только чтение, публичный ключ)."""
    url = f"{SUPABASE_URL}/rest/v1/directory_landlords?select=phone,city,name&order=created_at.asc&limit=2000"
    req = urllib.request.Request(url, headers={"apikey": SUPABASE_KEY, "Authorization": f"Bearer {SUPABASE_KEY}"})
    with urllib.request.urlopen(req, timeout=30) as r:
        rows = json.load(r)
    payload = {
        "_note": "Снимок справочника из боевой базы (только для повторяемой сборки файла дополнения). "
                 "Обновлять командой: python3 tools/build_directory_import.py --snapshot",
        "taken_at": __import__("datetime").date.today().isoformat(),
        "count": len(rows),
        "rows": [{"phone": r["phone"], "city": r["city"], "name": r["name"]} for r in rows],
    }
    with open(EXISTING_JSON, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=1)
    print(f"Снимок обновлён: {EXISTING_JSON} ({len(rows)} записей)")
    return payload


def read_rows():
    with open(CSV_PATH, encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def prepare():
    snapshot = load_existing()
    known = {phone10(r["phone"]) for r in snapshot["rows"]}
    rows = read_rows()

    new_rows, skipped = [], []
    seen = {}
    for r in rows:
        raw_city = (r.get("city") or "").strip()
        city = CITY_ALIASES.get(raw_city, raw_city)
        name = (r.get("name") or "").strip()
        address = (r.get("address") or "").strip()
        source = (r.get("source") or "").strip()
        phone = norm_phone_db(r.get("phone"))
        p10 = phone10(phone)

        if not name or not phone or not address:
            skipped.append((name or "—", "нет названия, телефона или адреса"))
            continue
        if p10 in seen:
            skipped.append((name, f"дубль внутри файла (телефон уже у «{seen[p10]}»)"))
            continue
        seen[p10] = name
        if p10 in known:
            continue

        # Пометки для рассылки ставим честно, без «опта»:
        # мобильный (79…) — WhatsApp возможен по данным источника; городской — нет.
        is_mobile = phone.startswith("79")
        new_rows.append({
            "city": city,
            "name": name,
            "phone": phone,
            "address": address,
            "source": source,
            "has_whatsapp": is_mobile,
            "has_telegram": False,
            "has_max": False,
            "is_mobile": is_mobile,
            "notes": "",
        })

    return {
        "rows": new_rows,
        "skipped": skipped,
        "source_rows": len(rows),
        "existing_count": len(snapshot["rows"]),
        "existing_taken_at": snapshot.get("taken_at", ""),
    }


def bool_sql(v):
    return "true" if v else "false"


def build_topup_sql(payload):
    rows = payload["rows"]
    out = []
    out.append("-- ============================================================================")
    out.append("-- Ива — ДОПОЛНЕНИЕ справочника прокатов (этап П2)")
    out.append("--")
    out.append("-- Что это: добавляет в боевой справочник те контакты из tools/real_bases.csv,")
    out.append(f"-- которых там ещё нет. На момент сборки в базе было {payload['existing_count']} записей")
    out.append(f"-- (снимок от {payload['existing_taken_at']}), новых в этом файле — {len(rows)}.")
    out.append("--")
    out.append("-- Как запускать: Supabase -> SQL Editor -> New query -> вставить весь файл -> Run.")
    out.append("-- Безопасно при повторном запуске: уже существующие телефоны пропускаются,")
    out.append("-- дубли не создаются. Ничего не удаляет и не изменяет.")
    out.append("-- ============================================================================")
    out.append("")
    out.append("begin;")
    out.append("")
    if rows:
        out.append("insert into public.directory_landlords")
        out.append("  (city, name, phone, address, source, has_whatsapp, has_telegram, has_max, is_mobile, notes, status)")
        out.append("select")
        out.append("  v.city, v.name, v.phone, v.address, v.source,")
        out.append("  v.has_whatsapp, v.has_telegram, v.has_max, v.is_mobile, v.notes, 'new'")
        out.append("from (values")
        defs = []
        for r in rows:
            defs.append("  (" + ", ".join([
                sql_str(r["city"]), sql_str(r["name"]), sql_str(r["phone"]), sql_str(r["address"]),
                sql_str(r["source"]), bool_sql(r["has_whatsapp"]), bool_sql(r["has_telegram"]),
                bool_sql(r["has_max"]), bool_sql(r["is_mobile"]), sql_str(r["notes"]),
            ]) + ")")
        out.append(",\n".join(defs))
        out.append(") as v(city, name, phone, address, source, has_whatsapp, has_telegram, has_max, is_mobile, notes)")
        out.append("where not exists (")
        out.append("  select 1 from public.directory_landlords d")
        out.append("  where right(regexp_replace(d.phone, '[^0-9]', '', 'g'), 10) = right(v.phone, 10)")
        out.append(");")
    else:
        out.append("-- Новых контактов нет: в базе уже всё, что есть в файле-источнике.")
        out.append("select 'Новых контактов нет — справочник уже полный' as \"результат\";")
    out.append("")
    out.append("commit;")
    out.append("")
    out.append("-- Итог: пришлите эту табличку — по ней видно результат")
    out.append("select")
    out.append("  count(*)                                           as \"всего в справочнике\",")
    out.append("  count(*) filter (where created_at >= now() - interval '1 hour') as \"добавлено только что\",")
    out.append("  count(distinct city)                               as \"городов\"")
    out.append("from public.directory_landlords;")
    out.append("")
    return "\n".join(out)


def build_bundle(migration, topup):
    return (
        "-- ============================================================================\n"
        "-- Ива: ВСЁ В ОДНОМ ФАЙЛЕ — справочник прокатов «под ключ» (этап П2).\n"
        "--\n"
        "-- ⚠️ ЭТОТ ФАЙЛ — ДЛЯ НОВОГО / ТЕСТОВОГО ПРОЕКТА. На боевом проекте он не нужен:\n"
        "--    таблица там уже создана, а данные — в отдельном файле дополнения.\n"
        "--\n"
        "-- Состав: структура таблицы + ввоз отсутствующих контактов из tools/real_bases.csv.\n"
        "-- Политики доступа — отдельным файлом (…_rls_NEW_PROJECT.sql), чтобы случайно\n"
        "-- не изменить права на боевом проекте.\n"
        "--\n"
        "-- Повторный запуск безопасен: ничего не удаляется, дубли не создаются.\n"
        "-- ============================================================================\n\n"
        + migration.rstrip() + "\n\n"
        + "-- ============================================================================\n"
        + "-- ЧАСТЬ 2: данные (контакты, которых ещё нет в базе)\n"
        + "-- ============================================================================\n\n"
        + topup
    )


def print_report(payload):
    print(f"Строк в файле источников:   {payload['source_rows']}")
    print(f"Уже в базе (снимок):        {payload['existing_count']} записей (снимок от {payload['existing_taken_at']})")
    print(f"Новых к добавлению:         {len(payload['rows'])}")
    mob = sum(1 for r in payload["rows"] if r["is_mobile"])
    print(f"   из них мобильных:        {mob} (городских: {len(payload['rows']) - mob})")
    print(f"Отброшено:                  {len(payload['skipped'])}")
    for name, why in payload["skipped"]:
        print(f"   - «{name}»: {why}")
    cities = {}
    for r in payload["rows"]:
        cities[r["city"]] = cities.get(r["city"], 0) + 1
    if cities:
        print("Города новых контактов:     " + ", ".join(f"{c} ({n})" for c, n in sorted(cities.items())))


def main():
    if "--snapshot" in sys.argv:
        refresh_snapshot()
        return 0

    payload = prepare()

    if "--report" in sys.argv:
        print_report(payload)
        return 0

    with open(MIGRATION_SQL, encoding="utf-8") as f:
        migration = f.read()
    topup = build_topup_sql(payload)
    bundle = build_bundle(migration, topup)

    if "--check" in sys.argv:
        problems = []
        for path, expected, what in ((OUT_SQL, topup, "файл дополнения"), (OUT_BUNDLE, bundle, "комбинированный файл")):
            if not os.path.exists(path):
                problems.append(f"нет файла: {path}")
                continue
            with open(path, encoding="utf-8") as f:
                if f.read() != expected:
                    problems.append(f"{what} не совпадает с источником: {path}")
        if problems:
            for p in problems:
                print("РАСХОЖДЕНИЕ: " + p)
            print("Починить: python3 tools/build_directory_import.py")
            return 1
        print(f"Файлы совпадают с источником: новых контактов {len(payload['rows'])}, ничего лишнего")
        return 0

    for path, text in ((OUT_SQL, topup), (OUT_BUNDLE, bundle)):
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"Записано: {path}")
    print_report(payload)
    return 0


if __name__ == "__main__":
    sys.exit(main())
