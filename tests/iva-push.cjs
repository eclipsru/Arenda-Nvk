// 10.74: мгновенные уведомления админам (FCM). Проверяет функцию supabase/functions/iva-push/index.ts
// на node (типы TypeScript снимает сам node), без сети: fetch подменён.
//   • подпись JWT RS256 сверяется открытым ключом; поля — как требует Google OAuth для FCM;
//   • настройки для приложения берутся только из клиента ru.prokatnvsk.app в google-services.json;
//   • POST: адресаты — только из базы по пропуску; в push нет текста (только t=m/o);
//     мёртвые токены уходят в iva_push_done, живые — нет; неверный пропуск — отказ без обращения к Google;
//   • SQL 20261014_push_admins.sql: закрытые таблицы, только админам, сбои глушатся в триггерах.
// Запуск: node tests/iva-push.cjs
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const root = path.join(__dirname, '..');
const read = (f) => fs.readFileSync(path.join(root, f), 'utf8');

try {
  const ff = 'supabase/functions/iva-push/index.ts';
  const fn = read(ff);
  assert.ok(!/^\s*import\s/m.test(fn), `${ff}: внешние библиотеки — в среде Deno их работа не проверена`);
  assert.ok(!/sb_secret_|service_role|eyJ[A-Za-z0-9_-]{20,}\.|BEGIN PRIVATE KEY-----\s*[A-Za-z0-9+/]{20}/.test(fn), `${ff}: секретный ключ в коде`);
  assert.ok(!/console\.(log|error)\([^)]*(tokens|\bt\b|pass|private_key|access_token)/.test(fn), `${ff}: токены или ключи пишутся в журнал`);
  assert.ok(!/notification\s*:/.test(fn), `${ff}: push должен быть только сигналом (data), без notification с текстом`);
  assert.match(fn, /rpc\("iva_push_take"/, `${ff}: адресаты должны браться из базы по пропуску`);
  assert.match(fn, /\/\^\[0-9a-f\]\{64\}\$\/\.test\(pass\)/, `${ff}: не проверяется формат пропуска`);

  const mf = 'supabase/migrations/20261014_push_admins.sql';
  const sql = read(mf);
  for (const t of ['iva_push_tokens', 'iva_push_jobs']) {
    assert.match(sql, new RegExp(`ALTER TABLE public\\.${t} ENABLE ROW LEVEL SECURITY;`), `${mf}: ${t} без RLS`);
    assert.match(sql, new RegExp(`REVOKE ALL ON public\\.${t} FROM anon, authenticated;`), `${mf}: ${t} открыта сайту`);
  }
  assert.ok(!/CREATE POLICY/i.test(sql), `${mf}: политики на таблицы push не нужны — читают только функции базы`);
  const reg = sql.slice(sql.indexOf('FUNCTION public.iva_push_register('), sql.indexOf('FUNCTION public.iva_push_forget('));
  assert.ok(reg.includes("'not_admin'") && reg.includes("a.role in ('admin', 'chief')") && reg.includes("coalesce(a.active, false)"),
    `${mf}: регистрировать токен — только админам (решение владельца 14.10)`);
  assert.match(sql, /GRANT EXECUTE ON FUNCTION public\.iva_push_register\(text\) TO authenticated;/, `${mf}: регистрация — только после входа`);
  assert.match(sql, /REVOKE ALL ON FUNCTION public\.iva_push_enqueue\(text, text\) FROM public, anon, authenticated;/, `${mf}: ставить сигналы может только база`);
  for (const f of ['iva_push_on_message', 'iva_push_on_part']) {
    const body = sql.slice(sql.indexOf(`FUNCTION public.${f}(`));
    assert.ok(body.slice(0, 700).includes('exception when others then'), `${mf}: ${f} — сбой push не должен мешать сообщению или заявке`);
  }
  assert.match(sql, /AFTER INSERT ON public\.app_messages/, `${mf}: нет триггера на сообщения`);
  assert.match(sql, /AFTER INSERT ON public\.order_parts/, `${mf}: нет триггера на заявки`);
  assert.ok(!/DROP TABLE|TRUNCATE|DELETE FROM public\.(app_messages|order_parts|orders)/i.test(sql), `${mf}: миграция что-то удаляет из рабочих таблиц`);
  assert.ok(!/sb_secret_|service_role/.test(sql), `${mf}: секретный ключ в миграции`);

  // функция — на node, без сети
  const script = String.raw`
    const { generateKeyPairSync, createVerify } = await import('node:crypto');
    const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
    const pem = privateKey.export({ type: 'pkcs8', format: 'pem' });
    const sa = { project_id: 'iva-prokat', client_email: 'fcm@iva-prokat.iam.gserviceaccount.com', private_key: pem, token_uri: 'https://oauth2.googleapis.com/token' };
    const gs = { project_info: { project_id: 'iva-prokat', project_number: '123456789012' }, client: [
      { client_info: { mobilesdk_app_id: '1:123456789012:android:aaaa', android_client_info: { package_name: 'ru.other.app' } }, api_key: [{ current_key: 'K-other' }] },
      { client_info: { mobilesdk_app_id: '1:123456789012:android:0a1b2c3d', android_client_info: { package_name: 'ru.prokatnvsk.app' } }, api_key: [{ current_key: 'AIzaSyIva' }] } ] };
    const envs = { FCM_SERVICE_ACCOUNT_B64: Buffer.from(JSON.stringify(sa)).toString('base64'), FCM_GOOGLE_SERVICES: JSON.stringify(gs) };
    globalThis.Deno = { env: { get: (k) => envs[k] } };
    const calls = [];
    let fcmCount = 0;
    globalThis.fetch = async (url, init = {}) => {
      const body = init.body ? String(init.body) : '';
      calls.push({ url: String(url), body, headers: init.headers || {} });
      const res = (status, obj) => new Response(typeof obj === 'string' ? obj : JSON.stringify(obj), { status });
      if (String(url).endsWith('/rpc/iva_push_take')) {
        const a = JSON.parse(body);
        return res(200, a.p_pass === 'a'.repeat(64) ? { kind: 'o', tokens: ['tokLIVE', 'tokDEAD', 'tokERR'] } : 'null');
      }
      if (String(url).endsWith('/rpc/iva_push_done')) return res(200, '');
      if (String(url) === 'https://oauth2.googleapis.com/token') return res(200, { access_token: 'ya29.test', expires_in: 3599 });
      if (String(url).includes('fcm.googleapis.com')) {
        fcmCount++;
        const t = JSON.parse(body).message.token;
        if (t === 'tokLIVE') return res(200, { name: 'projects/iva-prokat/messages/1' });
        if (t === 'tokDEAD') return res(404, { error: { status: 'NOT_FOUND', details: [{ errorCode: 'UNREGISTERED' }] } });
        return res(500, { error: { status: 'INTERNAL' } });
      }
      return res(599, 'unexpected ' + url);
    };
    const m = await import(process.argv[1]);
    const out = {};
    // подпись
    const jwt = await m.signJwt(sa, 1700000000);
    const [h, c, s] = jwt.split('.');
    const v = createVerify('RSA-SHA256'); v.update(h + '.' + c);
    out.sigOk = v.verify(publicKey, Buffer.from(s, 'base64url'));
    out.head = JSON.parse(Buffer.from(h, 'base64url'));
    out.claims = JSON.parse(Buffer.from(c, 'base64url'));
    out.cfg = m.clientConfig(gs);
    out.cfgBad = m.clientConfig({ project_info: { project_id: 'p', project_number: '1' }, client: [] });
    out.dead = [m.deadToken(404, ''), m.deadToken(400, 'UNREGISTERED'), m.deadToken(400, 'The registration token is not a valid FCM registration token'), m.deadToken(400, 'INVALID_ARGUMENT: data must be strings'), m.deadToken(500, '')];
    out.msg = m.fcmMessage('T', 'm');
    // GET
    out.getPlain = await (await m.handle(new Request('https://x/functions/v1/iva-push'))).json();
    out.getCfg = await (await m.handle(new Request('https://x/functions/v1/iva-push?cfg=1'))).json();
    // POST: неверный пропуск — без Google; верный — рассылка
    const bad = await m.handle(new Request('https://x/', { method: 'POST', body: JSON.stringify({ id: 5, pass: 'zz' }) }));
    out.badStatus = bad.status; out.callsAfterBad = calls.length;
    const foreign = await (await m.handle(new Request('https://x/', { method: 'POST', body: JSON.stringify({ id: 5, pass: 'b'.repeat(64) }) }))).json();
    out.foreign = foreign; out.fcmAfterForeign = fcmCount;
    const errs = []; const ce = console.error; console.error = (x) => errs.push(String(x));
    out.post = await (await m.handle(new Request('https://x/', { method: 'POST', body: JSON.stringify({ id: 7, pass: 'a'.repeat(64) }) }))).json();
    console.error = ce;
    out.errs = errs;
    out.done = JSON.parse(calls.filter((x) => x.url.endsWith('/rpc/iva_push_done')).pop().body);
    out.fcmBodies = calls.filter((x) => x.url.includes('fcm.googleapis.com')).map((x) => JSON.parse(x.body));
    out.fcmUrl = calls.find((x) => x.url.includes('fcm.googleapis.com')).url;
    out.fcmAuth = calls.find((x) => x.url.includes('fcm.googleapis.com')).headers.Authorization;
    out.oauthBody = calls.find((x) => x.url === 'https://oauth2.googleapis.com/token').body;
    console.log(JSON.stringify(out));
  `;
  const r = spawnSync(process.execPath, ['--experimental-strip-types', '--no-warnings', '--input-type=module', '-e', script, path.join(root, ff)],
    { encoding: 'utf8', timeout: 60000 });
  assert.equal(r.status, 0, `${ff}: не запустилась на node: ${(r.stderr || '').slice(0, 600)}`);
  const o = JSON.parse(r.stdout.trim().split('\n').pop());
  assert.equal(o.sigOk, true, 'подпись JWT RS256 не сходится с открытым ключом');
  assert.deepEqual(o.head, { alg: 'RS256', typ: 'JWT' });
  assert.deepEqual(o.claims, { iss: 'fcm@iva-prokat.iam.gserviceaccount.com', scope: 'https://www.googleapis.com/auth/firebase.messaging',
    aud: 'https://oauth2.googleapis.com/token', iat: 1700000000, exp: 1700003600 }, 'поля JWT для Google OAuth');
  assert.ok(o.oauthBody.startsWith('grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=ey'), 'обмен JWT на токен Google');
  assert.deepEqual(o.cfg, { project_id: 'iva-prokat', sender_id: '123456789012', app_id: '1:123456789012:android:0a1b2c3d', api_key: 'AIzaSyIva' },
    'настройки — только клиента ru.prokatnvsk.app');
  assert.equal(o.cfgBad, null, 'неполные настройки — null');
  assert.deepEqual(o.dead, [true, true, true, false, false], 'мёртвые токены: 404/UNREGISTERED/не FCM-токен; ошибки нашего запроса и сбои Google — не повод удалять');
  assert.deepEqual(o.msg, { message: { token: 'T', data: { t: 'm' }, android: { priority: 'HIGH', ttl: '600s', collapse_key: 'iva_m' } } }, 'сообщение FCM');
  assert.deepEqual(o.getPlain, { ok: true, ready: true }, 'GET без параметров — только да/нет (проверка выкладки)');
  assert.deepEqual(o.getCfg, { ready: true, ...o.cfg }, 'GET ?cfg=1 — публичные настройки');
  assert.equal(o.badStatus, 400); assert.equal(o.callsAfterBad, 0, 'неверный пропуск — ни базы, ни Google');
  assert.deepEqual(o.foreign, { skip: true }); assert.equal(o.fcmAfterForeign, 0, 'чужой пропуск — Google не вызывается');
  assert.deepEqual(o.post, { sent: 1, removed: 1 }, 'POST: один доставлен, один мёртвый');
  assert.deepEqual(o.done, { p_id: 7, p_pass: 'a'.repeat(64), p_sent: 1, p_bad: ['tokDEAD'], p_err: 'FCM: HTTP 500' }, 'итог в базу: мёртвый токен — на удаление, сбойный — нет');
  assert.equal(o.fcmBodies.length, 3);
  for (const b of o.fcmBodies) assert.deepEqual(Object.keys(b.message.data), ['t'], 'в push только вид сигнала, без текста');
  assert.equal(o.fcmUrl, 'https://fcm.googleapis.com/v1/projects/iva-prokat/messages:send');
  assert.equal(o.fcmAuth, 'Bearer ya29.test');
  assert.ok(o.errs.length === 1 && !/tok(LIVE|DEAD|ERR)|ya29|@/.test(o.errs[0]), 'в журнале функции нет токенов и адресов');
  console.log('Push 10.74: подпись RS256 верна, настройки только ru.prokatnvsk.app, адресаты по пропуску, в push нет текста, мёртвые токены удаляются, SQL закрыт');
} catch (e) {
  console.error('✘ ' + e.message);
  process.exit(1);
}
