// 10.133: оплата комиссии через ЮKassa. Проверяет функцию supabase/functions/iva-pay/index.ts на node
// (типы TypeScript снимает сам node), без сети: fetch подменён.
//   • GET — только «подключено / тестовый режим», без ключей;
//   • новая оплата: только вошедший (почта — из Supabase Auth по его ключу), сумма 1–100 000, в ЮKassa — Basic-ключ
//     магазина, Idempotence-Key по номеру оплаты, без чека и без почты; отказ ЮKassa → iva_yk_fail и понятный ответ;
//   • уведомление ЮKassa: итог берётся ТОЛЬКО из нашего GET /v3/payments/{id} — поддельное «succeeded» в теле не
//     засчитывается; ЮKassa недоступна → 500 (ЮKassa повторит); ключей нет → 503;
//   • «check»: незавершённые оплаты вошедшего — по базе (iva_yk_open), итог — у ЮKassa;
//   • ключ базы — штатный секрет Supabase, только в apikey (новый sb_…) или Bearer (прежний JWT); в коде ключей нет.
// Запуск: node tests/iva-pay.cjs
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const root = path.join(__dirname, '..');
const read = (f) => fs.readFileSync(path.join(root, f), 'utf8');

try {
  const ff = 'supabase/functions/iva-pay/index.ts';
  const fn = read(ff);
  assert.ok(!/^\s*import\s/m.test(fn), `${ff}: внешние библиотеки — в среде Deno их работа не проверена`);
  assert.ok(!/sb_secret_|eyJ[A-Za-z0-9_-]{20,}\.|test_[A-Za-z0-9_-]{20,}|live_[A-Za-z0-9_-]{20,}/.test(fn), `${ff}: ключ в коде`);
  assert.ok(!/console\.(log|error)\([^)]*(auth|key|dbKey|me\b|email|Authorization)/.test(fn), `${ff}: ключи или почты пишутся в журнал`);
  assert.ok(!/receipt/.test(fn.replace(/^\s*\/\/.*$/gm, '')), `${ff}: чек в платёж не передаётся (решение владельца — чеки в «Мой налог» вручную)`);
  assert.match(fn, /YK_API \+ path/, `${ff}: запросы к ЮKassa — только через yk()`);
  assert.match(fn, /"Idempotence-Key"/, `${ff}: без Idempotence-Key повтор запроса создаст второй платёж`);
  assert.match(fn, /await settle\(id, auth\)/, `${ff}: уведомление должно проверяться запросом к ЮKassa`);

  const mf = 'supabase/migrations/20261123_yookassa_fee.sql';
  const sql = read(mf);
  assert.match(sql, /ALTER TABLE public\.iva_yk_pay ENABLE ROW LEVEL SECURITY;/, `${mf}: таблица без RLS`);
  assert.match(sql, /REVOKE ALL ON public\.iva_yk_pay FROM public, anon, authenticated;/, `${mf}: таблица открыта на запись`);
  for (const f of ['iva_yk_fee_insert(text, numeric, text)', 'iva_yk_new(text, numeric)', 'iva_yk_set(bigint, text, text)', 'iva_yk_fail(bigint, text)',
    'iva_yk_open(text, bigint)', 'iva_yk_done(text, text, numeric, boolean, text, text)']) {
    assert.ok(sql.includes(`REVOKE ALL ON FUNCTION public.${f} FROM public, anon, authenticated;`), `${mf}: ${f} можно вызвать с сайта / из приложения`);
    assert.ok(sql.includes(`GRANT EXECUTE ON FUNCTION public.${f} TO service_role;`), `${mf}: ${f} не выдана серверу`);
  }
  assert.ok(!/(DROP TABLE|TRUNCATE|DELETE FROM|UPDATE|ALTER TABLE)\s+(IF EXISTS\s+)?(public\.)?fee_payments/i.test(sql.replace(/--.*$/gm, '')), `${mf}: миграция меняет fee_payments`);
  assert.ok(!/'pending'\)?[^;]*into public\.fee_payments|fee_payments \([^)]*\) values \([^)]*'pending'/i.test(sql), `${mf}: онлайн-оплата не должна создавать «pending» в fee_payments`);
  assert.ok(fs.existsSync(path.join(root, 'supabase/migrations/20261123_yookassa_fee_rollback.sql')), `${mf}: нет отката`);
  assert.ok(fs.existsSync(path.join(root, 'tools/yk-pay-stand.py')), 'нет стенда SQL на PostgreSQL');
  const page = read('pay-done.html');
  assert.ok(!/<script/i.test(page) && /noindex/.test(page), 'pay-done.html: без скриптов и не для поисковиков');
  const wf = read('.github/workflows/deploy-functions.yml');
  assert.ok(/YOOKASSA_SHOP_ID=%s/.test(wf) && /YOOKASSA_SECRET_KEY: \$\{\{ secrets\.YOOKASSA_SECRET_KEY \}\}/.test(wf),
    'deploy-functions.yml: ключи ЮKassa не переносятся из секретов GitHub в Supabase');

  const script = String.raw`
    const DBKEY = ['sb', 'secret', 'TESTKEY123'].join('_');
    let envs = {};
    globalThis.Deno = { env: { get: (k) => envs[k] } };
    const calls = [];
    let yk = {};             // ответы ЮKassa: { create: {status, j}, get: {id: {status, j}} }
    let dbFail = {};         // имя rpc → текст ошибки
    let dbRet = {};
    globalThis.fetch = async (url, init = {}) => {
      const c = { url: String(url), method: init.method || 'GET', headers: init.headers || {}, body: init.body };
      calls.push(c);
      const res = (status, j) => ({ ok: status >= 200 && status < 300, status, json: async () => j, text: async () => JSON.stringify(j) });
      if (c.url.endsWith('/auth/v1/user')) return c.headers.Authorization === 'Bearer eyJgood' ? res(200, { email: 'Admin@X.ru' }) : res(401, {});
      if (c.url.includes('/rest/v1/rpc/')) {
        const name = c.url.split('/rpc/')[1];
        if (dbFail[name]) return { ok: false, status: 400, text: async () => dbFail[name] };
        return { ok: true, status: 200, text: async () => JSON.stringify(dbRet[name] ?? null) };
      }
      if (c.url === 'https://api.yookassa.ru/v3/payments' && c.method === 'POST') {
        const cr = typeof yk.create === 'function' ? yk.create(JSON.parse(c.body)) : yk.create;
        return res(cr.status, cr.j);
      }
      if (c.url === 'https://api.yookassa.ru/v3/me') { const g = yk.me || { status: 404, j: {} }; return res(g.status, g.j); }
      const m = c.url.match(/^https:\/\/api\.yookassa\.ru\/v3\/payments\/([^/]+)$/);
      if (m) { const g = yk.get[decodeURIComponent(m[1])]; return g ? res(g.status, g.j) : res(404, {}); }
      return res(599, {});
    };
    const m = await import(process.argv[1]);
    const out = {};
    const req = (method, body, auth) => new Request('https://x.supabase.co/functions/v1/iva-pay', {
      method, headers: Object.assign({ 'Content-Type': 'application/json' }, auth ? { Authorization: auth } : {}), body: body === undefined ? undefined : JSON.stringify(body) });
    const run = async (method, body, auth) => { calls.length = 0; const r = await m.handle(req(method, body, auth)); return { s: r.status, j: await r.json() }; };

    out.amount = ['1 500,50', 1500, '0.99', '100000', '100000.01', '10.555', 'abc', null, -5].map(m.parseAmount);
    out.getNone = await run('GET');
    out.newNone = await run('POST', { a: 'new', amount: 100 }, 'Bearer eyJgood');
    envs = { YOOKASSA_SHOP_ID: '123456', YOOKASSA_SECRET_KEY: 'test_' + 'k'.repeat(30), SUPABASE_SECRET_KEYS: JSON.stringify({ default: DBKEY }) };
    out.getTest = await run('GET');

    out.noAuth = await run('POST', { a: 'new', amount: 100 });
    out.noAuthCalls = calls.length;
    out.badTok = await run('POST', { a: 'new', amount: 100 }, 'Bearer ' + DBKEY);
    out.badTokCalls = calls.map((c) => c.url);
    out.badAmount = await run('POST', { a: 'new', amount: '0,5' }, 'Bearer eyJgood');
    out.badAmountRpc = calls.filter((c) => c.url.includes('/rpc/')).length;

    dbRet = { iva_yk_new: 17 };
    yk = { create: { status: 200, j: { id: '2f6a1b7c-000f-5000-9000-1b68e7b15f3f', status: 'pending', test: true,
      confirmation: { type: 'redirect', confirmation_url: 'https://yoomoney.ru/checkout/payments/v2/contract?orderId=2f6a' } } }, get: {} };
    out.created = await run('POST', { a: 'new', amount: '1 500,5' }, 'Bearer eyJgood');
    const nw = calls.find((c) => c.url.endsWith('/rpc/iva_yk_new'));
    out.newArgs = JSON.parse(nw.body);
    out.dbHeaders = nw.headers;
    const cr = calls.find((c) => c.url === 'https://api.yookassa.ru/v3/payments');
    out.ykAuth = cr.headers.Authorization;
    out.ykIdem = cr.headers['Idempotence-Key'];
    out.ykBody = JSON.parse(cr.body);
    out.setArgs = JSON.parse(calls.find((c) => c.url.endsWith('/rpc/iva_yk_set')).body);

    // 10.135: «Оплатить через СБП» — способ задан заранее; СБП не включена — обычная страница
    const PAY = (url) => ({ status: 200, j: { id: '2f6a1b7c-000f-5000-9000-1b68e7b15f3f', status: 'pending', test: true, confirmation: { type: 'redirect', confirmation_url: url } } });
    yk.create = (b) => PAY(b.payment_method_data ? 'https://yoomoney.ru/checkout/payments/sbp?orderId=2f6a' : 'https://yoomoney.ru/checkout/payments/v2/contract?orderId=2f6a');
    out.sbp = await run('POST', { a: 'new', amount: 100, method: 'sbp' }, 'Bearer eyJgood');
    out.sbpPosts = calls.filter((c) => c.url === 'https://api.yookassa.ru/v3/payments').map((c) => [c.headers['Idempotence-Key'], JSON.parse(c.body).payment_method_data || null]);
    yk.create = (b) => b.payment_method_data ? { status: 400, j: { type: 'error', code: 'invalid_request', description: 'Способ оплаты недоступен' } }
      : PAY('https://yoomoney.ru/checkout/payments/v2/contract?orderId=2f6a');
    out.sbpOff = await run('POST', { a: 'new', amount: 100, method: 'sbp' }, 'Bearer eyJgood');
    out.sbpOffPosts = calls.filter((c) => c.url === 'https://api.yookassa.ru/v3/payments').map((c) => [c.headers['Idempotence-Key'], !!JSON.parse(c.body).payment_method_data]);
    out.sbpOffFail = calls.some((c) => c.url.endsWith('/rpc/iva_yk_fail'));
    yk.create = (b) => b.payment_method_data ? { status: 503, j: {} } : PAY('https://x');
    out.sbpDown = await run('POST', { a: 'new', amount: 100, method: 'sbp' }, 'Bearer eyJgood');
    out.sbpDownPosts = calls.filter((c) => c.url === 'https://api.yookassa.ru/v3/payments').length;

    yk.create = { status: 403, j: { type: 'error', code: 'forbidden', description: 'Магазин не активирован' } };
    out.ykRefused = await run('POST', { a: 'new', amount: 100 }, 'Bearer eyJgood');
    out.failArgs = JSON.parse((calls.find((c) => c.url.endsWith('/rpc/iva_yk_fail')) || { body: 'null' }).body);
    out.failSet = calls.some((c) => c.url.endsWith('/rpc/iva_yk_set'));
    out.refusedMe = calls.filter((c) => c.url.endsWith('/v3/me')).map((c) => [c.method, c.headers.Authorization.startsWith('Basic ')]);

    // 06.11.2026: случай владельца — СБП и обычная страница, обе 403 «Transaction forbidden»; магазин отключён
    const EID = '1f2e3d4c-0000-4000-8000-0123456789ab';
    const FORB = { status: 403, j: { type: 'error', id: EID, code: 'forbidden', description: 'Unspecified error. Transaction forbidden. Contact the Support Service' } };
    yk.create = FORB;
    yk.me = { status: 200, j: { account_id: '123456', test: false, status: 'disabled', payment_methods: ['bank_card', 'sbp'], itn: '7700000000' } };
    out.forbOff = await run('POST', { a: 'new', amount: 5, method: 'sbp' }, 'Bearer eyJgood');
    out.forbOffPosts = calls.filter((c) => c.url === 'https://api.yookassa.ru/v3/payments').length;
    out.forbOffFail = JSON.parse((calls.find((c) => c.url.endsWith('/rpc/iva_yk_fail')) || { body: 'null' }).body);
    yk.me = { status: 200, j: { account_id: '123456', test: false, status: 'enabled', payment_methods: ['bank_card', 'sbp'] } };
    out.forbOn = await run('POST', { a: 'new', amount: 5 }, 'Bearer eyJgood');
    yk.me = { status: 200, j: { account_id: '123456', test: false, status: 'enabled', payment_methods: [] } };
    out.forbNoMethods = await run('POST', { a: 'new', amount: 5 }, 'Bearer eyJgood');
    yk.me = { status: 403, j: { type: 'error', code: 'forbidden' } };
    out.forbNoMe = await run('POST', { a: 'new', amount: 5 }, 'Bearer eyJgood');
    yk.me = undefined;
    yk.create = { status: 401, j: { type: 'error', id: EID, code: 'invalid_credentials', description: 'Login has not been found' } };
    out.badKeys = await run('POST', { a: 'new', amount: 5 }, 'Bearer eyJgood');
    out.badKeysMe = calls.some((c) => c.url.endsWith('/v3/me'));
    yk.create = { status: 200, j: { id: EID, status: 'pending' } };
    out.noUrl = await run('POST', { a: 'new', amount: 5 }, 'Bearer eyJgood');
    out.why = [
      m.ykWhy(429, { type: 'error', code: 'too_many_requests' }).text,
      m.ykWhy(400, { type: 'error', code: 'invalid_request', parameter: 'amount.value', id: 'x y' }).text,
      m.ykWhy(400, { type: 'error', code: 'invalid_request', parameter: '<script>' }).text,
      m.ykWhy(500, {}).text,
      m.ykWhy(403, { type: 'error', code: 'forbidden', id: EID, description: 'x'.repeat(400) }).log.length,
    ];
    out.shop = [m.shopInfo({ status: 'enabled', test: true, payment_methods: ['bank_card', 1, 'Evil<b>'], account_id: '1', itn: '2' }), m.shopInfo({ type: 'error', status: 'x' }), m.shopInfo(null)];

    dbFail = { iva_yk_new: '{"code":"P0001","message":"IVA_TOO_MANY"}' };
    out.tooMany = await run('POST', { a: 'new', amount: 100 }, 'Bearer eyJgood');
    out.tooManyYk = calls.some((c) => c.url.includes('yookassa'));
    dbFail = { iva_yk_new: '{"code":"P0001","message":"IVA_NOT_ADMIN"}' };
    out.notAdmin = await run('POST', { a: 'new', amount: 100 }, 'Bearer eyJgood');
    dbFail = { iva_yk_new: '{"code":"PGRST202","message":"Could not find the function public.iva_yk_new"}' };
    out.noSql = await run('POST', { a: 'new', amount: 100 }, 'Bearer eyJgood');
    dbFail = { iva_yk_open: '{"code":"PGRST202"}' };
    out.noSqlCheck = await run('POST', { a: 'check' }, 'Bearer eyJgood');
    dbFail = {};

    // уведомление: тело врёт «succeeded», ЮKassa говорит «canceled» — засчитывать нельзя
    const PID = '2f6a1b7c-000f-5000-9000-1b68e7b15f3f';
    dbRet = { iva_yk_done: { id: 17, status: 'canceled', amount: 1500.5 } };
    yk.get = { [PID]: { status: 200, j: { id: PID, status: 'canceled', paid: false, amount: { value: '1500.50', currency: 'RUB' },
      cancellation_details: { party: 'yoo_money', reason: 'expired_on_confirmation' } } } };
    out.forged = await run('POST', { type: 'notification', event: 'payment.succeeded', object: { id: PID, status: 'succeeded', paid: true, amount: { value: '1500.50' } } });
    out.forgedDone = JSON.parse(calls.find((c) => c.url.endsWith('/rpc/iva_yk_done')).body);
    out.forgedYkAuth = calls.find((c) => c.url.includes('/v3/payments/')).headers.Authorization;

    yk.get[PID] = { status: 200, j: { id: PID, status: 'succeeded', paid: true, amount: { value: '1500.50', currency: 'RUB' }, payment_method: { type: 'sbp' } } };
    dbRet = { iva_yk_done: { id: 17, status: 'succeeded', amount: 1500.5 } };
    out.paid = await run('POST', { type: 'notification', event: 'payment.succeeded', object: { id: PID } });
    out.paidDone = JSON.parse(calls.find((c) => c.url.endsWith('/rpc/iva_yk_done')).body);

    yk.get[PID] = { status: 200, j: { id: PID, status: 'pending', paid: false, amount: { value: '1500.50' } } };
    out.stillPending = await run('POST', { type: 'notification', event: 'payment.succeeded', object: { id: PID } });
    out.stillPendingDone = calls.some((c) => c.url.endsWith('/rpc/iva_yk_done'));

    yk.get[PID] = { status: 500, j: {} };
    out.ykDown = await run('POST', { type: 'notification', event: 'payment.succeeded', object: { id: PID } });
    out.badNote = await run('POST', { type: 'notification', object: { id: '../../etc' } });
    out.unknownPay = await run('POST', { type: 'notification', event: 'payment.succeeded', object: { id: '00000000-0000-0000-0000-000000000000' } });

    // «check»: незавершённые оплаты вошедшего
    yk.get[PID] = { status: 200, j: { id: PID, status: 'succeeded', paid: true, amount: { value: '1500.50' }, payment_method: { type: 'bank_card' } } };
    dbRet = { iva_yk_open: [{ id: 17, yk_id: PID }], iva_yk_done: { id: 17, status: 'succeeded', amount: 1500.5 } };
    out.check = await run('POST', { a: 'check', id: 17 }, 'Bearer eyJgood');
    out.openArgs = JSON.parse(calls.find((c) => c.url.endsWith('/rpc/iva_yk_open')).body);
    out.checkBadId = await run('POST', { a: 'check', id: 'x' }, 'Bearer eyJgood');

    // прежний ключ базы (JWT) — в Bearer; ключей ЮKassa нет — уведомление 503
    envs = { YOOKASSA_SHOP_ID: '123456', YOOKASSA_SECRET_KEY: 'live_' + 'k'.repeat(30), SUPABASE_SERVICE_ROLE_KEY: 'eyJlegacy' };
    dbRet = { iva_yk_open: [] };
    await run('POST', { a: 'check' }, 'Bearer eyJgood');
    out.legacyHeaders = calls.find((c) => c.url.endsWith('/rpc/iva_yk_open')).headers;
    out.getLive = await run('GET');
    envs = {};
    out.noteNoKeys = await run('POST', { type: 'notification', event: 'payment.succeeded', object: { id: PID } });
    // сайт (06.11.2026): «Внести платёж» в кабинете — CORS только для адресов сайта
    const oreq = (method, origin) => new Request('https://x.supabase.co/functions/v1/iva-pay', { method,
      headers: Object.assign({ 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'authorization, apikey, content-type' }, origin ? { Origin: origin } : {}) });
    const hs = (r) => ({ s: r.status, o: r.headers.get('access-control-allow-origin'), h: r.headers.get('access-control-allow-headers'), m: r.headers.get('access-control-allow-methods') });
    out.corsPre = hs(await m.handle(oreq('OPTIONS', 'https://eclipsru.github.io')));
    out.corsPreGh = hs(await m.handle(oreq('OPTIONS', 'https://raw.githack.com')));
    out.corsPreBad = hs(await m.handle(oreq('OPTIONS', 'https://evil.example')));
    out.corsGet = hs(await m.handle(oreq('GET', 'https://eclipsru.github.io')));
    out.corsGetBad = hs(await m.handle(oreq('GET', 'https://eclipsru.github.io.evil.example')));
    out.put = await run('PUT', {});
    console.log(JSON.stringify(out));
  `;
  const r = spawnSync(process.execPath, ['--experimental-strip-types', '--no-warnings', '--input-type=module', '-e', script, path.join(root, ff)],
    { encoding: 'utf8', timeout: 60000 });
  assert.equal(r.status, 0, `${ff}: не запустилась на node: ${(r.stderr || '').slice(0, 900)}`);
  const o = JSON.parse(r.stdout.trim().split('\n').pop());
  const PID = '2f6a1b7c-000f-5000-9000-1b68e7b15f3f';
  const DBKEY = ['sb', 'secret', 'TESTKEY123'].join('_');
  let n = 0;
  const ok = (cond, msg) => { assert.ok(cond, msg); n++; };
  const eq = (a, b, msg) => { assert.deepEqual(a, b, msg); n++; };

  eq(o.amount, [1500.5, 1500, null, 100000, null, null, null, null, null], 'сумма: «1 500,50», 1–100 000, не больше двух знаков');
  eq(o.getNone, { s: 200, j: { ok: true, ready: false, test: false } }, 'GET без ключей — «не подключено» (проверка выкладки — 200)');
  eq(o.newNone, { s: 503, j: { error: 'not_ready' } }, 'без ключей оплату не создать');
  eq(o.getTest, { s: 200, j: { ok: true, ready: true, test: true } }, 'GET с тестовыми ключами — «подключено, тестовый режим»');
  eq(o.noAuth, { s: 401, j: { error: 'auth' } }, 'без входа — отказ');
  eq(o.noAuthCalls, 0, 'без входа — никуда не ходим');
  ok(o.badTok.s === 401 && !o.badTokCalls.some((u) => u.includes('/auth/v1/user')), 'не ключ входа (не JWT) — отказ без запроса к Auth');
  ok(o.badAmount.s === 400 && o.badAmount.j.error === 'bad_amount' && o.badAmountRpc === 0, 'сумма меньше 1 ₽ — отказ до базы');
  eq(o.created, { s: 200, j: { id: 17, url: 'https://yoomoney.ru/checkout/payments/v2/contract?orderId=2f6a', test: true, sbp: false, fallback: false } }, 'новая оплата → номер и ссылка ЮKassa');
  eq(o.sbp, { s: 200, j: { id: 17, url: 'https://yoomoney.ru/checkout/payments/sbp?orderId=2f6a', test: true, sbp: true, fallback: false } }, '10.135: «через СБП» — сразу страница СБП');
  eq(o.sbpPosts, [['iva-pay-17-sbp', { type: 'sbp' }]], '10.135: в ЮKassa — payment_method_data sbp, свой ключ повтора');
  eq(o.sbpOff, { s: 200, j: { id: 17, url: 'https://yoomoney.ru/checkout/payments/v2/contract?orderId=2f6a', test: true, sbp: false, fallback: true } }, '10.135: СБП не включена — обычная страница, приложение узнает');
  ok(JSON.stringify(o.sbpOffPosts) === JSON.stringify([['iva-pay-17-sbp', true], ['iva-pay-17', false]]) && !o.sbpOffFail, '10.135: вторая попытка — без способа и со своим ключом; оплата не отменена');
  ok(o.sbpDown.s === 502 && o.sbpDown.j.error === 'yookassa' && o.sbpDownPosts === 1, '10.135: ЮKassa недоступна (5xx) — без второй попытки, ошибка');
  eq(o.newArgs, { p_email: 'admin@x.ru', p_amount: 1500.5 }, 'почта — из Supabase Auth (не из запроса), сумма разобрана');
  ok(o.dbHeaders.apikey === DBKEY && !o.dbHeaders.Authorization, 'ключ базы (новый sb_…) — только в apikey');
  eq(o.ykAuth, 'Basic ' + Buffer.from('123456:test_' + 'k'.repeat(30)).toString('base64'), 'ЮKassa: Basic shopId:secretKey');
  eq(o.ykIdem, 'iva-pay-17', 'Idempotence-Key — по номеру оплаты (повтор не создаст второй платёж)');
  eq(o.ykBody, { amount: { value: '1500.50', currency: 'RUB' }, capture: true,
    confirmation: { type: 'redirect', return_url: 'https://eclipsru.github.io/Arenda-Nvk/pay-done.html?p=17' },
    description: 'Комиссия площадки «Ива», платёж №17', metadata: { iva_id: '17' } }, 'тело платежа: сумма, сразу списать, возврат на сайт; без чека и почты');
  eq(o.setArgs, { p_id: 17, p_yk_id: PID, p_url: 'https://yoomoney.ru/checkout/payments/v2/contract?orderId=2f6a' }, 'номер ЮKassa записан в базу');
  eq(o.ykRefused, { s: 502, j: { error: 'yookassa', message: 'ЮKassa запретила платежи в магазине площадки «Ива». Владельцу площадки: проверьте магазин в кабинете ЮKassa или напишите в поддержку ЮKassa', code: 'forbidden', eid: '' } },
    'ЮKassa отказала (403) — причина по-русски; настройки магазина не узнать — общая подсказка');
  ok(o.failArgs && o.failArgs.p_reason === 'forbidden 403: Магазин не активирован' && !o.failSet, 'отказ ЮKassa → iva_yk_fail с кодом и описанием ЮKassa, без iva_yk_set');
  eq(o.refusedMe, [['GET', true]], '403 — один раз спросили у ЮKassa настройки магазина (GET /v3/me, ключ магазина)');
  const EID = '1f2e3d4c-0000-4000-8000-0123456789ab';
  eq(o.forbOff, { s: 502, j: { error: 'yookassa', message: 'ЮKassa запретила платежи в магазине площадки «Ива» — магазин отключён в ЮKassa. Владельцу площадки: включите магазин в кабинете ЮKassa или напишите в поддержку ЮKassa (номер ошибки ЮKassa: ' + EID + ')',
    code: 'forbidden', eid: EID, shop: { status: 'disabled', test: false, methods: ['bank_card', 'sbp'] } } }, '06.11: «Transaction forbidden», магазин отключён — так и сказано, с номером ошибки; ИНН и номер магазина не отдаются');
  eq(o.forbOffPosts, 2, '06.11: СБП отказана — вторая попытка обычной страницей, как раньше');
  eq(o.forbOffFail && o.forbOffFail.p_reason, 'forbidden 403: Unspecified error. Transaction forbidden. Contact the Support Service · ' + EID + ' · магазин disabled [bank_card,sbp]', '06.11: в базе — код, описание ЮKassa, номер ошибки и состояние магазина');
  ok(/магазин включён, но операции запрещены/.test(o.forbOn.j.message) && o.forbOn.j.message.endsWith('(номер ошибки ЮKassa: ' + EID + ')'), 'магазин включён, но 403 — «напишите в поддержку» и номер ошибки');
  ok(/не включён ни один способ оплаты/.test(o.forbNoMethods.j.message), 'магазин без способов оплаты — подсказка включить карты и СБП');
  ok(/проверьте магазин в кабинете ЮKassa/.test(o.forbNoMe.j.message) && !('shop' in o.forbNoMe.j) && o.forbNoMe.j.eid === EID, 'настройки магазина ЮKassa не отдала — общая подсказка, номер ошибки на месте');
  ok(o.badKeys.s === 502 && /не приняла ключи магазина/.test(o.badKeys.j.message) && o.badKeys.j.code === 'invalid_credentials' && !o.badKeysMe, '401 — «проверьте ключи», настройки не спрашиваем');
  ok(o.noUrl.s === 502 && /не прислала ссылку/.test(o.noUrl.j.message) && o.noUrl.j.eid === '', 'ЮKassa ответила без ссылки — понятная ошибка; номер платежа не выдаётся за номер ошибки');
  ok(o.why[0] === 'ЮKassa просит подождать — попробуйте через минуту' && o.why[1] === 'ЮKassa отклонила платёж (поле amount.value) — попробуйте другую сумму или способ оплаты'
    && !/script/.test(o.why[2]) && /временно не отвечает/.test(o.why[3]) && o.why[4] === 200, 'причины 429 / 400 / 5xx по-русски; поле — только безопасное; запись в базу ≤200 знаков');
  eq(o.shop, [{ status: 'enabled', test: true, methods: ['bank_card'] }, null, null], 'настройки магазина — только статус, тест и способы оплаты');
  ok(/временно не отвечает/.test(o.sbpDown.j.message), 'ЮKassa недоступна — «временно не отвечает»');
  ok(o.tooMany.s === 429 && o.tooMany.j.error === 'too_many' && !o.tooManyYk, '«слишком часто» из базы — 429, в ЮKassa не идём');
  eq(o.notAdmin, { s: 403, j: { error: 'not_admin' } }, 'не админ — 403');
  eq(o.noSql, { s: 503, j: { error: 'not_ready' } }, 'ключи есть, SQL 1123 не выполнен — «не подключено»');
  eq(o.noSqlCheck, { s: 503, j: { error: 'not_ready' } }, '«check» без SQL 1123 — «не подключено», без сбоя');
  eq(o.forged.s, 200, 'уведомление обработано');
  eq(o.forgedDone, { p_yk_id: PID, p_status: 'canceled', p_amount: 1500.5, p_paid: false, p_method: '', p_reason: 'expired_on_confirmation' },
    'поддельное «succeeded» в теле уведомления не засчитано: в базу — то, что ответила ЮKassa');
  ok(o.forgedYkAuth.startsWith('Basic '), 'статус спрашиваем у ЮKassa с ключом магазина');
  eq(o.paid, { s: 200, j: { ok: true } }, 'оплачено — 200');
  eq(o.paidDone, { p_yk_id: PID, p_status: 'succeeded', p_amount: 1500.5, p_paid: true, p_method: 'sbp', p_reason: '' }, 'оплачено → iva_yk_done(succeeded, paid, сумма, СБП)');
  ok(o.stillPending.s === 200 && !o.stillPendingDone, 'ЮKassa: ещё не оплачено — базу не трогаем');
  eq(o.ykDown, { s: 500, j: { error: 'retry' } }, 'ЮKassa недоступна — 500, ЮKassa повторит уведомление');
  eq(o.badNote.s, 400, 'номер платежа не похож на номер ЮKassa — отказ');
  eq(o.unknownPay, { s: 200, j: { ok: true } }, 'не наш платёж (ЮKassa: 404) — 200 без записи');
  eq(o.check, { s: 200, j: { rows: [{ id: 17, status: 'succeeded', amount: 1500.5 }] } }, '«check» — итог от ЮKassa');
  eq(o.openArgs, { p_email: 'admin@x.ru', p_id: 17 }, '«check» — только свои оплаты (почта из Auth)');
  eq(o.checkBadId.s, 400, '«check» с неверным номером — отказ');
  ok(o.legacyHeaders.apikey === 'eyJlegacy' && o.legacyHeaders.Authorization === 'Bearer eyJlegacy', 'прежний ключ базы (JWT) — в apikey и Bearer');
  eq(o.getLive, { s: 200, j: { ok: true, ready: true, test: false } }, 'боевые ключи — не тестовый режим');
  eq(o.noteNoKeys, { s: 503, j: { error: 'not_ready' } }, 'уведомление без ключей — 503 (ЮKassa повторит позже)');
  eq(o.corsPre, { s: 204, o: 'https://eclipsru.github.io', h: 'authorization, apikey, content-type, x-client-info', m: 'GET, POST, OPTIONS' }, 'сайт: предварительный запрос браузера — 204 с разрешением для адреса сайта');
  eq(o.corsPreGh.o, 'https://raw.githack.com', 'предпросмотр владельца (raw.githack.com) — тоже можно');
  ok(o.corsPreBad.s === 204 && o.corsPreBad.o === null, 'чужой адрес — без разрешения (браузер не пустит)');
  ok(o.corsGet.s === 200 && o.corsGet.o === 'https://eclipsru.github.io', 'обычный ответ сайту — с разрешением');
  eq(o.corsGetBad.o, null, 'похожий чужой адрес (…github.io.evil.example) — без разрешения');
  eq(o.put.s, 405, 'другие методы — 405');
  console.log(`Оплата ЮKassa: ${n} проверок функции iva-pay пройдено, SQL и выкладка — на месте`);
} catch (e) { console.error('✘ ' + e.message); process.exit(1); }
