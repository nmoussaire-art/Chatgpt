#!/usr/bin/env python3
"""Static production-boundary checks that do not require the Android SDK."""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN_ROOTS = sorted(ROOT.glob("*/src/main"))
TEXT_EXTENSIONS = {".kt", ".java", ".xml", ".kts"}

if not MAIN_ROOTS:
    raise SystemExit("No production source roots found.")

production_files = [
    path
    for source_root in MAIN_ROOTS
    for path in source_root.rglob("*")
    if path.is_file() and path.suffix in TEXT_EXTENSIONS
]
production_text = "\n".join(path.read_text(errors="ignore") for path in production_files)

errors: list[str] = []

if "android.permission.INTERNET" in production_text:
    errors.append("Production manifest requests android.permission.INTERNET.")

for token in (
    "FakeTelemetry",
    "DemoTelemetry",
    "MockBatteryTelemetry",
    "SampleBatteryRepository",
    "randomBatteryPercent",
):
    if token.lower() in production_text.lower():
        errors.append(f"Forbidden production token found: {token}")

for path in production_files:
    if re.search(r"(^|[/_.-])(fake|mock|demo|sample)([/_.-]|$)", path.as_posix(), re.I):
        errors.append(f"Suspicious production file name: {path.relative_to(ROOT)}")

binding_pattern = re.compile(
    r"bindTelemetry\s*\(\s*impl\s*:\s*AndroidBatteryTelemetrySource\s*\)\s*:\s*BatteryTelemetrySource"
)
if not binding_pattern.search(production_text):
    errors.append("Production DI does not directly bind AndroidBatteryTelemetrySource.")

app_module = ROOT / "app/src/main/java/com/batterycast/quant/di/AppModule.kt"
if app_module.exists() and re.search(r"BatteryForecastEngine\s*\([^)]*randomSeed", app_module.read_text(), re.S):
    errors.append("Production DI supplies a fixed Monte Carlo seed.")

manifest = ROOT / "app/src/main/AndroidManifest.xml"
try:
    ET.parse(manifest)
except (ET.ParseError, OSError) as exc:
    errors.append(f"AndroidManifest.xml is invalid XML: {exc}")

for xml_path in (ROOT / "app/src/main/res").rglob("*.xml"):
    try:
        ET.parse(xml_path)
    except ET.ParseError as exc:
        errors.append(f"Invalid XML in {xml_path.relative_to(ROOT)}: {exc}")

if errors:
    print("Production-data verification FAILED:", file=sys.stderr)
    for error in errors:
        print(f" - {error}", file=sys.stderr)
    raise SystemExit(1)

print(f"Production-data verification passed across {len(production_files)} production files.")
print("No INTERNET permission, fake telemetry binding, sample provider, or fixed production seed was found.")
