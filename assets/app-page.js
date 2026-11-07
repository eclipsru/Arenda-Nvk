/* ===========================================================
   Ива — страница приложения app.html «как в Google Play» (просьба владельца 02.11.2026).
   Сюда ведёт постоянный QR-код (assets/qr-app-logo.png).

   - Версия, ссылка «Установить», «Что нового» — из выпущенной для всех версии (assets/app-release.js, таблица app_releases).
   - Размер файла — по заголовку Content-Length самого APK (запрос HEAD), дата — confirmed_at из app_releases.
   - Оценки и отзывы о приложении — SQL supabase/migrations/20261120_app_reviews.sql (функции iva_app_review*).
     Пока SQL не выполнен — страница честно пишет «Отзывы скоро появятся», ничего не выдумывает.
   - Экраны: пока иллюстрации из HTML (картинки категорий сайта). Настоящие снимки экрана — положить в
     assets/app-shots/ и перечислить в IVA_APP_SHOTS ниже: тогда покажутся они.
   Зависит от assets/app.js (mountChrome, openSheet, toast, esc, isCreator) и assets/sb.js (api, restoreSess, isAuthed).
   =========================================================== */
var IVA_APP_SHOTS = [
  // 'assets/app-shots/1.jpg', 'assets/app-shots/2.jpg', …  (вертикальные снимки экрана 9:16 или 9:19)
];

