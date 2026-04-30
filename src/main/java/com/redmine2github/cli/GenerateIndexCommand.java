package com.redmine2github.cli;

import com.redmine2github.config.AppConfig;
import com.redmine2github.service.AllProjectsIndexGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code output/} 디렉터리를 스캔하여 전체 프로젝트 인덱스 파일을 생성/갱신한다.
 *
 * <p>Redmine 연결 없이 로컬 {@code output/} 폴더만으로 동작한다.
 * {@code fetch --all} 이후 개별 프로젝트를 다시 수집한 경우 이 커맨드로
 * 인덱스를 재생성할 수 있다.</p>
 */
@Command(
    name = "generate-index",
    description = "output/ 디렉터리를 스캔하여 all_projects_wiki.md / all_projects_issue.md를 생성/갱신한다",
    mixinStandardHelpOptions = true,
    footer = {
        "",
        "사용 예시:",
        "  redmine2github generate-index                         # 기본 output/ 디렉터리 사용",
        "  redmine2github generate-index --output-dir ./output  # 경로 직접 지정",
        "",
        "생성되는 파일:",
        "  {output-dir}/all_projects_wiki.md  — Wiki 페이지 수 기준 정렬",
        "  {output-dir}/all_projects_issue.md — Issue 수 기준 정렬",
        "",
        "각 프로젝트 폴더에 _project.json이 있으면 프로젝트 표시 이름을 사용하고,",
        "없으면 폴더명을 그대로 사용한다."
    }
)
public class GenerateIndexCommand implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(GenerateIndexCommand.class);

    @Option(names = "--output-dir",
            description = "스캔할 output 디렉터리 경로 (기본값: AppConfig의 OUTPUT_DIR 또는 'output')")
    private String outputDir;

    @Override
    public void run() {
        String dir = resolveOutputDir();
        log.info("전체 프로젝트 인덱스 생성 시작 — 디렉터리: {}", dir);
        System.out.println("  전체 프로젝트 인덱스 생성 중: " + dir);
        AllProjectsIndexGenerator.generate(dir);
    }

    private String resolveOutputDir() {
        if (outputDir != null && !outputDir.isBlank()) {
            return outputDir;
        }
        try {
            return AppConfig.load().getOutputDir();
        } catch (Exception e) {
            log.debug("AppConfig 로드 실패, 기본값 'output' 사용: {}", e.getMessage());
            return "output";
        }
    }
}
