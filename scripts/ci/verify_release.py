#!/usr/bin/env python3
"""Validate apksigner/aapt output after successful commands, then package a receipt.

Uses the original public certificate fingerprint, not an expiring Actions artifact.
Version and application ID come from the checked-out Gradle configuration.
"""

import hashlib
import os
from pathlib import Path
import re
import shutil


ROOT = Path(__file__).resolve().parents[2]
WORKER_MAPPING = "io.toolbox.host.background.ToolBoxBackgroundWorker -> io.toolbox.host.background.ToolBoxBackgroundWorker:"


def verify_release(signature: str, badging: str, gradle: str, mapping: str, expected: str) -> dict[str, str]:
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise ValueError("Original release certificate fingerprint is missing or invalid")
    if not re.search(r"^Number of signers: 1\s*$", signature, re.MULTILINE):
        raise ValueError("Release APK must have exactly one signer")
    # Android build-tools labels may say Signer #1 or V2 Signer.
    certificates = re.findall(r"^.*certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$", signature, re.MULTILINE)
    if not certificates or {cert.lower() for cert in certificates} != {expected}:
        raise ValueError("APK signing certificate differs from the original release")
    if re.search(r"^application-debuggable", badging, re.MULTILINE):
        raise ValueError("Refusing to deliver a debuggable APK as release")
    fields = dict(re.findall(r"(\w+)='([^']*)'", next((line for line in badging.splitlines() if line.startswith("package: ")), "")))
    config = {}
    for key in ("applicationId", "versionName", "versionCode"):
        pattern = rf'\b{key}\s*=\s*"([^"\n]+)"' if key != "versionCode" else r"\bversionCode\s*=\s*(\d+)"
        value = re.search(pattern, gradle)
        if not value:
            raise ValueError(f"Cannot read {key} from app/build.gradle.kts")
        config[key] = value[1]
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?", config["versionName"]):
        raise ValueError("Unsupported release version name")
    for apk_key, config_key in (("name", "applicationId"), ("versionName", "versionName"), ("versionCode", "versionCode")):
        if fields.get(apk_key) != config[config_key]:
            raise ValueError(f"APK {apk_key} does not match the checked-out build configuration")
    if WORKER_MAPPING not in mapping.splitlines():
        raise ValueError("R8 must retain the background worker class name")
    for flag in ("isMinifyEnabled", "isShrinkResources"):
        if not re.search(rf"\b{flag}\s*=\s*true\b", gradle):
            raise ValueError(f"Release optimization {flag} is disabled")
    return config


def main() -> None:
    if os.environ.get("VERIFY_JOB_RESULT") != "success":
        raise ValueError("Host verification must pass before delivery")
    evidence = ROOT / "build/release-evidence"
    expected = os.environ["TOOLBOX_RELEASE_CERT_SHA256"]
    config = verify_release(
        (evidence / "signature.txt").read_text(), (evidence / "badging.txt").read_text(),
        (ROOT / "app/build.gradle.kts").read_text(),
        (ROOT / "app/build/outputs/mapping/release/mapping.txt").read_text(), expected,
    )
    delivery = ROOT / "build/ci-delivery"
    delivery.mkdir(parents=True, exist_ok=False)
    filename = f"toolbox-v{config['versionName']}-release.apk"
    shutil.copyfile(ROOT / "app/build/outputs/apk/release/app-release.apk", delivery / filename)
    shutil.copyfile(evidence / "signature.txt", delivery / "SIGNATURE.txt")
    receipt = {
        "BUILD_COMMIT": os.environ["GITHUB_SHA"], "BUILD_REF": os.environ["GITHUB_REF"],
        "WORKFLOW_RUN": os.environ["GITHUB_RUN_ID"], "APPLICATION_ID": config["applicationId"],
        "VERSION_NAME": config["versionName"], "VERSION_CODE": config["versionCode"],
        "BUILD_TYPE": "release", "APK_DEBUGGABLE": "false", "R8_MINIFICATION": "PASS",
        "RESOURCE_SHRINKING": "ENABLED", "APK_SIGNING_CERT_SHA256": expected,
        "ORIGINAL_CERTIFICATE_MATCH": "PASS", "HOST_VERIFY_JOB": "success",
        "HOST_BACKUP_THEME_UPGRADE_API35_EMULATOR": "PASS", "PROCESS_RESTART_API35_EMULATOR": "PASS",
        "EMBEDDED_EXAMPLE_BYTES_AND_REPRODUCIBILITY": "PASS",
        "STANDALONE_TBX_VALIDATION": "SEPARATE_TBX_CI",
        "HOST_SCREENSHOT_VALIDATION": "REMOVED_BY_USER_REQUEST",
        "REAL_DEVICE": "NOT_RUN", "REAL_SERVER_LOGIN": "NOT_RUN", "MINIFIED_RUNTIME_DEVICE_TEST": "NOT_RUN",
    }
    (delivery / "BUILD_AND_TEST_RECEIPT.txt").write_text("".join(f"{key}={value}\n" for key, value in receipt.items()))
    sums = [f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in sorted(delivery.iterdir())]
    (delivery / "SHA256SUMS.txt").write_text("".join(sums))
    with Path(os.environ["GITHUB_ENV"]).open("a") as handle:
        handle.write(f"TOOLBOX_VERSION={config['versionName']}\n")
    print(f"Verified stable signed release: {filename}; certificate SHA-256: {expected}")


if __name__ == "__main__":
    main()
