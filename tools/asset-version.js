#!/usr/bin/env node
'use strict';
/* ============================================================
   Ива — метки кэша (?v=…) для общих файлов assets/*.css и *.js.

   Зачем: сайт статический, браузер кэширует CSS и JS. После правки
   assets/theme.css или assets/app.js нужно поменять метку во всех
   страницах, иначе часть людей увидит старую версию. Раньше это
   делалось руками в 80+ файлах и один раз уже разошлось.

   Команды:
     node tools/asset-version.js check
         проверить: у каждого файла одна метка на всех страницах
         (включая city/, шаблон и генератор страниц городов).

     node tools/asset-version.js bump assets/theme.css 20261017-rel
         поставить новую метку во всех страницах одной командой.

     node tools/asset-version.js bump-all 20261017-rel
         поднять метки у всех файлов, где они уже есть (крупный релиз).

     node tools/asset-version.js list
         показать текущие метки и сколько раз они встречаются.

   Код выхода: 0 — всё хорошо, 1 — расхождение/ошибка, 2 — неверный запуск.
   ============================================================ */
const fs = require('node:fs');
const path = require('node:path');

const REAL_ROOT = path.resolve(__dirname, '..');
let ROOT = process.env.IVA_ROOT ? path.resolve(process.env.IVA_ROOT) : REAL_ROOT;
const args = process.argv.slice(2);

const rootIndex = args.indexOf('--root');
if (rootIndex >= 0) {
  if (!args[rootIndex + 1]) {
    console.error('Нужно значение: --root <папка>');
    process.exit(2);
  }
  ROOT = path.resolve(args[rootIndex + 1]);
  args.splice(rootIndex, 2);
}

const cmd = args[0] || 'check';

function usage() {
  console.log(`Ива — метки кэша assets/*.css и *.js

  node tools/asset-version.js check
  node tools/asset-version.js list
  node tools/asset-version.js bump assets/theme.css 20261017-rel
  node tools/asset-version.js bump-all 20261017-rel

  Проверяются страницы сайта, city/*.html, tools/city_template.html
  и tools/build_city_pages.py — то есть всё, откуда берутся страницы.`);
}

function targets() {
  const out = [];
  for (const f of fs.readdirSync(ROOT)) {
    if (f.endsWith('.html')) out.push(f);
  }
  const city = path.join(ROOT, 'city');
  if (fs.existsSync(city)) {
    for (const f of fs.readdirSync(city)) {
      if (f.endsWith('.html')) out.push(path.join('city', f));
    }
  }
  for (const f of ['tools/city_template.html', 'tools/build_city_pages.py']) {
    if (fs.existsSync(path.join(ROOT, f))) out.push(f);
  }
  return out;
}

function refsIn(text, file) {
  const refs = [];
  const push = (asset, version) => refs.push({ asset, version: version || '', file });

  // <script src="assets/…"> и <link href="assets/…"> — в том числе внутри строк Python-генератора.
  // 404.html использует абсолютный адрес /Arenda-Nvk/assets/… — его тоже учитываем.
  for (const m of text.matchAll(/<(?:script|link)\b[^>]*\b(?:src|href)=["']([^"']+)["']/gi)) {
    const mm = m[1].match(/^(?:\/Arenda-Nvk\/)?assets\/([A-Za-z0-9._-]+\.(?:js|css))(?:\?v=([A-Za-z0-9._-]+))?$/);
    if (mm) push(mm[1], mm[2]);
  }
  return refs;
}

function collect() {
  const byAsset = new Map();
  for (const file of targets()) {
    let text;
    try {
      text = fs.readFileSync(path.join(ROOT, file), 'utf8');
    } catch {
      continue;
    }
    for (const ref of refsIn(text, file)) {
      if (!byAsset.has(ref.asset)) byAsset.set(ref.asset, new Map());
      const versions = byAsset.get(ref.asset);
      if (!versions.has(ref.version)) versions.set(ref.version, []);
      versions.get(ref.version).push(ref.file);
    }
  }
  return byAsset;
}

function rows() {
  const out = [];
  for (const [asset, versions] of [...collect()].sort((a, b) => a[0].localeCompare(b[0], 'ru'))) {
    const counts = [...versions.entries()]
      .map(([version, files]) => ({ version, count: files.length, files }))
      .sort((a, b) => b.count - a.count);
    out.push({ asset, counts, total: counts.reduce((n, c) => n + c.count, 0) });
  }
  return out;
}

function label(version) {
  return version || 'без метки';
}

