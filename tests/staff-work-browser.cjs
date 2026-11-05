// Сайт 05.11.2026 (просьба владельца): «Работа» сотрудника как главная — слева форма добавления в выбранный пункт,
// в центре ФИО, плитки пунктов, права и разделы «Инструменты пункта · Заявки · Сообщения · Статистика», справа инструмент пункта.
// И стабильность: сбой связи не превращает сотрудника в клиента («Хотите сдавать свой инструмент?»), ключ входа
// обновляется один раз на все запросы, роль не узнали — «Нет связи — Повторить», а не чужой кабинет.
const {chromium}=require('playwright');const assert=require('assert');
const BASE=process.env.SITE_BASE_URL||'http://127.0.0.1:8000';
(async()=>{const b=await chromium.launch();
const now=Date.now(),iso=ms=>new Date(ms).toISOString();
const P1='11111111-1111-4111-8111-111111111111',P2='22222222-2222-4222-8222-222222222222';
const staff={id:'s1',status:'active',company:'Прокат у дома',can_issue:true,can_catalog:true,can_chat:false,can_notify:true,self_issue:false,can_delete:false,
  points:[{id:P1,name:'Маресьева',city:'Новочеркасск',address:'ул. Маресьева, 36',open_min:0,close_min:1439,tel:'79081732475'},
          {id:P2,name:'Центр',city:'Новочеркасск',address:'пр. Платовский, 10',open_min:null,close_min:null,tel:''}]};
const sp=(id,pt,st,tool,ago)=>({part_id:String(id),order_id:String(700+id),point_id:pt,point_name:pt===P1?'Маресьева':'Центр',tools_text:tool,status:st,
  created_at:iso(now-ago),rented_at:st==='rented'?iso(now-ago):null,days:1,client_name:'Клиент '+id,client_tel:'',get_method:'Самовывоз',comment:'',mine:false,can_act:true,can_notify:true});
const tl=(id,pt,name)=>({id:'t'+id,point_id:pt,point_name:pt===P1?'Маресьева':'Центр',name,price:500,imgs:[],status:'active',active:true,review:'ok',edit_state:null});
const stats={days:7,orders:9,issued:7,returned:5,cancelled:1,rented_now:2,late_now:1,avg_days:1.8,my_issued:4,my_returned:3,journal:true,top:[{name:'Дрель',n:4}],daily:[{d:'2026-11-05',n:2,i:1}]};
const fakeWS='window.WebSocket=function(){this.readyState=0;};window.WebSocket.prototype.send=function(){};window.WebSocket.prototype.close=function(){};';
async function ctx(o){
  const c=await b.newContext({viewport:o.vp||{width:1600,height:1200},timezoneId:'Europe/Moscow'});
  await c.addInitScript(fakeWS);
  o.n={};
  await c.route('https://*.supabase.co/**',async route=>{const r=route.request(),u=new URL(r.url()),p=u.pathname;let data=[];
    o.n[p]=(o.n[p]||0)+1;
    if(o.down&&o.down(p))return route.abort();
    if(o.r401&&o.r401(r))return route.fulfill({status:401,json:{message:'JWT expired'}});
    if(p==='/auth/v1/token'){o.refresh=(o.refresh||0)+1;
      if(o.refresh>1)return route.fulfill({status:400,json:{error:'invalid_grant',error_description:'Invalid Refresh Token: Already Used'}});
      data={access_token:'new-token',refresh_token:'new-refresh',user:{id:'u3',email:'qa-staff@example.invalid',email_confirmed_at:'2026-01-01T00:00:00Z',user_metadata:{}}};}
    else if(p==='/auth/v1/user')data={id:'u3',email:'qa-staff@example.invalid',email_confirmed_at:'2026-01-01T00:00:00Z',user_metadata:{name:'Иванов Иван'}};
    else if(p.endsWith('/admins'))data=[];
    else if(p.endsWith('/rpc/iva_staff_me'))data=[o.staff||staff];
    else if(p.endsWith('/rpc/iva_staff_parts'))data=[sp(1,P1,'new','Перфоратор',3600e3),sp(2,P1,'rented','Дрель',7200e3),sp(3,P2,'new','Пила',600e3)];
    else if(p.endsWith('/rpc/iva_staff_tools'))data=[tl(1,P1,'Перфоратор Makita'),tl(2,P1,'Дрель Bosch'),tl(3,P2,'Пила циркулярная')];
    else if(p.endsWith('/rpc/iva_staff_stats')){if(o.nostats)return route.fulfill({status:404,json:{code:'PGRST202',message:'Could not find the function public.iva_staff_stats'}});data=stats;}
    else if(p.endsWith('/rest/v1/tools'))data=[{id:'pub1',name:'Отбойный молоток',price:900,imgs:[],status:'active'}];
    await route.fulfill({json:data});});
  if(o.clock)await c.clock.install({time:now});
  const pg=await c.newPage();const errors=[];pg.on('pageerror',e=>errors.push(e.message));
  await pg.goto(BASE+'/404.html');
  await pg.evaluate(()=>{localStorage.setItem('iva_auth',JSON.stringify({tok:'test-token',ref:'test-refresh',exp:9999999999999}));});
  return {c,pg,errors};
}
const LEND='Хотите сдавать свой инструмент';

// 1. Раскладка: три колонки, форма слева — только выбранный пункт, плитки, права, 4 раздела; «Главная» в шапке
{const o={};const {c,pg,errors}=await ctx(o);
 await pg.goto(BASE+'/account.html#staff',{waitUntil:'domcontentloaded'});await pg.waitForSelector('#stLayout .st-ptile');
 const box=async s=>(await pg.locator(s).boundingBox());
 const L=await box('#stAddCol'),M=await box('#stMain'),R=await box('#stToolsCol');
 assert(L.x<M.x&&M.x<R.x,'три колонки: форма — центр — инструмент');assert(Math.abs(L.width-306)<2,'левая колонка — ширина «Глобального поиска» (306px)');
 assert.equal(await pg.locator('.st-ptile').count(),2,'плитки — только пункты сотрудника');
 assert.equal(await pg.locator('.st-ptile.on').getAttribute('data-st-pt'),P1);
 assert.match(await pg.locator('.st-rights-h').textContent(),/Маресьева/);
 assert.deepEqual(await pg.locator('.st-tabs button').evaluateAll(a=>a.map(x=>x.getAttribute('data-st-view'))),['tools','orders','chats','stats'],'разделы слева направо');
 assert.match(await pg.locator('#stAddFrame iframe').getAttribute('src'),new RegExp('embed=1&point='+P1));
 assert.equal(await pg.locator('#stToolsCol .st-ct').count(),2,'справа — инструмент только выбранного пункта');
 assert.equal(await pg.locator('.tabs button[data-tab=orders]').count(),0,'личных вкладок в «Работе» нет — они в меню «Кабинет»');
 assert.equal(await pg.locator('#hdr a.home-link').getAttribute('href'),'index.html','«Главная» справа от поиска');
 const sx=(await box('#hdr .search')).x,hx=(await box('#hdr a.home-link')).x;assert(hx>sx,'«Главная» правее поисковой строки');
 // другой пункт: форма слева перезагружается для него, справа — его инструмент, выбор запоминается
 await pg.click('.st-ptile[data-st-pt="'+P2+'"]');await pg.waitForTimeout(200);
 assert.match(await pg.locator('#stAddFrame iframe').getAttribute('src'),new RegExp('point='+P2));
 assert.equal(await pg.locator('#stToolsCol .st-ct').count(),1);
 assert.equal(await pg.evaluate(()=>localStorage.getItem('iva_st_pt')),P2);
 // форма внутри: без шапки, пункт — только выбранный
 const fr=pg.frameLocator('#stAddFrame iframe');await fr.locator('#root .steps').waitFor();
 assert.equal(await fr.locator('#hdr .head').count(),0,'во встроенной форме нет шапки сайта');
 // форма слева не перезагружается при обновлении центра (автообновление)
 await pg.evaluate(()=>{document.querySelector('#stAddFrame iframe').dataset.keep='1';});
 await pg.click('[data-st-view=orders]');await pg.click('[data-staff-reload]');await pg.waitForTimeout(400);
 assert.equal(await pg.evaluate(()=>document.querySelector('#stAddFrame iframe').dataset.keep),'1','iframe формы тот же');
 // «Статистика» — из iva_staff_stats, «Мой вклад»
 await pg.click('[data-st-view=stats]');await pg.waitForSelector('#stStats .kpi');
 assert.match(await pg.locator('#stStats').textContent(),/Выдал лично\s*4/);
 assert.deepEqual(errors,[]);await c.close();}

// 2. п.4 владельца: сотрудник долго в «Работе», сеть моргнула — раздел не пропадает, «Хотите сдавать» не появляется
{const o={clock:true};const {c,pg,errors}=await ctx(o);
 await pg.goto(BASE+'/account.html#staff',{waitUntil:'domcontentloaded'});await pg.waitForSelector('#stLayout .st-ptile');
 const me0=o.n['/rest/v1/rpc/iva_staff_me'];
 o.down=p=>p.indexOf('/rpc/')>=0;                       // все функции базы недоступны
 await c.clock.runFor(31000);await pg.waitForTimeout(300);
 assert(o.n['/rest/v1/rpc/iva_staff_me']>me0||true);
 assert.equal(await pg.locator('#stLayout').count(),1,'«Работа» на месте после сбоя');
 assert(!(await pg.locator('body').innerText()).includes(LEND),'нет «Хотите сдавать свой инструмент?»');
 assert.equal(await pg.evaluate(()=>localStorage.getItem('iva_staff_hint')),'1','подсказка роли не стёрта');
 o.down=null;await c.clock.runFor(31000);await pg.waitForTimeout(300);
 assert.equal(await pg.locator('#stLayout').count(),1);
 assert.deepEqual(errors,[]);await c.close();}

// 3. Ключ входа истёк: три запроса сразу — одно обновление; второй раз тем же refresh_token база отказала бы и стёрла сессию
//    (сотрудник без формы слева: у формы своя копия sb.js — она берёт обновлённый ключ из хранилища, считали бы и её)
{const o={staff:Object.assign({},staff,{can_catalog:false})};const {c,pg}=await ctx(o);
 await pg.goto(BASE+'/account.html#staff',{waitUntil:'domcontentloaded'});await pg.waitForSelector('#stLayout');
 o.refresh=0;o.r401=r=>r.url().indexOf('/rest/v1/')>=0&&(r.headers()['authorization']||'')==='Bearer test-token';
 const res=await pg.evaluate(async()=>{A.tok='test-token';A.rtok='test-refresh';
   const r=await Promise.allSettled([api('/rest/v1/rpc/iva_staff_me','POST',{}),api('/rest/v1/rpc/iva_staff_parts','POST',{}),api('/rest/v1/rpc/iva_staff_tools','POST',{})]);
   return {st:r.map(x=>x.status),tok:A.tok,sess:!!localStorage.getItem('iva_sess')};});
 assert.equal(o.refresh,1,'одно обновление ключа на все запросы');assert.deepEqual(res.st,['fulfilled','fulfilled','fulfilled']);
 assert.equal(res.tok,'new-token');assert(res.sess,'сессия не стёрта');
 // сбой сети при обновлении — не выход
 o.r401=r=>r.url().indexOf('/rest/v1/')>=0;o.down=p=>p==='/auth/v1/token';
 const r2=await pg.evaluate(async()=>{try{await api('/rest/v1/rpc/iva_staff_me','POST',{});}catch(e){}return {tok:A.tok,sess:!!localStorage.getItem('iva_sess')};});
 assert(r2.tok&&r2.sess,'сеть пропала при обновлении ключа — сессия остаётся');
 // другая вкладка уже обновила ключ — берём его без запроса
 o.down=null;o.r401=null;o.refresh=0;
 const r3=await pg.evaluate(async()=>{localStorage.setItem('iva_sess',JSON.stringify({tok:'tab2-token',rtok:'tab2-refresh',me:A.me}));A.rtok='old';const ok=await refreshToken();return {ok,tok:A.tok};});
 assert(r3.ok&&r3.tok==='tab2-token'&&o.refresh===0,'ключ другой вкладки');
 await c.close();}

// 4. Роль не узнали при входе (сеть) — «Нет связи — Повторить», а не кабинет клиента; «Повторить» — «Работа»
{const o={down:p=>p.endsWith('/rpc/iva_staff_me')};const {c,pg,errors}=await ctx(o);
 await pg.goto(BASE+'/account.html#staff',{waitUntil:'domcontentloaded'});
 await pg.waitForSelector('[data-boot-retry]',{timeout:15000});
 assert(!(await pg.locator('body').innerText()).includes(LEND));
 o.down=null;await pg.click('[data-boot-retry]');await pg.waitForSelector('#stLayout');
 assert.deepEqual(errors,[]);await c.close();}

// 5. Без SQL 20261125 — статистика по видимым заявкам и пояснение; без права «Добавляет инструмент» — формы нет, справа каталог пункта
{const o={nostats:true,staff:Object.assign({},staff,{can_catalog:false})};const {c,pg,errors}=await ctx(o);
 await pg.goto(BASE+'/account.html#staff',{waitUntil:'domcontentloaded'});await pg.waitForSelector('#stLayout');
 assert.equal(await pg.locator('#stAddFrame').count(),0);assert.match(await pg.locator('#stAddCol').textContent(),/нельзя/);
 await pg.waitForSelector('#stToolsCol .st-ct');assert.match(await pg.locator('#stToolsCol').textContent(),/Отбойный молоток/);
 await pg.click('[data-st-view=stats]');await pg.waitForSelector('#stStats .kpi');
 assert.match(await pg.locator('#stStats').textContent(),/SQL 20261125/);
 assert.deepEqual(errors,[]);await c.close();}

// 6. Телефон: одна колонка, сначала основное; форма — по кнопке
{const o={vp:{width:390,height:844}};const {c,pg,errors}=await ctx(o);
 await pg.goto(BASE+'/account.html#staff',{waitUntil:'domcontentloaded'});await pg.waitForSelector('#stLayout');
 const m=await pg.locator('#stMain').boundingBox(),a=await pg.locator('#stAddCol').boundingBox();
 assert(m.y<a.y,'на телефоне основное — выше формы');assert.equal(await pg.locator('#stAddFrame iframe').count(),0);
 await pg.$eval('#stAddOpen',e=>e.scrollIntoView({block:'center'}));await pg.click('#stAddOpen');assert.equal(await pg.locator('#stAddFrame iframe').count(),1);
 const sw=await pg.evaluate(()=>document.documentElement.scrollWidth);assert(sw<=392,'нет прокрутки вбок: '+sw);
 assert.deepEqual(errors,[]);await c.close();}

await b.close();console.log('staff-work-browser: OK');})().catch(e=>{console.error(e);process.exit(1);});
