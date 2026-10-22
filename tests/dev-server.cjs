// Сторож локального сервера предпросмотра (17.10.2026).
// Проверяет: страница отдаётся, CSS имеет правильный тип, кэш запрещён,
// вставка автоперезагрузки есть, 404 и защита от выхода за папку работают.
// Запуск: node tests/dev-server.cjs
const assert = require('node:assert/strict');
const path = require('node:path');
const { createServer } = require('../tools/dev-server.js');

const ROOT = path.resolve(__dirname, '..');

async function main() {
  const server = createServer({ root: ROOT, reload: true });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const { port } = server.address();
  const base = `http://127.0.0.1:${port}`;

  try {
    const page = await fetch(`${base}/index.html`);
    assert.equal(page.status, 200, 'index.html должен отдаваться');
    assert.match(page.headers.get('content-type') || '', /text\/html/, 'у страницы должен быть text/html');
    assert.match(page.headers.get('cache-control') || '', /no-store/, 'кэш браузера должен быть запрещён');
    const html = await page.text();
    assert.match(html, /<html/i, 'отдалась не страница сайта');
    assert.match(html, /__dev\/reload/, 'в страницу не вставлена автоперезагрузка');

    const css = await fetch(`${base}/assets/theme.css`);
    assert.equal(css.status, 200, 'CSS должен отдаваться');
    assert.match(css.headers.get('content-type') || '', /text\/css/, 'у CSS должен быть text/css');
    assert.match(css.headers.get('cache-control') || '', /no-store/, 'CSS тоже не должен кэшироваться');

    const rootPage = await fetch(`${base}/`);
    assert.equal(rootPage.status, 200, 'корень сайта должен открывать index.html');
    assert.match(await rootPage.text(), /<html/i, 'корень отдал не страницу');

    const prefixed = await fetch(`${base}/Arenda-Nvk/index.html`);
    assert.equal(prefixed.status, 200, 'префикс GitHub Pages /Arenda-Nvk/ должен работать локально');
    assert.match(await prefixed.text(), /<html/i, 'страница с префиксом отдала не сайт');

    const missing = await fetch(`${base}/no-such-page-for-dev-server-test.html`);
    assert.equal(missing.status, 404, 'несуществующая страница должна давать 404');

    const traversal = await fetch(`${base}/..%2F..%2Fetc%2Fpasswd`);
    assert.notEqual(traversal.status, 200, 'нельзя выходить за папку сайта');

    for (const blocked of ['/tools/dev-server.js', '/docs/ЖУРНАЛ.md', '/.git/config', '/package.json']) {
      const r = await fetch(base + blocked);
      assert.equal(r.status, 403, `файл вне сайта должен быть закрыт: ${blocked}`);
    }

    const head = await fetch(`${base}/index.html`, { method: 'HEAD' });
    assert.equal(head.status, 200, 'HEAD должен работать');
    assert.equal((await head.text()).length, 0, 'HEAD не должен отдавать тело');
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }

  console.log('Локальный сервер: страница, CSS, 404, HEAD, запрет кэша и защита папки проверены.');
}

main().catch((err) => {
  console.error('✘ ' + (err && err.message ? err.message : err));
  process.exit(1);
});
