/* ===========================================================
   Ива — слой работы с Supabase: авторизация, объявления, фото.
   Без сборки, ES5-совместимо. Подключается ПЕРЕД app.js.
   =========================================================== */

const SB  = 'https://wdxdeatphizclskfmfxi.supabase.co';
const KEY = 'sb_publishable_dtRaEHNNPBFbHFvg8hw9iA_FqJSz9BE';

/* Сессия пользователя */
const A = {
  tok:   null,   // access_token
  rtok:  null,   // refresh_token
  me:    '',     // email в нижнем регистре
  uname: '',     // имя
  uid: null, userMeta: {}, profileCity: '',
  admin: null    // запись из admins, если есть
};

const TOK_KEY = 'iva_sess';

function applyAuthUser(user){
  if (!user) return;
  A.uid = user.id || A.uid;
  A.me = (user.email || A.me || '').toLowerCase();
  A.userMeta = user.user_metadata || {};
  A.uname = A.userMeta.name || A.userMeta.full_name || A.uname || A.me.split('@')[0];
}
function cityFromProfileAddress(address){
  const text = String(address || '').trim();
  if (!text) return '';
  const known = typeof CITIES !== 'undefined' ? CITIES.find(c=>{
    const escaped=c.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');
    return new RegExp('(^|[^А-ЯЁа-яёA-Za-z])'+escaped+'(?=$|[^А-ЯЁа-яёA-Za-z])','i').test(text);
  }) : '';
  if (known) return known;
  const marked = text.match(/(?:^|[,;]\s*)(?:г\.\s*|город\s+)([^,;\n]+)/i);
  if (marked) return marked[1].trim();
  const first = text.split(/[,;\n]/)[0].trim();
  if (/^[А-ЯЁA-Z][А-ЯЁа-яёA-Za-z -]{1,79}$/.test(first) &&
      !/(?:улица|проспект|область|район|край|переулок|шоссе|набережная)/i.test(first)) return first;
  return '';
}
async function loadProfileCity(){
  let city = typeof A.userMeta.city === 'string' ? A.userMeta.city.trim() : '';
  if (!city && A.admin) city = cityFromProfileAddress(A.admin.address);
  if (!city && A.uid){
    try {
      const rows = await api('/rest/v1/landlord_applications?select=city&user_id=eq.' + encodeURIComponent(A.uid) + '&limit=1');
      city = rows && rows[0] && rows[0].city || '';
    } catch(e){} // Отсутствие анкеты не мешает входу и работе профиля.
  }
  A.profileCity = String(city || '').trim();
  if (typeof applyProfileCity === 'function') applyProfileCity(A.profileCity);
  return A.profileCity;
}
async function saveProfileCity(city){
  city=String(city || '').trim();
  if (city.length<2 || city.length>100) throw new Error('Укажите город от 2 до 100 символов');
  const user=await api('/auth/v1/user','PUT',{data:Object.assign({},A.userMeta,{city:city})});
  applyAuthUser(user);
  A.userMeta.city=city; A.profileCity=city;
  saveSess();
  if (typeof applyProfileCity === 'function') applyProfileCity(city,true);
}


/* ---------- Разбор ошибок PostgREST ---------- */
function sbErr(txt){
  try {
    const j = JSON.parse(txt);
    return j.message || j.error_description || j.msg || j.error || txt;
  } catch(e){ return txt; }
}

/* ---------- Базовый запрос к REST ---------- */
async function api(path, method, body, extraHeaders){
  const h = {
    apikey: KEY,
    'Content-Type': 'application/json'
  };
  if (A.tok) h.Authorization = 'Bearer ' + A.tok;
  if (extraHeaders) for (const k in extraHeaders) h[k] = extraHeaders[k];

  const opt = { method: method || 'GET', headers: h };
  if (body !== undefined && body !== null) opt.body = JSON.stringify(body);

  const r = await fetch(SB + path, opt);

  /* Токен протух — пробуем обновить один раз */
  if (r.status === 401 && A.rtok && !opt.__retry){
    const ok = await refreshToken();
    if (ok){
      opt.__retry = true;
      return api(path, method, body, extraHeaders);
    }
  }

  const txt = await r.text();
  if (!r.ok) throw new Error(sbErr(txt));
  if (!txt) return null;
  try { return JSON.parse(txt); } catch(e){ return txt; }
}

