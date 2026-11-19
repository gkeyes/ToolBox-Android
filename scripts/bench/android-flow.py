"""Collect Android-side evidence for one explicit ToolBox workload."""
import argparse
import json
import re
import subprocess
import time
from pathlib import Path

PACKAGE = "io.toolbox.host"


def proc_stat(text):
    # comm is parenthesized and may include spaces and right parentheses.
    end = text.rfind(")")
    if end < 0:
        return None
    fields = text[end + 2:].split()
    try:
        return int(fields[19]), int(fields[11]) + int(fields[12])
    except (ValueError, IndexError):
        return None


def total_pss(text):
    match = re.search(r"TOTAL PSS:\s*([\d,]+)", text)
    return int(match[1].replace(",", "")) if match else None


def summarize(samples):
    if not samples:
        return {"samples": 0}
    times = [s["pssKiB"] for s in samples if s["pssKiB"] is not None]
    ticks = [s["cpuTicks"] for s in samples]
    identities = {s["startTicks"] for s in samples}
    stable = len(samples) >= 2 and len(identities) == 1 and None not in identities
    return {"samples": len(samples), "sampledPeakPssKiB": max(times) if times else None,
            "pssSamples": len(times), "stableIdentity": stable,
            "cpuTicks": ticks[-1] - ticks[0] if stable and None not in ticks else None}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario", choices=["read", "sync", "background"])
    parser.add_argument("--seconds", type=int, required=True)
    parser.add_argument("--interval", type=float, default=1.0)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    parser.add_argument("--renderer-pid", type=int, action="append", default=[],
                        help="WebView renderer PID independently attributed to this tool; repeat for more than one")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.seconds < 1 or args.interval < 0.25 or any(pid < 1 for pid in args.renderer_pid):
        parser.error("Provide a positive duration, interval >= 0.25 s and positive renderer PIDs")
    prefix = [args.adb] + (["-s", args.serial] if args.serial else [])

    def adb(*words, required=True):
        result = subprocess.run(prefix + list(words), capture_output=True, text=True, timeout=35)
        if required and result.returncode:
            raise RuntimeError(result.stderr.strip() or result.stdout.strip() or "adb failed")
        return result.stdout.strip() if result.returncode == 0 else ""

    if adb("get-state") != "device":
        raise RuntimeError("Connect one authorized device")
    args.output.mkdir(parents=True, exist_ok=False)
    installed = adb("shell", "dumpsys", "package", PACKAGE)
    version = re.search(r"\bversionName=(\S+)", installed)
    if not version:
        raise RuntimeError("ToolBox is not installed")

    def checkpoint(label):
        power = adb("shell", "dumpsys", "power")
        facts = [line.strip() for line in power.splitlines() if line.strip().startswith("mWakefulness=")]
        (args.output / f"power-{label}.txt").write_text("\n".join(facts))
        for service, words in {"battery": ["batterystats", "--charged", PACKAGE],
                               "frames": ["gfxinfo", PACKAGE, "framestats"]}.items():
            (args.output / f"{service}-{label}.txt").write_text(adb("shell", "dumpsys", *words))

    report = {"scenario": args.scenario, "seconds": args.seconds, "version": version[1],
              "model": adb("shell", "getprop", "ro.product.model"),
              "android": adb("shell", "getprop", "ro.build.version.release"),
              "webview": next((s.strip() for s in adb("shell", "dumpsys", "webviewupdate").splitlines()
                               if "Current WebView package" in s), None),
              "startEpoch": time.time(), "size": adb("shell", "wm", "size"),
              "rendererPids": args.renderer_pid}
    checkpoint("before")
    if args.scenario == "background":
        # No polling, wake locks, simulated Doze or changes to background policy.
        print("Recording quiet interval. The phone can remain asleep.", flush=True)
        time.sleep(args.seconds)
    else:
        pids = adb("shell", "pidof", PACKAGE).split()
        if len(pids) != 1:
            raise RuntimeError("Open the ToolBox flow and leave its host process running")
        processes = [int(pids[0]), *args.renderer_pid]
        if len(processes) != len(set(processes)):
            raise RuntimeError("Duplicate process ID")
        report["hostPid"] = processes[0]
        tick_rate = adb("shell", "getconf", "CLK_TCK", required=False)
        report["ticksPerSecond"] = int(tick_rate) if tick_rate.isdigit() else None
        samples = {pid: [] for pid in processes}
        totals = []
        start = time.monotonic()
        with (args.output / "samples.jsonl").open("x") as stream:
            print("Run the selected workload now.", flush=True)
            while True:
                batch = []
                for pid in processes:
                    stat = proc_stat(adb("shell", "cat", f"/proc/{pid}/stat", required=False))
                    memory = total_pss(adb("shell", "dumpsys", "meminfo", str(pid), required=False))
                    row = {"timeSeconds": time.monotonic()-start, "pid": pid, "pssKiB": memory,
                           "startTicks": stat[0] if stat else None, "cpuTicks": stat[1] if stat else None}
                    samples[pid].append(row)
                    batch.append(memory)
                    stream.write(json.dumps(row) + "\n")
                stream.flush()
                if None not in batch:
                    totals.append(sum(batch))
                remaining = args.seconds - (time.monotonic()-start)
                if remaining <= 0:
                    break
                time.sleep(min(args.interval, remaining))
        report["processes"] = {pid: summarize(rows) for pid, rows in samples.items()}
        end_pids = adb("shell", "pidof", PACKAGE, required=False).split()
        report["endHostPids"] = end_pids
        stable = end_pids == [str(processes[0])] and all(
            metrics["stableIdentity"] for metrics in report["processes"].values()
        )
        report["sampledPeakSelectedPssKiB"] = max(totals) if stable and totals else None
        report["includesSpecifiedRenderers"] = bool(args.renderer_pid)
        report["allSelectedPssSamplesComplete"] = stable and all(
            metrics["pssSamples"] == metrics["samples"] for metrics in report["processes"].values()
        )
    checkpoint("after")
    report["endEpoch"] = time.time()
    (args.output / "summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(args.output / "summary.json")


if __name__ == "__main__":
    main()
