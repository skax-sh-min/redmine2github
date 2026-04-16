package com.redmine2github.cli;

import com.redmine2github.config.AppConfig;
import com.redmine2github.service.IssueMigrationService;
import com.redmine2github.service.TimeEntryMigrationService;
import com.redmine2github.service.WikiMigrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Phase 2: 로컬 {@code output/}의 파일을 GitHub에 업로드한다.
 *
 * <p>{@code fetch} 커맨드로 수집된 파일이 {@code output/}에 있어야 한다.
 * GitHub Personal Access Token({@code GITHUB_TOKEN})이 필요하다.
 *
 * <p>{@code --all} 옵션을 사용하면 {@code output/} 하위의 모든 프로젝트 디렉터리를
 * 순서대로 업로드한다 (Redmine API 불필요).
 */
@Command(
    name = "upload",
    description = "Phase 2: 로컬(output/)의 변환 파일을 GitHub에 업로드한다",
    mixinStandardHelpOptions = true,
    footer = {
        "",
        "단일 프로젝트 업로드 (REDMINE_PROJECT 환경 변수 기준):",
        "  redmine2github upload                          # 전체 업로드",
        "  redmine2github upload --only wiki              # Wiki만 업로드",
        "  redmine2github upload --only issues            # 일감만 업로드",
        "  redmine2github upload --only time-entries      # 작업 내역만 업로드",
        "  redmine2github upload --resume                 # 중단 후 재개",
        "  redmine2github upload --retry-failed           # 실패 항목 재처리",
        "",
        "전체 프로젝트 업로드 (--all):",
        "  redmine2github upload --all                    # output/ 하위 모든 프로젝트",
        "  redmine2github upload --all --only wiki        # 모든 프로젝트, Wiki만",
        "  redmine2github upload --all --skip foo,bar     # 일부 프로젝트 제외",
        "",
        "사전 조건:",
        "  - fetch 커맨드로 output/ 파일이 준비되어 있어야 합니다",
        "  - GITHUB_TOKEN, GITHUB_REPO 환경 변수가 설정되어 있어야 합니다"
    }
)
public class UploadCommand implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(UploadCommand.class);

    @Option(names = "--only",
            description = "업로드 대상 선택: wiki, issues, time-entries (미지정 시 전체)")
    private String only;

    @Option(names = "--resume",
            description = "이전 중단 지점부터 재개")
    private boolean resume;

    @Option(names = "--retry-failed",
            description = "이전 실행에서 실패한 항목만 재처리")
    private boolean retryFailed;

    @Option(names = "--all",
            description = "output/ 하위의 모든 프로젝트 디렉터리를 순서대로 업로드한다")
    private boolean all;

    @Option(names = "--skip", split = ",",
            description = "--all 사용 시 제외할 프로젝트 식별자 (쉼표 구분, 예: --skip foo,bar)")
    private List<String> skip;

    @Override
    public void run() {
        AppConfig baseConfig = AppConfig.load();

        boolean runWiki   = only == null || "wiki".equals(only);
        boolean runIssues = only == null || "issues".equals(only);
        boolean runTime   = only == null || "time-entries".equals(only);

        if (all) {
            runAll(baseConfig, runWiki, runIssues, runTime);
        } else {
            runSingle(baseConfig, runWiki, runIssues, runTime);
        }
    }

    // ── 단일 프로젝트 ─────────────────────────────────────────────

    private void runSingle(AppConfig config, boolean runWiki, boolean runIssues, boolean runTime) {
        if (runWiki) {
            log.info("=== Wiki upload 시작 ===");
            new WikiMigrationService(config).upload(resume, retryFailed);
        }
        if (runIssues) {
            log.info("=== Issues upload 시작 ===");
            new IssueMigrationService(config).upload(resume, retryFailed);
        }
        if (runTime) {
            log.info("=== Time Entries upload 시작 ===");
            new TimeEntryMigrationService(config).upload(resume, retryFailed);
        }
        log.info("=== upload 완료 ===");
    }

    // ── 전체 프로젝트 (--all) ─────────────────────────────────────

    private void runAll(AppConfig baseConfig, boolean runWiki, boolean runIssues, boolean runTime) {
        Path outputRoot = Path.of(baseConfig.getOutputDir());
        if (!Files.exists(outputRoot)) {
            System.err.println("[ERROR] output 디렉터리가 없습니다: " + outputRoot);
            System.err.println("  먼저 fetch --all 을 실행하세요.");
            return;
        }

        List<Path> projectDirs;
        try {
            projectDirs = Files.list(outputRoot)
                    .filter(Files::isDirectory)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.error("output 디렉터리 읽기 실패: {}", e.getMessage(), e);
            System.err.println("[ERROR] output 디렉터리 읽기 실패: " + e.getMessage());
            return;
        }

        if (projectDirs.isEmpty()) {
            System.err.println("[ERROR] output/ 하위에 프로젝트 디렉터리가 없습니다.");
            System.err.println("  먼저 fetch --all 을 실행하세요.");
            return;
        }

        System.out.printf("%n  총 %d개 프로젝트 업로드 시작%n%n", projectDirs.size());

        int index = 0;
        for (Path dir : projectDirs) {
            index++;
            String projectId = dir.getFileName().toString();

            if (skip != null && skip.contains(projectId)) {
                System.out.printf("  [%d/%d] %s — 스킵 (--skip)%n", index, projectDirs.size(), projectId);
                continue;
            }

            System.out.printf("  [%d/%d] === %s ===%n", index, projectDirs.size(), projectId);
            log.info("프로젝트 업로드 시작: {}", projectId);

            try {
                runSingle(baseConfig.withProject(projectId), runWiki, runIssues, runTime);
            } catch (Exception e) {
                log.error("[{}] 업로드 중 오류 — 다음 프로젝트로 계속: {}", projectId, e.getMessage(), e);
                System.out.printf("  [%d/%d] %s — 오류 발생, 다음 프로젝트로 계속: %s%n",
                        index, projectDirs.size(), projectId, e.getMessage());
            }
        }

        System.out.println();
        System.out.println("  upload --all 완료.");
        log.info("=== upload --all 완료 ({} 프로젝트) ===", projectDirs.size());
    }
}
