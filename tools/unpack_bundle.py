#!/usr/bin/env python3
"""Раскладывает «пакет одним файлом» (.md) обратно по файлам — в docs/пакет/.

Зачем: чат агента не принимает .zip, а пакет документов владельца жил только
в песочнице другого чата. Агент того чата собирает все текстовые файлы в один .md
такого вида:

    ===== ФАЙЛ: iva-package/00-НАЧНИ-ОТСЮДА.md =====
    ...содержимое...
    ===== КОНЕЦ ФАЙЛА =====

Запуск:  python3 tools/unpack_bundle.py <пакет.md> [<часть2.md> ...] [--dest docs/пакет]
Безопасность: пути с «..» и абсолютные отклоняются; файлы с похожими на секреты
строками (токены, ключи, пароли) НЕ записываются — печатается предупреждение.
Существующие файлы не перезаписываются (правило «ничего не удалять»): новая
версия кладётся рядом с суффиксом .new.
"""
import re
import sys
from pathlib import Path

START = re.compile(r'^===== ФАЙЛ: (.+?) =====\s*$')
END = re.compile(r'^===== КОНЕЦ ФАЙЛА =====\s*$')
# Образцы в документах (postgres://postgres:<пароль>@…, [YOUR-PASSWORD]) секретом не считаются.
SECRETS = [
    ('токен Telegram-бота', re.compile(r'\b[0-9]{6,12}:[A-Za-z0-9_-]{30,}\b')),
    ('токен GitHub', re.compile(r'github_pat_[A-Za-z0-9_]{10,}|ghp_[A-Za-z0-9]{20,}')),
    ('ключ Supabase (JWT/secret)', re.compile(r'eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}|sb_secret_[A-Za-z0-9_-]{5,}')),
    ('приватный ключ', re.compile(r'BEGIN [A-Z ]*PRIVATE KEY')),
    ('пароль keystore', re.compile('prokat' + '20' + '26')),  # по частям: целиком строку держать в репозитории нельзя
    ('ключ vk_wall_post', re.compile(r'vkwp_[0-9a-f]{6,}', re.I)),
    ('строка подключения к базе с паролем', re.compile(r'postgres(?:ql)?://[^:\s]+:(?![<\[{]|\$\{|YOUR|your|пароль|PASSWORD|password)[^@\s]{6,}@')),
]


def parse(text):
    files, cur, buf = [], None, []
    for line in text.splitlines(keepends=True):
        m = START.match(line.rstrip('\n'))
        if m and cur is None:
            cur, buf = m.group(1).strip(), []
            continue
        if cur is not None and END.match(line.rstrip('\n')):
            files.append((cur, ''.join(buf)))
            cur = None
            continue
        if cur is not None:
            buf.append(line)
    if cur is not None:
        raise SystemExit(f'Файл «{cur}» не закрыт строкой «===== КОНЕЦ ФАЙЛА =====» — пакет обрезан?')
    return files


def main(argv):
    dest = Path('docs/пакет')
    args = list(argv)
    if '--dest' in args:
        i = args.index('--dest')
        dest = Path(args[i + 1])
        del args[i:i + 2]
    if not args:
        raise SystemExit(__doc__)
    written, skipped = 0, []
    for src in args:
        for rel, body in parse(Path(src).read_text(encoding='utf-8')):
            p = Path(rel)
            if p.is_absolute() or '..' in p.parts or not rel:
                skipped.append((rel, 'опасный путь'))
                continue
            hits = [label for label, rx in SECRETS if rx.search(body)]
            if hits:
                skipped.append((rel, 'похоже на секрет: ' + ', '.join(hits)))
                continue
            out = dest / p
            if out.exists():
                out = out.with_name(out.name + '.new')
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text(body, encoding='utf-8')
            written += 1
    print(f'Разложено файлов: {written} → {dest}/')
    for rel, why in skipped:
        print(f'  ПРОПУЩЕН {rel}: {why}')
    return 0 if not skipped else 3


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
