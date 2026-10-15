#!/usr/bin/env python3
"""Package a static web directory as a reproducible, unsigned ToolBox .tbx."""

import argparse
from decimal import Decimal
import hashlib
import json
import os
import re
import shutil
from pathlib import Path
import stat
import tempfile
import unicodedata
import zipfile


IGNORED = {".DS_Store", "__MACOSX", ".git", "__pycache__", "node_modules"}
GENERATED = {"integrity.json", "signature.json"}
# The rules below mirror the host (tool-package/src/main/kotlin/io/toolbox/tool/packagekit/), so a
# package this script builds is not rejected at install: PackagePathPolicy.forbiddenCode,
# ZipArchiveReader.sniffPayload, PackagePathPolicy.validate and ManifestValidator.
ARCHIVES = {".zip", ".tbx", ".jar", ".apk", ".aar", ".7z", ".rar", ".tar", ".gz", ".bz2", ".xz"}
CODE = {".dex", ".so", ".class", ".odex", ".vdex", ".oat"}
WASM_MAGIC = b"\x00asm"
ARCHIVE_MAGIC = (b"PK\x03\x04", b"PK\x05\x06", b"\x1f\x8b", b"7z\xbc\xaf\x27\x1c", b"Rar!\x1a\x07", b"BZh",
                 b"\xfd7zXZ\x00")
CODE_MAGIC = (b"\x7fELF", b"dex\n", b"\xca\xfe\xba\xbe")
SNIFF_BYTES = 512
DRIVE_PATH = re.compile(r"^[A-Za-z]:")
ENCODED_SEPARATOR_OR_DOT = re.compile(r"%(?:2e|2f|5c)", re.IGNORECASE)

ID_PATTERN = re.compile(r"[a-z][a-z0-9]*(\.[a-z][a-z0-9-]*){2,}")
VERSION_PATTERN = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?")
PATH_CHARACTERS = re.compile(r"[A-Za-z0-9._/-]+")
PERMISSIONS = {
    "storage", "storage.secure", "clipboard.write", "clipboard.read", "share", "browser",
    "files.open", "files.save", "network", "device.basic", "haptics", "notifications",
    "shortcuts", "camera", "location", "background.tasks", "background.runtime",
    "location.background", "alarms",
}
V03_CAPABILITIES = {"background.runtime", "location.background", "alarms"}
TOP_LEVEL_FIELDS = {
    "schemaVersion", "id", "name", "shortName", "description", "version", "versionCode",
    "entry", "icon", "apiVersion", "minHostVersion", "categories", "permissions", "network",
    "securityProfile", "ui",
}
INT_MAX = 2**31 - 1
SAFE_INTEGER_MAX = 2**53 - 1


def validate_path(relative):
    """PackagePathPolicy.validate for a file path; returns the NFC form the host stores."""
    if not relative or "\x00" in relative or "\\" in relative or any(ord(char) < 32 for char in relative):
        raise ValueError(f"Invalid resource path: {relative!r}")
    if relative.startswith("/") or DRIVE_PATH.search(relative) or ENCODED_SEPARATOR_OR_DOT.search(relative):
        raise ValueError(f"Absolute or encoded traversal path is forbidden: {relative}")
    if any(segment in ("", ".", "..") for segment in relative.split("/")):
        raise ValueError(f"Empty, dot or parent path segment is forbidden: {relative}")
    normalized = unicodedata.normalize("NFC", relative)
    if any(len(segment.encode("utf-8")) > 255 for segment in normalized.split("/")):
        raise ValueError(f"Path component exceeds 255 bytes: {relative}")
    return normalized


