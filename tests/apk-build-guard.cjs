// Сторож «сборка приложения» (05.10.2026, баг 1 владельца: туда-обратно, доставка, публикация младшим админом).
// Ловит: вывод ключа/пароля (set -x, echo, пароль в командной строке), запуск из Pull Request,
// подпись без сверки сертификата с эталоном ключа №6, эталон ≠ памятке, коммит APK не в ветку агента,
// поломку смены версии в двоичном манифесте (проверяется на настоящем манифесте 10.14),
// пропажу любой из 12 точек apply.py (11 видов вызовов IvaPatch и замена текста кнопки доставки), кандидата без нужной версии,
// и что фраза «Нет связи с сервером…» в classes.dex 10.14 одна (точка 6 однозначна).
// Запуск: node tests/apk-build-guard.cjs
const fs = require('node:fs'), path = require('node:path'), os = require('node:os'), assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const ROOT = path.join(__dirname, '..');
const R = p => fs.readFileSync(path.join(ROOT, p), 'utf8');
try {
  const f = '.github/workflows/build-apk.yml';
  const code = R(f).replace(/^\s*#.*$/gm, '');
  assert.ok(!/pull_request/.test(code), `${f}: запуск из Pull Request запрещён`);
  assert.ok(!/set\s+-[a-z]*x/.test(code), `${f}: set -x покажет секреты`);
  for (const s of ['KS_PASS', 'KS_B64'])
    assert.ok(!new RegExp(`(echo|bad)[^\\n]*\\$\\{?${s}`).test(code), `${f}: ${s} выводится в журнал`);
  assert.ok(!/-storepass\s+["$]/.test(code) && !/--ks-pass\s+pass:/.test(code), `${f}: пароль в командной строке`);
  assert.match(code, /-storepass:env KS_PASS/); assert.match(code, /--ks-pass env:KS_PASS/);
  assert.match(code, /trap 'rm -f "\$KS"' EXIT/, `${f}: временный файл ключа не удаляется`);
  assert.match(code, /\[ "\$\{CERT\^\^\}" = "\$EXPECTED" \] \|\| bad/, `${f}: нет сверки сертификата с эталоном`);
  assert.match(code, /apksigner" verify "\$A" \|\| bad/, `${f}: нет apksigner verify`);
  assert.match(code, /versionCode='\$NEW_CODE'/); assert.match(code, /versionName='\$NEW_NAME'/);
  assert.match(code, /if: startsWith\(github\.ref, 'refs\/heads\/arena\/'\)/, `${f}: APK коммитится только в ветку агента (arena/…)`);
  assert.ok(!/arena\/01a101d5/.test(code), `${f}: осталось имя старой ветки агента — при смене сессии его не поменяли бы`);
  assert.ok(!/git add[^\n]*(out\/|\*|\.p12|\$KS)/.test(code), `${f}: в коммит попадает лишнее`);
  // эталон = памятка = проверка ключа
  const exp = (code.match(/EXPECTED: ([0-9A-F]{64})/) || [])[1];
  const doc = (R('docs/КЛЮЧ-ПОДПИСИ.md').match(/Эталон проверки: `([0-9A-F]{64})`/) || [])[1];
  assert.ok(exp && exp === doc, `${f}: эталон ${exp} ≠ памятке ${doc}`);
  assert.match(R('.github/workflows/keystore-check.yml'), new RegExp('EXPECTED=' + exp));
  const ap = R('android/patches/apply.py');
  // вставки: вызов IvaPatch в smali (их ровно 11, 11-я — новый метод onNewIntent); виньетка добавляется внутри уже имеющейся точки onCreated
  const insertions = ['->onPrice(Ljava/lang/String;)Ljava/lang/String;', '->onSummary(Landroid/app/Activity;)V',
    '->onHub(Landroid/app/Activity;)V', '->publishError(Ljava/lang/String;)Ljava/lang/String;',
    '->netError(Ljava/lang/String;)Ljava/lang/String;', '->onCreated(Landroid/app/Activity;)V',
    '->onSession(Landroid/app/Activity;)V', '->onAvatar(Landroid/app/Activity;Ljava/lang/String;)V',
    '->onLogout(Landroid/app/Activity;)V', '->onTab(Landroid/app/Activity;Ljava/lang/String;)V',
    '->onNewIntent(Landroid/app/Activity;Landroid/content/Intent;)V'];
  // якоря: по ним apply.py находит место вставки (метод, поле «avatar», тело updateSummary-подобных методов)
  const anchors = ["one_method(lines, r' paintDelToggle\\(Z\\)V$')", "one_method(lines, r' logoutLocal\\(\\)V$')",
    "one_method(lines, r' showTab\\(Ljava/lang/String;\\)V$')", 'lambda\\$loadAdminProfile\\$201', '"avatar"'];
  for (const k of [...insertions, ...anchors]) assert.ok(ap.includes(k), `apply.py: нет ${k}`);
  const jp = R('android/patches/IvaPatch.java');
  const jpc = R('android/patches/IvaClientPatch.java');
  // 10.40 — штатная строка «Клиент» остаётся единственной точкой входа, но открывает
  // отдельный полноэкранный экран настроек (не AlertDialog-форму); hook onActivityResult
  // в MainActivity по-прежнему отключён (с 10.39), фото возвращается во вспомогательный Fragment.
  assert.match(code, /android\/patches\/IvaPatch\.java android\/patches\/IvaClientPatch\.java android\/patches\/IvaSearchPatch\.java android\/patches\/IvaChatPatch\.java android\/patches\/IvaOrderPatch\.java android\/patches\/IvaNotifyPatch\.java android\/patches\/IvaReviewPatch\.java(?: android\/patches\/Iva\w+Patch\.java)* 2>&1/,
    `${f}: шаг 10.30 не компилирует IvaClientPatch`);
  assert.match(ap, /IvaClientPatch;->onProfile\(Landroid\/app\/Activity;\)V/,
    `${f}: hook onProfile не подключён`);
  assert.doesNotMatch(ap, /IvaClientPatch;->onActivityResult\(Landroid\/app\/Activity;IILandroid\/content\/Intent;\)V/,
    `${f}: recovery-сборка не должна вызывать потенциально проблемный hook onActivityResult`);
  for (const call of ['onFeed', 'onCatalogLoaded', 'onLocationPermission', 'onDetail'])
    assert.doesNotMatch(ap, new RegExp(`IvaClientPatch;->${call}\\(`),
      `${f}: hook ${call} должен оставаться выключенным`);
  for (const k of ['onProfile(Activity', 'onActivityResult(final Activity', 'onFeed(Activity', 'onDetail(Activity', 'installHomeFilter(final Activity', 'loadNativeClientCoords(final Activity'])
    assert.ok(jpc.includes(k), `IvaClientPatch.java: нет метода ${k}`);
  assert.doesNotMatch(jpc, /host\.addView\(card, 0, lp\)/, 'IvaClientPatch: белая верхняя карточка клиента не должна добавляться');
  assert.match(jpc, /title\.setText\("Настройки профиля"\)/, 'IvaClientPatch: нет заголовка экрана настроек профиля');
  // Экран, а не форма-диалог: полноэкранная тема, своя шапка «←», системная «Назад» закрывает экран.
  assert.match(jpc, /static final class ProfileScreen/, 'IvaClientPatch: нет экрана настроек профиля ProfileScreen');
  assert.match(jpc, /new Dialog\(a, android\.R\.style\.Theme_Material_NoActionBar\)/,
    'IvaClientPatch: экран настроек должен открываться полноэкранным окном (Theme_Material_NoActionBar)');
  assert.match(jpc, /public void onBackPressed\(\) \{ requestClose\(\); \}/, 'IvaClientPatch: «Назад» должна закрывать экран через requestClose');
  assert.match(jpc, /back\.setText\("\\u2190"\)/, 'IvaClientPatch: в шапке экрана нет стрелки «←»');
  assert.doesNotMatch(jpc, /\.setView\(scroll\)/, 'IvaClientPatch: форма профиля снова открывается диалогом AlertDialog');
  assert.doesNotMatch(jpc, /setTitle\("Настройки профиля клиента"\)/, 'IvaClientPatch: форма профиля снова открывается диалогом AlertDialog');
  assert.match(jpc, /onClick\(View v\) \{ openClientProfileScreen\(a\); \}/, 'IvaClientPatch: строка «Клиент» не открывает экран настроек');
  // Сохранение — только после загрузки записи с сервера: иначе пустая форма затёрла бы паспорт, город и фото.
  assert.match(jpc, /boolean enabled = loaded && !saving && !uploading;/, 'IvaClientPatch: «Сохранить» доступна до загрузки профиля');
  assert.match(jpc, /body\.put\("p_city", clientProfile\.optString\("city", ""\)\)/, 'IvaClientPatch: город профиля не сохраняется как был');
  // Фото: результат выбора — во вспомогательный Fragment, без hook в MainActivity.
  assert.match(jpc, /public static final class AvatarPicker extends android\.app\.Fragment/, 'IvaClientPatch: нет помощника AvatarPicker для выбора фото');
  assert.match(jpc, /public AvatarPicker\(\) \{\}/, 'IvaClientPatch: AvatarPicker должен иметь публичный конструктор без параметров (восстановление после смерти процесса)');
  assert.match(jpc, /f\.startActivityForResult\(/, 'IvaClientPatch: выбор фото должен запускаться от имени AvatarPicker');
  // 10.41: фото клиента — в штатном кружке строки «Клиент» и в кружке «Кабинета» нижнего меню.
  assert.match(jpc, /\n        try \{ showClientAvatar\(a\); \} catch \(Throwable ignore\) \{\}\n/, 'IvaClientPatch: onProfile не показывает фото клиента');
  assert.match(jpc, /Object img = get\(a, "avatarImg"\);[\s\S]{0,500}iv\.setVisibility\(View\.VISIBLE\);/, 'IvaClientPatch: фото клиента не ставится в строку «Клиент» (avatarImg)');
  assert.match(jpc, /static void putClientAvatar[\s\S]{0,1200}box\.findViewWithTag\(AV_TAG\)[\s\S]{0,600}box\.addView\(av, new FrameLayout\.LayoutParams\(s, s, Gravity\.CENTER\)\)/, 'IvaClientPatch: фото клиента не ставится в нижнее меню');
  assert.match(jpc, /if \(!signedIn\) \{[\s\S]{0,300}clientAvatarBitmap = null;[\s\S]{0,200}restoreClientHead\(a\)/, 'IvaClientPatch: после выхода фото клиента должно убираться');
  assert.match(jpc, /if \(!isClient\(a\)\) return;   \/\/ админ/, 'IvaClientPatch: фото админа ведёт штатный код — клиентский код его не трогает');
  assert.match(jpc, /rememberClientAvatar\(avatarCacheKey\(clientProfileEmail, path\), preview\);\n\s*showClientAvatar\(a\);/, 'IvaClientPatch: новое фото не попадает в строку и меню после загрузки');
  // 10.42: имя из поля «Имя» вместо «Клиент» в строке кабинета; ФИО и телефон переживают перезапуск.
  assert.match(jpc, /try \{ showClientName\(a\); \} catch \(Throwable ignore\) \{\}\n        try \{ showClientAvatar\(a\); \}/, 'IvaClientPatch: onProfile не пишет имя клиента в строку');
  assert.match(jpc, /String name = clientHeadName\(clientProfile\.optString\("given_name", ""\)\);[\s\S]{0,120}if \(name\.isEmpty\(\)\) return;[\s\S]{0,120}Object pn = get\(a, "profName"\);\n\s*if \(pn instanceof TextView\) \(\(TextView\) pn\)\.setText\(name\);/, 'IvaClientPatch: в строке «Клиент» должно быть имя из поля «Имя»');
  assert.match(jpc, /static void showClientName\(Activity a\) \{\n\s*try \{\n\s*if \(!isClient\(a\)\) return;/, 'IvaClientPatch: имя админа ведёт штатный код');
  assert.match(jpc, /e\.putString\("name", full\)/, 'IvaClientPatch: ФИО клиента не сохраняется в настройках телефона (после перезапуска снова «Клиент»)');
  assert.match(jpc, /if \(err == null\) \{\n\s*try \{ showClientName\(a\); \}/, 'IvaClientPatch: имя не обновляется после загрузки профиля');
  // 10.43: логотип у клиента — раздел «Избранное / Мои инструменты» вместо формы заявки; у админа и гостя — штатный onPlus.
  assert.match(jpc, /client = isClient\(a\);[\s\S]{0,120}if \(client && openClientHub\(a\)\) return;\n\s*callNative\(a, "onPlus"\);/, 'IvaClientPatch: логотип у админа и гостя должен вести в штатный onPlus');
  assert.match(jpc, /View plus = a\.findViewById\(id\(a, "navPlus"\)\);/, 'IvaClientPatch: обработчик логотипа не ставится на navPlus');
  assert.match(jpc, /if \(toHub && openClientHub\(a\)\) return;\n\s*callNativeTab\(a, "home"\);/, 'IvaClientPatch: «Назад» в карточке инструмента должна вести на главную, как штатная');
  assert.match(jpc, /try \{ installClientNav\(a\); \} catch \(Throwable ignore\) \{\}\n\s*try \{ if \(hubVisible && !isClient\(a\)\) callNativeTab\(a, "home"\); \}/, 'IvaClientPatch: onProfile не ставит логотип клиента или не прячет раздел при выходе');
  assert.match(jp, /try \{ IvaClientPatch\.onShowTab\(a, tab\); \} catch \(Throwable ignore\) \{\}\n(?:\s*\/\/[^\n]*\n)?\s*try \{ IvaSearchPatch\.onShowTab\(a, tab\); \} catch \(Throwable ignore\) \{\}\n(?:\s*\/\/[^\n]*\n)?\s*try \{ IvaChatPatch\.onShowTab\(a, tab\); \} catch \(Throwable ignore\) \{\}\n(?:\s*\/\/[^\n]*\n)?\s*try \{ IvaOrderPatch\.onShowTab\(a, tab\); \} catch \(Throwable ignore\) \{\}\n(?:\s*\/\/[^\n]*\n)?\s*try \{ IvaNotifyPatch\.onShowTab\(a, tab\); \} catch \(Throwable ignore\) \{\}\n    \}/, 'IvaPatch.onTab: штатный переход должен прятать раздел клиента, включать/прятать поиск 10.45, чат 10.54, заявку 10.55 и уведомления 10.56');
  assert.match(jpc, /if \(hubVisible\) \{\n\s*detailFromHub = "detail"\.equals\(t\);\n\s*hideClientHub\(a\);/, 'IvaClientPatch: раздел клиента не прячется при штатном переходе');
  assert.match(jpc, /hubStyling = true;\n\s*try \{ IvaPatch\.onTab\(a, HUB_PAGE\); \}[^\n]*finally \{ hubStyling = false; \}/, 'IvaClientPatch: подсветка логотипа не должна прятать сам раздел');
  assert.match(jpc, /favs\.contains\("t" \+ tid\)/, 'IvaClientPatch: «Избранное» должно брать штатный набор favs (ключ t+id)');
  assert.match(jpc, /getDeclaredMethod\("feedCard", JSONObject\.class, boolean\.class\)/, 'IvaClientPatch: карточки раздела — штатный feedCard');
  assert.match(jpc, /"\/rest\/v1\/orders\?select=created_at,tools,status" \+ q/, 'IvaClientPatch: «Мои инструменты» должны читать заявки клиента');
  assert.match(jpc, /"&user_email=eq\." \+ java\.net\.URLEncoder\.encode\(email, "UTF-8"\)/, 'IvaClientPatch: заявки — только свои (user_email)');
  // Выдачу отмечает арендодатель в части заявки (order_parts.status) — «Брали в аренду» должно её учитывать.
  assert.match(jpc, /"\/rest\/v1\/orders\?select=created_at,tools,status,order_parts\(status,tools_text\)" \+ q/, 'IvaClientPatch: «Мои инструменты» не читают статусы частей заявки (order_parts)');
  assert.match(jpc, /done = partMarksRented\(part\.optString\("status", ""\), part\.optString\("tools_text", ""\), name\);/, 'IvaClientPatch: выдача по части заявки не учитывается');
  for (const k of ['"Избранное"', '"Мои инструменты"', 'HUB_TAG', 'orderMentionsTool(String', 'isRentedStatus(String', 'if (hubTab == 0) renderHubFavorites'])
    assert.ok(jpc.includes(k), `IvaClientPatch.java: нет части раздела логотипа ${k}`);
  assert.ok(!/IvaClientPatch\.(onShowTab|openClientHub|installClientNav)/.test(R('android/patches/apply.py')), 'apply.py: раздел логотипа не должен добавлять точек вставки');
  assert.match(jpc, /profileHead/, 'IvaClientPatch: строка «Клиент» не подключена к профилю');
  assert.match(jpc, /installClientProfileHead\(a\)/, 'IvaClientPatch: обработчик строки «Клиент» не устанавливается');
  assert.match(jpc, /depth < 3/, 'IvaClientPatch: оболочки строки «Клиент» не подключены');
  for (const k of ['avatarImage', 'Изменить фото', 'CLIENT_AVATAR_REQUEST', 'image/*', 'CATEGORY_OPENABLE', 'BitmapFactory.decodeByteArray',
    'prepareAvatar(byte[]', 'uploadClientAvatar(Activity', 'downloadClientAvatar(Activity', 'customer-avatars'])
    assert.ok(jpc.includes(k), `IvaClientPatch.java: нет функции аватарки ${k}`);
  for (const k of ['onPrice(String', 'onSummary(final Activity', 'onHub(Activity', 'publishError(String', 'netError(String',
    'onCreated(Activity', 'onSession(Activity', 'onAvatar(final Activity', 'onLogout(final Activity', 'onTab(Activity',
    'navSelected(String', 'isLogoTab(String', 'styleAvatarOutline(Activity', 'wantsAvatar(String', 'installHomeVignette(Activity',
    'HomeVignette extends View', 'new RadialGradient', 'HOME_VIGNETTE_TAG', 'ViewOutlineProvider', 'setClipToOutline(true)', 'setRoundRect', 'px(float'])
    assert.ok(jp.includes('static ') && jp.includes(k), `IvaPatch.java: нет метода ${k}`);
  // точка 6 есть в настоящем APK: фраза «Нет связи с сервером…» лежит в classes.dex 10.14 ровно один раз —
  // значит вставка однозначна и владелец увидит причину именно там, где раньше была общая фраза (07.10.2026)
  const NET = 'Нет связи с сервером. Проверьте интернет и попробуйте ещё раз.';
  const dex = execFileSync('python3', ['-c',
    'import zipfile,sys;sys.stdout.buffer.write(zipfile.ZipFile(sys.argv[1]).read("classes.dex"))',
    path.join(ROOT, 'ProkatInstrumenta-10.14.apk')], { maxBuffer: 64 * 1024 * 1024 });
  let n = 0, from = 0, needle = Buffer.from(NET, 'utf8');
  while ((from = dex.indexOf(needle, from)) !== -1) { n++; from += needle.length; }
  assert.equal(n, 1, `в classes.dex 10.14 фраза «Нет связи с сервером…» встречается ${n} раз, нужно ровно 1`);
  assert.ok(/catch \(Throwable ignore\)/.test(jp), 'IvaPatch: доработка должна молча отступать при сбое, не ломая заявку');
  // 10.21: то, на что опирается патч, есть в настоящем APK 10.14 (иначе на телефоне вставка молча не сработает)
  const zip14 = entry => execFileSync('python3', ['-c',
    'import zipfile,sys;sys.stdout.buffer.write(zipfile.ZipFile(sys.argv[1]).read(sys.argv[2]))',
    path.join(ROOT, 'ProkatInstrumenta-10.14.apk'), entry], { maxBuffer: 64 * 1024 * 1024 });
  const dex14 = Buffer.concat([zip14('classes.dex'), zip14('classes2.dex')]), arsc14 = zip14('resources.arsc');
  const has = (buf, str) => buf.includes(Buffer.from(str, 'utf8')) || buf.includes(Buffer.from(str, 'utf16le'));
  for (const str of ['logoutLocal', 'saveSession', 'onCreate', 'lambda$loadAdminProfile$201', 'CircleImageView', 'loadPhoto', 'loadAdminProfile', 'navProfIc', 'accessToken', 'avatarImg', 'avatarTxt', 'userEmail', 'profName', 'userName', 'prefs'])
    assert.ok(has(dex14, str), `classes.dex 10.14: нет «${str}» — точка вставки или имя для патча пропали`);
  for (const str of ['navPlus', 'navProfIc', 'ic_logo'])
    assert.ok(has(arsc14, str), `resources.arsc 10.14: нет ресурса «${str}» — логотип или аватарка не найдут место в меню`);
  assert.equal(zip14('res/drawable-nodpi/ic_logo.png').subarray(1, 4).toString('latin1'), 'PNG', '10.14: нет логотипа ic_logo.png');
  // 10.45: поиск на главной (IvaSearchPatch) — без новых точек вставки, на штатных полях и ресурсах 10.14.
  const js45 = R('android/patches/IvaSearchPatch.java');
  assert.ok(!/IvaSearchPatch/.test(ap), 'apply.py: поиск 10.45 не должен добавлять точек вставки в smali');
  for (const str of ['pageHome', 'searchBox', 'feedGrid', 'suggestBox', 'pageBg', 'renderFeed', 'renderCatGrid', 'curCat', 'curSub', 'catMode', 'subcats', 'prokat_session', 'Каталог пуст', 'Ничего не найдено'])
    assert.ok(has(dex14, str), `classes.dex 10.14: нет «${str}» — поиск 10.45 опирается на это имя`);
  for (const str of ['bg_chip', 'bg_chip_on', 'bg_search', 'bg_field', 'bg_card', 'bg_btn_orange'])
    assert.ok(has(arsc14, str), `resources.arsc 10.14: нет «${str}» — фильтры потеряют штатный вид`);
  const cities = s => (s.match(/'[^']+'|"[^"]+"/g) || []).map(x => x.slice(1, -1));
  const webCities = cities(R('assets/data.js').match(/const CITIES = \[([\s\S]*?)\];/)[1]);
  const appCities = cities(js45.match(/static final String\[\] CITIES = \{([\s\S]*?)\};/)[1]);
  assert.deepEqual(appCities, webCities, 'IvaSearchPatch.CITIES должен совпадать с CITIES в assets/data.js');
  for (const k of ['"Ваш город"', '"Выберите город"', '"Сбросить"', '"Фильтры"', 'showLabel(n)', 'KeyEvent.KEYCODE_BACK',
    'requestDisallowInterceptTouchEvent(true)', 'OnHierarchyChangeListener', 'K_CITY', 'K_SEEN',
    '"/auth/v1/user"', '"/rest/v1/rpc/get_my_customer_profile"', 'Город в профиле не меняется'])
    assert.ok(js45.includes(k), `IvaSearchPatch.java: нет ${k}`);
  // 10.46: ручка внизу над нижним меню, шторка снизу (85%), левой полосы нет; 10.47 — до шапки, 10.48 — снова 85% (решение владельца)
  for (const k of ['BAR_DP = 22', 'SHEET_MAX = 0.85f', 'new FrameLayout.LayoutParams(-1, dp(a, BAR_DP), Gravity.BOTTOM)', 'hlp.bottomMargin = dp(a, BAR_DP)',
    'sheet.setTranslationY((1f - progress) * sheetSize)', 'setProgress(a, p0 - dy / sheetSize)', 'DragListener.HANDLE', 'DragListener.GRAB', 'DragListener.SCRIM',
    'Gravity.BOTTOM));', 'fitSheet(a)', 'LengthFilter(7)', 'facetVisible(catKeys, catCounts, draft.cat)', 'facetVisible(subKeys, subCounts, draft.sub)',
    'normRange(from, to)', '"Радиус \\u00b7 скоро"', 'rc.setEnabled(false)',
    // поля для «Показать N» читаются как в штатной ленте (optString: null → "null"), не через js()
    'return nativeMatch(j.optString("t_status", ""), j.optString("cat", ""), catsOf(j), j.optString("sub", ""),\n            j.optString("name", ""), j.optString("descr", ""), catMode, curCat, curSub, query);'])
    assert.ok(js45.includes(k), `IvaSearchPatch.java (10.46): нет ${k}`);
  assert.ok(!/STRIP_DP|RIGHT_DP|Gravity\.LEFT \| Gravity\.CENTER_VERTICAL|content\.setPadding\(/.test(js45), 'IvaSearchPatch: левая полоса с ручкой должна быть убрана (10.46)');
  // 10.47: панель становится видимой ДО заполнения — иначе refreshPanel молчит и шторка открывается пустой
  // (10.46: категории, числа и надписи кнопок появлялись только после нажатия на «доставку»)
  {
    const pp = js45.slice(js45.indexOf('static void preparePanel('), js45.indexOf('static View handleView('));
    const vis = pp.indexOf('panelRoot.setVisibility(View.VISIBLE);'), build = pp.indexOf('buildSheet(a);');
    assert.ok(vis > 0 && build > vis, 'IvaSearchPatch.preparePanel: панель должна стать видимой до buildSheet (иначе шторка пустая)');
    assert.ok(js45.includes('if (draft == null || sheet == null || panelRoot == null || panelRoot.getVisibility() != View.VISIBLE) return;')
      && /refreshPanel\(a\);\n    \}\n\n    static EditText priceField/.test(js45), 'IvaSearchPatch.buildSheet должен заканчиваться refreshPanel');
    // выбранные условия в шапке шторки — текстом с ✕, в ленте под поиском — как раньше
    assert.ok(js45.includes('@Override public void run() { syncInputs(a); refreshPanel(a); }\n            }, true);')
      && js45.includes('TextView c = plain ? plainChip(a, text) : chip(a, text, true, true);'), 'IvaSearchPatch: выбранное в шапке шторки — простым текстом с ✕');
    // 10.48: выбранное в шапке — один ряд с прокруткой вбок свайпом, без полосы прокрутки
    assert.ok(js45.includes('HorizontalScrollView hs = new HorizontalScrollView(a);\n        hs.setHorizontalScrollBarEnabled(false);')
      && js45.includes('pc.setOrientation(LinearLayout.HORIZONTAL);') && js45.includes('hs.addView(pc, new FrameLayout.LayoutParams(-2, -2));')
      && !/static Flow panelChips/.test(js45), 'IvaSearchPatch: выбранное в шапке шторки — один ряд, прокрутка вбок без полосы');
    // 10.48: нажатие на полосу с «язычком» вверху шторки закрывает её (и касанием, и через TalkBack)
    assert.ok(js45.includes('grabBar.setOnTouchListener(new DragListener(a, DragListener.GRAB));')
      && js45.includes('} else if (kind == SCRIM || kind == GRAB) {')
      && js45.includes('grabBar.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { closePanel(a, true); } });'),
      'IvaSearchPatch: нажатие на полосу вверху шторки должно закрывать её');
    const pc = js45.slice(js45.indexOf('static TextView plainChip('), js45.indexOf('static void addActiveChips('));
    assert.ok(!/setBackground\(|bg_chip/.test(pc), 'IvaSearchPatch.plainChip: без фона-«пузыря»');
  }
  // 10.49: шапка шторки прокручивается вместе с фильтрами, закреплена только полоса с «язычком» (и кнопки внизу)
  {
    const bs = js45.slice(js45.indexOf('static void buildSheet('), js45.indexOf('static EditText priceField('));
    assert.ok(bs.includes('sh.addView(grabBar, new LinearLayout.LayoutParams(-1, dp(a, 22)));')
      && bs.includes('scroll.addView(head, new LinearLayout.LayoutParams(-1, -2));') && bs.includes('scroll.addView(hs, new LinearLayout.LayoutParams(-1, -2));')
      && bs.includes('scroll.addView(body, new LinearLayout.LayoutParams(-1, -2));') && !bs.includes('sh.addView(head') && !bs.includes('sh.addView(hs'),
      'IvaSearchPatch.buildSheet: шапка шторки должна прокручиваться вместе с фильтрами, закреплена только полоса');
    assert.ok(bs.indexOf('sh.addView(grabBar') < bs.indexOf('sh.addView(sc,') && bs.indexOf('sh.addView(sc,') < bs.indexOf('sh.addView(foot'),
      'IvaSearchPatch.buildSheet: порядок — полоса, прокрутка, кнопки внизу');
    // главная: «Фильтр» справа от города открывает шторку; поиск 36dp; ряд условий — вбок без полосы и без кнопки «Фильтры»
    const row = js45.slice(js45.indexOf('static View buildCityRow('), js45.indexOf('static void refreshCityRow('));
    assert.ok(row.includes('fb.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openPanel(a); } });')
      && row.includes('row.addView(fb, flp);') && row.indexOf('row.addView(cityBox, blp);') < row.indexOf('row.addView(fb, flp);'),
      'IvaSearchPatch: кнопка «Фильтр» справа от города должна открывать шторку');
    assert.ok(js45.includes('SEARCH_DP = 38;') && js45.includes('slp.height = dp(a, SEARCH_DP);') && js45.includes('CITY_BTN_DP = 31;')
      && js45.includes('CITY_ROW_DP = 35;') && (js45.match(/dp\(a, CITY_BTN_DP\)/g) || []).length === 3,
      'IvaSearchPatch: строка поиска 38dp, строка города 35dp, кнопки города и «Фильтр» 31dp (10.51)');
    // 10.52: картинка-шапка и ряд условий — от края до края на любом телефоне: контейнер главной не обрезает по полям,
    // отступы — из настоящих полей контейнера (не числом), верх картинки прямой, низ скруглён; виньетка 10.20 не тронута
    const fb = js45.slice(js45.indexOf('static void fullBleedHome('), js45.indexOf('static final class BottomRound'));
    const ins52 = js45.slice(js45.indexOf('static void install('), js45.indexOf('static void syncVisibility('));
    assert.ok(fb.includes('content.setClipToPadding(false);') && fb.includes('int l = -content.getPaddingLeft(), r = -content.getPaddingRight();')
      && fb.includes('lp.leftMargin = l;') && fb.includes('lp.rightMargin = r;') && fb.includes('IvaClientPatch.HOME_VIGNETTE_TAG.equals(')
      && fb.includes('top.setOutlineProvider(new BottomRound(dp(a, BG_RADIUS_DP)));') && fb.includes('android.os.Build.VERSION.SDK_INT >= 21')
      && js45.includes('outline.setRoundRect(0, -radius, view.getWidth(), view.getHeight(), radius);'),
      'IvaSearchPatch.fullBleedHome: картинка-шапка во всю ширину экрана по полям контейнера, верх прямой, низ скруглён');
    assert.ok(ins52.includes('fullBleedHome(a);') && /static void onHomeShown\(Activity a\) \{\n        hideCatGrid\(a\);\n        fullBleedHome\(a\);/.test(js45)
      && ins52.includes('clp.leftMargin = -edge;') && ins52.includes('clp.rightMargin = -content.getPaddingRight();')
      && ins52.includes('chips.setPadding(edge, 0, content.getPaddingRight(), 0);') && !/setClipToPadding\(false\)/.test(ins52),
      'IvaSearchPatch: «во всю ширину» включается при установке и при каждом показе главной; ряд условий — от края до края, первый «пузырь» по полю');
    assert.ok((js45.match(/setClipToPadding\(/g) || []).length === 1 && !js45.includes('setClipToOutline(false)')
      && (fb.match(/setClip\w*\(/g) || []).length === 2 && fb.includes('top.setClipToOutline(true);'),
      'IvaSearchPatch: обрезка по полям снимается только у контейнера главной (и больше нигде)');
    // 10.53: в шторке «Сбросить» / «Показать N» и поля цены — толщиной со строку поиска (обе кнопки и оба поля)
    assert.ok(js45.includes('SHEET_BTN_DP = SEARCH_DP;') && js45.includes('PRICE_FIELD_DP = SEARCH_DP;')
      && (js45.match(/dp\(a, SHEET_BTN_DP\)/g) || []).length === 2 && (js45.match(/dp\(a, PRICE_FIELD_DP\)/g) || []).length === 2,
      'IvaSearchPatch: кнопки шторки и поля цены — толщиной со строку поиска (10.53)');
    // 10.53: картинка-шапка — до строки города (условий нет) или до низа ряда условий; строка поиска на месте; обрезка снизу
    const fit = js45.slice(js45.indexOf('static void fitHeaderBg('), js45.indexOf('static void cropFromTop('));
    assert.ok(fit.includes('int searchTop = blp.height + slp.topMargin;') && fit.includes('slp.topMargin = searchTop - target;')
      && fit.includes('blp.height = target;') && fit.includes('Math.abs(target - blp.height) <= 1')
      && fit.includes('chipsScroll.getVisibility() == View.VISIBLE') && fit.includes('bgHeight(top.getTop(), row.getBottom(), shown ? chipsScroll.getBottom() : 0, shown, dp(a, BG_GAP_DP))')
      && fit.includes('home.getVisibility() != View.VISIBLE'),
      'IvaSearchPatch.fitHeaderBg: высота картинки по строке города / ряду условий, верх строки поиска не меняется, без зацикливания');
    assert.ok(js45.includes('BG_GAP_DP = 8;') && js45.includes('int bottom = chipsShown && chipsBottom > rowBottom ? chipsBottom : rowBottom;')
      && js45.includes('int h = Math.max(vh, refH);') && js45.includes('bgNativeH = top.getLayoutParams().height;')
      && js45.includes('bg.setScaleType(ImageView.ScaleType.MATRIX);')
      && js45.includes('installBgFit(a, home, content, searchFrame, row, chipsScroll);')
      && js45.indexOf('installBgFit(a, home, content, searchFrame, row, chipsScroll);') > js45.indexOf('content.addView(chipsScroll, content.indexOfChild(row) + 1, clp);')
      && (js45.match(/addOnGlobalLayoutListener\(/g) || []).length === 1,
      'IvaSearchPatch: подгонка картинки ставится один раз после ряда условий, обрезка только снизу (штатный масштаб)');
    assert.ok(/eq\("шапка 10\.53: ниже штатной — тот же масштаб и верх \(обрезка снизу\)"/.test(R('android/patches/test/IvaPatchTest.java')),
      'IvaPatchTest: проверки высоты и обрезки картинки-шапки 10.53');
    // 10.50: поиск — сверху на штатном месте (его отступ поверх картинки не трогаем), город — под подсказками, ряд условий — под городом
    const ins = js45.slice(js45.indexOf('static void install('), js45.indexOf('static void syncVisibility('));
    assert.ok(!/slp\.topMargin\s*=/.test(ins) && !ins.includes('rlp.topMargin = slp.topMargin'), 'IvaSearchPatch.install: отступ строки поиска (поверх картинки-шапки) должен остаться штатным');
    assert.ok(ins.includes('content.addView(row, below + 1, rlp);') && ins.includes('content.addView(chipsScroll, content.indexOfChild(row) + 1, clp);')
      && ins.indexOf('slp.height = dp(a, SEARCH_DP);') < ins.indexOf('content.addView(row, below + 1, rlp);'),
      'IvaSearchPatch.install: порядок — поиск, подсказки, «Ваш город · Фильтр», ряд условий');
    // 10.50: плитки категорий скрыты (и при установке, и при каждом показе главной), штатная сетка не удаляется
    assert.ok(ins.includes('hideCatGrid(a);') && /static void onHomeShown\(Activity a\) \{\n        hideCatGrid\(a\);/.test(js45)
      && js45.includes('((View) cg).setVisibility(View.GONE);') && !/removeView\([^)]*catGrid|catGrid[^;]*removeAllViews/.test(js45),
      'IvaSearchPatch: плитки категорий на главной скрыты, штатная сетка не удалена');
    assert.ok(js45.includes('chipsScroll.setHorizontalScrollBarEnabled(false);') && js45.includes('chips.setOrientation(LinearLayout.HORIZONTAL);')
      && js45.includes('chips.setTag(CHIPS_TAG);') && !/chip\(a, "Фильтры"/.test(js45),
      'IvaSearchPatch: ряд условий под поиском — один ряд без полосы прокрутки, без кнопки «Фильтры»');
    // ряд под поиском — оранжевые «пузыри» (как было), не текст
    assert.ok(/addActiveChips\(a, box, f, new Runnable\(\) \{[\s\S]{0,400}?\n            \}\);/.test(js45.slice(js45.indexOf('static void refreshChips('))),
      'IvaSearchPatch.refreshChips: ряд под поиском — оранжевые «пузыри»');
  }
  // 10.54: чат по инструментам — новый файл компилируется и в сборке, и в проверке; вход только через IvaPatch.onTab
  {
    const chat = R('android/patches/IvaChatPatch.java'), chk54 = R('.github/workflows/check.yml'), ip = R('android/patches/IvaPatch.java');
    assert.ok(/javac [^\n]*android\/patches\/IvaSearchPatch\.java android\/patches\/IvaChatPatch\.java android\/patches\/IvaOrderPatch\.java android\/patches\/IvaNotifyPatch\.java android\/patches\/IvaReviewPatch\.java(?: android\/patches\/Iva\w+Patch\.java)* 2>&1/.test(code), 'build-apk.yml: IvaChatPatch.java не компилируется в сборку');
    assert.ok(chk54.includes('android/patches/IvaSearchPatch.java android/patches/IvaChatPatch.java android/patches/IvaOrderPatch.java android/patches/IvaNotifyPatch.java \\'), 'check.yml: IvaChatPatch.java не компилируется в проверке');
    const onTab = ip.slice(ip.indexOf('public static void onTab('), ip.indexOf('static boolean navSelected('));
    assert.ok(onTab.includes('try { IvaChatPatch.onShowTab(a, tab); } catch (Throwable ignore) {}'), 'IvaPatch.onTab: нет безопасного вызова IvaChatPatch');
    assert.ok(!R('android/patches/apply.py').includes('IvaChatPatch'), 'IvaChatPatch: новые smali-хуки не нужны (вход — IvaPatch.onTab)');
    // штатные поля, методы и ресурсы, к которым чат обращается через отражение, — есть в 10.14
    for (const str of ['chatWith', 'chatTool', 'detItem', 'detOwner', 'chatList', 'chatScroll', 'pageChat', 'chatInput', 'curTab',
      'userEmail', 'userName', 'accessToken', 'isAdminEmail', 'loadChat', 'sendChat', 'showTab', 'loadPhoto', 'humanError',
      'lambda$setupChat$88$ru-prokatnvsk-app-MainActivity'])
      assert.ok(has(dex14, str), `classes.dex 10.14: нет «${str}» — чат 10.54 не найдёт штатное поле или метод`);
    for (const str of ['detAsk', 'chatSend', 'bg_bubble_me', 'bg_bubble_other', 'bg_avatar', 'bg_card', 'bg_photo', 'bg_badge', 'ic_play', 'ic_pause', 'ic_mic', 'ic_wrench'])
      assert.ok(has(arsc14, str), `resources.arsc 10.14: нет «${str}» — чат 10.54 потеряет штатный вид`);
    // «Спросить…» без автосообщения: в нашем обработчике нет POST с текстом «Вопрос по инструменту»; без входа / админу — штатный обработчик
    const ask = chat.slice(chat.indexOf('static void hookAsk('), chat.indexOf('static void nativeAsk('));
    assert.ok(!/Вопрос по инструменту|apiCall|"POST"/.test(ask) && ask.includes('if (!loggedIn(a) || isAdmin(a)) { nativeAsk(a, v); return; }')
      && ask.includes('openThread(a, owner, toolIdOf(tool), tool);') && ask.includes('callTab(a, "chat");'),
      'IvaChatPatch.hookAsk: «Спросить об инструменте» должен открывать пустую переписку без автосообщения');
    // отправка: только нажатие (запись голоса — штатная), tool_id открытой переписки, при отказе базы — без него
    const snd = chat.slice(chat.indexOf('static void hookSend('), chat.indexOf('static boolean ok('));
    assert.ok(snd.includes('send.setOnClickListener(') && !/setOnLongClickListener|setOnTouchListener/.test(snd)
      && snd.includes('JSONObject b = sendBody(from, partner, text, tool, toolColumn, topicColumn);') && chat.includes('else if (!clean(tool).isEmpty() && toolCol) b.put("tool_id", tool);') && snd.includes('b.remove("tool_id");')
      && snd.includes('if (input.getText().toString().trim().isEmpty()) input.setText(text);'),
      'IvaChatPatch.send: только нажатие, tool_id, повтор без него и возврат текста при ошибке');
    // свои — справа, собеседника — слева; аватарка у каждого сообщения
    const row = chat.slice(chat.indexOf('static View bubbleRow('), chat.indexOf('static String senderTitle('));
    assert.ok(row.includes('row.setGravity(mine ? Gravity.END | Gravity.BOTTOM : Gravity.START | Gravity.BOTTOM);')
      && row.indexOf('row.addView(col, new LinearLayout.LayoutParams(-2, -2));\n            row.addView(ava, al);') > 0
      && row.includes('setBg(a, t, mine ? "bg_bubble_me" : "bg_bubble_other");'),
      'IvaChatPatch.bubbleRow: свои справа с аватаркой справа, собеседника слева');
    // шапка закреплена над прокруткой: карточка, затем «Назад к чатам»
    const hd = chat.slice(chat.indexOf('static void renderHeader('), chat.indexOf('static String partnerTitle('));
    assert.ok(hd.indexOf('header.addView(card') >= 0 && hd.indexOf('header.addView(card') < hd.indexOf('header.addView(back')
      && chat.includes('r.addView(h, new LinearLayout.LayoutParams(-1, -2));') && chat.includes('r.addView(s, new LinearLayout.LayoutParams(-1, 0, 1f));')
      && chat.includes('static final String BACK_TITLE = "‹  Назад к чатам";'),
      'IvaChatPatch: карточка инструмента и «Назад к чатам» закреплены над перепиской (карточка первой)');
    // штатный чат не удаляется: только скрывается и возвращается при сбое
    assert.ok(!/removeView\([^)]*sc\b|removeAllViews\(\);[^\n]*chatList/.test(chat) && chat.includes('((View) sc).setVisibility(View.VISIBLE);'),
      'IvaChatPatch: штатный chatScroll только скрывается и возвращается при сбое');
    // личные данные: от сервера — только почта и путь к фото, никаких ФИО/телефонов клиентов
    assert.ok(chat.includes('"/rest/v1/rpc/iva_chat_peers"') && !/customer_profiles|passport|phone/.test(chat), 'IvaChatPatch: лишние персональные данные');
    const sql = R('supabase/migrations/20261012_chat_tools_avatars.sql');
    assert.ok(sql.includes('returns table (email text, avatar_path text)') && /security definer/i.test(sql)
      && sql.includes("revoke all on function public.iva_chat_peers() from public, anon;")
      && sql.includes("revoke all on function public.iva_tag_my_messages(text[], uuid) from public, anon;")
      && !/passport|phone|surname|given_name/.test(sql.replace(/^--.*$/gm, '')),
      'supabase/migrations/20261012_chat_tools_avatars.sql: функции без лишних данных и без доступа anon');
    assert.ok(/eq\("чат 10\.54: строки списка — по инструменту/.test(R('android/patches/test/IvaPatchTest.java')), 'IvaPatchTest: проверки чата 10.54');
  }
  // 10.55: заявка — ФИО по полям, свой срок у инструмента, «+ Добавить инструмент», средняя доставка, один прокат.
  // Вход только через IvaPatch.onTab / onSummary / onPrice; штатные строки и счётчик срока только прячутся.
  {
    const op = R('android/patches/IvaOrderPatch.java'), ip = R('android/patches/IvaPatch.java');
    assert.ok(!R('android/patches/apply.py').includes('IvaOrderPatch'), 'IvaOrderPatch: новые smali-хуки не нужны (вход — IvaPatch)');
    const onTab = ip.slice(ip.indexOf('public static void onTab('), ip.indexOf('static boolean navSelected('));
    assert.ok(onTab.includes('try { IvaOrderPatch.onShowTab(a, tab); } catch (Throwable ignore) {}'), 'IvaPatch.onTab: нет безопасного вызова IvaOrderPatch');
    assert.ok(ip.includes('    public static void onSummary(final Activity a) {\n        // 10.55: свои строки заявки (срок у каждого инструмента) и итог — сразу после штатного updateSummary\n        try { IvaOrderPatch.onSummary(a); } catch (Throwable ignore) {}\n'),
      'IvaPatch.onSummary: IvaOrderPatch.onSummary должен вызываться первым и в try');
    assert.ok(ip.includes('String one = normalize(IvaOrderPatch.onPrice(s));') && op.includes('return averaged(s, rateCount(lines(a)));')
      && op.includes('if (s == null || n <= 1) return s;'), 'доставка 10.55: средняя (сумма штатного расчёта / число цен за км), до «туда и обратно»');
    for (const str of ['selected', 'orderLines', 'dMinus', 'rbDelivery', 'rbPickup', 'rgMethod', 'ordSum', 'oLast', 'oName', 'oPatr', 'oPhone',
      'oAddr', 'oCmt', 'btnSend', 'delivTxt', 'admAddr', 'detItem', 'detIsSrv', 'feedCard', 'calcDelivery', 'renderOrderLines', 'updateSummary',
      'sendOrder', 'humanError', 'showScreen', 'scrSuccess', 'userName', 'lambda$setupOrder$122$ru-prokatnvsk-app-MainActivity',
      'lambda$sendOrder$126$ru-prokatnvsk-app-MainActivity', '/rest/v1/rpc/submit_order', 'p_days', 'p_get_method'])
      assert.ok(has(dex14, str), `classes.dex 10.14: нет «${str}» — заявка 10.55 не найдёт штатное поле или метод`);
    for (const str of ['detOrder', 'bg_card', 'bg_stepper'])
      assert.ok(has(arsc14, str), `resources.arsc 10.14: нет «${str}» — заявка 10.55 потеряет штатный вид`);
    // отправка: только нажатием, одна заявка, те же 8 полей, что у штатной; пустая заявка / без входа — штатный sendOrder
    const snd = op.slice(op.indexOf('static void send(final Activity a)'), op.indexOf('static void sent(Activity a)'));
    const keys = (snd.match(/body\.put\("(p_[a-z_]+)"/g) || []).map(x => x.slice(10, -1));
    assert.deepEqual(keys, ['p_name', 'p_phone', 'p_tools', 'p_get_method', 'p_address', 'p_comment', 'p_user_email', 'p_days'], 'IvaOrderPatch.send: поля submit_order как у штатной заявки');
    assert.ok(snd.includes('IvaClientPatch.apiCall(a, "/rest/v1/rpc/submit_order", "POST", body.toString(), true)') && (op.match(/apiCall\(/g) || []).length === 7
      && op.includes('IvaClientPatch.apiCall(a, ordPath(ORD_PATH, raw), "GET", null, true)') && (op.match(/"POST"/g) || []).length === 3
      && snd.includes('body.put("p_days", maxDays(ls));') && snd.includes('body.put("p_tools", toolsText(ls));')
      && snd.includes('callNative(a, "sendOrder");                       // штатные') && snd.includes('if (sending) return;'),
      'IvaOrderPatch.send: одна заявка submit_order, p_days — самый длинный срок, без повторной отправки');
    assert.ok(op.includes('((View) send).setOnClickListener(new View.OnClickListener() {\n                @Override public void onClick(View v) { send(a); }')
      && (op.match(/setOnLongClickListener/g) || []).length === 1 && op.includes('@Override public boolean onLongClick(View v) { try { ordAskArchive(a, r, !archived); } catch (Throwable ignore) {} return true; }')
      && !/setOnTouchListener|postDelayed\([^)]*send/.test(op), 'IvaOrderPatch: заявка уходит только по нажатию «Отправить заявку» (долгое нажатие — только «В архив?» на карточке 10.63)');
    assert.ok(!/passport|birth|паспорт/i.test(op), 'IvaOrderPatch: лишние персональные данные');
    // один прокат: другой прокат — вопрос «Заменить?», очистка только по согласию
    const det = op.slice(op.indexOf('static void onDetOrder('), op.indexOf('static void nativeAdd('));
    assert.ok(det.includes('if (conflict(cartOwner(ls), owner)) {') && det.includes('.setPositiveButton("Заменить", new DialogInterface.OnClickListener() {')
      && det.includes('.setNegativeButton("Отмена", null)') && (det.match(/\.clear\(\)/g) || []).length === 1
      && det.indexOf('.clear()') > det.indexOf('setPositiveButton("Заменить"'), 'IvaOrderPatch: в заявке один прокат, замена — только после «Заменить»');
    // «+ Добавить инструмент»: только этот прокат, без сданных, без уже выбранных; при доставке — только с доставкой и подсказка
    const pick = op.slice(op.indexOf('static void openPicker('), op.indexOf('static void showPicker('));
    assert.ok(pick.includes('!owner.equalsIgnoreCase(t.optString("owner_email", DEFAULT_OWNER))') && pick.includes('"rented".equals(t.optString("t_status", ""))')
      && pick.includes('if (deliv && !t.optBoolean("delivery", false)) { hidden++; continue; }') && pick.includes('showPicker(a, list, deliv && hidden > 0, defaultDays(ls));')
      && op.includes('static final String PICK_HINT = "Показаны только инструменты с доставкой. Если выбрать самовывоз, инструментов в списке может стать больше.";')
      && op.includes('View card = (View) fc.invoke(a, t, Boolean.FALSE);'), 'IvaOrderPatch.openPicker: карточки (штатный feedCard) этого же проката; при доставке — только с доставкой и подсказка');
    // доставка только если она у всех; штатные строки и общий срок — только скрыты; количество всегда 1
    const enf = op.slice(op.indexOf('static void enforceDelivery('), op.indexOf('static void render('));
    assert.ok(enf.includes('if (d.isChecked() && rp instanceof RadioButton) ((RadioButton) rp).setChecked(true);') && op.includes('if (!l.deliv) return false;'),
      'IvaOrderPatch: инструмент без доставки — только самовывоз');
    const opForm = op.slice(0, op.indexOf('// ───────────────────────── 10.62–10.63: заявки клиента'));
    // 10.67: единственное исключение — карточка заявки у админа: дату и названия переносим в столбец рядом с картинками
    // (не удаляем, на ошибке — обратно); проверяется блоком 10.67 ниже
    const op67 = op.indexOf('// ── 10.67: картинки и в заявках у админа'), op67e = op.indexOf('/** Запасной путь (наш список не загрузился)');
    const opNo67 = op67 > 0 && op67e > op67 ? op.slice(0, op67) + op.slice(op67e) : op;
    assert.ok(op67 > 0 && op67e > op67 && !/removeView\(|\.removeViewAt\(/.test(opNo67) && opForm.length > 1000 && (opForm.match(/removeAllViews\(\)/g) || []).length === 1 && opForm.includes('box.removeAllViews();')
      && (op.match(/removeAllViews\(\)/g) || []).length === 4 && op.includes('mine.removeAllViews();') && op.includes('list.removeAllViews();')
      && op.includes('((View) ol).setVisibility(View.GONE);') && op.includes('dr.setVisibility(View.GONE);') && op.includes('if (j.optInt("qty", 1) != 1) j.put("qty", 1);'),
      'IvaOrderPatch: штатные строки заявки и общий срок только скрыты (не удаляются), количество — 1');
    assert.ok(op.includes('static final int MIN_DAYS = 1, MAX_DAYS = 90;'), 'IvaOrderPatch: срок 1…90 как штатный счётчик');
    // ФИО: из профиля по полям, правки клиента не перезаписываются
    const fio = op.slice(op.indexOf('static void fillFio('), op.indexOf('static void toast('));
    assert.ok(fio.includes('if (!pristine(text(last), text(name), text(patr), userName, lastFill)) return;')
      && fio.includes('fio(p.optString("surname", ""), p.optString("given_name", ""), p.optString("patronymic", ""), userName)')
      && !/apiCall|\"POST\"/.test(fio), 'IvaOrderPatch.fillFio: ФИО из профиля по полям, без записи в профиль и без затирания правок клиента');
    assert.ok(/eq\("заявка 10\.55: ФИО из профиля по полям/.test(R('android/patches/test/IvaPatchTest.java')), 'IvaPatchTest: проверки заявки 10.55');
  }
  // 10.56: «Настройки» в кабинете → уведомления с переключателями; свой опрос вместо штатного; без Firebase и новых разрешений.
  {
    const np = R('android/patches/IvaNotifyPatch.java'), chk56 = R('.github/workflows/check.yml');
    assert.ok(/android\/patches\/IvaOrderPatch\.java android\/patches\/IvaNotifyPatch\.java android\/patches\/IvaReviewPatch\.java(?: android\/patches\/Iva\w+Patch\.java)* 2>&1/.test(code) && chk56.includes('android/patches/IvaNotifyPatch.java \\'),
      'IvaNotifyPatch.java не компилируется в сборке или в проверке');
    assert.ok(!R('android/patches/apply.py').includes('IvaNotifyPatch'), 'IvaNotifyPatch: новые smali-хуки не нужны (вход — IvaPatch.onTab)');
    // этап 1 — без фоновых служб; этап 2 (10.65) — отдельный раздел в конце файла, только JobScheduler (проверка в блоке 10.65)
    const np1 = np.slice(0, np.indexOf('// ───────────── 10.65: этап 2'));
    assert.ok(np1.length > 1000 && !/firebase|com\.google\.android\.gms|JobScheduler|AlarmManager|WorkManager/i.test(np1), 'IvaNotifyPatch: этап 1 — без Firebase и фоновых служб');
    assert.ok(!/com\.google\.firebase|FirebaseMessaging|com\.google\.android\.gms|AlarmManager|ALARM_SERVICE|androidx\.work|WorkManager/.test(np), 'IvaNotifyPatch: без Firebase, AlarmManager и WorkManager (решение владельца 13.10)');
    assert.ok(!/customer_profiles|passport|phone|surname/.test(np), 'IvaNotifyPatch: лишние персональные данные');
    assert.ok(np.includes('static boolean pref(Context c, String k) { return prefs(c).getBoolean(k, true); }'), 'уведомления 10.56: по умолчанию всё включено');
    assert.ok(np.includes('if (!all || !own) return false;\n        return !K_ORD.equals(kind) || admin;')
      && np.includes('if (admin) kids.add(switchRow(a, card, "Новые заявки"') && np.includes('if (logged && admin && !email.isEmpty()) checkOrders(a, email);'),
      'уведомления 10.56: «Новые заявки» — только админам, общий переключатель выключает всё');
    assert.ok(np.includes('static final String CH_MSG = "iva_msg", CH_ORD = "iva_orders", CH_UPD = "iva_update";')
      && np.includes('int imp = CH_UPD.equals(id) ? NotificationManager.IMPORTANCE_DEFAULT : NotificationManager.IMPORTANCE_HIGH;'),
      'уведомления 10.56: три канала; сообщения и заявки — всплывающие (HIGH)');
    const stop = np.slice(np.indexOf('static void stopNative(Activity a)'), np.indexOf('static void startPoll()'));
    assert.ok(stop.includes('Object h = get(a, "pollHandler"), r = get(a, "pollRun");') && stop.includes('removeCallbacks((Runnable) r)')
      && stop.includes('IvaClientPatch.set(a, "lastNewCnt", Integer.valueOf(-1));') && stop.includes('IvaClientPatch.set(a, "lastUnread", Integer.valueOf(-1));'),
      'уведомления 10.56: штатный опрос (без настроек) должен выключаться');
    assert.ok(np.includes('static final long POLL_MS = 30000L;') && np.includes('poll.postDelayed(TICK, POLL_MS);') && !/postDelayed\(TICK, [0-9]{1,3}\)/.test(np),
      'уведомления 10.56: опрос раз в 30 с, как штатный');
    assert.ok(np.includes('if (t instanceof TextView) ((TextView) t).setText(n > 0 ? "Заявки •" : "Заявки");'), 'уведомления 10.56: отметка «Заявки •» в меню осталась');
    assert.ok(np.includes('if (!hiddenInChat(openWith, list.get(ids.indexOf(id)).optString("from_email", ""))) show.add(id);')
      && np.includes('if (nw.isEmpty() || !on(a, K_MSG, admin)) return;') && np.includes('b.setVisibility(Notification.VISIBILITY_PRIVATE);'),
      'уведомления 10.56/10.57: в «Чате» без уведомления только о видимом; на экране блокировки без текста сообщения');
    assert.ok(np.includes('(Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)'), 'уведомления 10.56: PendingIntent без FLAG_IMMUTABLE упадёт на Android 12+');
    assert.ok(np.includes('static final long UPDATE_EVERY_MS = 6L * 60 * 60 * 1000;') && np.includes('static final String UPDATE_URL = "https://eclipsru.github.io/Arenda-Nvk/app-update.json";')
      && has(dex14, 'https://eclipsru.github.io/Arenda-Nvk/app-update.json'), 'уведомления 10.56: обновления — тот же app-update.json, что у штатной кнопки, не чаще раза в 6 часов');
    for (const str of ['pollHandler', 'pollRun', 'lastNewCnt', 'lastUnread', 'navOrdersTxt', 'isAdminEmail', 'curTab', 'accessToken', 'userEmail', 'showTab',
      '/rest/v1/order_parts?select=id&status=eq.new&archived=eq.false&owner_email=eq.', 'app_messages'])
      assert.ok(has(dex14, str), `classes.dex 10.14: нет «${str}» — уведомления 10.56 не найдут штатное поле или метод`);
    for (const str of ['profHelp', 'bg_card', 'ic_gear', 'ic_chat', 'ic_chevron'])
      assert.ok(has(arsc14, str), `resources.arsc 10.14: нет «${str}» — строка «Настройки» потеряет штатный вид`);
    const row = np.slice(np.indexOf('static void ensureRow(final Activity a)'), np.indexOf('static Dialog settings;'));
    assert.ok(row.includes('if (box.findViewWithTag(ROW_TAG) != null) return;') && row.includes('box.addView(row, box.indexOfChild(help), lp);')
      && row.includes('row.setPadding(dp(a, 16), dp(a, 16), dp(a, 16), dp(a, 16));') && row.includes('t.setTextSize(15f);') && !row.includes('removeView'),
      'IvaNotifyPatch.ensureRow: строка «Настройки» — одна, перед «Инструкция», в штатном виде строк кабинета, ничего не удаляет');
    assert.ok(/eq\("уведомления 10\.56: /.test(R('android/patches/test/IvaPatchTest.java')), 'IvaPatchTest: проверки уведомлений 10.56');
  }
  // 10.57: открытый чат обновляется сам; значок уведомлений — логотип (силуэт в строке состояния, цветной в шторке).
  {
    const cp = R('android/patches/IvaChatPatch.java'), np = R('android/patches/IvaNotifyPatch.java');
    const live = cp.slice(cp.indexOf('static void live() {'), cp.indexOf('static JSONArray getArr(Activity a, String path)'));
    assert.ok(live.includes('if (!"chat".equals(str(IvaClientPatch.get(a, "curTab")))) return;') && live.includes('handler.postDelayed(LIVE, LIVE_MS);')
      && live.includes('if (!a.hasWindowFocus() || !loggedIn(a) || liveBusy || loading) return;'),
      'чат 10.57: проверка только пока «Чат» на экране, раз в LIVE_MS, без наложения запросов');
    assert.ok(live.includes('"/rest/v1/app_messages?select=id,is_read" + msgFilter(my, sup)') && live.includes('callNative(a, "loadChat");\n                        refresh(a);')
      && !/"PATCH"|"POST"|is_read=eq/.test(live), 'чат 10.57: лёгкий запрос (id, is_read), перезагрузка штатным loadChat; сам ничего не пишет в базу');
    assert.ok(cp.includes('static final long LIVE_MS = 4000L;') && cp.includes('String filter = msgFilter(my, sup);')
      && cp.includes('try { syncState(a); render(a); refresh(a); } catch (Throwable e) { fallback(a); }\n                        startLive();'),
      'чат 10.57: та же выборка, что у загрузки; запуск при входе в «Чат»');
    assert.ok(np.includes('static void setIcons(Context c, Notification.Builder b, int fallback) {') && np.includes('b.setSmallIcon(android.graphics.drawable.Icon.createWithBitmap(si));')
      && (np.match(/setIcons\(c, (b|pub), icon\);/g) || []).length === 2,
      'уведомления 10.57: силуэт логотипа в строке состояния — и в обычном, и в скрытом (экран блокировки) виде');
    assert.ok(np.includes('getIdentifier("ic_logo", "drawable"') && has(arsc14, 'ic_logo'), 'уведомления 10.57: цветной логотип — штатный ic_logo');
    const png = Buffer.from((np.match(/STATUS_ICON_PNG =\s*([\s\S]*?);/) || [])[1].replace(/[\s"+]/g, ''), 'base64');
    assert.ok(png.slice(1, 4).toString() === 'PNG' && png.readUInt32BE(16) === 96 && png.readUInt32BE(20) === 96 && png[25] === 6,
      'уведомления 10.57: значок строки состояния — PNG 96×96 RGBA');
    assert.ok(np.includes('List<String> nw = seen == null ? recent(ids, created, isoUtc(System.currentTimeMillis() - FIRST_RUN_WINDOW_MS)) : fresh(seen, ids);'),
      'уведомления 10.57: первый запуск — о непрочитанных за сутки');
    assert.ok(/eq\("чат 10\.57: /.test(R('android/patches/test/IvaPatchTest.java')) && /eq\("уведомления 10\.57: /.test(R('android/patches/test/IvaPatchTest.java')),
      'IvaPatchTest: проверки 10.57');
  }
  // 10.58: голосовые — своё воспроизведение из кэша (вид — с 10.60 как в ВК); уведомление-«разговор» с цветным логотипом слева, без картинки справа.
  {
    const cp = R('android/patches/IvaChatPatch.java'), np = R('android/patches/IvaNotifyPatch.java'), tp = R('android/patches/test/IvaPatchTest.java');
    // вид голосового с 10.60 — как в ВК (просьба владельца 14.10.2026), проверяется в блоке 10.60
    assert.ok(!cp.includes('"playVoice"') && cp.includes('mp.prepareAsync();') && (cp.match(/\.prepare\(\);/g) || []).length === 1
      && cp.includes('try { f = voiceFile(a, url); } catch (Throwable e) { err = e.getMessage(); }') && cp.includes('vWorker.execute(new Runnable() {'),
      'голосовые 10.58: не штатный playVoice (prepare по сети в UI-потоке) — скачивание в фоне, prepareAsync; синхронный prepare только в фоновой очереди длительностей');
    assert.ok(cp.includes('if (!chat) voiceStop(a);') && cp.includes('openPartner = ""; openPartnerRaw = ""; openTool = "";\n        voiceStop(a);')
      && cp.includes('if (key.equals(vKey)) {\n            // Перерисовка'),
      'голосовые 10.58: замолкает при уходе из чата/переписки; перерисовка подхватывает играющее');
    assert.ok(cp.includes('Toast.makeText(a, "Голосовое не загрузилось"') && cp.includes('voiceFail(a, f, what + "/" + extra);'),
      'голосовые 10.58: понятная причина, если не загрузилось или не играет');
    assert.ok(!np.includes('setLargeIcon') && !np.includes('LARGE_ICON_PX'), 'уведомления 10.58: картинки справа нет (решение владельца 14.10)');
    const cv = np.slice(np.indexOf('static boolean conversation('), np.indexOf('/** Нажали на уведомление'));
    assert.ok(cv.includes('if (Build.VERSION.SDK_INT < 30) return false;') && cv.includes('.setLongLived(true)') && cv.includes('sm.pushDynamicShortcut(info);')
      && cv.includes('b.setShortcutId(sid);') && cv.includes('new Notification.MessagingStyle(self)') && cv.includes('ms.setGroupConversation(false);')
      && cv.includes('sm.removeDynamicShortcuts(ids);') && !cv.includes('removeLongLivedShortcuts'),
      'уведомления 10.58: Android 11+ — «разговор» (MessagingStyle + долгоживущий ярлык) с логотипом в кружке слева');
    assert.ok(np.includes('if (!conversation(a, b, ch, title, text, open, target)) b.setStyle(new Notification.BigTextStyle().bigText(text));'),
      'уведомления 10.58: не вышло или Android до 11 — обычный вид с полным текстом');
    assert.ok(np.includes('cv.drawCircle(AVATAR_PX / 2f, AVATAR_PX / 2f, AVATAR_PX / 2f, pt);') && np.includes('getIdentifier("ic_logo", "drawable"'),
      'уведомления 10.58: штатный ic_logo, обрезанный кругом');
    assert.ok(/eq\("голосовые 10\.58: /.test(tp) && /eq\("уведомления 10\.58: /.test(tp), 'IvaPatchTest: проверки 10.58');
  }
  // 10.59: нажатие на уведомление — сразу на сообщение / заявку / кнопку обновления. Решение владельца 14.10.2026:
  // новый метод MainActivity.onNewIntent (в 10.14 его нет) — единственная новая smali-вставка, существующие методы не меняются.
  {
    const np = R('android/patches/IvaNotifyPatch.java'), cp = R('android/patches/IvaChatPatch.java'), ip = R('android/patches/IvaPatch.java');
    const tp = R('android/patches/test/IvaPatchTest.java');
    assert.ok(ap.includes("    '.method protected onNewIntent(Landroid/content/Intent;)V',\n    '    .registers 2',")
      && ap.includes("'    invoke-super {p0, p1}, Landroid/app/Activity;->onNewIntent(Landroid/content/Intent;)V',")
      && ap.includes("'    invoke-virtual {p0, p1}, Lru/prokatnvsk/app/MainActivity;->setIntent(Landroid/content/Intent;)V',")
      && ap.includes("f'    invoke-static {{p0, p1}}, {P}->onNewIntent(Landroid/app/Activity;Landroid/content/Intent;)V',")
      && ap.includes("if methods(lines, r' onNewIntent\\('):") && ap.includes("if sup != ['.super Landroid/app/Activity;']:")
      && ap.includes('lines.extend(NEW_INTENT)'),
      'apply.py 13: новый onNewIntent — super, setIntent, IvaPatch; отказ, если метод уже есть или класс не от Activity');
    assert.ok(!has(dex14, 'onNewIntent'), 'classes.dex 10.14: onNewIntent уже есть — вставка 13 не нужна или сломает класс');
    for (const str of ['admParts', 'admDrill', 'admClientKey', 'partClientKey', 'renderAdmOrders', 'progOrders', 'ordersList', 'profVer', 'checkInBackground', 'iva_selfupdate_btn'])
      assert.ok(has(dex14, str), `classes.dex 10.14: нет «${str}» — переход 10.59 не найдёт штатное поле или метод`);
    assert.ok(ip.includes('public static void onNewIntent(Activity a, android.content.Intent it) {\n        try { IvaNotifyPatch.onNewIntent(a, it); } catch (Throwable ignore) {}'),
      'IvaPatch.onNewIntent: любая ошибка перехода не мешает приложению');
    const rt = np.slice(np.indexOf('static void route(final Activity a, Intent it) {'), np.indexOf('static void tab(Activity a, String t) {'));
    assert.ok(rt.indexOf('it.removeExtra(EXTRA_OPEN);') > 0 && rt.indexOf('it.removeExtra(EXTRA_OPEN);') < rt.indexOf('.post(new Runnable()')
      && rt.includes('IvaChatPatch.openFromNote(a, target);') && rt.includes('openOrder(a, target);') && rt.includes('openUpdate(a, target);'),
      'переход 10.59: цель снимается с intent до перехода (повторно не переходим); чат / заявка / обновление');
    assert.ok(np.includes('it.putExtra(EXTRA_TARGET, target == null ? "" : target);') && np.includes('si.putExtra(EXTRA_TARGET, target == null ? "" : target);')
      && np.includes('"orders", orderTarget(nw));') && np.includes('"chat", messageTarget(nw, from));') && np.includes('remote + "|" + j.optString("versionName", ""));'),
      'переход 10.59: цель в уведомлении (и в ярлыке «разговора»): сообщение, заявка, «код|версия»');
    const oo = np.slice(np.indexOf('static void openOrder('), np.indexOf('static String clientKey('));
    assert.ok(oo.includes('boolean busy = pb instanceof View && ((View) pb).getVisibility() == View.VISIBLE;') && oo.includes('IvaClientPatch.set(a, "admDrill", Boolean.TRUE);')
      && oo.includes('IvaClientPatch.set(a, "admClientKey", key);') && oo.includes('call(a, "renderAdmOrders");') && oo.includes('if (list.getChildCount() == count + 1) reveal(list.getChildAt(idx + 1));')
      && oo.includes('!"orders".equals(String.valueOf(get(a, "curTab")))') && !/apiCall|"POST"|"PATCH"/.test(oo),
      'переход 10.59: заявка — ждём штатную загрузку, открываем клиента как штатное нажатие, подсветка только при совпадении карточек, ушли — не мешаем, в базу не пишем');
    const ou = np.slice(np.indexOf('static void openUpdate('), np.indexOf('static ViewGroup updateBox('));
    assert.ok(ou.includes('su.getDeclaredMethod("checkInBackground", Activity.class, View.class);') && ou.includes('box.removeView(old);')
      && ou.includes('if (b != null) { reveal(b); return; }') && !ou.includes('onButtonTap'),
      'переход 10.59: обновление — штатная кнопка SelfUpdate (проверка ещё раз), само не скачивает и не ставит');
    assert.ok(cp.includes('try { applyNote(a); } catch (Throwable ignore) {}\n        String sig = signature(a);') && cp.includes('load(a, my, adm, partner, tool);\n                    loadDone = Math.max(loadDone, gen);')
      && cp.includes('callTab(a, "chat");                  // штатный переход') && cp.includes('if (loadDone > noteGen) noteMsg = "";'),
      'переход 10.59: чат — переписка сообщения после загрузки, начатой после нажатия; нет сообщения — список');
    const rth = cp.slice(cp.indexOf('static void renderThread('), cp.indexOf('static View bubbleRow('));
    assert.ok(rth.includes('if (!flashMsg.isEmpty() && flashMsg.equals(m.id)) flashRow = br;')
      && cp.includes('if (flashRow != null) {          // открыли по уведомлению') && cp.includes('IvaNotifyPatch.reveal(flashRow);'),
      'переход 10.59: чат — прокрутка к сообщению из уведомления и подсветка');
    assert.ok(/eq\("переход 10\.59: /.test(tp), 'IvaPatchTest: проверки перехода 10.59');
  }
  // 10.60: голосовые как в ВК, цвета приложения (просьба владельца 14.10.2026, по снимкам: пузырь сжимался до ширины времени).
  {
    const cp = R('android/patches/IvaChatPatch.java'), tp = R('android/patches/test/IvaPatchTest.java');
    const br = cp.slice(cp.indexOf('static View bubbleRow('), cp.indexOf('static String senderTitle('));
    assert.ok(br.includes('col.addView(voice, new LinearLayout.LayoutParams(-2, -2));') && !/col\.addView\(voiceBubble\([^)]*\)\)/.test(cp)
      && br.includes('if (voice != null) voice.setOnLongClickListener(hold);'),
      'голосовые 10.60: пузырь в колонке по своему размеру (без размеров сжимался до подписи времени); долгое нажатие — удалить, как у текста');
    const vb = cp.slice(cp.indexOf('static View voiceBubble('), cp.indexOf('static void voiceShow('));
    assert.ok(vb.includes('setBg(a, v, mine ? "bg_bubble_me" : "bg_bubble_other");') && vb.includes('int accent = color(a, "orange");')
      && vb.includes('new VoiceBtn(a, mine ? 0xFFFFFFFF : accent, mine ? accent : 0xFFFFFFFF)') && vb.includes('new VoiceWave(a, pk, mine ? 0xFFFFFFFF : accent, mine ? VOICE_REST_MINE : VOICE_REST_OTHER, den)')
      && vb.includes('voiceWavePx(known, sw, den)') && vb.includes('if (pk == null) voiceAskWave(a, m.audio);') && vb.includes('voiceSeek(a, key, m.audio, ui, e.getX()')
      && !/#[0-9A-Fa-f]{6}|0x[0-9A-Fa-f]{8}/.test(vb.replace(/0xFFFFFFFF/g, '')),
      'голосовые 10.60: как в ВК — круглая кнопка слева, волна, длительность; цвета приложения (оранжевый из темы, белый), без новых цветов');
    assert.ok(cp.includes('if (!outEos) return null;') && cp.includes('java.io.File wf = new java.io.File(f.getPath() + ".wave");')
      && cp.includes('synchronized (vPeaksAsked) { vPeaksAsked.remove(url); }') && cp.includes('if (u != null) u.wave.setPeaks(fp);'),
      'голосовые 10.60: волна по настоящей громкости (в фоне, кэш рядом с файлом), дорисовывается на месте без перерисовки чата; не вышло — ровная линия');
    assert.ok(cp.includes('java.io.File tmp = new java.io.File(dir, f.getName() + "." + System.nanoTime() + ".part");'),
      'голосовые 10.60: кнопка и фоновая волна качают один файл без столкновения временных файлов');
    assert.ok(cp.includes('if (vSeek >= 0) p.seekTo(voiceSeekMs(vSeek, p.getDuration()));') && cp.includes('vSeek = -1f;\n        voiceReset(vUi.get());'),
      'голосовые 10.60: перемотка до подготовки применяется после неё; стоп сбрасывает всё');
    assert.ok(!cp.includes('ic_mic') && !cp.includes('"Голосовое", 14') && !cp.includes('VOICE_TRACK_BG'), 'голосовые 10.60: старого вида v9.8 не осталось');
    assert.ok(/eq\("голосовые 10\.60: /.test(tp), 'IvaPatchTest: проверки 10.60');
  }
  // 10.61: «удалить чат» — только у себя, с подтверждением (решение владельца 14.10.2026: в базе, долгое нажатие в списке,
  // написали снова — чат появляется только с новыми). Сообщения не удаляются, собеседник ничего не замечает.
  {
    const cp = R('android/patches/IvaChatPatch.java'), tp = R('android/patches/test/IvaPatchTest.java');
    const sql = R('supabase/migrations/20261014_chat_hidden.sql'), body = sql.replace(/^--.*$/gm, '');
    assert.ok(body.includes('primary key (user_email, partner, tool_id)') && body.includes('alter table public.app_chat_hidden enable row level security;')
      && body.includes('revoke all on public.app_chat_hidden from anon;') && body.includes('revoke all on public.app_chat_hidden from authenticated;')
      && body.includes('grant select on public.app_chat_hidden to authenticated;')
      && body.includes("using (user_email = lower(coalesce(auth.jwt() ->> 'email', '')));")
      && !/grant\s+(insert|update|delete|all)[^;]*app_chat_hidden/i.test(body) && !/for\s+(insert|update|delete|all)\b/i.test(body),
      '20261014_chat_hidden.sql: каждый видит только свои строки; писать напрямую нельзя');
    assert.ok(body.includes("me text := lower(coalesce(auth.jwt() ->> 'email', ''));") && body.includes('values (me, who, t, p_until)')
      && body.includes('set hidden_until = greatest(public.app_chat_hidden.hidden_until, excluded.hidden_until),')
      && body.includes('if p_until > now() then') && /security definer\s+set search_path = public/.test(body)
      && body.includes('revoke all on function public.iva_hide_chat(text, text, timestamptz) from public, anon;')
      && !/app_messages|delete\s+from|\bupdate\s+public\.app_messages/i.test(body),
      '20261014_chat_hidden.sql: iva_hide_chat — только за себя (почта из входа), граница только вперёд и не из будущего; app_messages не трогает');
    assert.ok(sql.includes('--   drop function if exists public.iva_hide_chat(text, text, timestamptz);') && sql.includes('--   drop table if exists public.app_chat_hidden;'),
      '20261014_chat_hidden.sql: откат записан');
    const row = cp.slice(cp.indexOf('static View chatRow('), cp.indexOf('static LinearLayout rowBase('));
    assert.ok(row.includes('@Override public boolean onLongClick(View v) { confirmHideChat(a, c); return true; }')
      && row.includes('.setTitle(HIDE_TITLE)') && row.includes('.setNegativeButton("Отмена", null)')
      && row.includes('@Override public void onClick(DialogInterface d, int w) { hideChat(a, c); }'),
      'чат 10.61: долгое нажатие на чат в списке → «Удалить чат?» → только после «Удалить»');
    assert.ok(row.includes('"/rest/v1/rpc/iva_hide_chat", "POST", hideBody(c), true)') && !/app_messages|"DELETE"|"PATCH"/.test(row)
      && row.includes('if ("ok".equals(fc)) {') && row.includes('} else if ("404".equals(fc)) {'),
      'чат 10.61: удаление — только отметка в app_chat_hidden; сообщения не удаляются и не помечаются прочитанными; скрываем только после ответа базы');
    assert.ok(cp.includes('b.put("p_until", c.last == null ? "" : c.last.created);') && cp.includes('long at = parseIso(o.optString("hidden_until", ""));')
      && cp.includes('"/rest/v1/app_chat_hidden?select=partner,tool_id,hidden_until&limit=1000", "GET", null, true)'),
      'чат 10.61: граница — время последнего сообщения из базы (не часы телефона); список удалённого — свой');
    assert.ok(cp.includes('if (hiddenMsg(hid, p, t, m.at) || (ids != null && ids.contains(m.id))) continue;') && cp.includes('&& !hiddenMsg(hid, p, t, m.at)) out.add(m);')
      && cp.includes('Map<String, String> tools = assignTools(asc, me, admins, sup, localTags);   // по всем сообщениям — как до удаления')
      && cp.includes('.append(hiddenVer).append(\'|\');'),
      'чат 10.61: скрытое не в списке и не в переписке; инструменты переписок считаются по всем сообщениям (новые попадают в тот же чат)');
    assert.ok(cp.includes('} else hiddenAt = 0;') && cp.includes('saveHidden(a, my);') && cp.includes('saveHidden(a, me);')
      && cp.includes('hidden.clear(); hiddenLocal.clear(); hiddenAt = 0; hiddenFor = ""; hiddenVer++;'),
      'чат 10.61: копия на телефоне (без интернета удалённое не возвращается), при смене аккаунта — сброс');
    assert.ok(/eq\("чат 10\.61: /.test(tp), 'IvaPatchTest: проверки 10.61');
  }
  // 10.62 (просьба владельца 14.10.2026): «Удалить у меня» / «Удалить у всех»; вкладки раздела логотипа как у админа;
  // заявки клиента нажимаются; в кабинете клиента нет «Позвонить», «Написать в МАКС», «Адрес выдачи».
  {
    const cp = R('android/patches/IvaChatPatch.java'), tp = R('android/patches/test/IvaPatchTest.java');
    const jc = R('android/patches/IvaClientPatch.java'), op = R('android/patches/IvaOrderPatch.java');
    const sql = R('supabase/migrations/20261014_msg_hidden.sql'), body = sql.replace(/^--.*$/gm, '');
    assert.ok(body.includes('primary key (user_email, msg_id)') && body.includes('alter table public.app_msg_hidden enable row level security;')
      && body.includes('revoke all on public.app_msg_hidden from anon;') && body.includes('revoke all on public.app_msg_hidden from authenticated;')
      && body.includes('grant select on public.app_msg_hidden to authenticated;')
      && body.includes("using (user_email = lower(coalesce(auth.jwt() ->> 'email', '')));")
      && !/grant\s+(insert|update|delete|all)[^;]*app_msg_hidden/i.test(body) && !/for\s+(insert|update|delete|all)\b/i.test(body),
      '20261014_msg_hidden.sql: каждый видит только свои строки; писать напрямую нельзя');
    assert.ok(body.includes("me text := lower(coalesce(auth.jwt() ->> 'email', ''));") && body.includes('values (me, mid)')
      && /security definer\s+set search_path = public/.test(body) && body.includes('revoke all on function public.iva_hide_msg(text) from public, anon;')
      && !/delete\s+from|\bupdate\s+public\.app_messages|insert\s+into\s+public\.app_messages/i.test(body),
      '20261014_msg_hidden.sql: iva_hide_msg — только за себя (почта из входа); app_messages только читает');
    assert.ok(sql.includes('--   drop function if exists public.iva_hide_msg(text);') && sql.includes('--   drop table if exists public.app_msg_hidden;'),
      '20261014_msg_hidden.sql: откат записан');
    const br = cp.slice(cp.indexOf('static View bubbleRow('), cp.indexOf('static View voiceBubble('));
    assert.ok(br.includes('if (!m.id.isEmpty()) {\n            if (deletable) {') && br.includes('confirmDelete(a, m, true); }')
      && br.includes('@Override public boolean onLongClick(View v) { confirmDelete(a, m, deletable); return true; }'),
      'сообщения 10.62: долгое нажатие — на любом сообщении, × — только у своих');
    const del = cp.slice(cp.indexOf('static void confirmDelete('), cp.indexOf('static void renderList('));
    assert.ok(del.includes('final String[] items = deleteChoices(own);') && del.includes('IvaClientPatch.styledChoice(a, DEL_TITLE, deleteHint(own), items, new IvaClientPatch.Choice() {')
      && del.includes('if (DEL_ALL.equals(items[w])) deleteForAll(a, m);') && cp.includes('return own ? new String[]{DEL_ME, DEL_ALL} : new String[]{DEL_ME};')
      && (del.match(/"DELETE"/g) || []).length === 1 && del.includes('"/rest/v1/rpc/iva_hide_msg", "POST", b.toString(), true)')
      && del.includes('if ("ok".equals(fc)) {') && del.includes('} else if ("404".equals(fc)) {'),
      'сообщения 10.62: «Удалить у всех» — только у своих (DELETE как раньше); «Удалить у меня» — отметка в базе, скрываем после ответа');
    assert.ok(cp.includes('if (ids != null && ids.contains(m.id)) continue;') && cp.includes('|| hiddenMsgs.contains(m.id)) return null;')
      && cp.includes('loadHiddenMsgs(a, my, now);') && cp.includes('"/rest/v1/app_msg_hidden?select=msg_id&limit=5000", "GET", null, true)')
      && cp.includes('hiddenMsgs.clear(); hiddenMsgsLocal.clear(); hiddenMsgsAt = 0; hiddenMsgsFor = "";') && cp.includes('saveHiddenMsgs(a, my);'),
      'сообщения 10.62: скрытое не в списке, не в переписке и не по уведомлению; копия на телефоне; при смене аккаунта — сброс');
    const tabs = jc.slice(jc.indexOf('static LinearLayout hubTabButton('), jc.indexOf('static void renderClientHub('));
    assert.ok(jc.includes('tabs.setBackground(tabCard(a));') && tabs.includes('g.setCornerRadii(hubBubbleRadii(hubDp(a, HUB_TAB_RADIUS_DP)));')
      && tabs.includes('line.setBackgroundColor(on ? orange : Color.TRANSPARENT);') && tabs.includes('setTextColor(on ? orange : gray)')
      && !/#[0-9A-Fa-f]{6}|0x[0-9A-Fa-f]{8}/.test(tabs.replace(/0xffff8f1f|0xff9aa1b0|0xff1a1d24|0xff262b35/g, '')),
      'вкладки 10.62–10.63: как у админа (цвета bg_card, линия 3dp, выбранная — оранжевая), без новых цветов');
    const pc = jc.slice(jc.indexOf('static void profileContacts('), jc.indexOf('// ── 10.63: окно выбора в стиле приложения'));
    assert.ok(jc.includes('try { if ("profile".equals(tab)) profileContacts(a); } catch (Throwable ignore) {}') && pc.includes('boolean show = !isClient(a);')
      && pc.includes('isSingleRow(a, (ViewGroup) row)') && !/removeView|removeAllViews|setText|setOnClick/.test(pc),
      'кабинет 10.62: у клиента строки только скрываются (у админа — видны), ничего не удаляется; строка адреса — только если это одна строка');
    const att = op.slice(op.indexOf('static void ordAttach('), op.indexOf('static boolean ordCard('));
    assert.ok(op.includes('if ("orders".equals(tab)) {\n            try { clientOrders(a); } catch (Throwable ignore) {}') && att.includes('!IvaClientPatch.isClient(a)) return;')
      && !/removeView|removeAllViews|setText\(|setVisibility/.test(att.replace('more.setText(ORD_MORE);', '')),
      'заявки 10.62: запасной путь — штатные карточки не пересоздаются — только нажатие и «Подробнее ›»');
    assert.ok(!/passport|birth|паспорт/i.test(op), 'заявки 10.62: лишних персональных данных нет');
    assert.ok(/eq\("сообщения 10\.62: /.test(tp) && /eq\("заявки 10\.62: /.test(tp) && /eq\("вкладки 10\.62: /.test(tp) && /eq\("кабинет 10\.62: /.test(tp), 'IvaPatchTest: проверки 10.62');
  }
  // 10.63 (просьба владельца 14.10.2026): «Назад» внутри приложения; вкладки клиента один в один как у админа, у админа
  // карточка вкладок снизу ровная; окно удаления сообщения в стиле приложения; «Архив заявок»; прокат в подробностях заявки.
  {
    const ip = R('android/patches/IvaPatch.java'), jc = R('android/patches/IvaClientPatch.java'), op = R('android/patches/IvaOrderPatch.java');
    const cp = R('android/patches/IvaChatPatch.java'), tp = R('android/patches/test/IvaPatchTest.java');
    const sql = R('supabase/migrations/20261014_order_archive.sql'), body = sql.replace(/^--.*$/gm, '');
    // «Назад»: без новых вставок в smali — обёртка окна только для клавиши «Назад»; на главной — свернуть
    const tab = ip.slice(ip.indexOf('static void onTab('), ip.indexOf('static void onTab(') + 600);
    assert.ok(tab.includes('installBack(a);') && ip.includes('if (e.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {')
      && ip.includes('try { return m.invoke(orig, args); }') && ip.includes('java.lang.reflect.Proxy.isProxyClass(orig.getClass())) return;')
      && ip.includes('else if (BACK_HOME.equals(act)) IvaNotifyPatch.tab(a, "home");') && ip.includes('a.moveTaskToBack(true)')
      && !/finish\(\)|System\.exit|killProcess/.test(ip.slice(ip.indexOf('// ───────────── 10.63: кнопка «Назад»'))),
      '«Назад» 10.63: только клавиша «Назад» (остальное — приложению как было), не закрывает приложение, на главной — свернуть');
    // вкладки и окно выбора — цвета темы
    assert.ok(jc.includes('card.setBackground(tabCard(a));') && jc.includes('try { adminTabsFlat(a); } catch (Throwable ignore) {}')
      && jc.includes('((TextView) tv).setTextColor(on ? orange : gray);'), 'вкладки 10.63: у админа карточка снизу ровная, у клиента — как у админа');
    const ch = jc.slice(jc.indexOf('// ── 10.63: окно выбора в стиле приложения'), jc.indexOf('static ViewGroup hubPages('));
    assert.ok(ch.includes('b.setTextColor(idx >= 0 ? orange : gray);') && ch.includes('d.setCanceledOnTouchOutside(true);')
      && !/#[0-9A-Fa-f]{6}|0x[0-9A-Fa-f]{8}/.test(ch.replace(/0xffff8f1f|0xff9aa1b0|0xfff2f4f8|0xff1a1d24|0xff262b35|0xff101217/g, '')),
      'окно выбора 10.63: пузыри цветов темы, оранжевый текст; мимо / «Отмена» / «Назад» — ничего не выбрано');
    assert.ok(!/AlertDialog\.Builder|new AlertDialog/.test(cp.slice(cp.indexOf('static void confirmDelete('), cp.indexOf('static void deleteForAll('))),
      'удаление 10.63: вопрос — окно в стиле приложения, не системное');
    // архив: SQL — только своё и только закрытые; заявки не меняются
    assert.ok(body.includes('primary key (user_email, order_id)') && body.includes('alter table public.app_order_archived enable row level security;')
      && body.includes('revoke all on public.app_order_archived from anon;') && body.includes('revoke all on public.app_order_archived from authenticated;')
      && body.includes('grant select on public.app_order_archived to authenticated;')
      && body.includes("using (user_email = lower(coalesce(auth.jwt() ->> 'email', '')));")
      && !/grant\s+(insert|update|delete|all)[^;]*app_order_archived/i.test(body) && !/for\s+(insert|update|delete|all)\b/i.test(body),
      '20261014_order_archive.sql: каждый видит только свой архив; писать напрямую нельзя');
    assert.ok(body.includes("me text := lower(coalesce(auth.jwt() ->> 'email', ''));") && body.includes('values (me, v_id)')
      && body.includes("lower(coalesce(o.user_email, '')) = me) then") && body.includes("in ('new', 'rented')) then\n      return 0;")
      && (body.match(/security definer\s+set search_path = public/g) || []).length === 2
      && body.includes('revoke all on function public.iva_archive_order(text, boolean) from public, anon;')
      && body.includes('revoke all on function public.iva_my_order_parts() from public, anon;')
      && !/\bupdate\s+public\.|insert\s+into\s+public\.(orders|order_parts)|delete\s+from\s+public\.(orders|order_parts)/i.test(body),
      '20261014_order_archive.sql: архив — только своя закрытая заявка; заявки и части заявок не меняются');
    const parts = body.slice(body.indexOf('create or replace function public.iva_my_order_parts()'));
    assert.ok(parts.includes("where lower(coalesce(o.user_email, '')) = lower(coalesce(auth.jwt() ->> 'email', ''))")
      && parts.includes('returns table (order_id text, owner_email text, status text)') && !/o\.(name|phone|address|comment)/.test(parts),
      '20261014_order_archive.sql: части — только своих заявок, без данных клиента');
    assert.ok(sql.includes('--   drop function if exists public.iva_my_order_parts();') && sql.includes('--   drop function if exists public.iva_archive_order(text, boolean);')
      && sql.includes('--   drop table if exists public.app_order_archived;'), '20261014_order_archive.sql: откат записан');
    // приложение: архив только у закрытых, только после ответа базы; без SQL — подсказка
    assert.ok(op.includes('if (on && !ordClosed(r)) { Toast.makeText(a, ARCH_ONLY_CLOSED, Toast.LENGTH_LONG).show(); return; }')
      && op.includes('IvaClientPatch.apiCall(a, ARCH_RPC, "POST", archBody(id, on), true)') && op.includes('if ("ok".equals(fc)) {')
      && op.includes('} else if ("404".equals(fc)) {') && op.includes('if (!adm && !r.containsKey(ORD_PARTIAL) && (archived || ordClosed(r))) {')
      && !/"DELETE"|"PATCH"/.test(op), 'заявки 10.63: в архив — только закрытые и только после ответа базы; заявки не удаляются и не меняются');
    // подробности: данные проката, без данных клиента
    assert.ok(op.includes('static final String[] ORD_COLS = {"id", "created_at", "status", "tools", "days", "get_method", "comment"};')
      && !/"Имя"|"Адрес"[^ ]|ordAdd\(out, "Телефон", r\.get/.test(op.slice(0, op.indexOf('// ───────────── 10.68: подробности заявки у админа')))
      && op.indexOf('// ───────────── 10.68: подробности заявки у админа') > 0 && op.includes('ordAdd(out, "Телефон", ad.get("phone"));')
      && op.includes('static final String PROKAT_PATH = "/rest/v1/admins?select=email,full_name,company,address,phone,work_hours&email=in.";'),
      'заявки 10.63: в подробностях — прокат (как на странице проката), имени, телефона и адреса клиента нет');
    // свой список — только у клиента; не загрузился — штатный
    const rn = op.slice(op.indexOf('static void ordRender('), op.indexOf('static TextView ordHint('));
    assert.ok(rn.includes('if (!client || (!ordMine(a) && ordFailed)) {') && rn.includes('if (client) ordAttach(a);')
      && rn.includes('if (scroll != null) scroll.setVisibility(View.VISIBLE);') && rn.includes('if (!ordOnTab(ordClosed(r), arch, ordTab)) continue;')
      && op.includes('if (archived) return false;'),
      'заявки 10.63–10.64: свой список только у клиента; не загрузилось — штатный; убранные в архив не показываются');
    assert.ok(op.includes('if ("profile".equals(tab)) {\n            try { profileArchiveRow(a); } catch (Throwable ignore) {}')
      && op.includes('list.addView(row, list.indexOfChild(call), lp);') && op.includes('row.setVisibility(client ? View.VISIBLE : View.GONE);'),
      'кабинет 10.63: «Архив заявок» — только у клиента, перед штатной строкой (ничего не удаляется)');
    assert.ok(!/passport|birth|паспорт|getLastKnownLocation|ACCESS_FINE/i.test(op), 'заявки 10.63: лишних персональных данных нет');
    assert.ok(/eq\("заявки 10\.63: /.test(tp) && /eq\("назад 10\.63: /.test(tp) && /eq\("удаление 10\.63: /.test(tp), 'IvaPatchTest: проверки 10.63');
  }
  // 10.64 (просьба владельца 14.10.2026): заявки снова не нажимались — в 10.63 наш список вставлялся рядом со штатным
  // внутрь PullScrollView (ScrollView держит одного ребёнка → исключение глушилось). Теперь раздел — ребёнок pageOrders;
  // вкладки «Активные / Закрытые заявки» как в разделе логотипа; любая ошибка — штатный список, и он тоже нажимается.
  {
    const op = R('android/patches/IvaOrderPatch.java'), jc = R('android/patches/IvaClientPatch.java'), tp = R('android/patches/test/IvaPatchTest.java');
    const rn = op.slice(op.indexOf('static void ordRender('), op.indexOf('static TextView ordHint('));
    assert.ok(rn.includes('Object po = get(a, "pageOrders");') && rn.includes('page.addView(root, new LinearLayout.LayoutParams(-1, 0, 1f));')
      && !/getParent\(\)/.test(rn)
      && !/ordersList[^;]*getParent|\(ViewGroup\) nat\.getParent\(\)|parent\.addView/.test(op.slice(op.indexOf('// ───────────────────────── 10.62–10.63: заявки клиента'))),
      'заявки 10.64: наш раздел — ребёнок pageOrders (LinearLayout), в PullScrollView и рядом со штатным списком ничего не вставляется');
    assert.ok(op.includes('static void ordSafeRender(Activity a) {') && (op.match(/ordRender\(a\)/g) || []).length === 2
      && op.includes('{ try { ordRender(a); } catch (Throwable ignore) {} return; }') && op.includes('        try { ordRender(a); }\n        catch (Throwable t) {')
      && (op.match(/ordSafeRender\(a\);/g) || []).length === 5,
      'заявки 10.64: любая ошибка своего раздела — штатный список (ordSafeRender), не пустота и не ненажимаемые карточки');
    const att = op.slice(op.indexOf('static void ordAttach('), op.indexOf('static boolean ordCard('));
    assert.ok(att.includes(': ordCardRow(date, tl, ') && !att.includes('if (idx < 0) { missing = true; continue; }'),
      'заявки 10.64: штатные карточки нажимаются всегда — без данных открывается то, что на карточке, и прокат');
    assert.ok(op.includes('static final String[] ORD_TABS = {"Активные заявки", "Закрытые заявки"};') && op.includes('tabs.setBackground(IvaClientPatch.tabCard(a));')
      && op.includes('IvaClientPatch.styleTabs(a, root, new String[]{ORD_TAB_ACTIVE, ORD_TAB_CLOSED}, ordTab);')
      && jc.includes('styleTabs(a, hub, new String[]{HUB_TAB_FAV_TAG, HUB_TAB_MINE_TAG}, hubTab);'),
      'заявки 10.64: вкладки «Активные / Закрытые» — те же, что в разделе логотипа (tabCard, tabButton, styleTabs)');
    assert.ok(op.includes('Class.forName("ru.prokatnvsk.app.PullScrollView").getConstructor(android.content.Context.class).newInstance(a);')
      && op.includes('sc = new ScrollView(a);') && op.includes('getDeclaredField("onRefresh")'),
      'заявки 10.64: «потянуть — обновить» как у штатного списка; нет PullScrollView — обычная прокрутка');
    assert.ok(op.includes('return base + "&user_email=eq." + java.net.URLEncoder.encode(e, "UTF-8");'), 'заявки 10.64: только свои заявки, как на сайте');
    for (const str of ['pageOrders', 'ordersScroll', 'ordersList'])
      assert.ok(has(arsc14, str), `resources.arsc 10.14: нет «${str}» — раздел «Заявки» 10.64 не найдёт штатную страницу`);
    assert.ok(has(dex14, 'Lru/prokatnvsk/app/PullScrollView;') && has(dex14, 'onRefresh'), 'classes.dex 10.14: нет PullScrollView.onRefresh');
    assert.ok(/eq\("заявки 10\.64: /.test(tp), 'IvaPatchTest: проверки 10.64');
  }
  // 10.66 (просьбы владельца 14.10.2026): «Состояние» в форме инструмента; картинки инструментов на карточках заявок
  {
    const ip = R('android/patches/IvaPatch.java'), op = R('android/patches/IvaOrderPatch.java'), tp = R('android/patches/test/IvaPatchTest.java');
    const cs = ip.slice(ip.indexOf('// ───────────── 10.66: «Состояние»'));
    assert.ok(cs.includes('condRow(a, "atTerms", "atSave", 0);') && cs.includes('condRow(a, "teTerms", "teSave", 1);')
      && cs.includes('form.addView(row, form.indexOfChild(terms), lp);          // перед «Условием аренды»')
      && cs.includes('if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && v.isEnabled()) condPut(terms, k);\n                    return false;')
      && cs.includes('static final String[] COND_TITLES = IvaSearchPatch.COND_TITLES;') && cs.includes('int bit = IvaSearchPatch.condFromTerms(terms);')
      && !/removeView|setOnClickListener\(null\)|apiCall|"PATCH"|"POST"/.test(cs),
      'состояние 10.66: строка перед «Условием аренды» в обеих формах; строка «Состояние: …» в условиях — при нажатии публикации (само нажатие не перехватываем), разбор — как у фильтра; ничего не удаляет и в базу само не пишет');
    assert.ok(ip.includes('try { condRows(a); } catch (Throwable ignore) {}      // 10.66: «Состояние» — Новый / Хорошее / Рабочее')
      && ip.includes('try { condRows(a); } catch (Throwable ignore) {}      // 10.66: «Состояние» в форме инструмента'),
      'состояние 10.66: ставится из onTab и onHub (новых вставок в smali нет)');
    assert.ok(/'Состояние: ' \+ condTitle\(F\.cond\)/.test(R('new.html')) && /Состояние:\\s\*Новый/.test(R('assets/sb.js')),
      'состояние 10.66: формат — тот же, что пишет и читает сайт');
    for (const str of ['atTerms', 'teTerms', 'atSave', 'teSave', 'bg_btn_orange', 'bg_field'])
      assert.ok(has(arsc14, str), `resources.arsc 10.14: нет «${str}» — строка «Состояние» не найдёт форму`);
    const cv = op.slice(op.indexOf('static View ordCardView('), op.indexOf('/** Запасной путь'));
    assert.ok(cv.includes('card.addView(c, new LinearLayout.LayoutParams(0, -2, 1f));') && cv.includes('View pics = ordPicsView(a, r);')
      && cv.includes('} catch (Throwable ignore) {}   // без картинок карточка остаётся как была')
      && cv.includes('card.setOnClickListener(') && cv.includes('card.setOnLongClickListener(') && cv.includes('return card;')
      && cv.includes('View pic = IvaChatPatch.toolPic(a, tool, id, ORD_PIC_DP);') && cv.includes('static final int ORD_PICS_MAX = 2, ORD_PIC_DP = 56;')
      && !/apiCall|new Thread/.test(cv),
      'заявки 10.66: справа картинки (скруглённые, штатный loadPhoto, без своих запросов); вся карточка нажимается и удерживается, как раньше');
    assert.ok(/eq\("состояние 10\.66: /.test(tp) && /eq\("заявки 10\.66: /.test(tp), 'IvaPatchTest: проверки 10.66');
  }
  // 10.67 (просьбы владельца 14.10.2026): картинки в заявках у админа; выбранная подкатегория — белым;
  // «Позвонить» / «Написать в МАКС» не возвращаются в кабинет клиента после обновления свайпом
  {
    const ip = R('android/patches/IvaPatch.java'), op = R('android/patches/IvaOrderPatch.java'), cp = R('android/patches/IvaClientPatch.java');
    const tp = R('android/patches/test/IvaPatchTest.java');
    const ad = op.slice(op.indexOf('// ── 10.67: картинки и в заявках у админа'), op.indexOf('/** Запасной путь (наш список не загрузился)'));
    assert.ok(ad.length > 1000 && ad.includes('if (rows.isEmpty() || cards.size() != rows.size()) return;')
      && ad.includes('keys.add(IvaNotifyPatch.clientKey(a, j));') && ad.includes('View pics = ordPicsView(a, r);')
      && ad.includes('card.addView(pics, card.getChildCount() - 1, pl);')
      && (ad.match(/removeView\(/g) || []).length === 5 && ad.includes('card.removeView(names);') && ad.includes('card.removeView(date);')
      && ad.includes('col.addView(date);') && ad.includes('col.addView(names);')
      && ad.includes('card.addView(date, 0);') && ad.includes('card.addView(names, 1);')
      && !/removeAllViews|setOnClickListener|setOnLongClickListener|apiCall|new Thread|\.setVisibility\(/.test(ad),
      'админ 10.67: картинки по порядку штатных карточек (число не совпало — не трогаем); дата и названия только переносятся (на ошибке — обратно); нажатия, кнопки и запросы не трогаем');
    assert.ok(op.includes('else try { admPics(a); } catch (Throwable ignore) {}   // 10.67: картинки на карточках админа')
      && (op.match(/setOnHierarchyChangeListener\(/g) || []).length === 1
      && op.indexOf('if (o instanceof LinearLayout && ordHooked.get() != o) {') < op.indexOf('if (!(o instanceof LinearLayout) || !IvaClientPatch.isClient(a)) { try { ordRender(a); }'),
      'админ 10.67: тот же один слушатель списка (и для админа), картинки — после штатного renderAdmOrders');
    const pk = ip.slice(ip.indexOf('// ───────────── 10.67: выбранная подкатегория'));
    assert.ok(pk.includes('{"atCat", "atCatKey"}, {"atSub", "atSubKey"},') && pk.includes('{"teCat", "editToolCat"}, {"teSub", "editToolSub"},')
      && pk.includes('tv.setTextColor(on ? navColor(a, "text", 0xfff2f4f8) : navColor(a, "gray2", 0xff6b7080));')
      && pk.includes('if (pickHooked.containsKey(tv)) continue;') && !/setText\(|removeView|apiCall/.test(pk)
      && (ip.match(/try \{ pickColors\(a\); \} catch \(Throwable ignore\) \{\}/g) || []).length === 2,
      'подкатегория 10.67: цвет по штатному ключу (выбрано — text, нет — gray2) в обеих формах, текст не меняем; из onTab и onHub');
    const pc = cp.slice(cp.indexOf('static void profileContacts(Activity a) {'), cp.indexOf('static void watchContacts('));
    const wc = cp.slice(cp.indexOf('static void watchContacts('), cp.indexOf('static void watchContacts(') + 2000);
    assert.ok(pc.includes('contactsShow = show;') && pc.includes('try { watchContacts(a); } catch (Throwable ignore) {}')
      && pc.includes('if (show || visible == null) return false;')
      && wc.includes('if (contactsWatched.get() == a) return;') && wc.includes('addOnPreDrawListener(')
      && wc.includes('if (contactsFixes >= CONTACTS_FIX_MAX) return true;') && wc.includes('} catch (Throwable ignore) { return true; }')
      && cp.includes('static final int CONTACTS_FIX_MAX = 2;') && !/apiCall|new Thread|removeView/.test(pc + wc),
      'кабинет 10.67: строки прячутся перед отрисовкой после любого штатного renderProfile (кадр пропускается не больше 2 раз подряд); админу не навязываем');
    for (const str of ['rowCall', 'rowMax', 'atSubKey', 'atCatKey', 'editToolSub', 'editToolCat', 'admParts', 'admDrill', 'admClientKey', 'partClientKey'])
      assert.ok(has(arsc14, str) || has(dex14, str), `APK 10.14: нет «${str}» — правка 10.67 не найдёт штатное поле`);
    assert.ok(/eq\("админ 10\.67: /.test(tp) && /eq\("подкатегория 10\.67: /.test(tp) && /eq\("кабинет 10\.67: /.test(tp), 'IvaPatchTest: проверки 10.67');
  }
  // 10.76 (просьба владельца 14.10.2026): 1) в активной заявке — «Написать» и «Позвонить» (клиенту — прокату, админу — клиенту,
  // первым может любой); 2) клиентам — напоминание об окончании аренды за 1/2/3 часа (конец = «Выдал» + сутки, по умолчанию выключено)
  {
    const op = R('android/patches/IvaOrderPatch.java'), cp = R('android/patches/IvaChatPatch.java'), np = R('android/patches/IvaNotifyPatch.java');
    const tp = R('android/patches/test/IvaPatchTest.java'), wf = R('.github/workflows/build-apk.yml'), ap = R('android/patches/apply.py');
    const sql = R('supabase/migrations/20261014_rent_reminder.sql'), chk = R('tools/check.sh');
    const fnx = (src, name) => { const mm = new RegExp(`static \\w+(?:\\[\\])? ${name}\\(`).exec(src); assert.ok(mm, `нет ${name}`); return src.slice(mm.index, src.indexOf('\n    }\n', mm.index)); };
    // 1) кнопки — только в подробностях активной заявки, у обеих сторон; сбой не ломает шторку
    const row = fnx(op, 'ordOpenRow');
    assert.ok(row.includes('if (ordActive(r, archived)) try { ordContactRow(a, sh, d, r, owners, adm); } catch (Throwable ignore) {}')
      && row.indexOf('ordContactRow(') < row.indexOf('sh.addView(close, cl);'), 'связь 10.76: «Написать»/«Позвонить» — в подробностях активной заявки, над «Закрыть»');
    assert.ok(fnx(op, 'ordActive').includes('!archived && !"Закрыта".equals(ordStatus(ordStatusOf(r)))'), 'связь 10.76: только новая или в аренде, не в архиве');
    const cr = fnx(op, 'ordContactRow');
    assert.ok(cr.includes('IvaChatPatch.openWith(a, k[0])') && cr.includes('d.dismiss()') && cr.includes('Intent.ACTION_DIAL') && !/ACTION_CALL\b|CALL_PHONE/.test(op),
      'связь 10.76: «Написать» — переписка в «Чате», «Позвонить» — набор номера без разрешения на звонки');
    const oc = fnx(op, 'ordContact');
    assert.ok(oc.includes('if (adm) return new String[]{ordVal(r.get("user_email")), ordVal(r.get("phone")), ordVal(r.get("name"))};') && oc.includes('ad.get("phone")'),
      'связь 10.76: админу — клиент заявки, клиенту — прокат из профиля');
    const ow = fnx(cp, 'openWith');
    assert.ok(ow.includes('openThread(a, p, "", null);') && ow.includes('callTab(a, "chat");') && ow.includes('!loggedIn(a)') && ow.includes('low(p).equals(low(str(IvaClientPatch.get(a, "userEmail"))))'),
      'связь 10.76: общая переписка, без входа и самому себе — нет');
    // 2) напоминание
    assert.ok(/ALTER TABLE public\.order_parts ADD COLUMN IF NOT EXISTS rented_at timestamptz;/.test(sql) && sql.includes('new.rented_at := old.rented_at;')
      && /BEFORE INSERT OR UPDATE ON public\.order_parts/.test(sql) && sql.includes("REVOKE ALL ON FUNCTION public.iva_my_rentals() FROM public, anon;")
      && sql.includes("and lower(coalesce(o.user_email, '')) = lower(auth.jwt() ->> 'email')") && sql.includes("and lower(btrim(coalesce(p.status, ''))) = 'rented'")
      && !/\b(o\.name|o\.phone|o\.address|passport)\b/.test(sql) && !/\bDROP\s+(TABLE|COLUMN)\b/i.test(sql.replace(/^--.*$/mg, '')),
      'SQL 10.76: время выдачи ставит база (не подделать), клиенту — только свои выданные, без личных данных, ничего не удаляется');
    assert.ok(chk.includes('tools/rent-reminder-stand.py'), 'SQL 10.76: стенд на PostgreSQL — в check.sh');
    const crn = fnx(np, 'checkRent');
    assert.ok(crn.includes('if (admin || email == null || email.trim().isEmpty() || !rentOn(a)) return;') && crn.includes('"404".equals(r[0])')
      && crn.includes('if (rentDue(e.end, now, hours) && !seen.contains(e.key)) { due.add(e); keys.add(e.key); }')
      && crn.indexOf('p.edit().putString(k, joinSet(remember(seen, keys))).apply();') < crn.indexOf('showNote(a, CH_RENT'),
      'аренда 10.76: только клиентам и если включено; без SQL — тихо; каждое напоминание один раз');
    assert.ok(crn.includes('if (a instanceof Activity && now - rentAt < RENT_EVERY_MS) return;'), 'аренда 10.76: открыто — не чаще раза в 5 минут');
    assert.ok(np.includes('if (logged && !admin && !email.isEmpty()) try { checkRent(a, email, admin); } catch (Throwable ignore) {}')
      && fnx(np, 'bgRun').includes('if (logged && !admin) try { checkRent(c, email, admin); } catch (Throwable ignore) {}'),
      'аренда 10.76: и при открытом приложении, и в фоне; сбой не мешает остальным уведомлениям');
    assert.ok(np.includes('static boolean rentOn(Context c) { return pref(c, K_ALL) && prefs(c).getBoolean(K_RENT, false); }'), 'аренда 10.76: по умолчанию выключено, общий выключатель главнее');
    assert.ok(np.includes('else if ("rent".equals(open)) IvaOrderPatch.openById(a, target);'), 'аренда 10.76: нажатие — эта заявка');
    const os = fnx(np, 'openSettings');
    assert.ok(os.includes('if (loggedIn(a) && !admin) {') && os.indexOf('switchRow(a, card, RENT_TITLE') > os.indexOf('if (loggedIn(a) && !admin) {')
      && os.includes('RENT_HOURS.length') && os.includes('"bg_chip_on" : "bg_chip"') && os.includes('card.addView(chips'),
      'аренда 10.76: в «Какие присылать» у клиентов — переключатель и чипы «За 1 час / 2 часа / 3 часа» в стиле приложения');
    assert.ok(/static final int\[\] RENT_HOURS = \{1, 2, 3\};/.test(np), 'аренда 10.76: выбор — 1, 2 или 3 часа');
    assert.ok(!/AlarmManager|WorkManager|customer_profiles|passport|phone|surname/.test(np), 'аренда 10.76: без будильников и личных данных в IvaNotifyPatch');
    assert.ok(!ap.includes('openWith') && !ap.includes('checkRent') && !ap.includes('openById'), 'связь/аренда 10.76: новых вставок в smali нет');
    assert.ok(/NEW_NAME: '10\.76'/.test(wf) && /NEW_CODE: '150'/.test(wf), 'версия 10.76 (150)');
    assert.ok((tp.match(/eq\("связь 10\.76: /g) || []).length >= 12 && (tp.match(/eq\("аренда 10\.76: /g) || []).length >= 16, 'IvaPatchTest: проверки 10.76');
  }
  // 10.75 (просьба владельца 14.10.2026): в «Настройках» — раскрывающаяся кнопка «Уведомления», по умолчанию свёрнута;
  // внутри сверху «Открыть настройки уведомлений Android», потом какие присылать, потом расписание, потом всё остальное
  {
    const np = R('android/patches/IvaNotifyPatch.java'), tp = R('android/patches/test/IvaPatchTest.java');
    const mm = /static \w+ openSettings\(/.exec(np); assert.ok(mm, 'IvaNotifyPatch: нет openSettings');
    const os = np.slice(mm.index, np.indexOf('\n    }\n', mm.index));
    const at = (t) => { const i = os.indexOf(t); assert.ok(i > 0, `openSettings: нет ${t}`); return i; };
    assert.ok(at('page.addView(head, hl);') < at('page.addView(body, new LinearLayout.LayoutParams(-1, -2));') && os.includes('body.setVisibility(View.GONE);'),
      'настройки 10.75: список «Уведомления» под кнопкой и по умолчанию свёрнут');
    assert.ok(os.includes('boolean open = body.getVisibility() != View.VISIBLE;') && os.includes('body.setVisibility(open ? View.VISIBLE : View.GONE);'),
      'настройки 10.75: нажатие на кнопку раскрывает и сворачивает список');
    assert.ok((os.match(/Открыть настройки уведомлений Android/g) || []).length === 1 && (np.match(/Открыть настройки уведомлений Android/g) || []).length === 1,
      'настройки 10.75: кнопка Android — одна, в других местах её нет');
    const sys = at('linkButton(a, body, "Открыть настройки уведомлений Android"');
    assert.ok(sys < at('switchRow(a, card, "Все уведомления"') && at('switchRow(a, card, "Все уведомления"') < at('schedSw[0] = switchRow(')
      && at('schedSw[0] = switchRow(') < at('body.addView(status, sl);') && at('body.addView(status, sl);') < at('body.addView(note, nl);'),
      'настройки 10.75: порядок — Android, какие присылать, расписание, остальное');
    assert.ok(!/(?:page|root)\.addView\((?:status|test|note|push|card|sc|sys)\b/.test(os) && (os.match(/page\.addView\(/g) || []).length === 2
      && (os.match(/\bpage\b/g) || []).length === 7 && !/\(a, page\b/.test(os),
      'настройки 10.75: всё про уведомления — внутри списка, снаружи только кнопка');
    assert.ok(os.includes('hsub.setText(headSummary(master, ok, schedSw[0] != null && schedSw[0].isChecked()));'), 'настройки 10.75: под кнопкой — коротко о состоянии');
    assert.ok(os.includes('drawable(a, "bg_card")') && os.includes('bellIcon(cOrange)') && !/#[0-9A-Fa-f]{6}|0xff(?!101217|f2f4f8|9aa1b0|ff8f1f)[0-9a-f]{6}/i.test(os),
      'настройки 10.75: стиль приложения — bg_card, оранжевый значок, только цвета темы');
    assert.ok((tp.match(/eq\("настройки 10\.75: /g) || []).length >= 5, 'IvaPatchTest: проверки 10.75');
  }
  // 10.74 (решение владельца 14.10.2026, отменяет «без Firebase» от 13.10 — только для админов): мгновенные уведомления.
  // Push — только сигнал «проверь сейчас»; показывает уведомление та же проверка, что и раньше (расписание, переключатели, «уже показано»).
  {
    const pp = R('android/patches/IvaPushPatch.java'), np = R('android/patches/IvaNotifyPatch.java'), tp = R('android/patches/test/IvaPatchTest.java');
    const ax = R('android/patches/axml_push.py'), chk = R('.github/workflows/check.yml'), ap = R('android/patches/apply.py');
    const fnp = (src, name) => { const mm = new RegExp(`static \\w+ ${name}\\(`).exec(src); const i = mm ? mm.index : -1; assert.ok(i > 0, `нет ${name}`); return src.slice(i, src.indexOf('\n    }\n', i)); };
    assert.ok(!/^import com\.google|com\.google\.firebase|FirebaseMessaging|AlarmManager|WorkManager/m.test(pp), 'push 10.74: без библиотек Firebase и будильников — только API Android');
    assert.ok(!/customer_profiles|passport|phone|surname/.test(pp), 'push 10.74: никаких персональных данных клиентов');
    // приёмник: только сигнал → обычная проверка (окно открыто — wake, закрыто — bgRun), всегда finish
    const rx = pp.slice(pp.indexOf('public static final class Rx'));
    assert.ok(rx.includes('ACT_RECEIVE.equals(intent.getAction())') && rx.includes('IvaNotifyPatch.wake()') && rx.includes('IvaNotifyPatch.bgRun(app)')
      && rx.includes('goAsync()') && /finally \{ try \{ pr\.finish\(\); \}/.test(rx) && rx.includes('setResultCode(-1)'),
      'push 10.74: приёмник — только «проверь сейчас» через те же проверки, фоновый приём всегда завершается');
    assert.ok(!/showNote|Notification\.Builder/.test(pp), 'push 10.74: уведомления показывает только IvaNotifyPatch (расписание 10.73 и «уже показано»)');
    // токен у сервисов Google — как Firebase SDK (bindService + Messenger), всегда отвязываемся
    const gt = fnp(pp, 'gmsToken');
    assert.ok(gt.includes('new Intent(ACT_REGISTER).setPackage(GMS)') && gt.includes('m.what = 1;') && gt.includes('d.putString("pkg", app.getPackageName());')
      && gt.includes('d.putBundle("data", extras);') && /\} finally \{\n\s+if \(bound\) try \{ app\.unbindService\(conn\); \}/.test(gt) && gt.includes('try { ht.quit(); }'),
      'push 10.74: токен — протокол GmsRpc/MessengerIpcClient, соединение и поток всегда закрываются');
    // только админам; вышли или вошёл клиент — токен убираем
    const sy = fnp(pp, 'sync');
    assert.ok(sy.includes('shouldForget(regEmail, tok, logged, admin)') && sy.includes('/rest/v1/rpc/iva_push_forget') && sy.includes('if (!logged || !admin || email == null'),
      'push 10.74: регистрация только у админа; после выхода токен забывается');
    assert.ok(fnp(pp, 'register').includes('IvaClientPatch.apiCall(a, "/rest/v1/rpc/iva_push_register"'), 'push 10.74: токен в базу — от имени вошедшего админа');
    assert.ok(np.includes('try { IvaPushPatch.sync(a, email, logged, admin); } catch (Throwable ignore) {') && /IvaPushPatch\.sync[^]*?finally \{\n\s*busy = false;/.test(fnp(np, 'tick')),
      'push 10.74: подключение — из обычного опроса, сбой не мешает уведомлениям');
    assert.ok(fnp(np, 'wake').includes('if (busy) poll.postDelayed(TICK, 2000); else poll.post(TICK);'), 'push 10.74: приход push при открытом окне — проверка сразу');
    const os = fnp(np, 'openSettings');
    assert.ok(os.indexOf('IvaPushPatch.statusLine(a)') > os.indexOf('body.addView(note, nl);') && /if \(admin\) \{\s+\/\/ 10\.74/.test(os), 'push 10.74: строка состояния — только у админов');
    assert.ok(!/com\.google\.firebase|FirebaseMessaging|com\.google\.android\.gms/.test(np), 'push 10.74: IvaNotifyPatch по-прежнему без Firebase (код push — отдельно)');
    // манифест и сборка
    assert.ok(ax.includes('RECEIVER = "ru.prokatnvsk.app.IvaPushPatch$Rx"') && ax.includes('SEND = "com.google.android.c2dm.permission.SEND"')
      && ax.includes('(ns, exp_attr, NONE, TYPE_BOOL, 0xFFFFFFFF)') && ax.includes('GMS = "com.google.android.gms"'),
      'push 10.74: приёмник в манифесте — exported, только от сервисов Google (c2dm SEND), queries для Android 11+');
    assert.ok(code.indexOf('python3 android/patches/axml_push.py "$W/AndroidManifest.xml"') > code.indexOf('python3 android/patches/axml_service.py "$W/AndroidManifest.xml"'),
      'push 10.74: axml_push.py — после axml_service.py');
    assert.ok(/IvaReviewPatch\.java android\/patches\/IvaPushPatch\.java 2>&1/.test(code) && chk.includes('android/patches/IvaPushPatch.java'), 'push 10.74: IvaPushPatch компилируется в сборку и в проверку');
    assert.ok(!ap.includes('IvaPushPatch'), 'push 10.74: новых вставок в smali нет');
    assert.ok((tp.match(/eq\("push 10\.74: /g) || []).length >= 14, 'IvaPatchTest: проверки 10.74 (push)');
  }
  // 10.73 (решения владельца 14.10.2026): расписание уведомлений у админов — одно на все виды, у каждого дня свои часы,
  // пришедшее вне расписания показываем в начале разрешённого времени
  {
    const np = R('android/patches/IvaNotifyPatch.java'), tp = R('android/patches/test/IvaPatchTest.java');
    const fn = (name) => { const mm = new RegExp(`static \\w+ ${name}\\(`).exec(np); const i = mm ? mm.index : -1; assert.ok(i > 0, `IvaNotifyPatch: нет ${name}`); return np.slice(i, np.indexOf('\n    }\n', i)); };
    // вне расписания: ничего не запоминаем как показанное (иначе уведомление потеряется) и не показываем
    const co = fn('checkOrders'), cm = fn('checkMessages'), cu = fn('checkUpdate');
    assert.ok(co.indexOf('if (quiet(a, true)) return;') > co.indexOf('"Заявки •"') && co.indexOf('if (quiet(a, true)) return;') < co.indexOf('remember(seen, ids)'),
      'расписание 10.73: заявки — отметка «Заявки •» обновляется всегда, а «уже показано» — только в разрешённое время');
    assert.ok(cm.indexOf('if (quiet(a, admin)) return;') > 0 && cm.indexOf('if (quiet(a, admin)) return;') < cm.indexOf('remember(seen, ids)'),
      'расписание 10.73: сообщения — вне расписания не запоминаются, придут позже');
    assert.ok(cu.includes('if (!on(a, K_UPD, admin) || quiet(a, admin)) return;') && cu.indexOf('quiet(a, admin)') < cu.indexOf('"upd_checked"'),
      'расписание 10.73: обновления — вне расписания не проверяются и не отмечаются');
    // одна и та же проверка и при открытом приложении, и в фоне (служба вызывает те же check*)
    const bg = fn('bgRun');
    assert.ok(bg.includes('checkOrders(c, email)') && bg.includes('checkMessages(c, email, admin, null)') && bg.includes('checkUpdate(c, admin)'),
      'расписание 10.73: фоновая проверка идёт через те же checkOrders/checkMessages/checkUpdate');
    // только админам; по умолчанию выключено — всё как раньше
    const q = fn('quiet');
    assert.ok(q.includes("if (!admin || !prefs(c).getBoolean(K_SCHED_ON, false)) return false;") && q.includes('catch (Throwable e) {\n            return false;'),
      'расписание 10.73: только у админа, по умолчанию выключено, при сбое уведомления не глушатся');
    assert.ok(np.includes('sw.setChecked(prefs(a).getBoolean(k, defOn(k)));') && np.includes('static boolean defOn(String k) { return !K_SCHED_ON.equals(k) && !K_RENT.equals(k); }'), 'расписание 10.73: остальные переключатели по умолчанию включены, расписание — выключено');
    const os = fn('openSettings');
    assert.ok(os.includes('if (admin) {') && os.indexOf('schedSw[0] = switchRow(') > os.indexOf('if (admin) {') && os.includes('for (int i = 0; i < 7; i++) {'),
      'расписание 10.73: раздел «Расписание» с семью днями — только админам');
    assert.ok(/static final int SCHED_FROM = 9 \* 60, SCHED_TO = 21 \* 60;/.test(np) && np.includes('static final String[] DAYS = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};'),
      'расписание 10.73: по умолчанию 09:00–21:00, неделя с понедельника');
    assert.ok(fn('pickHours').includes('android.R.style.Theme_Material_Dialog') && (fn('pickHours').match(/, true\);/g) || []).length === 2,
      'расписание 10.73: выбор часов — штатный диалог в 24-часовом формате, «с» и «до»');
    assert.ok(!/SharedPreferences\.Editor[^;]*clear\(\)|\.clear\(\)\.apply/.test(np), 'расписание 10.73: настройки уведомлений не стираются');
    assert.ok((tp.match(/eq\("расписание 10\.73: /g) || []).length >= 13, 'IvaPatchTest: проверки 10.73 (расписание)');
  }
  // 10.72 (просьба владельца 14.10.2026): «Хочу сдавать инструмент» → «Чат с владельцем» — отдельная переписка;
  // у владельца в карточке — подмигивающий кислотно-зелёный смайлик, у клиента — аватарка владельца
  {
    const cp = R('android/patches/IvaChatPatch.java'), tp = R('android/patches/test/IvaPatchTest.java');
    const sql = R('supabase/migrations/20261014_chat_lend_topic.sql');
    // кнопка lendChat есть в 10.14 и перехватывается так же, как «Спросить…»; без входа / владелец / без SQL — штатно
    assert.ok(has(arsc14, 'lendChat') && has(dex14, 'lendChatSend'), '10.14: нет кнопки lendChat / lendChatSend');
    const hl = cp.slice(cp.indexOf('static void hookLend('), cp.indexOf('static void nativeAsk('));
    assert.ok(hl.includes('IvaClientPatch.id(a, "lendChat")') && hl.includes('openThread(a, OWNER, LEND, lendObj());')
      && hl.includes('if (!loggedIn(a) || superView(str(IvaClientPatch.get(a, "userEmail"))) || !topicColumn) { callNative(a, "lendChatSend"); return; }')
      && (hl.match(/callNative\(a, "lendChatSend"\)/g) || []).length === 2 && !/setOnLongClickListener/.test(hl),
      'сдавать 10.72: «Чат с владельцем» открывает переписку «Хочу сдавать»; без входа, у владельца, без SQL и при сбое — штатно');
    assert.ok(cp.includes('        hookAsk(a);\n        hookSend(a);\n        hookLend(a);'), 'сдавать 10.72: кнопка подключается вместе с остальными (install)');
    // в базу — только topic='lend' и только из этой переписки; без колонки — общий чат, как раньше
    const sb = cp.slice(cp.indexOf('static JSONObject sendBody('), cp.indexOf('static boolean topicColumn'));
    assert.ok(sb.includes('if (isLend(tool)) { if (topicCol) b.put("topic", LEND); }') && sb.includes('else if (!clean(tool).isEmpty() && toolCol) b.put("tool_id", tool);'),
      'сдавать 10.72: topic пишется только в переписке «Хочу сдавать», вместо tool_id');
    assert.ok(cp.includes('if (!ok(r) && b.has("topic")) {') && cp.includes('b.remove("topic");')
      && cp.includes('if (isLend(openTool) && !topicColumn) {') && cp.includes('if (!topicColumn && now - topicAt > HIDDEN_RETRY_MS) topicColumn = true;'),
      'сдавать 10.72: без SQL — отправка без topic и общий чат; колонку проверяем снова раз в 10 минут');
    assert.ok(cp.includes('String tid = isLendObj(tool) ? LEND : toolIdOf(tool);') && cp.includes('return isLend(tool) ? lendObj() : toolCache.get(tool);')
      && cp.includes('openThread(a, t[1].isEmpty() ? t[0] : t[1], t[2], toolObjFor(t[2]));') && cp.includes('c.tool, toolObjFor(c.tool));'),
      'сдавать 10.72: переписка узнаётся по метке в штатном chatTool (из списка и из уведомления); штатное «Написать» — общий чат');
    assert.ok(cp.includes('msgTool(o.isNull("tool_id") ? "" : o.optString("tool_id", ""), o.isNull("topic") ? "" : o.optString("topic", ""))')
      && cp.includes('static final String MSG_SELECT_TOPIC = MSG_SELECT + ",topic";'), 'сдавать 10.72: список читает topic');
    assert.ok(cp.includes('if (!t.isEmpty() && !isLend(t) && !toolCache.containsKey(t)') && cp.includes('if (!tool.isEmpty() && !isLend(tool) && !toolCache.containsKey(tool)'),
      'сдавать 10.72: тема не запрашивается как инструмент');
    assert.ok(cp.includes('"/rest/v1/rpc/iva_tag_my_topic"') && sql.includes('create or replace function public.iva_tag_my_topic(p_ids text[], p_topic text)'),
      'сдавать 10.72: голосовые помечаются темой функцией из SQL');
    // смайлик — только у владельца, в списке на месте картинки инструмента и в шапке переписки
    assert.ok(cp.includes('return isLend(tool) && superView(me);') && cp.includes('if (lendSmile(my, c.tool)) {') && cp.includes('row.addView(smiley(a), pl);')
      && cp.includes('card.addView(lendSmile(myH, openTool) ? smiley(a) : avatar(a, openPartner, partnerTitle(openPartner), HEAD_TOOL_DP),')
      && cp.includes('static final int SMILE_COLOR = 0xffa6ff00;'),
      'сдавать 10.72: у владельца — кислотно-зелёный смайлик вместо картинки инструмента, у клиента — аватарка владельца');
    // SQL: только колонка, ограничение и функция; ничего не удаляет, anon без доступа
    assert.ok(sql.includes('alter table public.app_messages add column if not exists topic text;')
      && sql.includes("check (topic is null or (topic = 'lend' and tool_id is null))")
      && sql.includes('revoke all on function public.iva_tag_my_topic(text[], text) from public, anon;')
      && !/^\s*(drop|delete|truncate)\b/im.test(sql), 'сдавать 10.72: SQL — колонка topic, ограничение, функция; ничего не удаляет');
    assert.ok((tp.match(/eq\("сдавать 10\.72: /g) || []).length >= 10, 'IvaPatchTest: проверки 10.72');
  }
  // 10.71 (просьба владельца 14.10.2026): рейтинг на картинке, «Нет отзывов», ряды ровные; мат запрещён (приложение + база)
  {
    const rp = R('android/patches/IvaReviewPatch.java'), tp = R('android/patches/test/IvaPatchTest.java');
    const sql = R('supabase/migrations/20261014_reviews_no_mat.sql');
    const jconst = (n) => { const m = rp.match(new RegExp('\\b' + n + ' = ((?:"[^"]*"\\s*(?:\\+\\s*)?)+)[,;]')); assert.ok(m, `IvaReviewPatch: нет ${n}`); return (m[1].match(/"([^"]*)"/g) || []).map((x) => x.slice(1, -1)).join(''); };
    // выражения в приложении и в базе одинаковые
    assert.ok(sql.includes(`IF t ~ '${jconst('MAT_RE')}' THEN`) && sql.includes(`CONTINUE WHEN t ~ '${jconst('MAT_ALLOW')}';`)
      && sql.includes(`translate(lower(p), '${jconst('MAT_FROM')}', '${jconst('MAT_TO')}')`)
      && sql.includes(`regexp_replace(s, '${jconst('MAT_STRIP').replace(/'/g, "''")}', '', 'g')`)
      && sql.includes(`regexp_split_to_table(s, '${jconst('MAT_SPLIT')}')`)
      && sql.includes("t := regexp_replace(t, '(.)\\1+', '\\1', 'g');") && rp.includes('String t = tok.replaceAll("(.)\\\\1+", "$1");'),
      'мат 10.71: проверка в приложении и в базе (iva_has_mat) должна совпадать');
    assert.equal(jconst('MAT_FROM').length, jconst('MAT_TO').length, 'мат 10.71: translate — строки одной длины');
    assert.ok(/BEFORE INSERT OR UPDATE OF text, author_name ON public\.reviews/.test(sql) && !/UPDATE OF[^\n]*status/.test(sql)
      && sql.includes("RAISE EXCEPTION 'В отзыве есть нецензурные слова. Уберите их, пожалуйста.';")
      && rp.includes('static final String MAT_MSG = "В отзыве есть нецензурные слова. Уберите их, пожалуйста.";'),
      'мат 10.71: триггер — на новый отзыв и правку текста/имени (модерация status не задевается), сообщение как в приложении');
    assert.ok(/^-- UPDATE public\.reviews SET status = 'hidden'/m.test(sql) && !/^UPDATE /m.test(sql) && !/DROP TABLE|DELETE FROM|TRUNCATE/i.test(sql),
      'мат 10.71: SQL ничего не удаляет; скрытие старых отзывов — только по желанию (закомментировано)');
    // форма не отправляет мат; «Скрыть спам» — только у отзывов с матом
    const fm = rp.slice(rp.indexOf('static void showForm('));
    assert.ok(fm.indexOf('if (bad != null) { err.setText(bad); err.setVisibility(View.VISIBLE); return; }') > 0
      && fm.indexOf('if (bad != null) { err.setText(bad); err.setVisibility(View.VISIBLE); return; }') < fm.indexOf('"/rest/v1/rpc/submit_review"'),
      'мат 10.71: форма проверяет текст и имя до отправки');
    assert.ok(rp.includes('if (!chief) return null;') && rp.includes('return mat ? "hidden" : null;'), 'мат 10.71: «Скрыть спам» — только главному админу и только с матом');
    // лента: плашка на картинке справа внизу, строка у всех карточек, карточки в ряду — на высоту ряда
    const dc = rp.slice(rp.indexOf('static void decorateCard('), rp.indexOf('static String priceText('));
    assert.ok(dc.includes('new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END)') && dc.includes('pic.addView(b, bl);') && dc.includes('b.setTag(BADGE_TAG);')
      && dc.includes('tv.setText(tx == null ? NO_REVIEWS_CARD : tx[1]);') && dc.includes('tv.setVisibility(tx == null ? View.INVISIBLE : View.VISIBLE);')
      && dc.includes('cl.height = ViewGroup.LayoutParams.MATCH_PARENT;') && dc.includes('getOrientation() == LinearLayout.HORIZONTAL'),
      'лента 10.71: «★ 4,8» на картинке справа внизу, «N отзывов» / «Нет отзывов» у всех, карточки в ряду одной высоты');
    assert.ok(!/removeView|setImage|setOnClickListener/.test(dc), 'лента 10.71: штатные картинка, избранное и нажатие не трогаются');
    assert.ok((tp.match(/eq\("(лента|мат) 10\.71: /g) || []).length >= 11, 'IvaPatchTest: проверки 10.71');
  }
  // 10.70 (решения владельца 14.10.2026): отзывы и оценки, этап 1 — те же отзывы, что на сайте; база и сайт не меняются
  {
    const rp = R('android/patches/IvaReviewPatch.java'), ip = R('android/patches/IvaPatch.java'), sp = R('android/patches/IvaSearchPatch.java');
    const op = R('android/patches/IvaOrderPatch.java'), tp = R('android/patches/test/IvaPatchTest.java'), ap = R('android/patches/apply.py');
    const mig = R('supabase/migrations/20260924_reviews.sql');
    assert.ok(!ap.includes('IvaReviewPatch'), 'отзывы 10.70: новых вставок в smali нет');
    const onTab = ip.slice(ip.indexOf('public static void onTab('), ip.indexOf('static boolean navSelected('));
    assert.ok(onTab.includes('try { IvaReviewPatch.onTab(a, tab); } catch (Throwable ignore) {}')
      && sp.includes('try { IvaReviewPatch.onFeedChild(a); } catch (Throwable ignore) {}')
      && op.includes('if (!adm && !r.containsKey(ORD_PARTIAL) && (archived || ordClosed(r))) try { IvaReviewPatch.orderBlock(a, body, r); } catch (Throwable ignore) {}'),
      'отзывы 10.70: подключено безопасно — onTab (инструмент, прокат), лента, шторка закрытой заявки клиента (не у админа)');
    assert.equal((ip.match(/IvaReviewPatch\./g) || []).length + (sp.match(/IvaReviewPatch\./g) || []).length + (op.match(/IvaReviewPatch\./g) || []).length, 3,
      'отзывы 10.70: вход только из трёх мест');
    // запросы: только те же, что у сайта (sb.js), и без e-mail авторов
    const calls = rp.match(/IvaClientPatch\.apiCall\(/g) || [];
    assert.equal(calls.length, 6, 'отзывы 10.70: запросов — 6 (список, право ×2, отзыв, модерация, рейтинг проката)');
    assert.ok(rp.includes('static final String REVIEW_COLS = "id,tool_id,author_id,author_name,rating,text,status,created_at,updated_at";')
      && !/author_email|select=\*|"PATCH"|"DELETE"/.test(rp)
      && rp.includes('"/rest/v1/reviews?select=" + REVIEW_COLS + "&tool_id=eq." + enc(id)'),
      'отзывы 10.70: отзывы читаются без e-mail авторов; таблица напрямую не меняется (только RPC базы, как на сайте)');
    for (const fn of ['can_user_review', 'submit_review', 'toggle_review_status', 'get_landlord_rating']) {
      assert.ok(rp.includes(`"/rest/v1/rpc/${fn}"`) && mig.includes(`FUNCTION public.${fn}(`), `отзывы 10.70: RPC ${fn} — тот же, что в миграции сайта`);
    }
    for (const col of 'id,tool_id,author_id,author_name,rating,text,status,created_at,updated_at'.split(','))
      assert.ok(new RegExp(`\\n  ${col} `).test(mig.slice(mig.indexOf('CREATE TABLE IF NOT EXISTS public.reviews'), mig.indexOf('CONSTRAINT uq_reviews_tool_author'))), `отзывы 10.70: колонки ${col} нет в таблице reviews`);
    // модерация — только кнопки главного админа (право всё равно проверяет база)
    assert.ok(rp.includes('} else if (item != null && modAction(chief, hidden, hasMat(body) || hasMat(val(r.get("author_name")))) != null) {') && rp.includes('final boolean ch = chief(a);')
      && rp.includes('return logged(a) && e instanceof String && CHIEF_EMAIL.equalsIgnoreCase(((String) e).trim());'),
      'отзывы 10.70: «Скрыть спам» / «Восстановить» — только главному админу');
    // прокат: штатный текст заменяется, только если это «Отзывы: пока нет», и только когда отзывы есть
    assert.ok(rp.includes('if (sellerText == null || !nativeNoReviews(String.valueOf(t.getText()))) return;')
      && rp.includes('if (sellerApplying || sellerText == null || !nativeNoReviews(String.valueOf(s))) return;'),
      'отзывы 10.70: на странице проката меняется только «Отзывы: пока нет»');
    // лента: только своя строка с меткой, штатные подписи не трогаются
    const dc = rp.slice(rp.indexOf('static void decorateCard('), rp.indexOf('static String priceText('));
    assert.ok(dc.includes('tv.setTag(CARD_TAG);') && !/removeView|setText\(\s*"|\.setVisibility\(View\.GONE\);\s*\}\s*$/.test(dc.replace('if (old != null) old.setVisibility(View.GONE); return;', ''))
      && !/getChildAt\(\d\)\)\.setText|info\.removeView/.test(dc), 'отзывы 10.70: на карточке — только своя строка');
    assert.ok(!/removeView\(|removeViewAt\(/.test(rp) && (rp.match(/removeAllViews\(\)/g) || []).length === 2
      && (rp.match(/block\.removeAllViews\(\)/g) || []).length === 2, 'отзывы 10.70: очищается только свой блок отзывов');
    // ошибки не роняют экраны
    assert.ok(rp.includes('if ("detail".equals(tab)) { try { onDetail(a); } catch (Throwable ignore) {} }')
      && rp.includes('else if ("seller".equals(tab)) { try { onSeller(a); } catch (Throwable ignore) {} }')
      && rp.includes('try { decorateFeed(a); } catch (Throwable ignore) {}'), 'отзывы 10.70: ошибки отзывов не трогают штатные экраны');
    assert.ok(rp.includes('if (gen != detGen || !id.equals(detTool)) return;'), 'отзывы 10.70: ответ для прошлого инструмента не рисуется на новом');
    assert.ok((tp.match(/eq\("отзывы 10\.70: /g) || []).length >= 30, 'IvaPatchTest: проверки 10.70 (отзывы)');
    for (const m of ['detItem', 'detName', 'detIsSrv', 'pageDetail', 'sellerRating', 'sellerEmail', 'feedGrid', 'userName', 'userEmail', 'money', 'Отзывы: пока нет', 'Цену уточняйте', ' ₽/сут'])
      assert.ok(has(dex14, m), `classes.dex 10.14: нет «${m}»`);
  }
  // 10.69 (решение владельца 14.10.2026): вход не слетает — без новых вставок в smali
  {
    const ip = R('android/patches/IvaPatch.java'), cp = R('android/patches/IvaClientPatch.java'), np = R('android/patches/IvaNotifyPatch.java');
    const tp = R('android/patches/test/IvaPatchTest.java'), ap = R('android/patches/apply.py');
    const au = cp.slice(cp.indexOf('// ───────────── 10.69: вход больше не слетает'));
    assert.ok(cp.indexOf('// ───────────── 10.69: вход больше не слетает') > 0 && !/authRecover|authStart|authBackup|iva_auth_bak/.test(ap),
      'вход 10.69: новых вставок в smali нет (только имеющиеся onCreated/onSession/onLogout/onTab)');
    const lo = ip.slice(ip.indexOf('public static void onLogout('), ip.indexOf('public static void onLogout(') + 400);
    assert.ok(lo.includes('try { if (IvaClientPatch.authRecover(a)) return; } catch (Throwable ignore) {}')
      && ip.includes('try { IvaClientPatch.authStart(a); } catch (Throwable ignore) {}')
      && ip.includes('try { IvaClientPatch.authBackup(a); } catch (Throwable ignore) {}   // 10.69: копия входа (вход, восстановление)')
      && ip.includes('try { IvaClientPatch.authBackup(a); } catch (Throwable ignore) {}   // 10.69: копия входа (пишется, лишь когда ключ сменился)'),
      'вход 10.69: подключено из onLogout, onCreated, onSession, onTab');
    const rc = au.slice(au.indexOf('static boolean authRecover('), au.indexOf('static void authStart('));
    // «Выйти» (главный поток) — копия стирается, выход как раньше; восстановление — только из фонового потока проверки
    assert.ok(rc.includes('boolean main = Looper.myLooper() == Looper.getMainLooper();')
      && rc.includes('if (main) { authForget(a); return false; }')
      && rc.indexOf('if (main) { authForget(a); return false; }') < rc.indexOf('authRefresh(')
      && rc.includes('if (d == AUTH_NONE) { if (has) authForget(a); return false; }'),
      'вход 10.69: кнопка «Выйти» выходит как раньше; rt не действует — выход настоящий');
    // открытие — штатным enterApp и только после штатного показа экрана входа (иначе он ляжет поверх)
    assert.ok(rc.includes('int st = authEnterStep(authVisible(a, "scrLogin"), authVisible(a, "scrMain"), tries++);')
      && rc.includes('if (st == AUTH_WAIT) { new Handler(Looper.getMainLooper()).postDelayed(this, AUTH_WAIT_MS); return; }')
      && rc.includes('if (st == AUTH_ENTER) try {')
      && rc.includes('a.getClass().getDeclaredMethod("enterApp")') && !/showScreen\(|logoutLocal\(|set\(a, "scr/.test(au),
      'вход 10.69: приложение открывается штатным enterApp после штатного экрана входа');
    // без новых данных: копируется только штатное хранилище входа, никуда не отправляется
    assert.ok(au.includes('static final String AUTH_BAK = "iva_auth_bak", AUTH_SESSION = "prokat_session";')
      && (au.match(/IvaNotifyPatch\.nativeCall\(/g) || []).length === 1
      && au.includes('SB_URL + "/auth/v1/token?grant_type=refresh_token", "POST"')
      && !/customer_profiles|passport|surname|\/rest\/v1|\/rpc\//.test(au),
      'вход 10.69: единственный запрос — обновление ключа (как штатный); новых персональных данных нет');
    // обновление заранее: не выходит при неудаче, не пишет после выхода, один за раз
    const tk = au.slice(au.indexOf('static void authTick('));
    assert.ok(tk.includes('synchronized (AUTH_LOCK)') && tk.includes('if (!r[0].startsWith("2")) return;')
      && tk.includes('if (!rt.equals(get(a, "refreshToken"))) return;')
      && tk.indexOf('if (!rt.equals(get(a, "refreshToken"))) return;') < tk.indexOf('set(a, "accessToken", r[1]);')
      && rc.includes('synchronized (AUTH_LOCK)') && np.includes('synchronized (IvaClientPatch.AUTH_LOCK) { return bgRefreshLocked(s, rt); }'),
      'вход 10.69: при неудаче не выходим; после «Выйти» пару не возвращаем; обновления не идут одновременно');
    assert.ok(au.includes('if (a.isFinishing()) return;') && au.includes('if (authRun != null) authH.removeCallbacks(authRun);'),
      'вход 10.69: проверка ключа — одна, останавливается с окном');
    assert.ok(np.includes('return cur == null || cur.trim().isEmpty() || "null".equals(cur.trim()) ? null : cur;'),
      'вход 10.69: фоновая проверка — пока ждали, вышли: пустой ключ не считается входом');
    assert.ok(np.includes('if (logged) try { IvaClientPatch.authBackup(app); } catch (Throwable ignore) {}   // 10.69: копия входа'),
      'вход 10.69: фоновая проверка обновляет копию входа');
    assert.ok((tp.match(/eq\("вход 10\.69: /g) || []).length >= 18, 'IvaPatchTest: проверки 10.69 (вход)');
    for (const m of ['enterApp', 'logoutLocal', 'restoreSession', 'scrLogin', 'scrMain', 'userPhone', 'userName', 'userEmail', 'refreshToken', 'accessToken'])
      assert.ok(has(dex14, m), `classes.dex 10.14: нет «${m}»`);
  }
  // 10.68 (решение владельца 14.10.2026): подробности заявки у админа — та же шторка, блок «Клиент» только у админа
  {
    const op = R('android/patches/IvaOrderPatch.java'), np = R('android/patches/IvaNotifyPatch.java'), tp = R('android/patches/test/IvaPatchTest.java');
    const ad = op.slice(op.indexOf('// ───────────── 10.68: подробности заявки у админа'));
    const sheet = op.slice(op.indexOf('static void ordOpenRow('), op.indexOf('static void ordFieldView('));
    assert.ok(sheet.includes('final boolean adm = r.containsKey(ADM_ROW);')
      && sheet.includes('for (String[] f : adm ? admFields(r, nice) : ordFields(r, nice)) ordFieldView(a, body, f, "Статус".equals(f[0]));')
      && sheet.includes('if (adm) admClientBlock(a, body, r);') && sheet.includes('if (!adm) ordFillProkat(a, prokat, owners);')
      && (op.match(/admClientBlock\(a, body, r\)/g) || []).length === 1 && (op.match(/m\.put\(ADM_ROW, "1"\)/g) || []).length === 1,
      'админ 10.68: шторка клиента прежняя (прокат, без данных клиента); «Клиент» — только для строки админа (admRow)');
    assert.ok(ad.includes('JSONObject o = part.optJSONObject("orders");') && ad.includes('ordAdd(out, "Телефон", r.get("phone"));')
      && !/apiCall|new Thread|"address"|removeView|removeAllViews/.test(ad)
      && ad.includes('if (part == null || card.findViewWithTag(ADM_MORE_TAG) != null) return;')
      && ad.includes('@Override public void onClick(View v) { try { ordOpenRow(a, row, false); } catch (Throwable ignore) {} }'),
      'админ 10.68: данные — из уже загруженного списка (без запросов и без адреса из базы), нажатие — на карточку, ничего не удаляется');
    assert.ok(op.includes('List<Integer> mine = drill ? admDrillIdx(keys, String.valueOf(get(a, "admClientKey"))) : null;')
      && op.includes('if (mine != null && i < mine.size())   // 10.68: нажатие на заявку — подробности')
      && op.includes('try { admMore(a, cards.get(i), (JSONObject) ((List<?>) parts).get(mine.get(i))); } catch (Throwable ignore) {}'),
      'админ 10.68: подробности — только на карточках заявок клиента, по тому же порядку, что картинки');
    assert.ok(np.includes('try { IvaOrderPatch.ordOpenRow(a, IvaOrderPatch.admRow(part), false); } catch (Throwable ignore) {}   // 10.68: сразу подробности'),
      'админ 10.68: из уведомления «Новая заявка» — сразу подробности');
    assert.ok(/eq\("админ 10\.68: /.test(tp), 'IvaPatchTest: проверки 10.68');
  }
  // 10.65: уведомления, этап 2 — когда приложение закрыто (JobScheduler раз в ~15 мин, без Firebase; решение владельца 13.10.2026)
  {
    const np = R('android/patches/IvaNotifyPatch.java'), tp = R('android/patches/test/IvaPatchTest.java'), ax = R('android/patches/axml_service.py');
    const bg = np.slice(np.indexOf('// ───────────── 10.65: этап 2'));
    assert.ok(bg.includes('public static final class Bg extends android.app.job.JobService {') && bg.includes('try { jobFinished(params, false); } catch (Throwable ignore) {}')
      && bg.includes('.setPeriodic(BG_EVERY_MS)') && bg.includes('static final long BG_EVERY_MS = 15L * 60 * 1000;')
      && bg.includes('.setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY);')
      && bg.includes('if (j.getId() == BG_JOB_ID && j.getIntervalMillis() == BG_EVERY_MS) { bgScheduled = true; return; }'),
      'уведомления 10.65: служба JobScheduler раз в 15 минут, ставится один раз (не сбивает отсчёт), работа — в своём потоке');
    const run = bg.slice(bg.indexOf('static void bgRun(Context app) {'));
    assert.ok(run.includes('if (!bgShouldRun(activityAlive(), pref(app, K_ALL))) return;')
      && run.includes('if (logged && admin) try { checkOrders(c, email); } catch (Throwable ignore) {}')
      && run.includes('if (logged) try { checkMessages(c, email, admin, null); } catch (Throwable ignore) {}')
      && run.includes('try { checkUpdate(c, admin); } catch (Throwable ignore) {}')
      && run.includes('boolean admin = logged && prefs(app).getBoolean(key("admin", email), false);'),
      'уведомления 10.65: те же проверки и переключатели, что у этапа 1; пока окно живо — не работает; заявки — только админу');
    const rf = bg.slice(bg.indexOf('static String bgRefresh('), bg.indexOf('static void bgRun('));
    assert.ok(rf.includes('"/auth/v1/token?grant_type=refresh_token", "POST"') && rf.includes('s.edit().putString("at", at).putString("rt", nrt).commit();')
      && rf.includes('if (!rt.equals(s.getString("rt", ""))) return null;') && !rf.includes('activityAlive') && !/\.clear\(\)|\.remove\(|logoutLocal/.test(bg),
      'уведомления 10.65: истёкший токен — обновить и записать новую пару в штатное хранилище (rt одноразовый); выход из аккаунта — только штатный');
    assert.ok(np.includes('if (logged && !email.isEmpty()) try { prefs(a).edit().putBoolean(key("admin", email), admin).apply(); } catch (Throwable ignore) {}')
      && np.includes('try { scheduleBg(a.getApplicationContext()); } catch (Throwable ignore) {}   // 10.65'),
      'уведомления 10.65: этап 1 запоминает «админ ли» и ставит фоновую проверку');
    assert.ok(np.includes(': nativeCall(IvaClientPatch.SB_URL + path, "GET", null, true, a instanceof BgCtx ? ((BgCtx) a).token : null);')
      && np.includes('Intent it = new Intent(c, mainClass(a));') && np.includes('si.setClass(c, mainClass(a));')
      && np.includes('if (a instanceof Activity) ((Activity) a).runOnUiThread(new Runnable() {'),
      'уведомления 10.65: проверки работают и без окна (токен входа, MainActivity для нажатия, без UI)');
    assert.ok(np.includes('note.setText(BG_NOTE);') && !np.includes('в следующей версии'), 'уведомления 10.65: в «Настройках» — что приходят и когда закрыто');
    for (const str of ['prokat_session', 'refresh_token', '/auth/v1/token?grant_type=refresh_token', 'MainActivity$Api'])
      assert.ok(has(dex14, str), `classes.dex 10.14: нет «${str}» — фоновая проверка не найдёт штатный вход`);
    assert.ok(ax.includes('SERVICE = "ru.prokatnvsk.app.IvaNotifyPatch$Bg"') && ax.includes('BIND = "android.permission.BIND_JOB_SERVICE"')
      && ax.includes('BOOT = "android.permission.RECEIVE_BOOT_COMPLETED"') && ax.includes('"permission": 0x01010006')
      && ax.includes('(ns, exp_attr, NONE, TYPE_BOOL, 0),'),
      'axml_service.py: служба с BIND_JOB_SERVICE, exported=false, разрешение после перезагрузки');
    assert.ok(code.includes('python3 android/patches/axml_service.py "$W/AndroidManifest.xml"')
      && code.indexOf('axml_service.py "$W') > code.indexOf('axml_version.py "$W'), 'сборка: служба добавляется в манифест после версии');
    assert.ok(/eq\("уведомления 10\.65: /.test(tp), 'IvaPatchTest: проверки 10.65 (уведомления)');
  }
  // 10.65 (просьба владельца 14.10.2026): «Доставка» в форме инструмента — выключатель вместо серой полосы
  {
    const ip = R('android/patches/IvaPatch.java'), tp = R('android/patches/test/IvaPatchTest.java');
    const ds = ip.slice(ip.indexOf('// ───────────── 10.65: «Доставка»'));
    assert.ok(ds.includes('delSwitch(a, "atDelToggle", "atDeliv");') && ds.includes('delSwitch(a, "teDelToggle", "teDeliv");')
      && ds.includes('try { btn.performClick(); } catch (Throwable ignore) {}') && ds.includes('btn.setVisibility(View.GONE);')
      && !/removeView|set\(a, "(at|te)Deliv"|paintDelToggle\(/.test(ds) && ds.includes('sw.setClickable(false);'),
      'доставка 10.65: штатная кнопка только скрыта, переключает штатный код (atDeliv/teDeliv не пишем), выключатель — по штатному значению');
    assert.ok(ip.includes('try { delSwitches(a); } catch (Throwable ignore) {}   // 10.65: «Доставка» — выключатель')
      && ip.includes('try { delSwitches(a); } catch (Throwable ignore) {}   // 10.65: «Доставка» в форме инструмента — выключатель'),
      'доставка 10.65: ставится из onTab и onHub (новых вставок в smali нет)');
    for (const str of ['atDelToggle', 'teDelToggle', 'bg_field'])
      assert.ok(has(arsc14, str), `resources.arsc 10.14: нет «${str}»`);
    assert.ok(has(dex14, 'atDeliv') && has(dex14, 'teDeliv'), 'classes.dex 10.14: нет atDeliv / teDeliv');
    assert.ok(/eq\("доставка 10\.65: /.test(tp), 'IvaPatchTest: проверки 10.65 (доставка)');
  }
  // радиус — только вид: варианты встречаются лишь в объявлении и в шторке, на фильтр/подсчёт не влияют
  assert.equal((js45.match(/RADIUS_TITLES/g) || []).length, 3, 'IvaSearchPatch: радиус не должен участвовать в фильтре (только заготовка в шторке)');
  assert.ok(!/radius|радиус/i.test(js45.slice(js45.indexOf('static boolean extraMatch('), js45.indexOf('static long[] normRange('))), 'IvaSearchPatch: радиус попал в extraMatch');
  // проверка каталога: только чтение, в CI не роняет проверку, сверка с сайтом сама себя проверяет
  const chk = R('.github/workflows/check.yml'), fetchPy = R('tools/catalog-audit/fetch.py');
  assert.match(chk, /- name: Фильтры приложения на настоящем каталоге \(только чтение\)\n\s+continue-on-error: true/, 'check.yml: нет шага проверки фильтров на каталоге');
  assert.ok(!/method=|data=/.test(fetchPy) && /urllib\.request\.Request\(url \+ path, headers=/.test(fetchPy)
    && (fetchPy.match(/urlopen\(/g) || []).length === 1 && fetchPy.includes('urlopen(req, timeout=30)'), 'tools/catalog-audit/fetch.py: только GET-запросы');
  for (const q of ['/rest/v1/tools?select=*&active=eq.true&order=sort.asc.nullslast,name.asc', '/rest/v1/cats?select=*&active=eq.true&order=sort.asc.nullslast', '/rest/v1/subcats?select=*&active=eq.true&order=sort.asc.nullslast'])
    assert.ok(fetchPy.includes(q) && has(dex14, q), `fetch.py и приложение 10.14 должны брать каталог одинаково: ${q}`);
  execFileSync('node', [path.join(ROOT, 'tools/catalog-audit/site-compare.cjs')], { stdio: 'pipe' });
  // схема для 10.47: только GET и только limit=0 — строки из базы не читаются; шаг не роняет CI
  const probePy = R('tools/catalog-audit/probe-columns.py');
  assert.ok(!/method=|data=/.test(probePy) && (probePy.match(/urlopen\(/g) || []).length === 1
    && probePy.includes("path = '/rest/v1/' + table + '?select=' + col + '&limit=0'"), 'probe-columns.py: только GET с limit=0');
  assert.ok(chk.includes('      - name: Схема базы для фильтров разделов (только чтение, строки не читаются)\n        continue-on-error: true'), 'check.yml: шаг схемы должен быть и не ронять проверку');
  // профиль из поиска не сохраняется: ни PUT в /auth/v1/user, ни save_customer_profile
  assert.ok(!/"PUT"|save_customer_profile|upsert_my_customer_profile/.test(js45), 'IvaSearchPatch: выбор города не должен менять профиль');
  // геолокация автоматически не запрашивается, карт и «рядом» нет
  assert.ok(!/requestPermissions|ACCESS_FINE_LOCATION|LocationManager|haversine/i.test(js45), 'IvaSearchPatch: геолокация и расстояния не используются');
  assert.match(jpc, /try \{ return new ArrayList\(IvaSearchPatch\.allTools\(a\)\); \}/, 'IvaClientPatch.hubTools: раздел логотипа должен брать полный каталог, а не выдачу по городу');
  // смена версии — на настоящем двоичном манифесте 10.14
  const name = (code.match(/NEW_NAME: '([\d.]+)'/) || [])[1], vcode = (code.match(/NEW_CODE: '(\d+)'/) || [])[1];
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'iva-apk-'));
  const py = `import zipfile,sys;open(sys.argv[2],'wb').write(zipfile.ZipFile(sys.argv[1]).read('AndroidManifest.xml'))`;
  const man = path.join(tmp, 'm.xml');
  execFileSync('python3', ['-c', py, path.join(ROOT, 'ProkatInstrumenta-10.14.apk'), man]);
  const out = execFileSync('python3', [path.join(ROOT, 'android/patches/axml_version.py'), man, '10.14', name, vcode]).toString();
  assert.match(out, new RegExp(`versionCode: 88 → ${vcode}`)); assert.match(out, new RegExp(`10\\.14 → ${name.replace('.', '\\.')}`));
  // перечитать записанное: повторный запуск печатает текущее значение versionCode
  const again = execFileSync('python3', [path.join(ROOT, 'android/patches/axml_version.py'), man, name, name, vcode]).toString();
  assert.match(again, new RegExp(`versionCode: ${vcode} →`), 'versionCode не записан в манифест');
  const b = fs.readFileSync(man);
  assert.ok(b.includes(Buffer.from(name, 'utf16le')) || b.includes(Buffer.from(name)), 'новая versionName не записана');
  // кандидат в репозитории: тот же номер версии внутри (повторная смена 10.14→… должна отказать — версии уже нет)
  const candApk = path.join(ROOT, `ProkatInstrumenta-${name}.apk`);
  if (fs.existsSync(candApk)) {
    const m2 = path.join(tmp, 'c.xml');
    execFileSync('python3', ['-c', py, candApk, m2]);
    let refused = false;
    try { execFileSync('python3', [path.join(ROOT, 'android/patches/axml_version.py'), m2, name, name, vcode], { stdio: 'pipe' }); } catch (e) { refused = true; }
    assert.ok(!refused, `кандидат ${path.basename(candApk)}: внутри не versionName ${name}`);
    const raw = fs.readFileSync(m2); const i = raw.indexOf(Buffer.from([0x1b, 0x02, 0x01, 0x01])); // id versionCode
    assert.ok(i >= 0, 'кандидат: нет versionCode');
  }
  fs.rmSync(tmp, { recursive: true, force: true });
  console.log(`Сборка приложения: секреты не выводятся, сертификат сверяется с эталоном ${exp.slice(0, 8)}…, версия ${name} (${vcode}) пишется в манифест 10.14, ${insertions.length} видов вызовов IvaPatch и замена текста кнопки доставки на месте, методы и ресурсы 10.14 на месте, фраза «Нет связи с сервером…» в classes.dex 10.14 одна`);
} catch (e) { console.error('✘ ' + e.message); process.exit(1); }
