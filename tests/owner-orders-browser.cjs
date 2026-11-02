// «Заявки» владельца на сайте (24.10.2026, «всё по порядку», пункт 4): плитки-фильтры, просроченные первыми, поиск, пункты, автообновление.
const {chromium}=require('playwright');const assert=require('assert');
(async()=>{const b=await chromium.launch();const c=await b.newContext({viewport:{width:1280,height:1000},timezoneId:'Europe/Moscow'});
const T0=Date.parse('2026-10-24T09:00:00Z');// 12:00 по Москве
const iso=ms=>new Date(ms).toISOString(),H=3600e3,email='qa-owner@example.invalid';
const A={id:1,name:'Пункт на Ленина',address:'Ленина, 1',city:'Ростов-на-Дону'},B={id:2,name:'Склад на Шолохова',address:'Шолохова, 5',city:'Ростов-на-Дону'};
const mk=(id,st,pt,o)=>Object.assign({id,order_id:String(500+id),owner_email:email,tools_text:'Инструмент '+id,status:st,fee:0,rent_sum:0,fee_pct:1,created_at:iso(T0-id*60e3),archived:false,
  orders:{name:'Клиент '+id,phone:'+7 900 000-00-'+String(id).padStart(2,'0'),user_email:'c'+id+'@example.invalid',tools:'',get_method:'Самовывоз',address:'',comment:'',days:1,created_at:iso(T0-id*60e3)},_pt:pt},o||{});
let parts=[
  mk(11,'new',A,{_change:{id:'ch1',state:'pending',before:{items:[{id:'t1',name:'Дрель',days:1}]},after:{items:[{id:'t1',name:'Дрель',days:2}]}}}),
  mk(12,'rented',B,{_rented:T0-5*24*H,tools_text:'Перфоратор Makita'}),
  mk(13,'rented',B,{_rented:T0-23*H}),           // вернуть сегодня в 13:00
  mk(14,'new',A),
  mk(15,'closed',A),
  mk(16,'cancelled',A),
  mk(17,'closed',A,{archived:true}),
  mk(19,'rented',A,{_rented:T0-2*H})            // вернуть завтра
];
const card=p=>({order_id:p.order_id,created_at:p.created_at,get_method:'Самовывоз',address:'',comment:'',days:1,client_name:p.orders.name,client_tel:p.orders.phone,
  parts:[{part_id:String(p.id),order_id:p.order_id,status:p.status,created_at:p.created_at,rented_at:p._rented?iso(p._rented):null,rent_sum:0,fee:0,tools_text:p.tools_text,
    point:p._pt,rent_mode:'day',close_min:[1140,1140,1140,1140,1140,1140,1140],tz:'Europe/Moscow',items:[{id:'t'+p.id,name:p.tools_text,price:500,days:1,img:''}],
    change:p._change||null,cancel:null}]});
let reloads=0,noCards=false;
await c.route('https://*.supabase.co/**',async route=>{const r=route.request(),u=new URL(r.url());let data=[];
  if(u.pathname==='/auth/v1/user')data={id:'30000000-0000-4000-8000-000000000002',email,email_confirmed_at:'2026-01-01T00:00:00.000Z',user_metadata:{}};
  else if(u.pathname.endsWith('/admins'))data=[{email,role:'admin',active:true,fee_pct:1,debt_limit:5000,company:'Прокат у дома',full_name:'Владелец',phone:'+79001112233'}];
  else if(u.pathname.endsWith('/order_parts')){reloads++;data=parts.map(p=>{const x=Object.assign({},p);delete x._pt;delete x._change;delete x._rented;return x;});}
  else if(u.pathname.endsWith('/rpc/iva_order_cards')){if(noCards){await route.fulfill({status:404,json:{code:'PGRST202',message:'not found'}});return;}const s=JSON.parse(r.postData()||'{}').p_scope;data=s==='admin'?parts.map(card):[];}
  else if(u.pathname.endsWith('/rpc/iva_staff_me'))data=null;
  await route.fulfill({json:data});});
await c.clock.install({time:T0});
const p=await c.newPage();const errors=[];p.on('pageerror',e=>errors.push(e.message));
await p.goto('http://127.0.0.1:8000/');await p.evaluate(()=>localStorage.setItem('iva_auth',JSON.stringify({tok:'test-token',ref:'test-refresh',exp:9999999999999})));
await p.goto('http://127.0.0.1:8000/account.html#orders',{waitUntil:'domcontentloaded'});await p.waitForSelector('#ordKpis');
const groups=async()=>(await p.locator('#ordList .st-g').allTextContents());
const kpi=async f=>(await p.locator('[data-ord-f='+f+'] .vl').textContent()).trim();
const ids=async()=>p.locator('#ordList .order-card').evaluateAll(a=>a.map(x=>x.getAttribute('data-order-id')));
// группы: просьба изменить → просроченные → новые → в аренде (ближайший возврат сверху) → завершены → отменены; архив скрыт
assert.deepEqual(await groups(),['Клиент просит изменить · 1','Просрочены · 1','Новые · 1','В аренде · 2','Завершены · 1','Отменены клиентом · 1']);
assert.deepEqual(await ids(),['11','12','14','13','19','15','16']);
assert.equal(await kpi('new'),'2');assert.equal(await kpi('late'),'1');assert.equal(await kpi('today'),'1');assert.equal(await kpi('rented'),'3');
// плитка — фильтр; повторное нажатие снимает
await p.locator('[data-ord-f=late]').click();assert.deepEqual(await ids(),['12']);assert((await p.locator('#ordUpdLine').textContent()).includes('фильтр «Просрочены»'));
await p.locator('[data-ord-f=late]').click();assert.equal((await ids()).length,7);
await p.locator('[data-ord-f=today]').click();assert.deepEqual(await ids(),['13']);
await p.locator('.st-link[data-ord-f=all]').click();
await p.locator('[data-ord-f=rented]').click();assert.deepEqual(await ids(),['12','13','19']);await p.locator('[data-ord-f=rented]').click();
// фильтры истории
await p.locator('[data-ost=archived]').click();assert.deepEqual(await ids(),['17']);assert.deepEqual(await groups(),['Архив · 1']);
await p.locator('[data-ost=change]').click();assert.deepEqual(await ids(),['11']);
await p.locator('[data-ost=all]').click();
// поиск: по телефону (цифры), по инструменту, по №; пусто → «Показать все заявки»
const search=async v=>{await p.locator('#ordQ').fill(v);await c.clock.runFor(300);};
await search('9000000014');assert.deepEqual(await ids(),['14']);
await search('перфоратор');assert.deepEqual(await ids(),['12']);
await search('№513');assert.deepEqual(await ids(),['13']);
await search('нет такого');assert.equal((await ids()).length,0);assert.equal(await p.locator('[data-ord-clear]').count(),1);
await p.locator('[data-ord-clear]').click();assert.equal(await p.locator('#ordQ').inputValue(),'');assert.equal((await ids()).length,7);
// пункты: два пункта — выбор
assert.equal(await p.locator('[data-ord-pt]').count(),3);
await p.locator('[data-ord-pt="2"]').click();assert.deepEqual(await ids(),['12','13']);assert.equal(await kpi('new'),'0');
await p.locator('[data-ord-pt=all]').click();
// автообновление: новая заявка появляется сама, подсвечивается и всплывает сообщение; ввод в поиске не сбивается
const r0=reloads;parts.unshift(mk(20,'new',A,{tools_text:'Бетономешалка'}));
await p.locator('#ordQ').focus();await p.keyboard.type('бетон');
await c.clock.fastForward(31000);await p.waitForFunction(()=>document.querySelector('.st-fresh [data-order-id="20"]'));
assert(reloads>r0);assert.equal(await p.locator('#ordQ').inputValue(),'бетон');
assert.equal(await p.evaluate(()=>document.activeElement&&document.activeElement.id),'ordQ');
assert.deepEqual(await ids(),['20']);
assert.equal((await p.locator('.tabs button[data-tab=orders] .n').textContent()).trim(),'6');assert((await p.locator('[data-ord-pt=all]').textContent()).includes('· 6'));
assert((await p.locator('#toast, .toast').first().textContent()).includes('Бетономешалка'));
await search('');assert.equal(await kpi('new'),'3');assert((await p.locator('#ordUpdLine').textContent()).includes('Обновлено 12:00'));
// «Сводка» → «Клиент просит изменить» ведёт на фильтр «Ждут решения»
await p.goto('http://127.0.0.1:8000/account.html#home',{waitUntil:'domcontentloaded'});await p.waitForSelector('.home-row');
await p.locator('.home-row',{hasText:'Клиент просит изменить'}).click();await p.waitForSelector('#ordKpis');assert.deepEqual(await ids(),['11']);
// база без iva_order_cards (SQL 1112 не выполнен) — простые карточки, группы по статусу, поиск работает
noCards=true;await p.goto('http://127.0.0.1:8000/account.html?nocards=1#orders',{waitUntil:'domcontentloaded'});await p.waitForSelector('#ordKpis');
assert.deepEqual(await groups(),['Новые · 3','В аренде · 3','Завершены · 1','Отменены клиентом · 1']);
assert.equal(await p.locator('#ordList .lc-card').count(),0);assert.equal(await p.locator('[data-ord-pt]').count(),0);
await search('перфоратор');assert.deepEqual(await ids(),['12']);await search('');
// мобильный экран без горизонтальной прокрутки
await p.setViewportSize({width:390,height:900});assert(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
assert.deepEqual(errors,[]);
console.log('PASS: owner orders — groups (change → late → new → rented by due → closed → cancelled), KPI tiles toggle, history chips, search by phone/tool/№, points, 30 s auto-refresh keeps search focus, Сводка link, 390px');
await b.close();})().catch(e=>{console.error(e);process.exit(1);});
