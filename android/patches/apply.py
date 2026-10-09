#!/usr/bin/env python3
"""Ива — патч 10.14 → новая версия в разобранном apktool-проекте (сейчас 10.17; номер передаётся аргументом).

Запуск: python3 android/patches/apply.py <папка apktool d> <versionCode> <versionName>
Строго: каждое место вставки должно найтись ровно столько раз, сколько ожидается,
иначе — ошибка и сборка останавливается (молча кривой APK не получится).

Вставки в smali/ru/prokatnvsk/app/MainActivity.smali (класс IvaPatch — android/patches/IvaPatch.java):
  1. lambda$calcDelivery$… : перед каждой записью delivTxt → IvaPatch.onPrice(текст) (×2, неразрывный пробел)
  2. updateSummary()        : в начале → IvaPatch.onSummary(this) (галочка «Туда и обратно», «от N ₽/км»)
  3. paintDelToggle(Z)      : тексты кнопки «Доставка: есть / нет» с подсказкой (форма админа)
  4. showAdmHub(I)          : после скрытия вкладки «Добавить» → IvaPatch.onHub(this) (объяснение про долг)
  5. lambda$setupAddTool$…(Button, Exception) : после humanError → IvaPatch.publishError(текст)
  6. humanError(String)                      : общая фраза «Нет связи с сервером…» → IvaPatch.netError(причина)
  7. onCreate(Bundle)                        : в конце → IvaPatch.onCreated(this) («+» → круглый логотип; профиль админа)
  8. saveSession(JSONObject)                 : в конце → IvaPatch.onSession(this) (вход: подгрузить профиль с аватаркой)
  9. lambda$loadAdminProfile$201(String)     : после чтения поля avatar → IvaPatch.onAvatar(this, адрес) (аватарка в «Кабинете»)
 10. logoutLocal()                           : в конце → IvaPatch.onLogout(this) (выход: иконка «Кабинета» снова на месте)
Плюс versionCode/versionName в apktool.yml.
"""
import os
import re
import sys
from pathlib import Path

P = 'Lru/prokatnvsk/app/IvaPatch;'
DELIV = 'Lru/prokatnvsk/app/MainActivity;->delivTxt:Ljava/lang/String;'


def esc(s):
    """Строка в виде, как её пишет baksmali (\\uXXXX для не-ASCII)."""
    return ''.join(c if ord(c) < 128 else '\\u%04x' % ord(c) for c in s)


def die(msg):
    print('ОШИБКА ПАТЧА: ' + msg, file=sys.stderr)
    if os.environ.get('GITHUB_ACTIONS'):
        print('::error title=Патч::' + msg)
    sys.exit(1)


def methods(lines, name_re):
    """Диапазоны [start, end] методов, чьё объявление совпадает с name_re."""
    out, start = [], None
    for i, l in enumerate(lines):
        if l.startswith('.method') and re.search(name_re, l):
            start = i
        elif l.startswith('.end method') and start is not None:
            out.append((start, i)); start = None
    return out


def one_method(lines, name_re):
    m = methods(lines, name_re)
    if len(m) != 1:
        die(f'метод {name_re}: найдено {len(m)}, нужно 1')
    return m[0]


