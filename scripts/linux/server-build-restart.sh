#!/usr/bin/env bash
# Rocky Linux 서버에서 빌드 후 서비스 재시작 스크립트.
#
# 작성자 : 안태욱
# 현재날짜 : 2026년 02월 20일

set -euo pipefail

PROJECT_DIR="${1:-/home/was/gnd-news}"

cd "${PROJECT_DIR}"
chmod -R u+rwX,go+rX "${PROJECT_DIR}"
chmod +x "${PROJECT_DIR}/gradlew"

./gradlew :api:bootJar :web:bootJar :batch:bootJar -x test --no-daemon

if sudo systemctl list-unit-files | grep -q '^gnd-h2\.service'; then
  sudo systemctl restart gnd-h2
fi

sudo systemctl restart gnd-api gnd-web gnd-batch

if sudo systemctl list-unit-files | grep -q '^gnd-h2\.service'; then
  sudo systemctl --no-pager --full status gnd-h2 gnd-api gnd-web gnd-batch
else
  sudo systemctl --no-pager --full status gnd-api gnd-web gnd-batch
fi
