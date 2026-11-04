// Ива — функция Supabase «iva-pay»: оплата комиссии площадки через ЮKassa владельца площадки, 10.133
// (просьба владельца 04.11.2026; SQL supabase/migrations/20261123_yookassa_fee.sql).
//
// Кто вызывает:
//   • приложение админа (ключ входа пользователя в Authorization):
//       POST {a: "new", amount, method?} — создать платёж в ЮKassa на любую сумму 1–100 000 ₽ → {id, url, sbp, fallback}
//                                 (страница оплаты ЮKassa; method "sbp" — 10.135: сразу страница СБП, не вышло — обычная);
//       POST {a: "check", id?}   — спросить у ЮKassa статус своих незавершённых оплат (после возврата со страницы оплаты);
//   • ЮKassa — HTTP-уведомление {type: "notification", event: "payment.succeeded" | "payment.canceled", object: {id}};
//   • GET без параметров — проверка выкладки и «подключено ли»: {ok, ready, test} (без ключей и сумм).
// Главное правило: деньги засчитываются ТОЛЬКО по ответу самой ЮKassa на наш запрос GET /v3/payments/{id} с секретным
// ключом. Тело уведомления не принимается на веру (его может прислать кто угодно) — по нему только узнаём номер.
// Засчитывает база (iva_yk_done): строка «confirmed» в fee_payments, один раз на платёж.
// Секреты (Supabase → Edge Functions → Secrets; переносит выкладка из секретов GitHub, НЕ в коде и НЕ в чате):
//   YOOKASSA_SHOP_ID, YOOKASSA_SECRET_KEY — из личного кабинета ЮKassa (тестовые ключи начинаются с test_).
//   Ключ базы для служебных функций — штатный секрет Supabase (SUPABASE_SECRET_KEYS / SUPABASE_SERVICE_ROLE_KEY).
// Чек: в платёж не передаётся — ЮKassa с 29.12.2025 чеки самозанятых не оформляет; владелец пробивает чек сам
// в «Мой налог» по списку в «Панели владельца» (решение владельца 04.11.2026).
// В журнал функции не пишутся ни ключи, ни ключи входа, ни почты.

const SB_URL = "https://wdxdeatphizclskfmfxi.supabase.co";
const SB_KEY = "sb_publishable_dtRaEHNNPBFbHFvg8hw9iA_FqJSz9BE";
const YK_API = "https://api.yookassa.ru/v3";
export const RETURN_URL = "https://eclipsru.github.io/Arenda-Nvk/pay-done.html";
export const MIN_RUB = 1;
export const MAX_RUB = 100000;

const env = (k: string): string => {
  try { return (globalThis as any).Deno?.env?.get(k) ?? ""; } catch { return ""; }
};

/** Ключ базы для служебных функций: новый секретный (SUPABASE_SECRET_KEYS.default) или прежний. */
export function dbKey(): string {
  try {
    const j = JSON.parse(env("SUPABASE_SECRET_KEYS") || "{}");
    if (typeof j?.default === "string" && j.default) return j.default;
    const first = Object.values(j ?? {}).find((v) => typeof v === "string" && v);
    if (typeof first === "string") return first;
  } catch { /* ниже — прежний ключ */ }
  return env("SUPABASE_SERVICE_ROLE_KEY").trim();
}

/** Basic-авторизация ЮKassa или null (секретов нет / не похожи на ключи). */
export function ykAuth(): string | null {
  const id = env("YOOKASSA_SHOP_ID").trim(), key = env("YOOKASSA_SECRET_KEY").trim();
  if (!/^\d{3,12}$/.test(id) || key.length < 10 || /\s/.test(key)) return null;
  return "Basic " + btoa(id + ":" + key);
}

export const ykTest = (): boolean => env("YOOKASSA_SECRET_KEY").trim().startsWith("test_");

/** Сумма из приложения: число или строка «1 500,5» → 1500.5; вне 1–100 000 или не число — null. */
export function parseAmount(x: unknown): number | null {
  const s = typeof x === "number" ? String(x) : typeof x === "string" ? x.replace(/[\s\u00a0]/g, "").replace(",", ".") : "";
  if (!/^\d{1,6}(\.\d{1,2})?$/.test(s)) return null;
  const v = Math.round(Number(s) * 100) / 100;
  return v >= MIN_RUB && v <= MAX_RUB ? v : null;
}

/** Тело создания платежа: сумма, сразу списать, страница оплаты ЮKassa, без чека и без личных данных.
 *  sbp — 10.135 (просьба владельца 04.11.2026 «чтобы сразу открывалась СБП»): способ задан заранее, ЮKassa открывает
 *  сразу свою страницу СБП (на телефоне — список банков), без выбора способа оплаты. Для СБП в ручной интеграции
 *  ЮKassa поддерживает только сценарий redirect (документация ЮKassa, «Система быстрых платежей»). */
