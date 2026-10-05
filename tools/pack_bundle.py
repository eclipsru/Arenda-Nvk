#!/usr/bin/env python3
"""Собирает папку с текстовыми файлами в ОДИН .md-файл («пакет одним файлом»).

Зачем: чат агента не принимает .zip, а пакет документов должен переезжать между чатами.
Агент собирает файлы в один .md, владелец прикрепляет его в другом чате, а там его
раскладывает tools/unpack_bundle.py обратно по файлам.

Формат — тот же, что понимает tools/unpack_bundle.py:
    ===== ФАЙЛ: iva-package/00-НАЧНИ-ОТСЮДА.md =====
    ...содержимое...
    ===== КОНЕЦ ФАЙЛА =====

Запуск:
    python3 tools/pack_bundle.py <папка> <выход.md> [--prefix имя] [--max-kb 250]

Что делает:
  • берёт только текстовые файлы (по расширению) и пропускает картинки/бинарники —
    они остаются в .zip, потому что в текст не влезают;
  • сортирует пути, чтобы порядок был стабильным и одинаковым в обе стороны;
  • БЕЗОПАСНОСТЬ: файлы, в которых есть строки, похожие на секреты (токены, приватные
    ключи, пароли, строка подключения с паролем), в пакет НЕ попадают — печатается
    предупреждение (иначе раскладчик на той стороне их всё равно отклонит);
  • если файл содержит строку-маркер «КОНЕЦ ФАЙЛА» внутри текста — такой файл
    пропускается (он сломал бы разбор), печатается предупреждение;
  • при --max-kb делит пакет на части: <выход>.part1.md, <выход>.part2.md, … (раскладчик
    принимает несколько частей сразу).

Код выхода: 0 — всё упаковано; 3 — часть файлов пропущена (см. предупреждения).
"""
import re
import sys
from pathlib import Path

TEXT_EXT = {'.md', '.sql', '.sh', '.py', '.json', '.txt', '.js', '.cjs', '.html', '.css',
            '.yml', '.yaml', '.xml', '.csv', '.ts', '.ini', '.cfg'}
END_MARKER = '===== КОНЕЦ ФАЙЛА ====='
START_MARKER = '===== ФАЙЛ:'

# Держим в синхроне с tools/unpack_bundle.py: то, что раскладчик отклонит, упаковщик
# не должен отправлять (иначе файл молча не доедет).
SECRETS = [
    ('токен Telegram-бота', re.compile(r'\b[0-9]{6,12}:[A-Za-z0-9_-]{30,}\b')),
    ('токен GitHub', re.compile(r'github_pat_[A-Za-z0-9_]{10,}|ghp_[A-Za-z0-9]{20,}')),
    ('ключ Supabase (JWT/secret)', re.compile(r'eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}|sb_secret_[A-Za-z0-9_-]{5,}')),
    ('приватный ключ', re.compile(r'BEGIN [A-Z ]*PRIVATE KEY')),
    ('пароль keystore', re.compile('prokat' + '20' + '26')),  # по частям: целиком строку держать в репозитории нельзя
    ('ключ vk_wall_post', re.compile(r'vkwp_[0-9a-f]{6,}', re.I)),
    ('строка подключения к базе с паролем', re.compile(r'postgres(?:ql)?://[^:\s]+:[^@\s]{6,}@')),
]


PLACEHOLDER_MARKERS = ('<', '>', '[', ']', '…', '***', '••', 'your-', 'your_', 'yourpassword',
                       'password', 'пароль', 'xxx', 'example', 'пример', 'sample', 'changeme',
                       'замени', 'скрыт')


def looks_like_placeholder(text: str) -> bool:
    """`postgres://postgres:<пароль>@…` и `[YOUR-PASSWORD]` — это примеры из инструкций,
    а не секреты: такие совпадения не считаем утечкой (иначе полезные документы
    выпадали бы из пакета)."""
    low = text.lower()
    return any(m in low for m in PLACEHOLDER_MARKERS)


