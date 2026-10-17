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
      && (js45.match(/dp\(a, SHEET_BTN_DP\)/g) || []).length === 2 && (js45.match(/dp\(a, PRICE_FIELD_DP\)/g) || []).length >= 2,   // 10.85: + поля «от/до» характеристик той же толщины
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
    // ряд под поиском — оранжевые «пузыри» (как было), не текст (10.83: окно 400 → 460 — добавлена строка характеристик)
    assert.ok(/addActiveChips\(a, box, f, new Runnable\(\) \{[\s\S]{0,460}?\n            \}\);/.test(js45.slice(js45.indexOf('static void refreshChips('))),
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
    // 10.102: отправка — IvaOrderPointPatch.submit (со списком инструментов; нет SQL — та же штатная submit_order, блок 10.102)
    assert.ok(snd.includes('String[] r = IvaOrderPointPatch.submit(a, body, fls);') && (op.match(/apiCall\(/g) || []).length === 6
      && op.includes('IvaClientPatch.apiCall(a, ordPath(ORD_PATH, raw), "GET", null, true)') && (op.match(/"POST"/g) || []).length === 2
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
      && has(dex14, 'https://eclipsru.github.io/Arenda-Nvk/app-update.json'), 'уведомления 10.56: обновления — тот же app-update.json, что у штатной кнопки (10.94: проверка раз в 5 минут, см. блок 10.94)');
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
    assert.ok(ou.includes('if (old == null) releaseCheck(a, true);') && ou.includes('box.removeView(old);')   // 10.78: проверка релиза, как у кнопки в кабинете
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
      && vb.includes('new VoiceBtn(a, accent);') && vb.includes('new VoiceWave(a, pk, accent, VOICE_REST_OTHER, den)')   // 10.96: рамкой у всех
      && vb.includes('voiceWavePx(known, sw, den)') && vb.includes('if (pk == null) voiceAskWave(a, m.audio);') && vb.includes('voiceSeek(a, key, m.audio, ui, e.getX()')
      && !/#[0-9A-Fa-f]{6}|0x[0-9A-Fa-f]{8}/.test(vb.replace(/0xFFFFFFFF/g, '')),
      'голосовые 10.60/10.96: как в ВК — круглая кнопка слева (рамкой), волна, длительность; цвета приложения, без новых цветов');
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
    assert.ok(jc.includes('try { if ("profile".equals(tab)) profileContacts(a); } catch (Throwable ignore) {}') && pc.includes('boolean show = PROFILE_CONTACTS_SHOW;')
      && pc.includes('isSingleRow(a, (ViewGroup) row)') && !/removeView|removeAllViews|setText|setOnClick/.test(pc),
      'кабинет 10.62/10.78: строки только скрываются (с 10.78 — и у админа), ничего не удаляется; строка адреса — только если это одна строка');
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
      && cs.includes('if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && v.isEnabled()) {\n                        condPut(terms, k);')   // 10.79: + вид
      && cs.includes('IvaKindPatch.onSaveTouch(a, k); } catch (Throwable ignore) {}   // 10.79: вид — после штатного успеха\n                    }\n                    return false;')
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
  // 10.87 (просьба владельца 15.10.2026: форма «Добавить» — понятнее и удобнее, «давай сделаем как ты считаешь нужным»;
  // справочник «очень слабый — максимально доработать»): разделы формы, «*» у обязательного, ошибки под полями, черновик,
  // «Город выдачи», «по образцу своего инструмента»; справочник — части 4–5. Новых smali-вставок нет: только arrange, onTab
  // и касание «Опубликовать» (condRow), которые уже были.
  {
    const fp = R('android/patches/IvaFormPatch.java'), kp = R('android/patches/IvaKindPatch.java'), ip = R('android/patches/IvaPatch.java');
    const tp = R('android/patches/test/IvaPatchTest.java'), wf = R('.github/workflows/build-apk.yml'), ck = R('.github/workflows/check.yml');
    assert.ok(kp.includes('try { IvaFormPatch.setup(a, form); } catch (Throwable ignore) {}') && kp.includes('try { IvaFormPatch.onTab(a, tab); } catch (Throwable ignore) {}')
      && ip.includes('try { stop = IvaFormPatch.onSaveTouch(a, e.getActionMasked()); } catch (Throwable ignore) {}') && ip.includes('if (k == 0 && v.isEnabled()) {'),
      'форма 10.87: вызовы IvaFormPatch — из arrange, onTab и касания «Опубликовать» (только форма «Добавить»)');
    assert.ok(fp.includes('static final String ERR_NAME = "Напишите название инструмента", ERR_CAT = "Выберите категорию", ERR_PRICE = "Укажите цену за сутки";')
      && fp.includes('static final int MAX_PICS = 5, SAMPLE_MAX = 15;'),
      'форма 10.87: тексты ошибок, не больше 5 фото');
    // 10.88 (просьба владельца 15.10): «Города выдачи» нет — пункт выдачи с адресом и временем уже в карточке
    assert.ok(!/pickup_city|cityExtra|cityValue|CITY_LABEL|Город выдачи/.test(fp + kp) && kp.includes('if (kind.isEmpty()) return;                              // новое объявление без вида — ничего не нужно'),
      'форма 10.88: города выдачи в форме нет, в запись после публикации он не пишется');
    // 10.88: кнопки «Опубликовать» / «Сохранить» в 10.14 — id разметки (не поля MainActivity): находим по id
    const vw = ip.slice(ip.indexOf('static View view(Activity a, String name) {'), ip.indexOf('static View view(Activity a, String name) {') + 420);
    assert.ok(vw.includes('Object o = get(a, name);') && vw.includes('a.getResources().getIdentifier(name, "id", a.getPackageName());') && vw.includes('a.findViewById(id)')
      && ip.includes('final View save = view(a, saveField);') && kp.includes('v = IvaPatch.view(a, key);') && fp.includes('View save = IvaPatch.view(a, "atSave");')
      && fp.includes('keepSaveLast(a, form);') && fp.includes('if (c.getVisibility() == View.GONE || c instanceof android.widget.Space) { i++; continue; }'),
      'форма 10.88: «Опубликовать» находится по id разметки и стоит последней (штатный отступ под ней остаётся)');
    assert.ok(has(arsc14, 'atSave') && has(arsc14, 'teSave'), 'APK 10.14: нет id atSave / teSave — кнопку публикации не найти');
    // 10.89 (просьба владельца 15.10): частые условия — одна строка с выпадающим списком; выбранное — в поле и из списка
    assert.ok(/SEC \+ "4", (IvaToolPointPatch\.TAG \+ ":0", )?IvaKindPatch\.LBL \+ "atTerms", "atTerms", TERMS, ERR \+ "atTerms", IvaPatch\.DEL_ROW_TAG/.test(fp) && fp.includes('static String termToggle(String terms, String p) {')
      && fp.includes('static List<String> termLeft(String src, String terms) {') && fp.includes('if ("atTerms".equals(field) && tv.getParent() instanceof LinearLayout) paintTerms(')
      && !fp.includes('ownTerms') && !fp.includes('"Наличные или перевод"') && fp.includes('TERMS_PICK = "Выбрать из частых условий"')
      && (fp.match(/static final String\[\] TERMS_BASE = \{[^}]*\}/) || [''])[0].split('",').length >= 10,
      'форма 10.89: частые условия — выпадающим списком под «Условиями аренды», без своих строк и «Наличные или перевод»');
    assert.ok(!/паспорт/i.test((fp.match(/static final String\[\] TERMS_\w+ = \{[^}]*\}/g) || []).join(' ')) && /eq\("форма 10\.90: в частых условиях нет ни одной подсказки про паспорт"/.test(tp),
      'форма 10.90: в частых условиях нет подсказок про паспорт (просьба владельца)');
    // 10.91 (просьба владельца 15.10): стрелка списка вправо / вниз, повторное нажатие и «Далее» закрывают, без «Сгенерировать описание»
    const mp91 = R('android/patches/IvaModelPatch.java');
    const fn91 = (name) => { const i = fp.indexOf(`static void ${name}(`); assert.ok(i >= 0, `нет ${name}`); return fp.slice(i, fp.indexOf('\n    }\n', i)); };
    assert.ok(fp.includes('static float arrowAngle(boolean open) { return open ? ARROW_OPEN : ARROW_CLOSED; }') && fp.includes('static final float ARROW_OPEN = 90f, ARROW_CLOSED = 0f;')
      && fp.includes('try { arrows(a, form); } catch (Throwable ignore) {}') && fp.includes('arrow(f, termsOpen);') && !fp.includes('chev.setLevel(1)')
      && fp.includes('if (c instanceof TextView && cs instanceof View) tapToggle((TextView) c, (View) cs);') && fp.includes('vto.addOnGlobalFocusChangeListener(')
      && !/setOnClickListener|setOnEditorActionListener/.test(fn91('arrows') + fn91('tapToggle'))
      && mp91.includes('static final boolean SHOW_GEN = false;') && mp91.includes('if (!SHOW_GEN) gen.setVisibility(View.GONE);')
      && /eq\("форма 10\.91: «Далее»/.test(tp) && /eq\("форма 10\.91: кнопки «Сгенерировать описание» нет"/.test(tp),
      'форма 10.91: стрелки списков, закрытие повторным нажатием и «Далее», кнопки «Сгенерировать описание» нет');
    assert.ok(fp.includes('static final boolean SHOW_SAMPLE = false;') && fp.includes('hideTop(form);') && fp.includes('TITLE_NATIVE = "Добавить инструмент", SUBTITLE_NATIVE = "Публикуется сразу"'),
      'форма 10.89: сверху нет заголовка, подзаголовка и «по образцу» (черновик остаётся)');
    // 10.89: фото — 5 окошек, большой просмотр, камера / галерея; результат — в свой Fragment, onActivityResult не трогаем
    const php = R('android/patches/IvaPhotoPatch.java');
    assert.ok(php.includes('public final class IvaPhotoPatch {') && php.includes('public static final class Picker extends android.app.Fragment {')
      && php.includes('public Picker() {}') && php.includes('new Intent("android.provider.action.PICK_IMAGES")') && php.includes('new Intent(Intent.ACTION_PICK)')
      && php.includes('android.provider.MediaStore.ACTION_IMAGE_CAPTURE') && php.includes('android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)')
      && php.includes('if (old != null) android.os.StrictMode.setVmPolicy(old);') && php.includes('static final int SLOTS = 5, MAX_SIDE = 1600, JPEG_Q = 85')
      && !/onActivityResult\(Activity|uses-permission|CAMERA"/.test(php) && fp.includes('try { IvaPhotoPatch.setup(a, form); } catch (Throwable ignore) {}'),
      'фото 10.89: 5 окошек, «Сделать фото» / «Из галереи» без новых разрешений, результат — в IvaPhotoPatch.Picker');
    for (const s2 of [wf, ck]) assert.ok(s2.includes('android/patches/IvaPhotoPatch.java'), 'IvaPhotoPatch.java — в списке javac сборки и проверки');
    // 10.92: «Цензура» — одна проверка в приложении и в базе: выражения и таблицы замен IvaCensorPatch = 20261021_censor.sql
    const cz = R('android/patches/IvaCensorPatch.java'), czs = R('supabase/migrations/20261021_censor.sql');
    const czc = (n) => {
      const m = cz.match(new RegExp(`(?:static final String |, )${n} = ((?:"(?:[^"\\\\]|\\\\.)*"(?:\\s*\\+\\s*)?)+)`));
      assert.ok(m, `IvaCensorPatch: нет ${n}`);
      return (m[1].match(/"(?:[^"\\]|\\.)*"/g) || []).map((x) => JSON.parse(x)).join('');
    };
    const czq = (v) => `'${v.replace(/'/g, "''")}'`;
    for (const n of ['LAT_EXCL', 'EN_MAT', 'EXCL', 'MAT', 'EN_RUDE', 'RUDE', 'MASK', 'MASK_FIRST', 'C_SPLIT', 'C_SEP', 'C_INVIS']) {
      assert.ok(czs.includes(czq(czc(n))), `цензура 10.92: ${n} в приложении и в базе (20261021_censor.sql) должны совпадать`);
    }
    assert.ok(czs.includes(`translate(t, ${czq(czc('V_FROM'))}, ${czq(czc('V_TO'))})`) && czs.includes(`translate(p, ${czq(czc('P_FROM'))}, ${czq(czc('P_TO'))})`)
      && czs.includes(`'${czc('C_UP')}', '${czc('C_LOW')}'`) && czs.includes(`left(p, ${(cz.match(/MAX_LEN = (\d+)/) || [])[1]})`),
      'цензура 10.92: таблицы замен (похожие буквы, транслит, регистр) и предел длины — как в базе');
    const czPairs = JSON.parse('[' + (cz.match(/P_PAIRS = \{([^}]*)\}/) || ['', ''])[1] + ']');
    const czRep = (czs.match(/replace\(p, '[^']+', '[^']+'\)/g) || []).map((x) => x.match(/'([^']+)', '([^']+)'/).slice(1)).flat();
    assert.deepStrictEqual(czPairs, czRep, 'цензура 10.92: сочетания транслита (sh → ш …) и их порядок — как в базе');
    assert.ok(cz.includes('static final String OWNER = "eclips.ru@mail.ru";') && R('android/patches/IvaNotifyPatch.java').includes('try { IvaCensorPatch.onTab(a, tab); } catch (Throwable ignore) {}')
      && R('android/patches/IvaReviewPatch.java').includes('try { if (IvaCensorPatch.check(s).level == 2) return true; } catch (Throwable ignore) {}')
      && fp.includes('{"atDescr", IvaCensorPatch.fieldError(text(a, "atDescr"))},') && fp.includes('{"atTerms", IvaCensorPatch.fieldError(text(a, "atTerms"))},')
      && R('android/patches/IvaChatPatch.java').includes('try { mat = IvaCensorPatch.fieldError(text); } catch (Throwable ignore) {}')
      && !/customer_profiles|passport|phone|surname/.test(cz) && !/select=\*/.test(cz),
      'цензура 10.92: «Цензура» — только у создателя; отзывы, форма и чат — та же проверка');
    for (const s2 of [wf, ck]) assert.ok(s2.includes('android/patches/IvaCensorPatch.java'), 'IvaCensorPatch.java — в списке javac сборки и проверки');
    // 10.93: кабинет создателя — «Управление» (7 страниц), «Мои отзывы»; карточка «Ваш доход с админов» спрятана
    const chp = R('android/patches/IvaChiefPatch.java');
    assert.ok(R('android/patches/IvaNotifyPatch.java').includes('try { IvaChiefPatch.onTab(a, tab); } catch (Throwable ignore) {}')
      && chp.includes('static final String INCOME_TITLE = "ВАШ ДОХОД С АДМИНОВ";') && chp.includes('box.setOnHierarchyChangeListener(')
      && chp.includes('IvaCensorPatch.rowFor(IvaNotifyPatch.loggedIn(a)') && (chp.match(/^        \{"[^"]+", "[^"]+", "ic_\w+"\},$/gm) || []).length === 7
      && !/customer_profiles|passport|phone|surname|select=\*|"POST"|"PATCH"|"DELETE"/.test(chp) && !/new Thread[\s\S]{0,40}removeAllViews/.test(chp)
      && /eq\("кабинет 10\.93: пункты «Управления» — по порядку владельца"/.test(tp),
      'кабинет 10.93: «Управление» и «Мои отзывы» — только у создателя, только чтение, без контактов; «Ваш доход с админов» спрятан');
    for (const s2 of [wf, ck]) assert.ok(s2.includes('android/patches/IvaChiefPatch.java'), 'IvaChiefPatch.java — в списке javac сборки и проверки');
    // 10.94: жёлтая «Обновить до версии N» у eclips и eclipsik — та же загрузка, выпуск строкой app_releases; уведомления
    const tsp = R('android/patches/IvaTestPatch.java'), np94 = R('android/patches/IvaNotifyPatch.java');
    const fn94 = (src, name) => { const mm = new RegExp(`static (?:final )?[\\w.<>, \\[\\]]+ ${name}\\(`).exec(src); assert.ok(mm, `нет ${name}`); return src.slice(mm.index, src.indexOf('\n    }\n', mm.index)); };
    assert.ok(np94.includes('try { IvaTestPatch.onTab(a, tab); } catch (Throwable ignore) {}') && np94.includes('else if ("test".equals(open)) IvaTestPatch.openFromNote(a);')
      && tsp.includes('static final String[] TESTERS = {"eclips.ru@mail.ru", "eclipsik.ru@mail.ru"};') && tsp.includes('static final String TEST_URL = IvaNotifyPatch.APK_BASE + "app-test.json";')
      && tsp.includes('boolean want = showTest(me(a), code, IvaNotifyPatch.relCode);') && tsp.includes('IvaNotifyPatch.updateTap(a, code, name, IvaNotifyPatch.APK_BASE + file);')
      && tsp.includes('if (!onSite(IvaNotifyPatch.APK_BASE + file)) err =') && tsp.includes('IvaClientPatch.apiCall(a, RELEASE_POST, "POST", body, true);')
      && tsp.includes('static final String RELEASE_POST = "/rest/v1/app_releases";') && tsp.includes('YELLOW = 0xFFFFEA00')
      && np94.includes('if (testDriving()) return;') && fn94(np94, 'applyRelease').indexOf('if (testDriving()) return;') < fn94(np94, 'applyRelease').indexOf('boolean want = updateWanted(')
      && !/customer_profiles|passport|phone|surname|select=\*|"PATCH"|"DELETE"/.test(tsp) && (tsp.match(/"POST"/g) || []).length === 1,
      'пробная 10.94: жёлтая кнопка — только у eclips и eclipsik, файл сайта app-test.json, загрузка оранжевой кнопки, выпуск — одна вставка в app_releases');
    assert.ok(np94.includes('kids.add(switchRow(a, card, IvaTestPatch.NOTE_TITLE, IvaTestPatch.NOTE_SUB, IvaTestPatch.K_TEST, cText, cGray, cOrange));')
      && np94.includes('if (loggedIn(a) && IvaTestPatch.isTester(String.valueOf(get(a, "userEmail"))))')
      && np94.includes('if (logged && !email.isEmpty()) try { IvaTestPatch.check(a, email, admin); } catch (Throwable ignore) {}')
      && np94.includes('if (logged) try { IvaTestPatch.check(c, email, admin); } catch (Throwable ignore) {}')
      && fn94(tsp, 'check').includes('if (!isTester(email) || !IvaNotifyPatch.on(c, K_TEST, admin) || IvaNotifyPatch.quiet(c, admin)) return;')
      && np94.includes('String[] ids = {CH_MSG, CH_ORD, CH_UPD, CH_RENT, IvaTestPatch.CH_TEST};')
      && np94.includes('static final long UPDATE_NOTE_EVERY_MS = 5L * 60 * 1000;') && fn94(np94, 'updateDue').includes('UPDATE_NOTE_EVERY_MS'),
      'уведомления 10.94: «Уведомления пробной версии» — только у eclips и eclipsik, выключаются; о выпущенной — у всех, проверка раз в 5 минут');
    const sq94 = R('supabase/migrations/20261022_app_releases_tester.sql');
    assert.ok(sq94.includes("CREATE POLICY app_releases_insert_tester ON public.app_releases") && sq94.includes("confirmed_by = 'eclipsik.ru@mail.ru'")
      && !/DROP (TABLE|POLICY app_releases_insert_creator)|TRUNCATE|GRANT (UPDATE|DELETE)/i.test(sq94.replace(/^--.*$/gm, ''))
      && R('tools/app-releases-stand.py').includes('20261022_app_releases_tester.sql'),
      'SQL 10.94: только добавляет право выпуска eclipsik (от своего имени); проверяется на стенде PostgreSQL');
    for (const s2 of [wf, ck]) assert.ok(s2.includes('android/patches/IvaTestPatch.java'), 'IvaTestPatch.java — в списке javac сборки и проверки');
    // 10.95: «Пункт выдачи» — строка в настройках у админов, отдельная страница, запись в iva_pickup_points; «Срок аренды» — по первому пункту
    const pk = R('android/patches/IvaPickupPatch.java'), rp95 = R('android/patches/IvaRentPatch.java'), np95 = R('android/patches/IvaNotifyPatch.java');
    // 10.98: строки «Пункты выдачи» и «Срок аренды» переехали на страницу «Настройка проката» (тоже только у админов)
    const sh95 = R('android/patches/IvaShopPatch.java');
    assert.ok(np95.includes('if (admin) try { IvaShopPatch.settingsRow(a, page, cText, cGray, cOrange); } catch (Throwable ignore) {}')
      && sh95.includes('try { IvaPickupPatch.settingsRow(a, list, cText, cGray, cOrange); } catch (Throwable ignore) {}')
      && sh95.indexOf('IvaPickupPatch.settingsRow(') < sh95.indexOf('IvaRentPatch.settingsSection(a, list,')
      && pk.includes('u.page = IvaChiefPatch.page(a, ROW_TITLE, PAGE_SUB, false);') && pk.includes('b.setBackground(IvaPatch.accentBg(a, cOrange, 12));')
      && pk.includes('static final String TABLE = "/rest/v1/iva_pickup_points";') && pk.includes('static final String PHOTON = "https://photon.komoot.io/api/";')
      && R('assets/geo.js').includes("const endpoint='https://photon.komoot.io/api/';")
      && pk.includes('try { ne = IvaCensorPatch.fieldError(p.name); } catch (Throwable ignore) {}')
      && !/customer_profiles|passport|phone|surname|select=\*/.test(pk) && !/owner_email\\"/.test(pk),
      'пункт 10.95: «Пункт выдачи» — только у админов, над «Сроком аренды»; своя страница; Photon как на сайте; без личных данных клиентов; владельца ставит база');
    assert.ok(rp95.includes('int[] pc = IvaPickupPatch.mainClose(a);') && rp95.includes('if (pc != null) st.close = pc.clone();')
      && rp95.includes('if (IvaPickupPatch.mainClose(a) != null) { linkedToast(a); return; }') && rp95.includes('relinkRef = new java.lang.ref.WeakReference<Runnable>(ref[0]);'),
      'пункт 10.95: «Срок аренды → До закрытия пункта» — время из первого пункта (только показ)');
    const sq95 = R('supabase/migrations/20261023_pickup_points.sql');
    assert.ok(sq95.includes('CREATE TABLE IF NOT EXISTS public.iva_pickup_points') && sq95.includes('ALTER TABLE public.iva_pickup_points ENABLE ROW LEVEL SECURITY;')
      && sq95.includes('REVOKE ALL ON TABLE public.iva_pickup_points FROM public, anon, authenticated;')
      && sq95.includes("raise exception 'Не больше 20 пунктов выдачи';") && sq95.includes('perform public.iva_pickup_sync_rent(')
      && sq95.includes('select p.close_min into pc from public.iva_pickup_points p where p.owner_email = em order by p.created_at, p.id limit 1;')
      && !/DROP TABLE|TRUNCATE|DELETE FROM/i.test(sq95.replace(/^--.*$/gm, '')) && R('tools/check.sh').includes('tools/pickup-stand.py'),
      'SQL 10.95: пункты — только свои у действующего админа, до 20, «Срок аренды» по первому; без удаления данных; стенд PostgreSQL в check.sh');
    for (const s2 of [wf, ck]) assert.ok(s2.includes('android/patches/IvaPickupPatch.java'), 'IvaPickupPatch.java — в списке javac сборки и проверки');
    // 10.96 (решение владельца 16.10.2026): все кнопки и выбранные элементы — «рамкой», как «Добавить» в «Пункт выдачи»; и впредь
    {
      const ol = R('android/patches/axml_outline.py'), ip96 = R('android/patches/IvaPatch.java');
      const fn96 = (src, name) => { const mm = new RegExp(`static (?:final )?[\\w.<>, \\[\\]]+ ${name}\\(`).exec(src); assert.ok(mm, `нет ${name}`); return src.slice(mm.index, src.indexOf('\n    }\n', mm.index)); };
      assert.ok(wf.indexOf('python3 android/patches/axml_install.py "$W/AndroidManifest.xml"') < wf.indexOf('python3 android/patches/axml_outline.py "$W"')
        && wf.indexOf('python3 android/patches/axml_outline.py "$W"') < wf.indexOf('java -jar "$RUNNER_TEMP/apktool.jar" b ')
        && R('tools/check.sh').includes('python3 android/patches/axml_outline.py /tmp/iva-outline'),
        'рамкой 10.96: фоны переделываются в ресурсах до пересборки APK; проверка — в check.sh на ресурсах 10.14');
      for (const n of ['bg_btn_orange_shape', 'bg_btn_orange_pressed', 'bg_chip_on', 'bg_bubble_me', 'bg_nav_circle_on', 'bg_badge', 'bg_avatar'])
        assert.ok(ol.includes(`'${n}': ('bg_`) && has(arsc14, n), `рамкой 10.96: ${n} — переделывается и есть в APK 10.14`);
      assert.ok(ol.includes('CARD = 0xFF1A1D24') && ol.includes('ORANGE = 0xFFFF8F1F') && ol.includes('STROKE_1_5DP = 0x0000C011') && ol.includes('LAYOUT_TEXTS = 13')
        && ip96.includes('static final int OUTLINE_FILL = 0xFF1A1D24, OUTLINE_ACCENT = 0xFFFF8F1F;') && ip96.includes('static final float OUTLINE_DP = 1.5f;'),
        'рамкой 10.96: в ресурсах и в коде — одни и те же цвета и рамка 1,5dp');
      const ot = ap.slice(ap.indexOf('OUTLINE_TEXT = ('), ap.indexOf('\n)\n', ap.indexOf('OUTLINE_TEXT = (')));
      assert.ok((ot.match(/, (\d)\),/g) || []).map(x => +x.match(/\d/)[0]).reduce((x, y) => x + y, 0) === 13 && !/invoke/.test(ot)
        && ap.includes("lines[i] = lines[i].replace('0x7f01000f', '0x7f01000a')") && !/feedCard|setMode|renderChat|setCircle/.test(ot),
        'рамкой 10.96: в штатном коде только 13 констант цвета (белый → оранжевый), без вызовов; сердечко избранного, вкладки входа и меню не трогаем');
      // впредь: без оранжевой/жёлтой заливки и без белого текста на кнопках — только IvaPatch.accentButton / accentBg / accentProgressBg
      const files96 = ['IvaPatch', 'IvaClientPatch', 'IvaSearchPatch', 'IvaChatPatch', 'IvaOrderPatch', 'IvaNotifyPatch', 'IvaReviewPatch', 'IvaPushPatch', 'IvaRentPatch',
        'IvaKindPatch', 'IvaModelPatch', 'IvaFormPatch', 'IvaPhotoPatch', 'IvaCensorPatch', 'IvaChiefPatch', 'IvaTestPatch', 'IvaPickupPatch'];
      for (const f of files96) {
        const src = R(`android/patches/${f}.java`).split('\n').filter(l => !/^\s*paint\.setColor\(/.test(l) && !l.includes('more.setTextColor(0xffffffff);')).join('\n');
        assert.ok(!/setTextColor\((Color\.WHITE|0x[fF]{8})\)/.test(src) && !/"white"/.test(src.replace('"white".equals(name)', ''))
          && !/\.setColor\((cOrange|orange|UPD_ORANGE|YELLOW|accent|color\(a, "orange"[^)]*\)|IvaPatch\.color\(a, "orange"[^)]*\))\)/.test(src)
          && !/(pill|rounded|hubPill|choiceBg)\(\s*(a,\s*)?(\w+ \? )?(cOrange|orange|color\(a, "orange")/.test(src),
          `рамкой 10.96: в ${f}.java нет оранжевой/жёлтой заливки и белого текста на кнопках (только IvaPatch.accentButton / accentBg)`);
      }
      assert.ok(fn96(ip96, 'accentButton').includes('b.setTextColor(o);') && fn96(ip96, 'accentBg').includes('android.R.attr.state_pressed')
        && R('android/patches/IvaPickupPatch.java').includes('b.setBackground(IvaPatch.accentBg(a, cOrange, 12));')
        && R('docs/README-DEV.md').includes('Кнопки «рамкой» (решение владельца 16.10.2026, приложение с 10.96)'),
        'рамкой 10.96: образец — «Добавить» в «Пункт выдачи»; общее правило — в README-DEV');
    }
    assert.ok(wf.includes('            tests/censor-cases.txt\n') && /eq\("цензура 10\.92: общие случаи tests\/censor-cases\.txt/.test(tp)
      && R('tools/check.sh').includes('tools/censor-stand.py'), 'цензура 10.92: общие случаи — в IvaPatchTest (сборка) и на стенде PostgreSQL (check.sh)');
    // 10.89: фильтр — 3 + «Все ▾», выбрали — только выбранная; карточка — модель и состояние; бренд и модель — текстом
    const sp89 = R('android/patches/IvaSearchPatch.java'), mp89 = R('android/patches/IvaModelPatch.java');
    assert.ok(sp89.includes('static final int FACET_TOP = 3;') && (sp89.match(/facetShown\(\w+Vis, \w+Counts, draft\.\w+, \w+Open, FACET_TOP\)/g) || []).length === 3
      && (sp89.match(/if \(facetMore\(\w+Vis, draft\.\w+, FACET_TOP\)\)/g) || []).length === 3 && !sp89.includes('addOption(a, catBox, "Все \\u00b7 "'),
      'фильтр 10.89: категории, подкатегории и вид — по 3 и «Все ▾»');
    assert.ok(mp89.includes('rows.addAll(detHead(kt, modelName(a, jstr(j, "model_id")), jstr(j, "terms")));') && mp89.includes('try { paintTerms(a, (JSONObject) it); } catch (Throwable ignore) {}')
      && mp89.includes('String nt = brandPut(String.valueOf(f.getText()), prev, brandSel, brands(idx, kindNow()));') && mp89.includes('try { syncName(a, f); } catch (Throwable ignore) {}'),
      'карточка и «Модель» 10.89: модель и состояние в «Характеристиках», бренд — в поле, модель — в названии');
    assert.ok((tp.match(/eq\("(форма|фото|модель|карточка|фильтр) 10\.89: /g) || []).length >= 13, 'IvaPatchTest: проверки 10.89');
    assert.ok((tp.match(/eq\("форма 10\.88: /g) || []).length >= 4, 'IvaPatchTest: проверки 10.88');
    assert.ok(fp.includes('static SharedPreferences prefs(Activity a) { return a.getSharedPreferences("iva_form", 0); }')
      && fp.includes('new File(new File(a.getFilesDir(), "iva_draft"), "p" + i + ".jpg")') && !/onActivityResult|MediaStore|ACTION_IMAGE_CAPTURE/.test(fp),
      'форма 10.87: черновик — только на телефоне (prefs и файлы приложения); камеры и onActivityResult нет');
    assert.ok(!/customer_profiles|passport|phone|surname/.test(fp) && !/reviews\?select=\*/.test(fp) && !/\.runOnUiThread\([^)]*apiCall/.test(fp),
      'форма 10.87: личных данных не читаем, сеть — не в главном потоке');
    for (const s of [wf, ck]) assert.ok(s.includes('android/patches/IvaFormPatch.java'), 'IvaFormPatch.java — в списке javac сборки и проверки');
    assert.ok((tp.match(/eq\("форма 10\.87: /g) || []).length >= 10, 'IvaPatchTest: проверки 10.87');
    // справочник: части 4–5 проходят генератор (повтор — ошибка), SQL совпадает с данными
    for (const f of ['supabase/models/4-current-power.txt', 'supabase/models/5-current-equipment.txt']) assert.ok(fs.existsSync(path.join(ROOT, f)), 'нет ' + f);
    const nModels = +((R('supabase/migrations/20261017_models.sql').match(/\((\d+) моделей\)/) || [])[1] || 0);
    assert.ok(nModels >= 4500, 'справочник 10.87: моделей не меньше 4500 (' + nModels + ')');
  }
  // 10.86 (просьба владельца 15.10.2026, решения main4_plus и model_only): форма «Добавить» в приложении — подписи над
  // пунктами, «Модель» под названием, «Категория / Подкатегория / Вид» над «Сгенерировать описание»; подсказки названия
  // понимают «болгарка», «ушм», раскладку, латиницу и опечатки; «Питание» — только у выбранной модели, напряжение — при
  // «Аккумуляторе», скрытое не сохраняется (приложение и сайт). Правка инструмента (te*) не меняется.
  {
    const mp = R('android/patches/IvaModelPatch.java'), kp = R('android/patches/IvaKindPatch.java'), ip = R('android/patches/IvaPatch.java');
    const tp = R('android/patches/test/IvaPatchTest.java'), wf = R('.github/workflows/build-apk.yml'), chk = R('tools/check.sh');
    const md = R('assets/models.js'), nw = R('new.html'), cab = R('cabinet.html'), jt = R('tests/model-search.cjs');
    const ff = R('tests/form-fields-cases.txt'), fl = R('supabase/models/fields.txt'), sql = R('supabase/migrations/20261019_power_src.sql');
    const fnx = (src, name) => { const mm = new RegExp(`static (?:final )?[\\w.<>, \\[\\]]+ ${name}\\(`).exec(src); assert.ok(mm, `нет ${name}`); return src.slice(mm.index, src.indexOf('\n    }\n', mm.index)); };
    // 1. раскладка: только форма «Добавить» (atName), подписи мелким серым, группа перед блоком «Сгенерировать описание»
    const ar = fnx(kp, 'arrange');
    assert.ok(ar.includes('IvaClientPatch.get(a, FORM[0][5])') && !/\bte[A-Z]/.test(ar) && ar.includes('try { IvaFormPatch.setup(a, form); } catch (Throwable ignore) {}')   // 10.87: порядок — IvaFormPatch
      && ar.includes('t.setTextSize(13f);') && ar.includes('IvaPatch.color(a, "gray", 0xff9aa1b0)') && kp.includes('static final int LABEL_TOP_DP = 12, FIELD_TOP_DP = 6;'),
      'форма 10.86: подписи и перестановка — только в форме «Добавить», без лишних движений');
    assert.ok(fnx(kp, 'refreshRows').includes('try { arrange(a); } catch (Throwable ignore) {}') && fnx(ip, 'condRows').includes('try { IvaKindPatch.arrange(a); } catch (Throwable ignore) {}')
      && fnx(mp, 'refresh').includes('try { IvaKindPatch.arrange(a); } catch (Throwable ignore) {}'),
      'форма 10.86: раскладка обновляется после вида, «Состояния» и блока модели');
    // 2. «Модель» — под названием (и его подсказками), видна и без вида; подпись мелким
    const rf = fnx(mp, 'refresh');
    assert.ok(rf.includes('View after = sug != null && sug.getParent() == form ? sug : nv;') && rf.includes('form.addView(block, form.indexOfChild(after) + 1')
      && fnx(mp, 'buildBlock').includes('box.addView(small(a, MODEL_LABEL), lpTop(a, 12));') && mp.includes('block.setVisibility(View.VISIBLE);')
      && fnx(mp, 'fillModelDrop').includes(': search(q, idx, kind, kind.isEmpty() ? "" : brandSel, MODEL_SUGGEST);'),
      'форма 10.86: «Модель» под названием, ищет и без вида');
    // 3. «Питание» и напряжение: одна функция в приложении и на сайте, на одних случаях; сохраняются только показанные поля
    assert.ok(fnx(mp, 'paintSpecs').includes('List<Field> fl = visFields(kind);') && fnx(mp, 'visFields').includes('return formFields(fieldsFor(fields, kind), m == null ? null : m.specs, specVals);')
      && mp.includes('String ex = extraBody(id, specsClean(specVals, visFields(kind)));') && mp.includes('proposalBody(kind, parts, title, specsClean(specVals, visFields(kind)));')
      && fnx(mp, 'paintChoice').includes('if (SRC.equals(f.key)) {') && !/specsClean\(specVals, fieldsFor\(/.test(mp),
      'форма 10.86: приложение — питание у модели, напряжение при аккумуляторе, скрытое не сохраняется');
    assert.ok(md.includes('ivaFormFields: ivaFormFields') && nw.includes('return ivaFormFields(ivaFieldsFor(MD, F.kind), m && m.kind === F.kind ? m.specs : null, F.specs);')
      && nw.includes('const sp = ivaSpecsClean(F.specs, specFields());') && nw.includes('if (specSig() !== was) paintCat();') && !/ivaSpecsClean\([^;]*ivaFieldsFor\(/.test(nw)
      && cab.includes('return ivaFormFields(ivaFieldsFor(MD,k),m&&m.kind===k?m.specs:null,mdl.specs)') && !/ivaSpecsClean\([^;]*ivaFieldsFor\(/.test(cab)
      && cab.includes('if(cfSig()!==was)paintModel()'),
      'форма 10.86: сайт (new.html, кабинет) — те же поля, сохраняются только показанные');
    assert.ok(nw.includes('clearTimeout(autoTimer);   /* 10.86') && cab.includes('clearTimeout(autoT);   /* 10.86'),
      'форма 10.86: выбор модели отменяет отложенный автоподбор (раньше сбрасывал вид и модель)');
    for (const f of ['models.js', 'model-form.js']) assert.ok(nw.includes(`assets/${f}?v=20261020-power`) && cab.includes(`assets/${f}?v=20261020-power`), 'новая версия ' + f);
    assert.ok(R('catalog.html').includes('assets/models.js?v=20261020-power') && R('tool.html').includes('assets/models.js?v=20261020-power'), 'models.js — новая версия везде');
    // 4. варианты «Питания»: 4 основных, редкие — только где бывают; SQL для применённой базы
    const srcOpts = (fl.match(/\bsrc\(([^)]*)\)/g) || []).map(x => x.slice(4, -1).split(/;\s*/));
    const RARE = ['Сеть 380 В', 'Сжатый воздух', 'Ручной'];
    assert.ok(srcOpts.length > 50 && srcOpts.every(o => o.slice(0, 4).join(';') === 'Сеть 220 В;Аккумулятор;Бензин;Дизель' && o.slice(4).every(x => RARE.includes(x))),
      'fields.txt: «Питание» — 4 основных варианта, редкие только 380 В / сжатый воздух / ручной');
    assert.ok(sql.includes("array['Сеть 220 В', 'Аккумулятор', 'Бензин', 'Дизель']") && sql.includes("where specs ->> 'src' in ('Газ', 'Гидравлический', '12 В');")
      && / power-src-browser[ ;]/.test(chk) && chk.includes('tools/power-src-stand.py'), 'SQL 20261019 и стенд «Питания» в check.sh');
    // 5. поиск: раскладка, транслит, опечатки — одинаково; случаи общие; сборка видит файлы
    assert.ok(md.includes('function ivaTranslit(') && md.includes('out.words = words.sort(byCode);') && fnx(mp, 'known').includes('for (String t : ix.words) if (t.startsWith(w)) return true;')
      && /^болгарка\|/m.test(R('tests/model-search-cases.txt')) && /^ушм\|/m.test(R('tests/model-search-cases.txt')),
      'поиск 10.86: «болгарка», «ушм», раскладка и опечатки — в приложении и на сайте');
    assert.ok(tp.includes('java.nio.file.Paths.get("tests/form-fields-cases.txt")') && jt.includes('form-fields-cases.txt') && ff.split('\n').filter(l => l && !l.startsWith('#')).length >= 12
      && wf.includes('            tests/form-fields-cases.txt\n'), 'форма 10.86: общие случаи полей — в обоих тестах и в сборке');
    assert.ok((tp.match(/eq\("форма 10\.86: /g) || []).length >= 3 && (tp.match(/eq\("поиск 10\.86: /g) || []).length >= 2
      && !/customer_profiles|passport|phone|surname/.test(mp + kp), 'IvaPatchTest: проверки 10.86');
  }
  // 10.85 (решение владельца 15.10.2026: «и то и другое» — доделка этапа 3 справочника моделей): в шторке «Фильтр»
  // приложения — числа «от–до» для числовых характеристик вида; объявления без нужной характеристики — в конце списка.
  // На сайте — то же в каталоге (catalog.html): «Вид», характеристики-выбор, числа, ссылка с условиями.
  {
    const mp = R('android/patches/IvaModelPatch.java'), sp = R('android/patches/IvaSearchPatch.java'), tp = R('android/patches/test/IvaPatchTest.java');
    const wf = R('.github/workflows/build-apk.yml'), chk = R('tools/check.sh'), md = R('assets/models.js'), cat = R('catalog.html'), sb = R('assets/sb.js');
    const bt = R('tests/spec-filter-browser.cjs'), jt = R('tests/spec-filter.cjs');
    const fnx = (src, name) => { const mm = new RegExp(`static (?:final )?[\\w.<>, \\[\\]]+ ${name}\\(`).exec(src); assert.ok(mm, `нет ${name}`); return src.slice(mm.index, src.indexOf('\n    }\n', mm.index)); };
    // 1. совпадение: нет значения — 0 (не скрываем), противоречие — -1; в общем extraMatch; лента — подходящие, затем без характеристики
    const ss = fnx(mp, 'specState');
    assert.ok(ss.includes('if (v.isEmpty()) missing = true;') && ss.includes('if (Double.isNaN(v)) missing = true;') && ss.includes('return missing ? 0 : 1;')
      && sp.includes('&& (f.specCount() == 0 || f.specState(IvaModelPatch.toolSpecs(j)) >= 0);')
      && sp.includes('if (bySpec && applied.specState(IvaModelPatch.toolSpecs((JSONObject) o)) == 0) tail.add(o); else out.add(o);') && sp.includes('out.addAll(tail);'),
      'фильтр 10.85: без нужной характеристики — не скрываем, а в конце ленты (штатный renderFeed — по порядку)');
    // 2. «от–до» в Filter: в счётчике, копия независима, сброс и смена вида снимают
    assert.ok(sp.includes('specs.size() + ranges.size(); }') && sp.includes('f.ranges = copyRanges(ranges);') && sp.includes('specs.clear(); ranges.clear(); specKind = ""; }')
      && sp.includes('{ draft.specs.clear(); draft.ranges.clear(); draft.specKind = draft.kind; }') && sp.includes('applied.ranges.clear();'),
      'фильтр 10.85: «от–до» считаются в «Фильтр · N», копируются, снимаются при сбросе и смене вида');
    // 3. поля не пересобираются, пока вводят; та же толщина, что поле цены; плашки «Мощность: от 700 Вт»
    const pn = fnx(sp, 'paintNumFilter');
    assert.ok(pn.includes('if (!sig.toString().equals(numSig)) {') && pn.includes('if (has.contains(fl.key) || draft.ranges.containsKey(fl.key)) show.add(fl);')
      && pn.includes('new LinearLayout.LayoutParams(0, dp(a, PRICE_FIELD_DP), 1f)') && fnx(sp, 'numField').includes('InputType.TYPE_NUMBER_FLAG_DECIMAL')
      && sp.includes('IvaModelPatch.rangeChip(IvaModelPatch.fields, f.kind, rk, f.ranges.get(rk))') && sp.includes('syncNumInputs();                                                       // 10.85'),
      'фильтр 10.85: поля «от/до» — только указанные у вида, не сбивают ввод, толщиной с поле цены; плашки снимаются');
    // 4. сайт: отдельный запрос активных объявлений (общий список полей не меняется), без SQL — как раньше
    assert.ok(md.includes("var base = '/rest/v1/tools?status=eq.active&active=eq.true&limit=1000&select=';") && md.includes("api(base + 'id,kind,specs')")
      && md.includes(".catch(function () { return api(base + 'id,kind'); })") && !/TOOL_FIELDS = [^;]*\b(kind|specs)\b/.test(sb)
      && /<script src="assets\/models\.js\?v=[\w-]+"><\/script>/.test(cat) && cat.includes('var rows = byKind(sortAds(applyFilters(ADS, F), SORT), F);')
      && cat.includes("return rows.filter(function(r){ return r.st === 1; }).concat(rows.filter(function(r){ return r.st === 0; }));")
      && cat.includes('if (F.kind && !kindOn()) clearKind();'),
      'фильтр 10.85: каталог сайта — вид и характеристики отдельным запросом, без характеристики — в конце, без SQL — как раньше');
    // 5. одни и те же случаи в приложении и на сайте; тесты в check.sh; сборка видит файл
    assert.ok(tp.includes('java.nio.file.Paths.get("tests/spec-filter-cases.txt")') && jt.includes('spec-filter-cases.txt')
      && wf.includes('            tests/spec-filter-cases.txt\n') && / spec-filter[ ;]/.test(chk) && / spec-filter-browser[ ;]/.test(chk)
      && ["await run('full');", "await run('nospecs');", "await run('old');"].every(x => bt.includes(x)),
      'фильтр 10.85: приложение и сайт — на одних случаях; браузерный тест каталога в трёх режимах');
    assert.ok((tp.match(/eq\("фильтр 10\.85: /g) || []).length >= 5 && !/customer_profiles|passport|phone|surname/.test(mp), 'IvaPatchTest: проверки 10.85');
  }
  // 10.84 (этап 4 справочника моделей, решение владельца 15.10.2026, №30): «Предложить модель» с одобрением главного админа
  // (вкладка «Модели» в chief.html), «Комплектация» (tools.kit), подсказка цены с «Подставить», «Сгенерировать описание» —
  // в приложении и на сайте (new.html, cabinet.html). SQL — 20261018_models_stage4.sql; без него всё как в 10.83.
  {
    const mp = R('android/patches/IvaModelPatch.java'), kp = R('android/patches/IvaKindPatch.java'), tp = R('android/patches/test/IvaPatchTest.java');
    const wf = R('.github/workflows/build-apk.yml'), chk = R('tools/check.sh'), sql = R('supabase/migrations/20261018_models_stage4.sql');
    const md = R('assets/models.js'), mf = R('assets/model-form.js'), pp = R('assets/model-proposals.js'), sb = R('assets/sb.js');
    const nw = R('new.html'), cab = R('cabinet.html'), ch = R('chief.html'), bt = R('tests/model-stage4-browser.cjs'), jt = R('tests/model-stage4.cjs');
    const fnx = (src, name) => { const mm = new RegExp(`static (?:final )?[\\w.<>, \\[\\]]+ ${name}\\(`).exec(src); assert.ok(mm, `нет ${name}`); return src.slice(mm.index, src.indexOf('\n    }\n', mm.index)); };
    // 1. SQL: комплектацию читают все, меняет главный; предлагают только админы от своего имени; решает только главный
    assert.ok(sql.includes('using (public.fn_is_chief()) with check (public.fn_is_chief());')
      && sql.includes('with check (public.fn_is_admin() and public.jwt_email() <> \'\' and proposed_by = public.jwt_email()')
      && sql.includes('using (proposed_by = public.jwt_email() or public.fn_is_chief());') && sql.includes('if not public.fn_is_chief() then')
      && sql.includes('alter table public.tools add column if not exists kit text[];') && sql.includes('alter table public.tools drop column if exists kit;')
      && !/\bdrop table\b|\btruncate\b/i.test(sql.split('\ncreate ')[0].replace(/^--.*$/gm, '') + sql.slice(sql.indexOf('\ncreate '))),
      'модели 10.84: SQL — предлагают админы от своего имени, решает главный; комплектация читается всеми; ничего не удаляет; откат в шапке');
    assert.ok(chk.includes('tools/kit-gen.py --check') && chk.includes('tools/models-stage4-stand.py') && / model-stage4[ ;]/.test(chk) && / model-stage4-browser[ ;]/.test(chk),
      'модели 10.84: сверка комплектации, стенд SQL, тест функций и браузерный тест — в check.sh');
    // 2. одни и те же запросы и тексты ошибок в приложении и на сайте
    assert.ok(mp.includes('KIT_PATH = "/rest/v1/iva_kit_items?select=kind,items", PROPOSALS_PATH = "/rest/v1/iva_model_proposals";')
      && md.includes("api('/rest/v1/iva_kit_items?select=kind,items')") && mf.includes("api('/rest/v1/iva_model_proposals', 'POST', row)")
      && fnx(mp, 'propErr').includes('"Предложения заработают после обновления базы."') && mf.includes("'Предложения заработают после обновления базы.'")
      && fnx(mp, 'propErr').includes('"Эту модель уже предложили — она ждёт проверки."'),
      'модели 10.84: одни и те же запросы и понятные ошибки в приложении и на сайте');
    // 3. ручное не перезаписываем: описание — только пустое или своё прошлое; цена — только в пустые поля
    const gn = fnx(mp, 'generate'), ph = fnx(mp, 'paintHelp');
    assert.ok(gn.includes('if (cur.trim().isEmpty() || cur.equals(lastDescr)) {') && gn.includes('"Описание уже написано — сотрите его, чтобы сгенерировать заново."')
      && ph.includes('boolean canFill = cur != null && cur.trim().isEmpty();') && ph.includes('String.valueOf(((TextView) df).getText()).trim().isEmpty()'),
      'модели 10.84: описание и цена не перезаписывают написанное руками');
    assert.ok(fnx(mp, 'priceHint').includes('if (pick.size() >= 2) { byModel = true; any = true; }') && fnx(mp, 'priceHint').includes('if (pick.size() >= 3) any = true;')
      && fnx(mp, 'catalogRows').includes('IvaSearchPatch.fullTools'),
      'модели 10.84: подсказка цены в приложении — из каталога в памяти (той же модели от 2, вида от 3), без новых запросов');
    // 4. нет столбца kit — объявление всё равно публикуется (приложение и сайт), kit не в общем списке полей
    assert.ok(fnx(kp, 'consumePending').includes('String noKit = IvaModelPatch.mergeBody(body, IvaModelPatch.dropKit(extra));')
      && nw.includes('if (!payload.model_id && !payload.specs && !payload.kit) throw e1;') && nw.includes('delete payload.kit;')
      && cab.includes('if(!body.model_id&&!body.specs&&!body.kit)throw e1;') && sb.includes('if (data.kit) row.kit = data.kit;')
      && !/TOOL_FIELDS = [^;]*\bkit\b/.test(sb) && nw.includes('<script src="assets/sb.js?v=20261018-stage4"></script>'),
      'модели 10.84: нет столбца kit — повтор без комплектации; kit отправляется только отмеченный');
    // 5. панель главного: вкладка «Модели», решение через RPC, после одобрения справочник перечитывается
    assert.ok(ch.includes('<script src="assets/model-proposals.js?v=20261018-stage4"></script>') && ch.includes('data-tab="models"')
      && pp.includes("api('/rest/v1/rpc/iva_model_decide', 'POST', { p_id: Number(id), p_approve: !!approve,")
      && pp.includes("localStorage.removeItem('iva_models_v2')") && md.includes("var MD_KEY = 'iva_models_v2'"),
      'модели 10.84: вкладка «Модели» — одобрить/отклонить через iva_model_decide, копия справочника сбрасывается');
    // 6. новых вставок в smali нет, личных данных не читаем; общие случаи и браузерный тест
    assert.ok(!/customer_profiles|passport|phone|surname/.test(mp) && tp.includes('java.nio.file.Paths.get("tests/model-stage4-cases.txt")')
      && tp.includes('"supabase/models/kit.txt"') && jt.includes('model-stage4-cases.txt')
      && wf.includes('            tests/model-stage4-cases.txt\n') && wf.includes('            supabase/models/kit.txt\n')
      && ["await runNew('full');", "await runNew('old');", "await runNew('nocol');", 'await runCabinet();', "await runChief('full');", "await runChief('old');"].every(x => bt.includes(x)),
      'модели 10.84: приложение и сайт — на одних случаях; сборка видит файлы; браузерный тест всех трёх страниц');
    assert.ok((tp.match(/eq\("этап 4 10\.84: /g) || []).length >= 7, 'IvaPatchTest: проверки 10.84');
  }
  // 10.83 (этап 3 справочника моделей, решение владельца 15.10.2026, №30): характеристики в карточке (приложение — над
  // отзывами, сайт — tool.html после «Состояния») и фильтр по характеристикам-выбору вида в шторке приложения.
  {
    const mp = R('android/patches/IvaModelPatch.java'), sp = R('android/patches/IvaSearchPatch.java'), tp = R('android/patches/test/IvaPatchTest.java');
    const wf = R('.github/workflows/build-apk.yml'), ap = R('android/patches/apply.py'), md = R('assets/models.js'), th = R('tool.html');
    const bt = R('tests/model-form-browser.cjs'), jt = R('tests/model-search.cjs');
    const fnx = (src, name) => { const mm = new RegExp(`static (?:final )?[\\w.<>, \\[\\]]+ ${name}\\(`).exec(src); assert.ok(mm, `нет ${name}`); return src.slice(mm.index, src.indexOf('\n    }\n', mm.index)); };
    // 1. карточка приложения: из onTab("detail") через IvaKindPatch (новых вставок в smali нет), блок перед отзывами
    const od = fnx(mp, 'onDetail');
    assert.ok(mp.includes('if ("detail".equals(tab)) { try { onDetail(a); } catch (Throwable ignore) {} }')
      && od.includes('box.findViewWithTag(IvaReviewPatch.DET_BLOCK_TAG)') && od.includes('box.indexOfChild(rv) : box.getChildCount()')
      && od.includes('if (rows.isEmpty()) { if (old != null) old.setVisibility(View.GONE); return; }') && od.includes('Boolean.TRUE.equals(IvaClientPatch.get(a, "detIsSrv"))')
      && !/apiCall/.test(od) && !/IvaModelPatch|model/i.test(ap),
      'характеристики 10.83: карточка приложения — блок над отзывами, без своих запросов, без вставок в smali');
    // 2. у клиента — только подписи полей, в фоне, с паузой после ошибки
    const ef = fnx(mp, 'ensureFields');
    assert.ok(ef.includes('if (fields != null || fieldsLoading || loading) return;') && ef.includes('if (now - fieldsTriedAt < RETRY_MS) return;')
      && ef.includes('IvaClientPatch.apiCall(a, FIELDS_PATH, "GET", null, false)') && ef.includes('new Thread(') && !ef.includes('modelsPath'),
      'характеристики 10.83: клиенту — только подписи полей, без моделей, в фоне');
    // 3. фильтр: характеристики живут только с тем же видом; совпадение — в общем extraMatch; применяются вместе с остальным
    assert.ok(sp.includes('int specCount() { return kind.isEmpty() || !kind.equals(specKind) ? 0 : specs.size()')   // 10.85: + ranges.size()
      && sp.includes('f.specs = new LinkedHashMap<String, String>(specs); f.specKind = specKind;')
      && /&& \(f\.specCount\(\) == 0 \|\| (IvaModelPatch\.specsMatch\(IvaModelPatch\.toolSpecs\(j\), f\.specs\)|f\.specState\(IvaModelPatch\.toolSpecs\(j\)\) >= 0)\);/.test(sp)
      && (sp.match(/applied\.setSpecsFrom\((f|d)\);/g) || []).length === 2 && sp.includes('activeCount(f.cat, f.sub, f.kind, f.from, f.to, f.delivery, f.conds) + f.specCount();')
      && /conds = 0; specs\.clear\(\);( ranges\.clear\(\);)? specKind = ""; \}/.test(sp),
      'характеристики 10.83: фильтр — только с тем же видом, в общем совпадении, считается в «Фильтр · N», сбрасывается');
    const pf = fnx(sp, 'paintSpecFilter');
    assert.ok(pf.includes('IvaModelPatch.choiceFields(IvaModelPatch.fields, draft.kind)') && pf.includes('other.specs.remove(fl.key);')
      && pf.includes('facetVisible(java.util.Arrays.asList(fl.options), cnt, sel)') && sp.includes('paintSpecFilter(a, city, query);'),
      'характеристики 10.83: в шторке — только поля-выбор вида и только значения, что есть у подходящих объявлений');
    // 4. сайт: карточка — отдельный запрос с запасным без specs; ошибка — как раньше
    assert.ok(/<script src="assets\/models\.js\?v=[\w-]+"><\/script>/.test(th) && th.includes('<div class="spec" id="specCond">')
      && th.includes("at.insertAdjacentHTML('afterend',") && th.includes("const r = await ivaToolSpecs(api, ad.id);")
      && md.includes(".catch(function () { return api(q + 'kind'); })") && md.includes("var q = '/rest/v1/tools?id=eq.' + encodeURIComponent(id) + '&select=';"),
      'характеристики 10.83: сайт — вид и характеристики после «Состояния»; нет столбца — только вид; ошибка — как раньше');
    // 5. одинаковые строки карточки в Java и JS
    assert.ok(tp.includes('"Питание=Сеть 220 В|Мощность=780" + NB + "Вт|Энергия удара=2,4" + NB + "Дж|"') && jt.includes("'Питание=Сеть 220 В|Мощность=780' + NB + 'Вт|Энергия удара=2,4' + NB + 'Дж|'")
      && bt.includes("await runTool('full');") && bt.includes("await runTool('nocol');") && bt.includes("await runTool('none');"),
      'характеристики 10.83: строки карточки одинаковы в приложении и на сайте; браузерный тест карточки');
    assert.ok((tp.match(/eq\("характеристики 10\.83: /g) || []).length >= 7, 'IvaPatchTest: проверки 10.83');
  }
  // 10.82 (этап 2 справочника моделей, решение владельца 15.10.2026, №30): подсказки моделей по названию, поле «Модель»
  // с брендами, «Характеристики» под видом — в форме «Добавить» приложения и на сайте (new.html, cabinet.html).
  // Справочник — iva_brands, iva_spec_fields, iva_models (SQL). Нет SQL — всё как в 10.81.
  {
    const mp = R('android/patches/IvaModelPatch.java'), kp = R('android/patches/IvaKindPatch.java'), tp = R('android/patches/test/IvaPatchTest.java');
    const wf = R('.github/workflows/build-apk.yml'), chk = R('tools/check.sh'), ap = R('android/patches/apply.py');
    const sql = R('supabase/migrations/20261017_models.sql'), md = R('assets/models.js'), mf = R('assets/model-form.js');
    const nw = R('new.html'), cab = R('cabinet.html'), sb = R('assets/sb.js'), bt = R('tests/model-form-browser.cjs'), jt = R('tests/model-search.cjs');
    const fnx = (src, name) => { const mm = new RegExp(`static (?:final )?[\\w.<>, \\[\\]]+ ${name}\\(`).exec(src); assert.ok(mm, `нет ${name}`); return src.slice(mm.index, src.indexOf('\n    }\n', mm.index)); };
    // 1. SQL: три таблицы читают все, правит только руководитель; удаление модели не трогает объявления
    assert.ok(['iva_brands', 'iva_spec_fields', 'iva_models'].every(t => sql.includes('create table if not exists public.' + t + ' ('))
      && sql.includes("execute format('grant select on public.%I to anon, authenticated', t);") && sql.includes('using (public.fn_is_chief()) with check (public.fn_is_chief())')
      && sql.includes('foreign key (model_id) references public.iva_models(id) on delete set null;')
      && !/\bdrop table\b|\btruncate\b/i.test(sql.split('Откат')[0]),
      'модели 10.82: SQL — справочник читают все, меняет только руководитель; удаление модели не трогает объявления');
    assert.ok(chk.includes('tools/models-stand.py') && / model-search[ ;]/.test(chk) && / model-form-browser[ ;]/.test(chk),
      'модели 10.82: стенд SQL, тест поиска и браузерный тест — в check.sh');
    // 2. загрузка: одни и те же запросы; страницами по 1000; ошибка — справочника нет (формы как в 10.81)
    assert.ok(mp.includes('static final String BRANDS_PATH = "/rest/v1/iva_brands?select=name,aliases&order=name.asc";')
      && md.includes("api('/rest/v1/iva_brands?select=name,aliases&order=name.asc')")
      && mp.includes('static final String FIELDS_PATH = "/rest/v1/iva_spec_fields?select=kind,key,title,unit,type,options,sort&order=kind.asc,sort.asc";')
      && md.includes("'/rest/v1/iva_spec_fields?select=kind,key,title,unit,type,options,sort&order=kind.asc,sort.asc'")
      && fnx(mp, 'modelsPath').includes('"/rest/v1/iva_models?select=id,kind,brand,model,title,specs&active=eq.true&order=id.asc&limit=" + PAGE + "&offset=" + offset')
      && md.includes("'/rest/v1/iva_models?select=id,kind,brand,model,title,specs&active=eq.true&order=id.asc&limit=' + MD_PAGE + '&offset=' + off"),
      'модели 10.82: приложение и сайт читают справочник одними и теми же запросами');
    const el = fnx(mp, 'ensureLoaded');
    assert.ok(mp.includes('static final int PAGE = 1000;') && el.includes('if (!ok(rb) || !ok(rf)) return;') && el.includes('if (!ok(rm)) return;')
      && el.includes('if (n < PAGE) break;') && el.includes('if (ms.isEmpty() || fl.isEmpty()) return;') && el.includes('new Thread(')
      && el.includes('} finally { loading = false; }'),
      'модели 10.82: приложение — в фоне, страницами по 1000; ошибка — справочника нет');
    // 3. запись: модель и характеристики дописываются к виду; база не приняла — хотя бы вид (приложение и сайт)
    const se = fnx(mp, 'saveExtra');
    assert.ok(se.includes('if (!ready() || kind == null || kind.isEmpty()) return "";') && se.includes('stillFits(name, chosen) ? chosen.id : 0')
      && kp.includes('try { pendingExtra = IvaModelPatch.saveExtra(a, kind, name); } catch (Throwable e) { pendingExtra = ""; }')
      && kp.includes('String full = IvaModelPatch.mergeBody(body, extra);')
      && kp.includes('if (!full.equals(body) && (p == null || p[0] == null || !p[0].startsWith("2")))')
      && kp.includes('p = IvaClientPatch.apiCall(a, patchPath(target), "PATCH", body, true);'),
      'модели 10.82: приложение — модель только если подходит к названию; не приняли — повтор без модели');
    assert.ok(nw.includes('if (!payload.model_id && !payload.specs) throw e') && nw.includes('delete payload.model_id; delete payload.specs;')
      && cab.includes("if(!body.model_id&&!body.specs)throw e") && cab.includes("delete body.model_id;delete body.specs;await api('/rest/v1/tools','POST',body)}")
      && sb.includes('if (data.model_id) row.model_id = data.model_id;') && sb.includes('if (data.specs) row.specs = data.specs;')
      && !/TOOL_FIELDS = [^;]*(model_id|specs)/.test(sb),
      'модели 10.82: сайт — модель и характеристики только если есть; не приняли — повтор без них; общий список полей не меняется');
    // 4. сайт: только новое объявление; общие модули; кабинет без theme.css
    assert.ok(nw.includes('function mdOn(){ return !!MD && !EDIT_ID; }') && nw.includes("if (!EDIT_ID && typeof ivaModelsLoad === 'function'){")
      && cab.includes("if(!t&&typeof ivaModelsLoad==='function')"),
      'модели 10.82: на сайте справочник только для нового объявления');
    assert.ok(nw.includes('<script src="assets/models.js?v=') && nw.includes('<script src="assets/model-form.js?v=')
      && cab.includes('<script src="assets/models.js?v=') && cab.includes('<script src="assets/model-form.js?v=')
      && mf.includes("if (e.target.closest('[data-msug]')) e.preventDefault();") && !/<link[^>]*theme\.css/.test(cab),
      'модели 10.82: сайт — общие модули; кабинет без theme.css');
    // 5. приложение: без новых вставок в smali — только из IvaKindPatch; файл в сборке; личных данных не читаем
    assert.ok(!/IvaModelPatch|model/i.test(ap) && /IvaKindPatch\.java android\/patches\/IvaModelPatch\.java (android\/patches\/IvaFormPatch\.java )?(android\/patches\/IvaPhotoPatch\.java )?(android\/patches\/IvaCensorPatch\.java )?(android\/patches\/IvaChiefPatch\.java )?(android\/patches\/IvaTestPatch\.java )?(android\/patches\/IvaPickupPatch\.java )?(android\/patches\/IvaAvatarPatch\.java )?(android\/patches\/IvaShopPatch\.java )?(android\/patches\/IvaSoundPatch\.java )?(android\/patches\/IvaToolPointPatch\.java )?(android\/patches\/IvaOrderPointPatch\.java )?(android\/patches\/IvaStaffPatch\.java )?(android\/patches\/IvaStaffOrdersPatch\.java )?(android\/patches\/IvaStaffToolsPatch\.java )?(android\/patches\/IvaStaffChatPatch\.java )?(android\/patches\/IvaStaffJournalPatch\.java )?(android\/patches\/IvaAdminsPatch\.java )?2>&1/.test(wf)
      && kp.includes('if (k == 0) IvaModelPatch.refresh(a);') && kp.includes('try { IvaModelPatch.onTab(a, tab); } catch (Throwable ignore) {}')
      && !/customer_profiles|passport|phone|surname/.test(mp),
      'модели 10.82: новых вставок в smali нет; личных данных не читаем');
    // 6. приложение и сайт проверяются на одних и тех же случаях и настоящем справочнике
    assert.ok(tp.includes('java.nio.file.Paths.get("tests/model-search-cases.txt")') && jt.includes('model-search-cases.txt')
      && tp.includes('java.nio.file.Paths.get("supabase/migrations")') && (bt + R('tests/model-fixtures.cjs')).includes('20261017_models.sql') && (bt + R('tests/model-fixtures.cjs')).includes('20261017_models_data_'),
      'модели 10.82: Java и JS — одни и те же случаи и справочник из SQL');
    assert.ok((tp.match(/eq\("модели 10\.82: /g) || []).length >= 20, 'IvaPatchTest: проверки 10.82');
    assert.ok(['supabase/migrations/20261017_models.sql', 'supabase/migrations/20261017_models_data_*.sql', 'tests/model-search-cases.txt']
      .every(f => wf.includes('\n            ' + f + '\n')), 'модели 10.82: файлы справочника и случаев есть в выборочной выгрузке сборки');
  }
  // 10.81 (этап 1 справочника моделей, решение владельца 15.10.2026, №30): категория, подкатегория и вид подставляются
  // сами по названию — в форме «Добавить» приложения и на сайте (new.html, cabinet.html). Слова — iva_kinds.words (SQL).
  {
    const kp = R('android/patches/IvaKindPatch.java'), tp = R('android/patches/test/IvaPatchTest.java'), wf = R('.github/workflows/build-apk.yml');
    const sql = R('supabase/migrations/20261016_kind_words.sql'), chk = R('tools/check.sh'), ap = R('android/patches/apply.py');
    const kg = R('assets/kind-guess.js'), nw = R('new.html'), cab = R('cabinet.html'), sb = R('assets/sb.js');
    const jt = R('tests/kind-guess.cjs'), bt = R('tests/kind-guess-browser.cjs');
    const fnx = (src, name) => { const mm = new RegExp(`static (?:final )?[\\w.<>, \\[\\]]+ ${name}\\(`).exec(src); assert.ok(mm, `нет ${name}`); return src.slice(mm.index, src.indexOf('\n    }\n', mm.index)); };
    // 1. SQL: столбец words, слова для всех 159 видов, повтор безопасен, откат в конце; стенд в check.sh
    assert.ok(sql.includes("alter table public.iva_kinds add column if not exists words text[] not null default '{}';")
      && sql.includes('and k.words is distinct from v.words;') && sql.includes('--   alter table public.iva_kinds drop column if exists words;')
      && (sql.match(/^\s*\('[a-z0-9-]+', array\[/gm) || []).length === 159 && !/\bdrop table\b|\bdelete from\b|\btruncate\b/i.test(sql.split('Откат')[0]),
      'автоподбор 10.81: SQL — столбец words для 159 видов, повтор безопасен, ничего не удаляет, откат в конце');
    assert.ok(chk.includes('tools/kind-words-stand.py') && / kind-guess[ ;]/.test(chk) && / kind-guess-browser[ ;]/.test(chk),
      'автоподбор 10.81: стенд SQL, тест алгоритма и браузерный тест — в check.sh');
    // 2. приложение: слова — отдельным запросом (старый запрос видов не меняется: нет столбца — только без автоподбора)
    assert.ok(kp.includes('static final String WORDS_PATH = "/rest/v1/iva_kinds?select=key,words&active=eq.true";') && !/KINDS_PATH = "[^"]*words/.test(kp),
      'автоподбор 10.81: слова — своим запросом, справочник видов прежний');
    const lw = fnx(kp, 'loadWords');
    assert.ok(lw.includes('r[0].startsWith("404") || r[0].startsWith("400")') && lw.includes('words = null;') && lw.includes('.remove(PREFS_WORDS)'),
      'автоподбор 10.81: столбца нет — слова забываются, автоподбора нет');
    // 3. алгоритм — как на сайте: ё→е, слова, начало слова, ничья → общее
    const tk = fnx(kp, 'tokens'), es = fnx(kp, 'entryScore'), gs = fnx(kp, 'guess'), pl = fnx(kp, 'plan');
    assert.ok(tk.includes(`.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^a-zа-я0-9]+", " ")`) && es.includes('if (t.startsWith(p)) { hit = true; break; }')
      && gs.includes('if (m > bs || (m == bs && n > bn))') && gs.includes('if (sameSub) return new Guess(f.cat, f.sub, "");') && gs.includes('if (sameCat) return new Guess(f.cat, "", "");')
      && kg.includes(".toLowerCase().replace(/ё/g, 'е')") && kg.includes(".replace(/[^a-zа-я0-9]+/g, ' ')") && kg.includes('toks[j].lastIndexOf(p, 0) === 0')
      && kg.includes('if (m > bs || (m === bs && n > bn))'),
      'автоподбор 10.81: алгоритм в приложении и на сайте один и тот же');
    assert.ok(pl.includes('if (!mayFill(cc, autoCat) && !cc.equals(g.cat)) return null;') && kg.includes('if (!ivaKindMayFill(cc, last.cat) && cc !== g.cat) return null;'),
      'автоподбор 10.81: категорию, выбранную руками, не меняем (приложение и сайт)');
    // 4. форма «Добавить»: свой слушатель названия (штатный не заменяется), пауза, как штатный выбор; правка — без автоподбора
    const wn = fnx(kp, 'watchName'), af = fnx(kp, 'autofill') + fnx(kp, 'applyPlan');   // 10.82: подстановка вынесена в applyPlan
    assert.ok(wn.includes('Object no = IvaClientPatch.get(a, FORM[0][5]);') && wn.includes('nv.addTextChangedListener(') && wn.includes('if (my != nameSeq) return;')
      && wn.includes('}, AUTO_DELAY_MS);') && !/setOnClickListener|setText\(|setOnFocusChangeListener/.test(wn) && fnx(kp, 'refreshRow').includes('if (k == 0) watchName(a);'),
      'автоподбор 10.81: слушатель названия добавляется (штатный не трогаем), с паузой, только в форме «Добавить»');
    assert.ok(af.includes('if (kinds == null || words == null) return;') && af.includes("if (!((View) no).isShown()) return;")
      && af.includes('if (catTitle == null) return;') && af.includes('String[] p = plan(curCat, curSub, sel[0], auto[0], auto[1], auto[2], g);')
      && af.includes('IvaClientPatch.set(a, FORM[0][1], p[0]);') && af.includes('IvaClientPatch.set(a, FORM[0][3], "");') && af.includes('IvaClientPatch.set(a, FORM[0][3], p[1]);')
      && af.includes('catV.setTextColor(text);') && af.includes('hideDrop(a, "atCatSug"); hideDrop(a, "atSubSug");') && !af.includes('FORM[1]'),
      'автоподбор 10.81: подстановка — как штатный выбор (ключ, текст, цвет, списки закрыты); категории нет в штатном списке — не трогаем');
    assert.ok(kp.includes('static final String SUB_HINT = "Подкатегория (необязательно)";') && has(dex14, 'Подкатегория (необязательно)')
      && has(dex14, 'atCatSug') && has(dex14, 'atSubSug') && has(dex14, 'atName'),
      'автоподбор 10.81: штатные поля и текст пустой подкатегории есть в 10.14');
    assert.ok(!/IvaKindPatch|kind/i.test(ap) && /IvaRentPatch\.java android\/patches\/IvaKindPatch\.java (android\/patches\/IvaModelPatch\.java )?(android\/patches\/IvaFormPatch\.java )?(android\/patches\/IvaPhotoPatch\.java )?(android\/patches\/IvaCensorPatch\.java )?(android\/patches\/IvaChiefPatch\.java )?(android\/patches\/IvaTestPatch\.java )?(android\/patches\/IvaPickupPatch\.java )?(android\/patches\/IvaAvatarPatch\.java )?(android\/patches\/IvaShopPatch\.java )?(android\/patches\/IvaSoundPatch\.java )?(android\/patches\/IvaToolPointPatch\.java )?(android\/patches\/IvaOrderPointPatch\.java )?(android\/patches\/IvaStaffPatch\.java )?(android\/patches\/IvaStaffOrdersPatch\.java )?(android\/patches\/IvaStaffToolsPatch\.java )?(android\/patches\/IvaStaffChatPatch\.java )?(android\/patches\/IvaStaffJournalPatch\.java )?(android\/patches\/IvaAdminsPatch\.java )?2>&1/.test(wf),
      'автоподбор 10.81: новых вставок в smali и новых файлов нет');
    // 5. сайт: один модуль, вид пишется только когда справочник видов есть в базе
    assert.ok(nw.includes('<script src="assets/kind-guess.js?v=20261016-kind"></script>') && cab.includes('<script src="assets/kind-guess.js?v=20261016-kind"></script>')
      && nw.includes('if (KINDS_DB.length){') && nw.includes("if (F.kind || EDIT_ID) payload.kind = F.kind || null;")
      && cab.includes("if(S.kinds&&S.kinds.length){var kv=") && sb.includes('if (data.kind) row.kind = data.kind;')
      && sb.includes("try { rows = await api(base + ',words'); }") && sb.includes('catch(e){ try { rows = await api(base); } catch(e2){ return []; } }')
      && !/TOOL_FIELDS = [^;]*kind/.test(sb),
      'автоподбор 10.81: сайт — общий модуль; вид в запросе только при справочнике видов; общий список полей объявления не меняется');
    assert.ok((nw.includes("(canAuto() && !EDIT_ID ?") || nw.includes("((canAuto() || mdOn()) && !EDIT_ID ?")) && cab.includes("if(!t)$('fName').oninput="),
      'автоподбор 10.81: на сайте автоподбор только для нового объявления');
    // 6. приложение и сайт проверяются на одних и тех же случаях и настоящем словаре
    assert.ok(tp.includes('java.nio.file.Paths.get("tests/kind-guess-cases.txt")') && jt.includes("'tests/kind-guess-cases.txt'")
      && tp.includes('supabase/migrations/20261016_kind_words.sql') && jt.includes('supabase/migrations/20261016_kind_words.sql') && bt.includes('20261016_kind_words.sql'),
      'автоподбор 10.81: Java и JS — одни и те же случаи и словарь из SQL');
    assert.ok((tp.match(/eq\("автоподбор 10\.81: /g) || []).length >= 15, 'IvaPatchTest: проверки 10.81');
    assert.ok(['supabase/migrations/20261015_category_tree.sql', 'supabase/migrations/20261016_kind_words.sql', 'tests/kind-guess-cases.txt']
      .every(f => wf.includes('\n            ' + f + '\n')), 'автоподбор 10.81: файлы словаря и случаев есть в выборочной выгрузке сборки (иначе IvaPatchTest их не найдёт)');
  }
  // 10.80 (просьба владельца 15.10.2026): обновление без окон. Серое окно «Всё готово!» убрано; «Показать окно установки»
  // ничего не делало — в манифесте не было REQUEST_INSTALL_PACKAGES (системный установщик молча отменял установку).
  {
    const np = R('android/patches/IvaNotifyPatch.java'), tp = R('android/patches/test/IvaPatchTest.java'), wf = R('.github/workflows/build-apk.yml');
    const ai = R('android/patches/axml_install.py'), ap = R('android/patches/apply.py');
    const fnx = (src, name) => { const mm = new RegExp(`static (?:final )?[\\w.<>, \\[\\]]+ ${name}\\(`).exec(src); assert.ok(mm, `нет ${name}`); return src.slice(mm.index, src.indexOf('\n    }\n', mm.index)); };
    // 1. манифест: разрешение добавляется после push-приёмника и проверяется в готовом APK
    assert.ok(ai.includes('INSTALL = "android.permission.REQUEST_INSTALL_PACKAGES"') && ai.includes('print("разрешение на установку обновления уже есть в манифесте")')
      && ai.includes('body = body[:app_start] + perm + body[app_start:]'),
      'обновление 10.80: axml_install.py — одно разрешение перед <application>, повторный запуск ничего не меняет');
    assert.ok(wf.indexOf('python3 android/patches/axml_push.py "$W/AndroidManifest.xml"') < wf.indexOf('python3 android/patches/axml_install.py "$W/AndroidManifest.xml"')
      && wf.includes(`grep -q "uses-permission: name='android.permission.REQUEST_INSTALL_PACKAGES'" || bad "в манифесте нет разрешения на установку обновления"`),
      'обновление 10.80: разрешение — в сборке и в проверке готового APK');
    assert.ok(!/SelfUpdate|install/i.test(ap), 'обновление 10.80: новых вставок в smali нет');
    // 2. кнопка своя, вид как у штатной; штатные окна (ProgressDialog, «Всё готово!») не вызываются
    const ub = fnx(np, 'updateButton');
    // 10.96: рамкой — оранжевая рамка и текст, полоса (ClipDrawable в IvaPatch.accentProgressBg) — только во время загрузки
    const apb = fnx(R('android/patches/IvaPatch.java'), 'accentProgressBg');
    assert.ok(ub.includes('b.setTag(UPDATE_BTN_TAG);') && ub.includes('b.setTextColor(UPD_ORANGE);') && ub.includes('b.setTextSize(15);') && ub.includes('b.setBackground(IvaPatch.accentProgressBg(a, UPD_ORANGE, 12));')
      && apb.includes('new android.graphics.drawable.ClipDrawable(bar, Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL)') && np.includes('UPD_ORANGE = 0xFFFF8F1F')
      && !/ProgressDialog|AlertDialog|showReadyDialog|onButtonTap|showButtonOnUi"/.test(np),
      'обновление 10.80/10.96: кнопка рамкой (оранжевая рамка и текст, 15sp, радиус 12dp), прогресс — полосой на кнопке; без серых окон');
    const apr = fnx(np, 'applyRelease');
    assert.ok(apr.includes('if (old != null && (!want || relShown != code || old != updBtn.get())) { box.removeView(old); old = null; }')
      && apr.includes('boolean want = updateWanted(code, installedCode(a), apk);'),
      'обновление 10.80: кнопка только при релизе новее установленного; штатная (если успела появиться) заменяется своей');
    // 3. нажатие: скачать (если нет годного файла) → проверить → сразу установка; повторное нажатие во время работы — ничего
    const tap = fnx(np, 'updateTap');
    assert.ok(tap.includes('if (updState != UPD_IDLE && updState != UPD_READY) return;') && /if \(!apkReady\(app, f\)\) \{\n\s+err = download\(apk, f\);/.test(tap) && tap.includes('install(app, f);')
      && tap.includes('new File(app.getCacheDir(), updFileName(code))'),
      'обновление 10.80: нажали — скачиваем во внутренний кэш и сразу ставим; двойное нажатие не запускает вторую загрузку');
    const ar = fnx(np, 'apkReady'), fits = fnx(np, 'apkFits');
    assert.ok(ar.includes('getPackageArchiveInfo(f.getPath(), 0)') && ar.includes('apkFits(pi.packageName, pi.versionCode, c.getPackageName(), installedCode(c))')
      && fits.includes('pkg.equals(wantPkg) && installed > 0 && ver > installed'),
      'обновление 10.80: ставим только APK нашего приложения и только новее установленного');
    const dl = fnx(np, 'download');
    assert.ok(dl.includes('if (releaseApk(url).isEmpty() && !url.startsWith(APK_BASE)) return "чужая ссылка";') && dl.includes('if (code != 200) return "сервер ответил " + code;')
      && dl.includes('if (done > UPD_MAX_BYTES) return "слишком большой файл";') && dl.includes('if (total > 0 && done != total) return "файл скачался не полностью";')
      && dl.includes('if (!part.renameTo(f)) return "не удалось сохранить файл";') && dl.includes('if (part.exists()) part.delete();'),
      'обновление 10.80: качаем только APK проекта, во временный файл, недокачанный не ставим');
    const ins = fnx(np, 'install');
    assert.ok(ins.includes('su.getDeclaredMethod("commitInstall", Context.class, File.class);\n            m.setAccessible(true);\n            m.invoke(null, app, f);') && ins.includes('canRequestPackageInstalls()')
      && ins.includes('if (updState == UPD_INSTALL) { updState = UPD_READY;') && has(dex14, 'commitInstall') && has(dex14, 'Lru/prokatnvsk/app/SelfUpdate$StatusReceiver;'),
      'обновление 10.80: установка — штатный commitInstall (ответ — штатный StatusReceiver); окно закрыли — «Установить версию N»');
    assert.ok(fnx(np, 'releaseCheck').includes('cleanupUpdates(a.getApplicationContext());') && fnx(np, 'staleUpdate').includes('Integer.parseInt(m.group(1)) <= installed'),
      'обновление 10.80: файлы уже установленных обновлений удаляются');
    assert.ok(!/customer_profiles|passport|surname|AlarmManager|WorkManager|com\.google\.android\.gms/.test(np), 'обновление 10.80: без личных данных и сервисов Google');
    assert.ok((tp.match(/eq\("обновление 10\.80: /g) || []).length >= 9, 'IvaPatchTest: проверки 10.80');
  }
  // 10.79 (решение владельца 15.10.2026, №28): новое дерево категорий (15 категорий, «Другое» в каждой) — одним SQL; «Вид
  // инструмента» — в форме «Добавить инструмент» и в правке (необязательно) и в фильтре после «Подкатегории»
  {
    const kp = R('android/patches/IvaKindPatch.java'), ip = R('android/patches/IvaPatch.java'), sp = R('android/patches/IvaSearchPatch.java');
    const tp = R('android/patches/test/IvaPatchTest.java'), wf = R('.github/workflows/build-apk.yml'), ck = R('.github/workflows/check.yml');
    const ap = R('android/patches/apply.py'), chk = R('tools/check.sh');
    const sql = R('supabase/migrations/20261015_category_tree.sql'), mv = R('supabase/migrations/20261015_category_tree_move.sql');
    const fnx = (src, name) => { const mm = new RegExp(`static (?:final )?[\\w.<>, \\[\\]]+ ${name}\\(`).exec(src); assert.ok(mm, `нет ${name}`); return src.slice(mm.index, src.indexOf('\n    }\n', mm.index)); };
    // 1. сборка и проверка компилируют IvaKindPatch; вставок в smali нет
    assert.ok(/IvaRentPatch\.java android\/patches\/IvaKindPatch\.java (android\/patches\/IvaModelPatch\.java )?(android\/patches\/IvaFormPatch\.java )?(android\/patches\/IvaPhotoPatch\.java )?(android\/patches\/IvaCensorPatch\.java )?(android\/patches\/IvaChiefPatch\.java )?(android\/patches\/IvaTestPatch\.java )?(android\/patches\/IvaPickupPatch\.java )?(android\/patches\/IvaAvatarPatch\.java )?(android\/patches\/IvaShopPatch\.java )?(android\/patches\/IvaSoundPatch\.java )?(android\/patches\/IvaToolPointPatch\.java )?(android\/patches\/IvaOrderPointPatch\.java )?(android\/patches\/IvaStaffPatch\.java )?(android\/patches\/IvaStaffOrdersPatch\.java )?(android\/patches\/IvaStaffToolsPatch\.java )?(android\/patches\/IvaStaffChatPatch\.java )?(android\/patches\/IvaStaffJournalPatch\.java )?(android\/patches\/IvaAdminsPatch\.java )?2>&1/.test(wf) && ck.includes('android/patches/IvaKindPatch.java'),
      'вид 10.79: IvaKindPatch компилируется в сборку и в проверку');
    assert.ok(!/IvaKindPatch|kind/i.test(ap), 'вид 10.79: новых вставок в smali нет');
    // 2. точки входа: onTab (справочник, форма, запись), onHub (только форма — запомненный выбор не трогает), касание сохранения
    assert.ok(fnx(ip, 'onTab').includes('try { IvaKindPatch.onTab(a, tab); } catch (Throwable ignore) {}')
      && fnx(ip, 'onHub').includes('try { IvaKindPatch.onHub(a); } catch (Throwable ignore) {}') && !fnx(ip, 'onHub').includes('IvaKindPatch.onTab('),
      'вид 10.79: вызывается из IvaPatch.onTab и onHub (onHub — без записи)');
    const cr = fnx(ip, 'condRow');
    assert.ok(/ACTION_DOWN && v\.isEnabled\(\)\) \{\n\s+condPut\(terms, k\);\n\s+try \{ IvaKindPatch\.onSaveTouch\(a, k\); \} catch \(Throwable ignore\) \{\}[^\n]*\n\s+\}\n\s+return false;/.test(cr),
      'вид 10.79: нажатие «Опубликовать»/«Сохранить» только запоминает вид (касание не перехватывается)');
    const oh = fnx(kp, 'onHub');
    assert.ok(!oh.includes('consumePending') && fnx(kp, 'onTab').includes('consumePending(a, tab)'), 'вид 10.79: записываем только из onTab');
    // 3. запись — только после штатного успеха (showTab("admhub")), 3 минуты, одна попытка; только поле kind
    assert.ok(fnx(kp, 'pendingWrite').includes('return pendingFresh(at, now) && "admhub".equals(tab);')
      && kp.includes('static final long PENDING_MS = 3 * 60_000L;') && kp.includes('static final long FRESH_MS = 30 * 60_000L;'),
      'вид 10.79: вид пишется только при открытии кабинета после нажатия (штатный успех), не позже 3 минут');
    const cp = fnx(kp, 'consumePending');
    assert.ok(cp.includes('pendingAt = 0;\n        if (!write) return;') && cp.includes('final String body = patchBody(kind);')
      && cp.includes('apiCall(a, patchPath(target), "PATCH", body, true)') && (cp.match(/apiCall\(/g) || []).length >= 3   // 10.82: + запись с моделью; 10.84: + без комплектации
      && cp.includes('String full = IvaModelPatch.mergeBody(body, extra);')
      && cp.includes('if (!clean(o.isNull("kind") ? "" : o.optString("kind", "")).isEmpty()) return;'),
      'вид 10.79: одна попытка; PATCH только тела patchBody; у нового объявления уже есть вид — не трогаем');
    assert.ok(/return "\{\\"kind\\":null\}";/.test(fnx(kp, 'patchBody')) && fnx(kp, 'patchBody').includes('if (!k.matches("[a-z0-9-]{1,64}")) return null;'),
      'вид 10.79: в теле запроса — только kind (ключ латиницей) или null');
    assert.ok(fnx(kp, 'lookupPath').includes('owner_email=eq.') && fnx(kp, 'lookupPath').includes('&created_at=gte.') && fnx(kp, 'lookupPath').includes('&order=created_at.desc&limit=1'),
      'вид 10.79: новое объявление — своё, с этим названием, свежее, последнее');
    const st = fnx(kp, 'onSaveTouch');
    assert.ok(st.includes('pendingAt = 0;') && st.includes('if (kinds == null) return;') && st.includes('if (!editLoaded || editId.isEmpty() || kind.equals(orig[1])) return;')
      && st.includes('if (kind.isEmpty()) return;'),
      'вид 10.79: без SQL — ничего; правка — только если вид загружен и изменён; новое без вида и города — ничего');
    // 4. справочник — публичный (без входа), копия на телефоне; нет таблицы — вида нигде нет
    assert.ok(kp.includes('static final String KINDS_PATH = "/rest/v1/iva_kinds?select=key,cat,sub,title,sort&active=eq.true&order=sort.asc,title.asc";')
      && fnx(kp, 'ensureLoaded').includes('IvaClientPatch.apiCall(a, KINDS_PATH, "GET", null, false)'),
      'вид 10.79: справочник видов читается публичным ключом');
    assert.ok(!/customer_profiles|passport|phone|surname|AlarmManager|WorkManager|com\.google/.test(kp), 'вид 10.79: без личных данных и сервисов Google');
    // 5. форма: поле после списка подкатегорий в обеих формах; виды — только выбранной подкатегории; чужой вид снимается
    assert.ok(kp.includes('{"atCat", "atCatKey", "atSub", "atSubKey", "atSubSug", "atName"},') && kp.includes('{"teCat", "editToolCat", "teSub", "editToolSub", "teSubSug", "teName"},')
      && fnx(kp, 'paint').includes('sel[k] = keep(all, cat, sub, sel[k]);') && fnx(kp, 'paint').includes('row.setVisibility(show ? View.VISIBLE : View.GONE);'),
      'вид 10.79: поле «Вид» в обеих формах, только при видах в подкатегории; вид чужой подкатегории снимается');
    assert.ok(!/#[0-9A-Fa-f]{6}\b|setBackgroundColor\(0x/.test(kp), 'вид 10.79: без новых цветов (фон и стрелка — копии штатного поля)');
    for (const str of ['atSubSug', 'teSubSug', 'atName', 'teName', 'editToolId', 'userEmail', 'atCatKey', 'atSubKey', 'editToolCat', 'editToolSub'])
      assert.ok(has(arsc14, str) || has(dex14, str), `APK 10.14: нет «${str}» — поле «Вид» не найдёт штатное поле`);
    assert.ok(has(dex14, 'admhub') && has(dex14, 'tooledit'), 'APK 10.14: нет вкладок admhub / tooledit');
    // 6. фильтр: «Вид» после «Подкатегории»; отбор ленты; число в «Фильтр · N»; смена категории/подкатегории снимает вид
    assert.ok(sp.includes('city, f.from, f.to, f.delivery, f.conds) && kindMatch(js(j, "kind"), f.kind)')
      && sp.includes('f.cat = cat; f.sub = sub; f.kind = kind;') && sp.includes('|| !kind.isEmpty()') && sp.includes('void resetAll() { cat = ""; sub = ""; kind = "";'),
      'фильтр 10.79: вид отбирает ленту, копируется, сбрасывается');
    const rp = fnx(sp, 'refreshPanel');
    assert.ok(rp.indexOf('subBox.setVisibility(') < rp.indexOf('kindBox.removeAllViews();') && rp.includes('IvaKindPatch.forSub(IvaKindPatch.list(), draft.cat, draft.sub)')
      && rp.includes('facetVisible(kindKeys, kindCounts, draft.kind)') && rp.includes('nativeMatch(j, "cat", draft.cat, draft.sub, query) || !extraMatch(j, city, noKind)')
      && rp.includes('draft.cat = key.equals(draft.cat) ? "" : key; draft.sub = ""; draft.kind = "";') && rp.includes('draft.sub = key.equals(draft.sub) ? "" : key; draft.kind = "";'),
      'фильтр 10.79: «Вид» после «Подкатегории» — только непустые с числом; смена категории/подкатегории снимает вид');
    assert.ok(fnx(sp, 'applyDraft').includes('applied.kind = d.kind;') && fnx(sp, 'refreshChips').includes('applied.kind = f.kind;')
      && fnx(sp, 'applySearch').includes('applied.kind = kindFor(a, applied.kind);') && fnx(sp, 'refreshFilterButton').includes('activeCount(f.cat, f.sub, f.kind,'),
      'фильтр 10.79: вид применяется кнопкой и пузырями, учитывается в «Фильтр · N», чужой вид снимается');
    // 7. SQL: 15 категорий, ключи прежние, «Другое» везде, виды и tools.kind, просмотр отдельно от переноса
    for (const k of ['power', 'cutting', 'construction', 'surface', 'garden', 'cleaning', 'climate', 'generators', 'compressors', 'welding', 'plumbing', 'measuring', 'moving', 'painting', 'auto'])
      assert.ok(new RegExp(`\\('${k}',\\s+'[^']+',\\s+\\d+,`).test(sql), `SQL 10.79: нет категории ${k}`);
    assert.ok(!/delete from public\.(cats|subcats|tools)/i.test(sql) && !/update public\.tools/i.test(sql) && /update public\.subcats set active = false where cat = 'power' and key in \('cordless', 'corded'\);/.test(sql),
      'SQL 10.79: шаг 1 ничего не удаляет и объявления не меняет; питание — выключается');
    assert.ok(sql.includes('create table if not exists public.iva_kinds') && sql.includes('create policy iva_kinds_read on public.iva_kinds for select to anon, authenticated using (true);')
      && sql.includes('using (public.fn_is_chief()) with check (public.fn_is_chief());') && sql.includes('foreign key (kind) references public.iva_kinds(key) on update cascade on delete set null;')
      && sql.includes('revoke all on function public.iva_tree_plan() from public, anon, authenticated;'),
      'SQL 10.79: виды читают все, меняет главный админ; вид объявления — только из справочника; план закрыт от гостей');
    assert.ok(/select \* from public\.iva_tree_plan\(\) order by old_cat, name;/.test(sql) && mv.includes('from public.iva_tree_plan() p') && mv.includes('ЗАПУСКАТЬ ТОЛЬКО ПОСЛЕ «да» владельца'),
      'SQL 10.79: просмотр переноса — в шаге 1, перенос — отдельный файл по тому же плану');
    assert.ok(chk.includes('"$PG_PY" tools/category-tree-stand.py'), 'check.sh: стенд дерева категорий');
    assert.ok((tp.match(/eq\("(вид|фильтр) 10\.79: /g) || []).length >= 17, 'IvaPatchTest: проверки 10.79');
  }
  // 10.78 (просьба владельца 14.10.2026): таймер «чч.мм.сс» (каждую секунду); раздел настроек «Срок аренды»; в кабинете и у админа
  // нет «Позвонить», «Написать в МАКС», «Адрес выдачи»; над «О приложении» — оранжевая «Обновить до версии N» (только если
  // выпущенный релиз новее установленного), ещё выше у админов — «Чат поддержки» (переписка с создателем); у создателя в чатах
  // с админами справа — «Админ» вместо картинки инструмента
  {
    const rp = R('android/patches/IvaRentPatch.java'), np = R('android/patches/IvaNotifyPatch.java'), jc = R('android/patches/IvaClientPatch.java');
    const cp = R('android/patches/IvaChatPatch.java'), tp = R('android/patches/test/IvaPatchTest.java'), wf = R('.github/workflows/build-apk.yml');
    const ap = R('android/patches/apply.py');
    const fnx = (src, name) => { const mm = new RegExp(`static (?:final )?[\\w.<>, \\[\\]]+ ${name}\\(`).exec(src); assert.ok(mm, `нет ${name}`); return src.slice(mm.index, src.indexOf('\n    }\n', mm.index)); };
    // 1. таймер: часы.минуты.секунды, часы не ограничены сутками; тик — со сменой секунды
    assert.ok(/String\.format\(Locale\.ROOT, "%02d\.%02d\.%02d", s \/ 3600, \(s \/ 60\) % 60, s % 60\)/.test(fnx(rp, 'hms'))
      && fnx(rp, 'seconds').includes('return now < end ? (end - now + 999) / 1000 : (now - end) / 1000;')
      && fnx(rp, 'timer').includes('hms(seconds(end, now))') && !/\bdhm\(|\bminutes\(/.test(rp)
      && rp.includes('l.setText(TIMER_LABEL + " (чч.мм.сс)");') && !rp.includes('(дд.чч.мм)"'),
      'таймер 10.78: «чч.мм.сс», часы без ограничения сутками, ровно в срок — 00.00.00');
    const nt = fnx(rp, 'nextTick');
    assert.ok(nt.includes('long best = 1000;') && nt.includes('long d = Math.abs(e.end - now) % 1000;') && nt.includes('return Math.max(200, best + 20);')
      && fnx(rp, 'timerView').includes('box.postDelayed(this, nextTick(es, now));'),
      'таймер 10.78: тикает каждую секунду (со сменой секунды), не чаще раза в 200 мс, только на экране');
    // 2. раздел настроек
    assert.ok(rp.includes('static final String HEAD_TITLE = "Срок аренды";') && fnx(rp, 'settingsSection').includes('ht.setText(HEAD_TITLE);'),
      'настройки 10.78: раздел называется «Срок аренды»');
    // 3. кабинет: контакты скрыты у всех, строки не удаляются
    const pc = fnx(jc, 'profileContacts');
    assert.ok(jc.includes('static final boolean PROFILE_CONTACTS_SHOW = false;') && pc.includes('boolean show = PROFILE_CONTACTS_SHOW;')
      && !pc.includes('isClient(') && !/removeView|removeAllViews|setText|setOnClick/.test(pc),
      'кабинет 10.78: «Позвонить», «Написать в МАКС», «Адрес выдачи» скрыты и у админа; только скрываются');
    // 4. «Обновить до версии N»: выпущенный релиз (app_releases, как у сайта), штатная кнопка, только если новее установленного
    const rel = fnx(np, 'released'), apr = fnx(np, 'applyRelease'), es = fnx(np, 'ensureSupport');
    assert.ok(np.includes('static final String RELEASE_PATH = "/rest/v1/app_releases?select=version_code,version_name,apk_url&order=version_code.desc&limit=1";')
      && rel.includes('nativeCall(IvaClientPatch.SB_URL + RELEASE_PATH, "GET", null, false, null);') && rel.includes('String body = httpGet(UPDATE_URL);')
      && rel.includes('return null;                                              // нет сети / сбой базы — не знаем'),
      'обновление 10.78: выпущенная версия — последняя строка app_releases (без входа, только номер и ссылка); строк нет — app-update.json');
    assert.ok(fnx(np, 'updateWanted').includes('return released > 0 && installed > 0 && released > installed && apk != null && !apk.isEmpty();')
      && apr.includes('boolean want = updateWanted(code, installedCode(a), apk);') && apr.includes('if (old != null && (!want || relShown != code')
      && apr.includes('View b = updateButton(a, code, shown, apk);')   // 10.80: кнопка своя (вид как у штатной), см. блок 10.80
      && apr.includes('if (code <= 0) return;') && !/onButtonTap|downloadApk|showReadyDialog/.test(np) && has(dex14, 'showButtonOnUi'),
      'обновление 10.78: штатная оранжевая кнопка над «О приложении»; релиз не новее установленного — кнопки нет; сами не качаем');
    const ra = fnx(np, 'releaseApk');
    assert.ok(ra.includes('if (u.matches("ProkatInstrumenta-[0-9]+\\\\.[0-9]+\\\\.apk")) return APK_BASE + u;') && ra.includes('!u.contains("..")')
      && np.includes('static final String APK_BASE = "https://eclipsru.github.io/Arenda-Nvk/";') && np.includes('static final String APK_RAW = "https://github.com/eclipsru/Arenda-Nvk/raw/";'),
      'обновление 10.78: качаем только APK этого проекта (как проверка в базе)');
    assert.ok(fnx(np, 'checkUpdate').includes('JSONObject j = released();') && fnx(np, 'openUpdate').includes('if (old == null) releaseCheck(a, true);')
      && fnx(np, 'releaseCheck').includes('if (relBusy || (!force && relAt > 0 && now - relAt < RELEASE_EVERY_MS)) return;') && np.includes('static final long RELEASE_EVERY_MS = 60000L;'),
      'обновление 10.78: уведомление и переход по нему — тот же выпущенный релиз; база — не чаще раза в минуту');
    // 5. «Чат поддержки»: только вошедшим админам (не создателю), над кнопкой обновления / «О приложении», открывает переписку с создателем
    assert.ok(np.includes('try { cabinet(a, tab); } catch (Throwable ignore) {}') && fnx(np, 'cabinet').includes('ensureSupport(a);')
      && fnx(np, 'supportFor').includes('return logged && admin && !IvaChatPatch.OWNER.equalsIgnoreCase(')
      && es.includes('int at = box.indexOfChild(upd != null && upd.getParent() == box ? upd : ver);') && es.includes('row.setVisibility(want ? View.VISIBLE : View.GONE);')
      && fnx(np, 'supportRow').includes('ok = IvaChatPatch.openWith(a, IvaChatPatch.OWNER);') && fnx(np, 'supportRow').includes('drawable(a, "bg_card")')
      && cp.includes('static final String OWNER = "eclips.ru@mail.ru";'),
      'кабинет 10.78: «Чат поддержки» — админам, выше «Обновить…» и «О приложении», переписка с создателем, в стиле строк кабинета');
    assert.ok(!/AlarmManager|WorkManager|customer_profiles|passport|phone|surname|com\.google/.test(np), 'кабинет 10.78: без личных данных и сервисов Google');
    // 6. «Админ» у создателя
    const row = fnx(cp, 'chatRow');
    assert.ok(fnx(cp, 'adminMark').includes('return superView(me) && !p.isEmpty() && !p.equals(low(me)) && admins != null && admins.contains(p);')
      && row.indexOf('} else if (adminMark(my, c.partner, adminEmails())) {') > 0 && row.indexOf('} else if (adminMark(my, c.partner, adminEmails())) {') < row.indexOf('toolPic(a, tool, c.tool, LIST_TOOL_DP)')
      && fnx(cp, 'renderHeader').includes('if (adminMark(myH, openPartner, adminEmails())) {'),
      'чат 10.78: у создателя в переписке с админом справа «Админ» вместо картинки инструмента (и в шапке)');
    const ab = fnx(cp, 'adminBadge');
    assert.ok(ab.includes('setBg(a, t, "bg_chip_on");') && ab.includes('text(a, ADMIN_BADGE, 12, "orange", true)') && !/#[0-9A-Fa-f]{6}|0x[0-9A-Fa-f]{8}/.test(ab)
      && cp.includes('static final String ADMIN_BADGE = "Админ", SUPPORT_SUB = "Чат поддержки";') && has(arsc14, 'bg_chip_on') && has(arsc14, 'ic_chat'),
      'чат 10.78: «Админ» — таблетка приложения (с 10.96 рамкой), без новых цветов');
    assert.ok(!/IvaRentPatch|cabinet|adminMark/.test(ap), '10.78: новых вставок в smali нет');
    assert.ok((tp.match(/eq\("(аренда|кабинет|чат) 10\.78: /g) || []).length >= 14, 'IvaPatchTest: проверки 10.78');
  }
  // 10.77 (просьба владельца 14.10.2026): «Аренда» в настройках админа — конец аренды «Сутки» (14:30 → 14:30) или «До закрытия
  // пункта» (время закрытия по дням недели, выходной → следующий рабочий день); смена режима — и для выданных; напоминание клиенту
  // по этому концу; живой таймер «дд.чч.мм» в выданных активных заявках у клиента и у админа
  {
    const rp = R('android/patches/IvaRentPatch.java'), op = R('android/patches/IvaOrderPatch.java'), np = R('android/patches/IvaNotifyPatch.java');
    const tp = R('android/patches/test/IvaPatchTest.java'), wf = R('.github/workflows/build-apk.yml'), ap = R('android/patches/apply.py');
    const ck = R('.github/workflows/check.yml'), sql = R('supabase/migrations/20261014_rent_end_mode.sql'), chk = R('tools/check.sh');
    const fnx = (src, name) => { const mm = new RegExp(`static (?:final )?[\\w.<>, \\[\\]]+ ${name}\\(`).exec(src); assert.ok(mm, `нет ${name}`); return src.slice(mm.index, src.indexOf('\n    }\n', mm.index)); };
    // расчёт: «Сутки» — +дни×24 ч; «До закрытия» — в часовом поясе проката, день выдачи + дни, время закрытия дня, выходной — дальше
    const end = fnx(rp, 'end');
    assert.ok(end.includes('long plain = rentedAt + days * DAY_MS;') && end.includes('if (r == null || !r.byClose() || !anyOpen(r.close)) return plain;')
      && end.includes('Calendar.getInstance(TimeZone.getTimeZone(') && end.includes('c.add(Calendar.DAY_OF_MONTH, days);')
      && /int m = r\.close\[IvaNotifyPatch\.dowMon\(c\.get\(Calendar\.DAY_OF_WEEK\)\)\];\s+if \(m >= 0\) \{/.test(end) && end.includes('for (int i = 0; i < 7; i++) {'),
      'аренда 10.77: конец — «Сутки» или до закрытия пункта по дням недели (выходной — следующий рабочий день), пояс проката');
    assert.ok(/if \(v < -1 \|\| v > 1439\) return defClose\(\);/.test(fnx(rp, 'parseClose')) && /if \(xs\.length != 7\) return defClose\(\);/.test(fnx(rp, 'parseClose')),
      'аренда 10.77: время закрытия — 7 дней, 00:00–23:59 или выходной; иначе 19:00');
    // одна формула — и для таймера, и для напоминания клиенту (10.76)
    assert.ok(np.includes('r.end = IvaRentPatch.end(rentedAt, e.getKey(), rule);') && !/r\.end = rentedAt \+/.test(np)
      && fnx(np, 'checkRent').includes('o.optInt("days", 0), at, IvaRentPatch.ruleOf(o))) {'),
      'аренда 10.77: напоминание клиенту — по режиму проката (та же формула, что у таймера)');
    assert.ok(fnx(rp, 'group').includes('IvaNotifyPatch.rentEnds(pid, oid, o.optString("tools_text", ""), o.optInt("days", 0),') && fnx(rp, 'group').includes('ruleOf(o)'),
      'аренда 10.77: таймер — те же сроки инструментов, что напоминание');
    // настройки: только админам, «Срок аренды» под «Уведомлениями» (с 10.97 — отдельной страницей), сбой не ломает настройки
    // 10.98: «Срок аренды» — на странице «Настройка проката» (строка — только у админов, под «Уведомлениями»)
    const os = fnx(np, 'openSettings'), shp = R('android/patches/IvaShopPatch.java');
    assert.ok(os.includes('if (admin) try { IvaShopPatch.settingsRow(a, page, cText, cGray, cOrange); } catch (Throwable ignore) {}')
      && os.indexOf('IvaShopPatch.settingsRow') > os.indexOf('page.addView(head, hl);') && !os.includes('IvaRentPatch.settingsSection')
      && fnx(shp, 'openPage').includes('try { IvaRentPatch.settingsSection(a, list, cText, cGray, cOrange); } catch (Throwable ignore) {}'),
      'аренда 10.77: «Аренда» — только у админов, под «Уведомлениями» (10.98 — в «Настройка проката»)');
    const ss = fnx(rp, 'settingsSection');
    assert.ok(ss.includes('IvaChiefPatch.subPage(a, HEAD_TITLE, body, ref[0]);') && !ss.includes('body.setVisibility(') && ss.includes('drawable(a, "bg_card")') && ss.includes('IvaNotifyPatch.tint(ds, cOrange);')
      && ss.includes('closeBox.setVisibility(byClose ? View.VISIBLE : View.GONE);') && ss.includes('"Хотя бы один день должен быть рабочим"')
      && ss.includes('status.postDelayed(doSave, 700)') && ss.includes('st.tz = TimeZone.getDefault().getID();') && ss.includes('|| edited[0]) return;'),
      'аренда 10.77/10.97: отдельной страницей, в стиле приложения; время закрытия — только в режиме «до закрытия»; сохраняется в базу; загрузка не затирает правку');
    assert.ok(rp.includes('DAY_TITLE = "Сутки"') && rp.includes('CLOSE_TITLE = "До закрытия пункта"') && rp.includes('static final String MODE_DAY = "day", MODE_CLOSE = "close";'),
      'аренда 10.77: два режима');
    // таймер: клиенту — карточка и подробности; админу — карточка заявки и подробности; только выданные активные
    assert.ok(op.includes('IvaRentPatch.loadMine(a);') && op.includes('if (!archived) try { IvaRentPatch.clientCard(a, c, r); } catch (Throwable ignore) {}')
      && op.includes('try { IvaRentPatch.admCard(a, cards.get(i), (JSONObject) ((List<?>) parts).get(mine.get(i))); } catch (Throwable ignore) {}')
      && op.includes('try { IvaRentPatch.admNeed(a, (List<?>) parts); } catch (Throwable ignore) {}')
      && op.includes('try { IvaRentPatch.sheet(a, body, r, adm, archived); } catch (Throwable ignore) {}'),
      'таймер 10.77: у клиента и у админа — на карточке и в подробностях; сбой не ломает «Заявки»');
    assert.ok(fnx(rp, 'clientCard').includes('IvaOrderPatch.ordActive(r, false)') && fnx(rp, 'sheet').includes('IvaOrderPatch.ordActive(r, archived)')
      && /"rented"\.equalsIgnoreCase\(part\.optString\("status", ""\)\.trim\(\)\) \? byPart\.get/.test(fnx(rp, 'admCard'))
      && fnx(rp, 'sheet').includes('"rented".equalsIgnoreCase(IvaOrderPatch.ordVal(r.get("status")))'),
      'таймер 10.77: только активные заявки «в аренде» (у админа — статус его части)');
    const tv = fnx(rp, 'timerView');
    assert.ok(tv.includes('if (box.getWindowToken() != null) box.postDelayed(this, nextTick(es, now));') && tv.includes('v.removeCallbacks(tick);')
      && tv.includes('setTextColor(now < e.end ? cOrange : cRed)'),
      'таймер 10.77: тикает только на экране, после срока — красным');
    const an = fnx(rp, 'admNeed');
    assert.ok(an.includes('if (admLoading || now < admMissingUntil) return;') && an.includes('if (!(lack && now - admAt > 5000) && now - admAt < 60000) return;')
      && an.includes('admMissingUntil = System.currentTimeMillis() + 10 * 60000L;') && an.includes('changed = !sig(byPart).equals(sig(g));'),
      'таймер 10.77: админ — не чаще раза в минуту (новая выдача — через 5 с); без SQL — тихо; перерисовка только при изменении');
    // сборка и проверка
    assert.ok(/IvaPushPatch\.java android\/patches\/IvaRentPatch\.java(?: android\/patches\/Iva\w+Patch\.java)* 2>&1/.test(wf) && ck.includes('android/patches/IvaRentPatch.java'), 'аренда 10.77: IvaRentPatch компилируется в сборку и в проверку');
    assert.ok(!ap.includes('IvaRentPatch'), 'аренда 10.77: новых вставок в smali нет');
    assert.ok(!/AlarmManager|WorkManager|customer_profiles|passport|surname|com\.google/.test(rp) && !/AlarmManager|WorkManager|customer_profiles|passport|phone|surname/.test(np),
      'аренда 10.77: без будильников и личных данных');
    // SQL: настройки меняет только админ и только свои, таблица закрыта, функции — вошедшим, ничего не удаляется
    assert.ok(sql.includes('ALTER TABLE public.iva_rent_settings ENABLE ROW LEVEL SECURITY;') && sql.includes('REVOKE ALL ON TABLE public.iva_rent_settings FROM public, anon, authenticated;')
      && /where a\.active and a\.role in \('admin', 'chief'\) and lower\(a\.email\) = em\) then\s+raise exception/.test(sql)
      && sql.includes("on conflict (email) do update set mode = excluded.mode")
      && (sql.match(/REVOKE ALL ON FUNCTION public\.iva_\w+\([^)]*\) FROM public, anon;/g) || []).length === 4
      && (sql.match(/GRANT EXECUTE ON FUNCTION public\.iva_\w+\([^)]*\) TO authenticated;/g) || []).length === 4
      && sql.includes("and lower(btrim(coalesce(p.owner_email, ''))) = lower(btrim(auth.jwt() ->> 'email'))")
      && sql.includes("and lower(coalesce(o.user_email, '')) = lower(auth.jwt() ->> 'email')")
      && !/DROP\s+(TABLE|COLUMN)|DELETE\s+FROM|TRUNCATE/i.test(sql.replace(/^\s*--.*$/mg, ''))
      && sql.includes('CREATE TRIGGER trg_iva_part_rented_at BEFORE INSERT OR UPDATE ON public.order_parts'),
      'SQL 10.77: только админ и свои; таблица закрыта; гостям — нет; включает 10.76; ничего не удаляется');
    assert.ok(!/client_name|phone|address/.test(sql.slice(sql.indexOf('CREATE OR REPLACE FUNCTION public.iva_admin_rentals'), sql.indexOf('-- 6. Проверка'))),
      'SQL 10.77: админу — без имени, телефона и адреса клиента');
    assert.ok(chk.includes('tools/rent-mode-stand.py'), 'SQL 10.77: стенд на PostgreSQL — в check.sh');
    assert.ok((tp.match(/eq\("аренда 10\.77: /g) || []).length >= 19, 'IvaPatchTest: проверки 10.77 (4 — с 10.78 под меткой 10.78)');
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
    assert.ok((tp.match(/eq\("связь 10\.76: /g) || []).length >= 12 && (tp.match(/eq\("аренда 10\.76: /g) || []).length >= 16, 'IvaPatchTest: проверки 10.76');
  }
  // 10.97 (правило владельца 16.10.2026): каждый пункт «Настроек» открывается ОТДЕЛЬНОЙ страницей — ничего не раскрывается на месте
  {
    const cp = R('android/patches/IvaChiefPatch.java'), np97 = R('android/patches/IvaNotifyPatch.java'),
      rp97 = R('android/patches/IvaRentPatch.java'), pk97 = R('android/patches/IvaPickupPatch.java'), dev = R('docs/README-DEV.md');
    const fn97 = (src, name) => { const m = new RegExp('static [\\w.<>\\[\\]]+ ' + name + '\\(').exec(src); assert.ok(m, 'нет ' + name);
      return src.slice(m.index, src.indexOf('\n    }\n', m.index)); };
    const sp = fn97(cp, 'subPage');
    assert.ok(sp.includes('Page p = page(a, title, null, false);') && sp.includes('((ViewGroup) vp).removeView(content);')
      && sp.includes('p.list.addView(content, new LinearLayout.LayoutParams(-1, -2));') && sp.includes('if (has) try { onFocus.run(); } catch (Throwable ignore) {}'),
      'настройки 10.97: IvaChiefPatch.subPage — отдельная страница как «Пункт выдачи», содержимое переносится целиком (повторное открытие работает)');
    // все пункты «Настроек»: строка в openSettings + строки, которые вставляют IvaPickupPatch.settingsRow и IvaRentPatch.settingsSection
    // 10.98: + «Настройка проката» (IvaShopPatch: строка, её страница и «Данные проката»)
    const sh97 = R('android/patches/IvaShopPatch.java');
    const os97 = fn97(np97, 'openSettings'), rows = [os97, fn97(rp97, 'settingsSection'), fn97(pk97, 'settingsRow'),
      fn97(sh97, 'settingsRow'), fn97(sh97, 'openPage'), fn97(sh97, 'openData')];
    for (const r of rows) assert.ok(!/setVisibility\(\s*open\b|\.setRotation\(open|rotation\(open|развернуть|свернуть/.test(r),
      'настройки 10.97: в «Настройках» ничего не раскрывается и не сворачивается на месте');
    assert.ok(/IvaChiefPatch\.subPage\(a, NOTIF_TITLE, body,/.test(os97) && /IvaChiefPatch\.subPage\(a, HEAD_TITLE, body,/.test(rows[1])
      && rows[2].includes('openPage(a)') && fn97(pk97, 'openPage').includes('IvaChiefPatch.page(a, ROW_TITLE, PAGE_SUB, false)')
      && rows[3].includes('openPage(a, cText, cGray, cOrange)') && rows[4].includes('IvaChiefPatch.page(a, ROW_TITLE, null, false)')
      && rows[4].includes('openData(a)') && rows[5].includes('u.page = IvaChiefPatch.page(a, DATA_TITLE, null, false);'),
      'настройки 10.97: «Уведомления», «Пункт выдачи», «Срок аренды» — каждый открывается своей страницей');
    // новый пункт в «Настройках» — только строкой, открывающей страницу: всё, что вставляется в page, — это head/строки пунктов
    const pageAdds = (os97.match(/page\.addView\((\w+)/g) || []).map(x => x.replace('page.addView(', ''));
    assert.ok(pageAdds.length === 1 && pageAdds[0] === 'head' && (os97.match(/\(a, page, cText, cGray, cOrange\)/g) || []).length === 1,   // 10.98: одна строка «Настройка проката» вместо двух
      'настройки 10.97: в «Настройки» вставляются только строки пунктов (новый пункт — строкой со своей страницей)');
    assert.ok(dev.includes('Каждый пункт «Настроек» — отдельной страницей (правило владельца 16.10.2026, приложение с 10.97)'),
      'настройки 10.97: правило записано в README-DEV');
  }
  // 10.98 (просьба владельца 16.10.2026): «Настройка проката» (только админам) — «Данные проката», «Пункты выдачи», «Срок аренды»;
  // «Мои данные» — только Фамилия, Имя, Отчество, телефон, мессенджеры; аватарка у всех — кругом без краёв, крупнее, просмотр, выбор области
  {
    const sh = R('android/patches/IvaShopPatch.java'), av = R('android/patches/IvaAvatarPatch.java'), cl = R('android/patches/IvaClientPatch.java'),
      ip = R('android/patches/IvaPatch.java'), np = R('android/patches/IvaNotifyPatch.java'), pk = R('android/patches/IvaPickupPatch.java'),
      rp = R('android/patches/IvaRentPatch.java'), ap = R('android/patches/apply.py'), tp = R('android/patches/test/IvaPatchTest.java'),
      wf = R('.github/workflows/build-apk.yml'), ck = R('.github/workflows/check.yml'), sq = R('supabase/migrations/20261024_rental_data.sql');
    const fn = (src, name) => { const m = new RegExp('static [\\w.<>\\[\\]]+ ' + name + '\\(').exec(src); assert.ok(m, 'нет ' + name);
      return src.slice(m.index, src.indexOf('\n    }\n', m.index)); };
    for (const s2 of [wf, ck]) assert.ok(s2.includes('android/patches/IvaAvatarPatch.java') && s2.includes('android/patches/IvaShopPatch.java'),
      'прокат 10.98: IvaAvatarPatch.java и IvaShopPatch.java — в списке javac сборки и проверки');
    // без новых вставок в smali: всё из IvaPatch.onTab, IvaClientPatch.onProfile и openSettings
    assert.ok(!/IvaAvatarPatch|IvaShopPatch/.test(ap)
      && ip.includes('try { IvaAvatarPatch.onTab(a, tab); } catch (Throwable ignore) {}') && ip.includes('try { IvaShopPatch.onTab(a, tab); } catch (Throwable ignore) {}')
      && cl.includes('try { IvaAvatarPatch.onProfile(a); } catch (Throwable ignore) {}'),
      'прокат 10.98: новых вставок в smali нет (onTab, onProfile, openSettings)');
    // «Настройка проката» — только админам; внутри три пункта, каждый своей страницей; «Пункты выдачи» переименован
    const os = fn(np, 'openSettings'), op = fn(sh, 'openPage');
    assert.ok(os.includes('if (admin) try { IvaShopPatch.settingsRow(a, page, cText, cGray, cOrange); } catch (Throwable ignore) {}')
      && !os.includes('IvaPickupPatch.settingsRow') && !os.includes('IvaRentPatch.settingsSection')
      && op.indexOf('openData(a)') > 0 && op.indexOf('openData(a)') < op.indexOf('IvaPickupPatch.settingsRow(a, list,')
      && op.indexOf('IvaPickupPatch.settingsRow(a, list,') < op.indexOf('IvaRentPatch.settingsSection(a, list,')
      && sh.includes('static final String ROW_TITLE = "Настройка проката"') && sh.includes('DATA_TITLE = "Данные проката"')
      && pk.includes('static final String ROW_TITLE = "Пункты выдачи"') && rp.includes('«Настройки → Настройка проката → Пункты выдачи»'),
      'прокат 10.98: «Настройка проката» только у админов — «Данные проката», «Пункты выдачи», «Срок аренды»');
    // «Данные проката»: аватарка вверху, поля с названиями над полями в порядке владельца
    const od = fn(sh, 'openData');
    const order = ['IvaAvatarPatch.circleBox(a, IvaAvatarPatch.PAGE_DP', 'field(u, list, L_NAME', 'field(u, list, L_ADDR', 'field(u, list, L_IP',
      'field(u, list, L_TEL', 'list.addView(label(u, L_TIME));', 'list.addView(label(u, L_DAYS));'];
    for (let i = 1; i < order.length; i++) assert.ok(od.indexOf(order[i - 1]) >= 0 && od.indexOf(order[i - 1]) < od.indexOf(order[i]), 'прокат 10.98: порядок полей — ' + order[i]);
    assert.ok(fn(sh, 'field').indexOf('list.addView(label(u, lbl));') < fn(sh, 'field').indexOf('list.addView(f,')
      && sh.includes('IvaPickupPatch.time(a, "Начало работы"') && sh.includes('IvaSearchPatch.chip(a, DAYS[i], false, false)')
      && sh.includes('try { ne = IvaCensorPatch.fieldError(u.d.name); } catch (Throwable ignore) {}'),
      'прокат 10.98: названия над полями; время — выбор часов; дни — чипы; название проверяется цензурой');
    // запись — только своя строка admins (как штатное «Сохранить»), без личных данных клиентов
    assert.ok(sh.includes('"/rest/v1/admins?email=eq." + IvaAvatarPatch.encode(me(a)), "PATCH"') && !/customer_profiles|passport|surname|select=\*|"DELETE"|"POST"/.test(sh)
      && !/customer_profiles|passport|surname|select=\*|"DELETE"/.test(av),
      'прокат 10.98: пишется только своя строка admins; личных данных клиентов не читаем');
    // «Мои данные»: прячем перенесённые поля, ФИО — тремя полями (в full_name уходит одной строкой)
    const md = fn(sh, 'myData');
    assert.ok(md.includes('new String[]{"adCompany", "adOrgAddr", "adAddr", "adHours"}') && md.includes('{"Фамилия", "Имя", "Отчество"}')
      && md.includes('fio.setText(joinFio(sn, nn, on));') && md.includes('fio.setVisibility(View.GONE);') && md.includes('"Номер телефона"')
      && fn(sh, 'syncNative').includes('"adCompany"'),
      'прокат 10.98: «Мои данные» — только Фамилия, Имя, Отчество, телефон, мессенджеры (данные проката — в скрытых штатных полях)');
    // аватарка: выбор области кругом, просмотр, круг без краёв, крупнее
    assert.ok(av.includes('public static final class Picker extends android.app.Fragment') && av.includes('f.startActivityForResult(Intent.createChooser(i, "Выберите фото"), REQ);')
      && av.includes('hole.setFillType(Path.FillType.EVEN_ODD);') && av.includes('static final int OUT_PX = 512')
      && av.includes('if (hasPhoto(photo.get())) return;') && av.includes('HEAD_DP = 72, PAGE_DP = 120')
      && fn(av, 'onProfile').includes('frame.setBackground(hole(a, iv));') && fn(av, 'dataPage').includes('av.setBackground(hole(a, av));')
      && cl.includes('circleBox.setBackground(IvaAvatarPatch.hole(a, avatarImage));') && cl.includes('static final int AVATAR_DP = 120;')
      && fn(av, 'saveAdminPhoto').includes('body.put("avatar", url);'),
      'аватар 10.98: круг без краёв (фон только без фото), 72/120dp, просмотр, выбор области и размера круга');
    assert.ok(cl.includes('if (!ok) pickAvatarPlain();') && fn(cl, 'saveAvatarPath').includes('if (p.optString("given_name", "").trim().isEmpty()) return NEED_NAME;'),
      'аватар 10.98: прежний выбор фото — запасной; фото клиента без имени в записи не сохраняется');
    assert.ok(sq.includes('alter table public.admins add column if not exists org_ip text;') && sq.includes('work_days between 0 and 127')
      && !/drop table|truncate|delete from|drop policy|create policy/i.test(sq.replace(/^--.*$/gm, '')) && sq.includes("notify pgrst, 'reload schema';"),
      'SQL 10.98: только новые колонки admins с проверками; политики и данные не трогаются');
    assert.ok((tp.match(/eq\("прокат 10\.98: /g) || []).length >= 15 && (tp.match(/eq\("аватар 10\.98: /g) || []).length >= 6, 'IvaPatchTest: проверки 10.98');
  }
  // 10.99 (просьба владельца 16.10.2026): «Телефон пункта выдачи» в карточке и в форме; «Изменить» — карточка растягивается вниз, поля в ней
  {
    const pk = R('android/patches/IvaPickupPatch.java'), tp = R('android/patches/test/IvaPatchTest.java'), sq = R('supabase/migrations/20261025_pickup_tel.sql'),
      st = R('tools/pickup-stand.py');
    const fn = (src, name) => { const m = new RegExp('static [\\w.<>\\[\\]]+ ' + name + '\\(').exec(src); assert.ok(m, 'нет ' + name);
      return src.slice(m.index, src.indexOf('\n    }\n', m.index)); };
    const dl = fn(pk, 'drawList'), bf = fn(pk, 'buildForm'), ec = fn(pk, 'editInCard');
    assert.ok(dl.includes('telLine(p.tel)') && dl.includes('editInCard(u, copy(p), c, info)') && !dl.includes('showForm(u, copy(p), false)')
      && bf.indexOf('label(u, "Адрес")') < bf.indexOf('label(u, L_TEL)') && bf.indexOf('label(u, L_TEL)') < bf.indexOf('label(u, "График")')
      && bf.includes('tel.setInputType(InputType.TYPE_CLASS_PHONE);') && bf.includes('te = telDb ? telErr(p.tel) : ""')
      && bf.includes('write(u, "PATCH", idPath(p.id), body(p, telDb), "Пункт сохранён")') && pk.includes('static final String L_TEL = "Телефон пункта выдачи";'),
      'пункт 10.99: телефон — в карточке и полем «Телефон пункта выдачи» под адресом; без SQL — как в 10.95');
    assert.ok(ec.includes('info.setVisibility(View.GONE);') && ec.includes('animateHeight(card, from, wrapHeight(card),')
      && ec.includes('animateHeight(card, start, to,') && fn(pk, 'animateHeight').includes('lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;')
      && fn(pk, 'showForm').includes('animateHeight(f, 0, wrapHeight(f), null);'),
      'пункт 10.99: «Изменить» — карточка плавно растягивается вниз, «Отмена» — сжимается; «Добавить» — тоже раскрывается');
    // телефон пункта — данные проката, не клиента: личные данные клиентов в файле по-прежнему не читаются
    assert.ok(!/customer_profiles|passport|phone|surname|select=\*/.test(pk) && pk.includes('static final String COLS_TEL = COLS + ",tel";'),
      'пункт 10.99: колонка tel пункта; личных данных клиентов нет');
    assert.ok(sq.includes('ALTER TABLE public.iva_pickup_points ADD COLUMN IF NOT EXISTS tel text NOT NULL DEFAULT') && sq.includes('GRANT UPDATE (tel) ON public.iva_pickup_points TO authenticated;')
      && !/DROP TABLE|TRUNCATE|DELETE FROM|DROP POLICY|CREATE POLICY|GRANT (SELECT|DELETE)/i.test(sq.replace(/^--.*$/gm, ''))
      && st.includes("TEL = rd('supabase/migrations/20261025_pickup_tel.sql')"),
      'SQL 10.99: только колонка tel с проверкой и право писать её; политики не трогаются; стенд PostgreSQL');
    assert.ok((tp.match(/eq\("пункт 10\.99: /g) || []).length >= 6, 'IvaPatchTest: проверки 10.99');
  }
  // 10.100 (решение владельца 16.10.2026): свой звук у всех уведомлений приложения — файл владельца из android/sounds
  {
    const sp = R('android/patches/IvaSoundPatch.java'), np = R('android/patches/IvaNotifyPatch.java'), tp = R('android/patches/test/IvaPatchTest.java'),
      ax = R('android/patches/axml_sound.py'), wf = R('.github/workflows/build-apk.yml'), ck = R('.github/workflows/check.yml'),
      chk = R('tools/check.sh'), ap = R('android/patches/apply.py');
    const fn = (src, name) => { const m = new RegExp('static [\\w.<>\\[\\]]+ ' + name + '\\(').exec(src); assert.ok(m, 'нет ' + name);
      return src.slice(m.index, src.indexOf('\n    }\n', m.index)); };
    assert.ok(fs.existsSync(path.join(ROOT, 'android/sounds/iva_notify.mp3')) && fs.existsSync(path.join(ROOT, 'android/sounds/ringtone-sms-notification.mp3')),
      'звук 10.100: файл владельца на месте, в APK — его копия без тегов android/sounds/iva_notify.mp3');
    // провайдер: только чтение одного файла; без MediaStore, внешней памяти и file://
    assert.ok(sp.includes('static final String AUTH = "ru.prokatnvsk.app.ivasound";') && sp.includes('static final String ASSET = "iva_notify.mp3";')
      && fn(sp, 'servable').includes('return ("/" + FILE).equals(path) && (mode == null || "r".equals(mode));')
      && sp.includes('ParcelFileDescriptor.open(cached(getContext()), ParcelFileDescriptor.MODE_READ_ONLY)')
      && (sp.match(/throw new UnsupportedOperationException\("только чтение"\);/g) || []).length === 3
      && !/MediaStore|getExternal|WRITE_EXTERNAL|file:\/\/|Uri\.fromFile|MODE_READ_WRITE|MODE_WRITE/.test(sp.replace(/\/\*[\s\S]*?\*\//g, '')),
      'звук 10.100: провайдер отдаёт только звук и только на чтение; внешней памяти и file:// нет');
    assert.ok(fn(sp, 'ready').includes('getAssets().open(ASSET)') && fn(sp, 'ready').includes('resolveContentProvider(AUTH, 0) != null')
      && fn(sp, 'attrs').includes('AudioAttributes.USAGE_NOTIFICATION') && fn(sp, 'legacy').includes('b.setDefaults(Notification.DEFAULT_ALL);'),
      'звук 10.100: свой звук — только если и файл, и провайдер есть в APK; иначе всё как в 10.99');
    // категории: новые id со звуком, старые удаляются, выключенная остаётся выключенной; оба Builder — с новой категорией
    const ec = fn(np, 'ensureChannels'), sn = np.slice(np.indexOf('static void showNote(Context a, String ch, int id, String title, String text, String open, String target) {'));
    assert.ok(ec.includes('boolean snd = IvaSoundPatch.ready(c);') && ec.includes('String sid = IvaSoundPatch.channelId(id, snd);')
      && ec.includes('if (nm.getNotificationChannel(sid) != null) continue;') && ec.includes('if (old != null && old.getImportance() == NotificationManager.IMPORTANCE_NONE) imp = NotificationManager.IMPORTANCE_NONE;')
      && ec.includes('if (snd) IvaSoundPatch.apply(ch);') && ec.includes('if (old != null) try { nm.deleteNotificationChannel(id); } catch (Throwable ignore) {}')
      && ec.indexOf('nm.createNotificationChannel(ch);') < ec.indexOf('nm.deleteNotificationChannel(id)'),
      'звук 10.100: категории пересоздаются со звуком (+_s1), старые удаляются после создания новых, выключенные остаются выключенными');
    assert.ok(sn.includes('String sysCh = IvaSoundPatch.channelId(ch, IvaSoundPatch.ready(c));') && (sn.slice(0, sn.indexOf('\n    }\n')).match(/new Notification\.Builder\(c, sysCh\)/g) || []).length === 2
      && !sn.slice(0, sn.indexOf('\n    }\n')).includes('new Notification.Builder(c, ch)') && sn.includes('IvaSoundPatch.legacy(c, b);')
      && !/DEFAULT_ALL/.test(np),
      'звук 10.100: каждое уведомление — в категорию со своим звуком; Android 7 и раньше — звук на уведомлении');
    // манифест и сборка
    assert.ok(ax.includes('PROVIDER = "ru.prokatnvsk.app.IvaSoundPatch$Files"') && ax.includes('AUTHORITY = "ru.prokatnvsk.app.ivasound"') && ax.includes('"authorities": 0x01010018'),
      'звук 10.100: axml_sound.py — провайдер с authorities ru.prokatnvsk.app.ivasound');
    assert.ok(wf.indexOf('python3 android/patches/axml_outline.py "$W"') < wf.indexOf('python3 android/patches/axml_sound.py "$W/AndroidManifest.xml"')
      && wf.indexOf('python3 android/patches/axml_sound.py "$W/AndroidManifest.xml"') < wf.indexOf('java -jar "$RUNNER_TEMP/apktool.jar" b ')
      && wf.indexOf('java -jar "$RUNNER_TEMP/apktool.jar" b ') < wf.indexOf('zip -q -X -0 "$RUNNER_TEMP/unsigned.apk" assets/iva_notify.mp3')
      && wf.indexOf('zip -q -X -0 "$RUNNER_TEMP/unsigned.apk" assets/iva_notify.mp3') < wf.indexOf('"$BT/zipalign" -f -p 4')
      && /sparse-checkout: \|[\s\S]*\n {12}android\/sounds\/iva_notify\.mp3\n {10}sparse-checkout-cone-mode: false/.test(wf)
      && wf.includes("grep -cx 'assets/iva_notify.mp3')\" = 1 ] || bad") && wf.includes("grep -c 'ru.prokatnvsk.app.ivasound')\" -ge 1 ] || bad"),
      'звук 10.100: сборка — провайдер в манифест до пересборки, звук в APK после неё (до выравнивания), готовый APK проверяется');
    assert.ok(wf.includes('android/patches/IvaSoundPatch.java') && ck.includes('android/patches/IvaSoundPatch.java') && !/IvaSoundPatch/.test(ap)
      && chk.includes('python3 android/patches/axml_sound.py /tmp/iva-sound/AndroidManifest.xml'),
      'звук 10.100: IvaSoundPatch.java — в списках javac, без новых вставок в smali; check.sh проверяет провайдер на манифесте 10.14');
    assert.ok((tp.match(/eq\("звук 10\.100: /g) || []).length >= 6, 'IvaPatchTest: проверки 10.100');
  }
  // 10.101 (решения владельца 16–17.10.2026, этап 1 «Сотрудники и пункты»): инструмент закреплён за пунктом выдачи
  {
    const tq = R('android/patches/IvaToolPointPatch.java'), kp = R('android/patches/IvaKindPatch.java'), fp = R('android/patches/IvaFormPatch.java'),
      pp = R('android/patches/IvaPickupPatch.java'), tp = R('android/patches/test/IvaPatchTest.java'), wf = R('.github/workflows/build-apk.yml'),
      ck = R('.github/workflows/check.yml'), ap = R('android/patches/apply.py'), chk = R('tools/check.sh'),
      sq = R('supabase/migrations/20261026_tool_points.sql'), st = R('tools/tool-points-stand.py');
    const code = tq.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
    assert.ok(wf.includes('android/patches/IvaToolPointPatch.java') && ck.includes('android/patches/IvaToolPointPatch.java') && !/IvaToolPointPatch/.test(ap),
      'пункт 10.101: IvaToolPointPatch.java — в списках javac, без новых вставок в smali');
    assert.ok(kp.includes('try { IvaToolPointPatch.onSaveTouch(a, k); } catch (Throwable ignore) {}') && kp.includes('try { IvaToolPointPatch.onTab(a, tab); } catch (Throwable ignore) {}')
      && kp.includes('try { IvaToolPointPatch.refresh(a); } catch (Throwable ignore) {}') && fp.includes('try { IvaToolPointPatch.ensure(a, form); } catch (Throwable ignore) {}')
      && fp.includes('SEC + "4", IvaToolPointPatch.TAG + ":0", IvaKindPatch.LBL + "atTerms"'),
      'пункт 10.101: подключение — через IvaKindPatch (onSaveTouch / onTab / refreshRows) и IvaFormPatch.setup, место в форме — начало «Условий и доставки»');
    assert.ok(!/customer_profiles|passport|phone|surname|select=\*|"DELETE"/.test(code) && (code.match(/"PATCH"/g) || []).length === 1
      && (code.match(/"POST"/g) || []).length === 1 && code.includes('IvaClientPatch.apiCall(a, RPC, "POST", rpcBody(ids), true)')
      && code.includes('IvaClientPatch.apiCall(a, IvaKindPatch.patchPath(target), "PATCH", body, true)') && code.includes('static String body(String id) { return uuid(id) ?'),
      'пункт 10.101: пишется только point_id (проверенный id) одной записью; читается iva_tool_points; без личных данных и select=*');
    const net = (code.match(/IvaClientPatch\.apiCall\(|IvaPickupPatch\.load\(/g) || []).length, thr = (code.match(/new Thread\(new Runnable\(\)/g) || []).length;
    assert.ok(net === 6 && thr === 4, 'пункт 10.101: все обращения к серверу — в своих потоках (6 вызовов в 4 потоках), не в главном: ' + net + '/' + thr);
    assert.ok(code.includes('if (!uuid(pid)) return;') && code.includes('if (IvaClientPatch.get(a, "detItem") != it) return;') && code.includes('Boolean.TRUE.equals(IvaClientPatch.get(a, "detIsSrv"))'),
      'пункт 10.101: карточка — только у инструмента с пунктом, ответ не попадает в другую открытую карточку');
    // 10.105: «свой» — владелец я или я сотрудник «Каталог» (база пускает сотрудника только в его пункты)
    assert.ok((code.match(/!ownEdit\(a\)/g) || []).length === 2 && (code.match(/editOwner\.equals\(IvaPickupPatch\.me\(a\)\)/g) || []).length === 1
      && /static boolean ownEdit\(Activity a\) \{\n\s*if \(editOwner\.equals\(IvaPickupPatch\.me\(a\)\)\) return true;\n\s*try \{ return IvaStaffToolsPatch\.staff\(a\); \} catch \(Throwable e\) \{ return false; \}/.test(code),
      'пункт 10.101: чужой инструмент (правка создателем) — без поля и без записи');
    assert.ok(pp.includes('if (b.contains("tools_point_fk")) return POINT_BUSY;') && pp.includes('IvaToolPointPatch.mineAt = 0;'),
      'пункт 10.101: удалить пункт с инструментом — понятная фраза; пункты поменялись — формы перечитают');
    const sc = sq.replace(/^--.*$/gm, '');
    assert.ok(sq.includes('--      ALTER TABLE public.tools DROP COLUMN IF EXISTS point_id;') && sq.includes('--      DROP TRIGGER IF EXISTS trg_iva_tool_point ON public.tools;')
      && sc.includes('FOREIGN KEY (point_id) REFERENCES public.iva_pickup_points(id) ON DELETE RESTRICT') && sc.includes('GRANT EXECUTE ON FUNCTION public.iva_tool_points(uuid[]) TO anon, authenticated;')
      && sc.includes("RAISE EXCEPTION 'Сначала примените 20261025_pickup_tel.sql") && !/DROP TABLE|TRUNCATE|DELETE FROM|POLICY|ALTER TABLE public\.iva_pickup_points|GRANT (SELECT|UPDATE|INSERT|DELETE)/i.test(sc)
      && (sc.match(/REVOKE ALL ON FUNCTION/g) || []).length === 6 && !/p\.email|owner_email\s*\)?\s*,?\s*\n?\s*from public\.iva_pickup_points p\s*\n\s*where p\.id = any/.test(sc.slice(sc.indexOf('iva_tool_points(p_ids'))),
      'SQL 10.101: откат в шапке; пункт с инструментом не удаляется; служебные функции закрыты; карточке — без email; политики пунктов не трогаются');
    assert.ok(st.includes("SQL = rd('supabase/migrations/20261026_tool_points.sql')") && chk.includes('tools/tool-points-stand.py'),
      'SQL 10.101: стенд PostgreSQL в check.sh');
    assert.ok((tp.match(/eq\("пункт 10\.101: /g) || []).length >= 12, 'IvaPatchTest: проверки 10.101');
  }
  // 10.102 (решения владельца 17.10.2026, этап 2 «Сотрудники и пункты»): заявки по пунктам выдачи
  {
    const oq = R('android/patches/IvaOrderPointPatch.java'), op = R('android/patches/IvaOrderPatch.java'), np = R('android/patches/IvaNotifyPatch.java'),
      tp = R('android/patches/test/IvaPatchTest.java'), wf = R('.github/workflows/build-apk.yml'), ck = R('.github/workflows/check.yml'),
      ap = R('android/patches/apply.py'), chk = R('tools/check.sh'), sq = R('supabase/migrations/20261027_order_points.sql'),
      rb = R('supabase/migrations/20261027_order_points_rollback.sql'), st = R('tools/order-points-stand.py');
    const code = oq.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
    assert.ok(wf.includes('android/patches/IvaOrderPointPatch.java') && ck.includes('android/patches/IvaOrderPointPatch.java') && !/IvaOrderPointPatch/.test(ap),
      'заявки 10.102: IvaOrderPointPatch.java — в списках javac, без новых вставок в smali');
    assert.ok(op.includes('String[] r = IvaOrderPointPatch.submit(a, body, fls);') && op.includes('try { if (IvaOrderPointPatch.admFilter(a)) return; } catch (Throwable ignore) {}')
      && op.includes('if (client) try { IvaOrderPointPatch.hide(a); } catch (Throwable ignore) {}') && op.includes('IvaOrderPointPatch.loadClient(a);')
      && op.includes('try { IvaOrderPointPatch.sheet(a, body, r, adm); } catch (Throwable ignore) {}') && op.includes('for (String[] f : ordProkatFields(ad, ordNoAddr))')
      && np.includes('try { IvaOrderPointPatch.showAll(a); } catch (Throwable ignore) {}'),
      'заявки 10.102: подключение — из IvaOrderPatch (отправка, admPics, ordRender, ordFetch, ordOpenRow) и IvaNotifyPatch.openOrder');
    assert.ok(!/customer_profiles|passport|phone|surname|select=\*|"PATCH"|"DELETE"/.test(code) && (code.match(/"POST"/g) || []).length === 3
      && code.includes('if (r == null || !noRpc(r[0], r.length > 1 ? r[1] : "")) return r;')
      && code.includes('return "404".equals(code) && (b.contains("PGRST202") || (b.contains("iva_submit_order") && b.contains("Could not find")));'),
      'заявки 10.102: штатная submit_order — повторно только при ответе «функции нет» (заявка не задвоится); ничего не пишет, без личных данных и select=*');
    const net = (code.match(/IvaClientPatch\.apiCall\(|IvaPickupPatch\.load\(/g) || []).length, thr = (code.match(/new Thread\(new Runnable\(\)/g) || []).length;
    assert.ok(net === 5 && thr === 1 && /static void send\(final Activity a\)[\s\S]*new Thread\(new Runnable\(\)[\s\S]*IvaOrderPointPatch\.submit\(/.test(op)
      && /new Thread\(new Runnable\(\)[\s\S]{0,1500}IvaOrderPointPatch\.loadClient\(a\);/.test(op.slice(op.indexOf('static void ordFetch('))),
      'заявки 10.102: обращения к серверу — не в главном потоке (свой поток у пунктов проката; отправка и пункты клиента — в потоках IvaOrderPatch): ' + net + '/' + thr);
    assert.ok(code.includes('boolean ours = cur == mineList && sig((List<?>) cur).equals(mineSig);') && code.includes('if (ours && sig(f).equals(mineSig)) return false;')
      && code.includes('return admCol && ps != null && ps.size() >= 2;'),
      'заявки 10.102: фильтр — только при двух и больше пунктах и SQL 10.102; повторно не перерисовывает');
    const sc = sq.replace(/^--.*$/gm, '');
    assert.ok(sq.includes('20261027_order_points_rollback.sql') && sc.includes("RAISE EXCEPTION 'Сначала примените 20261026_tool_points.sql")
      && !/FUNCTION public\.submit_order\(|DROP TABLE|TRUNCATE|DELETE FROM|POLICY|GRANT (SELECT|UPDATE|INSERT|DELETE)/i.test(sc)
      && sc.includes('FOREIGN KEY (point_id) REFERENCES public.iva_pickup_points(id) ON DELETE SET NULL')
      && sc.includes('REVOKE ALL ON FUNCTION public.iva_order_items_clean(jsonb) FROM public, anon, authenticated;')
      && sc.includes('REVOKE ALL ON FUNCTION public.iva_my_part_points() FROM public, anon;')
      && sc.includes("if current_user in ('anon', 'authenticated') then") && sc.includes("lower(coalesce(t.owner_email, 'eclips.ru@mail.ru')) = lower(new.owner_email)"),
      'SQL 10.102: старая submit_order не трогается; без SQL 10.101 — понятная ошибка; список и пункт части защищены; комиссия без списка — только свой прокат; откат — отдельным файлом');
    assert.ok(rb.includes('CREATE OR REPLACE FUNCTION public.fn_split_order()') && rb.includes('CREATE OR REPLACE FUNCTION public.fn_fee_accrue()')
      && rb.includes('DROP FUNCTION IF EXISTS public.iva_submit_order(text, text, text, text, text, text, text, integer, jsonb);') && !/DROP TABLE|TRUNCATE|DELETE FROM/i.test(rb),
      'SQL 10.102: откат возвращает прежние функции и убирает новое, без удаления данных');
    assert.ok(st.includes("SQL = rd('supabase/migrations/20261027_order_points.sql')") && chk.includes('tools/order-points-stand.py'),
      'SQL 10.102: стенд PostgreSQL в check.sh');
    assert.ok((tp.match(/eq\("заявки 10\.102: /g) || []).length >= 10, 'IvaPatchTest: проверки 10.102');
  }
  // 10.103 (решения владельца 16–17.10.2026, этап 3а «Сотрудники и пункты»): сотрудники, приглашение, права
  {
    const sp = R('android/patches/IvaStaffPatch.java'), pk = R('android/patches/IvaPickupPatch.java'), np = R('android/patches/IvaNotifyPatch.java'),
      tp = R('android/patches/test/IvaPatchTest.java'), wf = R('.github/workflows/build-apk.yml'), ck = R('.github/workflows/check.yml'),
      ap = R('android/patches/apply.py'), chk = R('tools/check.sh'), sq = R('supabase/migrations/20261028_staff.sql'), st = R('tools/staff-stand.py');
    const code = sp.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
    assert.ok(wf.includes('android/patches/IvaStaffPatch.java') && ck.includes('android/patches/IvaStaffPatch.java') && !/IvaStaffPatch/.test(ap),
      'сотрудники 10.103: IvaStaffPatch.java — в списках javac, без новых вставок в smali');
    assert.ok(/new Thread\(new Runnable\(\)[\s\S]{0,300}try \{ ps = load\(u\.a, new String\[1\]\); IvaStaffPatch\.fetch\(u\.a\); \}/.test(pk)
      && pk.includes('IvaStaffPatch.openPoint(a, p, u.points, new Runnable()') && (pk.match(/IvaStaffPatch\./g) || []).length === 5
      && np.includes('try { IvaStaffPatch.onShowTab(a, tab); } catch (Throwable ignore) {}'),
      'сотрудники 10.103: подключение — страница пунктов (список в её потоке, «Сотрудники · N», предупреждение при удалении) и кабинет');
    assert.ok(!/customer_profiles|passport|phone|surname|select=|"PATCH"|"DELETE"|"GET"|\/rest\/v1\/iva_staff/.test(code)
      && (code.match(/IvaClientPatch\.apiCall\(/g) || []).length === 5 && (code.match(/"POST"/g) || []).length === 5
      && (code.match(/new Thread\(new Runnable\(\)/g) || []).length === 4
      && code.includes('boolean want = IvaNotifyPatch.loggedIn(a) && !me.isEmpty() && !IvaNotifyPatch.isAdmin(a);'),
      'сотрудники 10.103: только функции базы iva_staff_* (таблицы напрямую — нет), сеть — в потоках, владельцу проката приглашений не показываем');
    const phrases = (code.match(/DB_PHRASES = \{([\s\S]*?)\};/) || ['', ''])[1].match(/"([^"]+)"/g) || [];
    const lost = phrases.map(x => x.slice(1, -1)).filter(x => !sq.includes("raise exception '" + x + "'"));
    assert.ok(phrases.length === 15 && lost.length === 0, 'сотрудники 10.103: фразы ошибок приложения = RAISE EXCEPTION в 20261028_staff.sql: ' + lost.join(' | '));
    const sc = sq.replace(/^--.*$/gm, '');
    assert.ok(!/DROP TABLE|TRUNCATE|DELETE FROM public\.(?!iva_staff)|ALTER TABLE public\.(?!iva_staff)|ON public\.(?!iva_staff)[a-z_]+ FOR|GRANT (INSERT|UPDATE|DELETE|ALL)/i.test(sc)
      && sc.includes("RAISE EXCEPTION 'Сначала примените 20261023_pickup_points.sql") && sc.includes('REVOKE ALL ON TABLE public.iva_staff FROM public, anon, authenticated;')
      && sc.includes('CREATE UNIQUE INDEX IF NOT EXISTS iva_staff_one_owner ON public.iva_staff (email) WHERE status IN (\'invited\', \'active\');')
      && sq.includes('--      DROP TABLE IF EXISTS public.iva_staff;') && !/'admin@|@mail\.ru'/.test(sc),
      'SQL 10.103: только новые таблицы и функции (существующее не трогает), писать напрямую нельзя, один человек — у одного проката, откат в шапке');
    assert.ok(st.includes("SQL = rd('supabase/migrations/20261028_staff.sql')") && chk.includes('tools/staff-stand.py'), 'SQL 10.103: стенд PostgreSQL в check.sh');
    assert.ok((tp.match(/eq\("сотрудники 10\.103: /g) || []).length >= 14, 'IvaPatchTest: проверки 10.103');
  }
  // 10.104 (решения владельца 16–17.10.2026, этап 3б «Сотрудники и пункты»): заявки пункта у сотрудника
  {
    const so = R('android/patches/IvaStaffOrdersPatch.java'), sp = R('android/patches/IvaStaffPatch.java'), np = R('android/patches/IvaNotifyPatch.java'),
      tp = R('android/patches/test/IvaPatchTest.java'), wf = R('.github/workflows/build-apk.yml'), ck = R('.github/workflows/check.yml'),
      ap = R('android/patches/apply.py'), chk = R('tools/check.sh'), sq = R('supabase/migrations/20261029_staff_orders.sql'), st = R('tools/staff-orders-stand.py');
    const code = so.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
    assert.ok(/android\/patches\/IvaStaffPatch\.java android\/patches\/IvaStaffOrdersPatch\.java (android\/patches\/IvaStaffToolsPatch\.java )?(android\/patches\/IvaStaffChatPatch\.java )?(android\/patches\/IvaStaffJournalPatch\.java )?(android\/patches\/IvaAdminsPatch\.java )?2>&1/.test(wf) && ck.includes('android/patches/IvaStaffOrdersPatch.java')
      && !/IvaStaffOrdersPatch/.test(ap), 'заявки пункта 10.104: IvaStaffOrdersPatch.java — в списках javac, без новых вставок в smali');
    const tk = np.slice(np.indexOf('static void tick() {'), np.indexOf('static JSONArray getArr('));
    const bg = np.slice(np.indexOf('static void bgRun(Context app) {'));
    assert.ok(/new Thread\(new Runnable\(\)[\s\S]*if \(logged && !admin && !email\.isEmpty\(\)\) try \{ IvaStaffOrdersPatch\.check\(a, email\); \} catch \(Throwable ignore\) \{\}/.test(tk)
      && bg.includes('if (logged && !admin) try { IvaStaffOrdersPatch.check(c, email); } catch (Throwable ignore) {}')
      && np.includes('else if (IvaStaffOrdersPatch.OPEN.equals(open)) IvaStaffOrdersPatch.openFromNote(a);')
      && (np.match(/IvaStaffOrdersPatch\./g) || []).length === 4
      && sp.includes('if (IvaStaffOrdersPatch.want(m.status, m.r)) {') && (sp.match(/IvaStaffOrdersPatch\./g) || []).length === 3,
      'заявки пункта 10.104: подключение — опрос (не владельцу, в потоке окна и в фоне), нажатие на уведомление, кнопка на «Мой пункт»');
    assert.ok(!/customer_profiles|passport|phone|surname|select=|"PATCH"|"DELETE"|"GET"|\/rest\/v1\/(?!rpc\/iva_staff_)/.test(code)
      && (code.match(/IvaClientPatch\.apiCall\(/g) || []).length === 2 && (code.match(/IvaNotifyPatch\.api\(/g) || []).length === 1
      && (code.match(/"POST"/g) || []).length === 3 && (code.match(/new Thread\(new Runnable\(\)/g) || []).length === 2
      && code.includes('if (m == null || !want(m.status, m.r)) return;'),
      'заявки пункта 10.104: только функции базы iva_staff_* (таблицы заявок напрямую — нет), сеть — в потоках, не сотрудник — в базу не ходим');
    const phrases = (code.match(/DB_PHRASES = \{([\s\S]*?)\};/) || ['', ''])[1].match(/"([^"]+)"/g) || [];
    const lost = phrases.map(x => x.slice(1, -1)).filter(x => !sq.includes("raise exception '" + x + "'"));
    assert.ok(phrases.length === 4 && lost.length === 0, 'заявки пункта 10.104: фразы ошибок приложения = RAISE EXCEPTION в 20261029_staff_orders.sql: ' + lost.join(' | '));
    const sc = sq.replace(/^--.*$/gm, '');
    const ret = (sc.match(/FUNCTION public\.iva_staff_parts\(\)\s+RETURNS TABLE \(([\s\S]*?)\)\s+LANGUAGE/) || ['', ''])[1];
    assert.ok(ret.includes('client_tel text') && !/fee|sum|price|email|passport/i.test(ret)
      && !/DROP TABLE|TRUNCATE|DELETE FROM|ALTER TABLE|POLICY|CREATE TABLE|GRANT (SELECT|INSERT|UPDATE|DELETE|ALL)|\bTRIGGER\b/i.test(sc)
      && sc.includes("RAISE EXCEPTION 'Сначала примените 20261028_staff.sql") && sc.includes("RAISE EXCEPTION 'Сначала примените 20261027_order_points.sql")
      && sc.includes('REVOKE ALL ON FUNCTION public.iva_staff_right(uuid, text) FROM public, anon, authenticated;')
      && sc.includes('REVOKE ALL ON FUNCTION public.iva_staff_parts() FROM public, anon;')
      && sc.includes('REVOKE ALL ON FUNCTION public.iva_staff_set_status(bigint, text) FROM public, anon;')
      && sc.includes("if pt.client = me and not coalesce(slf, false) then") && sc.includes("not public.iva_staff_right(pt.point_id, 'issue')")
      && sq.includes('--      DROP FUNCTION IF EXISTS public.iva_staff_set_status(bigint, text);') && !/'admin@|@mail\.ru'/.test(sc),
      'SQL 10.104: только функции (таблицы и правила доступа не меняются), без денег и почты клиента, право и «самому себе» проверяет база, откат в шапке');
    assert.ok(st.includes("SQL = rd('supabase/migrations/20261029_staff_orders.sql')") && chk.includes('tools/staff-orders-stand.py'), 'SQL 10.104: стенд PostgreSQL в check.sh');
    assert.ok((tp.match(/eq\("заявки пункта 10\.104: /g) || []).length >= 12, 'IvaPatchTest: проверки 10.104');
  }
  // 10.105 (решения владельца 16–17.10.2026, этап 3в «Сотрудники и пункты»): инструменты пункта у сотрудника, одобрение
  {
    const sk = R('android/patches/IvaStaffToolsPatch.java'), sp = R('android/patches/IvaStaffPatch.java'), np = R('android/patches/IvaNotifyPatch.java'),
      ip = R('android/patches/IvaPatch.java'), sh = R('android/patches/IvaShopPatch.java'), kp = R('android/patches/IvaKindPatch.java'),
      tpp = R('android/patches/IvaToolPointPatch.java'), tp = R('android/patches/test/IvaPatchTest.java'), wf = R('.github/workflows/build-apk.yml'),
      ck = R('.github/workflows/check.yml'), ap = R('android/patches/apply.py'), chk = R('tools/check.sh'),
      sq = R('supabase/migrations/20261030_staff_tools.sql'), st = R('tools/staff-tools-stand.py');
    const code = sk.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
    assert.ok(/IvaStaffOrdersPatch\.java android\/patches\/IvaStaffToolsPatch\.java (android\/patches\/IvaStaffChatPatch\.java )?(android\/patches\/IvaStaffJournalPatch\.java )?(android\/patches\/IvaAdminsPatch\.java )?2>&1/.test(wf) && ck.includes('android/patches/IvaStaffToolsPatch.java')
      && !/IvaStaffToolsPatch/.test(ap), 'инструменты пункта 10.105: IvaStaffToolsPatch.java — в списках javac, без новых вставок в smali');
    const tk = np.slice(np.indexOf('static void tick() {'), np.indexOf('static JSONArray getArr('));
    const bg = np.slice(np.indexOf('static void bgRun(Context app) {'));
    assert.ok(/new Thread\(new Runnable\(\)[\s\S]*if \(logged && admin && !email\.isEmpty\(\)\) try \{ IvaStaffToolsPatch\.check\(a, email\); \} catch \(Throwable ignore\) \{\}/.test(tk)
      && bg.includes('if (logged && admin) try { IvaStaffToolsPatch.check(c, email); } catch (Throwable ignore) {}')
      && np.includes('else if (IvaStaffToolsPatch.OPEN.equals(open)) IvaStaffToolsPatch.openFromNote(a);')
      && (np.match(/IvaStaffToolsPatch\./g) || []).length === 4
      && sp.includes('if (IvaStaffToolsPatch.want(m.status, m.r)) {') && (sp.match(/IvaStaffToolsPatch\./g) || []).length === 4
      && sh.includes('try { IvaStaffToolsPatch.settingsSection(a, list, cText, cGray, cOrange); } catch (Throwable ignore) {}')
      && ip.includes('try { IvaStaffToolsPatch.onTab(a, tab); } catch (Throwable ignore) {}')
      && ip.includes('try { if (IvaStaffToolsPatch.onBack(a, ct instanceof String ? (String) ct : "")) return; } catch (Throwable ignore) {}')
      && (ip.match(/IvaStaffToolsPatch\./g) || []).length === 2,
      'инструменты пункта 10.105: подключение — onTab/onBack, «Мой пункт», «Настройка проката», опрос владельца (окно и фон), нажатие на уведомление');
    assert.ok(!/customer_profiles|passport|phone|surname|select=|"PATCH"|"DELETE"|"GET"|\/rest\/v1\/(?!rpc\/iva_(staff_tools|tools_pending|tool_review|staff_publish))/.test(code)
      && (code.match(/IvaClientPatch\.apiCall\(/g) || []).length === 6 && (code.match(/IvaNotifyPatch\.api\(/g) || []).length === 1
      && (code.match(/new Thread\(new Runnable\(\)/g) || []).length === 5
      && code.includes('if (!IvaNotifyPatch.loggedIn(a) || IvaNotifyPatch.isAdmin(a)) return false;')
      && code.includes('if (IvaNotifyPatch.isAdmin(a)) openReview(a, null);'),
      'инструменты пункта 10.105: только функции базы (таблицу инструментов напрямую не пишем — это делает штатная форма), сеть — в потоках, владелец сотрудником не считается');
    assert.ok(code.includes('getDeclaredMethod("showAdmHub", int.class)') && code.includes('getDeclaredMethod("openToolEdit", JSONObject.class)')
      && code.includes('IvaNotifyPatch.tab(a, "addtool");') && !/showTab\("admhub"\)|loadAdminItems|selfAdmin/.test(code),
      'инструменты пункта 10.105: форма — штатная (addtool + showAdmHub(1), openToolEdit), кабинет владельца сотруднику не открываем');
    const phrases = (code.match(/DB_PHRASES = \{([\s\S]*?)\};/) || ['', ''])[1].match(/"([^"]+)"/g) || [];
    const lost = phrases.map(x => x.slice(1, -1)).filter(x => !sq.includes("raise exception '" + x + "'"));
    assert.ok(phrases.length === 5 && lost.length === 0, 'инструменты пункта 10.105: фразы ошибок приложения = RAISE EXCEPTION в 20261030_staff_tools.sql: ' + lost.join(' | '));
    assert.ok(/static String lookupPath\(String email, String name, String since, String staffId\) \{/.test(kp)
      && kp.includes('lookupPath(email, name, isoUtc(System.currentTimeMillis() - FRESH_MS), staffId)')
      && tpp.includes('IvaKindPatch.isoUtc(System.currentTimeMillis() - IvaKindPatch.FRESH_MS), staffId)')
      && tpp.includes('try { sp = IvaStaffToolsPatch.points(a); } catch (Throwable ignore) {}'),
      'инструменты пункта 10.105: вид, модель и пункт после публикации сотрудника — поиск по номеру сотрудника, в форме — его пункты');
    const sc = sq.replace(/^--.*$/gm, '');
    assert.ok(!/DROP TABLE|TRUNCATE|DELETE FROM|GRANT (INSERT|UPDATE|DELETE|ALL)|DROP POLICY IF EXISTS tools_admin|CREATE POLICY tools_admin/i.test(sc)
      && sc.includes("RAISE EXCEPTION 'Сначала примените 20261029_staff_orders.sql") && sc.includes("RAISE EXCEPTION 'Сначала примените 20261028_staff.sql")
      && sc.includes('REVOKE ALL ON FUNCTION public.iva_tools_staff_before() FROM public, anon, authenticated;')
      && sc.includes("new.owner_email := sowner;") && sc.includes("new.owner_email := old.owner_email;")
      && sc.includes("new.review := 'pending';") && sc.includes("ADD COLUMN IF NOT EXISTS staff_publish text NOT NULL DEFAULT 'approve'")
      && /iva_staff_tools\(\)[\s\S]{0,400}to_jsonb\(t\) - 'owner_email' - 'staff_id'/.test(sc) && !/staff_id text|created_by/.test(sc)
      && sq.includes('--      DROP TRIGGER IF EXISTS trg_iva_staff_tool ON public.tools;') && !/'admin@|@mail\.ru'/.test(sc),
      'SQL 10.105: правило владельца не меняется, владелец инструмента сотрудника — прокат, новые — на одобрение по умолчанию, почта сотрудника и владельца в списке не отдаётся, откат в шапке');
    assert.ok(st.includes("SQL = rd('supabase/migrations/20261030_staff_tools.sql')") && chk.includes('tools/staff-tools-stand.py'), 'SQL 10.105: стенд PostgreSQL в check.sh');
    assert.ok((tp.match(/eq\("инструменты пункта 10\.105: /g) || []).length >= 12, 'IvaPatchTest: проверки 10.105');
  }
  // 10.106 (решения владельца 16–17.10.2026, этап 3г «Сотрудники и пункты»): чат пункта у сотрудника — ответ от имени проката
  {
    const sc6 = R('android/patches/IvaStaffChatPatch.java'), sp = R('android/patches/IvaStaffPatch.java'), np = R('android/patches/IvaNotifyPatch.java'),
      ip = R('android/patches/IvaPatch.java'), tp = R('android/patches/test/IvaPatchTest.java'), wf = R('.github/workflows/build-apk.yml'),
      ck = R('.github/workflows/check.yml'), ap = R('android/patches/apply.py'), chk = R('tools/check.sh'),
      sq = R('supabase/migrations/20261031_staff_chat.sql'), st = R('tools/staff-chat-stand.py'), cen = R('supabase/migrations/20261021_censor.sql');
    const code = sc6.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
    assert.ok(/IvaStaffToolsPatch\.java android\/patches\/IvaStaffChatPatch\.java (android\/patches\/IvaStaffJournalPatch\.java )?(android\/patches\/IvaAdminsPatch\.java )?2>&1/.test(wf) && ck.includes('android/patches/IvaStaffChatPatch.java')
      && !/IvaStaffChatPatch/.test(ap) && !/IvaStaffChatPatch/.test(ip), 'чат пункта 10.106: IvaStaffChatPatch.java — в списках javac, без новых вставок в smali и onTab');
    const tk = np.slice(np.indexOf('static void tick() {'), np.indexOf('static JSONArray getArr('));
    const bg = np.slice(np.indexOf('static void bgRun(Context app) {'));
    assert.ok(/new Thread\(new Runnable\(\)[\s\S]*if \(logged && !admin && !email\.isEmpty\(\)\) try \{ IvaStaffChatPatch\.check\(a, email\); \} catch \(Throwable ignore\) \{\}/.test(tk)
      && bg.includes('if (logged && !admin) try { IvaStaffChatPatch.check(c, email); } catch (Throwable ignore) {}')
      && np.includes('else if (IvaStaffChatPatch.OPEN.equals(open)) IvaStaffChatPatch.openFromNote(a, target);')
      && (np.match(/IvaStaffChatPatch\./g) || []).length === 4
      && sp.includes('if (IvaStaffChatPatch.want(m.status, m.r)) {') && (sp.match(/IvaStaffChatPatch\./g) || []).length === 3,
      'чат пункта 10.106: подключение — «Мой пункт», опрос сотрудника (окно и фон, не владельцу), нажатие на уведомление');
    assert.ok(!/customer_profiles|passport|phone|surname|select=|from_email|to_email|app_messages|"PATCH"|"DELETE"|"GET"|\/rest\/v1\/(?!rpc\/iva_staff_chat(s|_read|_send)?")/.test(code)
      && (code.match(/IvaClientPatch\.apiCall\(/g) || []).length === 4 && (code.match(/IvaNotifyPatch\.api\(/g) || []).length === 1
      && (code.match(/new Thread\(new Runnable\(\)/g) || []).length === 3
      && code.includes('if (m == null || !want(m.status, m.r)) return;')
      && code.includes('static boolean isPeer(String p) { return p != null && p.trim().matches("[0-9a-f]{32}"); }'),
      'чат пункта 10.106: только функции базы, почты клиента в приложении сотрудника нет (метка md5), сеть — в потоках, опрос — только сотруднику с чатом');
    const phrases = (code.match(/DB_PHRASES = \{([\s\S]*?)\};/) || ['', ''])[1].match(/"([^"]+)"/g) || [];
    const lost = phrases.map(x => x.slice(1, -1)).filter(x => !sq.includes("raise exception '" + x + "'"));
    const mat = (code.match(/MAT_PHRASE = "([^"]+)"/) || ['', '-'])[1];
    assert.ok(phrases.length === 4 && lost.length === 0 && cen.includes("'" + mat + "'"),
      'чат пункта 10.106: фразы ошибок приложения = RAISE EXCEPTION в 20261031_staff_chat.sql и «Цензура» чата: ' + lost.join(' | '));
    const sc = sq.replace(/^--.*$/gm, '');
    const rt = (sc.match(/FUNCTION public\.iva_staff_chats\(\)\s+RETURNS TABLE \(([^)]*)\)/) || ['', 'email'])[1];
    assert.ok(!/DROP TABLE|TRUNCATE|DELETE FROM|GRANT (INSERT|UPDATE|DELETE|ALL)|POLICY [\s\S]{0,60}app_messages/i.test(sc)
      && sc.includes("RAISE EXCEPTION 'Сначала примените 20261028_staff.sql") && sc.includes("RAISE EXCEPTION 'Сначала примените 20261012_chat_tools_avatars.sql")
      && sc.includes('REVOKE ALL ON FUNCTION public.iva_staff_chat_me() FROM public, anon, authenticated;')
      && sc.includes('REVOKE ALL ON FUNCTION public.iva_staff_chat_tools() FROM public, anon, authenticated;')
      && !/email|phone|surname|passport/.test(rt) && /select g\.tid, md5\(g\.pe\),/.test(sc)
      && sc.includes('values (me.owner, btrim(cl), t, p_tool, me.sid)') && sc.includes('join lc on lc.tid = g.tid and lc.pe = g.pe')
      && sq.includes('--      DROP FUNCTION IF EXISTS public.iva_staff_chat_send(uuid, text, text);') && !/'admin@|@mail\.ru'/.test(sc),
      'SQL 10.106: правила доступа к сообщениям не меняются, почты клиента сотруднику нет, ответ — от проката и только где клиент писал, откат в шапке');
    assert.ok(st.includes("SQL = rd('supabase/migrations/20261031_staff_chat.sql')") && chk.includes('tools/staff-chat-stand.py'), 'SQL 10.106: стенд PostgreSQL в check.sh');
    assert.ok((tp.match(/eq\("чат пункта 10\.106: /g) || []).length >= 12, 'IvaPatchTest: проверки 10.106');
  }
  // 10.107 (решения владельца 16–17.10.2026, этап 4 «Сотрудники и пункты»): «кто выдал / кто принял», «Сводка по пунктам»,
  // имя сотрудника под его ответом в чате владельца
  {
    const sj = R('android/patches/IvaStaffJournalPatch.java'), op = R('android/patches/IvaOrderPatch.java'), shp = R('android/patches/IvaShopPatch.java'),
      chp = R('android/patches/IvaChatPatch.java'), ip = R('android/patches/IvaPatch.java'), tp = R('android/patches/test/IvaPatchTest.java'),
      wf = R('.github/workflows/build-apk.yml'), ck = R('.github/workflows/check.yml'), ap = R('android/patches/apply.py'), chk = R('tools/check.sh'),
      sq = R('supabase/migrations/20261101_staff_journal.sql'), st = R('tools/staff-journal-stand.py');
    const code = sj.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
    assert.ok(/IvaStaffChatPatch\.java android\/patches\/IvaStaffJournalPatch\.java (android\/patches\/IvaAdminsPatch\.java )?2>&1/.test(wf) && ck.includes('android/patches/IvaStaffJournalPatch.java')
      && !/IvaStaffJournalPatch/.test(ap) && !/IvaStaffJournalPatch/.test(ip), 'журнал 10.107: IvaStaffJournalPatch.java — в списках javac, без новых вставок в smali и onTab');
    assert.ok(op.includes('try { IvaStaffJournalPatch.sheet(a, body, r, adm); } catch (Throwable ignore) {}') && (op.match(/IvaStaffJournalPatch\./g) || []).length === 1
      && op.indexOf('IvaStaffJournalPatch.sheet(') > op.indexOf('IvaOrderPointPatch.sheet(a, body, r, adm)')
      && shp.includes('try { IvaStaffJournalPatch.settingsRow(a, list, cText, cGray, cOrange); } catch (Throwable ignore) {}') && (shp.match(/IvaStaffJournalPatch\./g) || []).length === 1
      && chp.includes('if (adm) try { IvaStaffJournalPatch.chatLoad(a, asc, my); } catch (Throwable ignore) {}')
      && chp.includes('if (mine) try { String sw = IvaStaffJournalPatch.who(m.id); if (!sw.isEmpty()) time = sw + " · " + time; } catch (Throwable ignore) {}')
      && chp.includes(".append(IvaStaffJournalPatch.chatVer)") && (chp.match(/IvaStaffJournalPatch\./g) || []).length === 3,
      'журнал 10.107: подключение — шторка заявки админа (после пункта), «Настройка проката», чат (загрузка у админа, подпись своего пузыря, перерисовка)');
    assert.ok(code.includes('if (!adm || r == null) return;') && !/customer_profiles|passport|phone|surname|select=|app_messages|"PATCH"|"DELETE"|"GET"|\/rest\/v1\/(?!rpc\/iva_(part_journal|points_summary|chat_staff_names)")/.test(code)
      && (code.match(/IvaClientPatch\.apiCall\(/g) || []).length === 3 && (code.match(/new Thread\(new Runnable\(\)/g) || []).length === 2,
      'журнал 10.107: только функции базы, только админу, сеть — в потоках (чат — в потоке загрузки чата)');
    const phrases = (code.match(/DB_PHRASES = \{([\s\S]*?)\};/) || ['', ''])[1].match(/"([^"]+)"/g) || [];
    const lost = phrases.map(x => x.slice(1, -1)).filter(x => !sq.includes("raise exception '" + x + "'"));
    assert.ok(phrases.length === 1 && lost.length === 0, 'журнал 10.107: фразы ошибок приложения = RAISE EXCEPTION в 20261101_staff_journal.sql: ' + lost.join(' | '));
    const sc = sq.replace(/^--.*$/gm, '');
    assert.ok(!/DROP TABLE|TRUNCATE|DELETE FROM|GRANT (INSERT|UPDATE|DELETE|ALL)|CREATE POLICY|DROP POLICY/i.test(sc)
      && sc.includes("RAISE EXCEPTION 'Сначала примените 20261031_staff_chat.sql") && sc.includes("RAISE EXCEPTION 'Сначала примените 20261027_order_points.sql")
      && sc.includes('new.issued_by := old.issued_by;') && sc.includes('new.returned_by := old.returned_by;') && sc.includes('new.returned_at := old.returned_at;')
      && sc.includes('REVOKE ALL ON FUNCTION public.iva_journal_who(text, text) FROM public, anon, authenticated;')
      && sc.includes('REVOKE ALL ON FUNCTION public.iva_points_summary(int) FROM public, anon;')
      && sc.includes("if me = '' or not public.iva_pickup_is_admin() then raise exception 'Только владелец проката'; end if;")
      && sc.includes("and lower(btrim(m.from_email)) = lower(btrim(auth.jwt() ->> 'email'));")
      && !/user_email|o\.phone|o\.name|passport/.test(sc.slice(sc.indexOf('FUNCTION public.iva_points_summary')))
      && sq.includes('--      DROP TRIGGER IF EXISTS trg_b_iva_part_journal ON public.order_parts;') && !/'admin@|@mail\.ru'/.test(sc),
      'SQL 10.107: журнал пишет только база (вручную не меняется), сводка и журнал — только владельцу, без почты и телефона клиента, имена в чате — только своих ответов, откат в шапке');
    assert.ok(st.includes("SQL = rd('supabase/migrations/20261101_staff_journal.sql')") && chk.includes('tools/staff-journal-stand.py'), 'SQL 10.107: стенд PostgreSQL в check.sh');
    assert.ok((tp.match(/eq\("(журнал|чат|сводка) 10\.107: /g) || []).length >= 10, 'IvaPatchTest: проверки 10.107');
  }
  // 10.108 (просьба владельца 17.10.2026): создатель приостанавливает и удаляет админов; «Добавить админа» — на странице
  // «Все администраторы»; в «Панели владельца» нет почты, статистики и поля добавления
  {
    const ad = R('android/patches/IvaAdminsPatch.java'), np = R('android/patches/IvaNotifyPatch.java'), chp = R('android/patches/IvaChiefPatch.java'),
      ip = R('android/patches/IvaPatch.java'), tp = R('android/patches/test/IvaPatchTest.java'), wf = R('.github/workflows/build-apk.yml'),
      ck = R('.github/workflows/check.yml'), ap = R('android/patches/apply.py'), chk = R('tools/check.sh'),
      sq = R('supabase/migrations/20261102_admin_manage.sql'), st = R('tools/admin-manage-stand.py');
    const code = ad.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
    assert.ok(/IvaStaffJournalPatch\.java android\/patches\/IvaAdminsPatch\.java 2>&1/.test(wf) && ck.includes('android/patches/IvaAdminsPatch.java')
      && !/IvaAdminsPatch/.test(ap) && !/IvaAdminsPatch/.test(ip), 'админы 10.108: IvaAdminsPatch.java — в списках javac, без новых вставок в smali и IvaPatch.onTab');
    assert.ok(np.includes('try { IvaAdminsPatch.onTab(a, tab); } catch (Throwable ignore) {}') && (np.match(/IvaAdminsPatch\./g) || []).length === 1
      && chp.includes('try { IvaAdminsPatch.panelChanged(a, (ViewGroup) parent); } catch (Throwable ignore) {}') && (chp.match(/IvaAdminsPatch\.panelChanged\(/g) || []).length === 1
      && chp.indexOf('IvaAdminsPatch.panelChanged(') > chp.indexOf('box.setOnHierarchyChangeListener(')
      && !/"POST"|"PATCH"|"DELETE"/.test(chp), 'админы 10.108: вход — onShowTab и слушатель «Панели владельца» (IvaChiefPatch по-прежнему без записи)');
    assert.ok(/static void onTab\(Activity a, String tab\) \{\s*if \(!chief\(a\)\) return;/.test(code) && code.includes('static boolean chief(Activity a) { return IvaReviewPatch.chief(a); }')
      && /static void panelChanged\(final Activity a, final ViewGroup box\) \{\s*if \(!chief\(a\)\) return;/.test(code) && /static void placeCard\(Activity a, ViewGroup box\) \{\s*if \(!chief\(a\)\) return;/.test(code),
      'админы 10.108: всё — только у создателя');
    assert.ok(!/customer_profiles|passport|phone|surname|select=|"PATCH"|"DELETE"|"GET"|\/rest\/v1\/(?!rpc\/iva_admin_(add|pause|remove|state)"|rpc\/iva_admins_removed"|admins")/.test(code)
      && (code.match(/IvaClientPatch\.apiCall\(/g) || []).length === 5 && (code.match(/new Thread\(new Runnable\(\)/g) || []).length === 4
      && code.includes('else if (NO_DB.equals(fe)) {') && !/static final Handler/.test(code),
      'админы 10.108: только функции базы (штатная вставка admins — лишь если SQL 10.108 ещё нет), сеть — в потоках, без Android в статике');
    for (const ph of ['Похоже, в email ошибка (пример: ivan@mail.ru)', 'Это ваша почта — вы уже владелец площадки'])
      assert.ok(code.includes('"' + ph + '"') && sq.includes("raise exception '" + ph + "'"), 'админы 10.108: фраза приложения = RAISE EXCEPTION в 20261102_admin_manage.sql: ' + ph);
    const sc = sq.replace(/^--.*$/gm, '');
    assert.ok(!/DROP TABLE|TRUNCATE|DELETE FROM|GRANT (INSERT|UPDATE|DELETE|ALL)/i.test(sc)
      && (sc.match(/if me = '' or not public\.fn_is_chief\(\) then/g) || []).length === 3
      && sc.includes("if coalesce(r.role, '') = 'chief' or em = me then raise exception 'Владельца площадки приостановить нельзя'; end if;")
      && sc.includes("if coalesce(r.role, '') = 'chief' or em = me then raise exception 'Владельца площадки удалить нельзя'; end if;")
      && sc.includes("else new.chief_paused := old.chief_paused;") && sc.includes("raise exception 'Удаляет админов только владелец площадки';")
      && sc.includes('REVOKE ALL ON FUNCTION public.iva_admin_access_trg() FROM public, anon, authenticated;')
      && sc.includes("update public.admins a set active = false, removed_at = now() where lower(btrim(a.email)) = em;")
      && sq.includes('--      UPDATE public.tools SET active = true WHERE chief_paused;') && sq.includes('--      ALTER TABLE public.admins DROP COLUMN IF EXISTS removed_at;')
      && !/'admin@|@mail\.ru'/.test(sc),
      'SQL 10.108: действия — только создателю, владельца площадки и себя нельзя, «скрыт паузой» ставит только база, ничего не удаляется, откат в шапке');
    assert.ok(st.includes("SQL = rd('supabase/migrations/20261102_admin_manage.sql')") && chk.includes('tools/admin-manage-stand.py'), 'SQL 10.108: стенд PostgreSQL в check.sh');
    assert.ok((tp.match(/eq\("админы 10\.108: /g) || []).length >= 6, 'IvaPatchTest: проверки 10.108');
  }
  // 10.75 (просьба владельца 14.10.2026): в «Настройках» — кнопка «Уведомления» (с 10.97 — открывает отдельную страницу);
  // внутри сверху «Открыть настройки уведомлений Android», потом какие присылать, потом расписание, потом всё остальное
  {
    const np = R('android/patches/IvaNotifyPatch.java'), tp = R('android/patches/test/IvaPatchTest.java');
    const mm = /static \w+ openSettings\(/.exec(np); assert.ok(mm, 'IvaNotifyPatch: нет openSettings');
    const os = np.slice(mm.index, np.indexOf('\n    }\n', mm.index));
    const at = (t) => { const i = os.indexOf(t); assert.ok(i > 0, `openSettings: нет ${t}`); return i; };
    assert.ok(at('page.addView(head, hl);') > 0 && !os.includes('page.addView(body') && !os.includes('body.setVisibility('),
      'настройки 10.75/10.97: в «Настройках» — только кнопка «Уведомления», список на месте не раскрывается');
    assert.ok(os.includes('try { IvaChiefPatch.subPage(a, NOTIF_TITLE, body, onPage[0]); } catch (Throwable ignore) {}') && os.includes('onPage[0] = refresh;'),
      'настройки 10.75/10.97: нажатие на кнопку открывает отдельную страницу «Уведомления» (возврат из Android — обновляет её)');
    assert.ok((os.match(/Открыть настройки уведомлений Android/g) || []).length === 1 && (np.match(/Открыть настройки уведомлений Android/g) || []).length === 1,
      'настройки 10.75: кнопка Android — одна, в других местах её нет');
    const sys = at('linkButton(a, body, "Открыть настройки уведомлений Android"');
    assert.ok(sys < at('switchRow(a, card, "Все уведомления"') && at('switchRow(a, card, "Все уведомления"') < at('schedSw[0] = switchRow(')
      && at('schedSw[0] = switchRow(') < at('body.addView(status, sl);') && at('body.addView(status, sl);') < at('body.addView(note, nl);'),
      'настройки 10.75: порядок — Android, какие присылать, расписание, остальное');
    const os75 = os.replace('IvaRentPatch.settingsSection(a, page, cText, cGray, cOrange)', '')   // 10.77: «Аренда» — отдельная кнопка ниже
      .replace('IvaPickupPatch.settingsRow(a, page, cText, cGray, cOrange)', '')   // 10.95: «Пункт выдачи» — тоже отдельная кнопка
      .replace('IvaShopPatch.settingsRow(a, page, cText, cGray, cOrange)', '');    // 10.98: «Настройка проката» вместо двух строк выше
    assert.ok(!/(?:page|root)\.addView\((?:status|test|note|push|card|sc|sys|body)\b/.test(os75) && (os75.match(/page\.addView\(/g) || []).length === 1
      && (os75.match(/\bpage\b/g) || []).length === 5 && !/\(a, page\b/.test(os75),
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
    assert.ok(/IvaReviewPatch\.java android\/patches\/IvaPushPatch\.java(?: android\/patches\/Iva\w+Patch\.java)* 2>&1/.test(code) && chk.includes('android/patches/IvaPushPatch.java'), 'push 10.74: IvaPushPatch компилируется в сборку и в проверку');
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
      && /if \(mine != null && i < mine\.size\(\)\)(?: \{)?   \/\/ 10\.68: нажатие на заявку — подробности/.test(op)
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
