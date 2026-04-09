# PRD: Redmine 프로젝트 전체 → GitHub 마이그레이션 도구

## 제품 개요

Redmine 프로젝트의 Wiki, 일감(Issue), 작업 내역(Time Entry), 버전, 멤버 등 전체 데이터를 GitHub Repository 및 GitHub Issues/Projects로 자동 이전하는 Java CLI 도구.

---

## 대상 사용자

- Redmine 기반 프로젝트 관리를 GitHub로 완전히 이전하려는 개발팀 및 프로젝트 관리자
- Redmine과 GitHub를 병행 운영 중이며 단계적 마이그레이션을 계획 중인 팀

---

## 전체 처리 흐름

모든 데이터 유형을 **2단계(fetch → upload)** 구조로 통일하여 처리한다.

### Phase 1: fetch — Redmine → 로컬 output/

```
Redmine API
    │
    ▼
로컬 output/ 저장                ← 사람이 검토·수정 가능
    - wiki/          Textile → .md 파일 + 첨부파일 다운로드
    - attachments/   첨부파일 바이너리
    - issues/        {id}.json (LocalIssue DTO) + _labels.json + _milestones.json
    - _migration/    time_entries.csv
```

### Phase 2: upload — 로컬 output/ → GitHub

```
로컬 output/
    │
    ├─ wiki/*.md, attachments/ ──▶ GitHub Repository push (API 또는 JGit)
    │
    ├─ issues/_labels.json      ──▶ GitHub Labels API
    ├─ issues/_milestones.json  ──▶ GitHub Milestones API
    ├─ issues/{id}.json         ──▶ GitHub Issues API (+ Comments)
    │
    └─ _migration/time_entries.csv ▶ GitHub Repository push
    │
    ▼
migration-state.json 갱신       ← 처리 완료 항목 기록 (중복 방지)
```

> 2단계 분리로 인해 fetch 완료 후 output/ 파일을 검토·수정한 뒤 upload할 수 있다.
> Issue도 `output/issues/{id}.json` 중간 파일을 경유하므로 업로드 전 내용 확인이 가능하다.

### 전체 흐름 요약

```
Redmine REST API
        │
        ▼  [Phase 1: fetch]
  로컬 output/ 저장
  ├─ wiki/              변환된 .md 파일
  ├─ attachments/       첨부파일
  ├─ issues/            {id}.json, _labels.json, _milestones.json
  └─ _migration/        time_entries.csv
        │
        ▼  [Phase 2: upload]
  ├─ GitHub Repository push  (wiki, attachments, time_entries.csv)
  └─ GitHub Issues API       (Labels → Milestones → Issues + Comments)
        │
        ▼
  migration-state.json 갱신
```

---

## Redmine → GitHub 데이터 매핑

| Redmine | GitHub | 비고 |
|---------|--------|------|
| Wiki 페이지 | Repository `.md` 파일 | 계층 구조 → 디렉터리 |
| 일감 (Issue) | GitHub Issue | 제목, 본문, 상태 매핑 |
| 일감 댓글 | Issue Comment | 작성자·날짜 헤더 포함 |
| 일감 첨부파일 | Issue Comment 내 링크 or Repository 파일 | |
| 트래커 (Bug/Feature/Support) | Label | `tracker:Bug` 형태 |
| 우선순위 (High/Normal/Low) | Label | `priority:High` 형태 |
| 상태 (New/In Progress/Closed) | Issue 상태(open/closed) + Label | `status:In Progress` |
| 카테고리 | Label | `category:이름` 형태 |
| 버전 (Version) | Milestone | 마감일, 설명 포함 |
| 담당자 (Assignee) | Assignee | GitHub 계정 매핑 필요 |
| 작업 내역 (Time Entry) | Repository 내 CSV 파일 | GitHub 미지원으로 파일 보관 |
| 뉴스 / 포럼 | GitHub Discussions (선택) | |
| 멤버 / 역할 | Repository Collaborator | 수동 초대 가이드 제공 |

---

## 핵심 기능

### 1. Wiki 마이그레이션
- Redmine REST API로 전체 Wiki 페이지 수집 (제목, 본문, 첨부파일, 계층 구조).
- Textile → GitHub Flavored Markdown(GFM) 변환.
- `[[PageName]]` 내부 링크를 상대 경로 Markdown 링크로 재작성.
- 로컬 디렉터리에 저장 후 GitHub Repository에 push.
- 업로드 방식은 GitHub API 또는 JGit 중 선택 ([github.upload.md](github.upload.md) 참고).

