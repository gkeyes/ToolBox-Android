#!/usr/bin/env python3
"""Select focused Android checks and run explicitly named test cases."""

import argparse
import json
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
BUNDLED_EXAMPLES = {"position-calculator", "quick-notes", "background-task-demo", "notification-lab"}
RUNTIME_STORAGE_ANDROID = {
    "io.toolbox.host.runtime.RuntimeStorageFlushInstrumentationTest#nonemptyFinalWriteWaitsForRoomThenSealRejectsAndCancelAllowsNewWrite",
}
RUNTIME_ADMISSION_ANDROID = {
    "tool-runtime=io.toolbox.tool.runtime.RuntimeBridgeAdmissionInstrumentationTest#busyOrdinaryAdmissionRetriesTheSameNonemptyFinalWriteWhileControlsRemainAvailable",
}
RUNTIME_SAVE_DIALOG_ANDROID = {
    "io.toolbox.host.ui.RuntimeSaveDialogBehaviorTest#miuixCloseChoicesAndCallbacks",
    "io.toolbox.host.ui.RuntimeSaveDialogBehaviorTest#liquidGlassCloseChoicesAndCallbacks",
}
UNIT_RE = re.compile(r"^(app|core-data|tool-package|tool-runtime)=([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+)(?:#([A-Za-z_]\w*))?$")
ANDROID_RE = re.compile(r"^(?:(app|tool-runtime)=)?(io\.toolbox\.[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)(?:#([A-Za-z_]\w*))?$")
NODE_RE = re.compile(r"^scripts/tests/browser-[A-Za-z0-9-]+\.test\.cjs$")
EVIDENCE_FILE = Path("build/ci-evidence/targeted-selection.json")


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
        if (path.startswith("examples/") or path == ".github/workflows/tbx.yml"
                or path in {"scripts/tests/watcher-reliability.test.cjs", "scripts/tests/tool-runtime-performance.test.cjs"}
                or path == "README.md" or path.startswith("docs/")
                or path.startswith("core-data/schemas/")
                or path in {"scripts/ci/tbx.py", "scripts/ci/tbx-targets.json"}):
            # These inputs are checked by TBX CI or do not change Android behavior.
            continue
        if path in {".github/workflows/android.yml", "scripts/ci/targeted-checks.py", "scripts/ci/reuse-host-verification.py", "scripts/tests/test_reuse_host_verification.py", "scripts/ci/verify_release.py", "scripts/ci/release-startup-smoke.py", "scripts/tests/test_release_startup.py", "scripts/tests/test_release_scope.py", "scripts/ci/run-android-behavior.sh"}:
            node.add("scripts/tests/browser-picker.test.cjs")
            android |= BROWSER_ANDROID
        elif path.startswith("app/src/main/kotlin/io/toolbox/host/background/") or path.startswith("app/src/test/kotlin/io/toolbox/host/background/"):
            unit |= NETWORK_UNIT if any(name in path for name in ("Network", "RuntimeNetwork")) else BACKGROUND_UNIT
            android |= BACKGROUND_ANDROID
            if any(name in path for name in ("BackgroundTask", "BackgroundTasks")):
                android |= MIGRATION_ANDROID
        elif path.startswith("app/src/androidTest/kotlin/io/toolbox/host/background/"):
            android.add("io.toolbox.host.background." + Path(path).stem)
        elif path == "app/src/androidTest/AndroidManifest.xml":
            android |= RUNTIME_STORAGE_ANDROID | RUNTIME_SAVE_DIALOG_ANDROID
        elif path == "core-data/build.gradle.kts" or (path.startswith("core-data/src/main/kotlin/io/toolbox/core/data/") and Path(path).name in {
            "CoreDataFactory.kt", "Repositories.kt", "Daos.kt", "Entities.kt", "RoomRepositories.kt", "ToolBoxDatabase.kt", "ToolBoxMigrations.kt",
        }):
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
        elif path.startswith("app/src/main/kotlin/io/toolbox/host/runtime/"):
            unit |= {
                "app=io.toolbox.host.runtime.RuntimeNotificationControllerTest",
                "app=io.toolbox.host.HostRootStateTest",
            }
            android |= BACKGROUND_ANDROID
            android |= RUNTIME_STORAGE_ANDROID
        elif path.startswith("app/src/androidTest/kotlin/io/toolbox/host/runtime/"):
            if Path(path).stem == "RuntimeStorageFlushInstrumentationTest":
                android |= RUNTIME_STORAGE_ANDROID
            else:
                android.add(path.removeprefix("app/src/androidTest/kotlin/").removesuffix(".kt").replace("/", "."))
        elif path.startswith("app/src/main/kotlin/io/toolbox/host/ui/"):
            unit.add("app=io.toolbox.host.HostRootStateTest")
            android.add("io.toolbox.host.SecondaryPageBehaviorTest")
            if Path(path).name == "HostCapabilityScreens.kt":
                android |= RUNTIME_SAVE_DIALOG_ANDROID
        elif path == "app/src/androidTest/kotlin/io/toolbox/host/ui/RuntimeSaveDialogBehaviorTest.kt":
            android |= RUNTIME_SAVE_DIALOG_ANDROID
        elif path == "app/build.gradle.kts":
            unit |= NETWORK_UNIT | BACKGROUND_UNIT
            android |= MIGRATION_ANDROID
        elif path in {"scripts/ci/verify-host-contract.mjs", "sdk/help/manual.md"}:
            unit.add("tool-runtime=io.toolbox.tool.runtime.ToolRuntimeSecurityBoundaryTest")
            android.add("tool-runtime=io.toolbox.tool.runtime.WasmRuntimeInstrumentationTest")
        elif path.startswith("app/src/test/kotlin/io/toolbox/host/") and Path(path).stem.endswith("Test"):
            unit.add("app=" + path.removeprefix("app/src/test/kotlin/").removesuffix(".kt").replace("/", "."))
        elif path.startswith("app/src/androidTest/kotlin/io/toolbox/host/") and Path(path).stem.endswith("Test"):
            android.add(path.removeprefix("app/src/androidTest/kotlin/").removesuffix(".kt").replace("/", "."))
        elif path.startswith("core-data/src/test/kotlin/") and Path(path).stem.endswith("Test"):
            unit.add("core-data=" + path.removeprefix("core-data/src/test/kotlin/").removesuffix(".kt").replace("/", "."))
        elif path.startswith("tool-package/src/test/kotlin/") and Path(path).stem.endswith("Test"):
            unit.add("tool-package=" + path.removeprefix("tool-package/src/test/kotlin/").removesuffix(".kt").replace("/", "."))
        elif path.startswith("tool-runtime/src/main/kotlin/io/toolbox/tool/runtime/") or path.startswith("tool-runtime/src/test/kotlin/io/toolbox/tool/runtime/"):
            unit |= {
                "tool-runtime=io.toolbox.tool.runtime.ToolRuntimeSecurityBoundaryTest",
                "tool-runtime=io.toolbox.tool.runtime.RuntimeNetworkBudgetTest",
                "tool-runtime=io.toolbox.tool.runtime.RuntimePresentationCoordinatorTest",
                "tool-runtime=io.toolbox.tool.runtime.RuntimeEventBufferTest",
                "tool-runtime=io.toolbox.tool.runtime.RuntimeAuthorizationStageTest",
            }
            android |= {
                "tool-runtime=io.toolbox.tool.runtime.RuntimeBridgeLifecycleInstrumentationTest#readyOnceEarlyEventsStateRevisionsCloseAndNewGeneration",
                "tool-runtime=io.toolbox.tool.runtime.RuntimeBridgeLifecycleInstrumentationTest#dedicatedProfileServiceWorkerHardeningIsRestoredAfterDeleteAndRecreate",
            }
            android |= RUNTIME_ADMISSION_ANDROID
        elif path.startswith("tool-runtime/src/androidTest/kotlin/io/toolbox/tool/runtime/"):
            test_name = Path(path).stem
            if test_name == "RuntimeBridgeLifecycleInstrumentationTest":
                android |= {
                    "tool-runtime=io.toolbox.tool.runtime.RuntimeBridgeLifecycleInstrumentationTest#readyOnceEarlyEventsStateRevisionsCloseAndNewGeneration",
                    "tool-runtime=io.toolbox.tool.runtime.RuntimeBridgeLifecycleInstrumentationTest#dedicatedProfileServiceWorkerHardeningIsRestoredAfterDeleteAndRecreate",
                }
            elif test_name == "RuntimeBridgeAdmissionInstrumentationTest":
                android |= RUNTIME_ADMISSION_ANDROID
            else:
                android.add("tool-runtime=io.toolbox.tool.runtime." + (test_name if test_name.endswith("Test") else "WasmRuntimeInstrumentationTest"))
        elif path.startswith("tool-api/src/main/kotlin/io/toolbox/tool/api/"):
            unit |= {
                "tool-runtime=io.toolbox.tool.runtime.ToolRuntimeSecurityBoundaryTest",
                "tool-runtime=io.toolbox.tool.runtime.RuntimeAuthorizationStageTest",
            }
            android.add("tool-runtime=io.toolbox.tool.runtime.RuntimeBridgeLifecycleInstrumentationTest#readyOnceEarlyEventsStateRevisionsCloseAndNewGeneration")
            android |= RUNTIME_ADMISSION_ANDROID
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
    android_groups(",".join(android))
    node = validate(items(os.environ.get("NODE_TEST_PATH", "")), NODE_RE, "Node test path")
    reuse_run = os.environ.get("REUSE_VERIFIED_RUN", "")
    if manual:
        if requested == "reuse":
            if not re.fullmatch(r"[0-9]+", reuse_run) or unit or android or node:
                raise ValueError("Reuse needs a prior numeric run ID and no new test filters")
            if os.environ.get("RUN_ANDROID_UI") != "true":
                raise ValueError("Reused verification must cold-start the signed release")
        elif reuse_run and os.environ.get("RUN_ANDROID_UI") != "true":
            raise ValueError("Reused verification must cold-start the signed release")
        if android:
            requested = "targeted"
        if requested != "targeted" and (unit or node):
            raise ValueError("Choose targeted validation when selecting unit or Node test filters")
        if requested == "targeted" and not (unit or android or node):
            raise ValueError("Targeted dispatch needs at least one test filter")
        if requested == "targeted" and os.environ.get("RUN_ANDROID_UI") == "true" and not android:
            raise ValueError("Targeted emulator runs need an Android class#method filter")
        if requested == "targeted" and reuse_run:
            raise ValueError("Select reuse validation to use a completed targeted run")
        scope = requested
        unknown = []
    else:
        paths = changed_paths()
        selected_unit, selected_android, selected_node, unknown = classify(paths)
        if unknown:
            raise ValueError("Missing targeted check mapping for: " + ", ".join(sorted(unknown)))
        if not (selected_unit or selected_android or selected_node):
            bundled = [path for path in paths if path.startswith("examples/")
                       and path.split("/", 2)[1] in BUNDLED_EXAMPLES]
            if bundled:
                raise ValueError("Bundled APK inputs need a focused build check: " + ", ".join(sorted(bundled)))
            scope = "docs_only"
        else:
            scope = "targeted"
        unit, android, node = sorted(selected_unit), sorted(selected_android), sorted(selected_node)
    outputs = {"scope": scope, "unit": ",".join(unit), "android": ",".join(android), "node": ",".join(node)}
    with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
        output.writelines(f"{key}={value}\n" for key, value in outputs.items())
    with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as summary:
        summary.write(f"Android check scope: `{scope}`. Unit: {len(unit)}, Android: {len(android)}, Node: {len(node)}.\n")
    if scope == "targeted":
        checkout_commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
        expected_commit = os.environ["HEAD_SHA"] if not manual else os.environ["GITHUB_SHA"]
        if checkout_commit != expected_commit:
            raise ValueError("Targeted checks must execute the exact PR head or dispatch commit")
        EVIDENCE_FILE.parent.mkdir(parents=True, exist_ok=True)
        EVIDENCE_FILE.write_text(json.dumps({
            "scope": "targeted", "run_id": int(os.environ["GITHUB_RUN_ID"]),
            "attempt": int(os.environ["GITHUB_RUN_ATTEMPT"]), "head_sha": checkout_commit,
            "filters": {"unit": unit, "android": android, "node": node},
            "executed": {"unit": {}, "android": {}, "node": {}},
        }, sort_keys=True) + "\n")


