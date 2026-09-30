#Requires -Version 5.1
<#
  Ива — бэкап базы данных Supabase (Windows / PowerShell).

  Что делает:
    1) ищет pg_dump (в PATH или в C:\Program Files\PostgreSQL\*\bin);
    2) спрашивает строку подключения — её берут в Supabase: Project Settings → Database → Connection string → URI;
    3) делает три дампа: полный, только схема, только данные ключевых таблиц;
    4) проверяет, что файлы не пустые и содержат таблицы;
    5) с ключом -Baseline кладёт структуру базы в supabase\migrations\0000_baseline_schema.sql
       (в репозитории базовой схемы нет — без неё восстановить базу «с нуля» невозможно).

  База НЕ изменяется: pg_dump только читает. Ничего не отправляется в GitHub: файлы бэкапа закрыты .gitignore.

  Запуск:
      powershell -ExecutionPolicy Bypass -File tools\backup-windows.ps1
      powershell -ExecutionPolicy Bypass -File tools\backup-windows.ps1 -Baseline
#>
param([switch]$Baseline)

$ErrorActionPreference = 'Stop'
$root   = Split-Path -Parent $PSScriptRoot
$stamp  = Get-Date -Format 'yyyy-MM-dd'
$outDir = Join-Path $root "backup-$stamp"

function Write-Section($t) { Write-Host ""; Write-Host "== $t ==" -ForegroundColor Cyan }
function Write-Ok($t)      { Write-Host "  [ok] $t" -ForegroundColor Green }
function Write-Warn2($t)   { Write-Host "  [!]  $t" -ForegroundColor Yellow }
function Write-Err($t)     { Write-Host "  [x]  $t" -ForegroundColor Red }

function Find-PgDump {
    $cmd = Get-Command pg_dump -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $bases = @()
    if ($env:ProgramFiles)          { $bases += (Join-Path $env:ProgramFiles 'PostgreSQL') }
    if (${env:ProgramFiles(x86)})   { $bases += (Join-Path ${env:ProgramFiles(x86)} 'PostgreSQL') }
    foreach ($base in $bases) {
        if (Test-Path $base) {
            $dirs = Get-ChildItem $base -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending
            foreach ($d in $dirs) {
                $cand = Join-Path $d.FullName 'bin\pg_dump.exe'
                if (Test-Path $cand) { return $cand }
            }
        }
    }
    return $null
}

$tables = @('tools','orders','order_parts','fee_payments','admins','limited_admins',
            'landlord_applications','landlord_application_events','landlord_profiles',
            'reviews','app_messages','likes','order_claims','cats','subcats','directory_landlords')

Write-Section '1. Поиск pg_dump'
$pgDump = Find-PgDump
if (-not $pgDump) {
    Write-Err 'pg_dump не найден.'
    Write-Host '  Установка одной командой (PowerShell от администратора):' -ForegroundColor Yellow
    Write-Host '      winget install -e --id PostgreSQL.PostgreSQL.16'
    Write-Host '  Затем закройте и заново откройте PowerShell и повторите запуск.'
    Write-Host ''
    Write-Host '  Вариант без установки (только данные, без структуры):' -ForegroundColor Yellow
    Write-Host '      Supabase → Table Editor → выбрать таблицу → кнопка ⋮ → Export data → CSV'
    Write-Host ('      Таблицы: ' + ($tables -join ', '))
    exit 2
}
Write-Ok "pg_dump: $pgDump"

Write-Section '2. Строка подключения'
Write-Host '  Где взять: Supabase → Project Settings → Database → Connection string → URI.'
Write-Host '  Вид: postgresql://postgres.<ref>:<ПАРОЛЬ>@aws-0-...pooler.supabase.com:5432/postgres'
Write-Host '  Строка содержит пароль базы — не вставляйте её в переписку и документы.' -ForegroundColor Yellow
$conn = Read-Host '  Вставьте строку подключения (Enter без текста — отмена)'
if ([string]::IsNullOrWhiteSpace($conn)) { Write-Warn2 'Отменено, ничего не делаю.'; exit 0 }

New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$full   = Join-Path $outDir ("backup_full_"   + $stamp + '.sql')
$schema = Join-Path $outDir ("backup_schema_" + $stamp + '.sql')
$data   = Join-Path $outDir ("backup_data_"   + $stamp + '.sql')

