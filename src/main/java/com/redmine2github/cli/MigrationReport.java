package com.redmine2github.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 마이그레이션 실행 결과를 누적하고 {@code _migration/REPORT.md} 로 출력한다.
 *
 * <p>서비스 계층(Wiki/Issue/TimeEntry)이 각 단계에서 이 객체에 통계·실패·경고를 기록하고,
 * CLI 계층이 마지막에 {@link #writeToFile(Path)}를 호출해 파일로 저장한다.</p>
 *
 * <h3>스레드 안전성</h3>
 * 모든 mutator 는 {@code synchronized} — 향후 병렬 fetch 도입 시에도 안전.
 */
public class MigrationReport {

    private static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String projectSlug;
    private final LocalDateTime startedAt = LocalDateTime.now();
    private LocalDateTime finishedAt;

    /** 섹션명("Wiki[fetch]" 등) → [total, success, failed, skipped] */
    private final Map<String, int[]> sections = new LinkedHashMap<>();

    /** [데이터 누락] 이벤트 — 403 등 접근 권한 문제로 수집되지 못한 항목 */
    private final List<String> dataMissingWarnings = new ArrayList<>();

    /** [type, identifier, reason] — 처리 실패 항목 목록 */
    private final List<String[]> failures = new ArrayList<>();

    /** Redmine 사용자명 중 user-mapping.yml 에 없는 것 */
    private final LinkedHashSet<String> unmappedUsers = new LinkedHashSet<>();

    /** 수집·업로드된 첨부파일 합계 */
    private long attachmentCount;
    private long attachmentTotalBytes;

    public MigrationReport(String projectSlug) {
        this.projectSlug = projectSlug;
    }

    // ── 기록 메서드 (서비스 계층에서 호출) ──────────────────────────────────

    /**
     * 섹션 실행 결과를 기록한다. 같은 섹션을 여러 번 호출하면 누적된다.
     *
     * @param name    섹션명 (예: "Wiki[fetch]", "Issues[upload]")
     * @param total   처리 시도 건수
     * @param success 성공 건수
     * @param failed  실패 건수
     * @param skipped 스킵 건수
     */
    public synchronized void recordSection(String name, int total, int success,
                                           int failed, int skipped) {
        sections.merge(name, new int[]{total, success, failed, skipped},
                (a, b) -> { a[0] += b[0]; a[1] += b[1]; a[2] += b[2]; a[3] += b[3]; return a; });
    }

    /** 403 등 접근 권한 문제로 데이터가 누락된 경우를 기록한다. */
    public synchronized void addDataMissing(String message) {
        dataMissingWarnings.add(message);
    }

    /**
     * 항목 처리 실패를 기록한다.
     *
     * @param type   항목 종류 (예: "wiki", "issue", "attachment")
     * @param id     항목 식별자 (예: "Home", "#1234", "image.png")
     * @param reason 실패 원인 메시지
     */
    public synchronized void addFailure(String type, String id, String reason) {
        failures.add(new String[]{type, id, reason != null ? reason : "(원인 없음)"});
    }

    /** user-mapping.yml 에 없는 Redmine 사용자명을 기록한다. */
    public synchronized void addUnmappedUser(String redmineLogin) {
        if (redmineLogin != null && !redmineLogin.isBlank()) {
            unmappedUsers.add(redmineLogin);
        }
    }

    /**
     * 첨부파일 통계를 누적한다. 여러 서비스에서 호출해도 합산된다.
     *
     * @param count      파일 수
     * @param totalBytes 총 바이트 수
     */
    public synchronized void addAttachmentStats(long count, long totalBytes) {
        this.attachmentCount += count;
        this.attachmentTotalBytes += totalBytes;
    }

    // ── 출력 ────────────────────────────────────────────────────────────────

    /**
     * {@code outputDir/_migration/REPORT.md} 에 리포트를 작성한다.
     *
     * @param outputDir 프로젝트 출력 루트 (예: {@code ./output/proj-a})
     */
    public void writeToFile(Path outputDir) {
        finishedAt = LocalDateTime.now();
        Path migDir = outputDir.resolve("_migration");
        Path reportFile = migDir.resolve("REPORT.md");
        try {
            Files.createDirectories(migDir);
            Files.writeString(reportFile, toMarkdown(), StandardCharsets.UTF_8);
            System.out.println();
            System.out.println("  ✔ 마이그레이션 리포트: " + reportFile.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("  [WARN] 마이그레이션 리포트 저장 실패: " + e.getMessage());
        }
    }

    /** 리포트 전체 내용을 마크다운 문자열로 반환한다. 테스트 및 직접 출력용. */
    public String toMarkdown() {
        LocalDateTime ts = finishedAt != null ? finishedAt : LocalDateTime.now();
        StringBuilder sb = new StringBuilder();

        // ── 헤더 ──────────────────────────────────────────────────────────
        sb.append("# ").append(projectSlug).append(" — Migration Report\n\n");
        sb.append("> 생성 시각: ").append(ts.format(DT_FMT)).append("  \n");
        sb.append("> 시작 시각: ").append(startedAt.format(DT_FMT)).append("\n\n");
        sb.append("---\n\n");

        // ── 처리 요약 ──────────────────────────────────────────────────────
        sb.append("## 처리 요약\n\n");
        if (sections.isEmpty()) {
            sb.append("_(실행된 섹션 없음)_\n\n");
        } else {
            sb.append("| 섹션 | 전체 | 성공 | 실패 | 스킵 |\n");
            sb.append("|---|---:|---:|---:|---:|\n");
            int[] totals = {0, 0, 0, 0};
            for (Map.Entry<String, int[]> e : sections.entrySet()) {
                int[] s = e.getValue();
                sb.append("| ").append(e.getKey())
                  .append(" | ").append(s[0])
                  .append(" | ").append(s[1])
                  .append(" | ").append(s[2])
                  .append(" | ").append(s[3])
                  .append(" |\n");
                for (int i = 0; i < 4; i++) totals[i] += s[i];
            }
            // 합계 행
            sb.append("| **합계** | **").append(totals[0])
              .append("** | **").append(totals[1])
              .append("** | **").append(totals[2])
              .append("** | **").append(totals[3])
              .append("** |\n\n");
        }

        // ── 첨부파일 ─────────────────────────────────────────────────────
        if (attachmentCount > 0) {
            sb.append("## 첨부파일\n\n");
            sb.append("- 파일 수: ").append(attachmentCount).append("개\n");
            sb.append("- 총 크기: ").append(formatBytes(attachmentTotalBytes)).append("\n\n");
        }

        // ── 누락 데이터 (403) ─────────────────────────────────────────────
        if (!dataMissingWarnings.isEmpty()) {
            sb.append("## ⚠ 누락 데이터 (접근 권한 없음)\n\n");
            for (String w : dataMissingWarnings) {
                sb.append("- ").append(w).append("\n");
            }
            sb.append("\n");
        }

        // ── 실패 항목 ────────────────────────────────────────────────────
        if (!failures.isEmpty()) {
            sb.append("## ✗ 실패 항목 (").append(failures.size()).append("건)\n\n");
            sb.append("| 유형 | 식별자 | 원인 |\n");
            sb.append("|---|---|---|\n");
            for (String[] f : failures) {
                sb.append("| ").append(f[0])
                  .append(" | ").append(f[1])
                  .append(" | ").append(f[2].replace("|", "\\|").replace("\n", " "))
                  .append(" |\n");
            }
            sb.append("\n");
        }

        // ── 미매핑 사용자 ─────────────────────────────────────────────────
        if (!unmappedUsers.isEmpty()) {
            sb.append("## ⚠ 사용자 매핑 미일치 (").append(unmappedUsers.size()).append("명)\n\n");
            sb.append("다음 Redmine 사용자가 `user-mapping.yml` 에 없습니다:\n\n");
            for (String u : unmappedUsers) {
                sb.append("- `").append(u).append("`\n");
            }
            sb.append("\n");
        }

        // ── 다음 액션 ────────────────────────────────────────────────────
        sb.append("## 다음 액션\n\n");
        long totalFailed = sections.values().stream().mapToLong(s -> s[2]).sum();
        boolean hasAction = false;

        if (totalFailed > 0) {
            sb.append("- [ ] `--retry-failed` 로 실패 항목 재처리 (")
              .append(totalFailed).append("건)\n");
            hasAction = true;
        }
        if (!unmappedUsers.isEmpty()) {
            sb.append("- [ ] `user-mapping.yml` 에 미매핑 사용자 ")
              .append(unmappedUsers.size()).append("명 추가 후 upload 재실행\n");
            hasAction = true;
        }
        if (!dataMissingWarnings.isEmpty()) {
            sb.append("- [ ] 403 누락 데이터 확인 — Redmine 관리자에게 권한 요청 또는 `--only` 로 제외\n");
            hasAction = true;
        }
        if (!hasAction) {
            sb.append("- 모든 항목이 성공적으로 처리되었습니다. ✓\n");
        }
        sb.append("\n");

        return sb.toString();
    }

    // ── 조회 ──────────────────────────────────────────────────────────────

    public boolean hasFailures()     { return !failures.isEmpty(); }
    public int     failureCount()    { return failures.size(); }
    public boolean hasUnmappedUsers(){ return !unmappedUsers.isEmpty(); }
    public String  getProjectSlug()  { return projectSlug; }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────

    private static String formatBytes(long bytes) {
        if (bytes < 1_024L)             return bytes + " B";
        if (bytes < 1_024L * 1_024)     return String.format("%.1f KB", bytes / 1_024.0);
        if (bytes < 1_024L * 1_024 * 1_024)
            return String.format("%.1f MB", bytes / (1_024.0 * 1_024));
        return String.format("%.1f GB", bytes / (1_024.0 * 1_024 * 1_024));
    }
}
