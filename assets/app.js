/* ===========================================================
   Ива — общая логика витрины: избранное, поиск, рендер карточек,
   фильтрация и сортировка. Работает без сборки, ES5-совместимо.
   =========================================================== */

/* ---------- Мелкие утилиты ---------- */
/* getElementById есть только у document, поэтому для произвольного
   контейнера ищем через querySelector — иначе падает TypeError. */
const $  = (id, root) => root ? root.querySelector('#' + id) : document.getElementById(id);
const $1 = (sel, root) => (root || document).querySelector(sel);
const $$ = (sel, root) => Array.prototype.slice.call((root || document).querySelectorAll(sel));

function esc(s){
  return String(s == null ? '' : s)
    .replace(/&/g,'&amp;').replace(/</g,'&lt;')
    .replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}
/* 1 500 ₽ — с неразрывными пробелами */
function rub(n){
  return String(Math.round(n || 0)).replace(/\B(?=(\d{3})+(?!\d))/g,'\u00A0') + '\u00A0₽';
}
function plural(n, one, few, many){
  const m10 = n % 10, m100 = n % 100;
  if (m10 === 1 && m100 !== 11) return one;
  if (m10 >= 2 && m10 <= 4 && (m100 < 10 || m100 >= 20)) return few;
  return many;
}
function daysAgo(d){
  if (d === 0) return 'сегодня';
  if (d === 1) return 'вчера';
  return d + ' ' + plural(d,'день','дня','дней') + ' назад';
}
function initials(name){
  const p = String(name).trim().split(/\s+/);
  return ((p[0] || '')[0] + (p[1] ? p[1][0] : '')).toUpperCase();
}

/* ---------- Тост ---------- */
let _toastT;
function toast(msg){
  let t = $('toast');
  if (!t){
    t = document.createElement('div');
    t.id = 'toast'; t.className = 'toast';
    document.body.appendChild(t);
  }
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(_toastT);
  _toastT = setTimeout(() => t.classList.remove('show'), 2600);
}

/* ---------- Избранное (localStorage) ---------- */
const FAV_KEY = 'iva_market_fav';
function favList(){
  try { return JSON.parse(localStorage.getItem(FAV_KEY) || '[]'); }
  catch(e){ return []; }
}
function favHas(id){ return favList().indexOf(Number(id)) !== -1; }
function favToggle(id){
  id = Number(id);
  const list = favList();
  const i = list.indexOf(id);
  if (i === -1) list.push(id); else list.splice(i, 1);
  try { localStorage.setItem(FAV_KEY, JSON.stringify(list)); } catch(e){}
  paintFavCount();
  return i === -1;
}
function paintFavCount(){
  const n = favList().length;
  $$('.fav-count').forEach(el => {
    el.textContent = n;
    el.style.display = n ? 'flex' : 'none';
  });
}

/* ---------- Город ---------- */
const CITY_KEY = 'iva_market_city';
function getCity(){
  try { return localStorage.getItem(CITY_KEY) || 'Новочеркасск'; }
  catch(e){ return 'Новочеркасск'; }
}
function setCity(c){
  try { localStorage.setItem(CITY_KEY, c); } catch(e){}
  $$('.city-name').forEach(el => el.textContent = c);
}

/* ---------- Иконки ---------- */
const IC = {
  heart:'<svg viewBox="0 0 24 24"><path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8l1.1 1L12 21.2l7.7-7.7 1.1-1a5.5 5.5 0 0 0 0-7.9z"/></svg>',
  search:'<svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg>',
  home:'<svg viewBox="0 0 24 24"><path d="M3 10.5 12 3l9 7.5"/><path d="M5 9.5V21h14V9.5"/></svg>',
  chat:'<svg viewBox="0 0 24 24"><path d="M21 11.5a8.4 8.4 0 0 1-9 8.4 8.4 8.4 0 0 1-3.8-.9L3 21l2-5.2a8.4 8.4 0 0 1-.9-3.8 8.4 8.4 0 0 1 8.4-8.4h.5a8.4 8.4 0 0 1 8 8z"/></svg>',
  user:'<svg viewBox="0 0 24 24"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>',
  plus:'<svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg>',
  filter:'<svg viewBox="0 0 24 24"><path d="M3 5h18M7 12h10M11 19h2"/></svg>',
  pin:'<svg viewBox="0 0 24 24"><path d="M20 10c0 5-8 12-8 12s-8-7-8-12a8 8 0 0 1 16 0"/><circle cx="12" cy="10" r="3"/></svg>',
  box:'<svg viewBox="0 0 24 24"><path d="M21 8 12 3 3 8v8l9 5 9-5z"/><path d="M3 8l9 5 9-5M12 13v8"/></svg>',
  left:'<svg viewBox="0 0 24 24"><path d="m15 18-6-6 6-6"/></svg>',
  right:'<svg viewBox="0 0 24 24"><path d="m9 18 6-6-6-6"/></svg>',
  phone:'<svg viewBox="0 0 24 24"><path d="M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3.1 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2.1 4.2 2 2 0 0 1 4.1 2h3a2 2 0 0 1 2 1.7c.1 1 .4 1.9.7 2.8a2 2 0 0 1-.5 2.1L8.1 9.9a16 16 0 0 0 6 6l1.3-1.3a2 2 0 0 1 2.1-.4c.9.3 1.8.6 2.8.7a2 2 0 0 1 1.7 2z"/></svg>'
};