def patch(lines):
    done = []
    # 1. onPrice перед каждой записью delivTxt в лямбде расчёта
    s, e = one_method(lines, r'lambda\$calcDelivery\$\d+\$')
    idx = [i for i in range(s, e) if re.match(r'\s*iput-object (\w+), \w+, ' + re.escape(DELIV), lines[i])]
    if len(idx) != 4:
        die(f'calcDelivery: записей delivTxt {len(idx)}, ожидалось 4 (10.14)')
    for i in reversed(idx):
        r = re.match(r'\s*iput-object (\w+),', lines[i]).group(1)
        lines[i:i] = [f'    invoke-static/range {{{r} .. {r}}}, {P}->onPrice(Ljava/lang/String;)Ljava/lang/String;',
                      f'    move-result-object {r}', '']
    done.append('1 onPrice ×4')

    # 2. onSummary в начале updateSummary
    s, e = one_method(lines, r' updateSummary\(\)V$')
    loc = [i for i in range(s, e) if lines[i].strip().startswith('.locals')]
    if len(loc) != 1:
        die('updateSummary: нет .locals')
    lines[loc[0] + 1:loc[0] + 1] = ['', f'    invoke-static {{p0}}, {P}->onSummary(Landroid/app/Activity;)V']
    done.append('2 onSummary')

    # 3. тексты кнопки доставки в форме админа
    s, e = one_method(lines, r' paintDelToggle\(Z\)V$')
    for old, new in (('Доставка: есть', 'Доставка: есть ✓ — цена ниже, ₽ за 1 км (в одну сторону)'),
                     ('Доставка: нет', 'Доставка: нет — нажмите, чтобы включить и указать ₽ за 1 км')):
        hits = [i for i in range(s, e) if re.search(r'const-string \w+, "(%s|%s)"$' % (re.escape(esc(old)), re.escape(old)), lines[i])]
        if len(hits) != 1:
            die(f'paintDelToggle: «{old}» найдено {len(hits)}')
        i = hits[0]
        reg = re.search(r'const-string (\w+),', lines[i]).group(1)
        lines[i] = f'    const-string {reg}, "{esc(new)}"'
    done.append('3 тексты доставки')

    # 4. onHub после setVisibility вкладки «Добавить» (первая setVisibility после чтения meLimited)
    s, e = one_method(lines, r' showAdmHub\(I\)V$')
    lim = [i for i in range(s, e) if '->meLimited:Z' in lines[i]]
    if len(lim) < 2:
        die('showAdmHub: нет чтения meLimited')
    vis = [i for i in range(lim[1], e) if 'Landroid/view/View;->setVisibility(I)V' in lines[i]]
    if not vis:
        die('showAdmHub: нет setVisibility')
    lines[vis[0] + 1:vis[0] + 1] = ['', f'    invoke-static {{p0}}, {P}->onHub(Landroid/app/Activity;)V']
    done.append('4 onHub')

    # 5. publishError после humanError в ошибке публикации
    s, e = one_method(lines, r'lambda\$setupAddTool\$\d+\$.*\(Landroid/widget/Button;Ljava/lang/Exception;\)V$')
    hum = [i for i in range(s, e) if '->humanError(Ljava/lang/String;)Ljava/lang/String;' in lines[i]]
    if len(hum) != 1:
        die(f'ошибка публикации: humanError найдено {len(hum)}')
    mr = [i for i in range(hum[0] + 1, min(hum[0] + 4, e)) if lines[i].strip().startswith('move-result-object')]
    if not mr:
        die('ошибка публикации: нет move-result-object')
    r = lines[mr[0]].split()[-1]
    lines[mr[0] + 1:mr[0] + 1] = ['', f'    invoke-static/range {{{r} .. {r}}}, {P}->publishError(Ljava/lang/String;)Ljava/lang/String;',
                                  f'    move-result-object {r}']
    done.append('5 publishError')

    # 6. причина сбоя связи словами: в humanError общая фраза «Нет связи с сервером…» заменяется
    #    на вызов IvaPatch.netError(причина) — он вернёт ту же фразу плюс техническую причину.
    #    Зачем: 07.10.2026 владелец на двух телефонах видел эту фразу при рабочем интернете.
    s, e = one_method(lines, r' humanError\(Ljava/lang/String;\)Ljava/lang/String;$')
    net = 'Нет связи с сервером. Проверьте интернет и попробуйте ещё раз.'
    hits = [i for i in range(s, e)
            if re.search(r'const-string(?:/jumbo)? (\w+), "(%s|%s)"$' % (re.escape(esc(net)), re.escape(net)), lines[i])]
    if len(hits) != 1:
        die(f'humanError: фраза «Нет связи с сервером…» найдена {len(hits)} раз, нужно ровно 1')
    reg = re.search(r'const-string(?:/jumbo)? (\w+),', lines[hits[0]]).group(1)
    lines[hits[0]:hits[0] + 1] = [
        f'    invoke-static {{{reg}}}, {P}->netError(Ljava/lang/String;)Ljava/lang/String;',
        f'    move-result-object {reg}']
    done.append('6 netError')

    # 7. конец onCreate → IvaPatch.onCreated(this): «+» в нижнем меню становится круглым логотипом
    #    и подгружается профиль админа (аватарка). onCreate заканчивается одним return-void — после bindViews/setupNav.
    s, e = one_method(lines, r' onCreate\(Landroid/os/Bundle;\)V$')
    before_return(lines, s, e, f'invoke-static {{p0}}, {P}->onCreated(Landroid/app/Activity;)V', 'onCreate')
    done.append('7 onCreated')

    # 8. конец saveSession (вход и восстановление сессии) → IvaPatch.onSession(this)
    s, e = one_method(lines, r' saveSession\(Lorg/json/JSONObject;\)V$')
    before_return(lines, s, e, f'invoke-static {{p0}}, {P}->onSession(Landroid/app/Activity;)V', 'saveSession')
    done.append('8 onSession')

    # 9. лямбда загрузки профиля админа: сразу после чтения поля avatar (optString → move-result-object)
    #    → IvaPatch.onAvatar(this, адрес). Пустой адрес в onAvatar означает «аватарки нет» (иконка остаётся).
    s, e = one_method(lines, r'lambda\$loadAdminProfile\$201\$')
    av = [i for i in range(s, e) if re.match(r'\s*const-string \w+, "avatar"$', lines[i])]
    if len(av) != 1:
        die(f'профиль админа: поле "avatar" найдено {len(av)} раз, нужно 1')
    opt = [k for k in range(av[0], min(av[0] + 4, e)) if 'Lorg/json/JSONObject;->optString(' in lines[k]]
    if not opt:
        die('профиль админа: нет optString после "avatar"')
    mr = [k for k in range(opt[0] + 1, min(opt[0] + 3, e)) if lines[k].strip().startswith('move-result-object')]
    if not mr:
        die('профиль админа: нет move-result-object после optString("avatar")')
    reg = lines[mr[0]].split()[-1]
    lines[mr[0] + 1:mr[0] + 1] = [f'    invoke-static {{p0, {reg}}}, {P}->onAvatar(Landroid/app/Activity;Ljava/lang/String;)V']
    done.append('9 onAvatar')

    # 10. конец logoutLocal (выход) → IvaPatch.onLogout(this): аватарка убирается, иконка возвращается
    s, e = one_method(lines, r' logoutLocal\(\)V$')
    before_return(lines, s, e, f'invoke-static {{p0}}, {P}->onLogout(Landroid/app/Activity;)V', 'logoutLocal')
    done.append('10 onLogout')
    return done


