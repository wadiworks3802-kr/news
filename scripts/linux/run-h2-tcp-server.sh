#!/usr/bin/env bash
# Rocky Linux 서버에서 H2 TCP 서버를 foreground로 실행한다. (systemd용)
#
# 작성자 : 안태욱
# 현재날짜 : 2026년 02월 20일

set -euo pipefail

PROJECT_DIR="${1:-/home/was/gnd-news}"
H2_PORT="${2:-9092}"
H2_BASE_DIR="${3:-${PROJECT_DIR}}"

GRADLE_H2_ROOT="${HOME}/.gradle/caches/modules-2/files-2.1/com.h2database/h2"
if [[ ! -d "${GRADLE_H2_ROOT}" ]]; then
  echo "[gnd-h2] H2 jar cache not found: ${GRADLE_H2_ROOT}" >&2
  echo "[gnd-h2] Run Gradle build once before starting gnd-h2 service." >&2
  exit 1
fi

H2_JAR="$(find "${GRADLE_H2_ROOT}" -type f -name 'h2-*.jar' | sort | tail -n 1)"
if [[ -z "${H2_JAR}" ]]; then
  echo "[gnd-h2] H2 jar not found under ${GRADLE_H2_ROOT}" >&2
  exit 1
fi

mkdir -p "${PROJECT_DIR}/.data"

exec /usr/bin/java \
  -cp "${H2_JAR}" \
  org.h2.tools.Server \
  -tcp \
  -tcpPort "${H2_PORT}" \
  -tcpAllowOthers \
  -ifNotExists \
  -baseDir "${H2_BASE_DIR}"