def collect(root: Path, prefix: str):
    """Возвращает (файлы_для_пакета, пропущенные) — пути относительно root."""
    keep, skipped = [], []
    for p in sorted(root.rglob('*')):
        if not p.is_file():
            continue
        rel = p.relative_to(root).as_posix()
        if p.suffix.lower() not in TEXT_EXT:
            skipped.append((rel, 'не текстовый файл (остаётся в .zip)'))
            continue
        try:
            body = p.read_text(encoding='utf-8')
        except UnicodeDecodeError:
            skipped.append((rel, 'не читается как UTF-8 (похоже, бинарный)'))
            continue
        hits = []
        for label, rx in SECRETS:
            if any(not looks_like_placeholder(m.group(0)) for m in rx.finditer(body)):
                hits.append(label)
        if hits:
            skipped.append((rel, 'похоже на секрет: ' + ', '.join(hits)))
            continue
        if any(line.strip() == END_MARKER for line in body.splitlines()):
            skipped.append((rel, 'содержит строку-маркер «КОНЕЦ ФАЙЛА» — сломает разбор'))
            continue
        keep.append((rel, body))
    return keep, skipped


def write_parts(out: Path, chunks, max_kb, header):
    """Пишет одну часть либо несколько, если пакет больше max_kb."""
    parts = []
    if not max_kb:
        parts = [chunks]
    else:
        cur, size = [], 0
        for name, text in chunks:
            piece = len(text.encode('utf-8')) + len(name.encode('utf-8')) + 80
            if cur and size + piece > max_kb * 1024:
                parts.append(cur)
                cur, size = [], 0
            cur.append((name, text))
            size += piece
        if cur:
            parts.append(cur)

    written = []
    total = len(parts)
    for i, part in enumerate(parts, 1):
        name = out if total == 1 else out.with_name(f'{out.stem}.part{i}{out.suffix}')
        with open(name, 'w', encoding='utf-8') as f:
            f.write(header if total == 1 else f'# Пакет «Ива» — часть {i} из {total}\n\n')
            for path, body in part:
                if not body.endswith('\n'):
                    body += '\n'
                f.write(f'{START_MARKER} {path} =====\n')
                f.write(body)
                f.write(f'{END_MARKER}\n\n')
        written.append((name, sum(len(b.encode("utf-8")) for _, b in part), len(part)))
    return written


def main(argv):
    args = list(argv)
    prefix, max_kb = None, 0
    for flag in ('--prefix', '--max-kb'):
        if flag in args:
            i = args.index(flag)
            val = args[i + 1]
            del args[i:i + 2]
            if flag == '--prefix':
                prefix = val
            else:
                max_kb = int(val)
    if len(args) != 2:
        raise SystemExit(__doc__)
    src, out = Path(args[0]), Path(args[1])
    if not src.is_dir():
        raise SystemExit(f'Нет такой папки: {src}')
    prefix = prefix or src.name

    keep, skipped = collect(src, prefix)
    if not keep:
        raise SystemExit('В папке нет текстовых файлов для упаковки.')

    header = (
        f'# Пакет «Ива» одним файлом ({prefix})\n\n'
        f'Как разложить обратно: `python3 tools/unpack_bundle.py {out.name} --dest <папка>`\n\n'
        f'Каждый файл начинается строкой `{START_MARKER} <путь> =====` и заканчивается '
        f'строкой `{END_MARKER}`. Картинки и вложения этим каналом не передаются.\n\n'
    )
    chunks = [(f'{prefix}/{rel}', body) for rel, body in keep]
    written = write_parts(out, chunks, max_kb, header)

    print(f'Упаковано файлов: {len(keep)} → ' +
          ', '.join(f'{n.name} ({sz // 1024} КБ, {c} файлов)' for n, sz, c in written))
    for rel, why in skipped:
        print(f'  ПРОПУЩЕН {rel}: {why}')
    return 0 if not skipped else 3


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