def record_execution(kind, counts):
    if not EVIDENCE_FILE.exists():
        return
    evidence = json.loads(EVIDENCE_FILE.read_text())
    if set(counts) - set(evidence["filters"][kind]):
        raise ValueError(f"Executed {kind} cases do not match the selected filters")
    evidence["executed"][kind].update(counts)
    replacement = EVIDENCE_FILE.with_suffix(".tmp")
    replacement.write_text(json.dumps(evidence, sort_keys=True) + "\n")
    replacement.replace(EVIDENCE_FILE)


def verify_evidence():
    evidence = json.loads(EVIDENCE_FILE.read_text())
    if evidence["scope"] != "targeted":
        raise ValueError("Only targeted check evidence can be uploaded here")
    for kind, selected in evidence["filters"].items():
        executed = evidence["executed"][kind]
        if set(executed) != set(selected) or any(type(executed[key]) is not int or executed[key] <= 0 for key in selected):
            raise ValueError(f"Selected {kind} checks lack nonzero execution evidence")


def android_groups(filters):
    grouped = {}
    for value in validate(items(filters), ANDROID_RE, "Android module=class#method"):
        match = ANDROID_RE.fullmatch(value)
        module, classname, method = match.groups()
        module = module or "app"
        if not classname.startswith("io.toolbox.host." if module == "app" else "io.toolbox.tool.runtime."):
            raise ValueError(f"Android test class does not belong to {module}: {classname}")
        grouped.setdefault(module, []).append((classname, method))
    return grouped


