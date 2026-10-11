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
        if (fail > 0) { System.out.println("ошибок: " + fail); System.exit(1); }
        System.out.println("все проверки IvaPatch пройдены");
    }
}
