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

/* ---------- Расстояние до пункта выдачи и доставка ----------
   На сайте расстояние считается только по адресу, который клиент сохранил
   в приватном профиле. Публичная карточка не получает и не показывает этот
   адрес; она получает только готовый текст результата. */
const IvaDistance = (function(){
  const cache = new Map();
  const pending = new Map();
  let observerTimer = null;
  function num(v){ return v !== null && v !== undefined && v !== '' && isFinite(Number(v)) ? Number(v) : null; }
  function coords(lat, lon){
    lat=num(lat); lon=num(lon);
    return lat !== null && lon !== null && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180
      ? {lat:lat, lon:lon} : null;
  }
  function profileAddress(){
    if (typeof A === 'undefined') return '';
    const p=A.customerProfile||{};
    return String(p.address || (A.userMeta && A.userMeta.address) || '').trim();
  }
  function geocode(address){
    address=String(address||'').trim();
    if (!address || !window.IvaGeo || typeof IvaGeo.search !== 'function') return Promise.resolve(null);
    const key='geo:'+address.toLowerCase();
    if (cache.has(key)) return Promise.resolve(cache.get(key));
    if (pending.has(key)) return pending.get(key);
    const job=IvaGeo.search(address,'address').then(function(rows){
      const r=rows&&rows[0]&&rows[0].coordinates;
      const c=r&&coords(r[1],r[0]);cache.set(key,c);return c;
    }).catch(function(){cache.set(key,null);return null;}).finally(function(){pending.delete(key);});
    pending.set(key,job);return job;
  }
  function pickupAddress(ad){
    if (!ad) return '';
    return String(ad.pickup_address || ad.address || '').trim();
  }
  function pickupCoords(ad){ return coords(ad&&ad.pickup_lat,ad&&ad.pickup_lng); }
  function haversine(a,b){
    const r=6371, dLat=(b.lat-a.lat)*Math.PI/180, dLon=(b.lon-a.lon)*Math.PI/180;
    const q=Math.sin(dLat/2)*Math.sin(dLat/2)+Math.cos(a.lat*Math.PI/180)*Math.cos(b.lat*Math.PI/180)*Math.sin(dLon/2)*Math.sin(dLon/2);
    return Math.max(1,Math.ceil(r*2*Math.atan2(Math.sqrt(q),Math.sqrt(1-q))));
  }
  function key(ad){ return String(ad&&ad.id||'')+'|'+pickupAddress(ad)+'|'+(ad&&ad.pickup_lat||'')+'|'+(ad&&ad.pickup_lng||'|')+'|'+profileAddress(); }
  function estimate(ad){
    const k=key(ad);
    if (cache.has('estimate:'+k)) return Promise.resolve(cache.get('estimate:'+k));
    if (pending.has('estimate:'+k)) return pending.get('estimate:'+k);
    let result;
    if (typeof A === 'undefined' || !A.tok){
      result={ok:false,reason:'guest'};
    } else if (!profileAddress()){
      result={ok:false,reason:'profile'};
    } else if (!pickupCoords(ad) && !pickupAddress(ad)){
      result={ok:false,reason:'pickup'};
    }
    if (result){cache.set('estimate:'+k,result);return Promise.resolve(result);}
    const job=Promise.all([
      Promise.resolve(pickupCoords(ad)||geocode(pickupAddress(ad))),
      geocode(profileAddress())
    ]).then(function(pair){
      if (!pair[0] || !pair[1]) result={ok:false,reason:pair[0]?'destination':'pickup'};
      else {
        const km=haversine(pair[0],pair[1]), rate=Number(ad.delivery_price)||40;
        result={ok:true,km:km,rate:rate,cost:ad.delivery?km*rate:0};
      }
      cache.set('estimate:'+k,result);return result;
    }).catch(function(){result={ok:false,reason:'geocode'};cache.set('estimate:'+k,result);return result;})
      .finally(function(){pending.delete('estimate:'+k);});
    pending.set('estimate:'+k,job);return job;
  }
  function fallback(reason){
    if (reason==='guest') return 'Расстояние — войдите и укажите адрес';
    if (reason==='profile') return 'Расстояние — добавьте адрес в профиле';
    if (reason==='pickup') return 'Расстояние — адрес пункта не указан';
    return 'Расстояние — не удалось определить автоматически';
  }
  function paintCards(root){
    const nodes=$$('[data-distance-ad]',root||document);
    nodes.forEach(function(node){
      const ad=typeof adById==='function'?adById(node.getAttribute('data-distance-ad')):null;
      if (!ad) return;
      const initial=fallback(typeof A!=='undefined'&&A.tok?(profileAddress()?'geocode':'profile'):'guest');
      if (node.textContent!==initial && !node.dataset.distanceReady) node.textContent=initial;
      const k=key(ad);
      if (node.dataset.distanceKey===k) return;
      node.dataset.distanceKey=k;node.dataset.distanceReady='';
      estimate(ad).then(function(r){
        if (node.getAttribute('data-distance-ad')!==String(ad.id)) return;
        const text=r.ok ? ('Расстояние до пункта: '+r.km+' км') : fallback(r.reason);
        node.textContent=text;node.dataset.distanceReady='1';
      });
    });
  }
  function observe(){
    if (!window.MutationObserver || !document.body) return;
    const run=function(){clearTimeout(observerTimer);observerTimer=setTimeout(function(){paintCards();},40);};
    new MutationObserver(run).observe(document.body,{childList:true,subtree:true});
  }
  setTimeout(observe,0);
  return {estimate:estimate,paintCards:paintCards,fallback:fallback,profileAddress:profileAddress,geocode:geocode,haversine:haversine};
})();

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
/* Реальные объявления используют UUID, демо — числовые ID.
   Храним оба вида строками; прежний Number(UUID) превращался в null в JSON. */
function favId(id){
  if (typeof id === 'string') return id.trim();
  if (typeof id === 'number' && Number.isFinite(id)) return String(id);
  return '';
}
function favList(){
  try {
    const raw = localStorage.getItem(FAV_KEY) || '[]';
    const saved = JSON.parse(raw);
    const list = [];
    if (Array.isArray(saved)) saved.forEach(function(id){
      const key = favId(id);
      if (key && list.indexOf(key) === -1) list.push(key);
    });
    /* Сохраняем доступные ID, убираем дубли и повреждённые null. */
    const clean = JSON.stringify(list);
    if (clean !== raw) {
      try { localStorage.setItem(FAV_KEY, clean); } catch(e){}
    }
    return list;
  } catch(e){ return []; }
}
function favHas(id){ return favList().indexOf(favId(id)) !== -1; }
function favToggle(id){
  id = favId(id);
  if (!id) return false;
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
let _selectedCity;
let _cityChosenManually = false;
const PROFILE_CITY_KEY = 'iva_market_profile_city';
function getCity(){
  if (_selectedCity === undefined){
    try {
      const saved = localStorage.getItem(CITY_KEY);
      // Пустая строка — явный выбор «Все города», а не отсутствие настройки.
      _selectedCity = saved === null ? 'Новочеркасск' : IvaGeo.city(saved);
    } catch(e){ _selectedCity = 'Новочеркасск'; }
  }
  return _selectedCity;
}
function setCity(c, manual){
  if (manual) _cityChosenManually = true;
  _selectedCity = IvaGeo.city(c);
  try {
    if (localStorage.getItem(CITY_KEY) !== _selectedCity) localStorage.setItem(CITY_KEY, _selectedCity);
  } catch(e){}
  $$('.city-name').forEach(el => el.textContent = _selectedCity || 'Все города');
  const sug = $('sug'); if (sug) sug.classList.add('hide');
}
// Профиль задаёт начальный город. Ручной выбор не меняет профиль и
// сохраняется между страницами, пока пользователь не изменит город профиля.
function applyProfileCity(city, force){
  if (!city || typeof A === 'undefined' || !A.me) return;
  let previous=null;
  try { previous=JSON.parse(localStorage.getItem(PROFILE_CITY_KEY) || 'null'); } catch(e){}
  const same=previous && previous.email===A.me && cityMatches(previous.city,city);
  try {localStorage.setItem(PROFILE_CITY_KEY,JSON.stringify({email:A.me,city:city}));}catch(e){}
  if(!force && (same || _cityChosenManually)) return;
  if(force) _cityChosenManually=false;
  setCity(city);
  if(window.onCityChanged)window.onCityChanged();
}
function cityKey(city){
  return IvaGeo.city(city).toLowerCase().replace(/ё/g,'е')
    .replace(/^(?:город\s+|г\.\s*|г\s+)/,'').replace(/[–—]/g,'-')
    .replace(/\s*-\s*/g,'-').replace(/\s+/g,' ');
}
function cityMatches(a,b){ return cityKey(a) === cityKey(b); }
function availableCities(){
  const result = [], seen = new Set();
  CITIES.concat(ADS.filter(a=>a.status==='active').map(a=>a.city), [getCity(), typeof A !== 'undefined' ? A.profileCity : '']).forEach(city=>{
    const key=cityKey(city);
    if (key && !seen.has(key)){ seen.add(key); result.push(IvaGeo.city(city)); }
  });
  // Сохранённый вариант написания должен оставаться выбираемым в select.
  const selected=getCity();
  if(selected && result.indexOf(selected)===-1){
    const i=result.findIndex(city=>cityMatches(city,selected));
    if(i!==-1)result[i]=selected;else result.push(selected);
  }
  return result;
}
window.addEventListener('storage',function(e){
  if(e.key !== CITY_KEY && e.key !== null) return;
  _selectedCity=undefined;
  setCity(getCity());
  if(window.onCityChanged)window.onCityChanged();
});

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
  chief:'<svg viewBox="0 0 24 24"><path d="M3 18h18"/><path d="m3 8 4.5 3L12 5l4.5 6L21 8v7H3z"/></svg>',
  phone:'<svg viewBox="0 0 24 24"><path d="M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3.1 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2.1 4.2 2 2 0 0 1 4.1 2h3a2 2 0 0 1 2 1.7c.1 1 .4 1.9.7 2.8a2 2 0 0 1-.5 2.1L8.1 9.9a16 16 0 0 0 6 6l1.3-1.3a2 2 0 0 1 2.1-.4c.9.3 1.8.6 2.8.7a2 2 0 0 1 1.7 2z"/></svg>'
};

