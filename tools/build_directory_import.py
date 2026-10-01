#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Ввоз справочника прокатов в базу (этап П2).

Читает tools/real_bases.csv (открытые источники), приводит данные в порядок и
собирает готовый SQL-файл supabase/seed/. Владелец вставляет этот файл в
SQL Editor своего проекта Supabase и нажимает Run — никаких ключей и установок.

Что делает приведение в порядок:
  * телефон -> единый вид +7XXXXXXXXXX;
  * город -> подтягивается ссылка (slug) из tools/cities.json, чтобы не завести
    «второй список городов»; для городов, которых нет в списке сайта, ссылка
    остаётся пустой, а строка помечается в отчёте (решение принимает владелец);
  * дубли по «телефон + адрес» отбрасываются (остаётся первая запись);
  * ничего не выдумывается: пустые поля остаются пустыми.

Запуск:
    python3 tools/build_directory_import.py            # собрать файл ввоза
    python3 tools/build_directory_import.py --check    # только проверить, не писать
    python3 tools/build_directory_import.py --report   # показать отчёт и выйти
"""
import csv
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CSV_PATH = os.path.join(ROOT, "tools", "real_bases.csv")
CITIES_JSON = os.path.join(ROOT, "tools", "cities.json")
OUT_SQL = os.path.join(ROOT, "supabase", "seed", "20261001_directory_landlords_import.sql")
MIGRATION_SQL = os.path.join(ROOT, "supabase", "migrations", "20261001_directory_landlords.sql")
OUT_BUNDLE = os.path.join(ROOT, "supabase", "seed", "20261001_directory_ALL_IN_ONE.sql")

# Написания городов, отличающиеся от списка сайта, читаем из общего файла,
# чтобы проверка (tests/directory-import.cjs) знала те же правила.
ALIASES_JSON = os.path.join(ROOT, "tools", "city_aliases.json")
with open(ALIASES_JSON, encoding="utf-8") as f:
    CITY_ALIASES = {k: v for k, v in json.load(f).items() if not k.startswith("_")}

# Дата получения контактов из открытых источников (файл подготовлен до этой даты).
SOURCE_DATE = "2026-09-26"


def norm_phone(raw):
    """Любой ввод -> +7XXXXXXXXXX; если не распознать — возвращаем как есть."""
    digits = re.sub(r"\D", "", str(raw or ""))
    if len(digits) == 11 and digits[0] in ("7", "8"):
        return "+7" + digits[1:]
    if len(digits) == 10:
        return "+7" + digits
    return (str(raw or "")).strip()


def sql_str(value):
    if value is None:
        return "NULL"
    return "'" + str(value).replace("'", "''") + "'"


def load_cities():
    with open(CITIES_JSON, encoding="utf-8") as f:
        data = json.load(f)
    by_name = {c["name"]: c for c in data["cities"]}
    return by_name, {c["slug"]: c for c in data["cities"]}


def read_rows():
    with open(CSV_PATH, encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def prepare():
    by_name, by_slug = load_cities()
    rows = read_rows()

    prepared = []
    skipped = []
    unknown_cities = {}
    flags = []

    seen_phone_address = {}
    for r in rows:
        raw_city = (r.get("city") or "").strip()
        city = CITY_ALIASES.get(raw_city, raw_city)
        phone = norm_phone(r.get("phone"))
        address = (r.get("address") or "").strip()
        name = (r.get("name") or "").strip()
        source = (r.get("source") or "").strip()
        whatsapp = str(r.get("has_whatsapp") or "").strip().lower() in ("true", "1", "да", "yes")
        status = (r.get("status") or "new").strip() or "new"
        if status not in ("new", "verified", "hidden", "declined"):
            flags.append(f"неизвестный статус «{status}» у «{name}» — приведён к new")
            status = "new"

        if not name or not phone or not address:
            skipped.append((name or "—", "нет названия, телефона или адреса"))
            continue

        key = (phone, address.lower())
        if key in seen_phone_address:
            skipped.append((name, f"дубль «телефон + адрес» с «{seen_phone_address[key]}»"))
            continue
        seen_phone_address[key] = name

        city_info = by_name.get(city)
        city_slug = city_info["slug"] if city_info else None
        if not city_info:
            unknown_cities.setdefault(city, 0)
            unknown_cities[city] += 1

        prepared.append({
            "city": city,
            "city_slug": city_slug,
            "name": name,
            "phone": phone,
            "address": address,
            "source": source,
            "has_whatsapp": whatsapp,
            "status": status,
        })

    return {
        "rows": prepared,
        "skipped": skipped,
        "unknown_cities": unknown_cities,
        "flags": flags,
        "by_slug": by_slug,
        "source_rows": len(rows),
    }


def build_sql(payload):
    rows = payload["rows"]
    lines = []
    lines.append("-- ============================================================================")
    lines.append("-- Ива — ввоз справочника прокатов (этап П2)")
    lines.append("--")
    lines.append(f"-- Файл собран автоматически (tools/build_directory_import.py) из tools/real_bases.csv.")
    lines.append(f"-- Строк к ввозу: {len(rows)}. Источники: открытые справочники (поле source у каждой строки).")
    lines.append("--")
    lines.append("-- Как запускать: Supabase -> SQL Editor -> New query -> вставить весь файл -> Run.")
    lines.append("-- Повторный запуск безопасен: строки обновляются, дубли не создаются,")
    lines.append("-- а статусы проверки (status, checked_at) НЕ затираются.")
    lines.append("-- ============================================================================")
    lines.append("")
    lines.append("begin;")
    lines.append("")
    lines.append("insert into public.directory_landlords")
    lines.append("  (city, city_slug, name, phone, address, source, source_date, has_whatsapp, status)")
    lines.append("values")

    defs = []
    for r in rows:
        defs.append(
            "  ("
            + ", ".join([
                sql_str(r["city"]),
                sql_str(r["city_slug"]) if r["city_slug"] else "NULL",
                sql_str(r["name"]),
                sql_str(r["phone"]),
                sql_str(r["address"]),
                sql_str(r["source"]),
                "DATE " + sql_str(SOURCE_DATE),
                "true" if r["has_whatsapp"] else "false",
                sql_str(r["status"]),
            ])
            + ")"
        )
    lines.append(",\n".join(defs))
    lines.append("on conflict (phone, address) do update set")
    lines.append("  city         = excluded.city,")
    lines.append("  city_slug    = excluded.city_slug,")
    lines.append("  name         = excluded.name,")
    lines.append("  source       = excluded.source,")
    lines.append("  has_whatsapp = excluded.has_whatsapp,")
    lines.append("  updated_at   = now();")
    lines.append("")
    lines.append("commit;")
    lines.append("")
    lines.append("-- Итог: что получилось (пришлите эту табличку обратно — по ней видно результат)")
    lines.append("select")
    lines.append("  count(*)                                        as \"всего строк\",")
    lines.append("  count(*) filter (where city_slug is null)       as \"городов нет в списке сайта\",")
    lines.append("  count(*) filter (where status = 'new')          as \"ждут проверки\",")
    lines.append("  count(distinct city)                            as \"городов\",")
    lines.append("  count(distinct phone)                           as \"телефонов\"")
    lines.append("from public.directory_landlords;")
    lines.append("")
    return "\n".join(lines)


def print_report(payload):
    rows = payload["rows"]
    print(f"Строк в файле источников: {payload['source_rows']}")
    print(f"К ввозу после чистки:     {len(rows)}")
    print(f"Отброшено:                {len(payload['skipped'])}")
    for name, why in payload["skipped"]:
        print(f"   - «{name}»: {why}")
    print(f"Городов:                  {len({r['city'] for r in rows})}")
    unknown = payload["unknown_cities"]
    if unknown:
        print(f"Городов нет в списке сайта ({len(unknown)}): " + ", ".join(sorted(unknown)))
        print("   (строки ввозятся, ссылка города пустая — решение о добавлении городов за владельцем)")
    wa = sum(1 for r in rows if r["has_whatsapp"])
    print(f"С пометкой WhatsApp:      {wa} из {len(rows)}")
    for f in payload["flags"]:
        print("   ! " + f)


def main():
    payload = prepare()
    sql = build_sql(payload)

    if "--report" in sys.argv:
        print_report(payload)
        return 0

    if "--check" in sys.argv:
        if not os.path.exists(OUT_SQL):
            print("нет файла ввоза — собрать: python3 tools/build_directory_import.py")
            return 1
        with open(OUT_SQL, encoding="utf-8") as f:
            current = f.read()
        if current != sql:
            print("РАСХОЖДЕНИЕ: файл ввоза не совпадает с tools/real_bases.csv")
            print("Починить: python3 tools/build_directory_import.py")
            print_report(payload)
            return 1
        if not os.path.exists(OUT_BUNDLE):
            print("нет комбинированного файла — собрать: python3 tools/build_directory_import.py")
            return 1
        with open(OUT_BUNDLE, encoding="utf-8") as f:
            bundle_current = f.read()
        if bundle_current.strip().split("\n")[-1] != sql.strip().split("\n")[-1] or sql not in bundle_current:
            print("РАСХОЖДЕНИЕ: комбинированный файл не содержит текущий ввоз")
            print("Починить: python3 tools/build_directory_import.py")
            return 1
        print(f"Файл ввоза совпадает с источником: {len(payload['rows'])} строк (и комбинированный файл в порядке)")
        return 0

    os.makedirs(os.path.dirname(OUT_SQL), exist_ok=True)
    with open(OUT_SQL, "w", encoding="utf-8") as f:
        f.write(sql)

    # Комбинированный файл: таблица + ввоз в одном тексте — владельцу достаточно
    # одной вставки в SQL Editor вместо двух.
    with open(MIGRATION_SQL, encoding="utf-8") as f:
        migration = f.read()
    bundle = (
        "-- ============================================================================\n"
        "-- Ива: ВСЁ В ОДНОМ ФАЙЛЕ — создание таблицы справочника прокатов и ввоз данных.\n"
        "--\n"
        "-- Этот файл собран автоматически (tools/build_directory_import.py) из двух:\n"
        "--   supabase/migrations/20261001_directory_landlords.sql  (создание таблицы)\n"
        "--   supabase/seed/20261001_directory_landlords_import.sql (ввоз 115 контактов)\n"
        "--\n"
        "-- Как запускать: Supabase -> SQL Editor -> New query -> вставить весь файл -> Run.\n"
        "-- Повторный запуск безопасен: строки обновляются, дубли не создаются, отметки\n"
        "-- проверки карточек не стираются.\n"
        "--\n"
        "-- В самом конце будет табличка с итогом — пришлите её, по ней видно результат.\n"
        "-- ============================================================================\n\n"
        + migration.rstrip() + "\n\n"
        + "-- ============================================================================\n"
        + "-- ЧАСТЬ 2: ввоз данных (115 контактов)\n"
        + "-- ============================================================================\n\n"
        + sql
    )
    with open(OUT_BUNDLE, "w", encoding="utf-8") as f:
        f.write(bundle)

    print(f"Записано: {OUT_SQL}")
    print(f"Записано: {OUT_BUNDLE} (всё в одном файле)")
    print_report(payload)
    return 0


if __name__ == "__main__":
    sys.exit(main())
