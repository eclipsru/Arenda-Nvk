#!/usr/bin/env node
'use strict';
/* ============================================================
   Ива — карта большой страницы: где HTML, где стили, где скрипты.

   Зачем: cabinet.html, chief.html, admin.html и ещё три страницы —
   это один файл на 40–270 КБ, в котором HTML, CSS и JS лежат вместе.
   Карта показывает номера строк и размеры блоков, чтобы не искать
   нужное место по всему файлу.

   Запуск:
     npm run map                    все страницы в корне
     npm run map -- cabinet.html    одна страница
     npm run map -- city/           все страницы города (по одной)
   ============================================================ */
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const args = process.argv.slice(2);

function pageFiles() {
  if (args.length) {
    const out = [];
    for (const a of args) {
      if (a.endsWith('/')) {
        const dir = path.join(ROOT, a);
        if (fs.existsSync(dir)) {
          for (const f of fs.readdirSync(dir).sort()) if (f.endsWith('.html')) out.push(path.join(a, f));
        }
      } else {
        out.push(a);
      }
    }
    return out;
  }
  return fs.readdirSync(ROOT).filter((f) => f.endsWith('.html')).sort();
}

function lineAt(text, index) {
  return text.slice(0, index).split('\n').length;
}

function functionsIn(code) {
  const names = [];
  const add = (name) => {
    if (name && !names.includes(name)) names.push(name);
  };
  for (const m of code.matchAll(/(?:async\s+)?function\s+([A-Za-z_$][\w$]*)\s*\(/g)) add(m[1]);
  for (const m of code.matchAll(/(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:async\s*)?(?:\([^)]*\)|[A-Za-z_$][\w$]*)\s*=>/g)) add(m[1]);
  return names;
}

function bytes(n) {
  if (n < 1024) return `${n} Б`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1).replace('.', ',')} КБ`;
  return `${(n / (1024 * 1024)).toFixed(1).replace('.', ',')} МБ`;
}

function mapPage(file) {
  const abs = path.join(ROOT, file);
  if (!fs.existsSync(abs)) {
    console.log(`${file}: файла нет`);
    return;
  }
  const text = fs.readFileSync(abs, 'utf8');
  const lines = text.split('\n').length;
  console.log(`${file} — ${bytes(Buffer.byteLength(text))}, ${lines} строк`);

  for (const m of text.matchAll(/<style\b[^>]*>([\s\S]*?)<\/style>/gi)) {
    console.log(`  <style>   строка ${lineAt(text, m.index)} — ${bytes(Buffer.byteLength(m[1]))}`);
  }

  let inlineIndex = 0;
  for (const m of text.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)) {
    if (/\bsrc\s*=/i.test(m[1]) || /application\/(?:ld\+json|json)/i.test(m[1])) continue;
    const body = m[2];
    if (!body.trim()) continue;
    inlineIndex += 1;
    const fns = functionsIn(body);
    const shown = fns.slice(0, 14).join(', ') + (fns.length > 14 ? ` … ещё ${fns.length - 14}` : '');
    console.log(`  <script> #${inlineIndex} строка ${lineAt(text, m.index)} — ${bytes(Buffer.byteLength(body))}${shown ? `: ${shown}` : ''}`);
  }

  const external = [];
  for (const m of text.matchAll(/<(?:script|link)\b[^>]*\b(?:src|href)=["']([^"']+)["'][^>]*>/gi)) {
    const url = m[1];
    if (!/^assets\//.test(url) && !/^https?:\/\//.test(url)) continue;
    external.push(url);
  }
  if (external.length) console.log(`  внешние:  ${external.join(', ')}`);
}

for (const file of pageFiles()) mapPage(file);
