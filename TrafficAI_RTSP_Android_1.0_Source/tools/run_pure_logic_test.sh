#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT

sources=(
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/TrafficState.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/RtspUrlBuilder.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/CountdownTracker.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/RoadGeometryPrior.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/GeoMath.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/RoutePlan.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/NavigationInstruction.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/NavigationSession.java"
  "$project_root/tools/PureLogicSelfTest.java"
)

if command -v javac >/dev/null 2>&1; then
  javac -encoding UTF-8 -d "$test_dir" "${sources[@]}"
else
  java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -d "$test_dir" "${sources[@]}"
fi

java -cp "$test_dir" PureLogicSelfTest