def android_compile(filters):
    modules = android_groups(filters)
    if not modules:
        raise ValueError("No Android test filter selected")
    subprocess.run(["./gradlew", "--no-daemon", *(f":{module}:compileDebugAndroidTestKotlin" for module in modules)], check=True)


def android_run(filters):
    modules = android_groups(filters)
    if not modules:
        raise ValueError("No Android test filter selected")
    for module, selected in modules.items():
        classes = ",".join(name + ("#" + method if method else "") for name, method in selected)
        subprocess.run(["./gradlew", "--no-daemon", f":{module}:connectedDebugAndroidTest",
                        f"-Pandroid.testInstrumentationRunnerArguments.class={classes}"], check=True)


def run_unit(filters):
    grouped = {}
    for value in validate(items(filters), UNIT_RE, "unit class#method"):
        match = UNIT_RE.fullmatch(value)
        module, name, method = match.groups()
        grouped.setdefault(module, []).append((name, method))
    if not grouped:
        return
    executed = {}
    for module, selected in grouped.items():
        command = ["./gradlew", "--no-daemon", f":{module}:testDebugUnitTest"]
        for name, method in selected:
            command.extend(["--tests", name + ("." + method if method else "")])
        subprocess.run(command, check=True)
        cases = testcases(Path(module) / "build/test-results/testDebugUnitTest")
        for name, method in selected:
            count = sum(case.get("classname") == name and (method is None or case.get("name") == method)
                        for case in cases)
            if not count:
                raise ValueError(f"No executed unit test matched {module}={name}{'#' + method if method else ''}")
            executed[f"{module}={name}{'#' + method if method else ''}"] = count
    record_execution("unit", executed)


