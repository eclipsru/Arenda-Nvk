// Сайт 05.11.2026 (просьба и правки владельца): «Работа» сотрудника — слева заявки выбранного пункта (новые зелёные, в аренде
// жёлтые, меньше часа / просрочено — красные), справа чаты (непрочитанные зелёные, без ответа жёлтые, не открыты больше суток —
// красные). Как на главной: названия «Заявки» / «Чаты» — над окнами на уровне «Глобальный поиск» / «Полезная информация», окна —
// на уровне окон фильтра и информации. Шапка сайта, шапка «Работы» (ФИО + «Пункт выдачи» — свой выпадающий список в стиле сайта)
// и окна стоят на месте при прокрутке. По центру выбранный пункт, права, разделы «Инструменты пункта · Заявки · Сообщения · Статистика · Добавить».
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
const sp=(id,pt,st,tool,ago,left)=>({part_id:String(id),order_id:String(700+id),point_id:pt,point_name:pt===P1?'Маресьева':'Центр',tools_text:tool,status:st,
  created_at:iso(now-ago),rented_at:st==='rented'?iso(now+(left!=null?left:3600e3)-86400e3):null,days:1,client_name:'Клиент '+id,client_tel:'',get_method:'Самовывоз',comment:'',mine:false,can_act:true,can_notify:true});
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
    else if(p.endsWith('/admins'))data=o.admins||[];
    else if(p.endsWith('/rpc/iva_staff_me'))data=o.nostaff?[]:[o.staff||staff];
    else if(p.endsWith('/rpc/iva_staff_parts'))data=o.parts||[sp(1,P1,'new','Перфоратор',3600e3),sp(2,P1,'rented','Дрель',7200e3,5*3600e3),
      sp(4,P1,'rented','Болгарка',90000e3,30*60e3),sp(5,P1,'rented','Шуруповёрт',99000e3,-2*3600e3),sp(3,P2,'new','Пила',600e3)];
    else if(p.endsWith('/rpc/iva_staff_point_chats'))data=o.chats||[];
    else if(p.endsWith('/rpc/iva_staff_chats'))data=[];
    else if(p.endsWith('/rpc/iva_staff_chat_unread')){if(o.nounr)return route.fulfill({status:404,json:{code:'PGRST202',message:'Could not find the function'}});data=o.unr||[];}
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
const pc=(peer,un,mine,agoH,name)=>({point_id:P1,peer,client_name:name,point_name:'Маресьева',last_text:'Здравствуйте',last_at:iso(now-agoH*3600e3),last_mine:mine,unread:un,last_client_id:'m'+peer});
const CHATS=[pc('pc4',0,true,0.2,'Отвечено'),pc('pc3',1,false,3,'Старое'),pc('pc2',0,false,0.5,'Ждёт'),pc('pc1',2,false,1,'Новое')];
const UNR=[{kind:'p',key:P1,peer:'pc3',first_unread_at:iso(now-50*3600e3)},{kind:'p',key:P1,peer:'pc1',first_unread_at:iso(now-3600e3)}];
const cls=async(pg,sel)=>pg.locator(sel).evaluateAll(a=>a.map(x=>(x.className.match(/c-([a-z])/)||[])[1]));

