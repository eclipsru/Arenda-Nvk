/* ===========================================================
   Ива — проверка «реальности» email: формат + одноразовые
   домены + подтверждение владения ящиком через Supabase.
   Без сборки, ES5-совместимо. Подключать ДО sb.js и любых
   инлайн-скриптов со входом/регистрацией.
   =========================================================== */
var IvaEmailGuard = (function(){
  'use strict';

  /* Одноразовые/временные почтовые домены: с таких ящиков
     подтвердить владение нельзя всерьёз — не принимаем. */
  var DISPOSABLE = [
    'tempmail.com','temp-mail.org','temp-mail.ru','temp-mail.io','tempmailo.com',
    'tempmail.net','tempail.com','mytemp.email','mtemporary.com','temporary-mail.net',
    '10minutemail.com','10minutemail.net','guerrillamail.com','guerrillamail.net',
    'mailinator.com','yopmail.com','yopmail.fr','trashmail.com','trash-mail.com',
    'dispostable.com','getnada.com','mohmal.com','emailondeck.com','fakemail.net',
    'tempr.email','dropmail.me','sharklasers.com','spambox.us','maildrop.cc',
    'harakirimail.com','throwawaymail.com','mintemail.com','mailnesia.com',
    'easytrashmail.com','tempmail.plus','tmail.ws','mail.tm','mail.gw'
  ];

  var NOT_CONFIRMED_MSG = 'Почта не подтверждена — откройте письмо от «Ивы» и перейдите по ссылке, затем войдите. Письма нет? Нажмите «Отправить письмо ещё раз».';

  function domainOf(email){
    var at = String(email).lastIndexOf('@');
    return at < 0 ? '' : String(email).slice(at + 1).toLowerCase();
  }

  /* Проверка формата и «реальности» адреса.
     Возвращает '' (пусто = можно регистрировать) или текст ошибки. */
  function check(email){
    email = (email == null ? '' : String(email)).trim().toLowerCase();
    if (!email) return 'Введите email';
    if (email.length > 254) return 'Слишком длинный email';
    if (email.indexOf(' ') >= 0) return 'В email не должно быть пробелов';
    var parts = email.split('@');
    if (parts.length !== 2 || !parts[0] || !parts[1]) return 'Введите корректный email: имя@домен';
    var local = parts[0], domain = parts[1];
    if (local.length > 64) return 'Слишком длинная часть email до @';
    if (!/^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+$/.test(local)) return 'Недопустимые символы в email';
    if (local.charAt(0) === '.' || local.charAt(local.length - 1) === '.') return 'Введите корректный email';
    if (local.indexOf('..') >= 0) return 'Введите корректный email';
    if (domain.length > 253 || domain.indexOf('.') < 0) return 'Введите корректный email: после @ нужен домен с точкой';
    if (!/^[a-z0-9.-]+$/.test(domain)) return 'Недопустимые символы в домене email';
    if (domain.indexOf('..') >= 0 || domain.charAt(0) === '.' || domain.charAt(domain.length - 1) === '.') return 'Введите корректный email';
    var labels = domain.split('.');
    var tld = labels[labels.length - 1];
    if (tld.length < 2 || !/^[a-z]+$/.test(tld)) return 'Введите корректный email';
    for (var i = 0; i < labels.length; i++){
      if (!labels[i] || labels[i].length > 63) return 'Введите корректный email';
      if (labels[i].charAt(0) === '-' || labels[i].charAt(labels[i].length - 1) === '-') return 'Введите корректный email';
    }
    for (var d = 0; d < DISPOSABLE.length; d++){
      if (domain === DISPOSABLE[d] || domain.slice(-(DISPOSABLE[d].length + 1)) === '.' + DISPOSABLE[d]){
        return 'Одноразовые ящики не подходят — укажите реальную почту (Mail, Яндекс, Gmail и т.п.)';
      }
    }
    return '';
  }

  /* Подтвердил ли пользователь владение ящиком (поле Supabase). */
  function isConfirmed(user){
    return !!(user && user.email_confirmed_at);
  }

  /* Повторная отправка письма с подтверждением. */
  function resendConfirm(sbUrl, apiKey, email, redirectTo){
    email = (email || '').trim().toLowerCase();
    var url = String(sbUrl).replace(/\/+$/, '') + '/auth/v1/resend';
    if (redirectTo) url += '?redirect_to=' + encodeURIComponent(redirectTo);
    return fetch(url, {
      method: 'POST',
      headers: { apikey: apiKey, 'Content-Type': 'application/json' },
      body: JSON.stringify({ type: 'signup', email: email })
    }).then(function(r){
      return r.text().then(function(t){
        if (!r.ok){
          var m = t;
          try { m = JSON.parse(t).message || JSON.parse(t).msg || t; } catch(e){}
          if (/already confirmed|already been confirmed/i.test(m)) throw new Error('Этот ящик уже подтверждён — просто войдите.');
          if (/rate limit|too many|too_many/i.test(m)) throw new Error('Слишком часто. Подождите минуту и повторите.');
          throw new Error(m);
        }
      });
    });
  }

  return {
    check: check,
    isConfirmed: isConfirmed,
    resendConfirm: resendConfirm,
    domainOf: domainOf,
    NOT_CONFIRMED_MSG: NOT_CONFIRMED_MSG
  };
})();