def before_return(lines, s, e, call, what):
    """Вставка перед единственным return-void метода (метод с двумя выходами — ошибка, а не догадка)."""
    rets = [i for i in range(s, e) if lines[i].strip() == 'return-void']
    if len(rets) != 1:
        die(f'{what}: return-void найдено {len(rets)}, нужно 1')
    lines[rets[0]:rets[0]] = [f'    {call}']


def bump(yml: Path, code: str, name: str):
    t = yml.read_text(encoding='utf-8')
    if 'versionCode' not in t:
        # разбор с -r (без ресурсов): версия хранится в двоичном манифесте — её меняет axml_version.py
        return 'версия — в манифесте (axml_version.py)'
    t2, n1 = re.subn(r"(versionCode:\s*)'?\d+'?", rf"\g<1>'{code}'", t)
    t2, n2 = re.subn(r"(versionName:\s*)'?[\w.]+'?", rf"\g<1>'{name}'", t2)
    if n1 != 1 or n2 != 1:
        # apktool 2.10 с -r пишет versionCode: null — версия в двоичном манифесте (axml_version.py),
        # итог всё равно сверяет aapt2 в build-apk.yml
        return 'версия — в манифесте (axml_version.py)'
    yml.write_text(t2, encoding='utf-8')
    return f'версия {name} ({code}) в apktool.yml'


def main():
    if len(sys.argv) != 4:
        die('нужно: apply.py <папка> <versionCode> <versionName>')
    root, code, name = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
    f = root / 'smali/ru/prokatnvsk/app/MainActivity.smali'
    if not f.exists():
        die(f'нет {f}')
    text = f.read_text(encoding='utf-8')
    if P in text:
        die('патч уже применён')
    lines = text.split('\n')
    done = patch(lines)
    f.write_text('\n'.join(lines), encoding='utf-8')
    done.append(bump(root / 'apktool.yml', code, name))
    print('патч применён: ' + '; '.join(done))
    if os.environ.get('GITHUB_ACTIONS'):
        print('::notice title=Патч::' + '; '.join(done))


if __name__ == '__main__':
    main()
