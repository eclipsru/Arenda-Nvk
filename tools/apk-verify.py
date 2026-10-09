#!/usr/bin/env python3
# ============================================================
#  Ива — проверка APK-файла без Java (в песочнице агента нет ни Java, ни apksigner).
#
#  Что делает (ничего не меняет, только читает):
#    1. манифест: пакет, versionName/versionCode, minSdk/targetSdk (androguard);
#    2. схемы подписи v1/v2/v3 и отпечаток сертификата SHA-256 (сравнение с ключами №5/№6);
#    3. целостность ZIP: CRC всех записей;
#    4. resources.arsc: без сжатия и выровнен по 4 байта (иначе Android 11+ не ставит);
#    5. v1 (JAR): дайджесты КАЖДОЙ записи из META-INF/MANIFEST.MF сверяются с файлом —
#       это проверка, что содержимое APK не меняли;
#    6. v2: подпись сверяется открытым ключом сертификата из самого APK —
#       доказывает, что signed_data (сертификат + дайджесты) создана закрытым ключом;
#    7. SHA-256 файла сверяется с эталоном, если он передан.
#
#  Чего НЕ делает: не пересчитывает дайджест содержимого v2 по трём секциям
#  (алгоритм apksigner здесь не воспроизведён) и не проверяет цепочку сертификатов.
#  Это делает apksigner verify в «Сборке приложения» (build-apk.yml).
#
#  Запуск:  python3 tools/apk-verify.py ProkatInstrumenta-10.15.apk [эталон-sha256]
#  В check.sh: раздел 4.4 (пропускается, если нет androguard/cryptography).
#  Откат: удалить файл — на сайт и базу не влияет.
# ============================================================
import base64
import hashlib
import struct
import sys
import zipfile

EXPECTED_PACKAGE = 'ru.prokatnvsk.app'
KEY6_CERT = '5D2B9F8BB54AD0B816A779B5991746957EBD6FA056D8A63D1D1635077BED5DDE'   # ключ №6 (решение №22)
KEY5_CERT = '4A70CC4F25C011B73343932DD2ABD861F1C6B096206868AEA4DE3D4E411882D7'   # ключ №5 (пароль утерян)
MAGIC = b'APK Sig Block 42'
ID_V2 = 0x7109871A
# Идентификаторы алгоритмов подписи (platform/frameworks/base, ApkSignatureSchemeV2Verifier)
SIG_ALG = {
    0x0101: ('pss', 'sha256'), 0x0102: ('pss', 'sha512'),
    0x0103: ('pkcs1', 'sha256'), 0x0104: ('pkcs1', 'sha512'),
    0x0201: ('ecdsa', 'sha256'), 0x0202: ('ecdsa', 'sha512'),
    0x0301: ('dsa', 'sha256'), 0x0302: ('dsa', 'sha512'),
}
ALG_NAME = {
    0x0101: 'RSA PSS + SHA-256', 0x0102: 'RSA PSS + SHA-512',
    0x0103: 'RSA PKCS#1 v1.5 + SHA-256', 0x0104: 'RSA PKCS#1 v1.5 + SHA-512',
    0x0201: 'ECDSA + SHA-256', 0x0202: 'ECDSA + SHA-512',
    0x0301: 'DSA + SHA-256', 0x0302: 'DSA + SHA-512',
}

fails = []


def bad(msg):
    fails.append(msg)
    print('  ✘ ' + msg)


def good(msg):
    print('  ✔ ' + msg)


def info(msg):
    print('  • ' + msg)


def le(b, off, size):
    return int.from_bytes(b[off:off + size], 'little')


def seq(buf, off=0):
    """Length-prefixed последовательность: (смещение значения, длина) для каждого элемента."""
    n = le(buf, off, 4)
    end, cur = off + 4 + n, off + 4
    while cur < end:
        ln = le(buf, cur, 4)
        yield cur + 4, ln
        cur += 4 + ln


def block_pairs(data, start, end):
    """ID-value пары APK Signing Block."""
    cur = start
    while cur + 24 <= end:
        ln = le(data, cur, 8)
        if ln < 4 or cur + 8 + ln > end:
            break
        yield le(data, cur + 8, 4), data[cur + 12:cur + 8 + ln]
        cur += 8 + ln