export function createBody(id: number, amount: number, sbp = false) {
  const b: Record<string, unknown> = {
    amount: { value: amount.toFixed(2), currency: "RUB" },
    capture: true,
    confirmation: { type: "redirect", return_url: RETURN_URL + "?p=" + id },
    description: `Комиссия площадки «Ива», платёж №${id}`,
    metadata: { iva_id: String(id) },
  };
  if (sbp) b.payment_method_data = { type: "sbp" };
  return b;
}

/** Ключ повтора ЮKassa: у СБП свой — тело другое (повтор с тем же ключом и другим телом ЮKassa не примет). */
export const idemKey = (id: number, sbp: boolean) => "iva-pay-" + id + (sbp ? "-sbp" : "");

/** Из ответа ЮKassa — то, что нужно базе. Не платёж — null. */
export function outcome(p: any): { id: string; status: string; amount: number; paid: boolean; method: string; reason: string } | null {
  if (!p || typeof p.id !== "string" || !p.id || typeof p.status !== "string") return null;
  return {
    id: p.id,
    status: p.status,
    amount: Number(p.amount?.value ?? NaN),
    paid: p.paid === true,
    method: String(p.payment_method?.type ?? ""),
    reason: String(p.cancellation_details?.reason ?? ""),
  };
}

/** Ошибка базы → понятный ответ приложению. */
export function dbError(text: string): { code: string; status: number } {
  if (/IVA_NOT_ADMIN/.test(text)) return { code: "not_admin", status: 403 };
  if (/IVA_BAD_AMOUNT/.test(text)) return { code: "bad_amount", status: 400 };
  if (/IVA_TOO_MANY/.test(text)) return { code: "too_many", status: 429 };
  if (/PGRST202|HTTP 404|Could not find the function/.test(text)) return { code: "not_ready", status: 503 };   // SQL 1123 не выполнен
  return { code: "db", status: 500 };
}

class DbError extends Error {}

async function db(name: string, args: Record<string, unknown>): Promise<any> {
  const key = dbKey();
  if (!key) throw new DbError("нет ключа базы");
  const h: Record<string, string> = { "Content-Type": "application/json", apikey: key };
  if (key.startsWith("eyJ")) h.Authorization = `Bearer ${key}`;           // прежний ключ — JWT; новый sb_… — только apikey
  const r = await fetch(`${SB_URL}/rest/v1/rpc/${name}`, { method: "POST", headers: h, body: JSON.stringify(args) });
  const t = await r.text();
  if (!r.ok) throw new DbError(`${name}: HTTP ${r.status} ${t.slice(0, 300)}`);
  return t ? JSON.parse(t) : null;
}

/** Почта вошедшего по его ключу входа (Supabase Auth); нет / чужой ключ — "". */
async function whoAmI(req: Request): Promise<string> {
  const m = /^Bearer\s+(\S+)$/i.exec(req.headers.get("Authorization") ?? "");
  if (!m || !m[1].startsWith("eyJ")) return "";
  const r = await fetch(`${SB_URL}/auth/v1/user`, { headers: { apikey: SB_KEY, Authorization: `Bearer ${m[1]}` } });
  if (!r.ok) return "";
  const j = await r.json().catch(() => ({}));
  return typeof j?.email === "string" ? j.email.trim().toLowerCase() : "";
}

async function yk(path: string, auth: string, init: { method?: string; body?: unknown; key?: string } = {}): Promise<{ ok: boolean; status: number; j: any }> {
  const h: Record<string, string> = { Authorization: auth, "Content-Type": "application/json" };
  if (init.key) h["Idempotence-Key"] = init.key;
  const r = await fetch(YK_API + path, { method: init.method ?? "GET", headers: h, body: init.body === undefined ? undefined : JSON.stringify(init.body) });
  const j = await r.json().catch(() => ({}));
  return { ok: r.ok, status: r.status, j };
}

/** Спросить ЮKassa и передать итог базе. null — не наш платёж; бросает — ЮKassa / база недоступны. */
async function settle(ykId: string, auth: string): Promise<any> {
  const r = await yk("/payments/" + encodeURIComponent(ykId), auth);
  if (r.status === 404) return null;
  if (!r.ok) throw new Error(`ЮKassa: HTTP ${r.status}`);
  const o = outcome(r.j);
  if (!o || o.id !== ykId) throw new Error("ЮKassa: не платёж");
  if (o.status !== "succeeded" && o.status !== "canceled") return { status: o.status };
  return await db("iva_yk_done", { p_yk_id: o.id, p_status: o.status, p_amount: o.amount, p_paid: o.paid, p_method: o.method, p_reason: o.reason });
}

const json = (o: unknown, status = 200) =>
  new Response(JSON.stringify(o), { status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } });

