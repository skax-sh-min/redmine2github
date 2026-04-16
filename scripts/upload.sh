#!/usr/bin/env bash
# ============================================================
# upload.sh — Phase 2: 로컬 파일을 GitHub에 업로드
#
# 사용법:
#   ./scripts/upload.sh [옵션...]
#
# 예시:
#   ./scripts/upload.sh                          # 전체 업로드
#   ./scripts/upload.sh --only wiki              # Wiki만
#   ./scripts/upload.sh --only issues            # 일감만
#   ./scripts/upload.sh --only time-entries      # 작업 내역만
#   ./scripts/upload.sh --resume                 # 이전 중단 지점부터 재개
#   ./scripts/upload.sh --retry-failed           # 실패 항목 재처리
#   ./scripts/upload.sh --all                    # output/ 하위 모든 프로젝트 업로드
#   ./scripts/upload.sh --all --only wiki        # 모든 프로젝트, Wiki만
#   ./scripts/upload.sh --all --skip foo,bar     # 일부 프로젝트 제외
#
# 필요 환경 변수:
#   GITHUB_TOKEN, GITHUB_REPO
#
# 사전 조건:
#   fetch.sh를 실행하여 output/ 파일이 준비되어 있어야 합니다.
# ============================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="${SCRIPT_DIR}/../build/libs/redmine2github.jar"

# ── .env 로드 (OS 환경 변수가 우선) ──────────────────────────
ENV_FILE="${SCRIPT_DIR}/../.env"
if [ -f "$ENV_FILE" ]; then
    while IFS= read -r line || [ -n "$line" ]; do
        [[ "$line" =~ ^[[:space:]]*# ]] && continue
        [[ -z "${line//[[:space:]]/}" ]] && continue
        key="${line%%=*}"
        val="${line#*=}"
        key="${key#"${key%%[![:space:]]*}"}"
        key="${key%"${key##*[![:space:]]}"}"
        [ -z "$key" ] && continue
        [ -z "${!key+set}" ] && export "$key=$val"
    done < "$ENV_FILE"
fi

# ── JAR 존재 확인 ─────────────────────────────────────────────
if [ ! -f "$JAR" ]; then
    echo "[오류] JAR 파일이 없습니다: $JAR"
    echo "  먼저 빌드하세요: ./gradlew shadowJar"
    exit 1
fi

# ── Java 버전 확인 (21 이상) ───────────────────────────────────
if ! command -v java &>/dev/null; then
    echo "[오류] java 명령어를 찾을 수 없습니다. JDK 21 이상을 설치하세요."
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
if [ "${JAVA_VERSION:-0}" -lt 21 ] 2>/dev/null; then
    echo "[경고] Java ${JAVA_VERSION} 감지됨. Java 21 이상을 권장합니다."
fi

# ── 환경 변수 확인 ────────────────────────────────────────────
: "${GITHUB_TOKEN:?'GITHUB_TOKEN 환경 변수가 설정되지 않았습니다.'}"
: "${GITHUB_REPO:?'GITHUB_REPO 환경 변수가 설정되지 않았습니다.'}"

# ── output/ 디렉터리 존재 확인 ────────────────────────────────
OUTPUT="${OUTPUT_DIR:-./output}"
if [ ! -d "$OUTPUT" ]; then
    echo "[오류] output 디렉터리가 없습니다: $OUTPUT"
    echo "  먼저 fetch를 실행하세요: ./scripts/fetch.sh"
    exit 1
fi

# ── 실행 ─────────────────────────────────────────────────────
echo "============================================"
echo " [Phase 2] 로컬 → GitHub 업로드 시작"
echo " 대상 GitHub: ${GITHUB_REPO}"
echo " 업로드 방식: ${GITHUB_UPLOAD_METHOD:-API}"
echo "============================================"

exec java -jar "$JAR" upload "$@"