/* ---------- Карточка объявления ---------- */
function adCard(ad){
  const owner = ownerById(ad.owner);
  const dep = depositOf(ad);
  const badges = [];
  if (ad.delivery) badges.push('<span class="badge deliv">Доставка</span>');
  if (ad.cond === 'new') badges.push('<span class="badge new">Новый</span>');

  const rCount = Number(ad.reviews_count) || 0;
  const rVal = (Number(ad.rating) || 0).toFixed(1);
  const rHTML = rCount > 0
    ? ('<span class="ad-owner" title="Рейтинг: ' + rVal + ' (' + rCount + ')"><span class="star">★</span>' + rVal + ' <span style="font-size:11px;color:var(--g2)">(' + rCount + ')</span></span>')
    : ('<span class="ad-owner" title="Новое объявление"><span class="star" style="color:var(--g2)">★</span><span style="color:var(--g2);font-size:11.5px">новый</span></span>');

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
        rHTML +
        '<span>·</span><span>' + esc(ad.city) + '</span>' +
        '<span class="ad-distance" data-distance-ad="' + esc(ad.id) + '">Расстояние — уточняется</span>' +
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
    if (f.city && !cityMatches(ad.city, f.city)) return false;
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
  /* Этап E (20.10.2026): сортировки, которых ждут на площадках аренды — свежие, цена, рейтинг */
  const fresh = (x, y) => (x.days || 0) - (y.days || 0);
  if (mode === 'fresh')  return a.sort(fresh);
  if (mode === 'cheap')  return a.sort((x, y) => (Number(x.price) || 0) - (Number(y.price) || 0) || fresh(x, y));
  if (mode === 'dear')   return a.sort((x, y) => (Number(y.price) || 0) - (Number(x.price) || 0) || fresh(x, y));
  if (mode === 'rating') return a.sort((x, y) => (Number(y.reviews_count) > 0) - (Number(x.reviews_count) > 0) ||
                                                 (Number(y.rating) || 0) - (Number(x.rating) || 0) ||
                                                 (Number(y.reviews_count) || 0) - (Number(x.reviews_count) || 0) || fresh(x, y));
  /* прежние ссылки ?sort=new|good|work — выбранное состояние первым */
  // Выбранное состояние идёт первым; внутри группы — свежие объявления.
  const order = ['new', 'good', 'work'];
  if (order.indexOf(mode) !== -1) {
    order.splice(order.indexOf(mode), 1);
    order.unshift(mode);
  }
  const rank = cond => order.indexOf(cond) === -1 ? order.length : order.indexOf(cond);
  a.sort((x,y) => rank(x.cond) - rank(y.cond) || (x.days || 0) - (y.days || 0));
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
      .filter(a => a.status === 'active' && (!getCity() || cityMatches(a.city,getCity())) && a.name.toLowerCase().indexOf(q) !== -1)
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
    if (it.type === 'cat') location.href = 'catalog.html?cat=' + encodeURIComponent(it.key) + '&city=' + encodeURIComponent(getCity());
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
  '<header class="head"><div class="wrap wide head-in">' +
    '<a class="logo" href="index.html">' +
      '<img src="assets/logo.png" alt="Ива">' +
      '<span><b>Ива</b><span>Инструмент в аренду</span></span>' +
    '</a>' +
    '<button class="city" id="cityBtn">' + IC.pin +
      '<span class="city-name">' + esc(getCity() || 'Все города') + '</span>' +
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
      '<a class="hlink' + (active === 'chat' ? ' on' : '') + '" href="chat.html">' +
        IC.chat + '<span>Сообщения</span>' +
        '<span class="dot chat-count" style="display:none">0</span></a>' +
      /* Меню аккаунта (этап C сайта, 20.10.2026): у вошедшего — выпадающее меню с разделами по роли
         и переключателем «Арендую / Сдаю» у арендодателя; у гостя — просто вход. См. paintAcct */
      '<div class="acct" id="acct">' +
        '<a class="hlink' + (active === 'cab' ? ' on' : '') + '" id="acctBtn" href="account.html">' +
          '<span class="acct-ic">' + IC.user + '</span><span>Кабинет</span></a>' +
        '<div class="acct-menu hide" id="acctMenu" role="menu" aria-label="Меню аккаунта"></div>' +
      '</div>' +
      /* Пункт «Управление» дорисовывается после проверки роли — см. paintChiefLink */
      '<a class="hlink chief-link hide' + (active === 'chief' ? ' on' : '') + '" href="chief.html">' +
        IC.chief + '<span>Управление</span></a>' +
      /* 20.10.2026 (решение владельца): размещать могут только арендодатели, которых добавил владелец.
         Клиенту и гостю кнопки нет вовсе; у арендодателя она появляется после проверки роли — см. paintChiefLink */
      '<a class="btn sm lord-only' + (lordHint() && ivaMode() === 'lend' ? '' : ' hide') + '" href="new.html" style="margin-left:6px">+ Добавить</a>' +
    '</nav>' +
  '</div></header>';
}

/* Нижнее меню (телефон). Решение владельца 20.10.2026: у клиента и гостя нет кнопки «Разместить» —
   сдавать могут только арендодатели, которых добавил владелец. Клиент может только написать владельцу.
     клиент и гость: Главная · Каталог · Избранное · Сообщения · Кабинет
     арендодатель:   Главная · Заявки · (+ Добавить) · Сообщения · Кабинет */
function bnavHTML(active, lord){
  const it = (key, href, icon, label, extra) =>
    '<a class="' + (active === key ? 'on' : '') + '" href="' + href + '"' + (extra || '') + '>' +
      icon + '<span>' + label + '</span>' +
      (key === 'fav'  ? '<span class="dot fav-count" style="display:none">0</span>' : '') +
      (key === 'chat' ? '<span class="dot chat-count" style="display:none">0</span>' : '') +
    '</a>';

  if (lord) return '<nav class="bnav" data-lord="1"><div class="bnav-in">' +
    it('home','index.html',IC.home,'Главная') +
    it('orders','account.html#orders',IC.box,'Заявки') +
    '<a class="mid' + (active === 'new' ? ' on' : '') + '" href="new.html">' +
      '<span class="plus">' + IC.plus + '</span><span>Добавить</span></a>' +
    it('chat','chat.html',IC.chat,'Сообщения') +
    it('cab','account.html',IC.user,'Кабинет') +
  '</div></nav>';

  return '<nav class="bnav" data-lord="0"><div class="bnav-in">' +
    it('home','index.html',IC.home,'Главная') +
    it('catalog','catalog.html',IC.search,'Каталог') +
    it('fav','favorites.html',IC.heart,'Избранное') +
    it('chat','chat.html',IC.chat,'Сообщения') +
    it('cab','account.html',IC.user,'Кабинет') +
  '</div></nav>';
}

