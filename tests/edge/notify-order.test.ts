// Тесты функции уведомлений (этап П5, шаг 1). Запуск: deno test
//
// Проверяем не «строчки в коде», а поведение: что именно уходит в Telegram,
// что происходит при 429/5xx/401, что дубликат не отправляется второй раз
// и что сбой Telegram не теряет заявку в базе (она сохраняется независимо).

globalThis.__ivaNoServe = true;
const mod = await import('../../supabase/functions/notify-order/index.ts');
const {
  esc, truncate, telHref, moscowTime, formatOrderMessage,
  sendTelegram, handleRequest,
} = mod;

type Handler = (url: string, init?: RequestInit) => Response;

const jsonResponse = (status: number, body: unknown): Handler =>
  () => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

function makeFetch(handlers: Handler[]) {
  const calls: Array<{ url: string; init?: RequestInit }> = [];
  let index = 0;
  const fn = (async (input: string | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, init });
    const handler = handlers[Math.min(index, handlers.length - 1)];
    index++;
    if (!handler) throw new Error('неожиданный запрос: ' + url);
    return handler(url, init);
  }) as (input: string | URL, init?: RequestInit) => Promise<Response>;
  return { fn, calls };
}

function assert(condition: unknown, message: string): void {
  if (!condition) throw new Error(message);
}

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) {
    throw new Error(`${message}\n  ожидалось: ${String(expected)}\n  получено:  ${String(actual)}`);
  }
}

const ORDER = {
  id: 12,
  created_at: '2026-10-03T12:07:00Z',
  name: 'Сергей Петров',
  phone: '8 918 000-11-22',
  tools: 'Перфоратор Bosch, Бетономешалка',
  get_method: 'Доставка: Новочеркасск, ул. Ленина, 5',
  address: 'Новочеркасск, ул. Ленина, 5',
  comment: 'Хочу на выходные',
  days: 2,
  user_email: 'client@mail.ru',
};

const ENV = {
  TELEGRAM_BOT_TOKEN: '111:TOKEN',
  TELEGRAM_CHAT_ID: '42',
  WEBHOOK_SECRET: 'test-secret-1',
  TELEGRAM_API_BASE: 'https://api.telegram.test',
};

const request = (record: unknown, secret = 'test-secret-1', method = 'POST') =>
  new Request('https://example.test/functions/v1/notify-order', {
    method,
    headers: { 'content-type': 'application/json', 'x-webhook-secret': secret },
    body: method === 'POST' ? JSON.stringify({ type: 'INSERT', table: 'orders', record }) : undefined,
  });

/* ------------------------------- текст ---------------------------------- */

Deno.test('сообщение содержит всё, по чему можно позвонить и решить', () => {
  const text = formatOrderMessage(ORDER);
  assert(text.includes('Новая заявка №12'), 'нет номера заявки');
  assert(text.includes('Сергей Петров'), 'нет имени клиента');
  assert(text.includes('tel:+79180001122'), 'телефон не оформлен ссылкой для набора (8XXX → +7XXX)');
  assert(text.includes('Перфоратор Bosch'), 'нет инструмента');
  assert(text.includes('Доставка: Новочеркасск'), 'нет способа получения');
  assert(text.includes('2 дня'), 'нет срока аренды (2 → «дня»)');
  assert(text.includes('Хочу на выходные'), 'нет комментария');
  assert(text.includes('03.10.2026, 15:07 МСК'), 'нет времени в московском времени');
});

Deno.test('клиентские данные экранируются и не ломают разметку', () => {
  const text = formatOrderMessage({ ...ORDER, name: '<b>Иван</b> & Co', comment: 'a < b > c' });
  assert(text.includes('&lt;b&gt;Иван&lt;/b&gt; &amp; Co'), 'имя не экранировано');
  assert(!text.includes('<b>Иван'), 'в сообщение попал клиентский HTML');
  assert(text.includes('a &lt; b &gt; c'), 'комментарий не экранирован');
  assertEquals(esc('<&>'), '&lt;&amp;&gt;', 'esc работает неверно');
});

Deno.test('длинный комментарий обрезается и не превращает сообщение в простыню', () => {
  const long = 'я'.repeat(900);
  const text = formatOrderMessage({ ...ORDER, comment: long });
  const line = text.split('\n').find((l) => l.startsWith('💬')) || '';
  assert(line.length <= 305, `строка комментария слишком длинная: ${line.length}`);
  assert(line.endsWith('…'), 'нет многоточия в обрезанном комментарии');
  assertEquals(truncate('  коротко  '), 'коротко', 'truncate не убирает пробелы');
});

Deno.test('пустые поля не ломают сообщение', () => {
  const text = formatOrderMessage({ id: 7, created_at: null });
  assert(text.includes('Новая заявка №7'), 'нет заголовка');
  assert(!text.includes('undefined'), 'в сообщение попало undefined');
  assert(!text.includes('null'), 'в сообщение попало null');
  assert(!text.includes('NaN'), 'в сообщение попало NaN');
});

