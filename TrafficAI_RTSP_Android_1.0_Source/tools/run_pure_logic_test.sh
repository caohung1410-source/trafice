#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT

sources=(
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/CameraRotationPolicy.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/AlertAudioMode.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/DistanceWarningState.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/DistanceWarningPolicy.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/LeadVehicleDistanceEstimator.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/TrafficState.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/RtspUrlBuilder.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/MacAddressPolicy.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/CountdownTracker.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/RoadGeometryPrior.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/SignDecisionPolicy.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/RecognitionReliability.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/SignTrackMath.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/SpeedSignPolicy.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/LanePreference.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/GeoMath.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/EarlySignalAlertPolicy.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/LandmarkHint.java"
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