function check(quiet = false) {
  const list = rows();
  if (!quiet) {
    console.log('Метки кэша общих файлов (assets/*.css, *.js):');
    for (const row of list) {
      const labels = row.counts.map((c) => `${label(c.version)}×${c.count}`).join(', ');
      console.log(`  ${row.asset.padEnd(24)} ${labels}`);
    }
    console.log('');
  }

  let problems = 0;
  for (const row of list) {
    const versioned = row.counts.filter((c) => c.version);
    const unversioned = row.counts.find((c) => !c.version);

    if (versioned.length > 1) {
      problems += 1;
      console.error(`✘ ${row.asset}: разные метки — ${versioned.map((c) => label(c.version)).join(' / ')}`);
      for (const c of versioned) {
        const files = [...new Set(c.files)];
        console.error(`    ${label(c.version)}: ${files.slice(0, 8).join(', ')}${files.length > 8 ? ` и ещё ${files.length - 8}` : ''}`);
      }
    }
    if (versioned.length && unversioned) {
      problems += 1;
      console.error(`✘ ${row.asset}: часть страниц без ?v= — браузер может взять старую копию`);
      console.error(`    без метки: ${[...new Set(unversioned.files)].slice(0, 8).join(', ')}`);
    }
  }

  if (problems) {
    console.error('');
    console.error(`Итог: проблем ${problems}.`);
    console.error('Починить одной командой: node tools/asset-version.js bump assets/<файл> <метка>');
    process.exitCode = 1;
    return false;
  }

  if (!quiet) console.log(`Итог: всё согласовано (${list.length} общих файлов).`);
  return true;
}

function escapeRe(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function bumpOne(assetArg, version, options = {}) {
  const asset = String(assetArg || '').replace(/^assets\//, '');
  if (!/^[A-Za-z0-9._-]+\.(?:js|css)$/.test(asset)) {
    console.error(`Неверное имя файла: ${assetArg}. Пример: assets/theme.css`);
    process.exitCode = 2;
    return 0;
  }
  if (!/^[A-Za-z0-9._-]+$/.test(String(version || ''))) {
    console.error(`Неверная метка: ${version}. Разрешены буквы, цифры, точка, дефис и подчёркивание.`);
    process.exitCode = 2;
    return 0;
  }
  if (!fs.existsSync(path.join(ROOT, 'assets', asset))) {
    console.error(`Нет файла assets/${asset} в ${ROOT}`);
    process.exitCode = 2;
    return 0;
  }

  const escaped = escapeRe(asset);
  const htmlRe = new RegExp(`((?:src|href)=["'](?:/Arenda-Nvk/)?assets/${escaped})(?:\\?v=([^"']*))?(["'])`, 'g');
  const pyRe = new RegExp(`(["'](?:/Arenda-Nvk/)?assets/${escaped})(?:\\?v=([^"']*))?(["'])`, 'g');
  let replaced = 0;
  const changed = [];

  for (const file of targets()) {
    const abs = path.join(ROOT, file);
    let text;
    try {
      text = fs.readFileSync(abs, 'utf8');
    } catch {
      continue;
    }
    let count = 0;
    let next = text.replace(htmlRe, (whole, pre, current, quote) => {
      if (current === version) return whole;
      count += 1;
      return `${pre}?v=${version}${quote}`;
    });
    // Генератор может хранить адрес без тега href — на этот случай второе правило.
    if (file.endsWith('.py')) {
      next = next.replace(pyRe, (whole, pre, current, quote) => {
        if (current === version) return whole;
        count += 1;
        return `${pre}?v=${version}${quote}`;
      });
    }
    if (count > 0 && next !== text) {
      fs.writeFileSync(abs, next);
      changed.push({ file, count });
      replaced += count;
    }
  }

  if (!options.quiet) {
    if (!replaced) {
      console.error(`✘ assets/${asset}: ссылок не найдено — метку ставить некуда`);
      process.exitCode = 1;
      return 0;
    }
    console.log(`assets/${asset} → ?v=${version}`);
    for (const c of changed) console.log(`  ${c.file} — ${c.count}`);
    console.log(`Итого заменено: ${replaced} в ${changed.length} файлах.`);
  }
  return replaced;
}

function bumpAll(version) {
  if (!/^[A-Za-z0-9._-]+$/.test(String(version || ''))) {
    console.error('Нужна метка: node tools/asset-version.js bump-all 20261017-rel');
    process.exitCode = 2;
    return;
  }
  const assets = rows().filter((row) => row.counts.some((c) => c.version)).map((row) => row.asset);
  if (!assets.length) {
    console.error('Нет файлов с метками — нечего менять.');
    process.exitCode = 1;
    return;
  }
  console.log(`Поднимаю метки у ${assets.length} файлов до ?v=${version}:`);
  let total = 0;
  let failed = false;
  for (const asset of assets) {
    const n = bumpOne(asset, version, { quiet: true });
    if (!n) failed = true;
    total += n;
  }
  console.log(`Готово: заменено ${total}. Проверка: node tools/asset-version.js check`);
  process.exitCode = failed ? 1 : 0;
}

switch (cmd) {
  case 'check':
    check();
    break;
  case 'list':
    for (const row of rows()) {
      const labels = row.counts.map((c) => `${label(c.version)}×${c.count}`).join(', ');
      console.log(`${row.asset.padEnd(24)} ${labels}`);
    }
    break;
  case 'bump':
    if (args.length < 3) {
      console.error('Нужно: node tools/asset-version.js bump assets/<файл> <метка>');
      process.exitCode = 2;
    } else {
      bumpOne(args[1], args[2]);
    }
    break;
  case 'bump-all':
    if (!args[1]) {
      console.error('Нужно: node tools/asset-version.js bump-all <метка>');
      process.exitCode = 2;
    } else {
      bumpAll(args[1]);
    }
    break;
  case 'help':
  case '--help':
  case '-h':
    usage();
    break;
  default:
    console.error(`Неизвестная команда: ${cmd}`);
    usage();
    process.exitCode = 2;
}
