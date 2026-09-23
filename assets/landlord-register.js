(function(){
 mountChrome('','');
 const root=document.getElementById('partnerRoot');let signup=true;let categories=[];let application=null;
 function errText(e){
  const m=e.message||'Не удалось выполнить запрос';
  if(/already registered|already been registered/i.test(m))return 'Такой аккаунт уже есть. Нажмите «Уже есть аккаунт» и войдите.';
  if(/rate limit|too many/i.test(m))return 'Слишком много попыток. Подождите и повторите позже.';
  return m;
 }
 function loginScreen(message,email){
  root.innerHTML='<section class="partner-box"><h2>Аккаунт «Ива»</h2><p class="muted small">Если вы уже зарегистрированы на площадке, используйте тот же email и пароль.</p><div class="partner-switch" role="tablist" style="margin-top:18px"><button id="partnerSignup" role="tab" aria-selected="'+signup+'">Создать аккаунт</button><button id="partnerLogin" role="tab" aria-selected="'+(!signup)+'">Уже есть аккаунт</button></div>'+
  (message?'<p class="partner-notice" style="margin-top:18px">'+esc(message)+'</p>':'')+
  '<form id="partnerAuthForm" class="partner-form"><label class="partner-field">Email<input class="inp" id="partnerEmail" type="email" autocomplete="email" maxlength="254" required value="'+esc(email||'')+'"></label><label class="partner-field">Пароль<input class="inp" id="partnerPassword" type="password" autocomplete="'+(signup?'new-password':'current-password')+'" '+(signup?'minlength="8"':'')+' maxlength="128" required><small>'+(signup?'Не менее 8 символов. Используйте уникальный пароль.':'Пароль вашего аккаунта на площадке.')+'</small></label>'+
  (signup?'<label class="partner-field">Повторите пароль<input class="inp" id="partnerPasswordAgain" type="password" autocomplete="new-password" minlength="8" maxlength="128" required></label><label class="partner-consent"><input type="checkbox" required><span>Согласен с <a class="partner-link" href="partner-terms.html" target="_blank" rel="noopener">правилами подключения</a> и ознакомлен с <a class="partner-link" href="partner-privacy.html" target="_blank" rel="noopener">обработкой данных аккаунта и заявки</a>.</span></label>':'')+
  '<div id="partnerAuthError" class="partner-error" role="alert"></div><button class="btn" type="submit">'+(signup?'Создать аккаунт и заполнить анкету':'Войти и продолжить')+'</button></form></section>';
  document.getElementById('partnerSignup').onclick=()=>{const em=document.getElementById('partnerEmail').value;signup=true;loginScreen('',em);};
  document.getElementById('partnerLogin').onclick=()=>{const em=document.getElementById('partnerEmail').value;signup=false;loginScreen('',em);};
  const form=document.getElementById('partnerAuthForm');form.onsubmit=async e=>{
   e.preventDefault();const error=document.getElementById('partnerAuthError');error.textContent='';
   const em=document.getElementById('partnerEmail').value.trim().toLowerCase(),pw=document.getElementById('partnerPassword').value;
   if(signup && pw!==document.getElementById('partnerPasswordAgain').value){error.textContent='Пароли не совпадают';return;}
   const button=form.querySelector('button[type=submit]');button.disabled=true;
   try{
    if(signup){
     const redirect=new URL('landlord-register.html',location.href).href;
     const response=await fetch(SB+'/auth/v1/signup?redirect_to='+encodeURIComponent(redirect),{method:'POST',headers:{apikey:KEY,'Content-Type':'application/json'},body:JSON.stringify({email:em,password:pw,data:{onboarding:'landlord'}})});
     const text=await response.text();if(!response.ok)throw new Error(sbErr(text));const j=JSON.parse(text);
     if(!j.access_token){signup=false;loginScreen('Если для этого адреса требуется подтверждение, проверьте письмо, подтвердите email и войдите. Анкета пока не отправлена.',em);return;}
     A.tok=j.access_token;A.rtok=j.refresh_token;A.me=((j.user||{}).email||em).toLowerCase();A.uname='';saveSess();await loadAdmin();
    }else await signIn(em,pw);
    await loadState();
   }catch(err){error.textContent=errText(err);button.disabled=false;}
  };
 }
 function accountLine(){return '<p class="small muted">Аккаунт: '+esc(A.me)+' · <button class="partner-link" type="button" id="partnerSignout">Выйти</button></p>';}
 function bindSignout(){const btn=document.getElementById('partnerSignout');if(btn)btn.onclick=()=>{signOut();application=null;signup=false;loginScreen();};}
 async function loadState(){
  root.innerHTML='<section class="partner-box"><p>Загружаем статус заявки…</p></section>';
  try{
   await loadAdmin();
   application=await loadMyLandlordApplication();
   if(A.admin && !application){root.innerHTML='<section class="partner-box">'+accountLine()+'<h2 style="margin-top:16px">Профиль арендодателя уже существует</h2><p class="muted">'+(isAdmin()?'Повторная заявка не нужна.':'Доступ отключён. Для восстановления обратитесь к владельцу площадки — повторная регистрация не снимает ограничения.')+'</p><a class="btn" href="account.html" style="margin-top:20px">Перейти в кабинет</a></section>';bindSignout();return;}
   if(application && (application.status==='pending'||application.status==='approved')){statusScreen();return;}
   const cats=await loadCats();categories=cats.cats.filter(c=>c.key);if(!categories.length)throw new Error('Категории пока недоступны. Попробуйте позже.');
   applicationForm();
  }catch(err){root.innerHTML='<section class="partner-box">'+accountLine()+'<div class="partner-error" style="margin:16px 0">'+esc(errText(err))+'</div><button class="btn sec" id="partnerRetry">Повторить загрузку</button></section>';bindSignout();document.getElementById('partnerRetry').onclick=loadState;}
 }
 function statusScreen(){
  const a=application,approved=a.status==='approved';
  root.innerHTML='<section class="partner-box">'+accountLine()+'<span class="partner-state" style="margin-top:20px">'+esc(PARTNER_STATUS[a.status])+'</span><h2 style="margin-top:14px">'+(approved?'Заявка одобрена':'Анкета отправлена владельцу площадки')+'</h2><p class="muted">'+(approved?'Ваши условия подключения указаны ниже. До начала размещения ознакомьтесь с ними. Если условия не подходят, свяжитесь с владельцем.':'До решения владельца права арендодателя не выдаются. Статус и комментарий появятся здесь. Фиксированного срока проверки нет — нажмите «Обновить статус», чтобы проверить решение.')+'</p>'+
  (approved?'<div class="partner-notice" style="margin-top:18px">Комиссия: '+esc(a.fee_pct)+'%. Лимит задолженности: '+esc(rub(a.debt_limit))+'.\n'+(isAdmin()?'Доступ к размещению открыт.':'Доступ сейчас отключён. Обратитесь к владельцу площадки.')+'</div>':'')+
  (a.review_comment?'<div class="partner-notice" style="margin-top:16px">Комментарий владельца:\n'+esc(a.review_comment)+'</div>':'')+
  '<div class="partner-switch" style="margin-top:20px"><button id="partnerRefresh">Обновить статус</button>'+(approved&&isAdmin()?'<a class="btn" href="account.html">Мои объявления</a><a class="btn sec" href="cabinet.html">Заявки и расчёты</a>':'')+'</div>'+(approved&&isAdmin()?'<p class="small muted" style="margin-top:12px">Если расширенный кабинет запросит вход, используйте тот же email и пароль.</p>':'')+'<details style="margin-top:22px"><summary>Отправленная анкета</summary>'+partnerSummary(a)+'</details><details style="margin-top:16px"><summary>История рассмотрения</summary><div id="applicationHistory">Загрузка…</div></details></section>';
  bindSignout();document.getElementById('partnerRefresh').onclick=loadState;
  loadLandlordHistory(a.id).then(rows=>{const box=document.getElementById('applicationHistory');if(box)box.innerHTML=partnerHistoryHTML(rows);}).catch(()=>{const box=document.getElementById('applicationHistory');if(box)box.textContent='Не удалось загрузить историю. Обновите статус.';});
 }
 function applicationForm(){
  const a=application||{};const input=(id,label,value,extra)=>'<label class="partner-field">'+label+'<input class="inp" id="'+id+'" value="'+esc(value||'')+'" '+(extra||'')+'></label>';
  root.innerHTML='<section class="partner-box">'+accountLine()+'<h2 style="margin-top:20px">Анкета арендодателя</h2><p class="small muted">Поля со звёздочкой обязательны. Точный адрес выдачи и часы работы можно заполнить после одобрения. Паспорт и банковская карта не нужны.</p>'+
  (a.status?'<div class="partner-notice" style="margin-top:18px">'+esc(PARTNER_STATUS[a.status])+':\n'+esc(a.review_comment||'Уточните сведения и отправьте анкету повторно.')+'</div>':'')+
  '<form id="landlordApplication" class="partner-form"><div class="partner-fields">'+
  input('laName','ФИО контактного лица *',a.full_name||A.uname,'required minlength="3" maxlength="150" autocomplete="name"')+
  input('laPhone','Телефон *',a.phone,'type="tel" required minlength="10" maxlength="25" autocomplete="tel" placeholder="+7 (900) 123-45-67"')+
  '<label class="partner-field">Статус бизнеса *<select id="laType" class="inp" required><option value="">Выберите статус</option>'+Object.keys(PARTNER_TYPES).map(k=>'<option value="'+k+'"'+(a.business_type===k?' selected':'')+'>'+esc(PARTNER_TYPES[k])+'</option>').join('')+'</select></label>'+
  input('laCompany','Название проката / компании',a.company,'maxlength="150" autocomplete="organization"')+
  input('laRegion','Регион *',a.region,'required minlength="2" maxlength="100" autocomplete="address-level1" placeholder="Ростовская область"')+
  input('laCity','Город *',a.city,'required minlength="2" maxlength="100" autocomplete="address-level2" placeholder="Новочеркасск"')+
  input('laCount','Примерное количество инструментов *',a.inventory_count,'type="number" min="1" max="100000" step="1" required')+
  input('laWebsite','Сайт или соцсеть',a.website,'type="url" maxlength="500" placeholder="https://…"')+'</div>'+
  '<fieldset class="partner-checks"><legend>Категории инструмента * — выберите хотя бы одну</legend>'+categories.map(c=>'<label class="partner-consent"><input type="checkbox" name="category" value="'+esc(c.key)+'"'+((a.categories||[]).includes(c.key)?' checked':'')+'><span>'+esc(c.title)+'</span></label>').join('')+'</fieldset>'+
  '<label class="partner-field">Комментарий<textarea class="inp" id="laComment" maxlength="2000" rows="4" placeholder="Что сдаёте, есть ли доставка, что важно знать владельцу площадки">'+esc(a.comment||'')+'</textarea></label>'+
  '<label class="partner-consent"><input type="checkbox" id="laTerms" required><span>Подтверждаю заявленный статус бизнеса и достоверность сведений, принимаю <a class="partner-link" href="partner-terms.html" target="_blank" rel="noopener">правила подключения</a> и <a class="partner-link" href="offer.html" target="_blank" rel="noopener">оферту</a>. Комиссию и лимит назначает владелец; я ознакомлюсь с ними до начала размещения.</span></label>'+
  '<label class="partner-consent"><input type="checkbox" id="laPrivacy" required><span>Даю <a class="partner-link" href="partner-privacy.html" target="_blank" rel="noopener">согласие на обработку данных анкеты</a> для рассмотрения заявки, связи со мной и подключения к площадке. Рекламная рассылка в это согласие не входит.</span></label>'+
  '<div id="laError" class="partner-error" role="alert"></div><button class="btn" type="submit">'+(a.status?'Отправить повторно на проверку':'Отправить на проверку')+'</button><p class="small muted">После отправки анкету рассматривает владелец площадки. Статус бизнеса указан вами: автоматическая проверка реестров при подаче не выполняется.</p></form></section>';
  bindSignout();const f=document.getElementById('landlordApplication');f.onsubmit=async e=>{
   e.preventDefault();const error=document.getElementById('laError');error.textContent='';
   const selected=[...f.querySelectorAll('[name=category]:checked')].map(c=>c.value);if(!selected.length){error.textContent='Выберите хотя бы одну категорию.';return;}
   const phone=document.getElementById('laPhone').value.replace(/[^+0-9]/g,'');if(!/^\+?[0-9]{10,15}$/.test(phone)){error.textContent='Укажите корректный телефон: от 10 до 15 цифр.';return;}
   const value=id=>document.getElementById(id).value.trim();const website=value('laWebsite');if(website&&!/^https?:\/\/\S+$/i.test(website)){error.textContent='Ссылка должна начинаться с https:// или http://';return;}
   const data={full_name:value('laName'),phone,business_type:value('laType'),company:value('laCompany'),region:value('laRegion'),city:value('laCity'),inventory_count:Number(value('laCount')),website,comment:value('laComment'),categories:selected,accept_terms:document.getElementById('laTerms').checked,accept_privacy:document.getElementById('laPrivacy').checked};
   const btn=f.querySelector('button[type=submit]');btn.disabled=true;
   try{const result=await api('/rest/v1/rpc/submit_landlord_application','POST',{p_data:data});application=Array.isArray(result)?result[0]:result;if(!application||!application.id)throw new Error('Не удалось получить статус заявки. Обновите страницу, чтобы проверить отправку.');statusScreen();root.scrollIntoView({behavior:'smooth',block:'start'});}
   catch(err){error.textContent=errText(err);btn.disabled=false;}
  };
 }
 (async()=>{
  // Email-confirmation callback: never trust a role or email taken from URL parameters.
  const hash=new URLSearchParams(location.hash.slice(1));
  if(hash.has('access_token')&&hash.has('refresh_token')){
   A.tok=hash.get('access_token');A.rtok=hash.get('refresh_token');saveSess();history.replaceState(null,'',location.pathname+location.search);
  }
  const restored=await restoreSess();
  if(restored&&isAuthed())await loadState();else{if(isAuthed())clearSess();loginScreen();}
 })();
})();
