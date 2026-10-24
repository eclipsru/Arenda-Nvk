// Проверка плиток категорий в Chromium (только для разработки, на сайт не идёт).
// Нужны: npm i @sparticuz/chromium@131 puppeteer-core@23 (в папке запуска), библиотеки Chromium в $LIBS,
// дерево категорий в $TREE ({cats:[...], subs:[...]}), сайт на http://127.0.0.1:8090.
//   node cat-tiles-check.mjs 390            все категории по очереди: ошибки JS, битые картинки, плитки без картинки
//   node cat-tiles-check.mjs 390 climate    + снимок плиток категории в out/tiles-390-climate.jpg
import chromium from '@sparticuz/chromium';
import puppeteer from 'puppeteer-core';
import fs from 'fs';
const TREE = process.env.TREE || '/tmp/cats/tree.json', LIBS = process.env.LIBS || '/tmp/shot/libs/lib';
const w = +(process.argv[2]||390); const levels = process.argv.slice(3);
const tree = JSON.parse(fs.readFileSync(TREE,'utf8'));
fs.mkdirSync('out', {recursive:true});
const b = await puppeteer.launch({executablePath: await chromium.executablePath(), args:[...chromium.args,'--lang=ru-RU'], headless:true, env:{...process.env, LD_LIBRARY_PATH:LIBS}});
const pg = await b.newPage();
await pg.setViewport({width:w, height:w<600?780:900, isMobile:w<600, hasTouch:w<600});
await pg.evaluateOnNewDocument(()=>{ try{sessionStorage.setItem('prokat_app_banner_closed','1')}catch(e){} });
await pg.setCacheEnabled(false);
await pg.setRequestInterception(true);
pg.on('request', r => {
  const u=r.url();
  if (u.startsWith('http://127.0.0.1')) return r.continue();
  if (u.includes('supabase.co')){
    const path=u.split('supabase.co')[1]; let body='[]', status=200;
    if (path.startsWith('/auth/v1/user')) { status=401; body='{}'; }
    else if (path.startsWith('/rest/v1/cats')) body=JSON.stringify(tree.cats);
    else if (path.startsWith('/rest/v1/subcats')) body=JSON.stringify(tree.subs);
    else if (path.startsWith('/rest/v1/rpc/')) body='null';
    return r.respond({status, contentType:'application/json', headers:{'access-control-allow-origin':'*'}, body});
  }
  r.abort();
});
const errs=[]; pg.on('pageerror', e=>errs.push(e.message.slice(0,160)));
pg.on('response', r=>{ if (r.url().includes('127.0.0.1') && r.status()>=400) errs.push(r.status()+' '+r.url()); });
await pg.goto('http://127.0.0.1:8090/index.html', {waitUntil:'networkidle0', timeout:20000}).catch(()=>errs.push('goto'));
await new Promise(r=>setTimeout(r,800));
const shot = async (tag)=>{ const el = await pg.$('#cats'); const bb = await el.boundingBox(); await pg.screenshot({path:`out/tiles-${w}-${tag}.jpg`, type:'jpeg', quality:72, clip:{x:0,y:Math.max(0,bb.y-60),width:w,height:bb.height+80}, captureBeyondViewport:true}); };
await shot('top');
const missing=[];
for (const lv of (levels.length?levels:tree.cats.map(c=>c.key))){
  await pg.evaluate(()=>{ const b=document.querySelector('[data-back]'); if(b) b.click(); });
  await new Promise(r=>setTimeout(r,150));
  await pg.click(`[data-cat="${lv}"]`); await new Promise(r=>setTimeout(r,500));
  if (levels.length) await shot(lv);
  const info = await pg.evaluate(()=>[...document.querySelectorAll('#cats .cat')].filter(e=>!e.matches('[data-back]')).map(e=>(e.classList.contains('pop')?'P:':'-:')+e.querySelector('b').textContent.replace(/\u00ad/g,'')));
  info.filter(x=>x.startsWith('-:')).forEach(x=>missing.push(lv+' / '+x.slice(2)));
}
await new Promise(r=>setTimeout(r,800));
const bad = await pg.evaluate(()=>[...document.images].filter(i=>i.complete && i.naturalWidth===0).map(i=>i.src));
console.log(JSON.stringify({errs, bad, missing}));
await b.close();
