"""Capture repeated ToolBox cold starts on an attached phone. Save any open tool first."""
import argparse
import json
import re
import statistics
import subprocess
import time
from pathlib import Path

PACKAGE = "io.toolbox.host"
COMPONENT = f"{PACKAGE}/.MainActivity"


def duration_ms(text):
    match = re.fullmatch(r"\+?(?:(\d+)s)?(\d+)ms", text)
    if match:
        return 1000 * int(match[1] or 0) + int(match[2])
    seconds = re.fullmatch(r"\+?(\d+)s", text)
    return 1000 * int(seconds[1]) if seconds else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", help="ADB serial when multiple devices are attached")
    parser.add_argument("--rounds", type=int, default=20)
    parser.add_argument("--cold", required=True, action="store_true",
                        help="Force-stop ToolBox before each launch. Installed tools and data are retained.")
    parser.add_argument("--memory", action="store_true",
                        help="Also sample the host process PSS after each home launch.")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.rounds < 1:
        parser.error("--rounds must be positive")
    if args.output.exists():
        parser.error("Choose a new output file for this run")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    command = [args.adb] + (["-s", args.serial] if args.serial else [])

    def adb(*words):
        return subprocess.check_output([*command, *words], text=True, encoding="utf-8",
                                       errors="replace", timeout=45).strip()

    if adb("get-state") != "device":
        parser.error("Connect and authorize one Android device")
    installed = adb("shell", "dumpsys", "package", PACKAGE)
    version = re.search(r"\bversionName=(\S+)", installed)
    if not version:
        parser.error(f"{PACKAGE} is not installed")
    report = {"model": adb("shell", "getprop", "ro.product.model"),
              "android": adb("shell", "getprop", "ro.build.version.release"),
              "size": adb("shell", "wm", "size"), "hostVersion": version[1],
              "webview": next((line.strip() for line in adb("shell", "dumpsys", "webviewupdate").splitlines()
                               if "Current WebView package" in line), None), "samples": []}
    component_pattern = re.escape(PACKAGE) + r"/(?:io\.toolbox\.host\.)?\.?MainActivity"
    for index in range(args.rounds):
        adb("shell", "am", "force-stop", PACKAGE)
        # Bound the log scan to this launch without clearing the phone's log buffers.
        since = float(adb("shell", "date", "+%s.%N"))
        launched = adb("shell", "am", "start", "-W", "-n", COMPONENT,
                       "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER")
        first = re.search(r"^TotalTime:\s*(\d+)", launched, re.M)
        if "Status: ok" not in launched or not first:
            raise RuntimeError(f"Cold launch failed: {launched}")
        drawn = None
        until = time.monotonic() + 10
        while time.monotonic() < until:
            logs = adb("logcat", "-d", "-v", "epoch", "-t", "500", "ActivityTaskManager:I", "ActivityManager:I", "*:S")
            lines = "\n".join(line for line in logs.splitlines()
                              if (stamp := re.match(r"^(\d+\.\d+) ", line)) and float(stamp[1]) >= since)
            marker = re.search(r"Fully drawn " + component_pattern + r":\s*(\+\S+)", lines)
            if marker:
                drawn = duration_ms(marker[1])
                break
            time.sleep(0.25)
        report["samples"].append({"launchTotalMs": int(first[1]), "homeFullyDrawnMs": drawn})
        if args.memory:
            memory = adb("shell", "dumpsys", "meminfo", PACKAGE)
            pss = re.search(r"TOTAL PSS:\s*([0-9,]+)", memory)
            report["samples"][-1]["hostHomePssKiB"] = int(pss[1].replace(",", "")) if pss else None
        print(f"{index + 1}/{args.rounds} launch {first[1]} ms, fully drawn {drawn} ms", flush=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    usable = [row["homeFullyDrawnMs"] for row in report["samples"] if row["homeFullyDrawnMs"] is not None]
    if len(usable) == args.rounds:
        print(f"Median fully drawn home: {statistics.median(usable):g} ms")
    else:
        print(f"Fully drawn measurements available for {len(usable)}/{args.rounds} runs.")


if __name__ == "__main__":
    main()