/* ---------- Карточка объявления ---------- */
function adCard(ad){
  const owner = ownerById(ad.owner);
  const dep = depositOf(ad);
  const badges = [];
  if (ad.delivery) badges.push('<span class="badge deliv">Доставка</span>');
  if (ad.cond === 'new') badges.push('<span class="badge new">Новый</span>');

  return '' +
  '<article class="ad" data-id="' + ad.id + '">' +
    '<a class="ad-ph" href="tool.html?id=' + ad.id + '">' +
      '<img src="' + photoURL(ad, 0) + '" alt="' + esc(ad.name) + '" loading="lazy">' +
      (badges.length ? '<div class="ad-badges">' + badges.join('') + '</div>' : '') +
    '</a>' +
    '<button class="fav' + (favHas(ad.id) ? ' on' : '') + '" data-fav="' + ad.id + '" ' +
            'aria-label="В избранное" title="В избранное">' + IC.heart + '</button>' +
    '<div class="ad-body">' +
      '<div class="ad-price">' + rub(ad.price) + ' <span>/ сутки</span></div>' +
      '<a class="ad-name" href="tool.html?id=' + ad.id + '">' + esc(ad.name) + '</a>' +
      '<div class="ad-dep">Залог ' + rub(dep) + '</div>' +
      '<div class="ad-meta">' +
        '<span class="ad-owner"><span class="star">★</span>' + owner.rating.toFixed(1) + '</span>' +
        '<span>·</span><span>' + esc(ad.city) + '</span>' +
      '</div>' +
    '</div>' +
  '</article>';
}

/* Делегированный обработчик сердечек — вешается один раз */
function bindFavs(root){
  (root || document).addEventListener('click', function(e){
    const b = e.target.closest ? e.target.closest('[data-fav]') : null;
    if (!b) return;
    e.preventDefault();
    const added = favToggle(b.getAttribute('data-fav'));
    b.classList.toggle('on', added);
    toast(added ? 'Добавлено в избранное' : 'Убрано из избранного');
    if (window.onFavChanged) window.onFavChanged();
  });
}

/* ---------- Фильтрация и сортировка ---------- */
function applyFilters(list, f){
  return list.filter(function(ad){
    if (ad.status !== 'active') return false;
    if (f.cat && ad.cat !== f.cat) return false;
    if (f.sub && ad.sub !== f.sub) return false;
    if (f.city && ad.city !== f.city) return false;
    if (f.priceFrom && ad.price < f.priceFrom) return false;
    if (f.priceTo   && ad.price > f.priceTo)   return false;
    if (f.conds && f.conds.length && f.conds.indexOf(ad.cond) === -1) return false;
    if (f.delivery && !ad.delivery) return false;
    if (f.q){
      const q = f.q.toLowerCase();
      const hay = (ad.name + ' ' + ad.descr + ' ' + catTitle(ad.cat) + ' ' + subTitle(ad.sub)).toLowerCase();
      if (hay.indexOf(q) === -1) return false;
    }
    return true;
  });
}

function sortAds(list, mode){
  const a = list.slice();
  if (mode === 'cheap')  a.sort((x,y) => x.price - y.price);
  else if (mode === 'exp') a.sort((x,y) => y.price - x.price);
  else if (mode === 'rating') a.sort((x,y) => ownerById(y.owner).rating - ownerById(x.owner).rating);
  else a.sort((x,y) => x.days - y.days); // 'new' — по свежести
  return a;
}