Write-Section '3. Дампы (только чтение)'
try {
    Write-Host '  полный дамп (структура + данные)...' -ForegroundColor DarkGray
    & $pgDump $conn --no-owner --no-privileges -f $full
    if ($LASTEXITCODE -ne 0) { throw "pg_dump вернул код $LASTEXITCODE" }
    Write-Ok "полный: $full"
} catch { Write-Err "полный дамп не удался: $($_.Exception.Message)"; exit 1 }

try {
    Write-Host '  только структура...' -ForegroundColor DarkGray
    & $pgDump $conn --no-owner --no-privileges --schema-only -f $schema
    if ($LASTEXITCODE -ne 0) { throw "pg_dump вернул код $LASTEXITCODE" }
    Write-Ok "структура: $schema"
} catch { Write-Warn2 "дамп структуры не удался: $($_.Exception.Message)" }

try {
    Write-Host '  данные ключевых таблиц...' -ForegroundColor DarkGray
    $tArgs = @()
    foreach ($t in $tables) { $tArgs += @('-t', "public.$t") }
    & $pgDump $conn --no-owner --no-privileges --data-only @tArgs -f $data
    if ($LASTEXITCODE -ne 0) { throw "pg_dump вернул код $LASTEXITCODE" }
    Write-Ok "данные: $data"
} catch { Write-Warn2 "дамп данных не удался: $($_.Exception.Message)" }

Write-Section '4. Проверка'
$problems = 0
if (Test-Path $full) {
    $sizeKb = [math]::Round((Get-Item $full).Length / 1KB, 1)
    Write-Ok "размер полного дампа: $sizeKb КБ"
    if ($sizeKb -lt 20) { Write-Warn2 'подозрительно мало — проверьте строку подключения и права'; $problems++ }
} else { Write-Err 'полного дампа нет'; $problems++ }

if (Test-Path $schema) {
    $schemaText  = Get-Content $schema -Raw
    $createCount = ([regex]::Matches($schemaText, 'CREATE TABLE')).Count
    Write-Ok "таблиц в структуре: $createCount"
    if ($createCount -lt 5) { Write-Warn2 'меньше 5 таблиц — похоже, схема не выгрузилась'; $problems++ }
} else { Write-Err 'дампа структуры нет'; $problems++ }

if (Test-Path $data) {
    $dataText  = Get-Content $data -Raw
    $copyCount = ([regex]::Matches($dataText, '(?m)^COPY ')).Count
    Write-Ok "таблиц с данными: $copyCount"
} else { Write-Warn2 'дампа данных нет' }

if ($Baseline -and (Test-Path $schema)) {
    Write-Section '5. Baseline-схема в репозиторий'
    $migDir = Join-Path $root 'supabase\migrations'
    if (-not (Test-Path $migDir)) { New-Item -ItemType Directory -Force -Path $migDir | Out-Null }
    $target = Join-Path $migDir '0000_baseline_schema.sql'
    $header = "-- Снимок структуры рабочей базы Supabase на $stamp.`r`n" +
              "-- Только для восстановления и истории. К обычным миграциям не относится.`r`n" +
              "-- Получен: tools\backup-windows.ps1 -Baseline (pg_dump --schema-only).`r`n`r`n"
    if (Test-Path $target) {
        Write-Warn2 "файл уже существует, не перезаписываю: $target"
    } else {
        Set-Content -Path $target -Value ($header + (Get-Content $schema -Raw)) -Encoding UTF8
        Write-Ok "структура положена в: supabase\migrations\0000_baseline_schema.sql"
        Write-Host '  Дальше: git add supabase/migrations/0000_baseline_schema.sql && git commit -m "chore(db): baseline-схема"' -ForegroundColor DarkGray
    }
}

Write-Section 'Итог'
if ($problems -eq 0) {
    Write-Ok "Готово. Бэкап лежит в: $outDir"
    Write-Host '  Скопируйте эту папку во второе место (внешний диск или облако).' -ForegroundColor DarkGray
    Write-Host '  В GitHub она не попадёт: файлы закрыты правилами .gitignore.' -ForegroundColor DarkGray
    exit 0
} else {
    Write-Warn2 'Есть замечания выше — покажите их мне, разберём.'
    exit 1
}
