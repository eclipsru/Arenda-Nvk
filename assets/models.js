/* Справочник моделей (этап 2, 10.82; характеристики в карточке — этап 3, 10.83; предложения, цена, описание, комплектация — этап 4, 10.84): подсказки моделей в форме добавления и характеристики.
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
   Пустой запрос (поле «Модель» с известным видом) — по названию. По умолчанию 8 подсказок.
   10.86 (просьба владельца: «ушм» подсказывал, а «болгарка» — нет): модель подходит и без сильного совпадения, если
   хоть одно слово запроса (не цифры, от 3 букв, не «для/под/…») совпало со словом названия или вида — запрос
   «болгарка», «болг», «перфоратор» показывает модели этого вида. Такие модели идут после моделей с сильным
   совпадением («ушм» — сначала Интерскол УШМ-125, затем остальные болгарки), между собой — по очкам, затем
   больше слов совпало со словом названия («дрель» — сначала дрели, потом шуруповёрты), затем вид, где моделей
   больше, затем по названию.
   Ничего не нашлось или не совпали все слова — ещё раз с другой раскладкой (",jkufhrf" → «болгарка», «ьфлшеф» →
   makita), латиницей по-русски («bolgarka»), затем с исправлением опечаток (берётся первый вариант, где совпали все
   слова, иначе первый непустой): слово от 4 букв без цифр, которого нет в справочнике, заменяется ближайшим словом справочника
   (до 1 ошибки, от 8 букв — до 2; перестановка соседних букв — одна ошибка; недописанное сравнивается с началом
   слова): «балгарка» → «болгарка», «макито» → «макита». */
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
    var perKind = {};   /* 10.86: сколько моделей у вида — модели по слову вида: сначала вид, где моделей больше */
    for (i = 0; i < out.length; i++) perKind[out[i].m.kind] = (perKind[out[i].m.kind] || 0) + 1;
    for (i = 0; i < out.length; i++) out[i].kn = perKind[out[i].m.kind];
    out.brandToks = allB;
    out.stemToks = allS;
    /* 10.86: словарь для исправления опечаток — слова без цифр от 4 букв, по кодам символов (как Java compareTo) */
    var voc = [], words = [], seen = {};
    function addV(t) {
      if (/[0-9]/.test(t) || seen[t]) return;
      seen[t] = 1;
      words.push(t);
      if (t.length >= 4) voc.push(t);
    }
    for (i = 0; i < out.length; i++) {
      for (j = 0; j < out[i].strong.length; j++) addV(out[i].strong[j]);
      for (j = 0; j < out[i].weak.length; j++) addV(out[i].weak[j]);
    }
    for (j = 0; j < allS.length; j++) addV(allS[j]);
    for (j = 0; j < allB.length; j++) addV(allB[j]);
    var byCode = function (x, y) { return x < y ? -1 : x > y ? 1 : 0; };
    out.vocab = voc.sort(byCode);
    out.words = words.sort(byCode);   /* все слова без цифр — «слово есть в справочнике» */
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

  /* 10.86: слова-связки — не слово вида */
  var STOP = ['для', 'под', 'над', 'без', 'при', 'или'];

  /* Очки модели или null; e.miss — сколько слов запроса не совпало, e.strongHit — было ли сильное совпадение. */
  function score(e, qi) {
    var total = 0, hasStrong = false, kindHit = false;
    e.miss = 0;
    e.strongHit = false;
    e.th = 0;
    for (var i = 0; i < qi.length; i++) {
      var w = qi[i].w, best = 0, j;
      for (j = 0; j < e.strong.length; j++) {
        var s = e.strong[j];
        if (s === w) { best = 2 * w.length + 1; break; }
        if (w.length >= 2 && s.lastIndexOf(w, 0) === 0 && 2 * w.length > best) best = 2 * w.length;
      }
      if (best) { hasStrong = true; total += best; continue; }
      var weak = false, kh = false;
      for (j = 0; j < e.weak.length && !weak; j++) if (e.weak[j].lastIndexOf(w, 0) === 0) weak = true;
      if (weak) { kh = true; e.th++; }
      for (j = 0; j < e.stems.length && !weak; j++) if (w.lastIndexOf(e.stems[j], 0) === 0) weak = true;
      /* 10.86: слово вида — начало слова названия, начало слова вида или слово вида с другим окончанием (до 2 букв) */
      if (!qi[i].digit && w.length >= 3 && STOP.indexOf(w) < 0) {
        for (j = 0; j < e.stems.length && !kh; j++) {
          var x = e.stems[j];
          if (x.lastIndexOf(w, 0) === 0 || (w.lastIndexOf(x, 0) === 0 && w.length - x.length <= 2)) kh = true;
        }
      } else kh = false;
      if (kh) { weak = true; kindHit = true; }
      if (weak) { total += w.length; continue; }
      if (qi[i].digit || qi[i].brand) return null;
      e.miss++;
      total -= qi[i].stem ? 2 * w.length : w.length;
    }
    e.strongHit = hasStrong;
    return (hasStrong || kindHit) && total > 0 ? total : null;
  }

  /* 10.86: другая раскладка — латиница ↔ кириллица по клавишам (строчные). */
  var LAY_EN = "`qwertyuiop[]asdfghjkl;'zxcvbnm,.", LAY_RU = 'ёйцукенгшщзхъфывапролджэячсмитьбю';
  function ivaSwapLayout(s) {
    var t = String(s == null ? '' : s).toLowerCase(), out = '';
    for (var i = 0; i < t.length; i++) {
      var c = t.charAt(i), k = LAY_EN.indexOf(c);
      if (k >= 0) out += LAY_RU.charAt(k);
      else if ((k = LAY_RU.indexOf(c)) >= 0) out += LAY_EN.charAt(k);
      else out += c;
    }
    return out;
  }

  /* 10.86: число ошибок между словами (вставка, удаление, замена, перестановка соседних букв). */
  function ivaTypoDist(a, b) {
    var n = a.length, m = b.length, d = [], i, j;
    for (i = 0; i <= n; i++) { d.push([]); d[i][0] = i; }
    for (j = 0; j <= m; j++) d[0][j] = j;
    for (i = 1; i <= n; i++) {
      for (j = 1; j <= m; j++) {
        var c = a.charAt(i - 1) === b.charAt(j - 1) ? 0 : 1;
        var v = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + c);
        if (i > 1 && j > 1 && a.charAt(i - 1) === b.charAt(j - 2) && a.charAt(i - 2) === b.charAt(j - 1)) v = Math.min(v, d[i - 2][j - 2] + 1);
        d[i][j] = v;
      }
    }
    return d[n][m];
  }

  /* 10.86: запрос с исправленными опечатками или null (исправлять нечего). Слово от 4 букв без цифр, которое не
     начало слова справочника и не начинается с основы вида, заменяется ближайшим словом словаря (до 1 ошибки, от 8
     букв — до 2; недописанное — по началу слова); равные — первое по порядку словаря. */
  function known(w, index) {
    var voc = index.words || [], j;
    for (j = 0; j < voc.length; j++) if (voc[j].lastIndexOf(w, 0) === 0) return true;
    for (j = 0; j < index.stemToks.length; j++) if (w.lastIndexOf(index.stemToks[j], 0) === 0) return true;
    for (j = 0; j < index.brandToks.length; j++) if (index.brandToks[j].lastIndexOf(w, 0) === 0) return true;
    return false;
  }

  function ivaFuzzyQuery(query, index) {
    var q = tokens(query), voc = (index && index.vocab) || [], changed = false, i, j;
    for (i = 0; index && i < q.length; i++) {
      var w = q[i];
      if (w.length < 4 || /[0-9]/.test(w) || known(w, index)) continue;
      var lim = w.length >= 8 ? 2 : 1, best = null, bd = lim + 1;
      for (j = 0; j < voc.length; j++) {
        var t = voc[j];
        if (t.length < w.length - lim) continue;
        var d = ivaTypoDist(w, t);
        if (t.length > w.length) d = Math.min(d, ivaTypoDist(w, t.slice(0, w.length)));
        if (d < bd) { bd = d; best = t; }
      }
      if (best !== null) { q[i] = best; changed = true; }
    }
    return changed ? q.join(' ') : null;
  }

  /* 10.86: латиница → кириллица («bolgarka» → «болгарка», «moyka» → «мойка») для слов только из латинских букв,
     которых нет в справочнике (bosch, makita, gws не трогаем). null — менять нечего. */
  var TR2 = [['shch', 'щ'], ['sch', 'щ'], ['zh', 'ж'], ['kh', 'х'], ['ts', 'ц'], ['ch', 'ч'], ['sh', 'ш'], ['yu', 'ю'], ['ya', 'я'], ['yo', 'е'], ['ye', 'е']];
  var TR1_EN = 'abcdefghijklmnopqrstuvwxz', TR1_RU = 'абкдефгхийклмнопкрстуввкз';
  function ivaTranslit(query, index) {
    var q = tokens(query), changed = false;
    for (var i = 0; index && i < q.length; i++) {
      var w = q[i];
      if (!/^[a-z]+$/.test(w) || known(w, index)) continue;
      var out = '', p = 0;
      while (p < w.length) {
        var hit = null;
        for (var t = 0; t < TR2.length && !hit; t++) if (w.lastIndexOf(TR2[t][0], p) === p) hit = TR2[t];
        if (hit) { out += hit[1]; p += hit[0].length; continue; }
        var c = w.charAt(p);
        if (c === 'y') out += p > 0 && 'aeiouy'.indexOf(w.charAt(p - 1)) >= 0 ? 'й' : 'ы';
        else if (c === 'x') out += 'кс';
        else out += TR1_RU.charAt(TR1_EN.indexOf(c));
        p++;
      }
      if (out !== w) { q[i] = out; changed = true; }
    }
    return changed ? q.join(' ') : null;
  }

  /* opts: {kind, brand, limit}. Пустой запрос с видом — модели вида (и бренда) по названию. Ничего не нашлось —
     другая раскладка, затем исправление опечаток (10.86). */
  function ivaModelSearch(query, index, opts) {
    var first = searchOnce(query, index, opts);
    if (first.full || !index || !tokens(query).length) return first.list;
    var sw = ivaSwapLayout(query), alts = [sw !== String(query).toLowerCase() ? sw : null, ivaTranslit(query, index), ivaFuzzyQuery(query, index)];
    var any = first.list.length ? first.list : null;
    for (var i = 0; i < alts.length; i++) {
      if (alts[i] === null) continue;
      var r = searchOnce(alts[i], index, opts);
      if (r.full) return r.list;
      if (!any && r.list.length) any = r.list;
    }
    return any || [];
  }

  function searchOnce(query, index, opts) {
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
      var miss = q.length ? e.miss : 0, st = q.length ? e.strongHit : false;
      if (!miss) full = true;
      res.push({ e: e, s: sc, miss: miss, st: st, u: st ? uncovered(e, qi) : 0, c: st ? e.code : 0, kn: st || !q.length ? 0 : e.kn, th: st || !q.length ? 0 : e.th });
    }
    if (full) res = res.filter(function (r) { return !r.miss; });
    res.sort(function (a, b) {
      if (a.st !== b.st) return a.st ? -1 : 1;
      if (a.s !== b.s) return b.s - a.s;
      if (a.th !== b.th) return b.th - a.th;
      if (a.kn !== b.kn) return b.kn - a.kn;
      if (a.u !== b.u) return a.u - b.u;
      if (a.c !== b.c) return a.c - b.c;
      return a.e.key < b.e.key ? -1 : a.e.key > b.e.key ? 1 : 0;
    });
    var out = [];
    for (var r = 0; r < res.length && out.length < limit; r++) out.push(res[r].e.m);
    return { list: out, full: full };
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

  /* 10.86 (решение владельца 15.10): какие поля характеристик показывать в форме добавления. fields — поля вида
     (ivaFieldsFor), modelSpecs — характеристики выбранной модели (нет модели — пусто), values — что сейчас в форме.
     «Питание» — только если у выбранной модели оно указано, и одним вариантом — её; без модели или без данных —
     поля нет. «Напряжение аккумулятора» — только когда выбрано питание «Аккумулятор» (у вида без «Питания», например
     пистолета для вязки арматуры, — всегда). Остальные поля — как есть. Как IvaModelPatch.formFields. */
  var SRC = 'src', VOLT = 'volt', BATTERY = 'Аккумулятор';
  function ivaFormFields(fields, modelSpecs, values) {
    var out = [], src = str((modelSpecs || {})[SRC]).trim(), hasSrc = false, i, f;
    for (i = 0; fields && i < fields.length; i++) if (fields[i].key === SRC) hasSrc = true;
    var srcOn = hasSrc && src !== '', cur = srcOn && str((values || {})[SRC]).trim() === src ? src : '';
    for (i = 0; fields && i < fields.length; i++) {
      f = fields[i];
      if (f.key === SRC) {
        if (!srcOn) continue;
        var c = {};
        for (var k in f) if (Object.prototype.hasOwnProperty.call(f, k)) c[k] = f[k];
        c.options = [src];
        out.push(c);
      } else if (f.key === VOLT) {
        if (!hasSrc || cur === BATTERY) out.push(f);
      } else out.push(f);
    }
    return out;
  }

  /* 10.83: строки карточки [подпись, значение] по полям вида в их порядке: число — с запятой, единица — через
     неразрывный пробел; пустое и чужие ключи не показываем (как IvaModelPatch.specRows). */
  function ivaSpecRows(specs, fields) {
    var out = [];
    if (!specs || !fields) return out;
    for (var i = 0; i < fields.length; i++) {
      var f = fields[i], v = str(specs[f.key]).trim();
      if (v === '') continue;
      var t = f.type === 'num' && /^\d+(\.\d+)?$/.test(v) ? v.replace('.', ',') : v;
      if (f.unit) t += '\u00a0' + f.unit;
      out.push([f.title, t]);
    }
    return out;
  }

  /* 10.83: характеристики объявления подходят под выбранные (нет значения — не подходит), как IvaModelPatch.specsMatch. */
  function ivaSpecsMatch(tool, sel) {
    for (var k in sel || {}) if (Object.prototype.hasOwnProperty.call(sel, k) && str(sel[k]) !== str((tool || {})[k]).trim()) return false;
    return true;
  }

  /* 10.83: «Вид» и характеристики объявления для карточки (tool.html). Нет столбца specs — только вид; нет вида или
     ошибка — null (карточка как раньше). */
  function ivaToolSpecs(api, id) {
    if (!id) return Promise.resolve(null);
    var q = '/rest/v1/tools?id=eq.' + encodeURIComponent(id) + '&select=';
    var tool = null;
    return new Promise(function (r) { r(api(q + 'kind,specs,kit')); })
      .catch(function () { return api(q + 'kind,specs'); })
      .catch(function () { return api(q + 'kind'); })
      .then(function (rows) {
        tool = Array.isArray(rows) && rows[0] || null;
        if (!tool || !tool.kind) return null;
        var specs = tool.specs && typeof tool.specs === 'object' && !Array.isArray(tool.specs) ? tool.specs : {};
        var k = encodeURIComponent(tool.kind);
        return Promise.all([
          Promise.resolve(api('/rest/v1/iva_kinds?select=title&key=eq.' + k)).catch(function () { return []; }),
          Object.keys(specs).length ? Promise.resolve(api('/rest/v1/iva_spec_fields?select=kind,key,title,unit,type,options,sort&kind=eq.' + k))
            .catch(function () { return []; }) : []
        ]).then(function (res) {
          var rows = [], kt = Array.isArray(res[0]) && res[0][0] && res[0][0].title;
          if (kt) rows.push(['Вид', kt]);
          var fl = (Array.isArray(res[1]) ? res[1] : []).filter(function (x) { return x && x.key; })
            .map(function (x) { return { kind: tool.kind, key: x.key, title: x.title || x.key, unit: x.unit || '', type: x.type, sort: x.sort || 0 }; });
          rows = rows.concat(ivaSpecRows(specs, ivaFieldsFor({ fields: fl }, tool.kind)));
          var kit = Array.isArray(tool.kit) ? tool.kit.filter(function (v) { return typeof v === 'string' && v.trim(); }) : [];
          if (kit.length) rows.push(['Комплектация', kit.join(', ')]);
          return { kind: tool.kind, rows: rows };
        });
      })
      .catch(function () { return null; });
  }

  /* ── 10.84, этап 4 (те же правила — IvaModelPatch.proposalParts / priceHint / describe / kitClean) ── */

  function low(s) { return str(s).toLowerCase(); }
  function squash(s) { return str(s).replace(/\s+/g, ' ').trim(); }

  /* «Предложить модель»: из текста поля «Модель» — бренд и модель. brandSel — выбранный бренд (чип) или ''.
     Бренд из справочника (имя или другое написание, самое длинное совпадение в начале) — как в справочнике;
     иначе первое слово. Нет модели после бренда или слишком длинно (бренд > 40, модель > 60) — null. */
  function ivaProposalParts(text, brandSel, brands) {
    var t = squash(text), lt = low(t), best = null, bl = 0, i, j;
    if (!t) return null;
    for (i = 0; brands && i < brands.length; i++) {
      var b = brands[i];
      if (!b || !b.name) continue;
      var cands = [b.name].concat(b.aliases || []);
      for (j = 0; j < cands.length; j++) {
        var c = low(squash(cands[j]));
        if (c && c.length > bl && (lt === c || lt.indexOf(c + ' ') === 0)) { best = b.name; bl = c.length; }
      }
    }
    var brand, model;
    var sel = squash(brandSel);
    if (sel) {
      brand = sel;
      model = best && low(best) === low(sel) ? t.slice(bl) : t;
    } else if (best) {
      brand = best; model = t.slice(bl);
    } else {
      var sp = t.indexOf(' ');
      if (sp < 0) return null;
      brand = t.slice(0, sp); model = t.slice(sp + 1);
    }
    model = squash(model);
    if (!brand || !model || brand.length > 40 || model.length > 60) return null;
    return { brand: brand, model: model };
  }

  /* Название предложенной модели: название объявления, если в нём есть код модели (слитно), иначе «Вид Бренд Модель». */
  function ivaProposalTitle(name, parts, kindTitle) {
    if (!parts) return '';
    var code = tokens(parts.model).join(''), n = squash(name);
    if (n && code && tokens(n).join('').indexOf(code) >= 0) return n.slice(0, 120);
    return squash((kindTitle ? kindTitle + ' ' : '') + parts.brand + ' ' + parts.model).slice(0, 120);
  }

  /* Модель уже есть в справочнике у этого вида: текст слитно = модель слитно или бренд + модель слитно. */
  function ivaModelExact(index, kind, text) {
    var tj = tokens(text).join('');
    if (!tj || !index) return null;
    for (var i = 0; i < index.length; i++) {
      var e = index[i], c = e.codeToks.join('');
      if (e.m.kind === kind && c && (c === tj || tokens(e.m.brand).join('') + c === tj)) return e.m;
    }
    return null;
  }

  function round50(v) { return v >= 100 ? Math.round(v / 50) * 50 : Math.round(v); }
  function numsOf(rows, key) {
    var out = [];
    for (var i = 0; i < rows.length; i++) {
      var v = Number(rows[i][key]);
      if (isFinite(v) && v > 0) out.push(v);
    }
    return out.sort(function (a, b) { return a - b; });
  }
  function spread(a) {
    var n = a.length;
    if (!n) return null;
    var lo = n >= 5 ? a[Math.floor((n - 1) / 4)] : a[0], hi = n >= 5 ? a[Math.ceil(3 * (n - 1) / 4)] : a[n - 1];
    var mid = n % 2 ? a[(n - 1) / 2] : (a[n / 2 - 1] + a[n / 2]) / 2;
    return [round50(lo), round50(hi), round50(mid)];
  }

  /* Подсказка цены и залога по активным объявлениям rows [{price, deposit, model_id, kind}]: той же модели, если
     их от 2, иначе того же вида, если от 3. Цены от 5 объявлений — от четверти до трёх четвертей (без крайних),
     меньше — от самой низкой до самой высокой; округление до 50 ₽. Залог — если указан хотя бы у двух.
     Возвращает {n, basis: 'model'|'kind', price: [от, до, середина], deposit: [от, до, середина]|null} или null. */
  function ivaPriceHint(rows, modelId, kind) {
    var pick = [], basis = '', i;
    rows = rows || [];
    if (modelId != null && modelId !== '') {
      for (i = 0; i < rows.length; i++) if (rows[i] && Number(rows[i].price) > 0 && str(rows[i].model_id) === str(modelId)) pick.push(rows[i]);
      if (pick.length >= 2) basis = 'model';
    }
    if (!basis && kind) {
      pick = [];
      for (i = 0; i < rows.length; i++) if (rows[i] && Number(rows[i].price) > 0 && rows[i].kind === kind) pick.push(rows[i]);
      if (pick.length >= 3) basis = 'kind';
    }
    if (!basis) return null;
    var d = numsOf(pick, 'deposit');
    return { n: pick.length, basis: basis, price: spread(numsOf(pick, 'price')), deposit: d.length >= 2 ? spread(d) : null };
  }

  function rubN(v) { return String(v).replace(/\B(?=(\d{3})+(?!\d))/g, '\u00a0') + '\u00a0₽'; }
  function rangeT(r) { return r[0] === r[1] ? rubN(r[0]) : String(r[0]).replace(/\B(?=(\d{3})+(?!\d))/g, '\u00a0') + '–' + rubN(r[1]); }

  /* Текст подсказки: «Обычно сдают за 700–1 000 ₽ в сутки, залог 3 000–5 000 ₽ (12 объявлений этой модели)». */
  function ivaPriceHintText(hint) {
    if (!hint || !hint.price) return '';
    var n = hint.n, w = n % 10 === 1 && n % 100 !== 11 ? 'объявление' : n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 12 || n % 100 > 14) ? 'объявления' : 'объявлений';
    return 'Обычно сдают за ' + rangeT(hint.price) + ' в сутки' + (hint.deposit ? ', залог ' + rangeT(hint.deposit) : '') +
      ' (' + n + ' ' + w + (hint.basis === 'model' ? ' этой модели' : ' этого вида') + ')';
  }

  /* «Сгенерировать описание»: название, характеристики строками («— Мощность: 780 Вт»), комплектация одной строкой.
     rows — как ivaSpecRows, kit — отмеченные пункты. Нет названия — ''. */
  function ivaDescribe(name, rows, kit) {
    var n = squash(name).replace(/[.!\s]+$/, '');
    if (!n) return '';
    var out = n + ' в аренду.';
    if (rows && rows.length) {
      out += '\n\nХарактеристики:';
      for (var i = 0; i < rows.length; i++) out += '\n— ' + rows[i][0] + ': ' + rows[i][1];
    }
    if (kit && kit.length) {
      var ks = [];
      for (var j = 0; j < kit.length; j++) {
        var k = squash(kit[j]);
        if (!k) continue;
        ks.push(k.length > 1 && k.charAt(1) === k.charAt(1).toLowerCase() && k.charAt(1) !== k.charAt(1).toUpperCase()
          ? k.charAt(0).toLowerCase() + k.slice(1) : k);
      }
      if (ks.length) out += '\n\nВ комплекте: ' + ks.join(', ') + '.';
    }
    return out;
  }

  /* Комплектация к сохранению: только пункты вида, в порядке вида, без повторов; пусто — null. */
  function ivaKitClean(sel, items) {
    var out = [];
    sel = sel || [];
    for (var i = 0; items && i < items.length; i++) if (sel.indexOf(items[i]) >= 0 && out.indexOf(items[i]) < 0) out.push(items[i]);
    return out.length ? out : null;
  }

  var MD_KEY = 'iva_models_v2', MD_TTL = 30 * 60 * 1000, MD_PAGE = 1000;
  var mdMem = null;

  /* Справочник с сервера: бренды, поля, модели страницами по 1000 (больше PostgREST не отдаёт). Копия — на 30 минут
     (в памяти и в localStorage). Не загрузился (SQL не применён, нет сети) — null: формы работают как раньше.
     api(path) — функция страницы (sb.js / cabinet.html), kinds — [{key, words}] для основ видов. */
  function ivaModelsLoad(api, kinds) {
    function build(raw) {
      var byId = {};
      for (var i = 0; i < raw.models.length; i++) byId[raw.models[i].id] = raw.models[i];
      return { at: raw.at, models: raw.models, fields: raw.fields, brands: raw.brands, kit: raw.kit || {}, byId: byId, index: ivaModelIndex(raw.models, raw.brands, kinds || []) };
    }
    var now = Date.now();
    if (mdMem && now - mdMem.at < MD_TTL) return Promise.resolve(build(mdMem));
    try {
      var c = typeof localStorage !== 'undefined' && JSON.parse(localStorage.getItem(MD_KEY) || 'null');
      if (c && c.at && now - c.at < MD_TTL && c.models && c.models.length) { mdMem = c; return Promise.resolve(build(c)); }
    } catch (e) {}
    var raw = { at: now, models: [], brands: [], fields: [], kit: {} };
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
        /* 10.84: комплектация по видам; нет таблицы (SQL этапа 4 не применён) — пусто, остальное работает */
        return Promise.resolve(api('/rest/v1/iva_kit_items?select=kind,items')).catch(function () { return []; });
      })
      .then(function (kr) {
        (Array.isArray(kr) ? kr : []).forEach(function (x) {
          if (x && x.kind && Array.isArray(x.items) && x.items.length) raw.kit[x.kind] = x.items.filter(function (v) { return typeof v === 'string' && v; });
        });
      })
      .then(function () {
        if (!raw.models.length || !raw.fields.length) return null;
        mdMem = raw;
        try { if (typeof localStorage !== 'undefined') localStorage.setItem(MD_KEY, JSON.stringify(raw)); } catch (e) {}
        return build(raw);
      })
      .catch(function () { return null; });
  }

  /* ═══ 10.85: фильтр по характеристикам — числа «от–до», объявления без нужной характеристики — в конце списка ═══
     Те же правила, что IvaModelPatch (num, specState, rangeChip, rangeHint, missingNote); общие случаи —
     tests/spec-filter-cases.txt (их проверяют IvaPatchTest и tests/spec-filter.cjs). */
  var NB = '\u00a0';

  /* Число характеристики или поля «от/до»: «800», «2,4», «1 500» → число; иначе NaN. */
  function ivaNum(v) {
    var t = str(v).replace(/[\s\u00a0]+/g, '');
    return /^\d{1,9}([.,]\d{1,3})?$/.test(t) ? parseFloat(t.replace(',', '.')) : NaN;
  }

  function ivaNumFields(fields) { return (fields || []).filter(function (f) { return f.type === 'num'; }); }
  function ivaChoiceFields(fields) { return (fields || []).filter(function (f) { return f.type === 'choice' && f.options && f.options.length; }); }

  function normRange(r) {
    var lo = r && r.length > 0 && typeof r[0] === 'number' ? r[0] : NaN, hi = r && r.length > 1 && typeof r[1] === 'number' ? r[1] : NaN;
    if (!isNaN(lo) && !isNaN(hi) && lo > hi) { var t = lo; lo = hi; hi = t; }
    return [lo, hi];
  }

  /* 1 — подходит; 0 — нужной характеристики нет (в конце списка); -1 — не подходит. ranges: {ключ: [от, до]}, NaN — без границы. */
  function ivaSpecState(tool, sel, ranges) {
    var missing = false, k;
    tool = tool || {};
    for (k in sel || {}) if (Object.prototype.hasOwnProperty.call(sel, k)) {
      var v = str(tool[k]).trim();
      if (v === '') missing = true;
      else if (str(sel[k]) !== v) return -1;
    }
    for (k in ranges || {}) if (Object.prototype.hasOwnProperty.call(ranges, k)) {
      var r = normRange(ranges[k]);
      if (isNaN(r[0]) && isNaN(r[1])) continue;
      var n = ivaNum(tool[k]);
      if (isNaN(n)) missing = true;
      else if ((!isNaN(r[0]) && n < r[0]) || (!isNaN(r[1]) && n > r[1])) return -1;
    }
    return missing ? 0 : 1;
  }

  /* «1 500», «2,4», «2,25» (не больше двух знаков после запятой). */
  function ivaNumText(v) {
    if (typeof v !== 'number' || !isFinite(v)) return '';
    var r = Math.round(v * 100) / 100;
    if (r === Math.round(r)) return String(r).replace(/\B(?=(\d{3})+(?!\d))/g, NB);
    return String(r).replace('.', ',');
  }

  function ivaRangeText(r0, unit) {
    var r = normRange(r0), t;
    if (!isNaN(r[0]) && !isNaN(r[1])) t = r[0] === r[1] ? ivaNumText(r[0]) : ivaNumText(r[0]) + '–' + ivaNumText(r[1]);
    else if (!isNaN(r[0])) t = 'от ' + ivaNumText(r[0]);
    else if (!isNaN(r[1])) t = 'до ' + ivaNumText(r[1]);
    else return '';
    return unit ? t + NB + unit : t;
  }

  /* Надпись плашки: «Мощность: 600–1 500 Вт» (поля нет — только числа). fields — поля вида. */
  function ivaRangeChip(fields, key, r) {
    for (var i = 0; fields && i < fields.length; i++) if (fields[i].key === key) return fields[i].title + ': ' + ivaRangeText(r, fields[i].unit || '');
    return ivaRangeText(r, '');
  }

  /* Подпись выбранного значения: «Питание: Аккумулятор». */
  function ivaSpecChip(fields, key, value) {
    for (var i = 0; fields && i < fields.length; i++) if (fields[i].key === key) return fields[i].title + ': ' + value;
    return str(value);
  }

  function ivaRangeHint(min, max, n, unit) {
    if (!(n > 0) || isNaN(min) || isNaN(max)) return 'В подходящих объявлениях не указано.';
    var u = unit ? NB + unit : '';
    if (min === max) return 'Сейчас у всех, где указано, — ' + ivaNumText(min) + u + '.';
    return 'Сейчас в выдаче: от ' + ivaNumText(min) + ' до ' + ivaNumText(max) + u + '.';
  }

  function ivaMissingNote(n) {
    if (!(n > 0)) return '';
    var w = n % 10 === 1 && n % 100 !== 11 ? 'объявление' : n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 12 || n % 100 > 14) ? 'объявления' : 'объявлений';
    return 'Ещё ' + n + ' ' + w + ' без указанной характеристики — в конце списка.';
  }

  /* Каталог сайта: вид и характеристики активных объявлений (отдельным запросом — общий список полей TOOL_FIELDS не
     меняется), виды и подписи полей. Нет столбца specs — только виды; нет столбца kind или таблиц — null (как раньше). */
  function ivaCatalogSpecs(api) {
    var base = '/rest/v1/tools?status=eq.active&active=eq.true&limit=1000&select=';
    var kinds = [], fields = [];
    return new Promise(function (r) { r(api(base + 'id,kind,specs')); })
      .catch(function () { return api(base + 'id,kind'); })
      .then(function (rows) {
        if (!Array.isArray(rows)) return null;
        return Promise.all([
          Promise.resolve(api('/rest/v1/iva_kinds?active=eq.true&order=sort.asc,title.asc&select=key,cat,sub,title,sort')).catch(function () { return []; }),
          Promise.resolve(api('/rest/v1/iva_spec_fields?select=kind,key,title,unit,type,options,sort&order=kind.asc,sort.asc')).catch(function () { return []; })
        ]).then(function (res) {
          kinds = (Array.isArray(res[0]) ? res[0] : []).filter(function (k) { return k && k.key && k.cat && k.sub; })
            .map(function (k) { return { key: k.key, cat: k.cat, sub: k.sub, title: k.title || k.key, sort: k.sort || 0 }; });
          if (!kinds.length) return null;
          fields = (Array.isArray(res[1]) ? res[1] : []).filter(function (x) { return x && x.kind && x.key; })
            .map(function (x) { return { kind: x.kind, key: x.key, title: x.title || x.key, unit: x.unit || '', type: x.type || 'text',
              options: Array.isArray(x.options) ? x.options : [], sort: x.sort || 0 }; });
          var byId = {};
          rows.forEach(function (t) {
            if (!t || t.id == null || !t.kind) return;
            byId[t.id] = { kind: String(t.kind), specs: t.specs && typeof t.specs === 'object' && !Array.isArray(t.specs) ? t.specs : {} };
          });
          return { byId: byId, kinds: kinds, fields: fields };
        });
      })
      .catch(function () { return null; });
  }

  var api = {
    ivaModelIndex: ivaModelIndex, ivaModelSearch: ivaModelSearch, ivaModelBrands: ivaModelBrands,
    ivaSwapLayout: ivaSwapLayout, ivaTypoDist: ivaTypoDist, ivaFuzzyQuery: ivaFuzzyQuery, ivaFormFields: ivaFormFields,
    ivaSpecsFill: ivaSpecsFill, ivaModelStillFits: ivaModelStillFits, ivaSpecsClean: ivaSpecsClean,
    ivaFieldsFor: ivaFieldsFor, ivaModelsLoad: ivaModelsLoad,
    ivaSpecRows: ivaSpecRows, ivaSpecsMatch: ivaSpecsMatch, ivaToolSpecs: ivaToolSpecs,
    ivaProposalParts: ivaProposalParts, ivaPriceHint: ivaPriceHint, ivaPriceHintText: ivaPriceHintText,
    ivaDescribe: ivaDescribe, ivaKitClean: ivaKitClean, ivaProposalTitle: ivaProposalTitle, ivaModelExact: ivaModelExact,
    ivaNum: ivaNum, ivaNumFields: ivaNumFields, ivaChoiceFields: ivaChoiceFields, ivaSpecState: ivaSpecState, ivaNumText: ivaNumText,
    ivaRangeText: ivaRangeText, ivaRangeChip: ivaRangeChip, ivaSpecChip: ivaSpecChip, ivaRangeHint: ivaRangeHint,
    ivaMissingNote: ivaMissingNote, ivaCatalogSpecs: ivaCatalogSpecs
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else for (var k in api) root[k] = api[k];
})(this);
