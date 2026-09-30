#!/usr/bin/env python3
"""Select focused Android checks and run explicitly named test cases."""

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


NETWORK_UNIT = {
    "app=io.toolbox.host.background.NetworkResourceAdmissionTest",
    "app=io.toolbox.host.background.ToolNetworkTransportTest",
    "app=io.toolbox.host.background.ToolNetworkResponseEncodingTest",
}
BACKGROUND_UNIT = {
    "app=io.toolbox.host.background.BackgroundTaskCancellationTest",
    "app=io.toolbox.host.background.BackgroundExecutionConcurrencyTest",
    "app=io.toolbox.host.background.BackgroundTaskActionsTest",
}
BROWSER_UNIT = {
    "app=io.toolbox.host.browser.BrowserFilterRulesTest",
    "app=io.toolbox.host.browser.BrowserInteractionStateTest",
}
BROWSER_ANDROID = {
    "io.toolbox.host.browser.BrowserBrowsingCompatibilityTest",
    "io.toolbox.host.browser.BrowserMediaLayoutInstrumentationTest",
    "io.toolbox.host.browser.BrowserNavigationInstrumentationTest",
}
BROWSER_NODE = {
    "scripts/tests/browser-picker.test.cjs",
    "scripts/tests/browser-media-layout.test.cjs",
    "scripts/tests/browser-media-layout-chromium.test.cjs",
}
BACKGROUND_ANDROID = {"io.toolbox.host.background.WorkerLifecycleInstrumentationTest"}
MIGRATION_ANDROID = {"io.toolbox.host.background.BackgroundTaskMigrationTest"}
UNIT_RE = re.compile(r"^(app|core-data|tool-package|tool-runtime)=([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+)(?:#([A-Za-z_]\w*))?$")
ANDROID_RE = re.compile(r"^io\.toolbox\.host\.[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*(?:#[A-Za-z_]\w*)?$")
NODE_RE = re.compile(r"^scripts/tests/browser-[A-Za-z0-9-]+\.test\.cjs$")


def items(value):
    return [part.strip() for part in value.split(",") if part.strip()]


def validate(values, pattern, label):
    for value in values:
        if not pattern.fullmatch(value):
            raise ValueError(f"Invalid {label}: {value}")
    return values


def classify(paths):
    unit, android, node, unknown = set(), set(), set(), []
    for path in paths:
        if path.startswith("examples/") or path == ".github/workflows/tbx.yml":
            # Standalone tools are checked by TBX CI; bundled examples still enter the APK build.
            continue
        if path in {".github/workflows/android.yml", "scripts/ci/targeted-checks.py", "scripts/ci/reuse-host-verification.py", "scripts/tests/test_reuse_host_verification.py"}:
            node.add("scripts/tests/browser-picker.test.cjs")
            android |= BROWSER_ANDROID
        elif path.startswith("app/src/main/kotlin/io/toolbox/host/background/") or path.startswith("app/src/test/kotlin/io/toolbox/host/background/"):
            unit |= NETWORK_UNIT if any(name in path for name in ("Network", "RuntimeNetwork")) else BACKGROUND_UNIT
            android |= BACKGROUND_ANDROID
            if any(name in path for name in ("BackgroundTask", "BackgroundTasks")):
                android |= MIGRATION_ANDROID
        elif path.startswith("app/src/androidTest/kotlin/io/toolbox/host/background/"):
            android.add("io.toolbox.host.background." + Path(path).stem)
        elif path.startswith("core-data/src/main/kotlin/io/toolbox/core/data/") and Path(path).name in {
            "CoreDataFactory.kt", "Repositories.kt", "Daos.kt", "Entities.kt", "RoomRepositories.kt", "ToolBoxDatabase.kt", "ToolBoxMigrations.kt",
        }:
            unit |= BACKGROUND_UNIT
            android |= MIGRATION_ANDROID
        elif path in {
            "app/src/main/kotlin/io/toolbox/host/HostOperations.kt",
            "app/src/main/kotlin/io/toolbox/host/ProductionHostOperations.kt",
            "app/src/main/kotlin/io/toolbox/host/backup/HostBackupService.kt",
            "app/src/test/kotlin/io/toolbox/host/settings/SettingsBackgroundUpdateTest.kt",
            "app/src/androidTest/kotlin/io/toolbox/host/backup/HostBackupResourceTest.kt",
        }:
            unit |= BACKGROUND_UNIT
            android |= MIGRATION_ANDROID
            if path.endswith("HostBackupResourceTest.kt"):
                android.add("io.toolbox.host.backup.HostBackupResourceTest")
        elif path.startswith("app/src/main/kotlin/io/toolbox/host/browser/") or path.startswith("app/src/test/kotlin/io/toolbox/host/browser/") or path.startswith("app/src/androidTest/kotlin/io/toolbox/host/browser/") or path.startswith("app/src/main/assets/browser/"):
            unit |= BROWSER_UNIT
            android |= BROWSER_ANDROID
            node |= BROWSER_NODE
        elif path.startswith("app/src/main/kotlin/io/toolbox/host/icons/") or path.startswith("app/src/test/kotlin/io/toolbox/host/icons/"):
            unit.add("app=io.toolbox.host.icons.ToolIconLoadCoordinatorTest")
        elif path == "app/src/main/kotlin/io/toolbox/host/runtime/RuntimeForegroundService.kt":
            android |= BACKGROUND_ANDROID
            unit |= BACKGROUND_UNIT
        elif path.startswith("tool-runtime/src/main/kotlin/io/toolbox/tool/runtime/") or path.startswith("tool-runtime/src/test/kotlin/io/toolbox/tool/runtime/"):
            unit |= {
                "tool-runtime=io.toolbox.tool.runtime.ToolRuntimeSecurityBoundaryTest",
                "tool-runtime=io.toolbox.tool.runtime.RuntimeNetworkBudgetTest",
            }
        elif path == "tool-package/src/test/kotlin/io/toolbox/tool/packagekit/fixtures/InMemoryCoreData.kt":
            unit |= {
                "tool-package=io.toolbox.tool.packagekit.lifecycle.PackageImportCancellationTest",
                "tool-package=io.toolbox.tool.packagekit.ManifestNetworkBudgetTest",
            }
        else:
            unknown.append(path)
    return unit, android, node, unknown


