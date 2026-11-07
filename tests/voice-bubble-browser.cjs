// Голосовое сообщение на сайте — пузырь как в приложении (просьба владельца 07.11.2026): вместо плеера браузера — круглая кнопка
// «рамкой», волна по громкости записи, длительность; свой пузырь — тёмный с оранжевой рамкой, чужой — тёмный; ширина волны —
// та же формула, что в приложении 10.139 (140 + 4 на секунду, не шире 250 и 58% экрана); нажатие — играет, ещё раз — пауза,
// нажатие на волну — перемотка; волна считается из файла один раз и запоминается; телефон 390 px — без прокрутки вбок.
const { chromium } = require('playwright'); const assert = require('assert');
const SHOTS = process.env.SHOTS || ''; const BASE = process.env.SITE_BASE_URL || 'http://127.0.0.1:8000';

/* WAV 1,6 с: тишина → громко → тихо → громко — волна не ровная */
function wav(){
  const rate = 8000, n = Math.round(rate * 1.6), b = Buffer.alloc(44 + n * 2);
  b.write('RIFF', 0); b.writeUInt32LE(36 + n * 2, 4); b.write('WAVE', 8); b.write('fmt ', 12); b.writeUInt32LE(16, 16);
  b.writeUInt16LE(1, 20); b.writeUInt16LE(1, 22); b.writeUInt32LE(rate, 24); b.writeUInt32LE(rate * 2, 28); b.writeUInt16LE(2, 32); b.writeUInt16LE(16, 34);
  b.write('data', 36); b.writeUInt32LE(n * 2, 40);
  for (let i = 0; i < n; i++){ const t = i / rate, env = t < 0.2 ? 0.02 : t < 0.6 ? 0.9 : t < 1.0 ? 0.15 : 0.7; b.writeInt16LE(Math.round(Math.sin(2 * Math.PI * 440 * t) * env * 32000), 44 + i * 2); }
  return b;
}
const WAV = wav();

