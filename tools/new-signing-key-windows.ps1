& {
# Ива — новый ключ подписи приложения (№6), создаётся НА КОМПЬЮТЕРЕ ВЛАДЕЛЬЦА (решение №22, 05.10.2026).
# Ничего устанавливать не нужно: всё встроено в Windows. Ключ никуда не отправляется — только в буфер обмена,
# чтобы вы сами вставили его в секреты GitHub. Пароль генерируется случайно (24 знака).
# Результат: папка KLUCH-IVA-2026 на Рабочем столе (ключ .pfx, ключ текстом, пароль) — для резервных копий.
$ErrorActionPreference = 'Stop'
$dir = Join-Path ([Environment]::GetFolderPath('Desktop')) 'KLUCH-IVA-2026'
if (Test-Path (Join-Path $dir '1-iva-release.pfx')) { Write-Host 'Ключ уже создан раньше — папка KLUCH-IVA-2026 на Рабочем столе. Второй раз не создаю.' -ForegroundColor Yellow; return }
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$abc = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789'
$rng = [Security.Cryptography.RandomNumberGenerator]::Create(); $b = New-Object byte[] 24; $rng.GetBytes($b)
$pw = -join ($b | ForEach-Object { $abc[$_ % $abc.Length] })
Write-Host 'Создаю ключ (до минуты)...'
$cert = New-SelfSignedCertificate -Subject 'CN=Iva Prokat, OU=Mobile, O=Iva, L=Novocherkassk, C=RU' -FriendlyName 'iva-release' `
  -KeyAlgorithm RSA -KeyLength 4096 -HashAlgorithm SHA256 -KeyExportPolicy Exportable -KeyUsage DigitalSignature `
  -NotAfter (Get-Date).AddYears(30) -CertStoreLocation 'Cert:\CurrentUser\My'
$sha = [BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($cert.RawData)).Replace('-', '')
$pfx = Join-Path $dir '1-iva-release.pfx'
Export-PfxCertificate -Cert $cert -FilePath $pfx -Password (ConvertTo-SecureString $pw -AsPlainText -Force) | Out-Null
try { Remove-Item ('Cert:\CurrentUser\My\' + $cert.Thumbprint) -DeleteKey } catch { }
$txt = [Convert]::ToBase64String([IO.File]::ReadAllBytes($pfx))
Set-Content -Path (Join-Path $dir '2-key-text-for-GitHub.txt') -Value $txt -NoNewline -Encoding ASCII
Set-Content -Path (Join-Path $dir '3-password.txt') -Value $pw -NoNewline -Encoding ASCII
Set-Content -Path (Join-Path $dir '0-README.txt') -Encoding UTF8 -Value @"
Ключ подписи приложения Ива (№6), создан $(Get-Date -Format 'dd.MM.yyyy HH:mm').
1-iva-release.pfx — ключ (файл). 2-key-text-for-GitHub.txt — тот же ключ текстом. 3-password.txt — пароль.
Отпечаток сертификата (не секрет): $sha
Хранить: секреты GitHub + флешка + облако в архиве с паролем + пароль в менеджере паролей.
Никому не присылать, в репозиторий не класть. Памятка: docs/КЛЮЧ-ПОДПИСИ.md
"@
Set-Clipboard -Value $txt
Write-Host ''
Write-Host 'ШАГ 1. Текст ключа СКОПИРОВАН.' -ForegroundColor Green
Write-Host '  GitHub → Settings → Secrets and variables → Actions → ANDROID_KEYSTORE_B64 → карандаш → Ctrl+V → Update secret'
Read-Host '  Сделали? Нажмите Enter'
Set-Clipboard -Value $pw
Write-Host 'ШАГ 2. Пароль СКОПИРОВАН.' -ForegroundColor Green
Write-Host '  GitHub → ANDROID_KEYSTORE_PASS → карандаш → Ctrl+V → Update secret. И вставьте его же в менеджер паролей.'
Read-Host '  Сделали? Нажмите Enter'
Set-Clipboard -Value ' '
Write-Host ''
Write-Host ('ГОТОВО. Отпечаток ключа (не секрет): ' + $sha.Substring(0, 8) + '…') -ForegroundColor Green
Write-Host 'ШАГ 3. Сейчас откроется папка KLUCH-IVA-2026 — скопируйте её на флешку и в облако (в архив с паролем).'
Write-Host 'Потом напишите агенту: «новый ключ положил».'
Start-Process explorer.exe $dir
}
