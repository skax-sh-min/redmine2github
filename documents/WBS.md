# WBS: Redmine → GitHub 마이그레이션 도구

> 상태: ⬜ 미시작 / 🔄 진행중 / ✅ 완료 / ⏸ 보류

---

## 1. 초기 설정

| ID | 작업 | 담당 | 상태 | 비고 |
|----|------|------|------|------|
| 1.1 | Gradle 프로젝트 생성 (Java 21) | | ✅ | `build.gradle`, `settings.gradle` |
| 1.2 | 의존성 정의 (OkHttp, Jackson, picocli, JGit, github-api, Commons CSV 등) | | ✅ | `build.gradle` |
| 1.3 | 패키지 구조 설계 | | ✅ | cli / config / redmine / github / converter / service / state |
| 1.4 | `.env` 및 설정 파일 로딩 구현 (`dotenv-java`) | | ✅ | `AppConfig.java`, `.env.example` |
| 1.5 | `user-mapping.yml` / `label-colors.yml` 스키마 정의 | | ✅ | `*.example` 파일 |
| 1.6 | 로깅 설정 (SLF4J + Logback, `migration.log` 파일 출력) | | ✅ | `logback.xml` |
| 1.7 | `migration-state.json` 구조 정의 및 읽기/쓰기 구현 | | ✅ | `MigrationState.java`, `MigrationStateManager.java` |

---

## 2. 백엔드 개발

### 2.1 Redmine API 클라이언트

| ID | 작업 | 담당 | 상태 | 비고 |
|----|------|------|------|------|
| 2.1.1 | Redmine API 인증 및 기본 HTTP 클라이언트 구현 (OkHttp) | | ✅ | `RedmineClient.java` |
| 2.1.2 | Wiki 페이지 목록 및 본문 수집 | | ✅ | `fetchAllWikiPages()` |
| 2.1.3 | 일감(Issue) 목록 수집 (페이지네이션) | | ✅ | `fetchAllIssues()` |
| 2.1.4 | 일감 댓글(Journal) 수집 | | ✅ | `include=journals` 파라미터 |
| 2.1.5 | 첨부파일 다운로드 | | ✅ | `RedmineClient.downloadAttachment()` 독립 메서드, Basic Auth 지원 |
| 2.1.6 | 작업 내역(Time Entry) 수집 | | ✅ | `fetchAllTimeEntries()` |
| 2.1.7 | 버전 / 카테고리 / 트래커 / 우선순위 수집 | | ✅ | `fetchTrackers()`, `fetchIssueCategories()`, `fetchIssuePriorities()`, `RedmineNamedItem` |
| 2.1.8 | 수집 데이터 `cache/` 로컬 저장 | | ✅ | `CacheManager.java`, 모든 fetch 메서드에 캐시 통합; wiki 캐시는 상세 노드 전체 저장 |
| 2.1.9 | Redmine API 요청 속도 제한 (`REQUEST_DELAY_MS`) | | ✅ | `RedmineClient.throttle()`, 기본값 10ms, `get()` 및 `downloadAttachment()` 적용 |

### 2.2 변환 엔진

| ID | 작업 | 담당 | 상태 | 비고 |
|----|------|------|------|------|
| 2.2.1 | Textile → GFM 변환 구현 (textile4j 또는 pandoc) | | ✅ | `TextileConverter.java` (정규식 기반) |
| 2.2.2 | `[[PageName]]` 내부 링크 재작성 | | ✅ | `LinkRewriter.java` |
| 2.2.3 | 첨부파일 경로 참조 갱신 | | ✅ | `AttachmentPathRewriter.java`, `AttachmentPathRewriterTest.java` |
| 2.2.4 | Issue 본문 헤더 생성 (원본 URL, 작성자, 날짜) | | ✅ | `IssueMigrationService.buildIssueBody()` |
| 2.2.5 | 작업 내역 → CSV 변환 (`Apache Commons CSV`) | | ✅ | `TimeEntryMigrationService.writeCsv()` |
| 2.2.6 | 사용자 매핑 적용 (user-mapping.yml 연동) | | ✅ | `IssueMigrationService` + `AppConfig` |

### 2.3 GitHub 업로드

