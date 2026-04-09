package com.redmine2github.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code fetch --all} 의 별칭 커맨드.
 *
 * <p>하위 호환성을 위해 유지되며, 내부적으로 {@link FetchCommand}에 {@code --all} 플래그를
 * 추가하여 위임한다.</p>
 *
 * <p>새로운 방식: {@code redmine2github fetch --all [옵션...]}</p>
 */
@Command(
    name = "fetch-all",
    description = "fetch --all 의 별칭: 접근 가능한 모든 Redmine 프로젝트를 수집한다",
    mixinStandardHelpOptions = true,
    footer = {
        "",
        "이 커맨드는 다음과 동일합니다:",
        "  redmine2github fetch --all [옵션...]",
        "",
        "사용 예시:",
        "  redmine2github fetch-all                        # 전체 프로젝트 수집",
        "  redmine2github fetch-all --only wiki            # Wiki만",
        "  redmine2github fetch-all --skip project-id      # 특정 프로젝트 제외",
        "",
        "수집 결과는 output/{project-id}/ 디렉터리에 저장됩니다."
    }
)
public class FetchAllProjectsCommand implements Runnable {

    @Option(names = "--only",
            description = "수집 대상 선택: wiki, issues, time-entries (미지정 시 전체)")
    private String only;

    @Option(names = "--resume",
            description = "이전 중단 지점부터 재개")
    private boolean resume;

    @Option(names = "--retry-failed",
            description = "이전 실행에서 실패한 항목만 재처리")
    private boolean retryFailed;

    @Option(names = "--skip", split = ",",
            description = "제외할 프로젝트 식별자 (쉼표 구분, 예: --skip foo,bar)")
    private List<String> skip;

    @Override
    public void run() {
        List<String> args = new ArrayList<>();
        args.add("--all");
        if (only != null)                          { args.add("--only"); args.add(only); }
        if (resume)                                  args.add("--resume");
        if (retryFailed)                             args.add("--retry-failed");
        if (skip != null && !skip.isEmpty())       { args.add("--skip"); args.add(String.join(",", skip)); }
        new CommandLine(new FetchCommand()).execute(args.toArray(new String[0]));
    }
}