/* Подсказка роли до проверки сессии: чтобы у арендодателя «+ Добавить» не мигало при каждом переходе.
   Это только вид меню — права проверяет база (размещать может только активный арендодатель). */
const LORD_HINT_KEY = 'iva_lord_hint';
function lordHint(){ try { return localStorage.getItem(LORD_HINT_KEY) === '1'; } catch(e){ return false; } }
function setLordHint(on){
  try { if (on) localStorage.setItem(LORD_HINT_KEY, '1'); else localStorage.removeItem(LORD_HINT_KEY); } catch(e){}
}

/* «Написать владельцу» — единственный путь клиента к сдаче инструмента (решение владельца 20.10.2026):
   переписка с владельцем площадки; арендодателем человека делает только владелец после разговора. */
function lendChatHref(){
  const owner = (typeof IVA_CREATOR_EMAIL !== 'undefined' && IVA_CREATOR_EMAIL) || 'eclips.ru@mail.ru';
  return 'chat.html?who=' + encodeURIComponent(owner) + '&lend=1';
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
        '<a href="account.html">Личный кабинет</a>' +
        '<a href="city/index.html">Города и прокаты</a></div>' +
      '<div><b>Контакты</b>' +
        '<a href="tel:+79081732475">+7 (908) 173-24-75</a>' +
        '<a href="privacy.html">Обработка данных</a>' +
        '<span>Новочеркасск, ул. Маресьева, 36</span>' +
        '<span>Ежедневно 8:00–20:00</span>' +
        '<a href="' + lendChatHref() + '">Написать владельцу площадки</a></div>' +
    '</div>' +
    '<div class="small">© ' + new Date().getFullYear() + ' Ива — Инструмент в аренду.</div>' +
  '</div></footer>';
}