export async function handle(req: Request): Promise<Response> {
  const auth = ykAuth();
  if (req.method === "GET") return json({ ok: true, ready: !!auth && !!dbKey(), test: !!auth && ykTest() });
  if (req.method !== "POST") return json({ error: "method" }, 405);
  let b: any = null;
  try { b = await req.json(); } catch { /* пусто — ниже отказ */ }
  if (!b || typeof b !== "object") return json({ error: "bad request" }, 400);

  // ── уведомление ЮKassa: только номер; итог — по нашему запросу к ЮKassa ──
  if (b.type === "notification") {
    const id = typeof b.object?.id === "string" ? b.object.id : "";
    if (!/^[0-9a-f-]{20,64}$/i.test(id)) return json({ error: "bad request" }, 400);
    if (!auth) return json({ error: "not_ready" }, 503);                 // ЮKassa повторит, когда ключи появятся
    try {
      await settle(id, auth);
      return json({ ok: true });
    } catch (e) {
      console.error("iva-pay: уведомление не обработано: " + String((e as Error)?.message ?? e).slice(0, 200));
      return json({ error: "retry" }, 500);                              // ЮKassa повторит уведомление
    }
  }

  if (!auth || !dbKey()) return json({ error: "not_ready" }, 503);
  const me = await whoAmI(req);
  if (!me) return json({ error: "auth" }, 401);

  if (b.a === "new") {
    const amount = parseAmount(b.amount);
    if (amount === null) return json({ error: "bad_amount" }, 400);
    let id = 0;
    try {
      id = Number(await db("iva_yk_new", { p_email: me, p_amount: amount }));
    } catch (e) {
      const d = dbError(String((e as Error)?.message ?? e));
      if (d.code === "db") console.error("iva-pay: новая оплата: " + String((e as Error)?.message ?? e).slice(0, 200));
      return json({ error: d.code }, d.status);
    }
    if (!Number.isSafeInteger(id) || id <= 0) return json({ error: "db" }, 500);
    const wantSbp = b.method === "sbp";
    let viaSbp = wantSbp;
    let r = await yk("/payments", auth, { method: "POST", body: createBody(id, amount, wantSbp), key: idemKey(id, wantSbp) });
    if (wantSbp && !r.ok && r.status >= 400 && r.status < 500) {
      // СБП не включена в магазине или ЮKassa отказала именно в СБП — обычная страница со всеми способами
      console.error(`iva-pay: СБП для платежа ${id} не создана (HTTP ${r.status}: ${String(r.j?.description ?? r.j?.code ?? "").slice(0, 120)}) — обычная страница`);
      viaSbp = false;
      r = await yk("/payments", auth, { method: "POST", body: createBody(id, amount, false), key: idemKey(id, false) });
    }
    const url = String(r.j?.confirmation?.confirmation_url ?? "");
    if (!r.ok || typeof r.j?.id !== "string" || !/^https:\/\//.test(url)) {
      const why = String(r.j?.description ?? r.j?.code ?? `HTTP ${r.status}`).slice(0, 200);
      await db("iva_yk_fail", { p_id: id, p_reason: why }).catch(() => null);
      console.error(`iva-pay: ЮKassa не создала платёж ${id}: ${why}`);
      return json({ error: "yookassa", message: why }, 502);
    }
    await db("iva_yk_set", { p_id: id, p_yk_id: r.j.id, p_url: url });
    return json({ id, url, test: r.j.test === true, sbp: viaSbp, fallback: wantSbp && !viaSbp });
  }

  if (b.a === "check") {
    const one = b.id === undefined || b.id === null ? null : Number(b.id);
    if (one !== null && (!Number.isSafeInteger(one) || one <= 0)) return json({ error: "bad request" }, 400);
    let rows: any[] = [];
    try {
      rows = (await db("iva_yk_open", { p_email: me, p_id: one })) ?? [];
    } catch (e) {
      const d = dbError(String((e as Error)?.message ?? e));
      return json({ error: d.code }, d.status);
    }
    const out: any[] = [];
    for (const row of rows) {
      try {
        const s = await settle(String(row.yk_id), auth);
        out.push({ id: row.id, status: s?.status ?? "pending", amount: s?.amount ?? null });
      } catch (e) {
        console.error(`iva-pay: проверка ${row.id}: ` + String((e as Error)?.message ?? e).slice(0, 200));
        out.push({ id: row.id, status: "pending", amount: null });
      }
    }
    return json({ rows: out });
  }

  return json({ error: "bad request" }, 400);
}

const D = (globalThis as any).Deno;
if (D?.serve) D.serve((req: Request) => handle(req).catch((e) => {
  console.error("iva-pay: сбой: " + String((e as Error)?.message ?? e).slice(0, 200));
  return json({ error: "internal" }, 500);
}));
