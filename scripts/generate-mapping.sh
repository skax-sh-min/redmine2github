#!/usr/bin/env bash
# ============================================================
# generate-mapping.sh — user-mapping.yml 초안 생성
#
# 사용법:
#   ./scripts/generate-mapping.sh
#   ./scripts/generate-mapping.sh --output my-mapping.yml
#
# 필요 환경 변수:
#   REDMINE_URL
#   REDMINE_API_KEY 또는 REDMINE_USERNAME + REDMINE_PASSWORD
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

# ── Java 확인 ─────────────────────────────────────────────────
if ! command -v java &>/dev/null; then
    echo "[오류] java 명령어를 찾을 수 없습니다. JDK 21 이상을 설치하세요."
    exit 1
fi

# ── 환경 변수 확인 ────────────────────────────────────────────
: "${REDMINE_URL:?'[오류] REDMINE_URL 환경 변수가 설정되지 않았습니다.'}"

# ── 실행 ─────────────────────────────────────────────────────
echo "============================================"
echo " 사용자 매핑 초안 생성"
echo " Redmine : ${REDMINE_URL}"
echo "============================================"

exec java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
     -jar "$JAR" generate-mapping "$@"
