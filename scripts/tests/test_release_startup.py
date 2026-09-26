import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("startup", Path(__file__).resolve().parents[1] / "ci/release-startup-smoke.py")
startup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(startup)


class ReleaseStartupEvidenceTest(unittest.TestCase):
    def fixture(self):
        return dict(
            package="io.toolbox.host", version="0.8.4", code="37",
            start="Status: ok\nActivity: io.toolbox.host/.MainActivity\n",
            installed="versionName=0.8.4\nversionCode=37 minSdk=33\n",
            logs="ActivityTaskManager: Fully drawn io.toolbox.host/.MainActivity: +712ms\n",
            ui='<?xml version="1.0"?><hierarchy><node package="io.toolbox.host" content-desc="首页" selected="true" />' + ''.join(
                f'<node package="io.toolbox.host" text="{label}" />' for label in
                ("全部工具", "设置", "还没有工具", "安装四个范例", "导入工具")) + '</hierarchy>',
            pid="1245\n",
        )

    def test_actual_home_and_matching_install_are_required(self):
        startup.validate_startup(**self.fixture())
        for field, replacement in (
            ("start", "Status: timeout\nActivity: io.toolbox.host/.MainActivity\n"),
            ("logs", "ActivityTaskManager: Displayed io.toolbox.host/.MainActivity: +100ms"),
            ("logs", "ActivityTaskManager: Fully drawn io.toolbox.other/.MainActivity: +100ms"),
            ("installed", "versionName=0.8.3\nversionCode=36\n"),
            ("ui", '<?xml version="1.0"?><hierarchy><node text="正在打开本机工具目录。" /></hierarchy>'),
            ("pid", ""),
        ):
            with self.subTest(field=field, value=replacement), self.assertRaises(ValueError):
                startup.validate_startup(**(self.fixture() | {field: replacement}))

    def test_crash_or_anr_after_first_draw_still_fails(self):
        for failure in ("Process: io.toolbox.host, PID: 1245", "Cmdline: io.toolbox.host", "ANR in io.toolbox.host"):
            evidence = self.fixture()
            evidence["logs"] += failure
            with self.subTest(failure=failure), self.assertRaises(ValueError):
                startup.validate_startup(**evidence)

    def test_another_packages_navigation_is_not_home_evidence(self):
        evidence = self.fixture()
        evidence["ui"] = evidence["ui"].replace('package="io.toolbox.host"', 'package="io.other"')
        with self.assertRaises(ValueError):
            startup.validate_startup(**evidence)

    def test_navigation_only_wrong_tab_and_loading_error_are_rejected(self):
        ui = self.fixture()["ui"]
        for replacement in (
            ui.replace('selected="true"', 'selected="false"'),
            ui.replace('text="还没有工具"', 'text=""'),
            ui.replace('</hierarchy>', '<node package="io.toolbox.host" text="工具列表暂时无法读取。" /></hierarchy>'),
        ):
            with self.subTest(ui=replacement), self.assertRaises(ValueError):
                startup.validate_startup(**(self.fixture() | {"ui": replacement}))

    def test_missing_or_invalid_hierarchy_is_never_home_evidence(self):
        for ui in ('cat: /sdcard/toolbox-startup.xml: No such file or directory\n', '', '<other/>'):
            with self.subTest(ui=ui), self.assertRaisesRegex(ValueError, 'UI hierarchy'):
                startup.validate_startup(**(self.fixture() | {"ui": ui}))
        # An XML declaration is optional; the actual rendered content remains required.
        startup.validate_startup(**(self.fixture() | {"ui": self.fixture()["ui"].replace('<?xml version="1.0"?>', '')}))

    @patch.object(startup.time, 'sleep')
    def test_zero_exit_dump_without_file_retries_collection_without_reusing_stale_ui(self, _sleep):
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory)
            (evidence / 'home.xml').write_text(self.fixture()['ui'])
            pulls = []

            def adb(*args, record=None):
                if args[0] == 'pull':
                    pulls.append(args)
                    self.assertFalse((evidence / 'home.xml').exists())
                    if len(pulls) == 1:
                        raise subprocess.CalledProcessError(1, 'adb pull', output='remote file missing')
                    Path(args[2]).write_text(self.fixture()['ui'])
                return 'ERROR: null root node' if args[:3] == ('shell', 'uiautomator', 'dump') and not pulls else ''

            ui = startup.capture_ui_hierarchy(adb, evidence)
            self.assertEqual(2, len(pulls))
            startup.validate_startup(**(self.fixture() | {"ui": ui}))
            self.assertTrue((evidence / 'ui-collection-error-1.txt').exists())

    @patch.object(startup.time, 'sleep')
    def test_collection_failure_remains_blocking(self, _sleep):
        with tempfile.TemporaryDirectory() as directory:
            def adb(*args, record=None):
                if args[0] == 'pull':
                    raise subprocess.CalledProcessError(1, 'adb pull')
                return ''
            with self.assertRaisesRegex(RuntimeError, 'Could not collect'):
                startup.capture_ui_hierarchy(adb, Path(directory))
