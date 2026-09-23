/* Shared Russian Photon suggestions. Same public endpoint as the original delivery form.
   No auth tokens, session data or full user records are sent to the geocoder. */
window.IvaGeo = (function(){
  const endpoint='https://photon.komoot.io/api/';
  const cache=new Map();let uid=0,closeActive=null;
  const text=x=>String(x||'').trim().replace(/\s+/g,' ');
  const ru=x=>/[а-яё]/i.test(x)&&!/[a-z]/i.test(x);
  const escape=x=>text(x).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  function city(raw){
    const s=text(raw);if(!s)return '';
    const known=typeof CITIES!=='undefined'?CITIES:[];
    const parts=s.split(/[,;\n]/).map(x=>x.trim());
    const strip=x=>x.replace(/^(?:город\s+|г[.\s]+|пос[.\s]+|пос[её]лок\s+|пгт[.\s]+|село\s+|деревня\s+|д\.\s*|с\.\s*)/i,'').trim();
    // Prefer explicit locality markers, then complete components. Never match a street substring.
    const marked=parts.filter(x=>/^(?:г[.\s]|город\s|пос[.\s]|пос[её]лок\s|пгт[.\s]|село\s|деревня\s)/i.test(x));
    for(const part of marked.concat(parts)){
      let p=strip(part);
      if(/сельсовет|муниципальн|городск(?:ой|ое) (?:округ|поселение)|сельское поселение/i.test(p))continue;
      if(!p || /^(?:Россия|Российская Федерация)$/i.test(p) || /(?:область|обл\.?|район|р-н|край|республика|округ)(?:\s|$)/i.test(p))continue;
      if(/^(?:ул[.\s]|улица\s|проспект\s|пр-т\s|переулок\s|пер[.\s]|шоссе\s|набережная\s|дом\s|д\.\s*\d)/i.test(p))continue;
      const match=known.find(c=>p.toLowerCase().startsWith(c.toLowerCase())&&(!p[c.length]||!/[а-яёa-z-]/i.test(p[c.length])));
      if(match)return match;
      p=p.split(/\s+(?:ул\.?|улица|проспект|пр-т|переулок|пер\.?|шоссе|набережная|дом)\s/i)[0].trim();
      if(/(?:^|\s)(?:улица|ул\.?|проспект|переулок|шоссе|набережная|дом)(?:\s|$)/i.test(p))continue;
      if(p.length>=2&&p.length<=100&&ru(p)&&/^[А-ЯЁа-яё .()—–-]+$/.test(p))return p;
    }
    return '';
  }
  function addressCity(raw){
    const s=text(raw),c=city(s);if(!c)return '';
    if(/(?:^|[,;]\s*)(?:г[.\s]|город\s|пос[её]лок\s|пгт[.\s]|село\s|деревня\s)/i.test(s))return c;
    if(/^(?:ул[.\s]|улица\s|проспект\s|пр-т\s|переулок\s|пер[.\s]|шоссе\s|набережная\s)/i.test(s))return '';
    const known=typeof CITIES!=='undefined'?CITIES:[];
    if(known.some(k=>k.toLowerCase()===c.toLowerCase()))return c;
    if(s.includes(',')||s.includes(';')||/\s(?:ул\.?|улица|проспект|переулок|шоссе|дом)\s/i.test(s))return c;
    return '';
  }
  function feature(ft,mode){
    const p=ft.properties||{};
    if(String(p.countrycode||'').toUpperCase()!=='RU')return null;
    const isPlace=p.osm_key==='place'&&['city','town','village','hamlet','isolated_dwelling'].includes(p.osm_value);
    const isCity=p.type==='city'||isPlace;
    const locality=city(isCity?p.name:(p.city||p.town||p.village||p.locality||''));
    const region=ru(text(p.state))?text(p.state):'';
    if(mode==='region'){
      const value=p.type==='state'||p.osm_value==='state'?text(p.name):region;
      return ru(value)?{value,label:value,city:'',region:value,source:'photon'}:null;
    }
    if(!locality)return null;
    if(mode==='city')return {value:locality,label:locality,city:locality,region,source:'photon'};
    const street=text(p.street||((p.type==='street'||p.osm_key==='highway')?p.name:''));
    if(street&&!ru(street))return null;
    const number=text(p.housenumber);
    let parts=[locality,street,number?'д. '+number:''];
    if(!street&&!number&&!isCity&&p.name&&p.name!==locality){if(!ru(text(p.name)))return null;parts.push(text(p.name));}
    const value=parts.filter(Boolean).join(', ');
    return {value,label:value,city:locality,region,coordinates:ft.geometry&&ft.geometry.coordinates,source:'photon'};
  }
  async function search(q,mode='address',signal){
    q=text(q).slice(0,200);if(q.length<2)return [];
    const key=mode+'|'+q.toLowerCase(),hit=cache.get(key);
    if(hit&&Date.now()-hit.at<300000)return hit.rows;
    if(!signal){const controller=new AbortController(),timeout=setTimeout(()=>controller.abort(),7000);
      try{return await search(q,mode,controller.signal);}finally{clearTimeout(timeout);}
    }
    const url=new URL(endpoint);url.searchParams.set('q',q);url.searchParams.set('lang','default'); // Public instance has no ru index; local Russian OSM names are selected explicitly.
    url.searchParams.set('countrycode','RU');url.searchParams.set('limit','8');
    if(mode==='city'){url.searchParams.append('layer','city');url.searchParams.append('layer','locality');}
    if(mode==='region')url.searchParams.set('layer','state');
    const response=await fetch(url,{signal,credentials:'omit',referrerPolicy:'no-referrer'});
    if(!response.ok)throw new Error('Сервис подсказок временно недоступен');
    const json=await response.json();if(!Array.isArray(json.features))throw new Error('Некорректный ответ справочника');
    const rows=[],seen=new Set();json.features.forEach(ft=>{const r=feature(ft,mode);if(r){const k=r.value+'|'+r.region;if(!seen.has(k)){seen.add(k);rows.push(r);}}});
    cache.set(key,{at:Date.now(),rows});if(cache.size>60)cache.delete(cache.keys().next().value);
    return rows;
  }
  function locals(q){
    const cities=typeof availableCities==='function'?availableCities():(typeof CITIES!=='undefined'?CITIES:[]);
    const seen=new Set();return cities.map(city).filter(c=>c&&c.toLowerCase().includes(q.toLowerCase())&&!seen.has(c)&&seen.add(c)).slice(0,8).map(c=>({value:c,label:c,city:c,region:'Город на сайте',source:'local'}));
  }
  function attach(input,mode){
    if(input.dataset.geoBound)return;input.dataset.geoBound=mode;input.autocomplete='off';input.removeAttribute('list');
    const wrap=document.createElement('div');wrap.className='geo-wrap';input.before(wrap);wrap.append(input);
    const panel=document.createElement('div');panel.className='geo-panel';panel.hidden=true;wrap.append(panel);
    const listId='geo-list-'+(++uid);input.setAttribute('role','combobox');input.setAttribute('aria-autocomplete','list');input.setAttribute('aria-expanded','false');input.setAttribute('aria-controls',listId);
    let rows=[],active=-1,timer,controller,seq=0,committing=false;
    function hide(){panel.hidden=true;input.setAttribute('aria-expanded','false');input.removeAttribute('aria-activedescendant');active=-1;}
    function cancel(){clearTimeout(timer);if(controller)controller.abort();seq++;}
    function display(items,note){
      if(!input.isConnected||document.activeElement!==input)return;
      if(closeActive&&closeActive!==hide)closeActive();closeActive=hide;rows=items;active=-1;
      panel.innerHTML='<div id="'+listId+'" role="listbox" aria-label="Подсказки '+(mode==='address'?'адреса':mode==='region'?'региона':'города')+'">'+items.map((r,i)=>'<div role="option" aria-selected="false" id="'+listId+'-'+i+'" data-geo-index="'+i+'" class="geo-option"><b>'+escape(r.label)+'</b>'+(r.region?'<small>'+escape(r.region)+'</small>':'')+'</div>').join('')+'</div>'+(note?'<div class="geo-message" role="status">'+escape(note)+'</div>':'')+'<div class="geo-credit">Подсказки: Photon · <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer">© OpenStreetMap</a></div>';
      panel.hidden=false;input.setAttribute('aria-expanded','true');
    }
    function setValue(el,value){if(!el)return;el.value=value;el.setCustomValidity('');el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}
    function pick(r){
      if(!r)return;cancel();committing=true;setValue(input,r.value);committing=false;
      if(mode==='address'){
        const point=input.closest('[data-point]');
        const linked=point?point.querySelector('[name="point-city"]'):(input.id==='iAddr'?document.getElementById('iCity'):null);
        if(r.city)setValue(linked,r.city);
      }
      if(input.id==='laCity'&&r.region&&r.source==='photon')setValue(document.getElementById('laRegion'),r.region);
      hide();input.dispatchEvent(new CustomEvent('geo:select',{bubbles:true,detail:r}));
    }
    panel.addEventListener('pointerdown',e=>{if(e.target.closest('[data-geo-index]'))e.preventDefault();});
    panel.addEventListener('click',e=>{const option=e.target.closest('[data-geo-index]');if(option){e.preventDefault();pick(rows[Number(option.dataset.geoIndex)]);}});
    input.addEventListener('input',()=>{
      input.setCustomValidity('');if(committing)return;cancel();const n=seq,q=text(input.value);hide();
      if(q.length<2)return;
      const local=mode==='city'?locals(q):[];display(local,'Ищем подсказки…');
      timer=setTimeout(async()=>{
        controller=new AbortController();const timeout=setTimeout(()=>controller.abort(),7000);
        try{
          let query=q;
          if(mode==='address'){
            const point=input.closest('[data-point]'),ci=point?point.querySelector('[name="point-city"]'):document.getElementById('iCity');
            const context=ci&&city(ci.value);
            if(context&&!q.toLowerCase().includes(context.toLowerCase())&&!addressCity(q))query=context+', '+q;
          }
          const result=await search(query,mode,controller.signal);
          if(n!==seq)return;
          const merged=result.concat(local.filter(l=>!result.some(r=>r.city===l.city)));
          display(merged,merged.length?'':'Не нашли подходящих вариантов. Можно ввести вручную.');
        }catch(e){if(n===seq)display(local,'Подсказки временно недоступны. Можно ввести вручную.');}
        finally{clearTimeout(timeout);}
      },450);
    });
    input.addEventListener('focus',()=>{if(mode==='city'){const found=locals(text(input.value));if(found.length)display(found,'');}});
    input.addEventListener('blur',()=>{cancel();setTimeout(hide,160);});
    input.addEventListener('change',()=>{
      if(mode!=='city'||!text(input.value))return;
      const clean=city(input.value);
      if(!clean){input.setCustomValidity('Укажите только город или населённый пункт на русском языке, без улицы и дома.');return;}
      if(clean!==input.value){input.value=clean;committing=true;input.dispatchEvent(new Event('input',{bubbles:true}));committing=false;}
    });
    input.addEventListener('keydown',e=>{
      if(e.key==='Escape'){cancel();hide();e.preventDefault();e.stopImmediatePropagation();}
      if(!panel.hidden&&rows.length&&['ArrowDown','ArrowUp'].includes(e.key)){
        e.preventDefault();active=(active+(e.key==='ArrowDown'?1:-1)+rows.length)%rows.length;
        panel.querySelectorAll('[role=option]').forEach((el,i)=>el.setAttribute('aria-selected',String(i===active)));
        input.setAttribute('aria-activedescendant',listId+'-'+active);panel.querySelector('#'+listId+'-'+active).scrollIntoView({block:'nearest'});
      }
      if(e.key==='Enter'&&!panel.hidden&&active>=0){e.preventDefault();e.stopImmediatePropagation();pick(rows[active]);}
    });
  }
  function scan(){
    document.querySelectorAll('#profileCity,#laCity,#iCity,[name="point-city"],#citySearch,.geo-filter-city').forEach(el=>attach(el,'city'));
    document.querySelectorAll('#laRegion').forEach(el=>attach(el,'region'));
    document.querySelectorAll('#iAddr,[name="point-address"]').forEach(el=>attach(el,'address'));
    document.querySelectorAll('select#fCity').forEach(select=>{
      if(select.dataset.geoFilterBound)return;select.dataset.geoFilterBound='1';
      const input=document.createElement('input');input.className='inp geo-filter-city';input.placeholder='Найти другой город…';input.setAttribute('aria-label','Найти город по России');input.maxLength=100;select.after(input);
      const apply=()=>{const c=city(input.value);if(!c){input.setCustomValidity('Укажите город без улицы и дома');input.reportValidity();return;}
        if(!Array.from(select.options).some(o=>o.value===c))select.add(new Option(c,c));select.value=c;select.dispatchEvent(new Event('change',{bubbles:true}));};
      attach(input,'city');input.addEventListener('geo:select',apply);input.addEventListener('keydown',e=>{if(e.key==='Enter'){e.preventDefault();apply();}});
    });
  }
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',scan);else scan();
  new MutationObserver(scan).observe(document.documentElement,{childList:true,subtree:true});
  return {endpoint,city,addressCity,feature,search,attach,scan};
})();
