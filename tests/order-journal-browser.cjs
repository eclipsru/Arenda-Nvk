// «Выдал / Принял» на карточках заявок владельца (24.10.2026): iva_parts_journal (SQL 20261118) — кто выдал, кто принял,
// приём по срокам — у каждого инструмента; без функции в базе — прежняя строка «Выдан …»; сотрудник журнала не видит.
const {chromium}=require('playwright');const assert=require('assert');
(async()=>{const b=await chromium.launch();const c=await b.newContext({viewport:{width:1280,height:1000},timezoneId:'Europe/Moscow'});
const now=Date.now(),H=3600e3,iso=ms=>new Date(ms).toISOString(),email='qa-owner@example.invalid';
const pt={id:1,name:'Пункт на Ленина',address:'Ленина, 1',city:'Ростов-на-Дону'};
const mk=(id,st,o)=>Object.assign({id,order_id:String(700+id),owner_email:email,tools_text:'Заявка '+id,status:st,fee:0,rent_sum:0,fee_pct:1,created_at:iso(now-id*60e3),archived:false,
  orders:{name:'Клиент '+id,phone:'+7 900 111-22-'+String(id).padStart(2,'0'),user_email:'c'+id+'@example.invalid',get_method:'Самовывоз',days:1,created_at:iso(now-id*60e3)}},o||{});
const parts=[
  mk(31,'rented',{_rented:now-2*H,_items:[{id:'a',name:'Дрель',price:500,days:1,returned_at:iso(now-H)},{id:'b',name:'Пила',price:400,days:2}]}),
  mk(32,'closed',{_rented:now-30*H,_items:[{id:'c',name:'Перфоратор',price:700,days:1,returned_at:iso(now-5*H),used_days:1}]}),
  mk(33,'closed',{_rented:now-50*H,_items:[{id:'d',name:'Болгарка',price:300,days:1,returned_at:iso(now-26*H),used_days:1},{id:'e',name:'Лобзик',price:300,days:2,returned_at:iso(now-3*H),used_days:2}]}),
  mk(34,'new',{_items:[{id:'f',name:'Шуруповёрт',price:200,days:1}]})
];
const J={
  '31':{part_id:'31',issued_who:'Иван',issued_at:iso(now-2*H),returned_who:'',returned_at:null,items:[{n:0,id:'a',returned_who:'Пётр',returned_at:iso(now-H)}]},
  '32':{part_id:'32',issued_who:'Вы',issued_at:iso(now-30*H),returned_who:'Пётр',returned_at:iso(now-5*H),items:[{n:0,id:'c',returned_who:'Пётр',returned_at:iso(now-5*H)}]},
  '33':{part_id:'33',issued_who:'Иван',issued_at:iso(now-50*H),returned_who:'Вы',returned_at:iso(now-3*H),
        items:[{n:0,id:'d',returned_who:'Пётр',returned_at:iso(now-26*H)},{n:1,id:'e',returned_who:'Вы',returned_at:iso(now-3*H)}]}
};
const card=p=>({order_id:p.order_id,created_at:p.created_at,get_method:'Самовывоз',address:'',comment:'',days:1,client_name:p.orders.name,client_tel:p.orders.phone,
  parts:[{part_id:String(p.id),order_id:p.order_id,status:p.status,created_at:p.created_at,rented_at:p._rented?iso(p._rented):null,rent_sum:0,fee:0,tools_text:p.tools_text,
    point:pt,rent_mode:'day',close_min:[1140,1140,1140,1140,1140,1140,1140],tz:'Europe/Moscow',items:p._items,change:null,cancel:null}]});
let noJournal=false,asked=null,calls=0;
await c.route('https://*.supabase.co/**',async route=>{const r=route.request(),u=new URL(r.url());let data=[];
  if(u.pathname==='/auth/v1/user')data={id:'30000000-0000-4000-8000-000000000003',email,email_confirmed_at:'2026-01-01T00:00:00.000Z',user_metadata:{}};
  else if(u.pathname.endsWith('/admins'))data=[{email,role:'admin',active:true,fee_pct:1,debt_limit:5000,company:'Прокат у дома',full_name:'Владелец'}];
  else if(u.pathname.endsWith('/order_parts'))data=parts.map(p=>{const x=Object.assign({},p);delete x._rented;delete x._items;return x;});
  else if(u.pathname.endsWith('/rpc/iva_order_cards')){data=JSON.parse(r.postData()||'{}').p_scope==='admin'?parts.map(card):[];}
  else if(u.pathname.endsWith('/rpc/iva_parts_journal')){calls++;if(noJournal){await route.fulfill({status:404,json:{code:'PGRST202',message:'Could not find the function public.iva_parts_journal(p_parts) in the schema cache'}});return;}
    asked=JSON.parse(r.postData()).p_parts;data=asked.map(String).filter(id=>J[id]).map(id=>J[id]);}
  await route.fulfill({json:data});});
const p=await c.newPage();const errors=[];p.on('pageerror',e=>errors.push(e.message));
await p.goto('http://127.0.0.1:8000/',{waitUntil:'domcontentloaded'});await p.evaluate(()=>localStorage.setItem('iva_auth',JSON.stringify({tok:'test-token',ref:'test-refresh',exp:9999999999999})));
const open=async q=>{await p.goto('http://127.0.0.1:8000/account.html'+q+'#orders',{waitUntil:'domcontentloaded'});await p.waitForSelector('#ordList .order-card');};
const txt=async id=>(await p.locator('[data-order-id="'+id+'"]').textContent()).replace(/\s+/g,' ');
await open('?a=1');
// запрос — только выданные и закрытые части, одним вызовом
assert.deepEqual(asked.slice().sort(),[31,32,33]);assert.equal(calls,1);
let t=await txt(31);assert(/Выдал: Иван · \d/.test(t),t);assert(/Дрель.*возвращён .* · принял: Пётр/.test(t),t);assert(!/Выдан \d/.test(t));assert(!t.includes('Принял:'));
t=await txt(32);assert(/Выдал: Вы · \d/.test(t),t);assert(/Принял: Пётр · \d/.test(t),t);assert(!t.includes('принял: Пётр'),t);
t=await txt(33);assert(t.includes('Выдал: Иван'),t);assert(/Болгарка.*принял: Пётр/.test(t),t);assert(/Лобзик.*принял: Вы/.test(t),t);assert(!t.includes('Принял:'),t);
t=await txt(34);assert(!t.includes('Выдал:'),t);
// поиск по сотруднику
await p.locator('#ordQ').fill('пётр');await p.waitForFunction(()=>document.querySelectorAll('#ordList .order-card').length===3);
await p.locator('#ordQ').fill('иван');await p.waitForFunction(()=>document.querySelectorAll('#ordList .order-card').length===2);
// база без функции: прежняя строка «Выдан …», без ошибок, повторно не спрашиваем
noJournal=true;calls=0;await open('?a=2');
t=await txt(31);assert(/Выдан \d/.test(t),t);assert(!t.includes('Выдал:'));assert(!t.includes('принял'));
await p.locator('[data-ord-reload]').click();await p.waitForTimeout(400);assert.equal(calls,1);
assert.deepEqual(errors,[]);
console.log('PASS: order journal — «Выдал / Принял» on owner cards, per-item receivers for split returns, one batched RPC (rented+closed only), search by staff name, graceful without SQL 20261118');
await b.close();})().catch(e=>{console.error(e);process.exit(1);});