(async () => {
  const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  const c = await b.newContext({ viewport: { width: 1280, height: 900 }, locale: 'ru-RU', timezoneId: 'Europe/Moscow' });
  const me = 'qa-voice@example.invalid', who = 'olga@example.invalid';
  const A1 = 'https://wdxdeatphizclskfmfxi.supabase.co/storage/v1/object/public/chat/v-short.wav';
  const A2 = 'https://wdxdeatphizclskfmfxi.supabase.co/storage/v1/object/public/chat/v-long.wav';
  const msgs = [
    { id: 3, from_email: me, to_email: who, text: '', img: '', audio: A2, audio_dur: 60, is_read: true, created_at: '2026-11-07T09:02:00Z', tool_id: null },
    { id: 2, from_email: who, to_email: me, text: '', img: '', audio: A1, audio_dur: 5, is_read: true, created_at: '2026-11-07T09:01:00Z', tool_id: null },
    { id: 1, from_email: who, to_email: me, text: 'Здравствуйте! Дрель свободна?', img: '', audio: '', audio_dur: 0, is_read: true, created_at: '2026-11-07T09:00:00Z', tool_id: null }];
  let audioGets = 0;
  await c.route('https://*.supabase.co/**', async route => {
    const r = route.request(), u = new URL(r.url());
    if (u.pathname.startsWith('/storage/')){ audioGets++; return route.fulfill({ status: 200, body: WAV, headers: { 'Content-Type': 'audio/wav', 'Access-Control-Allow-Origin': '*', 'Accept-Ranges': 'none' } }); }
    if (u.pathname === '/auth/v1/user') return route.fulfill({ json: { id: 'u1', email: me, user_metadata: {} } });
    if (u.pathname.endsWith('/app_messages') && r.method() === 'GET') return route.fulfill({ json: msgs });
    return route.fulfill({ json: [] });
  });
  const p = await c.newPage(); const errors = [];
  p.on('pageerror', e => errors.push(e.message));
  const shot = async n => { if (SHOTS) await p.screenshot({ path: SHOTS + '/' + n + '.png' }); };
  await p.goto(BASE + '/404.html');
  await p.evaluate(() => localStorage.setItem('iva_auth', JSON.stringify({ tok: 'test-token', ref: 'test-refresh', exp: 9999999999999 })));
  await p.goto(BASE + '/chat.html?who=' + encodeURIComponent(who));
  await p.waitForSelector('.bub.vbub .vb', { timeout: 15000 });

  // 1) пузыри: два голосовых, без плеера браузера; свой — тёмный с оранжевой рамкой, чужой — тёмный
  assert.equal(await p.locator('.bub.vbub').count(), 2);
  assert.equal(await p.locator('.bub audio').count(), 0, 'плеера браузера нет');
  const st = await p.evaluate(() => {
    const m = document.querySelector('.bub.vbub.me'), t = document.querySelector('.bub.vbub.they'), btn = m.querySelector('.vb-btn');
    const cs = getComputedStyle(m), bs = getComputedStyle(btn);
    return { bg: cs.backgroundColor, bc: cs.borderTopColor, bw: cs.borderTopWidth, theyBg: getComputedStyle(t).backgroundColor,
      btnW: btn.getBoundingClientRect().width, btnBorder: bs.borderTopColor, round: bs.borderTopLeftRadius };
  });
  assert.equal(st.bg, 'rgb(26, 29, 36)', 'свой пузырь — тёмный (как карточка)'); assert.equal(st.bc, 'rgb(255, 143, 31)'); assert.ok(st.bw === '1.5px' || st.bw === '1px', st.bw);   // на экране с плотностью 1 Chrome округляет 1,5px до 1px
  assert.notEqual(st.theyBg, 'rgb(255, 143, 31)', 'чужой — не оранжевый');
  assert.equal(st.btnW, 40); assert.equal(st.btnBorder, 'rgb(255, 143, 31)'); assert.equal(st.round, '50%');

  // 2) ширина волны — на сайте шире, чем в приложении (07.11.2026): 200 + 6 на секунду, до 400 и 62% окна: 5 с → 230, 60 с → 400 (экран 1280)
  const widths = await p.evaluate(() => Array.from(document.querySelectorAll('.vb')).map(e => [e.getAttribute('data-dur'), Math.round(e.querySelector('.vb-wave').getBoundingClientRect().width)]));
  assert.deepEqual(widths.sort(), [['5', 230], ['60', 400]].sort(), JSON.stringify(widths));
  assert.deepEqual(await p.evaluate(() => [IvaVoice.wavePx(0, 1080 / 2.75), IvaVoice.wavePx(10, 1080 / 2.75), IvaVoice.wavePx(120, 1080 / 2.75)]), [200, 243, 243], 'формула сайта: от 200, до 62% окна');
  assert.ok(await p.evaluate(() => IvaVoice.wavePx(0, 1280) > 140 && IvaVoice.wavePx(10, 1280) > 180 && IvaVoice.wavePx(120, 1280) > 250), 'шире, чем в приложении 10.139 (140 / 180 / 250)');
  assert.deepEqual(await p.evaluate(() => [...document.querySelectorAll('.vb-t')].map(e => e.textContent)).then(a => a.sort()), ['0:05', '1:00']);

  // 3) волна по громкости — посчитана из файла и запомнена на устройстве
  await p.waitForFunction(() => Object.keys(localStorage).filter(k => k.startsWith('iva_vpk:')).length === 2, null, { timeout: 10000 });
  const pk = await p.evaluate(() => { const k = Object.keys(localStorage).find(k => k.startsWith('iva_vpk:')); const s = localStorage.getItem(k); const v = []; for (let i = 0; i < 64; i++) v.push(parseInt(s.substr(1 + i * 2, 2), 16)); return v; });
  assert.ok(Math.max(...pk) === 255 && Math.min(...pk) < 80, 'волна не ровная: ' + pk.join(','));
  const lit = await p.evaluate(() => { const cv = document.querySelector('.bub.vbub.they .vb-wave'), d = cv.getContext('2d').getImageData(0, 0, cv.width, cv.height).data; let n = 0; for (let i = 3; i < d.length; i += 4) if (d[i] > 0) n++; return n; });
  assert.ok(lit > 200, 'столбики нарисованы');
  await shot('voice-1280');

  // 4) нажатие — играет (❚❚, идёт время), в конце — снова ▶ и длительность
  const they = p.locator('.bub.vbub.they .vb');
  await they.locator('.vb-btn').click();
  await p.waitForFunction(() => document.querySelector('.bub.vbub.they .vb-btn').getAttribute('data-st') === 'pause', null, { timeout: 8000 });
  await shot('voice-playing');
  await p.waitForFunction(() => { const e = document.querySelector('.bub.vbub.they'); return e.querySelector('.vb-btn').getAttribute('data-st') === 'play' && e.querySelector('.vb-t').textContent === '0:05'; }, null, { timeout: 8000 });
  // ещё раз — и пауза
  await they.locator('.vb-btn').click();
  await p.waitForFunction(() => document.querySelector('.bub.vbub.they .vb-btn').getAttribute('data-st') === 'pause', null, { timeout: 8000 });
  await they.locator('.vb-btn').click();
  await p.waitForFunction(() => document.querySelector('.bub.vbub.they .vb-btn').getAttribute('data-st') === 'play', null, { timeout: 4000 });

  // 5) нажатие на волну другого голосового — первое останавливается, второе играет с места нажатия (85% от 1,6 с → «0:01» сразу)
  const t0 = Date.now();
  await p.locator('.bub.vbub.me .vb-wave').click({ position: { x: 212, y: 12 } });
  await p.waitForFunction(() => document.querySelector('.bub.vbub.me .vb-t').textContent === '0:01', null, { timeout: 4000 });
  assert.ok(Date.now() - t0 < 2500, 'перемотка сработала');
  assert.equal(await p.locator('.bub.vbub.they .vb-btn').getAttribute('data-st'), 'play', 'играет одно голосовое за раз');
  await p.waitForFunction(() => document.querySelector('.bub.vbub.me .vb-btn').getAttribute('data-st') === 'play', null, { timeout: 6000 });

  // 6) телефон 390 px: волна не шире 62% экрана, без прокрутки вбок
  await p.setViewportSize({ width: 390, height: 844 });
  await p.waitForTimeout(150);
  const w390 = await p.evaluate(() => Math.round(document.querySelector('.bub.vbub.me .vb-wave').getBoundingClientRect().width));
  assert.equal(w390, Math.round(390 * 0.62));
  assert.ok(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'нет прокрутки вбок');
  await shot('voice-390');

  // 7) повторный вход — волна из памяти устройства, файл заново не качается для волны
  const g0 = audioGets;
  await p.reload(); await p.waitForSelector('.bub.vbub .vb'); await p.waitForTimeout(800);
  assert.equal(audioGets, g0, 'волна — из памяти устройства');

  // 8) кабинет («Сообщения» пункта) — тот же пузырь
  const fs = require('fs'), path = require('path');
  const acc = fs.readFileSync(path.join(__dirname, '..', 'account.html'), 'utf8');
  assert.ok(acc.includes("IvaVoice.html(m)") && acc.includes("(vb ? ' vbub' : '')"), 'account.html: голосовое — тот же пузырь');

  assert.deepEqual(errors, []);
  console.log('PASS: voice bubble like the app — round outlined button, real waveform (cached), duration, own bubble dark with orange border, wave wider than the app (200 + 6/s, up to 400 and 62%), play / pause / end, seek by tapping the wave, one at a time, 390px without horizontal scroll');
  await b.close();
})().catch(e => { console.error(e); process.exit(1); });
