// Сайт 06.11.2026 (просьба владельца): главная.
// 1. Плитки: первая — «Все категории», за ней 6 первых категорий; остальные открываются нажатием на «Все категории»
//    (повторное нажатие сворачивает, состояние помним в пределах вкладки).
// 2. «Глобальный поиск»: только категории (и подкатегории), которые есть в каталоге выбранного города; выбранная — остаётся.
// 3. «Цена за сутки» — внизу, над «Сбросить фильтры»; поля «от / до» высотой с кнопку города в шапке (и на телефоне).
// 4. Поля «Найти другой город…» под списком городов нет.
// 5. В списке городов — только города, где есть инструмент (+ выбранный).
// 6. (06.11, вторая просьба) В «Глобальном поиске» — 5 категорий, остальные раскрывает «Все категории».
// 7. Карточка: сверху название (на месте цены), цена — в правом нижнем углу, на фото справа внизу — расстояние «2,1 км»
//    (только когда известно: вошёл и есть адрес в профиле; иначе значка нет).
// Запуск (нужен локальный сервер проекта): SITE_BASE_URL=http://127.0.0.1:8000 node tests/home-filters-browser.cjs
const {chromium}=require('playwright');const assert=require('assert');
const BASE=process.env.SITE_BASE_URL||'http://127.0.0.1:8000';
const T=(id,name,cat,sub,city,price)=>({id,name,cat,sub,price,deposit:1000,descr:'',pickup_city:city,status:'active',active:true,
  owner_email:'o@x.test',imgs:[],terms:'Состояние: Рабочее'});
const TOOLS=[T('t1','Перфоратор','power','hammers','Новочеркасск',900),T('t2','Болгарка','power','grinders','Новочеркасск',500),
  T('t3','Газонокосилка','garden','lawnmowers','Новочеркасск',1200),T('t4','Сварка','welding','','Ростов-на-Дону',1500),
  T('t5','Тележка','moving','hand-trucks','Новочеркасск',300)];
