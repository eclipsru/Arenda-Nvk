// Сторож «у всех пользователей скачивание приложения не должно ломаться» (11.10.2026).
//
// Зачем появился. Выпущенная для всех версия — это последняя строка таблицы app_releases в базе.
// В строке записано имя файла на сайте (ProkatInstrumenta-<версия>.apk), и именно на этот файл ведут
// зелёная карточка в кабинете и баннер сверху. Убрали файл из корня — у всех битая ссылка «Скачать APK».
// Ровно это чуть не случилось 11.10.2026: при переводе тестового канала на 10.17 из корня убирали
// ProkatInstrumenta-10.16.apk, а он в тот момент БЫЛ выпущенным (владелец подтвердил 10.16). Владелец
// увидел бы сломанное скачивание, сам того не желая (подробности — docs/ЖУРНАЛ.md, 11.10.2026).
//
// База читается так же, как её читает сайт: публичный ключ (лежит в assets/app-release.js, он и так
// открыт), только чтение, ничего не пишем. Нет сети или база недоступна — честный пропуск (код 2),
// а не «зелено»: молчание сети не должно выглядеть как успешная проверка.
//
// Запуск: node tests/released-apk-present.cjs
//   0 — файл выпущенной версии на месте (или выпуска ещё не было)
//   1 — файл пропал: у всех битая ссылка
//   2 — проверить не удалось (нет сети / база недоступна)
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
const ROOT = path.join(__dirname, '..');
const R = p => fs.readFileSync(path.join(ROOT, p), 'utf8');

const rel = R('assets/app-release.js');
const sb = (rel.match(/var IVA_RELEASE_SB = '([^']+)'/) || [])[1];
const key = (rel.match(/var IVA_RELEASE_KEY = '([^']+)'/) || [])[1];
assert.ok(sb, 'assets/app-release.js: не найден адрес базы (IVA_RELEASE_SB)');
assert.ok(key, 'assets/app-release.js: не найден публичный ключ (IVA_RELEASE_KEY)');

(async () => {
  let rows;
  try {
    const ctl = new AbortController(), timer = setTimeout(() => ctl.abort(), 10000);
    const r = await fetch(sb + '/rest/v1/app_releases?select=version_code,version_name,apk_url&order=version_code.desc&limit=1',
      { headers: { apikey: key }, signal: ctl.signal });
    clearTimeout(timer);
    if (!r.ok) throw new Error('HTTP ' + r.status);
    rows = await r.json();
  } catch (e) {
    console.log('ПРОПУСК: база релизов недоступна — проверить не удалось (' + String(e && e.message || e) + ')');
    process.exit(2);
  }
  if (!Array.isArray(rows) || !rows.length) {
    console.log('ПРОПУСК: в app_releases нет записей — выпущенной версии ещё нет, проверять нечего');
    process.exit(2);
  }
  const row = rows[0], url = String(row.apk_url || ''), file = url.split('/').pop();
  assert.match(file, /^ProkatInstrumenta-[\d.]+\.apk$/, `в базе ссылка выпущенной версии не на файл приложения: «${url}»`);
  assert.ok(fs.existsSync(path.join(ROOT, file)),
    `выпущенная версия ${row.version_name} (код ${row.version_code}) ссылается на ${file}, а файла нет в корне — у всех пользователей ссылка «Скачать APK» битая. `
    + 'Верните файл (и строку в app-keep-apks.txt) или подтвердите выпуск новой версии.');
  assert.ok(!/^https?:\/\//.test(url),
    `выпущенная версия ведёт на внешний адрес, а не на файл сайта: «${url}» — подтверждение релиза пишет только имя файла`);
  console.log(`Выпущенная версия ${row.version_name} (код ${row.version_code}): файл ${file} лежит в корне — ссылка «Скачать APK» у всех работает`);
})().catch(e => { console.error('✘ ' + (e && e.message || e)); process.exit(1); });