/* ---------- Шторка выбора города ---------- */
function citySheet(){
  const cur = getCity();
  openSheet('Выберите город',
    '<form class="city-search-form" id="citySearchForm"><label for="citySearch" class="small">Город или населённый пункт</label><input id="citySearch" class="inp" maxlength="100" placeholder="Начните вводить город…"><button class="btn sec sm" type="submit">Выбрать город</button><p class="geo-helper">Выберите подсказку или укажите город вручную. Улицу и дом сюда вводить не нужно.</p></form>' +
    '<div class="f-list" style="max-height:none">' +
      [''].concat(availableCities()).map(c =>
        '<div class="f-item' + (c === cur ? ' on' : '') + '" data-city="' + esc(c) + '">' +
          esc(c || 'Все города') + (c === cur ? '<span class="n">✓</span>' : '') +
        '</div>').join('') +
    '</div>', null);

  function chooseTyped(){
    const input=$('citySearch'),city=IvaGeo.city(input.value);
    if(!city){input.setCustomValidity('Укажите город на русском языке, без улицы и дома');input.reportValidity();return;}
    setCity(city,true);closeSheet();if(window.onCityChanged)window.onCityChanged();
  }
  IvaGeo.scan();
  $('citySearch').addEventListener('geo:select',chooseTyped);
  $('citySearchForm').onsubmit=function(e){e.preventDefault();chooseTyped();};
  $1('.sheet-b').addEventListener('click', function(e){
    const d = e.target.closest('[data-city]');
    if (!d) return;
    setCity(d.getAttribute('data-city'), true);
    closeSheet();
    toast(d.getAttribute('data-city') ? 'Город: ' + d.getAttribute('data-city') : 'Показаны все города');
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

/* ---------- Приложение для Android: баннер и карточки кабинета (решение владельца 05.10.2026) ----------
   Какая версия выпущена для всех — assets/app-release.js (таблица app_releases; пока пусто — старая 10.14).
   - Все пользователи: сверху на 10 секунд баннер со ссылкой на ВЫПУЩЕННУЮ версию + та же ссылка в кабинете (зелёная карточка).
   - Создатель (eclips.ru@mail.ru) на телефоне: в кабинете ДВЕ карточки — жёлтая «новая версия» с кнопками
     «Скачать» и «Подтвердить релиз» (сверху) и зелёная «сейчас у всех» (ниже). Больше ни у кого двух ссылок нет.
   - «Подтвердить релиз» добавляет запись в app_releases → с этого момента у всех новая версия.
   Скрытие, а не защита: репозиторий публичный, тестовый файл можно найти по прямой ссылке. */

function currentUserEmail(){
  // A объявлен в assets/sb.js через const — в window.A его нет, обращаемся напрямую
  try { if (typeof A !== 'undefined' && A && A.me) return String(A.me).trim().toLowerCase(); } catch(e){}
  try { const s = JSON.parse(localStorage.getItem('iva_sess') || 'null'); if (s && s.me) return String(s.me).trim().toLowerCase(); } catch(e){}
  try {
    if (typeof getUser === 'function') { const u = getUser(); if (u && u.email) return String(u.email).trim().toLowerCase(); }
  } catch(e){}
  return '';
}

function isCreator(){ return currentUserEmail() === IVA_CREATOR_EMAIL; }
function isAppTester(){ return isMobileDevice() && isCreator(); }

function isMobileDevice(){
  return /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(navigator.userAgent) || window.innerWidth <= 768;
}

function isInsideNativeApp(){
  return !!(window.IS_PROKAT_APP || window.isNativeApp || /ProkatApp|ProkatInstrumenta/i.test(navigator.userAgent) || location.search.indexOf('from_app=1') !== -1);
}

/* Зелёная карточка — выпущенная версия (её видят все). forCreator — подпись «сейчас у всех». */
function apkPublicCardHTML(rel, forCreator){
  return '<div class="apk-card apk-public">' +
    '<div><b>📱 Ива для Android · версия ' + esc(rel.versionName) + '</b>' +
      (forCreator ? '<p><b>Сейчас у всех пользователей.</b> Эту ссылку видят все — в баннере и в кабинете.</p>' : '') +
      (rel.notes ? '<p>' + esc(rel.notes) + '</p>' : '<p>Каталог, заявки и чат в приложении.</p>') +
      (rel.reinstall ? '<p>Версия подписана новым ключом: <b>сначала удалите старое приложение</b>, затем установите эту и войдите заново — данные хранятся на сервере.</p>' : '') +
    '</div>' +
    '<a class="btn" href="' + esc(rel.apk) + '" download>Скачать APK ' + esc(rel.versionName) + '</a></div>';
}

/* Карточки скачивания в кабинете. slot — пустой <div id="apkCards">. */
function mountAppCards(slot){
  if (!slot) return;
  const draw = function(rel){
    const tester = isAppTester();
    slot.innerHTML = (tester ? '<div class="apk-test-slot"><div class="apk-test"><b>🧪 Новая версия — видна только вам</b><p>Проверяем, есть ли новая сборка…</p></div></div>' : '') +
      apkPublicCardHTML(rel, tester);
    if (tester) mountTestApkCard(slot.querySelector('.apk-test-slot'), rel, function(){ ivaReleased(true).then(draw); });
  };
  draw(ivaReleasedNow());
  ivaReleased().then(draw);
}

/* Жёлтая карточка создателя: новая сборка из app-test.json + «Подтвердить релиз». */
function mountTestApkCard(slot, rel, onReleased){
  if (!slot || !isAppTester()) return;
  rel = rel || IVA_RELEASE_FALLBACK;
  fetch(IVA_TEST_FEED + '?t=' + Date.now(), { cache: 'no-store' })
    .then(function(r){ if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
    .then(function(j){
      const code = Number(j && j.versionCode) || 0;
      if (code > rel.versionCode && j.apk) {
        slot.innerHTML = '<div class="apk-test">' +
          '<div><b>🧪 Новая версия ' + esc(j.versionName || '') + ' — видна только вам</b>' +
          '<p>Остальные пока получают ' + esc(rel.versionName) + ' (зелёная карточка ниже).' +
          (j.built ? ' Собрана ' + esc(j.built) + '.' : '') +
          (j.notes ? '<br>' + esc(j.notes) : '') +
          (j.reinstall ? '<br><b>Сначала удалите установленное приложение</b> — новая сборка подписана другим ключом.' : '') +
          '<br>Проверьте на телефоне, потом нажмите «Подтвердить релиз» — и новую версию увидят все.</p></div>' +
          '<div class="apk-test-btns"><a class="btn" href="' + esc(j.apk) + '" download>Скачать ' + esc(j.versionName || '') + '</a>' +
          '<button class="btn apk-confirm" type="button" id="apkConfirm">✅ Подтвердить релиз</button></div>' +
          '<p class="apk-test-msg" id="apkConfirmMsg"></p></div>';
        const btn = slot.querySelector('#apkConfirm');
        if (btn) btn.addEventListener('click', function(){ confirmAppRelease(j, btn, slot.querySelector('#apkConfirmMsg'), onReleased); });
      } else {
        slot.innerHTML = '<div class="apk-test"><b>🧪 Новых версий нет</b>' +
          '<p>У всех сейчас ' + esc(rel.versionName) + ' (зелёная карточка ниже). Новая сборка появится здесь сама.</p></div>';
      }
    })
    .catch(function(){
      slot.innerHTML = '<div class="apk-test"><b>🧪 Новая версия</b><p>Не удалось проверить новую сборку. Обновите страницу позже.</p></div>';
    });
}

/* «Подтвердить релиз»: запись в app_releases. Права проверяет база — запись примет только у eclips.ru@mail.ru.
   В записи — только имя файла APK, а он должен уже лежать на сайте рядом с app-test.json. Если его там нет
   (сборка ещё не выложена на сайт), подтверждение отменяем: иначе у всех появилась бы битая ссылка. */
async function confirmAppRelease(feed, btn, msg, onReleased){
  const say = function(t, bad){ if (msg) { msg.textContent = t; msg.className = 'apk-test-msg' + (bad ? ' bad' : ''); } };
  if (typeof api !== 'function' || typeof A === 'undefined' || !A.tok) { say('Войдите в кабинет заново и повторите.', true); return; }
  const file = ivaApkFile(feed.apk);
  if (!file) { say('В тестовой сборке не указан файл APK — подтверждение отменено.', true); return; }
  btn.disabled = true; say('Проверяю файл на сайте…');
  let onSite = false;
  try { const h = await fetch(file, { method: 'HEAD', cache: 'no-store' }); onSite = !!h.ok; } catch(e){ onSite = false; }
  if (!onSite) { btn.disabled = false; say('Файл ' + file + ' ещё не открывается на сайте. Обычно это несколько минут после слияния PR — подождите и обновите страницу.', true); return; }
  if (!window.confirm('Выпустить версию ' + feed.versionName + ' для всех пользователей?\n\nСсылка на неё появится у всех в баннере и в кабинете.')) { btn.disabled = false; say(''); return; }
  say('Подтверждаю…');
  try {
    await api('/rest/v1/app_releases', 'POST', {
      version_code: Number(feed.versionCode), version_name: String(feed.versionName),
      apk_url: file, reinstall: !!feed.reinstall, notes: String(feed.notes || '').slice(0, 500)
    }, { Prefer: 'return=minimal' });
    say('Готово: версия ' + feed.versionName + ' выпущена для всех.');
    if (typeof toast === 'function') toast('Релиз ' + feed.versionName + ' подтверждён — теперь его видят все');
    if (onReleased) setTimeout(onReleased, 800);
  } catch(e){
    btn.disabled = false;
    const t = String(e && e.message || e);
    if (/app_releases/.test(t) && /(does not exist|schema cache|not find)/i.test(t)) say('В базе ещё нет таблицы релизов: примените SQL 20261005_app_releases.sql (текст — в чате у агента).', true);
    else if (/row-level security|permission|42501/i.test(t)) say('База не приняла: подтверждать релиз может только eclips.ru@mail.ru.', true);
    else if (/duplicate|23505/i.test(t)) { say('Эта версия уже подтверждена.'); if (onReleased) onReleased(); }
    else say('Не получилось: ' + t, true);
  }
}

/* Баннер сверху: у ВСЕХ на телефоне, 10 секунд, ссылка на выпущенную версию. */
function mountAppTopBanner(container){
  if (!isMobileDevice() || isInsideNativeApp()) return;
  if (document.getElementById('appTopBanner')) return;
  try { if (sessionStorage.getItem('prokat_app_banner_closed')) return; } catch(e){}
  ivaReleased().then(function(rel){ showAppTopBanner(container, rel); });
}

function showAppTopBanner(container, rel){
  if (document.getElementById('appTopBanner')) return;
  let oldDownload = null, downloadedRelease = null;
  try {
    oldDownload = localStorage.getItem('prokat_app_installed_ver'); // устаревший, не подтверждает установку
    downloadedRelease = localStorage.getItem('prokat_app_download_release');
  } catch(e){}
  const file = ivaApkFile(rel.apk);
  // Повторно не показываем, только если скачали ИМЕННО выпущенный файл.
  if (downloadedRelease === file) return;

  const hadApp = !!oldDownload || !!downloadedRelease;
  const titleText = hadApp ? 'Обновление · версия ' + rel.versionName : 'Ива для Android · ' + rel.versionName;
  const subText = rel.reinstall
    ? 'Новая версия на новом ключе: сначала удалите старое приложение, затем установите эту (данные — на сервере)'
    : 'Нативное приложение: каталог, заявки и чат';

  const banner = document.createElement('div');
  banner.id = 'appTopBanner';
  banner.className = 'app-top-banner';
  banner.innerHTML =
    '<div class="app-tb-wrap">' +
      '<a class="app-tb-left" href="' + esc(rel.apk) + '" download id="appTbLink">' +
        '<img class="app-tb-icon" src="assets/logo.png" alt="Ива">' +
        '<div class="app-tb-text">' +
          '<b class="app-tb-title">' + esc(titleText) + ' <span class="app-tb-star">★</span></b>' +
          '<span class="app-tb-sub">' + esc(subText) + '</span>' +
        '</div>' +
      '</a>' +
      '<div class="app-tb-right">' +
        '<a class="app-tb-btn" href="' + esc(rel.apk) + '" download id="appTbBtn">Скачать</a>' +
        '<button class="app-tb-close" id="appTbClose" aria-label="Закрыть">×</button>' +
      '</div>' +
    '</div>' +
    '<div class="app-tb-progress"><div class="app-tb-bar"></div></div>';

  if (container && container.firstChild) container.insertBefore(banner, container.firstChild);
  else if (container) container.appendChild(banner);
  else document.body.insertBefore(banner, document.body.firstChild);

  const close = banner.querySelector('#appTbClose');
  close.addEventListener('click', (e) => {
    e.preventDefault(); e.stopPropagation();
    try { sessionStorage.setItem('prokat_app_banner_closed', '1'); } catch(err){}
    dismissAppTopBanner();
  });
  if (window._appBannerTimer) clearTimeout(window._appBannerTimer);
  window._appBannerTimer = setTimeout(dismissAppTopBanner, APP_BANNER_MS);
}
const APP_BANNER_MS = 10000; // 10 секунд — решение владельца 05.10.2026

function dismissAppTopBanner(){
  const b = document.getElementById('appTopBanner');
  if (window._appBannerTimer) { clearTimeout(window._appBannerTimer); window._appBannerTimer = null; }
  if (!b) return;
  b.classList.add('closing');
  setTimeout(() => { if (b.parentNode) b.parentNode.removeChild(b); }, 400);
}

// Клик означает скачивание, НЕ успешную установку (подписи версий могут отличаться).
document.addEventListener('click', function(e){
  const a = e.target.closest ? e.target.closest('a[href*="ProkatInstrumenta-"]') : null;
  if (!a) return;
  const file = ivaApkFile(a.getAttribute('href'));
  if (!file) return;
  try { localStorage.setItem('prokat_app_download_release', file); } catch(err){}
  dismissAppTopBanner();
});

/* ---------- Инициализация общего каркаса ---------- */
let CHROME_ACTIVE = '';
function mountChrome(active, q){
  const h = $('hdr');   if (h) h.innerHTML = headerHTML(active, q);
  CHROME_ACTIVE = active;
  const b = $('bnav');  if (b) b.innerHTML = bnavHTML(active, lordHint() && ivaMode() === 'lend');
  const f = $('ftr');   if (f) f.innerHTML = footHTML();

  mountAppTopBanner(h);

  paintFavCount();
  bindFavs();
  bindSoon();
  paintChiefLink();
  paintChatBadge();

  const cb = $('cityBtn'); if (cb) cb.addEventListener('click', citySheet);

  const qi = $('q'), go = $('qGo'), clr = $('qClr'), sug = $('sug');
  if (qi){
    buildSuggest(qi, sug);
    const doSearch = () => {
      const v = qi.value.trim();
      location.href = 'catalog.html?city=' + encodeURIComponent(getCity()) + (v ? '&q=' + encodeURIComponent(v) : '');
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


/* Пункт «Управление» виден только владельцу площадки.
   «+ Добавить» (шапка и нижнее меню) — только активному арендодателю (20.10.2026). */
async function paintChiefLink(){
  if (typeof restoreSess !== 'function') return;
  try {
    if (!isAuthed()) await restoreSess();
    if (typeof isChief === 'function' && isChief()){
      $$('.chief-link').forEach(function(el){ el.classList.remove('hide'); });
    }
    paintLordChrome(isAuthed() && typeof isAdmin === 'function' && isAdmin());
    if (typeof IvaDistance !== 'undefined') IvaDistance.paintCards();
  } catch(e){}
}


function paintLordChrome(lord){
  setLordHint(lord);
  $$('.lord-only').forEach(function(el){ el.classList.toggle('hide', !lord); });
  const lendView = lord && ivaMode() === 'lend';
  $$('.lord-only').forEach(function(el){ el.classList.toggle('hide', !lendView); });
  const b = $('bnav'), nav = b && b.querySelector('.bnav');
  if (nav && (nav.getAttribute('data-lord') === '1') !== lendView){
    b.innerHTML = bnavHTML(CHROME_ACTIVE, lendView);
    paintFavCount();
    paintChatBadge();
  }
  paintAcct();
}

/* ---------- Режим арендодателя «Арендую / Сдаю» (как «Режим хозяина» у Airbnb) ----------
   Арендодатель тоже берёт инструмент в аренду. В режиме «Арендую» сайт выглядит как у клиента
   (каталог, избранное), в режиме «Сдаю» — меню арендодателя (заявки, «+ Добавить»).
   Это только вид меню: права по-прежнему проверяет база. Клиенту переключатель не показывается. */
const MODE_KEY = 'iva_mode';
function ivaMode(){ try { return localStorage.getItem(MODE_KEY) === 'rent' ? 'rent' : 'lend'; } catch(e){ return 'lend'; } }
function setIvaMode(m, go){
  try { localStorage.setItem(MODE_KEY, m === 'rent' ? 'rent' : 'lend'); } catch(e){}
  paintLordChrome(lordHint());
  toast(m === 'rent' ? 'Режим «Арендую»: каталог и избранное' : 'Режим «Сдаю»: объявления и заявки');
  if (!go) return;
  /* в кабинете режим меняет вкладки на месте (этап 2 кабинетов, 22.10.2026): «Арендую» — «Я арендую», «Сдаю» — «Сводка» */
  if (/(^|\/)account\.html$/.test(location.pathname)) { location.hash = m === 'rent' ? 'sent' : 'home'; return; }
  location.href = m === 'rent' ? 'catalog.html' : 'account.html#home';
}

/* Строка вкладок на узком экране: затухание у края, за которым есть ещё вкладки (этап 2 кабинетов) */
function ivaTabsFade(tb){
  if (!tb) return;
  const upd = function(){
    const more = tb.scrollWidth - tb.clientWidth > 4;
    tb.classList.toggle('fade-l', more && tb.scrollLeft > 4);
    tb.classList.toggle('fade-r', more && tb.scrollLeft + tb.clientWidth < tb.scrollWidth - 4);
  };
  if (!tb._ivaFade){ tb._ivaFade = true; tb.addEventListener('scroll', upd, { passive: true }); window.addEventListener('resize', upd); }
  upd();
}
function modeSwitchHTML(){
  const m = ivaMode();
  return '<div class="acct-mode" role="group" aria-label="Режим">' +
    '<button type="button" data-mode="rent" class="' + (m === 'rent' ? 'on' : '') + '" aria-pressed="' + (m === 'rent') + '">Арендую</button>' +
    '<button type="button" data-mode="lend" class="' + (m === 'lend' ? 'on' : '') + '" aria-pressed="' + (m === 'lend') + '">Сдаю</button>' +
  '</div>';
}
document.addEventListener('click', function(e){
  const mb = e.target.closest ? e.target.closest('[data-mode]') : null;
  if (!mb || mb.classList.contains('on')) return;
  e.preventDefault();
  setIvaMode(mb.getAttribute('data-mode'), true);
});

/* ---------- Меню аккаунта в шапке ---------- */
function acctMenuHTML(){
  const lord = typeof isAdmin === 'function' && isAdmin();
  const chief = typeof isChief === 'function' && isChief();
  const lend = lord && ivaMode() === 'lend';
  /* сотрудник проката — подсказка из кабинета (account.html → iva_staff_me) */
  let staff = false; try { staff = !lord && localStorage.getItem('iva_staff_hint') === '1'; } catch(e){}
  const name = (typeof A !== 'undefined' && A.uname) || 'Кабинет';
  const me = (typeof A !== 'undefined' && A.me) || '';
  const role = chief ? 'Владелец площадки' : lord ? 'Арендодатель' : staff ? 'Сотрудник проката' : 'Клиент';
  const a = (href, label, extra) => '<a role="menuitem" href="' + href + '">' + label + (extra || '') + '</a>';
  let h = '<div class="acct-head"><span class="acct-ava" data-ava="' + esc(me) + '">' + esc(initials(name)) + '</span>' +
    '<span style="min-width:0"><b>' + esc(name) + '</b><small>' + esc(me) + '</small>' +
    '<span class="acct-role">' + role + '</span></span></div>';
  if (lord) h += modeSwitchHTML();
  if (lend){
    h += a('account.html#home', 'Сводка') + a('account.html#orders', 'Заявки на мой инструмент') + a('account.html#ads', 'Мои объявления') +
         a('account.html#fee', 'Комиссия и оплата') + a('account.html#reviews', 'Отзывы') +
         a('new.html', '+ Добавить инструмент');
  } else if (!lord){
    if (staff) h += a('account.html#staff', 'Работа в прокате');
    h += a('account.html#orders', 'Мои заявки');
  } else {
    h += a('catalog.html', 'Каталог') + a('account.html#sent', 'Я арендую — мои заявки');
  }
  h += a('favorites.html', 'Избранное', '<span class="acct-n fav-count" style="display:none">0</span>') +
       a('chat.html', 'Сообщения', '<span class="acct-n chat-count" style="display:none">0</span>') +
       a(lord ? 'account.html#prof' : 'profile.html', 'Профиль и настройки');
  if (chief) h += '<div class="acct-sep"></div>' + a('chief.html', 'Управление площадкой');
  if (!lord && !staff) h += '<div class="acct-sep"></div>' + a(lendChatHref(), 'Хочу сдавать инструмент');
  h += '<div class="acct-sep"></div><button type="button" role="menuitem" class="acct-out" id="acctOut">Выйти</button>';
  return h;
}
function closeAcct(){
  const m = $('acctMenu'), b = $('acctBtn');
  if (m) m.classList.add('hide');
  if (b) b.setAttribute('aria-expanded', 'false');
}
/* Нижнее меню на телефоне: «Кабинет» — своей аватаркой, как в приложении; фото нет — прежний значок */
function paintBnavAva(authed){
  const a = document.querySelector('#bnav a[href="account.html"]');
  if (!a) return;
  if (!authed || typeof IvaAva === 'undefined'){
    const sp = a.querySelector('.bnav-ava');
    if (sp) sp.outerHTML = IC.user;
    return;
  }
  IvaAva.mine().then(function(url){
    const link = document.querySelector('#bnav a[href="account.html"]');
    if (!link || !url) return;
    let sp = link.querySelector('.bnav-ava');
    if (!sp){
      sp = document.createElement('span');
      sp.className = 'bnav-ava';
      const svg = link.querySelector('svg');
      if (svg) svg.replaceWith(sp); else link.insertBefore(sp, link.firstChild);
    }
    sp.setAttribute('data-ava', (A && A.me) || '');
    IvaAva.paint(sp);
  }).catch(function(){});
}
function paintAcct(){
  const btn = $('acctBtn'), menu = $('acctMenu');
  if (!btn || !menu) return;
  const authed = typeof isAuthed === 'function' && isAuthed();
  const ic = btn.querySelector('.acct-ic');
  paintBnavAva(authed);
  if (!authed){
    if (ic) ic.innerHTML = IC.user;
    btn.removeAttribute('aria-haspopup'); btn.removeAttribute('aria-expanded');
    btn.lastChild.textContent = 'Войти';
    menu.classList.add('hide');
    return;
  }
  btn.setAttribute('aria-haspopup', 'menu');
  if (!btn.hasAttribute('aria-expanded')) btn.setAttribute('aria-expanded', 'false');
  btn.lastChild.textContent = 'Кабинет';
  if (ic){
    /* аватарка (21.10.2026): кружок не перерисовываем, если в нём уже фото этого же аккаунта */
    const cur = ic.querySelector('.acct-ini');
    if (!cur || cur.getAttribute('data-ava') !== ((A && A.me) || '')){
      ic.innerHTML = '<span class="acct-ini" data-ava="' + esc((A && A.me) || '') + '">' + esc(initials((A && A.uname) || (A && A.me) || '?')) + '</span>';
    }
    if (typeof IvaAva !== 'undefined') IvaAva.paint(ic);
  }
  if (!menu.classList.contains('hide')){ menu.innerHTML = acctMenuHTML(); if (typeof IvaAva !== 'undefined') IvaAva.paint(menu); }
}
document.addEventListener('click', function(e){
  const btn = e.target.closest ? e.target.closest('#acctBtn') : null;
  const menu = $('acctMenu');
  if (btn && menu && btn.getAttribute('aria-haspopup') === 'menu'){
    e.preventDefault();
    if (menu.classList.contains('hide')){
      menu.innerHTML = acctMenuHTML();
      if (typeof IvaAva !== 'undefined') IvaAva.paint(menu);
      menu.classList.remove('hide');
      btn.setAttribute('aria-expanded', 'true');
      paintFavCount(); paintChatBadge();
    } else closeAcct();
    return;
  }
  if (e.target.closest && e.target.closest('#acctOut')){
    if (typeof signOut === 'function') signOut();
    setLordHint(false);
    try { localStorage.removeItem('iva_staff_hint'); } catch(err){}
    location.href = 'index.html';
    return;
  }
  if (menu && !menu.classList.contains('hide') && !(e.target.closest && e.target.closest('#acct'))) closeAcct();
});
document.addEventListener('keydown', function(e){ if (e.key === 'Escape') closeAcct(); });

/* Счётчик непрочитанных сообщений в шапке и нижнем меню */
async function paintChatBadge(){
  if (typeof unreadCount !== 'function') return;
  try {
    if (!isAuthed()) await restoreSess();
    if (!isAuthed()) return;
    const n = await unreadCount();
    $$('.chat-count').forEach(function(el){
      el.textContent = n;
      el.style.display = n ? 'flex' : 'none';
    });
  } catch(e){}
}


/* ===========================================================
   Яндекс.Метрика — подключение по одному номеру счётчика.

   Пока номер не задан (пустая строка) — ничего не грузится и ничего не собирается.
   Номер вписывается здесь одной строкой, и счётчик включается на всех страницах.

   Включать только после того, как на сайте появится политика обработки данных
   (этап П4): Метрика ставит cookie и собирает статистику посещений, о чём людей
   нужно предупредить.
   =========================================================== */
var METRIKA_ID = '';  // например '12345678' — 8 цифр из кабинета Метрики

function mountMetrika(){
  if (!METRIKA_ID) return;                       // выключено — ничего не делаем
  if (!/^[0-9]{6,10}$/.test(String(METRIKA_ID))){ // защита от опечатки
    console.warn('Метрика: номер счётчика выглядит неверно — счётчик не подключён');
    return;
  }
  if (window.__ivaMetrikaLoaded) return;
  window.__ivaMetrikaLoaded = true;
  (function(m,e,t,r,i,k,a){m[i]=m[i]||function(){(m[i].a=m[i].a||[]).push(arguments)};
    m[i].l=1*new Date(); k=e.createElement(t),a=e.getElementsByTagName(t)[0],k.async=1,k.src=r;
    a.parentNode.insertBefore(k,a)})(window,document,'script','https://mc.yandex.ru/metrika/tag.js','ym');
  window.ym(Number(METRIKA_ID), 'init', {clickmap:true, trackLinks:true, accurateTrackBounce:true});
}

if (typeof document !== 'undefined') mountMetrika();


/* ===========================================================
   Пустые состояния: что показать, когда объявлений нет.

   Смысл: пустой каталог — это тупик, из которого человек уходит. Вместо тупика
   показываем честное объяснение и живые действия: позвонить нам, посмотреть
   пункты проката в городе, разместить объявление.

   Персональные данные здесь не собираются: это тексты и ссылки, не форма.
   =========================================================== */
var _cityPagesData = null;      // уже загруженный список «город → его страница»
var _cityPagesPromise = null;   // обещание загрузки (чтобы не запрашивать дважды)

function loadCityPages(){
  if (!_cityPagesPromise){
    _cityPagesPromise = fetch('assets/city-pages.json')
      .then(function(r){ return r.ok ? r.json() : null; })
      .then(function(d){ _cityPagesData = (d && d.pages) ? d : null; return _cityPagesData; })
      .catch(function(){ _cityPagesData = null; return null; });
  }
  return _cityPagesPromise;
}

// Ссылка на страницу города: своя, если есть; иначе — общий список городов.
function ivaCityPageUrl(cityName, pages){
  var d = pages || _cityPagesData;
  if (d && d.pages && cityName && d.pages[cityName]) return d.pages[cityName];
  return (d && d.hub) ? d.hub : 'city/index.html';
}

// Разметка пустого состояния. allEmpty — объявлений нет вообще (а не «не подошли фильтры»).
function ivaEmptyStateHTML(o){
  o = o || {};
  var city = o.city || '';
  var hasFilters = !!o.hasFilters;
  var allEmpty = !!o.allEmpty;
  var pages = o.pages || null;

  var title, text;
  if (allEmpty){
    title = 'Объявлений пока нет';
    text = 'Арендодатели ещё не разместили объявления — площадка только запускается. ' +
           'Но инструмент можно найти уже сейчас:';
  } else if (city){
    title = 'В городе ' + city + ' ничего не нашлось';
    text = 'По выбранным условиям объявлений нет. Ниже — что можно сделать прямо сейчас:';
  } else {
    title = 'Ничего не нашлось';
    text = 'По выбранным условиям объявлений нет. Ниже — что можно сделать прямо сейчас:';
  }

  var dirLabel = (city && pages && pages.pages && pages.pages[city])
    ? 'Пункты проката в городе ' + city
    : 'Пункты проката по городам';
  var dirHref = ivaCityPageUrl(city, pages);

  return '<div class="empty" style="grid-column:1/-1">' +
      '<svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg>' +
      '<b>' + title + '</b>' +
      '<p>' + text + '</p>' +
      '<div style="display:flex;gap:10px;flex-wrap:wrap;justify-content:center;margin-top:6px">' +
        '<a class="btn hot" href="tel:+79081732475" data-lead="1">Позвонить — подберём инструмент</a>' +
        '<a class="btn sec" href="' + dirHref + '">' + dirLabel + '</a>' +
        (hasFilters ? '<button class="btn sec" id="eReset">Сбросить фильтры</button>' : '') +
      '</div>' +
      '<p class="small mute" style="margin-top:10px">Хотите сдавать свой инструмент? ' +
        '<a href="' + lendChatHref() + '" style="color:var(--or)">Напишите владельцу площадки</a> — ' +
        'обсудим условия и подключим вас.</p>' +
      '<p class="small mute" style="margin-top:6px">Звонок бесплатный: мы получаем плату только ' +
        'от арендодателей, которые размещают объявления.</p>' +
    '</div>';
}

/* ===========================================================
   Аватарки на сайте (21.10.2026, просьба владельца «на сайте нужно сделать аватарки»).
   Те же фото, что в приложении (10.98 / 10.109), — ничего нового не собирается:
   • свой кружок: арендодатель — admins.avatar (файл публичный, как в «Мои данные» приложения);
     клиент — приватный customer-avatars/<uid>/avatar.jpg (ссылка на 1 час; файл видит только сам клиент
     и администратор, которому клиент писал — правила SQL 10.54);
   • собеседник-арендодатель — admins.avatar; клиент у администратора — iva_chat_peers() (SQL 10.54);
   • прокат в карточке инструмента — admins.org_avatar через iva_prokat_points() (SQL 10.120), иначе фото админа.
   Нет фото, функция ещё не применена или файл не открылся — остаются буквы, как раньше.
   Разметка: <span class="ava" data-ava="почта">ИН</span> или data-ava-point / data-ava-owner, затем IvaAva.paint(узел).
   =========================================================== */
const IvaAva = (function(){
  const SS = 'iva_ava_v1', TTL_SIGNED = 50 * 60000, TTL_PUBLIC = 6 * 3600000, TTL_NONE = 2 * 60000;
  let mem = {};
  try { mem = JSON.parse(sessionStorage.getItem(SS) || '{}') || {}; } catch(e){ mem = {}; }
  const pend = {};
  let adm = null, admAt = 0, peers = null, peersFor = '';

  function low(e){ return String(e || '').trim().toLowerCase(); }
  function isUrl(u){ return /^https:\/\//.test(String(u || '').trim()); }
  function keep(k, url, ttl){
    mem[k] = { u: url || '', t: Date.now() + (url ? ttl : TTL_NONE) };
    try { sessionStorage.setItem(SS, JSON.stringify(mem)); } catch(e){}
  }
  function cached(k){ const c = mem[k]; return c && c.t > Date.now() ? c : null; }
  function drop(prefix){
    Object.keys(mem).forEach(function(k){ if (k.indexOf(prefix) === 0) delete mem[k]; });
    try { sessionStorage.setItem(SS, JSON.stringify(mem)); } catch(e){}
  }
  async function ready(){
    try { if (typeof _restoreSessionPending !== 'undefined' && _restoreSessionPending) await _restoreSessionPending; } catch(e){}
  }
  function lord(){ return typeof isAdmin === 'function' && isAdmin(); }

  /* Пакетная подпись приватных файлов клиентов: один запрос на весь список */
  async function signPaths(paths){
    const out = {};
    if (!paths.length || !A.tok) return out;
    try {
      const r = await fetch(SB + '/storage/v1/object/sign/customer-avatars', {
        method: 'POST',
        headers: { apikey: KEY, Authorization: 'Bearer ' + A.tok, 'Content-Type': 'application/json' },
        body: JSON.stringify({ expiresIn: 3600, paths: paths })
      });
      if (!r.ok) return out;
      (await r.json() || []).forEach(function(x){
        const s = x && !x.error && (x.signedURL || x.signedUrl);
        if (s) out[x.path] = s.indexOf('http') === 0 ? s : SB + '/storage/v1' + s;
      });
    } catch(e){}
    return out;
  }

  /* Фото администраторов (те же, что видит приложение в чате) */
  async function admins(){
    if (adm && Date.now() - admAt < 300000) return adm;
    const m = {};
    try {
      (await api('/rest/v1/admins?select=email,avatar') || []).forEach(function(x){
        if (x && isUrl(x.avatar)) m[low(x.email)] = String(x.avatar).trim();
      });
    } catch(e){}
    adm = m; admAt = Date.now();
    return m;
  }
  /* Администратору — пути к фото клиентов, которые ему писали */
  async function peerPaths(){
    const me = low(A.me);
    if (peers && peersFor === me) return peers;
    peers = {}; peersFor = me;
    if (!A.tok || !lord()) return peers;
    try {
      (await api('/rest/v1/rpc/iva_chat_peers', 'POST', {}) || []).forEach(function(x){
        if (x && x.avatar_path) peers[low(x.email)] = String(x.avatar_path);
      });
    } catch(e){}
    return peers;
  }

  async function mine(){
    await ready();
    const me = low(A.me);
    if (!me || !A.tok) return '';
    const c = cached('me:' + me);
    if (c) return c.u;
    if (pend['me:' + me]) return pend['me:' + me];
    return pend['me:' + me] = (async function(){
      let url = '';
      if (lord()){
        try {
          const r = await api('/rest/v1/admins?select=avatar&email=eq.' + encodeURIComponent(me));
          if (r && r[0] && isUrl(r[0].avatar)) url = String(r[0].avatar).trim();
        } catch(e){}
      }
      if (!url && typeof getCustomerAvatarUrl === 'function'){
        try { url = await getCustomerAvatarUrl(); } catch(e){}
      }
      keep('me:' + me, url, TTL_SIGNED);
      return url;
    })().finally(function(){ delete pend['me:' + me]; });
  }

  /* Заранее одним заходом: фото админов и пакетная подпись фото клиентов */
  const inflight = {};   // почта → идущий запрос (список диалогов и шапка переписки просят одновременно)
  async function prefetch(emails){
    const me = low(A.me);
    const all = emails.map(low).filter(function(e, i, a){ return e && e !== me && a.indexOf(e) === i && !cached('u:' + e); });
    const wait = all.filter(function(e){ return inflight[e]; }).map(function(e){ return inflight[e]; });
    const need = all.filter(function(e){ return !inflight[e]; });
    if (need.length){
      const job = load(need);
      need.forEach(function(e){ inflight[e] = job; });
      job.finally(function(){ need.forEach(function(e){ if (inflight[e] === job) delete inflight[e]; }); });
      wait.push(job);
    }
    try { await Promise.all(wait); } catch(e){}
  }
  async function load(need){
    const m = await admins();
    const rest = [];
    need.forEach(function(e){ if (m[e]) keep('u:' + e, m[e], TTL_PUBLIC); else rest.push(e); });
    if (!rest.length) return;
    const p = await peerPaths();
    const paths = rest.map(function(e){ return p[e]; }).filter(Boolean);
    const s = await signPaths(paths);
    rest.forEach(function(e){ keep('u:' + e, p[e] ? (s[p[e]] || '') : '', TTL_SIGNED); });
  }
  async function of(email){
    const e = low(email);
    if (!e) return '';
    if (e === low(A.me)) return mine();
    const c = cached('u:' + e);
    if (c) return c.u;
    await ready();
    await prefetch([e]);
    const c2 = cached('u:' + e);
    return c2 ? c2.u : '';
  }

  /* Прокат: логотип из «Данных проката» (org_avatar), иначе фото арендодателя */
  async function prokat(point, owner){
    const k = 'p:' + (point || '') + ':' + low(owner);
    const c = cached(k);
    if (c) return c.u;
    let url = '';
    if (point){
      try {
        const r = await api('/rest/v1/rpc/iva_prokat_points', 'POST', { p_point: point });
        const row = (r || [])[0];
        if (row && isUrl(row.avatar)) url = String(row.avatar).trim();
      } catch(e){}
    }
    if (!url && owner) url = (await admins())[low(owner)] || '';
    keep(k, url, TTL_PUBLIC);
    return url;
  }

  function put(el, url){
    if (!el || !url || el.getAttribute('data-ava-src') === url) return;
    const im = new Image();
    im.alt = ''; im.decoding = 'async';
    im.onload = function(){
      if (!el.hasAttribute('data-ini')) el.setAttribute('data-ini', el.textContent);
      el.setAttribute('data-ava-src', url);
      el.textContent = '';
      el.appendChild(im);
      el.classList.add('ava-img');
    };
    im.src = url;
  }
  function reset(el){
    if (el.hasAttribute('data-ini')) el.textContent = el.getAttribute('data-ini');
    el.removeAttribute('data-ava-src');
    el.classList.remove('ava-img');
  }

  async function paint(root){
    root = root || document;
    let els;
    try { els = Array.prototype.slice.call(root.querySelectorAll('[data-ava],[data-ava-point],[data-ava-owner],[data-ava-org]')); }
    catch(e){ return; }
    if (root.hasAttribute && (root.hasAttribute('data-ava') || root.hasAttribute('data-ava-owner'))) els.unshift(root);
    if (!els.length) return;
    try {
      await ready();
      await prefetch(els.map(function(el){ return el.getAttribute('data-ava') || ''; }));
    } catch(e){}
    els.forEach(function(el){
      let job;
      if (el.hasAttribute('data-ava-org')) job = myOrg();
      else if (el.hasAttribute('data-ava-point') || el.hasAttribute('data-ava-owner'))
        job = prokat(el.getAttribute('data-ava-point') || '', el.getAttribute('data-ava-owner') || '');
      else job = of(el.getAttribute('data-ava'));
      job.then(function(url){ if (url) put(el, url); }).catch(function(){});
    });
  }

  function repaintMine(url){
    const me = low(A.me);
    keep('me:' + me, url, TTL_SIGNED);
    document.querySelectorAll('[data-ava]').forEach(function(el){
      if (low(el.getAttribute('data-ava')) !== me) return;
      reset(el);
      if (url) put(el, url);
    });
  }

  /* ---------- Логотип проката (свой) ---------- */
  async function myOrg(){
    await ready();
    const me = low(A.me);
    if (!me || !lord()) return '';
    const c = cached('org:' + me);
    if (c) return c.u;
    let url = '';
    try {
      const r = await api('/rest/v1/admins?select=org_avatar&email=eq.' + encodeURIComponent(me));
      if (r && r[0] && isUrl(r[0].org_avatar)) url = String(r[0].org_avatar).trim();
    } catch(e){}
    keep('org:' + me, url, TTL_PUBLIC);
    return url;
  }

  /* ---------- Сохранение ---------- */
  async function uploadPublic(blob){
    const path = 'tools/avatar_' + Date.now() + '_' + Math.floor(Math.random() * 1e6) + '.jpg';
    const r = await fetch(SB + '/storage/v1/object/tool-photos/' + path, {
      method: 'POST',
      headers: { apikey: KEY, Authorization: 'Bearer ' + A.tok, 'Content-Type': 'image/jpeg' },
      body: blob
    });
    if (!r.ok) throw new Error(sbErr(await r.text()));
    return SB + '/storage/v1/object/public/tool-photos/' + path;
  }
  async function saveMine(blob){
    await ready();
    if (!A.tok) throw new Error('Нужно войти');
    if (lord()){
      const url = await uploadPublic(blob);
      await api('/rest/v1/admins?email=eq.' + encodeURIComponent(low(A.me)), 'PATCH', { avatar: url }, { Prefer: 'return=minimal' });
      adm = null;
      repaintMine(url);
      return url;
    }
    if (!A.uid) throw new Error('Нужно войти');
    const path = A.uid + '/avatar.jpg';
    const r = await fetch(SB + '/storage/v1/object/customer-avatars/' + path, {
      method: 'PUT',
      headers: { apikey: KEY, Authorization: 'Bearer ' + A.tok, 'Content-Type': 'image/jpeg', 'x-upsert': 'true' },
      body: blob
    });
    if (!r.ok) throw new Error(sbErr(await r.text()));
    const prof = A.customerProfile || {}, m = A.userMeta || {};
    /* база требует имя в записи профиля — берём уже сохранённое имя (как приложение) */
    const given = String(prof.given_name || m.given_name || m.name || m.full_name || A.uname || '').trim();
    if (!given) throw new Error('Сначала укажите имя в профиле');
    await saveCustomerProfile(Object.assign({}, prof, { given_name: prof.given_name || given, avatar_path: path }));
    const url = await getCustomerAvatarUrl();
    repaintMine(url);
    return url;
  }
  async function saveOrg(blob){
    await ready();
    if (!A.tok || !lord()) throw new Error('Только для арендодателя');
    const url = await uploadPublic(blob);
    await api('/rest/v1/admins?email=eq.' + encodeURIComponent(low(A.me)), 'PATCH', { org_avatar: url }, { Prefer: 'return=minimal' });
    drop('p:');
    keep('org:' + low(A.me), url, TTL_PUBLIC);
    document.querySelectorAll('[data-ava-org]').forEach(function(el){ reset(el); put(el, url); });
    return url;
  }

  /* ---------- Выбор фото и область кругом (как в приложении 10.98/10.109) ---------- */
  function pick(title, save){
    const inp = document.createElement('input');
    inp.type = 'file'; inp.accept = 'image/*';
    inp.onchange = function(){
      const f = inp.files && inp.files[0];
      if (!f) return;
      if (!/^image\//.test(f.type)){ toast('Выберите изображение'); return; }
      const src = URL.createObjectURL(f), img = new Image();
      img.onload = function(){ editor(img, title, save, src); };
      img.onerror = function(){ URL.revokeObjectURL(src); toast('Файл не читается — выберите другое фото'); };
      img.src = src;
    };
    inp.click();
  }
  function editor(img, title, save, src){
    const S = 560, M = 10;                 // холст и отступ круга
    let rot = 0, z = 1, ox = 0, oy = 0;
    openSheet(title || 'Фото профиля',
      '<div class="ava-crop"><canvas id="avaCv" width="' + S + '" height="' + S + '" aria-label="Область фото"></canvas></div>' +
      '<input type="range" class="ava-zoom" id="avaZoom" min="1" max="4" step="0.01" value="1" aria-label="Масштаб">' +
      '<p class="small muted" style="text-align:center;margin:0">Двигайте фото пальцем или мышью, масштаб — ползунком, колёсиком или двумя пальцами. ' +
        'В кружке — то, что увидят другие.</p>',
      '<div style="display:flex;gap:10px;width:100%"><button class="btn sec" type="button" id="avaRot">Повернуть</button>' +
      '<button class="btn" type="button" id="avaSave" style="flex:1">Сохранить</button></div>');
    const cv = $('avaCv'), ctx = cv.getContext('2d'), zoom = $('avaZoom');
    function dims(){ return rot % 2 ? [img.height, img.width] : [img.width, img.height]; }
    function scale(){ const d = dims(); return (S - 2 * M) / Math.min(d[0], d[1]) * z; }
    function clamp(){
      const d = dims(), k = scale();
      const mx = Math.max(0, (d[0] * k - (S - 2 * M)) / 2), my = Math.max(0, (d[1] * k - (S - 2 * M)) / 2);
      ox = Math.max(-mx, Math.min(mx, ox)); oy = Math.max(-my, Math.min(my, oy));
    }
    function drawImg(c){
      const k = scale();
      c.save();
      c.translate(S / 2 + ox, S / 2 + oy);
      c.rotate(rot * Math.PI / 2);
      c.drawImage(img, -img.width * k / 2, -img.height * k / 2, img.width * k, img.height * k);
      c.restore();
    }
    function draw(){
      clamp();
      ctx.fillStyle = '#000'; ctx.fillRect(0, 0, S, S);
      drawImg(ctx);
      ctx.save();
      ctx.fillStyle = 'rgba(16,18,23,.62)';
      ctx.beginPath(); ctx.rect(0, 0, S, S); ctx.arc(S / 2, S / 2, S / 2 - M, 0, Math.PI * 2, true); ctx.fill();
      ctx.strokeStyle = 'rgba(255,143,31,.9)'; ctx.lineWidth = 3;
      ctx.beginPath(); ctx.arc(S / 2, S / 2, S / 2 - M, 0, Math.PI * 2); ctx.stroke();
      ctx.restore();
    }
    function setZoom(v){ z = Math.max(1, Math.min(4, v)); zoom.value = z; draw(); }
    const pts = {};
    let pinch0 = 0, z0 = 1;
    function ratio(){ const r = cv.getBoundingClientRect(); return r.width ? S / r.width : 1; }
    cv.addEventListener('pointerdown', function(e){
      cv.setPointerCapture(e.pointerId); pts[e.pointerId] = [e.clientX, e.clientY];
      const ids = Object.keys(pts);
      if (ids.length === 2){ const a = pts[ids[0]], b = pts[ids[1]]; pinch0 = Math.hypot(a[0] - b[0], a[1] - b[1]); z0 = z; }
    });
    cv.addEventListener('pointermove', function(e){
      const p = pts[e.pointerId];
      if (!p) return;
      const ids = Object.keys(pts);
      if (ids.length === 1){
        const k = ratio();
        ox += (e.clientX - p[0]) * k; oy += (e.clientY - p[1]) * k;
        pts[e.pointerId] = [e.clientX, e.clientY];
        draw();
      } else if (ids.length === 2){
        pts[e.pointerId] = [e.clientX, e.clientY];
        const a = pts[ids[0]], b = pts[ids[1]], d = Math.hypot(a[0] - b[0], a[1] - b[1]);
        if (pinch0 > 0) setZoom(z0 * d / pinch0);
      }
    });
    function up(e){ delete pts[e.pointerId]; pinch0 = 0; }
    cv.addEventListener('pointerup', up); cv.addEventListener('pointercancel', up);
    cv.addEventListener('wheel', function(e){ e.preventDefault(); setZoom(z * (e.deltaY < 0 ? 1.08 : 1 / 1.08)); }, { passive: false });
    zoom.addEventListener('input', function(){ setZoom(Number(zoom.value) || 1); });
    $('avaRot').onclick = function(){ rot = (rot + 1) % 4; const t = ox; ox = -oy; oy = t; draw(); };
    $('avaSave').onclick = async function(){
      const b = this, rb = $('avaRot');
      b.disabled = true; rb.disabled = true; b.textContent = 'Сохраняем…';
      try {
        const out = document.createElement('canvas'), O = 512, k = O / (S - 2 * M);
        out.width = out.height = O;
        const c = out.getContext('2d');
        c.fillStyle = '#fff'; c.fillRect(0, 0, O, O);
        c.scale(k, k); c.translate(-M, -M);
        drawImg(c);
        const blob = await new Promise(function(res){ out.toBlob(res, 'image/jpeg', 0.88); });
        if (!blob) throw new Error('не удалось подготовить фото');
        await save(blob);
        try { URL.revokeObjectURL(src); } catch(e){}
        closeSheet();
        toast('Фото сохранено');
      } catch(e){
        b.disabled = false; rb.disabled = false; b.textContent = 'Сохранить';
        toast('Фото не сохранено: ' + ((e && e.message) || 'нет связи'));
      }
    };
    draw();
  }

  return { paint: paint, of: of, mine: mine, prokat: prokat, myOrg: myOrg, pick: pick,
           saveMine: saveMine, saveOrg: saveOrg, reset: reset };
})();
