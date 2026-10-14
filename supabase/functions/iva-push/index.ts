// Ива — функция Supabase «iva-push»: мгновенные уведомления админам через Firebase (FCM), 10.74.
//
// Кто вызывает:
//   • база (триггеры trg_iva_push_msg / trg_iva_push_part → iva_push_enqueue) — POST {id, pass};
//   • приложение админа — GET ?cfg=1: публичные настройки проекта Firebase (те же, что зашиты в любой
//     Firebase-APK: project_id, номер отправителя, app_id, api_key) — чтобы получить push-токен.
// Что делает на POST:
//   1. по одноразовому пропуску берёт задание в базе (rpc iva_push_take) — адресаты только из базы;
//   2. шлёт каждому телефону адресата сигнал FCM v1 «проверь сейчас»: data {t: "m"|"o"}, без текста,
//      имён и адресов (приложение само заберёт новое обычным запросом);
//   3. сообщает базе итог (rpc iva_push_done); токены, которые Google назвал недействительными, база удалит.
// Секреты (Supabase → Edge Functions → Secrets; переносит выкладка из секретов GitHub, НЕ в коде и НЕ в чате):
//   FCM_SERVICE_ACCOUNT  — JSON ключа сервисного аккаунта Firebase (или FCM_SERVICE_ACCOUNT_B64 — он же в base64);
//   FCM_GOOGLE_SERVICES  — google-services.json приложения ru.prokatnvsk.app (или FCM_GOOGLE_SERVICES_B64).
// Ключ ниже — публичный ключ сайта (sb_publishable_…), он и так открыт в assets/sb.js.
// В журнал функции не пишутся ни токены телефонов, ни адреса, ни ключи.
// Без внешних библиотек: подпись RS256 — встроенный WebCrypto.

const SB_URL = "https://wdxdeatphizclskfmfxi.supabase.co";
const SB_KEY = "sb_publishable_dtRaEHNNPBFbHFvg8hw9iA_FqJSz9BE";
const PKG = "ru.prokatnvsk.app";
const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

type ServiceAccount = { project_id: string; client_email: string; private_key: string; token_uri?: string };
type ClientConfig = { project_id: string; sender_id: string; app_id: string; api_key: string };

const env = (k: string): string => {
  try { return (globalThis as any).Deno?.env?.get(k) ?? ""; } catch { return ""; }
};

export function b64urlBytes(b: Uint8Array): string {
  let bin = "";
  for (const x of b) bin += String.fromCharCode(x);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
const b64urlText = (s: string) => b64urlBytes(new TextEncoder().encode(s));

function b64decode(s: string): Uint8Array {
  const bin = atob(s.replace(/-/g, "+").replace(/_/g, "/").replace(/\s+/g, ""));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** Секрет-JSON: как есть или в base64 (выкладка из GitHub кладёт *_B64 — так надёжнее для многострочного текста). */
export function secretJson(name: string): any | null {
  const raw = (env(name) || env(name + "_B64")).trim();
  if (!raw) return null;
  try {
    return JSON.parse(raw.startsWith("{") ? raw : new TextDecoder().decode(b64decode(raw)));
  } catch { return null; }
}

/** Публичные настройки для приложения из google-services.json (только клиент ru.prokatnvsk.app). */
export function clientConfig(gs: any): ClientConfig | null {
  try {
    const client = (gs?.client ?? []).find((c: any) => c?.client_info?.android_client_info?.package_name === PKG);
    const cfg = {
      project_id: String(gs?.project_info?.project_id ?? ""),
      sender_id: String(gs?.project_info?.project_number ?? ""),
      app_id: String(client?.client_info?.mobilesdk_app_id ?? ""),
      api_key: String(client?.api_key?.[0]?.current_key ?? ""),
    };
    if (!cfg.project_id || !/^\d{4,20}$/.test(cfg.sender_id) || !/^1:\d+:android:[0-9a-f]+$/i.test(cfg.app_id) || !cfg.api_key) return null;
    return cfg;
  } catch { return null; }
}

/** JWT для обмена на токен Google OAuth (RS256, ключ сервисного аккаунта). */
export async function signJwt(sa: ServiceAccount, nowSec: number): Promise<string> {
  const head = b64urlText(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = b64urlText(JSON.stringify({
    iss: sa.client_email, scope: SCOPE, aud: sa.token_uri || "https://oauth2.googleapis.com/token",
    iat: nowSec, exp: nowSec + 3600,
  }));
  const pem = sa.private_key.replace(/\\n/g, "\n").replace(/-----(BEGIN|END) PRIVATE KEY-----/g, "").replace(/\s+/g, "");
  const key = await crypto.subtle.importKey("pkcs8", b64decode(pem), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(head + "." + claims)));
  return head + "." + claims + "." + b64urlBytes(sig);
}

let cached: { token: string; exp: number; who: string } | null = null;

async function accessToken(sa: ServiceAccount): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cached && cached.who === sa.client_email && cached.exp - 120 > now) return cached.token;
  const r = await fetch(sa.token_uri || "https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: "grant_type=" + encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer") + "&assertion=" + await signJwt(sa, now),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok || !j.access_token) throw new Error(`Google OAuth: HTTP ${r.status} ${String(j.error ?? "")}`.trim());
  cached = { token: j.access_token, exp: now + Number(j.expires_in ?? 3600), who: sa.client_email };
  return cached.token;
}

