/* Справочник моделей (этап 2, 10.82; этап 4 — 10.84): общие кусочки формы сайта — список подсказок под полем и поля характеристик.
   Поиск и данные — assets/models.js. Используют new.html и cabinet.html (форма «Новый инструмент»). */
(function (root) {
  'use strict';

  function h(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  /* Подсказки под полем. search(текст) → модели, label(модель) → надпись, pick(модель) — выбор.
     Касание подсказки не уводит фокус с поля (mousedown отменён), выбор — по click. */
  function ivaSugAttach(input, box, search, label, pick, delay) {
    var timer = 0, items = [];
    function paint() {
      items = (document.activeElement === input && search(input.value)) || [];
      box.innerHTML = items.map(function (m, i) {
        return '<button type="button" data-msug="' + i + '">' + h(label(m)) + '</button>';
      }).join('');
      box.classList.toggle('on', items.length > 0);
    }
    function hide() { box.classList.remove('on'); }
    input.addEventListener('input', function () { clearTimeout(timer); timer = setTimeout(paint, delay == null ? 200 : delay); });
    input.addEventListener('focus', paint);
    input.addEventListener('blur', function () { setTimeout(hide, 150); });
    box.addEventListener('mousedown', function (e) { if (e.target.closest('[data-msug]')) e.preventDefault(); });
    box.addEventListener('click', function (e) {
      var b = e.target.closest('[data-msug]');
      if (!b) return;
      var m = items[+b.getAttribute('data-msug')];
      hide();
      if (m) pick(m);
    });
    return { paint: paint, hide: hide };
  }

  /* Поля характеристик: число — поле с цифровой клавиатурой, выбор — список, текст — поле. lab(текст) → подпись. */
  function ivaSpecsHTML(fields, values, lab) {
    values = values || {};
    return fields.map(function (f) {
      var v = values[f.key] == null ? '' : String(values[f.key]);
      var t = f.unit ? f.title + ', ' + f.unit : f.title;
      var inp;
      if (f.type === 'choice') {
        inp = '<select class="inp" data-spec="' + h(f.key) + '"><option value="">—</option>' + (f.options || []).map(function (o) {
          return '<option value="' + h(o) + '"' + (o === v ? ' selected' : '') + '>' + h(o) + '</option>';
        }).join('') + '</select>';
      } else {
        inp = '<input class="inp" data-spec="' + h(f.key) + '" autocomplete="off" maxlength="60"' +
          (f.type === 'num' ? ' inputmode="decimal"' : '') + ' placeholder="' + h(f.unit || '') + '" value="' + h(v) + '">';
      }
      return '<div class="mspec">' + lab(t) + inp + '</div>';
    }).join('');
  }

  /* Что сейчас в полях характеристик контейнера: {ключ: строка}. */
  function ivaSpecsRead(box) {
    var out = {};
    if (!box) return out;
    var els = box.querySelectorAll('[data-spec]');
    for (var i = 0; i < els.length; i++) out[els[i].getAttribute('data-spec')] = els[i].value;
    return out;
  }

  /* ── 10.84, этап 4 ── */

  /* Ошибка сервера при «Предложить модель» → понятный текст. */
  function ivaProposalErr(msg) {
    var m = String(msg || '');
    if (/already in directory/i.test(m)) return 'Такая модель уже есть в справочнике — выберите её в подсказках.';
    if (/duplicate key|23505|pending_uq/i.test(m)) return 'Эту модель уже предложили — она ждёт проверки.';
    if (/too many pending/i.test(m)) return 'Слишком много предложений ждут проверки — дождитесь решения по ним.';
    if (/row-level security|42501|permission denied/i.test(m)) return 'Предлагать модели могут только администраторы проката.';
    if (/iva_model_proposals|PGRST205|does not exist|schema cache/i.test(m)) return 'Предложения заработают после обновления базы.';
    return 'Не удалось отправить: ' + (m || 'нет связи') + '.';
  }

  /* Отправка предложения. Promise → '' (отправлено) или текст ошибки. */
  function ivaProposalSend(api, row) {
    return Promise.resolve().then(function () { return api('/rest/v1/iva_model_proposals', 'POST', row); })
      .then(function () { return ''; }, function (e) { return ivaProposalErr(e && e.message); });
  }

  /* Строка под полем «Модель»: parts — {brand, model} или null, state — '' | 'sending' | 'ok' | текст ошибки. */
  function ivaPropHTML(parts, state) {
    if (!parts) return '';
    var nm = h(parts.brand + ' ' + parts.model);
    if (state === 'ok') return '<div class="hint mprop">«' + nm + '» отправлена главному админу на проверку. Объявление можно разместить уже сейчас.</div>';
    if (state === 'sending') return '<div class="hint mprop">Отправляем «' + nm + '»…</div>';
    return '<div class="hint mprop">' + (state ? h(state) + ' ' : 'Нет в списке? ') +
      '<button type="button" data-mprop="1" style="color:var(--or);font-weight:600;background:none;border:0;padding:0;font:inherit;cursor:pointer">' +
      'Предложить «' + nm + '»</button>' + (state ? '' : ' — главный админ проверит и добавит в справочник.') + '</div>';
  }

  /* Комплектация: кнопки-переключатели data-kit. cls — класс кнопок страницы. */
  function ivaKitHTML(items, sel, cls) {
    sel = sel || [];
    return (items || []).map(function (it) {
      var on = sel.indexOf(it) >= 0;
      return '<button type="button" data-kit="' + h(it) + '" class="' + h((cls || '') + (on ? ' on' : '')).trim() + '" aria-pressed="' + on + '">' +
        (on ? '✓ ' : '') + h(it) + '</button>';
    }).join('');
  }

  /* Активные объявления вида для подсказки цены (с model_id; нет столбца — без него). Ошибка — []. */
  var priceMem = {};
  function ivaPriceRows(api, kind) {
    if (!kind) return Promise.resolve([]);
    if (priceMem[kind]) return priceMem[kind];
    var q = '/rest/v1/tools?active=eq.true&kind=eq.' + encodeURIComponent(kind) + '&limit=1000&select=';
    priceMem[kind] = Promise.resolve().then(function () { return api(q + 'price,deposit,model_id,kind'); })
      .catch(function () { return api(q + 'price,deposit,kind'); })
      .then(function (r) { return Array.isArray(r) ? r : []; }, function () { delete priceMem[kind]; return []; });
    return priceMem[kind];
  }

  var api = { ivaSugAttach: ivaSugAttach, ivaSpecsHTML: ivaSpecsHTML, ivaSpecsRead: ivaSpecsRead,
    ivaProposalErr: ivaProposalErr, ivaProposalSend: ivaProposalSend, ivaPropHTML: ivaPropHTML, ivaKitHTML: ivaKitHTML, ivaPriceRows: ivaPriceRows };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else for (var k in api) root[k] = api[k];
})(this);