/* ---------- Сохранение сессии ---------- */
function saveSess(){
  try {
    localStorage.setItem(TOK_KEY, JSON.stringify({
      tok: A.tok, rtok: A.rtok, me: A.me, uname: A.uname
    }));
    localStorage.setItem('iva_auth',JSON.stringify({tok:A.tok,ref:A.rtok,exp:Date.now()+3600000}));
  } catch(e){}
}
function clearSess(){
  A.tok = A.rtok = null; A.me = A.uname = ''; A.admin = null;
  A.uid=null; A.userMeta={}; A.profileCity='';
  try { localStorage.removeItem(TOK_KEY); localStorage.removeItem('iva_auth'); } catch(e){}
}

/* ---------- Обновление токена ---------- */
async function refreshToken(){
  if (!A.rtok) return false;
  try {
    const r = await fetch(SB + '/auth/v1/token?grant_type=refresh_token', {
      method:'POST',
      headers:{ apikey:KEY, 'Content-Type':'application/json' },
      body: JSON.stringify({ refresh_token: A.rtok })
    });
    if (!r.ok) { clearSess(); return false; }
    const j = await r.json();
    A.tok  = j.access_token;
    A.rtok = j.refresh_token || A.rtok;
    applyAuthUser(j.user);
    saveSess();
    return true;
  } catch(e){ return false; }
}

/* ---------- Вход ---------- */
async function signIn(email, pass){
  const r = await fetch(SB + '/auth/v1/token?grant_type=password', {
    method:'POST',
    headers:{ apikey:KEY, 'Content-Type':'application/json' },
    body: JSON.stringify({ email: email, password: pass })
  });
  const txt = await r.text();
  if (!r.ok){
    const m = sbErr(txt);
    if (/invalid login/i.test(m)) throw new Error('Неверная почта или пароль');
    if (/email not confirmed/i.test(m)) throw new Error('Почта не подтверждена — проверьте письмо');
    throw new Error(m);
  }
  const j = JSON.parse(txt);
  A.tok  = j.access_token;
  A.rtok = j.refresh_token;
  applyAuthUser(j.user || {email:email});
  saveSess();
  await loadAdmin();
  return A;
}

/* ---------- Восстановление сессии при загрузке ---------- */
// Шапка и страница запрашивают одну сессию: не запускаем параллельные
// восстановления, которые могли бы перезаписать уже изменённый профиль.
let _restoreSessionPending = null;
async function restoreSess(){
  if (_restoreSessionPending) return _restoreSessionPending;
  _restoreSessionPending = restoreSessOnce();
  try { return await _restoreSessionPending; }
  finally { _restoreSessionPending = null; }
}
async function restoreSessOnce(){
  let s = null;
  try { s = JSON.parse(localStorage.getItem(TOK_KEY) || 'null'); } catch(e){}
  if (!s || !s.rtok){
    try { const legacy=JSON.parse(localStorage.getItem('iva_auth')||'null');
      if(legacy&&legacy.tok&&legacy.ref)s={tok:legacy.tok,rtok:legacy.ref};
    }catch(e){}
  }
  if (!s || !s.rtok) return false;

  A.tok = s.tok; A.rtok = s.rtok; A.me = s.me || ''; A.uname = s.uname || '';

  /* Проверяем токен; если протух — обновляем */
  try {
    const r = await fetch(SB + '/auth/v1/user', {
      headers:{ apikey:KEY, Authorization:'Bearer ' + A.tok }
    });
    if (!r.ok){
      const ok = await refreshToken();
      if (!ok) return false;
    } else {
      const u = await r.json();
      applyAuthUser(u);
    }
  } catch(e){ return false; }

  await loadAdmin();
  return true;
}

function signOut(){ clearSess(); }

