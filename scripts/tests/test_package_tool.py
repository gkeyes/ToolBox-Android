import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest
import zipfile
import hashlib


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("package_tool", ROOT / "scripts/package-tool.py")
PACKAGER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PACKAGER)
# NextFlux's packager carries the repository's dependency-free manifest.schema.json checker.
SCHEMA_SPEC = importlib.util.spec_from_file_location("nextflux_package", ROOT / "examples/nextflux/package.py")
SCHEMA_CHECKER = importlib.util.module_from_spec(SCHEMA_SPEC)
SCHEMA_SPEC.loader.exec_module(SCHEMA_CHECKER)
SCHEMA = json.loads((ROOT / "schema/manifest.schema.json").read_text(encoding="utf-8"))


class PackageToolTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="toolbox-packager-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "my-tool"
        self.source.mkdir()
        manifest = {
            "schemaVersion": 1, "id": "io.toolbox.packagerfixture", "name": "Packager fixture",
            "version": "1.0.0", "versionCode": 1, "entry": "index.html",
            "apiVersion": "1.0", "minHostVersion": "0.3.2", "permissions": [],
            "securityProfile": "strict",
        }
        (self.source / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        (self.source / "index.html").write_text(
            '<!doctype html><html><head><script src="app.js" defer></script></head><body>Fixture</body></html>',
            encoding="utf-8",
        )
        (self.source / "app.js").write_text('"use strict";', encoding="utf-8")

    def test_reproducible_nested_resources_and_integrity(self):
        (self.source / "assets").mkdir()
        (self.source / "assets/data.json").write_text('{"value": 1}', encoding="utf-8")
        (self.source / "worker.js").write_text("self.onmessage = () => {};", encoding="utf-8")
        (self.source / ".DS_Store").write_text("ignored", encoding="utf-8")
        (self.source / "integrity.json").write_text("old", encoding="utf-8")
        (self.source / "signature.json").write_text("old", encoding="utf-8")
        first = self.root / "first.tbx"
        second = self.root / "second.tbx"
        digest = PACKAGER.package_tool(self.source, first)
        self.assertEqual(digest, PACKAGER.package_tool(self.source, second))
        self.assertEqual(first.read_bytes(), second.read_bytes())
        with zipfile.ZipFile(first) as archive:
            names = set(archive.namelist())
            self.assertIn("manifest.json", names)
            self.assertIn("worker.js", names)
            self.assertIn("assets/data.json", names)
            self.assertNotIn(".DS_Store", names)
            self.assertNotIn("signature.json", names)
            integrity = json.loads(archive.read("integrity.json"))
            self.assertEqual(set(integrity["files"]), names - {"integrity.json"})
            for name, expected in integrity["files"].items():
                self.assertEqual(hashlib.sha256(archive.read(name)).hexdigest(), expected)

    def test_existing_output_requires_explicit_overwrite(self):
        output = self.root / "tool.tbx"
        output.write_bytes(b"user-owned artifact")
        with self.assertRaises(FileExistsError):
            PACKAGER.package_tool(self.source, output)
        self.assertEqual(output.read_bytes(), b"user-owned artifact")
        PACKAGER.package_tool(self.source, output, overwrite=True)
        self.assertTrue(zipfile.is_zipfile(output))

    def test_wasm_and_companion_binary_resources_preserve_bytes_and_integrity(self):
        # (module (func (export "add") (param i32 i32) (result i32)
        #   local.get 0 local.get 1 i32.add))
        wasm = bytes.fromhex(
            "0061736d0100000001070160027f7f017f03020100070701036164640000"
            "0a09010700200020016a0b"
        )
        resources = {
            "compute.wasm": wasm,
            "upper.WASM": wasm,
            "modules/side.so": wasm,
            "modules/extensionless": wasm,
            "assets/model.data": bytes(range(256)) * 4,
            "assets/payload.bin": b"\x00\xff\xfe\x80\r\n\x00",
            "assets/raw": b"\x00\x80\xffnot UTF-8\n",
        }
        for name, payload in resources.items():
            target = self.source / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(payload)
        output = self.root / "wasm-tool.tbx"
        PACKAGER.package_tool(self.source, output)
        with zipfile.ZipFile(output) as archive:
            integrity = json.loads(archive.read("integrity.json"))
            self.assertEqual(set(integrity["files"]), set(archive.namelist()) - {"integrity.json"})
            for name, payload in resources.items():
                with self.subTest(resource=name):
                    self.assertEqual(archive.read(name), payload)
                    self.assertEqual(integrity["files"][name], hashlib.sha256(payload).hexdigest())

    def test_many_companion_files_are_not_subject_to_former_install_count_limit(self):
        data = self.source / "data"
        data.mkdir()
        for index in range(513):
            (data / f"part-{index:04d}.bin").write_bytes(index.to_bytes(2, "little"))
        output = self.root / "many-resources.tbx"
        PACKAGER.package_tool(self.source, output)
        with zipfile.ZipFile(output) as archive:
            integrity = json.loads(archive.read("integrity.json"))
            for index in range(513):
                name = f"data/part-{index:04d}.bin"
                payload = index.to_bytes(2, "little")
                self.assertEqual(archive.read(name), payload)
                self.assertEqual(integrity["files"][name], hashlib.sha256(payload).hexdigest())

    def test_invalid_source_has_no_output_or_temporary_residue(self):
        for kind in ("entry", "icon", "archive", "file-link", "directory-link"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory(dir=self.root) as directory:
                root = Path(directory)
                source = root / "source"
                shutil.copytree(self.source, source)
                if kind == "entry":
                    (source / "index.html").unlink()
                elif kind == "icon":
                    manifest = json.loads((source / "manifest.json").read_text(encoding="utf-8"))
                    manifest["icon"] = {"invalid": "type"}
                    (source / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
                elif kind == "archive":
                    (source / "nested.zip").write_bytes(b"nested")
                elif kind == "file-link":
                    (source / "linked.js").symlink_to(source / "app.js")
                else:
                    (source / "linked-directory").symlink_to(self.source, target_is_directory=True)
                with self.assertRaises(ValueError):
                    PACKAGER.package_tool(source, root / "result.tbx")
                self.assertEqual([path.name for path in root.iterdir()], ["source"])

    def write_manifest(self, source, **changes):
        manifest = json.loads((source / "manifest.json").read_text(encoding="utf-8"))
        manifest.update(changes)
        (source / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")

    def assert_rejected_without_output(self, prepare):
        with tempfile.TemporaryDirectory(dir=self.root) as directory:
            root = Path(directory)
            source = root / "source"
            shutil.copytree(self.source, source)
            prepare(source)
            with self.assertRaises(ValueError):
                PACKAGER.package_tool(source, root / "result.tbx")
            self.assertEqual([path.name for path in root.iterdir()], ["source"])

    def test_host_payload_rules_are_mirrored(self):
        # ZipArchiveReader.sniffPayload and PackagePathPolicy.forbiddenCode, by name and by content.
        cases = {
            "nested.bz2": b"plain", "nested.xz": b"plain", "nested.gz": b"plain",
            "classes.dex": b"plain", "lib/native.so": b"plain", "Main.class": b"plain",
            "base.odex": b"plain", "base.vdex": b"plain", "base.oat": b"plain",
            "elf.js": b"\x7fELF\x02", "dex.txt": b"dex\n035", "java.bin": b"\xca\xfe\xba\xbe",
            "zip.png": b"PK\x03\x04", "empty-zip.dat": b"PK\x05\x06" + bytes(18), "gzip.css": b"\x1f\x8b\x08",
            "seven.bin": b"7z\xbc\xaf\x27\x1c", "rar.bin": b"Rar!\x1a\x07\x00", "bzip.bin": b"BZh91AY",
            "xz.bin": b"\xfd7zXZ\x00", "tar.bin": bytes(257) + b"ustar\x0000",
        }
        for name, payload in cases.items():
            with self.subTest(name=name):
                def prepare(source, name=name, payload=payload):
                    (source / name).parent.mkdir(parents=True, exist_ok=True)
                    (source / name).write_bytes(payload)
                self.assert_rejected_without_output(prepare)

    def test_dot_named_files_use_the_host_extension_rule(self):
        # PackagePathPolicy.forbiddenCode takes the text after the last '.', even at the start of a name.
        for name in [".so", "sub/.zip", "lib/.DEX", ".tar.gz"]:
            with self.subTest(name=name):
                def prepare(source, name=name):
                    (source / name).parent.mkdir(parents=True, exist_ok=True)
                    (source / name).write_bytes(b"plain")
                self.assert_rejected_without_output(prepare)
        # Other dot-named files and dotted directories are still packaged, as the host accepts them.
        (self.source / ".config").write_text("1", encoding="utf-8")
        (self.source / "v1.so.d").mkdir()
        (self.source / "v1.so.d/data.txt").write_text("1", encoding="utf-8")
        PACKAGER.package_tool(self.source, self.root / "dotted.tbx")

    def test_host_path_rules_are_mirrored(self):
        names = ["a%2e%2e.js", "b%2F.js", "c%5c.js", "C:drive.js"]
        for name in names:
            with self.subTest(name=name[:40]):
                self.assert_rejected_without_output(lambda source, name=name: (source / name).write_text("1", encoding="utf-8"))
        def file_and_directory(source):
            (source / "Data").write_text("file", encoding="utf-8")
            (source / "data").mkdir()
            (source / "data/part.js").write_text("1", encoding="utf-8")
        self.assert_rejected_without_output(file_and_directory)
        # Most filesystems cannot hold a longer name; the rule itself still matches the host's 255 bytes.
        with self.assertRaises(ValueError):
            PACKAGER.validate_path("dir/" + "é" * 128)
        self.assertEqual(PACKAGER.validate_path("dir/" + "é" * 127), "dir/" + "é" * 127)
        # A 255-byte component is still accepted, as on the host.
        (self.source / ("y" * 252 + ".js")).write_text("1", encoding="utf-8")
        PACKAGER.package_tool(self.source, self.root / "long-name.tbx")

    def test_core_manifest_rules_are_mirrored(self):
        long_permission = [{"name": "alarms", "reason": "Wake up"}]
        cases = {
            "unknown field": {"futureField": True},
            "unknown permission": {"permissions": [{"name": "future.capability", "reason": "New"}]},
            "duplicate permission": {"permissions": [{"name": "storage", "reason": "a"}, {"name": "storage", "reason": "b"}]},
            "0.3 capability on 0.2 host": {"minHostVersion": "0.2.9", "permissions": long_permission},
            "dot entry": {"entry": "./index.html"},
            "empty segment entry": {"entry": "a//index.html"},
            "non-ASCII entry": {"entry": "页面.html"},
            "non-HTML entry": {"entry": "app.js"},
            "directory icon": {"icon": "img/"},
            "versionCode zero": {"versionCode": 0},
            "boolean versionCode": {"versionCode": True},
            "fractional versionCode": {"versionCode": 1.5},
            "bad id": {"id": "Example"},
            "bad apiVersion": {"apiVersion": "2.0"},
            "bad securityProfile": {"securityProfile": "open"},
            "bad network": {"network": {"maxResponseBytes": 0}},
            "removed network field": {"network": {"allowDomains": []}},
            "duplicate categories": {"categories": ["a", "a"]},
            "bad ui": {"ui": {"orientation": "sideways"}},
            "bad edgeToEdge": {"ui": {"edgeToEdge": "yes"}},
        }
        for label, changes in cases.items():
            with self.subTest(case=label):
                self.assert_rejected_without_output(lambda source, changes=changes: self.write_manifest(source, **changes))
        for raw in ('{"schemaVersion":1,"schemaVersion":1}', '{"versionCode": NaN}'):
            with self.subTest(raw=raw):
                with self.assertRaises(ValueError):
                    PACKAGER.validate_manifest(raw.encode("utf-8"))
        accepted = dict(minHostVersion="0.3.0", permissions=long_permission, versionCode=2.0,
                        network={"maxResponseBytes": 9007199254740991, "timeoutMs": 0},
                        ui={"orientation": "portrait", "allowFullscreen": True, "edgeToEdge": True})
        self.write_manifest(self.source, **accepted)
        PACKAGER.package_tool(self.source, self.root / "accepted.tbx")

    def test_schema_agrees_with_the_host_manifest_rules(self):
        base = json.loads((self.source / "manifest.json").read_text(encoding="utf-8"))
        alarms = [{"name": "alarms", "reason": "Wake up"}]
        cases = [{"entry": entry} for entry in (
            "index.html", "pages/help.html", "./index.html", "a//index.html", "a/./index.html",
            "../index.html", "/index.html", "pages/", "x" * 250 + ".html", "x" * 251 + ".html",
        )] + [{"icon": icon} for icon in ("icon.svg", "img/", "./icon.svg", "a//icon.svg", "x" * 255, "x" * 256)] + [
            {"minHostVersion": version, "permissions": alarms}
            for version in ("0.2.9", "0.3.0", "0.3.0-beta", "0.10.0", "1.0.0", "00.03.00", "0.02.9")
        ]
        for changes in cases:
            manifest = dict(base, **changes)
            with self.subTest(changes={key: str(value)[:40] for key, value in changes.items()}):
                try:
                    PACKAGER.validate_manifest(json.dumps(manifest).encode("utf-8"))
                    host = True
                except ValueError:
                    host = False
                try:
                    SCHEMA_CHECKER.schema_check(manifest, SCHEMA, SCHEMA)
                    schema = True
                except ValueError:
                    schema = False
                self.assertEqual(host, schema)

    def test_output_inside_source_is_rejected(self):
        with self.assertRaises(ValueError):
            PACKAGER.package_tool(self.source, self.source / "nested.tbx")
        self.assertFalse((self.source / "nested.tbx").exists())


if __name__ == "__main__":
    unittest.main()
