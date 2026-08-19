#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"

required=(
  "$project_root/settings.gradle"
  "$project_root/app/build.gradle"
  "$project_root/app/src/main/AndroidManifest.xml"
  "$project_root/app/src/main/res/layout/activity_main.xml"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/MainActivity.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/AiCoordinator.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/RoadGeometryPrior.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/TemporalObjectTracker.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/ForwardHazardAnalyzer.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/YoloDetector.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/CarTelemetryStore.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/TrafficCarAppService.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/OfflineGpsView.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/GeoMath.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/LandmarkHint.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/LandmarkMemoryStore.java"
  "$project_root/app/src/main/res/xml/automotive_app_desc.xml"
  "$project_root/app/src/main/assets/sign_labels_vi.txt"
)

for file in "${required[@]}"; do
  test -s "$file"
done

label_count="$(awk 'NF {count++} END {print count+0}' "$project_root/app/src/main/assets/sign_labels_vi.txt")"
test "$label_count" = "82"

if command -v xmllint >/dev/null 2>&1; then
  while IFS= read -r -d '' xml; do
    xmllint --noout "$xml"
  done < <(find "$project_root/app/src/main" -name '*.xml' -print0)
fi

if rg -n 'SharedPreferences|putString\([^,]*(password|pass|rtsp)' \
  "$project_root/app/src/main/java" >/dev/null; then
  echo "Không đạt: phát hiện khả năng lưu credential" >&2
  exit 1
fi

bash "$project_root/tools/run_pure_logic_test.sh"
rg -q "org.maplibre.gl:android-sdk" "$project_root/app/build.gradle"
rg -q "MAP_STYLE_URL" "$project_root/app/src/main/java/vn/bachphuc/trafficai/MainActivity.java"
echo "verify_project: PASS • TrafficAI 2.1 MapLibre/offline/landmark memory • 82 labels • Android Auto • XML/credential OK"
