#!/usr/bin/env bash
# ============================================================
# migrate.sh — fetch + upload 전체 파이프라인 실행
#
# 사용법:
#   ./scripts/migrate.sh [옵션...]
#
# 예시:
#   ./scripts/migrate.sh                          # 전체 마이그레이션
#   ./scripts/migrate.sh --only wiki              # Wiki만
#   ./scripts/migrate.sh --resume                 # 이전 중단 지점부터 재개
#   ./scripts/migrate.sh --retry-failed           # 실패 항목 재처리
#
# 단계별 실행이 필요하면 fetch.sh / upload.sh를 개별 실행하세요.
#
# 필요 환경 변수:
#   REDMINE_URL, REDMINE_PROJECT
#   REDMINE_API_KEY 또는 REDMINE_USERNAME + REDMINE_PASSWORD
#   GITHUB_TOKEN, GITHUB_REPO
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
: "${REDMINE_PROJECT:?'REDMINE_PROJECT 환경 변수가 설정되지 않았습니다.'}"
: "${GITHUB_TOKEN:?'GITHUB_TOKEN 환경 변수가 설정되지 않았습니다.'}"
: "${GITHUB_REPO:?'GITHUB_REPO 환경 변수가 설정되지 않았습니다.'}"

# ── 실행 ─────────────────────────────────────────────────────
echo "============================================"
echo " Redmine → GitHub 전체 마이그레이션 시작"
echo " 대상 Redmine: ${REDMINE_URL}/projects/${REDMINE_PROJECT}"
echo " 대상 GitHub : ${GITHUB_REPO}"
echo "============================================"

exec java -jar "$JAR" migrate "$@"
