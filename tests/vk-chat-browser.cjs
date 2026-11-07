// Сообщения как в VK Мессенджере (просьба владельца 07.11.2026) — только компьютер, цвета «Ивы».
// chat.html на 1280: одна карточка в две колонки, «Чаты» + число непрочитанных, «Поиск» по имени / почте / тексту,
// строки «Вы: текст · 5м» с ✓✓ (оранжевые — прочитано) или счётчиком, «Только непрочитанные»; блоки сообщений —
// подряд от одного — вплотную (3px), время внутри блока; поле «Напишите сообщение...» — ⊕ слева, микрофон,
// который сменяется «Отправить», когда есть текст; набранный текст не пропадает при перерисовке.
// Телефон 390: поиска, переключателя и «Вы: » нет — как было; кнопка «Отправить» на месте.
const { chromium } = require('playwright'); const assert = require('assert');
const SHOTS = process.env.SHOTS || ''; const BASE = process.env.SITE_BASE_URL || 'http://127.0.0.1:8000';

(async () => {
  const b = await chromium.launch();
  const me = 'qa-vk@example.invalid', olga = 'olga@example.invalid', ivan = 'ivan@example.invalid', petr = 'petr@example.invalid';
  const ago = m => new Date(Date.now() - m * 60000).toISOString();
  const M = (id, from, to, text, min, read) => ({ id, from_email: from, to_email: to, text, img: '', audio: '', audio_dur: 0, is_read: read, created_at: ago(min), tool_id: null });
  const msgs = [
    M(9, me, olga, 'Да, свободна. Можно забрать после 17:00', 5, true),
    M(8, olga, me, 'Дрель свободна на выходные?', 7, true),
    M(7, olga, me, 'Здравствуйте!', 8, true),
    M(6, ivan, me, 'А болгарку можно на неделю?', 40, false),
    M(5, ivan, me, 'Добрый день', 41, false),
    M(4, me, petr, 'Спасибо, ждём снова', 60 * 30, false),
    M(3, petr, me, 'Вернул перфоратор', 60 * 30 + 3, true)];
  async function ctx(w, h){
    const c = await b.newContext({ viewport: { width: w, height: h }, locale: 'ru-RU', timezoneId: 'Europe/Moscow' });
    await c.route('https://*.supabase.co/**', async route => {
      const r = route.request(), u = new URL(r.url());
      if (u.pathname === '/auth/v1/user') return route.fulfill({ json: { id: 'u1', email: me, user_metadata: {} } });
      if (u.pathname.endsWith('/app_messages') && r.method() === 'GET') return route.fulfill({ json: msgs });
      return route.fulfill({ json: [] });
    });
    const p = await c.newPage(); p.errors = [];
    p.on('pageerror', e => p.errors.push(e.message));
    await p.goto(BASE + '/404.html');
    await p.evaluate(() => localStorage.setItem('iva_auth', JSON.stringify({ tok: 'test-token', ref: 'test-refresh', exp: 9999999999999 })));
    return p;
  }
  const shot = async (p, n) => { if (SHOTS) await p.screenshot({ path: SHOTS + '/' + n + '.png' }); };

  /* ---------- компьютер 1280 ---------- */
  const p = await ctx(1280, 900);
  await p.goto(BASE + '/chat.html?who=' + encodeURIComponent(olga));
  await p.waitForSelector('#dlgItems .dlg', { timeout: 15000 });
  await p.waitForSelector('#thread .bub');
  await shot(p, 'vk-1280');

  const lay = await p.evaluate(() => {
    const w = document.querySelector('.chat-wrap.vk'), l = document.getElementById('dlgList'), t = document.getElementById('thread');
    const r = w.getBoundingClientRect(), cs = getComputedStyle(w);
    return { cols: cs.gridTemplateColumns, gap: cs.columnGap, overflow: cs.overflow, radius: cs.borderTopLeftRadius,
      listW: Math.round(l.getBoundingClientRect().width), tLeft: Math.round(t.getBoundingClientRect().left - l.getBoundingClientRect().right),
      fitsScreen: r.bottom <= innerHeight + 1, sect: getComputedStyle(document.querySelector('.chat-sect')).display };
  });
  assert.equal(lay.listW, 340, JSON.stringify(lay)); assert.equal(lay.tLeft, 0, 'колонки вплотную, одна карточка');
  assert.equal(lay.overflow, 'hidden'); assert.equal(lay.radius, '14px'); assert.ok(lay.fitsScreen, 'карточка помещается в окно'); assert.equal(lay.sect, 'none');

  // левая колонка: «Чаты», число непрочитанных, поиск, строки
  assert.equal(await p.locator('.vk-lh b').innerText(), 'Чаты');
  assert.equal(await p.locator('#vkUnread').innerText(), '2');
  assert.ok(await p.locator('#dlgQ').isVisible()); assert.equal(await p.locator('#dlgQ').getAttribute('placeholder'), 'Поиск');
  assert.equal(await p.locator('#dlgItems .dlg').count(), 3);
  const rows = await p.evaluate(() => Array.from(document.querySelectorAll('#dlgItems .dlg')).map(d => ({
    on: d.classList.contains('on'), unr: d.classList.contains('unr'), last: d.querySelector('.dlast').innerText.replace(/\s+/g, ' ').trim(),
    you: !!d.querySelector('.dyou') && getComputedStyle(d.querySelector('.dyou')).display !== 'none',
    read: d.querySelector('.dread') ? [d.querySelector('.dread').innerText, getComputedStyle(d.querySelector('.dread')).color] : null,
    n: d.querySelector('.dunread') ? d.querySelector('.dunread').innerText : '', ava: Math.round(d.querySelector('.ava').getBoundingClientRect().width),
    bg: getComputedStyle(d).backgroundColor, radius: getComputedStyle(d).borderTopLeftRadius, time: getComputedStyle(d.querySelector('.dtime') || d).display })));
  const [rO, rI, rP] = rows;
  assert.ok(rO.on && rO.you && /^Вы: Да, свободна.*· 5м$/.test(rO.last), JSON.stringify(rO));
  assert.deepEqual(rO.read, ['✓✓', 'rgb(255, 143, 31)'], 'прочитано — оранжевые ✓✓');
  assert.equal(rO.ava, 48); assert.equal(rO.radius, '10px'); assert.notEqual(rO.bg, 'rgba(0, 0, 0, 0)', 'выбранная строка подсвечена');
  assert.ok(rI.unr && rI.n === '2' && !rI.you && /А болгарку.*· 40м$/.test(rI.last), JSON.stringify(rI));
  assert.ok(rP.you && rP.read && rP.read[0] === '✓' && /· вчера$|· \d+ч$/.test(rP.last), JSON.stringify(rP));
  assert.equal(rP.read[1] === 'rgb(255, 143, 31)', false, 'не прочитано — серая ✓');

  // поиск: по тексту, по почте, «ничего не найдено»; переписка справа не трогается
  await p.fill('#dlgQ', 'болгарку');
  assert.equal(await p.locator('#dlgItems .dlg').count(), 1); assert.match(await p.locator('#dlgItems .dlg .dnm').innerText(), /ivan/i);
  await p.fill('#dlgQ', 'PETR@');
  assert.equal(await p.locator('#dlgItems .dlg').count(), 1);
  await p.fill('#dlgQ', 'нет такого');
  assert.equal(await p.locator('#dlgItems .dlg').count(), 0); assert.equal(await p.locator('.vk-none').innerText(), 'Ничего не найдено');
  assert.ok(await p.locator('#thread .bub').count() >= 3, 'переписка на месте');
  await p.fill('#dlgQ', '');
  assert.equal(await p.locator('#dlgItems .dlg').count(), 3);

  // «Только непрочитанные»: остаются непрочитанные и открытая переписка
  await p.locator('.vk-only').click();
  assert.equal(await p.locator('#dlgUnr').isChecked(), true);
  assert.equal(await p.locator('#dlgItems .dlg').count(), 2);
  assert.equal(await p.locator('#dlgItems .dlg.unr').count(), 1);
  await shot(p, 'vk-1280-unread');
  await p.locator('.vk-only').click();
  assert.equal(await p.locator('#dlgItems .dlg').count(), 3);

  // блоки: свой — мягкий оранжевый, чужой — карточка; подряд — 3px, новая группа — 10px; время внутри блока
  const bub = await p.evaluate(() => {
    const bs = Array.from(document.querySelectorAll('#thread .bub'));
    return bs.map(e => { const cs = getComputedStyle(e), t = e.querySelector('.btime'), r = e.getBoundingClientRect(), tr = t.getBoundingClientRect();
      return { me: e.classList.contains('me'), cont: e.classList.contains('cont'), mt: cs.marginTop, bg: cs.backgroundColor, radius: cs.borderTopLeftRadius,
        border: cs.borderTopWidth, color: cs.color, timeInside: tr.bottom <= r.bottom && tr.right <= r.right && tr.top >= r.top, timePos: getComputedStyle(t).position,
        h: Math.round(r.height) }; });
  });
  assert.equal(bub.length, 3, JSON.stringify(bub));
  assert.ok(!bub[0].me && !bub[0].cont && bub[1].cont && !bub[1].me && bub[2].me && !bub[2].cont, JSON.stringify(bub));
  assert.equal(bub[1].mt, '3px'); assert.equal(bub[2].mt, '10px');
  assert.equal(bub[0].bg, 'rgb(26, 29, 36)', 'чужой блок — карточка'); assert.equal(bub[0].border, '0px');
  assert.equal(bub[2].color, 'rgb(242, 244, 248)', 'свой блок — светлый текст, не белый на оранжевом');
  assert.notEqual(bub[2].bg, 'rgb(255, 143, 31)');
  bub.forEach(x => { assert.equal(x.radius, '14px'); assert.ok(x.timeInside); assert.equal(x.timePos, 'absolute'); assert.ok(x.h < 40, 'короткое сообщение — одна строка вместе со временем: ' + x.h); });
  assert.equal(await p.evaluate(() => getComputedStyle(document.querySelector('.bub.me .btime span')).color), 'rgb(255, 143, 31)', 'прочитано — оранжевые ✓✓');
  assert.equal(await p.evaluate(() => getComputedStyle(document.querySelector('.th-day')).position), 'sticky');

  // поле ввода: ⊕, «Напишите сообщение...», микрофон ↔ «Отправить»
  assert.equal(await p.locator('#thText').getAttribute('placeholder'), 'Напишите сообщение...');
  assert.ok(await p.locator('#bPhoto .ic-d').isVisible()); assert.ok(!(await p.locator('#bPhoto .ic-m').isVisible()));
  assert.ok(await p.locator('#bMic').isVisible()); assert.ok(!(await p.locator('#bSend').isVisible()));
  const ic = await p.evaluate(() => { const f = document.getElementById('thIn').getBoundingClientRect(), c = y => (y.top + y.bottom) / 2;
    return ['#bPhoto .ic-d', '#bMic svg'].map(s => Math.abs(c(document.querySelector(s).getBoundingClientRect()) - c(f))); });
  assert.ok(ic.every(d => d <= 2), 'значки ⊕ и микрофон по центру поля: ' + ic);
  await p.fill('#thText', 'Хорошо, буду в 18');
  assert.ok(!(await p.locator('#bMic').isVisible())); assert.ok(await p.locator('#bSend').isVisible());
  const al = await p.evaluate(() => {
    const f = document.getElementById('thIn').getBoundingClientRect(), s = document.getElementById('bSend').getBoundingClientRect(), ph = document.getElementById('bPhoto').getBoundingClientRect(),
      ta = document.getElementById('thText').getBoundingClientRect();
    return { inside: s.right <= f.right && ph.left >= f.left && s.bottom <= f.bottom, sendBg: getComputedStyle(document.getElementById('bSend')).backgroundColor,
      dy: Math.abs((s.top + s.bottom) / 2 - (f.top + f.bottom) / 2), radius: getComputedStyle(document.getElementById('thIn')).borderTopLeftRadius };
  });
  assert.ok(al.inside, 'кнопки внутри поля'); assert.equal(al.sendBg, 'rgba(0, 0, 0, 0)', 'стрелка без заливки'); assert.ok(al.dy <= 2, 'кнопки по центру поля: ' + al.dy); assert.equal(al.radius, '12px');
  await shot(p, 'vk-1280-typing');
  // набранный текст переживает перерисовку переписки (новое сообщение)
  await p.evaluate(() => document.getElementById('thText').blur());
  await p.locator('#dlgItems .dlg').first().click();
  assert.equal(await p.inputValue('#thText'), 'Хорошо, буду в 18', 'текст не пропал');
  await p.fill('#thText', '');
  assert.ok(await p.locator('#bMic').isVisible(), 'текст стёрт — снова микрофон');
  // другая переписка — поле пустое
  await p.fill('#thText', 'черновик');
  await p.locator('#dlgItems .dlg').nth(1).click();
  await p.waitForFunction(() => /ivan/i.test(document.querySelector('.th-head').innerText));
  assert.equal(await p.inputValue('#thText'), '', 'черновик не переезжает в чужую переписку');
  assert.equal(p.errors.length, 0, p.errors.join('\n'));

  /* ---------- телефон 390: как было ---------- */
  const m = await ctx(390, 844);
  await m.goto(BASE + '/chat.html');
  await m.waitForSelector('#dlgItems .dlg', { timeout: 15000 });
  const mob = await m.evaluate(() => ({
    hidden: ['.vk-lh', '.vk-search', '.vk-only', '.dyou', '.dago', '.dread'].map(s => Array.from(document.querySelectorAll(s)).every(e => getComputedStyle(e).display === 'none')),
    time: getComputedStyle(document.querySelector('.dright .dtime')).display, sect: getComputedStyle(document.querySelector('.chat-sect')).display,
    wide: document.documentElement.scrollWidth <= innerWidth }));
  assert.deepEqual(mob.hidden, [true, true, true, true, true, true], JSON.stringify(mob));
  assert.notEqual(mob.time, 'none'); assert.notEqual(mob.sect, 'none'); assert.ok(mob.wide);
  await shot(m, 'vk-390-list');
  await m.locator('#dlgItems .dlg').first().click();
  await m.waitForSelector('#thText');
  assert.equal(await m.locator('#thText').getAttribute('placeholder'), 'Сообщение');
  assert.ok(await m.locator('#bPhoto .ic-m').isVisible()); assert.ok(!(await m.locator('#bPhoto .ic-d').isVisible()));
  assert.equal(await m.evaluate(() => getComputedStyle(document.querySelector('.bub.me')).backgroundColor), 'rgb(255, 143, 31)', 'на телефоне свой пузырь прежний — оранжевый');
  await shot(m, 'vk-390-thread');
  assert.equal(m.errors.length, 0, m.errors.join('\n'));

  await b.close();
  console.log('PASS: VK-style messages on desktop — one two-column card, «Чаты» + unread count, search by name / email / text, «Вы: … · 5м» rows with ✓✓ / counter, «Только непрочитанные», blocks with 3px in-group gap and inline time, ⊕ / mic ↔ send composer, draft kept on repaint; phone 390 unchanged');
})().catch(e => { console.error(e); process.exit(1); });
