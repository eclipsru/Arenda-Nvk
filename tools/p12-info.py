#!/usr/bin/env python3
"""Ива — что можно узнать о файле ключа подписи (.p12) БЕЗ пароля (05.10.2026).

Зачем: в истории приложения было 5 разных ключей подписи (см. docs/ЖУРНАЛ.md, 05.10.2026),
а к файлу владельца пароль не подошёл. В файле .p12 часть сведений хранится открыто:
имя ключа (alias), «метка ключа» (localKeyId) и способ шифрования. По ним можно понять,
какой это ключ, не зная пароля:
  * OpenSSL пишет в метку отпечаток SHA-1 сертификата → сверяем с 5 известными;
  * Java keytool пишет «Time <миллисекунды>» → время создания записи; сверяем с датами 5 ключей;
  * если сертификат лежит незашифрованным — сверяем его отпечаток напрямую.
Секретов не выводит: ни пароля, ни закрытого ключа здесь нет и быть не может.
Запуск: python3 tools/p12-info.py файл.p12
"""
import hashlib
import sys
from datetime import datetime, timezone

# Сертификаты подписи всех версий приложения из истории репозитория (SHA-256, SHA-1, время выпуска UTC).
KNOWN = [
    ('№1 исходный (v1.1…10.11 и 10.11-recovery, 14–24.09)', '6D077A926738F40B025AC98A0594DE2B06E371E16599FAACD89FC6BA4C44D606', '572A141784F8FC3CD213D079B6497E8B9AACCE07', '2026-09-14 12:02:07'),
    ('№2 разовый (сборка 24.09 12:54)', 'ACBF8076F1C1CF32234AE25CD0C0F092C157D7EBA7E4FD464FCA8C14532F466F', '7BAD7B29C62C51ECE4E63106FB4AEF0E5F00D75F', '2026-09-24 12:52:57'),
    ('№3 разовый (сборки 24.09 13:19 и 15:37)', '1398FE07906F60D53D8D863B5814180B36759B719B5F739D43B17DC2101A0DA7', '61C1D0BBE39E9AE0D421CBE1D23CA9B7D5684D80', '2026-09-24 13:18:32'),
    ('№4 первая 10.12 (24.09 21:36)', '8AD9D6A9DEC4CB0262F1628C2888E3A85C188AE8A6E42F2424BBF2E67699ADC3', 'D31F07C2CC39A3693EC3C2CAE92223B2CFF7B8FC', '2026-09-24 21:23:12'),
    ('№5 (10.12-clone, 10.13, 10.14; пароль утерян)', '4A70CC4F25C011B73343932DD2ABD861F1C6B096206868AEA4DE3D4E411882D7', 'EF6C6075BDC31E10C28D767393AFE5BEFD3D49AD', '2026-09-24 22:12:47'),
]
OIDS = {
    '1.2.840.113549.1.9.20': 'friendlyName', '1.2.840.113549.1.9.21': 'localKeyId',
    '1.2.840.113549.1.7.1': 'data', '1.2.840.113549.1.7.6': 'encryptedData',
    '1.2.840.113549.1.12.10.1.2': 'shroudedKeyBag', '1.2.840.113549.1.12.10.1.3': 'certBag',
    '1.2.840.113549.1.9.22.1': 'x509Certificate',
    '1.2.840.113549.1.5.13': 'PBES2', '2.16.840.1.101.3.4.1.42': 'AES-256-CBC',
    '1.2.840.113549.1.12.1.3': 'PBE-SHA1-3DES', '1.2.840.113549.1.12.1.6': 'PBE-SHA1-RC2-40',
    '1.2.840.113549.2.9': 'HmacSHA256', '2.16.840.1.101.3.4.2.1': 'SHA-256', '1.3.14.3.2.26': 'SHA-1',
}


def tlv(b, i):
    tag = b[i]; i += 1
    ln = b[i]; i += 1
    if ln & 0x80:
        n = ln & 0x7F
        ln = int.from_bytes(b[i:i + n], 'big'); i += n
    return tag, b[i:i + ln], i + ln


