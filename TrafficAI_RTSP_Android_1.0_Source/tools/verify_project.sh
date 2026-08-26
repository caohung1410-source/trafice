#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"

required=(
  "$project_root/settings.gradle"
  "$project_root/app/build.gradle"
  "$project_root/app/src/main/AndroidManifest.xml"
  "$project_root/app/src/main/res/layout/activity_main.xml"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/MainActivity.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/CameraRotationPolicy.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/CameraProfileStore.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/MacAddressPolicy.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/MacCameraLocator.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/TrafficMapIconFactory.java"
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
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/SignDecisionPolicy.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/RecognitionReliability.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/SignTrackMath.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/SpeedSignPolicy.java"
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/LanePreference.java"
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

# Bản 2.5.1 được phép lưu credential nhưng bắt buộc là ciphertext qua Android Keystore.
if command -v rg >/dev/null 2>&1; then
  if rg -n 'putString\("(password|safety_code|rtsp_url|full_url)"' \
    "$project_root/app/src/main/java" >/dev/null; then
    echo "Không đạt: phát hiện credential có thể được lưu bản rõ" >&2
    exit 1
  fi
else
  if grep -REn 'putString\("(password|safety_code|rtsp_url|full_url)"' \
    "$project_root/app/src/main/java" >/dev/null; then
    echo "Không đạt: phát hiện credential có thể được lưu bản rõ" >&2
    exit 1
  fi
fi
grep -q 'AndroidKeyStore' "$project_root/app/src/main/java/vn/bachphuc/trafficai/CameraProfileStore.java"
grep -q 'AES/GCM/NoPadding' "$project_root/app/src/main/java/vn/bachphuc/trafficai/CameraProfileStore.java"

bash "$project_root/tools/run_pure_logic_test.sh"
grep -q "org.maplibre.gl:android-sdk" "$project_root/app/build.gradle"
grep -q "MAP_STYLE_URL" "$project_root/app/src/main/java/vn/bachphuc/trafficai/MainActivity.java"
grep -q "RecognizerIntent" "$project_root/app/src/main/java/vn/bachphuc/trafficai/MainActivity.java"
grep -q "overpass-api" "$project_root/app/src/main/res/layout/activity_main.xml"
grep -q "overpass.kumi.systems" "$project_root/app/src/main/java/vn/bachphuc/trafficai/NavigationDataService.java"
grep -q "overpass.private.coffee" "$project_root/app/src/main/java/vn/bachphuc/trafficai/NavigationDataService.java"
grep -q "roundForPublicMapQuery" "$project_root/app/src/main/java/vn/bachphuc/trafficai/NavigationDataService.java"
grep -q "BIỂN RAW" "$project_root/app/src/main/java/vn/bachphuc/trafficai/AiCoordinator.java"
grep -q "signTrackId" "$project_root/app/src/main/java/vn/bachphuc/trafficai/AiResult.java"
grep -q 'android:id="@+id/settingsPanel"' "$project_root/app/src/main/res/layout/activity_main.xml"
grep -q 'android:visibility="gone"' "$project_root/app/src/main/res/layout/activity_main.xml"
grep -q "LanePreference" "$project_root/app/src/main/java/vn/bachphuc/trafficai/AiCoordinator.java"
grep -q 'TrafficFeature.CAMERA' "$project_root/app/src/main/java/vn/bachphuc/trafficai/MainActivity.java"
grep -q 'android.permission.CAMERA' "$project_root/app/src/main/AndroidManifest.xml"
grep -q 'phoneCameraView' "$project_root/app/src/main/res/layout/activity_main.xml"
grep -q 'switchToPhoneCamera' "$project_root/app/src/main/java/vn/bachphuc/trafficai/MainActivity.java"
grep -q 'rotatePhoneCameraButton' "$project_root/app/src/main/res/layout/activity_main.xml"
grep -q 'CameraRotationPolicy.previewRotationDegrees' "$project_root/app/src/main/java/vn/bachphuc/trafficai/MainActivity.java"
grep -q 'SpeedSignPolicy' "$project_root/app/src/main/java/vn/bachphuc/trafficai/MainActivity.java"
grep -q 'visionQualityText' "$project_root/app/src/main/res/layout/activity_main.xml"
grep -q 'RecognitionReliability.shouldApplySpeedLimit' "$project_root/app/src/main/java/vn/bachphuc/trafficai/MainActivity.java"
grep -q 'highway\\"=\\"speed_camera' "$project_root/app/src/main/java/vn/bachphuc/trafficai/NavigationDataService.java"
grep -q 'quickMenuOverlay' "$project_root/app/src/main/res/layout/activity_main.xml"
grep -q 'incidentOverlay' "$project_root/app/src/main/res/layout/activity_main.xml"
grep -q 'settingsHomeList' "$project_root/app/src/main/res/layout/activity_main.xml"
grep -q 'setVoiceAlertsEnabled' "$project_root/app/src/main/java/vn/bachphuc/trafficai/MainActivity.java"
grep -q 'mapMarkerIcon' "$project_root/app/src/main/java/vn/bachphuc/trafficai/MainActivity.java"
grep -q 'macConnectButton' "$project_root/app/src/main/res/layout/activity_main.xml"
grep -q 'NEARBY_WIFI_DEVICES' "$project_root/app/src/main/AndroidManifest.xml"
echo "verify_project: PASS • TrafficAI 2.5.1 • Precision • Icon map • Camera Lock"
