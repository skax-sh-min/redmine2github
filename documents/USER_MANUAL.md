# 사용자 매뉴얼 — Redmine2Github 마이그레이션 도구

> **대상 독자**: Redmine 프로젝트를 GitHub로 이전하는 실무 담당자  
> **도구 버전**: 1.4.0  
> **최종 수정**: 2026-04-15

---

## 목차

1. [개요](#1-개요)
2. [사전 준비](#2-사전-준비)
3. [설치 및 빌드](#3-설치-및-빌드)
4. [설정 파일 구성](#4-설정-파일-구성)
5. [2단계 마이그레이션](#5-2단계-마이그레이션)
   - [Phase 1: fetch — Redmine → 로컬](#51-phase-1-fetch--redmine--로컬)
   - [Phase 2: upload — 로컬 → GitHub](#52-phase-2-upload--로컬--github)
6. [전체 프로젝트 일괄 처리 (--all)](#6-전체-프로젝트-일괄-처리---all)
7. [통합 실행 (migrate)](#7-통합-실행-migrate)
8. [CLI 커맨드 레퍼런스](#8-cli-커맨드-레퍼런스)
9. [출력 파일 구조](#9-출력-파일-구조)
10. [변환 규칙 (Textile → GFM)](#10-변환-규칙-textile--gfm)
11. [중단 및 재개](#11-중단-및-재개)
12. [진행 상태 모니터링](#12-진행-상태-모니터링)
13. [GitHub 업로드 방식 선택](#13-github-업로드-방식-선택)
14. [트러블슈팅](#14-트러블슈팅)
15. [제한 사항](#15-제한-사항)

---

## 1. 개요

**Redmine2Github**는 Redmine 프로젝트의 데이터를 GitHub로 일괄 이전하는 Java CLI 도구입니다.

### 마이그레이션 대상

| Redmine 데이터 | GitHub 대상 | 형식 |
|----------------|-------------|------|
| Wiki 페이지 (Textile) | Repository 파일 | `.md` (GFM) |
| 일감 (Issue) | Repository 파일 또는 GitHub Issue | `issues/{id}.md` + `issues.md` 인덱스 (기본) 또는 GitHub Issues API |
| 일감 댓글 / 변경 이력 | 일감 MD 파일 내 Comments 섹션 | Markdown (노트 + 필드 변경 이력) |
| 버전 (Version) | Milestone | — |
| 트래커 / 우선순위 / 상태 / 카테고리 | Label | 색상 지정 가능 |
| 첨부파일 (Wiki) | Repository 파일 | 원본 그대로 |
| 첨부파일 (Issue) | Repository 파일 | `attachments-issue/` 폴더에 저장 |
| 작업 내역 (Time Entry) | Repository 파일 | `time_entries.csv` |

### 2단계 처리 흐름

```
┌─────────────────────────────────────────────────────┐
│  Phase 1: fetch                                     │
│                                                     │
│  Redmine API                                        │
│      │  (wiki, issues, time entries, versions...)   │
│      ▼                                              │
│  Textile → GFM 변환 + 링크 재작성                    │
│      │                                              │
│      ▼                                              │
│  output/  ← 로컬 파일로 저장                              │
│      wiki/                 .md 파일                      │
│      attachments/          Wiki 첨부파일                  │
│      attachments-issue/    이슈 첨부파일                  │
│      issues-json/          Issue JSON + Label/Milestone  │
│      issues/               Issue .md 파일 (기본 활성화)  │
│      issues.md             이슈 목록 인덱스               │
│      _migration/           time_entries.csv              │
└─────────────────────────────────────────────────────┘
              ↓ 사람이 직접 검토·수정 가능 ↓
┌─────────────────────────────────────────────────────┐
│  Phase 2: upload                                    │
│                                                     │
│  output/ 파일                                       │
│      │                                              │
│      ▼                                              │
│  GitHub Repository                                        │
│      wiki/*.md             Repository 파일               │
│      issues/{id}.md        Issue MD 파일 (기본)           │
│      issues.md             이슈 목록 인덱스 (기본)         │
│      GitHub Issues         Label + Milestone + Assignee  │
│                            (REDMINE_ISSUE_MD_FETCH=false 시) │
│      _migration/           time_entries.csv              │
└─────────────────────────────────────────────────────┘
```

> **핵심 장점**: fetch 단계 후 `output/` 파일을 직접 확인·수정한 뒤 upload할 수 있습니다.

---

## 2. 사전 준비

### 2.1 Java 설치 확인

Java 21 이상이 필요합니다.

```bash
java -version
# java version "21.0.x" ...
```

### 2.2 Redmine API Key 발급

1. Redmine 로그인 → **내 계정** (우측 상단)
2. **API 접근 키** 섹션 → **API 키 표시** 클릭
3. 키를 복사해 두세요

> API Key를 사용할 수 없는 환경이면 Redmine 로그인 ID/PW를 대신 사용할 수 있습니다.

### 2.3 GitHub Personal Access Token 발급

1. GitHub → **Settings** → **Developer settings** → **Personal access tokens** → **Tokens (classic)**
2. **Generate new token** 클릭
3. 권한 체크: `repo` (private) 또는 `public_repo` (public)
4. 생성된 토큰 복사 (화면을 벗어나면 다시 볼 수 없음)

> fetch 단계는 GitHub Token 없이 실행 가능합니다. Token은 upload 단계에만 필요합니다.

### 2.4 대상 GitHub 리포지터리 확인

- 이전 대상 리포지터리가 생성되어 있어야 합니다.
- 기본 브랜치(`main` 또는 `master`)에 파일이 하나 이상 있어야 합니다.  
  (빈 리포지터리라면 README 파일을 하나 만들어 두세요.)

---

## 3. 설치 및 빌드

```bash
# 빌드
./gradlew shadowJar          # macOS / Linux
gradlew.bat shadowJar        # Windows

# 빌드 결과
build/libs/redmine2github.jar

# 동작 확인
java -jar build/libs/redmine2github.jar --help
```

---

## 4. 설정 파일 구성

### 4.1 환경 변수

#### 변수 목록

| 변수 | Phase | 필수 | 설명 |
|------|-------|:----:|------|
| `REDMINE_URL` | fetch | ✅ | Redmine 서버 주소 (예: `https://redmine.example.com`) |
| `REDMINE_PROJECT` | fetch | △ | Redmine 프로젝트 식별자 (단일 프로젝트 시 필요) |
| `REDMINE_PROJECTS` | fetch | — | 여러 프로젝트 식별자, 쉼표 구분 (예: `proj-a,proj-b,proj-c`) |
| `REDMINE_API_KEY` | fetch | ※ | Redmine API Key (방식 1) |
| `REDMINE_USERNAME` | fetch | ※ | Redmine 로그인 ID (방식 2) |
| `REDMINE_PASSWORD` | fetch | ※ | Redmine 비밀번호 (방식 2) |
| `GITHUB_TOKEN` | upload | ✅ | GitHub Personal Access Token |
| `GITHUB_REPO` | upload | ✅ | 대상 리포지터리 (`owner/repo-name`) |
| `OUTPUT_DIR` | 공통 | — | 변환 파일 출력 경로 (기본: `./output`) |
| `CACHE_DIR` | fetch | — | API 응답 캐시 경로 (기본: `./cache`) |
| `GITHUB_UPLOAD_METHOD` | upload | — | 업로드 방식: `API` 또는 `JGIT` (기본: `API`) |
| `REDMINE_ISSUE_MD_FETCH` | fetch/upload | — | 일감을 MD 파일로 저장·업로드할지 여부 (기본: `true`). `true`이면 `issues/{id}.md` + `issues.md` 생성 및 리포지터리 업로드. `false`이면 GitHub Issues API로 등록 |
| `UPLOAD_MAX_FILE_SIZE_KB` | upload | — | 이 크기(KB)를 초과하는 파일은 업로드에서 제외 (기본: `0` = 제한 없음). 예: `10240` → 10MB 초과 파일 제외 |

> ※ `REDMINE_API_KEY` **또는** `REDMINE_USERNAME`+`REDMINE_PASSWORD` 중 하나 필수  
> △ `fetch --all` 또는 `fetch --project <id>` 사용 시 불필요

#### 설정 방법 A: OS 환경 변수 (권장)

**macOS / Linux**

```bash
# Phase 1 (fetch) 필요
export REDMINE_URL=https://redmine.example.com
export REDMINE_PROJECT=my-project
export REDMINE_API_KEY=abcdef1234567890abcdef1234567890

# Phase 2 (upload) 필요
export GITHUB_TOKEN=ghp_xxxxxxxxxxxxxxxxxxxx
export GITHUB_REPO=my-org/my-repo
```

**Windows PowerShell**

```powershell
# Phase 1 (fetch) 필요
$env:REDMINE_URL     = "https://redmine.example.com"
$env:REDMINE_PROJECT = "my-project"
$env:REDMINE_API_KEY = "abcdef1234567890abcdef1234567890"

# Phase 2 (upload) 필요
$env:GITHUB_TOKEN = "ghp_xxxxxxxxxxxxxxxxxxxx"
$env:GITHUB_REPO  = "my-org/my-repo"
```

#### 설정 방법 B: `.env` 파일 (로컬 개발용)

```bash
cp .env.example .env
# .env 파일 편집
```

`.env` 파일 예시:

```dotenv
# Phase 1 (fetch) 필요
REDMINE_URL=https://redmine.example.com

# 수집 대상 프로젝트 — 세 가지 방식 중 하나 선택
REDMINE_PROJECT=my-project             # 단일 프로젝트
# REDMINE_PROJECTS=proj-a,proj-b       # 여러 특정 프로젝트
# (전체 프로젝트는 --all 옵션 사용, 환경 변수 불필요)

REDMINE_API_KEY=abcdef1234567890abcdef1234567890

# Phase 2 (upload) 필요
GITHUB_TOKEN=ghp_xxxxxxxxxxxxxxxxxxxx
GITHUB_REPO=my-org/my-repo

# 선택 설정
OUTPUT_DIR=./output
CACHE_DIR=./cache
GITHUB_UPLOAD_METHOD=API

# 일감 MD 파일 저장 여부 (기본: true)
# true  → issues/{id}.md 저장 + issues.md 인덱스 생성 → 리포지터리 업로드
# false → GitHub Issues API로 직접 등록
REDMINE_ISSUE_MD_FETCH=true

# 업로드 파일 크기 상한 (KB) — 초과 파일 제외 (기본: 0 = 제한 없음)
# 예: 10240이면 10MB 초과 파일을 업로드에서 제외
# UPLOAD_MAX_FILE_SIZE_KB=0
```

> **보안 주의**: `.env` 파일은 절대 커밋하지 마세요. `.gitignore`에 포함되어 있습니다.

### 4.2 사용자 매핑 파일 (`user-mapping.yml`)

```bash
# Redmine 사용자 목록으로 초안 자동 생성
java -jar build/libs/redmine2github.jar generate-mapping
```

생성된 파일을 열어 GitHub 계정명을 채웁니다:

```yaml
users:
  john.doe:    john-doe-github
  jane.smith:  janesmith
  admin:       ""           # 빈 값 → Assignee 미설정
```

**사용 시점 및 방식**

| 단계 | 사용 목적 |
|------|-----------|
| **fetch** | Redmine 이슈를 `{id}.json`으로 변환할 때 `assignee` 및 댓글 작성자를 GitHub 계정명으로 치환하여 저장 |
| **upload** | 저장된 JSON의 `assignee` 값을 그대로 GitHub Issue에 설정 |

> **주의**: 매핑은 **fetch 단계에서 적용**되어 output JSON에 저장됩니다.  
> fetch 후 `user-mapping.yml`을 수정해도 이미 생성된 JSON에는 반영되지 않습니다.  
> 매핑을 바꾸려면 `output/issues/{id}.json`을 직접 수정하거나 fetch를 다시 실행하세요.

> **관리자 권한 없는 환경**: `/users.json` API 접근이 차단(403)되면  
> 자동으로 `/projects/{project}/memberships.json` 으로 폴백합니다.  
> 이 경우 키가 Redmine **표시 이름**(display name)으로 채워지므로,  
> 실제 Redmine **로그인 ID**로 직접 수정한 뒤 마이그레이션을 실행하세요.

### 4.3 Label 색상 파일 (`label-colors.yml`) — 선택

```bash
cp label-colors.yml.example label-colors.yml
```

### 4.4 URL 치환 규칙 파일 (`url-rewrites.yml`) — 선택

fetch 시 변환된 마크다운 본문의 특정 URL을 일괄 치환합니다.  
Redmine 서버에서 사용한 레거시 IP 주소를 공식 도메인으로 교체하는 데 주로 활용합니다.

```bash
cp url-rewrites.yml.example url-rewrites.yml
# url-rewrites.yml 편집
```

```yaml
# url-rewrites.yml 예시
rewrites:
  - old: "http://old-svn.internal"
    new: "https://new-git.com"
  - old: "http://old-server.internal"
    new: "https://new-server.example.com"
```

> `old` 값이 길고 구체적인 것부터 나열하면 의도치 않은 부분 치환을 피할 수 있습니다.

---

## 5. 2단계 마이그레이션

### 5.1 Phase 1: fetch — Redmine → 로컬

fetch 단계는 GitHub 자격증명 없이 실행할 수 있습니다.

**단일 프로젝트 수집** (`.env`의 `REDMINE_PROJECT` 사용):

```bash
# macOS / Linux
./scripts/fetch.sh

# Windows
scripts\fetch.bat
```

**특정 프로젝트 직접 지정** (환경 변수 없이):

```bash
./scripts/fetch.sh --project my-project-id
```

**수집 항목별 선택 실행:**

```bash
./scripts/fetch.sh --only wiki          # Wiki만
./scripts/fetch.sh --only issues        # 일감만
./scripts/fetch.sh --only time-entries  # 작업 내역만
```

**실행 결과** — `output/` 디렉터리에 저장됩니다:

```
output/
├── wiki/
│   ├── GettingStarted.md          ← Textile → GFM 변환 완료
│   ├── Installation.md
│   └── UserGuide/
│       └── BasicUsage.md
├── attachments/                   ← Wiki 첨부파일
│   └── screenshot.png
├── attachments-issue/             ← 이슈 첨부파일 (다운로드됨)
│   └── image.png
├── issues-json/                   ← Issue 변환 데이터 (JSON)
│   ├── _labels.json               ← Label 정의 목록
│   ├── _milestones.json           ← Milestone 정의 목록
│   ├── 1.json                     ← Issue #1 변환 데이터
│   └── ...
├── issues/                        ← Issue MD 파일 (REDMINE_ISSUE_MD_FETCH=true 시)
│   ├── 1.md                       ← Issue #1 본문 + 댓글 + 이력
│   └── ...
├── issues.md                      ← 이슈 목록 인덱스 (ID / 제목 / status / tracker / category / 생성자 / 날짜)
└── _migration/
    └── time_entries.csv
```

**검토 및 수정**: `output/` 파일을 직접 편집하여 변환 품질을 조정할 수 있습니다.

- `output/wiki/*.md` — Markdown 문법 수정
- `output/issues-json/*.json` — 본문·댓글·Assignee 수정
- `output/issues-json/_labels.json` — Label 이름·색상 조정
- `output/issues/*.md` — Issue MD 내용 직접 수정 (REDMINE_ISSUE_MD_FETCH=true 시)

**여러 특정 프로젝트** (`.env`에 `REDMINE_PROJECTS=proj-a,proj-b` 설정 후):

```bash
./scripts/fetch.sh    # 자동으로 각 프로젝트를 순서대로 수집
```

### 5.2 Phase 2: upload — 로컬 → GitHub

fetch와 검토가 완료된 후 업로드합니다.

```bash
# macOS / Linux
./scripts/upload.sh

# Windows
scripts\upload.bat
```

**업로드 항목별 선택 실행:**

```bash
./scripts/upload.sh --only wiki          # Wiki만
./scripts/upload.sh --only issues        # 일감만
./scripts/upload.sh --only time-entries  # 작업 내역만
```

**전체 프로젝트 일괄 업로드** (`fetch --all`로 수집한 경우):

```bash
./scripts/upload.sh --all                    # output/ 하위 모든 프로젝트
./scripts/upload.sh --all --only wiki        # 모든 프로젝트, Wiki만
./scripts/upload.sh --all --skip foo,bar     # 일부 프로젝트 제외
```

> `--all`은 Redmine API를 사용하지 않고 `output/` 하위 디렉터리를 스캔합니다.

---

## 6. 전체 프로젝트 일괄 처리 (--all)

### 6.1 fetch --all — 전체 프로젝트 수집

Redmine에 접근 가능한 **모든 프로젝트**를 한 번에 수집합니다. `REDMINE_PROJECT` 환경 변수가 없어도 실행할 수 있습니다.

```bash
# macOS / Linux
./scripts/fetch.sh --all

# Windows
scripts\fetch.bat --all
```

**옵션 조합:**

```bash
./scripts/fetch.sh --all --only wiki          # 모든 프로젝트, Wiki만
./scripts/fetch.sh --all --only issues        # 모든 프로젝트, 일감만
./scripts/fetch.sh --all --skip foo,bar       # foo, bar 프로젝트 제외
./scripts/fetch.sh --all --resume             # 중단 후 재개

# Windows
scripts\fetch.bat --all --only wiki
scripts\fetch.bat --all --skip foo,bar
```

**출력 디렉터리** — 프로젝트별 서브디렉터리로 저장됩니다:

```
output/
├── all_projects_wiki.md           ← 전체 프로젝트 Wiki 인덱스 (자동 생성)
├── all_projects_issue.md          ← 전체 프로젝트 Issue 인덱스 (자동 생성)
├── project-a/
│   ├── _project.json              ← 프로젝트 메타정보 (id, name, description)
│   ├── wiki/
│   ├── attachments/
│   ├── attachments-issue/
│   ├── issues-json/
│   ├── issues/
│   ├── issues.md
│   └── _migration/
├── project-b/
│   └── ...
└── project-c/
    └── ...
```

### 6.2 upload --all — 전체 프로젝트 업로드

`fetch --all`로 수집한 결과를 한 번에 업로드합니다.  
Redmine API는 사용하지 않으며 `output/` 하위 디렉터리를 자동으로 스캔합니다.

```bash
# macOS / Linux
./scripts/upload.sh --all

# Windows
scripts\upload.bat --all
```

**옵션 조합:**

```bash
./scripts/upload.sh --all --only wiki          # 모든 프로젝트, Wiki만
./scripts/upload.sh --all --skip foo,bar       # foo, bar 프로젝트 제외
./scripts/upload.sh --all --resume             # 중단 후 재개

# Windows
scripts\upload.bat --all --only wiki
scripts\upload.bat --all --skip foo,bar
```

> **팁**: `fetch --all` 후 `output/` 각 프로젝트 디렉터리를 검토한 뒤 `upload --all`을 실행하세요.

---

## 7. 통합 실행 (migrate)

fetch + upload를 한 번에 실행합니다.

> **커맨드 이름**: `migrate`  
> 스크립트(`migrate.sh` / `migrate.bat`)는 내부적으로 `migrate` 커맨드를 호출합니다.  
> **필수 환경 변수**: `REDMINE_URL`, `REDMINE_PROJECT`, `GITHUB_TOKEN`, `GITHUB_REPO`  
> (`fetch --all` 형태는 `migrate`에서 지원하지 않습니다. 단일 프로젝트만 통합 실행 가능합니다.)

```bash
# macOS / Linux
./scripts/migrate.sh

# Windows
scripts\migrate.bat
```

**옵션 조합:**

```bash
./scripts/migrate.sh --only wiki          # Wiki만
./scripts/migrate.sh --only issues        # 일감만
./scripts/migrate.sh --only time-entries  # 작업 내역만
./scripts/migrate.sh --resume             # 이전 중단 지점부터 재개
./scripts/migrate.sh --retry-failed       # 실패 항목 재처리
```

직접 JAR로 실행할 때:

```bash
java -jar build/libs/redmine2github.jar migrate
java -jar build/libs/redmine2github.jar migrate --only wiki
```

---

## 8. CLI 커맨드 레퍼런스

### `fetch` — Phase 1

```
java -jar redmine2github.jar fetch [옵션...]
```

| 옵션 | 설명 |
|------|------|
| `--only <대상>` | `wiki` / `issues` / `time-entries` 선택 |
| `--resume` | 이전 중단 지점부터 재개 |
| `--retry-failed` | 이전 실패 항목 재처리 |
| `--all` | 접근 가능한 모든 프로젝트 수집 |
| `--project <id>` | 수집할 프로젝트 직접 지정 (환경 변수 대체) |
| `--skip <id,...>` | `--all` 사용 시 제외할 프로젝트 (쉼표 구분) |
| `-h`, `--help` | 도움말 |

### `upload` — Phase 2

```
java -jar redmine2github.jar upload [옵션...]
```

| 옵션 | 설명 |
|------|------|
| `--only <대상>` | `wiki` / `issues` / `time-entries` 선택 |
| `--resume` | 이전 중단 지점부터 재개 |
| `--retry-failed` | 이전 실패 항목 재처리 |
| `--all` | `output/` 하위 모든 프로젝트 디렉터리를 순서대로 업로드 |
| `--skip <id,...>` | `--all` 사용 시 제외할 프로젝트 (쉼표 구분) |
| `-h`, `--help` | 도움말 |

### `migrate` — 통합 실행

```
java -jar redmine2github.jar migrate [옵션...]
```

스크립트: `./scripts/migrate.sh` / `scripts\migrate.bat`

`fetch` + `upload`와 동일한 옵션(`--only`, `--resume`, `--retry-failed`)을 지원합니다.

### `generate-mapping` — 사용자 매핑 초안 생성

```
java -jar redmine2github.jar generate-mapping [--output <경로>]
```

스크립트: `./scripts/generate-mapping.sh` / `scripts\generate-mapping.bat`

**필수 환경 변수**: `REDMINE_URL`, `REDMINE_API_KEY` (또는 `REDMINE_USERNAME`+`REDMINE_PASSWORD`)

### `generate-index` — 전체 프로젝트 인덱스 파일 생성/갱신

```
java -jar redmine2github.jar generate-index [--output-dir <경로>]
```

| 옵션 | 설명 |
|------|------|
| `--output-dir <경로>` | 스캔할 output 디렉터리 경로 (기본: `OUTPUT_DIR` 환경 변수 또는 `./output`) |
| `-h`, `--help` | 도움말 |

`output/` 디렉터리의 각 프로젝트 폴더를 스캔하여 두 인덱스 파일을 생성/갱신합니다.  
Redmine 연결 없이 실행 가능합니다.

생성 파일:
- `output/all_projects_wiki.md` — Wiki 페이지 수 기준 정렬. 프로젝트명 옆에 Wiki 페이지 수 표시
- `output/all_projects_issue.md` — Issue 수 기준 정렬. 프로젝트명 옆에 Issue 수 표시

> `fetch --all` 완료 시 자동으로 실행됩니다. 개별 프로젝트를 재수집한 뒤에는 이 커맨드로 인덱스를 수동 갱신할 수 있습니다.

### 전형적인 실행 시나리오

**시나리오 A: 단일 프로젝트**

```bash
# 1. 사용자 매핑 생성
java -jar build/libs/redmine2github.jar generate-mapping

# 2. user-mapping.yml 편집 (GitHub 계정 입력)

# 3. Phase 1: Redmine 수집 (REDMINE_PROJECT 설정 필요)
./scripts/fetch.sh

# 4. output/ 검토 및 수정 (필요 시)

# 5. Phase 2: GitHub 업로드
./scripts/upload.sh

# 6. 실패 항목 재처리
./scripts/upload.sh --retry-failed
```

**시나리오 B: 전체 프로젝트 일괄 처리**

```bash
# 1. 모든 프로젝트 수집 (REDMINE_PROJECT 불필요)
#    완료 시 output/all_projects_wiki.md, output/all_projects_issue.md 자동 생성
./scripts/fetch.sh --all          # macOS / Linux
scripts\fetch.bat --all           # Windows

# 2. output/{project-id}/ 디렉터리 검토

# 2-a. 개별 프로젝트 재수집 후 인덱스 갱신
./scripts/fetch.sh --project my-project-id
java -jar build/libs/redmine2github.jar generate-index

# 3. 전체 프로젝트 일괄 업로드
./scripts/upload.sh --all         # macOS / Linux
scripts\upload.bat --all          # Windows

# 3-a. 특정 프로젝트 제외하려면
./scripts/upload.sh --all --skip foo,bar
```

---

## 9. 출력 파일 구조

### 로컬 `output/` 디렉터리

```
output/
├── all_projects_wiki.md           # 전체 프로젝트 Wiki 인덱스 (fetch --all 또는 generate-index 생성)
├── all_projects_issue.md          # 전체 프로젝트 Issue 인덱스 (fetch --all 또는 generate-index 생성)
├── {project}/                     # 프로젝트 식별자 폴더
│   ├── _project.json              # 프로젝트 메타정보 (id, name, description) — fetch --all 시 생성
│   ├── wiki/                      # Wiki .md 파일 (Textile → GFM)
│   │   ├── GettingStarted.md
│   │   └── ParentPage/            # 하위 페이지는 부모 이름 폴더 아래
│   │       └── SubPage.md
│   ├── attachments/               # Wiki 첨부파일
│   ├── attachments-ext/           # 마크다운 본문 내 Redmine URL에서 다운로드한 외부 파일
│   ├── attachments-issue/         # 이슈 첨부파일
│   ├── issues-json/               # Issue 변환 데이터 (JSON)
│   │   ├── _labels.json           # Label 정의 [{name, color, description}]
│   │   ├── _milestones.json       # Milestone 정의 [{name, description, dueDate}]
│   │   └── {id}.json             # Issue 변환 데이터
│   ├── issues/                    # Issue MD 파일 (REDMINE_ISSUE_MD_FETCH=true 시)
│   │   └── {id}.md               # Issue 본문 + 첨부파일 + Comments (노트 + 변경 이력)
│   ├── issues.md                  # 이슈 목록 인덱스 (ID / 제목 / status / tracker / category / 생성자 / 날짜)
│   └── _migration/
│       └── time_entries.csv
```

### GitHub 리포지터리 업로드 구조

```
{project}/
  wiki/
    GettingStarted.md
    ParentPage/
      SubPage.md
  attachments/
  attachments-ext/
  attachments-issue/
  issues/                  ← REDMINE_ISSUE_MD_FETCH=true 시
    {id}.md
  issues.md                ← REDMINE_ISSUE_MD_FETCH=true 시
```

> Wiki `.md` 파일 내 첨부파일 상대 경로는 자동 계산됩니다.  
> 예) `{project}/wiki/Root.md` → `../attachments/file.png`  
> Issue MD 파일 내 첨부파일 경로: `{project}/issues/{id}.md` → `../attachments-issue/file.png`

#### REDMINE_ISSUE_MD_FETCH 모드별 upload 동작

| 값 | upload 동작 |
|----|-------------|
| `true` (기본) | GitHub Issues API 등록 생략. `issues/{id}.md` + `issues.md`를 리포지터리 파일로 업로드 |
| `false` | Labels, Milestones, Issues를 GitHub Issues API로 등록 |

### Issue JSON 형식 (`output/issues-json/{id}.json`)

```json
{
  "redmineId": 42,
  "subject": "로그인 버그 수정",
  "body": "> **[Redmine #42]** | 작성: alice | 날짜: 2024-01-15\n\n...",
  "labels": ["tracker:Bug", "priority:High", "status:New", "project:my-proj"],
  "author": "alice",
  "assignee": "github-username",
  "createdOn": "2024-01-15T10:30:00Z",
  "comments": ["> **alice** (2024-01-16)\n\n재현 완료"],
  "closed": false
}
```

> **`closed` 필드**: Redmine 상태가 `Closed` 또는 `Resolved`이면 `true`.  
> upload 시 GitHub Issue를 생성한 직후 자동으로 닫습니다.  
> 그 외 상태(`New`, `In Progress`, `Feedback` 등)는 `false` → open으로 유지됩니다.

### Issue MD 형식 (`output/issues/{id}.md`, REDMINE_ISSUE_MD_FETCH=true 시)

```markdown
# [#42] 로그인 버그 수정

> **[Redmine #42]** | 프로젝트: `my-proj` | 작성: alice | 날짜: 2024-01-15T10:30:00Z

이슈 본문 내용...

## 첨부파일

- [screenshot.png](../attachments-issue/screenshot.png)

---

## Comments (2)

> **alice** (2024-01-16T09:00:00Z)
>
> - **상태**: `1` → `2`
> - 첨부파일 추가: [log.txt](../attachments-issue/log.txt)

재현 완료. 로그 첨부합니다.

---
```

> **이력(Comments) 섹션**: 노트(댓글 본문)와 필드 변경 이력(상태·담당자·진행률 등)을 모두 포함합니다.  
> 첨부파일이 추가된 이력은 `../attachments-issue/` 에 대한 링크로 표시됩니다.

### Time Entries CSV 컬럼

| 컬럼 | 설명 |
|------|------|
| `redmine_issue_id` | 원본 Redmine 일감 번호 |
| `date` | 작업 날짜 (YYYY-MM-DD) |
| `hours` | 작업 시간 |
| `activity` | 활동 유형 |
| `user` | 작업자 로그인 ID |
| `comment` | 작업 메모 |

---

## 10. 변환 규칙 (Textile → GFM)

| Textile | GFM |
|---------|-----|
| `h1. 제목` | `# 제목` |
| `h2. 제목` | `## 제목` |
| `*굵게*` | `**굵게**` |
| `_기울임_` | `*기울임*` |
| `-취소선-` | `~~취소선~~` |
| `@코드@` | `` `코드` `` |
| `<pre>블록</pre>` | ` ```블록``` ` |
| `"텍스트":URL` | `[텍스트](URL)` |
| `!이미지!` | `![](이미지)` |
| `* 항목` | `- 항목` |
| `# 항목` | `1. 항목` |
| `[[PageName]]` | `[PageName](PageName.md)` |
| `{REDMINE_URL}/projects/{proj}/wiki/{page}` | 프로젝트 간 상대 경로 `.md` 링크 |
| `{REDMINE_URL}/attachments/{id}/{file}` | `attachments-ext/` 다운로드 후 상대 경로 링크 |

**미지원 문법** (수동 수정 필요):

- Textile 테이블
- 중첩 목록
- `{color:red}` 등 인라인 스타일
- Redmine 플러그인 전용 문법

> **팁**: fetch 후 `output/wiki/` 파일을 직접 수정하여 변환 품질을 보완한 뒤 upload하세요.

---

## 11. 중단 및 재개

### migration-state.json 구조

실행 중 생성되는 상태 파일입니다.

```json
{
  "fetchedWikiPages":       ["GettingStarted", "Installation"],
  "fetchedIssueIds":        [1, 2, 3],
  "timeEntriesFetched":     true,
  "labelsFetched":          true,
  "milestonesFetched":      true,

  "completedWikiPages":     ["wiki/GettingStarted.md"],
  "completedIssueIds":      [1, 2],
  "failedIssueIds":         [3],
  "timeEntriesDone":        false,
  "labelsDone":             true,
  "milestonesDone":         true,
  "completedIssueMdPaths":  ["my-proj/issues/1.md", "my-proj/issues/2.md"],
  "issuesMdIndexDone":      false
}
```

> **`completedIssueMdPaths`**: 리포지터리에 업로드 완료된 Issue MD 파일 경로 목록 (`REDMINE_ISSUE_MD_FETCH=true` 시).  
> **`issuesMdIndexDone`**: `issues.md` 인덱스 파일 업로드 완료 여부.

### 재개 방법

```bash
# fetch 재개
./scripts/fetch.sh --resume

# upload 재개
./scripts/upload.sh --resume

# 실패 항목만 재처리
./scripts/upload.sh --retry-failed
```

### 처음부터 다시 시작

```bash
rm migration-state.json
rm -rf output/      # 로컬 파일도 초기화할 경우
./scripts/fetch.sh
```

> **주의**: GitHub에 이미 생성된 Issue나 업로드된 파일은 삭제되지 않습니다.

---

## 12. 진행 상태 모니터링

### 콘솔 출력 예시

```
============================================
 [Phase 1] Redmine → 로컬 수집 시작
 대상 Redmine: https://redmine.example.com/projects/my-project
============================================
[Wiki[fetch]] 시작 — 총 42건
[Wiki[fetch]]   ✓   1/42  GettingStarted
[Wiki[fetch]]   ✓   2/42  Installation
[Wiki[fetch]]   →   3/42  OldPage (스킵: 이미 완료)
[Wiki[fetch]]   ✗   4/42  BrokenPage (실패: HTTP 404)
[Wiki[fetch]] 완료 — 성공: 40 / 스킵: 1 / 실패: 1 / 전체: 42

  fetch 완료. output/ 디렉터리를 검토한 뒤 upload를 실행하세요.
    redmine2github upload
```

### 로그 파일

```bash
tail -f migration.log
grep "ERROR\|WARN" migration.log
```

### GitHub Rate Limit

- 인증 시 **시간당 5,000회** 제한
- upload 단계에서 10~20건마다 잔여 횟수 표시
- 소진 시 자동 대기 (exponential backoff)

---

## 13. GitHub 업로드 방식 선택

`GITHUB_UPLOAD_METHOD` 환경 변수로 설정합니다.

| 방식 | 설명 | 적합한 경우 |
|------|------|-------------|
| `API` (기본) | Contents API, 파일별 개별 업로드 | 파일 500개 미만 |
| `JGIT` | git clone → commit → push | 파일 500개 이상, 대용량 첨부파일 |

자세한 비교: [github.upload.md](github.upload.md)

---

## 14. 트러블슈팅

**`[오류] JAR 파일이 없습니다`**  
→ `./gradlew shadowJar` 실행

**`[오류] output 디렉터리가 없습니다` (upload 단계)**  
→ `./scripts/fetch.sh`를 먼저 실행

**`IllegalStateException: Redmine 인증 정보가 없습니다`**  
→ `REDMINE_API_KEY` 또는 `REDMINE_USERNAME`+`REDMINE_PASSWORD` 환경 변수 확인

**`HTTP 401 Unauthorized` (Redmine)**  
→ API Key 오류 또는 Redmine 관리자 설정에서 REST API 활성화 여부 확인

**`HTTP 403 Forbidden` (GitHub)**  
→ `GITHUB_TOKEN` 권한(`repo` 또는 `public_repo`) 확인, 토큰 만료 여부 확인

**Redmine 링크가 변환되지 않을 때** (`http://REDMINE_URL/projects/...`가 그대로 남을 때)  
→ `REDMINE_URL` 환경 변수가 Redmine URL과 정확히 일치하는지 확인

**IP 주소를 도메인으로 교체하려면**  
→ `url-rewrites.yml` 생성 후 규칙 추가 (`cp url-rewrites.yml.example url-rewrites.yml`)

**`HTTP 422 Unprocessable Entity` (Issue 생성)**  
→ Assignee가 해당 리포지터리 Collaborator가 아님. `user-mapping.yml`에서 해당 항목을 빈 값으로 두거나 Collaborator 추가

**Issue JSON 파일 내용을 수정하고 싶을 때**  
→ `output/issues/{id}.json` 직접 편집 후 `upload --only issues --retry-failed` 실행

**Wiki 내부 링크가 깨질 때**  
→ `output/wiki/*.md` 파일의 링크 수정 후 `upload --only wiki --retry-failed` 실행

**같은 Issue가 중복 생성됨**  
→ `--resume` 없이 upload를 재실행했을 가능성. 반드시 `--resume` 사용

**Rate Limit으로 속도가 느려짐**  
→ 자동 대기 중. 기다리면 됨. 또는 `GITHUB_UPLOAD_METHOD=JGIT` 설정

---

## 15. 제한 사항

| 항목 | 내용 |
|------|------|
| Textile 변환 | 기본 문법만 지원. 테이블·중첩 목록·인라인 스타일은 수동 수정 필요 |
| GitHub Wiki | GitHub Wiki 저장소 업로드 미지원 (일반 Repository 파일로만 업로드) |
| 첨부파일 크기 | API 방식: 파일당 100MB 초과 불가 → JGIT 방식 사용. `UPLOAD_MAX_FILE_SIZE_KB`로 업로드 상한을 설정하면 대용량 파일을 사전에 제외할 수 있음 |
| 사용자 계정 | Redmine 사용자 계정·권한 이전 불가 |
| Issue 번호 | GitHub Issue 번호 ≠ Redmine 일감 번호. Issue 본문에 원본 Redmine URL 포함 |
| Redmine 플러그인 | 플러그인 전용 문법 완전 변환 미보장 |
| 프로젝트 간 wiki 링크 | `{REDMINE_URL}/projects/{proj}/wiki/{page}` 형식만 자동 변환. 다른 형식은 수동 수정 필요 |
