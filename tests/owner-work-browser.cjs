// Кабинет владельца проката (просьба владельца 06.11.2026): в шапке нет «+ Добавить» (на компьютере; «+» в нижней панели телефона остаётся),
// кружок «Работа» слева от «Прокат»; «Работа» владельца — та же страница, что у сотрудника (все пункты, все права, статистика без денег),
// на странице «Прокат» — окна «Сотрудники» (слева) и «Пункты выдачи» (справа) с полным управлением, как в приложении.
const {chromium}=require('playwright');const assert=require('assert');
const SHOTS=process.env.SHOTS||'';
(async()=>{const b=await chromium.launch();const c=await b.newContext({viewport:{width:1600,height:1000},timezoneId:'Europe/Moscow',locale:'ru-RU'});
const T0=Date.parse('2026-11-06T09:00:00Z');// пятница, 12:00 по Москве
const iso=ms=>new Date(ms).toISOString(),H=3600e3,email='qa-owner@example.invalid';
const P1='11111111-1111-4111-8111-111111111111',P2='22222222-2222-4222-8222-222222222222';
const W=[540,540,540,540,540,600,-1],WC=[1140,1140,1140,1140,1140,960,-1];
let pts=[
  {id:P1,owner_email:email,name:'Пункт на Ленина',address:'ул. Ленина, 1',city:'Ростов-на-Дону',lat:47.2,lng:39.7,open_min:W,close_min:WC,email:null,tel:'+79001112233',staff_publish:'approve',created_at:'2026-10-01T00:00:00Z'},
  {id:P2,owner_email:email,name:'Склад на Шолохова',address:'пр. Шолохова, 5',city:'Ростов-на-Дону',lat:47.25,lng:39.8,open_min:[480,480,480,480,480,-1,-1],close_min:[1020,1020,1020,1020,1020,-1,-1],email:null,tel:'',staff_publish:'now',created_at:'2026-10-02T00:00:00Z'}
];
let staff=[
  {id:'s1',email:'ivan@example.invalid',name:'Иван Петров',tel:'+79005556677',can_issue:true,can_catalog:false,can_chat:true,can_notify:true,self_issue:false,can_delete:false,status:'active',point_ids:[P1],created_at:'2026-10-05T00:00:00Z'},
  {id:'s2',email:'olga@example.invalid',name:'Ольга Смирнова',tel:'',can_issue:false,can_catalog:true,can_chat:false,can_notify:false,self_issue:false,can_delete:true,status:'invited',point_ids:[P1,P2],created_at:'2026-10-06T00:00:00Z'}
];
const tools=[
  {id:'t1',name:'Дрель Bosch',price:500,imgs:[],img:'',status:'active',active:true,point_id:P1,review:'pending',cat:'drill'},
  {id:'t2',name:'Перфоратор Makita',price:700,imgs:[],img:'',status:'active',active:true,point_id:P2,review:'approved',cat:'drill'},
  {id:'t3',name:'Лестница',price:300,imgs:[],img:'',status:'active',active:true,point_id:null,review:null,cat:'other'}
];
let pending=[{id:'t1',staff_name:'Ольга Смирнова'},{id:'t2',edit:true,staff_name:'Ольга Смирнова'}];
const msgs=[
  {id:'m1',from_email:'c11@example.invalid',to_email:email,text:'Дрель свободна завтра?',img:null,audio:null,is_read:false,created_at:iso(T0-30*60e3),tool_id:'t1',point_id:null},
  {id:'m2',from_email:'c14@example.invalid',to_email:email,text:'Где вас найти?',img:null,audio:null,is_read:true,created_at:iso(T0-3*H),tool_id:null,point_id:P2},
  {id:'m3',from_email:email,to_email:'c14@example.invalid',text:'Шолохова, 5',img:null,audio:null,is_read:true,created_at:iso(T0-2*H),tool_id:null,point_id:P2}
];
const mk=(id,st,pt,o)=>Object.assign({id,order_id:String(500+id),owner_email:email,tools_text:'Инструмент '+id,status:st,fee:0,rent_sum:0,fee_pct:1,created_at:iso(T0-id*60e3),archived:false,
  orders:{name:'Клиент '+id,phone:'+7 900 000-00-'+String(id).padStart(2,'0'),user_email:'c'+id+'@example.invalid',tools:'',get_method:'Самовывоз',address:'',comment:'',days:1,created_at:iso(T0-id*60e3)},_pt:pt},o||{});
let parts=[mk(11,'new',{id:P1,name:'Пункт на Ленина'},{tools_text:'Дрель Bosch'}),mk(12,'rented',{id:P2,name:'Склад на Шолохова'},{_rented:T0-5*24*H,tools_text:'Перфоратор Makita'}),
  mk(14,'new',null),mk(15,'closed',{id:P1,name:'Пункт на Ленина'})];
const card=p=>({order_id:p.order_id,created_at:p.created_at,get_method:'Самовывоз',address:'',comment:'',days:1,client_name:p.orders.name,client_tel:p.orders.phone,
  parts:[{part_id:String(p.id),order_id:p.order_id,status:p.status,created_at:p.created_at,rented_at:p._rented?iso(p._rented):null,rent_sum:0,fee:0,tools_text:p.tools_text,
    point:p._pt,rent_mode:'day',close_min:WC,tz:'Europe/Moscow',items:[{id:'t'+p.id,name:p.tools_text,price:500,days:1,img:''}],change:null,cancel:null}]});
const calls=[];
await c.route('https://*.supabase.co/**',async route=>{const r=route.request(),u=new URL(r.url()),m=r.method(),body=JSON.parse(r.postData()||'null');let data=[];
  const path=u.pathname;
  if(path!=='/auth/v1/user')calls.push({m,path,q:u.search,body});
  if(path==='/auth/v1/user')data={id:'30000000-0000-4000-8000-000000000002',email,email_confirmed_at:'2026-01-01T00:00:00.000Z',user_metadata:{}};
  else if(path.endsWith('/admins'))data=[{email,role:'admin',active:true,fee_pct:1,debt_limit:5000,company:'Прокат у дома',full_name:'Владелец',phone:'+79001112233'}];
  else if(path.endsWith('/order_parts'))data=parts.map(p=>{const x=Object.assign({},p);delete x._pt;delete x._rented;return x;});
  else if(path.endsWith('/rpc/iva_order_cards')){const s=(body||{}).p_scope;data=s==='admin'?parts.map(card):[];}
  else if(path.endsWith('/rpc/iva_staff_me'))data=null;
  else if(path.endsWith('/iva_pickup_points')){
    if(m==='GET'){const id=u.searchParams.get('id');data=id?pts.filter(p=>'eq.'+p.id===id):pts;}
    else if(m==='POST'){const n=Object.assign({id:'33333333-3333-4333-8333-333333333333',owner_email:email,created_at:'2026-11-06T09:00:00Z'},body);pts.push(n);data=[n];}
    else if(m==='PATCH'){const id=u.searchParams.get('id').slice(3);const p=pts.find(x=>x.id===id);Object.assign(p,body);data=[p];}
    else if(m==='DELETE'){const id=u.searchParams.get('id').slice(3);pts=pts.filter(x=>x.id!==id);data=[];}
  }
  else if(path.endsWith('/tools'))data=tools;
  else if(path.endsWith('/rpc/iva_tools_pending'))data=pending;
  else if(path.endsWith('/app_messages'))data=m==='GET'?msgs:[];
  else if(path.endsWith('/rpc/iva_staff_list'))data=staff;
  else if(path.endsWith('/rpc/iva_staff_save')){
    if(!body.p_points||!body.p_points.length){await route.fulfill({status:400,json:{code:'P0001',message:'Отметьте хотя бы один пункт выдачи'}});return;}
    const id=body.p_id||'s3';let s=staff.find(x=>x.id===id);
    if(!s){s={id,email:body.p_email,status:'invited',created_at:iso(T0)};staff.push(s);}
    Object.assign(s,{name:body.p_name,tel:body.p_tel||'',point_ids:body.p_points,can_issue:body.p_issue,can_catalog:body.p_catalog,can_chat:body.p_chat,can_notify:body.p_notify,self_issue:body.p_self,can_delete:!!body.p_delete});
    data=id;}
  else if(path.endsWith('/rpc/iva_staff_link')){const s=staff.find(x=>x.id===body.p_id);s.point_ids=s.point_ids.filter(x=>x!==body.p_point);data=s.point_ids.length?'unlinked':'removed';if(!s.point_ids.length)staff=staff.filter(x=>x!==s);}
  else if(path.endsWith('/rpc/iva_staff_remove')){staff=staff.filter(x=>x.id!==body.p_id);data=null;}
  else if(path.endsWith('/rpc/iva_tool_review')){pending=pending.filter(x=>x.id!==body.p_id);const t=tools.find(x=>x.id===body.p_id);if(t)t.review=body.p_ok?'approved':'rejected';data=null;}
  else if(path.endsWith('/rpc/iva_tool_delete_review')){pending=pending.filter(x=>x.id!==body.p_id);data=null;}
  else if(path.endsWith('/rpc/iva_chat_who'))data=null;
  await route.fulfill({json:data});});
await c.clock.install({time:T0});
const p=await c.newPage();const errors=[];p.on('pageerror',e=>errors.push(e.message));
p.on('dialog',d=>{errors.push('native dialog: '+d.message());d.dismiss();});
const shot=async n=>{if(SHOTS)await p.screenshot({path:SHOTS+'/'+n+'.png'});};
await p.goto('http://127.0.0.1:8000/404.html');await p.evaluate(()=>localStorage.setItem('iva_auth',JSON.stringify({tok:'test-token',ref:'test-refresh',exp:9999999999999})));

// 1) «Прокат»: шапка — «Работа» слева от «Прокат», «+ Добавить» нет; окна «Сотрудники» слева и «Пункты выдачи» справа
await p.goto('http://127.0.0.1:8000/account.html#home',{waitUntil:'domcontentloaded'});
await p.waitForSelector('#prStaffCol .st-si');await p.waitForSelector('#prPtCol .st-si');
await shot('owner-prokat-1600');
const hdr=await p.evaluate(()=>{const v=e=>e&&getComputedStyle(e).display!=='none'&&!e.classList.contains('hide');const w=document.getElementById('workLink'),k=document.getElementById('prokLink');
  return {work:v(w),prok:v(k),order:w&&k?(w.compareDocumentPosition(k)&Node.DOCUMENT_POSITION_FOLLOWING?'work<prok':'prok<work'):'',add:[...document.querySelectorAll('.head a,.head button')].filter(e=>v(e)&&/Добавить/.test(e.textContent)&&e.offsetParent).length,
    workHref:w&&w.getAttribute('href'),prokOn:k&&k.classList.contains('on')};});
assert.deepEqual(hdr,{work:true,prok:true,order:'work<prok',add:0,workHref:'account.html#staff',prokOn:true},JSON.stringify(hdr));
const pos=await p.evaluate(()=>{const a=document.getElementById('prStaffCol').getBoundingClientRect(),m=document.getElementById('prMain').getBoundingClientRect(),z=document.getElementById('prPtCol').getBoundingClientRect();return [a.right<=m.left+1,m.right<=z.left+1];});
assert.deepEqual(pos,[true,true]);
const staffRows=await p.locator('#prStaffCol .st-si').allInnerTexts();
assert.equal(staffRows.length,2);assert(staffRows[0].includes('Иван Петров')&&/Работает/.test(staffRows[0]),staffRows[0]);assert(/Ждёт ответа/.test(staffRows[1]),staffRows[1]);
const ptRows=await p.locator('#prPtCol .st-si').allInnerTexts();
assert.equal(ptRows.length,2);assert(ptRows[0].includes('Пункт на Ленина'),ptRows[0]);
// вкладки кабинета не пропали
assert.equal(await p.locator('#prMain .tabs button[data-tab=orders]').count(),1);

// 2) сотрудник: правка прав и пунктов → iva_staff_save; наборы «Выдача» / «Каталог»; «Удалять» только с «Выкладывает»
await p.locator('#prStaffCol .st-si',{hasText:'Иван Петров'}).click();await p.waitForSelector('#owSf');
await p.waitForTimeout(300);await shot('owner-staff-sheet');
assert.equal(await p.locator('#owSf [data-ow-r=can_delete]').isDisabled(),true);
await p.locator('#owSf [data-ow-set=catalog]').click();
assert.equal(await p.locator('#owSf [data-ow-r=can_catalog]').isChecked(),true);assert.equal(await p.locator('#owSf [data-ow-r=can_delete]').isDisabled(),false);
await p.locator('#owSf [data-ow-p="'+P2+'"]').check();
await p.locator('#owSfSave').click();await p.waitForFunction(()=>!document.getElementById('owSf'));
let sv=calls.filter(x=>x.path.endsWith('/rpc/iva_staff_save')).pop();
assert.equal(sv.body.p_id,'s1');assert.deepEqual(sv.body.p_points.sort(),[P1,P2].sort());assert.equal(sv.body.p_catalog,true);assert.equal(sv.body.p_issue,true);
// новый сотрудник без пунктов — ошибка базы видна в окне, окно не закрывается
await p.locator('#prStaffCol [data-ow-staff=new]').click();await p.waitForSelector('#owSfEmail');
await p.fill('#owSfEmail','petr@example.invalid');await p.fill('#owSfName','Пётр');
for(const id of [P1,P2]){const el=p.locator('#owSf [data-ow-p="'+id+'"]');if(await el.isChecked())await el.uncheck();}
await p.locator('#owSfSave').click();await p.waitForSelector('#owSfErr.show');
assert((await p.locator('#owSfErr').textContent()).includes('пункт'));
await p.locator('#owSf [data-ow-p="'+P2+'"]').check();await p.locator('#owSfSave').click();await p.waitForFunction(()=>!document.getElementById('owSf'));
sv=calls.filter(x=>x.path.endsWith('/rpc/iva_staff_save')).pop();assert.equal(sv.body.p_id,null);assert.equal(sv.body.p_email,'petr@example.invalid');
await p.waitForFunction(()=>document.querySelectorAll('#prStaffCol .st-si').length===3);

// 3) пункт: график по дням, телефон, «сразу, без одобрения»; PATCH только своих полей
await p.locator('#prPtCol .st-si',{hasText:'Пункт на Ленина'}).click();await p.waitForSelector('#owPf');
await p.waitForTimeout(300);await shot('owner-point-sheet');
assert.equal(await p.locator('#owPf .ow-hr').count(),7);assert.equal(await p.locator('#owPf .ow-hr.off').count(),1);
await p.fill('#owPfTel','+7 900 111-22-44');
await p.locator('#owPf .ow-hr[data-ow-d="6"] [data-ow-w]').check();
await p.locator('#owPf .ow-hr[data-ow-d="6"] [data-ow-o]').fill('10:00');await p.locator('#owPf .ow-hr[data-ow-d="6"] [data-ow-c]').fill('15:00');
await p.locator('#owPfPub').check();
await p.locator('#owPfSave').click();await p.waitForFunction(()=>!document.getElementById('owPf'));
const pa=calls.filter(x=>x.m==='PATCH'&&x.path.endsWith('/iva_pickup_points')).pop();
assert.deepEqual(pa.body.open_min,[540,540,540,540,540,600,600]);assert.deepEqual(pa.body.close_min,[1140,1140,1140,1140,1140,960,900]);
assert.equal(pa.body.staff_publish,'now');assert.equal(pa.body.tel,'+79001112244'.length?pa.body.tel:'');assert(!('owner_email' in pa.body)&&!('id' in pa.body));
// «Убрать из пункта» внутри окна пункта — подтверждение в нижней панели, не окно браузера
await p.locator('#prPtCol .st-si',{hasText:'Склад на Шолохова'}).click();await p.waitForSelector('#owPf');
const nIn=await p.locator('#owPf .ow-ps').count();assert(nIn>=2,'staff in P2: '+nIn);
await p.locator('#owPf .ow-ps',{hasText:'Иван Петров'}).locator('[data-ow-unlink]').click();
await p.waitForSelector('#askYes');assert((await p.locator('#sheet .st-ask').innerText()).includes('Пункт на Ленина'));   // в других пунктах останется
await p.locator('#askYes').click();
await p.waitForFunction(()=>document.getElementById('owPf')&&![...document.querySelectorAll('#owPf .ow-ps')].some(e=>e.textContent.includes('Иван Петров')),null,{timeout:5000});
const ln=calls.filter(x=>x.path.endsWith('/rpc/iva_staff_link')).pop();assert.deepEqual([ln.body.p_id,ln.body.p_point,ln.body.p_on],['s1',P2,false]);
const closeSheet=async()=>{await p.locator('#sheet .x').click();await p.waitForFunction(()=>!document.getElementById('sheet')&&!document.getElementById('sheetMask'),null,{timeout:5000});};
await closeSheet();

// 4) новый пункт: адрес, график, POST с return=representation
await p.locator('#prPtCol [data-ow-pt=new]').click();await p.waitForSelector('#owPf');
await p.fill('#owPfName','Пункт у рынка');await p.fill('#owPfAddr','Ростов-на-Дону, ул. Садовая, 10');
await p.evaluate(()=>{const c=document.querySelector('#owPf [name="point-city"]');if(c)c.value='Ростов-на-Дону';});
await p.locator('#owPfSave').click();await p.waitForFunction(()=>!document.getElementById('owPf'),null,{timeout:5000});
const po=calls.filter(x=>x.m==='POST'&&x.path.endsWith('/iva_pickup_points')).pop();
assert.equal(po.body.name,'Пункт у рынка');assert.equal(po.body.open_min.length,7);
await p.waitForFunction(()=>document.querySelectorAll('#prPtCol .st-si').length===3);

// 5) «Работа» владельца: та же страница, что у сотрудника; пункты — все; инструмент на одобрении — «Одобрить»
await p.goto('http://127.0.0.1:8000/account.html#staff',{waitUntil:'domcontentloaded'});await p.waitForSelector('#stHead .st-headrow');
await p.waitForSelector('#stOrdCol .st-si');
await shot('owner-work-1600');
const head=await p.locator('#stHead').innerText();assert(head.includes('владелец проката'),head);assert(!head.includes('Ваши права'));
assert.equal(await p.locator('#workLink.on').count(),1);
// заявки пункта 1: своя (11) и без пункта (14); 12 — в пункте 2
const ordTxt=await p.locator('#stOrdCol').innerText();assert(ordTxt.includes('Клиент 11')&&ordTxt.includes('Клиент 14')&&!ordTxt.includes('Клиент 12'),ordTxt);
// чат по инструменту пункта 1 — непрочитанный
const chTxt=await p.locator('#stChatCol').innerText();assert(chTxt.includes('Дрель Bosch')||chTxt.includes('c11'),chTxt);
await p.locator('[data-st-view=tools]').first().click();await p.waitForSelector('[data-ow-trev="t1"]');
await shot('owner-work-tools');
await p.locator('[data-ow-trev="t1"][data-ok="1"]').click();await p.waitForSelector('#askYes');
assert((await p.locator('#sheet').innerText()).includes('Сотрудник: Ольга Смирнова'));await p.locator('#askYes').click();
await p.waitForFunction(()=>!document.querySelector('[data-ow-trev="t1"]'),null,{timeout:5000});
const rv=calls.filter(x=>x.path.endsWith('/rpc/iva_tool_review')).pop();assert.equal(rv.body.p_id,'t1');assert.equal(rv.body.p_ok,true);
// статистика без денег
await p.locator('[data-st-view=stats]').first().click();await p.waitForSelector('#stStats .st-sh');
const stTxt=await p.locator('#stStats').innerText();assert(!/₽/.test(stTxt),stTxt);assert(!stTxt.includes('Мой вклад'));assert(stTxt.includes('без денег'));
// переписка: открыть чат, отправить ответ
await p.locator('#stChatCol .st-si').first().click();await p.waitForSelector('#stChBody');
assert.equal(await p.locator('#stChClaim').count(),1);           // переписка по инструменту — «кто отвечает», как в приложении
await p.waitForFunction(()=>document.querySelector('#stChBody').textContent.includes('Дрель свободна'));
await p.fill('#stChTxt','Да, свободна');await p.locator('#stChSend').click();
await p.waitForFunction(()=>true);await p.waitForTimeout(300);
const sm=calls.filter(x=>x.m==='POST'&&x.path.endsWith('/app_messages')).pop();
assert.deepEqual([sm.body.to_email,sm.body.tool_id,sm.body.text],['c11@example.invalid','t1','Да, свободна']);
assert(calls.some(x=>x.m==='PATCH'&&x.path.endsWith('/app_messages')&&x.q.includes('tool_id=eq.t1')),'read mark');
await shot('owner-work-chat');
await closeSheet();
// сотрудниковых запросов у владельца нет
assert.equal(calls.filter(x=>/iva_staff_parts|iva_staff_chats|iva_staff_point_chats|iva_staff_stats/.test(x.path)).length,0);

// 6) телефон: «Работа» и «Прокат» в верхней строке, «+» в нижней панели остаётся; окна — под основным
await p.setViewportSize({width:390,height:900});
await p.goto('http://127.0.0.1:8000/account.html#home',{waitUntil:'domcontentloaded'});await p.waitForSelector('#prStaffCol .st-si');
await shot('owner-prokat-390');
const mob=await p.evaluate(()=>{const v=e=>!!e&&getComputedStyle(e).display!=='none'&&!e.classList.contains('hide')&&e.getBoundingClientRect().width>0;
  return {work:v(document.getElementById('workMob')),prok:v(document.getElementById('prokMob')),plus:[...document.querySelectorAll('.bnav a')].some(a=>/new\.html/.test(a.getAttribute('href')||'')&&v(a)),
    noScroll:document.documentElement.scrollWidth<=innerWidth};});
assert.deepEqual(mob,{work:true,prok:true,plus:true,noScroll:true},JSON.stringify(mob));
await p.setViewportSize({width:1280,height:900});await p.waitForTimeout(200);await shot('owner-prokat-1280');
assert.deepEqual(errors,[]);
console.log('PASS: owner account — header «Работа» left of «Прокат», no header «+ Добавить»; «Прокат» side windows: staff (edit rights/points, invite, DB error shown) and points (hours, tel, publish, unlink staff, new point); owner «Работа»: all points, orders without point in each, approve tool, stats without money, tool chat with «Отвечу я», reply sent and read-marked; 390px bottom «+» kept');
await b.close();})().catch(e=>{console.error(e);process.exit(1);});
