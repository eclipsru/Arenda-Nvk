// Сторож «скрипты читаются на любом Python» (этап П3, инцидент 01.10.2026).
//
// Что случилось: в tools/build_city_pages.py в выражении f-строки оказался
// обратный слэш — «f'<h2{" style=\"…\"" if collapse else ""}>». До Python 3.12
// это SyntaxError, поэтому генератор страниц городов молча не работал на
// Python 3.10–3.11 (Windows-машина владельца как раз из таких). Проверка
// проходила на Python 3.12, где ограничение снято, — то есть регресс был
// невидим ровно там, где он ломает работу.
//
// Этот сторож делает две вещи:
//   1. ищет обратный слэш внутри выражения {…} любой f-строки (по исходнику —
//      значит ловит на любой версии Python, а не только на старой);
//   2. если python3 есть — компилирует все .py-файлы репозитория.
//
// Запуск: node tests/py-compat.cjs
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');

const ROOT = path.resolve(__dirname, '..');

// Все Python-файлы проекта. Идём по файловой системе, а не по git: новый файл
// должен попадать под сторожа сразу, а не после первого коммита.
const SKIP_DIRS = new Set(['.git', 'node_modules', '.snapshots', '.venv', 'venv', '__pycache__']);
function pythonFiles(dir = ROOT, acc = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.isDirectory()) {
      if (SKIP_DIRS.has(e.name) || e.name.startsWith('backup-')) continue;
      pythonFiles(path.join(dir, e.name), acc);
    } else if (e.isFile() && e.name.endsWith('.py')) {
      acc.push(path.relative(ROOT, path.join(dir, e.name)).split(path.sep).join('/'));
    }
  }
  return acc.sort();
}

// Разбирает одну строку исходника: находит f-строки и проверяет их выражения.
// Строгого парсера Python здесь нет — достаточно честного обхода с учётом
// кавычек, экранирования и вложенности фигурных скобок (в проекте f-строки
// однострочные; многострочные тройные кавычки тоже проходят).
function fstringIssues(file, src) {
  const problems = [];
  let i = 0;
  let line = 1;
  while (i < src.length) {
    const ch = src[i];
    if (ch === '\n') { line++; i++; continue; }
    if (ch === '#') { while (i < src.length && src[i] !== '\n') i++; continue; }
    // начало строкового литерала с возможным префиксом f/F и r/b (rb, fr…)
    const m = /^[fFrRbBuU]{0,3}(?=['"])/.exec(src.slice(i, i + 4));
    if (!m) { i++; continue; }
    const prefix = m[0].toLowerCase();
    const isF = prefix.includes('f');
    let q = src[i + prefix.length];
    let triple = src.slice(i + prefix.length, i + prefix.length + 3) === q.repeat(3);
    let j = i + prefix.length + (triple ? 3 : 1);
    let depth = 0;
    let innerQuote = null;
    let sawBackslashInExpr = 0;
    while (j < src.length) {
      const c = src[j];
      if (c === '\n') line++;
      if (c === '\\') { // экранирование: вне выражения — часть строки, внутри — нарушение
        if (depth > 0) sawBackslashInExpr = line;
        j += 2;
        continue;
      }
      if (innerQuote) {
        if (c === innerQuote) innerQuote = null;
        else if (c === '\n' && innerQuote !== "'" && innerQuote !== '"') innerQuote = null;
        j++;
        continue;
      }
      if (depth > 0 && (c === "'" || c === '"')) { innerQuote = c; j++; continue; }
      if (c === '{') {
        if (src[j + 1] === '{') { j += 2; continue; } // {{ — не выражение
        depth++; j++; continue;
      }
      if (c === '}') {
        if (src[j + 1] === '}' && depth === 0) { j += 2; continue; }
        depth = Math.max(0, depth - 1); j++; continue;
      }
      if (depth === 0 && src.slice(j, j + (triple ? 3 : 1)) === (triple ? q.repeat(3) : q)) {
        j += triple ? 3 : 1;
        break;
      }
      j++;
    }
    if (isF && sawBackslashInExpr) {
      problems.push({ file, line: sawBackslashInExpr });
    }
    i = j;
  }
  return problems;
}

const files = pythonFiles();
assert.ok(files.length >= 5, `ожидалось минимум 5 Python-файлов в репозитории, найдено ${files.length}`);

let backslashHits = 0;
for (const rel of files) {
  const src = fs.readFileSync(path.join(ROOT, rel), 'utf8');
  for (const p of fstringIssues(rel, src)) {
    backslashHits++;
    console.error(`✘ ${rel}:${p.line} — обратный слэш внутри выражения f-строки: `
      + 'файл не прочитается на Python 3.10–3.11. Вынесите выражение в переменную.');
  }
}
assert.equal(backslashHits, 0, `f-строк с обратным слэшем в выражении: ${backslashHits}`);

// Компиляция — если python3 вообще есть (на машине без него честно пропускаем эту часть).
const probe = spawnSync('python3', ['-V'], { encoding: 'utf8' });
if (probe.status !== 0) {
  console.log('py-compat OK: правило f-строк проверено в '
    + `${files.length} файлах; компиляция пропущена (python3 не найден)`);
  process.exit(0);
}
for (const rel of files) {
  const r = spawnSync('python3', ['-m', 'py_compile', path.join(ROOT, rel)], { encoding: 'utf8' });
  assert.equal(r.status, 0, `${rel} не компилируется на ${probe.stdout.trim()}:\n${r.stderr}`);
}

console.log(`py-compat OK: ${files.length} Python-файлов читаются на `
  + `${probe.stdout.trim()} (f-строки без обратных слэшей в выражениях)`);
