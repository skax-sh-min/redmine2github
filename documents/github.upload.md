# GitHub 업로드 방식 비교

로컬에 저장된 `.md` 파일과 첨부파일을 GitHub Repository에 올리는 방법은 크게 두 가지다.

---

## 방안 1: GitHub REST API (kohsuke/github-api)

### 개요
GitHub의 Contents API(`PUT /repos/{owner}/{repo}/contents/{path}`)를 호출하여 파일을 한 건씩 업로드한다.
Java에서는 `org.kohsuke:github-api` 라이브러리로 API를 추상화해서 사용할 수 있다.

### 동작 원리
```
파일 1개 업로드 = API 호출 1회 (Base64 인코딩된 파일 내용 + 커밋 메시지 전송)
```

파일이 이미 존재하면 현재 파일의 SHA를 함께 전송해야 업데이트가 가능하다.

### 코드 예시 (Java)
```java
GitHub github = new GitHubBuilder().withOAuthToken(token).build();
GHRepository repo = github.getRepository("owner/repo");

byte[] content = Files.readAllBytes(Path.of("output/docs/PageName.md"));

// 신규 파일
repo.createContent()
    .path("docs/PageName.md")
    .message("migrate: PageName")
    .content(content)
    .commit();

// 기존 파일 업데이트 시 SHA 필요
GHContent existing = repo.getFileContent("docs/PageName.md");
repo.createContent()
    .path("docs/PageName.md")
    .message("migrate: PageName")
    .content(content)
    .sha(existing.getSha())
    .commit();
```

### Gradle 의존성
```groovy
implementation 'org.kohsuke:github-api:1.321'
implementation 'com.squareup.okhttp3:okhttp:4.12.0'  // 내부 HTTP 클라이언트
```

### 장점
| # | 내용 |
|---|------|
| 1 | **Git 설치 불필요** — JVM만 있으면 실행 가능 |
| 2 | **로컬 git 저장소 불필요** — 임시 clone 디렉터리 관리가 없음 |
| 3 | **파일 단위 제어** — 특정 파일만 선택적으로 업로드·업데이트하기 쉬움 |
| 4 | **멱등성 구현 용이** — SHA 비교로 변경된 파일만 업로드 가능 |
| 5 | **순수 Java** — 외부 프로세스 의존 없음 |

### 단점
| # | 내용 |
|---|------|
| 1 | **파일당 API 호출 1회** — 파일 수가 많으면 Rate Limit(인증 시 시간당 5,000회) 소진 위험 |
| 2 | **파일 크기 제한** — Contents API는 파일당 최대 **100MB**, 단일 요청 본문은 **1MB** 권장 |
| 3 | **커밋 분산** — 파일마다 개별 커밋이 생성되어 git 히스토리가 지저분해질 수 있음 |
| 4 | **디렉터리 생성 불가** — 파일 경로에 `/`를 포함하면 자동 생성되지만, 빈 디렉터리는 만들 수 없음 |

### Rate Limit 대응 전략
- 인증 토큰(PAT) 필수 사용 (미인증 시 시간당 60회로 제한)
- 업로드 사이에 `Thread.sleep()` 또는 exponential backoff 적용
- `github.getRateLimit().getRemaining()`으로 잔여 횟수 모니터링

---

## 방안 2: JGit (git clone → commit → push)

### 개요
`org.eclipse.jgit` 라이브러리를 사용해 Repository를 로컬에 clone하고, 파일을 복사한 뒤 `git add → commit → push`를 수행한다.
표준 git 워크플로우를 Java에서 그대로 실행하는 방식이다.

### 동작 원리
```
git clone → 파일 복사(로컬) → git add → git commit → git push
```

### 코드 예시 (Java)
```java
// 1. Clone
Git git = Git.cloneRepository()
    .setURI("https://github.com/owner/repo.git")
    .setDirectory(new File("tmp/repo"))
    .setCredentialsProvider(new UsernamePasswordCredentialsProvider("token", ""))
    .call();

// 2. 파일 복사 (output/ → tmp/repo/docs/)
Files.copy(Path.of("output/docs/PageName.md"),
           Path.of("tmp/repo/docs/PageName.md"),
           StandardCopyOption.REPLACE_EXISTING);

// 3. add & commit
git.add().addFilepattern("docs/").call();
git.commit().setMessage("migrate: Redmine Wiki pages").call();

// 4. push
git.push()
   .setCredentialsProvider(new UsernamePasswordCredentialsProvider("token", ""))
   .call();

git.close();
```

### Gradle 의존성
```groovy
implementation 'org.eclipse.jgit:org.eclipse.jgit:6.9.0.202403050737-r'
```

### 장점
| # | 내용 |
|---|------|
| 1 | **단일 커밋으로 전체 파일 이전** — git 히스토리가 깔끔함 |
| 2 | **API Rate Limit 무관** — push는 git 프로토콜을 사용하므로 Contents API 호출 횟수와 무관 |
| 3 | **대용량 파일 처리 유리** — git LFS 연동도 가능 |
| 4 | **표준 git 워크플로우** — 브랜치 생성 후 PR을 여는 흐름도 쉽게 구현 가능 |
| 5 | **오프라인 작업 가능** — push 전까지 네트워크 없이 로컬 커밋 작업 가능 |

### 단점
| # | 내용 |
|---|------|
| 1 | **초기 clone 필요** — Repository가 크면 clone 시간이 오래 걸림 (`--depth 1`로 완화 가능) |
| 2 | **로컬 임시 디렉터리 관리** — clone한 디렉터리를 작업 후 정리해야 함 |
| 3 | **JGit API 복잡도** — 네이티브 git CLI보다 API가 verbose하고 예외 처리가 번거로움 |
| 4 | **충돌 처리** — 원격에 변경사항이 있으면 pull/rebase 로직이 추가로 필요 |

### shallow clone으로 속도 개선
```java
Git git = Git.cloneRepository()
    .setURI("https://github.com/owner/repo.git")
    .setDirectory(new File("tmp/repo"))
    .setDepth(1)  // shallow clone
    .setCredentialsProvider(...)
    .call();
```

---

## 방안 비교 요약

| 항목 | 방안 1: GitHub API | 방안 2: JGit |
|------|-------------------|-------------|
| 외부 의존 | 없음 (순수 Java) | 없음 (순수 Java) |
| git 히스토리 | 파일마다 개별 커밋 | 단일(또는 소수) 커밋 |
| Rate Limit | 파일 수에 비례해 소진 | push 1회, 사실상 무관 |
| 파일 크기 제한 | 100MB / 요청당 1MB 권장 | git 제한(일반적으로 100MB) |
| 로컬 저장소 필요 | 불필요 | clone 임시 디렉터리 필요 |
| 부분 업데이트 | SHA 비교로 간단 | diff/status로 가능하나 구현 복잡 |
| 구현 난이도 | 낮음 | 중간 |
| 적합한 규모 | 수백 파일 이하 | 수천 파일 이상 또는 대용량 |

---

## 선택 가이드

```
마이그레이션 대상 파일이 500개 미만이고,
git 히스토리보다 구현 단순성이 중요하다면  → 방안 1 (GitHub API)

파일 수가 많거나, 단일 커밋으로 깔끔하게
이전하고 싶다면                              → 방안 2 (JGit)
```

> 두 방안 모두 **Personal Access Token(PAT)** 또는 **GitHub App Token** 인증이 필요하다.
> 토큰 권한: `repo` 스코프(private repository) 또는 `public_repo` 스코프(public repository).