### 2. 일감(Issue) 마이그레이션
- Redmine API로 전체 일감 수집 → `output/issues/{id}.json`(LocalIssue DTO)에 저장 (fetch 단계).
- Label 정의 → `output/issues/_labels.json`, Milestone 정의 → `output/issues/_milestones.json`.
- upload 단계에서 JSON 파일을 읽어 GitHub Issues API를 호출하여 Issue 생성.
- **본문 변환**: Textile → Markdown, 원본 Redmine URL·작성자·날짜를 헤더에 기록.
- **댓글**: 각 일감의 Journal(댓글+변경 이력)을 Issue Comment로 순서대로 생성.
- **첨부파일**: 로컬에 다운로드 후 Repository에 저장하고 Issue 본문의 링크를 갱신.
- **상태 매핑**: Closed/Resolved 상태는 GitHub Issue를 closed로 처리.
- **번호 보존 불가**: GitHub Issue 번호는 자동 채번되므로 본문에 `[Redmine #1234]` 형태로 원본 번호를 기록.

### 3. 레이블 / 마일스톤 사전 생성
- 트래커·우선순위·상태·카테고리를 `cache/meta.json`에 캐싱 후 GitHub Labels API로 일괄 생성.
- Redmine Version을 GitHub Milestones API로 변환 (이름, 설명, 마감일 포함).
- 색상 규칙을 설정 파일로 커스터마이징 가능.

### 4. 작업 내역(Time Entry) 이전
- GitHub에는 시간 기록 기능이 없으므로 CSV 파일로 변환하여 Repository에 저장.
- 저장 경로: `_migration/time_entries.csv`
- CSV 컬럼: `issue_id, redmine_issue_id, date, hours, activity, user, comment`
- 선택적으로 각 Issue Comment에 작업 시간 요약을 추가 가능.

### 5. 사용자 계정 매핑
- Redmine 사용자 로그인과 GitHub 계정을 연결하는 매핑 파일(`user-mapping.yml`)을 사용.
- 매핑이 없는 사용자는 본문에 이름을 텍스트로 기록.
- 매핑 파일 초안을 자동 생성하여 수동 편집을 안내.

### 6. 마이그레이션 상태 관리
- 처리된 항목을 `migration-state.json`에 기록하여 중단 후 재개(resume) 가능.
- 동일 항목 재실행 시 중복 생성 방지 (멱등성 보장).
- 실패 항목은 `migration.log`에 기록하고 `--retry-failed` 옵션으로 재처리.

---

## 기술 스택

| 영역 | 선택 기술 |
|------|-----------|
| 언어 | Java 21 |
| 빌드 | Gradle |
| Redmine 연동 | `OkHttp` + `Jackson` (Redmine REST API) |
| Textile 변환 | 정규식 기반 Textile→GFM 변환기 (`TextileConverter.java`) |
| GitHub 연동 | `github-api` (kohsuke) + `JGit` |
| CLI 인터페이스 | `picocli` |
| 설정 관리 | `.env` + `dotenv-java`, `user-mapping.yml` |
| 로깅 | SLF4J + Logback |
| CSV 출력 | `Apache Commons CSV` |

---

## CLI 인터페이스 설계

```bash
# Phase 1: Redmine → 로컬 output/ (GitHub 자격증명 불필요)

# 단일 프로젝트 (REDMINE_PROJECT 환경 변수 사용)
redmine2github fetch
redmine2github fetch --only wiki
redmine2github fetch --only issues
redmine2github fetch --only time-entries
redmine2github fetch --resume

# 특정 프로젝트 직접 지정 (환경 변수 대체)
redmine2github fetch --project my-project-id

# 전체 프로젝트 일괄 수집 (REDMINE_PROJECT 불필요)
redmine2github fetch --all
redmine2github fetch --all --only wiki
redmine2github fetch --all --skip foo,bar
redmine2github fetch-all                    # fetch --all 의 별칭

# Phase 2: 로컬 output/ → GitHub
redmine2github upload
redmine2github upload --only issues
redmine2github upload --resume
redmine2github upload --retry-failed

# 전체 파이프라인 (fetch + upload 연속 실행)
redmine2github migrate
redmine2github migrate --only wiki
redmine2github migrate --resume

# 사용자 매핑 초안 생성
redmine2github generate-mapping
```