def verify_v2(data):
    """v2: найти signer, сверить подпись открытым ключом сертификата из самого APK."""
    eocd = data.rfind(b'PK\x05\x06')
    if eocd < 0:
        return bad('v2: не найден конец ZIP (EOCD)')
    cd_off, cd_size = le(data, eocd + 16, 4), le(data, eocd + 12, 4)
    if cd_off + cd_size != eocd:
        return bad(f'v2: центральный каталог не на своём месте ({cd_off}+{cd_size} != {eocd})')
    if data[cd_off - 16:cd_off] != MAGIC:
        return bad('v2: нет APK Signing Block перед центральным каталогом')
    size = le(data, cd_off - 24, 8)
    block_start = cd_off - size - 8
    if block_start < 0 or le(data, block_start, 8) != size:
        return bad('v2: размер блока подписи не сходится')
    v2 = dict(block_pairs(data, block_start + 8, cd_off - 24)).get(ID_V2)
    if not v2:
        return bad('v2: в блоке подписи нет пары v2 (0x7109871a)')
    info(f'v2: APK Signing Block найден ({size + 8} байт, смещение {block_start})')

    signer_len = le(v2, 4, 4)
    signer = v2[8:8 + signer_len]
    sd_len = le(signer, 0, 4)
    signed_data = signer[4:4 + sd_len]          # подписывается БЕЗ поля длины (проверено на наших APK)
    body = signed_data
    dig_len = le(body, 0, 4)
    digests = body[0:4 + dig_len]
    certs_len = le(body, 4 + dig_len, 4)
    certs = body[4 + dig_len:8 + dig_len + certs_len]
    sigs_len = le(signer, 4 + sd_len, 4)
    signatures = signer[4 + sd_len:8 + sd_len + sigs_len]

    d_off, _ = next(seq(digests))
    d_alg = le(digests, d_off, 4)
    d_len = le(digests, d_off + 4, 4)
    info(f'v2: в signed_data — дайджест содержимого {ALG_NAME.get(d_alg, hex(d_alg))}, {d_len} байт')

    try:
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import ec, padding
        from cryptography.x509 import load_der_x509_certificate
        c_off, c_len = next(seq(certs))
        cert = load_der_x509_certificate(certs[c_off:c_off + c_len])
        fp = hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest().upper()
        s_off, _ = next(seq(signatures))
        alg = le(signatures, s_off, 4)
        s_len = le(signatures, s_off + 4, 4)          # внутри элемента: алгоритм (4) + длина (4) + подпись
        sig = signatures[s_off + 8:s_off + 8 + s_len]
        pub = cert.public_key()
        kind, hashname = SIG_ALG.get(alg, ('', ''))
        if not kind:
            return bad(f'v2: неизвестный алгоритм подписи 0x{alg:04x}')
        h = hashes.SHA512() if hashname == 'sha512' else hashes.SHA256()
        if kind == 'pkcs1':
            pub.verify(sig, signed_data, padding.PKCS1v15(), h)
        elif kind == 'pss':
            pub.verify(sig, signed_data, padding.PSS(mgf=padding.MGF1(h), salt_length=padding.PSS.DIGEST_LENGTH), h)
        else:
            pub.verify(sig, signed_data, ec.ECDSA(h))
        good(f'v2: подпись подтверждена открытым ключом сертификата ({ALG_NAME[alg]}) — '
             f'signed_data создал владелец ключа {fp[:8]}…')
        return fp
    except ImportError:
        info('v2: нет пакета cryptography — подпись ключом не проверена (pip install cryptography)')
    except Exception as e:
        bad('v2: подпись ключом сертификата не подтвердилась: '
            + type(e).__name__ + ' ' + str(e).split('\n')[0][:120])


def verify_v1(path):
    """v1 (JAR): дайджест каждой записи из MANIFEST.MF + дайджест самого манифеста из .SF."""
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        if 'META-INF/MANIFEST.MF' not in names:
            return info('v1: нет META-INF/MANIFEST.MF — подпись v1 отсутствует')
        raw = z.read('META-INF/MANIFEST.MF').replace(b'\r\n', b'\n')
        # манифест JAR свёрнут по 72 байта: продолжение строки начинается с пробела
        unfolded = raw.replace(b'\n ', b'')
        sections, cur = [], {}
        for line in unfolded.split(b'\n'):
            if not line.strip():
                if cur:
                    sections.append(cur)
                    cur = {}
                continue
            if b':' in line:
                k, v = line.split(b':', 1)
                cur[k.strip().decode()] = v.strip().decode('utf-8', 'replace')
        if cur:
            sections.append(cur)
        entries = [s for s in sections if s.get('Name')]
        hashes = {'SHA-256-Digest': hashlib.sha256, 'SHA1-Digest': hashlib.sha1}
        checked = wrong = missing = 0
        for s in entries:
            name = s['Name']
            alg = next((a for a in hashes if a in s), None)
            if not alg:
                continue
            if name not in names:
                missing += 1
                continue
            want = base64.b64decode(s[alg])
            try:
                got = hashes[alg](z.read(name)).digest()
            except zipfile.BadZipFile:
                wrong += 1
                bad(f'v1: запись {name} не читается (битый CRC) — файл повреждён')
                continue
            checked += 1
            if got != want:
                wrong += 1
                if wrong <= 3:
                    bad(f'v1: дайджест записи {name} не совпал — файл меняли после подписания')
        if missing:
            bad(f'v1: в манифесте есть {missing} записей, которых нет в APK')
        if wrong == 0 and missing == 0:
            good(f'v1: дайджесты всех {checked} записей из MANIFEST.MF сошлись — содержимое APK не меняли')
        # .SF: дайджест всего манифеста + отметка о схемах подписи
        sf = next((n for n in names if n.startswith('META-INF/') and n.endswith('.SF')), None)
        if sf:
            sfd = z.read(sf).replace(b'\r\n', b'\n')
            for line in sfd.split(b'\n'):
                if line.startswith(b'SHA-256-Digest-Manifest:'):
                    want = base64.b64decode(line.split(b':', 1)[1].strip())
                    got = hashlib.sha256(z.read('META-INF/MANIFEST.MF')).digest()
                    good('v1: дайджест MANIFEST.MF в .SF сошёлся') if got == want else bad('v1: дайджест MANIFEST.MF в .SF не сошёлся')
                elif line.startswith(b'X-Android-APK-Signed:'):
                    info('v1: отметка X-Android-APK-Signed = ' + line.split(b':', 1)[1].strip().decode()
                         + ' (схемы, которыми подписан APK)')


