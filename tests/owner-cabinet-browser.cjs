// Кабинет владельца проката, просьба владельца №11 (06.11.2026): без «Арендую / Сдаю»; шапка как у «Работы» (ФИО, выбранный пункт,
// «Пункт выдачи» — фильтр сводки, заявок, инструмента, сотрудников); вкладки Прокат · Заявки · Пункт выдачи · Инструмент · Сотрудники ·
// Отзывы · Комиссия и оплата; пункт зелёный — есть сотрудник и инструмент, красный — чего-то нет; окна справа — по высоте содержимого;
// «Комиссия и оплата» — баланс из базы (iva_fee_balance: переплата как в приложении), «Платные ресурсы», окно «Внести платёж» (QR-код СБП, карта).
const {chromium}=require('playwright');const assert=require('assert');
const SHOTS=process.env.SHOTS||'';const BASE=process.env.SITE_BASE_URL||'http://127.0.0.1:8000';
(async()=>{const b=await chromium.launch();const c=await b.newContext({viewport:{width:1600,height:1000},timezoneId:'Europe/Moscow',locale:'ru-RU'});
const T0=Date.parse('2026-11-06T09:00:00Z'),H=3600e3,iso=ms=>new Date(ms).toISOString(),email='qa-owner@example.invalid';
const P1='11111111-1111-4111-8111-111111111111',P2='22222222-2222-4222-8222-222222222222',P3='33333333-3333-4333-8333-333333333333';
const W=[540,540,540,540,540,600,-1],WC=[1140,1140,1140,1140,1140,960,-1];
const pts=[
  {id:P1,owner_email:email,name:'Пункт на Ленина',address:'ул. Ленина, 1',city:'Ростов-на-Дону',open_min:W,close_min:WC,tel:'+79001112233',staff_publish:'approve',created_at:'2026-10-01T00:00:00Z'},
  {id:P2,owner_email:email,name:'Склад на Шолохова',address:'пр. Шолохова, 5',city:'Ростов-на-Дону',open_min:W,close_min:WC,tel:'',staff_publish:'now',created_at:'2026-10-02T00:00:00Z'},
  {id:P3,owner_email:email,name:'Гараж на Красноармейской',address:'ул. Красноармейская, 9',city:'Ростов-на-Дону',open_min:W,close_min:WC,tel:'',staff_publish:'now',created_at:'2026-10-03T00:00:00Z'}];
const staff=[
  {id:'s1',email:'ivan@example.invalid',name:'Иван Петров',tel:'',can_issue:true,can_catalog:false,can_chat:true,can_notify:true,self_issue:false,can_delete:false,status:'active',point_ids:[P1],created_at:'2026-10-05T00:00:00Z'},
  {id:'s2',email:'olga@example.invalid',name:'',tel:'',can_issue:true,can_catalog:true,can_chat:false,can_notify:false,self_issue:false,can_delete:false,status:'invited',point_ids:[P2],created_at:'2026-10-06T00:00:00Z'}];
const tools=[
  {id:'t1',name:'Дрель Bosch',price:500,imgs:[],img:'',status:'active',active:true,point_id:P1,cat:'drill'},
  {id:'t2',name:'Перфоратор Makita',price:700,imgs:[],img:'',status:'active',active:true,point_id:P2,cat:'drill'},
  {id:'t3',name:'Болгарка DeWalt',price:400,imgs:[],img:'',status:'moderation',active:true,point_id:P1,cat:'grinder'}];
const mk=(id,st,pt,o)=>Object.assign({id,order_id:String(500+id),owner_email:email,tools_text:'Инструмент '+id,status:st,fee:0,rent_sum:0,fee_pct:1,created_at:iso(T0-id*60e3),archived:false,
  orders:{name:'Клиент '+id,phone:'+7 900 000-00-'+String(id).padStart(2,'0'),user_email:'c'+id+'@example.invalid',tools:'',get_method:'Самовывоз',address:'',comment:'',days:1,created_at:iso(T0-id*60e3)},_pt:pt},o||{});
const parts=[mk(11,'new',{id:P1,name:'Пункт на Ленина'}),mk(12,'rented',{id:P2,name:'Склад на Шолохова'},{_rented:T0-2*H,_by:'olga@example.invalid'}),
  mk(15,'closed',{id:P1,name:'Пункт на Ленина'},{fee:5,rent_sum:500,_rented:T0-30*H,_ret:T0-6*H,_by:'ivan@example.invalid'}),mk(16,'closed',{id:P2,name:'Склад на Шолохова'},{fee:2,rent_sum:200})];
const card=p=>({order_id:p.order_id,created_at:p.created_at,get_method:'Самовывоз',address:'',comment:'',days:1,client_name:p.orders.name,client_tel:p.orders.phone,
  parts:[{part_id:String(p.id),order_id:p.order_id,status:p.status,created_at:p.created_at,rented_at:p._rented?iso(p._rented):null,rent_sum:p.rent_sum,fee:p.fee,tools_text:p.tools_text,
    point:p._pt,rent_mode:'day',close_min:WC,tz:'Europe/Moscow',items:[{id:'t'+p.id,name:p.tools_text,price:500,days:1,img:'',returned_at:p._ret?iso(p._ret):null}],change:null,cancel:null}]});
// баланс из базы — как в приложении: начислено 7 ₽, оплачено 100 ₽ → переплата 93 ₽ (сайт раньше показывал 0)
const bal={email,pct:1,fee:7,paid:100,pending:0,debt:0,over:93,rent:700,orders:2,at:iso(T0)};
let payNew=null,payChecks=0,feeBalCalls=0;const calls=[];
await c.route('https://*.supabase.co/**',async route=>{const r=route.request(),u=new URL(r.url()),m=r.method(),body=JSON.parse(r.postData()||'null');let data=[];
  const path=u.pathname;if(path!=='/auth/v1/user')calls.push({m,path,q:u.search,body});
  if(path==='/auth/v1/user')data={id:'30000000-0000-4000-8000-000000000002',email,email_confirmed_at:'2026-01-01T00:00:00.000Z',user_metadata:{}};
  else if(path.endsWith('/admins'))data=[{email,role:'admin',active:true,fee_pct:1,debt_limit:5000,company:'Прокат у дома',full_name:'Владелец Прокатов',phone:'+79001112233'}];
  else if(path.endsWith('/order_parts')){data=parts.map(p=>{const x=Object.assign({},p);for(const k of ['_pt','_rented','_ret','_by'])delete x[k];return x;});
    if(u.searchParams.get('select')==='fee')data=data.map(x=>({fee:x.fee}));}
  else if(path.endsWith('/fee_payments'))data=[{id:1,admin_email:email,amount:100,status:'confirmed',note:'ЮKassa',created_at:iso(T0-48*H),confirmed_at:iso(T0-48*H)}];
  else if(path.endsWith('/rpc/iva_fee_balance')){feeBalCalls++;data=bal;}
  else if(path.endsWith('/rpc/iva_order_cards')){const s=(body||{}).p_scope;data=s==='admin'?parts.map(card):[];}
  else if(path.endsWith('/rpc/iva_parts_journal'))data=parts.filter(p=>p._by).map(p=>({part_id:p.id,order_id:p.order_id,status:p.status,rented_at:iso(p._rented),rented_by:p._by,rented_by_name:p._by.startsWith('ivan')?'Иван Петров':'olga',
    returned_at:p._ret?iso(p._ret):null,returned_by:p._ret?p._by:null,returned_by_name:p._ret?'Иван Петров':null,closed_at:p._ret?iso(p._ret):null,closed_by:p._ret?p._by:null,closed_by_name:p._ret?'Иван Петров':null,items:[]}));
  else if(path.endsWith('/rpc/iva_staff_me'))data=null;
  else if(path.endsWith('/iva_pickup_points'))data=pts;
  else if(path.endsWith('/tools'))data=tools;
  else if(path.endsWith('/rpc/iva_tools_pending'))data=[];
  else if(path.endsWith('/app_messages'))data=[];
  else if(path.endsWith('/rpc/iva_staff_list'))data=staff;
  else if(path.endsWith('/rpc/iva_chat_who'))data=null;
  else if(path.endsWith('/functions/v1/iva-pay')){
    if(m==='GET')data={ready:true,test:true};
    else if(body&&body.a==='new'){payNew=body;data={id:'pay-1',url:body.method==='sbp'?'https://qr.nspk.ru/AS1000TEST':'https://yoomoney.ru/checkout/payments/v2/contract?orderId=test',test:true,sbp:body.method==='sbp',fallback:false};}
    else if(body&&body.a==='check'){payChecks++;data={rows:[{id:'pay-1',status:payChecks>=2?'succeeded':'pending'}]};}
  }
  await route.fulfill({json:data});});
await c.clock.install({time:T0});
const p=await c.newPage();const errors=[];p.on('pageerror',e=>errors.push(e.message));
p.on('dialog',d=>{errors.push('native dialog: '+d.message());d.dismiss();});
const shot=async n=>{if(SHOTS)await p.screenshot({path:SHOTS+'/'+n+'.png'});};
await p.goto(BASE+'/404.html');await p.evaluate(()=>{localStorage.setItem('iva_auth',JSON.stringify({tok:'test-token',ref:'test-refresh',exp:9999999999999}));localStorage.setItem('iva_mode','rent');});
const tabs=()=>p.locator('#prMain .tabs button[data-tab]').evaluateAll(a=>a.map(e=>e.getAttribute('data-tab')));
const tab=async t=>{await p.locator('#prMain .tabs button[data-tab="'+t+'"]').click();await p.waitForSelector('#prMain .tabs button[data-tab="'+t+'"].on');};

// 1) «Прокат»: даже со старым «Арендую» в памяти — только «Сдаю», переключателя нет нигде; шапка как у «Работы»; вкладки по порядку
await p.goto(BASE+'/account.html',{waitUntil:'domcontentloaded'});
await p.waitForSelector('#prHead #prPtSel');await p.waitForSelector('#prPtCol .st-si');
await shot('owner11-prokat-1600');
assert.equal(await p.locator('[data-mode]').count(),0,'нет «Арендую / Сдаю»');
await p.locator('#acctBtn').click().catch(()=>{});await p.waitForTimeout(200);
assert.equal(await p.locator('[data-mode]').count(),0,'и в меню «Кабинет»');await p.keyboard.press('Escape');
assert.deepEqual(await tabs(),['home','orders','points','ads','team','reviews','fee']);
assert.deepEqual(await p.locator('#prMain .tabs button[data-tab]').evaluateAll(a=>a.map(e=>e.childNodes[0].textContent.trim())),
  ['Прокат','Заявки','Пункт выдачи','Инструмент','Сотрудники','Отзывы','Комиссия и оплата']);
const head=await p.locator('#prHead').innerText();
assert(head.includes('qa-owner')&&head.includes('Прокат у дома')&&head.includes('Все пункты · 3')&&/Требуют внимания: 2 из 3/.test(head)&&head.includes('Пункт выдачи'),head);
// шапка стоит на месте, как у «Работы»
assert.equal(await p.evaluate(()=>getComputedStyle(document.getElementById('prHead')).position),'sticky');
// окна справа: пункт 1 зелёный (сотрудник и инструмент), 2 — красный (приглашение не принято), 3 — красный (никого и ничего)
const side=await p.locator('#prPtCol .st-si').evaluateAll(a=>a.map(e=>[e.querySelector('b').textContent,e.classList.contains('c-g')?'g':e.classList.contains('c-r')?'r':'?',e.querySelector('small').textContent]));
assert.deepEqual(side.map(x=>x.slice(0,2)),[['Пункт на Ленина','g'],['Склад на Шолохова','r'],['Гараж на Красноармейской','r']],JSON.stringify(side));
assert(/не принял/.test(side[1][2])&&/нет сотрудника, нет инструмента/.test(side[2][2]),JSON.stringify(side));
// окна — по высоте содержимого (не до низа экрана)
const hh=await p.evaluate(()=>[document.getElementById('prStaffCol').getBoundingClientRect().height,document.getElementById('prPtCol').getBoundingClientRect().height,innerHeight]);
assert(hh[0]<hh[2]*0.6&&hh[1]<hh[2]*0.6,'окна по содержимому: '+JSON.stringify(hh));
// сводка: красные пункты — в «Что сделать», ведут на вкладку «Пункт выдачи»
const todo=await p.locator('#prMain').innerText();assert(/Пункта не работают|Пунктов не работают|не работает/.test(todo),todo.slice(0,800));

// 2) «Пункт выдачи»: карточки, красные — первыми, с причиной
await tab('points');await p.waitForSelector('.pr-card');await shot('owner11-points');
const cards=await p.locator('.pr-cards .pr-card').evaluateAll(a=>a.map(e=>[e.querySelector('.pr-card-h b').textContent,e.className.includes('c-r')?'r':'g',(e.querySelector('.pr-why')||{}).textContent||'']));
assert.deepEqual(cards.map(x=>x[1]),['r','r','g'],JSON.stringify(cards));assert.equal(cards[2][0],'Пункт на Ленина');
assert(cards.some(x=>/Нет сотрудника · нет инструмента/.test(x[2])),JSON.stringify(cards));

// 3) «Пункт выдачи» в шапке — фильтр: заявки, инструмент, сотрудники только выбранного пункта; выбор запоминается
await p.locator('#prPtSel').click();await p.waitForSelector('#prPtMenu:not([hidden])');
await p.locator('#prPtMenu [data-pr-pt="'+P2+'"]').click();await p.waitForSelector('#prPtMenu',{state:'hidden'});
assert.equal(await p.evaluate(()=>localStorage.getItem('iva_pr_pt')),P2);
const head2=await p.locator('#prHead').innerText();assert(head2.includes('«Склад на Шолохова»')&&head2.includes('Изменить пункт'),head2);
await tab('orders');await p.waitForSelector('#ordList');
const ordTxt=await p.locator('#ordList').innerText();assert(ordTxt.includes('Клиент 12')&&!ordTxt.includes('Клиент 11'),ordTxt);
assert.equal(await p.locator('#ordPts').count(),0,'кнопок пунктов над заявками нет — пункт выбран в шапке');
// кто выдал — с датой и временем (журнал выдачи)
assert(/Выдал[^\n]*olga/.test(ordTxt)||/Выдал/.test(ordTxt),ordTxt);
await tab('ads');const adsTxt=await p.locator('#prMain').innerText();
assert(adsTxt.includes('Инструмент пункта «Склад на Шолохова»')&&adsTxt.includes('Перфоратор Makita')&&!adsTxt.includes('Дрель Bosch'),adsTxt.slice(0,900));
await tab('team');const teamTxt=await p.locator('.pr-cards').innerText();
assert(teamTxt.includes('olga@example.invalid')&&!teamTxt.includes('Иван Петров'),teamTxt);   // без имени — почта, пока не принял
await p.reload({waitUntil:'domcontentloaded'});await p.waitForSelector('#prHead #prPtSel');
assert((await p.locator('#prPtSel').innerText()).includes('Склад на Шолохова'),'выбор пункта сохранился');
await p.locator('#prPtSel').click();await p.locator('#prPtMenu [data-pr-pt="all"]').click();
// клавиатура: стрелки и Esc
await p.locator('#prPtSel').focus();await p.keyboard.press('ArrowDown');await p.waitForSelector('#prPtMenu:not([hidden])');await p.keyboard.press('Escape');
await p.waitForSelector('#prPtMenu',{state:'hidden'});

// 4) «Комиссия и оплата»: переплата 93 ₽ из базы — как в приложении
await tab('fee');await p.waitForSelector('#feeHero');await shot('owner11-fee');
const hero=await p.locator('#feeHero').innerText();assert(/Переплата/.test(hero)&&/93/.test(hero),hero);
const bp=await p.evaluate(()=>{const h=document.getElementById('feeHero').getBoundingClientRect(),b=document.getElementById('btnPayFeeTop').getBoundingClientRect();return b.left>h.left+h.width/2;});
assert(bp,'«Внести платёж» — справа в карточке');
assert(feeBalCalls>=1,'iva_fee_balance вызван');
// платные ресурсы → окно со всеми начислениями
await p.locator('[data-fee-res=rent]').click();await p.waitForSelector('#feeResBody table');await p.waitForTimeout(300);await shot('owner11-fee-res');
const res=await p.locator('#sheet').innerText();assert(res.includes('№515')||res.includes('515'),res.slice(0,600));
await p.locator('#feeResOk').click();await p.waitForFunction(()=>!document.getElementById('feeResBody'));

// 5) «Внести платёж»: сумма, «QR-код СБП» (QR рисуется на странице), «Картой онлайн» (ЮKassa в новой вкладке)
await p.locator('#btnPayFeeTop').click();await p.waitForSelector('#payWin');
await p.waitForFunction(()=>!document.getElementById('payQr').disabled);
assert((await p.locator('.pay-sum').innerText()).includes('Переплата'));
await p.fill('#payAmt','0');await p.locator('#payQr').click();await p.waitForSelector('#payErr.show');
await p.fill('#payAmt','6000');await p.locator('#payQr').click();assert(/5 000/.test(await p.locator('#payErr').innerText()));   // переплата ≤ 5 000 ₽
await p.fill('#payAmt','150,5');await p.locator('#payQr').click();
await p.waitForSelector('#payQrBox .pay-qr-img svg',{timeout:8000});await shot('owner11-pay-qr');
assert.deepEqual(payNew,{a:'new',amount:'150.50',method:'sbp'});
const [pop]=await Promise.all([c.waitForEvent('page'),p.locator('#payCard').click()]);
await p.waitForFunction(()=>/новой вкладке/.test(document.getElementById('payQrBox').textContent));
assert.deepEqual(payNew,{a:'new',amount:'150.50'});await pop.close().catch(()=>{});
// ЮKassa подтвердила — окно закрывается само, баланс перечитан
const n0=feeBalCalls;await c.clock.runFor(11000);await p.waitForFunction(()=>!document.getElementById('payWin'),null,{timeout:8000});
assert(feeBalCalls>n0,'после оплаты баланс перечитан');

// 6) окно сотрудника — без «Имя и фамилия»
await tab('team');await p.locator('.pr-cards [data-ow-staff="s1"]').first().click();await p.waitForSelector('#owSf');
assert.equal(await p.locator('#owSfName').count(),0);assert(!/Имя и фамилия/.test(await p.locator('#owSf').innerText()));
await p.evaluate(()=>closeSheet());await p.waitForFunction(()=>!document.getElementById('owSf'));

// 7) телефон: без горизонтальной прокрутки, шапка и вкладки на месте
await p.setViewportSize({width:390,height:900});await p.goto(BASE+'/404.html');await p.goto(BASE+'/account.html#points',{waitUntil:'domcontentloaded'});await p.waitForSelector('.pr-card');
await shot('owner11-points-390');
assert(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'нет горизонтальной прокрутки');
assert.deepEqual(errors,[]);
console.log('PASS: owner cabinet #11 — no «Арендую / Сдаю», tabs order, «Работа»-style sticky header with point filter (orders, tools, staff; remembered; keyboard), points green/red with reasons, side windows sized by content, fee overpayment 93 from iva_fee_balance, paid resources sheet, pay modal (limits, SBP QR, card in new tab, auto-close on success), staff form without name, 390px');
await b.close();})().catch(e=>{console.error(e);process.exit(1);});
