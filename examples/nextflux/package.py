#!/usr/bin/env python3
"""Validate and package the built NextFlux ToolBox client without overwriting artifacts."""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
import stat
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
UPSTREAM_URL = "https://github.com/electh/nextflux"
UPSTREAM_COMMIT = "a9f97de654d00f62cbbad877d583fae7cd76ec59"
MAX_FILES = 512
MAX_ZIP = 20 * 1024 * 1024
MAX_EXPANDED = 80 * 1024 * 1024
MAX_SINGLE = 20 * 1024 * 1024
MAX_RATIO = 100


class UnsupportedSchema(RuntimeError):
    pass


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def json_text(value):
    return json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def schema_check(value, schema, root_schema, location="manifest"):
    """Validate the keywords used by this repository's JSON Schema, without deps.

    Unknown validation keywords fail closed, so a future schema extension cannot
    silently bypass this small manifest-specific validator.
    """
    supported = {
        "$schema", "$id", "$defs", "$ref", "title", "description", "default",
        "type", "const", "enum", "required", "properties", "additionalProperties",
        "minLength", "maxLength", "pattern", "minimum", "maximum", "items",
        "minItems", "maxItems", "uniqueItems", "contains", "minContains", "maxContains",
        "allOf", "if", "then", "else",
    }
    unknown = set(schema) - supported
    if unknown:
        raise UnsupportedSchema(f"Unsupported schema keywords at {location}: {sorted(unknown)}")
    if "$ref" in schema:
        reference = schema["$ref"]
        if not reference.startswith("#/"):
            raise ValueError("Only repository-local schema references are supported")
        resolved = root_schema
        for part in reference[2:].split("/"):
            resolved = resolved[part.replace("~1", "/").replace("~0", "~")]
        schema_check(value, resolved, root_schema, location)
    expected = schema.get("type")
    types = {
        "object": isinstance(value, dict),
        "array": isinstance(value, list),
        "string": isinstance(value, str),
        "integer": isinstance(value, int) and not isinstance(value, bool),
        "number": isinstance(value, (int, float)) and not isinstance(value, bool),
        "boolean": isinstance(value, bool),
        "null": value is None,
    }
    if expected and not types.get(expected, False):
        raise ValueError(f"{location} must be {expected}")
    canonical = lambda item: json.dumps(item, sort_keys=True, ensure_ascii=False)
    if "const" in schema and canonical(value) != canonical(schema["const"]):
        raise ValueError(f"{location} does not match the required constant")
    if "enum" in schema and canonical(value) not in map(canonical, schema["enum"]):
        raise ValueError(f"{location} is not an allowed value")
    if isinstance(value, dict):
        missing = set(schema.get("required", [])) - set(value)
        if missing:
            raise ValueError(f"{location} is missing {sorted(missing)}")
        properties = schema.get("properties", {})
        if schema.get("additionalProperties") is False and set(value) - set(properties):
            raise ValueError(f"{location} has undeclared properties")
        for key, child in properties.items():
            if key in value:
                schema_check(value[key], child, root_schema, f"{location}.{key}")
    if isinstance(value, str):
        if len(value) < schema.get("minLength", 0) or len(value) > schema.get("maxLength", float("inf")):
            raise ValueError(f"{location} has invalid string length")
        if "pattern" in schema and re.search(schema["pattern"], value) is None:
            raise ValueError(f"{location} does not match the schema pattern")
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        if value < schema.get("minimum", -float("inf")) or value > schema.get("maximum", float("inf")):
            raise ValueError(f"{location} exceeds its numeric bounds")
    if isinstance(value, list):
        if len(value) < schema.get("minItems", 0) or len(value) > schema.get("maxItems", float("inf")):
            raise ValueError(f"{location} has invalid item count")
        if schema.get("uniqueItems") and len({canonical(item) for item in value}) != len(value):
            raise ValueError(f"{location} contains duplicate values")
        if "items" in schema:
            for index, item in enumerate(value):
                schema_check(item, schema["items"], root_schema, f"{location}[{index}]")
        if "contains" in schema:
            matches = 0
            for item in value:
                try:
                    schema_check(item, schema["contains"], root_schema, location)
                    matches += 1
                except ValueError:
                    pass
            if matches < schema.get("minContains", 1) or matches > schema.get("maxContains", float("inf")):
                raise ValueError(f"{location} violates contains constraints")
    for child in schema.get("allOf", []):
        schema_check(value, child, root_schema, location)
    if "if" in schema:
        try:
            schema_check(value, schema["if"], root_schema, location)
            condition = True
        except ValueError:
            condition = False
        selected = schema.get("then" if condition else "else")
        if selected:
            schema_check(value, selected, root_schema, location)


def read_manifest():
    manifest = json.loads((HERE / "manifest.json").read_text())
    schema = json.loads((ROOT / "schema/manifest.schema.json").read_text())
    schema_check(manifest, schema, schema)
    package = json.loads((HERE / "package.json").read_text())
    lock = json.loads((HERE / "package-lock.json").read_text())
    if package["version"] != manifest["version"] or lock["version"] != manifest["version"]:
        raise ValueError("Align manifest.json, package.json and package-lock.json versions")
    if lock["packages"][""]["version"] != manifest["version"]:
        raise ValueError("The lockfile root package version must match the manifest")
    if f"Lockfile SHA-256: {digest(HERE / 'package-lock.json')}" not in (HERE / "THIRD_PARTY_NOTICES.txt").read_text():
        raise ValueError("Regenerate THIRD_PARTY_NOTICES.txt for the current lockfile")
    return manifest


