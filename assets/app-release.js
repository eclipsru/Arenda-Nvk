/* ===========================================================
   Ива — какая версия Android-приложения выпущена для всех (решение владельца 05.10.2026).
   Без сборки, ES5. Подключается ПЕРЕД app.js на всех страницах, а также на app.html и admin.html.

   Как работает:
   - «Выпущенная» версия = последняя запись в таблице Supabase public.app_releases.
     Запись добавляет ТОЛЬКО создатель (eclips.ru@mail.ru) кнопкой «Подтвердить релиз»
     на жёлтой карточке в кабинете (права проверяет сама база, политика RLS).
   - Пока записи нет или база недоступна — у всех старая версия IVA_RELEASE_FALLBACK.
   - Все ссылки вида <a data-app-dl="Скачать {v}"> получают адрес и номер выпущенной версии.
   =========================================================== */

var IVA_CREATOR_EMAIL = 'eclips.ru@mail.ru';
// Старая (проверенная) версия — её видят все, пока создатель не подтвердил новую.
var IVA_RELEASE_FALLBACK = { versionName: '10.14', versionCode: 88, apk: 'ProkatInstrumenta-10.14.apk', reinstall: false, notes: '' };
// Сведения о новой (тестовой) сборке: пишет «Сборка приложения» (build-apk.yml). При смене ветки агента — поменять.
var IVA_TEST_FEED = 'https://raw.githubusercontent.com/eclipsru/Arenda-Nvk/arena/7ef9c334-arenda-nvk/app-test.json';
var IVA_RELEASE_SB = 'https://wdxdeatphizclskfmfxi.supabase.co';
var IVA_RELEASE_KEY = 'sb_publishable_dtRaEHNNPBFbHFvg8hw9iA_FqJSz9BE';
var IVA_RELEASE_CACHE = 'iva_app_release_v1';

var _ivaReleasePromise = null;

function ivaReleaseFromRow(row){
  if (!row || !row.apk_url || !row.version_code) return null;
  return { versionName: String(row.version_name || ''), versionCode: Number(row.version_code) || 0,
           apk: String(row.apk_url), reinstall: !!row.reinstall, notes: String(row.notes || '') };
}

// Синхронно: последняя известная выпущенная версия (из кэша вкладки) или старая.
function ivaReleasedNow(){
  try {
    var c = JSON.parse(sessionStorage.getItem(IVA_RELEASE_CACHE) || 'null');
    if (c && c.rel && c.rel.apk && (Date.now() - c.t) < 120000) return c.rel;
  } catch(e){}
  return IVA_RELEASE_FALLBACK;
}

// Promise с выпущенной версией. force=true — не брать из кэша (после подтверждения релиза).
function ivaReleased(force){
  if (_ivaReleasePromise && !force) return _ivaReleasePromise;
  if (!force) {
    try {
      var c = JSON.parse(sessionStorage.getItem(IVA_RELEASE_CACHE) || 'null');
      if (c && c.rel && c.rel.apk && (Date.now() - c.t) < 120000) return (_ivaReleasePromise = Promise.resolve(c.rel));
    } catch(e){}
  }
  _ivaReleasePromise = fetch(IVA_RELEASE_SB + '/rest/v1/app_releases?select=version_code,version_name,apk_url,reinstall,notes&order=version_code.desc&limit=1',
      { headers: { apikey: IVA_RELEASE_KEY }, cache: 'no-store' })
    .then(function(r){ if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
    .then(function(rows){
      var rel = ivaReleaseFromRow(rows && rows[0]);
      if (!rel || rel.versionCode < IVA_RELEASE_FALLBACK.versionCode) rel = IVA_RELEASE_FALLBACK;
      try { sessionStorage.setItem(IVA_RELEASE_CACHE, JSON.stringify({ t: Date.now(), rel: rel })); } catch(e){}
      return rel;
    })
    .catch(function(){ return IVA_RELEASE_FALLBACK; });
  return _ivaReleasePromise;
}

function ivaApkFile(url){ var m = String(url || '').match(/(ProkatInstrumenta-[\d.]+\.apk)$/); return m ? m[1] : ''; }

// Подставить выпущенную версию во все ссылки «Скачать».
function ivaPaintReleaseLinks(root, rel){
  var paint = function(r){
    var scope = root || document;
    var links = scope.querySelectorAll ? scope.querySelectorAll('[data-app-dl]') : [];
    for (var i = 0; i < links.length; i++) {
      var a = links[i];
      a.setAttribute('href', r.apk);
      var tpl = a.getAttribute('data-app-dl');
      if (tpl) a.textContent = tpl.replace('{v}', r.versionName);
    }
    var vers = scope.querySelectorAll ? scope.querySelectorAll('[data-app-ver]') : [];
    for (var j = 0; j < vers.length; j++) vers[j].textContent = r.versionName;
    return r;
  };
  if (rel) return paint(rel);
  paint(ivaReleasedNow());
  return ivaReleased().then(paint);
}

if (typeof document !== 'undefined') {
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', function(){ ivaPaintReleaseLinks(); });
  else ivaPaintReleaseLinks();
}
