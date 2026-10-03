/* ============================================================================
   Ива — уведомление о новой заявке в Telegram (этап П5, шаг 1).

   Как это работает:
   в таблице orders появляется строка (клиент отправил заявку с сайта) →
   вебхук Supabase вызывает эту функцию → функция собирает сообщение и
   отправляет владельцу в Telegram. Бот ничего не читает и не имеет доступа
   к аккаунту — он только доставляет одно сообщение в указанный чат.

   Секреты задаются в Supabase → Edge Functions → Secrets и в код не попадают:
     TELEGRAM_BOT_TOKEN — токен бота от @BotFather;
     TELEGRAM_CHAT_ID   — куда слать (личный чат владельца с ботом);
     WEBHOOK_SECRET     — своя длинная случайная строка. Такая же строка
                          указывается заголовком x-webhook-secret в вебхуке
                          базы. Без неё функция не работает: иначе кто угодно,
                          узнав адрес функции, мог бы слать вам ложные «заявки».

   Надёжность (проверяется тестами tests/edge/notify-order.test.ts):
     • Telegram ответил 429 или 5xx — повторяем до 3 раз (уважаем retry_after);
     • Telegram недоступен совсем — вебхук получает 502, в логах видно причину;
     • повторная доставка того же заказа — второй раз не шлём (таблица notify_log);
     • нет сервисного ключа или таблицы notify_log — шлём всё равно (лучше
       дубль, чем молчание);
     • отправка не удалась — запись в notify_log снимается, чтобы следующий
       запуск мог отправить снова.

   Заявка сохраняется в базе независимо от этой функции: если Telegram лёг,
   клиент всё равно увидит «Заявка принята», а заявка останется в кабинете.
   ============================================================================ */

export interface OrderRecord {
  id?: number | string | null;
  created_at?: string | null;
  status?: string | null;
  name?: string | null;
  phone?: string | null;
  tools?: string | null;
  get_method?: string | null;
  address?: string | null;
  comment?: string | null;
  days?: number | string | null;
  user_email?: string | null;
}

export interface WebhookPayload {
  type?: string;
  table?: string;
  schema?: string;
  record?: OrderRecord | null;
}

export interface Env {
  TELEGRAM_BOT_TOKEN?: string;
  TELEGRAM_CHAT_ID?: string;
  WEBHOOK_SECRET?: string;
  TELEGRAM_API_BASE?: string;
  SUPABASE_URL?: string;
  SUPABASE_SERVICE_ROLE_KEY?: string;
  SUPABASE_SECRET_KEY?: string;
}

export type FetchLike = (input: string | URL, init?: RequestInit) => Promise<Response>;

/* ------------------------------- мелочи ---------------------------------- */

