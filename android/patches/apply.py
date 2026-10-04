#!/usr/bin/env python3
"""Ива — патч 10.14 → 10.15 в разобранном apktool-проекте.

Запуск: python3 android/patches/apply.py <папка apktool d> <versionCode> <versionName>
Строго: каждое место вставки должно найтись ровно столько раз, сколько ожидается,
иначе — ошибка и сборка останавливается (молча кривой APK не получится).

Вставки в smali/ru/prokatnvsk/app/MainActivity.smali (класс IvaPatch — android/patches/IvaPatch.java):
  1. lambda$calcDelivery$… : перед каждой записью delivTxt → IvaPatch.onPrice(текст) (×2, неразрывный пробел)
  2. updateSummary()        : в начале → IvaPatch.onSummary(this) (галочка «Туда и обратно», «от N ₽/км»)
  3. paintDelToggle(Z)      : тексты кнопки «Доставка: есть / нет» с подсказкой (форма админа)
  4. showAdmHub(I)          : после скрытия вкладки «Добавить» → IvaPatch.onHub(this) (объяснение про долг)
  5. lambda$setupAddTool$…(Button, Exception) : после humanError → IvaPatch.publishError(текст)
Плюс versionCode/versionName в apktool.yml.
"""
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
    return done


def bump(yml: Path, code: str, name: str):
    t = yml.read_text(encoding='utf-8')
    t2, n1 = re.subn(r"(versionCode:\s*)'?\d+'?", rf"\g<1>'{code}'", t)
    t2, n2 = re.subn(r"(versionName:\s*)'?[\w.]+'?", rf"\g<1>'{name}'", t2)
    if n1 != 1 or n2 != 1:
        die('apktool.yml: не найдены versionCode/versionName')
    yml.write_text(t2, encoding='utf-8')


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
    bump(root / 'apktool.yml', code, name)
    print('патч применён: ' + '; '.join(done) + f'; версия {name} ({code})')


if __name__ == '__main__':
    main()
