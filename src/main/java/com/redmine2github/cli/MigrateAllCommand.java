package com.redmine2github.cli;

import com.redmine2github.config.AppConfig;
import com.redmine2github.state.FailureLog;
import com.redmine2github.service.IssueMigrationService;
import com.redmine2github.service.TimeEntryMigrationService;
import com.redmine2github.service.WikiMigrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;

/**
 * fetch + upload를 순차 실행하는 통합 커맨드.
 *
 * <p>내부적으로 각 서비스의 {@code fetch()} → {@code upload()} 를 차례로 호출한다.
 * 단계별 실행이 필요하다면 {@code fetch} / {@code upload} 커맨드를 개별적으로 사용하세요.
 */
@Command(
    name = "migrate",
    description = "Phase 1(fetch) + Phase 2(upload)를 순차 실행한다",
    mixinStandardHelpOptions = true,
    footer = {
        "",
        "사용 예시:",
        "  redmine2github migrate                          # 전체 실행",
        "  redmine2github migrate --only wiki              # Wiki만",
        "  redmine2github migrate --only issues            # 일감만",
        "  redmine2github migrate --only time-entries      # 작업 내역만",
        "  redmine2github migrate --resume                 # 중단 후 재개",
        "  redmine2github migrate --retry-failed           # 실패 항목 재처리",
        "",
        "단계별 실행:",
        "  redmine2github fetch    # Phase 1 only: Redmine → 로컬",
        "  redmine2github upload   # Phase 2 only: 로컬 → GitHub"
    }
)
public class MigrateAllCommand implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(MigrateAllCommand.class);

    @Option(names = "--only",
            description = "대상 선택: wiki, issues, time-entries (미지정 시 전체 실행)")
    private String only;

    @Option(names = "--resume",
            description = "이전 중단 지점부터 재개")
    private boolean resume;

    @Option(names = "--retry-failed",
            description = "이전 실행에서 실패한 항목만 재처리")
    private boolean retryFailed;

    @Override
    public void run() {
        AppConfig config = AppConfig.load();

        boolean runWiki   = only == null || "wiki".equals(only);
        boolean runIssues = only == null || "issues".equals(only);
        boolean runTime   = only == null || "time-entries".equals(only);

        MigrationReport report = new MigrationReport(config.getProjectSlug());
        FailureLog failureLog  = new FailureLog(Path.of(config.getProjectOutputDir()));

        if (runWiki) {
            log.info("=== Wiki 마이그레이션 시작 ===");
            new WikiMigrationService(config, report, failureLog).run(resume, retryFailed);
        }
        if (runIssues) {
            log.info("=== Issues 마이그레이션 시작 ===");
            new IssueMigrationService(config, report, failureLog).run(resume, retryFailed);
        }
        if (runTime) {
            log.info("=== Time Entries 마이그레이션 시작 ===");
            new TimeEntryMigrationService(config, report, failureLog).run(resume, retryFailed);
        }

        report.writeToFile(Path.of(config.getProjectOutputDir()));
        log.info("=== 마이그레이션 완료 ===");
    }
}
