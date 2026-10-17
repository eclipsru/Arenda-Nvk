#!/usr/bin/env node
'use strict';
/* ============================================================
   Ива — локальный сервер для быстрой правки сайта.

   Запуск:   npm run dev        (или: node tools/dev-server.js)
   Открыть:  http://localhost:8080

   Что делает:
     • отдаёт файлы сайта как GitHub Pages (index.html, city/, assets/);
     • запрещает браузеру кэшировать файлы — правка видна сразу;
     • сам перезагружает страницу в браузере после сохранения файла;
     • слушает 0.0.0.0, поэтому открывается и с телефона в той же сети,
       и в предпросмотре песочницы.

   Ничего не публикует и не меняет файлы репозитория.
   ============================================================ */
const http = require('node:http');
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { URL } = require('node:url');
const { spawn } = require('node:child_process');

const ROOT = path.resolve(__dirname, '..');
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.xml': 'application/xml; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
  '.md': 'text/markdown; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.apk': 'application/vnd.android.package-archive',
  '.pdf': 'application/pdf',
};

const RELOAD_SNIPPET = `
<!-- Локальный предпросмотр: перезагрузка после сохранения файла (в публикацию не попадает) -->
<script>
(function () {
  try {
    var es = new EventSource('/__dev/reload');
    es.onmessage = function (e) { if (e.data === 'reload') location.reload(); };
  } catch (e) {}
})();
</script>`;

function parseArgs(argv) {
  const out = {
    port: Number(process.env.PORT || 8080),
    host: process.env.HOST || '0.0.0.0',
    root: ROOT,
    reload: true,
    open: false,
    help: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--port' || a === '-p') out.port = Number(argv[++i]);
    else if (a.startsWith('--port=')) out.port = Number(a.slice(7));
    else if (a === '--host') out.host = argv[++i];
    else if (a.startsWith('--host=')) out.host = a.slice(7);
    else if (a === '--root') out.root = path.resolve(argv[++i]);
    else if (a === '--no-reload') out.reload = false;
    else if (a === '--open') out.open = true;
    else if (a === '--help' || a === '-h') out.help = true;
  }
  return out;
}

function usage() {
  console.log(`Ива — локальный сервер предпросмотра

  npm run dev                     порт 8080, адрес 0.0.0.0, автоперезагрузка
  node tools/dev-server.js --port 9000
  node tools/dev-server.js --no-reload
  node tools/dev-server.js --open          открыть браузер после запуска

  Переменные окружения: PORT, HOST.`);
}

function contentType(file) {
  return MIME[path.extname(file).toLowerCase()] || 'application/octet-stream';
}

/* Сервер предпросмотра отдаёт только файлы сайта: страницы, assets/, city/,
   APK и служебные файлы корня. Папки tools/, tests/, docs/, .git, .env
   и прочее наружу не выходят — даже если кто-то откроет прямой адрес. */
function isSitePath(pathname) {
  const parts = pathname.split('/').filter(Boolean);
  if (!parts.length) return false;
  if (parts.some((p) => p.startsWith('.') || p === 'node_modules')) return false;
  if (parts[0] === 'assets' || parts[0] === 'city') return true;
  if (parts.length === 1) {
    // package.json и список APK сайту не нужны — наружу их не отдаём.
    if (['package.json', 'package-lock.json', 'app-keep-apks.txt'].includes(parts[0])) return false;
    return /\.(html|apk|json|svg|png|jpe?g|gif|webp|ico|webmanifest|txt|xml)$/i.test(parts[0]);
  }
  return false;
}

async function send404(req, res, root) {
  const fallback = path.join(root, '404.html');
  try {
    const body = await fsp.readFile(fallback);
    res.writeHead(404, {
      'Content-Type': 'text/html; charset=utf-8',
      'Content-Length': body.length,
      'Cache-Control': 'no-store, no-cache, must-revalidate',
    });
    if (req.method === 'HEAD') res.end();
    else res.end(body);
  } catch {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end('404 — файл не найден');
  }
}

