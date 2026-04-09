package com.redmine2github.service;

import com.redmine2github.cli.ProgressReporter;
import com.redmine2github.config.AppConfig;
import com.redmine2github.github.GitHubFileUploader;
import com.redmine2github.github.GitHubUploader;
import com.redmine2github.redmine.RedmineClient;
import com.redmine2github.redmine.model.RedmineTimeEntry;
import com.redmine2github.state.MigrationState;
import com.redmine2github.state.MigrationStateManager;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 작업 내역(Time Entry) 마이그레이션 서비스 — 2단계 구조.
 *
 * <ul>
 *   <li>{@link #fetch}: Phase 1 — Redmine Time Entries 수집 → {@code output/_migration/time_entries.csv}</li>
 *   <li>{@link #upload}: Phase 2 — CSV 파일 → GitHub Repository push</li>
 *   <li>{@link #run}: fetch + upload 전체 파이프라인</li>
 * </ul>
 */
public class TimeEntryMigrationService {

    private static final Logger log = LoggerFactory.getLogger(TimeEntryMigrationService.class);
    private static final String LOCAL_PATH = "_migration/time_entries.csv";
    private static final String REPO_PATH  = "_migration/time_entries.csv";

    private final AppConfig config;

    public TimeEntryMigrationService(AppConfig config) {
        this.config = config;
    }

    // ── Phase 1: Redmine → 로컬 ───────────────────────────────────────────────

    /**
     * Redmine 작업 내역을 수집하여 로컬 CSV 파일로 저장한다.
     */
    public void fetch(boolean resume, boolean retryFailed) {
        ProgressReporter progress = new ProgressReporter("TimeEntries[fetch]");
        MigrationStateManager stateMgr = new MigrationStateManager(resume, config.getProjectCacheDir());
        MigrationState state = stateMgr.getState();

        if (state.isTimeEntriesFetched() && !retryFailed) {
            System.out.println("  [TimeEntries] fetch 이미 완료 — 스킵");
            log.info("작업 내역 fetch 이미 완료 — 스킵");
            return;
        }

        RedmineClient redmine = new RedmineClient(config);
        List<RedmineTimeEntry> entries = redmine.fetchAllTimeEntries();
        if (entries.isEmpty()) {
            log.info("작업 내역 없음 (권한 부족 또는 데이터 없음) — 스킵");
            state.markTimeEntriesFetched();
            stateMgr.save();
            return;
        }
        progress.start(entries.size());

        Path csvPath = Path.of(config.getProjectOutputDir(), LOCAL_PATH);
        try {
            Files.createDirectories(csvPath.getParent());
            writeCsv(csvPath, entries);
        } catch (IOException e) {
            log.error("CSV 저장 실패: {}", e.getMessage(), e);
            progress.itemFailed("CSV 저장", e.getMessage());
            return;
        }

        progress.itemDone("time_entries.csv (" + entries.size() + "건)");
        state.markTimeEntriesFetched();
        stateMgr.save();
        log.info("작업 내역 CSV 로컬 저장 완료: {}", csvPath);
        progress.finish();
    }

    // ── Phase 2: 로컬 → GitHub ────────────────────────────────────────────────

    /**
     * 로컬 CSV 파일을 GitHub Repository에 업로드한다.
     * fetch를 먼저 실행하여 CSV 파일이 준비되어 있어야 한다.
     */
    public void upload(boolean resume, boolean retryFailed) {
        Path csvPath = Path.of(config.getProjectOutputDir(), LOCAL_PATH);
        if (!Files.exists(csvPath)) {
            log.warn("Time Entries CSV 파일이 없습니다. 먼저 fetch를 실행하세요: {}", csvPath);
            System.out.println("  [TimeEntries] CSV 파일 없음 — fetch를 먼저 실행하세요.");
            return;
        }

        MigrationStateManager stateMgr = new MigrationStateManager(resume, config.getProjectCacheDir());
        MigrationState state = stateMgr.getState();

        if (state.isTimeEntriesDone() && !retryFailed) {
            System.out.println("  [TimeEntries] upload 이미 완료 — 스킵");
            return;
        }

        ProgressReporter progress = new ProgressReporter("TimeEntries[upload]");
        progress.start(1);

        GitHubUploader gh = new GitHubUploader(config);
        GitHubFileUploader fileUploader = new GitHubFileUploader(config, gh);

        try {
            fileUploader.uploadFile(csvPath, REPO_PATH, "migrate: time entries CSV");
            progress.reportRateLimit(gh.getRateLimitRemaining());
            progress.itemDone("time_entries.csv");
            state.markTimeEntriesDone();
            stateMgr.save();
            log.info("작업 내역 CSV GitHub 업로드 완료: {}", REPO_PATH);
        } catch (Exception e) {
            log.error("Time Entries CSV 업로드 실패: {}", e.getMessage(), e);
            progress.itemFailed("time_entries.csv", e.getMessage());
        }

        progress.finish();
    }

    // ── 전체 파이프라인 ────────────────────────────────────────────────────────

    /** fetch → upload 전체 파이프라인. migrate-all 커맨드에서 사용한다. */
    public void run(boolean resume, boolean retryFailed) {
        fetch(resume, retryFailed);
        upload(resume, retryFailed);
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────────

    private void writeCsv(Path path, List<RedmineTimeEntry> entries) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader("redmine_issue_id", "date", "hours", "activity", "user", "comment")
                .build();
        try (CSVPrinter printer = new CSVPrinter(new FileWriter(path.toFile()), format)) {
            for (RedmineTimeEntry e : entries) {
                printer.printRecord(
                    e.getIssueId(),
                    e.getSpentOn(),
                    e.getHours(),
                    e.getActivity(),
                    e.getUserLogin(),
                    e.getComment()
                );
            }
        }
    }
}
