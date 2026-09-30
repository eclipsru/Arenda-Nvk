#Requires -Version 5.1
<#
  Ива — смена пароля ключа подписи приложения (Windows / PowerShell).

  Что делает:
    1) ищет keytool (PATH, Oracle/OpenJDK, Android Studio);
    2) запоминает отпечаток SHA-256 сертификата ДО смены пароля;
    3) создаёт копию keystore с НОВЫМ паролем (сам сертификат не меняется!);
    4) сверяет отпечаток ПОСЛЕ — он обязан совпасть до символа;
    5) если найден apksigner, проверяет подпись на реальном APK из репозитория.

  Почему это безопасно: смена пароля не меняет сертификат, значит обновления
  приложения «поверх» у пользователей продолжают работать.

  Запуск:
      powershell -ExecutionPolicy Bypass -File tools\rotate-keystore-windows.ps1
      powershell -ExecutionPolicy Bypass -File tools\rotate-keystore-windows.ps1 -KeystorePath "D:\keys\prokat-new.p12"

  Никакие пароли не сохраняются и никуда не отправляются.
#>
param(
    [string]$KeystorePath = '',
    [string]$Alias = 'prokat'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

if ([Console]::IsInputRedirected -and $env:IVA_KEYSTORE_PIPE -ne '1') {
    Write-Host '  [x]  Ввод перенаправлен (запуск через |, <, планировщик или CI).' -ForegroundColor Red
    Write-Host '  Пароли так вводить нельзя. Запустите скрипт в обычном окне PowerShell:' -ForegroundColor Yellow
    Write-Host '      powershell -ExecutionPolicy Bypass -File tools\rotate-keystore-windows.ps1' -ForegroundColor Yellow
    exit 1
}

function Write-Section($t) { Write-Host ""; Write-Host "== $t ==" -ForegroundColor Cyan }
function Write-Ok($t)      { Write-Host "  [ok] $t" -ForegroundColor Green }
function Write-Warn2($t)   { Write-Host "  [!]  $t" -ForegroundColor Yellow }
function Write-Err($t)     { Write-Host "  [x]  $t" -ForegroundColor Red }

function Find-Keytool {
    $cmd = Get-Command keytool -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $cands = @()
    foreach ($base in @("$env:ProgramFiles\Java", "$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Microsoft\jdk", "$env:ProgramFiles\Android\Android Studio\jbr")) {
        if (Test-Path $base) { $cands += (Get-ChildItem $base -Recurse -Filter keytool.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName) }
    }
    if ($env:LOCALAPPDATA) {
        foreach ($base in @("$env:LOCALAPPDATA\Programs\Android Studio\jbr", "$env:LOCALAPPDATA\Programs\Eclipse Adoptium")) {
            if (Test-Path $base) { $cands += (Get-ChildItem $base -Recurse -Filter keytool.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName) }
        }
    }
    if ($cands.Count -gt 0) { return $cands[0] }
    return $null
}

function Find-ApkSigner {
    if (-not $env:LOCALAPPDATA) { return $null }
    $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools'
    if (-not (Test-Path $sdk)) { return $null }
    $dirs = Get-ChildItem $sdk -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending
    foreach ($d in $dirs) {
        $f = Join-Path $d.FullName 'apksigner.bat'
        if (Test-Path $f) { return $f }
    }
    return $null
}

function Get-Fingerprint([string]$keytool, [string]$ks, [string]$pass, [string]$alias) {
    $out = & $keytool -list -v -keystore $ks -storetype PKCS12 -alias $alias -storepass $pass 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($out | Out-String) }
    $text = $out | Out-String
    $m = [regex]::Match($text, 'SHA256:\s*([0-9A-Fa-f:\s]+)')
    if (-not $m.Success) { throw 'не нашёл строку SHA256 в выводе keytool' }
    return (($m.Groups[1].Value -replace '[^0-9A-Fa-f]', '').ToUpper())
}

Write-Section '1. Поиск keytool'
$keytool = Find-Keytool
if (-not $keytool) {
    Write-Err 'keytool не найден.'
    Write-Host '  Поставьте JDK одной командой (PowerShell от администратора):' -ForegroundColor Yellow
    Write-Host '      winget install -e --id Microsoft.OpenJDK.17'
    Write-Host '  Или используйте keytool из Android Studio: C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe'
    exit 2
}
Write-Ok "keytool: $keytool"

Write-Section '2. Файл ключа'
if ([string]::IsNullOrWhiteSpace($KeystorePath)) {
    $found = Get-ChildItem $root -Filter '*.p12' -ErrorAction SilentlyContinue
    if ($found.Count -ge 1) { $KeystorePath = $found[0].FullName; Write-Host "  найден рядом с проектом: $KeystorePath" }
}
if ([string]::IsNullOrWhiteSpace($KeystorePath)) {
    $KeystorePath = Read-Host '  Укажите полный путь к файлу keystore (.p12)'
}
if (-not (Test-Path $KeystorePath)) { Write-Err "файл не найден: $KeystorePath"; exit 1 }
Write-Ok "keystore: $KeystorePath"
Write-Host "  alias: $Alias (если другой — запустите с -Alias имя)" -ForegroundColor DarkGray