/* ---------- Кто такой: админ или обычный пользователь ---------- */
async function loadAdmin(){
  if (!A.me) { A.admin = null; return null; }
  try {
    const arr = await api('/rest/v1/admins?select=email,role,active,fee_pct,debt_limit,full_name,company,phone,address,work_hours' +
                          '&email=eq.' + encodeURIComponent(A.me));
    A.admin = (arr && arr.length) ? arr[0] : null;
  } catch(e){ A.admin = null; }
  await loadProfileCity();
  return A.admin;
}
function isAdmin(){ return !!(A.admin && A.admin.active !== false); }
function isAuthed(){ return !!A.tok; }

/* ---------- Справочники ---------- */
async function loadCats(){
  const cats = await api('/rest/v1/cats?select=key,title,sort&active=eq.true&order=sort.asc.nullslast');
  const subs = await api('/rest/v1/subcats?select=key,title,cat,sort&active=eq.true&order=sort.asc.nullslast');
  return { cats: cats || [], subs: subs || [] };
}

/* ---------- Объявления ---------- */
const TOOL_FIELDS = 'id,name,cat,sub,descr,price,deposit,imgs,img,delivery,' +
                    'delivery_price,terms,owner_email,status,active,created_at';

/* Публичная витрина — только активные */
async function loadPublicTools(){
  return await api('/rest/v1/tools?select=' + TOOL_FIELDS +
                   '&status=eq.active&active=eq.true&order=created_at.desc&limit=500') || [];
}

/* Мои объявления — все статусы */
async function loadMyTools(){
  if (!A.me) return [];
  return await api('/rest/v1/tools?select=' + TOOL_FIELDS +
                   '&owner_email=eq.' + encodeURIComponent(A.me) +
                   '&order=created_at.desc&limit=500') || [];
}

async function loadTool(id){
  const a = await api('/rest/v1/tools?select=' + TOOL_FIELDS + '&id=eq.' + encodeURIComponent(id));
  return (a && a[0]) || null;
}

/* Создание. owner_email ставим свой — RLS иначе не пропустит */
async function createTool(data){
  const row = {
    name:           data.name,
    cat:            data.cat,
    sub:            data.sub || '',
    descr:          data.descr || '',
    price:          Number(data.price) || 0,
    deposit:        Number(data.deposit) || 0,
    imgs:           data.imgs || [],
    img:            (data.imgs && data.imgs[0]) || '',
    delivery:       !!data.delivery,
    delivery_price: data.delivery ? (Number(data.delivery_price) || 0) : 0,
    terms:          data.terms || '',
    owner_email:    A.me,
    status:         data.status || 'active',
    active:         (data.status || 'active') === 'active'
  };
  const r = await api('/rest/v1/tools', 'POST', row, { Prefer: 'return=representation' });
  return (r && r[0]) || null;
}

async function updateTool(id, patch){
  if (patch.status){ patch.active = patch.status === 'active'; }
  if (patch.imgs)  { patch.img = patch.imgs[0] || ''; }
  const r = await api('/rest/v1/tools?id=eq.' + encodeURIComponent(id), 'PATCH', patch,
                      { Prefer: 'return=representation' });
  return (r && r[0]) || null;
}

async function deleteTool(id){
  await api('/rest/v1/tools?id=eq.' + encodeURIComponent(id), 'DELETE', null,
            { Prefer: 'return=minimal' });
  return true;
}

/* ---------- Загрузка фото ----------
   Сжимаем до 1280 px и JPEG 82% — как в рабочем кабинете. */