def run_node(paths):
    selected = validate(items(paths), NODE_RE, "Node test path")
    executed = {}
    for path in selected:
        result = subprocess.run(["node", "--test", path], check=True, text=True, capture_output=True)
        sys.stdout.write(result.stdout)
        sys.stderr.write(result.stderr)
        counts = re.findall(r"^# pass (\d+)\s*$", result.stdout, re.MULTILINE)
        if not counts or int(counts[-1]) == 0:
            raise ValueError(f"No Node tests executed in {path}")
        executed[path] = int(counts[-1])
    record_execution("node", executed)


def testcases(folder):
    return [case for path in folder.rglob("TEST-*.xml") for case in ET.parse(path).iter("testcase")
            if not any(child.tag in {"skipped", "failure", "error"} for child in case)]


def android_results(filters):
    android_groups(filters)
    executed, reports = {}, {}
    for value in items(filters):
        module, classname, method = ANDROID_RE.fullmatch(value).groups()
        module = module or "app"
        if module not in reports:
            reports[module] = testcases(Path(module) / "build/outputs/androidTest-results/connected")
        cases = reports[module]
        count = sum(case.get("classname") == classname and (not method or case.get("name") == method) for case in cases)
        if not count:
            raise ValueError(f"No executed Android test matched {value}")
        executed[value] = count
    record_execution("android", executed)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["plan", "unit", "node", "android-compile", "android-run", "android-results", "evidence-verify"])
    parser.add_argument("--filters", default="")
    args = parser.parse_args()
    {"plan": plan, "unit": lambda: run_unit(args.filters), "node": lambda: run_node(args.filters),
     "android-compile": lambda: android_compile(args.filters), "android-run": lambda: android_run(args.filters),
     "android-results": lambda: android_results(args.filters), "evidence-verify": verify_evidence}[args.action]()
