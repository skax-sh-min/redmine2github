# Redmine2Github

Redmine 프로젝트를 GitHub로 마이그레이션하는 Java CLI 도구입니다.  
Wiki, 일감(Issues), 작업 내역(Time Entries)을 **2단계**로 나누어 안전하게 이전합니다.

## 마이그레이션 대상

| Redmine | GitHub |
|---------|--------|
| Wiki 페이지 (Textile) | `.md` 파일 (GFM) — 리포지터리 push |
| 일감 (Issue) | GitHub Issue (Label + Milestone + Assignee) |
| 일감 댓글 / 변경 이력 | Issue Comment |
| 버전 (Version) | Milestone |
| 트래커 / 우선순위 / 상태 / 카테고리 | Label |
| 첨부파일 | 리포지터리 push |
| 작업 내역 (Time Entry) | `time_entries.csv` — 리포지터리 push |

## 2단계 처리 흐름

```
Phase 1: fetch          →  output/ (로컬 파일)
                               ↓ 검토·수정 가능
Phase 2: upload         →  GitHub
```

- **Phase 1 (fetch)**: Redmine API 수집 → Textile 변환 → 로컬 저장  
- **Phase 2 (upload)**: 로컬 파일 → GitHub Issues / 파일 push

## 요구 사항

- Java 21+
- Gradle 8+ (빌드 시)
- Redmine API Key 또는 Redmine 계정 (ID/PW) — Phase 1 필요
- GitHub Personal Access Token (repo 권한) — Phase 2 필요

## 빌드

```bash
./gradlew shadowJar
# → build/libs/redmine2github.jar
```

## 설정

### 1. 환경 변수

| 변수 | Phase | 필수 | 설명 |
|------|-------|:----:|------|
| `REDMINE_URL` | fetch | ✅ | Redmine 서버 주소 |
| `REDMINE_PROJECT` | fetch | △ | Redmine 프로젝트 식별자 (단일 프로젝트 시 필요) |
| `REDMINE_PROJECTS` | fetch | — | 여러 프로젝트 식별자, 쉼표 구분 (예: `proj-a,proj-b`) |
| `REDMINE_API_KEY` | fetch | ※ | Redmine API Key (방식 1) |
| `REDMINE_USERNAME` | fetch | ※ | Redmine 로그인 ID (방식 2) |
| `REDMINE_PASSWORD` | fetch | ※ | Redmine 비밀번호 (방식 2) |
| `GITHUB_TOKEN` | upload | ✅ | GitHub Personal Access Token |
| `GITHUB_REPO` | upload | ✅ | 대상 리포지터리 (`owner/repo-name`) |
| `OUTPUT_DIR` | 공통 | — | 출력 경로 (기본: `./output`) |
| `CACHE_DIR` | fetch | — | 캐시 경로 (기본: `./cache`) |
| `GITHUB_UPLOAD_METHOD` | upload | — | `API` 또는 `JGIT` (기본: `API`) |
| `REQUEST_DELAY_MS` | 공통 | — | API 요청 간 지연(ms) (기본: `10`) |

> ※ `REDMINE_API_KEY` 또는 `REDMINE_USERNAME`+`REDMINE_PASSWORD` 중 하나 필수  
> △ `--all` 또는 `--project <id>` 사용 시 불필요

```bash
cp .env.example .env
# .env 파일 편집
```

### 2. 사용자 매핑 파일 생성

```bash
java -jar build/libs/redmine2github.jar generate-mapping
# → user-mapping.yml 초안 생성 후 GitHub 계정명 직접 입력
```

> Redmine 관리자 권한이 없어 `/users.json`이 403을 반환하면  
> `/projects/{project}/memberships.json` 으로 자동 폴백합니다.  
> 이 경우 키가 **표시 이름(display name)** 으로 채워지므로  
> 실제 Redmine **로그인 ID** 로 수정한 뒤 마이그레이션을 실행하세요.

### 3. URL 치환 규칙 파일 생성 (`url-rewrites.yml`) — 선택

fetch 시 마크다운 본문의 레거시 URL(IP 주소 등)을 일괄 치환합니다.

```bash
cp url-rewrites.yml.example url-rewrites.yml
# url-rewrites.yml 편집
```