Deno.test('номер для набора приводится к одному виду', () => {
  assertEquals(telHref('8 918 000-11-22'), '+79180001122', '8XXX не превратился в +7XXX');
  assertEquals(telHref('+7 (918) 000-11-22'), '+79180001122', 'номер с +7 испорчен');
  assertEquals(telHref('3452-55-66-77'), '+3452556677', 'городской номер испорчен');
  assertEquals(telHref(''), '', 'пустой телефон должен давать пустую ссылку');
});

Deno.test('сроки склоняются по-русски', () => {
  const cases: Array<[number, string]> = [[1, '1 день'], [2, '2 дня'], [5, '5 дней'], [11, '11 дней'], [21, '21 день']];
  for (const [days, expected] of cases) {
    const text = formatOrderMessage({ ...ORDER, days });
    assert(text.includes(expected), `для ${days} ожидалось «${expected}»`);
  }
});

Deno.test('время переводится в московское', () => {
  assertEquals(moscowTime('2026-10-03T12:07:00Z'), '03.10.2026, 15:07 МСК', 'МСК-время посчитано неверно');
  assertEquals(moscowTime(''), '', 'пустая дата должна давать пустую строку');
  assertEquals(moscowTime('не дата'), '', 'мусор в дате должен давать пустую строку');
});

/* ----------------------------- отправка --------------------------------- */

Deno.test('успешная отправка: один запрос, правильное тело', async () => {
  const { fn, calls } = makeFetch([jsonResponse(200, { ok: true })]);
  const result = await sendTelegram({
    token: '111:TOKEN', chatId: '42', text: 'привет', apiBase: 'https://api.telegram.test',
    fetchImpl: fn, delayMs: 0,
  });
  assert(result.ok, 'отправка не удалась');
  assertEquals(result.attempts, 1, 'должна быть одна попытка');
  assertEquals(calls.length, 1, 'должен быть один запрос');
  assertEquals(calls[0].url, 'https://api.telegram.test/bot111:TOKEN/sendMessage', 'неверный адрес Telegram');
  const body = JSON.parse(String(calls[0].init?.body || '{}'));
  assertEquals(body.chat_id, '42', 'неверный получатель');
  assertEquals(body.parse_mode, 'HTML', 'нужен режим HTML');
});

Deno.test('429 — уважаем retry_after и повторяем', async () => {
  const { fn, calls } = makeFetch([
    jsonResponse(429, { ok: false, description: 'Too Many Requests', parameters: { retry_after: 0 } }),
    jsonResponse(200, { ok: true }),
  ]);
  const result = await sendTelegram({
    token: 't', chatId: '1', text: 'x', apiBase: 'https://api.telegram.test',
    fetchImpl: fn, delayMs: 0,
  });
  assert(result.ok, 'после 429 отправка должна была пройти со второго раза');
  assertEquals(result.attempts, 2, 'ожидалось две попытки');
  assertEquals(calls.length, 2, 'ожидалось два запроса');
});

Deno.test('500, 500, 200 — три попытки и успех', async () => {
  const { fn, calls } = makeFetch([
    jsonResponse(500, { ok: false }),
    jsonResponse(502, { ok: false }),
    jsonResponse(200, { ok: true }),
  ]);
  const result = await sendTelegram({
    token: 't', chatId: '1', text: 'x', apiBase: 'https://api.telegram.test',
    fetchImpl: fn, delayMs: 0,
  });
  assert(result.ok, 'третья попытка должна была пройти');
  assertEquals(result.attempts, 3, 'ожидалось три попытки');
  assertEquals(calls.length, 3, 'ожидалось три запроса');
});

Deno.test('неверный токен (401) — не повторяем, причина понятна', async () => {
  const { fn, calls } = makeFetch([jsonResponse(401, { ok: false, description: 'Unauthorized' })]);
  const result = await sendTelegram({
    token: 'плохой', chatId: '1', text: 'x', apiBase: 'https://api.telegram.test',
    fetchImpl: fn, delayMs: 0,
  });
  assert(!result.ok, 'отправка не должна была пройти');
  assertEquals(result.attempts, 1, 'при 401 повторять бессмысленно');
  assertEquals(calls.length, 1, 'должен быть один запрос');
  assert(String(result.error).includes('401'), 'в ошибке нет кода ответа Telegram');
});

Deno.test('сеть недоступна — повторяем и честно сообщаем об ошибке', async () => {
  const failing = (async () => { throw new Error('сеть лежит'); }) as (input: string | URL, init?: RequestInit) => Promise<Response>;
  const result = await sendTelegram({
    token: 't', chatId: '1', text: 'x', apiBase: 'https://api.telegram.test',
    fetchImpl: failing, delayMs: 0,
  });
  assert(!result.ok, 'без сети отправка не может удаться');
  assertEquals(result.attempts, 3, 'ожидалось три попытки');
  assert(String(result.error).includes('сеть'), 'в ошибке нет пояснения про сеть');
});

