/* Справочник моделей (этап 2, 10.82): подсказки моделей в форме добавления и характеристики.
   Данные — iva_models / iva_brands / iva_spec_fields (supabase/migrations/20261017_models*.sql).
   Тот же алгоритм — в приложении (IvaModelPatch); оба проверяются на tests/model-search-cases.txt.

   Поиск. Слова запроса — как в автоподборе вида (ivaKindTokens: строчные, ё→е, всё кроме букв и цифр — пробел).
   У модели «сильные» слова: слова бренда и модели, их части «буквы/цифры» (hr2470 → hr, 2470), модель слитно
   (GBH 2-26 → gbh226) и написания бренда (макита). «Слабые» слова: слова названия (перфоратор) и основы слов вида
   из iva_kinds.words (болгар, ушм).
   Слово запроса:  начало сильного слова → 2 × длина (+1, если совпало целиком; одна буква — только целиком);
                   иначе начало слабого слова или основа вида — начало слова запроса → длина;
                   иначе слово с цифрой → модель не подходит (это код другой модели);
                   иначе слово от 3 букв — начало слова другого бренда (штиль, bosch) → модель не подходит;
                   иначе слово начинается с основы другого вида (бензопила у болгарки) → минус 2 × длина;
                   иначе → минус длина (лишние слова вроде «аккумуляторный» не прячут модель, но опускают её).
   Модель подходит, если хоть одно слово совпало сильно и очков больше нуля. Если есть модели, где совпали все слова
   запроса, показываются только они (makita dga — только DGA, без остальных Makita). Порядок: очки по убыванию, затем меньше
   слов модели, которых нет в запросе (Гном 10-10 раньше Гном 6-10), затем более короткий код модели (слитно), затем название (строчные, по кодам символов — так же сравнивает Java).
   Пустой запрос (поле «Модель» с известным видом) — по названию. По умолчанию 8 подсказок. */