Write-Section '3. Пароли'
$oldSec = Read-Host '  Старый пароль' -AsSecureString
$newSec1 = Read-Host '  Новый пароль (12+ символов)' -AsSecureString
$newSec2 = Read-Host '  Повторите новый пароль' -AsSecureString
$old = [System.Net.NetworkCredential]::new('', $oldSec).Password
$new1 = [System.Net.NetworkCredential]::new('', $newSec1).Password
$new2 = [System.Net.NetworkCredential]::new('', $newSec2).Password
if ([string]::IsNullOrEmpty($old) -or [string]::IsNullOrEmpty($new1)) {
    Write-Err 'Пароль не введён.'
    Write-Host '  Запускайте этот скрипт в обычном окне PowerShell (двойной клик по файлу или из меню «Здесь открыть PowerShell»).' -ForegroundColor Yellow
    Write-Host '  Через перенаправление ввода (| или <) пароли не читаются.' -ForegroundColor Yellow
    exit 1
}
if ($new1 -ne $new2) { Write-Err 'Новые пароли не совпадают.'; exit 1 }
if ($new1.Length -lt 12) { Write-Err 'Новый пароль короче 12 символов.'; exit 1 }
if ($new1 -eq $old) { Write-Warn2 'Новый пароль совпадает со старым — смысла нет.'; exit 1 }
Write-Ok 'Пароли приняты (никуда не сохраняются).'

Write-Section '4. Отпечаток сертификата ДО смены пароля'
try { $fpBefore = Get-Fingerprint $keytool $KeystorePath $old $Alias }
catch { Write-Err "не удалось прочитать keystore старым паролем: $($_.Exception.Message)"; exit 1 }
Write-Ok "SHA-256: $fpBefore"
Write-Host '  Сохраните это значение — оно должно остаться тем же.' -ForegroundColor DarkGray

Write-Section '5. Создание копии с новым паролем'
$dir = Split-Path -Parent $KeystorePath
$rotated = Join-Path $dir 'prokat-new-rotated.p12'
if (Test-Path $rotated) { Write-Warn2 "файл уже существует, будет перезаписан: $rotated" }
& $keytool -importkeystore `
    -srckeystore $KeystorePath -srcstoretype PKCS12 -srcalias $Alias -srcstorepass $old `
    -destkeystore $rotated -deststoretype PKCS12 -destalias $Alias -deststorepass $new1 -destkeypass $new1
if ($LASTEXITCODE -ne 0) { Write-Err 'keytool вернул ошибку — смена пароля не выполнена.'; exit 1 }
Write-Ok "создано: $rotated"

Write-Section '6. Проверка: сертификат не изменился?'
try { $fpAfter = Get-Fingerprint $keytool $rotated $new1 $Alias }
catch { Write-Err "не удалось прочитать новый файл: $($_.Exception.Message)"; exit 1 }
if ($fpAfter -eq $fpBefore) {
    Write-Ok "отпечаток совпал: $fpAfter"
    Write-Host '  Значит, обновления приложения у пользователей продолжат устанавливаться «поверх».' -ForegroundColor Green
} else {
    Write-Err 'ОТПЕЧАТОК ИЗМЕНИЛСЯ! Не используйте этот файл.'
    Write-Host "  было:  $fpBefore" -ForegroundColor Red
    Write-Host "  стало: $fpAfter"  -ForegroundColor Red
    exit 1
}

Write-Section '7. Проверка подписи на реальном APK (если есть apksigner)'
$apksigner = Find-ApkSigner
$apk = Get-ChildItem $root -Filter '*.apk' -ErrorAction SilentlyContinue | Where-Object { $_.Name -match '10\.14|ProkatInstrumenta\.apk' } | Select-Object -First 1
if ($apksigner -and $apk) {
    $test = Join-Path $env:TEMP 'iva-test-signed.apk'
    & $apksigner sign --ks $rotated --ks-key-alias $Alias --ks-pass "pass:$new1" --key-pass "pass:$new1" --out $test $apk.FullName
    if ($LASTEXITCODE -eq 0) {
        $ver = & $apksigner verify --print-certs $test 2>&1 | Out-String
        if ($ver -match [regex]::Escape(($fpAfter -replace '(..)(?=.)', '$1:'))) { Write-Ok 'apksigner: подпись валидна, отпечаток совпал' }
        elseif ($ver -match 'Verifies') { Write-Ok 'apksigner: подписи валидны' } else { Write-Warn2 'apksigner: проверьте вывод вручную' }
    } else { Write-Warn2 'тестовая подпись не удалась (это не блокирует — проверьте при следующей сборке)' }
    Remove-Item $test -ErrorAction SilentlyContinue
} else {
    Write-Warn2 'apksigner или APK не найдены — шаг пропущен (не критично)'
}

Write-Section 'Что сделать дальше (вручную)'
Write-Host '  1. Новый файл prokat-new-rotated.p12 — сделайте рабочим (переименуйте как удобно).'
Write-Host '  2. Старый файл с прежним паролем — удалите, чтобы не путаться.'
Write-Host '  3. Новый пароль — в менеджер паролей (Bitwarden/KeePass/1Password).'
Write-Host '  4. Сделайте ДВЕ резервные копии keystore (флешка + облако).'
Write-Host '     Потеря keystore = невозможность обновлять приложение в Android.'
Write-Host '  5. Проверьте, что в репозитории нет пароля: bash tools/check.sh (или node tools/check-release.js)'
Write-Section 'Готово'
