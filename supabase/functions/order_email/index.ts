// Ива — функция Supabase «order_email»: письма клиенту о его заявке (этап П5).
//
// Кто вызывает: только база (триггер trg_iva_email_part → iva_email_on_part), передаёт {id, token}.
// Что делает:
//   1. по одноразовому пропуску берёт письмо в базе (rpc iva_email_take) — адрес и текст
//      из базы, поэтому через функцию нельзя написать произвольному адресату;
//   2. отправляет его с почтового ящика владельца через SMTP mail.ru (порт 465, шифрование);
//   3. сообщает базе результат (rpc iva_email_done).
// Секреты (задаются в Supabase → Edge Functions → Secrets, НЕ в коде и НЕ в чате):
//   SMTP_USER — адрес ящика (например, eclipsru@mail.ru);
//   SMTP_PASS — «пароль для внешних приложений» из настроек mail.ru (не основной пароль!).
// Необязательные: SMTP_HOST (по умолчанию smtp.mail.ru), SMTP_PORT (465), SMTP_FROM_NAME.
// Ключ ниже — публичный ключ сайта (sb_publishable_…), он и так открыт в assets/sb.js.
// В журнал функции не пишутся ни адреса, ни тексты писем.

// Без внешних библиотек: письмо отправляется встроенным Deno.connectTls (порт 465, шифрование сразу).
// (nodemailer в среде Deno не прошёл проверку агента 03.10.2026 — обрыв TLS; denomailer — сторонний
//  и не скачивается из песочницы для проверки. Свой короткий клиент проверен на тестовом SMTP-сервере.)

const b64 = (s: string) => {
  const bytes = new TextEncoder().encode(s);
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin);
};
const mimeWord = (s: string) => `=?UTF-8?B?${b64(s)}?=`;

type Mail = { host: string; port: number; user: string; pass: string; fromName: string;
  to: string; subject: string; text: string; caCerts?: string[] };

async function smtpSend(m: Mail): Promise<void> {
  if (/[\r\n<>]/.test(m.to) || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(m.to)) throw new Error("bad recipient");
  const conn = await Deno.connectTls({ hostname: m.host, port: m.port, caCerts: m.caCerts });
  const enc = new TextEncoder(), dec = new TextDecoder();
  let buf = "";
  const read = async (want: number[]): Promise<string> => {
    const timer = setTimeout(() => { try { conn.close(); } catch { /* уже закрыто */ } }, 20000);
    try {
      for (;;) {
        const lines = buf.split("\r\n");
        const done = lines.findIndex((l) => /^\d{3} /.test(l));
        if (done >= 0) {
          const resp = lines.slice(0, done + 1).join("\n");
          buf = lines.slice(done + 1).join("\r\n");
          const code = Number(resp.slice(-9999).match(/(\d{3}) [^\n]*$/)![1]);
          if (!want.includes(code)) throw new Error(`SMTP ${resp.replace(/\s+/g, " ").slice(0, 200)}`);
          return resp;
        }
        const chunk = new Uint8Array(4096);
        const n = await conn.read(chunk);
        if (n === null) throw new Error("SMTP: соединение закрыто сервером");
        buf += dec.decode(chunk.subarray(0, n));
      }
    } finally { clearTimeout(timer); }
  };
  const cmd = async (line: string, want: number[]) => { await conn.write(enc.encode(line + "\r\n")); return read(want); };
  try {
    await read([220]);
    await cmd("EHLO iva-prokat", [250]);
    await cmd("AUTH LOGIN", [334]);
    await cmd(b64(m.user), [334]);
    await cmd(b64(m.pass), [235]);
    await cmd(`MAIL FROM:<${m.user}>`, [250]);
    await cmd(`RCPT TO:<${m.to}>`, [250, 251]);
    await cmd("DATA", [354]);
    const domain = m.user.split("@")[1] || "localhost";
    const body = (b64(m.text).match(/.{1,76}/g) || []).join("\r\n");
    const msg = [
      `From: ${mimeWord(m.fromName)} <${m.user}>`,
      `To: <${m.to}>`,
      `Subject: ${mimeWord(m.subject)}`,
      `Date: ${new Date().toUTCString().replace("GMT", "+0000")}`,
      `Message-ID: <${crypto.randomUUID()}@${domain}>`,
      "MIME-Version: 1.0",
      "Content-Type: text/plain; charset=UTF-8",
      "Content-Transfer-Encoding: base64",
      "Auto-Submitted: auto-generated",
      "",
      body,
    ].join("\r\n");
    await cmd(msg + "\r\n.", [250]);
    try { await cmd("QUIT", [221]); } catch { /* письмо уже принято */ }
  } finally {
    try { conn.close(); } catch { /* уже закрыто */ }
  }
}

