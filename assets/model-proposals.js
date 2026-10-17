/* Справочник моделей, этап 4 (10.84): вкладка «Модели» в панели владельца (chief.html).
   Админы предлагают модели из формы добавления (iva_model_proposals, SQL 20261018_models_stage4.sql); здесь главный
   админ видит ждущие проверки, может поправить бренд, модель и название и одобрить (модель попадает в справочник,
   объявления предложившего привязываются) или отклонить. Решение — RPC iva_model_decide (только главный админ). */
(function (root) {
  'use strict';

  function h(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function dt(d) { try { return d ? new Date(d).toLocaleString('ru-RU') : '—'; } catch (e) { return '—'; } }

  /* Предложения + подписи видов и полей. Promise → {rows, kinds: {key: title}, fields: {kind: [..]}, error}. */
  function ivaPropLoad(api) {
    var out = { rows: [], kinds: {}, fields: {}, error: '' };
    return Promise.resolve().then(function () {
      return api('/rest/v1/iva_model_proposals?select=id,kind,brand,model,title,specs,proposed_by,status,created_at,decided_at,model_id&order=created_at.desc&limit=300');
    }).then(function (rows) {
      out.rows = Array.isArray(rows) ? rows : [];
      var ks = [];
      out.rows.forEach(function (r) { if (r && r.kind && ks.indexOf(r.kind) < 0) ks.push(r.kind); });
      if (!ks.length) return out;
      var inl = ks.map(encodeURIComponent).join(',');
      return Promise.all([
        Promise.resolve().then(function () { return api('/rest/v1/iva_kinds?select=key,title&key=in.(' + inl + ')'); }).catch(function () { return []; }),
        Promise.resolve().then(function () { return api('/rest/v1/iva_spec_fields?select=kind,key,title,unit,sort&kind=in.(' + inl + ')&order=sort.asc'); }).catch(function () { return []; })
      ]).then(function (r) {
        (Array.isArray(r[0]) ? r[0] : []).forEach(function (k) { if (k && k.key) out.kinds[k.key] = k.title || k.key; });
        (Array.isArray(r[1]) ? r[1] : []).forEach(function (f) { if (f && f.kind && f.key) (out.fields[f.kind] = out.fields[f.kind] || []).push(f); });
        return out;
      });
    }).catch(function (e) {
      var m = String(e && e.message || e || '');
      out.error = /iva_model_proposals|PGRST205|does not exist|schema cache/i.test(m)
        ? 'Таблицы предложений ещё нет — примените SQL supabase/migrations/20261018_models_stage4.sql.' : 'Не удалось загрузить: ' + m;
      return out;
    });
  }

  function specsText(specs, fields) {
    var sp = specs && typeof specs === 'object' ? specs : {}, used = {}, out = [];
    (fields || []).forEach(function (f) {
      if (sp[f.key] == null || String(sp[f.key]).trim() === '') return;
      used[f.key] = 1;
      out.push(f.title + ': ' + String(sp[f.key]).replace(/^(\d+)\.(\d+)$/, '$1,$2') + (f.unit ? '\u00a0' + f.unit : ''));
    });
    Object.keys(sp).forEach(function (k) { if (!used[k] && String(sp[k]).trim() !== '') out.push(k + ': ' + sp[k]); });
    return out.join(' · ');
  }

  function ivaPropPending(d) { return (d && d.rows || []).filter(function (r) { return r.status === 'pending'; }).length; }

  var ST = { pending: 'Ждёт проверки', approved: 'Одобрена', rejected: 'Отклонена' };

  /* HTML вкладки. Ждущие — сверху, с полями правки и кнопками; решённые — последние 30, только просмотр. */
  function ivaPropView(d) {
    if (d.error) return '<div class="sect-h"><h2>Предложенные модели</h2></div><div class="partner-error">' + h(d.error) + '</div>' +
      '<button class="btn sec sm" data-pp-refresh>Повторить</button>';
    var pend = d.rows.filter(function (r) { return r.status === 'pending'; }), done = d.rows.filter(function (r) { return r.status !== 'pending'; }).slice(0, 30);
    function card(r) {
      var kt = d.kinds[r.kind] || r.kind, sp = specsText(r.specs, d.fields[r.kind]);
      var head = '<span class="partner-state">' + h(ST[r.status] || r.status) + '</span><p class="small muted" style="margin:6px 0 0">' + h(kt) +
        ' · предложил ' + h(r.proposed_by) + ' · ' + h(dt(r.created_at)) + (r.decided_at ? ' · решение ' + h(dt(r.decided_at)) : '') + '</p>';
      if (r.status !== 'pending') {
        return '<article class="partner-review-row" data-pp-row="' + h(r.id) + '"><div>' + head + '<h3>' + h(r.title) + '</h3><p>' + h(r.brand + ' ' + r.model) +
          (sp ? ' · ' + h(sp) : '') + '</p></div></article>';
      }
      return '<article class="partner-review-row" data-pp-row="' + h(r.id) + '" style="display:block"><div>' + head +
        '<div class="partner-fields" style="margin-top:10px">' +
          '<label class="partner-field">Бренд<input class="inp" data-pp-f="brand" maxlength="40" value="' + h(r.brand) + '"></label>' +
          '<label class="partner-field">Модель<input class="inp" data-pp-f="model" maxlength="60" value="' + h(r.model) + '"></label>' +
          '<label class="partner-field full">Название в справочнике<input class="inp" data-pp-f="title" maxlength="120" value="' + h(r.title) + '"></label>' +
        '</div>' +
        (sp ? '<p class="small muted" style="margin:8px 0 0">Характеристики: ' + h(sp) + '</p>' : '<p class="small muted" style="margin:8px 0 0">Характеристики не указаны</p>') +
        '<div style="display:flex;gap:10px;flex-wrap:wrap;margin-top:12px">' +
          '<button class="btn sm" data-pp-ok="' + h(r.id) + '">Одобрить</button>' +
          '<button class="btn sec sm" data-pp-no="' + h(r.id) + '">Отклонить</button>' +
        '</div></div></article>';
    }
    return '<div class="sect-h"><h2>Предложенные модели</h2><span class="cnt">' + pend.length + ' ждут</span>' +
        '<button class="btn sec sm" data-pp-refresh>Обновить</button></div>' +
      '<p class="muted small">Админы предлагают модели, которых нет в справочнике, прямо из формы добавления инструмента. ' +
        'Одобренная модель появится в подсказках у всех (в приложении — в течение 30 минут), а объявления предложившего ' +
        'того же вида с этой моделью в названии привяжутся к ней. Бренд, модель и название можно поправить перед одобрением.</p>' +
      '<div class="partner-review-list">' + (pend.length ? pend.map(card).join('') :
        '<div class="empty"><b>Новых предложений нет</b><p>Они появятся, когда админ нажмёт «Предложить модель» в форме добавления.</p></div>') + '</div>' +
      (done.length ? '<h3 style="margin:22px 0 8px">Решённые</h3><div class="partner-review-list">' + done.map(card).join('') + '</div>' : '');
  }

  /* Решение. Promise → '' (готово) или текст ошибки. */
  function ivaPropDecide(api, id, approve, f) {
    f = f || {};
    return Promise.resolve().then(function () {
      return api('/rest/v1/rpc/iva_model_decide', 'POST', { p_id: Number(id), p_approve: !!approve,
        p_brand: approve ? f.brand || null : null, p_model: approve ? f.model || null : null, p_title: approve ? f.title || null : null });
    }).then(function () {
      try { if (approve && typeof localStorage !== 'undefined') localStorage.removeItem('iva_models_v2'); } catch (e) {}
      return '';
    }, function (e) {
      var m = String(e && e.message || '');
      if (/already decided/i.test(m)) return 'По этому предложению уже есть решение — обновите список.';
      if (/required/i.test(m)) return 'Укажите бренд, модель и название.';
      if (/only chief|42501/i.test(m)) return 'Решать может только главный админ.';
      return 'Не удалось: ' + (m || 'нет связи');
    });
  }

  var api = { ivaPropLoad: ivaPropLoad, ivaPropView: ivaPropView, ivaPropDecide: ivaPropDecide, ivaPropPending: ivaPropPending };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else for (var k in api) root[k] = api[k];
})(this);