def main():
    if len(sys.argv) < 2:
        print('Запуск: python3 tools/apk-verify.py <файл.apk> [эталон-sha256]')
        return 2
    path = sys.argv[1]
    want_sha = sys.argv[2].lower() if len(sys.argv) > 2 else ''
    print(f'== Проверка {path} (без Java) ==')
    data = open(path, 'rb').read()
    got_sha = hashlib.sha256(data).hexdigest()
    print(f'  инфо  размер: {len(data)} байт, SHA-256: {got_sha}')
    if want_sha:
        good('SHA-256 совпадает с эталоном') if got_sha == want_sha else bad(f'SHA-256 {got_sha} != эталон {want_sha}')

    # 1–2. манифест и сертификат
    try:
        import logging
        logging.disable(logging.CRITICAL)
        from androguard.core.apk import APK
        a = APK(path)
        pkg = a.get_package()
        good(f'пакет {pkg}') if pkg == EXPECTED_PACKAGE else bad(f'пакет {pkg}, ожидался {EXPECTED_PACKAGE}')
        print(f'  инфо  versionName {a.get_androidversion_name()}, versionCode {a.get_androidversion_code()}, '
              f'minSdk {a.get_min_sdk_version()}, targetSdk {a.get_target_sdk_version()}')
        print(f'  инфо  схемы подписи: v1={a.is_signed_v1()} v2={a.is_signed_v2()} v3={a.is_signed_v3()}')
        for c in a.get_certificates():
            fp = hashlib.sha256(c.dump()).hexdigest().upper()
            who = {KEY6_CERT: 'ключ №6 (действующий)', KEY5_CERT: 'ключ №5 (пароль утерян)'}.get(fp, 'неизвестный ключ')
            print(f'  инфо  сертификат SHA-256 {fp[:8]}…{fp[-4:]} — {who}')
            if fp == KEY5_CERT:
                bad('подписано ключом №5 — им больше не подписываем (решение №22)')
    except ImportError:
        info('нет androguard — манифест и сертификат не прочитаны (pip install androguard)')
    except Exception as e:
        bad('androguard не смог прочитать APK: ' + str(e).split('\n')[0][:120])

    # 3–4. ZIP и выравнивание
    with zipfile.ZipFile(path) as z:
        broken = z.testzip()
        good('ZIP цел: CRC всех записей сходится') if broken is None else bad(f'ZIP повреждён на записи {broken}')
        names = z.namelist()
        print(f'  инфо  записей {len(names)}, dex: '
              f'{", ".join(sorted(n for n in names if n.startswith("classes") and n.endswith(".dex")))}')
        for need in ('AndroidManifest.xml', 'resources.arsc'):
            if need not in names:
                bad(f'в APK нет {need}')
        with open(path, 'rb') as f:
            for it in z.infolist():
                if it.filename != 'resources.arsc':
                    continue
                f.seek(it.header_offset)
                loc = f.read(30)
                data_off = it.header_offset + 30 + le(loc, 26, 2) + le(loc, 28, 2)   # + имя + extra локального заголовка
                if it.compress_type != zipfile.ZIP_STORED:
                    bad('resources.arsc сжат — Android 11+ такой APK не ставит (targetSdk 34)')
                elif data_off % 4:
                    bad(f'resources.arsc не выровнен по 4 байта (данные начинаются с {data_off})')
                else:
                    good(f'resources.arsc без сжатия и выровнен по 4 байта (данные с {data_off})')

    # 5. v1 — дайджесты записей
    verify_v1(path)
    # 6. v2 — подпись ключом сертификата
    verify_v2(data)

    print(f'== Итог: {"ошибок нет" if not fails else "ОШИБОК: " + str(len(fails))} ==')
    return 1 if fails else 0


if __name__ == '__main__':
    sys.exit(main())
