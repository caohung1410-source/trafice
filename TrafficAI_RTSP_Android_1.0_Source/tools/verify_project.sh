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
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/RoutePlan.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/NavigationInstruction.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/NavigationSession.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/NavigationDataService.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/MapFeatureStore.java"
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

credential_match=false
if command -v rg >/dev/null 2>&1; then
  rg -n '(putString|getString)\([^,]*(password|rtsp|safety|credential)' \
    "$project_root/app/src/main/java" >/dev/null && credential_match=true
else
  grep -REn '(putString|getString)\([^,]*(password|rtsp|safety|credential)' \
    "$project_root/app/src/main/java" >/dev/null && credential_match=true
fi
if "$credential_match"; then
  echo "Không đạt: phát hiện khả năng lưu credential" >&2
  exit 1
fi

bash "$project_root/tools/run_pure_logic_test.sh"
grep -q "org.maplibre.gl:android-sdk" "$project_root/app/build.gradle"
grep -q "MAP_STYLE_URL" "$project_root/app/src/main/java/vn/bachphuc/trafficai/MainActivity.java"
grep -q "RecognizerIntent" "$project_root/app/src/main/java/vn/bachphuc/trafficai/MainActivity.java"
grep -q "overpass-api" "$project_root/app/src/main/res/layout/activity_main.xml"
grep -q "overpass.kumi.systems" "$project_root/app/src/main/java/vn/bachphuc/trafficai/NavigationDataService.java"
grep -q "overpass.private.coffee" "$project_root/app/src/main/java/vn/bachphuc/trafficai/NavigationDataService.java"
grep -q "roundForPublicMapQuery" "$project_root/app/src/main/java/vn/bachphuc/trafficai/NavigationDataService.java"
echo "verify_project: PASS • TrafficAI 2.2.1 Overpass fallback/privacy fix • 82 labels • Android Auto • XML/credential OK"
