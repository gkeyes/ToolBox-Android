#!/usr/bin/env python3
"""Cold-start the actual signed APK on the CI emulator; never targets hardware.

This verifies startup/initial home only, not full minified runtime behavior or visuals.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


def parse_hierarchy(ui):
    try:
        document = ET.fromstring(ui.strip())
    except ET.ParseError as failure:
        raise ValueError("Android did not provide a valid UI hierarchy") from failure
    if document.tag != "hierarchy":
        raise ValueError("Android did not provide a UI hierarchy document")
    return document


def capture_ui_hierarchy(adb, evidence):
    remote = "/sdcard/toolbox-startup.xml"
    destination = evidence / "home.xml"
    # UiAutomator can return exit 0 without producing a file while accessibility
    # attaches to a freshly started app. Retry collection, never its assertions.
    for attempt in range(1, 4):
        destination.unlink(missing_ok=True)
        adb("shell", "rm", "-f", remote)
        try:
            adb("shell", "uiautomator", "dump", "--compressed", remote, record=f"ui-dump-{attempt}.txt")
            # exec-out does not reliably propagate a remote cat failure. Pull must
            # actually succeed and supply a parseable, fresh local document.
            adb("pull", remote, str(destination), record=f"ui-pull-{attempt}.txt")
            ui = destination.read_text()
            parse_hierarchy(ui)
            return ui
        except (subprocess.SubprocessError, OSError, ValueError) as failure:
            (evidence / f"ui-collection-error-{attempt}.txt").write_text(str(failure) + "\n")
            if attempt < 3:
                time.sleep(.5)
    raise RuntimeError("Could not collect the initial home UI; see ui-dump/ui-pull evidence")


def validate_startup(package, version, code, start, installed, logs, ui, pid):
    component = rf"{re.escape(package)}/(?:{re.escape(package)})?\.MainActivity"
    if not re.search(r"^Status: ok\s*$", start, re.M) or not re.search(component, start):
        raise ValueError("MainActivity launch did not succeed")
    if not re.search(rf"\bversionName={re.escape(version)}\s", installed):
        raise ValueError("Installed version name differs from the APK")
    if not re.search(rf"\bversionCode={re.escape(code)}\b", installed):
        raise ValueError("Installed version code differs from the APK")
    if not re.search(rf"Fully drawn {component}:", logs):
        raise ValueError("Host did not report a fully drawn home")
    if re.search(rf"(?:Process: |Cmdline: |ANR in ){re.escape(package)}(?:[,:\s]|$)", logs):
        raise ValueError("Host crash or ANR during startup")
    if not re.fullmatch(r"\d+", pid.strip()):
        raise ValueError("Host process did not remain alive")
    document = parse_hierarchy(ui)
    host_nodes = [node for node in document.iter("node") if node.get("package") == package]
    labels = {node.get(key, "") for node in host_nodes for key in ("text", "content-desc")}
    if not {"首页", "全部工具", "设置"}.issubset(labels):
        raise ValueError("Initial home navigation is missing from the rendered UI")
    if not any(node.get("selected") == "true" and any(
            child.get(key) == "首页" for child in node.iter("node")
            if child.get("package") == package for key in ("text", "content-desc")
    ) for node in host_nodes):
        raise ValueError("The initial home tab is not selected")
    # This job installs on a fresh emulator: content must be ready, not just its navigation shell.
    if not {"还没有工具", "安装四个范例", "导入 .tbx"}.issubset(labels):
        raise ValueError("The fresh-install home content did not load")
    if any("无法读取" in label for label in labels):
        raise ValueError("The initial home reports a loading failure")


def main():
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise RuntimeError("This smoke test runs only in GitHub Actions")
    evidence = ROOT / "build/release-evidence/startup"
    evidence.mkdir(parents=True, exist_ok=True)

    def adb(*args, record=None, timeout=60):
        completed = subprocess.run(["adb", "-e", *args], check=False, text=True,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
        if record:
            (evidence / record).write_text(completed.stdout)
        completed.check_returncode()
        return completed.stdout

    if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise RuntimeError("An emulator is required; physical devices are excluded")
    gradle = (ROOT / "app/build.gradle.kts").read_text()
    package = re.search(r'applicationId\s*=\s*"([^"]+)"', gradle)[1]
    version = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)[1]
    code = re.search(r'versionCode\s*=\s*(\d+)', gradle)[1]
    apk = ROOT / "app/build/outputs/apk/release/app-release.apk"
    result = {"status": "FAILED", "scope": "cold_start_initial_home",
              "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
              "application_id": package, "version_name": version, "version_code": code}
    try:
        result["android_version"] = adb("shell", "getprop", "ro.build.version.release").strip()
        adb("shell", "dumpsys", "webviewupdate", record="webview-provider.txt")
        adb("install", "-r", str(apk), record="install.txt")
        installed = adb("shell", "dumpsys", "package", package, record="installed-package.txt")
        adb("shell", "svc", "power", "stayon", "true")
        adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        adb("shell", "wm", "dismiss-keyguard")
        adb("shell", "am", "force-stop", package)
        adb("logcat", "-b", "all", "-c")
        start = adb("shell", "am", "start", "-W", "-n", f"{package}/.MainActivity",
                    "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", record="launch.txt")
        # A CI observation deadline, not a product startup or workload limit.
        deadline = time.monotonic() + 60
        logs = ""
        while time.monotonic() < deadline:
            logs = adb("logcat", "-b", "main", "-b", "system", "-b", "crash", "-d")
            if f"Fully drawn {package}/" in logs:
                break
            time.sleep(.5)
        ui = capture_ui_hierarchy(adb, evidence)
        logs = adb("logcat", "-b", "main", "-b", "system", "-b", "crash", "-d", record="startup-logcat.txt")
        pid = adb("shell", "pidof", package, record="host-pid.txt")
        validate_startup(package, version, code, start, installed, logs, ui, pid)
        result["status"] = "PASS"
    finally:
        (evidence / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        adb("logcat", "-b", "main", "-b", "system", "-b", "crash", "-d", record="startup-logcat.txt")
        adb("logcat", "-b", "crash", "-d", record="crash-buffer.txt")
        adb("shell", "rm", "-f", "/sdcard/toolbox-startup.xml")
    print(f"Signed APK cold-start/initial-home smoke: {result['status']} ({version}/{code})")


if __name__ == "__main__":
    main()
