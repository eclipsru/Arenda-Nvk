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
  assert.match(code, /android\/patches\/IvaPatch\.java android\/patches\/IvaClientPatch\.java 2>&1/,
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
  assert.match(jp, /try \{ IvaClientPatch\.onShowTab\(a, tab\); \} catch \(Throwable ignore\) \{\}\n    \}/, 'IvaPatch.onTab: штатный переход должен прятать раздел клиента');
  assert.match(jpc, /if \(hubVisible\) \{\n\s*detailFromHub = "detail"\.equals\(t\);\n\s*hideClientHub\(a\);/, 'IvaClientPatch: раздел клиента не прячется при штатном переходе');
  assert.match(jpc, /hubStyling = true;\n\s*try \{ IvaPatch\.onTab\(a, HUB_PAGE\); \}[^\n]*finally \{ hubStyling = false; \}/, 'IvaClientPatch: подсветка логотипа не должна прятать сам раздел');
  assert.match(jpc, /favs\.contains\("t" \+ tid\)/, 'IvaClientPatch: «Избранное» должно брать штатный набор favs (ключ t+id)');
  assert.match(jpc, /getDeclaredMethod\("feedCard", JSONObject\.class, boolean\.class\)/, 'IvaClientPatch: карточки раздела — штатный feedCard');
  assert.match(jpc, /"\/rest\/v1\/orders\?select=created_at,tools,status" \+ q/, 'IvaClientPatch: «Мои инструменты» должны читать заявки клиента');
  assert.match(jpc, /"&user_email=eq\." \+ java\.net\.URLEncoder\.encode\(email, "UTF-8"\)/, 'IvaClientPatch: заявки — только свои (user_email)');
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
