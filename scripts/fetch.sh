#!/usr/bin/env bash
# ============================================================
# fetch.sh — Phase 1: Redmine 데이터를 수집하여 로컬에 저장
#
# 사용법:
#   ./scripts/fetch.sh [옵션...]
#
# 단일 프로젝트:
#   ./scripts/fetch.sh                          # REDMINE_PROJECT 환경 변수 사용
#   ./scripts/fetch.sh --project my-project     # 직접 지정
#   ./scripts/fetch.sh --only wiki              # Wiki만
#   ./scripts/fetch.sh --resume                 # 이전 중단 지점부터 재개
#
# 전체 프로젝트:
#   ./scripts/fetch.sh --all                    # 모든 프로젝트 수집
#   ./scripts/fetch.sh --all --only wiki        # 모든 프로젝트, Wiki만
#   ./scripts/fetch.sh --all --skip foo,bar     # 일부 제외
#   ./scripts/fetch-all.sh                      # 위와 동일한 별칭
#
# 필요 환경 변수:
#   REDMINE_URL (필수)
#   REDMINE_API_KEY 또는 REDMINE_USERNAME + REDMINE_PASSWORD (필수)
#   REDMINE_PROJECT (단일 프로젝트 시 필요; --all 또는 --project 사용 시 불필요)
#   REDMINE_PROJECTS=a,b,c (여러 특정 프로젝트 지정 시 사용)
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
: "${REDMINE_URL:?'REDMINE_URL 환경 변수가 설정되지 않았습니다.'}"
# REDMINE_PROJECT: --all 또는 --project 사용 시 불필요 (CLI에서 검증)

# ── 로그 파일 설정 ───────────────────────────────────────────
LOG="${SCRIPT_DIR}/../migration.log"

# ── 실행 ─────────────────────────────────────────────────────
echo "============================================"
echo " [Phase 1] Redmine -> 로컬 수집 시작"
echo " Redmine URL: ${REDMINE_URL}"
if [ -n "${REDMINE_PROJECTS:-}" ]; then
    echo " 대상 프로젝트: ${REDMINE_PROJECTS} (REDMINE_PROJECTS)"
elif [ -n "${REDMINE_PROJECT:-}" ]; then
    echo " 대상 프로젝트: ${REDMINE_PROJECT}"
fi
echo " 출력 디렉터리: ${OUTPUT_DIR:-./output}"
echo "============================================"

{ echo ""; echo "=== [fetch] $(date '+%Y-%m-%d %H:%M:%S') ==="; } >> "$LOG"
java -jar "$JAR" fetch "$@" 2>&1 | tee -a "$LOG"
