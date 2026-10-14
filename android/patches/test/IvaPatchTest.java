// Проверка чистой логики IvaPatch на обычной Java (без телефона). Запускается в build-apk.yml.
package ru.prokatnvsk.app;

public class IvaPatchTest {
    static int fail = 0;
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
            eq("уведомления 10.56: проверка обновлений — рано", IvaNotifyPatch.updateDue(1000L, 1000L + 5L * 3600000), false);
            eq("уведомления 10.56: проверка обновлений — через 6 часов", IvaNotifyPatch.updateDue(1000L, 1000L + 6L * 3600000), true);
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
            eq("голосовые 10.60: непрослушанное — полупрозрачный белый", Integer.toHexString(IvaChatPatch.VOICE_REST_MINE) + "|" + Integer.toHexString(IvaChatPatch.VOICE_REST_OTHER), "73ffffff|59ffffff");
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
        if (fail > 0) { System.out.println("ошибок: " + fail); System.exit(1); }
        System.out.println("все проверки IvaPatch пройдены");
    }
}
