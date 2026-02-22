#!/usr/bin/env bash
# Rocky Linux 서버에서 zip 압축 해제 + 빌드/재기동을 수행하는 스크립트.
#
# 작성자 : 안태욱
# 현재날짜 : 2026년 02월 20일

set -euo pipefail

ZIP_PATH="${1:-/home/was/gnd-news-src.zip}"
TARGET_DIR="${2:-/home/was/gnd-news}"

mkdir -p "${TARGET_DIR}"
unzip -o "${ZIP_PATH}" -d "${TARGET_DIR}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
"${SCRIPT_DIR}/server-build-restart.sh" "${TARGET_DIR}"

