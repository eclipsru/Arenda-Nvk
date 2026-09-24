"""
Скрипт для наполнения таблицы directory_landlords реальными базами проката.
Использует Supabase Management API (прямой SQL).
Запуск: SUPABASE_MANAGEMENT_KEY=sbp_... python3 tools/seed_real_bases.py
"""
import json, re, urllib.request, os

SUPABASE_MANAGEMENT_KEY = os.environ.get("SUPABASE_MANAGEMENT_KEY", "")
SUPABASE_PROJECT_REF = os.environ.get("SUPABASE_PROJECT_REF", "wdxdeatphizclskfmfxi")

if not SUPABASE_MANAGEMENT_KEY:
    print("Укажите SUPABASE_MANAGEMENT_KEY в env. Пример:")
    print("SUPABASE_MANAGEMENT_KEY=sbp_... python3 tools/seed_real_bases.py")
    print("Ключ можно взять из КЛЮЧИ-ДОСТУПЫ.md")
    # не выходим, просто предупредим

def run_sql(sql):
    if not SUPABASE_MANAGEMENT_KEY:
        print("SKIP SQL (нет ключа):", sql[:100])
        return None
    req = urllib.request.Request(
        f"https://api.supabase.com/v1/projects/{SUPABASE_PROJECT_REF}/database/query",
        headers={"Authorization": f"Bearer {SUPABASE_MANAGEMENT_KEY}", "Content-Type": "application/json"},
        data=json.dumps({"query": sql}).encode(),
        method="POST"
    )
    try:
        with urllib.request.urlopen(req) as resp:
            print("OK", sql[:120])
            return resp.read().decode()
    except Exception as e:
        print("ERR", e)
        try:
            print(e.read().decode()[:1000])
        except:
            pass
        return None

def clean_phone(p):
    d = re.sub(r'\D', '', str(p))
    if len(d) == 11 and d.startswith('8'):
        d = '7' + d[1:]
    elif len(d) == 10:
        d = '7' + d
    return d