> `.env` 파일에 공통 설정(URL, 토큰 등)을 미리 정의하면 커맨드 인수를 생략할 수 있다.  
> `fetch`는 Redmine 자격증명만 필요하며, `upload`는 GitHub Token만 필요하다.  
> `--all` 모드에서는 `REDMINE_PROJECT` 환경 변수 없이 실행 가능하다.

---

## 설정 파일 구조

```
project-root/
├── .env                        # API 키, URL 등 민감 정보
├── user-mapping.yml            # Redmine 사용자 ↔ GitHub 계정 매핑
├── label-colors.yml            # 레이블 색상 커스터마이징 (선택)
├── migration-state.json        # 진행 상태 자동 생성 (resume용)
├── migration.log               # 실행 로그 자동 생성
├── cache/                      # Redmine API 응답 캐시 (재실행 시 API 호출 생략)
│   ├── wiki_pages.json         # Wiki 페이지 상세 (text + attachments 포함)
│   ├── issues.json             # 일감 + 댓글 원본
│   ├── trackers.json           # 트래커 목록
│   ├── issue_priorities.json   # 우선순위 목록
│   ├── issue_categories.json   # 카테고리 목록
│   └── versions.json           # 버전(Milestone) 원본
└── output/                     # fetch 결과물 (검토·수정 후 upload)
    ├── wiki/                   # 변환된 .md 파일 → Repository push 대상
    ├── attachments/            # 다운로드된 첨부파일 → Repository push 대상
    ├── issues/                 # 변환된 Issue 중간 파일
    │   ├── 1.json              # LocalIssue DTO (id별)
    │   ├── _labels.json        # Label 정의 목록
    │   └── _milestones.json    # Milestone 정의 목록
    └── _migration/
        └── time_entries.csv    # 작업 내역 → Repository push 대상
```

### user-mapping.yml 예시
```yaml
users:
  redmine_login_a: github-username-a
  redmine_login_b: github-username-b
  # 매핑 없는 사용자는 이름을 텍스트로 기록
```

---

## 비기능 요구사항

- **멱등성**: `migration-state.json` 기반으로 이미 처리된 항목은 재생성하지 않는다. fetch/upload 단계 각각 독립적으로 추적한다.
- **GitHub Rate Limit 대응**: GitHub API 호출 사이에 exponential backoff를 적용하고, 잔여 한도를 모니터링한다 (`RateLimitAwareExecutor`).
- **Redmine Rate Limit 대응**: `REQUEST_DELAY_MS`(기본 10ms) 설정으로 Redmine API 요청 간 지연을 조절한다.
- **2단계 분리**: fetch(Redmine→로컬)와 upload(로컬→GitHub)를 독립 실행할 수 있어 검토·수정 후 업로드가 가능하다.
- **부분 실행**: Wiki, Issues, Time Entries를 `--only` 옵션으로 독립적으로 실행할 수 있다.
- **대용량 지원**: 일감 수천 건 이상을 처리할 수 있도록 페이지네이션 및 배치 처리를 구현한다.
- **투명성**: 각 GitHub Issue 본문 하단에 원본 Redmine URL을 기록하여 추적 가능성을 유지한다.

---

## 실행 순서 권장

GitHub Issue 번호 충돌 및 참조 오류를 최소화하기 위해 아래 순서로 실행한다.

```
1단계: 레이블 생성       (Labels)
2단계: 마일스톤 생성     (Milestones ← Redmine Versions)
3단계: Wiki 마이그레이션 (Repository 파일)
4단계: 일감 마이그레이션 (Issues + Comments + Attachments)
5단계: 작업 내역 이전    (CSV → Repository 파일)
```

---

## 제외 범위

- Redmine 포럼(Forum) / 뉴스(News) 마이그레이션 (GitHub Discussions 연동은 미포함)
- Redmine 연결된 SVN/Git 저장소 이전 (별도 git migration 작업 필요)
- Redmine 사용자 계정 자동 생성 (GitHub 계정은 수동 생성 후 매핑)
- GitHub Issue 번호와 Redmine 일감 번호의 1:1 동기화 (GitHub는 번호 지정 불가)
- Redmine 플러그인이 생성한 커스텀 필드의 완전한 변환 보장
- 마이그레이션 이후 Redmine↔GitHub 실시간 동기화
