/* ===========================================================
   Ива — счётчик обращений со страниц городов (этап П3).

   Зачем: знать, сколько звонков уходит в пункты проката из справочника и
   сколько людей зовут нашу помощь. Это цифры для разговора с прокатами
   («мы привели вам N обращений») — без них непонятно, работает ли страница.

   Принципы:
   • никаких персональных данных: не пишем ни IP, ни cookies, ни отпечаток
     браузера; сохраняем только «какой телефон, город, страница, тип, время»;
   • никогда не мешает посетителю: звонок уходит сразу, отправка статистики —
     фоном, ошибки глушим; если таблицы нет, пробуем один раз и замолкаем;
   • работает без сторонних сервисов (никакой Метрики и внешних счётчиков).
   =========================================================== */
(function () {
  var PAUSE_KEY = 'iva_clicks_paused_until';  // если счётчик недоступен — не дёргаем его 6 часов
  var PAUSE_MS = 6 * 60 * 60 * 1000;

  function isDisabled() {
    // Таблицы может не быть (владелец ещё не включил счётчик). Тогда молчим 6 часов,
    // а потом пробуем снова — чтобы после включения таблицы счётчик заработал сам.
    try { return Date.now() < Number(localStorage.getItem(PAUSE_KEY) || 0); } catch (e) { return false; }
  }

  function pause() {
    try { localStorage.setItem(PAUSE_KEY, String(Date.now() + PAUSE_MS)); } catch (e) {}
  }

  function send(payload) {
    if (isDisabled() || typeof fetch !== 'function') return;
    try {
      fetch(SB + '/rest/v1/directory_clicks', {
        method: 'POST',
        headers: { apikey: KEY, 'Content-Type': 'application/json', Prefer: 'return=minimal' },
        body: JSON.stringify(payload),
        keepalive: true
      }).then(function (r) {
        // Таблицы ещё нет (404) или прав нет (401/403) — замолкаем на 6 часов:
        // страница не сыплет запросами, но счётчик сам включится, когда таблица появится.
        if (r.status === 404 || r.status === 401 || r.status === 403) pause();
      }).catch(function () { /* сеть недоступна — молча */ });
    } catch (e) { /* молча */ }
  }

  function init(cityName) {
    var city = cityName || '';
    var page = location.pathname.split('/').pop() || 'city';
    var links = document.querySelectorAll('a[href^="tel:"]');

    // Отправляем только те номера, которые реально напечатаны на этой странице:
    // чужой номер через подставную страницу не запишется.
    var allowed = {};
    for (var k = 0; k < links.length; k++) {
      var digits = (links[k].getAttribute('href') || '').replace(/[^0-9]/g, '');
      if (digits) allowed[digits] = true;
    }

    for (var i = 0; i < links.length; i++) {
      (function (el) {
        el.addEventListener('click', function () {
          var tel = (el.getAttribute('href') || '').replace(/[^0-9]/g, '');
          if (!tel || !allowed[tel]) return;
          send({
            phone: tel,
            city: city,
            page: page,
            kind: el.getAttribute('data-lead') === '1' ? 'lead' : 'call'
          });
        });
      })(links[i]);
    }

    // Показ страницы — чтобы видеть долю обращений от посетителей.
    send({ phone: '', city: city, page: page, kind: 'view' });
  }

  window.IvaClicks = { init: init };
})();