/* ---------- Подсказки поиска ---------- */
function buildSuggest(input, box, onPick){
  let items = [], act = -1;

  function close(){ box.classList.add('hide'); act = -1; }

  function render(q){
    q = q.trim().toLowerCase();
    if (!q){ close(); return; }

    const cats = CATS
      .filter(c => c.title.toLowerCase().indexOf(q) !== -1)
      .map(c => ({type:'cat', title:c.title, key:c.key}));

    const ads = ADS
      .filter(a => a.status === 'active' && a.name.toLowerCase().indexOf(q) !== -1)
      .slice(0, 6)
      .map(a => ({type:'ad', title:a.name, id:a.id, cat:catTitle(a.cat)}));

    items = cats.concat(ads).slice(0, 8);
    if (!items.length){ close(); return; }

    box.innerHTML = items.map((it, i) =>
      '<div data-i="' + i + '">' + IC.search +
        '<span>' + esc(it.title) + '</span>' +
        '<span class="s-cat">' + (it.type === 'cat' ? 'категория' : esc(it.cat)) + '</span>' +
      '</div>'
    ).join('');
    box.classList.remove('hide');
  }

  function pick(i){
    const it = items[i];
    if (!it) return;
    close();
    if (it.type === 'cat') location.href = 'catalog.html?cat=' + encodeURIComponent(it.key);
    else location.href = 'tool.html?id=' + it.id;
  }

  input.addEventListener('input', () => render(input.value));
  input.addEventListener('focus', () => { if (input.value.trim()) render(input.value); });

  input.addEventListener('keydown', function(e){
    if (box.classList.contains('hide')) return;
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp'){
      e.preventDefault();
      act += (e.key === 'ArrowDown' ? 1 : -1);
      if (act < 0) act = items.length - 1;
      if (act >= items.length) act = 0;
      $$('div', box).forEach((d, i) => d.classList.toggle('act', i === act));
    } else if (e.key === 'Enter' && act >= 0){
      e.preventDefault(); pick(act);
    } else if (e.key === 'Escape'){ close(); }
  });

  box.addEventListener('mousedown', function(e){
    const d = e.target.closest('[data-i]');
    if (d) { e.preventDefault(); pick(Number(d.getAttribute('data-i'))); }
  });

  document.addEventListener('click', function(e){
    if (!box.contains(e.target) && e.target !== input) close();
  });
}

/* ---------- Шапка и нижнее меню ---------- */
function headerHTML(active, q){
  return '' +
  '<header class="head"><div class="wrap head-in">' +
    '<a class="logo" href="index.html">' +
      '<img src="assets/logo.png" alt="Ива">' +
      '<span><b>Ива</b><span>Инструмент в аренду</span></span>' +
    '</a>' +
    '<button class="city" id="cityBtn">' + IC.pin +
      '<span class="city-name">' + esc(getCity()) + '</span>' +
    '</button>' +
    '<div class="search">' +
      '<svg class="s-ic" viewBox="0 0 24 24"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg>' +
      '<input id="q" type="search" placeholder="Поиск инструмента" ' +
             'value="' + esc(q || '') + '" autocomplete="off">' +
      '<button class="s-clr hide" id="qClr" aria-label="Очистить">×</button>' +
      '<button class="s-go" id="qGo">Найти</button>' +
      '<div class="sug hide" id="sug"></div>' +
    '</div>' +
    '<nav class="hlinks">' +
      '<a class="hlink' + (active === 'fav' ? ' on' : '') + '" href="favorites.html">' +
        IC.heart + '<span>Избранное</span>' +
        '<span class="dot fav-count" style="display:none">0</span>' +
      '</a>' +
      '<a class="hlink" href="account.html#msg">' + IC.chat + '<span>Сообщения</span></a>' +
      '<a class="hlink' + (active === 'cab' ? ' on' : '') + '" href="account.html">' +
        IC.user + '<span>Кабинет</span></a>' +
      '<a class="btn sm" href="new.html" style="margin-left:6px">Разместить</a>' +
    '</nav>' +
  '</div></header>';
}

function bnavHTML(active){
  const it = (key, href, icon, label, extra) =>
    '<a class="' + (active === key ? 'on' : '') + '" href="' + href + '"' + (extra || '') + '>' +
      icon + '<span>' + label + '</span>' +
      (key === 'fav' ? '<span class="dot fav-count" style="display:none">0</span>' : '') +
    '</a>';

  return '<nav class="bnav"><div class="bnav-in">' +
    it('home','index.html',IC.home,'Главная') +
    it('fav','favorites.html',IC.heart,'Избранное') +
    '<a class="mid" href="new.html">' +
      '<span class="plus">' + IC.plus + '</span><span>Разместить</span></a>' +
    it('chat','account.html#msg',IC.chat,'Сообщения') +
    it('cab','account.html',IC.user,'Кабинет') +
  '</div></nav>';
}

