/* ===========================================================
   Ива — настройки профиля клиента: отдельная страница profile.html.
   Здесь только личные данные обычного пользователя (арендатора):
   имя, телефон, город профиля, вход/выход и карточки приложения.
   Сведения о прокате арендодателя живут в кабинете (account.html#prof)
   и сюда не копируются: комиссия, лимит, роль и допуск по-прежнему
   читаются из базы только для просмотра.
   Новые персональные данные страница не собирает: пишется только имя,
   телефон и город в метаданные Auth. Остальные поля метаданных
   (город, отметки площадки) сохраняются как есть — иначе они потеряются.
   =========================================================== */
const IvaClientProfile = (function(){
  const MAX_NAME = 150;   // как в старом редакторе кабинета
  const MAX_PHONE = 100;  // как в старом редакторе кабинета
  const MAX_CITY = 100;

  function meta(){ return (A && A.userMeta) ? A.userMeta : {}; }
  function displayName(){
    const m = meta();
    return A.uname || m.full_name || m.name || A.me || '';
  }
  function roleText(){
    if (typeof isAdmin !== 'function' || !isAdmin()) return 'Арендатор';
    return 'Арендодатель' + (A.admin && A.admin.role === 'chief' ? ' (главный)' : '');
  }
  function value(v){
    const s = String(v == null ? '' : v).trim();
    return s ? esc(s) : '<span class="muted">Не указано</span>';
  }
  function row(key, v){
    return '<div class="pf-row"><span class="k">' + esc(key) + '</span><span class="v">' + value(v) + '</span></div>';
  }

  /* ---------- Личные данные ---------- */

  /* Метаданные Auth собираются поверх уже существующих: город и другие
     поля пользователя не пропадают при сохранении имени и телефона. */
  function personalPayload(source, name, phone){
    const data = Object.assign({}, source || {});
    data.name = name;
    data.full_name = name;
    data.phone = phone;
    return data;
  }

  async function savePersonalData(name, phone){
    const clean = String(name == null ? '' : name).trim();
    const tel = String(phone == null ? '' : phone).trim();
    if (!clean) throw new Error('Укажите имя');
    if (clean.length > MAX_NAME) throw new Error('Имя длиннее ' + MAX_NAME + ' символов');
    if (tel.length > MAX_PHONE) throw new Error('Телефон длиннее ' + MAX_PHONE + ' символов');
    const user = await api('/auth/v1/user', 'PUT', { data: personalPayload(meta(), clean, tel) });
    if (user) applyAuthUser(user);
    A.uname = clean;
    A.userMeta = personalPayload(meta(), clean, tel);
    if (typeof saveSess === 'function') saveSess();
    return A.userMeta;
  }

  /* Город профиля сохраняется тем же способом, что и в кабинете:
     отдельным полем метаданных, не затрагивая остальные. */
  async function saveCityData(city){
    const clean = String(city == null ? '' : city).trim();
    if (clean.length < 2 || clean.length > MAX_CITY) throw new Error('Укажите город от 2 до 100 символов');
    return saveProfileCity(clean);
  }

  /* ---------- Разметка ---------- */

  function identityHTML(){
    return '<div style="display:flex;align-items:center;gap:14px">' +
        '<div class="ava" style="width:56px;height:56px;font-size:20px">' +
          esc(initials(displayName()) || '?') + '</div>' +
        '<div><div style="font-weight:700;font-size:17px">' + value(displayName()) + '</div>' +
          '<div class="small muted">' + esc(A.me || '') + '</div></div>' +
      '</div>' +
      '<div class="pf-rows">' +
        row('Почта для входа', A.me) +
        row('Телефон', meta().phone) +
        row('Город в профиле', A.profileCity || meta().city) +
        row('Статус', typeof isAdmin === 'function' && isAdmin() ? roleText() : 'Арендатор') +
      '</div>';
  }

  function personalFormHTML(){
    return '<form id="personalForm">' +
      '<div class="pf-grid">' +
        '<label class="pf-field">Имя *' +
          '<input class="inp" name="name" required maxlength="' + MAX_NAME + '" autocomplete="name" value="' + esc(displayName()) + '">' +
        '</label>' +
        '<label class="pf-field">Телефон' +
          '<input class="inp" type="tel" name="phone" maxlength="' + MAX_PHONE + '" autocomplete="tel" placeholder="+7 900 000-00-00" value="' + esc(meta().phone || '') + '">' +
        '</label>' +
      '</div>' +
      '<p class="small muted" style="margin:10px 0 0">Имя и телефон видны арендодателю, когда вы оставляете заявку. ' +
        'Почта для входа (' + esc(A.me || '') + ') здесь не меняется.</p>' +
      '<p class="pf-error" id="personalError" role="alert"></p>' +
      '<button class="btn sm" type="submit" style="margin-top:12px">Сохранить</button>' +
    '</form>';
  }

  function cityFormHTML(){
    const cities = (typeof availableCities === 'function') ? availableCities() : [];
    return '<form id="profileCityForm">' +
      '<label class="pf-field">Город в профиле' +
        '<input class="inp" id="profileCity" name="city" autocomplete="address-level2" list="profileCityOptions" ' +
          'required minlength="2" maxlength="' + MAX_CITY + '" value="' + esc(A.profileCity || '') + '" placeholder="Укажите ваш город">' +
      '</label>' +
      '<datalist id="profileCityOptions">' + cities.map(function(c){ return '<option value="' + esc(c) + '"></option>'; }).join('') + '</datalist>' +
      '<p class="small muted" style="margin:8px 0 12px">Город используется для поиска по умолчанию. ' +
        'Другой город можно выбрать в шапке — это не изменит профиль и адрес выдачи инструмента.</p>' +
      '<p class="pf-error" id="profileCityError" role="alert"></p>' +
      '<button class="btn sec sm" type="submit">Сохранить город</button>' +
    '</form>';
  }

  function securityHTML(){
    return '<p class="small muted" style="line-height:1.55">Пароль не хранится на этой странице. ' +
        'Чтобы сменить его: нажмите «Выйти», затем на экране входа — «Забыли пароль»; ' +
        'на почту придёт ссылка для нового пароля.</p>' +
      '<div style="display:flex;gap:10px;flex-wrap:wrap;margin-top:12px">' +
        '<button class="btn ghost sm" id="bOut" type="button">Выйти</button>' +
      '</div>';
  }

  function landlordHTML(){
    return '<p class="small muted" style="line-height:1.55">Сведения о прокате, точки выдачи, объявления, заявки и расчёты — в кабинете. ' +
        'Личные данные (имя, телефон, город) меняются здесь.</p>' +
      '<div style="display:flex;gap:10px;flex-wrap:wrap;margin-top:12px">' +
        '<a class="btn sec sm" href="account.html#prof">Данные проката</a>' +
        '<a class="btn sec sm" href="account.html">Мои объявления</a>' +
        '<a class="btn sec sm" href="account.html#orders">Заявки</a>' +
        '<a class="btn sec sm" href="account.html#fee">Комиссия и оплата</a>' +
      '</div>';
  }

  function loginHTML(){
    return '<div class="empty" style="padding:70px 20px">' +
      '<svg viewBox="0 0 24 24"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>' +
      '<b>Войдите, чтобы настроить профиль</b>' +
      '<p>Имя, телефон и город хранятся в аккаунте — по ним оформляются заявки.</p>' +
      '<a class="btn" href="account.html">Войти</a></div>';
  }

  function pageHTML(){
    const landlord = (typeof isAdmin === 'function') && isAdmin();
    return '<div class="sect-h"><h2>Настройки профиля</h2>' +
        '<span class="cnt">' + esc(A.me || '') + ' · ' + esc(roleText().toLowerCase()) + '</span>' +
        '<a href="account.html">В кабинет</a></div>' +
      '<div class="box">' + identityHTML() + '</div>' +
      '<div class="box"><h3>Личные данные</h3>' + personalFormHTML() + '</div>' +
      '<div class="box"><h3>Город в профиле</h3>' + cityFormHTML() + '</div>' +
      '<div class="box"><h3>Вход и безопасность</h3>' + securityHTML() + '</div>' +
      (landlord ? '<div class="box"><h3>Прокат и заявки</h3>' + landlordHTML() + '</div>' : '') +
      '<div class="apk-cards" id="apkCards"></div>';
  }

  /* ---------- Сборка страницы ---------- */

  function render(host){
    if (!host) return;
    if (typeof isAuthed === 'function' && !isAuthed()){
      host.innerHTML = loginHTML();
      return;
    }
    host.innerHTML = pageHTML();
    wire(host);
  }

  function wire(host){
    const form = host.querySelector('#personalForm');
    if (form){
      form.addEventListener('submit', async function(e){
        e.preventDefault();
        const btn = form.querySelector('button');
        const err = host.querySelector('#personalError');
        if (err) err.textContent = '';
        if (btn) btn.disabled = true;
        try {
          await savePersonalData(form.elements.name.value, form.elements.phone.value);
          toast('Данные профиля сохранены');
          render(host); // форма перерисовывается — сохранённые значения уже в полях
        } catch(ex){
          if (err) err.textContent = ex.message || 'Не удалось сохранить данные';
          if (btn) btn.disabled = false;
        }
      });
    }

    const cityForm = host.querySelector('#profileCityForm');
    if (cityForm){
      cityForm.addEventListener('submit', async function(e){
        e.preventDefault();
        const btn = cityForm.querySelector('button');
        const err = host.querySelector('#profileCityError');
        if (err) err.textContent = '';
        if (btn) btn.disabled = true;
        try {
          await saveCityData(host.querySelector('#profileCity').value);
          toast('Город профиля сохранён');
        } catch(ex){
          if (err) err.textContent = ex.message || 'Не удалось сохранить город';
        }
        if (btn) btn.disabled = false;
      });
    }

    const out = host.querySelector('#bOut');
    if (out){
      out.addEventListener('click', function(){
        signOut();
        toast('Вы вышли');
        render(host);
      });
    }

    const cards = host.querySelector('#apkCards');
    if (cards && typeof mountAppCards === 'function') mountAppCards(cards);
  }

  async function mount(host){
    if (!host) return;
    host.innerHTML = '<div class="empty" style="padding:70px 20px"><b>Загрузка…</b></div>';
    if (typeof restoreSess === 'function'){
      try { await restoreSess(); } catch(e){}
    }
    if (!host.isConnected) return;
    render(host);
  }

  return { mount: mount, render: render, savePersonalData: savePersonalData, saveCityData: saveCityData, personalPayload: personalPayload };
})();
