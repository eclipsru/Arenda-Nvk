// Сторож «сборка приложения» (05.10.2026, баг 1 владельца: туда-обратно, доставка, публикация младшим админом).
// Ловит: вывод ключа/пароля (set -x, echo, пароль в командной строке), запуск из Pull Request,
// подпись без сверки сертификата с эталоном ключа №6, эталон ≠ памятке, коммит APK не в ветку агента,
// поломку смены версии в двоичном манифесте (проверяется на настоящем манифесте 10.14),
// пропажу любой из 11 точек apply.py (10 видов вызовов IvaPatch и замена текста кнопки доставки), кандидата без нужной версии,
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
  // вставки: вызов IvaPatch в smali (их ровно 10); виньетка добавляется внутри уже имеющейся точки onCreated
  const insertions = ['->onPrice(Ljava/lang/String;)Ljava/lang/String;', '->onSummary(Landroid/app/Activity;)V',
    '->onHub(Landroid/app/Activity;)V', '->publishError(Ljava/lang/String;)Ljava/lang/String;',
    '->netError(Ljava/lang/String;)Ljava/lang/String;', '->onCreated(Landroid/app/Activity;)V',
    '->onSession(Landroid/app/Activity;)V', '->onAvatar(Landroid/app/Activity;Ljava/lang/String;)V',
    '->onLogout(Landroid/app/Activity;)V', '->onTab(Landroid/app/Activity;Ljava/lang/String;)V'];
  // якоря: по ним apply.py находит место вставки (метод, поле «avatar», тело updateSummary-подобных методов)
  const anchors = ["one_method(lines, r' paintDelToggle\\(Z\\)V$')", "one_method(lines, r' logoutLocal\\(\\)V$')",
    "one_method(lines, r' showTab\\(Ljava/lang/String;\\)V$')", 'lambda\\$loadAdminProfile\\$201', '"avatar"'];
  for (const k of [...insertions, ...anchors]) assert.ok(ap.includes(k), `apply.py: нет ${k}`);
  const jp = R('android/patches/IvaPatch.java');
  const jpc = R('android/patches/IvaClientPatch.java');
  // 10.40 — штатная строка «Клиент» остаётся единственной точкой входа, но открывает
  // отдельный полноэкранный экран настроек (не AlertDialog-форму); hook onActivityResult
  // в MainActivity по-прежнему отключён (с 10.39), фото возвращается во вспомогательный Fragment.
  assert.match(code, /android\/patches\/IvaPatch\.java android\/patches\/IvaClientPatch\.java android\/patches\/IvaSearchPatch\.java android\/patches\/IvaChatPatch\.java android\/patches\/IvaOrderPatch\.java android\/patches\/IvaNotifyPatch\.java 2>&1/,
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
    assert.ok(/javac [^\n]*android\/patches\/IvaSearchPatch\.java android\/patches\/IvaChatPatch\.java android\/patches\/IvaOrderPatch\.java android\/patches\/IvaNotifyPatch\.java 2>&1/.test(code), 'build-apk.yml: IvaChatPatch.java не компилируется в сборку');
    assert.ok(chk54.includes('android/patches/IvaSearchPatch.java android/patches/IvaChatPatch.java android/patches/IvaOrderPatch.java android/patches/IvaNotifyPatch.java \\'), 'check.yml: IvaChatPatch.java не компилируется в проверке');
    const onTab = ip.slice(ip.indexOf('public static void onTab('), ip.indexOf('static boolean navSelected('));
    assert.ok(onTab.includes('try { IvaChatPatch.onShowTab(a, tab); } catch (Throwable ignore) {}'), 'IvaPatch.onTab: нет безопасного вызова IvaChatPatch');
    assert.ok(!R('android/patches/apply.py').includes('IvaChatPatch'), 'IvaChatPatch: новые smali-хуки не нужны (вход — IvaPatch.onTab)');
    // штатные поля, методы и ресурсы, к которым чат обращается через отражение, — есть в 10.14
    for (const str of ['chatWith', 'chatTool', 'detItem', 'detOwner', 'chatList', 'chatScroll', 'pageChat', 'chatInput', 'curTab',
      'userEmail', 'userName', 'accessToken', 'isAdminEmail', 'loadChat', 'sendChat', 'showTab', 'loadPhoto', 'playVoice', 'humanError',
      'lambda$setupChat$88$ru-prokatnvsk-app-MainActivity'])
      assert.ok(has(dex14, str), `classes.dex 10.14: нет «${str}» — чат 10.54 не найдёт штатное поле или метод`);
    for (const str of ['detAsk', 'chatSend', 'bg_bubble_me', 'bg_bubble_other', 'bg_avatar', 'bg_card', 'bg_photo', 'bg_badge', 'ic_play', 'ic_wrench'])
      assert.ok(has(arsc14, str), `resources.arsc 10.14: нет «${str}» — чат 10.54 потеряет штатный вид`);
    // «Спросить…» без автосообщения: в нашем обработчике нет POST с текстом «Вопрос по инструменту»; без входа / админу — штатный обработчик
    const ask = chat.slice(chat.indexOf('static void hookAsk('), chat.indexOf('static void nativeAsk('));
    assert.ok(!/Вопрос по инструменту|apiCall|"POST"/.test(ask) && ask.includes('if (!loggedIn(a) || isAdmin(a)) { nativeAsk(a, v); return; }')
      && ask.includes('openThread(a, owner, toolIdOf(tool), tool);') && ask.includes('callTab(a, "chat");'),
      'IvaChatPatch.hookAsk: «Спросить об инструменте» должен открывать пустую переписку без автосообщения');
    // отправка: только нажатие (запись голоса — штатная), tool_id открытой переписки, при отказе базы — без него
    const snd = chat.slice(chat.indexOf('static void hookSend('), chat.indexOf('static boolean ok('));
    assert.ok(snd.includes('send.setOnClickListener(') && !/setOnLongClickListener|setOnTouchListener/.test(snd)
      && snd.includes('b.put("tool_id", tool);') && snd.includes('b.remove("tool_id");')
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
    assert.ok(snd.includes('IvaClientPatch.apiCall(a, "/rest/v1/rpc/submit_order", "POST", body.toString(), true)') && (op.match(/apiCall\(/g) || []).length === 1
      && snd.includes('body.put("p_days", maxDays(ls));') && snd.includes('body.put("p_tools", toolsText(ls));')
      && snd.includes('callNative(a, "sendOrder");                       // штатные') && snd.includes('if (sending) return;'),
      'IvaOrderPatch.send: одна заявка submit_order, p_days — самый длинный срок, без повторной отправки');
    assert.ok(op.includes('((View) send).setOnClickListener(new View.OnClickListener() {\n                @Override public void onClick(View v) { send(a); }')
      && !/setOnLongClickListener|setOnTouchListener|postDelayed\([^)]*send/.test(op), 'IvaOrderPatch: заявка уходит только по нажатию «Отправить заявку»');
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
    assert.ok(!/removeView\(|\.removeViewAt\(/.test(op) && (op.match(/removeAllViews\(\)/g) || []).length === 1 && op.includes('box.removeAllViews();')
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
    assert.ok(code.includes('android/patches/IvaOrderPatch.java android/patches/IvaNotifyPatch.java 2>&1') && chk56.includes('android/patches/IvaNotifyPatch.java \\'),
      'IvaNotifyPatch.java не компилируется в сборке или в проверке');
    assert.ok(!R('android/patches/apply.py').includes('IvaNotifyPatch'), 'IvaNotifyPatch: новые smali-хуки не нужны (вход — IvaPatch.onTab)');
    assert.ok(!/firebase|com\.google\.android\.gms|JobScheduler|AlarmManager|WorkManager/i.test(np), 'IvaNotifyPatch: этап 1 — без Firebase и фоновых служб');
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
    assert.ok(np.includes('if (nw.isEmpty() || inChat || !on(a, K_MSG, admin)) return;') && np.includes('b.setVisibility(Notification.VISIBILITY_PRIVATE);'),
      'уведомления 10.56: в открытом чате без уведомления; на экране блокировки без текста сообщения');
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
