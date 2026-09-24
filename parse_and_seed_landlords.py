#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Парсер и загрузчик баз проката инструмента в Supabase.
Поддерживает сбор по городам через Яндекс.Карты / 2ГИС API / CSV / Сетевые списки
и прямую заливку в таблицу directory_landlords.
"""

import os
import sys
import json
import urllib.request
import urllib.parse
import re

# Ключи берутся из переменных окружения или конфигурационного файла
SUPABASE_MANAGEMENT_KEY = os.environ.get("SUPABASE_MANAGEMENT_KEY", "")
SUPABASE_PROJECT_REF = os.environ.get("SUPABASE_PROJECT_REF", "wdxdeatphizclskfmfxi")

def clean_phone(raw_phone):
    digits = re.sub(r'\D', '', str(raw_phone))
    if len(digits) == 11:
        if digits.startswith('8'):
            digits = '7' + digits[1:]
    elif len(digits) == 10:
        digits = '7' + digits
    return digits

def insert_directory_landlords(records, management_key=None, project_ref=None):
    """
    Загрузка пачки организаций в Supabase в таблицу directory_landlords
    records: list of dict(city, name, phone, address, source, has_whatsapp)
    """
    key = management_key or SUPABASE_MANAGEMENT_KEY
    pref = project_ref or SUPABASE_PROJECT_REF

    if not key:
        print("Ошибка: укажите SUPABASE_MANAGEMENT_KEY через аргумент или переменную окружения.")
        return

    if not records:
        print("Нет записей для вставки")
        return

    values = []
    for r in records:
        city = r['city'].replace("'", "''")
        name = r['name'].replace("'", "''")
        phone = clean_phone(r['phone'])
        address = (r.get('address') or '').replace("'", "''")
        source = r.get('source', '2gis')
        has_wa = 'true' if r.get('has_whatsapp', True) else 'false'
        
        # Фильтруем некорректные номера
        if not phone.startswith('79') and not phone.startswith('78'):
            continue
            
        values.append(f"('{city}', '{name}', '{phone}', '{address}', '{source}', {has_wa})")

    if not values:
        print("Нет валидных номеров для сохранения")
        return

    chunk_size = 50
    for i in range(0, len(values), chunk_size):
        chunk = values[i:i+chunk_size]
        sql = f"""
        INSERT INTO public.directory_landlords (city, name, phone, address, source, has_whatsapp)
        VALUES {', '.join(chunk)}
        ON CONFLICT (phone) DO UPDATE 
        SET name = EXCLUDED.name, address = EXCLUDED.address, city = EXCLUDED.city;
        """

        req = urllib.request.Request(
            f"https://api.supabase.com/v1/projects/{pref}/database/query",
            headers={
                "Authorization": f"Bearer {key}",
                "Content-Type": "application/json"
            },
            data=json.dumps({"query": sql}).encode("utf-8"),
            method="POST"
        )
        try:
            with urllib.request.urlopen(req) as resp:
                print(f"Загружено {len(chunk)} баз...")
        except Exception as e:
            print(f"Ошибка загрузки пачки: {e}")

if __name__ == "__main__":
    print("Модуль загрузчика готов к работе.")