def oid(v):
    first = v[0]; out = [first // 40, first % 40]; x = 0
    for c in v[1:]:
        x = (x << 7) | (c & 0x7F)
        if not c & 0x80:
            out.append(x); x = 0
    return '.'.join(map(str, out))


def walk(b, out, depth=0):
    """Обходит DER; заходит внутрь OCTET STRING, если там тоже DER (так устроен .p12)."""
    i = 0
    while i < len(b):
        try:
            tag, val, i = tlv(b, i)
        except (IndexError, ValueError):
            return False
        if i > len(b):
            return False
        out.append((depth, tag, val))
        if tag & 0x20 or tag == 0x04:
            sub = []
            if walk(val, sub, depth + 1):
                out.extend(sub)
            elif tag & 0x20:
                return False
    return True


def main(path):
    raw = open(path, 'rb').read()
    print(f'Файл: {len(raw)} байт, SHA-1 файла {hashlib.sha1(raw).hexdigest()[:8]}…')
    if raw[:4] == b'\xfe\xed\xfe\xed':
        print('Формат: JKS (старый формат Java)'); return
    if raw[:1] != b'\x30':
        print('Формат: НЕ файл ключа (.p12)'); return
    items = []
    walk(raw, items)
    names, keyids, algs, certs, iters = [], [], [], [], []
    for n, (d, tag, val) in enumerate(items):
        if tag == 0x06:
            o = oid(val); name = OIDS.get(o)
            if name in ('friendlyName', 'localKeyId'):
                for d2, t2, v2 in items[n + 1:n + 4]:
                    if name == 'friendlyName' and t2 == 0x1E:
                        names.append(v2.decode('utf-16-be', 'replace')); break
                    if name == 'localKeyId' and t2 == 0x04 and not (v2[:1] == b'\x30' and len(v2) > 40):
                        keyids.append(v2); break
            elif name == 'x509Certificate':
                for d2, t2, v2 in items[n + 1:n + 4]:
                    if t2 == 0x04 and v2[:1] == b'\x30':
                        certs.append(v2); break
            elif name and name not in ('data',):
                algs.append(name)
        elif tag == 0x02 and 1 <= len(val) <= 3:
            iters.append(int.from_bytes(val, 'big'))
    print('Имя ключа (alias):', ', '.join(dict.fromkeys(names)) or 'не записано открыто')
    print('Способ шифрования:', ', '.join(dict.fromkeys(a for a in algs if a not in ('friendlyName', 'localKeyId'))) or '—',
          '| число повторов:', max(iters) if iters else '—')
    verdict = []
    for c in certs:
        s256 = hashlib.sha256(c).hexdigest().upper()
        hit = [k for k in KNOWN if k[1] == s256]
        verdict.append(f'сертификат лежит открыто: {s256[:8]}… → ' + (hit[0][0] if hit else 'НЕ из 5 известных'))
    for kid in dict.fromkeys(keyids):
        if kid.startswith(b'Time '):
            try:
                t = datetime.fromtimestamp(int(kid[5:]) / 1000, tz=timezone.utc)
                near = min(KNOWN, key=lambda k: abs((datetime.strptime(k[3], '%Y-%m-%d %H:%M:%S').replace(tzinfo=timezone.utc) - t).total_seconds()))
                dt = abs((datetime.strptime(near[3], '%Y-%m-%d %H:%M:%S').replace(tzinfo=timezone.utc) - t).total_seconds())
                verdict.append(f'метка Java keytool: запись создана {t:%d.%m.%Y %H:%M:%S} UTC; ближайший ключ — {near[0]} '
                               f'(разница {int(dt // 60)} мин{"" if dt < 3600 else "; большая разница — возможно, файл пересохраняли (смена пароля)"})')
            except ValueError:
                verdict.append('метка Java keytool нечитаема')
        elif len(kid) == 20:
            h = kid.hex().upper(); hit = [k for k in KNOWN if k[2] == h]
            verdict.append(f'метка OpenSSL (SHA-1 сертификата {h[:8]}…) → ' + (hit[0][0] if hit else 'НЕ из 5 известных'))
        else:
            verdict.append(f'метка ключа другого вида ({len(kid)} байт)')
    print('Какой это ключ:', '; '.join(verdict) if verdict else 'по открытой части не определить (нужен пароль)')


if __name__ == '__main__':
    main(sys.argv[1])