/** Сообщение FCM: только сигнал, высокий приоритет (будит телефон), живёт 10 минут, повторы склеиваются. */
export function fcmMessage(token: string, kind: string) {
  return { message: { token, data: { t: kind }, android: { priority: "HIGH", ttl: "600s", collapse_key: "iva_" + kind } } };
}

/** Токен больше не действует (приложение удалено, токен сменился) — его надо забыть. Ошибки нашего запроса — не повод. */
export function deadToken(status: number, body: string): boolean {
  return status === 404 || /UNREGISTERED/.test(body) || (status === 400 && /not a valid FCM registration token/i.test(body));
}

async function rpc(name: string, args: Record<string, unknown>): Promise<any> {
  const r = await fetch(`${SB_URL}/rest/v1/rpc/${name}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` },
    body: JSON.stringify(args),
  });
  if (!r.ok) throw new Error(`${name}: HTTP ${r.status}`);
  const t = await r.text();
  return t ? JSON.parse(t) : null;
}

const json = (o: unknown, status = 200) =>
  new Response(JSON.stringify(o), { status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } });

export async function handle(req: Request): Promise<Response> {
  const url = new URL(req.url);
  const sa = secretJson("FCM_SERVICE_ACCOUNT") as ServiceAccount | null;
  const cfg = clientConfig(secretJson("FCM_GOOGLE_SERVICES"));
  if (req.method === "GET") {
    if (url.searchParams.has("cfg")) return json(cfg ? { ready: true, ...cfg } : { ready: false });
    return json({ ok: true, ready: !!(sa?.private_key && sa?.client_email && cfg) });   // проверка выкладки: только да/нет
  }
  if (req.method !== "POST") return json({ error: "method" }, 405);
  let id = 0, pass = "";
  try {
    const b = await req.json();
    id = Number(b?.id);
    pass = String(b?.pass ?? "");
  } catch { /* пусто — ниже отказ */ }
  if (!Number.isSafeInteger(id) || id <= 0 || !/^[0-9a-f]{64}$/.test(pass)) return json({ error: "bad request" }, 400);
  const job = await rpc("iva_push_take", { p_id: id, p_pass: pass });
  if (!job) return json({ skip: true });                       // чужой/использованный/просроченный пропуск
  const tokens: string[] = Array.isArray(job.tokens) ? job.tokens.filter((t: unknown) => typeof t === "string") : [];
  const kind = job.kind === "o" ? "o" : "m";
  let sent = 0, err = "";
  const bad: string[] = [];
  try {
    if (!sa?.private_key || !sa?.client_email || !sa?.project_id) throw new Error("нет секрета FCM_SERVICE_ACCOUNT");
    const at = await accessToken(sa);
    await Promise.all(tokens.map(async (t) => {
      const r = await fetch(`https://fcm.googleapis.com/v1/projects/${encodeURIComponent(sa.project_id)}/messages:send`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${at}` },
        body: JSON.stringify(fcmMessage(t, kind)),
      });
      const body = await r.text();
      if (r.ok) sent++;
      else if (deadToken(r.status, body)) bad.push(t);
      else err = `FCM: HTTP ${r.status}`;
    }));
  } catch (e) {
    err = String((e as Error)?.message ?? e).slice(0, 200);
  }
  await rpc("iva_push_done", { p_id: id, p_pass: pass, p_sent: sent, p_bad: bad, p_err: err || null });
  if (err) console.error(`iva-push: задание ${id}: ${err}`);  // без токенов и адресов
  return json({ sent, removed: bad.length });
}

const D = (globalThis as any).Deno;
if (D?.serve) D.serve((req: Request) => handle(req).catch((e) => {
  console.error("iva-push: сбой: " + String((e as Error)?.message ?? e).slice(0, 200));
  return json({ error: "internal" }, 500);
}));
