import importlib.util
from pathlib import Path
import unittest

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
                ("全部工具", "设置", "还没有工具", "安装四个范例", "导入 .tbx")) + '</hierarchy>',
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