```yaml
rewrites:
  - old: "http://{IP}/svn"
    new: "http://{domain}/svn"
```

## 실행

### Phase 1: Redmine → 로컬 수집

```bash
# 단일 프로젝트 (REDMINE_PROJECT 환경 변수 사용)
./scripts/fetch.sh                       # macOS / Linux
scripts\fetch.bat                        # Windows

# 특정 프로젝트 직접 지정
./scripts/fetch.sh --project my-project

# 전체 프로젝트 일괄 수집
./scripts/fetch.sh --all
```

`output/` 디렉터리에 변환 파일이 생성됩니다. 내용을 검토·수정할 수 있습니다.

### Phase 2: 로컬 → GitHub 업로드

```bash
# macOS / Linux
./scripts/upload.sh

# Windows
scripts\upload.bat
```

### 통합 실행 (fetch + upload)

스크립트 내부에서 `migrate` CLI 커맨드를 호출합니다.

```bash
./scripts/migrate.sh      # macOS / Linux
scripts\migrate.bat       # Windows
```

### 항목별 선택 실행

```bash
./scripts/fetch.sh --only wiki
./scripts/upload.sh --only issues
./scripts/migrate.sh --only time-entries
```

### 주요 옵션

| 옵션 | 설명 |
|------|------|
| `--only <대상>` | `wiki` / `issues` / `time-entries` 선택 실행 |
| `--resume` | 이전 중단 지점부터 재개 |
| `--retry-failed` | 이전 실행에서 실패한 항목만 재처리 |
| `--all` | 접근 가능한 모든 프로젝트 일괄 수집 (`fetch` 전용) |
| `--project <id>` | 수집할 프로젝트 직접 지정 (`fetch` 전용) |
| `--skip <id,...>` | `--all` 사용 시 제외할 프로젝트 (`fetch` 전용) |

## 파일 구조

```
.
├── scripts/                # 실행 스크립트
│   ├── fetch.sh / fetch.bat          # 단일 또는 전체(--all) 수집
│   ├── upload.sh / upload.bat
│   └── migrate.sh / migrate.bat      # 내부적으로 migrate 호출
├── documents/              # 문서
│   ├── UserManual.md
│   ├── WBS.wiki.md
│   ├── PRD.wiki.md
│   └── github.upload.md
├── .env                    # 환경 변수 (gitignore)
├── user-mapping.yml        # 사용자 매핑 (gitignore)
├── label-colors.yml        # Label 색상 설정 (gitignore)
├── url-rewrites.yml        # URL 치환 규칙 (gitignore)
├── output/                 # fetch 결과물 (gitignore)
│   └── {project}/
│       ├── wiki/
│       ├── attachments/
│       ├── attachments-ext/  # 마크다운 본문 내 Redmine URL에서 다운로드한 파일
│       ├── issues/
│       └── _migration/
├── cache/                  # API 캐시 (gitignore)
└── migration-state.json    # 진행 상태 (gitignore)
```

## 로그

```bash
tail -f migration.log
```

## GitHub 업로드 방식

| 방식 | 설명 | 적합한 경우 |
|------|------|-------------|
| `API` (기본) | GitHub Contents API, 파일별 업로드 | 소규모 (500개 미만) |
| `JGIT` | git clone → commit → push | 대규모, 대용량 파일 |

자세한 비교: [documents/github.upload.md](documents/github.upload.md)

## 재개 및 멱등성

`migration-state.json`으로 완료 항목을 추적합니다.

```bash
./scripts/fetch.sh --resume      # fetch 재개
./scripts/upload.sh --resume     # upload 재개
./scripts/upload.sh --retry-failed  # 실패 항목 재처리
```

## 테스트

```bash
./gradlew test
```

## 기술 스택

- **Java 21**, Gradle
- **CLI**: picocli 4.7.6
- **HTTP**: OkHttp 4.12.0
- **JSON/YAML**: Jackson 2.17.2
- **GitHub 연동**: kohsuke/github-api 1.321, JGit 6.9.0
- **텍스트 변환**: 정규식 기반 Textile → GFM
- **CSV**: Apache Commons CSV 1.11.0
- **로깅**: SLF4J + Logback

## 상세 문서

- [사용자 매뉴얼](documents/UserManual.md)
- [GitHub 업로드 방식 비교](documents/github.upload.md)
