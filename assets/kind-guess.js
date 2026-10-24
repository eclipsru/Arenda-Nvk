/* Автоподбор категории, подкатегории и вида инструмента по названию (10.81).
   Слова берутся из iva_kinds.words (supabase/migrations/20261016_kind_words.sql).
   Тот же алгоритм — в приложении (IvaKindPatch.guess); оба проверяются на tests/kind-guess-cases.txt.

   Название → строчные, ё→е, всё кроме букв и цифр → пробел, слова.
   Запись «болгар 125» подходит, если каждая часть — начало какого-то слова названия.
   Очки записи — сумма длин частей. У вида: лучшая запись, при равенстве — число подошедших записей.
   Один лучший вид → {cat, sub, kind}; несколько в одной подкатегории → {cat, sub};
   в одной категории → {cat}; иначе — пусто. */
(function (root) {
  'use strict';

  function ivaKindTokens(s) {
    return String(s == null ? '' : s).toLowerCase().replace(/ё/g, 'е')
      .replace(/[^a-zа-я0-9]+/g, ' ').trim().split(' ').filter(Boolean);
  }

  function entryScore(entry, toks) {
    var parts = ivaKindTokens(entry), total = 0;
    if (!parts.length) return 0;
    for (var i = 0; i < parts.length; i++) {
      var p = parts[i], hit = false;
      for (var j = 0; j < toks.length; j++) {
        if (toks[j].lastIndexOf(p, 0) === 0) { hit = true; break; }
      }
      if (!hit) return 0;
      total += p.length;
    }
    return total;
  }

  /* kinds: [{key, cat, sub, words:[...]}] */
  function ivaGuessKind(name, kinds) {
    var none = { cat: '', sub: '', kind: '' };
    var toks = ivaKindTokens(name);
    if (!toks.length || !kinds || !kinds.length) return none;
    var best = [], bs = 0, bn = 0;
    for (var i = 0; i < kinds.length; i++) {
      var k = kinds[i], ws = (k && k.words) || [], m = 0, n = 0;
      if (!k || !k.key || !k.cat || !k.sub) continue;
      for (var w = 0; w < ws.length; w++) {
        var sc = entryScore(ws[w], toks);
        if (sc > 0) { n++; if (sc > m) m = sc; }
      }
      if (!m) continue;
      if (m > bs || (m === bs && n > bn)) { bs = m; bn = n; best = [k]; }
      else if (m === bs && n === bn) best.push(k);
    }
    if (!best.length) return none;
    var f = best[0];
    if (best.length === 1) return { cat: f.cat, sub: f.sub, kind: f.key };
    var sameSub = true, sameCat = true;
    for (var b = 1; b < best.length; b++) {
      if (best[b].cat !== f.cat) { sameCat = false; sameSub = false; }
      else if (best[b].sub !== f.sub) sameSub = false;
    }
    if (sameSub) return { cat: f.cat, sub: f.sub, kind: '' };
    if (sameCat) return { cat: f.cat, sub: '', kind: '' };
    return none;
  }

  /* Ручной выбор не перезаписываем: поле меняем, только если оно пустое
     или в нём всё ещё то, что подставили мы в прошлый раз. */
  function ivaKindMayFill(current, lastAuto) {
    current = current || '';
    return current === '' || current === (lastAuto || '');
  }

  /* Что поставить в форме: {cat, sub, kind} или null — ничего не менять. cur — что в форме сейчас,
     last — что подставили мы в прошлый раз. Категорию, выбранную руками, не меняем; подкатегорию и вид, выбранные
     руками, — тоже (пока категория та же). То же — IvaKindPatch.plan в приложении. */
  function ivaKindPlan(cur, last, g) {
    cur = cur || {}; last = last || {};
    var cc = cur.cat || '', cs = cur.sub || '', ck = cur.kind || '';
    if (!g || !g.cat) return null;
    if (!ivaKindMayFill(cc, last.cat) && cc !== g.cat) return null;
    var catChanged = g.cat !== cc;
    var sub = (catChanged || ivaKindMayFill(cs, last.sub) || cs === g.sub) ? g.sub : cs;
    var kind;
    if (sub === g.sub && (catChanged || sub !== cs || ivaKindMayFill(ck, last.kind) || ck === g.kind)) kind = g.kind;
    else kind = sub === cs ? ck : '';
    if (g.cat === cc && sub === cs && kind === ck) return null;
    return { cat: g.cat, sub: sub, kind: kind };
  }

  var api = { ivaKindTokens: ivaKindTokens, ivaGuessKind: ivaGuessKind, ivaKindMayFill: ivaKindMayFill, ivaKindPlan: ivaKindPlan };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else { root.ivaKindTokens = ivaKindTokens; root.ivaGuessKind = ivaGuessKind; root.ivaKindMayFill = ivaKindMayFill; root.ivaKindPlan = ivaKindPlan; }
})(this);
