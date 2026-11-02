// Сайт: заявки сразу, без перезагрузки + кружок «Работа» / «Прокат» в шапке (просьба владельца 24.10.2026).
// Realtime подменён: страница получает «phx_reply ok» и сигнал iva_order_ping так же, как от Supabase.
const {chromium}=require('playwright');const assert=require('assert');
(async()=>{const b=await chromium.launch();
const iso=ms=>new Date(ms).toISOString(),T0=Date.parse('2026-10-24T09:00:00Z');
const fakeWS=(joinOk)=>`(function(){
  window.__rt={sent:[],socks:[],push:function(rec){this.socks.forEach(function(s){s._in({topic:'realtime:public:iva_order_ping',event:'postgres_changes',payload:{data:{type:'UPDATE',record:rec}}});});}};
  function W(url){this.url=url;this.readyState=0;var me=this;window.__rt.socks.push(this);setTimeout(function(){me.readyState=1;me.onopen&&me.onopen();},5);}
  W.prototype.send=function(t){var d=JSON.parse(t);window.__rt.sent.push(d);var me=this;
    if(d.event==='phx_join')setTimeout(function(){me._in({topic:d.topic,event:'phx_reply',ref:d.ref,payload:{status:${joinOk?"'ok'":"'error'"},response:{}}});},5);};
  W.prototype._in=function(d){this.onmessage&&this.onmessage({data:JSON.stringify(d)});};
  W.prototype.close=function(){this.readyState=3;};
  window.WebSocket=W;})();`;
async function ctx(opt){
  const c=await b.newContext({viewport:opt.vp||{width:1280,height:1000},timezoneId:'Europe/Moscow'});
  await c.addInitScript(fakeWS(opt.joinOk!==false));
  await c.route('https://*.supabase.co/**',async route=>{const r=route.request(),u=new URL(r.url());let data=[];
    if(u.pathname==='/auth/v1/user')data={id:'30000000-0000-4000-8000-00000000000'+(opt.lord?'2':'3'),email:opt.email,email_confirmed_at:'2026-01-01T00:00:00.000Z',user_metadata:{}};
    else if(u.pathname.endsWith('/admins'))data=opt.lord?[{email:opt.email,role:'admin',active:true,fee_pct:1,debt_limit:5000,company:'Прокат у дома',full_name:'Владелец',phone:'+79001112233'}]:[];
    else if(u.pathname.endsWith('/order_parts')){opt.reloads=(opt.reloads||0)+1;data=opt.parts||[];}
    else if(u.pathname.endsWith('/rpc/iva_staff_me')){opt.me=(opt.me||0)+1;data=opt.staff?[opt.staff]:[];}
    else if(u.pathname.endsWith('/rpc/iva_staff_parts')){opt.sreloads=(opt.sreloads||0)+1;data=opt.sparts||[];}
    else if(u.pathname.endsWith('/rpc/iva_order_cards'))data=[];
    await route.fulfill({json:data});});
  if(opt.clock)await c.clock.install({time:T0});
  const p=await c.newPage();const errors=[];p.on('pageerror',e=>errors.push(e.message));
  await p.goto('http://127.0.0.1:8000/404.html');
  await p.evaluate(()=>{localStorage.setItem('iva_auth',JSON.stringify({tok:'test-token',ref:'test-refresh',exp:9999999999999}));localStorage.setItem('iva_mode','lend');});
  return {c,p,errors};
}
const part=(id,st,tool)=>({id,order_id:String(500+id),owner_email:'qa-owner@example.invalid',tools_text:tool,status:st,fee:0,rent_sum:0,fee_pct:1,created_at:iso(T0-60e3),archived:false,
  orders:{name:'Клиент '+id,phone:'',user_email:'c'+id+'@example.invalid',tools:'',get_method:'Самовывоз',address:'',comment:'',days:1,created_at:iso(T0-60e3)}});

// 1. Владелец: вкладка «Мои объявления» (не «Заявки») — сигнал базы → заявка перечитана сразу, сообщение, число у вкладки
{const o={lord:true,email:'qa-owner@example.invalid',parts:[part(1,'rented','Дрель')],clock:true};const {c,p,errors}=await ctx(o);
 await p.goto('http://127.0.0.1:8000/account.html#ads',{waitUntil:'domcontentloaded'});await p.waitForSelector('.tabs button[data-tab=orders]');
 await c.clock.runFor(200);
 assert.equal(await p.evaluate(()=>window.__rt.sent.filter(d=>d.event==='phx_join'&&d.topic==='realtime:public:iva_order_ping').length),1,'одна подписка на iva_order_ping');
 // кружок «Прокат» слева от аватарки
 const wl=p.locator('#workLink');assert(!(await wl.getAttribute('class')).includes('hide'));assert.equal((await wl.locator('.work-t').textContent()),'Прокат');
 assert.equal(await wl.locator('.work-ini').getAttribute('data-ava-org'),'1');assert.equal(await wl.getAttribute('title'),'Прокат · Прокат у дома');
 assert.equal(await p.evaluate(()=>{const w=document.getElementById('workLink'),a=document.getElementById('acct');return w.nextElementSibling===a;}),true,'кружок прямо слева от аватарки');
 const r0=o.reloads;o.parts=[part(2,'new','Бетономешалка'),part(1,'rented','Дрель')];
 await p.evaluate(()=>window.__rt.push({email:'qa-owner@example.invalid',kind:'new',part_id:2}));
 await c.clock.runFor(1500);await p.waitForFunction(()=>/Бетономешалка/.test((document.getElementById('toast')||{}).textContent||''));
 assert(o.reloads>r0,'перечитал заявки по сигналу');
 assert.equal((await p.locator('.tabs button[data-tab=orders] .n').textContent()).trim(),'2');
 assert(await p.locator('.tabs button[data-tab=ads].on').count()===1,'вкладку не сменили');
 // «Заявки»: строка «обновляется сразу» и кнопка уведомлений браузера
 await p.locator('.tabs button[data-tab=orders]').click();await p.waitForSelector('#ordUpdLine');
 const line=await p.locator('#ordUpdLine').textContent();assert(line.includes('обновляется сразу'),line);
 // опрос раз в 30 секунд тоже на любой вкладке
 await p.locator('.tabs button[data-tab=ads]').click();const r1=o.reloads;o.parts=[part(3,'new','Плиткорез')].concat(o.parts);
 await c.clock.fastForward(31000);await p.waitForFunction(()=>/Плиткорез/.test((document.getElementById('toast')||{}).textContent||''));assert(o.reloads>r1);
 // другая страница: сигнал «new» → сообщение и число на кружке; «Заявки» — число сбрасывается
 await p.goto('http://127.0.0.1:8000/catalog.html',{waitUntil:'domcontentloaded'});await p.waitForSelector('#workLink:not(.hide)');await c.clock.runFor(200);
 await p.evaluate(()=>window.__rt.push({email:'qa-owner@example.invalid',kind:'new',part_id:4}));
 await p.waitForFunction(()=>/Новая заявка — откройте «Прокат» вверху/.test((document.getElementById('toast')||{}).textContent||''));
 assert.equal((await p.locator('#workLink .work-count').textContent()).trim(),'1');assert.equal(await p.locator('#workLink').getAttribute('href'),'account.html#orders');
 await p.evaluate(()=>window.__rt.push({email:'someone@else',kind:'new',part_id:5}));await c.clock.runFor(300);
 assert.equal((await p.locator('#workLink .work-count').textContent()).trim(),'1','чужая строка не считается');
 await p.evaluate(()=>window.__rt.push({email:'qa-owner@example.invalid',kind:'upd',part_id:4}));await c.clock.runFor(300);
 assert.equal((await p.locator('#workLink .work-count').textContent()).trim(),'1','«upd» — без числа');
 await p.goto('http://127.0.0.1:8000/account.html?x=1#orders',{waitUntil:'domcontentloaded'});await p.waitForSelector('#ordUpdLine');
 assert.equal(await p.locator('#workLink .work-count').isVisible(),false);
 assert.deepEqual(errors,[]);await c.close();}

// 2. Сотрудник: кружок «Работа» с логотипом проката (по пункту), на телефоне — в шапке справа; сигнал → «Работа» перечитана
{const st={id:'s1',status:'active',company:'Прокат у дома',can_issue:true,can_catalog:false,can_chat:false,can_notify:true,self_issue:false,points:[{id:'11111111-0000-0000-0000-000000000001',name:'Склад'}],can_delete:false};
 const sp=(id,s,tool)=>({part_id:String(id),order_id:String(700+id),point_id:'11111111-0000-0000-0000-000000000001',point_name:'Склад',tools_text:tool,status:s,created_at:iso(Date.now()),mine:false,can_act:true,can_notify:true});
 const o={email:'qa-staff@example.invalid',staff:st,sparts:[sp(1,'rented','Дрель')]};const {c,p,errors}=await ctx(o);
 await p.goto('http://127.0.0.1:8000/index.html',{waitUntil:'domcontentloaded'});await p.waitForSelector('#workLink:not(.hide)');
 assert.equal(await p.locator('#workLink .work-t').textContent(),'Работа');assert.equal(await p.locator('#workLink').getAttribute('href'),'account.html#staff');
 assert.equal(await p.locator('#workLink .work-ini').getAttribute('data-ava-point'),'11111111-0000-0000-0000-000000000001');
 assert.equal((await p.locator('#workLink .work-ini').textContent()).trim().length>0,true);
 const me0=o.me;await p.goto('http://127.0.0.1:8000/catalog.html',{waitUntil:'domcontentloaded'});await p.waitForSelector('#workLink:not(.hide)');
 assert.equal(o.me,me0,'iva_staff_me не спрашиваем на каждой странице (кэш 5 минут)');
 await p.goto('http://127.0.0.1:8000/account.html#orders',{waitUntil:'domcontentloaded'});await p.waitForSelector('.tabs button[data-tab=staff]');await p.waitForTimeout(300);
 const s0=o.sreloads;o.sparts=[sp(2,'new','Перфоратор'),sp(1,'rented','Дрель')];
 await p.evaluate(()=>window.__rt.push({email:'qa-staff@example.invalid',kind:'new',part_id:2}));
 await p.waitForFunction(()=>/Перфоратор/.test((document.getElementById('toast')||{}).textContent||''),null,{timeout:5000});
 assert(o.sreloads>s0);assert.equal((await p.locator('.tabs button[data-tab=staff] .n').textContent()).trim(),'1');
 // телефон: кружок в шапке рядом с городом
 await p.setViewportSize({width:390,height:800});await p.goto('http://127.0.0.1:8000/index.html?m=1',{waitUntil:'domcontentloaded'});
 await p.waitForSelector('#workMob',{state:'visible'});assert.equal(await p.locator('#workLink').isVisible(),false);
 const bx=await p.locator('#workMob').boundingBox();assert(bx.width>=30&&bx.width<=40&&bx.y<60,JSON.stringify(bx));
 assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth<=390),true,'шапка влезает в 390px');
 if(process.env.IVA_SHOT_DIR)await p.screenshot({path:process.env.IVA_SHOT_DIR+'/live-staff-mobile.png',clip:{x:0,y:0,width:390,height:140}});
 assert.deepEqual(errors,[]);await c.close();}

// 3. Без SQL 20261119 (подписка не принята): «обновляется само каждые 30 секунд»; клиенту без работы кружка нет
{const o={email:'qa-client@example.invalid',joinOk:false};const {c,p,errors}=await ctx(o);
 await p.goto('http://127.0.0.1:8000/index.html',{waitUntil:'domcontentloaded'});await p.waitForTimeout(600);
 assert.equal(await p.locator('#workLink').isVisible(),false);assert.equal(await p.locator('#workMob').isVisible(),false);
 assert.equal(await p.evaluate(()=>IvaLive.isLive()),false);assert.equal(await p.evaluate(()=>IvaLive.updTxt()),'обновляется само каждые 30 секунд');
 assert.deepEqual(errors,[]);await c.close();}

await b.close();console.log('Заявки сразу + кружок «Работа» / «Прокат»: все проверки пройдены');})().catch(e=>{console.error(e);process.exit(1);});