// 1. Раскладка: слева заявки, в центре основное, справа чаты; названия и окна — на тех же линиях, что на главной;
//    пункт — свой выпадающий список справа от ФИО (клик, стрелки, Esc, клик мимо); шапка и окна — на месте при прокрутке;
//    пункт по центру, ниже права, ниже 5 разделов; «Главная» в шапке
{const o={staff:Object.assign({},staff,{can_chat:true}),chats:CHATS,unr:UNR};const {c,pg,errors}=await ctx(o);
 await pg.goto(BASE+'/account.html#staff',{waitUntil:'domcontentloaded'});await pg.waitForSelector('#stLayout #stPtSel');await pg.waitForSelector('#stChatList .st-si');
 const box=async s=>(await pg.locator(s).first().boundingBox());
 const L=await box('#stOrdCol'),M=await box('#stMain'),R=await box('#stChatCol');
 assert(L.x<M.x&&M.x<R.x,'три колонки: заявки — основное — чаты');assert(Math.abs(L.width-306)<2&&Math.abs(R.width-306)<2,'боковые окна — ширина «Глобального поиска» (306px)');
 const fio=await box('.st-head .acct-head');
 // названия «Заявки» / «Чаты» и окна — на тех же линиях, что «Глобальный поиск» / «Полезная информация» и их окна на главной
 const oh=await box('#stOrdCol .st-side-h'),ch=await box('#stChatCol .st-side-h'),oc=await box('#stOrdCol .st-side-card'),cc=await box('#stChatCol .st-side-card');
 assert(Math.abs(oh.y-fio.y)<=2&&Math.abs(ch.y-fio.y)<=2,'названия окон — на линии ФИО: '+[oh.y,ch.y,fio.y]);
 assert(oh.y+oh.height<=oc.y&&ch.y+ch.height<=cc.y,'названия — над окнами, не внутри');
 assert.equal(await pg.locator('#stOrdCol .st-side-card h2, #stChatCol .st-side-card h2').count(),0);
 assert.equal(await pg.locator('#stOrdCol .st-side-h.cats-head').count(),1,'название — как на главной (.cats-head)');
 {const hp=await c.newPage();await hp.setViewportSize({width:1600,height:1200});await hp.goto(BASE+'/index.html',{waitUntil:'domcontentloaded'});await hp.waitForSelector('#filters');await hp.waitForTimeout(300);
  const hy=await hp.evaluate(()=>['#globalSearchTitle','#filters','#usefulInfoTitle','.useful-card'].map(s=>document.querySelector(s).getBoundingClientRect().y));await hp.close();
  const sy=[await pg.locator('#stOrdTitle').boundingBox(),oc,await pg.locator('#stChatTitle').boundingBox(),cc].map(b=>b.y);
  sy.forEach((y,i)=>assert(Math.abs(y-hy[i])<=2,'линии как на главной: «Работа» '+sy+' / главная '+hy));}
 // главная: окна «Глобальный поиск» и «Полезная информация» стоят на месте при прокрутке, как в «Работе»; длинный фильтр — прокрутка внутри
 {const hp=await c.newPage();await hp.setViewportSize({width:1600,height:700});await hp.goto(BASE+'/index.html',{waitUntil:'domcontentloaded'});await hp.waitForSelector('#filters .f-grp');await hp.waitForTimeout(300);
  const yy=()=>hp.evaluate(()=>['#globalSearchTitle','#filters','#usefulInfoTitle','.useful-card'].map(s=>Math.round(document.querySelector(s).getBoundingClientRect().y)));
  // прокрутка — в пределах раздела (дальше окна уходят вместе с концом раздела, к подвалу — как любое липкое окно)
  const room=await hp.evaluate(()=>{const L=document.querySelector('.layout.home');return Math.floor(L.getBoundingClientRect().bottom-parseFloat(getComputedStyle(L).paddingBottom)-document.querySelector('.home-filter-column').getBoundingClientRect().bottom);});
  const sc=Math.min(250,room-2);assert(sc>=20,'есть куда прокрутить: '+room);
  const y0=await yy();await hp.evaluate(t=>window.scrollTo({top:t,behavior:'instant'}),sc);await hp.waitForTimeout(200);const y1=await yy();
  assert.equal(await hp.evaluate(()=>window.scrollY),sc,'главная прокрутилась');assert.deepEqual(y1,y0,'окна главной — на месте: '+y0+' → '+y1);
  const fs=await hp.evaluate(()=>{const f=document.querySelector('#filters'),r=f.getBoundingClientRect();return [getComputedStyle(f).overflowY,r.bottom<=innerHeight+1];});
  assert.deepEqual(fs,['auto',true],'фильтр целиком на экране, прокрутка — внутри');
  await hp.setViewportSize({width:1280,height:700});await hp.evaluate(()=>window.scrollTo({top:0,behavior:'instant'}));await hp.waitForTimeout(200);
  const f0=await hp.evaluate(()=>document.querySelector('#filters').getBoundingClientRect().y);await hp.evaluate(()=>window.scrollTo({top:60,behavior:'instant'}));await hp.waitForTimeout(200);
  assert.equal(await hp.evaluate(()=>document.querySelector('#filters').getBoundingClientRect().y),f0,'1280px: фильтр — на месте');
  await hp.close();}
 // «Пункт выдачи» — свой выпадающий список только с названиями, справа от ФИО, в той же строке; подпись крупная и белая
 const sel=await box('#stPtSel'),h2=await box('.st-head h2');assert(sel.x>h2.x+h2.width&&Math.abs((sel.y+sel.height/2)-(fio.y+fio.height/2))<30,'список пунктов — напротив ФИО');
 assert.equal(await pg.locator('#stPtSel .st-dd-v').textContent(),'Маресьева');
 assert.equal(await pg.locator('#stPtMenu').isVisible(),false,'список закрыт');
 const lab=await pg.locator('.st-ptsel-l').evaluate(e=>{const c=getComputedStyle(e),b=getComputedStyle(document.querySelector('#stPtSel'));return {t:e.textContent,fs:parseFloat(c.fontSize),bfs:parseFloat(b.fontSize),col:c.color};});
 assert.equal(lab.t,'Пункт выдачи');assert(lab.fs<lab.bfs&&lab.fs<=13.5,'подпись — мельче текста списка: '+JSON.stringify(lab));assert.equal(lab.col,'rgb(154, 161, 176)','подпись — серая');
 // список — размером и видом как кнопка города в шапке сайта
 const cb=await box('#cityBtn'),sb=await box('#stPtSel');assert(Math.abs(cb.height-sb.height)<=2,'высота — как у кнопки города: '+[cb.height,sb.height]);
 assert.deepEqual(await pg.evaluate(()=>{const a=getComputedStyle(document.querySelector('#cityBtn')),b=getComputedStyle(document.querySelector('#stPtSel'));return [a.fontSize===b.fontSize,a.borderRadius===b.borderRadius,a.backgroundColor===b.backgroundColor];}),[true,true,true],'шрифт, скругление и фон — как у кнопки города');
 await pg.click('#stPtSel');await pg.waitForSelector('#stPtMenu',{state:'visible'});
 assert.equal(await pg.locator('#stPtSel').getAttribute('aria-expanded'),'true');
 assert.deepEqual(await pg.locator('#stPtMenu [role=option]').evaluateAll(a=>a.map(x=>[x.getAttribute('data-st-pt'),x.innerText.trim(),x.getAttribute('aria-selected')])),
   [[P1,'Маресьева','true'],[P2,'Центр','false']],'в списке только названия, выбранный отмечен');
 const mn=await pg.locator('#stPtMenu').evaluate(e=>{const c=getComputedStyle(e);return [c.backgroundColor,c.borderRadius];});
 assert.equal(mn[0],'rgb(26, 29, 36)','меню — карточка сайта');
 await pg.mouse.click(800,700);await pg.waitForSelector('#stPtMenu',{state:'hidden'});
 await pg.focus('#stPtSel');await pg.keyboard.press('ArrowDown');await pg.waitForSelector('#stPtMenu',{state:'visible'});
 assert.equal(await pg.evaluate(()=>document.activeElement.getAttribute('data-st-pt')),P1,'стрелка — открыть на выбранном');
 await pg.keyboard.press('ArrowDown');assert.equal(await pg.evaluate(()=>document.activeElement.getAttribute('data-st-pt')),P2);
 await pg.keyboard.press('Escape');await pg.waitForSelector('#stPtMenu',{state:'hidden'});
 assert.equal(await pg.evaluate(()=>document.activeElement.id),'stPtSel','Esc — закрыть, фокус на списке');
 assert.equal(await pg.locator('.st-ptile').count(),0,'плиток пунктов больше нет');
 // ниже по центру — выбранный пункт; ниже права; ниже разделы
 // выбранный пункт (название, адрес, телефон) — в шапке по центру, между ФИО и «Пункт выдачи»; под шапкой — счётчики во всю ширину
 assert.equal(await pg.locator('.st-head .st-pinfo h3').textContent(),'«Маресьева»');
 const pcH=await box('.st-pinfo h3'),hrB=await box('.st-headrow');
 const whoB=await box('.st-who'),psB=await box('.st-ptsel'),piB=await box('.st-pinfo');assert(whoB.x+whoB.width<=piB.x&&piB.x+piB.width<=psB.x,'ФИО — пункт — «Пункт выдачи» в одной строке');
 const gapMid=(whoB.x+whoB.width+psB.x)/2;assert(Math.abs((pcH.x+pcH.width/2)-gapMid)<3,'пункт — ровно посередине между ФИО и «Пункт выдачи»: '+[pcH.x+pcH.width/2,gapMid]);
 assert(Math.abs((piB.y+piB.height/2)-(fio.y+(fio.height-12)/2))<14,'пункт — на уровне ФИО');
 assert.match(await pg.locator('.st-pinfo').textContent(),/ул\. Маресьева, 36/);assert.match(await pg.locator('.st-pinfo').textContent(),/\+7 \(908\)/);
 assert.equal(await pg.locator('.st-pcard h3, .st-pcard-k').count(),0,'в полосе под шапкой — только счётчики');
 const pc=await box('.st-pcard');assert(Math.abs(pc.width-hrB.width)<=2,'счётчики — во всю ширину');
 assert.deepEqual(await pg.locator('.st-pcard .st-pst span').allInnerTexts(),['Новые','В аренде','Просрочены','Инструмент','Непрочитано']);
 const ys=await pg.locator('.st-pcard .st-pst').evaluateAll(a=>a.map(x=>Math.round(x.getBoundingClientRect().y)));assert(ys.every(y=>y===ys[0]),'счётчики — в одну строку');
 const yP=(await box('.st-pcard')).y,yT=(await box('.st-tabs')).y;assert(fio.y<yP&&yP<yT,'ФИО → счётчики → разделы');
 // «Ваши права» — рядом с телефоном пункта; окно с правами и крестиком (ещё закрывается кликом мимо и Esc); на странице блока прав нет
 assert.equal(await pg.locator('.st-main .st-rights').count(),0,'блока прав на странице нет — он в окне');
 const tl=await box('.st-pinfo-m a[href^="tel:"]'),rb=await box('[data-st-rights]');assert(Math.abs(rb.y-tl.y)<30&&Math.abs((rb.x+rb.width/2)-(tl.x+tl.width/2))<200,'«Ваши права» — рядом с телефоном');
 assert.equal((await pg.locator('[data-st-rights]').innerText()).trim(),'Ваши права');
 await pg.click('[data-st-rights]');await pg.waitForSelector('#sheet.st-rsheet.open');await pg.waitForTimeout(300);
 assert.match(await pg.locator('#sheet .sheet-h b').textContent(),/Ваши права в пункте «Маресьева»/);
 assert.deepEqual(await pg.locator('#sheet .st-right b').allInnerTexts(),['Выдача и приём','Уведомления о заявках','Добавляет инструмент','Удаляет инструмент','Отвечает в чате пункта']);
 assert.deepEqual(await pg.locator('#sheet .st-right').evaluateAll(a=>a.map(x=>x.classList.contains('on'))),[true,true,true,false,true]);
 assert.match(await pg.locator('#sheet').textContent(),/Права одни на все ваши пункты/);
 const shB=await box('#sheet');assert(Math.abs((shB.x+shB.width/2)-800)<3&&shB.width<=560&&shB.y>40,'окошко по центру экрана: '+JSON.stringify(shB));
 assert.equal(await pg.evaluate(()=>document.activeElement.getAttribute('aria-label')),'Закрыть','фокус — на крестике');
 await pg.click('#sheet .x');await pg.waitForSelector('#sheet',{state:'detached'});
 await pg.click('[data-st-rights]');await pg.waitForSelector('#sheet.st-rsheet.open');await pg.keyboard.press('Escape');await pg.waitForSelector('#sheet',{state:'detached'});
 assert.equal(await pg.evaluate(()=>document.activeElement.getAttribute('data-st-rights')),'1','после Esc фокус — на «Ваши права»');
 await pg.click('[data-st-rights]');await pg.waitForSelector('#sheet.st-rsheet.open');await pg.waitForTimeout(300);await pg.mouse.click(60,600);await pg.waitForSelector('#sheet',{state:'detached'});
 assert.deepEqual(await pg.locator('.st-tabs button').evaluateAll(a=>a.map(x=>x.getAttribute('data-st-view'))),['tools','orders','chats','stats','add'],'разделы слева направо');
 // заявки: сначала новые (зелёные), потом в аренде по сроку; просрочено и меньше часа — красные
 assert.deepEqual(await cls(pg,'#stOrdList .st-si'),['g','r','r','y'],'порядок и цвета заявок');
 const ot=await pg.locator('#stOrdList .st-si').allInnerTexts();
 assert.match(ot[0],/Перфоратор/);assert.match(ot[1],/Шуруповёрт[\s\S]*просрочена на 2 ч/);assert.match(ot[2],/Болгарка[\s\S]*осталось (29|30) мин/);assert.match(ot[3],/Дрель[\s\S]*вернуть/);
 assert.equal(await pg.locator('#stOrdList .st-si').count(),4,'заявки только выбранного пункта, без закрытых');
 // чаты: непрочитанные (зелёные), без ответа (жёлтые), не открыты больше суток (красные), отвеченные
 assert.deepEqual(await cls(pg,'#stChatList .st-si'),['g','y','r','n'],'порядок и цвета чатов');
 assert.match((await pg.locator('#stChatList .st-si').allInnerTexts())[2],/Старое[\s\S]*больше суток/);
 // окна стоят на месте при прокрутке страницы
 await pg.setViewportSize({width:1600,height:600});await pg.click('[data-st-view=orders]');await pg.evaluate(()=>window.scrollTo({top:700,behavior:'instant'}));await pg.waitForTimeout(150);
 const L2=await box('#stOrdCol .st-side-card'),R2=await box('#stChatCol .st-side-card'),M2=await box('#stMain'),H2=await box('.st-head .acct-head'),T2=await box('#stOrdCol .st-side-h');
 assert(M2.y<-300,'страница прокрутилась: '+M2.y);assert(Math.abs(L2.y-oc.y)<=2&&Math.abs(R2.y-L2.y)<2,'окна заявок и чатов — на месте: '+L2.y);
 assert.equal(await pg.evaluate(()=>document.querySelector('#hdr .head').getBoundingClientRect().y),0,'шапка сайта — на месте');
 assert(Math.abs(H2.y-fio.y)<=2&&Math.abs(T2.y-H2.y)<=2,'шапка с ФИО и «Пункт выдачи» — на месте, на линии названий окон: '+[H2.y,T2.y]);
 assert.equal(await pg.evaluate(()=>{const r=document.querySelector('#stPtSel').getBoundingClientRect();const e=document.elementFromPoint(r.x+r.width/2,r.y+r.height/2);return !!e.closest('#stPtSel');}),true,'список пунктов поверх содержимого');
 const hh=await pg.evaluate(()=>{const l=document.querySelector('#stOrdCol .st-side-card').getBoundingClientRect();return [l.bottom,innerHeight];});
 assert(hh[0]<=hh[1]+1,'окно заявок целиком на экране: '+hh);
 await pg.setViewportSize({width:1600,height:1200});await pg.evaluate(()=>window.scrollTo(0,0));
 // нажали заявку слева — «Заявки» и её карточка
 await pg.click('#stOrdList .st-si.c-y');await pg.waitForTimeout(200);
 assert.equal(await pg.locator('.st-tabs button.on').getAttribute('data-st-view'),'orders');
 assert.equal(await pg.locator('#stList .st-wrap.st-flash').getAttribute('data-part'),'2');
 // нажали чат справа — переписка
 await pg.click('#stChatList .st-si.c-g');await pg.waitForSelector('#stChBody');await pg.click('#sheet .x');await pg.waitForSelector('#sheet',{state:'detached'});
 // «Добавить»: форма для выбранного пункта; не перезагружается при обновлении и переходах между разделами
 await pg.click('[data-st-view=add]');await pg.waitForSelector('#stAddFrame iframe');
 assert.match(await pg.locator('#stAddFrame iframe').getAttribute('src'),new RegExp('embed=1&point='+P1));
 assert.match(await pg.locator('#stAddPane .st-add-pt').textContent(),/Маресьева/);
 const fr=pg.frameLocator('#stAddFrame iframe');await fr.locator('#root .steps').waitFor();
 assert.equal(await fr.locator('#hdr .head').count(),0,'во встроенной форме нет шапки сайта');
 await pg.evaluate(()=>{document.querySelector('#stAddFrame iframe').dataset.keep='1';});
 await pg.click('[data-st-view=orders]');assert(await pg.locator('#stAddPane').evaluate(e=>e.classList.contains('hide')));
 await pg.click('[data-staff-reload]');await pg.waitForTimeout(400);await pg.click('[data-st-view=add]');
 assert.equal(await pg.evaluate(()=>document.querySelector('#stAddFrame iframe').dataset.keep),'1','iframe формы тот же');
 // другой пункт из списка: форма — для него, слева его заявки, выбор запоминается
 await pg.click('#stPtSel');await pg.click('#stPtMenu [data-st-pt="'+P2+'"]');await pg.waitForTimeout(250);
 assert.equal(await pg.locator('#stPtSel .st-dd-v').textContent(),'Центр');assert.equal(await pg.locator('#stPtMenu').isVisible(),false);
 assert.match(await pg.locator('#stAddFrame iframe').getAttribute('src'),new RegExp('point='+P2));
 assert.equal(await pg.locator('.st-pinfo h3').textContent(),'«Центр»');
 assert.deepEqual(await cls(pg,'#stOrdList .st-si'),['g']);
 assert.equal(await pg.evaluate(()=>localStorage.getItem('iva_st_pt')),P2);
 assert.equal(await pg.locator('.tabs button[data-tab=orders]').count(),0,'личных вкладок в «Работе» нет — они в меню «Кабинет»');
 assert.equal(await pg.locator('#hdr a.home-link').getAttribute('href'),'index.html','«Главная» справа от поиска');
 const sx=(await box('#hdr .search')).x,hx=(await box('#hdr a.home-link')).x;assert(hx>sx,'«Главная» правее поисковой строки');
 // «Статистика» — из iva_staff_stats, «Мой вклад»
 await pg.click('[data-st-view=stats]');await pg.waitForSelector('#stStats .kpi');
 assert.match(await pg.locator('#stStats').textContent(),/Выдал лично\s*4/);
 assert.deepEqual(errors,[]);await c.close();}