/* ---------------------------- обработчик -------------------------------- */

Deno.test('вебхук без верного секрета не отправляет ничего', async () => {
  const { fn, calls } = makeFetch([jsonResponse(200, { ok: true })]);
  const bad = await handleRequest(request(ORDER, 'wrong-secret'), ENV, fn);
  assertEquals(bad.status, 401, 'чужой секрет должен получать 401');
  const none = await handleRequest(request(ORDER, 'test-secret-1'), { ...ENV, WEBHOOK_SECRET: '' }, fn);
  assertEquals(none.status, 500, 'без настроенного секрета функция должна честно отказать');
  assertEquals(calls.length, 0, 'при неверном секрете в Telegram ходить нельзя');
});

Deno.test('не POST и пустой record — понятные отказы', async () => {
  const { fn } = makeFetch([jsonResponse(200, { ok: true })]);
  assertEquals((await handleRequest(request(null, 'test-secret-1', 'GET'), ENV, fn)).status, 405, 'GET должен отклоняться');
  assertEquals((await handleRequest(request(null, 'test-secret-1'), ENV, fn)).status, 400, 'без record — 400');
  const notJson = new Request('https://example.test/functions/v1/notify-order', {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-webhook-secret': 'test-secret-1' },
    body: 'не json',
  });
  assertEquals((await handleRequest(notJson, ENV, fn)).status, 400, 'не-JSON тело — 400');
});

Deno.test('нормальный проход: заявка уходит в Telegram, ответ 200', async () => {
  const { fn, calls } = makeFetch([jsonResponse(200, { ok: true })]);
  const response = await handleRequest(request(ORDER), ENV, fn);
  assertEquals(response.status, 200, 'ожидался успех');
  const body = await response.json() as Record<string, unknown>;
  assertEquals(body.sent, true, 'в ответе нет подтверждения отправки');
  assertEquals(calls.length, 1, 'должен быть один запрос в Telegram');
});

Deno.test('дубликат: второй раз та же заявка не отправляется', async () => {
  // Первый ответ — от PostgREST (пометка вставлена), второй — от Telegram.
  const first = makeFetch([jsonResponse(201, [{ order_id: 12 }]), jsonResponse(200, { ok: true })]);
  const firstEnv = { ...ENV, SUPABASE_URL: 'https://db.test', SUPABASE_SERVICE_ROLE_KEY: 'service' };
  const ok = await handleRequest(request(ORDER), firstEnv, first.fn);
  assertEquals(ok.status, 200, 'первая отправка должна пройти');

  // Повтор: PostgREST вернул пустой список — значит, пометка уже стояла.
  const second = makeFetch([jsonResponse(201, [])]);
  const again = await handleRequest(request(ORDER), firstEnv, second.fn);
  assertEquals(again.status, 200, 'повтор не должен считаться ошибкой');
  const body = await again.json() as Record<string, unknown>;
  assertEquals(body.skipped, 'duplicate', 'повтор должен быть помечен как дубликат');
  assertEquals(second.calls.length, 1, 'в Telegram при дубликате ходить нельзя');
});

Deno.test('нет сервисного ключа или журнал недоступен — заявка всё равно уходит', async () => {
  const withoutKey = makeFetch([jsonResponse(200, { ok: true })]);
  const r1 = await handleRequest(request(ORDER), ENV, withoutKey.fn);
  assertEquals(r1.status, 200, 'без ключа уведомление всё равно должно уйти');
  assertEquals(withoutKey.calls.length, 1, 'должен быть только запрос в Telegram');

  const brokenLog = makeFetch([jsonResponse(500, { message: 'нет таблицы' }), jsonResponse(200, { ok: true })]);
  const r2 = await handleRequest(request(ORDER), { ...ENV, SUPABASE_URL: 'https://db.test', SUPABASE_SERVICE_ROLE_KEY: 'service' }, brokenLog.fn);
  assertEquals(r2.status, 200, 'при недоступном журнале уведомление всё равно должно уйти');
});

Deno.test('Telegram не принял — вебхук видит ошибку, пометка снимается', async () => {
  const { fn, calls } = makeFetch([
    jsonResponse(201, [{ order_id: 12 }]),                       // пометка поставлена
    jsonResponse(400, { ok: false, description: 'chat not found' }), // Telegram отказал
    jsonResponse(200, []),                                        // пометка снята
  ]);
  const env = { ...ENV, SUPABASE_URL: 'https://db.test', SUPABASE_SERVICE_ROLE_KEY: 'service' };
  const response = await handleRequest(request(ORDER), env, fn);
  assertEquals(response.status, 502, 'ошибка Telegram должна быть видна вебхуку');
  const body = await response.json() as Record<string, unknown>;
  assert(String(body.detail || '').includes('chat not found'), 'в ответе нет причины от Telegram');
  const deleted = calls.some((c) => c.url.includes('notify_log?order_id=eq.12') && c.init?.method === 'DELETE');
  assert(deleted, 'пометка не снята — следующая попытка не сможет отправить');
});
