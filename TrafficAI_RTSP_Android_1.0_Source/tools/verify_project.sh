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
  "$project_root/app/src/main/java/vn/bachphuc/trafficai/YoloDetector.java"
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
echo "verify_project: PASS • 82 labels • XML OK • credential persistence check OK"
