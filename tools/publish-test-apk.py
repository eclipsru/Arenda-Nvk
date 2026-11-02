#!/usr/bin/env python3
"""Publish one test APK and its feed into a local site checkout.

The test channel lives beside the static pages.  ``app-test.json`` therefore
contains only the APK filename, never a raw GitHub URL or an agent branch.
This helper is deliberately dependency-free because it runs in GitHub Actions
before the site checkout is pushed to ``main``.

Usage::

    python3 tools/publish-test-apk.py \
        --site /tmp/site \
        --apk out/ProkatInstrumenta-10.18.apk \
        --code 92 --name 10.18 \
        --notes '...' --reinstall false

An existing file is never replaced.  Re-running the same publication is
idempotent when the bytes are identical; a different APK with the same version
fails instead of silently changing a file that may already be downloaded.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import shutil
import sys
import tempfile
from pathlib import Path
from typing import NoReturn
from urllib.parse import urlparse


APK_RE = re.compile(r"^ProkatInstrumenta-(?P<version>[0-9]+(?:\.[0-9]+)+)\.apk$")


def fail(message: str) -> NoReturn:
    print(f"ОШИБКА ПУБЛИКАЦИИ: {message}", file=sys.stderr)
    raise SystemExit(1)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def bool_arg(value: str) -> bool:
    value = value.strip().lower()
    if value == "true":
        return True
    if value == "false":
        return False
    raise argparse.ArgumentTypeError("нужно true или false")


def safe_filename(value: object) -> str:
    """Extract a filename from the feed, while rejecting paths and URLs."""
    text = str(value or "").strip()
    if not text:
        return ""
    # A historical feed may contain a full URL.  It is read only so the old
    # test APK can be retained; the new feed written below is always a name.
    parsed = urlparse(text)
    if parsed.scheme or parsed.netloc:
        text = Path(parsed.path).name
    else:
        text = Path(text).name if "/" in text or "\\" in text else text
    return text if APK_RE.fullmatch(text) else ""


def read_feed(path: Path) -> dict:
    if not path.exists():
        return {}
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return value if isinstance(value, dict) else {}


def read_keep(path: Path) -> tuple[list[str], set[str]]:
    if not path.exists():
        return [], set()
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    names = set()
    for line in lines:
        stripped = line.strip()
        if stripped and not stripped.startswith("#"):
            name = stripped.split()[0]
            if APK_RE.fullmatch(name):
                names.add(name)
    return lines, names


def retain_previous_test(site: Path, old: dict, current: str, version: str) -> None:
    """Keep a previous test APK addressable after app-test.json moves on."""
    old_name = safe_filename(old.get("apk"))
    if not old_name or old_name == current:
        return
    old_file = site / old_name
    if not old_file.is_file():
        # Do not add a dead line to the keep list.  The normal guard will still
        # report an old/missing feed file if one was already referenced.
        return
    keep_path = site / "app-keep-apks.txt"
    lines, names = read_keep(keep_path)
    if old_name in names:
        return
    old_version = str(old.get("versionName") or APK_RE.fullmatch(old_name).group("version"))
    code = str(old.get("versionCode") or "?")
    line = (
        f"{old_name} — предыдущая тестовая сборка {old_version} ({code}), "
        f"сохранена при публикации {version}.\n"
    )
    if lines and not lines[-1].endswith("\n"):
        lines[-1] += "\n"
    lines.append(line)
    keep_path.write_text("".join(lines), encoding="utf-8")
    print(f"сохранён предыдущий APK: {old_name} (app-keep-apks.txt)")


def install_apk(source: Path, destination: Path) -> str:
    if not source.is_file():
        fail(f"нет исходного APK: {source}")
    source_hash = sha256(source)
    if destination.exists():
        if not destination.is_file():
            fail(f"место назначения занято не-файлом: {destination.name}")
        existing_hash = sha256(destination)
        if existing_hash != source_hash:
            fail(
                f"{destination.name} уже есть и отличается — выпущенный или проверенный APK "
                "не перезаписываю"
            )
        print(f"APK уже на месте, байты совпадают: {destination.name}")
        return source_hash

    destination.parent.mkdir(parents=True, exist_ok=True)
    # Write beside the final file and rename atomically, so a failed copy never
    # leaves a truncated APK in the static site checkout.
    fd, temporary = tempfile.mkstemp(prefix=f".{destination.name}.", dir=str(destination.parent))
    os.close(fd)
    temporary_path = Path(temporary)
    try:
        shutil.copyfile(source, temporary_path)
        shutil.copystat(source, temporary_path)
        os.replace(temporary_path, destination)
    finally:
        temporary_path.unlink(missing_ok=True)
    print(f"добавлен APK: {destination.name}")
    return source_hash


def write_feed(path: Path, *, code: int, version: str, filename: str, notes: str, reinstall: bool) -> None:
    value = {
        "versionCode": code,
        "versionName": version,
        "apk": filename,
        "built": dt.datetime.now(dt.timezone.utc).strftime("%d.%m.%Y"),
        "reinstall": reinstall,
        "notes": notes,
    }
    fd, temporary = tempfile.mkstemp(prefix=".app-test.", dir=str(path.parent), text=True)
    temporary_path = Path(temporary)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--site", required=True, type=Path)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--code", required=True, type=int)
    parser.add_argument("--name", required=True)
    parser.add_argument("--notes", default="")
    parser.add_argument("--reinstall", default="false", type=bool_arg)
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+)+", args.name):
        parser.error("--name должен быть номером вида 10.18")
    if args.code < 1:
        parser.error("--code должен быть положительным")
    return args


def main() -> None:
    args = parse_args()
    site = args.site.resolve()
    source = args.apk.resolve()
    if not site.is_dir():
        fail(f"нет каталога сайта: {site}")

    filename = f"ProkatInstrumenta-{args.name}.apk"
    old = read_feed(site / "app-test.json")
    destination = site / filename
    digest = install_apk(source, destination)
    retain_previous_test(site, old, filename, args.name)
    write_feed(
        site / "app-test.json",
        code=args.code,
        version=args.name,
        filename=filename,
        notes=args.notes,
        reinstall=args.reinstall,
    )
    print(
        f"тестовая сборка опубликована: {filename} ({args.code}), "
        f"SHA-256 {digest[:16]}…; app-test.json.apk = только имя файла"
    )


if __name__ == "__main__":
    main()
