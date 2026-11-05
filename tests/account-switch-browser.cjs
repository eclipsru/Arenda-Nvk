// Сайт 06.11.2026 (просьба владельца): быстрая смена аккаунта в «Работе».
// «Сменить» рядом с ФИО сотрудника — окошко «Email» + «Пароль»; войти может только сотрудник любого пункта этого проката или его
// владелец, иначе — «Вы не работаете в этом прокате», и прежний вход остаётся. У сотрудника «Кабинет» → «Профиль», справа —
// кружок «Владелец»: только пароль, почта владельца подставлена и скрыта (o***@x.ru). После входа весь сайт — от нового
// аккаунта, прежняя сессия закрыта; у владельца тоже есть «Сменить». Нет SQL 20261126 — кнопок нет. Другая вкладка
// переходит на новый аккаунт сама. Телефон 390 / 360 — кружок «Владелец» помещается в шапку.
const {chromium}=require('playwright');const assert=require('assert');
const BASE=process.env.SITE_BASE_URL||'http://127.0.0.1:8000';
const crypto=require('crypto');const md5=s=>crypto.createHash('md5').update(s).digest('hex');
const K1=md5('owner@x.ru'),K2=md5('other@x.ru');
const USERS={
  'ivan@x.ru':{pass:'ivan-pass',name:'Иванов Иван',staff:true,pk:[{key:K1,role:'staff',owner:'owner@x.ru',company:'Прокат у дома'}]},
  'olga@x.ru':{pass:'olga-pass',name:'Ольга Петрова',staff:true,pk:[{key:K1,role:'staff',owner:'owner@x.ru',company:'Прокат у дома'}]},
  'owner@x.ru':{pass:'own-pass',name:'Владимир',admin:true,pk:[{key:K1,role:'owner',owner:'owner@x.ru',company:'Прокат у дома'}]},
  'gosha@x.ru':{pass:'gosha-pass',name:'Гоша',staff:true,pk:[{key:K2,role:'staff',owner:'other@x.ru',company:'Чужой прокат'}]},
  'client@x.ru':{pass:'cl-pass',name:'Клиент',pk:[]}
};
const tokOf=e=>'tk-'+e.split('@')[0],emailOf=t=>Object.keys(USERS).find(e=>tokOf(e)===String(t||'').replace(/^Bearer /,''));
const P1='11111111-1111-4111-8111-111111111111';
const staffRow=e=>({id:'s-'+e,status:'active',company:'Прокат у дома',can_issue:true,can_catalog:false,can_chat:false,can_notify:true,self_issue:false,can_delete:false,
  points:[{id:P1,name:'Маресьева',city:'Новочеркасск',address:'ул. Маресьева, 36',open_min:0,close_min:1439,tel:'79081732475'}]});