function footHTML(){
  return '<footer class="foot"><div class="wrap">' +
    '<div class="cols">' +
      '<div><b>Ива</b>' +
        '<a href="index.html">Главная</a>' +
        '<a href="catalog.html">Весь каталог</a>' +
        '<a href="favorites.html">Избранное</a></div>' +
      '<div><b>Аренда</b>' +
        '<a href="offer.html">Публичная оферта</a>' +
        '<a href="catalog.html?delivery=1">С доставкой</a>' +
        '<a href="account.html">Личный кабинет</a></div>' +
      '<div><b>Контакты</b>' +
        '<a href="tel:+79081732475">+7 (908) 173-24-75</a>' +
        '<span>Новочеркасск, ул. Маресьева, 36</span>' +
        '<span>Ежедневно 8:00–20:00</span></div>' +
    '</div>' +
    '<div class="small">© ' + new Date().getFullYear() + ' Ива — Инструмент в аренду. ' +
      'Витрина работает на демонстрационных данных.</div>' +
  '</div></footer>';
}

/* ---------- Шторка выбора города ---------- */
function citySheet(){
  const cur = getCity();
  openSheet('Выберите город',
    '<div class="f-list" style="max-height:none">' +
      CITIES.map(c =>
        '<div class="f-item' + (c === cur ? ' on' : '') + '" data-city="' + esc(c) + '">' +
          esc(c) + (c === cur ? '<span class="n">✓</span>' : '') +
        '</div>').join('') +
    '</div>', null);

  $1('.sheet-b').addEventListener('click', function(e){
    const d = e.target.closest('[data-city]');
    if (!d) return;
    setCity(d.getAttribute('data-city'));
    closeSheet();
    toast('Город: ' + d.getAttribute('data-city'));
    if (window.onCityChanged) window.onCityChanged();
  });
}

/* ---------- Универсальная шторка ---------- */
function openSheet(title, bodyHTML, footHTMLStr){
  closeSheet(true);
  const mask = document.createElement('div');
  mask.className = 'mask'; mask.id = 'sheetMask';

  const sh = document.createElement('div');
  sh.className = 'sheet'; sh.id = 'sheet';
  sh.innerHTML =
    '<div class="grabber"></div>' +
    '<div class="sheet-h"><b>' + esc(title) + '</b>' +
      '<button class="x" aria-label="Закрыть">×</button></div>' +
    '<div class="sheet-b">' + bodyHTML + '</div>' +
    (footHTMLStr ? '<div class="sheet-f">' + footHTMLStr + '</div>' : '');

  document.body.appendChild(mask);
  document.body.appendChild(sh);
  document.body.style.overflow = 'hidden';

  requestAnimationFrame(() => {
    mask.classList.add('open');
    sh.classList.add('open');
  });

  mask.addEventListener('click', () => closeSheet());
  $1('.x', sh).addEventListener('click', () => closeSheet());
}

function closeSheet(instant){
  const sh = $('sheet'), mask = $('sheetMask');
  document.body.style.overflow = '';
  if (!sh) return;
  if (instant){ sh.remove(); if (mask) mask.remove(); return; }
  sh.classList.remove('open');
  if (mask) mask.classList.remove('open');
  setTimeout(() => { sh.remove(); if (mask) mask.remove(); }, 260);
}

/* ---------- Заглушки будущих этапов ---------- */
function bindSoon(){
  document.addEventListener('click', function(e){
    const a = e.target.closest ? e.target.closest('[data-soon]') : null;
    if (!a) return;
    e.preventDefault();
    toast(a.getAttribute('data-soon') + ' — появится на следующем этапе');
  });
}

/* ---------- Инициализация общего каркаса ---------- */
function mountChrome(active, q){
  const h = $('hdr');   if (h) h.innerHTML = headerHTML(active, q);
  const b = $('bnav');  if (b) b.innerHTML = bnavHTML(active);
  const f = $('ftr');   if (f) f.innerHTML = footHTML();

  paintFavCount();
  bindFavs();
  bindSoon();

  const cb = $('cityBtn'); if (cb) cb.addEventListener('click', citySheet);

  const qi = $('q'), go = $('qGo'), clr = $('qClr'), sug = $('sug');
  if (qi){
    buildSuggest(qi, sug);
    const doSearch = () => {
      const v = qi.value.trim();
      location.href = 'catalog.html' + (v ? '?q=' + encodeURIComponent(v) : '');
    };
    go.addEventListener('click', doSearch);
    qi.addEventListener('keydown', e => {
      if (e.key === 'Enter' && sug.classList.contains('hide')) doSearch();
    });
    const paintClr = () => clr.classList.toggle('hide', !qi.value);
    qi.addEventListener('input', paintClr);
    paintClr();
    clr.addEventListener('click', () => { qi.value = ''; paintClr(); qi.focus(); });
  }
}