REAL_BASES = [
    {"city": "Ростов-на-Дону", "name": "ЛигаСтрой", "phone": "79001295454", "address": "ул. Миронова, 2", "source": "jsprav.ru"},
    {"city": "Ростов-на-Дону", "name": "Технополис", "phone": "79287619306", "address": "ул. Вавилова, 62А", "source": "jsprav.ru"},
    {"city": "Ростов-на-Дону", "name": "Прок61", "phone": "79281827504", "address": "ул. Мечникова, 33", "source": "2gis"},
    {"city": "Ростов-на-Дону", "name": "Прокат 61 (филиал)", "phone": "79281573972", "address": "ул. Мечникова, 33", "source": "2gis"},
    {"city": "Ростов-на-Дону", "name": "Делай просто", "phone": "79604685900", "address": "Осенняя ул., 6", "source": "spravker.ru"},
    {"city": "Ростов-на-Дону", "name": "Стахановец.рф Ростов", "phone": "79054852233", "address": "ул. Вятская, 40", "source": "stahanovec.ru"},
    {"city": "Ростов-на-Дону", "name": "ПрокатСервис", "phone": "79889990797", "address": "Енисейская ул., 12, х. Камышеваха", "source": "2gis"},
    {"city": "Ростов-на-Дону", "name": "Домино+", "phone": "79508480868", "address": "Пушкинская ул., 83", "source": "2gis"},
    {"city": "Ростов-на-Дону", "name": "ЛИГАстрой", "phone": "79001344494", "address": "ул. Миронова, 2", "source": "2gis"},
    {"city": "Ростов-на-Дону", "name": "ИнструментМастер", "phone": "79185854383", "address": "Сортовой пер., 56", "source": "2gis"},
    {"city": "Ростов-на-Дону", "name": "Стройка-Аренда Ростов", "phone": "79188967690", "address": "ул. Нансена, 77/2", "source": "stroika-arenda.ru"},
    {"city": "Ростов-на-Дону", "name": "Ваш Дом Прокат", "phone": "78633100003", "address": "пр-т 40 лет Победы, 264/110а", "source": "vashdom24.ru"},
    {"city": "Ростов-на-Дону", "name": "Стройпрокат (rostovrent.ru)", "phone": "79274884898", "address": "ул. Нансена, 73", "source": "rostovrent.ru"},
    {"city": "Ростов-на-Дону", "name": "ПрофиРент", "phone": "79612896622", "address": "ул. Обсерваторная, 48", "source": "profi.rent"},
    {"city": "Ростов-на-Дону", "name": "MachineStore", "phone": "78633033093", "address": "Привокзальная ул., 2", "source": "spravker.ru"},
    {"city": "Новочеркасск", "name": "Агентство Инструментарий", "phone": "79515233452", "address": "ул. Гагарина, 33", "source": "yandex.maps"},
    {"city": "Новочеркасск", "name": "Агентство Инструментарий (стац.)", "phone": "78635251150", "address": "ул. Гагарина, 33", "source": "yandex.maps"},
    {"city": "Новочеркасск", "name": "Прокат Инструмента Сарматская", "phone": "79081787838", "address": "Сарматская ул., 61", "source": "spravker.ru"},
    {"city": "Новочеркасск", "name": "Электро-Бензо Сила", "phone": "79064286075", "address": "Платовский просп., 101", "source": "spravker.ru"},
    {"city": "Таганрог", "name": "Прокат инструмента Чехова", "phone": "79525650378", "address": "ул. Чехова, 120-3", "source": "spravker.ru"},
    {"city": "Таганрог", "name": "Прокат инструмента (Вячеслав)", "phone": "79508465027", "address": "ул. Чехова, 120", "source": "electroprokat.ru"},
    {"city": "Таганрог", "name": "СтальСтрой", "phone": "79381558002", "address": "Конторская ул., 78", "source": "spravker.ru"},
    {"city": "Таганрог", "name": "Кран Строй", "phone": "79885452787", "address": "ул. Лесная Биржа", "source": "spravker.ru"},
    {"city": "Таганрог", "name": "Аренда Электроинструмента", "phone": "79281629020", "address": "Мало-Почтовая ул., 54А", "source": "spravker.ru"},
    {"city": "Батайск", "name": "Впрокате Батайск", "phone": "79198825969", "address": "Рыбная ул., 119", "source": "spravker.ru"},
    {"city": "Батайск", "name": "Аренда инструмента Ленинградская", "phone": "79281110041", "address": "ул. Максима Горького, 372", "source": "spravker.ru"},
    {"city": "Батайск", "name": "Фирма ЛТД Батайск", "phone": "78633089672", "address": "Промышленная улица", "source": "spravker.ru"},
    {"city": "Батайск", "name": "Инструментик Батайск", "phone": "79996968167", "address": "Кооперативная ул., 103", "source": "xtool.ru"},
    {"city": "Батайск", "name": "Инструментик Батайск 2", "phone": "79885602422", "address": "Кооперативная ул., 103", "source": "xtool.ru"},
    {"city": "Азов", "name": "Аренда строительных инструментов Азов", "phone": "79775335026", "address": "ул. Победы, 20Ж", "source": "spravker.ru"},
    {"city": "Азов", "name": "ХозяиН Азов", "phone": "79281004610", "address": "Объездной пр., 7А", "source": "spravker.ru"},
    {"city": "Аксай", "name": "Стройрент Аксай", "phone": "78633332893", "address": "г. Аксай, пр. Ленина, д. 53 Д", "source": "stroyrent.ru"},
    {"city": "Волгодонск", "name": "Прокатстрой Волгодонск", "phone": "79185305523", "address": "СНТ Машиностроитель 1п, Романовское шоссе", "source": "fooby.ru"},
    {"city": "Волгодонск", "name": "ПРОКАТСТРОЙСЕРВИС Волгодонск", "phone": "79381117706", "address": "ул. Шлюзовская, 11", "source": "prokatstroyresurs.ru"},
    {"city": "Волгодонск", "name": "ПрокатИнСтрой Волгодонск", "phone": "79081889080", "address": "пр-т Курчатова, 1ж, бокс 13", "source": "vk.com/prokatenstroi"},
    {"city": "Волгодонск", "name": "Аренда-инструмента-161 Волгодонск", "phone": "79381615767", "address": "пер. Пупкова, 22", "source": "spravker.ru"},
    {"city": "Каменск-Шахтинский", "name": "Прокат инструмента Ясельная", "phone": "79381621322", "address": "Ясельная ул., 72", "source": "business.site"},
    {"city": "Каменск-Шахтинский", "name": "Прокат инструмента Ясельная 2", "phone": "79034307430", "address": "Ясельная ул., 72", "source": "spravker.ru"},
    {"city": "Шахты", "name": "Гарант Шахты", "phone": "79185260868", "address": "ул. Громова, 1, офис 3", "source": "big-book-city.ru"},
    {"city": "Шахты", "name": "MachineStore Шахты", "phone": "79281485979", "address": "Садовая ул., 15", "source": "big-book-city.ru"},
    {"city": "Белая Калитва", "name": "Калитва Прокат", "phone": "79612949445", "address": "ул. Вахрушева, 3А", "source": "vk.com/kalitvaprokat"},
    {"city": "Новошахтинск", "name": "Кузница инструмента Новошахтинск", "phone": "79508523921", "address": "Харьковская ул., 105", "source": "big-book-city.ru"},
    {"city": "Сальск", "name": "Металлопрокат Сальск", "phone": "79281245500", "address": "г. Сальск", "source": "stroiclick.ru"},
    {"city": "Краснодар", "name": "Время Проката", "phone": "79530846970", "address": "ул. им. Лизы Чайкиной, 16", "source": "2gis"},
    {"city": "Краснодар", "name": "РосПрокат 23", "phone": "78612012501", "address": "Северная ул., 223", "source": "2gis"},
    {"city": "Краснодар", "name": "Проинструменты24", "phone": "79615804433", "address": "Есаульская ул., 72 стр1", "source": "2gis"},
    {"city": "Краснодар", "name": "Центр проката Школьная", "phone": "79992335792", "address": "Школьная ул., 13/5", "source": "2gis"},
    {"city": "Краснодар", "name": "Прокат Северная 16д", "phone": "79892005153", "address": "Северная ул., 16д, пос. Южный", "source": "2gis"},
    {"city": "Краснодар", "name": "ТЕХ-ПРОКАТ", "phone": "79183802121", "address": "ул. Уральская, 87/1с2", "source": "spravka.city"},
    {"city": "Сочи", "name": "Центр проката Сочи", "phone": "79882374247", "address": "ул. 20 Горнострелковой дивизии, 16", "source": "center-prokat.ru"},
    {"city": "Сочи", "name": "Проф_Центр Сочи", "phone": "79388780022", "address": "ул. Следопытов, 3", "source": "vse-naprokat.com"},
    {"city": "Воронеж", "name": "Бригадир Прокат", "phone": "74732000352", "address": "ул. Димитрова, 112", "source": "brigadirprokat36.ru"},
    {"city": "Воронеж", "name": "Прокат36", "phone": "74733003618", "address": "ул. Кривошеина, 15, офис 223А", "source": "prokat36.ru"},
    {"city": "Воронеж", "name": "Стахановец.рф Воронеж", "phone": "79206215999", "address": "пер. Отличников, д.1Б", "source": "stahanovec.ru"},
    {"city": "Ставрополь", "name": "Михалыч Прокат", "phone": "79624488748", "address": "ул. Индустриальная, 35", "source": "mihalpro.ru"},
    {"city": "Ставрополь", "name": "СтавПрокат", "phone": "79187796059", "address": "ул. Селекционная, 3А", "source": "stahanovec.ru"},
    {"city": "Москва", "name": "МосСтройПрокат", "phone": "74953746108", "address": "Пятницкое шоссе, 28 стр.1", "source": "mosstroyprokat.ru"},
    {"city": "Санкт-Петербург", "name": "Стахановец.рф СПб", "phone": "79095854060", "address": "ул. Михаила Дудина, 11", "source": "stahanovec.ru"},
    {"city": "Екатеринбург", "name": "ИнструментБург", "phone": "73432264443", "address": "г. Екатеринбург", "source": "instrumentburg.ru"},
    {"city": "Новосибирск", "name": "НСК Прокат", "phone": "79137817755", "address": "ул. Пасечная, 1а", "source": "nskprokat.ru"},
]

if __name__ == "__main__":
    cleaned=[]
    seen=set()
    for r in REAL_BASES:
        ph=clean_phone(r['phone'])
        if len(ph)!=11: continue
        if ph in seen: continue
        seen.add(ph)
        r['phone']=ph
        cleaned.append(r)
    print(f"Inserting {len(cleaned)} real bases")
    vals=[]
    for r in cleaned:
        vals.append(f"('{r['city'].replace(chr(39),chr(39)+chr(39))}','{r['name'].replace(chr(39),chr(39)+chr(39))}','{r['phone']}','{r['address'].replace(chr(39),chr(39)+chr(39))}','{r['source']}', true)")
    sql=f"INSERT INTO public.directory_landlords (city, name, phone, address, source, has_whatsapp) VALUES {', '.join(vals)} ON CONFLICT (phone) DO UPDATE SET name=EXCLUDED.name, address=EXCLUDED.address, city=EXCLUDED.city, source=EXCLUDED.source;"
    run_sql(sql)