const fakeWS='window.WebSocket=function(){this.readyState=0;};window.WebSocket.prototype.send=function(){};window.WebSocket.prototype.close=function(){};';
(async()=>{const b=await chromium.launch();
async function ctx(o){
  const c=await b.newContext({viewport:o.vp||{width:1440,height:1000},timezoneId:'Europe/Moscow'});
  await c.addInitScript(fakeWS);
  o.revoked=[];o.logins=[];
  await c.route('https://*.supabase.co/**',async route=>{const r=route.request(),u=new URL(r.url()),p=u.pathname;let data=[];
    const who=emailOf(r.headers()['authorization']);
    if(p==='/auth/v1/token'){const body=JSON.parse(r.postData()||'{}');
      if(u.searchParams.get('grant_type')==='password'){o.logins.push(body.email);const us=USERS[body.email];
        if(!us||us.pass!==body.password)return route.fulfill({status:400,json:{error:'invalid_grant',error_description:'Invalid login credentials'}});
        data={access_token:tokOf(body.email),refresh_token:'rt-'+body.email,user:{id:'u-'+body.email,email:body.email,email_confirmed_at:'2026-01-01T00:00:00Z',user_metadata:{name:us.name}}};}
      else{const e=String(body.refresh_token||'').replace(/^rt-/,'');if(!USERS[e])return route.fulfill({status:400,json:{error:'invalid_grant'}});
        data={access_token:tokOf(e),refresh_token:'rt-'+e,user:{id:'u-'+e,email:e,email_confirmed_at:'2026-01-01T00:00:00Z',user_metadata:{name:USERS[e].name}}};}}
    else if(p==='/auth/v1/logout'){o.revoked.push(who);return route.fulfill({status:204,body:''});}
    else if(p==='/auth/v1/user'){if(!who||o.revoked.includes(who))return route.fulfill({status:401,json:{message:'invalid JWT'}});
      data={id:'u-'+who,email:who,email_confirmed_at:'2026-01-01T00:00:00Z',user_metadata:{name:USERS[who].name}};}
    else if(p.endsWith('/admins'))data=who&&USERS[who].admin?[{email:who,role:'admin',active:true,company:'Прокат у дома',full_name:USERS[who].name}]:[];
    else if(p.endsWith('/rpc/iva_staff_me'))data=who&&USERS[who].staff?[staffRow(who)]:[];
    else if(p.endsWith('/rpc/iva_switch_info')){if(o.nosql)return route.fulfill({status:404,json:{code:'PGRST202',message:'Could not find the function public.iva_switch_info'}});
      data=who?{me:who,prokats:USERS[who].pk}:{me:'',prokats:[]};}
    else if(p.endsWith('/rpc/get_my_customer_profile'))data=[];
    await route.fulfill({json:data});});
  const pg=await c.newPage();const errors=[];pg.on('pageerror',e=>errors.push(e.message));
  await pg.goto(BASE+'/404.html');
  await pg.evaluate(e=>{localStorage.setItem('iva_sess',JSON.stringify({tok:'tk-'+e.split('@')[0],rtok:'rt-'+e,me:e,uname:''}));},o.as||'ivan@x.ru');
  return {c,pg,errors};
}
const sess=pg=>pg.evaluate(()=>JSON.parse(localStorage.getItem('iva_sess')||'null'));
const vis=(pg,s)=>pg.locator(s).first().isVisible();
async function openSw(pg,sel){await pg.click(sel);await pg.waitForSelector('#sheet.sw-sheet.open');await pg.waitForTimeout(250);}
async function tryLogin(pg,email,pass){if(email!=null)await pg.fill('#swEmail',email);await pg.fill('#swPass',pass);await pg.click('#swGo');}
async function errText(pg){await pg.waitForSelector('#swErr.show');return (await pg.locator('#swErr').innerText()).trim();}

// 1. Сотрудник: «Профиль», кружок «Владелец» справа от него; «Сменить» рядом с ФИО; чужой прокат, неверный пароль, тот же
//    аккаунт — отказ без смены входа; сотрудник другого пункта этого проката — вход, прежняя сессия закрыта
{const o={};const {c,pg,errors}=await ctx(o);
 await pg.goto(BASE+'/account.html#staff',{waitUntil:'domcontentloaded'});await pg.waitForSelector('.st-head .sw-btn',{state:'visible'});
 assert.equal((await pg.locator('#acctBtn > span:last-child').innerText()).trim(),'Профиль','у сотрудника «Кабинет» → «Профиль»');
 await pg.waitForSelector('#ownLink',{state:'visible'});
 const wl=await pg.locator('#workLink').boundingBox(),ab=await pg.locator('#acctBtn').boundingBox(),ol=await pg.locator('#ownLink').boundingBox();
 assert(wl.x<ab.x&&ab.x<ol.x,'порядок: «Работа» — «Профиль» — «Владелец»: '+[wl.x,ab.x,ol.x]);
 assert(Math.abs((ol.y+ol.height/2)-(ab.y+ab.height/2))<3,'«Владелец» — в одной строке с «Профилем»');
 assert.equal((await pg.locator('#ownLink').innerText()).trim(),'Владелец');
 assert.equal(await pg.locator('#ownLink svg rect').count(),1,'в кружке — замок');
 assert.match(await pg.locator('#ownLink').getAttribute('title'),/Прокат у дома/);
 const h2=await pg.locator('.st-head h2').boundingBox(),sw=await pg.locator('.st-head .sw-btn').boundingBox();
 assert(sw.x>=h2.x+h2.width&&sw.x-(h2.x+h2.width)<20&&Math.abs((sw.y+sw.height/2)-(h2.y+h2.height/2))<4,'«Сменить» — справа от ФИО, в той же строке');
 assert.equal((await pg.locator('.st-head .sw-btn').innerText()).trim(),'Сменить');
 assert.equal(await pg.locator('.st-head .sw-btn').evaluate(e=>getComputedStyle(e).backgroundColor),'rgba(0, 0, 0, 0)','«Сменить» — контурная кнопка');
 await openSw(pg,'.st-head .sw-btn');
 assert.equal((await pg.locator('#sheet .sheet-h b').innerText()).trim(),'Сменить аккаунт');
 assert.deepEqual(await pg.locator('#sheet .sw-l').allInnerTexts(),['Email','Пароль'],'сверху Email, ниже пароль');
 const eb=await pg.locator('#swEmail').boundingBox(),pb=await pg.locator('#swPass').boundingBox();assert(eb.y<pb.y);
 assert.equal(await pg.evaluate(()=>document.activeElement.id),'swEmail','фокус — на почте');
 const shB=await pg.locator('#sheet').boundingBox();assert(shB.width<=440&&Math.abs(shB.x+shB.width/2-720)<3,'небольшое окошко по центру: '+JSON.stringify(shB));
 await tryLogin(pg,'gosha@x.ru','gosha-pass');assert.equal(await errText(pg),'Вы не работаете в этом прокате');
 assert.equal((await sess(pg)).me,'ivan@x.ru','чужой прокат — прежний вход остаётся');
 assert(o.revoked.includes('gosha@x.ru')&&!o.revoked.includes('ivan@x.ru'),'проверочный вход чужого закрыт, свой — нет: '+o.revoked);
 assert.equal(await pg.inputValue('#swPass'),'','пароль стёрт после отказа');
 await tryLogin(pg,'olga@x.ru','wrong');assert.equal(await errText(pg),'Неверная почта или пароль');
 await tryLogin(pg,'client@x.ru','cl-pass');assert.equal(await errText(pg),'Вы не работаете в этом прокате','клиент — тоже отказ');
 await tryLogin(pg,'IVAN@x.ru','ivan-pass');assert.equal(await errText(pg),'Вы уже вошли как ivan@x.ru');
 assert.equal((await sess(pg)).me,'ivan@x.ru');
 await pg.keyboard.press('Escape');await pg.waitForSelector('#sheet',{state:'detached'});
 // сотрудник другого пункта того же проката — входит; Enter в поле пароля = «Войти»
 await openSw(pg,'.st-head .sw-btn');await pg.fill('#swEmail','olga@x.ru');await pg.fill('#swPass','olga-pass');
 await Promise.all([pg.waitForNavigation({waitUntil:'domcontentloaded'}),pg.press('#swPass','Enter')]);
 await pg.waitForFunction(()=>/Ольга/.test((document.querySelector('.st-head h2')||{}).textContent||''));
 assert.equal((await sess(pg)).me,'olga@x.ru','весь сайт — от нового аккаунта');assert(pg.url().endsWith('account.html#staff'),pg.url());
 assert(o.revoked.includes('ivan@x.ru'),'прежняя сессия закрыта (вернуться — только с паролем)');
 assert.equal((await pg.locator('#acctBtn > span:last-child').innerText()).trim(),'Профиль');
 assert.deepEqual(errors,[],'без ошибок JS: '+errors.join('; '));
 await c.close();}

// 2. «Владелец»: только пароль, почта владельца скрыта; неверный пароль — отказ; верный — кабинет владельца проката,
//    «Кабинет» вместо «Профиля», кружка «Владелец» нет, у владельца есть «Сменить» и «Сменить аккаунт» в меню; обратно — с паролем
{const o={};const {c,pg,errors}=await ctx(o);
 await pg.goto(BASE+'/index.html',{waitUntil:'domcontentloaded'});await pg.waitForSelector('#ownLink',{state:'visible'});
 assert.equal((await pg.locator('#acctBtn > span:last-child').innerText()).trim(),'Профиль','«Профиль» — на любой странице сайта');
 await openSw(pg,'#ownLink');
 assert.equal((await pg.locator('#sheet .sheet-h b').innerText()).trim(),'Владелец');
 assert.equal(await pg.locator('#swEmail').count(),0,'почту вводить не нужно');
 assert.equal((await pg.locator('#swMail').innerText()).trim(),'o***@x.ru','почта владельца — скрыта');
 assert(!(await pg.locator('#sheet').innerHTML()).includes('owner@x.ru'),'полной почты владельца на странице нет');
 assert.match(await pg.locator('#sheet .sw-note').innerText(),/«Прокат у дома»/);
 assert.equal(await pg.evaluate(()=>document.activeElement.id),'swPass','фокус — на пароле');
 await tryLogin(pg,null,'ivan-pass');assert.equal(await errText(pg),'Неверная почта или пароль');
 assert.equal((await sess(pg)).me,'ivan@x.ru');
 await Promise.all([pg.waitForNavigation({waitUntil:'domcontentloaded'}),tryLogin(pg,null,'own-pass')]);
 await pg.waitForFunction(()=>location.hash==='#home'&&typeof isAdmin==='function'&&isAdmin());
 assert.equal((await sess(pg)).me,'owner@x.ru');assert(pg.url().endsWith('account.html#home'),pg.url());
 assert.equal(o.logins.at(-1),'owner@x.ru','вход — по почте владельца, которую подставил сайт');
 assert(o.revoked.includes('ivan@x.ru'),'сессия сотрудника закрыта');
 await pg.waitForSelector('.acct-head .sw-btn',{state:'visible'});
 assert.equal((await pg.locator('#acctBtn > span:last-child').innerText()).trim(),'Кабинет','у владельца — «Кабинет»');
 assert.equal(await vis(pg,'#ownLink'),false,'у владельца нет кружка «Владелец»');
 assert.equal(await pg.evaluate(()=>localStorage.getItem('iva_staff_hint')),null,'подсказка «сотрудник» стёрта');
 await pg.click('#acctBtn');await pg.waitForSelector('#acctMenu:not(.hide)');
 assert.equal(await vis(pg,'#acctMenu .acct-sw'),true,'в меню владельца — «Сменить аккаунт»');
 await openSw(pg,'#acctMenu .acct-sw');assert.equal(await vis(pg,'#acctMenu'),false,'меню закрылось');
 await tryLogin(pg,'gosha@x.ru','gosha-pass');assert.equal(await errText(pg),'Вы не работаете в этом прокате','владельцу — тоже только свои');
 await Promise.all([pg.waitForNavigation({waitUntil:'domcontentloaded'}),tryLogin(pg,'ivan@x.ru','ivan-pass')]);
 await pg.waitForSelector('.st-head .sw-btn',{state:'visible'});
 assert.equal((await sess(pg)).me,'ivan@x.ru','обратно к сотруднику — с его паролем');assert(pg.url().endsWith('account.html#staff'));
 assert(o.revoked.includes('owner@x.ru'));
 assert.deepEqual(errors,[],'без ошибок JS: '+errors.join('; '));
 await c.close();}

// 3. Нет SQL 20261126 — ни «Сменить», ни «Владелец» (остальное как было); клиенту — ничего нового, «Кабинет»
{const o={nosql:true};const {c,pg,errors}=await ctx(o);
 await pg.goto(BASE+'/account.html#staff',{waitUntil:'domcontentloaded'});await pg.waitForSelector('.st-head h2');await pg.waitForTimeout(700);
 assert.equal(await vis(pg,'.st-head .sw-btn'),false,'без SQL «Сменить» нет');assert.equal(await vis(pg,'#ownLink'),false,'без SQL «Владелец» нет');
 assert.equal((await pg.locator('#acctBtn > span:last-child').innerText()).trim(),'Профиль');
 await pg.click('#acctBtn');await pg.waitForSelector('#acctMenu:not(.hide)');assert.equal(await vis(pg,'#acctMenu .acct-sw'),false);
 assert.deepEqual(errors,[]);await c.close();}
{const o={as:'client@x.ru'};const {c,pg,errors}=await ctx(o);
 await pg.goto(BASE+'/index.html',{waitUntil:'domcontentloaded'});await pg.waitForFunction(()=>typeof isAuthed==='function'&&isAuthed()&&document.querySelector('#acctBtn').textContent.trim()==='Кабинет');await pg.waitForTimeout(500);
 assert.equal(await vis(pg,'#ownLink'),false);assert.equal(await vis(pg,'#workLink'),false);
 assert.equal((await pg.locator('#acctBtn > span:last-child').innerText()).trim(),'Кабинет','у клиента — «Кабинет»');
 assert.deepEqual(errors,[]);await c.close();}

// 4. Телефон 390 и 360: кружок «Владелец» рядом с «Работой» помещается, страница не шире экрана; внизу — «Профиль»
for(const w of [390,360]){const o={vp:{width:w,height:800}};const {c,pg,errors}=await ctx(o);
 await pg.goto(BASE+'/index.html',{waitUntil:'domcontentloaded'});await pg.waitForSelector('#ownMob',{state:'visible'});await pg.waitForTimeout(300);
 const wm=await pg.locator('#workMob').boundingBox(),om=await pg.locator('#ownMob').boundingBox(),cb=await pg.locator('#cityBtn').boundingBox();
 assert(om.x>=wm.x+wm.width&&om.x+om.width<=w,w+': «Владелец» справа от «Работы», в экране: '+JSON.stringify([wm,om]));
 assert(cb.x+cb.width<=wm.x+1,w+': кнопка города не наезжает');
 assert(Math.abs((om.y+om.height/2)-(wm.y+wm.height/2))<3,w+': в одной строке');
 assert(await pg.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),w+': нет прокрутки вбок');
 assert.equal((await pg.locator('#bnav a[href="account.html"] > span:last-of-type').innerText()).trim(),'Профиль',w+': внизу — «Профиль»');
 await openSw(pg,'#ownMob');assert.equal((await pg.locator('#swMail').innerText()).trim(),'o***@x.ru');
 const sb=await pg.locator('#sheet').boundingBox();assert(sb.x>=0&&sb.x+sb.width<=w+1,w+': окно в экране');
 assert.deepEqual(errors,[]);await c.close();}

// 5. Две вкладки: сменили аккаунт в одной — вторая сама переходит на новый аккаунт
{const o={};const {c,pg,errors}=await ctx(o);
 const p2=await c.newPage();await p2.goto(BASE+'/favorites.html',{waitUntil:'domcontentloaded'});await p2.waitForFunction(()=>typeof A!=='undefined'&&A.me==='ivan@x.ru');
 await pg.goto(BASE+'/account.html#staff',{waitUntil:'domcontentloaded'});await pg.waitForSelector('.st-head .sw-btn',{state:'visible'});
 await openSw(pg,'.st-head .sw-btn');
 await Promise.all([pg.waitForNavigation({waitUntil:'domcontentloaded'}),tryLogin(pg,'olga@x.ru','olga-pass')]);
 await p2.waitForFunction(()=>typeof A!=='undefined'&&A.me==='olga@x.ru',null,{timeout:8000});
 assert(p2.url().endsWith('favorites.html'),'вторая вкладка осталась на своей странице');
 assert.deepEqual(errors,[]);await c.close();}

await b.close();console.log('account-switch-browser: OK (5 сценариев)');})().catch(e=>{console.error(e);process.exit(1);});
