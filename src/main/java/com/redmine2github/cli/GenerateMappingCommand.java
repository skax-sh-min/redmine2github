package com.redmine2github.cli;

import com.redmine2github.config.AppConfig;
import com.redmine2github.redmine.RedmineClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

@Command(
    name = "generate-mapping",
    description = "Redmine 사용자 목록을 조회하여 user-mapping.yml 초안을 생성한다",
    mixinStandardHelpOptions = true,
    footer = {
        "",
        "사용 예시:",
        "  redmine2github generate-mapping                          # 기본 파일명으로 생성",
        "  redmine2github generate-mapping --output my-mapping.yml # 파일명 지정",
        "",
        "생성된 파일에서 각 Redmine 로그인 이름 옆에 GitHub 계정을 입력하세요:",
        "  users:",
        "    redmine-user: github-user",
        "    another-user: another-github-user"
    }
)
public class GenerateMappingCommand implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(GenerateMappingCommand.class);

    @Option(names = "--output",
            description = "출력 파일 경로 (기본값: user-mapping.yml)",
            defaultValue = "user-mapping.yml")
    private String output;

    @Override
    public void run() {
        AppConfig config = AppConfig.load();
        RedmineClient client = new RedmineClient(config);

        log.info("Redmine 사용자 목록 조회 중...");
        var users = client.fetchUsers();

        Map<String, Object> mapping = new LinkedHashMap<>();
        Map<String, String> userMap = new LinkedHashMap<>();
        users.forEach(u -> userMap.put(u.getLogin(), ""));
        mapping.put("users", userMap);

        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            mapper.writeValue(new File(output), mapping);
            log.info("user-mapping.yml 초안 생성 완료: {}", output);
            log.info("GitHub 계정을 직접 입력한 뒤 마이그레이션을 실행하세요.");
        } catch (Exception e) {
            log.error("파일 저장 실패: {}", e.getMessage(), e);
        }
    }
}
