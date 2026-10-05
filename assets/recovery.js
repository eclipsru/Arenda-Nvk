/* Ива — перехват ссылки «восстановить пароль» из письма (баг владельца 04.10.2026).
   Письмо Supabase (из приложения или с сайта, «Забыли пароль») ведёт на главную страницу сайта
   с ключом в адресе: #access_token=…&type=recovery. Раньше ни одна страница его не обрабатывала —
   человек просто попадал на главную. Теперь любая из страниц, куда может вести ссылка
   (главная, кабинеты, страница приложения, 404), сразу переносит на reset-password.html,
   где можно задать новый пароль. Ключ передаётся только в части адреса после «#» — она не уходит на сервер. */
(function () {
  var h = location.hash || '';
  if (!/[#&](type=recovery|error_code=otp_expired|error=access_denied)\b/.test(h)) return;
  if (/reset-password\.html$/.test(location.pathname)) return;
  var base = location.pathname.indexOf('/Arenda-Nvk/') === 0 ? '/Arenda-Nvk/' : '/';
  location.replace(base + 'reset-password.html' + h);
})();