// 1b. Цвет по времени: до конца аренды 61 мин — жёлтая; через 2 минуты (автообновление) — красная. Без SQL «не прочитано с …» —
//     красный чат по времени последнего сообщения (больше суток)
{const o={clock:true,staff:Object.assign({},staff,{can_chat:true}),parts:[sp(9,P1,'rented','Миксер',90000e3,61*60e3)],
   chats:[pc('pa',1,false,30,'Давнее'),pc('pb',1,false,2,'Свежее')],nounr:true};const {c,pg,errors}=await ctx(o);
 await pg.goto(BASE+'/account.html#staff',{waitUntil:'domcontentloaded'});await pg.waitForSelector('#stOrdList .st-si');
 assert.deepEqual(await cls(pg,'#stOrdList .st-si'),['y']);
 assert.deepEqual(await cls(pg,'#stChatList .st-si'),['g','r'],'без SQL — по времени последнего сообщения');
 await c.clock.runFor(125000);await pg.waitForTimeout(300);
 assert.deepEqual(await cls(pg,'#stOrdList .st-si'),['r'],'меньше часа — красная без перезагрузки');
 assert.deepEqual(errors,[]);await c.close();}

// 2. п.4 владельца: сотрудник долго в «Работе», сеть моргнула — раздел не пропадает, «Хотите сдавать» не появляется
{const o={clock:true};const {c,pg,errors}=await ctx(o);
 await pg.goto(BASE+'/account.html#staff',{waitUntil:'domcontentloaded'});await pg.waitForSelector('#stLayout #stPtSel');
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

// 5. Без SQL 20261125 — статистика по видимым заявкам и пояснение; без права «Добавляет инструмент» — во вкладке «Добавить» пояснение,
//    «Инструменты пункта» — каталог пункта; без права чата — справа пояснение
{const o={nostats:true,staff:Object.assign({},staff,{can_catalog:false})};const {c,pg,errors}=await ctx(o);
 await pg.goto(BASE+'/account.html#staff',{waitUntil:'domcontentloaded'});await pg.waitForSelector('#stLayout');
 await pg.click('[data-st-view=add]');assert.equal(await pg.locator('#stAddFrame').count(),0);assert.match(await pg.locator('#stAddPane').textContent(),/нельзя/);
 assert.match(await pg.locator('#stChatCol').textContent(),/не показываются/);
 await pg.click('[data-st-view=tools]');await pg.waitForSelector('#stTabBody .st-tool');assert.match(await pg.locator('#stTabBody').textContent(),/Отбойный молоток/);
 await pg.click('[data-st-view=stats]');await pg.waitForSelector('#stStats .kpi');
 assert.match(await pg.locator('#stStats').textContent(),/SQL 20261125/);
 assert.deepEqual(errors,[]);await c.close();}

// 6. Телефон: одна колонка, сначала основное, потом заявки и чаты; «Пункт выдачи» и список — строкой под ФИО, до края;
//    шапка на месте при прокрутке; форма — во вкладке «Добавить»
{const o={vp:{width:390,height:844},staff:Object.assign({},staff,{can_chat:true}),chats:CHATS,unr:UNR};const {c,pg,errors}=await ctx(o);
 await pg.goto(BASE+'/account.html#staff',{waitUntil:'domcontentloaded'});await pg.waitForSelector('#stLayout #stPtSel');
 const m=await pg.locator('#stMain').boundingBox(),sd=await pg.locator('#stSide').boundingBox();
 assert(m.y+m.height<=sd.y+1,'на телефоне основное — выше заявок и чатов');
 const sl=await pg.locator('#stPtSel').boundingBox(),lb=await pg.locator('.st-ptsel-l').boundingBox(),hr=await pg.locator('.st-headrow').boundingBox();
 assert(sl.width>=160&&Math.abs(sl.x+sl.width-(hr.x+hr.width))<=2&&Math.abs((lb.y+lb.height/2)-(sl.y+sl.height/2))<4,'список — рядом с подписью, до края: '+JSON.stringify([sl,lb]));
 await pg.evaluate(()=>window.scrollTo({top:1200,behavior:'instant'}));await pg.waitForTimeout(150);
 const hy=(await pg.locator('.st-head .acct-head').boundingBox()).y;assert(hy>=0&&hy<=30,'шапка на месте на телефоне: '+hy);
 await pg.click('#stPtSel');await pg.waitForSelector('#stPtMenu',{state:'visible'});
 const mb=await pg.locator('#stPtMenu').boundingBox();assert(mb.x>=0&&mb.x+mb.width<=391,'меню не уходит за экран');
 await pg.keyboard.press('Escape');await pg.evaluate(()=>window.scrollTo({top:0,behavior:'instant'}));
 await pg.$eval('[data-st-view=add]',e=>e.scrollIntoView({block:'center'}));await pg.click('[data-st-view=add]');await pg.waitForSelector('#stAddFrame iframe');
 const sw=await pg.evaluate(()=>document.documentElement.scrollWidth);assert(sw<=392,'нет прокрутки вбок: '+sw);
 assert.deepEqual(errors,[]);await c.close();}

// 7. п.4 правок: сотрудник с прежней строкой арендодателя (admins.active = false) — форма, а не «Размещение приостановлено»
{const o={admins:[{email:'qa-staff@example.invalid',role:'admin',active:false}]};const {c,pg,errors}=await ctx(o);
 await pg.goto(BASE+'/new.html',{waitUntil:'domcontentloaded'});await pg.waitForSelector('#root .steps');
 assert(!(await pg.locator('body').innerText()).includes('приостановлено'));
 // без права «Добавляет инструмент» — честное «вам пока нельзя», не «приостановлено»
 o.staff=Object.assign({},staff,{can_catalog:false});await pg.reload({waitUntil:'domcontentloaded'});
 await pg.waitForFunction(()=>/Добавлять инструмент вам пока нельзя/.test(document.body.innerText));
 // не сотрудник, доступ арендодателя выключен — «Размещение приостановлено» как и раньше
 o.nostaff=true;await pg.reload({waitUntil:'domcontentloaded'});
 await pg.waitForFunction(()=>/Размещение приостановлено/.test(document.body.innerText));
 assert.deepEqual(errors,[]);await c.close();}

// 9. Один пункт (как у владельца на скриншоте 05.11 — «На дому»): список тоже открывается, в нём этот пункт с галочкой
{const o={staff:Object.assign({},staff,{points:[staff.points[0]]})};const {c,pg,errors}=await ctx(o);
 await pg.goto(BASE+'/account.html#staff',{waitUntil:'domcontentloaded'});await pg.waitForSelector('#stLayout #stPtSel');
 assert.equal(await pg.locator('#stPtSel .st-dd-v').textContent(),'Маресьева');assert(!(await pg.locator('#stPtSel').isDisabled()));
 assert.equal(await pg.locator('#stPtSel .st-dd-ch').count(),1,'со стрелкой');
 await pg.click('#stPtSel');await pg.waitForSelector('#stPtMenu',{state:'visible'});
 assert.deepEqual(await pg.locator('#stPtMenu [role=option]').evaluateAll(a=>a.map(x=>[x.innerText.trim(),x.getAttribute('aria-selected')])),[['Маресьева','true']]);
 await pg.click('#stPtMenu [role=option]');await pg.waitForSelector('#stPtMenu',{state:'hidden'});
 assert.deepEqual(errors,[]);await c.close();}

await b.close();console.log('staff-work-browser: OK');})().catch(e=>{console.error(e);process.exit(1);});
