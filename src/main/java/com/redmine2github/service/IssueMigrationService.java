package com.redmine2github.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redmine2github.cli.MigrationReport;
import com.redmine2github.cli.ProgressReporter;
import com.redmine2github.config.AppConfig;
import com.redmine2github.github.GitHubFileUploader;
import com.redmine2github.github.GitHubUploader;
import com.redmine2github.redmine.RedmineClient;
import com.redmine2github.redmine.model.RedmineIssue;
import com.redmine2github.service.model.LocalIssue;
import com.redmine2github.service.model.LocalLabel;
import com.redmine2github.service.model.LocalMilestone;
import com.redmine2github.state.MigrationState;
import com.redmine2github.state.MigrationStateManager;
import org.kohsuke.github.GHIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Issue 마이그레이션 서비스 — 2단계 구조.
 *
 * <ul>
 *   <li>{@link #fetch}: Phase 1 — Redmine Issues 수집·변환 → {@code output/issues-json/} JSON 저장</li>
 *   <li>{@link #upload}: Phase 2 — JSON 파일 읽기 → GitHub Issues/Labels/Milestones 생성</li>
 *   <li>{@link #run}: fetch + upload 전체 파이프라인</li>
 * </ul>
 *
 * <p>fetch 단계 출력 파일:
 * <ul>
 *   <li>{@code output/issues-json/{id}.json} — 변환된 Issue 데이터</li>
 *   <li>{@code output/issues-json/_labels.json} — GitHub Label 정의 목록</li>
 *   <li>{@code output/issues-json/_milestones.json} — GitHub Milestone 정의 목록</li>
 *   <li>{@code output/issues/{id}.md} — 이슈 Markdown 파일 (REDMINE_ISSUE_MD_FETCH=true 시)</li>
 *   <li>{@code output/issues.md} — 전체 이슈 목록 인덱스</li>
 * </ul>
 */
public class IssueMigrationService {

    private static final Logger log = LoggerFactory.getLogger(IssueMigrationService.class);

    private final AppConfig config;
    private final MigrationReport report;
    private final IssueConverter issueConverter;

    public IssueMigrationService(AppConfig config) {
        this(config, new MigrationReport(config.getProjectSlug()));
    }

    public IssueMigrationService(AppConfig config, MigrationReport report) {
        this.config = config;
        this.report = report;
        this.issueConverter = new IssueConverter(config, report);
    }

    // ── Phase 1: Redmine → 로컬 ───────────────────────────────────────────────

    /**
     * Redmine Issues를 수집하여 JSON 파일로 변환·저장한다.
     * Label / Milestone 정의도 함께 저장한다.
     */
    public void fetch(boolean resume, boolean retryFailed) {
        ProgressReporter progress = new ProgressReporter("Issues[fetch]");
        MigrationStateManager stateMgr = new MigrationStateManager(resume, config.getProjectCacheDir());
        MigrationState state = stateMgr.getState();

        RedmineClient redmine = new RedmineClient(config);
        ObjectMapper mapper = new ObjectMapper();

        Path issuesJsonDir = Path.of(config.getProjectOutputDir(), "issues-json");
        try {
            Files.createDirectories(issuesJsonDir);
        } catch (IOException e) {
            log.error("issues-json 디렉터리 생성 실패: {}", e.getMessage(), e);
            return;
        }

        Path issuesMdDir = null;
        if (config.isIssueMdFetch()) {
            issuesMdDir = Path.of(config.getProjectOutputDir(), "issues");
            try {
                Files.createDirectories(issuesMdDir);
            } catch (IOException e) {
                log.error("issues 디렉터리 생성 실패: {}", e.getMessage(), e);
            }
        }

        // Label 정의 저장
        if (!state.isLabelsFetched()) {
            try {
                saveLabelDefs(redmine, issuesJsonDir, mapper);
                state.markLabelsFetched();
                stateMgr.save();
            } catch (Exception e) {
                log.error("Label 정의 저장 실패: {}", e.getMessage(), e);
            }
        }

        // Milestone 정의 저장
        if (!state.isMilestonesFetched()) {
            try {
                saveMilestoneDefs(redmine, issuesJsonDir, mapper);
                state.markMilestonesFetched();
                stateMgr.save();
            } catch (Exception e) {
                log.error("Milestone 정의 저장 실패: {}", e.getMessage(), e);
            }
        }

        // Issues 수집 및 변환
        // fetchAllIssues(): 목록 API로 이슈 ID 목록 확보 (journals.details 미포함)
        // fetchIssueDetail(): 단건 API로 재조회하여 journals.details(이력) 포함한 전체 데이터 취득
        List<RedmineIssue> issueList = redmine.fetchAllIssues();
        progress.start(issueList.size());

        for (RedmineIssue basicIssue : issueList) {
            if (!retryFailed && state.isIssueFetched(basicIssue.getId())) {
                progress.itemSkipped("#" + basicIssue.getId());
                continue;
            }
            try {
                RedmineIssue issue = redmine.fetchIssueDetail(basicIssue.getId());
                LocalIssue local = issueConverter.convert(issue, redmine);
                mapper.writeValue(issuesJsonDir.resolve(issue.getId() + ".json").toFile(), local);
                if (issuesMdDir != null) {
                    saveIssueMd(local, issuesMdDir);
                }
                state.markIssueFetched(issue.getId());
                stateMgr.save();
                progress.itemDone("#" + issue.getId() + " " + issue.getSubject());
            } catch (Exception e) {
                log.error("Issue 수집 실패 [#{}]: {}", basicIssue.getId(), e.getMessage(), e);
                progress.itemFailed("#" + basicIssue.getId(), e.getMessage());
                report.addFailure("issue", "#" + basicIssue.getId(), e.getMessage());
            }
        }

        progress.finish();
        report.recordSection(progress.getSection(), progress.getTotal(),
                progress.getDone(), progress.getFailed(), progress.getSkipped());

        // 전체 이슈 목록 인덱스 파일 생성
        generateIssueIndex(issuesJsonDir, mapper);
    }

    private void saveIssueMd(LocalIssue local, Path issuesMdDir) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# [#").append(local.getRedmineId()).append("] ")
          .append(escapeMarkdown(local.getSubject())).append("\n\n");
        sb.append(local.getBody()).append("\n");

        if (!local.getComments().isEmpty()) {
            sb.append("\n---\n\n## Comments (").append(local.getComments().size()).append(")\n\n");
            for (String comment : local.getComments()) {
                sb.append(comment).append("\n\n---\n\n");
            }
        }

        Files.writeString(issuesMdDir.resolve(local.getRedmineId() + ".md"), sb.toString());
    }

    private void generateIssueIndex(Path issuesJsonDir, ObjectMapper mapper) {
        Path indexFile = Path.of(config.getProjectOutputDir(), "issues.md");
        List<LocalIssue> issues;
        try (Stream<Path> stream = Files.list(issuesJsonDir)) {
            issues = stream
                    .filter(p -> p.getFileName().toString().matches("\\d+\\.json"))
                    .sorted(Comparator.comparingInt(
                            p -> Integer.parseInt(p.getFileName().toString().replace(".json", ""))))
                    .map(p -> {
                        try {
                            return mapper.readValue(p.toFile(), LocalIssue.class);
                        } catch (IOException e) {
                            log.warn("Issue JSON 읽기 실패 [{}]: {}", p, e.getMessage());
                            return null;
                        }
                    })
                    .filter(i -> i != null)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("issues-json 디렉터리 읽기 실패: {}", e.getMessage(), e);
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(config.getProjectSlug()).append(" — Issues\n\n");
        sb.append("| ID | 제목 | status | tracker | category | 생성자 | 날짜 |\n");
        sb.append("|---|---|---|---|---|---|---|\n");

        for (LocalIssue issue : issues) {
            String idCell = config.isIssueMdFetch()
                    ? "[#" + issue.getRedmineId() + "](issues/" + issue.getRedmineId() + ".md)"
                    : "#" + issue.getRedmineId();

            String status   = extractLabel(issue.getLabels(), "status:");
            String tracker  = extractLabel(issue.getLabels(), "tracker:");
            String category = extractLabel(issue.getLabels(), "category:");

            String author    = issue.getAuthor()    != null ? issue.getAuthor()    : "";
            String createdOn = issue.getCreatedOn() != null ? issue.getCreatedOn() : "";
            if (createdOn.contains("T")) createdOn = createdOn.substring(0, createdOn.indexOf('T'));

            sb.append("| ").append(idCell)
              .append(" | ").append(escapeMdCell(issue.getSubject()))
              .append(" | ").append(escapeMdCell(status))
              .append(" | ").append(escapeMdCell(tracker))
              .append(" | ").append(escapeMdCell(category))
              .append(" | ").append(escapeMdCell(author))
              .append(" | ").append(escapeMdCell(createdOn))
              .append(" |\n");
        }

        try {
            Files.writeString(indexFile, sb.toString());
            log.info("이슈 인덱스 생성: {} ({}건)", indexFile, issues.size());
            System.out.println("  → issues.md 생성 완료 (" + issues.size() + "건)");
        } catch (IOException e) {
            log.error("issues.md 생성 실패: {}", e.getMessage(), e);
        }
    }

    private void saveLabelDefs(RedmineClient redmine, Path issuesJsonDir, ObjectMapper mapper) throws IOException {
        List<LocalLabel> labels = new ArrayList<>();
        labels.add(new LocalLabel("project:" + config.getProjectSlug(), "0e8a16",
                "Redmine 프로젝트: " + config.getProjectSlug()));
        redmine.fetchTrackers().forEach(t -> labels.add(new LocalLabel("tracker:" + t.getName(), "aaaaaa", "")));
        redmine.fetchIssuePriorities().forEach(p -> labels.add(new LocalLabel("priority:" + p.getName(), "fbca04", "")));
        List.of("New", "In Progress", "Resolved", "Closed", "Feedback").forEach(s ->
                labels.add(new LocalLabel("status:" + s, "00aabb", "")));
        redmine.fetchIssueCategories().forEach(c -> labels.add(new LocalLabel("category:" + c.getName(), "e8852c", "")));
        mapper.writeValue(issuesJsonDir.resolve("_labels.json").toFile(), labels);
        log.info("Label 정의 저장: {}개", labels.size());
    }

    private void saveMilestoneDefs(RedmineClient redmine, Path issuesDir, ObjectMapper mapper) throws IOException {
        List<LocalMilestone> milestones = new ArrayList<>();
        redmine.fetchVersions().forEach(v ->
                milestones.add(new LocalMilestone(v.getName(), v.getDescription(), v.getDueDate())));
        mapper.writeValue(issuesDir.resolve("_milestones.json").toFile(), milestones);
        log.info("Milestone 정의 저장: {}개", milestones.size());
    }

    // ── Phase 2: 로컬 → GitHub ────────────────────────────────────────────────

    /**
     * 로컬 JSON 파일을 읽어 GitHub에 Label, Milestone, Issue를 생성한다.
     * fetch를 먼저 실행하여 JSON 파일이 준비되어 있어야 한다.
     */
    public void upload(boolean resume, boolean retryFailed) {
        Path issuesJsonDir = Path.of(config.getProjectOutputDir(), "issues-json");
        if (!Files.exists(issuesJsonDir)) {
            log.warn("issues-json 디렉터리가 없습니다. 먼저 fetch를 실행하세요: {}", issuesJsonDir);
            System.out.println("  [Issues] 출력 디렉터리 없음 — fetch를 먼저 실행하세요.");
            return;
        }

        MigrationStateManager stateMgr = new MigrationStateManager(resume, config.getProjectCacheDir());
        MigrationState state = stateMgr.getState();

        GitHubUploader gh = new GitHubUploader(config);

        // REDMINE_ISSUE_MD_FETCH=true: GitHub Issues API 등록 생략, MD 파일만 업로드
        if (config.isIssueMdFetch()) {
            log.info("[Issues] REDMINE_ISSUE_MD_FETCH=true — GitHub Issues API 등록을 건너뜁니다. " +
                     "issue MD 파일만 repository에 업로드합니다.");
            System.out.println("  [Issues] REDMINE_ISSUE_MD_FETCH=true: GitHub Issues API 등록 생략 → MD 파일 업로드만 수행합니다.");
            uploadIssueMdFiles(state, stateMgr, gh, retryFailed);
            return;
        }

        ObjectMapper mapper = new ObjectMapper();
        ProgressReporter progress = new ProgressReporter("Issues[upload]");

        // Labels 생성
        if (!state.isLabelsDone()) {
            System.out.println("  → Label 생성 중...");
            createLabelsFromFile(issuesJsonDir, gh, mapper);
            state.markLabelsDone();
            stateMgr.save();
            System.out.println("  → Label 생성 완료");
        }

        // Milestones 생성
        if (!state.isMilestonesDone()) {
            System.out.println("  → Milestone 생성 중...");
            createMilestonesFromFile(issuesJsonDir, gh, mapper);
            state.markMilestonesDone();
            stateMgr.save();
            System.out.println("  → Milestone 생성 완료");
        }

        // Issues 업로드
        List<Path> issueFiles;
        try (Stream<Path> stream = Files.list(issuesJsonDir)) {
            issueFiles = stream
                    .filter(p -> p.getFileName().toString().matches("\\d+\\.json"))
                    .sorted(Comparator.comparingInt(
                            p -> Integer.parseInt(p.getFileName().toString().replace(".json", ""))))
                    .toList();
        } catch (IOException e) {
            log.error("issues-json 디렉터리 읽기 실패: {}", e.getMessage(), e);
            return;
        }

        progress.start(issueFiles.size());
        int processedSinceRateCheck = 0;

        for (Path issueFile : issueFiles) {
            LocalIssue local;
            try {
                local = mapper.readValue(issueFile.toFile(), LocalIssue.class);
            } catch (IOException e) {
                log.error("Issue JSON 읽기 실패 [{}]: {}", issueFile, e.getMessage());
                continue;
            }

            if (!retryFailed && state.isIssueDone(local.getRedmineId())) {
                progress.itemSkipped("#" + local.getRedmineId());
                continue;
            }

            try {
                uploadIssue(local, gh);
                state.markIssueDone(local.getRedmineId());
                stateMgr.save();
                progress.itemDone("#" + local.getRedmineId() + " " + local.getSubject());
            } catch (Exception e) {
                log.error("Issue 업로드 실패 [#{}]: {}", local.getRedmineId(), e.getMessage(), e);
                state.markIssueFailed(local.getRedmineId());
                stateMgr.save();
                progress.itemFailed("#" + local.getRedmineId(), e.getMessage());
                report.addFailure("issue-upload", "#" + local.getRedmineId(), e.getMessage());
            }

            if (++processedSinceRateCheck >= 20) {
                progress.reportRateLimit(gh.getRateLimitRemaining());
                processedSinceRateCheck = 0;
            }
        }

        progress.reportRateLimit(gh.getRateLimitRemaining());
        progress.finish();
        report.recordSection(progress.getSection(), progress.getTotal(),
                progress.getDone(), progress.getFailed(), progress.getSkipped());
    }

    private void uploadIssueMdFiles(MigrationState state, MigrationStateManager stateMgr,
                                     GitHubUploader gh, boolean retryFailed) {
        Path issuesMdDir  = Path.of(config.getProjectOutputDir(), "issues");
        Path issuesIndexFile = Path.of(config.getProjectOutputDir(), "issues.md");

        if (!Files.exists(issuesMdDir) && !Files.exists(issuesIndexFile)) {
            log.warn("issues/ 디렉터리와 issues.md 파일이 없습니다. fetch를 먼저 실행하세요.");
            return;
        }

        GitHubFileUploader fileUploader = new GitHubFileUploader(config, gh);
        String slug = config.getProjectSlug();

        // issues.md 인덱스 업로드
        if (Files.exists(issuesIndexFile)) {
            String repoPath = slug + "/issues.md";
            if (retryFailed || !state.isIssuesMdIndexDone()) {
                try {
                    fileUploader.uploadFile(issuesIndexFile, repoPath, "migrate: " + repoPath);
                    state.markIssuesMdIndexDone();
                    stateMgr.save();
                    log.info("issues.md 업로드 완료: {}", repoPath);
                } catch (Exception e) {
                    log.warn("issues.md 업로드 실패: {}", e.getMessage());
                }
            }
        }

        // issues/{id}.md 개별 파일 업로드
        if (!Files.exists(issuesMdDir)) return;

        List<Path> mdFiles;
        try (Stream<Path> stream = Files.list(issuesMdDir)) {
            mdFiles = stream
                    .filter(p -> p.getFileName().toString().matches("\\d+\\.md"))
                    .sorted(Comparator.comparingInt(
                            p -> Integer.parseInt(p.getFileName().toString().replace(".md", ""))))
                    .toList();
        } catch (IOException e) {
            log.error("issues/ 디렉터리 읽기 실패: {}", e.getMessage(), e);
            return;
        }

        ProgressReporter mdProgress = new ProgressReporter("Issues[upload-md]");
        mdProgress.start(mdFiles.size());

        for (Path mdFile : mdFiles) {
            String repoPath = slug + "/issues/" + mdFile.getFileName();
            if (!retryFailed && state.isIssueMdDone(repoPath)) {
                mdProgress.itemSkipped(repoPath);
                continue;
            }
            try {
                fileUploader.uploadFile(mdFile, repoPath, "migrate: " + repoPath);
                state.markIssueMdDone(repoPath);
                stateMgr.save();
                mdProgress.itemDone(repoPath);
            } catch (Exception e) {
                log.error("Issue MD 업로드 실패 [{}]: {}", repoPath, e.getMessage(), e);
                mdProgress.itemFailed(repoPath, e.getMessage());
                report.addFailure("issue-md-upload", repoPath, e.getMessage());
            }
        }

        mdProgress.finish();
        report.recordSection(mdProgress.getSection(), mdProgress.getTotal(),
                mdProgress.getDone(), mdProgress.getFailed(), mdProgress.getSkipped());
    }

    private void createLabelsFromFile(Path issuesDir, GitHubUploader gh, ObjectMapper mapper) {
        Path labelsFile = issuesDir.resolve("_labels.json");
        if (!Files.exists(labelsFile)) {
            log.warn("Label 정의 파일 없음: {}", labelsFile);
            return;
        }
        try {
            List<LocalLabel> labels = mapper.readValue(labelsFile.toFile(),
                    mapper.getTypeFactory().constructCollectionType(List.class, LocalLabel.class));
            labels.forEach(l -> gh.createLabel(l.getName(), l.getColor(), l.getDescription()));
        } catch (IOException e) {
            log.error("Label 정의 읽기 실패: {}", e.getMessage(), e);
        }
    }

    private void createMilestonesFromFile(Path issuesDir, GitHubUploader gh, ObjectMapper mapper) {
        Path milestonesFile = issuesDir.resolve("_milestones.json");
        if (!Files.exists(milestonesFile)) {
            log.warn("Milestone 정의 파일 없음: {}", milestonesFile);
            return;
        }
        try {
            List<LocalMilestone> milestones = mapper.readValue(milestonesFile.toFile(),
                    mapper.getTypeFactory().constructCollectionType(List.class, LocalMilestone.class));
            milestones.forEach(m -> gh.createMilestone(m.getName(), m.getDescription(), m.getDueDate()));
        } catch (IOException e) {
            log.error("Milestone 정의 읽기 실패: {}", e.getMessage(), e);
        }
    }

    private void uploadIssue(LocalIssue local, GitHubUploader gh) {
        String[] labels = local.getLabels().toArray(new String[0]);
        GHIssue ghIssue = gh.createIssue(local.getSubject(), local.getBody(), labels, null, local.getAssignee());

        for (String comment : local.getComments()) {
            gh.addComment(ghIssue, comment);
        }

        if (local.isClosed()) {
            gh.closeIssue(ghIssue);
        }
    }

    // ── 전체 파이프라인 ────────────────────────────────────────────────────────

    /** fetch → upload 전체 파이프라인. migrate-all 커맨드에서 사용한다. */
    public void run(boolean resume, boolean retryFailed) {
        fetch(resume, retryFailed);
        upload(resume, retryFailed);
    }

    /** labels 목록에서 prefix에 해당하는 값만 추출한다. (예: "status:신규" → "신규") */
    private static String extractLabel(List<String> labels, String prefix) {
        return labels.stream()
                .filter(l -> l.startsWith(prefix))
                .map(l -> l.substring(prefix.length()))
                .findFirst()
                .orElse("");
    }

    /** 마크다운 제목에서 특수문자를 이스케이프한다. */
    private static String escapeMarkdown(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("`", "\\`").replace("*", "\\*")
                .replace("[", "\\[").replace("]", "\\]");
    }

    /** 마크다운 테이블 셀 내 파이프·개행을 이스케이프한다. */
    private static String escapeMdCell(String s) {
        if (s == null) return "";
        return s.replace("|", "\\|").replace("\r\n", " ").replace("\n", " ").replace("\r", " ");
    }
}
