package com.redmine2github.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redmine2github.cli.ProgressReporter;
import com.redmine2github.config.AppConfig;
import com.redmine2github.converter.TextileConverter;
import com.redmine2github.github.GitHubUploader;
import com.redmine2github.redmine.RedmineClient;
import com.redmine2github.redmine.model.RedmineIssue;
import com.redmine2github.redmine.model.RedmineJournal;
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
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Issue 마이그레이션 서비스 — 2단계 구조.
 *
 * <ul>
 *   <li>{@link #fetch}: Phase 1 — Redmine Issues 수집·변환 → {@code output/issues/} JSON 저장</li>
 *   <li>{@link #upload}: Phase 2 — JSON 파일 읽기 → GitHub Issues/Labels/Milestones 생성</li>
 *   <li>{@link #run}: fetch + upload 전체 파이프라인</li>
 * </ul>
 *
 * <p>fetch 단계 출력 파일:
 * <ul>
 *   <li>{@code output/issues/{id}.json} — 변환된 Issue 데이터</li>
 *   <li>{@code output/issues/_labels.json} — GitHub Label 정의 목록</li>
 *   <li>{@code output/issues/_milestones.json} — GitHub Milestone 정의 목록</li>
 * </ul>
 */
public class IssueMigrationService {

    private static final Logger log = LoggerFactory.getLogger(IssueMigrationService.class);

    private final AppConfig config;
    private final TextileConverter converter = new TextileConverter();

    public IssueMigrationService(AppConfig config) {
        this.config = config;
    }

    // ── Phase 1: Redmine → 로컬 ───────────────────────────────────────────────

    /**
     * Redmine Issues를 수집하여 JSON 파일로 변환·저장한다.
     * Label / Milestone 정의도 함께 저장한다.
     */
    public void fetch(boolean resume, boolean retryFailed) {
        ProgressReporter progress = new ProgressReporter("Issues[fetch]");
        MigrationStateManager stateMgr = new MigrationStateManager(resume);
        MigrationState state = stateMgr.getState();

        RedmineClient redmine = new RedmineClient(config);
        ObjectMapper mapper = new ObjectMapper();

        Path issuesDir = Path.of(config.getProjectOutputDir(), "issues");
        try {
            Files.createDirectories(issuesDir);
        } catch (IOException e) {
            log.error("Issues 디렉터리 생성 실패: {}", e.getMessage(), e);
            return;
        }

        // Label 정의 저장
        if (!state.isLabelsFetched()) {
            try {
                saveLabelDefs(redmine, issuesDir, mapper);
                state.markLabelsFetched();
                stateMgr.save();
            } catch (Exception e) {
                log.error("Label 정의 저장 실패: {}", e.getMessage(), e);
            }
        }

        // Milestone 정의 저장
        if (!state.isMilestonesFetched()) {
            try {
                saveMilestoneDefs(redmine, issuesDir, mapper);
                state.markMilestonesFetched();
                stateMgr.save();
            } catch (Exception e) {
                log.error("Milestone 정의 저장 실패: {}", e.getMessage(), e);
            }
        }

        // Issues 수집 및 변환
        List<RedmineIssue> issues = redmine.fetchAllIssues();
        progress.start(issues.size());

        for (RedmineIssue issue : issues) {
            if (!retryFailed && state.isIssueFetched(issue.getId())) {
                progress.itemSkipped("#" + issue.getId());
                continue;
            }
            try {
                LocalIssue local = convertIssue(issue);
                mapper.writeValue(issuesDir.resolve(issue.getId() + ".json").toFile(), local);
                state.markIssueFetched(issue.getId());
                stateMgr.save();
                progress.itemDone("#" + issue.getId() + " " + issue.getSubject());
            } catch (Exception e) {
                log.error("Issue 수집 실패 [#{}]: {}", issue.getId(), e.getMessage(), e);
                progress.itemFailed("#" + issue.getId(), e.getMessage());
            }
        }

        progress.finish();
    }

    private void saveLabelDefs(RedmineClient redmine, Path issuesDir, ObjectMapper mapper) throws IOException {
        List<LocalLabel> labels = new ArrayList<>();
        redmine.fetchTrackers().forEach(t -> labels.add(new LocalLabel("tracker:" + t.getName(), "ee0701", "")));
        redmine.fetchIssuePriorities().forEach(p -> labels.add(new LocalLabel("priority:" + p.getName(), "fbca04", "")));
        List.of("New", "In Progress", "Resolved", "Closed", "Feedback").forEach(s ->
                labels.add(new LocalLabel("status:" + s, "0075ca", "")));
        redmine.fetchIssueCategories().forEach(c -> labels.add(new LocalLabel("category:" + c.getName(), "e4e669", "")));
        mapper.writeValue(issuesDir.resolve("_labels.json").toFile(), labels);
        log.info("Label 정의 저장: {}개", labels.size());
    }

    private void saveMilestoneDefs(RedmineClient redmine, Path issuesDir, ObjectMapper mapper) throws IOException {
        List<LocalMilestone> milestones = new ArrayList<>();
        redmine.fetchVersions().forEach(v ->
                milestones.add(new LocalMilestone(v.getName(), v.getDescription(), v.getDueDate())));
        mapper.writeValue(issuesDir.resolve("_milestones.json").toFile(), milestones);
        log.info("Milestone 정의 저장: {}개", milestones.size());
    }

    private LocalIssue convertIssue(RedmineIssue issue) {
        Map<String, String> userMap = config.getUserMapping();

        String body     = buildIssueBody(issue, userMap);
        List<String> labels  = buildLabels(issue);
        String assignee = issue.getAssigneeLogin() != null
                        ? userMap.getOrDefault(issue.getAssigneeLogin(), null)
                        : null;

        List<String> comments = issue.getJournals().stream()
                .filter(j -> !j.getNotes().isBlank())
                .map(j -> buildCommentBody(j, userMap))
                .collect(Collectors.toList());

        return new LocalIssue(
                issue.getId(), issue.getSubject(), body,
                labels, assignee, comments, isClosed(issue.getStatus())
        );
    }

    // ── Phase 2: 로컬 → GitHub ────────────────────────────────────────────────

    /**
     * 로컬 JSON 파일을 읽어 GitHub에 Label, Milestone, Issue를 생성한다.
     * fetch를 먼저 실행하여 JSON 파일이 준비되어 있어야 한다.
     */
    public void upload(boolean resume, boolean retryFailed) {
        Path issuesDir = Path.of(config.getProjectOutputDir(), "issues");
        if (!Files.exists(issuesDir)) {
            log.warn("Issues 출력 디렉터리가 없습니다. 먼저 fetch를 실행하세요: {}", issuesDir);
            System.out.println("  [Issues] 출력 디렉터리 없음 — fetch를 먼저 실행하세요.");
            return;
        }

        ProgressReporter progress = new ProgressReporter("Issues[upload]");
        MigrationStateManager stateMgr = new MigrationStateManager(resume);
        MigrationState state = stateMgr.getState();

        ObjectMapper mapper = new ObjectMapper();
        GitHubUploader gh = new GitHubUploader(config);

        // Labels 생성
        if (!state.isLabelsDone()) {
            System.out.println("  → Label 생성 중...");
            createLabelsFromFile(issuesDir, gh, mapper);
            state.markLabelsDone();
            stateMgr.save();
            System.out.println("  → Label 생성 완료");
        }

        // Milestones 생성
        if (!state.isMilestonesDone()) {
            System.out.println("  → Milestone 생성 중...");
            createMilestonesFromFile(issuesDir, gh, mapper);
            state.markMilestonesDone();
            stateMgr.save();
            System.out.println("  → Milestone 생성 완료");
        }

        // Issues 업로드
        List<Path> issueFiles;
        try {
            issueFiles = Files.list(issuesDir)
                    .filter(p -> p.getFileName().toString().matches("\\d+\\.json"))
                    .sorted(Comparator.comparingInt(
                            p -> Integer.parseInt(p.getFileName().toString().replace(".json", ""))))
                    .toList();
        } catch (IOException e) {
            log.error("Issues 디렉터리 읽기 실패: {}", e.getMessage(), e);
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
            }

            if (++processedSinceRateCheck >= 20) {
                progress.reportRateLimit(gh.getRateLimitRemaining());
                processedSinceRateCheck = 0;
            }
        }

        progress.reportRateLimit(gh.getRateLimitRemaining());
        progress.finish();
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

    // ── 변환 헬퍼 ─────────────────────────────────────────────────────────────

    private String buildIssueBody(RedmineIssue issue, Map<String, String> userMap) {
        String author = userMap.getOrDefault(
                issue.getAssigneeLogin(),
                issue.getAssigneeLogin() != null ? issue.getAssigneeLogin() : "unknown"
        );
        String md = converter.convert(issue.getDescription());
        return String.format("""
                > **[Redmine #%d]** | 작성: %s | 날짜: %s

                %s
                """, issue.getId(), author, issue.getCreatedOn(), md);
    }

    private String buildCommentBody(RedmineJournal journal, Map<String, String> userMap) {
        String author = userMap.getOrDefault(journal.getAuthorLogin(), journal.getAuthorLogin());
        String md = converter.convert(journal.getNotes());
        return String.format("> **%s** (%s)\n\n%s", author, journal.getCreatedOn(), md);
    }

    private List<String> buildLabels(RedmineIssue issue) {
        List<String> labels = new ArrayList<>();
        labels.add("tracker:" + issue.getTracker());
        labels.add("priority:" + issue.getPriority());
        labels.add("status:" + issue.getStatus());
        if (issue.getCategory() != null) labels.add("category:" + issue.getCategory());
        return labels;
    }

    private boolean isClosed(String status) {
        return "Closed".equalsIgnoreCase(status) || "Resolved".equalsIgnoreCase(status);
    }
}
