// Сторож «сборка приложения» (05.10.2026, баг 1 владельца: туда-обратно, доставка, публикация младшим админом).
// Ловит: вывод ключа/пароля (set -x, echo, пароль в командной строке), запуск из Pull Request,
// подпись без сверки сертификата с эталоном ключа №6, эталон ≠ памятке, коммит APK не в ветку агента,
// поломку смены версии в двоичном манифесте (проверяется на настоящем манифесте 10.14),
// пропажу любой из 5 точек вставки в apply.py, кандидата без нужной версии.
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
  assert.match(code, /if: startsWith\(github\.ref, 'refs\/heads\/arena\/'\)/, `${f}: APK коммитится только в ветки агента arena/…`);
  assert.ok(!/arena\/01a101d5/.test(code), `${f}: осталось имя старой ветки агента — при смене сессии его не поменяли бы`);
  assert.ok(!/git add[^\n]*(out\/|\*|\.p12|\$KS)/.test(code), `${f}: в коммит попадает лишнее`);
  // эталон = памятка = проверка ключа
  const exp = (code.match(/EXPECTED: ([0-9A-F]{64})/) || [])[1];
  const doc = (R('docs/КЛЮЧ-ПОДПИСИ.md').match(/Эталон проверки: `([0-9A-F]{64})`/) || [])[1];
  assert.ok(exp && exp === doc, `${f}: эталон ${exp} ≠ памятке ${doc}`);
  assert.match(R('.github/workflows/keystore-check.yml'), new RegExp('EXPECTED=' + exp));
  // 5 точек вставки
  const ap = R('android/patches/apply.py');
  for (const k of ['->onPrice(Ljava/lang/String;)Ljava/lang/String;', '->onSummary(Landroid/app/Activity;)V',
    "one_method(lines, r' paintDelToggle\\(Z\\)V$')", '->onHub(Landroid/app/Activity;)V', '->publishError(Ljava/lang/String;)Ljava/lang/String;'])
    assert.ok(ap.includes(k), `apply.py: нет вставки ${k}`);
  const jp = R('android/patches/IvaPatch.java');
  for (const k of ['onPrice(String', 'onSummary(final Activity', 'onHub(Activity', 'publishError(String'])
    assert.ok(jp.includes('public static ') && jp.includes(k), `IvaPatch.java: нет метода ${k}`);
  assert.ok(/catch \(Throwable ignore\)/.test(jp), 'IvaPatch: доработка должна молча отступать при сбое, не ломая заявку');
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
  console.log(`Сборка приложения: секреты не выводятся, сертификат сверяется с эталоном ${exp.slice(0, 8)}…, версия ${name} (${vcode}) пишется в манифест 10.14, 5 точек вставки на месте`);
} catch (e) { console.error('✘ ' + e.message); process.exit(1); }
