// Страница приложения app.html «как в Google Play» (просьба владельца 02.11.2026): сюда ведёт постоянный QR-код.
// База Supabase подменена: выпущенная версия, оценки и отзывы (SQL 20261120_app_reviews.sql), вход.
// Проверяем: версия/ссылка/размер/дата, оценки и гистограмма, отзывы, «Полезно?», свой отзыв, ответ создателя,
// нет SQL — честное «скоро», iPhone — «только Android», компьютер — QR-код. Снимки — в IVA_SHOT_DIR (если задан).
const {chromium}=require('playwright');const assert=require('assert');const path=require('path');
const BASE=process.env.SITE_BASE_URL||'http://127.0.0.1:8000';const SHOT=process.env.IVA_SHOT_DIR||'';
const ANDROID='Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36';
const IPHONE='Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1';
const REL=[{version_code:203,version_name:'10.129',apk_url:'ProkatInstrumenta-10.129.apk',reinstall:false,notes:'Исправлено «Удаляет инструмент»',confirmed_at:'2026-10-24T10:00:00Z'}];
const rv=(id,o)=>Object.assign({id,name:'Иван',rating:5,body:'Удобно: нашёл перфоратор рядом за пять минут.',app_version:'10.129',created_at:'2026-10-20T10:00:00Z',updated_at:'2026-10-20T10:00:00Z',helpful:0,reply:'',reply_at:null,mine:false,my_vote:null,status:'visible'},o);
(async()=>{const b=await chromium.launch();let n=0;
async function open(o){
  const c=await b.newContext({viewport:o.vp||{width:390,height:844},userAgent:o.ua||ANDROID,timezoneId:'Europe/Moscow',locale:'ru-RU',deviceScaleFactor:o.dpr||1});
  o.calls=[];
  await c.route('https://*.supabase.co/**',async route=>{const r=route.request(),u=new URL(r.url()),p=u.pathname;let data=[];
    let body=null;try{body=r.postDataJSON();}catch(e){}
    if(p.startsWith('/rest/v1/rpc/'))o.calls.push({fn:p.slice(13),body});
    if(p==='/auth/v1/user')data={id:'40000000-0000-4000-8000-000000000001',email:o.email,email_confirmed_at:'2026-01-01T00:00:00Z',user_metadata:{full_name:'Тест Тестов'}};
    else if(p.endsWith('/app_releases'))data=REL;
    else if(/iva_app_review/.test(p)&&o.noSql)return route.fulfill({status:404,json:{code:'PGRST202',message:'Could not find the function public.'+p.slice(13)}});
    else if(p.endsWith('/rpc/iva_app_reviews_stats'))data=[o.stats||{total:0,avg:0,s1:0,s2:0,s3:0,s4:0,s5:0}];
    else if(p.endsWith('/rpc/iva_app_reviews_list')){const s=body&&body.p_stars;data=(o.list||[]).filter(x=>!s||x.rating===s);}
    else if(p.endsWith('/rpc/iva_app_review_vote'))data=body.p_helpful?8:7;
    else if(p.endsWith('/rpc/iva_app_review_save'))data={id:1,status:o.saveStatus||'visible'};
    else if(p.endsWith('/rpc/iva_app_review_reply')||p.endsWith('/rpc/iva_app_review_status')||p.endsWith('/rpc/iva_app_review_delete'))data=true;
    await route.fulfill({json:data});});
  const pg=await c.newPage();const errors=[];pg.on('pageerror',e=>errors.push(e.message));
  if(o.email){await pg.goto(BASE+'/404.html');await pg.evaluate(()=>{localStorage.setItem('iva_auth',JSON.stringify({tok:'test-token',ref:'test-refresh',exp:9999999999999}));});}
  if(o.rate)await pg.evaluate(r=>sessionStorage.setItem('iva_app_rate',r),String(o.rate));
  await pg.goto(BASE+'/app.html',{waitUntil:'domcontentloaded'});
  return {c,p:pg,errors};
}
const shot=async(p,name,full)=>{if(SHOT)await p.screenshot({path:path.join(SHOT,name),fullPage:!!full});};
const stats={total:3,avg:4.3,s1:0,s2:0,s3:1,s4:0,s5:2};
const list=[rv(11,{helpful:7,reply:'Спасибо за отзыв!',reply_at:'2026-10-21T09:00:00Z'}),rv(12,{name:'Мария',rating:3,body:'Хорошо, но хочется карту.\n'.repeat(8),app_version:'10.128'}),rv(13,{name:'',rating:5,body:'',app_version:''})];

// 1. Гость на Android-телефоне
{const o={stats,list};const {c,p,errors}=await open(o);
 await p.waitForFunction(()=>document.querySelectorAll('#rvList .rv').length===3);
 const ins=p.locator('#apInstall');await p.waitForFunction(()=>document.getElementById('apInstall').getAttribute('href')==='ProkatInstrumenta-10.129.apk');
 assert.equal((await ins.textContent()).trim(),'Установить · версия 10.129');
 assert.equal(await p.locator('#whatsNew').textContent(),'Исправлено «Удаляет инструмент»');
 await p.waitForFunction(()=>document.getElementById('infDate').textContent==='24 октября 2026');
 await p.waitForFunction(()=>/^\d+,\d МБ$/.test(document.getElementById('infSize').textContent));
 assert.equal(await p.locator('#rtAvg').textContent(),'4,3');assert.equal(await p.locator('#rtTotal').textContent(),'3 отзыва');
 assert.equal(await p.locator('#stRateN').textContent(),'3 отзыва');
 assert.equal(await p.locator('#rtBars .rt-bar').count(),5);
 assert.equal(await p.locator('#rtBars .rt-bar').first().locator('s').getAttribute('style'),'width:67%');
 assert.equal(await p.locator('#appTopBanner').count(),0,'второй баннер «скачать» на странице приложения не нужен');
 assert.equal(await p.locator('#apShots .shot').count(),5,'5 экранов-иллюстраций');
 const first=p.locator('#rvList .rv').first();
 assert((await first.textContent()).includes('7 человек считают этот отзыв полезным'));
 assert((await first.locator('.rv-reply').textContent()).includes('Спасибо за отзыв!'));
 assert.equal(await p.locator('#rvList .rv').nth(2).locator('.rv-n').textContent(),'Пользователь Ивы');
 assert.equal(await p.locator('#rvList .rv').nth(1).locator('.rv-more:not([hidden])').count(),1,'длинный отзыв свёрнут, есть «Ещё»');
 assert.equal(await p.locator('.rv-act').count(),0,'у гостя нет кнопок создателя');
 assert.equal(await p.locator('#rvMore').isVisible(),false,'«Показать ещё» не видно, когда отзывов больше нет');
 await shot(p,'app-390-top.png');
 // липкая кнопка внизу после прокрутки
 assert.equal(await p.locator('#apSticky.show').count(),0);
 await p.evaluate(()=>window.scrollTo(0,900));await p.waitForSelector('#apSticky.show');
 // «Полезно?» без входа → вход с возвратом на отзывы
 await first.locator('[data-act=vote][data-v="1"]').click();await p.waitForSelector('#sheet .btn');
 assert.equal(await p.locator('#sheet .sheet-f a').getAttribute('href'),'account.html?next=app.html%23reviews');
 await p.evaluate(()=>closeSheet(true));
 await p.locator('#tapStars [data-rate="4"]').click();await p.waitForSelector('#sheet');
 assert.equal(await p.evaluate(()=>sessionStorage.getItem('iva_app_rate')),'4');
 await p.evaluate(()=>closeSheet(true));
 // фильтр по звёздам
 await p.locator('#rtTools [data-stars="3"]').click();await p.waitForFunction(()=>document.querySelectorAll('#rvList .rv').length===1);
 assert.equal(o.calls.filter(x=>x.fn==='iva_app_reviews_list').pop().body.p_stars,3);
 await p.locator('#rtTools [data-stars="1"]').click();await p.waitForFunction(()=>/с такой оценкой пока нет/.test(document.getElementById('rvList').textContent));
 await p.locator('#rtTools [data-stars="0"]').click();await p.locator('#rtTools [data-sort="new"]').click();
 await p.waitForFunction(()=>document.querySelectorAll('#rvList .rv').length===3);
 assert.equal(o.calls.filter(x=>x.fn==='iva_app_reviews_list').pop().body.p_sort,'new');
 await p.evaluate(()=>document.getElementById('reviews').scrollIntoView());await shot(p,'app-390-reviews.png');
 if(SHOT)await shot(p,'app-390-full.png',true);
 // «Установить» на телефоне → подсказка, как установить
 await p.evaluate(()=>window.scrollTo(0,0));await ins.click();await p.waitForSelector('#sheet');
 assert.equal(await p.locator('#sheet .sheet-h b').textContent(),'Файл скачивается');
 assert((await p.locator('#sheet').textContent()).includes('ProkatInstrumenta-10.129.apk'));
 assert.deepEqual(errors,[]);await c.close();n++;}

// 2. Вошедший пользователь со своим отзывом: свой сверху, изменить, «полезно» чужому, отзыв на проверку
{const o={email:'qa-user@example.invalid',stats,saveStatus:'pending',list:[rv(20,{mine:true,name:'Тест',rating:4,body:'Мой отзыв',status:'visible'}),rv(11,{helpful:7})]};
 const {c,p,errors}=await open(o);await p.waitForFunction(()=>document.querySelectorAll('#rvList .rv').length===2);
 const mine=p.locator('#rvList .rv').first();assert.equal(await mine.locator('.rv-badge.me').textContent(),'Ваш отзыв');
 assert.equal(await mine.locator('.rv-vote').count(),0,'за свой отзыв не голосуют');
 assert.equal(await p.locator('#rtRateT').textContent(),'Ваша оценка');assert.equal(await p.locator('#tapStars button.on').count(),4);
 await p.locator('#rvList .rv').nth(1).locator('[data-act=vote][data-v="1"]').click();
 await p.waitForFunction(()=>/8 человек считают/.test(document.querySelectorAll('#rvList .rv')[1].textContent));
 assert.deepEqual(o.calls.find(x=>x.fn==='iva_app_review_vote').body,{p_id:11,p_helpful:true});
 assert.equal(await p.locator('#rvList .rv').nth(1).locator('[data-v="1"].on').count(),1);
 await mine.locator('[data-act=edit]').click();await p.waitForSelector('#shTxt');
 assert.equal(await p.locator('#shTxt').inputValue(),'Мой отзыв');assert.equal(await p.locator('#shStars button.on').count(),4);
 await p.locator('#shStars [data-r="2"]').click();await p.locator('#shTxt').fill('Стало лучше');
 await p.locator('#shSave').click();await p.waitForFunction(()=>/на проверку/.test((document.getElementById('toast')||{}).textContent||''));
 assert.deepEqual(o.calls.find(x=>x.fn==='iva_app_review_save').body,{p_rating:2,p_body:'Стало лучше',p_version:'10.129'});
 assert.deepEqual(errors,[]);await c.close();n++;}

// 2б. Вернулся после входа с выбранной оценкой — сразу открывается окно отзыва с ней
{const o={email:'qa-new@example.invalid',stats,list:[rv(11,{})],rate:5};const {c,p,errors}=await open(o);
 await p.waitForSelector('#shStars');assert.equal(await p.locator('#shStars button.on').count(),5);
 assert.equal(await p.evaluate(()=>sessionStorage.getItem('iva_app_rate')),null);
 await shot(p,'app-390-write.png');assert.deepEqual(errors,[]);await c.close();n++;}

// 3. Создатель: «Ответить», «Скрыть» / «Опубликовать» прямо на странице
{const o={email:'eclips.ru@mail.ru',stats,list:[rv(11,{}),rv(14,{name:'Олег',status:'pending',body:'Сомнительный текст'})]};
 const {c,p,errors}=await open(o);await p.waitForFunction(()=>document.querySelectorAll('#rvList .rv-act').length===2);
 const pend=p.locator('#rvList .rv').nth(1);assert.equal(await pend.locator('.rv-badge').textContent(),'на проверке');
 await pend.locator('[data-act=show]').click();await p.waitForFunction(()=>/Отзыв опубликован/.test((document.getElementById('toast')||{}).textContent||''));
 assert.deepEqual(o.calls.find(x=>x.fn==='iva_app_review_status').body,{p_id:14,p_status:'visible'});
 await p.locator('#rvList .rv').first().locator('[data-act=reply]').click();await p.waitForSelector('#rpTxt');
 await p.locator('#rpTxt').fill('Спасибо!');await p.locator('#rpSave').click();
 await p.waitForFunction(()=>/Ответ сохранён/.test((document.getElementById('toast')||{}).textContent||''));
 assert.deepEqual(o.calls.find(x=>x.fn==='iva_app_review_reply').body,{p_id:11,p_reply:'Спасибо!'});
 assert.deepEqual(errors,[]);await c.close();n++;}

// 4. SQL ещё не выполнен: честное «скоро», без выдуманных отзывов; создателю — подсказка, какой файл выполнить
{const o={email:'eclips.ru@mail.ru',noSql:true};const {c,p,errors}=await open(o);
 await p.waitForFunction(()=>/скоро появятся/.test(document.getElementById('rvList').textContent));
 assert((await p.locator('#rvList').textContent()).includes('20261120_app_reviews.sql'));
 assert.equal(await p.locator('#rtRate').isHidden(),true);assert.equal(await p.locator('#rtTools').isVisible(),false);assert.equal(await p.locator('#rtAvg').textContent(),'—');
 assert.equal(await p.locator('#stRateN').textContent(),'отзывы скоро');
 assert.deepEqual(errors,[]);await c.close();n++;}

// 5. iPhone: «только для Android», кнопки «Установить» нет
{const o={ua:IPHONE,stats,list};const {c,p,errors}=await open(o);await p.waitForSelector('body.is-ios');
 assert.equal(await p.locator('#apInstall').isVisible(),false);assert.equal(await p.locator('.ap-ios').isVisible(),true);
 await p.evaluate(()=>window.scrollTo(0,900));await p.waitForTimeout(300);assert.equal(await p.locator('#apSticky').isVisible(),false);
 assert.deepEqual(errors,[]);await c.close();n++;}

// 6. Компьютер: справа QR-код на эту же страницу; «Установить» подсказывает поставить с телефона
{const o={vp:{width:1440,height:900},ua:'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36',stats,list};
 const {c,p,errors}=await open(o);await p.waitForFunction(()=>document.querySelectorAll('#rvList .rv').length===3);
 assert.equal(await p.locator('.ap-qr').isVisible(),true);assert.equal(await p.locator('.ap-qr img').getAttribute('src'),'assets/qr-app-logo.png');
 await shot(p,'app-1440.png');
 await p.locator('#apInstall').click();await p.waitForSelector('#sheet');
 assert.equal(await p.locator('#sheet .sheet-h b').textContent(),'Установите на телефон');
 assert.deepEqual(errors,[]);await c.close();n++;}

// 7. Планшет 768: без горизонтальной прокрутки страницы
{const o={vp:{width:768,height:1024},stats,list};const {c,p,errors}=await open(o);await p.waitForFunction(()=>document.querySelectorAll('#rvList .rv').length===3);
 assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),true,'нет горизонтальной прокрутки на 768');
 await shot(p,'app-768.png');assert.deepEqual(errors,[]);await c.close();n++;}

await b.close();console.log('app-page-browser: ✔ '+n+' сценариев (гость, свой отзыв, возврат после входа, создатель, нет SQL, iPhone, компьютер, 768)');})()
.catch(e=>{console.error(e);process.exit(1);});
