#Requires -Version 5.1
<#
  Ива — выгрузка данных из Supabase через REST API (Windows / PowerShell).

  Зачем: pg_dump может быть недоступен (winget падает с ошибкой 403, установка не нужна вообще).
  Этот скрипт сохраняет СОДЕРЖИМОЕ таблиц, не скачивая и не устанавливая ничего.

  Что делает:
    1) спрашивает адрес проекта и ключ service_role (ключ остаётся только в памяти, никуда не пишется);
    2) сам узнаёт список таблиц проекта (через описание API) — или берёт список из -Tables;
    3) постранично выгружает каждую таблицу в отдельный файл <таблица>.ndjson (одна строка = одна запись);
    4) сверяет количество выгруженных строк с количеством в базе и пишет manifest.json;
    5) кладёт всё в папку backup-<дата> (она закрыта .gitignore и не попадёт в GitHub).

  Чего НЕ делает: не меняет базу (только чтение), не выгружает структуру таблиц,
  не выгружает файлы из хранилищ (tool-photos, voice) — их содержимое лежит не в базе.

  Ключ service_role даёт полный доступ к данным. Вставляйте его только в это окно,
  никому не пересылайте, после работы закройте окно.
  Где взять: Supabase → Project Settings → API → Project API keys → service_role.

  Запуск:
      powershell -ExecutionPolicy Bypass -File tools\backup-api-windows.ps1
      powershell -ExecutionPolicy Bypass -File tools\backup-api-windows.ps1 -ProjectUrl https://xxxx.supabase.co
      powershell -ExecutionPolicy Bypass -File tools\backup-api-windows.ps1 -Tables tools,orders,images
#>
param(
    [string]$ProjectUrl,                     # например https://abcdefgh.supabase.co
    [string]$Key,                            # если не задан — спросит и не покажет на экране
    [string]$KeyEnv = 'SUPABASE_SERVICE_ROLE_KEY',  # или возьмёт ключ из переменной среды
    [string[]]$Tables,                        # по умолчанию — все таблицы, которые отдаёт API
    [int]$PageSize = 1000,
    [string]$OutDir
)

$ErrorActionPreference = 'Stop'
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch { }
$ProgressPreference = 'SilentlyContinue'

$root  = Split-Path -Parent $PSScriptRoot
$stamp = Get-Date -Format 'yyyy-MM-dd'
if (-not $OutDir) { $OutDir = Join-Path $root "backup-$stamp\api" }

function Write-Section($t) { Write-Host ""; Write-Host "== $t ==" -ForegroundColor Cyan }
function Write-Ok($t)      { Write-Host "  [ok] $t" -ForegroundColor Green }
function Write-Warn2($t)   { Write-Host "  [!]  $t" -ForegroundColor Yellow }
function Write-Err($t)     { Write-Host "  [x]  $t" -ForegroundColor Red }

# --- запасной список таблиц, если описание API недоступно ---------------------
$fallbackTables = @('tools','orders','order_parts','fee_payments','admins','limited_admins',
                    'landlord_applications','landlord_application_events','landlord_profiles',
                    'reviews','app_messages','likes','order_claims','cats','subcats','directory_landlords')

Write-Section '1. Подключение'
if (-not $ProjectUrl) {
    Write-Host '  Адрес проекта: Supabase → Project Settings → Data API (или Settings → API) → Project URL.'
    Write-Host '  Вид: https://xxxxxxxxxxxx.supabase.co'
    $ProjectUrl = (Read-Host '  Адрес проекта (Enter без текста — отмена)').Trim()
    if ([string]::IsNullOrWhiteSpace($ProjectUrl)) { Write-Warn2 'Отменено.'; exit 0 }
}
$ProjectUrl = $ProjectUrl.TrimEnd('/')
if ($ProjectUrl -notmatch '^https?://') { $ProjectUrl = 'https://' + $ProjectUrl }