/** Экранирование для parse_mode=HTML: клиентские данные не должны ломать разметку. */
export function esc(value: unknown): string {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

/** Длинный комментарий режем, чтобы сообщение не превращалось в простыню. */
export function truncate(value: string, max = 300): string {
  const text = value.trim();
  if (text.length <= max) return text;
  return text.slice(0, max - 1).trimEnd() + '…';
}

/** Ссылка tel: для кнопки-набора номера. 8XXX → +7XXX, прочее — с плюсом. */
export function telHref(phone: string): string {
  const digits = String(phone || '').replace(/\D/g, '');
  if (!digits) return '';
  if (digits.length === 11 && digits.startsWith('8')) return '+7' + digits.slice(1);
  return '+' + digits.replace(/^\+/, '');
}

/** Время заявки в московском времени — владельцу так понятнее. */
export function moscowTime(iso?: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const parts = new Intl.DateTimeFormat('ru-RU', {
    timeZone: 'Europe/Moscow',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).formatToParts(d);
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? '';
  return `${get('day')}.${get('month')}.${get('year')}, ${get('hour')}:${get('minute')} МСК`;
}

export function pluralDays(days: number): string {
  const n = Math.abs(days) % 100;
  const n1 = n % 10;
  if (n > 10 && n < 20) return 'дней';
  if (n1 === 1) return 'день';
  if (n1 >= 2 && n1 <= 4) return 'дня';
  return 'дней';
}

/* --------------------------- текст сообщения ----------------------------- */

export function formatOrderMessage(record: OrderRecord): string {
  const lines: string[] = [];
  lines.push(`🆕 <b>Новая заявка №${esc(record.id ?? '—')}</b>`);
  lines.push('');

  const name = (record.name || '').trim();
  const phone = (record.phone || '').trim();
  if (name) lines.push(`👤 <b>${esc(name)}</b>`);
  if (phone) {
    const href = telHref(phone);
    lines.push(`📞 ${href ? `<a href="tel:${esc(href)}">${esc(phone)}</a>` : esc(phone)}`);
  }
  if (record.tools) lines.push(`🧰 ${esc(record.tools)}`);
  if (record.get_method) lines.push(`🚚 ${esc(record.get_method)}`);

  const days = Number(record.days);
  if (Number.isFinite(days) && days > 0) lines.push(`📅 ${days} ${pluralDays(days)}`);

  const comment = (record.comment || '').trim();
  if (comment) lines.push(`💬 ${esc(truncate(comment))}`);

  const when = moscowTime(record.created_at);
  if (when) lines.push(`🕒 ${esc(when)}`);

  lines.push('');
  lines.push('Свяжитесь с клиентом и подтвердите заявку.');
  return lines.join('\n');
}

/* ------------------------------ отправка --------------------------------- */

export interface SendResult {
  ok: boolean;
  attempts: number;
  status?: number;
  error?: string;
}

export interface SendOptions {
  token: string;
  chatId: string;
  text: string;
  apiBase?: string;
  fetchImpl: FetchLike;
  attempts?: number;
  /** пауза перед повтором, мс; в тестах передаём 0 */
  delayMs?: number;
  sleep?: (ms: number) => Promise<void>;
}

const defaultSleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

export async function sendTelegram(options: SendOptions): Promise<SendResult> {
  const {
    token, chatId, text,
    apiBase = 'https://api.telegram.org',
    fetchImpl,
    attempts = 3,
    delayMs = 500,
    sleep = defaultSleep,
  } = options;

  const url = `${apiBase.replace(/\/+$/, '')}/bot${token}/sendMessage`;
  const body = JSON.stringify({
    chat_id: chatId,
    text,
    parse_mode: 'HTML',
    disable_web_page_preview: true,
  });

  let lastError = '';
  let lastStatus = 0;

  for (let attempt = 1; attempt <= attempts; attempt++) {
    let response: Response | null = null;
    try {
      response = await fetchImpl(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body,
      });
    } catch (err) {
      lastError = 'сеть: ' + (err instanceof Error ? err.message : String(err));
      lastStatus = 0;
    }

    if (response) {
      lastStatus = response.status;
      if (response.ok) return { ok: true, attempts: attempt, status: response.status };

      let description = '';
      try {
        const parsed = await response.json() as { description?: string };
        description = parsed?.description || '';
      } catch {
        description = '';
      }
      lastError = `Telegram ответил ${response.status}${description ? ': ' + description : ''}`;

      // 401/403/400 — токен или chat_id неверные, повторять бессмысленно.
      if (response.status !== 429 && response.status < 500) {
        return { ok: false, attempts: attempt, status: response.status, error: lastError };
      }

      // 429 — Telegram сам говорит, сколько ждать.
      if (response.status === 429 && attempt < attempts) {
        let waitSec = 0;
        try {
          const parsed = await response.json() as { parameters?: { retry_after?: number } };
          waitSec = Number(parsed?.parameters?.retry_after) || 0;
        } catch {
          waitSec = 0;
        }
        const waitMs = delayMs > 0 ? Math.max(delayMs, waitSec * 1000) : 0;
        if (waitMs > 0) await sleep(waitMs);
      } else if (attempt < attempts && delayMs > 0) {
        await sleep(delayMs * attempt);
      }
    } else if (attempt < attempts && delayMs > 0) {
      await sleep(delayMs * attempt);
    }
  }

  return { ok: false, attempts, status: lastStatus, error: lastError };
}

/* ---------------------- защита от повторной отправки --------------------- */

const NOTIFY_LOG = 'notify_log';

function serviceKey(env: Env): string {
  return env.SUPABASE_SERVICE_ROLE_KEY || env.SUPABASE_SECRET_KEY || '';
}

export function dedupeAvailable(env: Env, orderId: unknown): boolean {
  return Boolean(env.SUPABASE_URL && serviceKey(env) && Number.isFinite(Number(orderId)));
}

function restHeaders(env: Env, extra?: Record<string, string>): Record<string, string> {
  const key = serviceKey(env);
  return {
    'Content-Type': 'application/json',
    apikey: key,
    Authorization: 'Bearer ' + key,
    ...(extra || {}),
  };
}

/** true — заказ уже уведомлён (шлём не мы); false — можно отправлять. */
export async function alreadyNotified(env: Env, fetchImpl: FetchLike, orderId: unknown): Promise<boolean> {
  if (!dedupeAvailable(env, orderId)) return false;
  try {
    const response = await fetchImpl(`${env.SUPABASE_URL}/rest/v1/${NOTIFY_LOG}`, {
      method: 'POST',
      headers: restHeaders(env, { Prefer: 'resolution=ignore-duplicates,return=representation' }),
      body: JSON.stringify([{ order_id: Number(orderId) }]),
    });
    if (!response.ok) return false; // таблицы нет или ключ не подошёл — шлём всё равно
    const rows = await response.json() as unknown[];
    return Array.isArray(rows) && rows.length === 0; // конфликт → строка не вставилась
  } catch {
    return false; // сеть подвела — лучше отправить, чем молчать
  }
}

/** Снимаем отметку, если отправка не удалась, — чтобы следующая попытка сработала. */
export async function forgetNotified(env: Env, fetchImpl: FetchLike, orderId: unknown): Promise<void> {
  if (!dedupeAvailable(env, orderId)) return;
  try {
    await fetchImpl(`${env.SUPABASE_URL}/rest/v1/${NOTIFY_LOG}?order_id=eq.${Number(orderId)}`, {
      method: 'DELETE',
      headers: restHeaders(env),
    });
  } catch {
    // не критично: отметка просто останется
  }
}

/* ------------------------------ обработчик ------------------------------- */

const json = (status: number, payload: Record<string, unknown>) =>
  new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });

export async function handleRequest(req: Request, env: Env, fetchImpl?: FetchLike): Promise<Response> {
  const doFetch: FetchLike = fetchImpl || ((input, init) => fetch(input as string, init));

  if (req.method !== 'POST') return json(405, { ok: false, error: 'только POST' });

  const secret = (env.WEBHOOK_SECRET || '').trim();
  if (!secret) {
    return json(500, { ok: false, error: 'не настроено: WEBHOOK_SECRET (секрет функции не задан)' });
  }
  if ((req.headers.get('x-webhook-secret') || '').trim() !== secret) {
    return json(401, { ok: false, error: 'неверный секрет вебхука' });
  }

  let payload: WebhookPayload;
  try {
    payload = await req.json() as WebhookPayload;
  } catch {
    return json(400, { ok: false, error: 'тело запроса не JSON' });
  }

  const record = payload?.record;
  if (!record || typeof record !== 'object') {
    return json(400, { ok: false, error: 'в запросе нет record (ожидается вебхук на INSERT в orders)' });
  }

  const token = (env.TELEGRAM_BOT_TOKEN || '').trim();
  const chatId = (env.TELEGRAM_CHAT_ID || '').trim();
  if (!token || !chatId) {
    return json(500, { ok: false, error: 'не настроено: TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID' });
  }

  if (await alreadyNotified(env, doFetch, record.id)) {
    return json(200, { ok: true, skipped: 'duplicate', order_id: Number(record.id) });
  }

  const text = formatOrderMessage(record);
  const result = await sendTelegram({
    token,
    chatId,
    text,
    apiBase: env.TELEGRAM_API_BASE,
    fetchImpl: doFetch,
  });

  if (!result.ok) {
    await forgetNotified(env, doFetch, record.id);
    return json(502, {
      ok: false,
      error: 'Telegram не принял сообщение',
      detail: result.error || '',
      attempts: result.attempts,
    });
  }

  return json(200, { ok: true, sent: true, attempts: result.attempts, order_id: Number(record.id) });
}

/* --------------------------- запуск на Supabase -------------------------- */

declare global {
  // deno-lint-ignore no-var
  var __ivaNoServe: boolean | undefined;
}

if (!globalThis.__ivaNoServe) {
  // На Supabase порт задаёт платформа. IVA_NOTIFY_PORT / IVA_NOTIFY_HOST нужны
  // только для локальной проверки на своей машине (см. 28-П5-УВЕДОМЛЕНИЯ...).
  const env = Deno.env.toObject() as Env & { IVA_NOTIFY_PORT?: string; IVA_NOTIFY_HOST?: string };
  Deno.serve(
    {
      port: Number(env.IVA_NOTIFY_PORT || 8000),
      hostname: env.IVA_NOTIFY_HOST || '0.0.0.0',
    },
    (req: Request) => handleRequest(req, env),
  );
}
