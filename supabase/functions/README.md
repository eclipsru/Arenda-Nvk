# Функции Supabase (Edge Functions) «Ивы»

Каждая папка с `index.ts` — одна функция. Выкладываются **автоматически** задачей GitHub
`.github/workflows/deploy-functions.yml` при любом изменении в этой папке (ветка агента или main),
решение владельца №21 от 04.10.2026. Проверка JWT выключена — функции защищены сами.

| Функция | Что делает | Документ |
|---|---|---|
| `order_email` | письма клиенту о заявке (принята / выдан / завершена) с ящика владельца на mail.ru | `docs/ПИСЬМА.md` |
| `iva-push` | 10.74: мгновенные уведомления админам — сигнал «проверь сейчас» через Firebase (FCM), без текста сообщений | `docs/PUSH.md` |
| `iva-pay` | 10.133: оплата комиссии площадки через ЮKassa — создаёт платёж, принимает уведомления ЮKassa и засчитывает оплату только по ответу самой ЮKassa | `docs/ЮKASSA.md` |

Секреты функций (`SMTP_USER`, `SMTP_PASS`, для push — `FCM_SERVICE_ACCOUNT`, `FCM_GOOGLE_SERVICES`, для оплаты — `YOOKASSA_SHOP_ID`, `YOOKASSA_SECRET_KEY`) владелец кладёт в секреты GitHub — выкладка переносит их
в Supabase, значения нигде не выводятся. Функции, созданные в Supabase вручную (`order_to_max`,
`vk_wall_post`), здесь не лежат: их кода в репозитории нет, выкладка их не трогает.

Перевыложить без изменений кода: поправить этот файл (любая правка в папке запускает выкладку).

## Если выкладка падает (разбор 04.11.2026)

Журнал запуска: Actions → «Выкладка функций Supabase» → запуск → шаг с ошибкой.

| Сообщение | Причина | Что делать |
|---|---|---|
| `Confirmation required … Re-run with --yes` | новая Supabase CLI спрашивает подтверждение | уже исправлено: `--yes` у всех команд (PR #36) |
| `Failed to resolve latest Supabase CLI release: rate limit exceeded` | при `version: latest` шаг спрашивает GitHub API без ключа | уже исправлено: версия CLI закреплена (PR #37) |
| `unexpected deploy status 401: Invalid access token` | ключ `SUPABASE_ACCESS_TOKEN` в секретах GitHub истёк или отозван | supabase.com/dashboard/account/tokens → Generate new token → GitHub → Settings → Secrets and variables → Actions → `SUPABASE_ACCESS_TOKEN` → Update; затем Actions → «Выкладка функций Supabase» → Run workflow (или правка этого файла) |

Проверка после выкладки: `https://wdxdeatphizclskfmfxi.supabase.co/functions/v1/iva-pay` → `{"ok":true,…}`
(«Requested function was not found» — функция не выложена).
