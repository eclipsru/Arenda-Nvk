#!/usr/bin/env node
/* ============================================================
   Ива — эталонные скриншоты страниц (контроль «дизайн не менялся»).

   Нужен Playwright:  npm install && npx playwright install chromium
   Режимы:
     node tools/screenshot-baseline.js --save    эталон: снять и записать хеши
     node tools/screenshot-baseline.js --check   сравнить с эталоном (только стабильные страницы)

   Куда пишет:
     .snapshots/screens/*.png          картинки (в репозиторий НЕ попадают, .gitignore)
     tools/screens-baseline.json       манифест с sha256 (в репозитории — да, он маленький)

   Важно: внешние запросы (чужие домены) блокируются — сравнение должно быть детерминированным.
   Хеши воспроизводимы в одном и том же окружении (одна машина / один образ CI).
   ============================================================ */
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const crypto = require('node:crypto');

let chromium;
try { ({ chromium } = require('playwright')); }
catch (e) { console.error('Нужен Playwright: npm install && npx playwright install chromium'); process.exit(2); }

const ROOT = path.resolve(__dirname, '..');
const OUT_DIR = path.join(ROOT, '.snapshots', 'screens');
const MANIFEST = path.join(__dirname, 'screens-baseline.json');
const PORT = 8031;

const VIEWPORTS = [
  { key: '390',  width: 390,  height: 844 },
  { key: '768',  width: 768,  height: 1024 },
  { key: '1440', width: 1440, height: 900 }
];

/* stable: true  — страница детерминированная, хеш участвует в сравнении
   stable: false — зависит от данных/сессии, картинка только для глаз */
const PAGES = [
  { file: 'index.html',             stable: false, note: 'ленты зависят от данных' },
  { file: 'catalog.html',           stable: false, note: 'зависит от данных' },
  { file: 'tool.html?id=1',         stable: false, note: 'карточка зависит от данных' },
  { file: 'favorites.html',         stable: false, note: 'зависит от localStorage' },
  { file: 'new.html',               stable: false, note: 'форма подачи объявления' },
  { file: 'account.html',           stable: false, note: 'кабинет, без входа — заглушка' },
  { file: 'app.html',               stable: true,  note: 'страница приложения' },
  { file: 'offer.html',             stable: true,  note: 'оферта' },
  { file: 'partner-terms.html',     stable: true,  note: 'правила арендодателей' },
  { file: 'partner-privacy.html',   stable: true,  note: 'политика данных арендодателя' },
  { file: 'landlord-register.html', stable: true,  note: 'вход/анкета арендодателя' },
  { file: '404.html',               stable: true,  note: 'страница не найдена' }
];

const MIME = {
  '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8', '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg',
  '.webp': 'image/webp', '.webmanifest': 'application/manifest+json', '.txt': 'text/plain; charset=utf-8',
  '.xml': 'application/xml; charset=utf-8', '.apk': 'application/vnd.android.package-archive',
  '.ico': 'image/x-icon'
};

function serve(port) {
  const server = http.createServer((req, res) => {
    const urlPath = decodeURIComponent((req.url || '/').split('?')[0].split('#')[0]);
    let rel = urlPath === '/' ? 'index.html' : urlPath.replace(/^\/+/, '');
    const file = path.join(ROOT, rel);
    if (!file.startsWith(ROOT) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
      const notFound = path.join(ROOT, '404.html');
      if (fs.existsSync(notFound)) { res.writeHead(404, { 'Content-Type': MIME['.html'] }); return res.end(fs.readFileSync(notFound)); }
      res.writeHead(404); return res.end('not found');
    }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file).toLowerCase()] || 'application/octet-stream' });
    res.end(fs.readFileSync(file));
  });
  server.listen(port, '127.0.0.1');
  return server;
}

const slug = s => s.replace(/[^\w.-]+/g, '_');
const keyOf = r => `${r.page}__${r.viewport}`;

(async () => {
  const mode = process.argv.includes('--check') ? 'check' : 'save';
  fs.mkdirSync(OUT_DIR, { recursive: true });
  const server = serve(PORT);
  const browser = await chromium.launch();
  const results = [];

  for (const vp of VIEWPORTS) {
    const ctx = await browser.newContext({ viewport: { width: vp.width, height: vp.height }, deviceScaleFactor: 1 });
    await ctx.route('**/*', route => {
      const u = new URL(route.request().url());
      if (u.hostname === '127.0.0.1' || u.hostname === 'localhost') return route.continue();
      return route.abort();
    });
    const page = await ctx.newPage();
    for (const p of PAGES) {
      try { await page.goto(`http://127.0.0.1:${PORT}/${p.file}`, { waitUntil: 'load', timeout: 15000 }); } catch (e) {}
      await page.waitForTimeout(700);
      let buf;
      try { buf = await page.screenshot({ fullPage: true }); } catch (e) { continue; }
      const name = `${slug(p.file)}__${vp.key}.png`;
      fs.writeFileSync(path.join(OUT_DIR, name), buf);
      results.push({ page: p.file, stable: !!p.stable, viewport: vp.key, sha256: crypto.createHash('sha256').update(buf).digest('hex'), file: name });
    }
    await ctx.close();
  }

  await browser.close();
  server.close();

  if (mode === 'save') {
    const manifest = {
      generated_at: new Date().toISOString(),
      note: 'Хеши эталонных скриншотов: сравниваются только stable-страницы. Картинки — в .snapshots/screens/ (в репозиторий не попадают).',
      viewports: VIEWPORTS.map(v => v.key),
      pages: results
    };
    fs.writeFileSync(MANIFEST, JSON.stringify(manifest, null, 2) + '\n');
    console.log(`Эталон сохранён: tools/screens-baseline.json — ${results.length} снимков, картинки в .snapshots/screens/`);
    process.exit(0);
  }

  if (!fs.existsSync(MANIFEST)) { console.error('Эталона нет. Сначала: npm run screens'); process.exit(2); }
  const base = JSON.parse(fs.readFileSync(MANIFEST, 'utf8'));
  const baseMap = new Map(base.pages.map(r => [keyOf(r), r]));
  let diff = 0, compared = 0;
  for (const r of results) {
    const b = baseMap.get(keyOf(r));
    if (!b) { console.log(`  новое      ${keyOf(r)}`); continue; }
    if (!r.stable) continue;
    compared++;
    if (b.sha256 !== r.sha256) { console.log(`  ИЗМЕНЕНО   ${keyOf(r)}  →  .snapshots/screens/${r.file}`); diff++; }
  }
  console.log(`  сравнено стабильных снимков: ${compared}, расхождений: ${diff}`);
  if (diff) console.log('  Посмотрите картинки: .snapshots/screens/ (и сравните с предыдущим этапом).');
  process.exit(diff ? 1 : 0);
})();