def prepare_stage(destination, manifest):
    dist = HERE / "dist"
    if dist.is_symlink() or not (dist / "index.html").is_file():
        raise ValueError("Run npm run build first; a regular dist/index.html is required")
    dist_hashes = {}
    for path in sorted(dist.rglob("*")):
        if path.is_symlink():
            raise ValueError(f"Symlink in production dist: {path.relative_to(dist)}")
        if path.is_dir():
            continue
        if not stat.S_ISREG(path.stat().st_mode):
            raise ValueError("Only regular production files may be packaged")
        relative = path.relative_to(dist)
        if path.suffix == ".map" or any(part in {"node_modules", ".git", "test", "tests"} for part in relative.parts):
            raise ValueError(f"Remove non-production content from dist: {relative}")
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
        dist_hashes[relative.as_posix()] = digest(target)
    for name in ("manifest.json", "THIRD_PARTY_NOTICES.txt", "UPSTREAM.md"):
        if (destination / name).exists():
            raise ValueError(f"Production dist unexpectedly contains reserved file: {name}")
        shutil.copyfile(HERE / name, destination / name)
    if not (destination / manifest["icon"]).is_file():
        raise ValueError("The declared icon is missing from production dist")
    # Keep source evidence as hashes only; do not ship source, tests or credentials.
    source_hash = hashlib.sha256()
    for path in sorted((HERE / "src").rglob("*")):
        if path.is_file():
            source_hash.update(path.relative_to(HERE).as_posix().encode() + b"\0" + path.read_bytes() + b"\0")
    provenance = {
        "application": "NextFlux for ToolBox",
        "version": manifest["version"],
        "upstream": {"repository": UPSTREAM_URL, "commit": UPSTREAM_COMMIT},
        "upstreamReadmeSHA256": digest(HERE / "UPSTREAM.md"),
        "packageLockSHA256": digest(HERE / "package-lock.json"),
        "thirdPartyNoticesSHA256": digest(HERE / "THIRD_PARTY_NOTICES.txt"),
        "sourceTreeSHA256": source_hash.hexdigest(),
        "packagingScriptSHA256": digest(Path(__file__)),
        "genericPackagerSHA256": digest(ROOT / "scripts/package-tool.py"),
        "productionFilesSHA256": dist_hashes,
        "runtimeVerification": "Packaging checks only; not Android or authenticated service verification.",
    }
    if (destination / "PROVENANCE.json").exists():
        raise ValueError("Production dist unexpectedly contains reserved file: PROVENANCE.json")
    (destination / "PROVENANCE.json").write_text(json_text(provenance))


def check_zip(path):
    size = path.stat().st_size
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        expanded = sum(item.file_size for item in entries)
        compressed = sum(item.compress_size for item in entries)
        largest = max((item.file_size for item in entries), default=0)
        ratio = max((item.file_size / max(1, item.compress_size) for item in entries), default=0)
        if len(entries) > MAX_FILES or size > MAX_ZIP or expanded > MAX_EXPANDED or largest > MAX_SINGLE:
            raise ValueError("Package exceeds ToolBox file-count or byte quotas")
        if ratio > MAX_RATIO or expanded > max(1, compressed) * MAX_RATIO:
            raise ValueError("Package exceeds ToolBox compression-ratio quota")
        bad = archive.testzip()
        if bad:
            raise ValueError(f"ZIP checksum failed: {bad}")
        integrity = json.loads(archive.read("integrity.json"))
        expected = {item.filename for item in entries} - {"integrity.json"}
        if set(integrity["files"]) != expected:
            raise ValueError("Integrity inventory does not match the packaged files")
        for name, expected_hash in integrity["files"].items():
            if hashlib.sha256(archive.read(name)).hexdigest() != expected_hash:
                raise ValueError(f"Integrity mismatch: {name}")
    return {"files": len(entries), "zipBytes": size, "expandedBytes": expanded,
            "largestFileBytes": largest, "maxFileCompressionRatio": round(ratio, 3),
            "totalCompressionRatio": round(expanded / max(1, compressed), 3), "sha256": digest(path)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Validate a temporary package without creating the delivery artifact")
    parser.add_argument("--output", type=Path, help="Optional new output path; existing artifacts are never replaced")
    args = parser.parse_args()
    try:
        manifest = read_manifest()
        output = args.output or ROOT / "build/nextflux" / f"nextflux-v{manifest['version']}.tbx"
        output = output.absolute()
        if output.suffix.lower() != ".tbx":
            raise ValueError("Output must have a .tbx extension")
        if not args.check and (output.exists() or output.is_symlink()):
            raise FileExistsError(f"Output already exists and will not be overwritten: {output}")
        spec = importlib.util.spec_from_file_location("toolbox_package_tool", ROOT / "scripts/package-tool.py")
        packager = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(packager)
        with tempfile.TemporaryDirectory(prefix="nextflux-package-") as directory:
            temporary = Path(directory)
            stage = temporary / "stage"
            stage.mkdir()
            prepare_stage(stage, manifest)
            candidate = temporary / "checked.tbx"
            packager.package_tool(stage, candidate)
            report = check_zip(candidate)
            if not args.check:
                output.parent.mkdir(parents=True, exist_ok=True)
                # The production packager uses an exclusive hard-link publication;
                # it also refuses a concurrent creation between validation and write.
                published_hash = packager.package_tool(stage, output)
                if published_hash != report["sha256"]:
                    raise ValueError("Repeated packaging did not reproduce the checked artifact")
                report["output"] = str(output)
            report["mode"] = "check-only" if args.check else "packaged"
            report["manifestSchema"] = "PASS"
            report["zipQuotas"] = "PASS"
            print(json_text(report), end="")
    except (OSError, ValueError, KeyError, UnsupportedSchema) as failure:
        parser.exit(1, f"Cannot package NextFlux: {failure}\n")


if __name__ == "__main__":
    main()