def check_payload(relative, path):
    """Name and content rules the host applies to every file, whatever its extension."""
    # PackagePathPolicy.forbiddenCode: everything after the last '.' of the whole path, so dot-named
    # files such as ".so" or "sub/.zip" count too (Path.suffix would see no extension there).
    lower = relative.lower()
    suffix = "." + lower.rsplit(".", 1)[1] if "." in lower else ""
    if suffix in ARCHIVES:
        raise ValueError(f"Remove nested archives: {relative}")
    with path.open("rb") as stream:
        prefix = stream.read(SNIFF_BYTES)
    if prefix.startswith(WASM_MAGIC):
        return
    if any(prefix.startswith(magic) for magic in ARCHIVE_MAGIC) or prefix[257:262] == b"ustar":
        raise ValueError(f"Nested archive content is forbidden: {relative}")
    if any(prefix.startswith(magic) for magic in CODE_MAGIC) or suffix in CODE:
        raise ValueError(f"Native or dynamic code is forbidden: {relative}")


def _reject_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"manifest.json repeats the key {key!r}")
        result[key] = value
    return result


def _reject_constant(name):
    raise ValueError(f"manifest.json contains the non-JSON number {name}")


def _string(values, name, minimum=1, maximum=None, pattern=None, required=True):
    if name not in values:
        if required:
            raise ValueError(f"manifest.{name} is required")
        return None
    value = values[name]
    if not isinstance(value, str):
        raise ValueError(f"manifest.{name} must be a string")
    units = len(value.encode("utf-16-le", "surrogatepass")) // 2  # Kotlin String.length
    if units < minimum or (maximum is not None and units > maximum):
        raise ValueError(f"manifest.{name} has an invalid length")
    if pattern is not None and not pattern.fullmatch(value):
        raise ValueError(f"manifest.{name} does not match its schema")
    return value


def _integer(value, label, minimum, maximum):
    if isinstance(value, bool) or not isinstance(value, (int, Decimal)):
        raise ValueError(f"{label} must be an integer")
    if isinstance(value, Decimal) and value != value.to_integral_value():
        raise ValueError(f"{label} must be an integer")
    if not minimum <= value <= maximum:
        raise ValueError(f"{label} must be between {minimum} and {maximum}")
    return int(value)


def _boolean(values, name, label):
    if name in values and not isinstance(values[name], bool):
        raise ValueError(f"{label} must be a boolean")


def _only(values, label, allowed):
    if not isinstance(values, dict):
        raise ValueError(f"{label} must be an object")
    extra = set(values) - allowed
    if extra:
        raise ValueError(f"{label} has unsupported fields: {sorted(extra)}")


def _manifest_path(value, label, html_only):
    if not isinstance(value, str):
        raise ValueError(f"{label} must be a string")
    try:
        normalized = validate_path(value)
    except ValueError as error:
        raise ValueError(f"{label}: {error}") from None
    if value.endswith("/") or not PATH_CHARACTERS.fullmatch(normalized) or (html_only and not normalized.endswith(".html")):
        raise ValueError(f"{label} must be a relative {'HTML ' if html_only else ''}file path of ASCII letters, digits, '.', '_', '-' and '/'")
    return normalized


def _version_at_least(value, minimum):
    parts = re.split(r"[-+]", value, maxsplit=1)[0].split(".")
    numbers = []
    for part in parts:
        if int(part) > INT_MAX:
            raise ValueError("manifest.minHostVersion contains an out-of-range number")
        numbers.append(int(part))
    return numbers >= list(minimum)


