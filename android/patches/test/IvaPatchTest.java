// Проверка чистой логики IvaPatch на обычной Java (без телефона). Запускается в build-apk.yml.
package ru.prokatnvsk.app;

public class IvaPatchTest {
    static int fail = 0;

    /** 10.93: строки «ключ=значение|…» → как IvaChiefPatch.arr (значение после «=» может быть пустым). */
    static java.util.List<java.util.Map<String, String>> ch93rows(String... rows) {
        java.util.List<java.util.Map<String, String>> out = new java.util.ArrayList<java.util.Map<String, String>>();
        for (String r : rows) {
            java.util.Map<String, String> m = new java.util.LinkedHashMap<String, String>();
            for (String kv : r.split("\\|")) { int e = kv.indexOf('='); m.put(kv.substring(0, e), kv.substring(e + 1)); }
            out.add(m);
        }
        return out;
    }
    static void eq(String what, Object got, Object exp) {
        if (!String.valueOf(got).equals(String.valueOf(exp))) { fail++; System.out.println("✘ " + what + ": «" + got + "» ≠ «" + exp + "»"); }
        else System.out.println("✔ " + what);
    }
    public static void main(String[] a) {
        String NB = "\u00a0";
        // разделитель тысяч → неразрывный пробел (иначе parseRub читал «1 200 ₽» как 1)
        eq("1 200 ₽ → неразрывный пробел", IvaPatch.normalize("1 200 ₽ (12.3 км)"), "1" + NB + "200 ₽ (12.3 км)");
        eq("сумма читается", IvaPatch.rub("1 200 ₽ (12.3 км)"), 1200);
        // в одну сторону — без изменений по смыслу
        IvaPatch.round = false;
        eq("в одну сторону", IvaPatch.onPrice("360 ₽ (12.0 км)"), "360 ₽ (12.0 км)");
        // туда и обратно — сумма первой и удвоена
        IvaPatch.round = true;
        String r = IvaPatch.onPrice("1 200 ₽ (12.3 км)");
        eq("туда и обратно", r, "2" + NB + "400 ₽ (туда и обратно: 2 × 1" + NB + "200 ₽, 12.3 км в одну сторону)");
        // как приложение считает итог: parseRub = до первого обычного пробела, убрать неразрывные
        String first = r.substring(0, r.indexOf(' ')).replace(NB, "");
        eq("parseRub видит 2400", first, "2400");
        eq("без цены — пометка", IvaPatch.onPrice("≈12.0 км — цену за километр уточнит владелец"), "≈12.0 км — цену за километр уточнит владелец · туда и обратно");
        eq("адрес не найден — без пометки", IvaPatch.onPrice("адрес не найден — уточните адрес"), "адрес не найден — уточните адрес");
        IvaPatch.round = false;
        eq("отказ в правах", IvaPatch.publishError("Ошибка: new row violates row-level security policy for table \"tools\"").startsWith("нет прав на публикацию"), true);
        eq("сессия", IvaPatch.publishError("JWT expired").startsWith("сессия истекла"), true);
        eq("прочее без изменений", IvaPatch.publishError("Нет связи с сервером."), "Нет связи с сервером.");
        // 6. сбой связи: та же фраза + техническая причина (владелец 07.10.2026: «нет связи» при рабочем интернете)
        eq("фраза сохраняется", IvaPatch.netError("failed to connect to wdxdeatphizclskfmfxi.supabase.co/104.18.38.10:443").startsWith("Нет связи с сервером."), true);
        eq("причина видна", IvaPatch.netError("Unable to resolve host \"wdxdeatphizclskfmfxi.supabase.co\"").contains("Unable to resolve host"), true);
        eq("причина помечена", IvaPatch.netError("timeout").contains("Технически: timeout"), true);
        eq("переносы убираются", IvaPatch.netError("строка1\nстрока2").contains("строка1 строка2"), true);
        String longOne = IvaPatch.netError(new String(new char[400]).replace('\0', 'x'));
        String reason = longOne.substring(longOne.indexOf(IvaPatch.TECH) + IvaPatch.TECH.length());
        eq("длинная причина режется до 197 знаков и многоточия", reason.length() == 198 && reason.endsWith("…"), true);
        eq("надпись = фраза + причина", longOne.length(), IvaPatch.NET_MSG.length() + 2 + IvaPatch.TECH.length() + 198);
        eq("пустая причина — только фраза", IvaPatch.netError(""), "Нет связи с сервером. Проверьте интернет и попробуйте ещё раз.");
        eq("null не роняет", IvaPatch.netError(null), "Нет связи с сервером. Проверьте интернет и попробуйте ещё раз.");
        // 9. аватарка в «Кабинете»: только http(s)-адрес; пусто или мусор — иконка остаётся
        eq("аватарки нет: пусто", IvaPatch.wantsAvatar(""), false);
        eq("аватарки нет: null", IvaPatch.wantsAvatar(null), false);
        eq("аватарка: https с пробелами", IvaPatch.wantsAvatar(" https://wdxdeatphizclskfmfxi.supabase.co/a.jpg "), true);
        eq("аватарка: http", IvaPatch.wantsAvatar("http://example.com/a.jpg"), true);
        eq("аватарка: не адрес — не грузим", IvaPatch.wantsAvatar("file:///etc/passwd"), false);
        // 7/9. размеры: dp → пиксели (кружок 40dp, логотип 50dp)
        eq("40dp при плотности 2.0 = 80 px", IvaPatch.px(40, 2.0f), 80);
        eq("50dp при плотности 2.625 = 131 px", IvaPatch.px(50, 2.625f), 131);
        eq("2dp при плотности 3.5 = 7 px", IvaPatch.px(2, 3.5f), 7);
        // 10.18. Подсветка выбирается по имени вкладки; центральный логотип — отдельный раздел.
        eq("выбран home", IvaPatch.navSelected("home", "home"), true);
        eq("chat не выбран на home", IvaPatch.navSelected("home", "chat"), false);
        eq("null не выбран", IvaPatch.navSelected(null, "home"), false);
        eq("логотип для заявки", IvaPatch.isLogoTab("order"), true);
        eq("логотип для раздела админа", IvaPatch.isLogoTab("admhub"), true);
        eq("логотип не для каталога", IvaPatch.isLogoTab("home"), false);
        // 10.40. Экран настроек профиля клиента: чистая логика (без телефона).
        eq("дата ISO читается", java.util.Arrays.toString(IvaClientPatch.dateParts("2015-03-07")), "[2015, 3, 7]");
        eq("дата ДД.ММ.ГГГГ (как вводили в 10.34–10.39) читается", java.util.Arrays.toString(IvaClientPatch.dateParts("07.03.2015")), "[2015, 3, 7]");
        eq("мусор вместо даты — null", IvaClientPatch.dateParts("вчера"), null);
        eq("13-й месяц — null", IvaClientPatch.dateParts("2015-13-01"), null);
        eq("пустая дата — null", IvaClientPatch.dateParts(null), null);
        eq("дата в базу — ГГГГ-ММ-ДД, как у поля type=date на сайте", IvaClientPatch.isoDate(2015, 3, 7), "2015-03-07");
        eq("дата на экране — ДД.ММ.ГГГГ", IvaClientPatch.dateForDisplay("2015-03-07"), "07.03.2015");
        eq("нераспознанный старый текст показывается как есть", IvaClientPatch.dateForDisplay(" весной "), "весной");
        eq("пустая дата на экране — пусто", IvaClientPatch.dateForDisplay(""), "");
        eq("ошибка сервера: поле message", IvaClientPatch.serverMessage("400", "{\"code\":\"P0001\",\"message\":\"Укажите имя\"}"), "Укажите имя");
        eq("нет функции на сервере — понятная причина",
            IvaClientPatch.serverMessage("404", "{\"code\":\"PGRST202\",\"message\":\"Could not find the function public.get_my_customer_profile\"}").contains("миграция"), true);
        eq("пустой ответ — код HTTP", IvaClientPatch.serverMessage("502", ""), "ошибка сервера (HTTP 502)");
        eq("кнопка до загрузки", IvaClientPatch.saveButtonText(false, false, false), "Загружаем профиль…");
        eq("кнопка готова", IvaClientPatch.saveButtonText(true, false, false), "Сохранить");
        eq("кнопка при сохранении", IvaClientPatch.saveButtonText(true, true, false), "Сохраняем…");
        eq("кнопка при загрузке фото", IvaClientPatch.saveButtonText(true, false, true), "Загружаем фото…");
        eq("фото 4000 px → чтение с шагом 4 (≥ 640 px)", IvaClientPatch.avatarSample(4000, 3000, 640), 4);
        eq("маленькое фото не уменьшается", IvaClientPatch.avatarSample(500, 400, 640), 1);
        eq("буквы: имя и фамилия", IvaClientPatch.initialsOf("иван петров"), "ИП");
        eq("буквы: пусто — вопрос", IvaClientPatch.initialsOf("  "), "?");
        // 10.42: имя клиента в строке «Клиент»
        eq("имя в строке: из поля «Имя», без лишних пробелов", IvaClientPatch.clientHeadName("  Иван   Сергеевич "), "Иван Сергеевич");
        eq("имя в строке: пусто — штатный текст", IvaClientPatch.clientHeadName("   "), "");
        eq("имя в строке: null — штатный текст", IvaClientPatch.clientHeadName(null), "");
        eq("имя в строке: длинное обрезается до 60", IvaClientPatch.clientHeadName(new String(new char[80]).replace('\0', 'Я')).length(), 60);
        eq("буква в кружке: первая буква имени заглавной", IvaClientPatch.clientHeadLetter("иван"), "И");
        eq("буква в кружке: пусто", IvaClientPatch.clientHeadLetter(" "), "");
        // 10.43: раздел логотипа у клиента — «Мои инструменты» из текста заявок
        eq("логотип для раздела клиента", IvaPatch.isLogoTab("clienthub"), true);
        eq("заявка: инструмент с количеством", IvaClientPatch.orderMentionsTool("Перфоратор Bosch ×1, Лестница ×2", "Перфоратор Bosch"), true);
        eq("заявка: второй инструмент в списке", IvaClientPatch.orderMentionsTool("Перфоратор Bosch ×1, Лестница ×2", "лестница"), true);
        eq("заявка: часть названия не считается", IvaClientPatch.orderMentionsTool("Перфоратор Bosch ×1", "Перфоратор"), false);
        eq("заявка: название внутри другого не считается", IvaClientPatch.orderMentionsTool("Большая лестница ×1", "Лестница"), false);
        eq("заявка: x вместо ×", IvaClientPatch.orderMentionsTool("Болгарка x2", "Болгарка"), true);
        eq("заявка: без количества в конце", IvaClientPatch.orderMentionsTool("Шуруповёрт, Болгарка", "Болгарка"), true);
        eq("заявка: построчный список", IvaClientPatch.orderMentionsTool("• Болгарка ×1\n• Миксер ×1", "Миксер"), true);
        eq("заявка: пустое название", IvaClientPatch.orderMentionsTool("Болгарка ×1", " "), false);
        eq("заявка: пустой текст", IvaClientPatch.orderMentionsTool(null, "Болгарка"), false);
        eq("заявка: совпадение со второй попытки", IvaClientPatch.orderMentionsTool("Миксер строительный ×1, Миксер ×1", "Миксер"), true);
        eq("статус: выдан", IvaClientPatch.isRentedStatus("rented"), true);
        eq("статус: закрыт (вернули)", IvaClientPatch.isRentedStatus(" Closed "), true);
        eq("статус: новая заявка", IvaClientPatch.isRentedStatus("new"), false);
        eq("статус: пусто", IvaClientPatch.isRentedStatus(null), false);
        eq("часть заявки: «Выдал» по этому инструменту", IvaClientPatch.partMarksRented("rented", "Болгарка ×1", "Болгарка"), true);
        eq("часть заявки: «Вернули», пустой текст — вся часть", IvaClientPatch.partMarksRented("closed", " ", "Болгарка"), true);
        eq("часть заявки: выдан другой инструмент", IvaClientPatch.partMarksRented("rented", "Миксер ×1", "Болгарка"), false);
        eq("часть заявки: ещё новая", IvaClientPatch.partMarksRented("new", "Болгарка ×1", "Болгарка"), false);
        // 10.41: кэш фото клиента для строки «Клиент» и нижнего меню
        eq("фото: ключ — почта без регистра и путь", IvaClientPatch.avatarCacheKey(" Ivan@Mail.RU ", "u1/avatar.jpg"), "ivan@mail.ru|u1/avatar.jpg");
        eq("фото: пустой путь — фото нет", IvaClientPatch.avatarCacheKey("ivan@mail.ru", "  "), "");
        eq("фото: путь null — фото нет", IvaClientPatch.avatarCacheKey("ivan@mail.ru", null), "");
        eq("фото: другой аккаунт — другой ключ", IvaClientPatch.avatarCacheKey("a@b.ru", "u1/avatar.jpg").equals(IvaClientPatch.avatarCacheKey("c@d.ru", "u1/avatar.jpg")), false);
        // 10.45: поиск на главной — город, фильтры, подсчёт (IvaSearchPatch)
        eq("город: список как на сайте (139)", IvaSearchPatch.CITIES.length, 139);
        eq("город: точное название", IvaSearchPatch.city("Ростов-на-Дону"), "Ростов-на-Дону");
        eq("город: строчными → из списка", IvaSearchPatch.city("ростов-на-дону"), "Ростов-на-Дону");
        eq("город: из адреса с «г.»", IvaSearchPatch.city("г. Шахты, ул. Победы, 2"), "Шахты");
        eq("город: область не город", IvaSearchPatch.city("Ростовская область"), "");
        eq("город: район не город", IvaSearchPatch.city("Аксайский район"), "");
        eq("город: улица не город", IvaSearchPatch.city("ул. Ленина, 5"), "");
        eq("город: не из списка, но похож", IvaSearchPatch.city("Шахтёрск"), "Шахтёрск");
        eq("город: латиница не принимается", IvaSearchPatch.city("Rostov"), "");
        eq("адрес: без города", IvaSearchPatch.addressCity("ул. Садовая, 1"), "");
        eq("адрес: с городом", IvaSearchPatch.addressCity("Ростов-на-Дону, ул. Садовая, 1"), "Ростов-на-Дону");
        eq("объявление: pickup_city", IvaSearchPatch.toolCity("Аксай", "", ""), "Аксай");
        eq("объявление: адрес в условиях", IvaSearchPatch.toolCity("", "", "Залог по договору\nАдрес выдачи: г. Шахты, ул. Победы 2\nСостояние: Новый"), "Шахты");
        eq("объявление: отдельный адрес важнее условий", IvaSearchPatch.toolCity(null, "Батайск, ул. Мира, 3", "Адрес выдачи: г. Шахты"), "Батайск");
        eq("объявление: без адреса — Новочеркасск (как на сайте)", IvaSearchPatch.toolCity("", "", ""), "Новочеркасск");
        eq("сравнение: «г.» и регистр", IvaSearchPatch.cityMatches("г. Ростов-на-Дону", "ростов-на-дону"), true);
        eq("сравнение: ё и е", IvaSearchPatch.cityMatches("Шахтёрск", "Шахтерск"), true);
        eq("сравнение: разные города", IvaSearchPatch.cityMatches("Аксай", "Шахты"), false);
        eq("сравнение: пусто", IvaSearchPatch.cityMatches("", ""), false);
        eq("состояние: новый", IvaSearchPatch.condFromTerms("Адрес выдачи: Аксай\nСостояние: Новый"), IvaSearchPatch.COND_NEW);
        eq("состояние: рабочее строчными", IvaSearchPatch.condFromTerms("состояние: рабочее"), IvaSearchPatch.COND_WORK);
        eq("состояние: хорошее", IvaSearchPatch.condFromTerms("Состояние: Хорошее"), IvaSearchPatch.COND_GOOD);
        eq("состояние: не указано", IvaSearchPatch.condFromTerms("Залог 1000"), 0);
        java.util.List<String> cats2 = java.util.Arrays.asList("garden", "power");
        eq("лента: сданный в аренду не показывается", IvaSearchPatch.nativeMatch("rented", "power", null, "", "Дрель", "", "root", "", "", ""), false);
        eq("лента: без категории — всё", IvaSearchPatch.nativeMatch("", "power", null, "", "Дрель", "", "root", "garden", "", ""), true);
        eq("лента: другая категория", IvaSearchPatch.nativeMatch("", "power", null, "", "Дрель", "", "cat", "garden", "", ""), false);
        eq("лента: категория из списка cats", IvaSearchPatch.nativeMatch("", "power", cats2, "", "Дрель", "", "cat", "garden", "", ""), true);
        eq("лента: подкатегория не та", IvaSearchPatch.nativeMatch("", "power", null, "saw", "Пила", "", "cat", "power", "drill", ""), false);
        eq("лента: поиск по описанию", IvaSearchPatch.nativeMatch("", "power", null, "", "Дрель", "Ударная Bosch", "root", "", "", "bosch"), true);
        eq("лента: поиск мимо", IvaSearchPatch.nativeMatch("", "power", null, "", "Дрель", "", "root", "", "", "пила"), false);
        eq("фильтр: город совпал", IvaSearchPatch.extraMatch("Аксай", 500, false, 0, "аксай", -1, -1, false, 0), true);
        eq("фильтр: другой город", IvaSearchPatch.extraMatch("Шахты", 500, false, 0, "Аксай", -1, -1, false, 0), false);
        eq("фильтр: все города", IvaSearchPatch.extraMatch("Шахты", 500, false, 0, "", -1, -1, false, 0), true);
        eq("фильтр: цена ниже «от»", IvaSearchPatch.extraMatch("Аксай", 299, false, 0, "", 300, -1, false, 0), false);
        eq("фильтр: цена на границе", IvaSearchPatch.extraMatch("Аксай", 1000, false, 0, "", 300, 1000, false, 0), true);
        eq("фильтр: цена выше «до»", IvaSearchPatch.extraMatch("Аксай", 1001, false, 0, "", -1, 1000, false, 0), false);
        eq("фильтр: только с доставкой", IvaSearchPatch.extraMatch("Аксай", 500, false, 0, "", -1, -1, true, 0), false);
        eq("фильтр: состояние из выбранных", IvaSearchPatch.extraMatch("Аксай", 500, true, IvaSearchPatch.COND_WORK, "", -1, -1, true, IvaSearchPatch.COND_NEW | IvaSearchPatch.COND_WORK), true);
        eq("фильтр: состояние не указано", IvaSearchPatch.extraMatch("Аксай", 500, true, 0, "", -1, -1, false, IvaSearchPatch.COND_NEW), false);
        eq("цена: пробелы", IvaSearchPatch.priceValue("1 500"), 1500);
        eq("цена: пусто", IvaSearchPatch.priceValue(" "), -1);
        eq("кнопка: 0", IvaSearchPatch.showLabel(0), "Ничего не найдено");
        eq("кнопка: 1", IvaSearchPatch.showLabel(1), "Показать 1" + NB + "инструмент");
        eq("кнопка: 3", IvaSearchPatch.showLabel(3), "Показать 3" + NB + "инструмента");
        eq("кнопка: 11", IvaSearchPatch.showLabel(11), "Показать 11" + NB + "инструментов");
        eq("кнопка: 21", IvaSearchPatch.showLabel(21), "Показать 21" + NB + "инструмент");
        eq("кнопка: 104", IvaSearchPatch.showLabel(104), "Показать 104" + NB + "инструмента");
        eq("кнопка: 5", IvaSearchPatch.showLabel(5), "Показать 5" + NB + "инструментов");
        eq("кнопка: 1000", IvaSearchPatch.showLabel(1000), "Показать 1000" + NB + "инструментов");
        eq("строка города: не выбран", IvaSearchPatch.cityButtonText("", false), "Выберите город");
        eq("строка города: все города", IvaSearchPatch.cityButtonText("", true), "Все города");
        eq("строка города: выбран", IvaSearchPatch.cityButtonText("Аксай", true), "Аксай");
        eq("цена в фильтре: от–до", IvaSearchPatch.priceChip(300, 1200), "300–1" + NB + "200" + NB + "₽/сутки");
        eq("цена в фильтре: только до", IvaSearchPatch.priceChip(-1, 800), "до 800" + NB + "₽/сутки");
        String[] pd = IvaSearchPatch.profileDecision("", "Ivan@Mail.ru", "Таганрог", false);
        eq("профиль: новый город выбирается", pd[1], "Таганрог");
        eq("профиль: запоминается почта и город", pd[0], "ivan@mail.ru\nтаганрог");
        eq("профиль: тот же город не перебивает ручной выбор", IvaSearchPatch.profileDecision(pd[0], "ivan@mail.ru", "Таганрог", false)[1], null);
        eq("профиль: изменённый город выбирается", IvaSearchPatch.profileDecision(pd[0], "ivan@mail.ru", "Аксай", false)[1], "Аксай");
        eq("профиль: ручной выбор в этом запуске важнее", IvaSearchPatch.profileDecision("", "ivan@mail.ru", "Аксай", true)[1], null);
        eq("профиль: города нет — выбор не меняется", IvaSearchPatch.profileDecision("x", "ivan@mail.ru", " ", false)[1], null);
        eq("профиль: другой аккаунт — свой город", IvaSearchPatch.profileDecision(pd[0], "petr@mail.ru", "Таганрог", false)[1], "Таганрог");
        java.util.LinkedHashMap<String, Integer> cc = new java.util.LinkedHashMap<String, Integer>();
        cc.put("Аксай", 2); cc.put("Шахты", 5); cc.put("Новочеркасск", 5);
        java.util.List<String> pc = IvaSearchPatch.pickerCities("", cc, "Таганрог");
        eq("выбор города: сначала профиль", pc.get(0), "Таганрог");
        eq("выбор города: затем где больше инструментов", pc.get(1) + "," + pc.get(2) + "," + pc.get(3), "Новочеркасск,Шахты,Аксай");
        eq("выбор города: без повторов", new java.util.HashSet<String>(pc).size(), pc.size());
        eq("выбор города: весь список", pc.size(), 139);
        java.util.List<String> pr = IvaSearchPatch.pickerCities("рост", cc, "");
        eq("выбор города: поиск по части названия", pr.contains("Ростов-на-Дону") && !pr.contains("Аксай"), true);
        eq("выбор города: ё = е", IvaSearchPatch.pickerCities("семикаракорск", cc, "").size(), 1);
        eq("выбор города: свой город", IvaSearchPatch.customCity("Шахтёрск", IvaSearchPatch.pickerCities("Шахтёрск", cc, "")), "Шахтёрск");
        eq("выбор города: уже в списке", IvaSearchPatch.customCity("аксай", IvaSearchPatch.pickerCities("аксай", cc, "")), "");
        eq("выбор города: улица — не город", IvaSearchPatch.customCity("ул. Ленина", new java.util.ArrayList<String>()), "");
        // 10.46: проверка каждого фильтра — город, категория, подкатегория, цена, доставка, состояние, радиус
        eq("город 10.46: адрес без города — город неизвестен (как сайт), не Новочеркасск", IvaSearchPatch.toolCity("", "ул. Садовая, 1", ""), "");
        eq("город 10.46: адрес выдачи в условиях без города — неизвестен", IvaSearchPatch.toolCity(null, null, "Адрес выдачи: ул. Победы 2"), "");
        eq("город 10.46: неразрывный пробел после «г.»", IvaSearchPatch.toolCity("", "г.\u00a0Шахты, ул. Победы 2", ""), "Шахты");
        eq("город 10.46: узкий неразрывный пробел после «г.»", IvaSearchPatch.city("г.\u202fРостов-на-Дону, ул. Садовая"), "Ростов-на-Дону");
        eq("город 10.46: пробел U+2009 после «г.»", IvaSearchPatch.city("г.\u2009Аксай"), "Аксай");
        eq("город 10.46: пробел U+3000 после «г.»", IvaSearchPatch.city("г.\u3000Батайск"), "Батайск");
        // 10.49: «Фильтр · N» справа от города — столько же условий, сколько «пузырей» под поиском
        eq("фильтр 10.49: ничего не выбрано", String.valueOf(IvaSearchPatch.activeCount("", "", -1, -1, false, 0)), "0");
        eq("фильтр 10.49: только категория", String.valueOf(IvaSearchPatch.activeCount("el", "", -1, -1, false, 0)), "1");
        eq("фильтр 10.49: категория и подкатегория", String.valueOf(IvaSearchPatch.activeCount("el", "drill", -1, -1, false, 0)), "2");
        eq("фильтр 10.49: подкатегория без категории не считается", String.valueOf(IvaSearchPatch.activeCount("", "drill", -1, -1, false, 0)), "0");
        eq("фильтр 10.49: цена «от» и «до» — одно условие", String.valueOf(IvaSearchPatch.activeCount("", "", 300, 1000, false, 0)), "1");
        eq("фильтр 10.49: только «до»", String.valueOf(IvaSearchPatch.activeCount("", "", -1, 1000, false, 0)), "1");
        eq("фильтр 10.49: «от 0» — условие", String.valueOf(IvaSearchPatch.activeCount("", "", 0, -1, false, 0)), "1");
        eq("фильтр 10.49: доставка", String.valueOf(IvaSearchPatch.activeCount("", "", -1, -1, true, 0)), "1");
        eq("фильтр 10.49: два состояния — два условия", String.valueOf(IvaSearchPatch.activeCount("", "", -1, -1, false,
            IvaSearchPatch.COND_NEW | IvaSearchPatch.COND_WORK)), "2");
        eq("фильтр 10.49: посторонние биты состояния не считаются", String.valueOf(IvaSearchPatch.activeCount("", "", -1, -1, false, 8 | 16)), "0");
        eq("фильтр 10.49: всё сразу", String.valueOf(IvaSearchPatch.activeCount("el", "drill", 300, -1, true, 7)), "7");
        eq("фильтр 10.49: null вместо категории", String.valueOf(IvaSearchPatch.activeCount(null, null, -1, -1, false, 0)), "0");
        eq("фильтр 10.49: подпись без условий", IvaSearchPatch.filterButtonText(0), "Фильтр");
        eq("фильтр 10.49: подпись с числом", IvaSearchPatch.filterButtonText(3), "Фильтр \u00b7 3");
        eq("город 10.46: неизвестный город не подходит под выбранный", IvaSearchPatch.extraMatch("", 500, false, 0, "Аксай", -1, -1, false, 0), false);
        eq("город 10.46: неизвестный город виден во «Все города»", IvaSearchPatch.extraMatch("", 500, false, 0, "", -1, -1, false, 0), true);
        eq("цена 10.46: «от» > «до» — меняются местами", IvaSearchPatch.extraMatch("Аксай", 500, false, 0, "", 1000, 300, false, 0), true);
        eq("цена 10.46: «от» > «до», вне диапазона", IvaSearchPatch.extraMatch("Аксай", 1500, false, 0, "", 1000, 300, false, 0), false);
        eq("цена 10.46: normRange", IvaSearchPatch.normRange(1000, 300)[0] + "-" + IvaSearchPatch.normRange(1000, 300)[1], "300-1000");
        eq("цена 10.46: normRange с одной границей", IvaSearchPatch.normRange(1000, -1)[0] + "/" + IvaSearchPatch.normRange(1000, -1)[1], "1000/-1");
        eq("цена 10.46: без цены не попадает под «до»", IvaSearchPatch.extraMatch("Аксай", 0, false, 0, "", -1, 500, false, 0), false);
        eq("цена 10.46: без цены не попадает под «от 0»", IvaSearchPatch.extraMatch("Аксай", 0, false, 0, "", 0, -1, false, 0), false);
        eq("цена 10.46: без цены видна, пока цена не выбрана", IvaSearchPatch.extraMatch("Аксай", 0, false, 0, "", -1, -1, false, 0), true);
        eq("цена 10.46: NaN не цена", IvaSearchPatch.extraMatch("Аксай", Double.NaN, false, 0, "", -1, 900, false, 0), false);
        eq("цена 10.46: дробная на границе", IvaSearchPatch.extraMatch("Аксай", 300.5, false, 0, "", 300, 301, false, 0), true);
        eq("цена 10.46: «12а3» → 123", IvaSearchPatch.priceValue("12а3"), 123);
        eq("цена 10.46: «0007» → 7", IvaSearchPatch.priceValue("0007"), 7);
        eq("цена 10.46: слишком длинное число — без границы", IvaSearchPatch.priceValue("9999999999"), -1);
        eq("цена 10.46: подпись «от 1000 до 300»", IvaSearchPatch.priceChip(1000, 300), "300–1" + NB + "000" + NB + "₽/сутки");
        eq("доставка 10.46: без доставки + фильтр — нет", IvaSearchPatch.extraMatch("Аксай", 500, false, 0, "", -1, -1, true, 0), false);
        eq("доставка 10.46: с доставкой + фильтр — да", IvaSearchPatch.extraMatch("Аксай", 500, true, 0, "", -1, -1, true, 0), true);
        eq("доставка 10.46: фильтр выключен — все", IvaSearchPatch.extraMatch("Аксай", 500, false, 0, "", -1, -1, false, 0), true);
        eq("состояние 10.46: «Б/у» — не указано", IvaSearchPatch.condFromTerms("Состояние: Б/у"), 0);
        eq("состояние 10.46: несколько выбранных — любое из них", IvaSearchPatch.extraMatch("Аксай", 500, false, IvaSearchPatch.COND_GOOD, "", -1, -1, false, 7), true);
        eq("состояние 10.46: «Новый» не подходит под «Рабочее»", IvaSearchPatch.extraMatch("Аксай", 500, false, IvaSearchPatch.COND_NEW, "", -1, -1, false, IvaSearchPatch.COND_WORK), false);
        eq("все фильтры 10.46: сразу город, цена, доставка, состояние", IvaSearchPatch.extraMatch("Шахты", 700, true, IvaSearchPatch.COND_NEW, "шахты", 500, 800, true, IvaSearchPatch.COND_NEW), true);
        eq("все фильтры 10.46: одно условие не выполнено", IvaSearchPatch.extraMatch("Шахты", 900, true, IvaSearchPatch.COND_NEW, "шахты", 500, 800, true, IvaSearchPatch.COND_NEW), false);
        eq("лента 10.46: описание null как у Android — запрос «ul» находит (как штатная лента)", IvaSearchPatch.nativeMatch("", "power", null, "", "Дрель", "null", "root", "", "", "ul"), true);
        eq("лента 10.46: поиск + категория + подкатегория", IvaSearchPatch.nativeMatch("", "power", null, "drill", "Дрель ударная", "", "cat", "power", "drill", "удар"), true);
        eq("лента 10.46: список cats без основной категории", IvaSearchPatch.nativeMatch("", "x", java.util.Arrays.asList("garden"), "", "Тачка", "", "cat", "garden", "", ""), true);
        eq("лента 10.46: пустой список cats", IvaSearchPatch.nativeMatch("", "x", new java.util.ArrayList<String>(), "", "Тачка", "", "cat", "garden", "", ""), false);
        java.util.LinkedHashMap<String, Integer> fc = new java.util.LinkedHashMap<String, Integer>();
        fc.put("power", 3); fc.put("garden", 0); fc.put("empty", 0);
        java.util.List<String> fk = java.util.Arrays.asList("power", "garden", "", "empty", "power", "noCount");
        eq("категории 10.46: пустые скрыты, порядок штатный", String.valueOf(IvaSearchPatch.facetVisible(fk, fc, "")), "[power]");
        eq("категории 10.46: выбранная остаётся, даже пустая", String.valueOf(IvaSearchPatch.facetVisible(fk, fc, "garden")), "[power, garden]");
        eq("категории 10.46: без счётчиков — ничего", String.valueOf(IvaSearchPatch.facetVisible(fk, null, "")), "[]");
        eq("радиус 10.46: варианты заготовки", java.util.Arrays.toString(IvaSearchPatch.RADIUS_TITLES), "[любой, 5 км, 10 км, 25 км, 50 км]");
        // 10.53: картинка-шапка — до строки города без условий, до низа ряда условий с ними; обрезка только снизу
        eq("шапка 10.53: условий нет — до низа строки города + зазор", IvaSearchPatch.bgHeight(0, 240, 330, false, 21), 261);
        eq("шапка 10.53: условия есть — до низа ряда условий + зазор", IvaSearchPatch.bgHeight(0, 240, 330, true, 21), 351);
        eq("шапка 10.53: ряд условий пустой (выше города) — по городу", IvaSearchPatch.bgHeight(0, 240, 0, true, 21), 261);
        eq("шапка 10.53: от верха картинки", IvaSearchPatch.bgHeight(30, 240, 330, true, 0), 300);
        eq("шапка 10.53: не меньше нуля", IvaSearchPatch.bgHeight(500, 240, 0, false, 0), 0);
        float[] c0 = IvaSearchPatch.topCrop(1080, 388, 1080, 400, 388);
        float[] c1 = IvaSearchPatch.topCrop(1080, 260, 1080, 400, 388);
        eq("шапка 10.53: при штатной высоте — как centerCrop", java.util.Arrays.toString(c0), "[1.0, 0.0, -6.0]");
        eq("шапка 10.53: ниже штатной — тот же масштаб и верх (обрезка снизу)", java.util.Arrays.toString(c1), java.util.Arrays.toString(c0));
        float[] c2 = IvaSearchPatch.topCrop(1080, 500, 1080, 400, 388);
        eq("шапка 10.53: выше штатной — заполняет без полос", String.valueOf(c2[0] * 400 >= 500 && c2[0] * 1080 >= 1080 && c2[2] <= 0), "true");
        float[] c3 = IvaSearchPatch.topCrop(1440, 300, 1000, 500, 388);
        eq("шапка 10.53: широкий экран — по ширине, по центру", String.valueOf(c3[0] * 1000 >= 1440 && c3[1] <= 0 && c3[0] * 500 >= 388), "true");
        eq("шапка 10.53: нет картинки — штатно", String.valueOf(IvaSearchPatch.topCrop(1080, 300, -1, -1, 388) == null), "true");
        eq("шторка 10.53: кнопки и поля цены — толщиной со строку поиска",
            IvaSearchPatch.SHEET_BTN_DP + "/" + IvaSearchPatch.PRICE_FIELD_DP, IvaSearchPatch.SEARCH_DP + "/" + IvaSearchPatch.SEARCH_DP);
        // 10.54: чат — переписки по паре «собеседник + инструмент», голосовые, время
        {
            String me = "client@x.ru", adm = "admin@x.ru", T1 = "11111111-1111-1111-1111-111111111111", T2 = "22222222-2222-2222-2222-222222222222";
            java.util.Set<String> admins = new java.util.HashSet<String>(java.util.Arrays.asList(adm, "eclips.ru@mail.ru", "second@x.ru"));
            java.util.List<IvaChatPatch.Msg> ms = new java.util.ArrayList<IvaChatPatch.Msg>();
            ms.add(IvaChatPatch.msg("1", me, adm, "Здравствуйте", "", "", 0, true, "2026-10-10T09:00:00+00:00", ""));
            ms.add(IvaChatPatch.msg("2", me, "Admin@X.ru", "Есть дрель?", "", "", 0, true, "2026-10-11T09:00:00.123456+00:00", T1));
            ms.add(IvaChatPatch.msg("3", adm, me, "Да", "", "", 0, false, "2026-10-11T09:05:00+00:00", T1));
            ms.add(IvaChatPatch.msg("4", me, adm, "Голосовое сообщение", "", "https://x/a.m4a", 7, false, "2026-10-11T09:06:00+00:00", null));
            ms.add(IvaChatPatch.msg("5", me, adm, "А пила?", "", "", 0, false, "2026-10-12T08:00:00Z", T2));
            ms.add(IvaChatPatch.msg("6", adm, me, "Голосовое сообщение", "", "https://x/b.m4a", 3, false, "2026-10-12T08:01:00+00:00", ""));
            java.util.Map<String, String> tags = new java.util.HashMap<String, String>();
            tags.put("6", T1);
            java.util.Map<String, String> tl = IvaChatPatch.assignTools(ms, me, admins, false, tags);
            eq("чат 10.54: текст без tool_id — общий чат", tl.get("1"), "");
            eq("чат 10.54: tool_id из базы", tl.get("2"), T1);
            eq("чат 10.54: голосовое без tool_id — инструмент предыдущего сообщения пары", tl.get("4"), T1);
            eq("чат 10.54: метка телефона важнее соседнего сообщения", tl.get("6"), T1);
            java.util.List<IvaChatPatch.Chat> cs = IvaChatPatch.buildChats(ms, me, admins, false, tags);
            StringBuilder cb = new StringBuilder();
            for (IvaChatPatch.Chat c : cs) cb.append(c.partner).append('|').append(c.tool.isEmpty() ? "-" : c.tool.substring(0, 1)).append('|').append(c.count).append('|').append(c.unread).append(' ');
            eq("чат 10.54: строки списка — по инструменту, свежие сверху, непрочитанные только входящие", cb.toString().trim(),
                "admin@x.ru|1|4|2 admin@x.ru|2|1|0 admin@x.ru|-|1|0");
            eq("чат 10.54: почта собеседника — без учёта регистра, исходная для фильтров", cs.get(0).partnerRaw, "admin@x.ru");
            java.util.List<IvaChatPatch.Msg> th = IvaChatPatch.thread(ms, me, admins, false, tags, "ADMIN@x.ru", T1);
            StringBuilder tb = new StringBuilder();
            for (IvaChatPatch.Msg m : th) tb.append(m.id);
            eq("чат 10.54: переписка по инструменту — по возрастанию времени", tb.toString(), "2346");
            eq("чат 10.54: новая переписка из карточки пуста — в списке её нет", IvaChatPatch.thread(ms, me, admins, false, tags, adm, "33333333-3333-3333-3333-333333333333").size(), 0);
            eq("чат 10.54: своё — справа", IvaChatPatch.ours(ms.get(1), me, admins, false), true);
            eq("чат 10.54: собеседника — слева", IvaChatPatch.ours(ms.get(2), me, admins, false), false);
            // владелец видит переписки администраторов; своя сторона — прокат
            IvaChatPatch.Msg om = IvaChatPatch.msg("7", "second@x.ru", "c2@y.ru", "Ответ", "", "", 0, true, "2026-10-12T08:00:00Z", "");
            eq("чат 10.54: владелец — собеседник клиент", IvaChatPatch.partnerOf(om, "eclips.ru@mail.ru", admins, true), "c2@y.ru");
            eq("чат 10.54: владелец — ответ администратора справа", IvaChatPatch.ours(om, "eclips.ru@mail.ru", admins, true), true);
            eq("чат 10.54: администратор — чужие переписки не его", IvaChatPatch.partnerOf(om, adm, admins, false), "");
            // голосовые этого телефона в открытой переписке
            java.util.Set<String> seen = new java.util.HashSet<String>(java.util.Arrays.asList("1", "2", "3", "5", "6"));
            eq("чат 10.54: новое своё голосовое без tool_id — метить", String.valueOf(IvaChatPatch.voiceToTag(ms, me, adm, T2, seen, tags)), "[4]");
            seen.add("4");
            eq("чат 10.54: уже виденное — не метить", String.valueOf(IvaChatPatch.voiceToTag(ms, me, adm, T2, seen, tags)), "[]");
            eq("чат 10.54: общий чат — не метить", String.valueOf(IvaChatPatch.voiceToTag(ms, me, adm, "", new java.util.HashSet<String>(), tags)), "[]");
            eq("чат 10.54: голосовое собеседника — не метить (только своё)", String.valueOf(IvaChatPatch.voiceToTag(ms, me, adm, T1, new java.util.HashSet<String>(), null)), "[4]");
            eq("чат 10.54: у администратора своё голосовое — метить", String.valueOf(IvaChatPatch.voiceToTag(ms, adm, me, T1, new java.util.HashSet<String>(), null)), "[6]");
            // время
            java.util.TimeZone msk = java.util.TimeZone.getTimeZone("Europe/Moscow");
            long t = IvaChatPatch.parseIso("2026-10-11T21:30:05.5+00:00");
            eq("чат 10.54: UTC → местное время", IvaChatPatch.hm(t, msk), "00:30");
            eq("чат 10.54: дата по местному времени", IvaChatPatch.dmy(t, msk), "12.10.2026");
            eq("чат 10.54: смещение +03:00", IvaChatPatch.parseIso("2026-10-12T00:30:05.5+03:00"), t);
            eq("чат 10.54: Z и пробел вместо T", IvaChatPatch.parseIso("2026-10-11 21:30:05.500Z"), t);
            eq("чат 10.54: мусор — −1", IvaChatPatch.parseIso("вчера"), -1L);
            eq("чат 10.54: нет времени — пусто", IvaChatPatch.hm(-1, msk), "");
            long now = IvaChatPatch.parseIso("2026-10-12T20:00:00Z");
            eq("чат 10.54: сегодня", IvaChatPatch.dayLabel(t, now, msk), "Сегодня");
            eq("чат 10.54: вчера", IvaChatPatch.dayLabel(IvaChatPatch.parseIso("2026-10-11T20:00:00Z"), now, msk), "Вчера");
            eq("чат 10.54: раньше — дата", IvaChatPatch.dayLabel(IvaChatPatch.parseIso("2026-10-01T10:00:00Z"), now, msk), "01.10.2026");
            // подписи
            eq("чат 10.54: имя проката — компания", IvaChatPatch.adminTitle("Прокат Ива", "Иван", "", adm), "Прокат Ива");
            eq("чат 10.54: без компании — ФИО", IvaChatPatch.adminTitle(" ", "Иван Петров", "Иван", adm), "Иван Петров");
            eq("чат 10.54: владелец без имени", IvaChatPatch.adminTitle(null, "null", "", "Eclips.ru@mail.ru"), "Владелец");
            eq("чат 10.54: превью голосового", IvaChatPatch.preview(ms.get(3), true), "Вы: Голосовое сообщение · 0:07");
            eq("чат 10.54: превью фото", IvaChatPatch.preview(IvaChatPatch.msg("8", adm, me, "", "https://x/p.jpg", "", 0, true, "", ""), false), "Фото");
            eq("чат 10.54: превью — одна строка", IvaChatPatch.preview(IvaChatPatch.msg("9", adm, me, "а\n  б", "", "", 0, true, "", ""), false), "а б");
            eq("чат 10.54: буква аватарки", IvaChatPatch.letter(" иван"), "И");
            eq("чат 10.54: кнопка назад", IvaChatPatch.BACK_TITLE, "‹  Назад к чатам");
        }
        // ── 10.55: заявка — ФИО по полям, срок у каждого инструмента, средняя доставка, один прокат ──
        {
            String NB2 = "\u00a0";
            eq("заявка 10.55: ФИО из профиля по полям", String.join("|", IvaOrderPatch.fio("Петров", "Иван", "Сергеевич", "Петров Иван Сергеевич")), "Петров|Иван|Сергеевич");
            eq("заявка 10.55: профиль без имени — разбор строки", String.join("|", IvaOrderPatch.fio("", " ", "null", "Петров Иван Сергеевич")), "Петров|Иван|Сергеевич");
            eq("заявка 10.55: одно слово — имя", String.join("|", IvaOrderPatch.splitName(" Иван ")), "|Иван|");
            eq("заявка 10.55: Иван Петров", String.join("|", IvaOrderPatch.splitName("Иван Петров")), "Петров|Иван|");
            eq("заявка 10.55: Петров Иван", String.join("|", IvaOrderPatch.splitName("Петров Иван")), "Петров|Иван|");
            eq("заявка 10.55: Марина Петрова", String.join("|", IvaOrderPatch.splitName("Марина Петрова")), "Петрова|Марина|");
            eq("заявка 10.55: Иван Сергеевич", String.join("|", IvaOrderPatch.splitName("Иван Сергеевич")), "|Иван|Сергеевич");
            eq("заявка 10.55: Иван Сергеевич Петров", String.join("|", IvaOrderPatch.splitName("Иван Сергеевич Петров")), "Петров|Иван|Сергеевич");
            eq("заявка 10.55: Петрова Анна Ильинична", String.join("|", IvaOrderPatch.splitName("Петрова\u00a0Анна  Ильинична")), "Петрова|Анна|Ильинична");
            eq("заявка 10.55: Оглы — остаток в отчество", String.join("|", IvaOrderPatch.splitName("Алиев Руслан Тофик оглы")), "Алиев|Руслан|Тофик оглы");
            eq("заявка 10.55: пусто/null", String.join("|", IvaOrderPatch.splitName("null")), "||");
            eq("заявка 10.55: штатно всё имя в «Имя» — можно разложить", IvaOrderPatch.pristine("", "Петров Иван", "", "Петров Иван", null), true);
            eq("заявка 10.55: наше прошлое заполнение — можно обновить", IvaOrderPatch.pristine("Петров", "Иван", "", "Петров Иван", new String[] {"Петров", "Иван", ""}), true);
            eq("заявка 10.55: клиент правил поля — не трогать", IvaOrderPatch.pristine("", "Ваня", "", "Петров Иван", null), false);
            eq("заявка 10.55: клиент вписал отчество — не трогать", IvaOrderPatch.pristine("", "Петров Иван", "Сергеевич", "Петров Иван", null), false);
            // сроки
            eq("заявка 10.55: 1 день", IvaOrderPatch.daysText(1), "1 день");
            eq("заявка 10.55: 3 дня", IvaOrderPatch.daysText(3), "3 дня");
            eq("заявка 10.55: 11 дней", IvaOrderPatch.daysText(11), "11 дней");
            eq("заявка 10.55: 22 дня", IvaOrderPatch.daysText(22), "22 дня");
            eq("заявка 10.55: срок не меньше 1", IvaOrderPatch.clampDays(0), 1);
            eq("заявка 10.55: срок не больше 90 (как штатный)", IvaOrderPatch.clampDays(91), 90);
            java.util.List<IvaOrderPatch.Line> ls = new java.util.ArrayList<IvaOrderPatch.Line>();
            ls.add(IvaOrderPatch.line("ta", "Дрель", 500, 2500, true, 30, false, "Adm@x.ru", 3));
            ls.add(IvaOrderPatch.line("tb", "Пила", 700, 0, true, 50, false, "adm@x.ru", 5));
            eq("заявка 10.55: аренда по срокам каждого", IvaOrderPatch.totalRent(ls), 1500 + 3500);
            eq("заявка 10.55: залог", IvaOrderPatch.totalDep(ls), 2500);
            eq("заявка 10.55: p_days — самый длинный срок", IvaOrderPatch.maxDays(ls), 5);
            eq("заявка 10.55: p_tools со сроками", IvaOrderPatch.toolsText(ls), "Дрель (3 дня), Пила (5 дней)");
            eq("заявка 10.55: новый инструмент — срок последнего", IvaOrderPatch.defaultDays(ls), 5);
            // доставка — средняя
            eq("заявка 10.55: цен за км — 2", IvaOrderPatch.rateCount(ls), 2);
            eq("заявка 10.55: средняя доставка = сумма / 2", IvaOrderPatch.averaged("960 ₽ (12.0 км)", 2), "480 ₽ (12.0 км)");
            eq("заявка 10.55: средняя с разделителем", IvaOrderPatch.averaged("1 200 ₽ (15.0 км)", 2), "600 ₽ (15.0 км)");
            eq("заявка 10.55: одна цена — без изменений", IvaOrderPatch.averaged("960 ₽ (12.0 км)", 1), "960 ₽ (12.0 км)");
            eq("заявка 10.55: без цены — без изменений", IvaOrderPatch.averaged("≈12.0 км — цену за километр уточнит владелец", 2), "≈12.0 км — цену за километр уточнит владелец");
            eq("заявка 10.55: средняя округляется", IvaOrderPatch.averaged("1 001 ₽ (11.0 км)", 2), "501 ₽ (11.0 км)");
            eq("заявка 10.55: без окна — onPrice не меняет", IvaOrderPatch.onPrice("960 ₽ (12.0 км)"), "960 ₽ (12.0 км)");
            eq("заявка 10.55: доставка есть у всех — предлагать", IvaOrderPatch.deliveryAllowed(ls), true);
            java.util.List<IvaOrderPatch.Line> mix = new java.util.ArrayList<IvaOrderPatch.Line>(ls);
            mix.add(IvaOrderPatch.line("tc", "Миксер", 300, 0, false, 0, false, "adm@x.ru", 1));
            eq("заявка 10.55: инструмент без доставки — только самовывоз", IvaOrderPatch.deliveryAllowed(mix), false);
            eq("заявка 10.55: подсказка — кто без доставки", IvaOrderPatch.noDeliveryNames(mix), "«Миксер»");
            eq("заявка 10.55: без доставки в среднюю не входит", IvaOrderPatch.rateCount(mix), 2);
            java.util.List<IvaOrderPatch.Line> srv = new java.util.ArrayList<IvaOrderPatch.Line>();
            srv.add(IvaOrderPatch.line("s1", "Заточка", 200, 0, false, 0, true, "", 1));
            eq("заявка 10.55: только услуга — доставки нет", IvaOrderPatch.deliveryAllowed(srv), false);
            eq("заявка 10.55: пустая заявка — доставки нет", IvaOrderPatch.deliveryAllowed(new java.util.ArrayList<IvaOrderPatch.Line>()), false);
            // один прокат
            eq("заявка 10.55: тот же прокат (регистр)", IvaOrderPatch.conflict(IvaOrderPatch.cartOwner(ls), "ADM@x.ru"), false);
            eq("заявка 10.55: другой прокат — спросить о замене", IvaOrderPatch.conflict(IvaOrderPatch.cartOwner(ls), "other@y.ru"), true);
            eq("заявка 10.55: пустая заявка — без вопроса", IvaOrderPatch.conflict(null, "other@y.ru"), false);
            eq("заявка 10.55: пустой владелец — владелец каталога", IvaOrderPatch.conflict("eclips.ru@mail.ru", ""), false);
            // чек и итог
            String rc = IvaOrderPatch.receipt(ls, true, "480 ₽ (12.0 км)");
            eq("заявка 10.55: чек", rc, "— — — ЧЕК (предварительно) — — —\n"
                + "• Дрель — 500 ₽ × 3 дня = 1" + NB2 + "500 ₽\n"
                + "• Пила — 700 ₽ × 5 дней = 3" + NB2 + "500 ₽\n"
                + "Срок: у каждого инструмента свой, до 5 дней\n"
                + "Аренда: 5" + NB2 + "000 ₽\n"
                + "Залог (возвращается при возврате и проверке инструмента): 2" + NB2 + "500 ₽\n"
                + "Доставка: 480 ₽ (12.0 км)\n"
                + "ИТОГО: 7" + NB2 + "980 ₽");
            java.util.List<IvaOrderPatch.Line> one = new java.util.ArrayList<IvaOrderPatch.Line>();
            one.add(IvaOrderPatch.line("tx", "Болгарка", 0, 0, false, 0, false, "", 2));
            eq("заявка 10.55: чек самовывоз, цена не указана", IvaOrderPatch.receipt(one, false, null), "— — — ЧЕК (предварительно) — — —\n"
                + "• Болгарка — 2 дня, цену уточнит прокат\nСрок: 2 дня\nАренда: 0 ₽\nДоставка: самовывоз\nИТОГО: 0 ₽");
            eq("заявка 10.55: итог под формой — доставка", IvaOrderPatch.summary(ls, true, null, "", " "),
                "Аренда (у каждого инструмента свой срок): 5" + NB2 + "000 ₽\nЗалог (возвратный): 2" + NB2 + "500 ₽\n"
                + "Доставка: введите адрес — рассчитаем автоматически\nИТОГО: 7" + NB2 + "500 ₽ + доставка\n"
                + "Залог возвращается при возврате и проверке инструмента.");
            eq("заявка 10.55: итог под формой — самовывоз, один срок, комментарий", IvaOrderPatch.summary(one, false, null, "г. Шахты, ул. Ленина, 1", "к 9 утра"),
                "Аренда (2 дня): 0 ₽\nСамовывоз: г. Шахты, ул. Ленина, 1\nИТОГО: 0 ₽\n"
                + "Залог возвращается при возврате и проверке инструмента.\nКомментарий: к 9 утра");
            eq("заявка 10.55: пустая заявка — пустой итог", IvaOrderPatch.summary(new java.util.ArrayList<IvaOrderPatch.Line>(), false, null, "", ""), "");
            eq("заявка 10.55: кнопка", IvaOrderPatch.ADD_TITLE, "+ Добавить инструмент");
        }
        {
            // 10.56: уведомления — переключатели, новое по id, тексты, обновления
            eq("уведомления 10.56: всё включено — сообщения", IvaNotifyPatch.allowed(true, true, "msg", false), true);
            eq("уведомления 10.56: общий выключен — ничего", IvaNotifyPatch.allowed(false, true, "msg", true), false);
            eq("уведомления 10.56: свой выключен", IvaNotifyPatch.allowed(true, false, "update", true), false);
            eq("уведомления 10.56: заявки — только админу", IvaNotifyPatch.allowed(true, true, "orders", false), false);
            eq("уведомления 10.56: заявки админу", IvaNotifyPatch.allowed(true, true, "orders", true), true);
            eq("уведомления 10.56: обновления и клиенту", IvaNotifyPatch.allowed(true, true, "update", false), true);
            java.util.List<String> now = java.util.Arrays.asList("12", "11", "10");
            eq("уведомления 10.56: первый запуск — только запомнить", IvaNotifyPatch.fresh(null, now).size(), 0);
            java.util.Set<String> seen = IvaNotifyPatch.splitSet("11,10,9");
            eq("уведомления 10.56: новое по id", String.join("|", IvaNotifyPatch.fresh(seen, now)), "12");
            eq("уведомления 10.56: прочитали одно, пришло другое — всё равно новое", String.join("|", IvaNotifyPatch.fresh(IvaNotifyPatch.splitSet("11"), java.util.Arrays.asList("13"))), "13");
            eq("уведомления 10.56: ничего нового", IvaNotifyPatch.fresh(IvaNotifyPatch.splitSet("12,11,10"), now).size(), 0);
            eq("уведомления 10.56: память — свежие первыми", IvaNotifyPatch.joinSet(IvaNotifyPatch.remember(seen, now)), "12,11,10,9");
            eq("уведомления 10.56: пустая память", IvaNotifyPatch.splitSet("").size(), 0);
            eq("уведомления 10.56: нет памяти", IvaNotifyPatch.splitSet(null), null);
            java.util.List<String> many = new java.util.ArrayList<String>();
            for (int i = 0; i < 400; i++) many.add("x" + i);
            eq("уведомления 10.56: память ограничена", IvaNotifyPatch.remember(IvaNotifyPatch.splitSet("old"), many).size(), 400);
            eq("уведомления 10.56: память ограничена, старое вытесняется", IvaNotifyPatch.remember(IvaNotifyPatch.splitSet("old"), many).contains("old"), false);
            eq("уведомления 10.56: память до 300 при малом ответе", IvaNotifyPatch.remember(new java.util.LinkedHashSet<String>(many), now).size(), 300);
            eq("уведомления 10.56: текст сообщения в одну строку", IvaNotifyPatch.messagePreview("  Здравствуйте,\n дрель\u00a0свободна? ", "", ""), "Здравствуйте, дрель свободна?");
            eq("уведомления 10.56: фото", IvaNotifyPatch.messagePreview("", "https://x/y.jpg", "null"), "Фото");
            eq("уведомления 10.56: голосовое", IvaNotifyPatch.messagePreview("null", null, "a.m4a"), "Голосовое сообщение");
            eq("уведомления 10.56: пусто — общий текст", IvaNotifyPatch.messagePreview(null, null, null), "Вам написали в чат — откройте «Чат»");
            StringBuilder lng = new StringBuilder();
            for (int i = 0; i < 30; i++) lng.append("слово ");
            eq("уведомления 10.56: длинный текст обрезается", IvaNotifyPatch.messagePreview(lng.toString(), "", "").length() <= 100, true);
            eq("уведомления 10.56: заголовок сообщений", IvaNotifyPatch.messageTitle(1) + "|" + IvaNotifyPatch.messageTitle(3), "Новое сообщение|Новые сообщения: 3");
            eq("уведомления 10.56: заголовок заявок", IvaNotifyPatch.orderTitle(1) + "|" + IvaNotifyPatch.orderTitle(2), "Новая заявка|Новые заявки: 2");
            eq("уведомления 10.56: заявка — инструменты", IvaNotifyPatch.orderText("Дрель (3 дня), Пила (5 дней)"), "Дрель (3 дня), Пила (5 дней)");
            eq("уведомления 10.56: заявка без списка", IvaNotifyPatch.orderText(""), "Поступила новая заявка на ваш инструмент");
            eq("уведомления 10.56: обновление — новее и не сообщали", IvaNotifyPatch.updateNews(131, 130, 0), true);
            eq("уведомления 10.56: обновление — уже сообщали", IvaNotifyPatch.updateNews(131, 130, 131), false);
            eq("уведомления 10.56: обновление — та же версия", IvaNotifyPatch.updateNews(130, 130, 0), false);
            eq("уведомления 10.56: обновление — сайт старее", IvaNotifyPatch.updateNews(127, 130, 0), false);
            eq("уведомления 10.56: проверка обновлений — первый раз", IvaNotifyPatch.updateDue(0, 1000L), true);
            eq("уведомления 10.94: проверка обновлений — рано (через 4 минуты)", IvaNotifyPatch.updateDue(1000L, 1000L + 4L * 60000), false);
            eq("уведомления 10.94: проверка обновлений — через 5 минут (раньше 6 часов)", IvaNotifyPatch.updateDue(1000L, 1000L + 5L * 60000), true);
            eq("уведомления 10.56: проверка обновлений — часы перевели назад", IvaNotifyPatch.updateDue(5000L, 1000L), true);
            eq("уведомления 10.56: текст обновления", IvaNotifyPatch.updateText("10.57"), "Версия 10.57 — откройте приложение и нажмите «Обновить»");
            eq("уведомления 10.56: текст обновления без номера", IvaNotifyPatch.updateText("null"), "Новая версия — откройте приложение и нажмите «Обновить»");
            eq("уведомления 10.56: каналы", IvaNotifyPatch.channelName("iva_msg") + "|" + IvaNotifyPatch.channelName("iva_orders") + "|" + IvaNotifyPatch.channelName("iva_update"),
                "Новые сообщения|Новые заявки|Обновления приложения");
            eq("уведомления 10.56: ключ памяти по почте", IvaNotifyPatch.key("seen_m", " Ivan@Mail.ru "), "seen_m_ivan@mail.ru");
            eq("уведомления 10.56: пункт кабинета", IvaNotifyPatch.ROW_TITLE, "Настройки");
        }
        {
            // 10.57: живой чат, уведомления в «Чате», первый запуск, значок
            java.util.List<IvaChatPatch.Msg> loc = new java.util.ArrayList<IvaChatPatch.Msg>();
            loc.add(IvaChatPatch.msg("5", "adm@x.ru", "me@x.ru", "Да", "", "", 0, false, "2026-10-13T10:00:00", ""));
            loc.add(IvaChatPatch.msg("6", "me@x.ru", "adm@x.ru", "Спасибо", "", "", 0, true, "2026-10-13T10:01:00", ""));
            java.util.List<String[]> srv = new java.util.ArrayList<String[]>();
            srv.add(new String[]{"6", "true"}); srv.add(new String[]{"5", "false"});
            eq("чат 10.57: ничего нового — не перезагружать", IvaChatPatch.liveChanged(srv, loc), false);
            srv.add(0, new String[]{"7", "false"});
            eq("чат 10.57: пришёл ответ — перезагрузить", IvaChatPatch.liveChanged(srv, loc), true);
            java.util.List<String[]> rd = new java.util.ArrayList<String[]>();
            rd.add(new String[]{"6", "true"}); rd.add(new String[]{"5", "true"});
            eq("чат 10.57: изменилось «прочитано» — перезагрузить", IvaChatPatch.liveChanged(rd, loc), true);
            eq("чат 10.57: пустой ответ — не перезагружать", IvaChatPatch.liveChanged(new java.util.ArrayList<String[]>(), loc), false);
            eq("чат 10.57: проверка раз в 4 с, 30 строк", IvaChatPatch.LIVE_MS + "/" + IvaChatPatch.LIVE_ROWS, "4000/30");
            eq("уведомления 10.57: «Чат» не на экране — уведомлять", IvaNotifyPatch.hiddenInChat(null, "adm@x.ru"), false);
            eq("уведомления 10.57: список чатов на экране — не уведомлять", IvaNotifyPatch.hiddenInChat("", "adm@x.ru"), true);
            eq("уведомления 10.57: открыта эта переписка — не уведомлять", IvaNotifyPatch.hiddenInChat("Adm@X.ru ", "adm@x.ru"), true);
            eq("уведомления 10.57: открыта другая переписка — уведомлять", IvaNotifyPatch.hiddenInChat("other@x.ru", "adm@x.ru"), false);
            java.util.TimeZone tz0 = java.util.TimeZone.getDefault();
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Moscow"));   // телефон в Москве, база — в UTC
            eq("уведомления 10.57: время как в базе (UTC)", IvaNotifyPatch.isoUtc(0L) + "|" + IvaNotifyPatch.isoUtc(1760380931000L), "1970-01-01T00:00:00|2025-10-13T18:42:11");
            java.util.TimeZone.setDefault(tz0);
            eq("уведомления 10.57: первый запуск — за последние сутки",
                String.join("|", IvaNotifyPatch.recent(java.util.Arrays.asList("9", "8", "7", "6"),
                    java.util.Arrays.asList("2026-10-14T09:00:00.5+00:00", "2026-10-13 12:00:00+00", "2026-10-12T08:00:00+00:00", "null"), "2026-10-13T10:00:00")), "9|8");
            eq("уведомления 10.57: окно первого запуска — сутки", IvaNotifyPatch.FIRST_RUN_WINDOW_MS, 86400000L);
            try {
                byte[] png = java.util.Base64.getDecoder().decode(IvaNotifyPatch.STATUS_ICON_PNG);
                eq("уведомления 10.57: значок — PNG", png.length > 8 && (png[1] & 0xff) == 'P' && (png[2] & 0xff) == 'N' && (png[3] & 0xff) == 'G', true);
                java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(png, 16, 10));
                int w = in.readInt(), h = in.readInt(), depth = in.readUnsignedByte(), type = in.readUnsignedByte();
                eq("уведомления 10.57: значок 96×96 с прозрачностью (RGBA)", w + "x" + h + " " + depth + "/" + type, "96x96 8/6");
            } catch (Throwable e) {
                eq("уведомления 10.57: значок читается", String.valueOf(e), "");
            }
            eq("уведомления 10.58: логотип в кружке слева — 192 px", IvaNotifyPatch.AVATAR_PX, 192);
            eq("уведомления 10.58: ярлыки разговоров по каналам", IvaNotifyPatch.SHORTCUT_PREFIX + IvaNotifyPatch.CH_MSG + "|" + IvaNotifyPatch.shortcutLabel(IvaNotifyPatch.CH_MSG)
                + "|" + IvaNotifyPatch.shortcutLabel(IvaNotifyPatch.CH_ORD) + "|" + IvaNotifyPatch.shortcutLabel(IvaNotifyPatch.CH_UPD), "iva_note_iva_msg|Сообщения|Заявки|Обновления");
            eq("уведомления 10.58: ярлык убирается через 5 с", IvaNotifyPatch.SHORTCUT_DROP_MS, 5000L);
            // голосовые 10.58 — размеры как у штатного пузыря v9.8
            // 10.61: «удалить чат» — только у себя
            {
                String hme = "client@x.ru", h1 = "adm@x.ru", h2 = "adm2@x.ru";
                java.util.Set<String> hads = new java.util.HashSet<String>(java.util.Arrays.asList(h1, h2));
                java.util.Map<String, String> hnt = new java.util.HashMap<String, String>();
                java.util.List<IvaChatPatch.Msg> hm = new java.util.ArrayList<IvaChatPatch.Msg>();
                hm.add(IvaChatPatch.msg("1", hme, h1, "Есть дрель?", "", "", 0, true, "2026-10-14T09:00:00+00:00", "T1"));
                hm.add(IvaChatPatch.msg("2", h1, hme, "Да", "", "", 0, false, "2026-10-14T09:01:00.123456+00:00", "T1"));
                hm.add(IvaChatPatch.msg("3", h2, hme, "Добрый день", "", "", 0, false, "2026-10-14T09:02:00+00:00", ""));
                long cut = IvaChatPatch.parseIso("2026-10-14T09:01:00.123456+00:00");
                java.util.Map<String, Long> hid = new java.util.HashMap<String, Long>();
                hid.put(IvaChatPatch.chatKey("Adm@X.ru", "T1"), cut);
                eq("чат 10.61: до удаления — два чата", IvaChatPatch.buildChats(hm, hme, hads, false, hnt, new java.util.HashMap<String, Long>()).size(), 2);
                java.util.List<IvaChatPatch.Chat> hc = IvaChatPatch.buildChats(hm, hme, hads, false, hnt, hid);
                eq("чат 10.61: удалённый у себя пропал, другой остался", hc.size() + "|" + hc.get(0).partner, "1|adm2@x.ru");
                eq("чат 10.61: в переписке удалённого — пусто", IvaChatPatch.thread(hm, hme, hads, false, hnt, h1, "T1", hid).size(), 0);
                hm.add(IvaChatPatch.msg("4", h1, hme, "Вы тут?", "", "", 0, false, "2026-10-14T10:00:00+00:00", "T1"));
                hm.add(IvaChatPatch.msg("5", h1, hme, "", "", "https://x/voice/v5.m4a", 3, false, "2026-10-14T10:01:00+00:00", ""));
                hc = IvaChatPatch.buildChats(hm, hme, hads, false, hnt, hid);
                IvaChatPatch.Chat back = null;
                for (IvaChatPatch.Chat c : hc) if (c.partner.equals(h1)) back = c;
                eq("чат 10.61: написали снова — чат вернулся только с новыми (непрочитанные — тоже только новые)",
                    back == null ? "нет" : back.count + "|" + back.unread + "|" + back.last.id + "|" + back.tool, "2|2|5|T1");
                java.util.List<IvaChatPatch.Msg> ht = IvaChatPatch.thread(hm, hme, hads, false, hnt, h1, "T1", hid);
                eq("чат 10.61: в переписке — только после удаления (голосовое без tool_id — по прежним правилам)", ht.size() + "|" + ht.get(0).id + "|" + ht.get(1).id, "2|4|5");
                eq("чат 10.61: граница включительно, время не разобрано — скрыто",
                    IvaChatPatch.hiddenMsg(hid, h1, "T1", cut) + "|" + IvaChatPatch.hiddenMsg(hid, h1, "T1", cut + 1) + "|" + IvaChatPatch.hiddenMsg(hid, h1, "T1", -1)
                    + "|" + IvaChatPatch.hiddenMsg(hid, h1, "", 0) + "|" + IvaChatPatch.hiddenMsg(null, h1, "T1", 0), "true|false|true|false|false");
                java.util.Map<String, Long> hsrv = new java.util.HashMap<String, Long>(), hloc = new java.util.HashMap<String, Long>();
                hsrv.put("a|", 100L); hsrv.put("b|T", 50L); hloc.put("b|T", 70L); hloc.put("c|", 5L); hloc.put("a|", 10L);
                eq("чат 10.61: сервер + удалённое за этот запуск — позднее", new java.util.TreeMap<String, Long>(IvaChatPatch.mergeHidden(hsrv, hloc)).toString(), "{a|=100, b|T=70, c|=5}");
                eq("чат 10.61: слияние с пустым", IvaChatPatch.mergeHidden(null, null).size() + "|" + IvaChatPatch.mergeHidden(hsrv, null).size(), "0|2");
                IvaChatPatch.hidden.put(IvaChatPatch.chatKey(h1, "T1"), cut);
                eq("чат 10.61: уведомление о скрытом сообщении — список, о новом — переписка",
                    (IvaChatPatch.locate(hm, hme, hads, false, hnt, "2") == null) + "|" + String.join("|", IvaChatPatch.locate(hm, hme, hads, false, hnt, "4")), "true|adm@x.ru|adm@x.ru|T1");
                eq("чат 10.61: buildChats без карты — по общей", IvaChatPatch.buildChats(hm, hme, hads, false, hnt).size(), 2);
                IvaChatPatch.hidden.clear();
                eq("чат 10.61: тексты подтверждения", IvaChatPatch.HIDE_TITLE + " " + IvaChatPatch.HIDE_TEXT,
                    "Удалить чат? Переписка пропадёт только у вас — у собеседника она останется. Если вам напишут снова, чат появится с новыми сообщениями.");
                eq("чат 10.61: без SQL — понятная подсказка, проверка раз в 10 мин", IvaChatPatch.HIDE_NO_DB + "|" + IvaChatPatch.HIDDEN_RETRY_MS, "Удаление чатов заработает после обновления базы|600000");
            }
            // 10.62: «Удалить у меня» / «Удалить у всех» для сообщения
            {
                String dme = "client@x.ru", d1 = "adm@x.ru";
                java.util.Set<String> dads = new java.util.HashSet<String>(java.util.Arrays.asList(d1));
                java.util.Map<String, String> dnt = new java.util.HashMap<String, String>();
                java.util.Map<String, Long> dnone = new java.util.HashMap<String, Long>();
                java.util.List<IvaChatPatch.Msg> dm = new java.util.ArrayList<IvaChatPatch.Msg>();
                dm.add(IvaChatPatch.msg("11", dme, d1, "Есть перфоратор?", "", "", 0, true, "2026-10-14T09:00:00+00:00", "T7"));
                dm.add(IvaChatPatch.msg("12", d1, dme, "Есть", "", "", 0, false, "2026-10-14T09:01:00+00:00", "T7"));
                dm.add(IvaChatPatch.msg("13", dme, d1, "Беру", "", "", 0, true, "2026-10-14T09:02:00+00:00", "T7"));
                java.util.Set<String> dids = new java.util.HashSet<String>(java.util.Arrays.asList("12"));
                java.util.List<IvaChatPatch.Msg> dt = IvaChatPatch.thread(dm, dme, dads, false, dnt, d1, "T7", dnone, dids);
                eq("сообщения 10.62: скрытое у меня — не в переписке, остальные на месте",
                    dt.size() + "|" + dt.get(0).id + "|" + dt.get(1).id, "2|11|13");
                java.util.List<IvaChatPatch.Chat> dc = IvaChatPatch.buildChats(dm, dme, dads, false, dnt, dnone, dids);
                eq("сообщения 10.62: в списке — без скрытого (непрочитанное скрытое не считается)",
                    dc.size() + "|" + dc.get(0).count + "|" + dc.get(0).unread + "|" + dc.get(0).last.id, "1|2|0|13");
                java.util.Set<String> dall = new java.util.HashSet<String>(java.util.Arrays.asList("11", "12", "13"));
                eq("сообщения 10.62: скрыл все — чата нет в списке", IvaChatPatch.buildChats(dm, dme, dads, false, dnt, dnone, dall).size(), 0);
                eq("сообщения 10.62: без скрытых — всё как было", IvaChatPatch.thread(dm, dme, dads, false, dnt, d1, "T7", dnone, null).size()
                    + "|" + IvaChatPatch.buildChats(dm, dme, dads, false, dnt, dnone, new java.util.HashSet<String>()).get(0).count, "3|3");
                IvaChatPatch.hiddenMsgs.add("12");
                eq("сообщения 10.62: старые вызовы берут общий набор; уведомление о скрытом — в список",
                    IvaChatPatch.thread(dm, dme, dads, false, dnt, d1, "T7", dnone).size() + "|" + (IvaChatPatch.locate(dm, dme, dads, false, dnt, "12") == null)
                    + "|" + IvaChatPatch.locate(dm, dme, dads, false, dnt, "13")[2], "2|true|T7");
                IvaChatPatch.hiddenMsgs.clear();
                eq("сообщения 10.62: своё — у меня и у всех, чужое — только у меня",
                    String.join("|", IvaChatPatch.deleteChoices(true)) + " / " + String.join("|", IvaChatPatch.deleteChoices(false)),
                    "Удалить у меня|Удалить у всех / Удалить у меня");
                java.util.Set<String> dset = new java.util.HashSet<String>(java.util.Arrays.asList("b2", " a1 ", "", "x,y", "c3"));
                eq("сообщения 10.62: копия на телефоне — по порядку, без пустых и запятых", IvaChatPatch.msgIdsToStr(dset), "a1,b2,c3");
                eq("сообщения 10.62: копия читается обратно", new java.util.TreeSet<String>(IvaChatPatch.msgIdsFromStr(" a1, ,b2,c3,")).toString()
                    + IvaChatPatch.msgIdsFromStr("").size() + IvaChatPatch.msgIdsFromStr(null).size() + IvaChatPatch.msgIdsToStr(null), "[a1, b2, c3]00");
                eq("сообщения 10.62: тексты", IvaChatPatch.DEL_TITLE + "|" + IvaChatPatch.DEL_NO_DB + "|" + IvaChatPatch.HIDDEN_MSG_PREFS,
                    "Удалить сообщение?|«Удалить у меня» заработает после обновления базы|iva_msg_hidden");
            }
            // 10.62: вкладки раздела логотипа как у админа; «пузырь» сверху закруглён, снизу ровный
            eq("вкладки 10.62: пузырь — верх закруглён, низ ровный", java.util.Arrays.toString(IvaClientPatch.hubBubbleRadii(5f)), "[5.0, 5.0, 5.0, 5.0, 0.0, 0.0, 0.0, 0.0]");
            eq("вкладки 10.62: размеры как у штатных hubTabMy (12dp сверху, линия 3dp через 10dp, углы как bg_card 16dp)",
                IvaClientPatch.HUB_TAB_TOP_DP + "|" + IvaClientPatch.HUB_TAB_LINE_DP + "|" + IvaClientPatch.HUB_TAB_LINE_TOP_DP + "|" + IvaClientPatch.HUB_TAB_RADIUS_DP, "12|3|10|16");
            // 10.62: кабинет клиента без «Позвонить», «Написать в МАКС» (и «Адрес выдачи» — по addrInfoTxt)
            eq("кабинет 10.62: скрываемые строки", String.join("|", IvaClientPatch.PROFILE_HIDE_IDS), "rowCall|rowMax");
            // 10.62: заявки клиента нажимаются — полные данные
            {
                eq("заявки 10.62: статус как на сайте", IvaOrderPatch.ordStatus("") + "|" + IvaOrderPatch.ordStatus(null) + "|" + IvaOrderPatch.ordStatus("new")
                    + "|" + IvaOrderPatch.ordStatus("rented") + "|" + IvaOrderPatch.ordStatus("done") + "|" + IvaOrderPatch.ordStatus(" rented "), "Новая|Новая|Новая|В аренде|Закрыта|В аренде");
                java.util.Map<String, String> or = new java.util.LinkedHashMap<String, String>();
                or.put("id", "42"); or.put("created_at", "2026-10-14T09:00:00+00:00"); or.put("status", "rented");
                or.put("tools", "Перфоратор (3 дн.)"); or.put("days", "3"); or.put("get_method", "Доставка"); or.put("address", "ул. Мира, 1");
                or.put("name", "Иванов Иван"); or.put("phone", "+7 900 000-00-00"); or.put("comment", "Чек: 1500 ₽"); 
                StringBuilder ob = new StringBuilder();
                for (String[] f : IvaOrderPatch.ordFields(or, "14 окт, 12:00")) ob.append(f[0]).append('=').append(f[1]).append(';');
                eq("заявки 10.63: поля по порядку, без имени, телефона и адреса клиента", ob.toString(), "Дата=14 окт, 12:00;Статус=В аренде;Инструменты=Перфоратор (3 дн.);Срок=3 дн.;Получение=Доставка;"
                    + "Комментарий=Чек: 1500 ₽;Номер заявки=42;");
                or.put("address", ""); or.put("comment", "null"); or.put("days", "0"); or.put("status", "");
                ob.setLength(0);
                for (String[] f : IvaOrderPatch.ordFields(or, " ")) ob.append(f[0]).append('=').append(f[1]).append(';');
                eq("заявки 10.62: пустое не показывается; нет даты — как в базе; статус всегда", ob.toString(), "Дата=2026-10-14T09:00:00+00:00;Статус=Новая;Инструменты=Перфоратор (3 дн.);"
                    + "Получение=Доставка;Номер заявки=42;");
                or.put("days", "по инструментам");
                eq("заявки 10.62: срок не числом — как есть", IvaOrderPatch.ordFields(or, "x").get(3)[0] + "=" + IvaOrderPatch.ordFields(or, "x").get(3)[1], "Срок=по инструментам");
                java.util.List<String> od = java.util.Arrays.asList("14 окт", "14 окт", "13 окт"), ot = java.util.Arrays.asList("Дрель", "Дрель", "Дрель");
                eq("заявки 10.62: карточка находит свою заявку, одинаковые — по номеру среди одинаковых",
                    IvaOrderPatch.ordFind(od, ot, IvaOrderPatch.ordKey("14 окт", "Дрель", 0)) + "|" + IvaOrderPatch.ordFind(od, ot, IvaOrderPatch.ordKey("14 окт", "Дрель", 1))
                    + "|" + IvaOrderPatch.ordFind(od, ot, IvaOrderPatch.ordKey("13 окт", "Дрель", 0)) + "|" + IvaOrderPatch.ordFind(od, ot, IvaOrderPatch.ordKey("14 окт", "Дрель", 2))
                    + "|" + IvaOrderPatch.ordFind(od, ot, IvaOrderPatch.ordKey("12 окт", "Пила", 0)) + "|" + IvaOrderPatch.ordFind(od, ot, IvaOrderPatch.ordKey(" 13 окт ", "Дрель ", 0)), "0|1|2|-1|-1|2");
                eq("заявки 10.63: запросы (100 последних; части заявки — прокат и статус); шторка 85%", IvaOrderPatch.ORD_PATH + "|" + IvaOrderPatch.ORD_PATH_PARTS + "|" + IvaOrderPatch.ORD_SHEET,
                    "/rest/v1/orders?select=*&order=created_at.desc&limit=100|/rest/v1/orders?select=*,order_parts(owner_email,status)&order=created_at.desc&limit=100|0.85");
                eq("заявки 10.63: в карточке нет полей клиента", String.join("|", IvaOrderPatch.ORD_COLS), "id|created_at|status|tools|days|get_method|comment");
                // 10.63: статус по частям заявки («Выдал»/«Вернули» прокат отмечает в части)
                eq("заявки 10.63: статус по частям", IvaOrderPatch.ordEffStatus("new", "closed,closed") + "|" + IvaOrderPatch.ordEffStatus("new", "closed,rented")
                    + "|" + IvaOrderPatch.ordEffStatus("closed", "rented,new") + "|" + IvaOrderPatch.ordEffStatus("rented", "") + "|" + IvaOrderPatch.ordEffStatus(" ", null)
                    + "|" + IvaOrderPatch.ordEffStatus("new", " closed , ") + "|" + IvaOrderPatch.ordEffStatus("Closed", ""), "closed|rented|new|rented|new|closed|closed");
                java.util.Map<String, String> oc = new java.util.HashMap<String, String>();
                oc.put("status", "new"); oc.put(IvaOrderPatch.ORD_PARTS, "closed");
                boolean oc1 = IvaOrderPatch.ordClosed(oc);
                oc.put(IvaOrderPatch.ORD_PARTS, "closed,new");
                boolean oc2 = IvaOrderPatch.ordClosed(oc);
                oc.put(IvaOrderPatch.ORD_PARTS, ""); oc.put("status", "done");
                boolean oc3 = IvaOrderPatch.ordClosed(oc);
                oc.put("status", "");
                eq("заявки 10.63: в архив — только закрытые", oc1 + "|" + oc2 + "|" + oc3 + "|" + IvaOrderPatch.ordClosed(oc), "true|false|true|false");
                eq("заявки 10.63: статусы частей — все по порядку, пустой = new",
                    IvaOrderPatch.ordJoinAll(java.util.Arrays.asList("closed", "", null, " Rented ")) + "|" + IvaOrderPatch.ordJoinAll(null), "closed,new,new,rented|");
                eq("заявки 10.63: прокаты — без повторов и пустых",
                    IvaOrderPatch.ordJoin(java.util.Arrays.asList("A@x.ru", "a@x.ru ", "", null, "b@x.ru", "c,d")), "a@x.ru,b@x.ru");
                java.util.List<java.util.Map<String, String>> om = new java.util.ArrayList<java.util.Map<String, String>>();
                java.util.Map<String, String> om1 = new java.util.HashMap<String, String>(); om1.put("id", "7"); om1.put(IvaOrderPatch.ORD_OWNERS, ""); om1.put(IvaOrderPatch.ORD_PARTS, "");
                java.util.Map<String, String> om2 = new java.util.HashMap<String, String>(); om2.put("id", "8"); om2.put(IvaOrderPatch.ORD_OWNERS, "q@x.ru"); om2.put(IvaOrderPatch.ORD_PARTS, "new");
                om.add(om1); om.add(om2);
                java.util.Map<String, String[]> omp = new java.util.HashMap<String, String[]>();
                omp.put("7", new String[]{"p@x.ru", "closed,closed"});
                IvaOrderPatch.ordMergeParts(om, omp);
                eq("заявки 10.63: части из функции базы подставляются, остальные как были",
                    om1.get(IvaOrderPatch.ORD_OWNERS) + "|" + om1.get(IvaOrderPatch.ORD_PARTS) + "|" + om2.get(IvaOrderPatch.ORD_OWNERS) + "|" + om2.get(IvaOrderPatch.ORD_PARTS)
                    + "|" + String.join(",", IvaOrderPatch.ordOwnersOf(om)), "p@x.ru|closed,closed|q@x.ru|new|p@x.ru,q@x.ru");
                // 10.63: прокат в подробностях — не данные клиента
                java.util.Map<String, String> pa = new java.util.HashMap<String, String>();
                pa.put("company", "Прокат-НВ"); pa.put("full_name", "Пётр"); pa.put("phone", "+7 900 111-22-33"); pa.put("address", "ул. Ленина, 5"); pa.put("work_hours", "9–18");
                StringBuilder pb = new StringBuilder();
                for (String[] f : IvaOrderPatch.ordProkatFields(pa)) pb.append(f[0]).append('=').append(f[1]).append(';');
                eq("заявки 10.63: прокат — название, контакт, телефон, адрес выдачи, часы", pb.toString(),
                    "Прокат=Прокат-НВ;Контактное лицо=Пётр;Телефон=+7 900 111-22-33;Адрес выдачи=ул. Ленина, 5;Часы работы=9–18;");
                pa.put("company", "null"); pa.put("work_hours", ""); pa.put("address", null);
                pb.setLength(0);
                for (String[] f : IvaOrderPatch.ordProkatFields(pa)) pb.append(f[0]).append('=').append(f[1]).append(';');
                eq("заявки 10.63: нет компании — имя; пустое не показывается", pb.toString(), "Прокат=Пётр;Телефон=+7 900 111-22-33;");
                pa.put("company", "ПЁТР");
                eq("заявки 10.63: имя как компания — без повтора; нет данных — пусто", IvaOrderPatch.ordProkatFields(pa).size() + "|" + IvaOrderPatch.ordProkatFields(null).size(), "2|0");
                eq("заявки 10.63: названия инструментов из заявки (для поиска проката по каталогу)",
                    String.join("|", IvaOrderPatch.ordToolNames("Дрель Bosch (3 дн.), Пила ×2, Лестница x 1; Пила, Шуруповёрт (1 дн.)\nА")),
                    "дрель bosch|пила|лестница|шуруповёрт");
                eq("заявки 10.63: пусто — пусто", IvaOrderPatch.ordToolNames(null).size() + "|" + IvaOrderPatch.ordToolNames(" , ").size(), "0|0");
                eq("заявки 10.63: архив — запросы", IvaOrderPatch.ARCH_PATH + "|" + IvaOrderPatch.ARCH_RPC + "|" + IvaOrderPatch.ORD_PARTS_RPC,
                    "/rest/v1/app_order_archived?select=order_id&limit=1000|/rest/v1/rpc/iva_archive_order|/rest/v1/rpc/iva_my_order_parts");
                eq("заявки 10.63: архив — подписи", IvaOrderPatch.ARCH_TITLE + "|" + IvaOrderPatch.ARCH_ON + "|" + IvaOrderPatch.ARCH_OFF + "|" + IvaOrderPatch.ARCH_ONLY_CLOSED,
                    "Архив заявок|В архив|Вернуть из архива|В архив можно убрать только закрытую заявку");
            }
            // 10.66: «Состояние» в форме инструмента — строкой условий, как на сайте
            eq("состояние 10.66: дописывается последней строкой, старая заменяется, «Адрес выдачи» остаётся",
                IvaPatch.condApply("Паспорт\nАдрес выдачи: ул. Ленина 1\nСостояние: Новый", 2) + "#" + IvaPatch.condApply("", 0) + "#" + IvaPatch.condApply("Паспорт\n\n", -1)
                + "#" + IvaPatch.condApply(null, 1),
                "Паспорт\nАдрес выдачи: ул. Ленина 1\nСостояние: Рабочее#Состояние: Новый#Паспорт\nСостояние: Хорошее#Состояние: Хорошее");
            eq("состояние 10.66: читается тем же разбором, что фильтр и сайт",
                IvaPatch.condIndex(IvaPatch.condApply("x", 0)) + "|" + IvaPatch.condIndex(IvaPatch.condApply("x", 1)) + "|" + IvaPatch.condIndex(IvaPatch.condApply("x", 2))
                + "|" + IvaPatch.condIndex("") + "|" + IvaPatch.condIndex(null) + "|" + IvaPatch.condIndex("состояние:   рабочее"),
                "0|1|2|-1|-1|2");
            eq("состояние 10.66: из поля убирается только строка состояния", IvaPatch.condStrip("Состояние: Новый") + "|" + IvaPatch.condStrip("А\n  состояние: хорошее\nБ") + "|" + IvaPatch.condStrip(null),
                "|А\nБ|");
            eq("состояние 10.66: кнопки — как на сайте, по умолчанию «Хорошее»", String.join("/", IvaPatch.COND_TITLES) + "|" + IvaPatch.COND_TITLES[IvaPatch.COND_DEFAULT], "Новый/Хорошее/Рабочее|Хорошее");
            // 10.66: картинки инструментов на карточке заявки
            eq("заявки 10.66: картинка — по названию, у проката заявки в первую очередь",
                IvaOrderPatch.ordPicIdx(java.util.Arrays.asList("дрель", "пила", "лестница"), java.util.Arrays.asList("Дрель", "Пила", "дрель ", "ПИЛА"),
                    java.util.Arrays.asList("a@x.ru", "b@x.ru", "B@x.ru", "a@x.ru"), java.util.Arrays.asList("b@x.ru")).toString()
                + IvaOrderPatch.ordPicIdx(java.util.Arrays.asList("дрель", "пила"), java.util.Arrays.asList("Дрель", "Пила", "дрель"),
                    java.util.Arrays.asList("a@x.ru", "b@x.ru", "b@x.ru"), new java.util.ArrayList<String>()).toString(),
                "[2, 1, -1][0, 1]");
            StringBuilder picPlan = new StringBuilder();
            for (int pn : new int[]{0, 1, 2, 3, 5}) { int[] pp = IvaOrderPatch.ordPicsPlan(pn); picPlan.append(pp[0]).append('/').append(pp[1]).append(' '); }
            eq("заявки 10.66: до двух картинок, на второй «+N»", picPlan.toString().trim(), "0/0 1/0 2/0 2/2 2/4");
            // 10.67: картинки в заявках у админа — карточки в порядке штатного renderAdmOrders
            java.util.List<String> admK = java.util.Arrays.asList("ivan", "petr", "ivan", null);
            java.util.List<String> admT = java.util.Arrays.asList("Дрель (3 дня)", "Пила (1 день)", "Перфоратор (2 дня), Дрель (1 день)", "null");
            java.util.List<String> admO = java.util.Arrays.asList("me@x.ru", "me@x.ru", "null", "me@x.ru");
            StringBuilder admGot = new StringBuilder();
            for (java.util.Map<String, String> admR : IvaOrderPatch.admPicRows(admK, admT, admO, false, ""))
                admGot.append(admR.get("tools")).append(" / ").append(admR.get(IvaOrderPatch.ORD_OWNERS)).append(" | ");
            eq("админ 10.67: по клиенту — инструменты всех его заявок, порядок клиентов как в списке",
                admGot.toString(), "Дрель (3 дня), Перфоратор (2 дня), Дрель (1 день) / me@x.ru | Пила (1 день) / me@x.ru |  / me@x.ru | ");
            StringBuilder admDr = new StringBuilder();
            for (java.util.Map<String, String> admR : IvaOrderPatch.admPicRows(admK, admT, admO, true, "ivan")) admDr.append(admR.get("tools")).append(" | ");
            eq("админ 10.67: заявки клиента — по карточке на заявку, по порядку",
                admDr.toString(), "Дрель (3 дня) | Перфоратор (2 дня), Дрель (1 день) | ");
            eq("админ 10.67: картинок — по разным названиям (дрель дважды — одна)",
                IvaOrderPatch.ordToolNames(IvaOrderPatch.admPicRows(admK, admT, admO, false, "").get(0).get("tools")).toString(), "[дрель, перфоратор]");
            eq("админ 10.67: нет такого клиента — ничего не рисуем",
                IvaOrderPatch.admPicRows(admK, admT, admO, true, "nobody").size() + "/" + IvaOrderPatch.admPicRows(new java.util.ArrayList<String>(), admT, admO, false, "").size(), "0/0");
            // 10.67: выбранная подкатегория — белым; подсказка — серым
            eq("подкатегория 10.67: выбрано — только при непустом ключе",
                IvaPatch.pickChosen("perf") + "/" + IvaPatch.pickChosen("") + "/" + IvaPatch.pickChosen("  ") + "/" + IvaPatch.pickChosen(null) + "/" + IvaPatch.pickChosen("null"),
                "true/false/false/false/false");
            StringBuilder pickF = new StringBuilder();
            for (String[] f : IvaPatch.PICK_FIELDS) pickF.append(f[0]).append('=').append(f[1]).append(' ');
            eq("подкатегория 10.67: обе формы — добавление и правка", pickF.toString().trim(), "atCat=atCatKey atSub=atSubKey teCat=editToolCat teSub=editToolSub");
            // 10.67: кабинет клиента — «Позвонить» / «Написать в МАКС» прячем и после штатного renderProfile
            eq("кабинет 10.67: у клиента видна строка — прятать; у админа — не трогать",
                IvaClientPatch.contactsLeak(false, new boolean[]{false, true}) + "/" + IvaClientPatch.contactsLeak(false, new boolean[]{false, false})
                + "/" + IvaClientPatch.contactsLeak(true, new boolean[]{false, false}) + "/" + IvaClientPatch.contactsLeak(true, new boolean[]{true, true})
                + "/" + IvaClientPatch.contactsLeak(false, null),
                "true/false/false/false/false");
            // 10.68: подробности заявки у админа
            java.util.Map<String, String> admD = new java.util.LinkedHashMap<String, String>();
            admD.put(IvaOrderPatch.ADM_ROW, "1"); admD.put("id", "57"); admD.put("status", "rented"); admD.put("tools", "Дрель (3 дня)");
            admD.put("get_method", "Доставка: ул. Мира, 5 — 300 ₽"); admD.put("comment", ""); admD.put("rent_sum", "1200.0"); admD.put("fee", "60");
            admD.put("created_at", "2026-10-14T10:00:00"); admD.put("name", "Иван Петров"); admD.put("phone", "+7 900 000-00-00"); admD.put("user_email", "");
            StringBuilder admF = new StringBuilder();
            for (String[] f : IvaOrderPatch.admFields(admD, "14 окт, 13:00")) admF.append(f[0]).append('=').append(f[1]).append(" | ");
            eq("админ 10.68: шторка — дата, статус, инструменты, получение, аренда с комиссией, номер; пустое не показываем",
                admF.toString(), "Дата=14 окт, 13:00 | Статус=В аренде | Инструменты=Дрель (3 дня) | Получение=Доставка: ул. Мира, 5 — 300 ₽ | Аренда=1200 ₽ · комиссия 5 %: 60 ₽ | Номер заявки=57 | ");
            StringBuilder admC = new StringBuilder();
            for (String[] f : IvaOrderPatch.admClientFields(admD)) admC.append(f[0]).append('=').append(f[1]).append(" | ");
            eq("админ 10.68: блок «Клиент» — имя и телефон (нажимается), пустой e-mail не показываем", admC.toString(), "Имя=Иван Петров | Телефон=+7 900 000-00-00 | ");
            eq("админ 10.68: суммы", IvaOrderPatch.admMoney("1200") + "|" + IvaOrderPatch.admMoney("99.5") + "|" + IvaOrderPatch.admMoney("null") + "|" + IvaOrderPatch.admMoney("abc") + "|" + IvaOrderPatch.admMoney(null),
                "1200 ₽|99,50 ₽|||");
            eq("админ 10.68: заявки клиента — номера частей по порядку карточек",
                IvaOrderPatch.admDrillIdx(java.util.Arrays.asList("ivan", "petr", "ivan", null), "ivan") + "/" + IvaOrderPatch.admDrillIdx(java.util.Arrays.asList("a", null), "") + "/" + IvaOrderPatch.admDrillIdx(java.util.Arrays.asList("a"), "b"),
                "[0, 2]/[1]/[]");
            eq("админ 10.68: статусы как у клиента", IvaOrderPatch.ordStatus("new") + "/" + IvaOrderPatch.ordStatus("rented") + "/" + IvaOrderPatch.ordStatus("closed"), "Новая/В аренде/Закрыта");
            // 10.65: уведомления, этап 2 — когда приложение закрыто
            String bgJwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiAidTEiLCAiZXhwIjogMTc2MDAwMDAwMCwgImVtYWlsIjogItCwQGIucnUifQ.sig";
            eq("уведомления 10.65: срок токена из JWT (base64url, кириллица, без «=»)", IvaNotifyPatch.jwtExp(bgJwt) + "|" + IvaNotifyPatch.jwtExp("плохой") + "|" + IvaNotifyPatch.jwtExp(null) + "|" + IvaNotifyPatch.jwtExp("a.%%%.b"),
                "1760000000|0|0|0");
            eq("уведомления 10.65: обновлять токен — истёк, истекает за 2 мин или срок неизвестен",
                IvaNotifyPatch.needRefresh(1760000000L, 1760000000L * 1000 - 600000) + "|" + IvaNotifyPatch.needRefresh(1760000000L, 1760000000L * 1000 - 60000)
                + "|" + IvaNotifyPatch.needRefresh(1760000000L, 1760000000L * 1000 + 1) + "|" + IvaNotifyPatch.needRefresh(0, 5),
                "false|true|true|true");
            eq("уведомления 10.65: фон — только когда окно закрыто и общий переключатель включён",
                IvaNotifyPatch.bgShouldRun(false, true) + "|" + IvaNotifyPatch.bgShouldRun(true, true) + "|" + IvaNotifyPatch.bgShouldRun(false, false),
                "true|false|false");
            eq("уведомления 10.65: rt в JSON экранируется", IvaNotifyPatch.jsonStr("a\"b\\c\n") + "|" + IvaNotifyPatch.jsonStr(null), "\"a\\\"b\\\\c\\u000a\"|\"\"");
            eq("уведомления 10.65: раз в 15 минут, своя задача", IvaNotifyPatch.BG_EVERY_MS + "|" + IvaNotifyPatch.BG_JOB_ID + "|" + IvaNotifyPatch.SESSION_PREFS, "900000|7210|prokat_session");
            eq("уведомления 10.65: в «Настройках» сказано, что приходят и когда закрыто", IvaNotifyPatch.BG_NOTE.contains("когда приложение закрыто") && IvaNotifyPatch.BG_NOTE.contains("15 минут")
                && !IvaNotifyPatch.BG_NOTE.contains("в следующей версии"), true);
            // 10.65: «Доставка» в форме инструмента — выключатель
            eq("доставка 10.65: подпись по штатному значению", IvaPatch.delSub(true) + "|" + IvaPatch.delSub(false) + "|" + IvaPatch.DEL_TITLE,
                "Есть — укажите цену ниже, ₽ за 1 км (в одну сторону)|Нет — только самовывоз. Включите, чтобы указать цену|Доставка");
            // 10.64: «Заявки» — вкладки «Активные / Закрытые», всё нажимается
            eq("заявки 10.64: активные — новые и в аренде, закрытые — закрытые; архив — нигде",
                IvaOrderPatch.ordOnTab(false, false, 0) + "|" + IvaOrderPatch.ordOnTab(true, false, 0) + "|" + IvaOrderPatch.ordOnTab(true, false, 1)
                + "|" + IvaOrderPatch.ordOnTab(false, false, 1) + "|" + IvaOrderPatch.ordOnTab(true, true, 1) + "|" + IvaOrderPatch.ordOnTab(false, true, 0),
                "true|false|true|false|false|false");
            eq("заявки 10.64: вкладки", String.join("|", IvaOrderPatch.ORD_TABS), "Активные заявки|Закрытые заявки");
            eq("заявки 10.64: только свои — как на сайте", IvaOrderPatch.ordPath("/o?x=1", " Ivan+1@Mail.ru ") + "|" + IvaOrderPatch.ordPath("/o?x=1", "") + "|" + IvaOrderPatch.ordPath("/o?x=1", null),
                "/o?x=1&user_email=eq.Ivan%2B1%40Mail.ru|/o?x=1|/o?x=1");
            {
                java.util.Map<String, String> cr = IvaOrderPatch.ordCardRow("14 окт, 12:00", "Дрель (3 дн.)", "Самовывоз");
                StringBuilder cb = new StringBuilder();
                for (String[] f : IvaOrderPatch.ordFields(cr, cr.get(IvaOrderPatch.ORD_NICE))) cb.append(f[0]).append('=').append(f[1]).append(';');
                eq("заявки 10.64: по штатной карточке — что на ней, без выдуманного статуса", cb.toString(), "Дата=14 окт, 12:00;Инструменты=Дрель (3 дн.);Получение=Самовывоз;");
                eq("заявки 10.64: по карточке прокат ищется по инструментам", String.join("|", IvaOrderPatch.ordToolNames(cr.get("tools"))), "дрель");
            }
            // 10.63: «Назад» на телефоне
            eq("назад 10.63: фильтр, «Заявка отправлена», вход", IvaPatch.backAction(true, true, true, true, "home", true) + "|" + IvaPatch.backAction(false, true, true, true, "orders", false)
                + "|" + IvaPatch.backAction(false, false, false, true, "orders", false), "panel|success|background");
            eq("назад 10.63: кнопка «‹» на экране, раздел логотипа, вкладка, главная",
                IvaPatch.backAction(false, false, true, true, "home", false) + "|" + IvaPatch.backAction(false, false, true, false, "home", true)
                + "|" + IvaPatch.backAction(false, false, true, false, "chat", false) + "|" + IvaPatch.backAction(false, false, true, false, "home", false)
                + "|" + IvaPatch.backAction(false, false, true, false, null, false), "button|home|home|background|home");
            eq("назад 10.63: метка кнопки", IvaPatch.BACK_MARK, "‹");
            // 10.63: вопрос «Удалить сообщение» в стиле приложения
            eq("удаление 10.63: подсказка — своё / чужое", IvaChatPatch.deleteHint(true) + "|" + IvaChatPatch.deleteHint(false),
                "«У меня» — сообщение останется у собеседника. «У всех» — пропадёт у обоих.|Сообщение пропадёт только у вас — у собеседника оно останется.");
            eq("удаление 10.63: пузыри 50dp, углы 14/20dp, ширина до 360dp", IvaClientPatch.CHOICE_BUBBLE_DP + "|" + IvaClientPatch.CHOICE_RADIUS_DP + "|" + IvaClientPatch.CHOICE_CARD_RADIUS_DP
                + "|" + IvaClientPatch.CHOICE_MAX_DP + "|" + IvaClientPatch.CHOICE_CANCEL, "50|14|20|360|Отмена");
            // 10.60: голосовые как в ВК — кнопка, волна по громкости, длительность; цвета приложения
            eq("голосовые 10.60: волна растёт с длительностью (1080 px, 2.75)", IvaChatPatch.voiceWavePx(0, 1080, 2.75f) + "|" + IvaChatPatch.voiceWavePx(10, 1080, 2.75f) + "|" + IvaChatPatch.voiceWavePx(120, 1080, 2.75f), "198|281|454");
            eq("голосовые 10.60: волна не шире 190dp и 42% экрана, не уже 72dp", IvaChatPatch.voiceWavePx(600, 3000, 1f) + "|" + IvaChatPatch.voiceWavePx(600, 300, 1f) + "|" + IvaChatPatch.voiceWavePx(-5, 100, 1f), "190|126|72");
            eq("голосовые 10.60: столбиков влезает (2dp + 1,5dp)", IvaChatPatch.voiceBarCount(198, 5.5f, 4.125f) + "|" + IvaChatPatch.voiceBarCount(0, 2f, 1f) + "|" + IvaChatPatch.voiceBarCount(1, 2f, 1f), "21|0|1");
            eq("голосовые 10.60: столбики — максимум по участку", java.util.Arrays.toString(IvaChatPatch.voiceBars(new float[]{0.9f, 0.1f, 0.4f, 0.2f}, 2)), "[0.9, 0.4]");
            eq("голосовые 10.60: столбиков больше, чем уровней", java.util.Arrays.toString(IvaChatPatch.voiceBars(new float[]{0.5f, 1f}, 4)), "[0.5, 0.5, 1.0, 1.0]");
            eq("голосовые 10.60: волны ещё нет — ровная линия", java.util.Arrays.toString(IvaChatPatch.voiceBars(null, 3)) + java.util.Arrays.toString(IvaChatPatch.voiceBars(new float[]{1f}, 0)), "[0.0, 0.0, 0.0][]");
            float[] vp = IvaChatPatch.voicePeaks(new int[]{0, 0, 1000, 1000, 8000, 8000, 32000, 32000}, 8, 4);
            eq("голосовые 10.60: громкость от самого громкого, тихое приподнято", String.format(java.util.Locale.ROOT, "%.2f|%.2f|%.2f|%.2f", vp[0], vp[1], vp[2], vp[3]), "0.00|0.09|0.38|1.00");
            eq("голосовые 10.60: тишина / пусто — нули", java.util.Arrays.toString(IvaChatPatch.voicePeaks(new int[]{0, 0}, 2, 2)) + java.util.Arrays.toString(IvaChatPatch.voicePeaks(null, 0, 2)) + IvaChatPatch.voicePeaks(new int[]{5}, 1, 0).length, "[0.0, 0.0][0.0, 0.0]0");
            eq("голосовые 10.60: короткая запись — уровней всё равно 64", IvaChatPatch.voicePeaks(new int[]{5, 10}, 2, IvaChatPatch.VOICE_PEAKS).length, 64);
            float[] vs = IvaChatPatch.voicePeaks(new int[]{4, 8}, 2, 4);
            eq("голосовые 10.60: короткая запись растягивается без провалов", String.format(java.util.Locale.ROOT, "%.2f|%.2f|%.2f|%.2f", vs[0], vs[1], vs[2], vs[3]), "0.62|0.62|1.00|1.00");
            eq("голосовые 10.60: count больше массива не ломает", IvaChatPatch.voicePeaks(new int[]{4, 8}, 9, 2)[1], 1.0f);
            float[] vr = new float[64]; vr[0] = 1f; vr[1] = 0.5f; vr[63] = 0.2f;
            float[] vb2 = IvaChatPatch.voicePeaksFrom(IvaChatPatch.voicePeaksBytes(vr));
            eq("голосовые 10.60: уровни в кэш и обратно", String.format(java.util.Locale.ROOT, "%.2f|%.2f|%.2f|%.2f", vb2[0], vb2[1], vb2[2], vb2[63]), "1.00|0.50|0.00|0.20");
            eq("голосовые 10.60: битый кэш волны — заново", IvaChatPatch.voicePeaksFrom(new byte[10]) == null && IvaChatPatch.voicePeaksFrom(null) == null, true);
            eq("голосовые 10.60: перемотка по волне", IvaChatPatch.voiceSeekMs(0.5f, 10000) + "|" + IvaChatPatch.voiceSeekMs(1.4f, 10000) + "|" + IvaChatPatch.voiceSeekMs(-1f, 10000) + "|" + IvaChatPatch.voiceSeekMs(0.5f, 0), "5000|9900|0|0");
            eq("голосовые 10.60: подпись — длительность, во время воспроизведения — сколько прошло",
                IvaChatPatch.voiceTimeLabel(-1, 75) + "|" + IvaChatPatch.voiceTimeLabel(0, 75) + "|" + IvaChatPatch.voiceTimeLabel(12500, 75) + "|" + IvaChatPatch.voiceTimeLabel(-1, 0), "1:15|1:15|0:12|");
            eq("голосовые 10.60: доля прослушанного", IvaChatPatch.voiceProgress(2500, 10000) + "|" + IvaChatPatch.voiceProgress(12000, 10000) + "|" + IvaChatPatch.voiceProgress(500, 0) + "|" + IvaChatPatch.voiceProgress(-1, 100), "0.25|1.0|0.0|0.0");
            eq("голосовые 10.60: размеры как в ВК", IvaChatPatch.VOICE_BTN_DP + "|" + IvaChatPatch.VOICE_BAR_DP + "|" + IvaChatPatch.VOICE_GAP_DP + "|" + IvaChatPatch.VOICE_WAVE_H_DP + "|" + IvaChatPatch.VOICE_PEAKS, "40|2.0|1.5|24.0|64");
            eq("голосовые 10.60: непрослушанное — полупрозрачный белый (10.96: пузырь тёмный у всех)", Integer.toHexString(IvaChatPatch.VOICE_REST_OTHER), "59ffffff");
            String vn = IvaChatPatch.voiceCacheName("https://x.supabase.co/storage/v1/object/public/voice/v1760-123.m4a?t=1");
            eq("голосовые 10.58: имя в кэше — из ссылки", vn.endsWith("-v1760-123.m4a"), true);
            eq("голосовые 10.58: имя в кэше без опасных символов", IvaChatPatch.voiceCacheName("https://x/voice/../a b%.m4a").matches("[0-9a-f]+-[A-Za-z0-9._-]+") && !IvaChatPatch.voiceCacheName("https://x/voice/../a b%.m4a").contains("/"), true);
            eq("голосовые 10.58: разные ссылки — разные файлы", IvaChatPatch.voiceCacheName("https://a/voice/v.m4a").equals(IvaChatPatch.voiceCacheName("https://b/voice/v.m4a")), false);
            eq("голосовые 10.58: ссылка без имени", IvaChatPatch.voiceCacheName("https://a/voice/").matches("[0-9a-f]+-v"), true);
            eq("голосовые 10.58: прогресс раз в 100 мс", IvaChatPatch.VOICE_TICK_MS, 100);
            // 10.59: нажатие на уведомление — сразу туда, где нужно
            java.util.List<String> nwIds = java.util.Arrays.asList("30", "29", "27");
            eq("переход 10.59: один собеседник — первое непрочитанное", IvaNotifyPatch.messageTarget(nwIds, java.util.Arrays.asList("adm@x.ru", "Adm@X.ru ", "adm@x.ru")), "27");
            eq("переход 10.59: несколько собеседников — список чатов", IvaNotifyPatch.messageTarget(nwIds, java.util.Arrays.asList("adm@x.ru", "b@x.ru", "adm@x.ru")), "");
            eq("переход 10.59: одно сообщение", IvaNotifyPatch.messageTarget(java.util.Arrays.asList("5"), java.util.Arrays.asList("a@x.ru")), "5");
            eq("переход 10.59: пусто / без отправителя / разная длина — список",
                IvaNotifyPatch.messageTarget(new java.util.ArrayList<String>(), new java.util.ArrayList<String>()) + "|"
                + IvaNotifyPatch.messageTarget(java.util.Arrays.asList("5"), java.util.Arrays.asList("")) + "|"
                + IvaNotifyPatch.messageTarget(java.util.Arrays.asList("5", "4"), java.util.Arrays.asList("a@x.ru")), "||");
            eq("переход 10.59: одна заявка — её карточка, несколько — список",
                IvaNotifyPatch.orderTarget(java.util.Arrays.asList("77")) + "|" + IvaNotifyPatch.orderTarget(java.util.Arrays.asList("77", "76")) + "|" + IvaNotifyPatch.orderTarget(null), "77||");
            java.util.List<String> pids = java.util.Arrays.asList("5", "4", "3", "2"), pkeys = java.util.Arrays.asList("e:a", "e:b", "e:a", "e:a");
            eq("переход 10.59: место карточки среди заявок клиента",
                IvaNotifyPatch.indexInClient(pids, pkeys, "5") + "|" + IvaNotifyPatch.indexInClient(pids, pkeys, "3") + "|" + IvaNotifyPatch.indexInClient(pids, pkeys, "2")
                + "|" + IvaNotifyPatch.indexInClient(pids, pkeys, "4"), "0|1|2|0");
            eq("переход 10.59: нет заявки / ключи не сошлись — не подсвечиваем",
                IvaNotifyPatch.indexInClient(pids, pkeys, "9") + "|" + IvaNotifyPatch.indexInClient(pids, java.util.Arrays.asList("e:a"), "5"), "-1|-1");
            eq("переход 10.59: обновление — код и версия", IvaNotifyPatch.targetCode("133|10.59") + "|" + IvaNotifyPatch.targetName("133|10.59"), "133|10.59");
            eq("переход 10.59: обновление — без кода", IvaNotifyPatch.targetCode("") + "|" + IvaNotifyPatch.targetName("") + "|" + IvaNotifyPatch.targetCode("x|1") + "|" + IvaNotifyPatch.targetName(null), "0||0|");
            eq("переход 10.59: прокрутка с отступом сверху", IvaNotifyPatch.revealY(500, 33), 467);
            eq("переход 10.59: ожидание и метка кнопки обновления", IvaNotifyPatch.ROUTE_WAIT_MS + "|" + IvaNotifyPatch.ROUTE_STEP_MS + "|" + IvaChatPatch.NOTE_WAIT_MS + "|" + IvaNotifyPatch.UPDATE_BTN_TAG,
                "15000|250|20000|iva_selfupdate_btn");
            eq("переход 10.59: подсветка — полупрозрачный оранжевый 1,6 с", Integer.toHexString(IvaNotifyPatch.FLASH_COLOR) + "|" + IvaNotifyPatch.FLASH_MS, "55ff8f1f|1600");
            {
                String cme = "client@x.ru", a1 = "adm@x.ru", a2 = "adm2@x.ru";
                java.util.Set<String> ads = new java.util.HashSet<String>(java.util.Arrays.asList(a1, a2));
                java.util.List<IvaChatPatch.Msg> lm = new java.util.ArrayList<IvaChatPatch.Msg>();
                lm.add(IvaChatPatch.msg("1", cme, a1, "Есть дрель?", "", "", 0, true, "2026-10-14T09:00:00+00:00", "T1"));
                lm.add(IvaChatPatch.msg("2", a1, cme, "Да", "", "", 0, false, "2026-10-14T09:01:00+00:00", "T1"));
                lm.add(IvaChatPatch.msg("3", "Adm@X.ru", cme, "", "", "https://x/voice/v.m4a", 5, false, "2026-10-14T09:02:00+00:00", ""));
                lm.add(IvaChatPatch.msg("4", a2, cme, "Добрый день", "", "", 0, false, "2026-10-14T09:03:00+00:00", ""));
                java.util.Map<String, String> noTags = new java.util.HashMap<String, String>();
                eq("переход 10.59: сообщение по инструменту — его переписка", String.join("|", IvaChatPatch.locate(lm, cme, ads, false, noTags, "2")), "adm@x.ru|adm@x.ru|T1");
                eq("переход 10.59: голосовое без tool_id — переписка предыдущего (как в списке)", String.join("|", IvaChatPatch.locate(lm, cme, ads, false, noTags, "3")), "adm@x.ru|Adm@X.ru|T1");
                eq("переход 10.59: общий чат другого проката", String.join("|", IvaChatPatch.locate(lm, cme, ads, false, noTags, "4")), "adm2@x.ru|adm2@x.ru|");
                eq("переход 10.59: нет сообщения / пустой id — null", IvaChatPatch.locate(lm, cme, ads, false, noTags, "9") == null && IvaChatPatch.locate(lm, cme, ads, false, noTags, " ") == null, true);
                // как в списке: переписка из locate есть среди buildChats
                boolean inList = false;
                for (IvaChatPatch.Chat c : IvaChatPatch.buildChats(lm, cme, ads, false, noTags))
                    if (c.partner.equals("adm@x.ru") && c.tool.equals("T1")) inList = true;
                eq("переход 10.59: та же переписка, что в списке чатов", inList, true);
            }
        }
        {   // 10.69: вход не слетает
            int N = IvaClientPatch.AUTH_NONE, K = IvaClientPatch.AUTH_KEEP, F = IvaClientPatch.AUTH_FRESH;
            eq("вход 10.69: «Выйти» (главный поток) — выход настоящий при любом ответе", IvaClientPatch.authDecision(true, true, "200") == N && IvaClientPatch.authDecision(true, true, "-1") == N, true);
            eq("вход 10.69: копии нет — выход как раньше", IvaClientPatch.authDecision(false, false, "200") == N && IvaClientPatch.authDecision(false, false, "-1") == N, true);
            eq("вход 10.69: ключ обновлён — вход с новой парой", IvaClientPatch.authDecision(false, true, "200") == F && IvaClientPatch.authDecision(false, true, "201") == F, true);
            eq("вход 10.69: нет связи — вход как был", IvaClientPatch.authDecision(false, true, "-1") == K && IvaClientPatch.authDecision(false, true, "") == K && IvaClientPatch.authDecision(false, true, null) == K, true);
            eq("вход 10.69: сервер занят — вход как был", IvaClientPatch.authDecision(false, true, "500") == K && IvaClientPatch.authDecision(false, true, "503") == K
                && IvaClientPatch.authDecision(false, true, "429") == K && IvaClientPatch.authDecision(false, true, "408") == K, true);
            eq("вход 10.69: rt больше не действует (400/401/403/404) — выход настоящий", IvaClientPatch.authDecision(false, true, "400") == N && IvaClientPatch.authDecision(false, true, "401") == N
                && IvaClientPatch.authDecision(false, true, "403") == N && IvaClientPatch.authDecision(false, true, "404") == N, true);
            long now = 1_800_000_000_000L;
            eq("вход 10.69: до конца ключа 10 минут — не обновляем", IvaClientPatch.authNeed(now / 1000 + 600, now), false);
            eq("вход 10.69: до конца 4 минуты — обновляем", IvaClientPatch.authNeed(now / 1000 + 240, now), true);
            eq("вход 10.69: ровно 5 минут — ещё нет, 4:59 — да", !IvaClientPatch.authNeed(now / 1000 + 300, now) && IvaClientPatch.authNeed(now / 1000 + 299, now), true);
            eq("вход 10.69: ключ уже истёк — обновляем", IvaClientPatch.authNeed(now / 1000 - 3600, now), true);
            eq("вход 10.69: срок не разобран — не трогаем", IvaClientPatch.authNeed(0, now), false);
            eq("вход 10.69: пустые значения", IvaClientPatch.authEmpty(null) && IvaClientPatch.authEmpty("") && IvaClientPatch.authEmpty(" ") && IvaClientPatch.authEmpty("null")
                && IvaClientPatch.authEmpty(Boolean.TRUE) && !IvaClientPatch.authEmpty("eyJ"), true);
            eq("вход 10.69: ключ телефона — штатный", IvaClientPatch.AUTH_PH, "phone");
            eq("вход 10.69: экран входа ещё не показан — ждём", IvaClientPatch.authEnterStep(false, false, 0), IvaClientPatch.AUTH_WAIT);
            eq("вход 10.69: штатный экран входа показан — открываем приложение", IvaClientPatch.authEnterStep(true, false, 3), IvaClientPatch.AUTH_ENTER);
            eq("вход 10.69: приложение уже открыто — не трогаем", IvaClientPatch.authEnterStep(true, true, 0), IvaClientPatch.AUTH_SKIP);
            eq("вход 10.69: ждём не дольше 3 с", IvaClientPatch.authEnterStep(false, false, 59) == IvaClientPatch.AUTH_WAIT
                && IvaClientPatch.authEnterStep(false, false, 60) == IvaClientPatch.AUTH_ENTER && IvaClientPatch.AUTH_WAIT_TRIES * IvaClientPatch.AUTH_WAIT_MS == 3000L, true);
            eq("вход 10.69: обновление заранее — 5 минут, проверка раз в минуту", IvaClientPatch.AUTH_AHEAD_MS == 300000L && IvaClientPatch.AUTH_TICK_MS == 60000L, true);
            // настоящий ключ: срок из JWT разбирается тем же jwtExp, что у фоновой проверки
            String jwt = "x." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"exp\":" + (now / 1000 + 200) + "}").getBytes()) + ".y";
            eq("вход 10.69: срок из ключа → пора обновлять", IvaClientPatch.authNeed(IvaNotifyPatch.jwtExp(jwt), now), true);
        }
        {   // 10.70: отзывы и оценки, этап 1
            eq("отзывы 10.70: рейтинг с запятой", IvaReviewPatch.fmtRating(4.75) + "|" + IvaReviewPatch.fmtRating(5) + "|" + IvaReviewPatch.fmtRating(3.04), "4,8|5,0|3,0");
            eq("отзывы 10.70: склонение", IvaReviewPatch.reviewsWord(1) + " " + IvaReviewPatch.reviewsWord(2) + " " + IvaReviewPatch.reviewsWord(5) + " " + IvaReviewPatch.reviewsWord(11)
                + " " + IvaReviewPatch.reviewsWord(12) + " " + IvaReviewPatch.reviewsWord(21) + " " + IvaReviewPatch.reviewsWord(104) + " " + IvaReviewPatch.reviewsWord(111), "отзыв отзыва отзывов отзывов отзывов отзыв отзыва отзывов");
            eq("отзывы 10.70: карточка ленты — «★ 4,8 (12)»", IvaReviewPatch.cardLine(4.8, 12), "★ 4,8 (12)");
            eq("отзывы 10.70: отзывов нет — на карточке ничего", IvaReviewPatch.cardLine(0, 0) + IvaReviewPatch.cardLine(4.5, 0) + IvaReviewPatch.cardLine(0, 3) + IvaReviewPatch.cardLine(Double.NaN, 3), "");
            eq("отзывы 10.70: страница инструмента", IvaReviewPatch.summaryLine(4.8, 12) + "|" + IvaReviewPatch.summaryLine(5, 1) + "|" + IvaReviewPatch.summaryLine(0, 0), "★ 4,8 · 12 отзывов|★ 5,0 · 1 отзыв|Отзывов пока нет");
            eq("отзывы 10.70: прокат — рейтинг или штатный текст", IvaReviewPatch.sellerLine(4.6, 3) + "|" + IvaReviewPatch.sellerLine(0, 0), "★ 4,6 · 3 отзыва об инструментах проката|null");
            eq("отзывы 10.70: штатный «Отзывы: пока нет» узнаётся (и с «рейтинг не сформирован»)", IvaReviewPatch.nativeNoReviews("Отзывы: пока нет") && IvaReviewPatch.nativeNoReviews("Отзывы: пока нет · рейтинг не сформирован")
                && !IvaReviewPatch.nativeNoReviews("★ 4,6 · 3 отзыва об инструментах проката") && !IvaReviewPatch.nativeNoReviews(null), true);
            java.util.List<java.util.Map<String, String>> q70rv = new java.util.ArrayList<java.util.Map<String, String>>();
            String[][] q70rows = {{"r1", "5", "published"}, {"r2", "4", "published"}, {"r3", "1", "hidden"}, {"r4", "4", "published"}, {"r5", "x", "published"}};
            for (String[] q70x : q70rows) { java.util.Map<String, String> q70m = new java.util.HashMap<String, String>(); q70m.put("id", q70x[0]); q70m.put("rating", q70x[1]); q70m.put("status", q70x[2]); q70rv.add(q70m); }
            double[] q70sm = IvaReviewPatch.summary(q70rv);
            eq("отзывы 10.70: сводка — только опубликованные (как сайт и триггер)", q70sm[0] + "|" + (int) q70sm[1], "4.3|3");
            eq("отзывы 10.70: пустая сводка", IvaReviewPatch.summary(null)[1] == 0 && IvaReviewPatch.summary(new java.util.ArrayList<java.util.Map<String, String>>())[0] == 0, true);
            String q70vis = ""; for (java.util.Map<String, String> q70m : IvaReviewPatch.visible(q70rv, false, "r2")) q70vis += q70m.get("id");
            String q70visC = ""; for (java.util.Map<String, String> q70m : IvaReviewPatch.visible(q70rv, true, "")) q70visC += q70m.get("id");
            eq("отзывы 10.70: список — опубликованные, свой отдельно", q70vis, "r1r4r5");
            eq("отзывы 10.70: главному админу — и скрытые", q70visC, "r1r2r3r4r5");
            eq("отзывы 10.70: звёзды", IvaReviewPatch.stars(4) + "|" + IvaReviewPatch.stars(0) + "|" + IvaReviewPatch.stars(9), "★★★★☆|☆☆☆☆☆|★★★★★");
            eq("отзывы 10.70: дата", IvaReviewPatch.fmtDate("2026-10-14T09:30:00+00:00") + "|" + IvaReviewPatch.fmtDate("2026-01-02") + "|" + IvaReviewPatch.fmtDate("null") + "|" + IvaReviewPatch.fmtDate("2026-13-01x"), "14 октября 2026|2 января 2026||2026-13-01");
            java.util.Map<String, String> q70dr = new java.util.HashMap<String, String>(); q70dr.put("created_at", "2026-10-01T10:00:00+00:00"); q70dr.put("updated_at", "2026-10-01T10:00:00+00:00");
            java.util.Map<String, String> q70de = new java.util.HashMap<String, String>(); q70de.put("created_at", "2026-10-01T10:00:00+00:00"); q70de.put("updated_at", "2026-10-05T10:00:00+00:00");
            eq("отзывы 10.70: дата изменения и «(изменён)» — как на сайте", IvaReviewPatch.dateLine(q70dr) + "|" + IvaReviewPatch.dateLine(q70de), "1 октября 2026|5 октября 2026 (изменён)");
            eq("отзывы 10.70: не вошёл — подсказка войти", IvaReviewPatch.formHint(false, false, false, ""), IvaReviewPatch.HINT_GUEST);
            eq("отзывы 10.70: свой инструмент — ничего; нет заявки — подсказка; можно — кнопка", "[" + IvaReviewPatch.formHint(true, true, false, "own_tool") + "]"
                + IvaReviewPatch.formHint(true, true, false, "no_order") + "|" + IvaReviewPatch.formHint(true, true, true, null), "[]" + IvaReviewPatch.HINT_NO_ORDER + "|null");
            eq("отзывы 10.70: право не проверилось — кнопки нет", IvaReviewPatch.formHint(true, false, false, ""), "");
            eq("отзывы 10.70: причины для шторки заявки", IvaReviewPatch.reasonText("own_tool").startsWith("Нельзя") && IvaReviewPatch.reasonText("no_order").equals(IvaReviewPatch.HINT_NO_ORDER)
                && IvaReviewPatch.reasonText("tool_not_found").contains("не опубликован"), true);
            eq("отзывы 10.70: название как у штатного shorten(34)", IvaReviewPatch.shorten34("Перфоратор") + "|" + IvaReviewPatch.shorten34("123456789012345678901234567890123456").length()
                + "|" + IvaReviewPatch.shorten34("1234567890123456789012345678901234").length(), "Перфоратор|34|34");
            java.util.List<String[]> q70cs = new java.util.ArrayList<String[]>();
            q70cs.add(new String[]{"от 500 ₽/сут", "★ 4,0 (2)"}); q70cs.add(new String[]{"от 700 ₽/сут", "★ 5,0 (1)"});
            eq("отзывы 10.70: одинаковые названия — по цене", IvaReviewPatch.chooseLine(q70cs, "от 700 ₽/сут"), "★ 5,0 (1)");
            eq("отзывы 10.70: не различить — не пишем", IvaReviewPatch.chooseLine(q70cs, "Цену уточняйте"), "");
            q70cs.add(new String[]{"от 700 ₽/сут", "★ 3,0 (4)"});
            eq("отзывы 10.70: и цена совпала — не пишем", IvaReviewPatch.chooseLine(q70cs, "от 700 ₽/сут"), "");
            java.util.List<String[]> q70c1 = new java.util.ArrayList<String[]>(); q70c1.add(new String[]{"x", "★ 4,8 (12)"}); q70c1.add(new String[]{"y", "★ 4,8 (12)"});
            eq("отзывы 10.70: строка одинакова у всех — пишем", IvaReviewPatch.chooseLine(q70c1, "z") + "|" + IvaReviewPatch.chooseLine(null, "z"), "★ 4,8 (12)|");
            eq("отзывы 10.70: сообщение базы", IvaReviewPatch.errMessage("400", "{\"code\":\"P0001\",\"message\":\"Отзыв могут оставить только арендаторы\"}"), "Отзыв могут оставить только арендаторы");
            eq("отзывы 10.70: сообщение \\u и кавычки", IvaReviewPatch.errMessage("400", "{\"message\":\"\\u041e\\u0448 \\\"x\\\"\"}"), "Ош \"x\"");
            eq("отзывы 10.70: нет связи / нет сообщения", IvaReviewPatch.errMessage("-1", null) + "|" + IvaReviewPatch.errMessage("500", "oops"), "нет связи с сервером|ошибка сервера (500)");
            eq("отзывы 10.70: тело submit_review", IvaReviewPatch.submitBody("t-1", 9, "  Хорошо \"ок\" ", null),
                "{\"p_tool_id\":\"t-1\",\"p_rating\":5,\"p_text\":\"Хорошо \\\"ок\\\"\",\"p_author_name\":\"\"}");
            eq("отзывы 10.70: оценка 0 → 1", IvaReviewPatch.submitBody("t", 0, "", "Иван").contains("\"p_rating\":1,") && IvaReviewPatch.submitBody("t", 0, "", "Иван").endsWith("\"p_author_name\":\"Иван\"}"), true);
            eq("отзывы 10.70: e-mail авторов не запрашивается", IvaReviewPatch.REVIEW_COLS.contains("email"), false);
            eq("отзывы 10.70: ответ сервера 2xx", IvaReviewPatch.ok(new String[]{"200", "[]"}) && IvaReviewPatch.ok(new String[]{"204", ""}) && !IvaReviewPatch.ok(new String[]{"400", ""})
                && !IvaReviewPatch.ok(new String[]{"-1", null}) && !IvaReviewPatch.ok(null), true);
            java.util.Map<String, String> q70rr = new java.util.HashMap<String, String>(); q70rr.put("rating", "null");
            eq("отзывы 10.70: оценка из строки", IvaReviewPatch.ratingOf(q70rr) + "|" + IvaReviewPatch.ratingOf(q70rv.get(0)), "0|5");
        }
        {   // 10.71: рейтинг на картинке, «Нет отзывов», запрет мата
            eq("лента 10.71: на картинке «★ 4,8», без отзывов — нет плашки", IvaReviewPatch.badgeText(4.75, 12) + "|" + IvaReviewPatch.badgeText(0, 0) + "|" + IvaReviewPatch.badgeText(4, 0), "★ 4,8||");
            eq("лента 10.71: под ценой — число отзывов / «Нет отзывов»", IvaReviewPatch.countLine(12) + "|" + IvaReviewPatch.countLine(1) + "|" + IvaReviewPatch.countLine(3) + "|" + IvaReviewPatch.countLine(0), "12 отзывов|1 отзыв|3 отзыва|Нет отзывов");
            eq("лента 10.71: ключ карточки", IvaReviewPatch.feedKey(4.8, 12) + "/" + IvaReviewPatch.feedKey(0, 0) + "/" + IvaReviewPatch.feedKey(5, 0), "12|4,8/0|/0|");
            eq("лента 10.71: тексты по ключу", String.join("/", IvaReviewPatch.feedTexts("12|4,8")) + "|" + String.join("/", IvaReviewPatch.feedTexts("0|")), "★ 4,8/12 отзывов|/Нет отзывов");
            eq("лента 10.71: карточку не узнали — null (строка невидима, место сохраняется)", IvaReviewPatch.feedTexts("") == null && IvaReviewPatch.feedTexts(null) == null && IvaReviewPatch.feedTexts("x|1") == null, true);
            java.util.List<String[]> q71c = new java.util.ArrayList<String[]>();
            q71c.add(new String[]{"от 500 ₽/сут", IvaReviewPatch.feedKey(0, 0)}); q71c.add(new String[]{"от 700 ₽/сут", IvaReviewPatch.feedKey(4.5, 2)});
            eq("лента 10.71: одинаковые названия — по цене", String.join("/", IvaReviewPatch.feedTexts(IvaReviewPatch.chooseLine(q71c, "от 500 ₽/сут"))), "/Нет отзывов");
            String[] q71bad = {"хуй", "Нахуй", "ХУЁВО", "х.у.й", "xуй", "пиздец", "пздц", "ебать", "Заебись", "выебон", "съебался", "сьебался", "ёбаный", "еб@ть", "долбоеб", "въебал",
                "бля", "блять", "бляяяя", "выблядок", "мудак", "пидор", "пидр", "залупа", "гондон", "манда", "мандавошка", "шлюха", "сука", "суука", "херня", "похер", "дрочить", "хули",
                "Отличная дрель, но продавец мудак"};
            String q71miss = "";
            for (String w : q71bad) if (!IvaReviewPatch.hasMat(w)) q71miss += w + ";";
            eq("мат 10.71: нецензурные слова узнаются (и с ё, латиницей, точками, повторами)", q71miss, "");
            String[] q71good = {"хлеб", "небо", "себе", "тебе", "требовать", "учебный", "колеблется", "бляха", "бляшка", "мудрый", "педикюр", "мандарин", "мандат", "сучок", "сук",
                "херес", "херувим", "страхует", "застрахуйте", "не психуй", "хулиган", "употреблять", "погребение", "вебинар", "дебет", "лебёдка", "гребень", "щебень", "худой",
                "хутор", "сукно", "скука", "хребет", "оскорблять", "Makita", "Хорошая дрель, всё понравилось!", "", null};
            String q71fp = "";
            for (String w : q71good) if (IvaReviewPatch.hasMat(w)) q71fp += w + ";";
            eq("мат 10.71: обычные слова — не мат (хлеб, себе, бляха, сучок, лебёдка, страхует…)", q71fp, "");
            eq("мат 10.71: «Скрыть спам» — только у отзыва с матом; скрытый — «Восстановить»; не главный админ — ничего",
                IvaReviewPatch.modAction(true, false, true) + "|" + IvaReviewPatch.modAction(true, false, false) + "|" + IvaReviewPatch.modAction(true, true, false) + "|" + IvaReviewPatch.modAction(false, false, true),
                "hidden|null|published|null");
            eq("мат 10.71: замена букв — одной длины (как translate в базе)", IvaReviewPatch.MAT_FROM.length() == IvaReviewPatch.MAT_TO.length(), true);
            eq("мат 10.71: подготовка текста", IvaReviewPatch.matNorm("ЁЖ x.y-й 3@0"), "еж хуй зао");
        }
        {   // 10.72: переписка «Хочу сдавать инструмент» — отдельная, у владельца смайлик
            eq("сдавать 10.72: тема из базы — tool_id важнее, topic='lend' → LEND, иначе общий чат",
                IvaChatPatch.msgTool("", "lend") + "|" + IvaChatPatch.msgTool("t1", "lend") + "|" + IvaChatPatch.msgTool("", "") + "|" + IvaChatPatch.msgTool(null, "spam") + "|" + IvaChatPatch.msgTool("null", " lend "),
                "lend|t1|||lend");
            java.util.Set<String> q72ad = new java.util.HashSet<String>(java.util.Arrays.asList("eclips.ru@mail.ru", "adm@x.ru"));
            java.util.List<IvaChatPatch.Msg> q72 = new java.util.ArrayList<IvaChatPatch.Msg>();
            q72.add(IvaChatPatch.msg("1", "Kli@x.ru", "eclips.ru@mail.ru", "вопрос", "", "", 0, true, "2026-10-14T10:00:00Z", ""));
            q72.add(IvaChatPatch.msg("2", "kli@x.ru", "eclips.ru@mail.ru", "хочу сдавать перфоратор", "", "", 0, false, "2026-10-14T10:01:00Z", "lend"));
            q72.add(IvaChatPatch.msg("3", "kli@x.ru", "eclips.ru@mail.ru", "", "", "a.m4a", 5, false, "2026-10-14T10:02:00Z", ""));
            q72.add(IvaChatPatch.msg("4", "kli@x.ru", "adm@x.ru", "про дрель", "", "", 0, true, "2026-10-14T10:03:00Z", "t1"));
            java.util.Map<String, Long> q72h = new java.util.HashMap<String, Long>();
            java.util.Set<String> q72i = new java.util.HashSet<String>();
            java.util.List<IvaChatPatch.Chat> q72o = IvaChatPatch.buildChats(q72, "eclips.ru@mail.ru", q72ad, true, null, q72h, q72i);
            StringBuilder q72s = new StringBuilder();
            for (IvaChatPatch.Chat c : q72o) q72s.append(c.partner).append('|').append(c.tool).append(':').append(c.count).append('/').append(c.unread).append(' ');
            eq("сдавать 10.72: у владельца — отдельная переписка «Хочу сдавать» (голосовое после неё — туда же), общий чат и чат по инструменту отдельно",
                q72s.toString().trim(), "kli@x.ru|t1:1/0 kli@x.ru|lend:2/2 kli@x.ru|:1/0");
            java.util.List<IvaChatPatch.Chat> q72c = IvaChatPatch.buildChats(q72, "kli@x.ru", q72ad, false, null, q72h, q72i);
            StringBuilder q72cs = new StringBuilder();
            for (IvaChatPatch.Chat c : q72c) q72cs.append(c.partner).append('|').append(c.tool).append(' ');
            eq("сдавать 10.72: у клиента — тоже отдельная переписка с владельцем", q72cs.toString().trim(), "adm@x.ru|t1 eclips.ru@mail.ru|lend eclips.ru@mail.ru|");
            eq("сдавать 10.72: переписка открывается своими сообщениями",
                IvaChatPatch.thread(q72, "kli@x.ru", q72ad, false, null, "eclips.ru@mail.ru", "lend", q72h, q72i).size() + "|"
                + IvaChatPatch.thread(q72, "kli@x.ru", q72ad, false, null, "eclips.ru@mail.ru", "", q72h, q72i).size(), "2|1");
            q72h.put(IvaChatPatch.chatKey("kli@x.ru", "lend"), IvaChatPatch.parseIso("2026-10-14T10:05:00Z"));
            eq("сдавать 10.72: «Удалить чат» у себя — по своей переписке, общий чат остаётся",
                IvaChatPatch.buildChats(q72, "eclips.ru@mail.ru", q72ad, true, null, q72h, q72i).size(), 2);
            java.util.Set<String> q72seen = new java.util.HashSet<String>(java.util.Arrays.asList("1", "2"));
            eq("сдавать 10.72: своё голосовое в переписке «Хочу сдавать» — на пометку темой",
                String.join(",", IvaChatPatch.voiceToTag(q72, "kli@x.ru", "eclips.ru@mail.ru", "lend", q72seen, null)), "3");
            eq("сдавать 10.72: смайлик — только у владельца и только в этой переписке",
                IvaChatPatch.lendSmile("eclips.ru@mail.ru", "lend") + "|" + IvaChatPatch.lendSmile("Eclips.RU@mail.ru", "lend") + "|" + IvaChatPatch.lendSmile("kli@x.ru", "lend")
                + "|" + IvaChatPatch.lendSmile("eclips.ru@mail.ru", "t1") + "|" + IvaChatPatch.lendSmile("adm@x.ru", "lend"), "true|true|false|false|false");
            eq("сдавать 10.72: подпись в списке", IvaChatPatch.chatSub("lend", "") + "|" + IvaChatPatch.chatSub("", "x") + "|" + IvaChatPatch.chatSub("t1", "Дрель") + "|" + IvaChatPatch.chatSub("t1", ""),
                "Хочу сдавать инструмент|Общий чат|Дрель|Инструмент");
            eq("сдавать 10.72: тема не путается с инструментом", IvaChatPatch.isLend("lend") && IvaChatPatch.isLend(" lend ") && !IvaChatPatch.isLend("") && !IvaChatPatch.isLend("t1"), true);
            eq("сдавать 10.72: в базу — topic, список читает topic", IvaChatPatch.MSG_SELECT_TOPIC.endsWith(",tool_id,topic"), true);
        }
        {   // 10.73: расписание уведомлений у админов
            int[][] q73d = IvaNotifyPatch.schedParse("");
            eq("расписание 10.73: по умолчанию — каждый день 09:00–21:00", IvaNotifyPatch.schedFormat(q73d), "1,540,1260;1,540,1260;1,540,1260;1,540,1260;1,540,1260;1,540,1260;1,540,1260");
            eq("расписание 10.73: битая строка — по умолчанию, хорошие дни сохраняются",
                IvaNotifyPatch.schedFormat(IvaNotifyPatch.schedParse("0,600,1080;x;1,2000,10;1,0,0")), "0,600,1080;1,540,1260;1,540,1260;1,0,0;1,540,1260;1,540,1260;1,540,1260");
            int[][] q73w = IvaNotifyPatch.schedParse("1,540,1260;1,540,1260;1,540,1260;1,540,1260;1,540,1260;1,600,1080;0,540,1260");
            eq("расписание 10.73: внутри и на границах часов (с — включительно, до — нет)",
                IvaNotifyPatch.inSchedule(q73w, 0, 540) + "|" + IvaNotifyPatch.inSchedule(q73w, 0, 539) + "|" + IvaNotifyPatch.inSchedule(q73w, 0, 1259) + "|" + IvaNotifyPatch.inSchedule(q73w, 0, 1260),
                "true|false|true|false");
            eq("расписание 10.73: у каждого дня свои часы; выключенный день — тихо",
                IvaNotifyPatch.inSchedule(q73w, 5, 590) + "|" + IvaNotifyPatch.inSchedule(q73w, 5, 600) + "|" + IvaNotifyPatch.inSchedule(q73w, 6, 720), "false|true|false");
            int[][] q73n = IvaNotifyPatch.schedParse("1,1320,480;0,0,0;0,0,0;0,0,0;0,0,0;0,0,0;1,0,0");
            eq("расписание 10.73: через полночь — хвост после 00:00 относится ко дню начала",
                IvaNotifyPatch.inSchedule(q73n, 0, 1330) + "|" + IvaNotifyPatch.inSchedule(q73n, 1, 400) + "|" + IvaNotifyPatch.inSchedule(q73n, 1, 480) + "|" + IvaNotifyPatch.inSchedule(q73n, 0, 400),
                "true|true|false|false");   // пн 06:40: воскресенье «круглосуточно» на понедельник не переходит
            eq("расписание 10.73: одинаковые «с» и «до» — круглосуточно", IvaNotifyPatch.inSchedule(q73n, 6, 0) + "|" + IvaNotifyPatch.inSchedule(q73n, 6, 1439), "true|true");
            int[][] q73r = IvaNotifyPatch.schedParse("1,600,600;0,0,0;0,0,0;0,0,0;0,0,0;0,0,0;0,0,0");
            eq("расписание 10.73: круглосуточно и при 10:00 – 10:00 (весь день, не через полночь)",
                IvaNotifyPatch.inSchedule(q73r, 0, 300) + "|" + IvaNotifyPatch.inSchedule(q73r, 0, 900) + "|" + IvaNotifyPatch.inSchedule(q73r, 1, 300), "true|true|false");
            eq("расписание 10.73: день недели из Calendar (вс=1 → 6, пн=2 → 0, сб=7 → 5)",
                IvaNotifyPatch.dowMon(1) + "|" + IvaNotifyPatch.dowMon(2) + "|" + IvaNotifyPatch.dowMon(7), "6|0|5");
            eq("расписание 10.73: тихо — только у админа и только при включённом расписании вне его часов",
                IvaNotifyPatch.quietAt(true, true, q73w, 6, 720) + "|" + IvaNotifyPatch.quietAt(false, true, q73w, 6, 720) + "|" + IvaNotifyPatch.quietAt(true, false, q73w, 6, 720) + "|" + IvaNotifyPatch.quietAt(true, true, q73w, 0, 720),
                "true|false|false|false");
            int[] q73s = IvaNotifyPatch.nextStart(q73w, 5, 1100);
            eq("расписание 10.73: ближайшее начало — суббота вечер → понедельник 09:00", q73s[0] + "|" + q73s[1], "0|540");
            eq("расписание 10.73: нет ни одного дня — начала нет", IvaNotifyPatch.nextStart(IvaNotifyPatch.schedParse("0,1,2;0,1,2;0,1,2;0,1,2;0,1,2;0,1,2;0,1,2"), 0, 0) == null, true);
            eq("расписание 10.73: строки дней", IvaNotifyPatch.dayText(q73w[0]) + "|" + IvaNotifyPatch.dayText(q73w[6]) + "|" + IvaNotifyPatch.dayText(q73n[0]) + "|" + IvaNotifyPatch.dayText(q73n[6]),
                "09:00 – 21:00|без уведомлений|22:00 – 08:00 (через полночь)|круглосуточно");
            eq("расписание 10.73: строка «сейчас»", IvaNotifyPatch.schedStatus(q73w, 0, 720) + "|" + IvaNotifyPatch.schedStatus(q73w, 6, 720),
                "Сейчас время по расписанию — уведомления приходят.|Сейчас вне расписания — уведомления придут в Пн в 09:00.");
            eq("расписание 10.73: часы с нулями", IvaNotifyPatch.hm(5) + "|" + IvaNotifyPatch.hm(1439), "00:05|23:59");
        }
        {   // 10.74: мгновенные уведомления админам (FCM без библиотек Firebase)
            byte[] p74ff = new byte[16], p74r = new byte[16];
            for (int i = 0; i < 16; i++) { p74ff[i] = (byte) 0xff; p74r[i] = (byte) i; }
            eq("push 10.74: FID как в firebase-installations (0111…, 22 знака base64url)",
                IvaPushPatch.fid(p74ff) + "|" + IvaPushPatch.fid(p74r), "f_____________________|cAECAwQFBgcICQoLDA0ODw");
            eq("push 10.74: base64url без «=» (0, 1, 2, 3 байта; знаки - и _)",
                IvaPushPatch.b64url(new byte[0]) + "|" + IvaPushPatch.b64url(new byte[] { 'f' }) + "|" + IvaPushPatch.b64url(new byte[] { 'f', 'o' })
                + "|" + IvaPushPatch.b64url(new byte[] { 'f', 'o', 'o' }) + "|" + IvaPushPatch.b64url(new byte[] { (byte) 0xfb, (byte) 0xff }), "|Zg|Zm8|Zm9v|-_8");
            eq("push 10.74: хеш имени приложения Firebase [DEFAULT]", IvaPushPatch.appNameHash(), "R1dAH9Ui7M-ynoznwBdw01tLxhI");
            eq("push 10.74: ответ сервисов Google — обычный и старый «|ID|n|:токен»",
                IvaPushPatch.cleanReply("abc:DEF") + "|" + IvaPushPatch.cleanReply("|ID|3|:tok123") + "|" + IvaPushPatch.cleanReply("|ID|3|SERVICE_NOT_AVAILABLE") + "|" + IvaPushPatch.cleanReply(null),
                "abc:DEF|tok123|SERVICE_NOT_AVAILABLE|null");
            long d74 = IvaPushPatch.DAY_MS, r74 = IvaPushPatch.RETRY_MS, n74 = 100L * d74;
            eq("push 10.74: когда регистрироваться (свежий токен — нет; другой аккаунт, старше суток, нет токена — да; не чаще 30 мин)",
                IvaPushPatch.due("a@x.ru", "A@x.ru ", "T", n74 - 1000, n74 - 1000, n74) + "|" + IvaPushPatch.due("a@x.ru", "b@x.ru", "T", n74 - 1000, n74 - r74, n74)
                + "|" + IvaPushPatch.due("a@x.ru", "a@x.ru", "T", n74 - d74, n74 - r74, n74) + "|" + IvaPushPatch.due("", "a@x.ru", "", 0, 0, n74)
                + "|" + IvaPushPatch.due("", "a@x.ru", "", 0, n74 - r74 + 1, n74) + "|" + IvaPushPatch.due("a@x.ru", "a@x.ru", "T", n74 + d74, n74 + 5, n74),
                "false|true|true|true|false|true");
            eq("push 10.74: убрать токен с сервера — вышли или вошёл не админ; у админа — нет; токена нет — нечего",
                IvaPushPatch.shouldForget("a@x.ru", "T", false, false) + "|" + IvaPushPatch.shouldForget("a@x.ru", "T", true, false)
                + "|" + IvaPushPatch.shouldForget("a@x.ru", "T", true, true) + "|" + IvaPushPatch.shouldForget("", "T", false, false) + "|" + IvaPushPatch.shouldForget("a@x.ru", "", false, false),
                "true|true|false|false|false");
            String c74 = "{\"ready\":true,\"project_id\":\"iva-prokat\",\"sender_id\":\"123456789012\",\"app_id\":\"1:123456789012:android:0a1b2c3d\",\"api_key\":\"AIzaSyX\\/y\"}";
            String[] p74 = IvaPushPatch.parseCfg(c74);
            eq("push 10.74: настройки Firebase от функции", p74 == null ? "null" : p74[0] + "|" + p74[1] + "|" + p74[2] + "|" + p74[3],
                "iva-prokat|123456789012|1:123456789012:android:0a1b2c3d|AIzaSyX/y");
            eq("push 10.74: не настроено или битые настройки — null",
                (IvaPushPatch.parseCfg("{\"ready\":false}") == null) + "|" + (IvaPushPatch.parseCfg(c74.replace("android:", "ios:")) == null)
                + "|" + (IvaPushPatch.parseCfg(c74.replace("123456789012\",\"app", "12ab\",\"app")) == null) + "|" + (IvaPushPatch.parseCfg(null) == null), "true|true|true|true");
            eq("push 10.74: срок токена Firebase", IvaPushPatch.expiresMs("604800s") + "|" + IvaPushPatch.expiresMs("x") + "|"
                + IvaPushPatch.fisFresh("t", 10L * 3600000, 0) + "|" + IvaPushPatch.fisFresh("t", 3600000, 0) + "|" + IvaPushPatch.fisFresh("", 10L * 3600000, 0),
                "604800000|3600000|true|false|false");
            java.util.List<String[]> x74 = IvaPushPatch.extras(p74, "fidX", "authY", 243999000, 34, 148, "10.74");
            StringBuilder s74 = new StringBuilder();
            for (String[] kv : x74) s74.append(kv[0]).append('=').append(kv[1]).append(';');
            eq("push 10.74: поля запроса токена — как firebase-messaging GmsRpc", s74.toString(),
                "scope=*;sender=123456789012;subtype=123456789012;gmp_app_id=1:123456789012:android:0a1b2c3d;gmsv=243999000;osv=34;app_ver=148;app_ver_name=10.74;"
                + "firebase-app-name-hash=R1dAH9Ui7M-ynoznwBdw01tLxhI;Goog-Firebase-Installations-Auth=authY;appid=fidX;cliv=fcm-24.0.0;");
            eq("push 10.74: без токена Firebase поле не шлём", IvaPushPatch.extras(p74, "f", "", 1, 1, 1, null).size() + "|" + IvaPushPatch.extras(p74, "f", "", 1, 1, 1, null).get(7)[1], "11|");
            eq("push 10.74: строки состояния",
                IvaPushPatch.statusText("ok", "").startsWith("Мгновенные уведомления: подключены") + "|" + IvaPushPatch.statusText("off", "").contains("не настроил Firebase")
                + "|" + IvaPushPatch.statusText("err", "TIMEOUT").contains("(TIMEOUT)") + "|" + IvaPushPatch.statusText("", ""), "true|true|true|Мгновенные уведомления: подключаем…");
            eq("push 10.74: ошибка в строке состояния — без токенов и адресов, коротко",
                IvaPushPatch.shortErr("сервер a@b.ru  токен " + "abcdefghij0123456789abcdefghij0123456789xyz") + "|" + IvaPushPatch.shortErr(null),
                "сервер … токен …|");
            eq("push 10.74: SHA-1 подписи — заглавные hex", IvaPushPatch.hexUpper(new byte[] { 0, (byte) 0xab, 15 }), "00AB0F");
            // 10.75: «Уведомления» — раскрывающаяся кнопка; под заголовком — коротко о состоянии
            eq("настройки 10.75: выключены — так и пишем", IvaNotifyPatch.headSummary(false, true, true), "Выключены");
            eq("настройки 10.75: Android запретил — видно сразу, не раскрывая", IvaNotifyPatch.headSummary(true, false, false), "Запрещены в настройках Android");
            eq("настройки 10.75: включены", IvaNotifyPatch.headSummary(true, true, false), "Включены");
            eq("настройки 10.75: включены по расписанию", IvaNotifyPatch.headSummary(true, true, true), "Включены · по расписанию");
            eq("настройки 10.75: заголовок кнопки", IvaNotifyPatch.NOTIF_TITLE, "Уведомления");
            // 10.76: связаться из активной заявки; напоминание об окончании аренды
            {
                java.util.Map<String, String> act = new java.util.HashMap<String, String>();
                act.put("status", "rented");
                eq("связь 10.76: заявка в аренде — активная", IvaOrderPatch.ordActive(act, false), true);
                eq("связь 10.76: в архиве — кнопок нет", IvaOrderPatch.ordActive(act, true), false);
                java.util.Map<String, String> cl = new java.util.HashMap<String, String>();
                cl.put("status", "new");
                cl.put(IvaOrderPatch.ORD_PARTS, "closed,closed");
                eq("связь 10.76: все части закрыты — заявка закрыта, кнопок нет", IvaOrderPatch.ordActive(cl, false), false);
                cl.put(IvaOrderPatch.ORD_PARTS, "closed,rented");
                eq("связь 10.76: одна часть ещё в аренде — активная", IvaOrderPatch.ordActive(cl, false), true);
                java.util.Map<String, String> adm = new java.util.HashMap<String, String>();
                adm.put(IvaOrderPatch.ADM_ROW, "1"); adm.put("status", "closed");
                eq("связь 10.76: у админа закрытая часть — кнопок нет", IvaOrderPatch.ordActive(adm, false), false);
                adm.put("status", "new"); adm.put("user_email", "Client@Mail.ru"); adm.put("phone", "+7 900 111-22-33"); adm.put("name", "Иван");
                eq("связь 10.76: админу — клиент заявки (почта как в базе, телефон, имя)",
                    java.util.Arrays.asList(IvaOrderPatch.ordContact(true, adm, new java.util.ArrayList<String>(), null)).toString(), "[Client@Mail.ru, +7 900 111-22-33, Иван]");
                java.util.List<String> own = new java.util.ArrayList<String>();
                own.add("prokat@mail.ru");
                java.util.Map<String, java.util.Map<String, String>> pk = new java.util.HashMap<String, java.util.Map<String, String>>();
                eq("связь 10.76: данные проката ещё не загружены — написать можно, телефона нет",
                    java.util.Arrays.asList(IvaOrderPatch.ordContact(false, act, own, pk)).toString(), "[prokat@mail.ru, , ]");
                java.util.Map<String, String> ad = new java.util.HashMap<String, String>();
                ad.put("email", "Prokat@Mail.ru"); ad.put("phone", "8 (863) 200-00-00"); ad.put("company", "Прокат Юг"); ad.put("full_name", "Пётр");
                pk.put("prokat@mail.ru", ad);
                eq("связь 10.76: клиенту — прокат заявки из профиля (почта как в базе, телефон, название)",
                    java.util.Arrays.asList(IvaOrderPatch.ordContact(false, act, own, pk)).toString(), "[Prokat@Mail.ru, 8 (863) 200-00-00, Прокат Юг]");
                eq("связь 10.76: прокат не найден — пусто", IvaOrderPatch.ordContact(false, act, new java.util.ArrayList<String>(), pk)[0], "");
                eq("связь 10.76: набор номера — только цифры и +", IvaOrderPatch.dialUri("+7 (900) 123-45-67"), "tel:+79001234567");
                eq("связь 10.76: нет номера — звонить некуда", IvaOrderPatch.dialUri(" тел. 12 "), "");
                eq("связь 10.76: «null» — не номер", IvaOrderPatch.dialUri("null"), "");
            }
            eq("аренда 10.76: за 1, 2 или 3 часа; другое — 1 час", IvaNotifyPatch.rentHours(3) + "," + IvaNotifyPatch.rentHours(2) + "," + IvaNotifyPatch.rentHours(7) + "," + IvaNotifyPatch.rentHours(0), "3,2,1,1");
            eq("аренда 10.76: подписи чипов", IvaNotifyPatch.hoursText(1) + "|" + IvaNotifyPatch.hoursText(2) + "|" + IvaNotifyPatch.hoursText(3), "1 час|2 часа|3 часа");
            eq("аренда 10.76: срок у каждого инструмента из текста заявки",
                IvaNotifyPatch.rentTools("Дрель (3 дня), Пила ×2 (1 день), Фен (10 дней)", 0).toString(), "{Дрель=3, Пила ×2=1, Фен=10}");
            eq("аренда 10.76: без срока в тексте — срок заявки; совсем без срока — не напоминаем",
                IvaNotifyPatch.rentTools("Дрель, Пила (2 сут.)", 4).toString() + IvaNotifyPatch.rentTools("Дрель", 0).toString(), "{Дрель=4, Пила=2}{}");
            {
                long at = 1760440000000L;
                java.util.List<IvaNotifyPatch.RentEnd> es = IvaNotifyPatch.rentEnds("11", "5", "Дрель (3 дня), Пила (1 день), Фен (3 дня)", 3, at);
                eq("аренда 10.76: одинаковый срок — одно напоминание, разный — отдельные", es.size(), 2);
                eq("аренда 10.76: конец = «Выдал» + сутки", es.get(0).end - at, 3L * 24 * 3600 * 1000);
                eq("аренда 10.76: в напоминании — инструменты с этим сроком", es.get(0).names + "|" + es.get(1).names, "Дрель, Фен|Пила");
                eq("аренда 10.76: ключ «уже напомнили» — часть и конец, заявка — для перехода", es.get(1).key + "|" + es.get(1).order, "r11@" + (at + 24L * 3600 * 1000) + "|5");
                eq("аренда 10.76: нет времени выдачи (выдали до SQL) — не напоминаем", IvaNotifyPatch.rentEnds("11", "5", "Дрель (3 дня)", 3, -1).size(), 0);
            }
            {
                long end = 1760440000000L, h = 3600000L;
                eq("аренда 10.76: за 1 час — ровно за час уже пора", IvaNotifyPatch.rentDue(end, end - h, 1), true);
                eq("аренда 10.76: за 1 час — за час с минутой ещё рано", IvaNotifyPatch.rentDue(end, end - h - 60000, 1), false);
                eq("аренда 10.76: за 3 часа — за 2 ч 59 мин пора", IvaNotifyPatch.rentDue(end, end - 3 * h + 60000, 3), true);
                eq("аренда 10.76: срок вышел — не напоминаем", IvaNotifyPatch.rentDue(end, end, 1) || IvaNotifyPatch.rentDue(end, end + 1, 3), false);
                java.util.TimeZone utc = java.util.TimeZone.getTimeZone("UTC");
                long noon = 1760443200000L;                      // 2025-10-14 12:00 UTC
                eq("аренда 10.76: заголовок — сегодня", IvaNotifyPatch.rentTitle(noon + 150 * 60000L, noon, utc), "Аренда заканчивается в 14:30");
                eq("аренда 10.76: заголовок — после полуночи", IvaNotifyPatch.rentTitle(noon + 13 * h, noon + 11 * h, utc), "Аренда заканчивается завтра в 01:00");
            }
            eq("аренда 10.76: по умолчанию выключено (как и расписание), остальное включено",
                IvaNotifyPatch.defOn(IvaNotifyPatch.K_RENT) + "," + IvaNotifyPatch.defOn(IvaNotifyPatch.K_SCHED_ON) + "," + IvaNotifyPatch.defOn(IvaNotifyPatch.K_MSG), "false,false,true");
            eq("аренда 10.76: текст — инструменты и куда нажать", IvaNotifyPatch.rentText("Дрель").startsWith("Дрель. ") && IvaNotifyPatch.rentText("Дрель").contains("«Написать» или «Позвонить»"), true);
            eq("аренда 10.76: свой канал «Окончание аренды»", IvaNotifyPatch.channelName(IvaNotifyPatch.CH_RENT), "Окончание аренды");
            // 10.77: режим окончания аренды (сутки / до закрытия пункта), живой таймер (с 10.78 — «чч.мм.сс»)
            {
                java.util.TimeZone utc = java.util.TimeZone.getTimeZone("UTC");
                java.util.Calendar cu = java.util.Calendar.getInstance(utc);
                cu.clear(); cu.set(2026, 9, 14, 11, 30, 0);              // ср 14.10.2026 14:30 по Москве
                long wed = cu.getTimeInMillis(), h = 3600000L, d = 24 * h;
                cu.clear(); cu.set(2026, 9, 16, 11, 30, 0);              // пт 16.10.2026 14:30 по Москве
                long fri = cu.getTimeInMillis();
                IvaRentPatch.Rule day = IvaRentPatch.rule("day", "", "");
                IvaRentPatch.Rule all19 = IvaRentPatch.rule("close", "[1140,1140,1140,1140,1140,1140,1140]", "Europe/Moscow");
                IvaRentPatch.Rule week = IvaRentPatch.rule("close", "[1080,1080,1080,1080,1080,900,-1]", "Europe/Moscow");
                eq("аренда 10.77: «Сутки» — 14:30 → 14:30 через столько суток, сколько в заявке", IvaRentPatch.end(wed, 3, day) - wed, 3 * d);
                eq("аренда 10.77: правила нет — как «Сутки» (как 10.76)", IvaRentPatch.end(wed, 2, null) - wed, 2 * d);
                eq("аренда 10.77: «До закрытия» — выдали в ср 14:30 на 1 день → чт до 19:00", IvaRentPatch.end(wed, 1, all19) - wed, d + 4 * h + 30 * 60000L);
                eq("аренда 10.77: на 3 дня → сб до 19:00", IvaRentPatch.end(wed, 3, all19) - wed, 3 * d + 4 * h + 30 * 60000L);
                eq("аренда 10.77: своё время у дня — пт 14:30 на 1 день → сб до 15:00", IvaRentPatch.end(fri, 1, week) - fri, d + 30 * 60000L);
                eq("аренда 10.77: срок в выходной (вс) → до закрытия понедельника 18:00", IvaRentPatch.end(fri, 2, week) - fri, 3 * d + 3 * h + 30 * 60000L);
                eq("аренда 10.77: выдали после закрытия (ср 20:00) на 1 день → чт до 19:00",
                    IvaRentPatch.end(wed + 5 * h + 30 * 60000L, 1, all19) - wed, d + 4 * h + 30 * 60000L);
                eq("аренда 10.77: часовой пояс проката — 19:00 по Самаре = 15:00 UTC",
                    IvaRentPatch.end(wed, 1, IvaRentPatch.rule("close", "[1140,1140,1140,1140,1140,1140,1140]", "Europe/Samara")) - wed, d + 3 * h + 30 * 60000L);
                eq("аренда 10.77: все дни выходные — как «Сутки»",
                    IvaRentPatch.end(wed, 2, IvaRentPatch.rule("close", "[-1,-1,-1,-1,-1,-1,-1]", "Europe/Moscow")) - wed, 2 * d);
                eq("аренда 10.77: нет времени выдачи или срока — конца нет", IvaRentPatch.end(-1, 2, all19) + IvaRentPatch.end(wed, 0, all19), 0L);
                eq("аренда 10.77: время закрытия из базы — [..], {..} и просто через запятую",
                    IvaRentPatch.closeJson(IvaRentPatch.parseClose("{1080,1080,1080,1080,1080,900,-1}")) + IvaRentPatch.closeJson(IvaRentPatch.parseClose(" 600, 600,600,600,600,600,600 ")),
                    "[1080,1080,1080,1080,1080,900,-1][600,600,600,600,600,600,600]");
                eq("аренда 10.77: неверное время закрытия — по умолчанию 19:00",
                    IvaRentPatch.closeJson(IvaRentPatch.parseClose("[1080,1080]")) + IvaRentPatch.closeJson(IvaRentPatch.parseClose("[1,1,1,1,1,1,1440]"))
                    + IvaRentPatch.closeJson(IvaRentPatch.parseClose("[1,1,1,1,1,1,-2]")) + IvaRentPatch.closeJson(IvaRentPatch.parseClose("null")),
                    "[1140,1140,1140,1140,1140,1140,1140][1140,1140,1140,1140,1140,1140,1140][1140,1140,1140,1140,1140,1140,1140][1140,1140,1140,1140,1140,1140,1140]");
                eq("аренда 10.77: неизвестный режим — «Сутки», пустой пояс — Москва",
                    IvaRentPatch.rule("week", "", "null").mode + "|" + IvaRentPatch.rule(null, null, null).tz + "|" + IvaRentPatch.rule(" close ", "", "Asia/Omsk").byClose(),
                    "day|Europe/Moscow|true");
                eq("аренда 10.77: сохранение в базу — режим, 7 значений, пояс (без лишних символов)",
                    IvaRentPatch.setBody(IvaRentPatch.rule("close", "[1080,1080,1080,1080,1080,900,-1]", "Europe/Moscow\"}")),
                    "{\"p_mode\":\"close\",\"p_close\":[1080,1080,1080,1080,1080,900,-1],\"p_tz\":\"Europe/Moscow\"}");
                eq("аренда 10.77: подпись под «Аренда»", IvaRentPatch.summary(day) + "|" + IvaRentPatch.summary(week),
                    "Сутки: с 14:30 до 14:30|До закрытия пункта: с 14:30 до закрытия");
                eq("аренда 10.78: чч.мм.сс, часы не ограничены сутками", IvaRentPatch.hms(29 * 3600 + 32 * 60 + 7) + "|" + IvaRentPatch.hms(0) + "|" + IvaRentPatch.hms(120 * 86400L + 59) + "|" + IvaRentPatch.hms(-5),
                    "29.32.07|00.00.00|2880.00.59|00.00.00");
                eq("аренда 10.78: до конца — секунды вверх (1,001 с → 2 с, 1 с → 1 с), после — сколько прошло",
                    IvaRentPatch.seconds(wed + 1001, wed) + "," + IvaRentPatch.seconds(wed + 1000, wed) + "," + IvaRentPatch.seconds(wed, wed + 1999) + "," + IvaRentPatch.seconds(wed, wed), "2,1,1,0");
                eq("аренда 10.78: таймер до и после срока",
                    IvaRentPatch.timer(wed + d + 5 * h + 32 * 60000L + 7000, wed) + "|" + IvaRentPatch.timer(wed, wed + 2 * h + 15 * 60000L + 4000) + "|" + IvaRentPatch.timer(wed + 500, wed),
                    "Осталось 29.32.07|Просрочено 02.15.04|Осталось 00.00.01");
                eq("аренда 10.77: срок — дата и время", IvaRentPatch.until(IvaRentPatch.end(wed, 1, all19), java.util.TimeZone.getTimeZone("Europe/Moscow")), "до 15.10 в 19:00");
                java.util.List<IvaNotifyPatch.RentEnd> es = IvaNotifyPatch.rentEnds("11", "5", "Дрель (3 дня), Пила (1 день)", 3, wed, all19);
                eq("аренда 10.77: у каждого инструмента свой срок — по правилу проката (и для напоминания)",
                    (es.get(0).end - wed) + "|" + (es.get(1).end - wed), (3 * d + 4 * h + 30 * 60000L) + "|" + (d + 4 * h + 30 * 60000L));
                eq("аренда 10.77: ключ «уже напомнили» меняется вместе со сроком (сменили режим — напомним по новому)",
                    es.get(1).key.equals(IvaNotifyPatch.rentEnds("11", "5", "Дрель (3 дня), Пила (1 день)", 3, wed, day).get(1).key), false);
                java.util.List<IvaNotifyPatch.RentEnd> e1 = es.subList(1, 2);
                long e1e = es.get(1).end;
                eq("аренда 10.78: таймер тикает каждую секунду — со сменой секунды (+20 мс), не чаще раза в 200 мс",
                    IvaRentPatch.nextTick(e1, e1e - 90000) + "," + IvaRentPatch.nextTick(e1, e1e + 10000) + "," + IvaRentPatch.nextTick(e1, e1e - 1500) + ","
                    + IvaRentPatch.nextTick(e1, e1e - 300) + "," + IvaRentPatch.nextTick(e1, e1e - 10) + "," + IvaRentPatch.nextTick(e1, e1e + 999) + "," + IvaRentPatch.nextTick(es, e1e - 90000),
                    "1020,1020,520,320,200,200,1020");
                eq("аренда 10.77: подпись сроков меняется вместе со сроком", IvaRentPatch.sig(es).equals(IvaRentPatch.sig(IvaNotifyPatch.rentEnds("11", "5", "Дрель (3 дня), Пила (1 день)", 3, wed, day))), false);
                eq("аренда 10.78: заголовок раздела", IvaRentPatch.HEAD_TITLE, "Срок аренды");
            }
            // 10.78: кабинет — без контактов и у админа; «Чат поддержки» админам; «Обновить до версии …» по выпущенному релизу
            {
                eq("кабинет 10.78: «Позвонить», «Написать в МАКС», «Адрес выдачи» не показываем никому", IvaClientPatch.PROFILE_CONTACTS_SHOW, false);
                eq("кабинет 10.78: штатная отрисовка вернула строку — снова прячем (и у админа)",
                    IvaClientPatch.contactsLeak(IvaClientPatch.PROFILE_CONTACTS_SHOW, new boolean[]{false, true}) + "," + IvaClientPatch.contactsLeak(IvaClientPatch.PROFILE_CONTACTS_SHOW, new boolean[]{false, false}), "true,false");
                eq("кабинет 10.78: ссылка из «Подтвердить релиз» — имя файла → сайт",
                    IvaNotifyPatch.releaseApk(" ProkatInstrumenta-10.78.apk "), "https://eclipsru.github.io/Arenda-Nvk/ProkatInstrumenta-10.78.apk");
                eq("кабинет 10.78: ссылка на GitHub этого проекта — как есть",
                    IvaNotifyPatch.releaseApk("https://github.com/eclipsru/Arenda-Nvk/raw/main/ProkatInstrumenta-10.78.apk"), "https://github.com/eclipsru/Arenda-Nvk/raw/main/ProkatInstrumenta-10.78.apk");
                eq("кабинет 10.78: чужие и странные ссылки — не качаем",
                    IvaNotifyPatch.releaseApk("https://evil.example/ProkatInstrumenta-10.78.apk") + "|" + IvaNotifyPatch.releaseApk("https://github.com/other/Arenda-Nvk/raw/main/ProkatInstrumenta-10.78.apk")
                    + "|" + IvaNotifyPatch.releaseApk("https://github.com/eclipsru/Arenda-Nvk/raw/../x/ProkatInstrumenta-10.78.apk") + "|" + IvaNotifyPatch.releaseApk("../ProkatInstrumenta-10.78.apk")
                    + "|" + IvaNotifyPatch.releaseApk("ProkatInstrumenta-10.78.apk.exe") + "|" + IvaNotifyPatch.releaseApk(null) + "|" + IvaNotifyPatch.releaseApk(""), "||||||");
                String rel = "https://eclipsru.github.io/Arenda-Nvk/ProkatInstrumenta-10.78.apk";
                eq("кабинет 10.78: кнопка — только если выпущенная новее установленной",
                    IvaNotifyPatch.updateWanted(152, 151, rel) + "," + IvaNotifyPatch.updateWanted(152, 152, rel) + "," + IvaNotifyPatch.updateWanted(88, 151, rel)
                    + "," + IvaNotifyPatch.updateWanted(152, 151, "") + "," + IvaNotifyPatch.updateWanted(0, 151, rel) + "," + IvaNotifyPatch.updateWanted(152, 0, rel), "true,false,false,false,false,false");
                eq("кабинет 10.78: «Чат поддержки» — вошедшим админам, кроме создателя",
                    IvaNotifyPatch.supportFor(true, true, "a@b.ru") + "," + IvaNotifyPatch.supportFor(true, false, "a@b.ru") + "," + IvaNotifyPatch.supportFor(false, true, "a@b.ru")
                    + "," + IvaNotifyPatch.supportFor(true, true, " Eclips.Ru@mail.ru ") + "," + IvaNotifyPatch.supportFor(true, true, null), "true,false,false,false,true");
                eq("кабинет 10.78: чат поддержки — с создателем", IvaChatPatch.OWNER, "eclips.ru@mail.ru");
                java.util.Set<String> ad = new java.util.HashSet<String>(java.util.Arrays.asList("eclips.ru@mail.ru", "adm@x.ru"));
                eq("чат 10.78: у создателя переписка с админом — «Админ»; с клиентом — нет; у админа — нет",
                    IvaChatPatch.adminMark("eclips.ru@mail.ru", "Adm@X.ru", ad) + "," + IvaChatPatch.adminMark("eclips.ru@mail.ru", "client@y.ru", ad)
                    + "," + IvaChatPatch.adminMark("adm@x.ru", "eclips.ru@mail.ru", ad) + "," + IvaChatPatch.adminMark("eclips.ru@mail.ru", "eclips.ru@mail.ru", ad)
                    + "," + IvaChatPatch.adminMark("eclips.ru@mail.ru", "adm@x.ru", null), "true,false,false,false,false");
                eq("чат 10.78: «Чат поддержки» — общая переписка админа с создателем (у обоих), не по инструменту и не с клиентом",
                    IvaChatPatch.supportChat("eclips.ru@mail.ru", "adm@x.ru", ad, "") + "," + IvaChatPatch.supportChat("adm@x.ru", "eclips.ru@mail.ru", ad, "")
                    + "," + IvaChatPatch.supportChat("adm@x.ru", "eclips.ru@mail.ru", ad, "15") + "," + IvaChatPatch.supportChat("client@y.ru", "eclips.ru@mail.ru", ad, "")
                    + "," + IvaChatPatch.supportChat("adm@x.ru", "client@y.ru", ad, ""), "true,true,false,false,false");
                eq("чат 10.78: надписи", IvaChatPatch.ADMIN_BADGE + "|" + IvaChatPatch.SUPPORT_SUB + "|" + IvaNotifyPatch.SUPPORT_TITLE, "Админ|Чат поддержки|Чат поддержки");
            }
            // 10.79: «Вид инструмента» — справочник, форма, запись после штатного успеха, фильтр
            {
                java.util.List<IvaKindPatch.Kind> kl = new java.util.ArrayList<IvaKindPatch.Kind>();
                kl.add(new IvaKindPatch.Kind("screwdriver", "power", "drills", "Шуруповёрт", 2));
                kl.add(new IvaKindPatch.Kind("drill", "power", "drills", "Дрель", 1));
                kl.add(new IvaKindPatch.Kind("magnetic-drill", "power", "drills", "Магнитная дрель", 2));
                kl.add(new IvaKindPatch.Kind("rotary-sds-plus", "power", "hammers", "Перфоратор SDS-plus", 1));
                kl.add(new IvaKindPatch.Kind("bottle-jack", "moving", "hand-trucks", "Бутылочный домкрат", 3));
                kl.add(new IvaKindPatch.Kind("", "power", "drills", "Без ключа", 0));
                StringBuilder ks = new StringBuilder();
                for (IvaKindPatch.Kind k : IvaKindPatch.forSub(kl, "power", "drills")) ks.append(k.key).append(',');
                eq("вид 10.79: виды подкатегории — по порядку, при равном — по названию; без ключа — нет", ks.toString(), "drill,magnetic-drill,screwdriver,");
                eq("вид 10.79: чужая категория с тем же ключом подкатегории — не её виды; пусто / null — ничего",
                    IvaKindPatch.forSub(kl, "auto", "drills").size() + "," + IvaKindPatch.forSub(kl, "power", "").size() + "," + IvaKindPatch.forSub(kl, null, "drills").size()
                    + "," + IvaKindPatch.forSub(null, "power", "drills").size(), "0,0,0,0");
                eq("вид 10.79: вид подходит только своей подкатегории; пустой — всегда; нет справочника — не подходит",
                    IvaKindPatch.fits(kl, "power", "drills", "drill") + "," + IvaKindPatch.fits(kl, "power", "hammers", "drill") + "," + IvaKindPatch.fits(kl, "power", "drills", "")
                    + "," + IvaKindPatch.fits(kl, "power", "drills", null) + "," + IvaKindPatch.fits(null, "power", "drills", "drill") + "," + IvaKindPatch.fits(kl, "auto", "drills", "drill"),
                    "true,false,true,true,false,false");
                eq("вид 10.79: сменили подкатегорию — вид, которого в ней нет, снимается; есть — остаётся",
                    IvaKindPatch.keep(kl, "power", "hammers", "drill") + "|" + IvaKindPatch.keep(kl, "power", "drills", "drill") + "|" + IvaKindPatch.keep(kl, "power", "drills", null)
                    + "|" + IvaKindPatch.keep(null, "power", "drills", "drill"), "|drill||");
                eq("вид 10.79: название поля — выбранный вид или подсказка",
                    IvaKindPatch.fieldText(kl, "bottle-jack") + "|" + IvaKindPatch.fieldText(kl, "") + "|" + IvaKindPatch.fieldText(kl, "nope") + "|" + IvaKindPatch.fieldText(null, "drill"),
                    "Бутылочный домкрат|Вид инструмента (необязательно)|Вид инструмента (необязательно)|Вид инструмента (необязательно)");
                eq("вид 10.79: JSON null у Android («null») и пробелы — пусто", IvaKindPatch.clean("null") + "|" + IvaKindPatch.clean(" drill ") + "|" + IvaKindPatch.clean(null), "|drill|");
                eq("вид 10.79: запись — только известный формат ключа; снять вид — null",
                    IvaKindPatch.patchBody("drill") + "|" + IvaKindPatch.patchBody("") + "|" + IvaKindPatch.patchBody(null) + "|" + IvaKindPatch.patchBody("a\"b") + "|" + IvaKindPatch.patchBody("Дрель"),
                    "{\"kind\":\"drill\"}|{\"kind\":null}|{\"kind\":null}|null|null");
                eq("вид 10.79: правка — PATCH по id; вид открытого объявления — только вид",
                    IvaKindPatch.patchPath("809aa868-7020-4311-be41-9a62a5b0e684") + "|" + IvaKindPatch.kindPath("12"),
                    "/rest/v1/tools?id=eq.809aa868-7020-4311-be41-9a62a5b0e684|/rest/v1/tools?select=kind&id=eq.12");
                eq("вид 10.79: новое — своё последнее с этим названием за последние 30 минут (почта строчными, название без пробелов по краям)",
                    IvaKindPatch.lookupPath(" Adm@X.ru ", "  Дрель Bosch & Co ", "2026-10-15T10:00:00Z"),
                    "/rest/v1/tools?select=id,kind&owner_email=eq.adm%40x.ru&name=eq.%D0%94%D1%80%D0%B5%D0%BB%D1%8C+Bosch+%26+Co&created_at=gte.2026-10-15T10%3A00%3A00Z&order=created_at.desc&limit=1");
                eq("вид 10.79: время для поиска — UTC", IvaKindPatch.isoUtc(0L) + "|" + IvaKindPatch.isoUtc(1760522400000L), "1970-01-01T00:00:00Z|2025-10-15T10:00:00Z");
                long t0 = 1_000_000_000L;
                eq("вид 10.79: выбор, запомненный нажатием «Сохранить», живёт 3 минуты (и не «из будущего»)",
                    IvaKindPatch.pendingFresh(t0, t0) + "," + IvaKindPatch.pendingFresh(t0, t0 + 180_000L) + "," + IvaKindPatch.pendingFresh(t0, t0 + 180_001L)
                    + "," + IvaKindPatch.pendingFresh(t0, t0 - 1) + "," + IvaKindPatch.pendingFresh(0, 5), "true,true,false,false,false");
                eq("вид 10.79: записываем только при штатном успехе (кабинет «admhub»), любая другая вкладка — забыть",
                    IvaKindPatch.pendingWrite("admhub", t0, t0 + 2000) + "," + IvaKindPatch.pendingWrite("tooledit", t0, t0 + 2000) + "," + IvaKindPatch.pendingWrite("home", t0, t0 + 2000)
                    + "," + IvaKindPatch.pendingWrite("admhub", t0, t0 + 200_000L) + "," + IvaKindPatch.pendingWrite(null, t0, t0), "true,false,false,false,false");
                eq("вид 10.79: поле — сразу после списка подкатегорий; списка нет / он не рядом — после самой подкатегории; подкатегории нет — никуда",
                    IvaKindPatch.rowIndex(5, 6) + "," + IvaKindPatch.rowIndex(5, -1) + "," + IvaKindPatch.rowIndex(5, 9) + "," + IvaKindPatch.rowIndex(-1, 3), "7,6,6,-1");
                eq("вид 10.79: надписи формы", IvaKindPatch.LABEL_HINT + "|" + IvaKindPatch.NO_KIND, "Вид инструмента (необязательно)|Без вида");
                eq("фильтр 10.79: вид — не выбран: подходит всё (и без вида); выбран: только он",
                    IvaSearchPatch.kindMatch("drill", "") + "," + IvaSearchPatch.kindMatch("", null) + "," + IvaSearchPatch.kindMatch("drill", "drill") + ","
                    + IvaSearchPatch.kindMatch(" drill ", "drill") + "," + IvaSearchPatch.kindMatch("", "drill") + "," + IvaSearchPatch.kindMatch(null, "drill") + "," + IvaSearchPatch.kindMatch("screwdriver", "drill"),
                    "true,true,true,true,false,false,false");
                eq("фильтр 10.79: «Фильтр · N» — вид считается только вместе с подкатегорией",
                    IvaSearchPatch.activeCount("power", "drills", "drill", -1, -1, false, 0) + "," + IvaSearchPatch.activeCount("power", "", "drill", -1, -1, false, 0)
                    + "," + IvaSearchPatch.activeCount("", "", "drill", -1, -1, false, 0) + "," + IvaSearchPatch.activeCount("power", "drills", "", 300, -1, true, 0)
                    + "," + IvaSearchPatch.activeCount("power", "drills", null, -1, -1, false, 0), "3,1,0,4,2");
                IvaSearchPatch.Filter kf = new IvaSearchPatch.Filter();
                kf.cat = "power"; kf.sub = "drills"; kf.kind = "drill";
                IvaSearchPatch.Filter kc = kf.copy();
                String r1 = kc.kind + "," + kf.extraActive();
                kf.resetAll();
                eq("фильтр 10.79: вид копируется, включает отбор ленты, «Сбросить» его снимает", r1 + "," + kf.kind.isEmpty() + "," + kf.extraActive(), "drill,true,true,false");
            }
            {
                // 10.80: обновление без окон — скачать с процентами на кнопке и сразу установка
                eq("обновление 10.80: надписи на кнопке по шагам",
                    IvaNotifyPatch.updText(IvaNotifyPatch.UPD_IDLE, "10.80", -1) + "|" + IvaNotifyPatch.updText(IvaNotifyPatch.UPD_DL, "10.80", 45) + "|"
                    + IvaNotifyPatch.updText(IvaNotifyPatch.UPD_DL, "10.80", -1) + "|" + IvaNotifyPatch.updText(IvaNotifyPatch.UPD_CHECK, "10.80", 100) + "|"
                    + IvaNotifyPatch.updText(IvaNotifyPatch.UPD_INSTALL, "10.80", 100) + "|" + IvaNotifyPatch.updText(IvaNotifyPatch.UPD_READY, "10.80", 100) + "|"
                    + IvaNotifyPatch.updText(IvaNotifyPatch.UPD_IDLE, "", -1),
                    "Обновить до версии 10.80|Скачиваем версию 10.80 · 45%|Скачиваем версию 10.80…|Проверяем файл…|Открываем установку…|Установить версию 10.80|Обновить до версии");
                eq("обновление 10.80: «Обновить до версии N» — как у штатной кнопки (openUpdate ищет версию в тексте)",
                    IvaNotifyPatch.updText(IvaNotifyPatch.UPD_IDLE, "10.80", -1).contains("10.80") + "," + IvaNotifyPatch.updText(IvaNotifyPatch.UPD_DL, "10.80", 3).contains("10.80"), "true,true");
                eq("обновление 10.80: проценты — по длине файла, без длины -1, не больше 100",
                    IvaNotifyPatch.updPercent(0, 1000) + "," + IvaNotifyPatch.updPercent(455, 1000) + "," + IvaNotifyPatch.updPercent(1000, 1000) + ","
                    + IvaNotifyPatch.updPercent(1500, 1000) + "," + IvaNotifyPatch.updPercent(10, -1) + "," + IvaNotifyPatch.updPercent(10, 0) + ","
                    + IvaNotifyPatch.updPercent(1_337_455L, 1_337_455L), "0,45,100,100,-1,-1,100");
                eq("обновление 10.80: полоса на кнопке (10.96) — во время загрузки по процентам, иначе её нет (кнопка рамкой)",
                    IvaNotifyPatch.updLevel(IvaNotifyPatch.UPD_DL, 0) + "," + IvaNotifyPatch.updLevel(IvaNotifyPatch.UPD_DL, 45) + "," + IvaNotifyPatch.updLevel(IvaNotifyPatch.UPD_DL, -1)
                    + "," + IvaNotifyPatch.updLevel(IvaNotifyPatch.UPD_DL, 140) + "," + IvaNotifyPatch.updLevel(IvaNotifyPatch.UPD_IDLE, 45) + "," + IvaNotifyPatch.updLevel(IvaNotifyPatch.UPD_READY, 0),
                    "0,4500,0,10000,0,0");
                eq("обновление 10.80: файл — во внутреннем кэше, по номеру сборки", IvaNotifyPatch.updFileName(154), "iva-update-154.apk");
                eq("обновление 10.80: удаляем только файлы уже установленных (и старый штатный), чужие файлы не трогаем",
                    IvaNotifyPatch.staleUpdate("iva-update-153.apk", 154) + "," + IvaNotifyPatch.staleUpdate("iva-update-154.apk", 154) + ","
                    + IvaNotifyPatch.staleUpdate("iva-update-155.apk", 154) + "," + IvaNotifyPatch.staleUpdate("iva-update-150.apk.part", 154) + ","
                    + IvaNotifyPatch.staleUpdate("iva-update-155.apk.part", 154) + "," + IvaNotifyPatch.staleUpdate("iva-update.apk", 154) + ","
                    + IvaNotifyPatch.staleUpdate("photo.jpg", 154) + "," + IvaNotifyPatch.staleUpdate("iva-update-153.apk", 0) + "," + IvaNotifyPatch.staleUpdate(null, 154) + ","
                    + IvaNotifyPatch.staleUpdate("xiva-update-1.apk", 154), "true,true,false,true,false,true,false,false,false,false");
                eq("обновление 10.80: ставим только наш пакет и только версию новее установленной",
                    IvaNotifyPatch.apkFits("ru.prokatnvsk.app", 155, "ru.prokatnvsk.app", 154) + "," + IvaNotifyPatch.apkFits("ru.prokatnvsk.app", 154, "ru.prokatnvsk.app", 154) + ","
                    + IvaNotifyPatch.apkFits("ru.prokatnvsk.app", 120, "ru.prokatnvsk.app", 154) + "," + IvaNotifyPatch.apkFits("com.evil.app", 999, "ru.prokatnvsk.app", 154) + ","
                    + IvaNotifyPatch.apkFits(null, 999, "ru.prokatnvsk.app", 154) + "," + IvaNotifyPatch.apkFits("ru.prokatnvsk.app", 155, "ru.prokatnvsk.app", 0),
                    "true,false,false,false,false,false");
                eq("обновление 10.80: качаем только APK этого проекта",
                    IvaNotifyPatch.download("https://evil.example/ProkatInstrumenta-10.80.apk", new java.io.File("/nonexistent/x.apk")) + "|"
                    + IvaNotifyPatch.download("https://github.com/someone/else/raw/main/ProkatInstrumenta-10.80.apk", new java.io.File("/nonexistent/x.apk")),
                    "чужая ссылка|чужая ссылка");
                eq("обновление 10.80: короткий текст ошибки",
                    IvaNotifyPatch.shortErr(new java.io.IOException("timeout")) + "|" + IvaNotifyPatch.shortErr(new java.net.UnknownHostException()) + "|"
                    + IvaNotifyPatch.shortErr(new RuntimeException("   ")) + "|" + IvaNotifyPatch.shortErr(null) + "|" + IvaNotifyPatch.shortErr(new RuntimeException(new String(new char[200]).replace('\0', 'x'))).length(),
                    "timeout|UnknownHostException|RuntimeException|неизвестная ошибка|80");
            }
        }
        {   // 10.81: автоподбор категории, подкатегории и вида по названию — те же случаи, что tests/kind-guess.cjs
            java.util.List<IvaKindPatch.Kind> kwAk = new java.util.ArrayList<IvaKindPatch.Kind>();
            java.util.Map<String, String[]> kwAw = new java.util.HashMap<String, String[]>();
            String[][] kwMini = {{"a1", "c1", "s1", "перфорат|перфорат max"}, {"a2", "c1", "s1", "перфорат|перфорат plus"},
                {"b1", "c1", "s2", "пил|цепн пил"}, {"d1", "c2", "s3", "пил|пил по металл"}, {"e1", "c1", "s4", "фрез"}, {"e2", "c1", "s5", "фрез"}};
            for (String[] kwM : kwMini) { kwAk.add(new IvaKindPatch.Kind(kwM[0], kwM[1], kwM[2], kwM[0], 0)); kwAw.put(kwM[0], kwM[3].split("\\|")); }
            eq("автоподбор 10.81: ничья в подкатегории → категория и подкатегория", IvaKindPatch.guess("Перфоратор Makita", kwAk, kwAw).toString(), "c1/s1/");
            eq("автоподбор 10.81: уточнение выигрывает", IvaKindPatch.guess("перфоратор SDS-MAX", kwAk, kwAw).toString(), "c1/s1/a1");
            eq("автоподбор 10.81: сочетание выигрывает у общего", IvaKindPatch.guess("Цепная пила", kwAk, kwAw).toString(), "c1/s2/b1");
            eq("автоподбор 10.81: ничья между категориями → ничего", IvaKindPatch.guess("Пила", kwAk, kwAw).toString(), "//");
            eq("автоподбор 10.81: ничья в категории → только категория", IvaKindPatch.guess("Фреза", kwAk, kwAw).toString(), "c1//");
            eq("автоподбор 10.81: часть — только начало слова", IvaKindPatch.guess("бензопила", kwAk, kwAw).toString(), "//");
            eq("автоподбор 10.81: пусто, нет слов, нет справочника → ничего",
                IvaKindPatch.guess("", kwAk, kwAw) + "," + IvaKindPatch.guess(null, kwAk, kwAw) + "," + IvaKindPatch.guess("пила", kwAk, null) + "," + IvaKindPatch.guess("пила", null, kwAw), "//,//,//,//");
            eq("автоподбор 10.81: нормализация", String.join(" ", IvaKindPatch.tokens("Шуруповёрт-дрель, 18V!")), "шуруповерт дрель 18v");
            eq("автоподбор 10.81: ручной выбор не перезаписывается",
                IvaKindPatch.mayFill("", "x") + "," + IvaKindPatch.mayFill("x", "x") + "," + IvaKindPatch.mayFill("y", "x") + "," + IvaKindPatch.mayFill(null, null), "true,true,false,true");
            IvaKindPatch.Guess kwG1 = new IvaKindPatch.Guess("power", "hammers", "rotary-sds-max");
            String[][] kwPl = {
                {"", "", "", "", "", "", "power/hammers/rotary-sds-max", "пустая форма — всё"},
                {"garden", "", "", "", "", "", "null", "категорию выбрали руками — не меняем"},
                {"power", "hammers", "", "power", "hammers", "", "power/hammers/rotary-sds-max", "уточнили название — вид дописан"},
                {"power", "drills", "", "power", "", "", "null", "подкатегорию выбрали руками — остаётся, вид не ставим"},
                {"power", "", "", "", "", "", "power/hammers/rotary-sds-max", "та же категория руками — подкатегорию и вид дописываем"},
                {"power", "hammers", "rotary-sds-max", "", "", "", "null", "уже стоит то же — ничего"},
            };
            for (String[] kwC : kwPl) {
                String[] kwPr = IvaKindPatch.plan(kwC[0], kwC[1], kwC[2], kwC[3], kwC[4], kwC[5], kwG1);
                eq("автоподбор 10.81: план — " + kwC[7], kwPr == null ? "null" : kwPr[0] + "/" + kwPr[1] + "/" + kwPr[2], kwC[6]);
            }
            String[] kwR1 = IvaKindPatch.plan("power", "hammers", "rotary-sds-max", "power", "hammers", "rotary-sds-max", new IvaKindPatch.Guess("power", "hammers", ""));
            String[] kwR2 = IvaKindPatch.plan("power", "hammers", "rotary-sds-plus", "power", "hammers", "", new IvaKindPatch.Guess("power", "hammers", ""));
            String[] kwR3 = IvaKindPatch.plan("power", "hammers", "rotary-sds-max", "power", "hammers", "rotary-sds-max", new IvaKindPatch.Guess("garden", "trimmers", "chainsaw"));
            String[] kwR4 = IvaKindPatch.plan("", "", "", "", "", "", new IvaKindPatch.Guess("", "", ""));
            eq("автоподбор 10.81: план — общее название снимает наш вид, ручной вид остаётся, новое название заменяет наше, пусто — ничего",
                (kwR1 == null ? "null" : kwR1[0] + "/" + kwR1[1] + "/" + kwR1[2]) + "," + (kwR2 == null ? "null" : "x") + "," + (kwR3 == null ? "null" : kwR3[0] + "/" + kwR3[1] + "/" + kwR3[2]) + "," + (kwR4 == null ? "null" : "x"),
                "power/hammers/,null,garden/trimmers/chainsaw,null");
            eq("автоподбор 10.81: запрос слов — свой, справочник видов прежний (нет столбца — только без автоподбора)",
                IvaKindPatch.WORDS_PATH + " | " + IvaKindPatch.KINDS_PATH.contains("words"),
                "/rest/v1/iva_kinds?select=key,words&active=eq.true | false");
            // настоящий словарь: виды из 20261015_category_tree.sql, слова из 20261016_kind_words.sql, случаи — общий файл
            try {
                String kwTree = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("supabase/migrations/20261015_category_tree.sql")), "UTF-8");
                String kwWsql = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("supabase/migrations/20261016_kind_words.sql")), "UTF-8");
                int kwI0 = kwTree.indexOf("insert into public.iva_kinds"), kwI1 = kwTree.indexOf("on conflict (key)", kwI0);
                java.util.regex.Matcher kwKm = java.util.regex.Pattern.compile("\\('([a-z0-9-]+)',\\s*'([a-z0-9-]+)',\\s*'([a-z0-9-]+)',\\s*'([^']+)',\\s*(\\d+)\\)").matcher(kwTree.substring(kwI0, kwI1));
                java.util.List<IvaKindPatch.Kind> kwRk = new java.util.ArrayList<IvaKindPatch.Kind>();
                while (kwKm.find()) kwRk.add(new IvaKindPatch.Kind(kwKm.group(1), kwKm.group(2), kwKm.group(3), kwKm.group(4), Integer.parseInt(kwKm.group(5))));
                java.util.Map<String, String[]> kwRw = new java.util.HashMap<String, String[]>();
                java.util.regex.Matcher kwWm = java.util.regex.Pattern.compile("(?m)^\\s*\\('([a-z0-9-]+)', array\\[(.*)\\]::text\\[\\]\\)").matcher(kwWsql);
                while (kwWm.find()) {
                    String[] kwParts = kwWm.group(2).split(",");
                    for (int kwJ = 0; kwJ < kwParts.length; kwJ++) kwParts[kwJ] = kwParts[kwJ].trim().replaceAll("^'|'$", "");
                    kwRw.put(kwWm.group(1), kwParts);
                }
                eq("автоподбор 10.81: настоящий словарь — 159 видов, у всех есть слова", kwRk.size() + "/" + kwRw.size(), "159/159");
                int kwOkc = 0, kwTotal = 0;
                StringBuilder kwBadc = new StringBuilder();
                for (String kwLine : new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("tests/kind-guess-cases.txt")), "UTF-8").split("\n")) {
                    if (kwLine.trim().isEmpty() || kwLine.startsWith("#")) continue;
                    String[] kwC = kwLine.split("\\|", -1);
                    kwTotal++;
                    String kwGot = IvaKindPatch.guess(kwC[0], kwRk, kwRw).toString();
                    if (kwGot.equals(kwC[1] + "/" + kwC[2] + "/" + kwC[3])) kwOkc++; else if (kwBadc.length() < 300) kwBadc.append(kwC[0]).append(" → ").append(kwGot).append("; ");
                }
                eq("автоподбор 10.81: общие случаи tests/kind-guess-cases.txt (" + kwTotal + ") — как на сайте", kwOkc == kwTotal && kwTotal >= 120 ? "все" : kwBadc.toString(), "все");
                int kwOkt = 0;
                for (IvaKindPatch.Kind kwK : kwRk) if (IvaKindPatch.guess(kwK.title, kwRk, kwRw).toString().equals(kwK.cat + "/" + kwK.sub + "/" + kwK.key)) kwOkt++;
                eq("автоподбор 10.81: название каждого вида подбирает сам вид", kwOkt + "/" + kwRk.size(), "159/159");
            } catch (Throwable kwE) { eq("автоподбор 10.81: настоящий словарь", "исключение " + kwE, ""); }
        }
        // ───── 10.82: справочник моделей (IvaModelPatch) — то же, что assets/models.js (tests/model-search.cjs) ─────
        {
            java.util.LinkedHashMap<String, String> mdA = new java.util.LinkedHashMap<String, String>();
            java.util.LinkedHashMap<String, String> mdB = new java.util.LinkedHashMap<String, String>();
            java.util.LinkedHashMap<String, String> mdC = new java.util.LinkedHashMap<String, String>();
            mdC.put("power", "780"); mdC.put("energy", "2.4");
            java.util.LinkedHashMap<String, String>[] mdR = IvaModelPatch.specsFill(mdA, mdB, mdC);
            eq("модели 10.82: пустая форма заполняется", mdR[0] + "|" + mdR[1], "{power=780, energy=2.4}|{power=780, energy=2.4}");
            mdA.put("power", "800"); mdA.put("energy", "2.4");
            mdB.put("power", "780"); mdB.put("energy", "2.4");
            mdC = new java.util.LinkedHashMap<String, String>(); mdC.put("power", "830"); mdC.put("weight", "2.9");
            mdR = IvaModelPatch.specsFill(mdA, mdB, mdC);
            eq("модели 10.82: ручная мощность остаётся, наша энергия убирается, вес подставляется", mdR[0] + "|" + mdR[1], "{power=800, energy=, weight=2.9}|{weight=2.9}");
            mdA = new java.util.LinkedHashMap<String, String>(); mdA.put("power", "780"); mdA.put("src", "Сеть 220 В");
            mdB = new java.util.LinkedHashMap<String, String>(); mdB.put("power", "780");
            mdC = new java.util.LinkedHashMap<String, String>(); mdC.put("power", "830");
            mdR = IvaModelPatch.specsFill(mdA, mdB, mdC);
            eq("модели 10.82: наше прошлое значение меняется на новое, ручное поле цело", mdR[0] + "|" + mdR[1], "{power=830, src=Сеть 220 В}|{power=830}");
            mdA = new java.util.LinkedHashMap<String, String>(); mdA.put("power", "");
            mdR = IvaModelPatch.specsFill(mdA, mdB, new java.util.LinkedHashMap<String, String>());
            eq("модели 10.82: модель без характеристик ничего не ставит", mdR[0] + "|" + mdR[1], "{power=}|{}");
            mdR = IvaModelPatch.specsFill(null, null, null);
            eq("модели 10.82: пустые аргументы", mdR[0] + "|" + mdR[1], "{}|{}");
            IvaModelPatch.Model mdHr = new IvaModelPatch.Model(1, "rotary-sds-plus", "Makita", "HR2470", "Перфоратор Makita HR2470", null);
            IvaModelPatch.Model mdGbh = new IvaModelPatch.Model(2, "rotary-sds-plus", "Bosch", "GBH 2-26", "Перфоратор Bosch GBH 2-26", null);
            eq("модели 10.82: модель ещё подходит к названию (код слитно)",
                IvaModelPatch.stillFits("Перфоратор Makita HR2470", mdHr) + "," + IvaModelPatch.stillFits("Макита hr-2470 б/у", mdHr) + ","
                + IvaModelPatch.stillFits("Перфоратор Makita HR2450", mdHr) + "," + IvaModelPatch.stillFits("Перфоратор Makita", mdHr) + ","
                + IvaModelPatch.stillFits("Bosch GBH2-26", mdGbh) + "," + IvaModelPatch.stillFits("GBH 2 26 DRE", mdGbh) + ","
                + IvaModelPatch.stillFits("x", null) + "," + IvaModelPatch.stillFits("x", new IvaModelPatch.Model(3, "", "", "", "", null)),
                "true,true,false,false,true,true,false,false");
            eq("модели 10.82: числа как в JSON", IvaModelPatch.numText("2.40") + "|" + IvaModelPatch.numText("007") + "|" + IvaModelPatch.numText("780") + "|"
                + IvaModelPatch.numText("1000") + "|" + IvaModelPatch.numText("0.0") + "|" + IvaModelPatch.numText("-1.50"), "2.4|7|780|1000|0|-1.5");
            eq("модели 10.82: разбор характеристик", IvaModelPatch.parseFlat("{\"src\":\"Аккумулятор\", \"volt\" : 18,\"energy\":2.40,\"x\":null,\"q\":\"a\\\"b\\u0436\",\"ok\":true}").toString(),
                "{src=Аккумулятор, volt=18, energy=2.4, q=a\"bж, ok=true}");
            eq("модели 10.82: плохой JSON — пусто", IvaModelPatch.parseFlat("{\"a\":") + "|" + IvaModelPatch.parseFlat("[1]") + "|" + IvaModelPatch.parseFlat(null) + "|" + IvaModelPatch.parseFlat("{}"), "{}|{}|{}|{}");
            eq("модели 10.82: к записи вида дописываются модель и характеристики",
                IvaModelPatch.mergeBody("{\"kind\":\"drill\"}", IvaModelPatch.extraBody(12, "{\"power\":780}")) + " | " + IvaModelPatch.mergeBody("{\"kind\":\"drill\"}", IvaModelPatch.extraBody(0, null))
                + " | " + IvaModelPatch.mergeBody("{\"kind\":\"drill\"}", IvaModelPatch.extraBody(0, "{\"src\":\"Бензин\"}")) + " | " + IvaModelPatch.mergeBody(null, "x")
                + " | " + IvaModelPatch.extraBody(7, null),
                "{\"kind\":\"drill\",\"model_id\":12,\"specs\":{\"power\":780}} | {\"kind\":\"drill\"} | {\"kind\":\"drill\",\"specs\":{\"src\":\"Бензин\"}} | null | \"model_id\":7");
            eq("модели 10.82: строка в JSON экранируется", IvaModelPatch.jsonStr("a\"b\\c\nd\u0001"), "\"a\\\"b\\\\c\\nd\\u0001\"");
            eq("модели 10.82: запрос моделей — страницами по 1000, только активные", IvaModelPatch.modelsPath(2000),
                "/rest/v1/iva_models?select=id,kind,brand,model,title,specs&active=eq.true&order=id.asc&limit=1000&offset=2000");
            eq("модели 10.82: надписи", IvaModelPatch.MODEL_LABEL + "|" + IvaModelPatch.MODEL_HINT + "|" + IvaModelPatch.SPECS_LABEL + "|"
                + new IvaModelPatch.Field("k", "power", "Мощность", "Вт", "num", null, 1).label() + "|" + new IvaModelPatch.Field("k", "src", "Питание", "", "choice", null, 1).label() + "|"
                + IvaModelPatch.modelText(mdGbh) + "|" + IvaModelPatch.modelText(null), "Модель|Например: HR2470 — или выберите из списка|Характеристики|Мощность, Вт|Питание|Bosch GBH 2-26|");
            java.util.List<IvaModelPatch.Model> mdMini = new java.util.ArrayList<IvaModelPatch.Model>();
            mdMini.add(new IvaModelPatch.Model(1, "k", "Denzel", "A1", "X Denzel A1", null)); mdMini.add(new IvaModelPatch.Model(2, "k", "DeWalt", "B2", "X DeWalt B2", null));
            mdMini.add(new IvaModelPatch.Model(3, "k", "AEG", "C3", "X AEG C3", null)); mdMini.add(new IvaModelPatch.Model(4, "z", "Bosch", "D4", "X Bosch D4", null));
            eq("модели 10.82: бренды без учёта регистра (Denzel раньше DeWalt), только свой вид",
                IvaModelPatch.brands(IvaModelPatch.index(mdMini, new java.util.LinkedHashMap<String, String[]>(), null), "k").toString(), "[AEG, Denzel, DeWalt]");
            try {
                java.nio.file.Path mdMig = java.nio.file.Paths.get("supabase/migrations");
                java.util.List<IvaModelPatch.Model> mdMs = new java.util.ArrayList<IvaModelPatch.Model>();
                java.util.List<String> mdSql = new java.util.ArrayList<String>();
                for (int mdN = 1; java.nio.file.Files.exists(mdMig.resolve("20261017_models_data_" + mdN + ".sql")); mdN++)
                    mdSql.add(new String(java.nio.file.Files.readAllBytes(mdMig.resolve("20261017_models_data_" + mdN + ".sql")), "UTF-8"));
                java.util.regex.Pattern mdTp = java.util.regex.Pattern.compile("(?m)^ {2}\\('((?:[^']|'')*)', '((?:[^']|'')*)', '((?:[^']|'')*)', '((?:[^']|'')*)', '((?:[^']|'')*)'\\)");
                long mdId = 1;
                java.util.List<String> mdRaw = new java.util.ArrayList<String>();
                for (String mdS : mdSql) {
                    java.util.regex.Matcher mdM = mdTp.matcher(mdS);
                    while (mdM.find()) {
                        String mdJs = mdM.group(5).replace("''", "'");
                        mdRaw.add(mdJs);
                        mdMs.add(new IvaModelPatch.Model(mdId++, mdM.group(1), mdM.group(2).replace("''", "'"), mdM.group(3).replace("''", "'"), mdM.group(4).replace("''", "'"), IvaModelPatch.parseFlat(mdJs)));
                    }
                }
                String mdSch = new String(java.nio.file.Files.readAllBytes(mdMig.resolve("20261017_models.sql")), "UTF-8");
                java.util.regex.Matcher mdNm = java.util.regex.Pattern.compile("\\((\\d+) моделей\\)").matcher(mdSch);
                eq("модели 10.82: модели из SQL — столько, сколько в шапке", mdNm.find() && mdMs.size() >= 2000 && Integer.parseInt(mdNm.group(1)) == mdMs.size() ? "да" : mdMs.size() + "", "да");
                String mdBs = mdSch.split("3\\. Бренды")[1].split("4\\. Характеристики")[0];
                java.util.LinkedHashMap<String, String[]> mdBr = new java.util.LinkedHashMap<String, String[]>();
                java.util.regex.Matcher mdBm = java.util.regex.Pattern.compile("(?m)^ {2}\\('((?:[^']|'')*)', (?:array\\[(.*)\\]::text\\[\\]|'\\{\\}'::text\\[\\])\\)").matcher(mdBs);
                while (mdBm.find()) {
                    String[] mdAl = mdBm.group(2) == null ? new String[0] : mdBm.group(2).split(", ");
                    for (int mdJ = 0; mdJ < mdAl.length; mdJ++) mdAl[mdJ] = mdAl[mdJ].replaceAll("^'|'$", "").replace("''", "'");
                    mdBr.put(mdBm.group(1).replace("''", "'"), mdAl);
                }
                String mdFs = mdSch.split("create temp table _f")[1];
                java.util.List<IvaModelPatch.Field> mdFl = new java.util.ArrayList<IvaModelPatch.Field>();
                java.util.regex.Matcher mdFm = java.util.regex.Pattern.compile("(?m)^ {2}\\('([a-z0-9-]+)', '([a-z0-9]+)', '((?:[^']|'')*)', '((?:[^']|'')*)', '(num|choice|text)', (?:array\\[(.*)\\]::text\\[\\]|'\\{\\}'::text\\[\\]), (\\d+)\\)").matcher(mdFs);
                java.util.Set<String> mdFk = new java.util.HashSet<String>();
                while (mdFm.find()) {
                    String[] mdOp = mdFm.group(6) == null ? new String[0] : mdFm.group(6).split("', '");
                    for (int mdJ = 0; mdJ < mdOp.length; mdJ++) mdOp[mdJ] = mdOp[mdJ].replaceAll("^'|'$", "").replace("''", "'");
                    mdFl.add(new IvaModelPatch.Field(mdFm.group(1), mdFm.group(2), mdFm.group(3).replace("''", "'"), mdFm.group(4).replace("''", "'"), mdFm.group(5), mdOp, Integer.parseInt(mdFm.group(7))));
                    mdFk.add(mdFm.group(1));
                }
                String mdWs = new String(java.nio.file.Files.readAllBytes(mdMig.resolve("20261016_kind_words.sql")), "UTF-8");
                java.util.Map<String, String[]> mdW = new java.util.HashMap<String, String[]>();
                java.util.regex.Matcher mdWm = java.util.regex.Pattern.compile("(?m)^\\s*\\('([a-z0-9-]+)', array\\[(.*)\\]::text\\[\\]\\)").matcher(mdWs);
                while (mdWm.find()) {
                    String[] mdP = mdWm.group(2).split(",");
                    for (int mdJ = 0; mdJ < mdP.length; mdJ++) mdP[mdJ] = mdP[mdJ].trim().replaceAll("^'|'$", "");
                    mdW.put(mdWm.group(1), mdP);
                }
                eq("модели 10.82: бренды, слова видов, поля 159 видов", (mdBr.size() >= 80) + "/" + mdW.size() + "/" + mdFk.size(), "true/159/159");
                int mdLost = 0, mdForeign = 0;
                for (int mdI = 0; mdI < mdMs.size(); mdI++) {
                    IvaModelPatch.Model mdM = mdMs.get(mdI);
                    java.util.List<IvaModelPatch.Field> mdF = IvaModelPatch.fieldsFor(mdFl, mdM.kind);
                    for (String mdK : mdM.specs.keySet()) { boolean mdHas = false; for (IvaModelPatch.Field mdX : mdF) if (mdX.key.equals(mdK)) mdHas = true; if (!mdHas) mdForeign++; }
                    String mdCl = IvaModelPatch.specsClean(mdM.specs, mdF);
                    if (mdM.specs.isEmpty() ? mdCl != null : !IvaModelPatch.parseFlat(mdCl).equals(mdM.specs)) mdLost++;
                    if (mdCl != null && !IvaModelPatch.parseFlat(mdRaw.get(mdI)).equals(IvaModelPatch.parseFlat(mdCl))) mdLost++;
                }
                eq("модели 10.82: характеристики моделей — поля своего вида, очистка ничего не теряет", mdForeign + "/" + mdLost, "0/0");
                IvaModelPatch.Index mdIx = IvaModelPatch.index(mdMs, mdBr, mdW);
                eq("модели 10.82: индекс по всем моделям", mdIx.list.size() == mdMs.size(), true);
                int mdOk = 0, mdTot = 0;
                StringBuilder mdBad = new StringBuilder();
                for (String mdLine : new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("tests/model-search-cases.txt")), "UTF-8").split("\n")) {
                    if (mdLine.trim().isEmpty() || mdLine.startsWith("#")) continue;
                    String[] mdC2 = mdLine.split("\\|", -1);
                    mdTot++;
                    java.util.List<IvaModelPatch.Model> mdH = IvaModelPatch.search(mdC2[0], mdIx, mdC2[1], mdC2[2], 8);
                    boolean mdGood = true;
                    if (!mdC2[3].isEmpty() && !"-".equals(mdC2[3])) mdGood = !mdH.isEmpty() && mdH.get(0).title.equals(mdC2[3]);
                    if ("-".equals(mdC2[3])) mdGood = mdH.isEmpty();
                    if (mdC2.length > 4 && !mdC2[4].isEmpty() && mdH.size() != Integer.parseInt(mdC2[4])) mdGood = false;
                    for (IvaModelPatch.Model mdX : mdH) {
                        if (!mdC2[1].isEmpty() && !mdX.kind.equals(mdC2[1])) mdGood = false;
                        if (!mdC2[2].isEmpty() && !mdX.brand.equals(mdC2[2])) mdGood = false;
                    }
                    if (mdGood) mdOk++;
                    else if (mdBad.length() < 400) mdBad.append(mdC2[0]).append(" → ").append(mdH.isEmpty() ? "—" : mdH.get(0).title).append(" (").append(mdH.size()).append("); ");
                }
                eq("модели 10.82: общие случаи tests/model-search-cases.txt (" + mdTot + ") — как на сайте", mdOk == mdTot && mdTot >= 40 ? "все" : mdBad.toString(), "все");
                java.util.Set<String> mdSeen = new java.util.HashSet<String>(), mdDup = new java.util.HashSet<String>();
                for (IvaModelPatch.Model mdX : mdMs) if (!mdSeen.add(mdX.title)) mdDup.add(mdX.title);
                int mdSelf = 0, mdPair = 0;
                StringBuilder mdBad2 = new StringBuilder();
                for (IvaModelPatch.Model mdX : mdMs) {
                    java.util.List<IvaModelPatch.Model> mdH = IvaModelPatch.search(mdX.title, mdIx, "", "", 8);
                    if (mdDup.contains(mdX.title) ? mdH.contains(mdX) : !mdH.isEmpty() && mdH.get(0) == mdX) mdSelf++;
                    else if (mdBad2.length() < 300) mdBad2.append(mdX.title).append("; ");
                    if (IvaModelPatch.search(mdX.brand + " " + mdX.model, mdIx, "", "", 8).contains(mdX)) mdPair++;
                }
                eq("модели 10.82: название каждой модели находит её саму (" + mdMs.size() + ")", mdSelf == mdMs.size() ? "все" : mdBad2.toString(), "все");
                eq("модели 10.82: бренд + модель находит модель среди подсказок", mdPair + "/" + mdMs.size(), mdMs.size() + "/" + mdMs.size());
                java.util.List<String> mdBl = IvaModelPatch.brands(mdIx, "rotary-sds-plus");
                java.util.List<String> mdSorted = new java.util.ArrayList<String>(new java.util.LinkedHashSet<String>(mdBl));
                java.util.Collections.sort(mdSorted, new java.util.Comparator<String>() { public int compare(String x, String y) { return x.toLowerCase().compareTo(y.toLowerCase()); } });
                eq("модели 10.82: бренды вида по алфавиту, без повторов; у вида без моделей брендов нет",
                    (mdBl.size() > 5 && mdBl.equals(mdSorted) && mdBl.contains("Makita")) + "," + IvaModelPatch.brands(mdIx, "bottle-jack").size(), "true,0");
                java.util.Map<String, String> mdV = new java.util.HashMap<String, String>();
                mdV.put("src", "Аккумулятор"); mdV.put("volt", "18"); mdV.put("power", " 780 "); mdV.put("energy", "2,4"); mdV.put("weight", "abc"); mdV.put("zzz", "1");
                java.util.List<IvaModelPatch.Field> mdRf = IvaModelPatch.fieldsFor(mdFl, "rotary-sds-plus");
                eq("модели 10.82: числа с запятой, лишние и плохие поля отбрасываются (как на сайте)", IvaModelPatch.specsClean(mdV, mdRf),
                    "{\"src\":\"Аккумулятор\",\"volt\":18,\"power\":780,\"energy\":2.4}");
                java.util.Map<String, String> mdV2 = new java.util.HashMap<String, String>();
                mdV2.put("src", "Газ");                                             // 10.86: «Бензин» теперь есть у всех видов
                java.util.Map<String, String> mdV3 = new java.util.HashMap<String, String>();
                mdV3.put("pipe", "  20–63 ");
                eq("модели 10.82: чужой вариант и пусто → null; строка — без пробелов по краям",
                    IvaModelPatch.specsClean(mdV2, mdRf) + "|" + IvaModelPatch.specsClean(new java.util.HashMap<String, String>(), mdRf) + "|" + IvaModelPatch.specsClean(null, mdRf)
                    + "|" + IvaModelPatch.specsClean(mdV3, IvaModelPatch.fieldsFor(mdFl, "pp-welder")), "null|null|null|{\"pipe\":\"20–63\"}");
                // 10.83: характеристики в карточке и фильтре — на настоящих полях
                java.util.Map<String, String> chS = new java.util.LinkedHashMap<String, String>();
                chS.put("weight", ""); chS.put("energy", "2.4"); chS.put("zzz", "1"); chS.put("power", "780"); chS.put("src", "Сеть 220 В");
                StringBuilder chR = new StringBuilder();
                for (String[] chX : IvaModelPatch.specRows(chS, IvaModelPatch.fieldsFor(mdFl, "rotary-sds-plus"))) chR.append(chX[0]).append('=').append(chX[1]).append('|');
                eq("характеристики 10.83: строки карточки — по порядку полей, число с запятой и единицей, пустое и чужое не показываем",
                    chR.toString(), "Питание=Сеть 220 В|Мощность=780" + NB + "Вт|Энергия удара=2,4" + NB + "Дж|");
                eq("характеристики 10.83: нет подписей или характеристик — строк нет",
                    IvaModelPatch.specRows(chS, null).size() + "," + IvaModelPatch.specRows(null, mdRf).size() + "," + IvaModelPatch.specRows(chS, IvaModelPatch.fieldsFor(mdFl, "")).size(), "0,0,0");
                StringBuilder chC = new StringBuilder();
                for (IvaModelPatch.Field chF : IvaModelPatch.choiceFields(mdFl, "chainsaw")) chC.append(chF.key).append(',');
                eq("характеристики 10.83: в фильтре только поля-выбор вида", chC.toString() + "|" + IvaModelPatch.choiceFields(null, "chainsaw").size(), "src,|0");
                int chAll = 0, chKinds = 0;
                java.util.Set<String> chSeen = new java.util.HashSet<String>();
                for (IvaModelPatch.Field chF : mdFl) if (chSeen.add(chF.kind) && !IvaModelPatch.choiceFields(mdFl, chF.kind).isEmpty()) chKinds++;
                for (IvaModelPatch.Field chF : mdFl) if ("choice".equals(chF.type)) { chAll++; if (chF.options.length < 2) chKinds = -1000; }
                eq("характеристики 10.83: у каждого поля-выбора не меньше двух вариантов", chAll > 50 && chKinds > 50, true);
                java.util.Map<String, String> chT = new java.util.HashMap<String, String>();
                chT.put("src", "Бензин"); chT.put("bar", "40");
                java.util.Map<String, String> chSel = new java.util.LinkedHashMap<String, String>();
                String chM0 = IvaModelPatch.specsMatch(chT, chSel) + "," + IvaModelPatch.specsMatch(chT, null);
                chSel.put("src", "Бензин");
                String chM1 = IvaModelPatch.specsMatch(chT, chSel) + "," + IvaModelPatch.specsMatch(new java.util.HashMap<String, String>(), chSel) + "," + IvaModelPatch.specsMatch(null, chSel);
                chSel.put("src", "Аккумулятор");
                eq("характеристики 10.83: совпадение — все выбранные значения; без значения — не подходит",
                    chM0 + "|" + chM1 + "|" + IvaModelPatch.specsMatch(chT, chSel), "true,true|true,false,false|false");
                eq("характеристики 10.83: плашка условия — «Питание: Бензин»; без подписей — только значение",
                    IvaModelPatch.chipText(mdFl, "chainsaw", "src", "Бензин") + "|" + IvaModelPatch.chipText(null, "chainsaw", "src", "Бензин"), "Питание: Бензин|Бензин");
                IvaSearchPatch.Filter chF1 = new IvaSearchPatch.Filter();
                chF1.cat = "garden"; chF1.sub = "saws"; chF1.kind = "chainsaw"; chF1.specKind = "chainsaw"; chF1.specs.put("src", "Бензин");
                IvaSearchPatch.Filter chF2 = chF1.copy();
                chF2.specs.put("src", "Аккумулятор");
                String chA = chF1.specCount() + "," + chF1.extraActive() + "," + chF1.specs.get("src") + "," + chF2.specs.get("src");
                chF2.kind = "brushcutter";
                String chB = chF2.specCount() + "";
                chF1.resetAll();
                eq("характеристики 10.83: фильтр — копия независима, другой вид снимает характеристики, «Сбросить» очищает",
                    chA + "|" + chB + "|" + chF1.specCount() + "," + chF1.specs.size() + "," + chF1.extraActive(), "1,true,Бензин,Аккумулятор|0|0,0,false");
                // 10.84: этап 4 — общие случаи tests/model-stage4-cases.txt (их же проверяет tests/model-stage4.cjs на сайте)
                java.util.LinkedHashMap<String, String[]> s4Br = new java.util.LinkedHashMap<String, String[]>();
                IvaModelPatch.Index s4Ix = null;
                int s4Ok = 0, s4Tot = 0;
                java.util.Map<String, Integer> s4Cnt = new java.util.TreeMap<String, Integer>();
                StringBuilder s4Bad = new StringBuilder();
                for (String s4L : new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("tests/model-stage4-cases.txt")), "UTF-8").split("\n")) {
                    if (s4L.isEmpty() || s4L.startsWith("#")) continue;
                    String[] s4F = s4L.split("\t", -1);
                    String s4Got = null, s4Exp = null;
                    if ("brands".equals(s4F[0])) {
                        for (String s4X : s4F[1].split(";")) { String[] s4P = s4X.split("=", -1); s4Br.put(s4P[0], s4P.length > 1 && !s4P[1].isEmpty() ? s4P[1].split(",") : new String[0]); }
                        continue;
                    } else if ("models".equals(s4F[0])) {
                        java.util.List<IvaModelPatch.Model> s4Ms = new java.util.ArrayList<IvaModelPatch.Model>();
                        int s4I = 1;
                        for (String s4X : s4F[1].split(";")) { String[] s4P = s4X.split(":"); s4Ms.add(new IvaModelPatch.Model(s4I++, s4P[0], s4P[1], s4P[2], s4P[2], null)); }
                        s4Ix = IvaModelPatch.index(s4Ms, s4Br, null);
                        continue;
                    } else if ("parts".equals(s4F[0])) {
                        String[] s4R = IvaModelPatch.proposalParts(s4F[1], s4F[2], s4Br);
                        s4Got = s4R == null ? "—" : s4R[0] + "|" + s4R[1]; s4Exp = s4F[3];
                    } else if ("hint".equals(s4F[0])) {
                        java.util.List<IvaModelPatch.PRow> s4Rows = new java.util.ArrayList<IvaModelPatch.PRow>();
                        for (String s4T : s4F[3].split(" ")) {
                            int s4N = 1, s4X = s4T.indexOf('×');
                            if (s4X >= 0) { s4N = Integer.parseInt(s4T.substring(s4X + 1)); s4T = s4T.substring(0, s4X); }
                            String[] s4P = s4T.split(":", -1);
                            for (int s4J = 0; s4J < s4N; s4J++) s4Rows.add(new IvaModelPatch.PRow(s4P[0].isEmpty() ? Double.NaN : Double.parseDouble(s4P[0]),
                                s4P[1].isEmpty() ? Double.NaN : Double.parseDouble(s4P[1]), "-".equals(s4P[2]) ? 0 : Long.parseLong(s4P[2]), s4P[3]));
                        }
                        IvaModelPatch.Hint s4H = IvaModelPatch.priceHint(s4Rows, s4F[1].isEmpty() ? 0 : Long.parseLong(s4F[1]), s4F[2]);
                        s4Got = s4H == null ? "—" : IvaModelPatch.hintText(s4H).replace('\u00a0', ' ') + " #" + s4H.price[2] + "/" + (s4H.deposit == null ? "-" : String.valueOf(s4H.deposit[2]));
                        s4Exp = s4F[4];
                    } else if ("describe".equals(s4F[0])) {
                        java.util.List<String[]> s4Rows = new java.util.ArrayList<String[]>();
                        if (!s4F[2].isEmpty()) for (String s4X : s4F[2].split(";")) s4Rows.add(s4X.split("=", 2));
                        java.util.List<String> s4K = s4F[3].isEmpty() ? new java.util.ArrayList<String>() : java.util.Arrays.asList(s4F[3].split(";"));
                        s4Got = IvaModelPatch.describe(s4F[1], s4Rows, s4K);
                        if (s4Got.isEmpty()) s4Got = "—";
                        s4Exp = s4F[4].replace("\\n", "\n");
                    } else if ("kit".equals(s4F[0])) {
                        java.util.List<String> s4R = IvaModelPatch.kitClean(s4F[1].isEmpty() ? new java.util.ArrayList<String>() : java.util.Arrays.asList(s4F[1].split(";")),
                            s4F[2].isEmpty() ? new String[0] : s4F[2].split(";"));
                        StringBuilder s4B = new StringBuilder();
                        if (s4R != null) for (String s4X : s4R) s4B.append(s4B.length() > 0 ? ";" : "").append(s4X);
                        s4Got = s4R == null ? "—" : s4B.toString(); s4Exp = s4F[3];
                    } else if ("title".equals(s4F[0])) {
                        s4Got = IvaModelPatch.proposalTitle(s4F[1], s4F[2].split("\\|"), s4F[3]); s4Exp = s4F[4];
                    } else if ("exact".equals(s4F[0])) {
                        IvaModelPatch.Model s4M = IvaModelPatch.exact(s4Ix, s4F[1], s4F[2]);
                        s4Got = s4M == null ? "—" : s4M.brand + "|" + s4M.model; s4Exp = s4F[3];
                    } else if ("err".equals(s4F[0])) {
                        s4Got = IvaModelPatch.propErr(s4F[1]); s4Exp = s4F[2];
                    } else continue;
                    s4Tot++;
                    s4Cnt.put(s4F[0], (s4Cnt.containsKey(s4F[0]) ? s4Cnt.get(s4F[0]) : 0) + 1);
                    if (s4Exp.equals(s4Got)) s4Ok++;
                    else if (s4Bad.length() < 600) s4Bad.append(s4F[0]).append(" «").append(s4F[1]).append("» → ").append(s4Got).append("; ");
                }
                eq("этап 4 10.84: общие случаи tests/model-stage4-cases.txt (" + s4Tot + ") — как на сайте", s4Ok == s4Tot && s4Tot >= 60 ? "все" : s4Bad.toString(), "все");
                eq("этап 4 10.84: все виды случаев", s4Cnt.keySet().toString(), "[describe, err, exact, hint, kit, parts, title]");
                eq("этап 4 10.84: тело предложения — JSON, характеристики объектом",
                    IvaModelPatch.proposalBody("drill", new String[]{"Bosch", "GBH \"9\""}, "Дрель", null) + "|" + IvaModelPatch.proposalBody("drill", new String[]{"B", "M"}, "T", "{\"p\":1}"),
                    "{\"kind\":\"drill\",\"brand\":\"Bosch\",\"model\":\"GBH \\\"9\\\"\",\"title\":\"Дрель\",\"specs\":{}}|{\"kind\":\"drill\",\"brand\":\"B\",\"model\":\"M\",\"title\":\"T\",\"specs\":{\"p\":1}}");
                java.util.List<String> s4Kit = java.util.Arrays.asList("Кейс", "Бур \"SDS\"");
                String s4Ex = IvaModelPatch.extraBody(12, "{\"a\":1}") + ",\"kit\":" + IvaModelPatch.kitJson(s4Kit);
                eq("этап 4 10.84: запись — комплектация последней; без столбца kit — модель и характеристики; без модели — только kit",
                    IvaModelPatch.mergeBody("{\"kind\":\"drill\"}", s4Ex) + "|" + IvaModelPatch.mergeBody("{\"kind\":\"drill\"}", IvaModelPatch.dropKit(s4Ex)) + "|"
                        + IvaModelPatch.dropKit("\"kit\":[\"Кейс\"]") + "|" + IvaModelPatch.dropKit("\"model_id\":3") + "|" + IvaModelPatch.kitJson(null),
                    "{\"kind\":\"drill\",\"model_id\":12,\"specs\":{\"a\":1},\"kit\":[\"Кейс\",\"Бур \\\"SDS\\\"\"]}|{\"kind\":\"drill\",\"model_id\":12,\"specs\":{\"a\":1}}||\"model_id\":3|null");
                eq("этап 4 10.84: tools.kit карточки — строки массива, мусор — пусто",
                    IvaModelPatch.parseStrList("[\"Кейс\", \" \", \"Бур \\\"SDS\\\"\"]") + "|" + IvaModelPatch.parseStrList("[]") + "|" + IvaModelPatch.parseStrList("null")
                        + "|" + IvaModelPatch.parseStrList("[1,2]") + "|" + IvaModelPatch.parseStrList("[\"a\""),
                    "[Кейс, Бур \"SDS\"]|[]|[]|[]|[]");
                java.util.Map<String, String> s4V = new java.util.HashMap<String, String>();
                s4V.put("power", "780,0"); s4V.put("src", "Сеть 220 В"); s4V.put("energy", "abc"); s4V.put("bogus", "1");
                java.util.List<IvaModelPatch.Field> s4Fl = IvaModelPatch.fieldsFor(mdFl, "rotary-sds-plus");
                eq("этап 4 10.84: описание из формы — характеристики очищены (число с запятой, мусор и чужое не берём)",
                    IvaModelPatch.describe("Перфоратор", IvaModelPatch.specRows(IvaModelPatch.cleanMap(s4V, s4Fl), s4Fl), null).replace('\u00a0', ' '),
                    "Перфоратор в аренду.\n\nХарактеристики:\n— Питание: Сеть 220 В\n— Мощность: 780 Вт");
                // настоящая комплектация: у каждого вида с пунктами описание собирается
                int s4KOk = 0, s4KAll = 0;
                for (String s4L : new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("supabase/models/kit.txt")), "UTF-8").split("\n")) {
                    java.util.regex.Matcher s4M = java.util.regex.Pattern.compile("^([a-z0-9-]+):\\s*(.*)$").matcher(s4L.trim());
                    if (!s4M.matches()) continue;
                    s4KAll++;
                    String[] s4It = s4M.group(2).isEmpty() ? new String[0] : s4M.group(2).split("\\s*;\\s*");
                    java.util.List<String> s4C = IvaModelPatch.kitClean(java.util.Arrays.asList(s4It), s4It);
                    String s4D = IvaModelPatch.describe("Инструмент", null, s4C);
                    if (s4It.length == 0 ? s4C == null && s4D.equals("Инструмент в аренду.") : s4D.endsWith(".") && s4D.split(", ").length == s4It.length) s4KOk++;
                }
                eq("этап 4 10.84: комплектация 159 видов из supabase/models/kit.txt", s4KOk + "/" + s4KAll, "159/159");
                // 10.85: фильтр по характеристикам — общие случаи tests/spec-filter-cases.txt (их же проверяет tests/spec-filter.cjs)
                int sfOk = 0, sfTot = 0;
                java.util.Map<String, Integer> sfCnt = new java.util.TreeMap<String, Integer>();
                StringBuilder sfBad = new StringBuilder();
                for (String sfL : new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("tests/spec-filter-cases.txt")), "UTF-8").split("\n")) {
                    if (sfL.isEmpty() || sfL.startsWith("#")) continue;
                    String[] sfF = sfL.split("\t", -1);
                    String sfGot, sfExp;
                    if ("num".equals(sfF[0])) {
                        double sfV = IvaModelPatch.num(sfF[1]);
                        sfGot = Double.isNaN(sfV) ? "" : (sfV == Math.rint(sfV) ? String.valueOf((long) sfV) : String.valueOf(sfV)); sfExp = sfF[2];
                    } else if ("state".equals(sfF[0])) {
                        java.util.LinkedHashMap<String, String> sfT = new java.util.LinkedHashMap<String, String>(), sfS = new java.util.LinkedHashMap<String, String>();
                        java.util.LinkedHashMap<String, double[]> sfR = new java.util.LinkedHashMap<String, double[]>();
                        if (!sfF[1].isEmpty()) for (String sfX : sfF[1].split(";")) { String[] sfP = sfX.split("=", 2); sfT.put(sfP[0], sfP[1]); }
                        if (!sfF[2].isEmpty()) for (String sfX : sfF[2].split(";")) { String[] sfP = sfX.split("=", 2); sfS.put(sfP[0], sfP[1]); }
                        if (!sfF[3].isEmpty()) for (String sfX : sfF[3].split(";")) {
                            String[] sfP = sfX.split("=", 2), sfB = sfP[1].split("\\.\\.", -1);
                            sfR.put(sfP[0], new double[]{sfB[0].isEmpty() ? Double.NaN : Double.parseDouble(sfB[0]), sfB[1].isEmpty() ? Double.NaN : Double.parseDouble(sfB[1])});
                        }
                        sfGot = String.valueOf(IvaModelPatch.specState(sfT, sfS, sfR)); sfExp = sfF[4];
                    } else if ("chip".equals(sfF[0])) {
                        String[] sfB = sfF[3].split("\\.\\.", -1);
                        sfGot = IvaModelPatch.rangeChip(mdFl, sfF[1], sfF[2], new double[]{sfB[0].isEmpty() ? Double.NaN : Double.parseDouble(sfB[0]),
                            sfB[1].isEmpty() ? Double.NaN : Double.parseDouble(sfB[1])}).replace('\u00a0', ' ');
                        sfExp = sfF[4];
                    } else if ("hint".equals(sfF[0])) {
                        sfGot = IvaModelPatch.rangeHint(sfF[1].isEmpty() ? Double.NaN : Double.parseDouble(sfF[1]), sfF[2].isEmpty() ? Double.NaN : Double.parseDouble(sfF[2]),
                            Integer.parseInt(sfF[3]), sfF[4]).replace('\u00a0', ' ');
                        sfExp = sfF[5];
                    } else if ("note".equals(sfF[0])) {
                        sfGot = IvaModelPatch.missingNote(Integer.parseInt(sfF[1])); sfExp = sfF[2];
                    } else { sfGot = "?"; sfExp = "известный вид строки: " + sfF[0]; }
                    sfTot++;
                    sfCnt.put(sfF[0], (sfCnt.containsKey(sfF[0]) ? sfCnt.get(sfF[0]) : 0) + 1);
                    if (sfGot.equals(sfExp)) sfOk++; else sfBad.append(" [").append(sfL.replace('\t', '|')).append(" → ").append(sfGot).append("]");
                }
                eq("фильтр 10.85: общие случаи tests/spec-filter-cases.txt", sfOk + "/" + sfTot + sfBad, sfTot + "/" + sfTot);
                eq("фильтр 10.85: в файле есть все виды случаев", sfCnt.keySet().toString(), "[chip, hint, note, num, state]");
                eq("фильтр 10.85: числовые поля перфоратора — из справочника",
                    IvaModelPatch.numFields(mdFl, "rotary-sds-plus").size() + ":" + IvaModelPatch.numFields(mdFl, "rotary-sds-plus").get(0).key
                        + "," + IvaModelPatch.choiceFields(mdFl, "rotary-sds-plus").size(), "4:volt,1");
                eq("фильтр 10.85: число для поля ввода — без пробелов",
                    IvaModelPatch.numInput(1500) + "|" + IvaModelPatch.numInput(2.4) + "|" + IvaModelPatch.numText(1234567) .replace('\u00a0', ' '), "1500|2,4|1 234 567");
                // Filter: диапазоны считаются в «Фильтр · N», копия независима, другой вид снимает, «Сбросить» очищает
                IvaSearchPatch.Filter sfA = new IvaSearchPatch.Filter();
                sfA.cat = "power"; sfA.sub = "hammers"; sfA.kind = "rotary-sds-plus"; sfA.specKind = "rotary-sds-plus";
                sfA.specs.put("src", "Аккумулятор");
                sfA.setRange("power", 600, Double.NaN);
                sfA.setRange("weight", Double.NaN, Double.NaN);
                IvaSearchPatch.Filter sfB2 = sfA.copy();
                sfB2.ranges.get("power")[0] = 9999;
                sfB2.setRange("energy", 2, 3);
                java.util.LinkedHashMap<String, String> sfT1 = new java.util.LinkedHashMap<String, String>();
                sfT1.put("src", "Аккумулятор"); sfT1.put("power", "780");
                java.util.LinkedHashMap<String, String> sfT2 = new java.util.LinkedHashMap<String, String>();
                sfT2.put("src", "Аккумулятор");
                java.util.LinkedHashMap<String, String> sfT3 = new java.util.LinkedHashMap<String, String>();
                sfT3.put("src", "Аккумулятор"); sfT3.put("power", "500");
                String sfS1 = sfA.specCount() + "," + sfA.ranges.size() + "," + sfA.ranges.get("power")[0] + "|" + sfB2.specCount()
                    + "|" + sfA.specState(sfT1) + sfA.specState(sfT2) + sfA.specState(sfT3);
                sfA.kind = "rotary-sds-max";
                String sfS2 = sfA.specCount() + "," + sfA.specState(sfT3) + "," + sfA.extraActive();
                sfB2.setRange("energy", Double.NaN, Double.NaN);
                String sfS3 = sfB2.ranges.keySet().toString();
                IvaSearchPatch.Filter sfC = new IvaSearchPatch.Filter();
                sfC.setSpecsFrom(sfB2);
                sfB2.resetAll();
                eq("фильтр 10.85: «от–до» в счётчике, копия независима, без характеристики — 0 (в конце), другой вид снимает, «Сбросить» очищает",
                    sfS1 + "|" + sfS2 + "|" + sfS3 + "|" + sfC.ranges.size() + "," + sfB2.ranges.size() + "," + sfB2.specCount(),
                    "2,1,600.0|3|10-1|0,1,true|[power]|1,0,0");
                // 10.86: форма «Добавить» — какие характеристики показывать (общие случаи tests/form-fields-cases.txt, их же
                // проверяет tests/model-search.cjs) и что из них сохранится; поиск — раскладка, латиница, опечатки
                int ffOk = 0, ffTot = 0;
                StringBuilder ffBad = new StringBuilder();
                for (String ffL : new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("tests/form-fields-cases.txt")), "UTF-8").split("\n")) {
                    if (ffL.trim().isEmpty() || ffL.startsWith("#")) continue;
                    String[] ffC = ffL.split("\\|", -1);
                    java.util.LinkedHashMap<String, String>[] ffKv = new java.util.LinkedHashMap[2];
                    for (int ffI = 0; ffI < 2; ffI++) {
                        ffKv[ffI] = new java.util.LinkedHashMap<String, String>();
                        for (String ffX : ffC[1 + ffI].split(";")) {
                            if (ffX.trim().isEmpty()) continue;
                            int ffE = ffX.indexOf('=');
                            ffKv[ffI].put(ffX.substring(0, ffE).trim(), ffX.substring(ffE + 1).trim());
                        }
                    }
                    java.util.List<IvaModelPatch.Field> ffV = IvaModelPatch.formFields(IvaModelPatch.fieldsFor(mdFl, ffC[0]), ffKv[0], ffKv[1]);
                    StringBuilder ffG = new StringBuilder();
                    for (IvaModelPatch.Field ffF : ffV) {
                        if (ffG.length() > 0) ffG.append(',');
                        ffG.append(ffF.key);
                        if ("choice".equals(ffF.type)) {
                            ffG.append('(');
                            for (int ffI = 0; ffI < ffF.options.length; ffI++) ffG.append(ffI == 0 ? "" : "; ").append(ffF.options[ffI]);
                            ffG.append(')');
                        }
                    }
                    String ffS = IvaModelPatch.specsClean(ffKv[1], ffV);
                    ffTot++;
                    if (ffG.toString().equals(ffC[3]) && String.valueOf(ffS).equals(ffC[4])) ffOk++;
                    else ffBad.append(" [").append(ffL).append(" → ").append(ffG).append(" | ").append(ffS).append("]");
                }
                eq("форма 10.86: общие случаи tests/form-fields-cases.txt (питание — только у модели, напряжение — при аккумуляторе)",
                    ffOk + "/" + ffTot + ffBad, ffTot + "/" + ffTot);
                eq("форма 10.86: случаев не меньше 12", ffTot >= 12, true);
                java.util.List<IvaModelPatch.Field> ffRot = IvaModelPatch.fieldsFor(mdFl, "rotary-sds-plus");
                java.util.Map<String, String> ffMs = new java.util.HashMap<String, String>();
                ffMs.put("src", "Сеть 220 В");
                eq("форма 10.86: поле вида не меняется (вариант модели — в копии); нет полей — пусто",
                    ffRot.get(0).options.length + "," + IvaModelPatch.formFields(ffRot, ffMs, null).get(0).options.length + ","
                        + IvaModelPatch.formFields(null, ffMs, null).size(), "4,1,0");
                eq("поиск 10.86: раскладка, опечатки, латиница",
                    IvaModelPatch.swapLayout(",jkufhrf") + "|" + IvaModelPatch.swapLayout("ЬФЛШЕФ") + "|" + IvaModelPatch.typoDist("балгарка", "болгарка")
                        + IvaModelPatch.typoDist("пефроратор", "перфоратор") + IvaModelPatch.typoDist("abc", "") + "|"
                        + IvaModelPatch.fuzzyQuery("балгарка makita", mdIx) + "|" + IvaModelPatch.translit("bolgarka moyka shchetka bosch", mdIx) + "|"
                        + IvaModelPatch.fuzzyQuery("болгарка", mdIx) + "|" + IvaModelPatch.translit("болгарка gws", mdIx),
                    "болгарка|makita|113|болгарка makita|болгарка мойка щетка bosch|null|null");
                StringBuilder ffLb = new StringBuilder();
                for (String[] ffX : IvaKindPatch.LABELS) ffLb.append(ffX[1]).append(';');
                eq("форма 10.87: подписи пунктов — обязательные со звёздочкой, у «Описания» подписи нет (это заголовок раздела)", ffLb.toString(),
                    "Фотографии · до 5, первое — на обложке;Название инструмента *;Категория *;Подкатегория;Вид инструмента;Цена за сутки, ₽ *;Залог, ₽;Условия аренды;");
                eq("поиск 10.86: словарь опечаток — слова без цифр от 4 букв, по порядку",
                    mdIx.vocab.size() > 500 && mdIx.vocab.contains("болгарка") && !mdIx.vocab.contains("hr2470")
                        && mdIx.vocab.get(0).compareTo(mdIx.vocab.get(1)) < 0, true);
            } catch (Throwable mdE) { eq("модели 10.82: настоящий справочник", "исключение " + mdE, ""); }
        }
        // ───── 10.87: форма «Добавить инструмент» (IvaFormPatch) ─────
        eq("форма 10.87: наши пункты встают на свои же места по порядку, прочие дети — где были; уже на месте — null",
            java.util.Arrays.toString(IvaFormPatch.permute(6, new int[]{4, 2, 3})) + "|" + IvaFormPatch.permute(5, new int[]{1, 2, 4})
                + "|" + java.util.Arrays.toString(IvaFormPatch.permute(5, new int[]{3, 0})) + "|" + IvaFormPatch.permute(5, new int[]{1, 1})
                + "|" + IvaFormPatch.permute(5, new int[]{7}) + "|" + IvaFormPatch.permute(5, new int[]{}) + "|" + IvaFormPatch.permute(0, new int[]{0}),
            "[0, 1, 4, 2, 3, 5]|null|[3, 1, 2, 0, 4]|null|null|null|null");
        eq("форма 10.87: цена за сутки — число больше нуля",
            IvaFormPatch.priceOk("800") + "," + IvaFormPatch.priceOk(" 1 500 ") + "," + IvaFormPatch.priceOk("99,5") + ","
                + IvaFormPatch.priceOk("") + "," + IvaFormPatch.priceOk("0") + "," + IvaFormPatch.priceOk("-5") + "," + IvaFormPatch.priceOk("abc")
                + "," + IvaFormPatch.priceOk(null) + "," + IvaFormPatch.priceOk("12345678"),
            "true,true,true,false,false,false,false,false,false");
        java.util.LinkedHashMap<String, String> fpSt = new java.util.LinkedHashMap<String, String>();
        fpSt.put("name", "Перфоратор Makita HR2470");
        fpSt.put("descr", "Строка 1\nстрока 2 = ок \\ слэш");
        fpSt.put("spec.power", "780");
        fpSt.put("kit.0", "Кейс");
        fpSt.put("bad=key", "x");
        fpSt.put("pics", "3");
        String fpEnc = IvaFormPatch.enc(fpSt);
        java.util.LinkedHashMap<String, String> fpDec = IvaFormPatch.dec(fpEnc);
        eq("форма 10.87: черновик туда и обратно — переводы строк, «=» и «\\» в значении, порядок; плохой ключ пропускается",
            fpDec.toString() + "|" + fpEnc.split("\n").length + "|" + IvaFormPatch.dec(null).size() + "|" + IvaFormPatch.dec("мусор\n=x\n").size(),
            "{name=Перфоратор Makita HR2470, descr=Строка 1\nстрока 2 = ок \\ слэш, spec.power=780, kit.0=Кейс, pics=3}|5|0|0");
        eq("форма 10.87: характеристики и комплектация из черновика",
            IvaFormPatch.specsOf(fpDec) + "|" + IvaFormPatch.kitOf(fpDec), "{power=780}|[Кейс]");
        java.util.LinkedHashMap<String, String> fpEmpty = new java.util.LinkedHashMap<String, String>();
        fpEmpty.put("name", "  "); fpEmpty.put("pics", "0"); fpEmpty.put("cat", "power");
        java.util.LinkedHashMap<String, String> fpPic = new java.util.LinkedHashMap<String, String>();
        fpPic.put("pics", "1");
        eq("форма 10.87: что-то есть — название, цена, описание, условия или фото (одна категория — не черновик)",
            IvaFormPatch.hasContent(fpDec) + "," + IvaFormPatch.hasContent(fpEmpty) + "," + IvaFormPatch.hasContent(fpPic) + "," + IvaFormPatch.hasContent(null),
            "true,false,true,false");
        eq("форма 10.87: строка черновика",
            IvaFormPatch.draftTitle(fpDec) + "|" + IvaFormPatch.draftTitle(fpPic),
            "Есть незаконченный инструмент: «Перфоратор Makita HR2470» · 3 фото|Есть незаконченный инструмент: без названия · 1 фото");
        // 10.88: частые условия аренды — плашки добавляют / убирают строку, остальное в поле не трогается
        eq("форма 10.88: условие — отдельной строкой в конец; ещё раз — убирается, остальные строки как были",
            IvaFormPatch.termToggle("", "Паспорт при получении") + "|" + IvaFormPatch.termToggle("Свой текст\n", "Вернуть чистым")
                + "|" + IvaFormPatch.termToggle("Свой текст\nвернуть чистым.\nЕщё", "Вернуть чистым")
                + "|" + IvaFormPatch.termToggle("• Вернуть Чистым", "Вернуть чистым") + "|" + IvaFormPatch.termToggle("Текст", "  "),
            "Паспорт при получении|Свой текст\nВернуть чистым|Свой текст\nЕщё||Текст");
        String fpAll = "";
        for (String fpP : IvaFormPatch.TERMS_BASE) fpAll = IvaFormPatch.termToggle(fpAll, fpP);
        boolean fpAllOn = true;
        for (String fpP : IvaFormPatch.TERMS_BASE) fpAllOn &= IvaFormPatch.termHas(fpAll, fpP);
        String fpNone = fpAll;
        for (String fpP : IvaFormPatch.TERMS_BASE) fpNone = IvaFormPatch.termToggle(fpNone, fpP);
        eq("форма 10.88: можно выбрать все условия сразу и снять все обратно",
            fpAllOn + "," + fpAll.split("\n").length + "," + fpNone.isEmpty() + "," + IvaFormPatch.termHas("Паспорт при получении — обязательно", "Паспорт при получении"),
            "true," + IvaFormPatch.TERMS_BASE.length + ",true,false");
        // 10.89: частые условия — выпадающий список: только то, чего ещё нет в поле; без «Наличные или перевод» и своих строк
        java.util.List<String> fpOpt = IvaFormPatch.termOptions("Бензин"), fpOptB = IvaFormPatch.termOptions("Аккумулятор"), fpOptN = IvaFormPatch.termOptions("");
        eq("форма 10.89: список — по питанию (бензин — топливо, аккумулятор — заряд), затем общие; «Наличные или перевод» убрано",
            fpOpt.subList(0, 2) + "|" + fpOpt.size() + "|" + fpOptB.get(0) + "|" + fpOptN.size() + "|" + fpOptN.contains("Вернуть с полным баком")
                + "|" + fpOptN.contains("Наличные или перевод") + "|" + fpOptN.contains("Паспорт РФ и договор"),
            "[Топливо — за счёт арендатора, Вернуть с полным баком]|" + (IvaFormPatch.TERMS_BASE.length + 2)
                + "|Аккумуляторы выдаём заряженными|" + IvaFormPatch.TERMS_BASE.length + "|false|false|false");
        java.util.List<String> fpLeft = IvaFormPatch.termLeft("", "Свой текст\n• оплата при получении.\nВернуть чистым");
        eq("форма 10.89: выбранное пропадает из списка (и с маркером, и другим регистром), убрали из поля — вернулось",
            fpLeft.contains("Оплата при получении") + "," + fpLeft.contains("Вернуть чистым") + "," + fpLeft.size() + ","
                + IvaFormPatch.termLeft("", "").size() + "," + IvaFormPatch.termLeft("", IvaFormPatch.termToggle("Вернуть чистым", "Вернуть чистым")).contains("Вернуть чистым"),
            "false,false," + (IvaFormPatch.TERMS_BASE.length - 2) + "," + IvaFormPatch.TERMS_BASE.length + ",true");
        boolean fpPass = false;
        for (String fpSrc : new String[]{"", "Бензин", "Аккумулятор", "Сеть 220 В"})
            for (String fpO : IvaFormPatch.termOptions(fpSrc)) fpPass |= fpO.toLowerCase(java.util.Locale.ROOT).contains("паспорт");
        eq("форма 10.90: в частых условиях нет ни одной подсказки про паспорт",
            fpPass + "," + IvaFormPatch.termOptions("").contains("Паспорт при получении") + "," + IvaFormPatch.termOptions("").contains("Оформляем договор аренды"),
            "false,false,true");
        eq("форма 10.91: стрелка списка — закрыт вправо (0°), открыт вниз (90°)",
            IvaFormPatch.arrowAngle(false) + "," + IvaFormPatch.arrowAngle(true), "0.0,90.0");
        eq("форма 10.91: повторное нажатие закрывает только список, открытый в момент касания, и только при отпускании",
            IvaFormPatch.tapCloses(true, true) + "," + IvaFormPatch.tapCloses(true, false) + "," + IvaFormPatch.tapCloses(false, true)
                + "," + IvaFormPatch.tapCloses(false, false), "true,false,false,false");
        eq("форма 10.91: «Далее» (фокус из поля формы в другое поле) закрывает списки; снятие фокуса, чужой экран, то же поле — нет",
            IvaFormPatch.closeOnFocus(true, true, true, false) + "," + IvaFormPatch.closeOnFocus(true, true, false, false)
                + "," + IvaFormPatch.closeOnFocus(false, true, true, false) + "," + IvaFormPatch.closeOnFocus(true, false, true, false)
                + "," + IvaFormPatch.closeOnFocus(true, true, true, true), "true,false,false,false,false");
        eq("форма 10.91: кнопки «Сгенерировать описание» нет", String.valueOf(IvaModelPatch.SHOW_GEN), "false");
        eq("форма 10.89: скрываем штатные «Добавить инструмент» и «Публикуется сразу…», остальное — нет",
            IvaFormPatch.nativeTop("Добавить инструмент") + "," + IvaFormPatch.nativeTop("Публикуется сразу — от вашего имени. Клиенты увидят его в поиске.")
                + "," + IvaFormPatch.nativeTop("Что сдаёте") + "," + IvaFormPatch.nativeTop(null) + "," + IvaFormPatch.SHOW_SAMPLE,
            "true,true,false,false,false");
        // 10.89: фото — 5 окошек
        eq("фото 10.89: окошки — фото по порядку, «+» в первом свободном, остальные пустые; 5 фото — «+» нет",
            java.util.Arrays.toString(IvaPhotoPatch.slots(0)) + java.util.Arrays.toString(IvaPhotoPatch.slots(2)) + java.util.Arrays.toString(IvaPhotoPatch.slots(5))
                + java.util.Arrays.toString(IvaPhotoPatch.slots(7)) + "|" + IvaPhotoPatch.left(0) + IvaPhotoPatch.left(3) + IvaPhotoPatch.left(6),
            "[1, 2, 2, 2, 2][0, 0, 1, 2, 2][0, 0, 0, 0, 0][0, 0, 0, 0, 0]|520");
        eq("фото 10.89: просмотр — нажали фото: открыть / ещё раз — скрыть; удалили фото — номер сдвигается или просмотр закрыт",
            IvaPhotoPatch.toggle(-1, 2) + "," + IvaPhotoPatch.toggle(2, 2) + "," + IvaPhotoPatch.toggle(2, 0) + "|" + IvaPhotoPatch.afterRemove(3, 1) + ","
                + IvaPhotoPatch.afterRemove(1, 1) + "," + IvaPhotoPatch.afterRemove(0, 2) + "," + IvaPhotoPatch.afterRemove(-1, 0),
            "2,-1,0|2,-1,0,-1");
        eq("фото 10.89: уменьшение при чтении (не меньше 1600 px) и поворот по EXIF",
            IvaPhotoPatch.sampleSize(4000, 3000, 1600) + "," + IvaPhotoPatch.sampleSize(1600, 1200, 1600) + "," + IvaPhotoPatch.sampleSize(8000, 6000, 1600)
                + "," + IvaPhotoPatch.sampleSize(100, 100, 0) + "|" + IvaPhotoPatch.rotationOf(6) + "," + IvaPhotoPatch.rotationOf(3) + ","
                + IvaPhotoPatch.rotationOf(8) + "," + IvaPhotoPatch.rotationOf(1) + "," + IvaPhotoPatch.rotationOf(0),
            "2,1,4,1|90,180,270,0,0");
        // 10.89: бренд над «Моделью»
        java.util.List<String> fpBr = java.util.Arrays.asList("Makita", "Bosch", "DeWalt", "Metabo HPT");
        eq("модель 10.89: бренд — в пустое поле; другой бренд — меняется (модель прежнего убирается); свой текст — не трогаем",
            "[" + IvaModelPatch.brandPut("", "", "Makita", fpBr) + "][" + IvaModelPatch.brandPut("Makita HR2470", "Makita", "Bosch", fpBr) + "]["
                + IvaModelPatch.brandPut("makita", "", "Bosch", fpBr) + "][" + IvaModelPatch.brandPut("HR2470", "", "Makita", fpBr) + "]["
                + IvaModelPatch.brandPut("Makita HR2470", "", "Makita", fpBr) + "][" + IvaModelPatch.brandPut("Metabo HPT DH26", "", "Metabo", java.util.Arrays.asList("Metabo", "Metabo HPT")) + "]",
            "[Makita ][Bosch ][Bosch ][HR2470][Makita HR2470][Metabo ]");
        eq("модель 10.89: сняли бренд — из поля уходит, только если кроме него ничего нет",
            "[" + IvaModelPatch.brandPut("Makita ", "Makita", "", fpBr) + "][" + IvaModelPatch.brandPut("Makita HR2470", "Makita", "", fpBr) + "]["
                + IvaModelPatch.brandPut("", "Makita", "", fpBr) + "]",
            "[][Makita HR2470][]");
        // 10.89: модель — в названии
        eq("модель 10.89: модель дописывается в конец названия и меняется вместе с полем; без повторов",
            "[" + IvaModelPatch.nameWithModel("Перфоратор", "", "Makita") + "][" + IvaModelPatch.nameWithModel("Перфоратор Makita", "Makita", "Makita HR2470") + "]["
                + IvaModelPatch.nameWithModel("Перфоратор Makita HR2470", "Makita HR2470", "") + "][" + IvaModelPatch.nameWithModel("Перфоратор makita", "", "Makita HR2470") + "]["
                + IvaModelPatch.nameWithModel("Перфоратор Makita HR2470 б/у", "Makita HR2470", "Bosch") + "][" + IvaModelPatch.nameWithModel("", "", "Bosch GBH 2-26") + "]["
                + IvaModelPatch.nameWithModel("Перфоратор Makita HR2470", "", "makita hr2470") + "]",
            "[Перфоратор Makita][Перфоратор Makita HR2470][Перфоратор][Перфоратор makita HR2470][Перфоратор Makita HR2470 б/у Bosch][Bosch GBH 2-26][Перфоратор Makita HR2470]");
        // 10.89: карточка
        eq("карточка 10.89: «Характеристики» начинаются с вида, модели и состояния (пустое — строки нет)",
            rowsText(IvaModelPatch.detHead("Перфоратор", "Makita HR2470", "Паспорт\nСостояние: Новый")) + "|" + rowsText(IvaModelPatch.detHead("", "", "Без состояния"))
                + "|" + rowsText(IvaModelPatch.detHead(null, " ", "Состояние: Рабочее")),
            "Вид=Перфоратор;Модель=Makita HR2470;Состояние=Новый;||Состояние=Рабочее;");
        eq("карточка 10.89: условия — без «Состояния»; одна строка — в строку, несколько — списком; пусто — строки нет",
            "[" + IvaModelPatch.cardTerms("Паспорт при получении\nСостояние: Хорошее") + "][" + IvaModelPatch.cardTerms("• Паспорт\n\nВернуть чистым\nСостояние: Новый") + "]["
                + IvaModelPatch.cardTerms("Состояние: Новый") + "][" + IvaModelPatch.cardTerms(null) + "]",
            "[Условия аренды: Паспорт при получении][Условия аренды:\n• Паспорт\n• Вернуть чистым][][]");
        // 10.89: фильтр — 3 + «Все ▾»
        java.util.List<String> fsVis = java.util.Arrays.asList("a", "b", "c", "d", "e");
        java.util.Map<String, Integer> fsCnt = new java.util.HashMap<String, Integer>();
        fsCnt.put("a", 1); fsCnt.put("b", 5); fsCnt.put("c", 2); fsCnt.put("d", 5); fsCnt.put("e", 9);
        eq("фильтр 10.89: сразу 3 самых наполненных; «Все» — остальные в штатном порядке; выбрали — только выбранная",
            IvaSearchPatch.facetShown(fsVis, fsCnt, "", false, 3) + "|" + IvaSearchPatch.facetShown(fsVis, fsCnt, "", true, 3) + "|"
                + IvaSearchPatch.facetShown(fsVis, fsCnt, "c", false, 3) + "|" + IvaSearchPatch.facetShown(fsVis, fsCnt, "x", false, 3) + "|"
                + IvaSearchPatch.facetShown(java.util.Arrays.asList("a", "b"), fsCnt, "", false, 3),
            "[e, b, d]|[e, b, d, a, c]|[c]|[e, b, d]|[b, a]");
        eq("фильтр 10.89: кнопка «Все ▾» — только без выбора и когда вариантов больше трёх",
            IvaSearchPatch.facetMore(fsVis, "", 3) + "," + IvaSearchPatch.facetMore(fsVis, "c", 3) + "," + IvaSearchPatch.facetMore(java.util.Arrays.asList("a", "b", "c"), "", 3)
                + "," + IvaSearchPatch.facetMore(fsVis, "x", 3) + "," + IvaSearchPatch.FACET_MORE + "," + IvaSearchPatch.FACET_TOP,
            "true,false,false,true,Все \u25be,3");
        eq("форма 10.87: цена из базы — без «.0»; пусто, 0 и мусор — пусто",
            IvaFormPatch.money("800.0") + "," + IvaFormPatch.money("1250.5") + "," + IvaFormPatch.money("0") + "," + IvaFormPatch.money("null")
                + "," + IvaFormPatch.money("") + "," + IvaFormPatch.money("x"),
            "800,1250.5,,,,");
        java.util.List<java.util.Map<String, String>> fpRows = new java.util.ArrayList<java.util.Map<String, String>>();
        for (String fpN : new String[]{"Перфоратор Makita", "перфоратор  makita", "", "Болгарка Bosch", "Миксер", "Фен"}) {
            java.util.Map<String, String> fpR = new java.util.HashMap<String, String>(); fpR.put("name", fpN); fpRows.add(fpR);
        }
        StringBuilder fpS = new StringBuilder();
        for (java.util.Map<String, String> fpR : IvaFormPatch.samples(fpRows, 3)) fpS.append(fpR.get("name")).append(';');
        eq("форма 10.87: образцы — новые сверху, без повторов названия и пустых, не больше трёх", fpS.toString(),
            "Перфоратор Makita;Болгарка Bosch;Миксер;");
        eq("форма 10.87: длинное название образца — с многоточием", IvaFormPatch.cut("Перфоратор аккумуляторный Makita DHR242Z", 20) + "|" + IvaFormPatch.cut(" Фен ", 20),
            "Перфоратор аккумуля…|Фен");
        StringBuilder fpO = new StringBuilder();
        for (String fpK : IvaFormPatch.ORDER) if (fpK.startsWith(IvaFormPatch.SEC) || fpK.startsWith("at") && !fpK.startsWith("atC") && !fpK.startsWith("atS")) fpO.append(fpK).append(' ');
        eq("форма 10.88: порядок — фото, название · цена, залог · условия, доставка · описание · кнопка",
            fpO.toString().trim(), "iva_sec:1 atPhotos atName iva_sec:2 iva_sec:3 atPrice atDep iva_sec:4 atTerms atDelToggle atDelPrice iva_sec:5 atDescr");
        java.util.List<String> fpOrd = java.util.Arrays.asList(IvaFormPatch.ORDER);
        eq("форма 10.88: «Модель» под названием, категория — до характеристик, подсказка цены — над ценой, «Сгенерировать» — под описанием, частые условия — под условиями, «Опубликовать» — последней",
            (fpOrd.indexOf(IvaModelPatch.BLOCK) == fpOrd.indexOf(IvaModelPatch.NAME_SUG) + 1) + "," + (fpOrd.indexOf("atCat") < fpOrd.indexOf(IvaModelPatch.SPECS))
                + "," + (fpOrd.indexOf(IvaModelPatch.HELP + ":price") + 2 == fpOrd.indexOf("atPrice")) + "," + (fpOrd.indexOf(IvaModelPatch.HELP) == fpOrd.indexOf("atDescr") + 1)
                + "," + (fpOrd.indexOf(IvaFormPatch.TERMS) == fpOrd.indexOf("atTerms") + 1) + "," + fpOrd.get(fpOrd.size() - 1)
                + "," + (fpOrd.size() == new java.util.HashSet<String>(fpOrd).size()) + "," + (fpOrd.indexOf(IvaPhotoPatch.TAG) == fpOrd.indexOf("atPhotos") + 1),
            "true,true,true,true,true,atSave,true,true");
        {   // 10.92: «Цензура» — общие случаи (их же проверяет база: tools/censor-stand.py → public.iva_censor)
            int cz92Tot = 0, cz92Ok = 0;
            StringBuilder cz92Bad = new StringBuilder();
            java.util.Set<String> cz92Lv = new java.util.HashSet<String>();
            try {
                for (String cz92L : new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("tests/censor-cases.txt")), "UTF-8").split("\n")) {
                    if (cz92L.trim().isEmpty() || cz92L.startsWith("#")) continue;
                    int cz92T = cz92L.indexOf('\t');
                    String cz92Want = cz92L.substring(0, cz92T), cz92Txt = IvaCensorPatch.unescape(cz92L.substring(cz92T + 1));
                    String cz92Got = IvaCensorPatch.levelName(IvaCensorPatch.check(cz92Txt, null, null).level);
                    cz92Tot++;
                    cz92Lv.add(cz92Want);
                    if (cz92Got.equals(cz92Want)) cz92Ok++; else cz92Bad.append(cz92L).append(" → ").append(cz92Got).append("; ");
                }
            } catch (Exception cz92E) { cz92Bad.append(cz92E); }
            eq("цензура 10.92: общие случаи tests/censor-cases.txt (" + cz92Tot + ") — мат, сомнительно, обычные тексты", cz92Ok == cz92Tot && cz92Tot >= 180 && cz92Lv.size() == 3 ? "все" : cz92Bad.toString(), "все");
            eq("цензура 10.92: почему сработало — латиница, транслит, по буквам, разбито, звёздочки, английский, грубое",
                IvaCensorPatch.check("xyй", null, null).hits.get(0).rule + "," + IvaCensorPatch.check("pizdec", null, null).hits.get(0).rule
                    + "," + IvaCensorPatch.check("х у й", null, null).hits.get(0).rule + "," + IvaCensorPatch.check("пи зда", null, null).hits.get(0).rule
                    + "," + IvaCensorPatch.check("х*й", null, null).hits.get(0).rule + "," + IvaCensorPatch.check("fuck", null, null).hits.get(0).rule
                    + "," + IvaCensorPatch.check("жопа", null, null).hits.get(0).rule + "," + IvaCensorPatch.check("хуй", null, null).hits.get(0).rule,
                "visual,translit,letters,split,masked,en,rude,word");
            eq("цензура 10.92: как прочитали — «xyйня» → «хуйня», «nahuy» → «нахуй», слово по буквам — как в тексте",
                IvaCensorPatch.check("Сдаю, xyйня", null, null).hits.get(0).as + "|" + IvaCensorPatch.check("nahuy", null, null).hits.get(0).as
                    + "|" + IvaCensorPatch.check("х у й", null, null).hits.get(0).word, "хуйня|нахуй|х у й");
            java.util.Set<String> cz92Al = new java.util.HashSet<String>(java.util.Arrays.asList("хрен", "her"));
            java.util.List<String> cz92Bn = java.util.Arrays.asList("гавно", "scam");
            eq("цензура 10.92: «Это не мат» — не трогаем; «Запрещать всегда» — мат и внутри слова, и латиницей",
                IvaCensorPatch.check("хрен", cz92Al, cz92Bn).level + "," + IvaCensorPatch.check("гавно", cz92Al, cz92Bn).level
                    + "," + IvaCensorPatch.check("гавноед", cz92Al, cz92Bn).level + "," + IvaCensorPatch.check("SCAM", cz92Al, cz92Bn).level
                    + "," + IvaCensorPatch.check("хрен", null, null).level + "," + IvaCensorPatch.check("гавно", cz92Al, cz92Bn).hits.get(0).rule,
                "0,2,2,2,1,ban");
            eq("цензура 10.92: таблицы замен — одной длины (как translate в базе)",
                (IvaCensorPatch.V_FROM.length() == IvaCensorPatch.V_TO.length()) + "," + (IvaCensorPatch.P_FROM.length() == IvaCensorPatch.P_TO.length())
                    + "," + (IvaCensorPatch.C_UP.length() == IvaCensorPatch.C_LOW.length()) + "," + (IvaCensorPatch.P_PAIRS.length % 2), "true,true,true,0");
            eq("цензура 10.92: варианты слова — похожие буквы и транслит, без повторов",
                String.join("/", IvaCensorPatch.vars("xyu")) + "|" + String.join("/", IvaCensorPatch.vars("shlyuhaaa")), "хуи/хю|sнlуина/шлюха");
            eq("цензура 10.92: пустое и null — чисто", IvaCensorPatch.check("", null, null).level + "," + IvaCensorPatch.check(null, null, null).level
                + "," + IvaCensorPatch.check("   ", null, null).level, "0,0,0");
            eq("цензура 10.92: пункт «Цензура» — только у создателя, вошедшего в аккаунт",
                IvaCensorPatch.rowFor(true, "eclips.ru@mail.ru") + "," + IvaCensorPatch.rowFor(true, " ECLIPS.RU@mail.ru ") + "," + IvaCensorPatch.rowFor(false, "eclips.ru@mail.ru")
                    + "," + IvaCensorPatch.rowFor(true, "admin@x.ru") + "," + IvaCensorPatch.rowFor(true, null), "true,true,false,false,false");
            eq("цензура 10.92: подпись пункта — сколько ждёт решения",
                IvaCensorPatch.rowSub(-1, false) + "|" + IvaCensorPatch.rowSub(0, false) + "|" + IvaCensorPatch.rowSub(3, false) + "|" + IvaCensorPatch.rowSub(100, false)
                    + "|" + IvaCensorPatch.rowSub(5, true),
                "Сомнительные объявления и отзывы — на проверку|На проверке ничего нет|На проверке: 3|На проверке: 100+|Нужно обновить базу (20261021_censor.sql)");
            eq("цензура 10.92: нет таблицы / функции (SQL не выполнен) — понятная подсказка",
                IvaCensorPatch.missingTable("404", "") + "," + IvaCensorPatch.missingTable("400", "{\"code\":\"PGRST205\"}") + "," + IvaCensorPatch.missingTable("400", "42P01")
                    + "," + IvaCensorPatch.missingTable("401", "JWT expired") + "," + IvaCensorPatch.missingTable("200", null), "true,true,true,false,false");
            eq("цензура 10.92: где и что — подписи карточки",
                IvaCensorPatch.sourceLabel("tool", "descr") + "|" + IvaCensorPatch.sourceLabel("review", "author_name") + "|" + IvaCensorPatch.statusLabel("kept")
                    + "|" + IvaCensorPatch.statusLabel("hidden") + "|" + IvaCensorPatch.statusLabel("fixed") + "|" + IvaCensorPatch.statusLabel("new"),
                "Объявление · описание|Отзыв · имя автора|Оставлено|Скрыто|Автор исправил текст|Ждёт решения");
            eq("цензура 10.92: подсветка слова в тексте (без регистра, ё = е)",
                IvaCensorPatch.findWord("Сдаю ЖОПА дрель", "жопа") + "," + IvaCensorPatch.findWord("ёлка", "елка") + "," + IvaCensorPatch.findWord("текст", "нет")
                    + "," + IvaCensorPatch.findWord(null, "x"), "5,0,-1,-1");
            eq("цензура 10.92: запросы решений — id, оставить/скрыть, слово (кавычки экранируются)",
                IvaCensorPatch.decideBody(7, true) + "|" + IvaCensorPatch.decideBody(8, false) + "|" + IvaCensorPatch.wordBody("ху\"й", "ban"),
                "{\"p_id\":7,\"p_keep\":true}|{\"p_id\":8,\"p_keep\":false}|{\"p_word\":\"ху\\\"й\",\"p_kind\":\"ban\"}");
            eq("цензура 10.92: ошибка под полем формы — только мат; сомнительное публикуется на проверку",
                IvaCensorPatch.fieldError("xyйня") + "|" + IvaCensorPatch.fieldError("жопа") + "|" + IvaCensorPatch.fieldError("Перфоратор")
                    + "|" + IvaFormPatch.heldWord("Перфоратор\nхозяин урод\n") + "|" + IvaFormPatch.heldWord("Перфоратор Makita"),
                "Нецензурное слово «xyйня» — уберите его, пожалуйста|||урод|");
            eq("цензура 10.92: в форме проверяются название, условия и описание; ошибки — под полями",
                (java.util.Arrays.asList(IvaFormPatch.ORDER).indexOf(IvaFormPatch.ERR + "atTerms") == java.util.Arrays.asList(IvaFormPatch.ORDER).indexOf(IvaFormPatch.TERMS) + 1)
                    + "," + (java.util.Arrays.asList(IvaFormPatch.ORDER).indexOf(IvaFormPatch.ERR + "atDescr") == java.util.Arrays.asList(IvaFormPatch.ORDER).indexOf(IvaModelPatch.HELP) + 1),
                "true,true");
            eq("цензура 10.92: отзыв — та же проверка (латиница и транслит теперь тоже мат)",
                IvaReviewPatch.hasMat("nahuy") + "," + IvaReviewPatch.hasMat("х у й") + "," + IvaReviewPatch.hasMat("жопа") + "," + IvaReviewPatch.hasMat("хлеб"), "true,true,false,false");
            eq("цензура 10.92: подробное «почему» — как в базе", IvaCensorPatch.why("translit") + "|" + IvaCensorPatch.why("masked"),
                "нецензурное слово, написанное латиницей (транслитом)|звёздочки вместо букв — похоже на замаскированный мат");
            eq("цензура 10.92: текст из файла случаев — \\n и \\u", IvaCensorPatch.unescape("а\\nб\\u200bв"), "а\nб\u200bв");
        }
        try {   // 10.93: кабинет создателя — «Управление», «Мои отзывы», без «Ваш доход с админов»
            StringBuilder ch93 = new StringBuilder();
            for (String[] ch93m : IvaChiefPatch.MENU) ch93.append(ch93m[0]).append('|');
            eq("кабинет 10.93: пункты «Управления» — по порядку владельца", ch93.toString(),
                "Сводка|Комиссия|Фильтры|Модерация|Кандидаты|Реклама Ива|Платный контент|");
            eq("кабинет 10.93: в кабинете — «Управление» и «Мои отзывы»", IvaChiefPatch.T_MANAGE + "|" + IvaChiefPatch.T_REVIEWS, "Управление|Мои отзывы");
            eq("кабинет 10.93: карточка «Ваш доход с админов» узнаётся по заголовку (и только она)",
                IvaChiefPatch.isIncomeTitle("ВАШ ДОХОД С АДМИНОВ") + "," + IvaChiefPatch.isIncomeTitle(" Ваш доход с админов ") + ","
                    + IvaChiefPatch.isIncomeTitle("ПАНЕЛЬ ВЛАДЕЛЬЦА") + "," + IvaChiefPatch.isIncomeTitle("Заявки на оплату") + "," + IvaChiefPatch.isIncomeTitle(null),
                "true,true,false,false,false");
            eq("кабинет 10.93: рубли — с пробелами, минус", IvaChiefPatch.rub(0) + "|" + IvaChiefPatch.rub(1234567.4) + "|" + IvaChiefPatch.rub(-950),
                "0 ₽|1\u00a0234\u00a0567 ₽|−950 ₽");
            long ch93Now = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", java.util.Locale.ROOT).parse("2026-10-15T12:00:00Z").getTime();
            java.util.List<java.util.Map<String, String>> ch93T = ch93rows("active=true|t_status=free", "active=true|t_status=booked", "active=true|t_status=rented", "active=false|t_status=free", "active=", "t_status=rented");
            eq("кабинет 10.93: сводка — объявления [в каталоге, свободно, забронировано, в аренде, снято]",
                java.util.Arrays.toString(IvaChiefPatch.toolCounts(ch93T)), "[5, 2, 1, 2, 1]");
            java.util.List<java.util.Map<String, String>> ch93P = ch93rows("status=new|created_at=2026-10-15T08:00:00+00:00|owner_email=a@x.ru|fee=50|rent_sum=1000",
                "status=rented|created_at=2026-10-12T08:00:00+00:00|owner_email=A@x.ru|fee=25,5|rent_sum=500",
                "status=closed|created_at=2026-09-20T08:00:00+00:00|owner_email=b@x.ru|fee=100|rent_sum=2000",
                "status=closed|created_at=2026-01-01T08:00:00+00:00|owner_email=eclips.ru@mail.ru|fee=0|rent_sum=9000",
                "status=closed|created_at=null|owner_email=|fee=7");
            eq("кабинет 10.93: сводка — заявки [всего, новые, в аренде, закрытые, сегодня, 7 дней, 30 дней]",
                java.util.Arrays.toString(IvaChiefPatch.orderCounts(ch93P, ch93Now)), "[5, 1, 1, 3, 1, 2, 3]");
            java.util.List<java.util.Map<String, String>> ch93F = ch93rows("admin_email=a@x.ru|amount=60|status=confirmed", "admin_email=b@x.ru|amount=30|status=pending",
                "admin_email=C@x.ru|amount=10|status=confirmed", "admin_email=b@x.ru|amount=99|status=rejected");
            IvaChiefPatch.Money ch93M = IvaChiefPatch.money(ch93P, ch93F);
            eq("кабинет 10.93: комиссия — как сайт: начислено, получено, долг, ждут; свои заявки создателя не в счёт",
                ch93M.fee + "|" + ch93M.paid + "|" + ch93M.owed() + "|" + ch93M.pendingN + "/" + ch93M.pending + "|" + ch93M.rent,
                "175.5|70.0|105.5|1/30.0|3500.0");
            StringBuilder ch93D = new StringBuilder();
            for (String ch93k : IvaChiefPatch.byDebt(ch93M)) ch93D.append(ch93k).append(' ').append(ch93M.byAdmin.get(ch93k)[0] - ch93M.byAdmin.get(ch93k)[1]).append(';');
            eq("кабинет 10.93: комиссия по админам — по убыванию долга, переплата в конце (почта без регистра)", ch93D.toString(),
                "b@x.ru 100.0;a@x.ru 15.5;c@x.ru -10.0;");
            eq("кабинет 10.93: отзывы [всего, скрыто, сумма оценок, опубликовано]",
                java.util.Arrays.toString(IvaChiefPatch.reviewCounts(ch93rows("rating=5|status=published", "rating=4|status=published", "rating=1|status=hidden", "rating=9|status=published"))),
                "[4, 1, 14, 3]");
            java.util.List<java.util.Map<String, String>> ch93C = ch93rows("active=true|sub=drills|kind=perf|model_id=m1|imgs=u", "active=true|sub=|kind=|model_id=|imgs=|img=x",
                "active=true|sub=g|kind=|imgs=|img=null", "active=false|sub=g|kind=k|model_id=m|imgs=u");
            eq("кабинет 10.93: фильтры — заполненность [в каталоге, подкатегория, вид, модель, фото]",
                java.util.Arrays.toString(IvaChiefPatch.coverage(ch93C)) + " " + java.util.Arrays.toString(IvaChiefPatch.coverage(ch93rows("sub=a"))),
                "[3, 2, 1, 1, 2] [1, 1, -1, -1, 0]");
            eq("кабинет 10.93: доля — «N из M · %», нет столбца — «нет в базе»",
                IvaChiefPatch.share(1, 3) + "|" + IvaChiefPatch.share(-1, 3) + "|" + IvaChiefPatch.share(0, 0), "1 из 3 · 33%|нет в базе|0");
            eq("кабинет 10.93: кандидаты — по статусам", java.util.Arrays.toString(IvaChiefPatch.candCounts(ch93rows("status=pending", "status=pending", "status=approved", "status=changes_requested", "status=x"))), "[2, 1, 1, 0]");
            eq("кабинет 10.93: кандидаты — подписи", IvaChiefPatch.bizLabel("self_employed") + "|" + IvaChiefPatch.bizLabel("sole_trader") + "|" + IvaChiefPatch.bizLabel("company")
                + "|" + IvaChiefPatch.candStatus("pending") + "|" + IvaChiefPatch.candStatus("changes_requested") + "|" + IvaChiefPatch.candStatus("approved") + "|" + IvaChiefPatch.candStatus("rejected"),
                "Самозанятый|ИП|Компания|Ждёт решения|Нужны правки|Одобрена|Отклонена");
            eq("кабинет 10.93: кандидаты — без контактов; запросы — только нужные столбцы, без select=*",
                !IvaChiefPatch.Q_CAND.matches(".*(phone|email|website|comment).*") && !(IvaChiefPatch.Q_TOOLS + IvaChiefPatch.Q_PARTS + IvaChiefPatch.Q_REVIEWS + IvaChiefPatch.Q_ADMINS
                    + IvaChiefPatch.Q_FEES + IvaChiefPatch.Q_COVER + IvaChiefPatch.Q_MOD + IvaChiefPatch.Q_CAND + IvaChiefPatch.qAboutMine("x") + IvaChiefPatch.qByMe("x")).contains("select=*") ? "да" : "нет", "да");
            eq("кабинет 10.93: «Мои отзывы» — о моих инструментах и написанные мной",
                IvaChiefPatch.qAboutMine("me@x.ru") + "\n" + IvaChiefPatch.qByMe("me@x.ru"),
                "/rest/v1/reviews?select=id,tool_id,author_id,author_name,rating,text,status,created_at,updated_at,tools!inner(name,owner_email)&tools.owner_email=eq.me%40x.ru&order=created_at.desc&limit=200\n"
                    + "/rest/v1/reviews?select=id,tool_id,author_id,author_name,rating,text,status,created_at,updated_at,tools(name)&author_email=eq.me%40x.ru&order=created_at.desc&limit=200");
            eq("кабинет 10.93: название инструмента в отзыве", IvaChiefPatch.toolName(ch93rows("tools.name=Перфоратор").get(0)) + "|"
                + IvaChiefPatch.toolName(ch93rows("id=1").get(0)), "Перфоратор|Инструмент");
            eq("кабинет 10.93: дни назад", IvaChiefPatch.daysAgo("2026-10-15T00:00:01Z", ch93Now) + "," + IvaChiefPatch.daysAgo("2026-10-08T12:00:00", ch93Now) + ","
                + IvaChiefPatch.daysAgo("кривое", ch93Now) + "," + IvaChiefPatch.daysAgo(null, ch93Now), "0,7,-1,-1");
        } catch (Exception ch93E) { eq("кабинет 10.93: тесты", String.valueOf(ch93E), ""); }
        // 10.96: кнопки «рамкой» — подсветка нажатия и полоса загрузки тем же цветом, прозрачнее
        eq("рамкой 10.96: прозрачность цвета рамки", Integer.toHexString(IvaPatch.withAlpha(IvaPatch.OUTLINE_ACCENT, IvaPatch.OUTLINE_PRESSED_A)) + ","
            + Integer.toHexString(IvaPatch.withAlpha(IvaTestPatch.YELLOW, IvaPatch.OUTLINE_PROGRESS_A)) + "," + Integer.toHexString(IvaPatch.withAlpha(0x12345678, 0x1FF)) + ","
            + Integer.toHexString(IvaPatch.OUTLINE_FILL) + "," + IvaPatch.OUTLINE_DP, "33ff8f1f,40ffea00,ff345678,ff1a1d24,1.5");
        // 10.95: «Пункт выдачи» — проверки полей, график (как в базе), подсказки адреса (как сайт), запросы
        {
            eq("пункт 10.95: email проката", IvaPickupPatch.emailOk("prokat@mail.ru") + "," + IvaPickupPatch.emailOk(" Prokat@Mail.RU ") + ","
                + IvaPickupPatch.emailOk("prokat.mail.ru") + "," + IvaPickupPatch.emailOk("prokat@mail") + "," + IvaPickupPatch.emailOk("a b@mail.ru") + ","
                + IvaPickupPatch.emailOk("") + "," + IvaPickupPatch.emailOk(null) + "," + IvaPickupPatch.emailOk("a@b.c"), "true,true,false,false,false,false,false,false");
            eq("пункт 10.95: ошибки полей", IvaPickupPatch.nameErr("  ") + "|" + IvaPickupPatch.nameErr("Склад") + "|" + IvaPickupPatch.nameErr(new String(new char[81]).replace('\0', 'я')) + "|"
                + IvaPickupPatch.addrErr("Нск") + "|" + IvaPickupPatch.addrErr("Новосибирск, улица Ленина, д. 5") + "|" + IvaPickupPatch.emailErr("") + "|" + IvaPickupPatch.emailErr("x@"),
                "Введите название пункта||Название — не длиннее 80 символов|Введите полный адрес: город, улица, дом||Введите email проката|Похоже, в email ошибка (пример: prokat@mail.ru)");
            int[] p95o = {540, 540, 540, 540, 540, 600, -1}, p95c = {1140, 1140, 1140, 1140, 1140, 960, -1};
            eq("пункт 10.95: график — как проверка базы", IvaPickupPatch.hoursOk(p95o, p95c) + ","
                + IvaPickupPatch.hoursOk(new int[]{1200, 540, 540, 540, 540, 540, 540}, new int[]{1140, 1140, 1140, 1140, 1140, 1140, 1140}) + ","
                + IvaPickupPatch.hoursOk(new int[]{-1, 540, 540, 540, 540, 540, 540}, new int[]{1140, 1140, 1140, 1140, 1140, 1140, 1140}) + ","
                + IvaPickupPatch.hoursOk(IvaPickupPatch.fill(-1), IvaPickupPatch.fill(-1)) + ","
                + IvaPickupPatch.hoursOk(IvaPickupPatch.fill(0), IvaPickupPatch.fill(1439)) + ","
                + IvaPickupPatch.hoursOk(IvaPickupPatch.fill(540), IvaPickupPatch.fill(1440)) + ","
                + IvaPickupPatch.hoursOk(new int[6], new int[6]) + "," + IvaPickupPatch.hoursOk(null, p95c), "true,false,false,false,true,false,false,false");
            eq("пункт 10.95: график текстом", IvaPickupPatch.hoursText(p95o, p95c) + " | "
                + IvaPickupPatch.hoursText(IvaPickupPatch.fill(0), IvaPickupPatch.fill(1439)) + " | "
                + IvaPickupPatch.hoursText(new int[]{540, -1, 540, 540, 600, 600, -1}, new int[]{1140, -1, 1140, 1140, 960, 960, -1}),
                "Пн–Пт 09:00–19:00 · Сб 10:00–16:00 · Вс выходной | Пн–Вс 00:00–23:59 | Пн 09:00–19:00 · Вт выходной · Ср, Чт 09:00–19:00 · Пт, Сб 10:00–16:00 · Вс выходной");
            eq("пункт 10.95: график из базы и в базу", IvaPickupPatch.arrJson(IvaPickupPatch.arr("{540,540,540,540,540,600,-1}")) + "|"
                + IvaPickupPatch.arrJson(IvaPickupPatch.arr("[1140, 1140,1140,1140,1140,960,-1]")) + "|" + IvaPickupPatch.arr("{1,2,3}") + "|"
                + IvaPickupPatch.arr("{1440,0,0,0,0,0,0}") + "|" + IvaPickupPatch.arr("x") + "|" + IvaPickupPatch.arr(null),
                "[540,540,540,540,540,600,-1]|[1140,1140,1140,1140,1140,960,-1]|null|null|null|null");
            eq("пункт 10.95: сколько пунктов", IvaPickupPatch.countText(1) + "," + IvaPickupPatch.countText(2) + "," + IvaPickupPatch.countText(5) + ","
                + IvaPickupPatch.countText(11) + "," + IvaPickupPatch.countText(14) + "," + IvaPickupPatch.countText(20) + "," + IvaPickupPatch.countText(21),
                "1 пункт,2 пункта,5 пунктов,11 пунктов,14 пунктов,20 пунктов,21 пункт");
            eq("пункт 10.95: запросы — только свои, нужные столбцы, по порядку добавления", IvaPickupPatch.listPath(" Admin@X.ru ") + " | " + IvaPickupPatch.idPath("a-1"),
                "/rest/v1/iva_pickup_points?select=id,name,address,city,lat,lng,open_min,close_min,email,created_at&owner_email=eq.admin%40x.ru&order=created_at.asc,id.asc"
                + " | /rest/v1/iva_pickup_points?id=eq.a-1");
            IvaPickupPatch.Point p95 = new IvaPickupPatch.Point();
            p95.name = "  Склад   \"Ленина\" "; p95.address = "Новосибирск,  улица Ленина, д. 5"; p95.city = "Новосибирск"; p95.email = " Prokat@Mail.ru ";
            p95.lat = 55.03; p95.lng = 82.92; p95.open = p95o; p95.close = p95c;
            String p95b = IvaPickupPatch.body(p95);
            p95.lng = null;
            eq("пункт 10.95: запись — без владельца, координаты парой", p95b + " | " + IvaPickupPatch.body(p95).contains("\"lat\":null,\"lng\":null"),
                "{\"name\":\"Склад \\\"Ленина\\\"\",\"address\":\"Новосибирск, улица Ленина, д. 5\",\"city\":\"Новосибирск\",\"lat\":55.03,\"lng\":82.92,"
                + "\"open_min\":[540,540,540,540,540,600,-1],\"close_min\":[1140,1140,1140,1140,1140,960,-1],\"email\":\"prokat@mail.ru\"} | true");
            eq("пункт 10.95: ответы базы", IvaPickupPatch.dbError("201", "") + "|" + IvaPickupPatch.dbError("400", "{\"message\":\"Не больше 20 пунктов выдачи\"}") + "|"
                + IvaPickupPatch.dbError("404", "{\"code\":\"PGRST205\"}") + "|" + IvaPickupPatch.dbError("403", "{\"code\":\"42501\"}") + "|"
                + IvaPickupPatch.dbError("400", "{\"code\":\"23514\"}") + "|" + IvaPickupPatch.dbError("", "") + "|" + IvaPickupPatch.dbError("500", "x"),
                "|Не больше 20 пунктов выдачи|Заработает после обновления базы (SQL 10.95)|Нет права: пункты добавляет действующий админ|"
                + "База не приняла данные — проверьте поля|Не сохранилось — нет связи с сервером|Не сохранилось (ответ 500)");
            eq("пункт 10.95: запрос подсказок — как сайт (Photon, Россия, 8)", IvaPickupPatch.photonUrl("  Новосибирск  Ленина 5 "),
                "https://photon.komoot.io/api/?q=%D0%9D%D0%BE%D0%B2%D0%BE%D1%81%D0%B8%D0%B1%D0%B8%D1%80%D1%81%D0%BA+%D0%9B%D0%B5%D0%BD%D0%B8%D0%BD%D0%B0+5&lang=default&countrycode=RU&limit=8");
            java.util.Map<String, String> p95h = new java.util.HashMap<String, String>();
            p95h.put("countrycode", "RU"); p95h.put("osm_key", "building"); p95h.put("type", "house"); p95h.put("city", "Новосибирск");
            p95h.put("street", "улица Ленина"); p95h.put("housenumber", "5"); p95h.put("state", "Новосибирская область");
            java.util.Map<String, String> p95c2 = new java.util.HashMap<String, String>();
            p95c2.put("countrycode", "RU"); p95c2.put("osm_key", "place"); p95c2.put("osm_value", "town"); p95c2.put("name", "город Бердск");
            java.util.Map<String, String> p95l = new java.util.HashMap<String, String>(p95h); p95l.put("street", "Lenina street");
            java.util.Map<String, String> p95k = new java.util.HashMap<String, String>(p95h); p95k.put("countrycode", "KZ");
            java.util.Map<String, String> p95n = new java.util.HashMap<String, String>();
            p95n.put("countrycode", "RU"); p95n.put("osm_key", "amenity"); p95n.put("type", "house"); p95n.put("town", "Искитим"); p95n.put("name", "Склад Ива");
            eq("пункт 10.95: подсказка адреса — как IvaGeo сайта", String.join("|", IvaPickupPatch.addrOf(p95h)) + " / " + String.join("|", IvaPickupPatch.addrOf(p95c2))
                + " / " + IvaPickupPatch.addrOf(p95l) + " / " + IvaPickupPatch.addrOf(p95k) + " / " + String.join("|", IvaPickupPatch.addrOf(p95n)) + " / " + IvaPickupPatch.addrOf(null),
                "Новосибирск, улица Ленина, д. 5|Новосибирск|Новосибирская область / Бердск|Бердск| / null / null / Искитим, Склад Ива|Искитим| / null");
        }
        // 10.94: жёлтая (пробная) версия у eclips и eclipsik — кнопка, выбор, выпуск, уведомление
        {
            eq("пробная 10.94: кому видна", IvaTestPatch.isTester("eclips.ru@mail.ru") + "," + IvaTestPatch.isTester(" Eclipsik.RU@mail.ru ") + ","
                + IvaTestPatch.isTester("someone@mail.ru") + "," + IvaTestPatch.isTester("") + "," + IvaTestPatch.isTester(null) + ","
                + IvaTestPatch.isTester("xeclips.ru@mail.ru"), "true,true,false,false,false,false");
            eq("пробная 10.94: файл — только APK в корне сайта", IvaTestPatch.feedFile("ProkatInstrumenta-10.93.apk") + "|"
                + IvaTestPatch.feedFile("https://eclipsru.github.io/Arenda-Nvk/ProkatInstrumenta-10.94.apk") + "|"
                + IvaTestPatch.feedFile("../ProkatInstrumenta-10.93.apk") + "|" + IvaTestPatch.feedFile("https://evil.ru/ProkatInstrumenta-10.93.apk") + "|"
                + IvaTestPatch.feedFile("ProkatInstrumenta-10.93.apk.exe") + "|" + IvaTestPatch.feedFile(null),
                "ProkatInstrumenta-10.93.apk|ProkatInstrumenta-10.94.apk||||");
            eq("пробная 10.94: жёлтая кнопка — только у своих и пока пробная новее выпущенной",
                IvaTestPatch.showTest(true, 168, 167) + "," + IvaTestPatch.showTest(true, 167, 167) + "," + IvaTestPatch.showTest(true, 166, 167) + ","
                + IvaTestPatch.showTest(false, 168, 167) + "," + IvaTestPatch.showTest(true, 168, 0) + "," + IvaTestPatch.showTest(true, 0, 167),
                "true,false,false,false,false,false");
            eq("пробная 10.94: надпись", IvaTestPatch.title(168, 167, "10.94") + "|" + IvaTestPatch.title(168, 168, "10.94") + "|" + IvaTestPatch.title(168, 0, ""),
                "Обновить до версии 10.94|Версия 10.94 · выпустить для всех|Обновить до версии 168");
            eq("пробная 10.94: два варианта", String.join("|", IvaTestPatch.choices("10.94")), "Обновить версию 10.94|Релиз версии 10.94");
            String t94Long = new String(new char[600]).replace('\0', 'я');
            String t94Body = IvaTestPatch.releaseBody(168, "10.94", "ProkatInstrumenta-10.94.apk", false, "Пробная \"сборка\"\n" + t94Long);
            eq("пробная 10.94: строка релиза — как у сайта", t94Body.startsWith("{\"version_code\":168,\"version_name\":\"10.94\",\"apk_url\":\"ProkatInstrumenta-10.94.apk\",\"reinstall\":false,\"notes\":\"Пробная \\\"сборка\\\"\\u000a")
                + "," + t94Body.endsWith("я\"}") + "," + (t94Body.length() - t94Body.replace("я", "").length()), "true,true,484");   // 483 «я» после обрезки до 500 + «я» в «Пробная»
            eq("пробная 10.94: ответы базы на выпуск", IvaTestPatch.releaseError("201", "") + "|" + IvaTestPatch.releaseError("409", "{\"code\":\"23505\"}") + "|"
                + IvaTestPatch.releaseError("403", "{\"code\":\"42501\"}") + "|" + IvaTestPatch.releaseError("401", "") + "|"
                + IvaTestPatch.releaseError("404", "{\"code\":\"PGRST205\"}") + "|" + IvaTestPatch.releaseError("400", "{\"code\":\"23514\"}") + "|"
                + IvaTestPatch.releaseError("500", "x") + "|" + IvaTestPatch.releaseError(null, null),
                "null|Эта версия уже выпущена|Нет права выпускать версию (для eclipsik.ru@mail.ru нужен SQL 20261022)|"
                + "Нет права выпускать версию (для eclipsik.ru@mail.ru нужен SQL 20261022)|В базе нет таблицы релизов (SQL 20261005)|"
                + "База не приняла номер или файл версии|Не удалось выпустить версию (ответ 500)|Не удалось выпустить версию (ответ —)");
            eq("пробная 10.94: уведомлять о новой жёлтой", IvaTestPatch.testNews(168, 167, 0, 0) + "," + IvaTestPatch.testNews(168, 167, 168, 0) + ","
                + IvaTestPatch.testNews(168, 168, 0, 0) + "," + IvaTestPatch.testNews(168, 167, 0, 168) + "," + IvaTestPatch.testNews(169, 167, 168, 0) + ","
                + IvaTestPatch.testNews(0, 167, 0, 0), "true,false,false,false,true,false");
            eq("пробная 10.94: файл сайта и переключатель", IvaTestPatch.TEST_URL + "|" + IvaTestPatch.K_TEST + "|" + IvaNotifyPatch.defOn(IvaTestPatch.K_TEST) + "|" + IvaTestPatch.NOTE_TITLE,
                "https://eclipsru.github.io/Arenda-Nvk/app-test.json|test_build|true|Уведомления пробной версии");
        }
        {   // 10.98: аватарки — выбор области кругом, центр старых фото, нажатие на край
            eq("аватар 10.98: круг не больше картинки и внутри неё", java.util.Arrays.toString(IvaAvatarPatch.clamp(50, 50, 500, 0, 0, 200, 100, 10)) + "|"
                + java.util.Arrays.toString(IvaAvatarPatch.clamp(-100, 300, 5, 10, 20, 200, 100, 10)) + "|"
                + java.util.Arrays.toString(IvaAvatarPatch.clamp(5, 5, 30, 0, 0, 16, 40, 36)),
                "[50.0, 50.0, 50.0]|[20.0, 110.0, 10.0]|[8.0, 8.0, 8.0]");
            eq("аватар 10.98: квадрат картинки под кругом", java.util.Arrays.toString(IvaAvatarPatch.srcRect(100, 60, 40, 0, 10, 0.5f, 400, 200)) + "|"
                + java.util.Arrays.toString(IvaAvatarPatch.srcRect(1000, 1000, 40, 0, 0, 0.5f, 400, 200)) + "|"
                + java.util.Arrays.toString(IvaAvatarPatch.srcRect(0, 0, 1000, 0, 0, 0.5f, 400, 200)) + "|"
                + java.util.Arrays.toString(IvaAvatarPatch.srcRect(10, 10, 5, 0, 0, 0f, 300, 200)),
                "[120, 20, 160]|[240, 40, 160]|[0, 0, 200]|[0, 0, 200]");
            float[] f98 = IvaAvatarPatch.fit(1000, 800, 400, 200, 10);
            eq("аватар 10.98: фото вписано в окно", Math.round(f98[0]) + "," + Math.round(f98[1]) + "," + Math.round(f98[2] * 100) + "|"
                + java.util.Arrays.toString(IvaAvatarPatch.fit(100, 100, 0, 50, 0)), "10,155,245|[0.0, 0.0, 1.0]");
            eq("аватар 10.98: центр не квадратного фото", java.util.Arrays.toString(IvaAvatarPatch.centerSquare(300, 200)) + "|"
                + java.util.Arrays.toString(IvaAvatarPatch.centerSquare(200, 300)) + "|" + java.util.Arrays.toString(IvaAvatarPatch.centerSquare(0, 0)),
                "[50, 0, 200]|[0, 50, 200]|[0, 0, 1]");
            eq("аватар 10.98: что взял палец", IvaAvatarPatch.grab(100, 100, 100, 100, 50, 10) + "," + IvaAvatarPatch.grab(150, 100, 100, 100, 50, 10) + ","
                + IvaAvatarPatch.grab(300, 300, 100, 100, 50, 10) + "," + IvaAvatarPatch.grab(136, 136, 100, 100, 50, 10) + ","
                + IvaAvatarPatch.grab(100, 145, 100, 100, 50, 10) + "," + IvaAvatarPatch.grab(100, 125, 100, 100, 50, 10), "1,2,1,2,2,1");
            eq("аватар 10.98: адрес фото", IvaAvatarPatch.isUrl(" https://x/a.jpg") + "," + IvaAvatarPatch.isUrl("http://x") + "," + IvaAvatarPatch.isUrl("ftp://x") + ","
                + IvaAvatarPatch.isUrl("") + "," + IvaAvatarPatch.isUrl(null), "true,true,false,false,false");
            eq("аватар 10.98: размеры", IvaAvatarPatch.HEAD_DP + "," + IvaAvatarPatch.PAGE_DP + "," + IvaClientPatch.AVATAR_DP + "," + IvaAvatarPatch.OUT_PX
                + "," + IvaAvatarPatch.JPEG_Q, "72,120,120,512,90");
        }
        {   // 10.98: «Настройка проката» → «Данные проката»
            eq("прокат 10.98: время из прежней строки", java.util.Arrays.toString(IvaShopPatch.parseTime("Пн–Сб 8:00–20:00")) + "|"
                + java.util.Arrays.toString(IvaShopPatch.parseTime("09.30 до 18.00")) + "|" + java.util.Arrays.toString(IvaShopPatch.parseTime("10:00 — 24:00")) + "|"
                + IvaShopPatch.parseTime("20:00-08:00") + "|" + IvaShopPatch.parseTime("25:00-26:00") + "|" + IvaShopPatch.parseTime("с 9 до 18") + "|"
                + IvaShopPatch.parseTime(null), "[480, 1200]|[570, 1080]|[600, 1440]|null|null|null|null");
            eq("прокат 10.98: дни из прежней строки", IvaShopPatch.daysFromText("Пн–Сб 8:00–20:00") + "," + IvaShopPatch.daysFromText("Ежедневно 9-18") + ","
                + IvaShopPatch.daysFromText("сб, вс") + "," + IvaShopPatch.daysFromText("Пт-Пн") + "," + IvaShopPatch.daysFromText("пнд встреча") + ","
                + IvaShopPatch.daysFromText("9:00-18:00") + "," + IvaShopPatch.daysFromText("Пн, Ср, Пт") + "," + IvaShopPatch.daysFromText(null), "63,127,96,113,0,0,21,0");
            eq("прокат 10.98: дни строкой", IvaShopPatch.daysText(63) + "|" + IvaShopPatch.daysText(127) + "|" + IvaShopPatch.daysText(96) + "|"
                + IvaShopPatch.daysText(21) + "|" + IvaShopPatch.daysText(59) + "|" + IvaShopPatch.daysText(113) + "|" + IvaShopPatch.daysText(0) + "|"
                + IvaShopPatch.daysText(1) + "|" + IvaShopPatch.daysText(255), "Пн–Сб|Ежедневно|Сб, Вс|Пн, Ср, Пт|Пн, Вт, Чт–Сб|Пн, Пт–Вс||Пн|Ежедневно");
            eq("прокат 10.98: «Время работы» для заявки и сайта", IvaShopPatch.hoursText(31, 540, 1080, "x") + "|" + IvaShopPatch.hoursText(0, 540, 1080, "")
                + "|" + IvaShopPatch.hoursText(31, -1, -1, " с 9 до 18 ") + "|" + IvaShopPatch.hoursText(127, 0, 1440, "") + "|" + IvaShopPatch.hoursText(1, 600, 600, null),
                "Пн–Пт 09:00–18:00|09:00–18:00|с 9 до 18|Ежедневно 00:00–24:00|");
            eq("прокат 10.98: проверки полей", IvaShopPatch.nameErr(" А ") + "|" + IvaShopPatch.nameErr("Прокат-НВ") + "|"
                + IvaShopPatch.nameErr(new String(new char[101]).replace('\0', 'я')) + "|" + IvaShopPatch.addrErr("") + "|" + IvaShopPatch.addrErr("Нск") + "|"
                + IvaShopPatch.ipErr(new String(new char[151]).replace('\0', 'я')) + "|" + IvaShopPatch.ipErr("ИП Иванов"),
                "Введите название проката||Название — не длиннее 100 символов||Введите адрес полностью: город, улица, дом|Не длиннее 150 символов|");
            eq("прокат 10.98: телефон начальника", IvaShopPatch.telErr("+7 (900) 123-45-67") + "|" + IvaShopPatch.telErr("") + "|" + IvaShopPatch.telErr("12345").isEmpty()
                + "|" + IvaShopPatch.telErr("+7 900 abc 45 67").isEmpty() + "|" + IvaShopPatch.telErr("8 900 123 45 67") + "|"
                + IvaShopPatch.telErr("1234567890123456").isEmpty(), "||false|false||false");
            eq("прокат 10.98: время с … до …", IvaShopPatch.timeErr(-1, -1) + "|" + IvaShopPatch.timeErr(540, -1) + "|" + IvaShopPatch.timeErr(600, 540) + "|"
                + IvaShopPatch.timeErr(540, 1080), "|Выберите и начало, и конец|Конец должен быть позже начала|");
            java.util.Map<String, String> r98 = new java.util.HashMap<String, String>();
            r98.put("company", " Прокат "); r98.put("org_address", "Нск, Ленина 5"); r98.put("work_hours", "Пн–Сб 8:00–20:00"); r98.put("work_days", null);
            r98.put("org_tel", "null");
            IvaShopPatch.Data d98 = IvaShopPatch.fromRow(r98, true);
            eq("прокат 10.98: строка admins → поля", d98.name + "|" + d98.addr + "|" + d98.from + "-" + d98.to + "|" + d98.days + "|" + d98.tel + "|" + d98.full,
                "Прокат|Нск, Ленина 5|480-1200|63|null|true".replace("|null|", "||"));
            r98.put("work_days", "5");
            eq("прокат 10.98: дни из базы важнее строки", IvaShopPatch.fromRow(r98, true).days + "," + IvaShopPatch.fromRow(new java.util.HashMap<String, String>(), false).from, "5,-1");
            IvaShopPatch.Data b98 = new IvaShopPatch.Data();
            b98.name = " Прокат  \"НВ\" "; b98.addr = "Нск,\nЛенина 5"; b98.hoursRaw = "old"; b98.from = 540; b98.to = 1080; b98.days = 31;
            b98.ip = "ИП Иванов\\"; b98.tel = " +7 900 ";
            eq("прокат 10.98: что уходит в admins", IvaShopPatch.body(b98, true) + "|" + IvaShopPatch.body(b98, false),
                "{\"company\":\"Прокат \\\"НВ\\\"\",\"org_address\":\"Нск, Ленина 5\",\"work_hours\":\"Пн–Пт 09:00–18:00\",\"org_ip\":\"ИП Иванов\\\\\",\"org_tel\":\"+7 900\",\"work_days\":31}|"
                + "{\"company\":\"Прокат \\\"НВ\\\"\",\"org_address\":\"Нск, Ленина 5\",\"work_hours\":\"Пн–Пт 09:00–18:00\"}");
            eq("прокат 10.98: экранирование", IvaShopPatch.q("a\u0001b\tc\r"), "\"a\\u0001b\\tc\\r\"");
            eq("прокат 10.98: нет колонок (SQL не применён)", IvaShopPatch.noColumn("400", "{\"code\":\"PGRST204\",\"message\":\"Could not find the 'org_ip' column\"}") + ","
                + IvaShopPatch.noColumn("400", "{\"code\":\"42703\",\"message\":\"column admins.org_ip does not exist\"}") + ","
                + IvaShopPatch.noColumn("403", "{\"code\":\"42501\"}") + "," + IvaShopPatch.noColumn("400", null), "true,true,false,false");
            eq("прокат 10.98: ФИО на три поля", String.join("/", IvaShopPatch.splitFio("Иванов  Иван Иванович", null)) + "|" + String.join("/", IvaShopPatch.splitFio("Иван", null))
                + "|" + String.join("/", IvaShopPatch.splitFio("Иванов Иван", null)) + "|" + String.join("/", IvaShopPatch.splitFio("", null)) + "|"
                + String.join("/", IvaShopPatch.splitFio("де ла Круз Хуан", new String[]{"де ла Круз", "Хуан", ""})) + "|"
                + String.join("/", IvaShopPatch.splitFio("Петров Пётр", new String[]{"Иванов", "Иван", ""})) + "|"
                + String.join("/", IvaShopPatch.splitFio("Оглы Мамед Али Оглы", null)),
                "Иванов/Иван/Иванович|/Иван/|Иванов/Иван/|//|де ла Круз/Хуан/|Петров/Пётр/|Оглы/Мамед/Али Оглы");
            eq("прокат 10.98: ФИО одной строкой", IvaShopPatch.joinFio(" Иванов ", "", " Петрович ") + "|" + IvaShopPatch.joinFio("", "", "") + "|"
                + IvaShopPatch.joinFio("Иванов", "Иван", "Иванович"), "Иванов Петрович||Иванов Иван Иванович");
            eq("прокат 10.98: названия", IvaShopPatch.ROW_TITLE + "|" + IvaShopPatch.DATA_TITLE + "|" + IvaPickupPatch.ROW_TITLE + "|" + IvaShopPatch.L_NAME + "|"
                + IvaShopPatch.L_ADDR + "|" + IvaShopPatch.L_IP + "|" + IvaShopPatch.L_TEL + "|" + IvaShopPatch.L_TIME + "|" + IvaShopPatch.L_DAYS,
                "Настройка проката|Данные проката|Пункты выдачи|Название проката|Адрес организации|ИП организации|Номер телефона (для связи с начальником)|Время работы|Рабочие дни");
            eq("прокат 10.98: подсказка «Срока аренды»", IvaRentPatch.LINK_HINT.contains("«Кабинет → Настройка проката → Пункты выдачи»") + "", "true");
        }
        {   // 10.99: телефон пункта выдачи; изменение — в карточке
            eq("пункт 10.99: телефон пункта — необязателен, 10–15 цифр", IvaPickupPatch.telErr("") + "|" + IvaPickupPatch.telErr(" +7 (383) 123-45-67 ") + "|"
                + IvaPickupPatch.telErr("12345").isEmpty() + "|" + IvaPickupPatch.telErr("+7 900 abc 45 67").isEmpty() + "|"
                + IvaPickupPatch.telErr("8 900 123 45 67") + "|" + IvaPickupPatch.telErr("+7 (900) 123-45-67               ").isEmpty() + "|"
                + IvaPickupPatch.telErr("1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6").isEmpty() + "|" + IvaPickupPatch.telErr(null), "||false|false||true|false|");
            eq("пункт 10.99: строка в карточке", IvaPickupPatch.telLine(" +7 900 123-45-67 ") + "|" + IvaPickupPatch.telLine("") + "|" + IvaPickupPatch.telLine(null),
                "Телефон: +7 900 123-45-67|Телефон: не указан|Телефон: не указан");
            IvaPickupPatch.Point p99 = new IvaPickupPatch.Point();
            p99.name = "Склад"; p99.address = "Новосибирск, улица Ленина, д. 5"; p99.email = "p@mail.ru"; p99.tel = " +7 900 \"1\" ";
            String b99 = IvaPickupPatch.body(p99, true);
            eq("пункт 10.99: телефон уходит в базу, без SQL — как раньше", b99.endsWith(",\"email\":\"p@mail.ru\",\"tel\":\"+7 900 \\\"1\\\"\"}") + "|"
                + IvaPickupPatch.body(p99, false).equals(IvaPickupPatch.body(p99)) + "|" + IvaPickupPatch.body(p99, false).contains("tel")
                + "|" + b99.startsWith(IvaPickupPatch.body(p99).substring(0, IvaPickupPatch.body(p99).length() - 1)), "true|true|false|true");
            eq("пункт 10.99: столбцы — с телефоном / без", IvaPickupPatch.listPath("a@x.ru", true).contains("select=id,name,address,city,lat,lng,open_min,close_min,email,created_at,tel&")
                + "|" + IvaPickupPatch.listPath("a@x.ru", false).equals(IvaPickupPatch.listPath("a@x.ru")) + "|" + IvaPickupPatch.listPath("a@x.ru").contains("tel"), "true|true|false");
            eq("пункт 10.99: ответы базы про телефон", IvaPickupPatch.noTel("400", "{\"code\":\"42703\",\"message\":\"column iva_pickup_points.tel does not exist\"}") + "|"
                + IvaPickupPatch.noTel("400", "{\"code\":\"PGRST204\",\"message\":\"Could not find the 'tel' column\"}") + "|" + IvaPickupPatch.noTel("403", "{\"code\":\"42501\"}") + "|"
                + IvaPickupPatch.dbError("400", "{\"code\":\"PGRST204\",\"message\":\"Could not find the 'tel' column\"}") + "|"
                + IvaPickupPatch.dbError("400", "{\"code\":\"23514\",\"message\":\"violates check constraint \\\"iva_pickup_tel\\\"\"}"),
                "true|true|false|Телефон пункта заработает после обновления базы (SQL 10.99)|Проверьте телефон: 10–15 цифр");
            eq("пункт 10.99: название поля", IvaPickupPatch.L_TEL, "Телефон пункта выдачи");
        }
        {   // 10.100: свой звук у всех уведомлений
            eq("звук 10.100: адрес звука для системы", IvaSoundPatch.uriText(), "content://ru.prokatnvsk.app.ivasound/notify.mp3");
            String[] chs = {IvaNotifyPatch.CH_MSG, IvaNotifyPatch.CH_ORD, IvaNotifyPatch.CH_UPD, IvaNotifyPatch.CH_RENT, IvaTestPatch.CH_TEST};
            StringBuilder on = new StringBuilder(), off = new StringBuilder();
            for (String c : chs) { on.append(IvaSoundPatch.channelId(c, true)).append('|'); off.append(IvaSoundPatch.channelId(c, false)).append('|'); }
            eq("звук 10.100: новые категории — у всех пяти", on.toString(), "iva_msg_s1|iva_orders_s1|iva_update_s1|iva_rent_s1|iva_test_build_s1|");
            eq("звук 10.100: без звука в APK — прежние категории", off.toString(), "iva_msg|iva_orders|iva_update|iva_rent|iva_test_build|");
            eq("звук 10.100: провайдер отдаёт только звук и только на чтение", IvaSoundPatch.servable("/notify.mp3", "r") + "|" + IvaSoundPatch.servable("/notify.mp3", null) + "|"
                + IvaSoundPatch.servable("/notify.mp3", "w") + "|" + IvaSoundPatch.servable("/notify.mp3", "rw") + "|" + IvaSoundPatch.servable("/../shared_prefs/x.xml", "r") + "|"
                + IvaSoundPatch.servable("/notify.mp3/../x", "r") + "|" + IvaSoundPatch.servable(null, "r") + "|" + IvaSoundPatch.servable("", "r"),
                "true|true|false|false|false|false|false|false");
            eq("звук 10.100: ответы провайдера на запрос", IvaSoundPatch.column("_display_name", 5) + "|" + IvaSoundPatch.column("title", 5) + "|" + IvaSoundPatch.column("_size", 36570L) + "|"
                + IvaSoundPatch.column("mime_type", 5) + "|" + IvaSoundPatch.column("_id", 5) + "|" + IvaSoundPatch.column("_data", 5), "notify.mp3|Ива|36570|audio/mpeg|1|null");
            String snd = "";
            try {
                byte[] f = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("android/sounds/iva_notify.mp3"));
                boolean frame = f.length > 4 && (f[0] & 0xff) == 0xff && (f[1] & 0xe0) == 0xe0;
                boolean id3 = f.length > 3 && f[0] == 'I' && f[1] == 'D' && f[2] == '3';
                boolean v1 = f.length > 128 && f[f.length - 128] == 'T' && f[f.length - 127] == 'A' && f[f.length - 126] == 'G';
                snd = frame + "|" + id3 + "|" + v1 + "|" + (f.length > 1000 && f.length <= 1024 * 1024);
            } catch (Exception e) { snd = "нет файла: " + e; }
            eq("звук 10.100: файл звука — MP3 без тегов, до 1 МБ", snd, "true|false|false|true");
        }
        {   // 10.101: инструмент закреплён за пунктом выдачи (этап 1 «Сотрудники и пункты»)
            String u1 = "0b6f9c2e-1d2a-4c3b-9e8f-112233445566", u2 = "AB6F9C2E-1D2A-4C3B-9E8F-112233445577";
            eq("пункт 10.101: тело записи — только настоящий id", IvaToolPointPatch.body(u1) + "|" + IvaToolPointPatch.body(u2) + "|" + IvaToolPointPatch.body("")
                + "|" + IvaToolPointPatch.body("1; drop") + "|" + IvaToolPointPatch.body("\"},{\"owner_email\":\"x\"") + "|" + IvaToolPointPatch.body(null),
                "{\"point_id\":\"" + u1 + "\"}|{\"point_id\":\"ab6f9c2e-1d2a-4c3b-9e8f-112233445577\"}|null|null|null|null");
            eq("пункт 10.101: когда видно поле", IvaToolPointPatch.show(0, 2, true, true) + "|" + IvaToolPointPatch.show(0, 1, true, true) + "|"
                + IvaToolPointPatch.show(0, 0, true, true) + "|" + IvaToolPointPatch.show(1, 1, true, true) + "|" + IvaToolPointPatch.show(1, 1, true, false) + "|"
                + IvaToolPointPatch.show(1, 3, true, false) + "|" + IvaToolPointPatch.show(0, 5, false, true) + "|" + IvaToolPointPatch.show(1, 1, false, true),
                "true|false|false|true|false|true|false|false");
            eq("пункт 10.101: когда писать после успеха", IvaToolPointPatch.needWrite(0, u1, u1, "") + "|" + IvaToolPointPatch.needWrite(0, u2, u1, "") + "|"
                + IvaToolPointPatch.needWrite(1, u1, "", u1) + "|" + IvaToolPointPatch.needWrite(1, u1, "", "") + "|" + IvaToolPointPatch.needWrite(1, "", "", u1) + "|"
                + IvaToolPointPatch.needWrite(1, u1, "", u1.toUpperCase()) + "|" + IvaToolPointPatch.needWrite(0, "x", u1, ""),
                "false|true|false|true|false|false|false");
            java.util.List<IvaPickupPatch.Point> ps = new java.util.ArrayList<IvaPickupPatch.Point>();
            eq("пункт 10.101: выбранный без пунктов", IvaToolPointPatch.current(u1, ps) + "|" + IvaToolPointPatch.current(u1, null), "|");
            IvaPickupPatch.Point a1 = new IvaPickupPatch.Point(), a2 = new IvaPickupPatch.Point();
            a1.id = u1; a1.name = "Склад"; a1.city = "Новосибирск"; a1.address = "Новосибирск, улица Ленина, д. 5"; a1.tel = " +7 383 111-22-33 ";
            a2.id = u2; a2.name = "Бердск центр"; a2.city = "Бердск"; a2.address = ""; a2.tel = "";
            a2.open = new int[]{540, 540, 540, 540, 540, 600, -1}; a2.close = new int[]{1140, 1140, 1140, 1140, 1140, 960, -1};
            ps.add(a1); ps.add(a2);
            eq("пункт 10.101: выбранный — свой, иначе первый", IvaToolPointPatch.current("", ps) + "|" + IvaToolPointPatch.current(u2, ps).equals(u2) + "|"
                + IvaToolPointPatch.current("чужой", ps), u1 + "|true|" + u1);
            eq("пункт 10.101: подписи кнопок", IvaToolPointPatch.chipText(a1) + "|" + IvaToolPointPatch.chipText(a2), "Склад · Новосибирск|Бердск центр");
            eq("пункт 10.101: карточка инструмента", IvaToolPointPatch.cardText(a1),
                "Пункт выдачи: Склад\nАдрес: Новосибирск, улица Ленина, д. 5\nВремя работы: Пн–Вс 09:00–19:00\nТелефон: +7 383 111-22-33");
            eq("пункт 10.101: карточка без адреса и телефона", IvaToolPointPatch.cardText(a2) + "|" + IvaToolPointPatch.cardText(null),
                "Пункт выдачи: Бердск центр\nВремя работы: Пн–Пт 09:00–19:00 · Сб 10:00–16:00 · Вс выходной|");
            eq("пункт 10.101: под кнопками — адрес и график", IvaToolPointPatch.subText(a1) + "|" + IvaToolPointPatch.subText(a2),
                "Новосибирск, улица Ленина, д. 5\nПн–Вс 09:00–19:00|Пн–Пт 09:00–19:00 · Сб 10:00–16:00 · Вс выходной");
            java.util.List<String> ids = new java.util.ArrayList<String>(java.util.Arrays.asList(u1, u2, u1, "x", null, u2.toLowerCase()));
            eq("пункт 10.101: запрос пунктов для карточек", IvaToolPointPatch.rpcBody(ids) + "|" + IvaToolPointPatch.rpcBody(null),
                "{\"p_ids\":[\"" + u1 + "\",\"ab6f9c2e-1d2a-4c3b-9e8f-112233445577\"]}|{\"p_ids\":[]}");
            java.util.List<String> many = new java.util.ArrayList<String>();
            for (int i = 0; i < 250; i++) many.add(String.format("00000000-0000-0000-0000-%012d", i));
            eq("пункт 10.101: не больше 200 id", (IvaToolPointPatch.rpcBody(many).split(",").length) + "", "200");
            eq("пункт 10.101: ответы базы", IvaToolPointPatch.writeError("204", "") + "|"
                + IvaToolPointPatch.writeError("400", "{\"code\":\"P0001\",\"message\":\"Пункт выдачи не найден у владельца инструмента\"}") + "|"
                + IvaToolPointPatch.writeError("400", "{\"code\":\"PGRST204\",\"message\":\"Could not find the 'point_id' column of 'tools'\"}") + "|"
                + IvaToolPointPatch.writeError("403", "{\"code\":\"42501\"}") + "|" + IvaToolPointPatch.writeError("", "") + "|" + IvaToolPointPatch.writeError("500", "x"),
                "|Пункт выдачи не сохранился: это пункт другого проката|Пункт выдачи заработает после обновления базы (SQL 10.101)|Пункт выдачи не сохранился: нет права|"
                + "Пункт выдачи не сохранился — нет связи с сервером|Пункт выдачи не сохранился (ответ 500)");
            eq("пункт 10.101: нет столбца — только про point_id", IvaToolPointPatch.noColumn("400", "{\"code\":\"42703\",\"message\":\"column tools.point_id does not exist\"}") + "|"
                + IvaToolPointPatch.noColumn("400", "{\"code\":\"42703\",\"message\":\"column tools.kind does not exist\"}"), "true|false");
            eq("пункт 10.101: пункт с инструментом не удаляется — понятная фраза",
                IvaPickupPatch.dbError("409", "{\"code\":\"23503\",\"message\":\"update or delete on table \\\"iva_pickup_points\\\" violates foreign key constraint \\\"tools_point_fk\\\" on table \\\"tools\\\"\"}"),
                "В этом пункте есть инструмент — сначала перенесите его в другой пункт (правка инструмента → «Пункт выдачи»)");
            boolean ord = false;
            for (int i = 0; i + 1 < IvaFormPatch.ORDER.length; i++)
                if ((IvaFormPatch.SEC + "4").equals(IvaFormPatch.ORDER[i]) && (IvaToolPointPatch.TAG + ":0").equals(IvaFormPatch.ORDER[i + 1])) ord = true;
            eq("пункт 10.101: в форме «Добавить» — первым в «Условиях и доставке»", ord + "", "true");
        }
        {   // 10.102: заявки по пунктам выдачи (этап 2 «Сотрудники и пункты»)
            String u1 = "0b6f9c2e-1d2a-4c3b-9e8f-112233445566", u2 = "AB6F9C2E-1D2A-4C3B-9E8F-112233445577";
            java.util.List<IvaOrderPatch.Line> ls = new java.util.ArrayList<IvaOrderPatch.Line>();
            IvaOrderPatch.Line l1 = IvaOrderPatch.line("t" + u1, "Дрель", 300, 1500, false, 0, false, "a@x.ru", 3); l1.id = u1;
            IvaOrderPatch.Line l2 = IvaOrderPatch.line("t" + u2, "Пила", 500, 2500, false, 0, false, "a@x.ru", 120); l2.id = u2;
            IvaOrderPatch.Line l3 = IvaOrderPatch.line("t" + u1, "Дрель ещё раз", 300, 1500, false, 0, false, "a@x.ru", 5); l3.id = u1.toUpperCase();
            IvaOrderPatch.Line l4 = IvaOrderPatch.line("s1", "Заточка", 100, 0, false, 0, true, "a@x.ru", 1); l4.id = u2;
            IvaOrderPatch.Line l5 = IvaOrderPatch.line("t7", "Старая", 100, 0, false, 0, false, "a@x.ru", 1); l5.id = "7";
            ls.add(l1); ls.add(l2); ls.add(l3); ls.add(l4); ls.add(l5);
            eq("заявки 10.102: список инструментов — id без повторов, свой срок (не больше 90), без услуг и старых id", IvaOrderPointPatch.itemsJson(ls),
                "[{\"id\":\"" + u1 + "\",\"days\":3},{\"id\":\"ab6f9c2e-1d2a-4c3b-9e8f-112233445577\",\"days\":90}]");
            java.util.List<IvaOrderPatch.Line> none = new java.util.ArrayList<IvaOrderPatch.Line>();
            none.add(l4); none.add(l5);
            eq("заявки 10.102: нет ни одного инструмента с id — без списка", IvaOrderPointPatch.itemsJson(none) + "|" + IvaOrderPointPatch.itemsJson(null), "null|null");
            java.util.List<IvaOrderPatch.Line> big = new java.util.ArrayList<IvaOrderPatch.Line>();
            for (int i = 0; i < 70; i++) { IvaOrderPatch.Line x = IvaOrderPatch.line("t" + i, "x", 1, 1, false, 0, false, "a@x.ru", 1); x.id = String.format("00000000-0000-0000-0000-%012d", i); big.add(x); }
            eq("заявки 10.102: не больше 50 инструментов", IvaOrderPointPatch.itemsJson(big).split("\\{").length - 1 + "", "50");
            eq("заявки 10.102: повторно штатно — только когда функции нет (заявка точно не сохранилась)",
                IvaOrderPointPatch.noRpc("404", "{\"code\":\"PGRST202\",\"message\":\"Could not find the function public.iva_submit_order(...)\"}") + "|"
                + IvaOrderPointPatch.noRpc("404", "{\"message\":\"Could not find the function public.iva_submit_order\"}") + "|"
                + IvaOrderPointPatch.noRpc("404", "<html>Not found</html>") + "|" + IvaOrderPointPatch.noRpc("500", "PGRST202") + "|"
                + IvaOrderPointPatch.noRpc("200", "123") + "|" + IvaOrderPointPatch.noRpc(null, null) + "|" + IvaOrderPointPatch.noRpc("400", "{\"code\":\"22P02\"}"),
                "true|true|false|false|false|false|false");
            eq("заявки 10.102: адрес самовывоза — адреса пунктов без повторов, нет — прежний",
                IvaOrderPointPatch.joinAddr(java.util.Arrays.asList("Новосибирск, Ленина, 5", " Новосибирск, Ленина, 5 ", "Бердск, Мира, 1", "", null, "null"), "прокат") + "|"
                + IvaOrderPointPatch.joinAddr(new java.util.ArrayList<String>(), "прокат") + "|" + IvaOrderPointPatch.joinAddr(null, null),
                "Новосибирск, Ленина, 5; Бердск, Мира, 1|прокат|");
            java.util.Map<String, String> pp = new java.util.HashMap<String, String>();
            pp.put("11", u1); pp.put("12", "ab6f9c2e-1d2a-4c3b-9e8f-112233445577"); pp.put("13", "");
            java.util.List<String> pids = java.util.Arrays.asList("11", "12", "13", "14", "11");
            eq("заявки 10.102: фильтр «Все пункты» / пункт", IvaOrderPointPatch.keep(pids, pp, "") + "|" + IvaOrderPointPatch.keep(pids, pp, u1) + "|"
                + IvaOrderPointPatch.keep(pids, pp, u2) + "|" + IvaOrderPointPatch.keep(pids, pp, "00000000-0000-0000-0000-000000000000") + "|"
                + IvaOrderPointPatch.keep(null, pp, u1) + "|" + IvaOrderPointPatch.keep(pids, null, u1),
                "[0, 1, 2, 3, 4]|[0, 4]|[1]|[]|[]|[]");
            IvaPickupPatch.Point a1 = new IvaPickupPatch.Point(), a2 = new IvaPickupPatch.Point();
            a1.id = u1; a1.name = "Склад"; a1.city = "Новосибирск"; a1.address = "Новосибирск, улица Ленина, д. 5"; a1.tel = " +7 383 111-22-33 ";
            a2.id = u2; a2.name = "Бердск центр"; a2.city = "Бердск"; a2.address = ""; a2.tel = "";
            a2.open = new int[]{540, 540, 540, 540, 540, 600, -1}; a2.close = new int[]{1140, 1140, 1140, 1140, 1140, 960, -1};
            StringBuilder one = new StringBuilder();
            for (String[] f : IvaOrderPointPatch.clientFields(java.util.Collections.singletonList(a1), java.util.Collections.singletonList("Дрель"))) one.append(f[0]).append('=').append(f[1]).append(';');
            eq("заявки 10.102: клиенту — пункт, адрес, время работы, телефон (одна часть — без списка инструментов)", one.toString(),
                "Пункт выдачи=Склад · Новосибирск;Адрес=Новосибирск, улица Ленина, д. 5;Время работы=Пн–Вс 09:00–19:00;Телефон=+7 383 111-22-33;");
            StringBuilder two = new StringBuilder();
            for (String[] f : IvaOrderPointPatch.clientFields(java.util.Arrays.asList(a1, a2), java.util.Arrays.asList("Дрель", "Пила"))) two.append(f[0]).append('=').append(f[1]).append(';');
            eq("заявки 10.102: две части — у каждой свой пункт и свои инструменты; пустое не показывается", two.toString(),
                "Пункт выдачи=Склад · Новосибирск;Инструменты=Дрель;Адрес=Новосибирск, улица Ленина, д. 5;Время работы=Пн–Вс 09:00–19:00;Телефон=+7 383 111-22-33;"
                + "Пункт выдачи=Бердск центр;Инструменты=Пила;Время работы=Пн–Пт 09:00–19:00 · Сб 10:00–16:00 · Вс выходной;");
            eq("заявки 10.102: без пунктов — пусто", IvaOrderPointPatch.clientFields(null, null).size() + "", "0");
            java.util.Map<String, String> ad = new java.util.HashMap<String, String>();
            ad.put("company", "Прокат"); ad.put("full_name", "Иван"); ad.put("phone", "+7 900"); ad.put("address", "Адрес проката"); ad.put("work_hours", "9–19");
            StringBuilder pf = new StringBuilder(), pn = new StringBuilder();
            for (String[] f : IvaOrderPatch.ordProkatFields(ad, false)) pf.append(f[0]).append(';');
            for (String[] f : IvaOrderPatch.ordProkatFields(ad, true)) pn.append(f[0]).append(';');
            eq("заявки 10.102: у заявки есть пункт — адрес и часы проката не показываются", pf + "|" + pn,
                "Прокат;Контактное лицо;Телефон;Адрес выдачи;Часы работы;|Прокат;Контактное лицо;Телефон;");
            eq("заявки 10.102: подпись набора частей", IvaOrderPointPatch.sig(java.util.Arrays.asList("x", null)) + "|" + IvaOrderPointPatch.sig(null), "?,?,|");
        }
        {   // 10.103: сотрудники пунктов выдачи (этап 3а «Сотрудники и пункты»)
            String u1 = "0b6f9c2e-1d2a-4c3b-9e8f-112233445566", u2 = "AB6F9C2E-1D2A-4C3B-9E8F-112233445577";
            eq("сотрудники 10.103: наборы прав", java.util.Arrays.toString(IvaStaffPatch.preset("Выдача")) + "|" + java.util.Arrays.toString(IvaStaffPatch.preset("Каталог"))
                + "|" + java.util.Arrays.toString(IvaStaffPatch.preset("другое")),
                "[true, false, true, true]|[false, true, false, false]|[false, false, false, false]");
            eq("сотрудники 10.103: какой набор выбран", IvaStaffPatch.presetOf(new boolean[]{true, false, true, true}) + "|" + IvaStaffPatch.presetOf(new boolean[]{false, true, false, false})
                + "|" + IvaStaffPatch.presetOf(new boolean[]{true, true, false, false}) + "|" + IvaStaffPatch.presetOf(null), "Выдача|Каталог||");
            eq("сотрудники 10.103: права текстом", IvaStaffPatch.rightsText(new boolean[]{true, false, true, false}) + "|" + IvaStaffPatch.rightsText(new boolean[4])
                + "|" + IvaStaffPatch.rightsText(new boolean[]{false, true, false, true}),
                "Выдаёт и принимает · Отвечает в чате|Без прав|Выкладывает инструмент · Получает уведомления");
            eq("сотрудники 10.103: состояние", IvaStaffPatch.statusText("invited") + "|" + IvaStaffPatch.statusText("active") + "|" + IvaStaffPatch.statusText("declined")
                + "|" + IvaStaffPatch.statusText(null), "Ждёт ответа|Работает|Отказался|Ждёт ответа");
            eq("сотрудники 10.103: кнопка у пункта", IvaStaffPatch.chipText(0) + "|" + IvaStaffPatch.chipText(3), "Сотрудники|Сотрудники · 3");
            boolean[] ok = {true, false, false, false}, none = new boolean[4];
            eq("сотрудники 10.103: проверка формы — как в базе",
                IvaStaffPatch.formErr(true, "", "a@x.ru", "Иван", "", 1, ok) + "|" + IvaStaffPatch.formErr(true, "ivan", "a@x.ru", "Иван", "", 1, ok) + "|"
                + IvaStaffPatch.formErr(true, " A@X.ru ", "a@x.ru", "Иван", "", 1, ok) + "|" + IvaStaffPatch.formErr(true, "i@x.ru", "a@x.ru", "  ", "", 1, ok) + "|"
                + IvaStaffPatch.formErr(true, "i@x.ru", "a@x.ru", "Иван", "12-34", 1, ok) + "|" + IvaStaffPatch.formErr(true, "i@x.ru", "a@x.ru", "Иван", "", 0, ok) + "|"
                + IvaStaffPatch.formErr(true, "i@x.ru", "a@x.ru", "Иван", "", 1, none) + "|" + IvaStaffPatch.formErr(true, "i@x.ru", "a@x.ru", "Иван", "+7 900 123-45-67", 1, ok) + "|"
                + IvaStaffPatch.formErr(false, "", "a@x.ru", "Иван", "", 1, ok),
                "Введите почту, под которой сотрудник входит в приложение|Похоже, в email ошибка (пример: ivan@mail.ru)|"
                + "Это ваша почта — владельца проката добавлять не нужно|Введите ФИО сотрудника|Проверьте номер: 10–15 цифр, например +7 900 123-45-67|"
                + "Отметьте хотя бы один пункт выдачи|Отметьте хотя бы одно право||");
            StringBuilder longName = new StringBuilder();
            for (int i = 0; i < 81; i++) longName.append('я');
            eq("сотрудники 10.103: имя не длиннее 80", IvaStaffPatch.formErr(false, "", "", longName.toString(), "", 1, ok), "ФИО — не длиннее 80 символов");
            eq("сотрудники 10.103: запрос «Пригласить» — почта строчными, только настоящие id пунктов, кавычки экранированы",
                IvaStaffPatch.saveBody(null, " Ivan@X.ru ", "  Иван  \"Ваня\"  ", " +7 900 ", java.util.Arrays.asList(u1, "x", null, u2), new boolean[]{true, false, true, true}, false),
                "{\"p_id\":null,\"p_email\":\"ivan@x.ru\",\"p_name\":\"Иван \\\"Ваня\\\"\",\"p_tel\":\"+7 900\",\"p_points\":[\"" + u1
                + "\",\"ab6f9c2e-1d2a-4c3b-9e8f-112233445577\"],\"p_issue\":true,\"p_catalog\":false,\"p_chat\":true,\"p_notify\":true,\"p_self\":false}");
            eq("сотрудники 10.103: запрос «Сохранить» — с id; плохой id — как новый",
                IvaStaffPatch.saveBody(u2, "", "И", "", null, null, true) + "|" + IvaStaffPatch.saveBody("1; drop", "", "И", "", null, null, true).startsWith("{\"p_id\":null,"),
                "{\"p_id\":\"ab6f9c2e-1d2a-4c3b-9e8f-112233445577\",\"p_email\":\"\",\"p_name\":\"И\",\"p_tel\":\"\",\"p_points\":[],\"p_issue\":false,\"p_catalog\":false,"
                + "\"p_chat\":false,\"p_notify\":false,\"p_self\":true}|true");
            eq("сотрудники 10.103: «Убрать» и «Принять / Отказаться» — только настоящий id",
                IvaStaffPatch.idBody(u1) + "|" + IvaStaffPatch.idBody("x") + "|" + IvaStaffPatch.answerBody(u2, true) + "|" + IvaStaffPatch.answerBody(u1, false) + "|" + IvaStaffPatch.answerBody(null, true),
                "{\"p_id\":\"" + u1 + "\"}|null|{\"p_id\":\"ab6f9c2e-1d2a-4c3b-9e8f-112233445577\",\"p_accept\":true}|{\"p_id\":\"" + u1 + "\",\"p_accept\":false}|null");
            eq("сотрудники 10.103: ответы базы",
                IvaStaffPatch.dbError("204", "") + "|"
                + IvaStaffPatch.dbError("400", "{\"code\":\"P0001\",\"message\":\"Этот человек уже сотрудник другого проката (или приглашён им)\"}") + "|"
                + IvaStaffPatch.dbError("409", "{\"code\":\"23505\",\"message\":\"duplicate key value violates unique constraint \\\"iva_staff_one_owner\\\"\"}") + "|"
                + IvaStaffPatch.dbError("404", "{\"code\":\"PGRST202\",\"message\":\"Could not find the function public.iva_staff_list\"}") + "|"
                + IvaStaffPatch.dbError("403", "{\"code\":\"42501\"}") + "|" + IvaStaffPatch.dbError("", "") + "|" + IvaStaffPatch.dbError("500", "x"),
                "|Этот человек уже сотрудник другого проката (или приглашён им)|Этот человек уже сотрудник другого проката (или приглашён им)|"
                + "Сотрудники заработают после обновления базы (SQL 10.103)|Нет права — войдите заново|Не получилось — нет связи с сервером|Не получилось (ответ 500)");
            eq("сотрудники 10.103: текст приглашения", IvaStaffPatch.inviteText("Ива-Прокат", java.util.Arrays.asList("Склад")) + "|"
                + IvaStaffPatch.inviteText(" ", java.util.Arrays.asList("Склад", "Бердск")) + "|" + IvaStaffPatch.inviteText("Ива", null),
                "«Ива-Прокат» приглашает вас сотрудником пункта «Склад»|«Прокат» приглашает вас сотрудником пунктов «Склад», «Бердск»|«Ива» приглашает вас сотрудником");
            eq("сотрудники 10.103: строка «Мой пункт»", IvaStaffPatch.mineSub("Ива", java.util.Arrays.asList("Склад")) + "|"
                + IvaStaffPatch.mineSub("Ива", java.util.Arrays.asList("Склад", "Бердск")) + "|" + IvaStaffPatch.mineSub("", null),
                "Склад · Ива|" + IvaPickupPatch.countText(2) + " · Ива|Прокат");
            IvaStaffPatch.Staff s1 = new IvaStaffPatch.Staff(), s2 = new IvaStaffPatch.Staff();
            s1.name = "Иван"; s1.points.add(u1); s2.name = "Ольга"; s2.points.add(u1); s2.points.add(u2.toLowerCase());
            java.util.List<IvaStaffPatch.Staff> all = java.util.Arrays.asList(s1, s2);
            StringBuilder of = new StringBuilder();
            for (IvaStaffPatch.Staff x : IvaStaffPatch.ofPoint(all, u2)) of.append(x.name);
            eq("сотрудники 10.103: сотрудники пункта", IvaStaffPatch.ofPoint(all, u1).size() + "|" + of + "|" + IvaStaffPatch.ofPoint(null, u1).size() + "|" + IvaStaffPatch.ofPoint(all, null).size(),
                "2|Ольга|0|0");
            IvaPickupPatch.Point a1 = new IvaPickupPatch.Point(), a2 = new IvaPickupPatch.Point();
            a1.id = u1; a1.name = "Склад"; a2.id = u2; a2.name = "Бердск";
            eq("сотрудники 10.103: «Ещё пункты»", IvaStaffPatch.pointNames(java.util.Arrays.asList(a1, a2), s2.points, u1) + "|"
                + IvaStaffPatch.pointNames(java.util.Arrays.asList(a1, a2), s2.points, "") + "|" + IvaStaffPatch.pointNames(null, s2.points, ""), "[Бердск]|[Склад, Бердск]|[]");
        }
        {   // 10.104: заявки пункта у сотрудника (этап 3б «Сотрудники и пункты»)
            eq("заявки пункта 10.104: запрос «Выдал» / «Вернули» — только номер из цифр и два состояния",
                IvaStaffOrdersPatch.markBody(" 123 ", "rented") + "|" + IvaStaffOrdersPatch.markBody("7", "closed") + "|" + IvaStaffOrdersPatch.markBody("7", "new") + "|"
                + IvaStaffOrdersPatch.markBody("1; drop", "rented") + "|" + IvaStaffOrdersPatch.markBody(null, "rented") + "|" + IvaStaffOrdersPatch.markBody("", "closed"),
                "{\"p_part\":123,\"p_status\":\"rented\"}|{\"p_part\":7,\"p_status\":\"closed\"}|null|null|null|null");
            eq("заявки пункта 10.104: кнопка и следующее состояние",
                IvaStaffOrdersPatch.actionText("new") + ">" + IvaStaffOrdersPatch.next("new") + "|" + IvaStaffOrdersPatch.actionText("rented") + ">" + IvaStaffOrdersPatch.next("rented")
                + "|" + IvaStaffOrdersPatch.actionText("closed") + ">" + IvaStaffOrdersPatch.next("closed") + "|" + IvaStaffOrdersPatch.next(null), "Выдал>rented|Вернули>closed|>|");
            eq("заявки пункта 10.104: состояние текстом", IvaStaffOrdersPatch.statusText("new") + "|" + IvaStaffOrdersPatch.statusText("rented") + "|"
                + IvaStaffOrdersPatch.statusText("closed") + "|" + IvaStaffOrdersPatch.statusText(null), "Новая|Выдана|Закрыта|");
            eq("заявки пункта 10.104: подтверждение", IvaStaffOrdersPatch.confirmTitle("new") + "|" + IvaStaffOrdersPatch.confirmTitle("rented") + "|"
                + IvaStaffOrdersPatch.confirmText("new", "Дрель Makita") + "|" + IvaStaffOrdersPatch.confirmText("rented", ""),
                "Отметить «Выдал»?|Отметить «Вернули»?|Дрель Makita\nСрок аренды начнётся сейчас.|Заявка закроется, инструмент снова свободен.");
            int[] n = IvaStaffOrdersPatch.counts(java.util.Arrays.asList("new", "rented", "new", "closed", "x", null));
            eq("заявки пункта 10.104: вкладки и счётчики", n[0] + "," + n[1] + "," + n[2] + "|" + IvaStaffOrdersPatch.tabText(0, n) + "|" + IvaStaffOrdersPatch.tabText(2, n)
                + "|" + IvaStaffOrdersPatch.tabText(1, new int[3]) + "|" + IvaStaffOrdersPatch.counts(null)[0], "2,1,1|Новые · 2|Закрыты · 1|Выданы|0");
            eq("заявки пункта 10.104: с какой вкладки открыть", IvaStaffOrdersPatch.firstTab(new int[]{1, 3, 0}) + "|" + IvaStaffOrdersPatch.firstTab(new int[]{0, 3, 0})
                + "|" + IvaStaffOrdersPatch.firstTab(new int[]{0, 0, 5}) + "|" + IvaStaffOrdersPatch.firstTab(null), "0|1|0|0");
            eq("заявки пункта 10.104: пусто", IvaStaffOrdersPatch.emptyText(0) + "|" + IvaStaffOrdersPatch.emptyText(1) + "|" + IvaStaffOrdersPatch.emptyText(2),
                "Новых заявок нет|Выданных сейчас нет|Закрытых за 7 дней нет");
            eq("заявки пункта 10.104: дни", IvaStaffOrdersPatch.daysText(1) + "|" + IvaStaffOrdersPatch.daysText(3) + "|" + IvaStaffOrdersPatch.daysText(5) + "|"
                + IvaStaffOrdersPatch.daysText(11) + "|" + IvaStaffOrdersPatch.daysText(21) + "|" + IvaStaffOrdersPatch.daysText(112), "1 день|3 дня|5 дней|11 дней|21 день|112 дней");
            eq("заявки пункта 10.104: уведомление — только новые и только с правом «Получает уведомления»",
                IvaStaffOrdersPatch.notifyIds(java.util.Arrays.asList("5", "4", "3", "2"), java.util.Arrays.asList("new", "new", "rented", "new"),
                    java.util.Arrays.asList(true, false, true, true)) + "|" + IvaStaffOrdersPatch.notifyIds(null, null, null), "[5, 2]|[]");
            eq("заявки пункта 10.104: заголовок и текст уведомления", IvaStaffOrdersPatch.noteTitle(1, "Склад") + "|" + IvaStaffOrdersPatch.noteTitle(1, " ") + "|"
                + IvaStaffOrdersPatch.noteTitle(3, "Склад") + "|" + IvaStaffOrdersPatch.noteText(1, "Пила") + "|" + IvaStaffOrdersPatch.noteText(2, "Пила"),
                "Новая заявка · Склад|Новая заявка|Новые заявки: 3|Пила|Откройте «Заявки пункта»");
            eq("заявки пункта 10.104: кому проверять заявки", IvaStaffOrdersPatch.want("active", new boolean[]{true, false, false, false}) + "|"
                + IvaStaffOrdersPatch.want("active", new boolean[]{false, false, false, true}) + "|" + IvaStaffOrdersPatch.want("active", new boolean[]{false, true, true, false}) + "|"
                + IvaStaffOrdersPatch.want("invited", new boolean[]{true, true, true, true}) + "|" + IvaStaffOrdersPatch.want("active", null), "true|true|false|false|false");
            eq("заявки пункта 10.104: ответы базы",
                IvaStaffOrdersPatch.dbError("200", "") + "|"
                + IvaStaffOrdersPatch.dbError("400", "{\"code\":\"P0001\",\"message\":\"Свою заявку выдаёт и принимает другой сотрудник или владелец\"}") + "|"
                + IvaStaffOrdersPatch.dbError("400", "{\"message\":\"Заявка уже в другом состоянии — обновите список\"}") + "|"
                + IvaStaffOrdersPatch.dbError("404", "{\"code\":\"PGRST202\"}") + "|" + IvaStaffOrdersPatch.dbError("401", "") + "|" + IvaStaffOrdersPatch.dbError(null, null),
                "|Свою заявку выдаёт и принимает другой сотрудник или владелец|Заявка уже в другом состоянии — обновите список|"
                + "Заявки пункта заработают после обновления базы (SQL 10.104)|Нет права — войдите заново|Не получилось — нет связи с сервером");
            eq("заявки пункта 10.104: id уведомления не совпадает с другими", IvaStaffOrdersPatch.ID_NOTE != IvaNotifyPatch.ID_ORD && IvaStaffOrdersPatch.ID_NOTE != IvaNotifyPatch.ID_TEST
                && IvaStaffOrdersPatch.ID_NOTE != IvaNotifyPatch.ID_UPD && IvaStaffOrdersPatch.ID_NOTE != IvaNotifyPatch.ID_MSG, true);
        }
        staffTools105();                                              // 10.105: отдельно — main у предела 64 КБ
        staffChat106();                                               // 10.106: чат пункта
        staffJournal107();                                            // 10.107: журнал, сводка, имена в чате
        adminManage108();                                             // 10.108: приостановить / удалить админа, порядок в панели
        avatarEdit109();                                              // 10.109: редактировать аватарку, галерея вместо проводника
        access110();                                                  // 10.110: убрали админа / сотрудника — сразу клиент
        summary111();                                                 // 10.111: в «Сводке» нет раздела «Без пункта»
        realtime112();                                                // 10.112: роль и приглашение — моментально (Realtime)
        logo113();                                                    // 10.113: вид раздела с логотипом, выключатель у пункта
        points114();                                                  // 10.114: оба набора прав, выбор пункта в «Добавить»
        live115();                                                    // 10.115: правки доступа сразу, «Сотрудники»
        live116();                                                    // 10.116: причина отказа сотруднику, «на одобрение», переходы
        cab117();                                                     // 10.117: комиссия без скачков, роль и архив в кабинете
        try118();                                                     // 10.118: пробная запись называет причину отказа
        mail119();                                                    // 10.119: почта — из ключа входа
        prokat120();                                                  // 10.120: страница проката, чат пункта, «На одобрении»
        v121();                                                       // 10.121: первый пункт, правки на одобрение, заявки целиком
        v122();                                                       // 10.122: удаление сотрудником, «Мой инструмент», чат, отмена заявки
        v123();                                                       // 10.123: доставка по наибольшей цене, «обратно» за каждым сроком, карточки
        if (fail > 0) { System.out.println("ошибок: " + fail); System.exit(1); }
        System.out.println("все проверки IvaPatch пройдены");
    }

    /** 10.105: инструменты пункта у сотрудника и одобрение владельцем (этап 3в «Сотрудники и пункты»). */
    static void staffChat106() {
        String u = "0F8FAD5B-D9CB-469F-A165-70867728950E", h = "5d41402abc4b2a76b9719d911017c592";
        eq("чат пункта 10.106: кому — принятый сотрудник с правом «Отвечает в чате»",
            IvaStaffChatPatch.want("active", new boolean[]{false, false, true, false}) + "|" + IvaStaffChatPatch.want("active", new boolean[]{true, true, false, true}) + "|"
            + IvaStaffChatPatch.want("invited", new boolean[]{true, true, true, true}) + "|" + IvaStaffChatPatch.want("active", null) + "|"
            + IvaStaffChatPatch.want("active", new boolean[]{true, true}), "true|false|false|false|false");
        eq("чат пункта 10.106: тело запроса — только uuid инструмента и md5-метка клиента",
            IvaStaffChatPatch.threadBody(u, h), "{\"p_tool\":\"0f8fad5b-d9cb-469f-a165-70867728950e\",\"p_peer\":\"" + h + "\"}");
        eq("чат пункта 10.106: почта, кавычки и чужое вместо метки — не отправляются",
            IvaStaffChatPatch.threadBody(u, "client@x.ru") + "|" + IvaStaffChatPatch.threadBody(u, h + "\"") + "|" + IvaStaffChatPatch.threadBody("1", h) + "|"
            + IvaStaffChatPatch.threadBody(null, null) + "|" + IvaStaffChatPatch.threadBody(u, h.toUpperCase()), "null|null|null|null|null");
        eq("чат пункта 10.106: ответ — текст экранирован, пробелы по краям убраны",
            IvaStaffChatPatch.sendBody(u, h, "  Приходите \"к 10\"\nдо свидания \\ "),
            "{\"p_tool\":\"0f8fad5b-d9cb-469f-a165-70867728950e\",\"p_peer\":\"" + h + "\",\"p_text\":\"Приходите \\\"к 10\\\"\\u000aдо свидания \\\\\"}");
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 2001; i++) big.append('а');
        eq("чат пункта 10.106: пустое и длиннее 2000 — не отправляем, подсказка как у базы",
            IvaStaffChatPatch.sendBody(u, h, "  ") + "|" + IvaStaffChatPatch.sendBody(u, h, big.toString()) + "|" + IvaStaffChatPatch.textError(" ") + "|"
            + IvaStaffChatPatch.textError(big.toString()) + "|" + IvaStaffChatPatch.textError(big.substring(1)).isEmpty(),
            "null|null|Напишите сообщение|Сообщение длиннее 2000 знаков — разбейте на части|true");
        eq("чат пункта 10.106: подписи — «Прокат:» в списке, «Вы» / «Прокат» над ответами, у клиента пусто",
            IvaStaffChatPatch.preview("Да,\n свободна", true) + "|" + IvaStaffChatPatch.preview("Есть?", false) + "|" + IvaStaffChatPatch.whoText(true, true) + "|"
            + IvaStaffChatPatch.whoText(true, false) + "|" + IvaStaffChatPatch.whoText(false, true), "Прокат: Да, свободна|Есть?|Вы|Прокат|");
        eq("чат пункта 10.106: счётчик, заголовок переписки, голосовое",
            IvaStaffChatPatch.unreadText(0) + "|" + IvaStaffChatPatch.unreadText(3) + "|" + IvaStaffChatPatch.unreadText(150) + "|"
            + IvaStaffChatPatch.subTitle("Дрель", "Склад") + "|" + IvaStaffChatPatch.subTitle("", "Склад") + "|" + IvaStaffChatPatch.voiceText(72) + "|" + IvaStaffChatPatch.voiceText(0),
            "|3|99+|Дрель · Склад|Склад|🎤 Голосовое · 1:12 — нажмите, чтобы послушать|🎤 Голосовое — нажмите, чтобы послушать");
        String[] pt = IvaStaffChatPatch.parseTarget(IvaStaffChatPatch.target(u, h));
        eq("чат пункта 10.106: цель уведомления — туда и обратно, мусор — null",
            pt[0] + "|" + pt[1] + "|" + IvaStaffChatPatch.parseTarget("x|y") + "|" + IvaStaffChatPatch.parseTarget(null) + "|" + IvaStaffChatPatch.target(u, "a@b"),
            u + "|" + h + "|null|null|");
        IvaStaffChatPatch.Chat c1 = new IvaStaffChatPatch.Chat(), c2 = new IvaStaffChatPatch.Chat(), c3 = new IvaStaffChatPatch.Chat();
        c1.unread = 2; c1.lastClient = "15";
        c2.unread = 1; c2.lastMine = true; c2.lastClient = "9";
        c3.unread = 0; c3.lastClient = "7";
        java.util.List<IvaStaffChatPatch.Chat> cs = new java.util.ArrayList<IvaStaffChatPatch.Chat>();
        cs.add(c1); cs.add(c2); cs.add(c3);
        eq("чат пункта 10.106: уведомлять — только где клиент написал последним и не прочитано",
            IvaStaffChatPatch.notifyIds(cs) + "|" + IvaStaffChatPatch.notifyIds(null), "[15]|[]");
        eq("чат пункта 10.106: текст уведомления",
            IvaStaffChatPatch.noteTitle(1, "Дрель Makita") + "|" + IvaStaffChatPatch.noteTitle(3, "x") + "|" + IvaStaffChatPatch.noteTitle(1, "") + "|"
            + IvaStaffChatPatch.noteText(1, "Анна", "Свободна\nли?") + "|" + IvaStaffChatPatch.noteText(1, "", "Да") + "|" + IvaStaffChatPatch.noteText(2, "Анна", "x"),
            "Новое сообщение · Дрель Makita|Новые сообщения: 3|Новое сообщение|Анна: Свободна ли?|Клиент: Да|Откройте «Чат пункта»");
        eq("чат пункта 10.106: ошибки базы — фразами, мат — фразой «Цензуры», без SQL — подсказка",
            IvaStaffChatPatch.dbError("400", "{\"message\":\"Переписка не найдена в вашем пункте\"}") + "|"
            + IvaStaffChatPatch.dbError("400", "{\"message\":\"В сообщении есть нецензурные слова. Уберите их, пожалуйста.\"}") + "|"
            + IvaStaffChatPatch.dbError("404", "{\"code\":\"PGRST202\"}") + "|" + IvaStaffChatPatch.dbError("200", "[]") + "|" + IvaStaffChatPatch.dbError("", ""),
            "Переписка не найдена в вашем пункте|В сообщении есть нецензурные слова. Уберите их, пожалуйста.|" + IvaStaffChatPatch.NO_DB + "||Не получилось — нет связи с сервером");
        eq("чат пункта 10.106: id уведомления и ключи — свои (не пересекаются с 10.104 / 10.105)",
            IvaStaffChatPatch.ID_NOTE + "|" + (IvaStaffChatPatch.ID_NOTE != IvaStaffOrdersPatch.ID_NOTE && IvaStaffChatPatch.ID_NOTE != IvaStaffToolsPatch.ID_NOTE) + "|"
            + (!IvaStaffChatPatch.OPEN.equals(IvaStaffOrdersPatch.OPEN) && !IvaStaffChatPatch.OPEN.equals(IvaStaffToolsPatch.OPEN)), "7207|true|true");
    }

    /** 10.107: «кто выдал / кто принял», «Сводка по пунктам», имя сотрудника в чате владельца (этап 4). */
    @SuppressWarnings("unchecked")
    static void realtime112() {
        // разбор JSON без org.json
        Object j = IvaRealtimePatch.Json.parse(" {\"a\":[1,2.5,-3e2,true,false,null],\"b\":{\"c\":\"x\\\"y\\\\z\\n\\u0416\"},\"d\":[]} ");
        java.util.Map<String, Object> jm = (java.util.Map<String, Object>) j;
        String bad = "";
        for (String b : new String[]{"", "{", "{\"a\":}", "[1,]x", "\"abc", "{\"a\":1}}"}) {
            try { IvaRealtimePatch.Json.parse(b); bad += "!" + b; } catch (RuntimeException e) { bad += "."; }
        }
        eq("realtime 10.112: разбор JSON — числа, строки с экранированием, вложенные, ошибки",
            jm.get("a") + "|" + ((java.util.Map<String, Object>) jm.get("b")).get("c") + "|" + jm.get("d") + "|" + bad,
            "[1, 2.5, -300.0, true, false, null]|x\"y\\z\nЖ|[]|......");
        // сообщения Phoenix 1.0.0 — как у supabase-js
        java.util.Map<String, Object> jn = (java.util.Map<String, Object>) IvaRealtimePatch.Json.parse(IvaRealtimePatch.joinMsg(" Ivan@Mail.RU ", "tok\"1"));
        java.util.Map<String, Object> pl = (java.util.Map<String, Object>) jn.get("payload");
        java.util.Map<String, Object> cf = (java.util.Map<String, Object>) pl.get("config");
        java.util.Map<String, Object> pc = (java.util.Map<String, Object>) ((java.util.List<Object>) cf.get("postgres_changes")).get(0);
        eq("realtime 10.112: вход в канал — своя строка iva_role_ping, ключ входа, не приватный",
            jn.get("topic") + "|" + jn.get("event") + "|" + jn.get("ref") + "|" + jn.get("join_ref") + "|" + pc.get("event") + "|" + pc.get("schema") + "|"
            + pc.get("table") + "|" + pc.get("filter") + "|" + cf.get("private") + "|" + pl.get("access_token"),
            "realtime:iva_role|phx_join|1|1|*|public|iva_role_ping|email=eq.ivan@mail.ru|false|tok\"1");
        java.util.Map<String, Object> hb = (java.util.Map<String, Object>) IvaRealtimePatch.Json.parse(IvaRealtimePatch.heartbeatMsg(7));
        java.util.Map<String, Object> tk = (java.util.Map<String, Object>) IvaRealtimePatch.Json.parse(IvaRealtimePatch.tokenMsg("new", 8));
        eq("realtime 10.112: heartbeat и новый ключ (10.69)",
            hb.get("topic") + "|" + hb.get("event") + "|" + hb.get("ref") + "|" + tk.get("topic") + "|" + tk.get("event") + "|"
            + ((java.util.Map<String, Object>) tk.get("payload")).get("access_token") + "|" + IvaRealtimePatch.path("k") + "|" + IvaRealtimePatch.host(),
            "phoenix|heartbeat|7|realtime:iva_role|access_token|new|/realtime/v1/websocket?apikey=k&vsn=1.0.0|wdxdeatphizclskfmfxi.supabase.co");
        String T = "\"topic\":\"realtime:iva_role\"";
        eq("realtime 10.112: ответы сервера — подписка, событие, ошибки, чужое",
            IvaRealtimePatch.classify("{\"event\":\"phx_reply\",\"payload\":{\"response\":{\"postgres_changes\":[{\"id\":5,\"event\":\"*\"}]},\"status\":\"ok\"},\"ref\":\"1\"," + T + "}") + ","
            + IvaRealtimePatch.classify("{\"event\":\"phx_reply\",\"payload\":{\"response\":{\"reason\":\"bad\"},\"status\":\"error\"},\"ref\":\"1\"," + T + "}") + ","
            + IvaRealtimePatch.classify("{\"event\":\"postgres_changes\",\"payload\":{\"data\":{\"type\":\"UPDATE\"},\"ids\":[5]},\"ref\":null," + T + "}") + ","
            + IvaRealtimePatch.classify("{\"event\":\"system\",\"payload\":{\"extension\":\"postgres_changes\",\"status\":\"ok\"},\"ref\":null," + T + "}") + ","
            + IvaRealtimePatch.classify("{\"event\":\"system\",\"payload\":{\"message\":\"Token has expired\",\"status\":\"error\"},\"ref\":null," + T + "}") + ","
            + IvaRealtimePatch.classify("{\"event\":\"phx_close\",\"payload\":{},\"ref\":\"1\"," + T + "}") + ","
            + IvaRealtimePatch.classify("{\"event\":\"phx_reply\",\"payload\":{\"response\":{},\"status\":\"ok\"},\"ref\":\"3\",\"topic\":\"phoenix\"}") + ","
            + IvaRealtimePatch.classify("{\"event\":\"phx_reply\",\"payload\":{\"response\":{},\"status\":\"ok\"},\"ref\":\"4\"," + T + "}") + ","
            + IvaRealtimePatch.classify("{\"event\":\"postgres_changes\",\"payload\":{},\"topic\":\"realtime:other\"}") + ","
            + IvaRealtimePatch.classify("не json"),
            "1,2,3,4,5,6,0,0,0,0");
        eq("realtime 10.112: переподключение — 2 с, 5 с, 15 с … не реже 5 минут",
            IvaRealtimePatch.retryMs(0) + "," + IvaRealtimePatch.retryMs(1) + "," + IvaRealtimePatch.retryMs(2) + "," + IvaRealtimePatch.retryMs(3) + ","
            + IvaRealtimePatch.retryMs(6) + "," + IvaRealtimePatch.retryMs(50),
            "2000,2000,5000,15000,300000,300000");
        String acc;
        try { acc = IvaRealtimePatch.acceptFor("dGhlIHNhbXBsZSBub25jZQ=="); } catch (Exception e) { acc = e.toString(); }
        eq("realtime 10.112: WebSocket — ключ из RFC 6455 и base64",
            acc + "|" + IvaRealtimePatch.b64(new byte[0]) + "|" + IvaRealtimePatch.b64("f".getBytes()) + "|" + IvaRealtimePatch.b64("fo".getBytes()) + "|" + IvaRealtimePatch.b64("foobar".getBytes()),
            "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=||Zg==|Zm8=|Zm9vYmFy");
        eq("realtime 10.112: права сотрудника поменялись — страницы пункта открываются заново",
            IvaAccessPatch.rightsChanged(new boolean[]{true, true, false, false}, false, new boolean[]{true, true, false, false}, false) + ","
            + IvaAccessPatch.rightsChanged(new boolean[]{true, true, false, false}, false, new boolean[]{true, false, false, false}, false) + ","
            + IvaAccessPatch.rightsChanged(new boolean[]{true, true, false, false}, false, new boolean[]{true, true, false, false}, true),
            "false,true,true");
        eq("realtime 10.112: WebSocket с настоящим сервером (кадры, маска, ping, части, 126, закрытие)", wsStand(true), "ok");
        eq("realtime 10.112: WebSocket — чужой ответ на рукопожатие не принимается", wsStand(false), "отказ");
    }

    /** Маленький WebSocket-сервер на 127.0.0.1: принимает рукопожатие, читает кадр клиента (с маской), шлёт ping,
     *  сообщение из двух частей, длинное (126) и закрывает. good=false — неверный Sec-WebSocket-Accept. */
    static String wsStand(final boolean good) {
        try {
            final java.net.ServerSocket ss = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
            final String[] got = {"", "", ""};
            Thread srv = new Thread(new Runnable() {
                @Override public void run() {
                    try (java.net.Socket c = ss.accept()) {
                        c.setSoTimeout(5000);
                        java.io.InputStream in = c.getInputStream();
                        java.io.OutputStream out = c.getOutputStream();
                        StringBuilder h = new StringBuilder();
                        while (!h.toString().endsWith("\r\n\r\n")) { int b = in.read(); if (b < 0) return; h.append((char) b); }
                        got[0] = h.substring(0, h.indexOf("\r\n"));
                        String key = "";
                        for (String l : h.toString().split("\r\n")) if (l.toLowerCase().startsWith("sec-websocket-key:")) key = l.substring(18).trim();
                        String acc = good ? IvaRealtimePatch.acceptFor(key) : "AAAA";
                        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + acc + "\r\n\r\n").getBytes("US-ASCII"));
                        out.flush();
                        if (!good) return;
                        got[1] = new String(frame(in, null), "UTF-8");          // текст клиента
                        out.write(new byte[]{(byte) 0x89, 2, 'h', 'i'});         // ping «hi»
                        out.write(new byte[]{(byte) 0x01, 3, 'a', 'b', 'c'});    // текст, часть 1
                        out.write(new byte[]{(byte) 0x80, 3, 'd', 'e', 'f'});    // часть 2 (fin)
                        byte[] big = new byte[300];
                        java.util.Arrays.fill(big, (byte) 'x');
                        out.write(new byte[]{(byte) 0x81, 126, 1, 44});
                        out.write(big);
                        out.flush();
                        int[] op = new int[1];
                        byte[] pong = frame(in, op);
                        got[2] = op[0] + ":" + new String(pong, "UTF-8");
                        out.write(new byte[]{(byte) 0x88, 0});
                        out.flush();
                        Thread.sleep(200);
                    } catch (Throwable e) { got[2] = "ошибка сервера " + e; }
                }
            });
            srv.start();
            String res;
            IvaRealtimePatch.Ws ws = null;
            try {
                ws = IvaRealtimePatch.Ws.open("127.0.0.1", ss.getLocalPort(), false, "/realtime/v1/websocket?apikey=k&vsn=1.0.0");
                ws.text("{\"привет\":1}");
                String m1 = ws.read(3000), m2 = ws.read(3000);
                String end;
                try { ws.read(3000); end = "нет закрытия"; } catch (java.io.EOFException e) { end = "закрыто"; }
                srv.join(3000);
                res = "abcdef".equals(m1) && m2 != null && m2.length() == 300 && "закрыто".equals(end)
                    && "GET /realtime/v1/websocket?apikey=k&vsn=1.0.0 HTTP/1.1".equals(got[0]) && "{\"привет\":1}".equals(got[1]) && "10:hi".equals(got[2])
                    ? "ok" : m1 + "|" + (m2 == null ? null : m2.length()) + "|" + end + "|" + got[0] + "|" + got[1] + "|" + got[2];
            } catch (java.io.IOException e) {
                res = good ? "ошибка " + e : "отказ";
            } finally {
                if (ws != null) ws.close();
                ss.close();
            }
            return res;
        } catch (Throwable e) { return "ошибка " + e; }
    }

    /** Кадр клиента: обязательно с маской (RFC 6455) — иначе ошибка. */
    static byte[] frame(java.io.InputStream in, int[] op) throws java.io.IOException {
        int b0 = in.read(), b1 = in.read();
        if (b0 < 0 || b1 < 0) throw new java.io.EOFException();
        if ((b1 & 0x80) == 0) throw new java.io.IOException("кадр клиента без маски");
        if (op != null) op[0] = b0 & 0x0f;
        int len = b1 & 0x7f;
        if (len == 126) len = in.read() << 8 | in.read();
        byte[] mask = new byte[4];
        for (int i = 0; i < 4; i++) mask[i] = (byte) in.read();
        byte[] d = new byte[len];
        for (int i = 0; i < len; i++) d[i] = (byte) (in.read() ^ mask[i & 3]);
        return d;
    }

    static void summary111() {
        eq("сводка 10.111: показываются только настоящие пункты, «Без пункта» (пустой id) — нет",
            IvaStaffJournalPatch.realPoint("6f1c2a3b-0000-4000-8000-000000000001") + "," + IvaStaffJournalPatch.realPoint("") + ","
            + IvaStaffJournalPatch.realPoint("  ") + "," + IvaStaffJournalPatch.realPoint(null) + "," + IvaStaffJournalPatch.realPoint("null"),
            "true,false,false,false,false");
    }

    static void access110() {
        java.util.List<String[]> rows = new java.util.ArrayList<String[]>();
        String pre = String.valueOf(IvaAccessPatch.adminFromPairs(rows, "a@b.ru"));
        rows.add(new String[]{"Other@b.ru", null});
        rows.add(new String[]{" A@B.ru ", null});
        String live = String.valueOf(IvaAccessPatch.adminFromPairs(rows, "a@b.ru"));
        rows.set(1, new String[]{"a@b.ru", "2026-10-17T10:00:00+00:00"});
        String gone = String.valueOf(IvaAccessPatch.adminFromPairs(rows, "a@b.ru"));
        java.util.List<String[]> old = new java.util.ArrayList<String[]>();
        old.add(new String[]{"a@b.ru"});                              // нет столбца removed_at — запасной запрос
        eq("доступ 10.110: строка admins — админ; удалён или нет строки — клиент; почта без регистра",
            pre + "," + live + "," + gone + "," + IvaAccessPatch.adminFromPairs(old, "a@b.ru") + ","
            + IvaAccessPatch.adminFromPairs(rows, "") + "," + IvaAccessPatch.adminFromPairs(null, "a@b.ru") + ","
            + IvaAccessPatch.adminFromRows("{\"code\":\"401\"}", "a@b.ru") + "," + IvaAccessPatch.removedEmpty("null") + "," + IvaAccessPatch.removedEmpty(" "),
            "false,true,false,true,null,null,null,true,true");
        eq("доступ 10.110: снять / вернуть — только по ответу базы, создателя не трогаем",
            IvaAccessPatch.adminChange(false, true, Boolean.FALSE) + "," + IvaAccessPatch.adminChange(false, false, Boolean.TRUE) + ","
            + IvaAccessPatch.adminChange(false, true, Boolean.TRUE) + "," + IvaAccessPatch.adminChange(false, false, Boolean.FALSE) + ","
            + IvaAccessPatch.adminChange(false, true, null) + "," + IvaAccessPatch.adminChange(true, true, Boolean.FALSE),
            "-1,1,0,0,0,0");
        eq("доступ 10.110: нет столбца removed_at (SQL 20261102 не применён) — спросить без него; прочие ошибки — нет",
            IvaAccessPatch.missingColumn("400", "{\"code\":\"42703\",\"message\":\"column admins.removed_at does not exist\"}") + ","
            + IvaAccessPatch.missingColumn("401", "{\"code\":\"PGRST301\"}") + "," + IvaAccessPatch.missingColumn("500", "removed_at 42703") + ","
            + IvaAccessPatch.missingColumn(null, null),
            "true,false,false,false");
        eq("доступ 10.110: со страниц админа — в профиль, профиль / заявки / чат — перерисовать, остальное — на месте",
            IvaAccessPatch.adminTab("addtool") + "," + IvaAccessPatch.adminTab("admList") + "," + IvaAccessPatch.adminTab("admPays") + ","
            + IvaAccessPatch.adminTab("orders") + "," + IvaAccessPatch.adminTab("home") + "," + IvaAccessPatch.adminTab(null) + "|"
            + IvaAccessPatch.redrawTab("orders") + "," + IvaAccessPatch.redrawTab("chat") + "," + IvaAccessPatch.redrawTab("profile") + ","
            + IvaAccessPatch.redrawTab("detail") + "|" + IvaAccessPatch.staffTab("tooledit") + "," + IvaAccessPatch.staffTab("orders"),
            "true,true,true,false,false,false|true,true,true,false|true,false");
        eq("доступ 10.110: сотрудник — убрали / отозвали приглашение / новое / без изменений",
            IvaAccessPatch.staffChange("active", "") + "," + IvaAccessPatch.staffChange("active", "declined") + ","
            + IvaAccessPatch.staffChange("invited", "") + "," + IvaAccessPatch.staffChange("", "invited") + ","
            + IvaAccessPatch.staffChange("invited", "active") + "," + IvaAccessPatch.staffChange("active", "active") + ","
            + IvaAccessPatch.staffChange("", "") + "," + IvaAccessPatch.staffChange(null, null),
            "1,1,2,3,3,0,0,0");
        eq("доступ 10.110: сотрудника спрашиваем каждые 30 с, клиента — тоже раз в 30 с (10.112), не чаще",
            IvaAccessPatch.staffDue("active", 1000, 2000) + "," + IvaAccessPatch.staffDue("invited", 1000, 2000) + ","
            + IvaAccessPatch.staffDue("", 1000, 2000) + "," + IvaAccessPatch.staffDue("", 1000, 31000) + "," + IvaAccessPatch.staffDue("", 0, 5),
            "true,true,false,true,true");
        String g = IvaAccessPatch.staffGoneText("Прокат «Сила»"), g0 = IvaAccessPatch.staffGoneText(" ");
        eq("доступ 10.110: тексты — понятно, что осталось клиенту",
            g.contains("к прокату «Прокат «Сила»» закрыт") && g.contains("возможности клиента") && g0.startsWith("Доступ сотрудника закрыт —")
            && IvaAccessPatch.ADMIN_GONE_TEXT.contains("возможности клиента") && "Понятно".equals(IvaAccessPatch.OK)
            && IvaAccessPatch.ADMIN_PATH.contains("select=email,removed_at&email=ilike.") && !IvaAccessPatch.ADMIN_PATH.contains("*")
            && IvaAccessPatch.TAB_EVERY_MS == 8000L && IvaAccessPatch.STAFF_IDLE_MS == 30000L, true);
    }

    static void avatarEdit109() {
        eq("аватар 10.109: окно выбора — системный выбор фото, галерея; проводник — только запасной",
            String.join(",", IvaPhotoPatch.galleryActions(true)) + "|" + String.join(",", IvaPhotoPatch.galleryActions(false)) + "|" + IvaPhotoPatch.REQ_EDIT_NATIVE,
            "android.provider.action.PICK_IMAGES,android.intent.action.PICK,android.intent.action.GET_CONTENT|android.intent.action.PICK,android.intent.action.GET_CONTENT|5402");
        int[] r0 = {10, 20, 30}, r1 = IvaAvatarPatch.rotRect90(r0, 100, 80), r2 = IvaAvatarPatch.rotRect90(r1, 80, 100),
            r3 = IvaAvatarPatch.rotRect90(r2, 100, 80), r4 = IvaAvatarPatch.rotRect90(r3, 80, 100);
        eq("аватар 10.109: «Повернуть» — круг остаётся на той же части фото, 4 поворота — как было",
            java.util.Arrays.toString(r1) + "|" + java.util.Arrays.toString(r4) + "|" + IvaAvatarPatch.rotRect90(null, 1, 1) + "|"
            + IvaAvatarPatch.nextRot(0) + "," + IvaAvatarPatch.nextRot(90) + "," + IvaAvatarPatch.nextRot(180) + "," + IvaAvatarPatch.nextRot(270),
            "[30, 10, 30]|[10, 20, 30]|null|90,180,270,0");
        float[] c = IvaAvatarPatch.circleOf(new int[]{10, 20, 30}, 5, 6, 2f);
        int[] back = IvaAvatarPatch.srcRect(c[0], c[1], c[2], 5, 6, 2f, 100, 80);
        eq("аватар 10.109: «Редактировать» — круг там же, где был (и сохраняется тот же квадрат)",
            Math.round(c[0]) + "," + Math.round(c[1]) + "," + Math.round(c[2]) + "|" + java.util.Arrays.toString(back), "55,76,30|[10, 20, 30]");
        eq("аватар 10.109: исходник — свой у каждого аккаунта и вида аватарки",
            IvaAvatarPatch.slot("me", " Ivan@Mail.RU ") + "|" + IvaAvatarPatch.slot("shop", "ivan@mail.ru") + "|" + IvaAvatarPatch.slot("me", "")
            + "|" + IvaAvatarPatch.slot("me", null) + "|" + IvaAvatarPatch.slot("me", "null"),
            "me:ivan@mail.ru|shop:ivan@mail.ru|||");
        long[] u = IvaAvatarPatch.unpackSaved(IvaAvatarPatch.packSaved(270, new int[]{12, 0, 640}, 0x8000000000000001L));
        eq("аватар 10.109: запись об исходнике — туда и обратно, испорченная — не берётся",
            java.util.Arrays.toString(u) + "|" + IvaAvatarPatch.unpackSaved("45|1,2,3|ff") + "|" + IvaAvatarPatch.unpackSaved("90|1,2,0|ff") + "|"
            + IvaAvatarPatch.unpackSaved("90|1,2|ff") + "|" + IvaAvatarPatch.unpackSaved("") + "|" + IvaAvatarPatch.unpackSaved(null) + "|"
            + IvaAvatarPatch.unpackSaved("x|1,2,3|ff"),
            "[270, 12, 0, 640, -9223372036854775807]|null|null|null|null|null|null");
        int[] g = new int[64], g2 = new int[64], inv = new int[64];
        for (int i = 0; i < 64; i++) {
            int v = (i % 8) * 30 + (i / 8) * 4, v2 = Math.min(255, v + ((i * 7) % 5)), w = 255 - v;
            g[i] = 0xff000000 | v << 16 | v << 8 | v;
            g2[i] = 0xff000000 | v2 << 16 | v2 << 8 | v2;
            inv[i] = 0xff000000 | w << 16 | w << 8 | w;
        }
        long h = IvaAvatarPatch.fingerprint(g);
        eq("аватар 10.109: отпечаток — та же картинка после сжатия совпадает, другая — нет",
            (h != 0L) + "|" + IvaAvatarPatch.samePhoto(h, IvaAvatarPatch.fingerprint(g2)) + "|" + IvaAvatarPatch.samePhoto(h, IvaAvatarPatch.fingerprint(inv))
            + "|" + IvaAvatarPatch.fingerprint(null) + "|" + IvaAvatarPatch.fingerprint(new int[3]),
            "true|true|false|0|0");
        eq("аватар 10.109: кнопки и размеры", IvaAvatarPatch.EDIT_THIS + "|" + IvaAvatarPatch.PICK_OTHER + "|" + IvaAvatarPatch.ROTATE + "|"
            + IvaAvatarPatch.KEEP_PX + "|" + IvaAvatarPatch.SAME_BITS, "Редактировать|Выбрать другое фото|Повернуть|1600|10");
    }

    static void adminManage108() {
        eq("админы 10.108: почта нового админа — как проверяет база",
            IvaAdminsPatch.emailErr(" Ivan@Mail.ru ", "eclips.ru@mail.ru").isEmpty() + "|" + IvaAdminsPatch.emailErr("", "x") + "|"
            + IvaAdminsPatch.emailErr("ivan.mail.ru", "x") + "|" + IvaAdminsPatch.emailErr("iv an@mail.ru", "x") + "|"
            + IvaAdminsPatch.emailErr("ivan@mail.r", "x") + "|" + IvaAdminsPatch.emailErr("Eclips.RU@mail.ru ", " eclips.ru@mail.ru") + "|"
            + IvaAdminsPatch.emailErr(null, null),
            "true|Введите почту нового админа|Похоже, в email ошибка (пример: ivan@mail.ru)|Похоже, в email ошибка (пример: ivan@mail.ru)|"
            + "Похоже, в email ошибка (пример: ivan@mail.ru)|Это ваша почта — вы уже владелец площадки|Введите почту нового админа");
        eq("админы 10.108: ответ базы — фраза базы, без SQL 10.108 — «обновите базу», нет права, нет связи",
            IvaAdminsPatch.msgOf("200", "{}") + "|" + IvaAdminsPatch.msgOf("404", "{\"code\":\"PGRST202\"}") + "|"
            + IvaAdminsPatch.msgOf("400", "{\"code\":\"P0001\",\"message\":\"\\u042d\\u0442\\u043e\\u0442 \\u0447\\u0435\\u043b\\u043e\\u0432\\u0435\\u043a \\u0443\\u0436\\u0435 \\u0430\\u0434\\u043c\\u0438\\u043d\"}") + "|"
            + IvaAdminsPatch.msgOf("400", "{\"message\":\"Админ не найден — возможно, его уже удалили\"}") + "|"
            + IvaAdminsPatch.msgOf("403", "{\"code\":\"42501\",\"message\":\"permission denied\"}") + "|"
            + IvaAdminsPatch.msgOf("", "") + "|" + IvaAdminsPatch.msgOf("500", "oops"),
            "|Заработает после обновления базы (SQL 10.108)|Этот человек уже админ|Админ не найден — возможно, его уже удалили|"
            + "Нет права — войдите заново|Не получилось — нет связи с сервером|Не получилось (ответ 500)");
        eq("админы 10.108: панель — прячем почту над «ПАНЕЛЬ ВЛАДЕЛЬЦА» и строку под ним, остальное не трогаем",
            java.util.Arrays.toString(IvaAdminsPatch.panelHide(new String[]{"eclips.ru@mail.ru", "ПАНЕЛЬ ВЛАДЕЛЬЦА", "Инструментов: 5 (забронировано 0, в аренде 1)\nЗаявки: новых 1", null, null}, "Eclips.ru@mail.ru"))
            + "|" + java.util.Arrays.toString(IvaAdminsPatch.panelHide(new String[]{"Другой текст", "ПАНЕЛЬ ВЛАДЕЛЬЦА", null}, "eclips.ru@mail.ru"))
            + "|" + java.util.Arrays.toString(IvaAdminsPatch.panelHide(new String[]{"eclips.ru@mail.ru", "Загружаю статистику…"}, "eclips.ru@mail.ru"))
            + "|" + IvaAdminsPatch.panelHide(null, null).length,
            "[true, false, true, false, false]|[false, false, false]|[false, false]|0");
        eq("админы 10.108: список — «(отключён)» → «(приостановлен)», удалённые — по ответу базы",
            IvaAdminsPatch.rowTitle("Иван (отключён)") + "|" + IvaAdminsPatch.rowTitle("Иван — владелец") + "|" + IvaAdminsPatch.rowTitle(null) + "|"
            + new java.util.TreeSet<String>(IvaAdminsPatch.parseRemoved("[\"A@x.ru\", \"b@x.ru\"]")) + "|" + IvaAdminsPatch.parseRemoved("[]").size() + "|"
            + IvaAdminsPatch.parseRemoved("{\"message\":\"x\"}").size() + "|" + IvaAdminsPatch.parseRemoved(null).size(),
            "Иван (приостановлен)|Иван — владелец|null|[a@x.ru, b@x.ru]|0|0|0");
        eq("админы 10.108: карточка «Доступ» — состояние",
            IvaAdminsPatch.stateText(true, 0) + "|" + IvaAdminsPatch.stateText(false, 3) + "|" + IvaAdminsPatch.stateText(false, 0),
            "Сейчас: активен|Сейчас: приостановлен · скрыто инструментов: 3|Сейчас: приостановлен");
        eq("админы 10.108: адреса — только функции базы (и штатная вставка, если SQL 10.108 ещё нет)",
            IvaAdminsPatch.RPC_ADD + "|" + IvaAdminsPatch.RPC_PAUSE + "|" + IvaAdminsPatch.RPC_REMOVE + "|" + IvaAdminsPatch.RPC_STATE + "|" + IvaAdminsPatch.RPC_REMOVED + "|" + IvaAdminsPatch.NATIVE_INSERT,
            "/rest/v1/rpc/iva_admin_add|/rest/v1/rpc/iva_admin_pause|/rest/v1/rpc/iva_admin_remove|/rest/v1/rpc/iva_admin_state|/rest/v1/rpc/iva_admins_removed|/rest/v1/admins");
    }

    static void staffJournal107() {
        java.util.TimeZone nsk = java.util.TimeZone.getTimeZone("Asia/Novosibirsk");
        eq("журнал 10.107: когда показывать — выданная и закрытая часть с номером",
            IvaStaffJournalPatch.want("15", "rented") + "|" + IvaStaffJournalPatch.want(" 15 ", "closed") + "|" + IvaStaffJournalPatch.want("15", "new") + "|"
            + IvaStaffJournalPatch.want("", "closed") + "|" + IvaStaffJournalPatch.want("15;drop", "closed") + "|" + IvaStaffJournalPatch.want(null, null),
            "true|true|false|false|false|false");
        eq("журнал 10.107: время — в поясе телефона, «17.10.2026, 14:05»",
            IvaStaffJournalPatch.when("2026-10-17T07:05:31.123456+00:00", nsk) + "|" + IvaStaffJournalPatch.when("", nsk) + "|" + IvaStaffJournalPatch.when("abc", nsk),
            "17.10.2026, 14:05||");
        java.util.List<String[]> r1 = IvaStaffJournalPatch.rows("closed", "Иван", "2026-10-17T07:05:00+00:00", "Вы", "2026-10-18T03:00:00+00:00", nsk);
        java.util.List<String[]> r2 = IvaStaffJournalPatch.rows("closed", "", "", "Вы", "2026-10-18T03:00:00+00:00", nsk);
        java.util.List<String[]> r3 = IvaStaffJournalPatch.rows("rented", "ivan@x.ru", "2026-10-17T07:05:00+00:00", "", "", nsk);
        java.util.List<String[]> r4 = IvaStaffJournalPatch.rows("closed", "", "", "", "", nsk);
        java.util.List<String[]> r5 = IvaStaffJournalPatch.rows("rented", "", "2026-10-17T07:05:00+00:00", "Вы", "2026-10-18T03:00:00+00:00", nsk);
        eq("журнал 10.107: «Выдал» / «Принял», без выдачи — «Закрыл», пустое — не показываем",
            r1.size() + ":" + r1.get(0)[0] + "=" + r1.get(0)[1] + ";" + r1.get(1)[0] + "=" + r1.get(1)[1] + "|" + r2.size() + ":" + r2.get(0)[0] + "|"
            + r3.size() + ":" + r3.get(0)[1] + "|" + r4.size() + "|" + r5.size() + ":" + r5.get(0)[1],
            "2:Выдал=Иван · 17.10.2026, 14:05;Принял=Вы · 18.10.2026, 10:00|1:Закрыл|1:ivan@x.ru · 17.10.2026, 14:05|0|1:17.10.2026, 14:05");
        java.util.List<String[]> ms = new java.util.ArrayList<String[]>();
        ms.add(new String[]{"11", "Admin@X.ru"}); ms.add(new String[]{"12", "client@x.ru"}); ms.add(new String[]{"13", "admin@x.ru"});
        ms.add(new String[]{"", "admin@x.ru"}); ms.add(new String[]{"14\"]}", "admin@x.ru"}); ms.add(new String[]{"11", "admin@x.ru"}); ms.add(null);
        java.util.Set<String> asked = new java.util.HashSet<String>(java.util.Arrays.asList("13"));
        java.util.List<String> need = IvaStaffJournalPatch.needIds(ms, " admin@x.ru ", asked);
        eq("чат 10.107: спрашиваем только про свои новые сообщения, номера — без кавычек",
            need + "|" + IvaStaffJournalPatch.idsBody(need) + "|" + IvaStaffJournalPatch.needIds(ms, "", asked) + "|" + IvaStaffJournalPatch.idsBody(new java.util.ArrayList<String>()),
            "[11]|{\"p_ids\":[\"11\"]}|[]|{\"p_ids\":[]}");
        java.util.List<String[]> many = new java.util.ArrayList<String[]>();
        for (int i = 0; i < 350; i++) many.add(new String[]{String.valueOf(i + 1), "a@b.ru"});
        eq("чат 10.107: за раз — не больше 300 (база больше не примет)",
            IvaStaffJournalPatch.needIds(many, "a@b.ru", new java.util.HashSet<String>()).size() + "|" + IvaStaffJournalPatch.CHAT_BATCH, "300|300");
        eq("чат 10.107: имени нет — пусто (ответил сам владелец)", IvaStaffJournalPatch.who("999999") + "|" + IvaStaffJournalPatch.who(null), "|");
        eq("сводка 10.107: период — 7 / 30 дней / всё время, тело запроса",
            IvaStaffJournalPatch.daysBody(7) + "|" + IvaStaffJournalPatch.daysBody(0) + "|" + IvaStaffJournalPatch.daysBody(-5) + "|" + java.util.Arrays.toString(IvaStaffJournalPatch.DAYS)
            + "|" + String.join(",", IvaStaffJournalPatch.DAYS_TEXT),
            "{\"p_days\":7}|{\"p_days\":0}|{\"p_days\":0}|[7, 30, 0]|7 дней,30 дней,Всё время");
        eq("сводка 10.107: строки карточки пункта",
            IvaStaffJournalPatch.pointTitle("Склад", "Новосибирск") + "|" + IvaStaffJournalPatch.pointTitle("Бердск", "Бердск") + "|" + IvaStaffJournalPatch.pointTitle("", "") + "|"
            + IvaStaffJournalPatch.nowLine(2, 1) + "|" + IvaStaffJournalPatch.periodLine(30, 5, 3) + "|" + IvaStaffJournalPatch.periodLine(7, 1, 0) + "|"
            + IvaStaffJournalPatch.periodLine(0, 9, 8) + "|" + IvaStaffJournalPatch.periodLine(1, 0, 0) + "|" + IvaStaffJournalPatch.periodLine(22, 0, 0),
            "Склад · Новосибирск|Бердск|Пункт|Сейчас: новых 2 · выдано 1|За 30 дней: заявок 5 · закрыто 3|За 7 дней: заявок 1 · закрыто 0|"
            + "За всё время: заявок 9 · закрыто 8|За 1 день: заявок 0 · закрыто 0|За 22 дня: заявок 0 · закрыто 0");
        eq("сводка 10.107: деньги, инструменты, сотрудники, кто выдавал",
            IvaStaffJournalPatch.moneyLine("1200", "60") + "|" + IvaStaffJournalPatch.moneyLine("", "") + "|" + IvaStaffJournalPatch.toolsLine(4, 3, 1) + "|"
            + IvaStaffJournalPatch.toolsLine(2, 2, 0) + "|" + IvaStaffJournalPatch.staffLine(2, 1) + "|" + IvaStaffJournalPatch.staffLine(0, 0) + "|"
            + IvaStaffJournalPatch.personLine("Иван", 3, 2) + "|" + IvaStaffJournalPatch.personLine("", 1, 0),
            "Аренда: 1200 ₽ · комиссия: 60 ₽|Аренда: 0 ₽ · комиссия: 0 ₽|Инструменты: 4, клиенты видят 3, на одобрении 1|Инструменты: 2, клиенты видят 2|"
            + "Сотрудники: 2 (ещё приглашены: 1)|Сотрудники: 0|Иван — выдал 3, принял 2|Без имени — выдал 1, принял 0");
        eq("сводка 10.107: ошибки базы — понятным текстом",
            IvaStaffJournalPatch.dbError("400", "{\"message\":\"Только владелец проката\"}") + "|" + IvaStaffJournalPatch.dbError("404", "{\"code\":\"PGRST202\"}") + "|"
            + IvaStaffJournalPatch.dbError("401", "") + "|" + IvaStaffJournalPatch.dbError("200", "{}") + "|" + IvaStaffJournalPatch.dbError("", ""),
            "Только владелец проката|" + IvaStaffJournalPatch.NO_DB + "|Нет права — войдите заново||Не получилось — нет связи с сервером");
    }

    static void staffTools105() {
        String u = "0F8FAD5B-D9CB-469F-A165-70867728950E";
        eq("инструменты пункта 10.105: кому показывать — принятый сотрудник с правом «Выкладывает инструмент»",
            IvaStaffToolsPatch.want("active", new boolean[]{false, true, false, false}) + "|" + IvaStaffToolsPatch.want("active", new boolean[]{true, false, true, true}) + "|"
            + IvaStaffToolsPatch.want("invited", new boolean[]{true, true, true, true}) + "|" + IvaStaffToolsPatch.want("active", null) + "|"
            + IvaStaffToolsPatch.want("active", new boolean[]{false}), "true|false|false|false|false");
        eq("инструменты пункта 10.105: возврат после штатного успеха — только сотрудника и только из кабинета",
            IvaStaffToolsPatch.redirect("admhub", true, false) + "|" + IvaStaffToolsPatch.redirect("admhub", true, true) + "|"
            + IvaStaffToolsPatch.redirect("admhub", false, false) + "|" + IvaStaffToolsPatch.redirect("addtool", true, false) + "|"
            + IvaStaffToolsPatch.redirect(null, true, false), "true|false|false|false|false");
        eq("инструменты пункта 10.105: «Назад» с формы и правки — к своему списку",
            IvaStaffToolsPatch.backTab("addtool", true, false) + "|" + IvaStaffToolsPatch.backTab("tooledit", true, false) + "|"
            + IvaStaffToolsPatch.backTab("home", true, false) + "|" + IvaStaffToolsPatch.backTab("addtool", false, false) + "|"
            + IvaStaffToolsPatch.backTab("addtool", true, true), "true|true|false|false|false");
        eq("инструменты пункта 10.105: состояние словами",
            IvaStaffToolsPatch.statusText("pending", false) + "|" + IvaStaffToolsPatch.statusText("rejected", false) + "|"
            + IvaStaffToolsPatch.statusText("", true) + "|" + IvaStaffToolsPatch.statusText("", false) + "|" + IvaStaffToolsPatch.statusHint(""),
            "На одобрении|Отклонён|Опубликован|Скрыт|");
        eq("инструменты пункта 10.105: цена и пункт",
            IvaStaffToolsPatch.priceText("400") + "|" + IvaStaffToolsPatch.priceText("450.6") + "|" + IvaStaffToolsPatch.priceText("null") + "|"
            + IvaStaffToolsPatch.priceText("абв") + "|" + IvaStaffToolsPatch.priceText("-5") + "|" + IvaStaffToolsPatch.line2("300", "Склад") + "|"
            + IvaStaffToolsPatch.line2("", "Склад") + "|" + IvaStaffToolsPatch.line2("300", " "),
            "400 ₽/сутки|451 ₽/сутки||||300 ₽/сутки · Склад|Склад|300 ₽/сутки");
        eq("инструменты пункта 10.105: «Опубликовать» / «Отклонить» — только номер-uuid",
            IvaStaffToolsPatch.reviewBody(u, true) + "|" + IvaStaffToolsPatch.reviewBody(u, false) + "|" + IvaStaffToolsPatch.reviewBody("1; drop", true)
            + "|" + IvaStaffToolsPatch.reviewBody(null, true),
            "{\"p_id\":\"0f8fad5b-d9cb-469f-a165-70867728950e\",\"p_ok\":true}|{\"p_id\":\"0f8fad5b-d9cb-469f-a165-70867728950e\",\"p_ok\":false}|null|null");
        eq("инструменты пункта 10.105: режим «сразу / после одобрения»",
            IvaStaffToolsPatch.modeBody("now") + "|" + IvaStaffToolsPatch.modeBody("approve") + "|" + IvaStaffToolsPatch.modeBody("later") + "|"
            + IvaStaffToolsPatch.modeOf("\"now\"") + "|" + IvaStaffToolsPatch.modeOf(" \"approve\" ") + "|" + IvaStaffToolsPatch.modeOf("null") + "|"
            + IvaStaffToolsPatch.modeText("now") + "|" + IvaStaffToolsPatch.modeText("approve") + "|" + IvaStaffToolsPatch.modeText(""),
            "{\"p_mode\":\"now\"}|{\"p_mode\":\"approve\"}|null|now|approve||Публиковать сразу|После моего одобрения|");
        eq("инструменты пункта 10.105: строка и уведомление",
            IvaStaffToolsPatch.reviewRow(0) + "|" + IvaStaffToolsPatch.reviewRow(2) + "|" + IvaStaffToolsPatch.noteTitle(1) + "|" + IvaStaffToolsPatch.noteTitle(3) + "|"
            + IvaStaffToolsPatch.noteText(1, "Перфоратор", "Пётр") + "|" + IvaStaffToolsPatch.noteText(1, "Перфоратор", " ") + "|" + IvaStaffToolsPatch.noteText(2, "Перфоратор", "Пётр"),
            "На одобрении|На одобрении · 2|Инструмент на одобрение|Инструменты на одобрение: 3|Перфоратор · добавил(а) Пётр|Перфоратор|"
            + "Откройте «Настройка проката → На одобрении»");
        eq("инструменты пункта 10.105: ответы базы",
            IvaStaffToolsPatch.dbError("201", "") + "|"
            + IvaStaffToolsPatch.dbError("400", "{\"message\":\"Пункт выдачи — только из ваших пунктов\"}") + "|"
            + IvaStaffToolsPatch.dbError("400", "{\"message\":\"Инструмент уже не ждёт одобрения — обновите список\"}") + "|"
            + IvaStaffToolsPatch.dbError("404", "{\"code\":\"PGRST202\"}") + "|" + IvaStaffToolsPatch.dbError("403", "") + "|" + IvaStaffToolsPatch.dbError("", ""),
            "|Пункт выдачи — только из ваших пунктов|Инструмент уже не ждёт одобрения — обновите список|"
            + "Инструменты сотрудников заработают после обновления базы (SQL 10.105)|Нет права — войдите заново|Не получилось — нет связи с сервером");
        eq("инструменты пункта 10.105: свой новый инструмент сотрудник ищет по номеру сотрудника, владелец — по почте",
            IvaKindPatch.lookupPath("Admin@X.ru", "Дрель", "2026-10-17T10:00:00Z", u).startsWith("/rest/v1/tools?select=id,kind&staff_id=eq.0f8fad5b-d9cb-469f-a165-70867728950e&name=eq.")
            + "|" + IvaKindPatch.lookupPath("Admin@X.ru", "Дрель", "2026-10-17T10:00:00Z", "").startsWith("/rest/v1/tools?select=id,kind&owner_email=eq.admin%40x.ru&name=eq.")
            + "|" + IvaKindPatch.lookupPath("a@x.ru", "Дрель", "t", "не-uuid").contains("owner_email=eq.")
            + "|" + IvaKindPatch.lookupPath("a@x.ru", "Дрель", "t").equals(IvaKindPatch.lookupPath("a@x.ru", "Дрель", "t", "")), "true|true|true|true");
        eq("инструменты пункта 10.105: id уведомления не совпадает с другими", IvaStaffToolsPatch.ID_NOTE != IvaNotifyPatch.ID_ORD
            && IvaStaffToolsPatch.ID_NOTE != IvaNotifyPatch.ID_TEST && IvaStaffToolsPatch.ID_NOTE != IvaNotifyPatch.ID_UPD
            && IvaStaffToolsPatch.ID_NOTE != IvaNotifyPatch.ID_MSG && IvaStaffToolsPatch.ID_NOTE != IvaStaffOrdersPatch.ID_NOTE
            && !IvaStaffToolsPatch.OPEN.equals(IvaStaffOrdersPatch.OPEN), true);
        java.util.List<IvaStaffToolsPatch.Pending> pp = null;
        try { pp = IvaStaffToolsPatch.parsePending("not json"); } catch (Throwable e) { pp = null; }
        eq("инструменты пункта 10.105: ответ не массив — пустой список", pp != null && pp.isEmpty(), true);
    }

    static String rowsText(java.util.List<String[]> rows) {
        StringBuilder b = new StringBuilder();
        for (String[] r : rows) b.append(r[0]).append('=').append(r[1]).append(';');
        return b.toString();
    }

    /** 10.113: «Вид раздела с логотипом»; «Инструменты сотрудников» — у каждого пункта; без email пункта. */
    static void logo113() {
        eq("логотип 10.113: вид по умолчанию — админ «как у админов», остальные «как у клиентов»; выбор сохраняется",
            IvaLogoPatch.norm("", true) + "|" + IvaLogoPatch.norm(null, false) + "|" + IvaLogoPatch.norm(" client ", true) + "|"
                + IvaLogoPatch.norm("admin", false) + "|" + IvaLogoPatch.norm("чужое", true),
            "admin|client|client|admin|admin");
        eq("логотип 10.113: что открывает — гость штатно, админ по выбору, сотрудник «Каталог» по выбору, клиент — раздел клиента",
            IvaLogoPatch.target(false, false, false, "client") + "" + IvaLogoPatch.target(false, true, false, "client")
                + IvaLogoPatch.target(true, true, false, "admin") + IvaLogoPatch.target(true, true, false, "client")
                + IvaLogoPatch.target(true, true, true, "client") + IvaLogoPatch.target(true, false, true, "admin")
                + IvaLogoPatch.target(true, false, true, "client") + IvaLogoPatch.target(true, false, false, "admin"),
            "00011211");
        eq("логотип 10.113: строка «Мои инструменты проката» — только админу с видом «как у клиентов»",
            IvaLogoPatch.cabinetRow(true, true, "client") + "|" + IvaLogoPatch.cabinetRow(true, true, "admin") + "|"
                + IvaLogoPatch.cabinetRow(true, false, "client") + "|" + IvaLogoPatch.cabinetRow(false, true, "client"),
            "true|false|false|false");
        eq("логотип 10.113: подписи — два образца и вкладки",
            IvaLogoPatch.sub("client") + " / " + IvaLogoPatch.sub("admin") + " / " + IvaLogoPatch.TITLE,
            "Как у клиентов: «Избранное» и «Мои инструменты» / Как у админов: «Мой инструмент» и «Добавить инструмент» / Вид раздела с логотипом");
        IvaPickupPatch.Point p = new IvaPickupPatch.Point();
        p.name = " Склад  на Ленина "; p.address = "Новосибирск, Ленина, 5"; p.email = "old@x.ru"; p.tel = "+7 900 123-45-67";
        String b1 = IvaPickupPatch.body(p, true, true), b0 = IvaPickupPatch.body(p, false, true);
        p.publish = "now";
        String b2 = IvaPickupPatch.body(p, false, true);
        eq("пункт 10.113: запись — без email, с выключателем (по умолчанию «после одобрения»), телефон — если есть в базе",
            !b1.contains("email") + "|" + b1.contains(",\"tel\":\"+7 900 123-45-67\",\"staff_publish\":\"approve\"}") + "|" + b0.contains("tel")
                + "|" + b2.endsWith(",\"staff_publish\":\"now\"}") + "|" + b1.startsWith("{\"name\":\"Склад на Ленина\""),
            "true|true|false|true|true");
        eq("пункт 10.113: без SQL 10.113 — прежняя запись (с email)", IvaPickupPatch.body(p, true, false).equals(IvaPickupPatch.body(p, true))
            + "|" + IvaPickupPatch.body(p, false, false).contains("\"email\":\"old@x.ru\""), "true|true");
        eq("пункт 10.113: столбцы и ответ «колонки нет»",
            IvaPickupPatch.listPath("a@x.ru", true, true).contains("close_min,email,created_at,tel,staff_publish&") + "|"
                + IvaPickupPatch.listPath("a@x.ru", true, false).equals(IvaPickupPatch.listPath("a@x.ru", true)) + "|"
                + IvaPickupPatch.noPub("400", "{\"code\":\"42703\",\"message\":\"column iva_pickup_points.staff_publish does not exist\"}") + "|"
                + IvaPickupPatch.noPub("400", "{\"code\":\"42703\",\"message\":\"column iva_pickup_points.tel does not exist\"}") + "|"
                + IvaPickupPatch.dbError("400", "{\"code\":\"PGRST204\",\"message\":\"Could not find the 'staff_publish' column\"}"),
            "true|true|true|false|" + IvaPickupPatch.NO_PUB);
        eq("пункт 10.113: строка на карточке и разбор режима",
            IvaPickupPatch.publishLine("now") + " / " + IvaPickupPatch.publishLine("approve") + " / " + IvaPickupPatch.publishOf(null)
                + IvaPickupPatch.publishOf(" now ") + IvaPickupPatch.publishOf("x"),
            "Инструменты сотрудников: сразу, без одобрения / Инструменты сотрудников: после вашего одобрения / approvenowapprove");
        // разбор ответа (org.json) здесь — заглушки Android; пустой email и режим проверяет стенд и сторож
        eq("пункт 10.113: общей строки «Инструменты сотрудников» в «Настройке проката» нет", IvaStaffToolsPatch.MODE_ROW, false);
    }

    /** 10.114: «Выдача» и «Каталог» вместе; пункт в «Добавить инструмент» выбирают сами; выбор сотрудника — в базу. */
    static void points114() {
        boolean[] none = new boolean[4], issue = IvaStaffPatch.preset("Выдача"), both = IvaStaffPatch.togglePreset("Каталог", issue);
        eq("сотрудник 10.114: наборы складываются — «Выдача» + «Каталог» = все четыре права",
            java.util.Arrays.toString(IvaStaffPatch.togglePreset("Выдача", none)) + "|" + java.util.Arrays.toString(both),
            "[true, false, true, true]|[true, true, true, true]");
        eq("сотрудник 10.114: обе кнопки выделены сразу; повторное нажатие снимает только свой набор",
            IvaStaffPatch.presetOn("Выдача", both) + "|" + IvaStaffPatch.presetOn("Каталог", both) + "|"
                + java.util.Arrays.toString(IvaStaffPatch.togglePreset("Выдача", both)) + "|"
                + IvaStaffPatch.presetOn("Выдача", new boolean[]{true, true, false, true}) + "|" + IvaStaffPatch.presetOn("другое", both)
                + "|" + IvaStaffPatch.presetOn("Каталог", null),
            "true|true|[false, true, false, false]|false|false|false");
        eq("сотрудник 10.114: прежняя «какой набор» не сломана", IvaStaffPatch.presetOf(both) + "|" + IvaStaffPatch.presetOf(issue), "|Выдача");
        eq("сотрудник 10.114: подпись прав", IvaStaffPatch.RIGHTS_LABEL, "Права — можно выбрать оба набора или отметить по одному");
        java.util.List<IvaPickupPatch.Point> ps = new java.util.ArrayList<IvaPickupPatch.Point>();
        String A = "0f8fad5b-d9cb-469f-a165-70867728950e", B = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
        for (String id : new String[]{A, B}) { IvaPickupPatch.Point p = new IvaPickupPatch.Point(); p.id = id; ps.add(p); }
        eq("пункт 10.114: заранее ничего не выбрано; выбран — свой; чужой — не выбран",
            "[" + IvaToolPointPatch.chosen("", ps) + "][" + IvaToolPointPatch.chosen(B, ps) + "][" + IvaToolPointPatch.chosen("x", ps) + "]["
                + IvaToolPointPatch.chosen(A, null) + "]",
            "[][" + B + "][][]");
        eq("пункт 10.114: выбранный пункт пишется всегда (первый у базы мог быть другим)",
            IvaToolPointPatch.needWrite(0, A, "", "") + "|" + IvaToolPointPatch.needWrite(0, "", "", ""), "true|false");
        long now = 10_000_000L;
        eq("пункт 10.114: выбор сотрудника в базе — тот же пункт и не старше 40 минут",
            IvaToolPointPatch.pickFresh(A, A.toUpperCase(java.util.Locale.ROOT), now - 60_000L, now) + "|" + IvaToolPointPatch.pickFresh(A, B, now, now)
                + "|" + IvaToolPointPatch.pickFresh(A, A, now - 41 * 60_000L, now) + "|" + IvaToolPointPatch.pickFresh("", "", now, now)
                + "|" + IvaToolPointPatch.pickFresh(A, A, now + 5, now),
            "true|false|false|false|false");
        eq("пункт 10.114: без SQL 20261105 — как в 10.113; тело — только проверенный id",
            IvaToolPointPatch.noPickRpc("404", "") + "|" + IvaToolPointPatch.noPickRpc("400", "{\"code\":\"PGRST202\"}") + "|"
                + IvaToolPointPatch.noPickRpc("401", "{\"code\":\"PGRST301\"}") + "|" + IvaToolPointPatch.noPickRpc("", "") + "|"
                + IvaToolPointPatch.pickBody(B.toUpperCase(java.util.Locale.ROOT)) + "|" + IvaToolPointPatch.pickBody("1; drop"),
            "true|true|false|false|{\"p_point\":\"" + B + "\"}|null");
        eq("пункт 10.114: подписи", IvaToolPointPatch.LABEL_ADD + " / " + IvaToolPointPatch.NEED,
            "В какой пункт выдачи добавляете инструмент / Выберите пункт выдачи — без него инструмент не опубликуется");
    }

    static IvaPickupPatch.Point pt115(String id, String name, String tel) {
        IvaPickupPatch.Point p = new IvaPickupPatch.Point();
        p.id = id; p.name = name; p.tel = tel; p.address = name + ", улица Ленина, 1";
        return p;
    }

    static IvaStaffPatch.Staff st115(String id, String name, String status) {
        IvaStaffPatch.Staff s = new IvaStaffPatch.Staff();
        s.id = id; s.name = name; s.status = status;
        return s;
    }

    static void live115() {
        String A = "11111111-1111-1111-1111-111111111111", B = "22222222-2222-2222-2222-222222222222", S = "33333333-3333-3333-3333-333333333333";
        boolean[] r = {true, false, true, true}, r2 = {true, true, true, true};
        java.util.List<IvaPickupPatch.Point> two = java.util.Arrays.asList(pt115(A, "Склад", ""), pt115(B, "Бердск", ""));
        java.util.List<IvaPickupPatch.Point> one = java.util.Arrays.asList(pt115(A, "Склад", ""));
        java.util.List<IvaPickupPatch.Point> oneTel = java.util.Arrays.asList(pt115(A, "Склад", "+79001234567"));
        eq("сотрудник 10.115: убрали из одного пункта из двух — подсказка и «пункты поменялись»",
            IvaStaffPatch.staffHint(r, false, two, r, false, one) + "|" + IvaStaffPatch.pointsMoved(two, one), "Вас убрали из пункта «Бердск»|true");
        eq("сотрудник 10.115: добавили пункт", IvaStaffPatch.staffHint(r, false, one, r, false, two), "Вас добавили в пункт «Бердск»");
        eq("сотрудник 10.115: права и «сам себе» — прежняя подсказка",
            IvaStaffPatch.staffHint(r, false, one, r2, false, one) + "|" + IvaStaffPatch.staffHint(r, false, one, r, true, one) + "|" + IvaStaffPatch.pointsMoved(one, one),
            "Владелец проката изменил ваши права|Владелец проката изменил ваши права|false");
        eq("сотрудник 10.115: данные пункта (телефон) — своя подсказка, пункты те же",
            IvaStaffPatch.staffHint(r, false, one, r, false, oneTel) + "|" + IvaStaffPatch.pointsMoved(one, oneTel), "Изменились данные пункта «Склад»|false");
        eq("сотрудник 10.115: всё сразу — по порядку; ничего — пусто",
            IvaStaffPatch.staffHint(r, false, two, r2, false, oneTel) + "|" + IvaStaffPatch.staffHint(r, false, two, r, false, two) + "|",
            "Вас убрали из пункта «Бердск». Владелец проката изменил ваши права. Изменились данные пункта «Склад»||");
        java.util.List<IvaStaffPatch.Staff> was = java.util.Arrays.asList(st115(S, "Иван", "invited"), st115(A, "Пётр", "invited"), st115(B, "Ольга", "active"));
        java.util.List<IvaStaffPatch.Staff> now = java.util.Arrays.asList(st115(S, "Иван", "active"), st115(A, "Пётр", "declined"), st115(B, "Ольга", "active"));
        eq("сотрудники 10.115: владельцу — кто принял и кто отказался; без изменений — пусто",
            IvaStaffPatch.ownerHint(was, now) + "|" + IvaStaffPatch.ownerHint(now, now) + "|" + IvaStaffPatch.ownerHint(null, now),
            "«Иван» принял приглашение. «Пётр» отказался от приглашения||");
        eq("сотрудники 10.115: тело «в пункт / из пункта» — только проверенные id",
            IvaStaffPatch.linkBody(S, A.toUpperCase(), true) + "|" + IvaStaffPatch.linkBody("x", A, false) + "|" + IvaStaffPatch.linkBody(S, "", false),
            "{\"p_id\":\"" + S + "\",\"p_point\":\"" + A + "\",\"p_on\":true}|null|null");
        java.util.List<String> ids = java.util.Arrays.asList(A, B);
        eq("сотрудники 10.115: пункты после «в / из пункта» и «последний пункт»",
            IvaStaffPatch.linked(ids, B, false) + "|" + IvaStaffPatch.linked(java.util.Arrays.asList(A), B.toUpperCase(), true) + "|" + IvaStaffPatch.linked(ids, A, true)
                + "|" + IvaStaffPatch.lastPoint(ids, A) + "|" + IvaStaffPatch.lastPoint(java.util.Arrays.asList(A), A),
            "[" + A + "]|[" + A + ", " + B + "]|[" + B + ", " + A + "]|false|true");
        eq("сотрудники 10.115: предупреждение «Убрать из пункта»",
            IvaStaffPatch.unlinkText("Иван", java.util.Arrays.asList("Бердск"), false) + "|" + IvaStaffPatch.unlinkText("Иван", new java.util.ArrayList<String>(), true),
            "В других пунктах (Бердск) он останется, права — прежние.|Это его единственный пункт — «Иван» перестанет быть сотрудником, доступ пропадёт сразу. Пригласить снова можно в любой момент.");
        eq("сотрудники 10.115: строка пунктов на карточке",
            IvaStaffPatch.pointsLine(java.util.Arrays.asList("Склад")) + "|" + IvaStaffPatch.pointsLine(java.util.Arrays.asList("Склад", "Бердск")) + "|" + IvaStaffPatch.pointsLine(null),
            "Пункт выдачи: Склад|Пункты выдачи: Склад, Бердск|Пункт выдачи не выбран");
        eq("сотрудники 10.115: «ФИО» в форме", IvaStaffPatch.formErr(true, "ivan@mail.ru", "me@mail.ru", " ", "", 1, r), "Введите ФИО сотрудника");
        eq("тариф 10.115: разбор строки admins", IvaAccessPatch.feeKey("[{\"fee_pct\":7.50,\"debt_limit\":5000.0}]") + "|"
            + IvaAccessPatch.feeKey("[{\"fee_pct\": null, \"debt_limit\": null}]") + "|" + IvaAccessPatch.feeKey("[]"), "7,5|5000|0|0|");
        eq("тариф 10.115: подсказка только когда поменялся (и раньше знали)",
            IvaAccessPatch.feeHint("5|0", "7,5|5000") + "|" + IvaAccessPatch.feeHint("7|5000", "7|0") + "|" + IvaAccessPatch.feeHint("", "7|0") + "|" + IvaAccessPatch.feeHint("7|0", "7|0"),
            "Создатель площадки изменил ваш тариф: комиссия 7,5 %, лимит задолженности 5000 ₽|Создатель площадки изменил ваш тариф: комиссия 7 %, без лимита задолженности||");
    }

    static void live116() {
        String rls = "Ошибка: new row violates row-level security policy for table \"tools\"";
        eq("сотрудник 10.116: без окна (нет Activity) — прежний текст отказа", IvaPatch.publishError(rls).startsWith("нет прав на публикацию. Учётная запись"), true);
        eq("сотрудник 10.116: короткий текст сотруднику — тот же префикс, без «арендодателя»",
            IvaPatch.STAFF_REFUSED.startsWith("нет прав на публикацию") && !IvaPatch.STAFF_REFUSED.contains("арендодател"), true);
        eq("сотрудник 10.116: шаг отказа — запись / фото / неизвестно",
            IvaStaffToolsPatch.refusalStep(rls) + "|" + IvaStaffToolsPatch.refusalStep("new row violates row-level security policy") + "|"
            + IvaStaffToolsPatch.refusalStep("42501") + "|" + IvaStaffToolsPatch.refusalStep(null), "2|1|0|0");
        eq("сотрудник 10.116: без SQL 10.116 — причина по iva_staff_catalog",
            (IvaStaffToolsPatch.catWhy("404", "") == IvaStaffToolsPatch.WHY_NO_SQL) + "|" + (IvaStaffToolsPatch.catWhy("200", "false") == IvaStaffToolsPatch.WHY_NO_RIGHT)
            + "|" + (IvaStaffToolsPatch.catWhy("200", "true") == IvaStaffToolsPatch.WHY_UNKNOWN) + "|" + IvaStaffToolsPatch.catWhy("", "").contains("нет связи")
            + "|" + IvaStaffToolsPatch.catWhy("500", "x").contains("500"), "true|true|true|true|true");
        eq("сотрудник 10.116: «нет SQL» называет файлы владельцу",
            IvaStaffToolsPatch.WHY_NO_SQL.contains("20261030_staff_tools.sql") && IvaStaffToolsPatch.WHY_NO_SQL.contains("20261107_staff_publish_check.sql"), true);
        eq("сотрудник 10.116: окно — шаг и причина; пустая причина — общая",
            IvaStaffToolsPatch.refusalText(2, "Нет пункта").startsWith("Объявление не сохранилось") + "|" + IvaStaffToolsPatch.refusalText(2, "Нет пункта").endsWith("\n\nНет пункта")
            + "|" + IvaStaffToolsPatch.refusalText(1, "x").startsWith("Фото не загрузилось") + "|" + IvaStaffToolsPatch.refusalText(0, "").endsWith(IvaStaffToolsPatch.WHY_UNKNOWN),
            "true|true|true|true");
        eq("сотрудник 10.116: ответ базы не разобран — пусто (тогда спросим iva_staff_catalog)",
            IvaStaffToolsPatch.whyOf("not json") + "|" + IvaStaffToolsPatch.whyOf(null) + "|" + IvaStaffToolsPatch.whyOf(""), "||");
        eq("одобрение 10.116: свой переключатель и своя категория уведомлений",
            !IvaNotifyPatch.K_REV.equals(IvaNotifyPatch.K_ORD) && IvaNotifyPatch.defOn(IvaNotifyPatch.K_REV)
            && IvaNotifyPatch.allowed(true, true, IvaNotifyPatch.K_REV, true) && !IvaNotifyPatch.allowed(true, false, IvaNotifyPatch.K_REV, true)
            && !IvaNotifyPatch.allowed(false, true, IvaNotifyPatch.K_REV, true) && !IvaNotifyPatch.allowed(true, true, IvaNotifyPatch.K_REV, false), true);
        eq("одобрение 10.116: имя категории Android", IvaNotifyPatch.channelName(IvaNotifyPatch.CH_REV), "Инструменты на одобрение");
        eq("кабинет 10.116: «Настройка проката» — только вошедшему админу",
            IvaShopPatch.cabWant(true, true) + "|" + IvaShopPatch.cabWant(true, false) + "|" + IvaShopPatch.cabWant(false, true), "true|false|false");
        eq("кабинет 10.116: подпись — «На одобрении: N» или обычная",
            IvaShopPatch.cabSub(3) + "|" + (IvaShopPatch.cabSub(0) == IvaShopPatch.ROW_SUB) + "|" + (IvaShopPatch.cabSub(-1) == IvaShopPatch.ROW_SUB),
            "На одобрении: 3 — откройте|true|true");
        eq("кабинет 10.116: подсказки ведут в «Кабинет», не в «Настройки»",
            IvaShopPatch.MOVED.contains("«Кабинет → Настройка проката") && !IvaShopPatch.MOVED.contains("Настройки →")
            && IvaRentPatch.LINK_HINT.contains("«Кабинет → Настройка проката") && !IvaRentPatch.LINK_HINT.contains("Настройки →"), true);
        eq("переход 10.116: что ждать до перехода",
            IvaNotifyPatch.routeNeed("orders") + "" + IvaNotifyPatch.routeNeed(IvaStaffToolsPatch.OPEN) + IvaNotifyPatch.routeNeed(IvaStaffOrdersPatch.OPEN)
            + IvaNotifyPatch.routeNeed(IvaStaffChatPatch.OPEN) + IvaNotifyPatch.routeNeed("chat") + IvaNotifyPatch.routeNeed("rent")
            + IvaNotifyPatch.routeNeed("test") + IvaNotifyPatch.routeNeed("update") + IvaNotifyPatch.routeNeed("???"), "223311100");
        eq("переход 10.116: готовность",
            IvaNotifyPatch.routeReady(0, false, false, false) + "|" + IvaNotifyPatch.routeReady(1, false, false, false) + "|" + IvaNotifyPatch.routeReady(1, true, false, false)
            + "|" + IvaNotifyPatch.routeReady(2, true, false, true) + "|" + IvaNotifyPatch.routeReady(2, true, true, false)
            + "|" + IvaNotifyPatch.routeReady(3, true, true, false) + "|" + IvaNotifyPatch.routeReady(3, true, false, true) + "|" + IvaNotifyPatch.routeReady(3, false, false, true),
            "true|false|true|false|true|false|true|false");
        eq("переход 10.116: ждём роль не дольше 20 с", IvaNotifyPatch.ROUTE_READY_MS, 20000L);
    }

    /** 10.117: кабинет админа — комиссия только по полному ответу, роль по прошлому ответу базы, свежий ключ. */
    static void cab117() {
        eq("кабинет 10.117: сумма по ответу базы; ошибка/не массив — null (не «0 ₽»)",
            IvaAccessPatch.feeSumField("[{\"fee\":100},{\"fee\":null},{\"fee\":50.5}]", "fee") + "|" + IvaAccessPatch.feeSumField("[]", "fee")
            + "|" + IvaAccessPatch.feeSumField("{\"code\":\"PGRST301\",\"message\":\"JWT expired\"}", "fee") + "|" + IvaAccessPatch.feeSumField("", "fee")
            + "|" + IvaAccessPatch.feeSumField(null, "amount") + "|" + IvaAccessPatch.feeSumField("[{\"amount\":\"200.00\"},{\"amount\":-20}]", "amount"),
            "150.5|0.0|null|null|null|180.0");
        eq("кабинет 10.117: поле другого имени не считается",
            IvaAccessPatch.feeSumField("[{\"fee_pct\":5,\"fee\":10}]", "fee") + "|" + IvaAccessPatch.feeSumField("[{\"amount_x\":5}]", "amount"), "10.0|0.0");
        eq("кабинет 10.117: процент — 7,5 не режется; нет строки — 5; ошибка — null",
            IvaAccessPatch.feePctField("[{\"fee_pct\":7.5}]") + "|" + IvaAccessPatch.feePctField("[]") + "|" + IvaAccessPatch.feePctField("[{\"fee_pct\":null}]")
            + "|" + IvaAccessPatch.feePctField("{\"message\":\"x\"}"), "7.5|5.0|5.0|null");
        String[] d = IvaAccessPatch.feeTexts(1250, 1000, 7.5), o = IvaAccessPatch.feeTexts(100, 250, 5), z = IvaAccessPatch.feeTexts(0, 0, 5);
        eq("кабинет 10.117: задолженность — как штатная карточка, красным",
            d[0] + "|" + d[1] + "|" + d[2] + "|" + d[3],
            "Комиссия владельцу — 7,5% от аренды|−250 ₽|задолженность · начислено 1 250 ₽ · оплачено 1 000 ₽|1");
        eq("кабинет 10.117: переплата и ноль — зелёным",
            o[1] + "|" + o[2] + "|" + o[3] + "|" + z[1] + "|" + z[2] + "|" + z[3],
            "+150 ₽|переплата · начислено 100 ₽ · оплачено 250 ₽|0|0 ₽|начислено 0 ₽ · оплачено 0 ₽|0");
        eq("кабинет 10.117: деньги как штатный money", IvaAccessPatch.feeMoney(1234567.4) + "|" + IvaAccessPatch.feeMoney(0.6), "1 234 567|1");
        double[] k = IvaAccessPatch.feeCache(IvaAccessPatch.feeMoneyKey(new double[]{1250, 1000, 7.5, 1760000000000d}));
        eq("кабинет 10.117: последняя удачная сумма на телефоне — туда и обратно",
            k[0] + "|" + k[1] + "|" + k[2] + "|" + (long) k[3], "1250.0|1000.0|7.5|1760000000000");
        eq("кабинет 10.117: испорченная запись — нет суммы",
            (IvaAccessPatch.feeCache("") == null) + "|" + (IvaAccessPatch.feeCache(null) == null) + "|" + (IvaAccessPatch.feeCache("1|2|x|4") == null)
            + "|" + (IvaAccessPatch.feeCache("1|2|3") == null), "true|true|true|true");
        eq("кабинет 10.117: нет свежих данных — пометка, а не другая сумма",
            IvaAccessPatch.feeStale(true, "14:05") + "|" + IvaAccessPatch.feeStale(false, ""),
            "Не удалось обновить — сумма на 14:05|Не удалось загрузить — откройте «Кабинет» ещё раз");
        eq("кабинет 10.117: три попытки, пауза 2,5 с", IvaAccessPatch.FEE_TRIES + "|" + IvaAccessPatch.FEE_RETRY_MS, "3|2500");
        eq("роль 10.117: по прошлому ответу базы — вернуть админа / сделать клиентом / не трогать",
            IvaAccessPatch.roleFix(false, true) + "|" + IvaAccessPatch.roleFix(true, false) + "|" + IvaAccessPatch.roleFix(true, true) + "|" + IvaAccessPatch.roleFix(false, false),
            "1|-1|0|0");
        long now = 1760000000000L;
        eq("ключ 10.117: истёк ли ключ, с которым ходили (запас 5 с)",
            IvaClientPatch.authDead(now / 1000 - 10, now) + "|" + IvaClientPatch.authDead(now / 1000 + 3, now) + "|" + IvaClientPatch.authDead(now / 1000 + 60, now)
            + "|" + IvaClientPatch.authDead(0, now), "true|true|false|false");
        eq("ключ 10.117: перерисовать после обновления — только «Кабинет» и «Заявки»",
            IvaClientPatch.authHealTab("profile") + "|" + IvaClientPatch.authHealTab("orders") + "|" + IvaClientPatch.authHealTab("home") + "|" + IvaClientPatch.authHealTab(""),
            "true|true|false|false");
        eq("ключ 10.117: первая проверка через 5 с после запуска", IvaClientPatch.AUTH_FIRST_MS, 5000L);
    }

    /** 10.118: сотруднику отказали в публикации — причина по пробной записи базы (iva_staff_publish_try). */
    static String jwt119(String payload) {
        try {
            java.util.Base64.Encoder e = java.util.Base64.getUrlEncoder().withoutPadding();
            return e.encodeToString("{\"alg\":\"HS256\"}".getBytes("UTF-8")) + "." + e.encodeToString(payload.getBytes("UTF-8")) + ".sig";
        } catch (Exception x) { return ""; }
    }

    static void mail119() {
        eq("почта 10.119: из ключа — верхнего уровня, строчными (user_metadata после)",
            IvaNotifyPatch.jwtEmail(jwt119("{\"aud\":\"authenticated\",\"exp\":1760000000,\"sub\":\"u1\",\"email\":\"Eclips161@Mail.RU\","
                + "\"user_metadata\":{\"email\":\"eclipsik.ru@mail.ru\",\"email_verified\":true}}")), "eclips161@mail.ru");
        eq("почта 10.119: вложенные объекты раньше почты — не путаемся",
            IvaNotifyPatch.jwtEmail(jwt119("{\"user_metadata\":{\"email\":\"eclipsik.ru@mail.ru\",\"x\":{\"email\":\"z@z\"}},"
                + "\"app_metadata\":{\"providers\":[\"email\"],\"a\":{\"b\":{\"c\":1}}},\"email\":\"staff@x.ru\"}")), "staff@x.ru");
        eq("почта 10.119: нет почты / только вложенная / мусор / пусто — \"\"",
            IvaNotifyPatch.jwtEmail(jwt119("{\"sub\":\"u\",\"exp\":1}")) + "|"
            + IvaNotifyPatch.jwtEmail(jwt119("{\"user_metadata\":{\"email\":\"eclipsik.ru@mail.ru\"}}")) + "|"
            + IvaNotifyPatch.jwtEmail("not.a.jwt") + "|" + IvaNotifyPatch.jwtEmail("abc") + "|" + IvaNotifyPatch.jwtEmail(null) + "|"
            + IvaNotifyPatch.jwtEmail(""), "|||||");
        eq("почта 10.119: срок из того же ключа читается как раньше",
            IvaNotifyPatch.jwtExp(jwt119("{\"exp\":1760000000,\"email\":\"a@b.ru\"}")), 1760000000L);
        eq("почта 10.119: чужая почта в поле — исправить; та же (любой регистр) — нет; ключ без почты — не трогать",
            IvaClientPatch.authEmailWrong("eclipsik.ru@mail.ru", "eclips161@mail.ru") + "|"
            + IvaClientPatch.authEmailWrong(null, "eclips161@mail.ru") + "|"
            + IvaClientPatch.authEmailWrong(" Eclips161@mail.ru ", "eclips161@mail.ru") + "|"
            + IvaClientPatch.authEmailWrong("eclipsik.ru@mail.ru", "") + "|" + IvaClientPatch.authEmailWrong("x@y", null),
            "true|true|false|false|false");
    }

    static void try118() {
        String rls = "new row violates row-level security policy for table \"tools\"";
        String why = IvaStaffToolsPatch.tryText("Не пускает ограничивающее правило: only_own.", "42501: " + rls);
        eq("проба 10.118: фраза и ответ базы отдельной строкой", why,
            "Не пускает ограничивающее правило: only_own.\n\nОтвет базы: 42501: " + rls);
        eq("проба 10.118: запись проходит — только фраза; ответ уже в фразе — не повторяем",
            IvaStaffToolsPatch.tryText(" Проходит. ", "") + "|" + IvaStaffToolsPatch.tryText("Стоп: x", "x") + "|" + IvaStaffToolsPatch.tryText("Нет права.", null),
            "Проходит.|Стоп: x|Нет права.");
        eq("проба 10.118: нет фразы — пусто (тогда как в 10.116)",
            IvaStaffToolsPatch.tryText("", "42501: x") + "|" + IvaStaffToolsPatch.tryText(null, null) + "|" + IvaStaffToolsPatch.tryText("  ", ""), "||");
        eq("проба 10.118: ответ не разобран — пусто", IvaStaffToolsPatch.tryOf("not json") + "|" + IvaStaffToolsPatch.tryOf(null) + "|" + IvaStaffToolsPatch.tryOf(""), "||");
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 50; i++) big.append("ошибка ");
        String line = IvaStaffToolsPatch.rawLine(big.toString() + "\nконец");
        eq("проба 10.118: ответ базы — одной строкой, не длиннее 240 знаков",
            line.startsWith("Ответ базы: ошибка") + "|" + line.endsWith("…") + "|" + !line.contains("\n") + "|"
            + (line.length() == "Ответ базы: ".length() + IvaStaffToolsPatch.RAW_MAX + 1), "true|true|true|true");
        eq("проба 10.118: окно — причина и ответ на саму публикацию",
            IvaStaffToolsPatch.refusalText(2, "Проходит.", rls), "Объявление не сохранилось: база не пустила запись.\n\nПроходит.\n\nОтвет базы: " + rls);
        eq("проба 10.118: ответ базы уже в причине — второй раз не пишем",
            IvaStaffToolsPatch.refusalText(2, why, rls).split("Ответ базы:", -1).length + "|"
            + IvaStaffToolsPatch.refusalText(2, "x", "").endsWith("\n\nx") + "|" + IvaStaffToolsPatch.refusalText(2, "x", null).endsWith("\n\nx"), "2|true|true");
        eq("проба 10.118: без причины — общая фраза называет 20261108",
            IvaStaffToolsPatch.WHY_UNKNOWN.contains("20261108_staff_publish_probe.sql"), true);
        eq("проба 10.118: адрес функции", IvaStaffToolsPatch.TRY_RPC, "/rest/v1/rpc/iva_staff_publish_try");
    }

    static void prokat120() {
        String id = "0F8FAD5B-D9CB-469F-A165-70867728950E", low = id.toLowerCase();
        eq("прокат 10.120: тело запроса пунктов — только uuid", IvaProkatPatch.body(" " + id + " ") + "|" + IvaProkatPatch.body("x") + "|" + IvaProkatPatch.body(null),
            "{\"p_point\":\"" + low + "\"}|null|null");
        eq("прокат 10.120: «Позвонить» — цифры и ведущий плюс; меньше 5 цифр — кнопки нет",
            IvaProkatPatch.telUri("+7 (383) 111-22-33") + "|" + IvaProkatPatch.telUri("8 913 000 11 22 доб. 5") + "|" + IvaProkatPatch.telUri("12-34")
            + "|" + IvaProkatPatch.telUri("") + "|" + IvaProkatPatch.telUri(null) + "|" + IvaProkatPatch.telUri("1+2345"),
            "tel:+73831112233|tel:891300011225||||tel:12345");
        eq("прокат 10.120: ключ переписки пункта «pt:uuid» и обратно",
            IvaProkatPatch.key(id) + "|" + IvaProkatPatch.isKey("pt:" + low) + "|" + IvaProkatPatch.isKey("pt:x") + "|" + IvaProkatPatch.isKey(low)
            + "|" + IvaProkatPatch.idOf("pt:" + low) + "|" + IvaProkatPatch.idOf(low) + "|" + IvaProkatPatch.key("x"),
            "pt:" + low + "|true|false|false|" + low + "||");
        eq("прокат 10.120: подписи", IvaProkatPatch.chatTitle(" Склад ") + "|" + IvaProkatPatch.chatTitle("") + "|" + IvaProkatPatch.toolsText(0) + "|"
            + IvaProkatPatch.toolsText(3) + "|" + IvaProkatPatch.pointsText(1) + "|" + IvaProkatPatch.pointsText(4),
            "Пункт выдачи «Склад»|Пункт выдачи|Инструментов пока нет|Инструментов: 3|Пункты выдачи|Пункты выдачи · 4");
        IvaProkatPatch.PP p = new IvaProkatPatch.PP();
        p.address = "Новосибирск, улица Ленина, 5"; p.tel = "+7 383 111-22-33";
        p.open = new int[]{540, 540, 540, 540, 540, -1, -1}; p.close = new int[]{1080, 1080, 1080, 1080, 1080, -1, -1};
        String info = IvaProkatPatch.infoText(p);
        eq("прокат 10.120: карточка пункта — адрес, время работы, телефон (почты нет)",
            info.startsWith("Новосибирск, улица Ленина, 5\nВремя работы: ") + "|" + info.endsWith("\nТелефон: +7 383 111-22-33") + "|" + info.contains("@")
            + "|" + IvaProkatPatch.infoText(new IvaProkatPatch.PP()).isEmpty() + "|" + IvaProkatPatch.infoText(null).isEmpty(), "true|true|false|true|true");
        eq("прокат 10.120: без инструментов в каталоге — пустой список", IvaProkatPatch.toolsOf(null, low).size() + "|"
            + IvaProkatPatch.toolsOf(new java.util.ArrayList<Object>(), low).size(), "0|0");
        eq("чат 10.120: сообщение с пунктом — переписка «pt:…»; инструмент важнее; без пункта — как раньше",
            IvaChatPatch.msgTool("", "", id) + "|" + IvaChatPatch.msgTool("t1", "", id) + "|" + IvaChatPatch.msgTool("", "lend", "") + "|"
            + IvaChatPatch.msgTool("", "", "") + "|" + IvaChatPatch.msgTool("", "", "bad"), "pt:" + low + "|t1|lend||");
        eq("чат 10.120: переписка пункта — не инструмент и не «Хочу сдавать»; подпись в списке",
            IvaChatPatch.isPoint("pt:" + low) + "|" + IvaChatPatch.isPoint(low) + "|" + IvaChatPatch.isLend("pt:" + low) + "|"
            + IvaChatPatch.chatSub("pt:" + low, "Пункт выдачи «Склад»") + "|" + IvaChatPatch.chatSub("pt:" + low, ""),
            "true|false|false|Пункт выдачи «Склад»|Пункт выдачи");
        String peer = "0123456789abcdef0123456789abcdef";
        eq("чат пункта 10.120: сотруднику — тело и функции переписки пункта",
            IvaStaffChatPatch.threadBody("pt:" + low, peer) + "|" + IvaStaffChatPatch.threadRpc("pt:" + low) + "|" + IvaStaffChatPatch.readRpc("pt:" + low)
            + "|" + IvaStaffChatPatch.sendRpc("pt:" + low),
            "{\"p_point\":\"" + low + "\",\"p_peer\":\"" + peer + "\"}|/rest/v1/rpc/iva_staff_point_chat|/rest/v1/rpc/iva_staff_point_chat_read|/rest/v1/rpc/iva_staff_point_chat_send");
        eq("чат пункта 10.120: переписка по инструменту — как в 10.106",
            IvaStaffChatPatch.threadBody(low, peer) + "|" + IvaStaffChatPatch.threadRpc(low) + "|" + IvaStaffChatPatch.sendRpc(low),
            "{\"p_tool\":\"" + low + "\",\"p_peer\":\"" + peer + "\"}|/rest/v1/rpc/iva_staff_chat|/rest/v1/rpc/iva_staff_chat_send");
        String[] tg = IvaStaffChatPatch.parseTarget(IvaStaffChatPatch.target("pt:" + low, peer));
        eq("чат пункта 10.120: уведомление ведёт в переписку пункта; чужое — нет",
            (tg == null ? "null" : tg[0] + "|" + tg[1]) + "|" + IvaStaffChatPatch.target("pt:x", peer) + "|" + IvaStaffChatPatch.parseTarget("pt:x|" + peer)
            + "|" + IvaStaffChatPatch.threadBody("pt:x", peer), "pt:" + low + "|" + peer + "||null|null");
        eq("«На одобрении» 10.120: залог и доставка — рубли", IvaStaffToolsPatch.rub("2000") + "|" + IvaStaffToolsPatch.rub("12,6") + "|"
            + IvaStaffToolsPatch.rub("") + "|" + IvaStaffToolsPatch.rub("-1") + "|" + IvaStaffToolsPatch.rub("abc") + "|" + IvaStaffToolsPatch.rub(null),
            "2000 ₽|13 ₽||||");
        eq("10.120: адреса функций", IvaProkatPatch.RPC + "|" + IvaStaffToolsPatch.CARD_RPC + "|" + IvaStaffChatPatch.P_CHATS_RPC,
            "/rest/v1/rpc/iva_prokat_points|/rest/v1/rpc/iva_tool_pending_card|/rest/v1/rpc/iva_staff_point_chats");
    }

    static void v121() {
        // «Данные проката» → «Адрес пункта выдачи»
        eq("10.121: адрес пункта выдачи уходит в admins.address", IvaShopPatch.withPick("{\"company\":\"Ива\"}", "  Нск,  Ленина 1 ") + "|"
            + IvaShopPatch.withPick("{}", "") + "|" + IvaShopPatch.withPick(null, "x"),
            "{\"company\":\"Ива\",\"address\":\"Нск, Ленина 1\"}|{\"address\":\"\"}|null");
        int[][] h = IvaShopPatch.pickHours(1 | 2 | 4 | 8 | 16, 540, 1140);
        eq("10.121: график первого пункта из «Данных проката»", java.util.Arrays.toString(h[0]) + java.util.Arrays.toString(h[1]) + "|"
            + (IvaShopPatch.pickHours(31, -1, 1140) == null) + "|" + (IvaShopPatch.pickHours(0, 540, 1140) == null) + "|"
            + (IvaShopPatch.pickHours(31, 600, 600) == null) + "|" + IvaShopPatch.pickHours(64, 600, 1440)[1][6],
            "[540, 540, 540, 540, 540, -1, -1][1140, 1140, 1140, 1140, 1140, -1, -1]|true|true|true|1439");
        IvaPickupPatch.Point pt = new IvaPickupPatch.Point();
        pt.city = "Новосибирск"; pt.lat = 55.0; pt.lng = 82.9;
        eq("10.121: тело iva_pickup_first", IvaPickupPatch.firstBody(" ул. Ленина, 1 ", pt, new int[][]{{540, 540, 540, 540, 540, -1, -1}, {1140, 1140, 1140, 1140, 1140, -1, -1}}),
            "{\"p_address\":\"ул. Ленина, 1\",\"p_city\":\"Новосибирск\",\"p_lat\":55.0,\"p_lng\":82.9,\"p_open\":[540,540,540,540,540,-1,-1],\"p_close\":[1140,1140,1140,1140,1140,-1,-1]}");
        eq("10.121: без подсказки и графика — null", IvaPickupPatch.firstBody("Ленина 1", new IvaPickupPatch.Point(), null),
            "{\"p_address\":\"Ленина 1\",\"p_city\":\"\",\"p_lat\":null,\"p_lng\":null,\"p_open\":null,\"p_close\":null}");
        eq("10.121: ответ — создан ли пункт", IvaPickupPatch.firstMade("\"0b7113d1-7e62-431a-8192-5f27b473843b\"") + "|" + IvaPickupPatch.firstMade("null")
            + "|" + IvaPickupPatch.firstMade("") + "|" + IvaPickupPatch.firstMade(null), "true|false|false|false");
        // правки сотрудников на одобрение
        eq("10.121: состояние правки у сотрудника", IvaStaffToolsPatch.statusText("", true, "pending") + "|" + IvaStaffToolsPatch.statusText("", false, "rejected")
            + "|" + IvaStaffToolsPatch.statusText("pending", true, "pending") + "|" + IvaStaffToolsPatch.statusText("", true, "") + "|" + IvaStaffToolsPatch.statusText("", false, null),
            "Правка на одобрении|Правка отклонена|На одобрении|Опубликован|Скрыт");
        eq("10.121: подсказки правки", IvaStaffToolsPatch.statusHint("", "pending") + "|" + IvaStaffToolsPatch.statusHint("", "rejected").startsWith("Владелец отклонил правку")
            + "|" + IvaStaffToolsPatch.statusHint("rejected", "pending").equals(IvaStaffToolsPatch.statusHint("rejected")) + "|" + IvaStaffToolsPatch.statusHint("", ""),
            "Клиенты видят прежний вариант, пока владелец не одобрит правку|true|true|");
        eq("10.121: что изменилось — подписи карточки", IvaStaffToolsPatch.changedText(java.util.Arrays.asList("price", "imgs", "img", "cat_label", "cat", "descr")) + "|"
            + IvaStaffToolsPatch.changedText(new java.util.ArrayList<String>()) + "|" + IvaStaffToolsPatch.changedLabels(null).size(),
            "Правка опубликованного инструмента. Изменено: Категория, Цена, Описание, Фото|Правка опубликованного инструмента|0");
        eq("10.121: «Отклонить» и итог — для правки свои слова", IvaStaffToolsPatch.rejectTitle(true) + "|" + IvaStaffToolsPatch.rejectTitle(false) + "|"
            + IvaStaffToolsPatch.reviewDone(true, true) + "|" + IvaStaffToolsPatch.reviewDone(false, true) + "|" + IvaStaffToolsPatch.reviewDone(true, false),
            "Отклонить правку?|Отклонить инструмент?|Правка опубликована — клиенты видят новый вариант|Опубликовано — клиенты видят|Правка отклонена — клиенты видят прежний вариант");
        eq("10.121: второй раз тот же инструмент — понятная фраза", IvaStaffToolsPatch.dbPhrase("{\"message\":\"" + IvaStaffToolsPatch.DUP_PHRASE + "\"}") + "|"
            + IvaStaffToolsPatch.dbPhrase("other") + "|" + IvaPatch.publishError("x " + IvaStaffToolsPatch.DUP_PHRASE).equals(IvaStaffToolsPatch.DUP_PHRASE)
,
            IvaStaffToolsPatch.DUP_PHRASE + "|null|true");
        eq("10.121: форма «Добавить» очищает все поля", String.join(",", IvaFormPatch.FORM_TEXTS), "atName,atPrice,atDep,atDescr,atTerms,atDelPrice");
        // переписка пункта без сотрудника — у владельца
        IvaStaffPatch.Staff s1 = new IvaStaffPatch.Staff(), s2 = new IvaStaffPatch.Staff(), s3 = new IvaStaffPatch.Staff();
        s1.status = "active"; s1.r = new boolean[]{true, false, true, false}; s1.points.add("aaa");
        s2.status = "invited"; s2.r = new boolean[]{true, true, true, true}; s2.points.add("bbb");
        s3.status = "active"; s3.r = new boolean[]{true, true, false, true}; s3.points.add("ccc");
        java.util.Set<String> ans = IvaProkatPatch.answeredFrom(java.util.Arrays.asList(s1, s2, s3));
        eq("10.121: кто отвечает в чате пункта", new java.util.TreeSet<String>(ans) + "|" + IvaProkatPatch.hideForOwner(ans, "AAA") + "|"
            + IvaProkatPatch.hideForOwner(ans, "bbb") + "|" + IvaProkatPatch.hideForOwner(null, "bbb") + "|" + IvaProkatPatch.answeredFrom(null).size(),
            "[aaa]|true|false|true|0");
        // заявки целиком
        eq("10.121: тело iva_order_cards", IvaOrderViewPatch.body("client") + "|" + IvaOrderViewPatch.body("staff") + "|" + IvaOrderViewPatch.body("x"),
            "{\"p_scope\":\"client\"}|{\"p_scope\":\"staff\"}|null");
        eq("10.121: сутки", IvaOrderViewPatch.days(1) + "|" + IvaOrderViewPatch.days(2) + "|" + IvaOrderViewPatch.days(5) + "|" + IvaOrderViewPatch.days(11)
            + "|" + IvaOrderViewPatch.days(21), "1 сутки|2 суток|5 суток|11 суток|21 сутки");
        eq("10.121: деньги", IvaOrderViewPatch.money(1200) + "|" + IvaOrderViewPatch.money(400) + "|" + IvaOrderViewPatch.money(1234567) + "|"
            + IvaOrderViewPatch.money(99.5) + "|" + IvaOrderViewPatch.money(-1) + "|" + IvaOrderViewPatch.money(Double.NaN),
            "1\u00a0200\u00a0₽|400\u00a0₽|1\u00a0234\u00a0567\u00a0₽|99,50\u00a0₽||");
        eq("10.121: строка инструмента и сумма", IvaOrderViewPatch.itemLine(3, 400, 2000) + "|" + IvaOrderViewPatch.itemLine(1, 400, Double.NaN) + "|"
            + IvaOrderViewPatch.itemLine(0, Double.NaN, 0) + "|" + IvaOrderViewPatch.itemSum(3, 400) + "|" + IvaOrderViewPatch.itemSum(0, 400),
            "3 суток · 400\u00a0₽/сутки · залог 2\u00a0000\u00a0₽|1 сутки · 400\u00a0₽/сутки||1200.0|NaN");
        long now = 1_000_000L;
        eq("10.121: плашка состояния", IvaOrderViewPatch.kind("new", 0, now) + "|" + IvaOrderViewPatch.kind("rented", now + 1, now) + "|"
            + IvaOrderViewPatch.kind("rented", now, now) + "|" + IvaOrderViewPatch.kind("closed", 0, now) + "|" + IvaOrderViewPatch.kind(null, 0, now)
            + "|" + IvaOrderViewPatch.kind("rented", 0, now), "0|1|3|2|0|1");
        eq("10.121: состояние заявки из частей", IvaOrderViewPatch.orderStatus(java.util.Arrays.asList("closed", "rented")) + "|"
            + IvaOrderViewPatch.orderStatus(java.util.Arrays.asList("closed", "new", "rented")) + "|" + IvaOrderViewPatch.orderStatus(java.util.Arrays.asList("closed"))
            + "|" + IvaOrderViewPatch.orderStatus(null), "rented|new|closed|new");
        eq("10.121: пункт выдачи строкой", IvaOrderViewPatch.pointLine("Склад", "ул. Ленина, 1", "Новосибирск") + "|"
            + IvaOrderViewPatch.pointLine("Склад", "Новосибирск, ул. Ленина, 1", "Новосибирск") + "|" + IvaOrderViewPatch.pointLine("", "Ленина 1", "") + "|"
            + IvaOrderViewPatch.pointLine("Склад", "", ""), "Склад · Новосибирск, ул. Ленина, 1|Склад · Новосибирск, ул. Ленина, 1|Ленина 1|Склад");
        int[] op = {540, 540, 540, 540, 540, 600, -1}, cl = {1140, 1140, 1140, 1140, 1140, 900, -1};
        eq("10.121: график пункта на сегодня", IvaOrderViewPatch.today(op, cl, 0) + "|" + IvaOrderViewPatch.today(op, cl, 5) + "|" + IvaOrderViewPatch.today(op, cl, 6)
            + "|" + IvaOrderViewPatch.today(null, cl, 0) + "|" + IvaOrderViewPatch.today(op, cl, 7), "сегодня 9:00–19:00|сегодня 10:00–15:00|сегодня выходной||");
        eq("10.121: число из базы", IvaOrderViewPatch.intOf("3", 0) + "|" + IvaOrderViewPatch.intOf(null, 7) + "|" + IvaOrderViewPatch.intOf("x", 1) + "|"
            + IvaOrderViewPatch.intOf(2.6, 0), "3|7|1|3");
        eq("10.121: адрес функции заявок", IvaOrderViewPatch.RPC, "/rest/v1/rpc/iva_order_cards");
    }

    static void v122() {
        String u1 = "11111111-2222-3333-4444-555555555555", u2 = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        // право «Удаляет инструмент»
        boolean[] cat = {false, true, false, false}, noCat = {true, false, false, false};
        eq("10.122: iva_staff_save + p_delete (без «Выкладывает» — false)",
            IvaStaffPatch.saveBody(null, "a@b.ru", "Иван", "", null, cat, false, true).endsWith(",\"p_self\":false,\"p_delete\":true}") + "|"
            + IvaStaffPatch.saveBody(null, "a@b.ru", "Иван", "", null, noCat, false, true).endsWith(",\"p_delete\":false}") + "|"
            + IvaStaffPatch.saveBody(null, "a@b.ru", "Иван", "", null, cat, false, false).endsWith(",\"p_delete\":false}"), "true|true|true");
        eq("10.122: статус «Ждёт удаления»", IvaStaffToolsPatch.statusText("", true, "delete") + "|" + IvaStaffToolsPatch.statusText("pending", true, "delete")
            + "|" + IvaStaffToolsPatch.statusText("", true, "pending"), "Ждёт удаления|" + IvaStaffToolsPatch.statusText("pending", true) + "|Правка на одобрении");
        eq("10.122: решение об удалении — тексты", IvaStaffToolsPatch.reviewDone(false, true, true) + "|" + IvaStaffToolsPatch.reviewDone(false, true, false)
            + "|" + IvaStaffToolsPatch.reviewDone(true, false, true).equals(IvaStaffToolsPatch.reviewDone(true, true)),
            "Инструмент удалён|Инструмент оставлен в каталоге|true");
        eq("10.122: адрес решения об удалении", IvaStaffToolsPatch.DEL_REVIEW_RPC + "|" + IvaToolEditPatch.DEL_RPC,
            "/rest/v1/rpc/iva_tool_delete_review|/rest/v1/rpc/iva_staff_tool_delete");
        eq("10.122: запрос на удаление — тело", IvaToolEditPatch.delBody(" " + u1.toUpperCase() + " ") + "|" + IvaToolEditPatch.delBody("x") + "|" + IvaToolEditPatch.delBody(null),
            "{\"p_id\":\"" + u1 + "\"}|null|null");
        eq("10.122: кнопка «Удалить инструмент» узнаётся по тексту", IvaToolEditPatch.isDelete(" Удалить инструмент ") + "|" + IvaToolEditPatch.isDelete("Удалить")
            + "|" + IvaToolEditPatch.isDelete(null), "true|false|false");
        // «Мой инструмент»
        IvaPickupPatch.Point p1 = new IvaPickupPatch.Point(); p1.id = u1.toUpperCase(); p1.name = "Склад";
        IvaPickupPatch.Point p2 = new IvaPickupPatch.Point(); p2.id = u2; p2.name = "Центр";
        java.util.List<IvaMyToolsPatch.Item> its = new java.util.ArrayList<IvaMyToolsPatch.Item>();
        String[][] raw = {{"Перфоратор", u1}, {"бетономешалка", u1}, {"Лестница", ""}, {"Пила", "99999999-2222-3333-4444-555555555555"}};
        for (String[] r : raw) { IvaMyToolsPatch.Item x = new IvaMyToolsPatch.Item(); x.name = r[0]; x.point = r[1]; its.add(x); }
        StringBuilder g = new StringBuilder();
        for (IvaMyToolsPatch.Group gr : IvaMyToolsPatch.groups(java.util.Arrays.asList(p1, p2), its)) {
            g.append(gr.name).append(':');
            for (IvaMyToolsPatch.Item x : gr.items) g.append(x.name).append(',');
            g.append(';');
        }
        eq("10.122: пункты → инструменты (по названию; без пункта — в конце)", g, "Склад:бетономешалка,Перфоратор,;Центр:;Пункт не выбран:Лестница,Пила,;");
        eq("10.122: сколько инструментов", IvaMyToolsPatch.countText(0) + "|" + IvaMyToolsPatch.countText(1) + "|" + IvaMyToolsPatch.countText(3) + "|"
            + IvaMyToolsPatch.countText(11) + "|" + IvaMyToolsPatch.countText(22) + "|" + IvaMyToolsPatch.countText(25),
            "Нет инструментов|1 инструмент|3 инструмента|11 инструментов|22 инструмента|25 инструментов");
        eq("10.122: цена", IvaMyToolsPatch.priceText("400.0") + "|" + IvaMyToolsPatch.priceText("") + "|" + IvaMyToolsPatch.priceText(null) + "|" + IvaMyToolsPatch.priceText("null"),
            "400 ₽/сутки|||");
        eq("10.122: перенос — тело", IvaMyToolsPatch.moveBody(u1, u2.toUpperCase()) + "|" + IvaMyToolsPatch.moveBody(u1, "") + "|" + IvaMyToolsPatch.moveBody("x", u2),
            "{\"p_id\":\"" + u1 + "\",\"p_point\":\"" + u2 + "\"}|null|null");
        eq("10.122: ошибка базы — нет SQL / успех", IvaMyToolsPatch.errText("404", "", "x") + "|" + IvaMyToolsPatch.errText("200", "", "x").isEmpty(),
            "Нужен SQL 10.122 — владелец применяет его в Supabase|true");
        IvaStaffToolsPatch.Tool t1 = new IvaStaffToolsPatch.Tool(); t1.id = u1; t1.name = "Пила"; t1.edit = "delete"; t1.active = true;
        IvaStaffToolsPatch.Tool t2 = new IvaStaffToolsPatch.Tool(); t2.id = u2; t2.name = "Дрель"; t2.active = true;
        java.util.List<IvaMyToolsPatch.Item> fs = IvaMyToolsPatch.fromStaff(java.util.Arrays.asList(t1, t2));
        eq("10.122: инструменты сотрудника → «Мой инструмент»", fs.size() + "|" + fs.get(0).status + "|" + fs.get(0).warn + "|" + fs.get(1).warn + "|" + fs.get(1).point.isEmpty(),
            "2|Удаление на одобрении|true|false|true");
        // кто отвечает в чате
        eq("10.122: md5 почты клиента (как md5(lower(btrim())) в базе)", IvaChatWhoPatch.md5(" Test@Mail.RU ") + "|" + IvaChatWhoPatch.md5(""),
            "cb69cd596f305fa5f24a998a817a160a|d41d8cd98f00b204e9800998ecf8427e");
        String pe = IvaChatWhoPatch.md5("test@mail.ru");
        eq("10.122: переписка — инструмент / пункт", IvaChatWhoPatch.thread(u1.toUpperCase(), pe) + "|" + IvaChatWhoPatch.thread("pt:" + u2, pe) + "|"
            + IvaChatWhoPatch.thread("lend", pe) + "|" + IvaChatWhoPatch.thread(u1, "abc") + "|" + IvaChatWhoPatch.thread(null, pe),
            "t:" + u1 + ":" + pe + "|p:" + u2 + ":" + pe + "|||");
        eq("10.122: тела iva_chat_mark / iva_chat_who", IvaChatWhoPatch.body("t:x", "open") + "|" + IvaChatWhoPatch.body("t:x", null) + "|" + IvaChatWhoPatch.body("", "open"),
            "{\"p_thread\":\"t:x\",\"p_state\":\"open\"}|{\"p_thread\":\"t:x\"}|null");
        eq("10.122: предупреждение перед отправкой", IvaChatWhoPatch.warnText("Иван (сотрудник)"), "Сейчас отвечает Иван (сотрудник). Всё равно отправить?");
        // отмена и изменение заявки
        eq("10.122: отмена — тело", IvaOrderEditPatch.cancelBody("12", "  Нашёл   \"дешевле\" ") + "|" + IvaOrderEditPatch.cancelBody("12", " a ") + "|"
            + IvaOrderEditPatch.cancelBody("x", "Передумал"), "{\"p_order\":12,\"p_reason\":\"Нашёл \\\"дешевле\\\"\"}|null|null");
        java.util.List<String[]> keep = new java.util.ArrayList<String[]>();
        keep.add(new String[]{u1, "3"});
        eq("10.122: изменение — тело", IvaOrderEditPatch.editBody("7", keep, null, true) + "|" + IvaOrderEditPatch.editBody("7", keep, "Позвоните\n", false),
            "{\"p_part\":7,\"p_items\":[{\"id\":\"" + u1 + "\",\"days\":3}],\"p_comment\":null,\"p_pickup\":true}|"
            + "{\"p_part\":7,\"p_items\":[{\"id\":\"" + u1 + "\",\"days\":3}],\"p_comment\":\"Позвоните\",\"p_pickup\":false}");
        java.util.List<String[]> bad = new java.util.ArrayList<String[]>();
        bad.add(new String[]{u1, "0"});
        eq("10.122: изменение — неверное не отправляем", IvaOrderEditPatch.editBody("7", bad, null, false) + "|" + IvaOrderEditPatch.editBody("7", new java.util.ArrayList<String[]>(), null, false)
            + "|" + IvaOrderEditPatch.editBody("x", keep, null, false), "null|null|null");
        eq("10.122: решение / ответ — тела", IvaOrderEditPatch.decideBody("5", true) + "|" + IvaOrderEditPatch.decideBody("", false) + "|"
            + IvaOrderEditPatch.answerBody("9", "took") + "|" + IvaOrderEditPatch.answerBody("9", "maybe"),
            "{\"p_change\":5,\"p_ok\":true}|null|{\"p_id\":9,\"p_answer\":\"took\"}|null");
        eq("10.122: отменить можно, пока ничего не выдано", IvaOrderEditPatch.canCancel(java.util.Arrays.asList("new", "new")) + "|"
            + IvaOrderEditPatch.canCancel(java.util.Arrays.asList("new", "rented")) + "|" + IvaOrderEditPatch.canCancel(java.util.Arrays.asList("cancelled"))
            + "|" + IvaOrderEditPatch.canCancel(java.util.Arrays.asList("new", "cancelled")) + "|" + IvaOrderEditPatch.canCancel(null), "true|false|false|true|false");
        eq("10.122: ответы базы — тексты", IvaOrderEditPatch.decideDone("\"issued\"") + "|" + IvaOrderEditPatch.decideDone("\"accepted\"") + "|"
            + IvaOrderEditPatch.answerDone("\"took\"") + "|" + IvaOrderEditPatch.answerDone("\"gone\""),
            "Инструмент уже выдан — изменение не применено|Изменение принято — заявка обновлена|Спасибо! Заявка отмечена как выполненная|Вопрос уже не актуален");
        eq("10.122: доля отмен и тревога", IvaOrderEditPatch.share(3, 20) + "|" + IvaOrderEditPatch.share(0, 0) + "|" + IvaOrderEditPatch.alarm(3, 10) + "|"
            + IvaOrderEditPatch.alarm(2, 4) + "|" + IvaOrderEditPatch.alarm(3, 11), "3 из 20 (15 %)|заявок нет|true|false|false");
        eq("10.122: статус «Отменена клиентом»", IvaOrderPatch.ordEffStatus("new", "cancelled,cancelled") + "|" + IvaOrderPatch.ordEffStatus("new", "cancelled,closed")
            + "|" + IvaOrderPatch.ordStatus("cancelled") + "|" + IvaOrderViewPatch.kind("cancelled", 0, 1) + "|" + IvaOrderViewPatch.KIND_TEXT[4] + "|"
            + IvaOrderViewPatch.orderStatus(java.util.Arrays.asList("cancelled")) + "|" + IvaOrderViewPatch.orderStatus(java.util.Arrays.asList("cancelled", "closed")),
            "cancelled|closed|Отменена клиентом|4|Отменена клиентом|cancelled|closed");
        eq("10.122: адреса функций заявок", IvaOrderEditPatch.CANCEL_RPC + "|" + IvaOrderEditPatch.EDIT_RPC + "|" + IvaOrderEditPatch.DECIDE_RPC,
            "/rest/v1/rpc/iva_order_cancel|/rest/v1/rpc/iva_order_edit|/rest/v1/rpc/iva_order_edit_decide");
    }

    static void v123() {
        String NB = "\u00a0";
        java.util.List<IvaOrderPatch.Line> ls = new java.util.ArrayList<IvaOrderPatch.Line>();
        ls.add(IvaOrderPatch.line("ta", "Дрель", 500, 0, true, 30, false, "adm@x.ru", 3));
        ls.add(IvaOrderPatch.line("tb", "Пила", 700, 0, true, 50, false, "adm@x.ru", 5));
        String[] d = IvaOrderPatch.delivery("960 ₽ (12.0 км)", ls);             // штатно: 12 км × (30 + 50)
        eq("10.123: туда — км × наибольшая цена (12 × 50)", d[0], "600 ₽ (12.0 км)");
        eq("10.123: туда и обратно, сроки разные — обратно за каждым сроком", d[1],
            "1" + NB + "560 ₽ (туда 600 ₽; обратно за каждым сроком: 360 ₽ через 3 дня + 600 ₽ через 5 дней; 12.0 км в одну сторону)");
        eq("10.123: итог читается по первой сумме", IvaPatch.rub(d[1]), 1560);
        java.util.List<IvaOrderPatch.Line> same = new java.util.ArrayList<IvaOrderPatch.Line>();
        same.add(IvaOrderPatch.line("ta", "Дрель", 500, 0, true, 30, false, "adm@x.ru", 3));
        same.add(IvaOrderPatch.line("tb", "Пила", 700, 0, true, 50, false, "adm@x.ru", 3));
        String[] s2 = IvaOrderPatch.delivery("1 200 ₽ (15.0 км)", same);
        eq("10.123: срок один — туда и обратно = 2 × км × наибольшая", s2[0] + " | " + s2[1],
            "750 ₽ (15.0 км) | 1" + NB + "500 ₽ (туда и обратно: 2 × 750 ₽, 15.0 км в одну сторону)");
        java.util.List<IvaOrderPatch.Line> three = new java.util.ArrayList<IvaOrderPatch.Line>(ls);
        three.add(IvaOrderPatch.line("tc", "Миксер", 300, 0, true, 40, false, "adm@x.ru", 3));
        three.add(IvaOrderPatch.line("s1", "Услуга", 300, 0, true, 99, true, "adm@x.ru", 7));
        String[] s3 = IvaOrderPatch.delivery("1 440 ₽ (12.0 км)", three);       // 12 × (30 + 50 + 40); услуга не считается
        eq("10.123: в сроке — наибольшая цена этого срока, количество и услуги не считаются", s3[0] + " | " + s3[1],
            "600 ₽ (12.0 км) | 1" + NB + "680 ₽ (туда 600 ₽; обратно за каждым сроком: 480 ₽ через 3 дня + 600 ₽ через 5 дней; 12.0 км в одну сторону)");
        eq("10.123: без цены / не нашли адрес — как было", IvaOrderPatch.delivery("≈12.0 км — цену за километр уточнит владелец", ls) == null
            && IvaOrderPatch.delivery("адрес не найден — уточните адрес", ls) == null && IvaOrderPatch.delivery("960 ₽ (12.0 км)", new java.util.ArrayList<IvaOrderPatch.Line>()) == null, true);
        eq("10.123: наибольшая цена за км (подпись)", IvaOrderPatch.maxRate(three) + "|" + IvaOrderPatch.maxRate(null), "50.0|0.0");
        eq("10.123: без окна — onPrice как было, «обратно» не задано", IvaOrderPatch.onPrice("960 ₽ (12.0 км)") + "|" + IvaOrderPatch.lastRound, "960 ₽ (12.0 км)|null");
        IvaPatch.round = true;
        eq("10.123: без окна — туда и обратно как раньше (×2)", IvaPatch.onPrice("600 ₽ (12.0 км)"), "1" + NB + "200 ₽ (туда и обратно: 2 × 600 ₽, 12.0 км в одну сторону)");
        IvaPatch.round = false;
        eq("10.123: подписи цены доставки", IvaFormPatch.HINTS[5][1] + "|" + IvaToolEditPatch.LABELS[8][1], "Доставка, ₽ за 1 км в одну сторону|Доставка, ₽ за 1 км в одну сторону");
        eq("10.123: плашка состояния инструмента", IvaMyToolsPatch.statusKind("Опубликован") + "" + IvaMyToolsPatch.statusKind("Скрыт") + IvaMyToolsPatch.statusKind("Отклонён")
            + IvaMyToolsPatch.statusKind("Правка отклонена") + IvaMyToolsPatch.statusKind("На одобрении") + IvaMyToolsPatch.statusKind("Ждёт удаления")
            + IvaMyToolsPatch.statusKind(""), "0122331");
    }
}
