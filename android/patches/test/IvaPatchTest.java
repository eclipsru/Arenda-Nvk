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
            eq("уведомления 10.57: логотип в шторке", IvaNotifyPatch.LARGE_ICON_PX, 192);
        }
        if (fail > 0) { System.out.println("ошибок: " + fail); System.exit(1); }
        System.out.println("все проверки IvaPatch пройдены");
    }
}