if (-not $Key) {
    if ($env:SUPABASE_SERVICE_ROLE_KEY) { $Key = $env:SUPABASE_SERVICE_ROLE_KEY }
    elseif ($env:SUPABASE_KEY)          { $Key = $env:SUPABASE_KEY }
    else {
        Write-Host '  Ключ: Supabase → Project Settings → API → service_role (кнопка Reveal, затем Copy).' -ForegroundColor Yellow
        Write-Host '  Ключ не отображается на экране и никуда не сохраняется.' -ForegroundColor DarkGray
        $sec = Read-Host '  Вставьте ключ service_role (Enter без текста — отмена)' -AsSecureString
        $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
        try   { $Key = [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
        if ([string]::IsNullOrWhiteSpace($Key)) { Write-Warn2 'Отменено.'; exit 0 }
    }
}
$Key = $Key.Trim()
Write-Ok "проект: $ProjectUrl"
Write-Ok ("ключ: найден, длина " + $Key.Length + " символов (не печатаю сам ключ)")

$headers = @{
    'apikey'        = $Key
    'Authorization' = "Bearer $Key"
    'Accept'        = 'application/json'
}

Write-Section '2. Проверка доступа'
try {
    $resp = Invoke-WebRequest -Uri "$ProjectUrl/rest/v1/" -Headers $headers -Method GET -UseBasicParsing -TimeoutSec 60
    Write-Ok ("API отвечает, код " + $resp.StatusCode)
} catch {
    $code = $null
    if ($_.Exception.Response) { try { $code = [int]$_.Exception.Response.StatusCode } catch { } }
    if ($code -eq 401 -or $code -eq 403) {
        Write-Err "Ключ не подошёл (код $code). Нужен именно service_role (не anon и не publishable)."
    } elseif ($code) {
        Write-Err "Проект ответил кодом $code. Проверьте адрес проекта."
    } else {
        Write-Err ("Не удалось связаться: " + $_.Exception.Message)
    }
    exit 1
}

# --- список таблиц: берём из описания API ------------------------------------
$tableNames = @()
if ($Tables) {
    $tableNames = $Tables
    Write-Ok ('список задан вручную: ' + ($tableNames -join ', '))
} else {
    try {
        $spec = $resp.Content | ConvertFrom-Json
        if ($spec.PSObject.Properties.Name -contains 'paths') {
            $tableNames = @($spec.paths.PSObject.Properties.Name | ForEach-Object { $_.TrimStart('/') } |
                            Where-Object { $_ -and $_ -notmatch '^rpc/' } | Sort-Object -Unique)
        }
    } catch { }
    if ($tableNames.Count -gt 0) {
        Write-Ok ("таблиц найдено автоматически: " + $tableNames.Count)
    } else {
        $tableNames = $fallbackTables
        Write-Warn2 ('список таблиц взять не удалось, беру известный: ' + $tableNames.Count + ' шт.')
    }
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$manifest  = @()
$problems  = 0
$totalRows = 0
$skipped   = @()

Write-Section '3. Выгрузка данных (только чтение)'
$index = 0
foreach ($table in $tableNames) {
    $index++
    $safe   = ($table -replace '[^A-Za-z0-9_\-\.]', '_')
    $file   = Join-Path $OutDir ($safe + '.ndjson')
    $url    = "$ProjectUrl/rest/v1/$table" + '?select=*'
    $order  = '&order=id.asc'   # без сортировки страницы могут «съезжать»
    $from   = 0
    $written = 0
    $total   = $null

    Write-Host ("  [{0}/{1}] {2} ..." -f $index, $tableNames.Count, $table) -ForegroundColor DarkGray
    while ($true) {
        $h  = $headers.Clone()
        $h['Prefer'] = 'count=exact'
        $pageUrl = $url + "&limit=$PageSize&offset=$from" + $order
        try {
            $page = Invoke-WebRequest -Uri $pageUrl -Headers $h -Method GET -UseBasicParsing -TimeoutSec 180
        } catch {
            $code = $null
            if ($_.Exception.Response) { try { $code = [int]$_.Exception.Response.StatusCode } catch { } }
            if ($order -and $code -eq 400) {
                # у таблицы нет колонки id — берём без сортировки, но с проверкой количества
                Write-Warn2 "  в таблице $table нет колонки id — выгружаю без сортировки (количество сверю)"
                $order = ''
                continue
            }
            if ($code -eq 404) { Write-Warn2 "  таблицы $table нет в API — пропускаю"; $skipped += $table; break }
            Write-Err "  $table — ошибка $code : $($_.Exception.Message)"
            $problems++
            break
        }
        $cr = $page.Headers['Content-Range']
        if ($cr -is [array]) { $cr = $cr[0] }
        if ($cr -and ($cr -match '/(\d+)$')) { $total = [int]$Matches[1] }

        $content = $page.Content
        if ([string]::IsNullOrWhiteSpace($content) -or $content -eq '[]') { break }
        $rows = $content | ConvertFrom-Json
        if ($null -eq $rows) { break }
        if ($rows -isnot [array]) { $rows = @($rows) }
        if ($rows.Count -eq 0) { break }

        $sb = New-Object System.Text.StringBuilder
        foreach ($row in $rows) {
            [void]$sb.AppendLine(($row | ConvertTo-Json -Compress -Depth 12))
        }
        [System.IO.File]::AppendAllText($file, $sb.ToString(), $utf8NoBom)
        $written += $rows.Count
        $from    += $rows.Count
        if ($rows.Count -lt $PageSize) { break }
        if ($total -and $from -ge $total) { break }
    }

    $totalRows += $written
    if ($total -ne $null -and $written -ne $total) {
        Write-Err ("  ${table}: выгружено $written, а в базе $total — расхождение, нужно повторить")
        $problems++
    } elseif ($written -gt 0) {
        Write-Ok "  ${table}: $written записей → $safe.ndjson"
    } else {
        Write-Ok "  ${table}: пусто"
    }
    if ($written -eq 0 -and (Test-Path $file)) { Remove-Item $file -Force }
    $manifest += [pscustomobject]@{
        table     = $table
        rows      = $written
        in_db     = $total
        file      = if ($written -gt 0) { "$safe.ndjson" } else { $null }
        exported  = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
    }
}

Write-Section '4. Итоговая проверка'
if ($manifest.Count -gt 0 -and $problems -eq 0) {
    Write-Ok "все таблицы выгружены полностью и сверены с базой"
} elseif ($problems -gt 0) {
    Write-Warn2 "есть расхождения или ошибки — покажите этот вывод, разберём"
}
$manifestPath = Join-Path $OutDir 'manifest.json'
$manifestText = [pscustomobject]@{
    project   = $ProjectUrl
    taken_at  = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
    total_rows = $totalRows
    tables    = $manifest
    skipped   = $skipped
    note      = 'Выгрузка данных через REST API. Структура таблиц — см. supabase/migrations/0000_baseline_schema.sql'
} | ConvertTo-Json -Depth 6
[System.IO.File]::WriteAllText($manifestPath, $manifestText, $utf8NoBom)

$sizeMb = 0
if (Test-Path $OutDir) {
    $sizeMb = [math]::Round(((Get-ChildItem $OutDir -File | Measure-Object Length -Sum).Sum / 1MB), 2)
}
Write-Section 'Что дальше'
Write-Host ("  Файлы: " + $OutDir)
Write-Host ("  Всего записей: " + $totalRows + " | Размер: " + $sizeMb + " МБ")
Write-Host '  Скопируйте папку во второе место (внешний диск или облако).' -ForegroundColor DarkGray
Write-Host '  В GitHub она не попадёт: папки backup-* закрыты правилами .gitignore.' -ForegroundColor DarkGray
Write-Host '  Файлы из хранилищ (фотографии инструментов, голосовые) сюда НЕ входят.' -ForegroundColor DarkGray
Write-Host '  Восстановление из этой выгрузки я добавлю отдельным шагом, когда понадобится.' -ForegroundColor DarkGray

if ($problems -eq 0) { exit 0 } else { exit 1 }
