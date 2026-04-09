package com.redmine2github.cli;

import com.redmine2github.config.AppConfig;
import com.redmine2github.service.IssueMigrationService;
import com.redmine2github.service.TimeEntryMigrationService;
import com.redmine2github.service.WikiMigrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Phase 2: 로컬 {@code output/}의 파일을 GitHub에 업로드한다.
 *
 * <p>{@code fetch} 커맨드로 수집된 파일이 {@code output/}에 있어야 한다.
 * GitHub Personal Access Token({@code GITHUB_TOKEN})이 필요하다.
 */
@Command(
    name = "upload",
    description = "Phase 2: 로컬(output/)의 변환 파일을 GitHub에 업로드한다",
    mixinStandardHelpOptions = true,
    footer = {
        "",
        "사용 예시:",
        "  redmine2github upload                          # 전체 업로드",
        "  redmine2github upload --only wiki              # Wiki만 업로드",
        "  redmine2github upload --only issues            # 일감만 업로드",
        "  redmine2github upload --only time-entries      # 작업 내역만 업로드",
        "  redmine2github upload --resume                 # 중단 후 재개",
        "  redmine2github upload --retry-failed           # 실패 항목 재처리",
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

    @Override
    public void run() {
        AppConfig config = AppConfig.load();

        boolean runWiki   = only == null || "wiki".equals(only);
        boolean runIssues = only == null || "issues".equals(only);
        boolean runTime   = only == null || "time-entries".equals(only);

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
}
