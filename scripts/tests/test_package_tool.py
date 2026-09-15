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

    def test_output_inside_source_is_rejected(self):
        with self.assertRaises(ValueError):
            PACKAGER.package_tool(self.source, self.source / "nested.tbx")
        self.assertFalse((self.source / "nested.tbx").exists())


if __name__ == "__main__":
    unittest.main()
