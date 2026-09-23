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
  admin: null    // запись из admins, если есть
};

const TOK_KEY = 'iva_sess';

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
  } catch(e){}
}
function clearSess(){
  A.tok = A.rtok = null; A.me = A.uname = ''; A.admin = null;
  try { localStorage.removeItem(TOK_KEY); } catch(e){}
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
  A.me   = ((j.user && j.user.email) || email).toLowerCase();
  const md = (j.user && j.user.user_metadata) || {};
  A.uname = md.name || md.full_name || A.me.split('@')[0];
  saveSess();
  await loadAdmin();
  return A;
}

/* ---------- Восстановление сессии при загрузке ---------- */
async function restoreSess(){
  let s = null;
  try { s = JSON.parse(localStorage.getItem(TOK_KEY) || 'null'); } catch(e){}
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
      A.me = (u.email || A.me).toLowerCase();
      const md = u.user_metadata || {};
      A.uname = md.name || md.full_name || A.uname || A.me.split('@')[0];
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
    const arr = await api('/rest/v1/admins?select=email,role,active,fee_pct,full_name,company,phone' +
                          '&email=eq.' + encodeURIComponent(A.me));
    A.admin = (arr && arr.length) ? arr[0] : null;
  } catch(e){ A.admin = null; }
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
