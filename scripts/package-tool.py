#!/usr/bin/env python3
"""Package a static web directory as a reproducible, unsigned ToolBox .tbx."""

import argparse
import hashlib
import json
import os
import shutil
from pathlib import Path
import stat
import tempfile
import unicodedata
import zipfile


IGNORED = {".DS_Store", "__MACOSX", ".git", "__pycache__", "node_modules"}
GENERATED = {"integrity.json", "signature.json"}
ARCHIVES = {".zip", ".tbx", ".apk", ".jar", ".aar", ".7z", ".rar", ".tar", ".gz"}


def file_digest(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(64 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def package_tool(source, destination, overwrite=False):
    source = Path(source).absolute()
    destination = Path(destination).absolute()
    if source.is_symlink() or not source.is_dir():
        raise ValueError("Source must be a real directory, not a symbolic link")
    source = source.resolve()
    destination = destination.parent.resolve() / destination.name
    if destination == source or source in destination.parents:
        raise ValueError("Keep the output .tbx outside the source directory")
    if destination.suffix.lower() != ".tbx":
        raise ValueError("Output filename must end in .tbx")
    if destination.exists() and not overwrite:
        raise FileExistsError("Output exists; choose another name or pass --overwrite")
    if destination.is_symlink() or destination.is_dir():
        raise ValueError("Output must not be a directory or symbolic link")

    entries = {}
    normalized_names = set()
    for directory, directories, filenames in os.walk(source, followlinks=False):
        directories[:] = sorted(name for name in directories if name not in IGNORED)
        for name in directories:
            if (Path(directory) / name).is_symlink():
                raise ValueError(f"Symbolic link is not allowed: {name}")
        for name in sorted(filenames):
            if name in IGNORED:
                continue
            path = Path(directory) / name
            relative = path.relative_to(source).as_posix()
            if relative in GENERATED:
                continue
            if path.is_symlink() or not stat.S_ISREG(path.stat().st_mode):
                raise ValueError(f"Not a regular file: {relative}")
            if "\\" in relative or any(ord(char) < 32 for char in relative):
                raise ValueError(f"Invalid resource path: {relative}")
            if path.suffix.lower() in ARCHIVES:
                raise ValueError(f"Remove nested archives: {relative}")
            normalized = unicodedata.normalize("NFC", relative).casefold()
            if normalized in normalized_names:
                raise ValueError(f"Case or Unicode path collision: {relative}")
            normalized_names.add(normalized)
            entries[relative] = path

    if "manifest.json" not in entries:
        raise ValueError("Missing manifest.json at the source root")
    manifest = json.loads(entries["manifest.json"].read_text(encoding="utf-8"))
    if not isinstance(manifest, dict):
        raise ValueError("manifest.json must be an object")
    entry = manifest.get("entry")
    if not isinstance(entry, str) or not entry.endswith(".html") or entry not in entries:
        raise ValueError("manifest.entry must name an existing .html file")
    icon = manifest.get("icon")
    if icon is not None and (not isinstance(icon, str) or icon not in entries):
        raise ValueError("manifest.icon must name an existing resource")

    integrity = {
        "schemaVersion": 1,
        "algorithm": "SHA-256",
        "files": {name: file_digest(entries[name]) for name in sorted(entries)},
    }
    entries["integrity.json"] = (json.dumps(integrity, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=destination.parent, suffix=".tmp", delete=False) as handle:
            temporary = Path(handle.name)
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for name in sorted(entries):
                info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.external_attr = (stat.S_IFREG | 0o644) << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                value = entries[name]
                if isinstance(value, Path):
                    info.file_size = value.stat().st_size
                    with value.open("rb") as source_file, archive.open(info, "w") as target_file:
                        shutil.copyfileobj(source_file, target_file)
                else:
                    archive.writestr(info, value, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
        digest = file_digest(temporary)
        if overwrite:
            os.replace(temporary, destination)
        else:
            os.link(temporary, destination)
        return digest
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", help="Directory containing manifest.json")
    parser.add_argument("output", help="Output .tbx outside the source directory")
    parser.add_argument("--overwrite", action="store_true", help="Replace the specified output file")
    args = parser.parse_args()
    try:
        digest = package_tool(args.source, args.output, args.overwrite)
    except (OSError, ValueError) as error:
        parser.exit(1, f"Cannot package tool: {error}\n")
    print(f"{digest}  {args.output}")


if __name__ == "__main__":
    main()