def validate_manifest(raw):
    """ManifestValidator.parse: the manifest rules the host checks before installing."""
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        raise ValueError("manifest.json is not valid UTF-8") from None
    try:
        manifest = json.loads(text, object_pairs_hook=_reject_duplicates, parse_float=Decimal,
                              parse_constant=_reject_constant)
    except json.JSONDecodeError as error:
        raise ValueError(f"manifest.json is not valid JSON: {error}") from None
    _only(manifest, "manifest.json", TOP_LEVEL_FIELDS)
    if "schemaVersion" not in manifest:
        raise ValueError("manifest.schemaVersion is required")
    _integer(manifest["schemaVersion"], "manifest.schemaVersion", 1, 1)
    _string(manifest, "id", 5, 255, ID_PATTERN)
    _string(manifest, "name")
    _string(manifest, "shortName", required=False)
    _string(manifest, "description", minimum=0, required=False)
    _string(manifest, "version", pattern=VERSION_PATTERN)
    if "versionCode" not in manifest:
        raise ValueError("manifest.versionCode is required")
    _integer(manifest["versionCode"], "manifest.versionCode", 1, INT_MAX)
    if "entry" not in manifest:
        raise ValueError("manifest.entry is required")
    entry = _manifest_path(manifest["entry"], "manifest.entry", html_only=True)
    icon = _manifest_path(manifest["icon"], "manifest.icon", html_only=False) if "icon" in manifest else None
    if _string(manifest, "apiVersion", 1, 16) != "1.0":
        raise ValueError("manifest.apiVersion must be 1.0")
    min_host_version = _string(manifest, "minHostVersion", pattern=VERSION_PATTERN)
    if "permissions" not in manifest or not isinstance(manifest["permissions"], list):
        raise ValueError("manifest.permissions must be an array")
    seen = set()
    for index, permission in enumerate(manifest["permissions"]):
        label = f"manifest.permissions[{index}]"
        _only(permission, label, {"name", "reason", "required"})
        name = permission.get("name")
        if not isinstance(name, str):
            raise ValueError(f"{label}.name must be a string")
        if name not in PERMISSIONS:
            raise ValueError(f"Unsupported permission: {name}")
        if name in seen:
            raise ValueError(f"Duplicate permission: {name}")
        seen.add(name)
        _string(permission, "reason")
        _boolean(permission, "required", f"{label}.required")
    if seen & V03_CAPABILITIES and not _version_at_least(min_host_version, (0, 3, 0)):
        raise ValueError("background.runtime, location.background and alarms require minHostVersion 0.3.0")
    if manifest.get("securityProfile") not in ("strict", "compat"):
        raise ValueError("manifest.securityProfile must be strict or compat")
    if "network" in manifest:
        network = manifest["network"]
        _only(network, "manifest.network", {"maxResponseBytes", "timeoutMs"})
        if "timeoutMs" in network:
            _integer(network["timeoutMs"], "manifest.network.timeoutMs", 0, INT_MAX)
        if "maxResponseBytes" in network:
            _integer(network["maxResponseBytes"], "manifest.network.maxResponseBytes", 1, SAFE_INTEGER_MAX)
    if "categories" in manifest:
        categories = manifest["categories"]
        if not isinstance(categories, list) or any(not isinstance(item, str) or not item for item in categories):
            raise ValueError("manifest.categories must be an array of nonempty strings")
        if len(set(categories)) != len(categories):
            raise ValueError("manifest.categories must contain unique values")
    if "ui" in manifest:
        ui = manifest["ui"]
        _only(ui, "manifest.ui", {"orientation", "allowFullscreen", "statusBarStyle", "edgeToEdge", "showHostToolbar"})
        if "orientation" in ui and ui["orientation"] not in ("unspecified", "portrait", "landscape"):
            raise ValueError("Invalid manifest.ui.orientation")
        if "statusBarStyle" in ui and ui["statusBarStyle"] not in ("auto", "light", "dark"):
            raise ValueError("Invalid manifest.ui.statusBarStyle")
        _boolean(ui, "allowFullscreen", "manifest.ui.allowFullscreen")
        _boolean(ui, "edgeToEdge", "manifest.ui.edgeToEdge")
        _boolean(ui, "showHostToolbar", "manifest.ui.showHostToolbar")
    return entry, icon


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
            normalized = validate_path(relative).casefold()
            check_payload(relative, path)
            if normalized in normalized_names:
                raise ValueError(f"Case or Unicode path collision: {relative}")
            normalized_names.add(normalized)
            entries[relative] = path
    # ZipStructureReader rejects a file whose path is also a directory prefix, case-insensitively.
    for name in normalized_names:
        segments = name.split("/")
        for end in range(1, len(segments)):
            if "/".join(segments[:end]) in normalized_names:
                raise ValueError(f"A file is also used as a directory: {name}")

    if "manifest.json" not in entries:
        raise ValueError("Missing manifest.json at the source root")
    entry, icon = validate_manifest(entries["manifest.json"].read_bytes())
    if entry not in entries:
        raise ValueError("manifest.entry must name an existing .html file")
    if icon is not None and icon not in entries:
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