| ID | 작업 | 담당 | 상태 | 비고 |
|----|------|------|------|------|
| 2.3.1 | GitHub API 인증 구현 (PAT) | | ✅ | `GitHubUploader.java` |
| 2.3.2 | Label 생성 (트래커 / 우선순위 / 상태 / 카테고리) | | ✅ | `createLabel()`, `createLabels()` |
| 2.3.3 | Milestone 생성 (Redmine Version 변환) | | ✅ | `createMilestone()`, `createMilestones()` |
| 2.3.4 | GitHub Issue 생성 (본문 + Label + Milestone + Assignee) | | ✅ | `createIssue()` |
| 2.3.5 | Issue Comment 생성 (댓글 + 변경 이력) | | ✅ | `addComment()` |
| 2.3.6 | Wiki `.md` 파일 Repository push (API 또는 JGit 선택) | | ✅ | `GitHubFileUploader.java` |
| 2.3.7 | 첨부파일 Repository push | | ✅ | `GitHubFileUploader.uploadFile()` |
| 2.3.8 | 작업 내역 CSV Repository push | | ✅ | `TimeEntryMigrationService` |
| 2.3.9 | Rate Limit 감지 및 exponential backoff 처리 | | ✅ | `RateLimitAwareExecutor.java`, `GitHubUploader` + `GitHubFileUploader` 연결 |
| 2.3.10 | 멱등성 처리 (migration-state.json 기반 중복 방지) | | ✅ | `MigrationStateManager` |

---

## 3. 프런트엔드 개발 (CLI)

| ID | 작업 | 담당 | 상태 | 비고 |
|----|------|------|------|------|
| 3.1 | `picocli` 기반 메인 커맨드 구조 설계 | | ✅ | `Redmine2GithubApp.java` |
| 3.2 | `migrate-all` 전체 실행 커맨드 구현 | | ✅ | `MigrateAllCommand.java` |
| 3.3 | `--only wiki / issues / time-entries` 부분 실행 옵션 | | ✅ | `@Option --only` |
| 3.4 | `--resume` 중단 후 재개 옵션 | | ✅ | `@Option --resume` |
| 3.5 | `--retry-failed` 실패 항목 재처리 옵션 | | ✅ | `@Option --retry-failed` |
| 3.6 | `fetch` / `upload` 2단계 커맨드 구현 | | ✅ | `FetchCommand.java`, `UploadCommand.java` (기존 `--dry-run` 대체) |
| 3.7 | 진행 상황 콘솔 출력 (처리 건수 / 잔여 Rate Limit 표시) | | ✅ | `ProgressReporter.java`, 10/20건마다 Rate Limit 잔여 표시 |
| 3.8 | `user-mapping.yml` 초안 자동 생성 커맨드 (`generate-mapping`) | | ✅ | `GenerateMappingCommand.java` |
| 3.9 | `--help` 및 사용 예시 문서화 | | ✅ | 모든 커맨드에 `footer` 예시 추가 |

---

## 4. 통합 및 배포

| ID | 작업 | 담당 | 상태 | 비고 |
|----|------|------|------|------|
| 4.1 | 단위 테스트 작성 (변환 엔진, 매핑 로직) | | ✅ | `TextileConverterTest`, `LinkRewriterTest` |
| 4.2 | Redmine API 연동 통합 테스트 | | ✅ | `RedmineClientTest.java` (MockWebServer, 8개 케이스) |
| 4.3 | GitHub API 연동 통합 테스트 | | ✅ | `GitHubUploaderTest.java` (Mockito, 10개 케이스) |
| 4.4 | 전체 E2E 시나리오 테스트 (소규모 프로젝트 대상) | | ✅ | `MigrationE2ETest.java` (dry-run, 9개 케이스) |
| 4.5 | Gradle `shadowJar` (fat JAR) 빌드 설정 | | ✅ | `build.gradle` shadowJar 플러그인 |
| 4.6 | 실행 스크립트 작성 (`scripts/` 폴더 구조화) | | ✅ | `fetch.sh/bat`, `upload.sh/bat`, `migrate.sh/bat` (루트 래퍼 포함) |
| 4.7 | README / UserManual 작성 | | ✅ | `README.md`, `documents/UserManual.md` (2단계 구조 반영) |
| 4.8 | 실제 프로젝트 대상 파일럿 마이그레이션 수행 | | ⬜ | |
| 4.9 | 파일럿 결과 검토 및 오류 수정 | | ⬜ | |
| 4.10 | 전체 마이그레이션 실행 | | ⬜ | |