(function(){
  'use strict';

  var PAGE = 10;
  var REL = (typeof ivaReleasedNow === 'function') ? ivaReleasedNow() : { versionName: '', apk: '' };
  var APK_SIZE = '';
  var STAR = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 2.6l2.9 6 6.6.8-4.9 4.6 1.3 6.6L12 17.4l-5.9 3.2 1.3-6.6-4.9-4.6 6.6-.8z"/></svg>';
  var AVA = ['var(--or)', 'var(--grn)', 'var(--yel)', 'var(--red)'];

  function $id(id){ return document.getElementById(id); }
  function plural(n, a, b, c){ n = Math.abs(n) % 100; var m = n % 10; if (n > 10 && n < 20) return c; if (m > 1 && m < 5) return b; if (m === 1) return a; return c; }
  function num(v){ return String(v).replace('.', ','); }
  function fmtDate(iso){
    if (!iso) return '';
    var d = new Date(iso); if (isNaN(d)) return '';
    return d.toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' }).replace(/\s*г\.?$/, '');
  }
  function stars(n, cls){
    var h = '', r = Math.round(Number(n) || 0);
    for (var i = 1; i <= 5; i++) h += '<span class="' + (i <= r ? '' : 'off') + '" style="line-height:0">' + STAR + '</span>';
    return '<span class="st ' + (cls || '') + '" aria-label="' + r + ' из 5">' + h + '</span>';
  }
  function isIOS(){ return /iPhone|iPad|iPod/i.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1); }
  function rpc(fn, body){ return api('/rest/v1/rpc/' + fn, 'POST', body || {}); }
  function loginUrl(){ return 'account.html?next=' + encodeURIComponent('app.html#reviews'); }

  /* ---------- Экраны ---------- */
  function tile(k, t){ return '<div class="m-tile"><i>' + t + '</i><img src="assets/cat/' + k + '-cut.webp" alt="" loading="lazy"></div>'; }
  var SHOTS = [
    { t: 'Инструмент в аренду <em>рядом с домом</em>',
      ph: '<div class="m-bar">🔍 Перфоратор, болгарка…</div><div class="m-grid">' +
          tile('power', 'Электро&shy;инструмент') + tile('construction', 'Стройобору&shy;дование') +
          tile('garden', 'Садовый инструмент') + tile('generators', 'Генераторы') + '</div>' },
    { t: 'Заявка прокату <em>в пару касаний</em>',
      ph: '<div class="m-tile" style="height:70px"><i>Перфоратор</i><img src="assets/cat/power-cut.webp" alt="" loading="lazy" style="height:60px"></div>' +
          '<div class="m-card"><b>Срок аренды</b>3 суток · с завтра</div>' +
          '<div class="m-card"><b>Получение</b>самовывоз из пункта выдачи</div>' +
          '<div class="m-btn">Отправить заявку</div>' },
    { t: 'Чат <em>с прокатом</em>',
      ph: '<div class="m-card" style="display:flex;gap:5px;align-items:center"><img src="assets/icon-192.png" alt="" style="width:16px;height:16px;border-radius:4px"><b style="display:inline">Прокат · пункт выдачи</b></div>' +
          '<div class="m-msg out">Здравствуйте! Бетономешалка свободна на выходные?</div>' +
          '<div class="m-msg in">Да, свободна. Приезжайте в субботу после 9:00</div>' +
          '<div class="m-msg out">Отлично, оформляю заявку</div>' +
          '<div class="m-bar" style="margin-top:auto;margin-bottom:8px">Сообщение…</div>' },
    { t: 'Уведомления <em>о заявках</em>',
      ph: '<div class="m-note"><img src="assets/icon-192.png" alt=""><div><b>Заявка принята</b>Прокат подтвердил аренду</div></div>' +
          '<div class="m-note"><img src="assets/icon-192.png" alt=""><div><b>Новое сообщение</b>Прокат ответил в чате</div></div>' +
          '<div class="m-note"><img src="assets/icon-192.png" alt=""><div><b>Срок подходит к концу</b>Верните инструмент или продлите</div></div>' },
    { t: 'Для прокатов: <em>заявки и сроки</em>',
      ph: '<div class="m-card m-tint"><b>Новая заявка</b>Шуруповёрт · 2 суток</div>' +
          '<div class="m-card m-tint y"><b>Выдана</b>осталось 1 д 4 ч · «чек» по инструменту</div>' +
          '<div class="m-card"><b>Пункты выдачи и сотрудники</b>права, одобрение, архив заявок</div>' +
          '<div class="m-btn" style="background:transparent;color:var(--or);border:1px solid var(--or)">Добавить инструмент</div>' }
  ];
  function renderShots(){
    var box = $id('apShots'); if (!box) return;
    if (IVA_APP_SHOTS.length){
      box.innerHTML = IVA_APP_SHOTS.map(function(src, i){
        return '<div class="shot"><img class="real" src="' + esc(src) + '" alt="Экран приложения ' + (i + 1) + '" loading="lazy"></div>';
      }).join('');
      var cap = $id('apShotsCap'); if (cap) cap.textContent = 'Экраны приложения';
      return;
    }
    box.innerHTML = SHOTS.map(function(s){
      return '<div class="shot"><div class="shot-t">' + s.t + '</div><div class="phone"><div class="pb"></div>' + s.ph + '</div></div>';
    }).join('');
  }

  /* ---------- Версия, размер, дата ---------- */
  function paintRelease(rel){
    REL = rel || REL;
    var wn = $id('whatsNew'); if (wn) wn.textContent = (REL.notes && REL.notes.trim()) ? REL.notes.trim() : 'Исправления и улучшения.';
    var an = $id('apkName'); if (an) an.textContent = (typeof ivaApkFile === 'function' && ivaApkFile(REL.apk)) || REL.apk;
    if (!REL.apk) return;
    fetch(REL.apk, { method: 'HEAD', cache: 'no-store' }).then(function(r){
      var n = Number(r.ok && r.headers.get('content-length')) || 0;
      if (n < 100000) return;
      APK_SIZE = num((n / 1048576).toFixed(1)) + ' МБ';
      var s = $id('stSize'); if (s) s.textContent = APK_SIZE;
      var sl = $id('stSizeL'); if (sl) sl.textContent = 'размер';
      var inf = $id('infSize'); if (inf) inf.textContent = APK_SIZE;
      var as = $id('apkSize'); if (as) as.textContent = ' (' + APK_SIZE + ')';
    }).catch(function(){});
  }
  function loadReleaseDate(){
    if (typeof IVA_RELEASE_SB === 'undefined') return;
    fetch(IVA_RELEASE_SB + '/rest/v1/app_releases?select=version_name,confirmed_at&order=version_code.desc&limit=1',
          { headers: { apikey: IVA_RELEASE_KEY }, cache: 'no-store' })
      .then(function(r){ return r.ok ? r.json() : []; })
      .then(function(rows){
        var row = rows && rows[0];
        ivaReleased().then(function(rel){
          if (!row || String(row.version_name) !== String(rel.versionName) || !row.confirmed_at) return;
          var d = fmtDate(row.confirmed_at);
          var a = $id('infDate'); if (a) a.textContent = d;
          var b = $id('wnDate'); if (b) b.textContent = ' · ' + d;
        });
      }).catch(function(){});
  }

  /* ---------- Верх страницы: кнопки ---------- */
  function bindTop(){
    document.addEventListener('click', function(e){
      var go = e.target.closest && e.target.closest('[data-go]');
      if (go){
        var t = $id(go.getAttribute('data-go'));
        if (t){ e.preventDefault(); t.scrollIntoView({ behavior: 'smooth', block: 'start' }); }
        return;
      }
      var dl = e.target.closest && e.target.closest('[data-app-dl]');
      if (dl) hitDownload();
      if (dl && !$id('sheet')) setTimeout(installSheet, 500);
    });
    var sh = $id('apShare');
    if (sh) sh.addEventListener('click', function(){
      var url = 'https://eclipsru.github.io/Arenda-Nvk/app.html';
      var data = { title: 'Ива — аренда инструмента', text: 'Приложение «Ива» для Android — аренда инструмента рядом с домом', url: url };
      if (navigator.share) { navigator.share(data).catch(function(){}); return; }
      if (navigator.clipboard) navigator.clipboard.writeText(url).then(function(){ toast('Ссылка скопирована'); }, function(){ prompt('Скопируйте ссылку:', url); });
      else prompt('Скопируйте ссылку:', url);
    });
    var ab = $id('aboutBtn');
    if (ab) ab.addEventListener('click', function(){
      openSheet('Об этом приложении',
        '<p class="ap-p">Ива — площадка аренды инструмента. Найдите электроинструмент, стройоборудование, садовую технику, генератор, сварочное или клининговое оборудование в своём городе, отправьте заявку прокату и договоритесь в чате.</p>' +
        '<p class="ap-p" style="margin-top:12px"><b style="color:var(--tx)">Тем, кто берёт в аренду:</b> каталог по категориям и городам, избранное, заявки с выбором срока, чат с прокатом, уведомления о статусе заявки.</p>' +
        '<p class="ap-p" style="margin-top:12px"><b style="color:var(--tx)">Прокатам:</b> объявления, заявки по пунктам выдачи, сотрудники с правами, срок аренды с таймером, «чек» по каждому инструменту, архив заявок. Стать арендодателем можно по заявке владельцу площадки.</p>' +
        '<p class="ap-p" style="margin-top:12px">Аккаунт общий с сайтом: войдите тем же e-mail — избранное, заявки и чаты на месте.</p>' +
        '<div class="ap-info" style="margin-top:18px"><div><span>Версия</span><b>' + esc(REL.versionName) + '</b></div>' +
        '<div><span>Требуется</span><b>Android 5.0 и выше</b></div>' +
        (APK_SIZE ? '<div><span>Размер</span><b>' + APK_SIZE + '</b></div>' : '') +
        '<div><span>Разработчик</span><b>Ива</b></div></div>');
    });
    // Липкая кнопка «Установить» внизу, когда большая кнопка ушла вверх
    var btn = $id('apInstall'), bar = $id('apSticky');
    if (btn && bar && 'IntersectionObserver' in window){
      new IntersectionObserver(function(en){
        var x = en[0];
        bar.classList.toggle('show', !x.isIntersecting && x.boundingClientRect.top < 0);
      }).observe(btn);
    }
  }

  function installSheet(){
    var file = (typeof ivaApkFile === 'function' && ivaApkFile(REL.apk)) || REL.apk;
    if (!isMobileDevice()){
      openSheet('Установите на телефон',
        '<p class="ap-p">Файл приложения скачивается на компьютер. Проще сразу на телефоне: наведите камеру Android-телефона на код — откроется эта страница, там нажмите «Установить».</p>' +
        '<img src="assets/qr-app-logo.png" alt="QR-код" style="width:200px;height:200px;display:block;margin:16px auto 0;background:#fff;border-radius:12px;padding:8px">');
      return;
    }
    openSheet('Файл скачивается',
      '<ol class="ap-steps">' +
        '<li>Дождитесь загрузки <b>' + esc(file) + '</b>' + (APK_SIZE ? ' (' + APK_SIZE + ')' : '') + '.</li>' +
        '<li>Откройте файл из уведомления или папки <b>«Загрузки»</b>.</li>' +
        '<li>Разрешите <b>установку из этого источника</b>, если телефон спросит.</li>' +
        '<li>Play Защита: <b>«Подробнее» → «Установить без проверки»</b>.</li>' +
      '</ol>' +
      '<p class="sh-note">Загрузка не началась? <a class="ap-link" href="' + esc(REL.apk) + '" data-app-dl="Скачать ещё раз" download>Скачать ещё раз</a></p>');
  }

  /* ---------- Счётчик скачиваний (просьба владельца 07.11.2026) ----------
     Как в Google Play: «10+», «50+», «100+», «500+», «1 000+»… — база отдаёт уже округлённое число
     (SQL supabase/migrations/20261128_appeals.sql: iva_download_count / iva_download_hit). Видно только в мобильной
     версии (в app.html .ap-st-dl скрыт от 1000px). Нажатие «Установить» / «Скачать» — одно скачивание с этого браузера:
     случайный номер браузера в localStorage, без IP, cookies и данных человека. Пока SQL не выполнен или скачиваний
     ещё нет — счётчика просто нет. */
  var DL_DEV = 'iva_dl_dev', DL_DONE = 'iva_dl_done', DL_RE = /^[A-Za-z0-9_-]{8,64}$/;
  function dlLabel(n){
    n = Math.floor(Number(n) || 0);
    return n >= 1 ? String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ' ') + '+' : '';
  }
  function paintDownloads(n){
    var box = $id('stDl'), v = $id('stDlV'); if (!box || !v) return;
    var t = dlLabel(n);
    if (!t){ box.hidden = true; return; }
    v.textContent = t; box.hidden = false;
  }
  function loadDownloads(){ rpc('iva_download_count').then(paintDownloads).catch(function(){}); }
  function dlDevice(){
    try {
      var d = localStorage.getItem(DL_DEV) || '';
      if (!DL_RE.test(d)){
        var a = new Uint8Array(12); (window.crypto || window.msCrypto).getRandomValues(a);
        d = 'w' + Array.prototype.map.call(a, function(x){ return ('0' + x.toString(16)).slice(-2); }).join('');
        localStorage.setItem(DL_DEV, d);
      }
      return d;
    } catch(e){ return ''; }
  }
  function hitDownload(){
    try { if (localStorage.getItem(DL_DONE) === '1') return; } catch(e){ return; }
    var d = dlDevice(); if (!d) return;
    rpc('iva_download_hit', { p_dev: d }).then(function(n){
      try { localStorage.setItem(DL_DONE, '1'); } catch(e){}
      paintDownloads(n);
    }).catch(function(){});
  }

  /* ---------- Оценки и отзывы ---------- */
  var R = { sort: 'helpful', stars: 0, items: [], offset: 0, more: false, ok: null, stats: null, mine: null };

  function loadStats(){
    return rpc('iva_app_reviews_stats').then(function(r){
      R.stats = (Array.isArray(r) ? r[0] : r) || { total: 0, avg: 0 }; R.ok = true;
    }).catch(function(e){ R.ok = false; R.err = String(e && e.message || e); }).then(paintStats);
  }
  function loadList(reset){
    if (reset){ R.offset = 0; R.items = []; }
    return rpc('iva_app_reviews_list', { p_sort: R.sort, p_stars: R.stars, p_limit: PAGE + 1, p_offset: R.offset })
      .then(function(rows){
        rows = Array.isArray(rows) ? rows : [];
        R.more = rows.length > PAGE; rows = rows.slice(0, PAGE);
        if (reset && !R.stars) R.mine = rows.filter(function(x){ return x.mine; })[0] || null;
        R.items = R.items.concat(rows); R.offset += rows.length; R.ok = true;
      })
      .catch(function(e){ if (reset) R.ok = false; else toast(String(e && e.message || e)); })
      .then(function(){ paintList(); paintRate(); });
  }
  function reloadAll(){ return Promise.all([loadStats(), loadList(true)]); }

  function paintStats(){
    var s = R.stats || { total: 0, avg: 0 }, total = Number(s.total) || 0, avg = Number(s.avg) || 0;
    var has = R.ok && total > 0;
    $id('rtAvg').textContent = has ? num(avg.toFixed(1)) : '—';
    $id('rtStars').innerHTML = stars(has ? avg : 0);
    $id('rtTotal').textContent = has ? total + ' ' + plural(total, 'отзыв', 'отзыва', 'отзывов') : (R.ok === false ? 'скоро' : 'нет оценок');
    var bars = '';
    for (var i = 5; i >= 1; i--){
      var c = Number(s['s' + i]) || 0, pct = has ? Math.round(c * 100 / total) : 0;
      bars += '<div class="rt-bar"><span>' + i + '</span><i><s style="width:' + pct + '%"></s></i></div>';
    }
    $id('rtBars').innerHTML = bars;
    $id('stRateV').innerHTML = has ? num(avg.toFixed(1)) + ' ' + STAR : '—';
    $id('stRateN').textContent = has ? total + ' ' + plural(total, 'отзыв', 'отзыва', 'отзывов') : (R.ok === false ? 'отзывы скоро' : 'нет оценок');
  }

  function paintRate(){
    var box = $id('rtRate'); if (!box) return;
    box.hidden = R.ok === false;
    $id('rtTools').hidden = R.ok === false;
    var m = R.mine, cur = m ? m.rating : 0, h = '';
    for (var i = 1; i <= 5; i++) h += '<button type="button" data-rate="' + i + '" class="' + (i <= cur ? 'on' : '') + '" aria-label="' + i + ' из 5">' + STAR + '</button>';
    $id('tapStars').innerHTML = h;
    $id('rtRateT').textContent = m ? 'Ваша оценка' : 'Оцените приложение';
    $id('rtRateS').textContent = m ? 'Нажмите на звёзды, чтобы изменить отзыв' : 'Расскажите другим, что вы думаете';
  }

  function card(r){
    var nm = String(r.name || 'Пользователь Ивы'), ch = nm.charAt(0).toUpperCase() || 'И';
    var hs = 0; for (var k = 0; k < nm.length; k++) hs = (hs * 31 + nm.charCodeAt(k)) % 997;
    var col = AVA[hs % AVA.length];
    var badge = r.mine ? '<span class="rv-badge me">Ваш отзыв</span>' : '';
    if (r.status === 'pending') badge += '<span class="rv-badge">на проверке</span>';
    if (r.status === 'hidden') badge += '<span class="rv-badge hid">скрыт</span>';
    var n = Number(r.helpful) || 0, h = '';
    h += '<div class="rv" data-id="' + r.id + '">';
    h += '<div class="rv-h"><span class="rv-ava" style="background:' + col + '">' + esc(ch) + '</span><span class="rv-n">' + esc(nm) + '</span>' + badge + '</div>';
    h += '<div class="rv-m">' + stars(r.rating) + '<span>' + esc(fmtDate(r.updated_at || r.created_at)) + '</span>' +
         (r.app_version ? '<span>· версия ' + esc(r.app_version) + '</span>' : '') + '</div>';
    if (r.body) h += '<p class="rv-b cl">' + esc(r.body) + '</p><button type="button" class="rv-more" data-act="more" hidden>Ещё</button>';
    if (n > 0) h += '<div class="rv-help">' + n + ' ' + plural(n, 'человек считает', 'человека считают', 'человек считают') + ' этот отзыв полезным</div>';
    if (!r.mine && r.status === 'visible')
      h += '<div class="rv-vote">Отзыв полезен?' +
           '<button type="button" data-act="vote" data-v="1" class="' + (r.my_vote === true ? 'on' : '') + '">Да</button>' +
           '<button type="button" data-act="vote" data-v="0" class="' + (r.my_vote === false ? 'on' : '') + '">Нет</button></div>';
    if (r.reply) h += '<div class="rv-reply"><div class="t">Ива · ответ разработчика<span>' + esc(fmtDate(r.reply_at)) + '</span></div><p>' + esc(r.reply) + '</p></div>';
    var act = '';
    if (r.mine) act += '<button type="button" data-act="edit">Изменить</button><button type="button" class="warn" data-act="del">Удалить</button>';
    if (isCreator()){
      act += '<button type="button" data-act="reply">' + (r.reply ? 'Изменить ответ' : 'Ответить') + '</button>';
      act += r.status === 'visible' ? '<button type="button" class="warn" data-act="hide">Скрыть</button>'
                                    : '<button type="button" data-act="show">Опубликовать</button>';
    }
    if (act) h += '<div class="rv-act">' + act + '</div>';
    return h + '</div>';
  }

  function paintList(){
    var box = $id('rvList'), more = $id('rvMore');
    if (R.ok === false){
      box.innerHTML = '<div class="rv-empty">Отзывы о приложении скоро появятся.' +
        (isCreator() ? '<br><small>Создателю: выполните в Supabase SQL <b>supabase/migrations/20261120_app_reviews.sql</b>.</small>' : '') + '</div>';
      more.hidden = true; return;
    }
    if (!R.items.length){
      box.innerHTML = '<div class="rv-empty">' + (R.stars ? 'Отзывов с такой оценкой пока нет.' : 'Отзывов пока нет — будьте первым!') + '</div>';
      more.hidden = true; return;
    }
    box.innerHTML = R.items.map(card).join('');
    more.hidden = !R.more;
    var ps = box.querySelectorAll('.rv-b.cl');
    for (var i = 0; i < ps.length; i++) if (ps[i].scrollHeight > ps[i].clientHeight + 2) ps[i].nextElementSibling.hidden = false;
  }

  function byId(id){ id = Number(id); return R.items.filter(function(x){ return Number(x.id) === id; })[0]; }

  function loginSheet(why){
    openSheet('Нужен вход',
      '<p class="ap-p">' + esc(why) + '. Войдите тем же e-mail, что в приложении, — после входа вы вернётесь сюда.</p>',
      '<a class="btn wide" href="' + loginUrl() + '">Войти или зарегистрироваться</a>');
  }

  function writeSheet(rating){
    if (!isAuthed()){
      try { sessionStorage.setItem('iva_app_rate', String(rating || 5)); } catch(e){}
      loginSheet('Чтобы оценить приложение, войдите');
      return;
    }
    var m = R.mine, cur = rating || (m && m.rating) || 0;
    var vers = [REL.versionName];
    if (m && m.app_version && vers.indexOf(m.app_version) < 0) vers.push(m.app_version);
    var sel = vers.filter(Boolean).map(function(v, i){
      return '<option value="' + esc(v) + '"' + ((m ? m.app_version === v : i === 0) ? ' selected' : '') + '>Версия ' + esc(v) + (v === REL.versionName ? ' (последняя)' : '') + '</option>';
    }).join('') + '<option value=""' + (m && !m.app_version ? ' selected' : '') + '>Не знаю / другая версия</option>';
    var st = '';
    for (var i = 1; i <= 5; i++) st += '<button type="button" data-r="' + i + '" class="' + (i <= cur ? 'on' : '') + '" aria-label="' + i + ' из 5">' + STAR + '</button>';
    openSheet(m ? 'Изменить отзыв' : 'Оценить приложение',
      '<div style="text-align:center"><div class="tap-st" id="shStars">' + st + '</div></div>' +
      '<textarea class="sh-txt" id="shTxt" maxlength="1000" placeholder="Что понравилось, что нет? (необязательно)">' + esc(m ? m.body : '') + '</textarea>' +
      '<div class="sh-cnt"><span id="shCnt">0</span> / 1000</div>' +
      '<select class="inp sh-sel" id="shVer">' + sel + '</select>' +
      '<p class="sh-note">Отзыв увидят все. Рядом покажется только ваше имя из профиля (первое слово), почта — нет. Нецензурные слова не пропустит проверка.</p>',
      '<button type="button" class="btn wide" id="shSave">Опубликовать</button>' +
      (m ? '<button type="button" class="btn ghost wide" id="shDel" style="margin-top:8px;color:var(--red)">Удалить отзыв</button>' : ''));
    var sheet = $id('sheet'), txt = $id('shTxt'), cnt = $id('shCnt');
    var paintCnt = function(){ cnt.textContent = txt.value.length; };
    paintCnt(); txt.addEventListener('input', paintCnt);
    $id('shStars').addEventListener('click', function(e){
      var b = e.target.closest('[data-r]'); if (!b) return;
      cur = Number(b.getAttribute('data-r'));
      var all = sheet.querySelectorAll('#shStars button');
      for (var j = 0; j < all.length; j++) all[j].classList.toggle('on', j < cur);
    });
    $id('shSave').addEventListener('click', function(){
      if (!cur){ toast('Поставьте оценку — от 1 до 5 звёзд'); return; }
      var btn = this; btn.disabled = true;
      rpc('iva_app_review_save', { p_rating: cur, p_body: txt.value, p_version: $id('shVer').value })
        .then(function(res){
          closeSheet();
          toast(res && res.status === 'pending' ? 'Отзыв отправлен на проверку — появится после одобрения' : 'Спасибо! Отзыв опубликован');
          return reloadAll();
        })
        .catch(function(e){ btn.disabled = false; toast(String(e && e.message || e)); });
    });
    var del = $id('shDel');
    if (del) del.addEventListener('click', function(){ closeSheet(); delMine(); });
  }

  function delMine(){
    if (!confirm('Удалить ваш отзыв о приложении?')) return;
    rpc('iva_app_review_delete').then(function(){ toast('Отзыв удалён'); R.mine = null; return reloadAll(); })
      .catch(function(e){ toast(String(e && e.message || e)); });
  }

  function replySheet(r){
    openSheet('Ответ разработчика',
      '<p class="sh-note" style="margin:0 0 10px">Отзыв: ' + esc(String(r.body || '').slice(0, 160) || 'без текста, ' + r.rating + ' из 5') + '</p>' +
      '<textarea class="sh-txt" id="rpTxt" maxlength="1000" placeholder="Ответ увидят все. Пусто — убрать ответ.">' + esc(r.reply || '') + '</textarea>',
      '<button type="button" class="btn wide" id="rpSave">Сохранить ответ</button>');
    $id('rpSave').addEventListener('click', function(){
      var b = this; b.disabled = true;
      rpc('iva_app_review_reply', { p_id: r.id, p_reply: $id('rpTxt').value })
        .then(function(){ closeSheet(); toast('Ответ сохранён'); return loadList(true); })
        .catch(function(e){ b.disabled = false; toast(String(e && e.message || e)); });
    });
  }

  function bindReviews(){
    $id('tapStars').addEventListener('click', function(e){
      var b = e.target.closest('[data-rate]'); if (b) writeSheet(Number(b.getAttribute('data-rate')));
    });
    $id('rtTools').addEventListener('click', function(e){
      var b = e.target.closest('button'); if (!b) return;
      var grp = b.hasAttribute('data-stars') ? 'data-stars' : 'data-sort';
      var all = this.querySelectorAll('[' + grp + ']');
      for (var i = 0; i < all.length; i++) all[i].classList.toggle('on', all[i] === b);
      if (grp === 'data-stars') R.stars = Number(b.getAttribute('data-stars')); else R.sort = b.getAttribute('data-sort');
      loadList(true);
    });
    $id('rvMore').addEventListener('click', function(){ loadList(false); });
    $id('rvList').addEventListener('click', function(e){
      var b = e.target.closest('[data-act]'); if (!b) return;
      var row = b.closest('.rv'), r = row && byId(row.getAttribute('data-id')), act = b.getAttribute('data-act');
      if (!r) return;
      if (act === 'more'){ var p = row.querySelector('.rv-b'); p.classList.remove('cl'); b.hidden = true; return; }
      if (act === 'edit'){ writeSheet(0); return; }
      if (act === 'del'){ delMine(); return; }
      if (act === 'reply'){ replySheet(r); return; }
      if (act === 'hide' || act === 'show'){
        rpc('iva_app_review_status', { p_id: r.id, p_status: act === 'hide' ? 'hidden' : 'visible' })
          .then(function(){ toast(act === 'hide' ? 'Отзыв скрыт' : 'Отзыв опубликован'); return reloadAll(); })
          .catch(function(e){ toast(String(e && e.message || e)); });
        return;
      }
      if (act === 'vote'){
        if (!isAuthed()){ loginSheet('Чтобы отметить отзыв, войдите'); return; }
        var v = b.getAttribute('data-v') === '1', nv = (r.my_vote === v) ? null : v;
        b.disabled = true;
        rpc('iva_app_review_vote', { p_id: r.id, p_helpful: nv })
          .then(function(n){ r.helpful = Number(n) || 0; r.my_vote = nv; row.outerHTML = card(r); })
          .catch(function(e){ b.disabled = false; toast(String(e && e.message || e)); });
      }
    });
  }

  /* ---------- Запуск ---------- */
  function start(){
    if (isIOS()) document.body.classList.add('is-ios');
    mountChrome('app', '');
    renderShots();
    bindTop();
    paintRelease(REL);
    ivaReleased().then(function(rel){ if (rel && rel.apk !== REL.apk) paintRelease(rel); else REL = rel || REL; });
    loadReleaseDate();
    loadDownloads();
    paintStats(); paintRate(); bindReviews();
    var sess = (typeof restoreSess === 'function') ? restoreSess().catch(function(){ return false; }) : Promise.resolve(false);
    sess.then(reloadAll).then(function(){
      var want = 0;
      try { want = Number(sessionStorage.getItem('iva_app_rate')) || 0; } catch(e){}
      if (want && isAuthed() && R.ok){
        try { sessionStorage.removeItem('iva_app_rate'); } catch(e){}
        $id('reviews').scrollIntoView({ block: 'start' });
        writeSheet(R.mine ? 0 : want);
      }
    });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
})();