async function uploadPhoto(file){
  if (!/^image\//.test(file.type)) throw new Error('Это не изображение');
  if (!A.tok) throw new Error('Нужно войти');

  const img = await new Promise(function(res, rej){
    const i = new Image();
    i.onload  = function(){ res(i); };
    i.onerror = function(){ rej(new Error('файл не читается')); };
    i.src = URL.createObjectURL(file);
  });

  const mx = 1280, sc = Math.min(1, mx / Math.max(img.width, img.height));
  const cv = document.createElement('canvas');
  cv.width  = Math.round(img.width  * sc);
  cv.height = Math.round(img.height * sc);
  cv.getContext('2d').drawImage(img, 0, 0, cv.width, cv.height);

  const blob = await new Promise(function(res){ cv.toBlob(res, 'image/jpeg', 0.82); });
  if (!blob) throw new Error('не удалось обработать изображение');

  const path = 'tools/' + Date.now() + '_' + Math.floor(Math.random() * 1e6) + '.jpg';
  const r = await fetch(SB + '/storage/v1/object/tool-photos/' + path, {
    method:'POST',
    headers:{ apikey:KEY, Authorization:'Bearer ' + A.tok, 'Content-Type':'image/jpeg' },
    body: blob
  });
  if (!r.ok) throw new Error(sbErr(await r.text()));

  return SB + '/storage/v1/object/public/tool-photos/' + path;
}

/* ---------- Статусы ---------- */
const STATUSES = {
  active:     { title:'Активно',      cls:'act' },
  moderation: { title:'На модерации', cls:'mod' },
  archived:   { title:'Снято',        cls:'off' }
};
function statusTitle(s){ return (STATUSES[s] || STATUSES.active).title; }
function statusCls(s){   return (STATUSES[s] || STATUSES.active).cls; }

/* ===========================================================
   Мост «база -> витрина»: приводим запись tools к формату,
   который понимают карточки из data.js / app.js.
   =========================================================== */

/* Владелец берётся из admins; подписи кэшируем */
let OWNER_CACHE = {};

function toolToAd(t){
  const imgs = (t.imgs && t.imgs.length) ? t.imgs : (t.img ? [t.img] : []);
  const oe   = (t.owner_email || '').toLowerCase();
  const o    = OWNER_CACHE[oe];

  /* Состояние и адрес лежат в terms — вытаскиваем для карточки */
  const terms = t.terms || '';
  let cond = 'good';
  if (/Состояние:\s*Новый/i.test(terms)) cond = 'new';
  else if (/Состояние:\s*Рабочее/i.test(terms)) cond = 'work';
  const mAddr = terms.match(/Адрес выдачи:\s*(.+)/);
  const city  = mAddr ? (mAddr[1].split(',')[0].trim() || 'Новочеркасск') : 'Новочеркасск';

  return {
    id:        t.id,
    name:      t.name,
    cat:       t.cat,
    sub:       t.sub || '',
    price:     Number(t.price) || 0,
    deposit:   Number(t.deposit) || 0,
    descr:     t.descr || '',
    owner:     oe,
    ownerObj:  {
      id: oe,
      name: (o && (o.full_name || o.company)) || (oe ? oe.split('@')[0] : 'Арендодатель'),
      rating: 4.9,
      deals: 0,
      since: 'сентябрь 2026',
      phone: (o && o.phone) || '+7 (908) 173-24-75',
      verified: true
    },
    city:      city,
    cond:      cond,
    delivery:  !!t.delivery,
    photos:    Math.max(imgs.length, 1),
    photoList: imgs,
    status:    t.status || 'active',
    days:      daysSince(t.created_at),
    specs:     buildSpecs(t),
    terms:     terms
  };
}

function daysSince(iso){
  if (!iso) return 0;
  const d = Math.floor((Date.now() - new Date(iso).getTime()) / 86400000);
  return d < 0 ? 0 : d;
}

function buildSpecs(t){
  const s = {};
  if (t.delivery) s['Доставка'] = 'Есть' + (t.delivery_price ? ', ' + t.delivery_price + ' ₽/км' : '');
  else s['Доставка'] = 'Только самовывоз';
  s['Минимальный срок'] = '1 сутки';
  return s;
}

/* Подгружаем подписи арендодателей одним запросом */
async function loadOwners(){
  try {
    const a = await api('/rest/v1/admins?select=email,full_name,company,phone');
    OWNER_CACHE = {};
    (a || []).forEach(function(x){ OWNER_CACHE[(x.email||'').toLowerCase()] = x; });
  } catch(e){ OWNER_CACHE = {}; }
}

/* Главная точка: наполняем витрину живыми данными.
   Если база недоступна — остаются демо-объявления из data.js. */
async function hydrateShowcase(){
  try {
    await loadOwners();
    const d = await loadCats();
    if (d.cats.length) CATS = d.cats.map(function(c, i){
      return { key:c.key, title:c.title, sort:c.sort != null ? c.sort : i };
    });
    if (d.subs.length) SUBS = d.subs.map(function(s){
      return { cat:s.cat, key:s.key, title:s.title };
    });

    const tools = await loadPublicTools();
    ADS = tools.map(toolToAd);
    return { ok:true, count:ADS.length };
  } catch(e){
    return { ok:false, error:e.message };
  }
}

/* ===========================================================
   ГЛАВНЫЙ АДМИН (chief): деньги, заявки, админы, модерация.
   Доступ ограничен на уровне RLS — политики fn_is_chief().
   =========================================================== */

function isChief(){ return !!(A.admin && A.admin.role === 'chief'); }

/* ---------- Заявки ---------- */
async function loadOrders(limit){
  return await api('/rest/v1/orders?select=*&order=id.desc&limit=' + (limit || 200)) || [];
}
async function loadParts(){
  return await api('/rest/v1/order_parts?select=*&order=id.desc&limit=500') || [];
}
async function updateOrderPart(id, patch){
  const r = await api('/rest/v1/order_parts?id=eq.' + id, 'PATCH', patch,
                      { Prefer:'return=representation' });
  return (r && r[0]) || null;
}

/* ---------- Админы ---------- */
async function loadAdmins(){
  return await api('/rest/v1/admins?select=*&order=role.asc') || [];
}
async function saveAdmin(email, patch){
  const r = await api('/rest/v1/admins?email=eq.' + encodeURIComponent(email), 'PATCH', patch,
                      { Prefer:'return=representation' });
  return (r && r[0]) || null;
}
async function addAdmin(row){
  const r = await api('/rest/v1/admins', 'POST', row, { Prefer:'return=representation' });
  return (r && r[0]) || null;
}
async function removeAdmin(email){
  await api('/rest/v1/admins?email=eq.' + encodeURIComponent(email), 'DELETE', null,
            { Prefer:'return=minimal' });
  return true;
}

/* ---------- Выплаты комиссии ---------- */
async function loadFees(){
  return await api('/rest/v1/fee_payments?select=*&order=id.desc&limit=300') || [];
}
async function addFee(row){
  const r = await api('/rest/v1/fee_payments', 'POST', row, { Prefer:'return=representation' });
  return (r && r[0]) || null;
}
async function confirmFee(id){
  const r = await api('/rest/v1/fee_payments?id=eq.' + id, 'PATCH',
    { status:'confirmed', confirmed_at:new Date().toISOString(), confirmed_by:A.me },
    { Prefer:'return=representation' });
  return (r && r[0]) || null;
}

/* ---------- Все объявления (для модерации) ---------- */
async function loadAllTools(){
  return await api('/rest/v1/tools?select=' + TOOL_FIELDS + '&order=created_at.desc&limit=500') || [];
}

/* ---------- Категории ---------- */
async function loadAllCats(){
  const cats = await api('/rest/v1/cats?select=*&order=sort.asc.nullslast') || [];
  const subs = await api('/rest/v1/subcats?select=*&order=sort.asc.nullslast') || [];
  return { cats: cats, subs: subs };
}
async function saveCat(key, patch){
  const r = await api('/rest/v1/cats?key=eq.' + encodeURIComponent(key), 'PATCH', patch,
                      { Prefer:'return=representation' });
  return (r && r[0]) || null;
}
async function addCat(row){
  const r = await api('/rest/v1/cats', 'POST', row, { Prefer:'return=representation' });
  return (r && r[0]) || null;
}
async function addSubcat(row){
  const r = await api('/rest/v1/subcats', 'POST', row, { Prefer:'return=representation' });
  return (r && r[0]) || null;
}
async function removeSubcat(key){
  await api('/rest/v1/subcats?key=eq.' + encodeURIComponent(key), 'DELETE', null,
            { Prefer:'return=minimal' });
  return true;
}

/* ---------- Подсчёт денег ---------- */
function moneyStats(parts, fees){
  const st = { rent:0, fee:0, paid:0, owed:0, byAdmin:{} };

  (parts || []).forEach(function(p){
    const rent = Number(p.rent_sum) || 0;
    const fee  = Number(p.fee) || 0;
    const em   = (p.owner_email || '').toLowerCase();
    st.rent += rent;
    st.fee  += fee;
    if (!st.byAdmin[em]) st.byAdmin[em] = { rent:0, fee:0, paid:0, orders:0 };
    st.byAdmin[em].rent += rent;
    st.byAdmin[em].fee  += fee;
    st.byAdmin[em].orders++;
  });

  (fees || []).forEach(function(f){
    if (f.status !== 'confirmed') return;
    const em = (f.admin_email || '').toLowerCase();
    const a  = Number(f.amount) || 0;
    st.paid += a;
    if (!st.byAdmin[em]) st.byAdmin[em] = { rent:0, fee:0, paid:0, orders:0 };
    st.byAdmin[em].paid += a;
  });

  st.owed = st.fee - st.paid;
  for (const k in st.byAdmin) st.byAdmin[k].debt = st.byAdmin[k].fee - st.byAdmin[k].paid;
  return st;
}

/* ===========================================================
   АВТОМОДЕРАЦИЯ.
   Настоящее распознавание предмета на фото требует платного
   vision-сервиса. Здесь — проверки, которые реально выполнимы
   в браузере: есть ли фото, не пустая ли картинка, вменяемы ли
   цена и текст. Всё, что не прошло, уходит главному админу.
   =========================================================== */

/* Анализ картинки: размер, «живость» кадра (разброс цветов и детали) */
async function analyzePhoto(url){
  return new Promise(function(resolve){
    const img = new Image();
    img.crossOrigin = 'anonymous';
    img.onerror = function(){ resolve({ ok:false, reason:'фото не открывается' }); };
    img.onload = function(){
      try {
        if (img.width < 300 || img.height < 300){
          resolve({ ok:false, reason:'фото слишком мелкое (' + img.width + '×' + img.height + ')' });
          return;
        }
        const N = 64;
        const cv = document.createElement('canvas');
        cv.width = cv.height = N;
        const cx = cv.getContext('2d');
        cx.drawImage(img, 0, 0, N, N);
        const d = cx.getImageData(0, 0, N, N).data;

        /* Разброс яркости: однотонная заливка = не фото товара */
        let sum = 0, sum2 = 0, n = N * N;
        const lum = new Float32Array(n);
        for (let i = 0; i < n; i++){
          const L = 0.299*d[i*4] + 0.587*d[i*4+1] + 0.114*d[i*4+2];
          lum[i] = L; sum += L; sum2 += L*L;
        }
        const mean = sum / n;
        const sd = Math.sqrt(Math.max(0, sum2/n - mean*mean));

        /* Детализация: средний перепад между соседними пикселями */
        let edge = 0, cnt = 0;
        for (let y = 1; y < N-1; y++){
          for (let x = 1; x < N-1; x++){
            const i = y*N + x;
            edge += Math.abs(lum[i] - lum[i+1]) + Math.abs(lum[i] - lum[i+N]);
            cnt += 2;
          }
        }
        edge = edge / cnt;

        if (sd < 12)   { resolve({ ok:false, reason:'фото почти однотонное', sd:sd, edge:edge }); return; }
        if (edge < 2.5){ resolve({ ok:false, reason:'на фото не видно предмета', sd:sd, edge:edge }); return; }
        if (mean < 18) { resolve({ ok:false, reason:'фото слишком тёмное', sd:sd, edge:edge }); return; }
        if (mean > 240){ resolve({ ok:false, reason:'фото засвечено', sd:sd, edge:edge }); return; }

        resolve({ ok:true, sd:sd, edge:edge, w:img.width, h:img.height });
      } catch(e){
        /* CORS или иная помеха — не наказываем объявление, отправляем к человеку */
        resolve({ ok:false, reason:'не удалось проверить фото' });
      }
    };
    img.src = url;
  });
}

const BAD_WORDS = ['продам','продажа','куплю','обмен','скам','казино','ставки','кредит'];

/* Главная проверка: вернуть {status, checks[]} */
async function autoModerate(ad){
  const checks = [];
  const add = function(ok, label, note){ checks.push({ ok:ok, label:label, note:note || '' }); };

  /* 1. Фото */
  const imgs = ad.imgs || [];
  if (!imgs.length){
    add(false, 'Фотография', 'нет ни одного фото');
  } else {
    const r = await analyzePhoto(imgs[0]);
    add(r.ok, 'На фото виден инструмент', r.ok ? '' : r.reason);
  }

  /* 2. Название */
  const nm = (ad.name || '').trim();
  add(nm.length >= 5 && nm.length <= 90 && /[а-яёa-z]/i.test(nm),
      'Название', nm.length < 5 ? 'слишком короткое' : '');

  /* 3. Описание */
  const ds = (ad.descr || '').trim();
  add(ds.length >= 20, 'Описание', ds.length < 20 ? 'короче 20 символов' : '');

  /* 4. Цена */
  const pr = Number(ad.price) || 0;
  add(pr > 0 && pr <= 100000, 'Цена', pr <= 0 ? 'не указана' : (pr > 100000 ? 'подозрительно высокая' : ''));

  /* 5. Залог */
  const dep = Number(ad.deposit) || 0;
  add(dep >= 0 && dep <= pr * 50, 'Залог', dep > pr * 50 ? 'несоразмерен цене' : '');

  /* 6. Текст без посторонних предложений и контактов */
  const all = (nm + ' ' + ds).toLowerCase();
  const bad = BAD_WORDS.filter(function(w){ return all.indexOf(w) !== -1; });
  const hasPhone = /(\+7|8)[\s\-(]*\d{3}[\s\-)]*\d{3}[\s\-]*\d{2}[\s\-]*\d{2}/.test(all);
  add(!bad.length && !hasPhone, 'Текст объявления',
      bad.length ? 'слова не по теме: ' + bad.join(', ') : (hasPhone ? 'телефон в тексте' : ''));

  const failed = checks.filter(function(c){ return !c.ok; });
  return {
    status: failed.length ? 'moderation' : 'active',
    checks: checks,
    failed: failed
  };
}

/* ===========================================================
   ЧАТ: диалоги, сообщения, вложения, realtime.
   Таблица app_messages, RLS: видно только свою переписку.
   =========================================================== */

const MSG_FIELDS = 'id,from_email,to_email,text,img,audio,audio_dur,is_read,created_at,tool_id';

/* Все сообщения, где я отправитель или получатель */
async function loadMessages(limit){
  if (!A.me) return [];
  const me = encodeURIComponent(A.me);
  return await api('/rest/v1/app_messages?select=' + MSG_FIELDS +
    '&or=(from_email.eq.' + me + ',to_email.eq.' + me + ')' +
    '&order=created_at.desc&limit=' + (limit || 400)) || [];
}

/* Переписка с конкретным собеседником, по возрастанию времени */
async function loadThread(who){
  if (!A.me || !who) return [];
  const me = encodeURIComponent(A.me), w = encodeURIComponent(who);
  const r = await api('/rest/v1/app_messages?select=' + MSG_FIELDS +
    '&or=(and(from_email.eq.' + me + ',to_email.eq.' + w + '),' +
        'and(from_email.eq.' + w + ',to_email.eq.' + me + '))' +
    '&order=created_at.asc&limit=500') || [];
  return r;
}

async function sendMessage(to, payload){
  const row = {
    from_email: A.me,
    to_email:   to,
    text:       payload.text || '',
    img:        payload.img || '',
    audio:      payload.audio || '',
    audio_dur:  payload.audio_dur || 0
  };
  if (payload.tool_id) row.tool_id = payload.tool_id;
  const r = await api('/rest/v1/app_messages', 'POST', row, { Prefer:'return=representation' });
  return (r && r[0]) || null;
}

/* Пометить прочитанными всё от собеседника */
async function markRead(who){
  if (!A.me || !who) return;
  try {
    await api('/rest/v1/app_messages?to_email=eq.' + encodeURIComponent(A.me) +
              '&from_email=eq.' + encodeURIComponent(who) + '&is_read=eq.false',
              'PATCH', { is_read:true }, { Prefer:'return=minimal' });
  } catch(e){}
}

async function unreadCount(){
  if (!A.me) return 0;
  try {
    const r = await api('/rest/v1/app_messages?select=id&to_email=eq.' +
                        encodeURIComponent(A.me) + '&is_read=eq.false&limit=200');
    return (r || []).length;
  } catch(e){ return 0; }
}

/* Сборка списка диалогов из плоского списка сообщений */
function buildDialogs(msgs){
  const map = {};
  (msgs || []).forEach(function(m){
    const mine = (m.from_email || '').toLowerCase() === A.me;
    const who  = (mine ? m.to_email : m.from_email || '').toLowerCase();
    if (!who || who === A.me) return;            /* заметки самому себе пропускаем */
    if (!map[who]){
      map[who] = { who:who, last:m, unread:0, count:0 };
    }
    map[who].count++;
    if (new Date(m.created_at) > new Date(map[who].last.created_at)) map[who].last = m;
    if (!mine && !m.is_read) map[who].unread++;
  });
  return Object.keys(map).map(function(k){ return map[k]; })
    .sort(function(a,b){ return new Date(b.last.created_at) - new Date(a.last.created_at); });
}

/* Короткое описание последнего сообщения для списка диалогов */
function msgPreview(m){
  if (m.audio) return '🎤 Голосовое сообщение';
  if (m.img)   return '📷 Фотография';
  return m.text || '';
}

/* ---------- Загрузка голосового ---------- */
async function uploadVoice(blob, ext){
  if (!A.tok) throw new Error('Нужно войти');
  const path = 'v' + Date.now() + '-' + Math.floor(Math.random()*1e5) + '.' + (ext || 'webm');
  const r = await fetch(SB + '/storage/v1/object/voice/' + path, {
    method:'POST',
    headers:{ apikey:KEY, Authorization:'Bearer ' + A.tok, 'Content-Type': blob.type || 'audio/webm' },
    body: blob
  });
  if (!r.ok) throw new Error(sbErr(await r.text()));
  return SB + '/storage/v1/object/public/voice/' + path;
}

/* ---------- Realtime ----------
   Подписка на новые сообщения через websocket. Если не выйдет —
   вызывающий код продолжит работать на опросе. */
function subscribeMessages(onInsert){
  if (!A.tok) return null;
  let ws = null, ref = 0, hb = null, closed = false;

  try {
    ws = new WebSocket(SB.replace('https://','wss://') +
                       '/realtime/v1/websocket?apikey=' + KEY + '&vsn=1.0.0');
  } catch(e){ return null; }

  ws.onopen = function(){
    ws.send(JSON.stringify({
      topic:'realtime:public:app_messages',
      event:'phx_join',
      payload:{ config:{ postgres_changes:[
        { event:'INSERT', schema:'public', table:'app_messages' }
      ]}, access_token: A.tok },
      ref: String(++ref)
    }));
    hb = setInterval(function(){
      if (ws.readyState === 1){
        ws.send(JSON.stringify({ topic:'phoenix', event:'heartbeat', payload:{}, ref:String(++ref) }));
      }
    }, 25000);
  };

  ws.onmessage = function(ev){
    try {
      const d = JSON.parse(ev.data);
      if (d.event === 'postgres_changes' && d.payload && d.payload.data &&
          d.payload.data.type === 'INSERT'){
        onInsert(d.payload.data.record);
      }
    } catch(e){}
  };

  ws.onclose = function(){ if (hb) clearInterval(hb); };
  ws.onerror = function(){ if (hb) clearInterval(hb); };

  return {
    close: function(){
      closed = true;
      if (hb) clearInterval(hb);
      try { ws.close(); } catch(e){}
    }
  };
}

/* Подпись собеседника: имя из admins, иначе часть почты */
function whoTitle(email){
  const o = OWNER_CACHE[(email || '').toLowerCase()];
  if (o && (o.full_name || o.company)) return o.full_name || o.company;
  return (email || '').split('@')[0];
}
