package com.redmine2github.cli;

import com.redmine2github.config.AppConfig;
import com.redmine2github.redmine.RedmineClient;
import com.redmine2github.redmine.model.RedmineProject;
import com.redmine2github.service.IssueMigrationService;
import com.redmine2github.service.TimeEntryMigrationService;
import com.redmine2github.service.WikiMigrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;

/**
 * Phase 1: Redmine 데이터를 수집하여 로컬 {@code output/}에 저장한다.
 *
 * <p>단일 프로젝트, 다중 프로젝트({@code REDMINE_PROJECTS}), 전체 프로젝트({@code --all})를
 * 모두 지원하는 통합 fetch 커맨드.</p>
 *
 * <p>GitHub 자격증명이 없어도 실행 가능하다. 수집된 파일을 검토한 뒤
 * {@code upload} 커맨드로 GitHub에 업로드한다.</p>
 */
@Command(
    name = "fetch",
    description = "Phase 1: Redmine 데이터를 수집하여 로컬(output/)에 저장한다",
    mixinStandardHelpOptions = true,
    footer = {
        "",
        "단일 프로젝트 수집 (REDMINE_PROJECT 환경 변수 또는 --project 사용):",
        "  redmine2github fetch                          # 전체 수집",
        "  redmine2github fetch --only wiki              # Wiki만",
        "  redmine2github fetch --only issues            # 일감만",
        "  redmine2github fetch --resume                 # 이전 중단 지점부터 재개",
        "  redmine2github fetch --project my-project     # 환경 변수 대신 직접 지정",
        "",
        "전체 프로젝트 일괄 수집 (--all):",
        "  redmine2github fetch --all                    # 접근 가능한 모든 프로젝트",
        "  redmine2github fetch --all --only wiki        # 모든 프로젝트, Wiki만",
        "  redmine2github fetch --all --skip foo,bar     # 일부 프로젝트 제외",
        "  redmine2github fetch-all                      # fetch --all 의 별칭",
        "",
        "수집 결과 저장 위치:",
        "  단일/--project: output/            (REDMINE_PROJECT 기준)",
        "  --all 모드:     output/{project-id}/ (프로젝트별 서브디렉터리)"
    }
)
public class FetchCommand implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(FetchCommand.class);

    @Option(names = "--only",
            description = "수집 대상 선택: wiki, issues, time-entries (미지정 시 전체)")
    private String only;

    @Option(names = "--resume",
            description = "이전 중단 지점부터 재개")
    private boolean resume;

    @Option(names = "--retry-failed",
            description = "이전 실행에서 실패한 항목만 재처리")
    private boolean retryFailed;

    @Option(names = "--all",
            description = "접근 가능한 모든 Redmine 프로젝트를 수집한다 (REDMINE_PROJECT 불필요)")
    private boolean all;

    @Option(names = "--project",
            description = "수집할 프로젝트 식별자 지정 (REDMINE_PROJECT 환경 변수를 대체한다)")
    private String projectOverride;

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

    // ── 단일 / 다중 프로젝트 ──────────────────────────────────

    private void runSingle(AppConfig baseConfig, boolean runWiki, boolean runIssues, boolean runTime) {
        // --project 가 지정된 경우 환경 변수보다 우선
        if (projectOverride != null && !projectOverride.isBlank()) {
            AppConfig config = baseConfig.withProject(projectOverride);
            log.info("=== fetch 시작: {} (--project) ===", projectOverride);
            runProject(config, runWiki, runIssues, runTime);
            printSingleDone();
            return;
        }

        // REDMINE_PROJECTS 환경 변수에 여러 프로젝트가 지정된 경우
        List<String> multiProjects = baseConfig.getRedmineProjects();
        if (!multiProjects.isEmpty()) {
            log.info("REDMINE_PROJECTS: {} 개 프로젝트 수집", multiProjects.size());
            System.out.printf("%n  총 %d개 프로젝트 수집 시작 (REDMINE_PROJECTS)%n%n", multiProjects.size());
            int idx = 0;
            for (String pid : multiProjects) {
                idx++;
                System.out.printf("  [%d/%d] === %s ===%n", idx, multiProjects.size(), pid);
                try {
                    runProject(baseConfig.withProject(pid), runWiki, runIssues, runTime);
                } catch (Exception e) {
                    log.error("[{}] 수집 중 오류 — 다음 프로젝트로 계속: {}", pid, e.getMessage(), e);
                    System.out.printf("  [%d/%d] %s — 오류: %s%n", idx, multiProjects.size(), pid, e.getMessage());
                }
            }
            System.out.println();
            System.out.println("  fetch 완료. output/ 디렉터리를 검토한 뒤 upload를 실행하세요.");
            return;
        }

        // REDMINE_PROJECT 단일 프로젝트
        if (baseConfig.getRedmineProject() == null || baseConfig.getRedmineProject().isBlank()) {
            System.err.println("[ERROR] 수집할 프로젝트가 지정되지 않았습니다.");
            System.err.println("  다음 중 하나를 설정하세요:");
            System.err.println("    .env 또는 환경 변수: REDMINE_PROJECT=my-project");
            System.err.println("    CLI 옵션:           --project my-project");
            System.err.println("    여러 프로젝트:      REDMINE_PROJECTS=proj-a,proj-b");
            System.err.println("    전체 프로젝트:      --all");
            return;
        }

        log.info("=== fetch 시작: {} ===", baseConfig.getRedmineProject());
        runProject(baseConfig, runWiki, runIssues, runTime);
        printSingleDone();
    }

    // ── 전체 프로젝트 (--all) ─────────────────────────────────

    private void runAll(AppConfig baseConfig, boolean runWiki, boolean runIssues, boolean runTime) {
        log.info("=== 전체 프로젝트 목록 조회 ===");
        List<RedmineProject> projects = new RedmineClient(baseConfig).fetchAllProjects();
        if (projects.isEmpty()) {
            System.err.println("[ERROR] 접근 가능한 프로젝트가 없습니다. API Key 권한을 확인하세요.");
            return;
        }

        System.out.printf("%n  총 %d개 프로젝트 수집 시작%n%n", projects.size());

        int index = 0;
        for (RedmineProject project : projects) {
            index++;
            String id = project.getIdentifier();

            if (skip != null && skip.contains(id)) {
                System.out.printf("  [%d/%d] %s — 스킵 (--skip)%n", index, projects.size(), id);
                continue;
            }

            System.out.printf("  [%d/%d] === %s (%s) ===%n",
                    index, projects.size(), id, project.getName());
            log.info("프로젝트 수집 시작: {}", id);

            try {
                runProject(baseConfig.withProject(id), runWiki, runIssues, runTime);
            } catch (Exception e) {
                log.error("[{}] 수집 중 오류 — 다음 프로젝트로 계속: {}", id, e.getMessage(), e);
                System.out.printf("  [%d/%d] %s — 오류 발생, 다음 프로젝트로 계속: %s%n",
                        index, projects.size(), id, e.getMessage());
            }
        }

        System.out.println();
        System.out.println("  fetch --all 완료. output/ 디렉터리를 검토한 뒤 upload를 실행하세요.");
        log.info("=== fetch --all 완료 ({} 프로젝트) ===", projects.size());
    }

    // ── 공통 헬퍼 ─────────────────────────────────────────────

    private void runProject(AppConfig config, boolean runWiki, boolean runIssues, boolean runTime) {
        MigrationReport report = new MigrationReport(config.getProjectSlug());
        if (runWiki)   new WikiMigrationService(config, report).fetch(resume, retryFailed);
        if (runIssues) new IssueMigrationService(config, report).fetch(resume, retryFailed);
        if (runTime)   new TimeEntryMigrationService(config, report).fetch(resume, retryFailed);
        report.writeToFile(Path.of(config.getProjectOutputDir()));
    }

    private void printSingleDone() {
        log.info("=== fetch 완료 — output/ 디렉터리를 확인하세요 ===");
        System.out.println();
        System.out.println("  fetch 완료. output/ 디렉터리를 검토한 뒤 upload를 실행하세요.");
        System.out.println("    redmine2github upload");
    }
}