function createServer(options = {}) {
  const root = path.resolve(options.root || ROOT);
  const reload = options.reload !== false;
  const clients = new Set();
  let watchers = null;
  let watchTimer = null;

  function notify() {
    for (const res of clients) {
      try {
        res.write('data: reload\n\n');
      } catch {
        clients.delete(res);
      }
    }
  }

  function scheduleReload() {
    if (watchTimer) clearTimeout(watchTimer);
    watchTimer = setTimeout(notify, 120);
  }

  function ensureWatch() {
    if (watchers || !reload) return;
    watchers = [];
    for (const dir of [root, path.join(root, 'assets'), path.join(root, 'city')]) {
      if (!fs.existsSync(dir)) continue;
      try {
        const w = fs.watch(dir, { persistent: false }, (_event, filename) => {
          const name = filename ? String(filename) : '';
          if (/\.(apk|idsig)$/i.test(name)) return;
          if (name.startsWith('.') || name === 'node_modules') return;
          scheduleReload();
        });
        w.on('error', () => {});
        watchers.push(w);
      } catch {
        /* на некоторых файловых системах слежение недоступно — страница всё равно работает */
      }
    }
  }

  const server = http.createServer(async (req, res) => {
    const started = Date.now();
    res.on('finish', () => {
      if (req.url !== '/__dev/reload') {
        console.log(`${res.statusCode} ${req.method} ${req.url} — ${Date.now() - started} мс`);
      }
    });

    if (req.method !== 'GET' && req.method !== 'HEAD') {
      res.writeHead(405, { Allow: 'GET, HEAD', 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('Только GET и HEAD');
      return;
    }

    let pathname;
    try {
      pathname = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
    } catch {
      res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('Некорректный адрес');
      return;
    }

    if (pathname === '/__dev/reload') {
      res.writeHead(200, {
        'Content-Type': 'text/event-stream; charset=utf-8',
        'Cache-Control': 'no-cache, no-transform',
        Connection: 'keep-alive',
        'X-Accel-Buffering': 'no',
      });
      res.write('retry: 1000\n\n');
      clients.add(res);
      req.on('close', () => clients.delete(res));
      ensureWatch();
      return;
    }

    // На GitHub Pages сайт живёт по адресу /Arenda-Nvk/ — локально срезаем этот префикс,
    // чтобы работали absolute-ссылки из 404.html и старых страниц.
    if (pathname === '/Arenda-Nvk') pathname = '/';
    else if (pathname.startsWith('/Arenda-Nvk/')) pathname = pathname.slice('/Arenda-Nvk'.length);

    if (pathname === '/') pathname = '/index.html';
    if (pathname.endsWith('/')) pathname += 'index.html';

    if (!isSitePath(pathname)) {
      res.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('403 — этот файл не относится к сайту');
      return;
    }

    const abs = path.resolve(root, `.${pathname}`);
    if (abs !== root && !abs.startsWith(root + path.sep)) {
      res.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('403 — нельзя выходить за папку сайта');
      return;
    }

    let stat;
    try {
      stat = await fsp.stat(abs);
    } catch {
      await send404(req, res, root);
      return;
    }

    let file = abs;
    if (stat.isDirectory()) {
      file = path.join(abs, 'index.html');
      try {
        stat = await fsp.stat(file);
      } catch {
        await send404(req, res, root);
        return;
      }
    }

    const ext = path.extname(file).toLowerCase();
    const headers = {
      'Content-Type': contentType(file),
      'Cache-Control': 'no-store, no-cache, must-revalidate, max-age=0',
      Pragma: 'no-cache',
      Expires: '0',
      'X-Content-Type-Options': 'nosniff',
    };

    if (ext === '.html' && reload) {
      let html = await fsp.readFile(file, 'utf8');
      html = /<\/body>/i.test(html) ? html.replace(/<\/body>/i, `${RELOAD_SNIPPET}\n</body>`) : html + RELOAD_SNIPPET;
      const body = Buffer.from(html, 'utf8');
      res.writeHead(200, { ...headers, 'Content-Length': body.length });
      if (req.method === 'HEAD') res.end();
      else res.end(body);
      return;
    }

    res.writeHead(200, { ...headers, 'Content-Length': stat.size });
    if (req.method === 'HEAD') {
      res.end();
      return;
    }
    fs.createReadStream(file).on('error', () => res.destroy()).pipe(res);
  });

  const heartbeat = setInterval(() => {
    for (const res of clients) {
      try {
        res.write(': ping\n\n');
      } catch {
        clients.delete(res);
      }
    }
  }, 20000);
  heartbeat.unref();

  server.on('close', () => {
    clearInterval(heartbeat);
    if (watchTimer) clearTimeout(watchTimer);
    if (watchers) watchers.forEach((w) => w.close());
    for (const res of clients) {
      try { res.end(); } catch { /* уже закрыт */ }
    }
    clients.clear();
  });

  return server;
}

function lanAddresses() {
  const out = [];
  for (const list of Object.values(os.networkInterfaces())) {
    for (const item of list || []) {
      if (item && item.family === 'IPv4' && !item.internal) out.push(item.address);
    }
  }
  return out;
}

function openBrowser(url) {
  let cmd;
  let args;
  if (process.platform === 'win32') {
    cmd = 'cmd';
    args = ['/c', 'start', '', url];
  } else if (process.platform === 'darwin') {
    cmd = 'open';
    args = [url];
  } else {
    cmd = 'xdg-open';
    args = [url];
  }
  try {
    spawn(cmd, args, { stdio: 'ignore', detached: true }).unref();
  } catch {
    /* браузер не обязателен */
  }
}

function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.help) {
    usage();
    return;
  }
  const server = createServer(opts);
  server.on('error', (err) => {
    if (err.code === 'EADDRINUSE') {
      console.error(`Порт ${opts.port} занят. Запустите с другим портом: node tools/dev-server.js --port ${opts.port + 1}`);
    } else {
      console.error('Сервер не запустился:', err.message);
    }
    process.exit(1);
  });
  server.listen(opts.port, opts.host, () => {
    const addr = server.address();
    const port = typeof addr === 'object' && addr ? addr.port : opts.port;
    const local = `http://localhost:${port}/`;
    console.log('');
    console.log('  Ива — локальный предпросмотр');
    console.log(`  Открыть:  ${local}`);
    for (const ip of lanAddresses()) console.log(`  В сети:   http://${ip}:${port}/`);
    console.log('  Остановить: Ctrl+C');
    console.log('');
    if (opts.open) openBrowser(local);
  });
  const stop = () => {
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 1500).unref();
  };
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
}

if (require.main === module) main();

module.exports = { createServer, parseArgs, MIME };