def changed_paths():
    base, head = os.environ["BASE_SHA"], os.environ["HEAD_SHA"]
    if base == "0" * 40:
        raise ValueError("Push has no prior commit")
    raw = subprocess.check_output(["git", "diff", "--name-only", "--no-renames", "-z", base, head])
    return [path.decode() for path in raw.split(b"\0") if path]


def plan():
    manual = os.environ["GITHUB_EVENT_NAME"] == "workflow_dispatch"
    requested = os.environ.get("VALIDATION_SCOPE", "full") if manual else "full"
    unit = validate(items(os.environ.get("UNIT_TEST_FILTER", "")), UNIT_RE, "unit class#method")
    android = validate(items(os.environ.get("ANDROID_TEST_FILTER", "")), ANDROID_RE, "Android class#method")
    node = validate(items(os.environ.get("NODE_TEST_PATH", "")), NODE_RE, "Node test path")
    if manual:
        if android:
            requested = "targeted"
        if requested == "targeted" and not (unit or android or node):
            raise ValueError("Targeted dispatch needs at least one test filter")
        if requested == "targeted" and os.environ.get("RUN_ANDROID_UI") == "true" and not android:
            raise ValueError("Targeted emulator runs need an Android class#method filter")
        scope = requested
        unknown = []
    else:
        try:
            selected_unit, selected_android, selected_node, unknown = classify(changed_paths())
            if not unknown and (selected_unit or selected_android or selected_node):
                scope = "targeted"
                unit, android, node = sorted(selected_unit), sorted(selected_android), sorted(selected_node)
            else:
                scope = "full"
        except (ValueError, subprocess.CalledProcessError) as error:
            scope, unknown = "full", [str(error)]
    outputs = {"scope": scope, "unit": ",".join(unit), "android": ",".join(android), "node": ",".join(node)}
    with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
        output.writelines(f"{key}={value}\n" for key, value in outputs.items())
    with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as summary:
        summary.write(f"Android check scope: `{scope}`. Unit: {len(unit)}, Android: {len(android)}, Node: {len(node)}.\n")
        if unknown:
            summary.write("No targeted mapping for: " + ", ".join(sorted(unknown)) + ". Running full checks.\n")


def run_unit(filters):
    grouped = {}
    for value in validate(items(filters), UNIT_RE, "unit class#method"):
        match = UNIT_RE.fullmatch(value)
        module, name, method = match.groups()
        grouped.setdefault(module, []).append((name, method))
    if not grouped:
        return
    for module, selected in grouped.items():
        command = ["./gradlew", "--no-daemon", f":{module}:testDebugUnitTest"]
        for name, method in selected:
            command.extend(["--tests", name + ("." + method if method else "")])
        subprocess.run(command, check=True)
        cases = testcases(Path(module) / "build/test-results/testDebugUnitTest")
        for name, method in selected:
            if not any(case.get("classname") == name and (method is None or case.get("name") == method)
                       for case in cases):
                raise ValueError(f"No executed unit test matched {module}={name}{'#' + method if method else ''}")


def run_node(paths):
    selected = validate(items(paths), NODE_RE, "Node test path")
    for path in selected:
        result = subprocess.run(["node", "--test", path], check=True, text=True, capture_output=True)
        sys.stdout.write(result.stdout)
        sys.stderr.write(result.stderr)
        counts = re.findall(r"^# tests (\d+)\s*$", result.stdout, re.MULTILINE)
        if not counts or int(counts[-1]) == 0:
            raise ValueError(f"No Node tests executed in {path}")


def testcases(folder):
    return [case for path in folder.rglob("TEST-*.xml") for case in ET.parse(path).iter("testcase")]


def android_results(filters):
    cases = testcases(Path("app/build/outputs/androidTest-results/connected"))
    for value in validate(items(filters), ANDROID_RE, "Android class#method"):
        classname, _, method = value.partition("#")
        if not any(case.get("classname") == classname and (not method or case.get("name") == method) for case in cases):
            raise ValueError(f"No executed Android test matched {value}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["plan", "unit", "node", "android-results"])
    parser.add_argument("--filters", default="")
    args = parser.parse_args()
    {"plan": plan, "unit": lambda: run_unit(args.filters), "node": lambda: run_node(args.filters),
     "android-results": lambda: android_results(args.filters)}[args.action]()
