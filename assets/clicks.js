/* ===========================================================
   Ива — счётчик обращений со страниц городов (этап П3).

   Зачем: знать, сколько людей зовут нашу помощь со страниц городов и сколько
   смотрят эти страницы. (Звонки напрямую в пункты проката считаются тоже —
   на случай, если телефоны когда-нибудь вернут на страницы флагом --with-phones.) Это цифры для разговора с прокатами
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

  // Считаем клик по номеру прямо в момент нажатия: так работает и для блоков,
  // которые появились позже (пустые состояния, всплывающие окна, каталог).
  function init(cityName, opts) {
    opts = opts || {};
    var city = cityName || '';
    var page = location.pathname.split('/').pop() || 'city';

    document.addEventListener('click', function (e) {
      var el = e.target && e.target.closest ? e.target.closest('a[href^="tel:"]') : null;
      if (!el) return;
      var tel = (el.getAttribute('href') || '').replace(/[^0-9]/g, '');
      if (!tel) return;

      // Отправляем только те номера, которые напечатаны на этой странице:
      // чужой номер через подставную страницу не запишется.
      var printed = document.querySelectorAll('a[href^="tel:"]');
      var allowed = false;
      for (var i = 0; i < printed.length; i++) {
        if ((printed[i].getAttribute('href') || '').replace(/[^0-9]/g, '') === tel) { allowed = true; break; }
      }
      if (!allowed) return;

      send({
        phone: tel,
        city: city,
        page: page,
        kind: el.getAttribute('data-lead') === '1' ? 'lead' : 'call'
      });
    });

    // Показ страницы — чтобы видеть долю обращений от посетителей.
    // На каталоге и главной показы не считаем: там важны только обращения
    // (и чтобы цифры «показов» не смешивались со страницами городов).
    if (opts.views !== false) send({ phone: '', city: city, page: page, kind: 'view' });
  }

  window.IvaClicks = { init: init };
})();
