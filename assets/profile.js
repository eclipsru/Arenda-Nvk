/* Expanded rental profile. Public business data is isolated from privileges and auth metadata. */
const RentalProfile = (function(){
  const groups=[
    ['about','О прокате',[
      ['display_name','Название проката *','text','Например: Инструмент рядом',150],
      ['specialization','Специализация','text','Строительный инструмент, садовая техника…',300],
      ['experience','Опыт работы','text','Например: работаем с 2020 года',300],
      ['description','О прокате','textarea','Ассортимент, кому помогаете, особенности сервиса',2000]]],
    ['contacts','Контакты',[
      ['contact_name','Контактное лицо','text','Как к вам обращаться',150],
      ['contact_role','Должность','text','Например: управляющий пунктом проката',150],
      ['phone','Рабочий телефон *','tel','+7 900 000-00-00',100],
      ['public_email','Публичный email','email','Для обращений арендаторов, не обязательно email входа',300]]],
    ['delivery','Доставка',[
      ['delivery_area','Зона доставки','text','Города или радиус; «Нет доставки», если только самовывоз',300],
      ['delivery_price','Стоимость доставки','text','Тариф, минимальная стоимость или по согласованию',300],
      ['delivery_terms','Условия доставки и обратного забора','textarea','Сроки, ограничения, подъём на этаж, возврат курьером',2000]]],
    ['terms','Условия аренды',[
      ['min_rental','Минимальный срок аренды','text','Например: от 4 часов / от 1 суток',300],
      ['payment_methods','Способы оплаты','text','Наличные, карта, безналичный расчёт…',300],
      ['deposit_terms','Правила залога','textarea','Когда нужен залог, как определяется и возвращается',2000],
      ['documents','Документы и оформление','textarea','Что нужно для договора. Не загружайте документы клиентов',2000],
      ['extension_terms','Продление аренды','textarea','Как и за сколько времени согласовать продление',2000],
      ['cancellation_terms','Отмена бронирования','textarea','Сроки уведомления и условия возврата оплаты',2000],
      ['return_terms','Получение и возврат','textarea','Проверка, комплектность, чистота, действия при поломке',2000],
      ['consumables','Расходники','textarea','Что входит в стоимость, что оплачивается отдельно',2000]]]
  ];
  let dirty=false;
  window.addEventListener('beforeunload',e=>{if(dirty){e.preventDefault();e.returnValue='';}});
  document.addEventListener('click',e=>{
    const target=e.target.closest('a,[data-tab],#bOut');
    if(!dirty||!target||target.closest('#rentalEditor'))return;
    if(!confirm('В профиле есть несохранённые изменения. Выйти без сохранения?')){e.preventDefault();e.stopImmediatePropagation();}
    else dirty=false;
  },true);
  function row(label,value){return '<div><div class="rp-key">'+esc(label.replace(' *',''))+'</div><div class="rp-value">'+(value?esc(value):'<span class="rp-empty">Не указано</span>')+'</div></div>';}
  function mapLink(p){return 'https://yandex.ru/maps/?text='+encodeURIComponent(p.city+', '+p.address);}
  function field(f,value){return '<label class="rp-field '+(f[2]==='textarea'?'rp-wide':'')+'">'+esc(f[1])+(f[2]==='textarea'?'<textarea':'<input type="'+f[2]+'"')+' class="inp" name="'+f[0]+'" maxlength="'+f[4]+'" '+(['display_name','phone'].includes(f[0])?'required ':'')+'placeholder="'+esc(f[3])+'"'+(f[2]==='textarea'?'>'+esc(value||'')+'</textarea>':' value="'+esc(value||'')+'">')+'</label>';}
  function pointFields(p,i){return '<section class="rp-point" data-point="'+esc(p.id)+'"><h4>Точка '+(i+1)+(i===0?' · основная':'')+'</h4><div class="rp-grid">'+[
    ['name','Название точки *','text','Прокат на Центральной',100],['city','Город *','text','Ростов-на-Дону',100],
    ['address','Точный адрес *','text','Улица, дом, помещение',500],['phone','Телефон точки','tel','Если отличается от общего',100],
    ['hours','График работы','text','Пн–Пт 09:00–18:00; Сб 10:00–15:00; Вс выходной',500],['directions','Как найти / порядок выдачи','textarea','Ориентир, вход, парковка, нужна ли предварительная запись',1000]
  ].map(f=>field(f,p[f[0]]).replace(/ required /g,' ').replace(/name="/,'name="point-').replace('placeholder=',(['name','city','address'].includes(f[0])?'required ':'')+'placeholder=')).join('')+'</div><div class="rp-actions">'+(i?'<button class="btn sec sm" type="button" data-primary="'+esc(p.id)+'">Сделать основной</button>':'')+'<button class="btn ghost sm" type="button" data-remove="'+esc(p.id)+'">Удалить точку</button></div></section>';}
  async function mount(host){
    dirty=false;
    if(!host || !isAdmin())return;
    host.className='rental-profile';host.innerHTML='<div class="rp-section">Загружаем профиль проката…</div>';
    let saved,application=null;
    try {
      const rows=await api('/rest/v1/landlord_profiles?owner_email=eq.'+encodeURIComponent(A.admin.email||A.me)+'&select=*');
      if(!host.isConnected)return;
      saved=rows[0]||{details:{},locations:[],revision:0};
    }catch(err){if(host.isConnected){host.innerHTML='<div class="rp-section"><p class="rp-error">Не удалось загрузить профиль: '+esc(err.message)+'</p><button class="btn sec sm" type="button">Повторить</button></div>';host.querySelector('button').onclick=()=>mount(host);}return;}
    try{const apps=await api('/rest/v1/landlord_applications?select=business_type,company,status,created_at&user_id=eq.'+encodeURIComponent(A.uid)+'&limit=1');application=apps[0]||null;}catch(e){}
    if(!host.isConnected)return;
    const a=A.admin;
    function initial(){
      if(saved.revision)return JSON.parse(JSON.stringify(saved));
      return {revision:0,details:{display_name:a.company||a.full_name||A.uname||'',contact_name:a.full_name||'',phone:a.phone||A.userMeta.phone||''},locations:a.address?[{id:'legacy-main',name:'Основная точка',city:cityFromProfileAddress(a.address)||A.profileCity||'',address:a.address,phone:'',hours:a.work_hours||'',directions:''}]:[]};
    }
    function view(){
      dirty=false;const model=initial(),d=model.details,points=model.locations;
      const checklist=[['Название проката',d.display_name],['Рабочий телефон',d.phone],['Описание',d.description],['Специализация',d.specialization],['Точка выдачи',points.some(p=>p.city&&p.address)],['График выдачи',points.some(p=>p.hours)],['Доставка / самовывоз',d.delivery_area],['Условия залога',d.deposit_terms],['Минимальный срок',d.min_rental],['Способы оплаты',d.payment_methods]];
      const missing=checklist.filter(x=>!x[1]).map(x=>x[0]),percent=Math.round((checklist.length-missing.length)/checklist.length*100);
      host.innerHTML='<div class="rp-head"><div><h2>Профиль проката</h2><p class="rp-muted">'+(saved.revision?'Сохранённые сведения о вашем прокате':'Заполните сведения о прокате. Данные из старого кабинета подставлены в форму, но ещё не опубликованы в новом профиле.')+'</p></div><button class="btn sm" id="editRental">Редактировать профиль</button></div>'+
        '<div class="rp-note">Контакты и точные адреса после сохранения доступны всем посетителям в объявлениях. Отдельную публичную страницу проката не создаём.</div>'+
        '<div class="rp-muted">Заполнено '+percent+'% · это подсказка, а не оценка или статус проверки</div><div class="rp-bar"><span style="width:'+percent+'%"></span></div>'+
        (missing.length?'<p class="rp-muted">Можно дополнить: '+esc(missing.join(', '))+'.</p>':'')+
        groups.slice(0,2).map(g=>'<section class="rp-section"><span class="rp-public">Публичные сведения</span><h3>'+g[1]+'</h3><div class="rp-grid">'+g[2].map(f=>row(f[1],d[f[0]])).join('')+'</div></section>').join('')+
        '<section class="rp-section"><span class="rp-public">Публичные адреса</span><h3>Точки выдачи · '+points.length+'</h3><p class="rp-muted">Точки могут находиться в разных городах. Основной город поиска в профиле не ограничивает их список.</p>'+points.map((p,i)=>'<div class="rp-point"><h4>'+esc(p.name)+(i===0?' · основная':'')+'</h4><div class="rp-grid">'+row('Город',p.city)+row('Адрес',p.address)+row('Телефон',p.phone||d.phone)+row('График',p.hours)+row('Как найти',p.directions)+'</div><p style="margin-top:12px"><a href="'+esc(mapLink(p))+'" target="_blank" rel="noopener noreferrer">Открыть на карте ↗</a></p></div>').join('')+(points.length?'':'<p class="rp-empty">Точек пока нет. Добавьте их в редакторе.</p>')+'</section>'+
        groups.slice(2).map(g=>'<section class="rp-section"><h3>'+g[1]+'</h3><div class="rp-grid">'+g[2].map(f=>row(f[1],d[f[0]])).join('')+'</div></section>').join('')+
        '<section class="rp-section"><span class="rp-public">Только в личном кабинете · не редактируется здесь</span><h3>Работа на площадке</h3><div class="rp-grid">'+row('Доступ',a.active!==false?'Арендодатель допущен к работе':'Приостановлен')+row('Роль',a.role==='chief'?'Владелец площадки':'Арендодатель')+row('Комиссия',a.fee_pct==null?'':a.fee_pct+' %')+row('Лимит задолженности',a.debt_limit==null?'':Number(a.debt_limit).toLocaleString('ru-RU')+' ₽')+row('Форма бизнеса по одобренной анкете',application&&application.status==='approved'?({self_employed:'Самозанятый',sole_trader:'ИП',company:'Компания'}[application.business_type]||''):'')+'</div><p class="rp-muted" style="margin-top:14px">Комиссию, лимит и права назначает владелец площадки. Для изменения юридических сведений обратитесь к нему. Паспортные и банковские данные в этом профиле не собираются.</p></section>';
      host.querySelector('#editRental').onclick=edit;
    }
    function edit(){
      const model=initial();
      host.innerHTML='<div class="rp-head"><div><h2>Редактор профиля проката</h2><p class="rp-muted">Обязательны название и рабочий телефон. У добавленной точки — название, город и точный адрес.</p></div></div><div class="rp-note">Это публичные сведения. Не указывайте личные документы, пароли и реквизиты карт. Точки не меняют адреса уже опубликованных объявлений автоматически.</div><nav class="rp-nav" aria-label="Разделы редактора">'+[['about','О прокате'],['contacts','Контакты'],['points','Точки выдачи'],['delivery','Доставка'],['terms','Условия аренды']].map(g=>'<button type="button" data-section="'+g[0]+'">'+g[1]+'</button>').join('')+'</nav><form id="rentalEditor">'+groups.slice(0,2).map(g=>section(g,model.details)).join('')+'<section class="rp-section" id="rp-points"><h3>Точки выдачи</h3><p class="rp-muted">До 20 точек в любых городах. Первая — основная. Точное место получения инструмента согласуйте с арендатором.</p><div id="rentalPoints"></div><button class="btn sec sm" type="button" id="addPoint" style="margin-top:16px">+ Добавить точку</button></section>'+groups.slice(2).map(g=>section(g,model.details)).join('')+'<p class="rp-error" id="rentalError" role="alert"></p><div class="rp-actions" style="margin:20px 0"><button class="btn" type="submit" id="saveRental">Сохранить профиль</button><button class="btn sec" type="button" id="cancelRental">Отмена</button></div></form>';
      const form=host.querySelector('#rentalEditor');let points=model.locations;
      const readPoints=()=>Array.from(form.querySelectorAll('[data-point]')).map(el=>{const p={id:el.dataset.point};el.querySelectorAll('[name^="point-"]').forEach(input=>p[input.name.slice(6)]=input.value.trim());p.city=IvaGeo.city(p.city);return p;});
      function paintPoints(){form.querySelector('#rentalPoints').innerHTML=points.map(pointFields).join('');form.querySelector('#addPoint').disabled=points.length>=20;}
      paintPoints();
      host.querySelectorAll('[data-section]').forEach(b=>b.onclick=()=>host.querySelector('#rp-'+b.dataset.section).scrollIntoView({behavior:'smooth',block:'start'}));
      form.addEventListener('input',()=>dirty=true);
      form.addEventListener('click',e=>{
        const rem=e.target.closest('[data-remove]'),primary=e.target.closest('[data-primary]');
        if(rem){if(!confirm('Удалить эту точку из профиля? Изменение вступит в силу после сохранения.'))return;points=readPoints().filter(p=>p.id!==rem.dataset.remove);dirty=true;paintPoints();}
        if(primary){points=readPoints();const i=points.findIndex(p=>p.id===primary.dataset.primary);if(i>0)points.unshift(points.splice(i,1)[0]);dirty=true;paintPoints();}
      });
      form.querySelector('#addPoint').onclick=()=>{points=readPoints();if(points.length>=20)return;points.push({id:crypto.randomUUID?crypto.randomUUID():'point-'+Date.now()+'-'+Math.random().toString(16).slice(2),name:'',city:'',address:'',phone:'',hours:'',directions:''});dirty=true;paintPoints();form.querySelector('[data-point]:last-child input').focus();};
      form.querySelector('#cancelRental').onclick=()=>{if(!dirty||confirm('Отменить несохранённые изменения?'))view();};
      form.onsubmit=async e=>{
        e.preventDefault();if(!form.reportValidity())return;
        const d={};groups.forEach(g=>g[2].forEach(f=>d[f[0]]=form.elements[f[0]].value.trim()));
        points=readPoints();const error=form.querySelector('#rentalError');error.textContent='';
        if(points.some(p=>IvaGeo.addressCity(p.address)&&!cityMatches(IvaGeo.addressCity(p.address),p.city))){error.textContent='Город и адрес точки не совпадают. Проверьте точку выдачи.';return;}
        const phones=[d.phone,...points.map(p=>p.phone).filter(Boolean)];
        if(phones.some(ph=>!/^\+?[0-9() .-]+$/.test(ph)||ph.replace(/\D/g,'').length<10||ph.replace(/\D/g,'').length>15)){error.textContent='Проверьте телефоны: от 10 до 15 цифр.';return;}
        if(points.some(p=>['name','city','address'].some(k=>p[k].length<2))||d.display_name.length<2){error.textContent='Название проката, название точки, город и адрес должны содержать не менее двух символов.';return;}
        const controls=Array.from(form.elements);controls.forEach(el=>el.disabled=true);host.classList.add('rp-saving');
        try{
          saved=await api('/rest/v1/rpc/save_landlord_profile','POST',{p_details:d,p_locations:points,p_revision:saved.revision||0});
          dirty=false;toast('Профиль проката сохранён');if(host.isConnected)view();
        }catch(err){error.textContent='Не удалось сохранить: '+err.message;controls.forEach(el=>el.disabled=false);form.querySelector('#addPoint').disabled=points.length>=20;}
        finally{host.classList.remove('rp-saving');}
      };
    }
    function section(g,d){
      const hint=g[0]==='delivery'?'<div class="rp-note" style="margin:0 0 16px 0;">Данные, которые указываются в этом пункте, будут автоматически применяться во всех инструментах, которые вы добавляете (с возможностью изменить в каждом объявлении). Если у вас для всего инструмента стоимость доставки разная, можете оставить этот пункт пустым.</div>':'';
      return '<section class="rp-section" id="rp-'+g[0]+'"><h3>'+g[1]+'</h3>'+hint+'<div class="rp-grid">'+g[2].map(f=>field(f,d[f[0]])).join('')+'</div></section>';
    }
    view();
  }
  return {mount,allowLeave:function(){if(!dirty)return true;if(!confirm('В профиле есть несохранённые изменения. Выйти без сохранения?'))return false;dirty=false;return true;}};
})();
