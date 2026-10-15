/* Справочник моделей (этап 2, 10.82): общие кусочки формы сайта — список подсказок под полем и поля характеристик.
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

  var api = { ivaSugAttach: ivaSugAttach, ivaSpecsHTML: ivaSpecsHTML, ivaSpecsRead: ivaSpecsRead };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else for (var k in api) root[k] = api[k];
})(this);
