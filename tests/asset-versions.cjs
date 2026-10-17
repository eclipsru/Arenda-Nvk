// Сторож меток кэша (17.10.2026).
// Проверяет, что у одного и того же файла assets/*.css|*.js одна метка ?v= на всех
// страницах сайта, в city/, в шаблоне и в генераторе городских страниц.
// Ловит ситуацию, когда после правки обновили 80 страниц, а 6 забыли.
// Запуск: node tests/asset-versions.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const ROOT = path.resolve(__dirname, '..');
const TOOL = path.join(ROOT, 'tools', 'asset-version.js');

function run(args, root) {
  return spawnSync(process.execPath, [TOOL, ...args], {
    cwd: ROOT,
    env: { ...process.env, IVA_ROOT: root || ROOT },
    encoding: 'utf8',
  });
}

function write(file, text) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, text);
}

// ---------- 1. Настоящий репозиторий ----------
const real = run(['check']);
assert.equal(real.status, 0,
  'в репозитории разные метки кэша:\n' + (real.stdout || '') + (real.stderr || ''));

// ---------- 2. Песочница: расхождение находится ----------
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'iva-assets-'));
try {
  write(path.join(tmp, 'assets/theme.css'), 'body{}\n');
  write(path.join(tmp, 'assets/app.js'), 'console.log(1);\n');
  write(path.join(tmp, 'index.html'),
    '<link rel="stylesheet" href="assets/theme.css?v=1">\n<script src="assets/app.js?v=1"></script>\n');
  write(path.join(tmp, 'city/a.html'),
    '<base href="../">\n<link rel="stylesheet" href="assets/theme.css?v=1">\n');
  write(path.join(tmp, 'tools/city_template.html'),
    '<link rel="stylesheet" href="assets/theme.css?v=1">\n');
  write(path.join(tmp, 'tools/build_city_pages.py'),
    "PAGE = '<link rel=\"stylesheet\" href=\"assets/theme.css?v=1\">'\n");
  write(path.join(tmp, '404.html'),
    '<script src="/Arenda-Nvk/assets/theme.css?v=1"></script>\n');

  let r = run(['check'], tmp);
  assert.equal(r.status, 0, 'одинаковые метки должны проходить:\n' + r.stdout + r.stderr);

  // Одна страница отстала — сторож обязан упасть и назвать файл.
  write(path.join(tmp, 'city/a.html'),
    '<base href="../">\n<link rel="stylesheet" href="assets/theme.css?v=OLD">\n');
  r = run(['check'], tmp);
  assert.equal(r.status, 1, 'разные метки должны ловиться');
  assert.match(r.stderr, /theme\.css/, 'в ошибке должен быть имя файла assets/theme.css');
  assert.match(r.stderr, /city\/a\.html/, 'в ошибке должна быть страница, где метка старая');

  // Команда bump чинит все страницы, включая city/, шаблон, генератор и 404.
  r = run(['bump', 'assets/theme.css', '2'], tmp);
  assert.equal(r.status, 0, 'bump должен пройти:\n' + r.stdout + r.stderr);
  for (const f of ['index.html', 'city/a.html', 'tools/city_template.html', 'tools/build_city_pages.py', '404.html']) {
    const text = fs.readFileSync(path.join(tmp, f), 'utf8');
    assert.match(text, /theme\.css\?v=2/, `${f}: bump не обновил метку`);
    assert.ok(!/theme\.css\?v=(?:1|OLD)/.test(text), `${f}: осталась старая метка`);
  }
  assert.match(fs.readFileSync(path.join(tmp, 'index.html'), 'utf8'), /app\.js\?v=1/,
    'bump одного файла не должен трогать другие файлы');
  r = run(['check'], tmp);
  assert.equal(r.status, 0, 'после bump проверка должна проходить:\n' + r.stdout + r.stderr);

  // Страница без метки при наличии меток у других — тоже ошибка.
  write(path.join(tmp, 'index.html'),
    '<link rel="stylesheet" href="assets/theme.css">\n<script src="assets/app.js?v=1"></script>\n');
  r = run(['check'], tmp);
  assert.equal(r.status, 1, 'страница без ?v= должна ловиться');
  assert.match(r.stderr, /без \?v=/, 'ошибка должна объяснять, что метки нет');

  // Несуществующий файл — понятный отказ, а не молчаливая замена.
  r = run(['bump', 'assets/nope.css', '3'], tmp);
  assert.notEqual(r.status, 0, 'bump несуществующего файла должен отказать');
  assert.match(r.stderr + r.stdout, /nope\.css/, 'в отказе должно быть имя файла');
} finally {
  fs.rmSync(tmp, { recursive: true, force: true });
}

console.log('Метки кэша: одна метка на файл; расхождение, пропуск метки и bump проверены в песочнице.');