(async()=>{const b=await chromium.launch();const errs=[];
async function page(vp,tools){tools=tools||TOOLS;const pg=await b.newPage({viewport:vp});
  pg.on('pageerror',e=>errs.push(e.message));pg.on('console',m=>{if(m.type()==='error')errs.push(m.text().slice(0,140));});
  await pg.route('https://*.supabase.co/**',r=>r.fulfill({json:r.request().url().includes('/rest/v1/tools')?tools:[]}));
  await pg.goto(BASE+'/index.html',{waitUntil:'domcontentloaded'});
  // ждём именно тестовые объявления: до ответа базы на странице мелькают демо-объявления из data.js
  await pg.waitForFunction(ids=>typeof ADS!=='undefined'&&ADS.map(a=>a.id).join()===ids&&document.querySelectorAll('#filters [data-fcat]').length&&
    document.querySelectorAll('#grid .ad').length,tools.map(t=>t.id).join());
  await pg.waitForTimeout(150);
  await pg.evaluate(()=>sessionStorage.clear());
  return pg;}
const tiles=pg=>pg.locator('#cats .cat').evaluateAll(a=>a.map(e=>e.getAttribute('data-cat')||(e.hasAttribute('data-allcats')?'ALL':'?')));
try{
 // ---------- компьютер ----------
 const pg=await page({width:1600,height:1000});
 const cats=await pg.evaluate(()=>CATS.map(c=>c.key));assert(cats.length>7,'в демо-наборе категорий больше 7: '+cats.length);
 // 1. «Все категории» + 6
 assert.deepEqual(await tiles(pg),['ALL'].concat(cats.slice(0,6)),'первая — «Все категории», за ней 6 первых категорий');
 const all=pg.locator('#cats [data-allcats]');
 assert.equal(await all.getAttribute('aria-expanded'),'false');
 assert.match(await all.innerText(),/Все категории[\s\S]*ещё\s+3/);
 await all.click();
 assert.deepEqual(await tiles(pg),['ALL'].concat(cats),'после нажатия — все категории');
 assert.equal(await all.getAttribute('aria-expanded'),'true');assert.match(await all.innerText(),/свернуть/);
 assert.equal(await pg.evaluate(()=>sessionStorage.getItem('iva_cats_open')),'1');
 // открытое состояние переживает переход в категорию и «Назад»
 await pg.click('#cats [data-cat="power"]');await pg.waitForSelector('#cats [data-back]');
 await pg.click('#cats [data-back]');await pg.waitForSelector('#cats [data-allcats]');
 assert.equal((await tiles(pg)).length,cats.length+1,'после «Назад» категории остались открытыми');
 await pg.click('#cats [data-allcats]');
 assert.deepEqual(await tiles(pg),['ALL'].concat(cats.slice(0,6)),'повторное нажатие сворачивает');
 // плитки в одной сетке: «Все категории» того же размера, что и соседняя
 await pg.mouse.move(5,990);await pg.waitForTimeout(250); // без наведения: плитка под мышью приподнимается на 2px
 const tb=await pg.locator('#cats .cat').evaluateAll(a=>a.slice(0,2).map(e=>{const r=e.getBoundingClientRect();return [Math.round(r.width),Math.round(r.height),Math.round(r.y)];}));
 assert(tb[0].every((v,i)=>Math.abs(v-tb[1][i])<=1),'плитка «Все категории» — как остальные: '+JSON.stringify(tb));

 // 2. категории фильтра — только из каталога (город Новочеркасск: power, garden, moving; welding — только в Ростове)
 const fc=()=>pg.locator('#filters [data-fcat]').evaluateAll(a=>a.map(e=>e.getAttribute('data-fcat')));
 assert.deepEqual(await fc(),['','power','garden','moving'],'в фильтре — только категории, которые есть в каталоге города');
 // подкатегории — тоже только те, что есть
 await pg.click('#filters [data-fcat="power"]');await pg.waitForSelector('#filters [data-fsub]');
 assert.deepEqual(await pg.locator('#filters [data-fsub]').evaluateAll(a=>a.map(e=>e.getAttribute('data-fsub'))),['','grinders','hammers'],'подкатегории — только с объявлениями');
 await pg.click('#cats [data-back]');await pg.waitForSelector('#cats [data-allcats]');

 // 5. города — только с инструментом; 4. «Найти другой город…» нет
 assert.deepEqual(await pg.locator('#filters #fCity option').allTextContents(),['Все города','Новочеркасск','Ростов-на-Дону'],'в списке — только города с инструментом');
 assert.equal(await pg.locator('.geo-filter-city').count(),0,'поля «Найти другой город…» нет');
 assert.equal(await pg.locator('input[placeholder*="другой город"]').count(),0);
 // выбор другого города меняет список категорий
 await pg.selectOption('#filters #fCity','Ростов-на-Дону');await pg.waitForFunction(()=>document.querySelector('#fCity').value==='Ростов-на-Дону');
 assert.deepEqual(await fc(),['','welding'],'Ростов-на-Дону: только «Сварочное оборудование»');
 await pg.selectOption('#filters #fCity','');await pg.waitForFunction(()=>document.querySelector('#fCity').value==='');
 assert.deepEqual(await fc(),['','power','garden','welding','moving'],'все города: категории из всего каталога');
 assert.equal(await pg.locator('.geo-filter-city').count(),0,'после перерисовки поле не появилось');
 // выбранный город без инструмента остаётся в списке (иначе список не покажет выбор)
 await pg.evaluate(()=>{setCity('Аксай',true);onCityChanged();});
 assert.deepEqual(await pg.locator('#filters #fCity option').allTextContents(),['Все города','Новочеркасск','Ростов-на-Дону','Аксай']);
 assert.equal(await pg.locator('#filters #fCity').inputValue(),'Аксай');
 assert.deepEqual(await fc(),[''],'в городе без инструмента — только «Все категории»');
 await pg.evaluate(()=>{setCity('Новочеркасск',true);onCityChanged();});

 // 3. цена — внизу над «Сбросить фильтры», высота — как у кнопки города
 const grp=await pg.locator('#filters .f-grp').evaluateAll(a=>a.map(g=>g.querySelector('#pFrom')?'price':g.querySelector('#fReset')?'reset':g.querySelector('#fCity')?'city':g.querySelector('[data-fcat]')?'cat':g.querySelector('[data-cond]')?'cond':g.querySelector('#fDeliv')?'deliv':'?'));
 assert.deepEqual(grp,['cat','city','cond','deliv','price','reset'],'цена — в самом низу, над «Сбросить фильтры»');
 const hCity=await pg.evaluate(()=>document.querySelector('#cityBtn').getBoundingClientRect().height);
 const hP=await pg.evaluate(()=>['#pFrom','#pTo'].map(s=>document.querySelector('#filters '+s).getBoundingClientRect().height));
 hP.forEach(h=>assert(Math.abs(h-hCity)<0.6,'поля цены высотой с кнопку города: '+hP+' / '+hCity));
 // цена работает
 await pg.fill('#filters #pFrom','800');await pg.press('#filters #pFrom','Enter');
 await pg.waitForFunction(()=>!/Болгарка/.test(document.querySelector('#grid').innerText));
 assert.match(await pg.locator('#grid').innerText(),/Перфоратор/);assert.equal(await pg.locator('#filters #pFrom').inputValue(),'800');
 await pg.close();

 // ---------- 6. фильтр: 5 категорий + «Все категории» раскрывает остальные; 7. карточки ----------
 {const many=TOOLS.concat([T('t6','Лазерный уровень','measuring','','Новочеркасск',400),T('t7','Генератор','generators','','Новочеркасск',2000),
    T('t8','Бетономешалка','construction','concrete','Новочеркасск',800)]);
  many[0].delivery=true;many[0].pickup_lat=47.43;many[0].pickup_lng=40.09;many[1].pickup_lat=47.30;many[1].pickup_lng=39.90;   // перфоратор ≈0,9 км, болгарка ≈19,9 км
  const pg=await page({width:1600,height:1000},many);
  const order=await pg.evaluate(()=>CATS.map(c=>c.key));
  const have=order.filter(k=>['power','garden','moving','measuring','generators','construction'].includes(k));
  const fc=()=>pg.locator('#filters [data-fcat]').evaluateAll(a=>a.map(e=>e.getAttribute('data-fcat')));
  assert.deepEqual(await fc(),[''].concat(have.slice(0,5)),'в списке 5 категорий: '+JSON.stringify(await fc()));
  const more=pg.locator('#filters [data-fmore]');
  assert.equal(await more.getAttribute('aria-expanded'),'false');assert.equal(await more.locator('.f-chev').count(),1,'у «Все категории» — стрелка');
  await more.click();assert.deepEqual(await fc(),[''].concat(have),'«Все категории» раскрывает остальные');
  assert.equal(await pg.locator('#filters [data-fmore]').getAttribute('aria-expanded'),'true');
  // выбранная 6-я категория видна и в свёрнутом списке; «Все категории» при выбранной — сброс, список открыт
  await pg.click('#filters [data-fcat="'+have[5]+'"]');await pg.waitForSelector('#cats [data-back]');
  await pg.click('#filters [data-fmore]');assert.equal(await pg.evaluate(()=>document.querySelector('#filters [data-fcat=""]').classList.contains('on')),true);
  assert.deepEqual(await fc(),[''].concat(have),'при выбранной категории «Все категории» — сброс, список открыт');
  await pg.click('#filters [data-fmore]');assert.deepEqual(await fc(),[''].concat(have.slice(0,5)),'повторно — свернуть');
  await pg.click('#cats [data-allcats]');await pg.click('#cats [data-cat="'+have[5]+'"]');await pg.waitForSelector('#cats [data-back]');
  assert.deepEqual(await fc(),[''].concat(have.slice(0,5),[have[5]]),'выбранная категория видна и в свёрнутом списке');
  await pg.click('#cats [data-back]');
  // клавиатура
  await pg.focus('#filters [data-fmore]');await pg.keyboard.press('Enter');assert.equal((await fc()).length,have.length+1,'Enter раскрывает');
  // 7. карточки: название сверху, цена — последняя строка справа; гость — значка расстояния нет
  const card=pg.locator('#grid .ad[data-id="t1"]');
  const cls=await card.locator('.ad-body > *').evaluateAll(a=>a.map(e=>e.className));
  assert.deepEqual(cls,['ad-name','ad-dep','ad-meta','ad-foot'],'порядок в карточке: название, залог, рейтинг · город, цена');
  const nb=await card.locator('.ad-name').boundingBox(),pb=await card.locator('.ad-price').boundingBox(),cb=await card.boundingBox();
  assert(nb.y-cb.y<cb.height*0.62,'название — сразу под фото');
  assert(cb.x+cb.width-(pb.x+pb.width)<=16&&cb.y+cb.height-(pb.y+pb.height)<=16,'цена — в правом нижнем углу: '+JSON.stringify([pb,cb]));
  assert.equal(await card.locator('.ad-km').isHidden(),true,'гость: расстояния нет');
  assert.equal(await pg.locator('#grid').innerText().then(t=>/Расстояние/.test(t)),false,'в карточках нет пояснений «Расстояние — …»');
  // вошёл и есть адрес: «0,9 км» на фото справа внизу
  await pg.evaluate(()=>{A.tok='x';A.customerProfile={address:'Новочеркасск, Московская, 1'};IvaGeo.search=async()=>[{coordinates:[40.0937,47.4222]}];IvaDistance.paintCards();});
  await pg.waitForFunction(()=>{const k=document.querySelector('#grid .ad[data-id="t1"] .ad-km');return k&&!k.hidden;});
  assert.equal(await card.locator('.ad-km').innerText(),'0,9 км');
  assert.equal(await pg.locator('#grid .ad[data-id="t2"] .ad-km').innerText(),'19,9 км');
  assert.equal(await pg.locator('#grid .ad[data-id="t6"] .ad-km').isHidden(),true,'у пункта нет адреса — значка нет');
  const kb=await card.locator('.ad-km').boundingBox(),ph=await card.locator('.ad-ph').boundingBox();
  assert(ph.x+ph.width-(kb.x+kb.width)<=12&&ph.y+ph.height-(kb.y+kb.height)<=12&&kb.y>ph.y+ph.height/2,'расстояние — на фото справа внизу');
  const bb=await card.locator('.ad-badges').boundingBox();assert(bb.x+bb.width<=kb.x+1,'бейджи не наезжают на расстояние');
  assert.equal(await pg.evaluate(()=>[IvaDistance.short(2.14),IvaDistance.short(2),IvaDistance.short(0.03),IvaDistance.short(123.4)].join('|')),'2,1 км|2 км|0,1 км|123 км');
  await pg.close();}

 // ---------- телефон 390 ----------
 const mp=await page({width:390,height:844});
 await mp.evaluate(()=>sessionStorage.removeItem('iva_cats_open'));await mp.reload();await mp.waitForSelector('#cats [data-allcats]');
 assert.equal((await tiles(mp)).length,7,'телефон: «Все категории» + 6');
 const mb=await mp.locator('#cats .cat').evaluateAll(a=>a.slice(0,5).map(e=>Math.round(e.getBoundingClientRect().y)));
 assert(mb.every(y=>y===mb[0]),'телефон: 5 плиток в первом ряду');
 await mp.click('#cats [data-allcats]');assert.equal((await tiles(mp)).length,cats.length+1);
 await mp.click('#fMob');await mp.waitForSelector('#sheet #pFrom');await mp.waitForTimeout(350);
 const mh=await mp.evaluate(()=>[document.querySelector('#cityBtn').getBoundingClientRect().height,document.querySelector('#sheet #pFrom').getBoundingClientRect().height]);
 assert(Math.abs(mh[0]-mh[1])<0.6,'телефон: поля цены высотой с кнопку города: '+mh);
 assert.equal(await mp.locator('#sheet .geo-filter-city').count(),0,'телефон: поля «Найти другой город…» нет');
 assert.deepEqual(await mp.locator('#sheet #fCity option').allTextContents(),['Все города','Новочеркасск','Ростов-на-Дону']);
 assert.equal(await mp.evaluate(()=>document.documentElement.scrollWidth>innerWidth+2),false,'без горизонтальной прокрутки');
 await mp.close();
 assert.deepEqual(errs,[],'ошибки на странице: '+errs.join(' | '));
 console.log('home-filters-browser: OK');
}catch(e){console.error(e);process.exitCode=1;}
await b.close();})();