const SB_URL = Deno.env.get("IVA_SB_URL") || Deno.env.get("SUPABASE_URL") ||
  "https://wdxdeatphizclskfmfxi.supabase.co";
const SB_KEY = "sb_publishable_dtRaEHNNPBFbHFvg8hw9iA_FqJSz9BE";

async function rpc(fn: string, args: Record<string, unknown>): Promise<unknown> {
  const r = await fetch(`${SB_URL}/rest/v1/rpc/${fn}`, {
    method: "POST",
    headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}`, "Content-Type": "application/json" },
    body: JSON.stringify(args),
  });
  if (!r.ok) throw new Error(`${fn}: HTTP ${r.status}`);
  const t = await r.text();
  return t ? JSON.parse(t) : null;
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ ok: true, hint: "order_email: вызывается только базой" });

  let id: unknown, token: unknown;
  try { ({ id, token } = await req.json()); } catch { return json({ ok: false, error: "bad json" }, 400); }
  if (!Number.isInteger(id) || typeof token !== "string" || !/^[0-9a-f]{64}$/.test(token)) {
    return json({ ok: false, error: "bad request" }, 400);
  }

  let job: { to?: string; subject?: string; text?: string; skip?: boolean } | null;
  try {
    job = await rpc("iva_email_take", { p_id: id, p_token: token }) as typeof job;
  } catch (e) {
    console.error("order_email: база не ответила", String(e));
    return json({ ok: false, error: "db" }, 502);
  }
  if (!job) return json({ ok: false, error: "пропуск недействителен" }, 403);
  if (job.skip || !job.to) return json({ ok: true, skipped: true });

  const user = Deno.env.get("SMTP_USER") || "";
  const pass = Deno.env.get("SMTP_PASS") || "";
  const done = (ok: boolean, err: string | null) =>
    rpc("iva_email_done", { p_id: id, p_token: token, p_ok: ok, p_err: err }).catch((e) =>
      console.error("order_email: не записан результат", String(e))
    );

  if (!user || !pass) {
    await done(false, "не заданы секреты SMTP_USER / SMTP_PASS");
    return json({ ok: false, error: "smtp secrets missing" }, 500);
  }

  const testCa = Deno.env.get("SMTP_TEST_CA"); // только для проверки на тестовом сервере
  try {
    await smtpSend({
      host: Deno.env.get("SMTP_HOST") || "smtp.mail.ru",
      port: Number(Deno.env.get("SMTP_PORT") || 465),
      user, pass,
      fromName: Deno.env.get("SMTP_FROM_NAME") || "Ива — инструмент в аренду",
      to: job.to, subject: job.subject || "Ива", text: job.text || "",
      caCerts: testCa ? [await Deno.readTextFile(testCa)] : undefined,
    });
    await done(true, null);
    return json({ ok: true, sent: true });
  } catch (e) {
    const msg = String((e as Error)?.message || e);
    console.error("order_email: почта не приняла письмо", msg.replace(/\S+@\S+/g, "<email>"));
    await done(false, msg);
    return json({ ok: false, error: "smtp" }, 502);
  }
});
