package com.redmine2github;

import com.redmine2github.cli.FetchCommand;
import com.redmine2github.cli.GenerateIndexCommand;
import com.redmine2github.cli.GenerateMappingCommand;
import com.redmine2github.cli.MigrateAllCommand;
import com.redmine2github.cli.UploadCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

@Command(
    name = "redmine2github",
    description = "Redmine 프로젝트를 GitHub로 마이그레이션하는 CLI 도구",
    subcommands = {
        FetchCommand.class,
        UploadCommand.class,
        MigrateAllCommand.class,
        GenerateMappingCommand.class,
        GenerateIndexCommand.class,
        CommandLine.HelpCommand.class
    },
    mixinStandardHelpOptions = true,
    versionProvider = ManifestVersionProvider.class,
    footer = {
        "",
        "단일 프로젝트 (2단계, 권장):",
        "  1. redmine2github fetch                # Redmine -> local(output/)",
        "  2. (output/ 검토 및 수정)",
        "  3. redmine2github upload               # local(output/) -> GitHub",
        "",
        "특정 프로젝트 지정 (환경 변수 없이):",
        "  redmine2github fetch --project my-id",
        "",
        "전체 프로젝트 일괄 수집 (--all):",
        "  redmine2github fetch --all             # 모든 프로젝트 수집",
        "  redmine2github fetch --all --only wiki # Wiki만",
        "  redmine2github fetch --all --skip foo,bar # 일부 제외",
        "",
        "통합 실행 (단일 프로젝트):",
        "  redmine2github migrate",
        "",
        "기타:",
        "  redmine2github generate-mapping   # 사용자 매핑 초안 생성",
        "  redmine2github generate-index     # 전체 프로젝트 인덱스 파일 생성/갱신",
        "",
        "서브커맨드 상세 도움말: redmine2github <command> --help"
    }
)
public class Redmine2GithubApp implements Runnable {

    public static void main(String[] args) throws Exception {
        // Windows 콘솔에서 한글 깨짐 방지: stdout/stderr를 UTF-8로 고정
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));

        int exitCode = new CommandLine(new Redmine2GithubApp()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