(function (root) {
  'use strict';

  var tokens = root && root.ivaKindTokens;
  if (!tokens && typeof require === 'function') tokens = require('./kind-guess.js').ivaKindTokens;

  function splitLD(t) {
    return t.match(/[a-zа-я]+|[0-9]+/g) || [];
  }

  function pushU(arr, v) {
    if (v && arr.indexOf(v) < 0) arr.push(v);
  }

  /* models: [{id, kind, brand, model, title, specs}], brands: [{name, aliases}], kinds: [{key, words}] */
  function ivaModelIndex(models, brands, kinds) {
    var al = {}, kw = {}, i, j;
    for (i = 0; brands && i < brands.length; i++) {
      var b = brands[i], at = [];
      for (j = 0; b.aliases && j < b.aliases.length; j++) {
        var ws = tokens(b.aliases[j]);
        for (var w = 0; w < ws.length; w++) pushU(at, ws[w]);
      }
      al[b.name] = at;
    }
    for (i = 0; kinds && i < kinds.length; i++) {
      var kt = [];
      for (j = 0; kinds[i].words && j < kinds[i].words.length; j++) {
        var parts = tokens(kinds[i].words[j]);
        for (var p = 0; p < parts.length; p++) if (parts[p].length >= 3) pushU(kt, parts[p]);
      }
      kw[kinds[i].key] = kt;
    }
    var out = [], allB = [], allS = [];
    for (i = 0; brands && i < brands.length; i++) {
      var nb = tokens(brands[i].name).concat(al[brands[i].name] || []);
      for (j = 0; j < nb.length; j++) if (nb[j].length >= 3) pushU(allB, nb[j]);
    }
    for (var kk in kw) if (Object.prototype.hasOwnProperty.call(kw, kk)) for (j = 0; j < kw[kk].length; j++) pushU(allS, kw[kk][j]);
    for (i = 0; models && i < models.length; i++) {
      var m = models[i];
      if (!m || !m.brand || !m.model) continue;
      var strong = [], weak = [], mt = tokens(m.model), bt = tokens(m.brand), t;
      for (j = 0; j < bt.length; j++) pushU(strong, bt[j]);
      for (j = 0; j < mt.length; j++) {
        pushU(strong, mt[j]);
        var ld = splitLD(mt[j]);
        if (ld.length > 1) for (t = 0; t < ld.length; t++) pushU(strong, ld[t]);
      }
      if (mt.length > 1) pushU(strong, mt.join(''));
      var a = al[m.brand] || [];
      for (j = 0; j < a.length; j++) pushU(strong, a[j]);
      var tt = tokens(m.title);
      for (j = 0; j < tt.length; j++) if (strong.indexOf(tt[j]) < 0) pushU(weak, tt[j]);
      out.push({ m: m, strong: strong, weak: weak, stems: kw[m.kind] || [], key: String(m.title || '').toLowerCase(), code: mt.join('').length, codeToks: mt });
    }
    out.brandToks = allB;
    out.stemToks = allS;
    return out;
  }

  /* Что про слово запроса известно без модели: цифра, другой бренд, основа какого-то вида. */
  function queryInfo(q, index) {
    var out = [];
    for (var i = 0; i < q.length; i++) {
      var w = q[i], brand = false, stem = false, j;
      if (w.length >= 3) for (j = 0; j < index.brandToks.length && !brand; j++) if (index.brandToks[j].lastIndexOf(w, 0) === 0) brand = true;
      for (j = 0; j < index.stemToks.length && !stem; j++) if (w.lastIndexOf(index.stemToks[j], 0) === 0) stem = true;
      out.push({ w: w, digit: /[0-9]/.test(w), brand: brand, stem: stem });
    }
    return out;
  }

  /* Сколько слов модели не покрыто запросом (слово запроса — начало слова модели; одна буква — целиком). */
  function uncovered(e, qi) {
    var n = 0, seen = [];
    for (var i = 0; i < e.codeToks.length; i++) {
      var t = e.codeToks[i], hit = false;
      if (seen.indexOf(t) >= 0) continue;
      seen.push(t);
      for (var j = 0; j < qi.length && !hit; j++) {
        var w = qi[j].w;
        if (t === w || (w.length >= 2 && t.lastIndexOf(w, 0) === 0)) hit = true;
      }
      if (!hit) n++;
    }
    return n;
  }

  /* Очки модели или null; e.miss — сколько слов запроса не совпало. */
  function score(e, qi) {
    var total = 0, hasStrong = false;
    e.miss = 0;
    for (var i = 0; i < qi.length; i++) {
      var w = qi[i].w, best = 0, j;
      for (j = 0; j < e.strong.length; j++) {
        var s = e.strong[j];
        if (s === w) { best = 2 * w.length + 1; break; }
        if (w.length >= 2 && s.lastIndexOf(w, 0) === 0 && 2 * w.length > best) best = 2 * w.length;
      }
      if (best) { hasStrong = true; total += best; continue; }
      var weak = false;
      for (j = 0; j < e.weak.length && !weak; j++) if (e.weak[j].lastIndexOf(w, 0) === 0) weak = true;
      for (j = 0; j < e.stems.length && !weak; j++) if (w.lastIndexOf(e.stems[j], 0) === 0) weak = true;
      if (weak) { total += w.length; continue; }
      if (qi[i].digit || qi[i].brand) return null;
      e.miss++;
      total -= qi[i].stem ? 2 * w.length : w.length;
    }
    return hasStrong && total > 0 ? total : null;
  }

  /* opts: {kind, brand, limit}. Пустой запрос с видом — модели вида (и бренда) по названию. */
  function ivaModelSearch(query, index, opts) {
    opts = opts || {};
    var limit = opts.limit || 8, q = tokens(query), res = [], qi = index ? queryInfo(q, index) : [], full = false;
    for (var i = 0; index && i < index.length; i++) {
      var e = index[i];
      if (opts.kind && e.m.kind !== opts.kind) continue;
      if (opts.brand && e.m.brand !== opts.brand) continue;
      var sc;
      if (!q.length) {
        if (!opts.kind) continue;
        sc = 0;
      } else {
        sc = score(e, qi);
        if (sc === null) continue;
      }
      var miss = q.length ? e.miss : 0;
      if (!miss) full = true;
      res.push({ e: e, s: sc, miss: miss, u: q.length ? uncovered(e, qi) : 0, c: q.length ? e.code : 0 });
    }
    if (full) res = res.filter(function (r) { return !r.miss; });
    res.sort(function (a, b) {
      if (a.s !== b.s) return b.s - a.s;
      if (a.u !== b.u) return a.u - b.u;
      if (a.c !== b.c) return a.c - b.c;
      return a.e.key < b.e.key ? -1 : a.e.key > b.e.key ? 1 : 0;
    });
    var out = [];
    for (var r = 0; r < res.length && out.length < limit; r++) out.push(res[r].e.m);
    return out;
  }

  /* Бренды, у которых есть модели этого вида — по алфавиту (без приоритетов). */
  function ivaModelBrands(index, kind) {
    var seen = [];
    for (var i = 0; index && i < index.length; i++) {
      if (kind && index[i].m.kind !== kind) continue;
      pushU(seen, index[i].m.brand);
    }
    return seen.sort(function (a, b) {
      var x = a.toLowerCase(), y = b.toLowerCase();
      return x < y ? -1 : x > y ? 1 : 0;
    });
  }

  function str(v) {
    return v == null ? '' : String(v);
  }

  /* Подстановка характеристик модели. cur — что в форме сейчас {key: строка}, lastAuto — что подставили мы
     в прошлый раз. Значение ставим, если поле пустое или в нём всё ещё наше прошлое значение; наши прошлые
     значения, которых у новой модели нет, убираем. Ручные правки не трогаем.
     Возвращает {values, auto}: values — новое содержимое формы, auto — что теперь стоит «от модели». */
  function ivaSpecsFill(cur, lastAuto, modelSpecs) {
    cur = cur || {}; lastAuto = lastAuto || {}; modelSpecs = modelSpecs || {};
    var values = {}, auto = {}, k;
    for (k in cur) if (Object.prototype.hasOwnProperty.call(cur, k)) values[k] = str(cur[k]);
    for (k in lastAuto) {
      if (!Object.prototype.hasOwnProperty.call(lastAuto, k) || Object.prototype.hasOwnProperty.call(modelSpecs, k)) continue;
      if (str(values[k]) === str(lastAuto[k])) values[k] = '';
    }
    for (k in modelSpecs) {
      if (!Object.prototype.hasOwnProperty.call(modelSpecs, k)) continue;
      var c = str(values[k]), v = str(modelSpecs[k]);
      if (c === '' || c === str(lastAuto[k])) { values[k] = v; auto[k] = v; }
    }
    return { values: values, auto: auto };
  }

  /* Модель ещё подходит к названию: код модели слитно есть в названии слитно (Makita GBH2-26 ↔ GBH 2-26). */
  function ivaModelStillFits(name, model) {
    if (!model || !model.model) return false;
    var code = tokens(model.model).join('');
    return code !== '' && tokens(name).join('').indexOf(code) >= 0;
  }

  /* Характеристики к сохранению: только поля вида; число — число (запятая = точка), выбор — из вариантов,
     строка — без пробелов по краям. Пустое не сохраняем. fields: [{key, type, options}]. */
  function ivaSpecsClean(values, fields) {
    var out = {}, n = 0;
    values = values || {};
    for (var i = 0; fields && i < fields.length; i++) {
      var f = fields[i], raw = str(values[f.key]).trim();
      if (raw === '') continue;
      if (f.type === 'num') {
        var t = raw.replace(',', '.');
        if (!/^\d+(\.\d+)?$/.test(t)) continue;
        out[f.key] = Number(t);
      } else if (f.type === 'choice') {
        if (!f.options || f.options.indexOf(raw) < 0) continue;
        out[f.key] = raw;
      } else {
        out[f.key] = raw;
      }
      n++;
    }
    return n ? out : null;
  }

  /* Поля вида по порядку: sort, затем ключ (как IvaModelPatch.fieldsFor). md — результат ivaModelsLoad. */
  function ivaFieldsFor(md, kind) {
    var all = md && md.fields ? md.fields : [], out = [];
    for (var i = 0; i < all.length; i++) if (kind && all[i].kind === kind) out.push(all[i]);
    return out.sort(function (a, b) {
      if ((a.sort || 0) !== (b.sort || 0)) return (a.sort || 0) - (b.sort || 0);
      return a.key < b.key ? -1 : a.key > b.key ? 1 : 0;
    });
  }

  var MD_KEY = 'iva_models_v1', MD_TTL = 30 * 60 * 1000, MD_PAGE = 1000;
  var mdMem = null;

  /* Справочник с сервера: бренды, поля, модели страницами по 1000 (больше PostgREST не отдаёт). Копия — на 30 минут
     (в памяти и в localStorage). Не загрузился (SQL не применён, нет сети) — null: формы работают как раньше.
     api(path) — функция страницы (sb.js / cabinet.html), kinds — [{key, words}] для основ видов. */
  function ivaModelsLoad(api, kinds) {
    function build(raw) {
      var byId = {};
      for (var i = 0; i < raw.models.length; i++) byId[raw.models[i].id] = raw.models[i];
      return { at: raw.at, models: raw.models, fields: raw.fields, byId: byId, index: ivaModelIndex(raw.models, raw.brands, kinds || []) };
    }
    var now = Date.now();
    if (mdMem && now - mdMem.at < MD_TTL) return Promise.resolve(build(mdMem));
    try {
      var c = typeof localStorage !== 'undefined' && JSON.parse(localStorage.getItem(MD_KEY) || 'null');
      if (c && c.at && now - c.at < MD_TTL && c.models && c.models.length) { mdMem = c; return Promise.resolve(build(c)); }
    } catch (e) {}
    var raw = { at: now, models: [], brands: [], fields: [] };
    function page(off) {
      return Promise.resolve(api('/rest/v1/iva_models?select=id,kind,brand,model,title,specs&active=eq.true&order=id.asc&limit=' + MD_PAGE + '&offset=' + off))
        .then(function (rows) {
          rows = Array.isArray(rows) ? rows : [];
          for (var i = 0; i < rows.length; i++) {
            var r = rows[i];
            if (r && r.id && r.brand && r.model) raw.models.push({ id: r.id, kind: r.kind || '', brand: r.brand, model: r.model, title: r.title || '',
              specs: r.specs && typeof r.specs === 'object' && !Array.isArray(r.specs) ? r.specs : {} });
          }
          return rows.length < MD_PAGE || off >= 19 * MD_PAGE ? null : page(off + MD_PAGE);
        });
    }
    return Promise.resolve(api('/rest/v1/iva_brands?select=name,aliases&order=name.asc'))
      .then(function (b) {
        raw.brands = (Array.isArray(b) ? b : []).filter(function (x) { return x && x.name; })
          .map(function (x) { return { name: x.name, aliases: Array.isArray(x.aliases) ? x.aliases : [] }; });
        return api('/rest/v1/iva_spec_fields?select=kind,key,title,unit,type,options,sort&order=kind.asc,sort.asc');
      })
      .then(function (f) {
        raw.fields = (Array.isArray(f) ? f : []).filter(function (x) { return x && x.kind && x.key && /^(num|choice|text)$/.test(x.type); })
          .map(function (x) { return { kind: x.kind, key: x.key, title: x.title || x.key, unit: x.unit || '', type: x.type,
            options: Array.isArray(x.options) ? x.options : [], sort: x.sort || 0 }; });
        return page(0);
      })
      .then(function () {
        if (!raw.models.length || !raw.fields.length) return null;
        mdMem = raw;
        try { if (typeof localStorage !== 'undefined') localStorage.setItem(MD_KEY, JSON.stringify(raw)); } catch (e) {}
        return build(raw);
      })
      .catch(function () { return null; });
  }

  var api = {
    ivaModelIndex: ivaModelIndex, ivaModelSearch: ivaModelSearch, ivaModelBrands: ivaModelBrands,
    ivaSpecsFill: ivaSpecsFill, ivaModelStillFits: ivaModelStillFits, ivaSpecsClean: ivaSpecsClean,
    ivaFieldsFor: ivaFieldsFor, ivaModelsLoad: ivaModelsLoad
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else for (var k in api) root[k] = api[k];
})(this);
