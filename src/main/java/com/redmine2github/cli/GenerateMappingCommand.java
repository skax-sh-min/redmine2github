package com.redmine2github.cli;

import com.redmine2github.config.AppConfig;
import com.redmine2github.redmine.RedmineClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import com.redmine2github.redmine.model.RedmineUser;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
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
        List<RedmineUser> users;
        try {
            users = client.fetchUsers();
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (msg.contains("[401]")) {
                log.error("Redmine 인증 실패 (401). REDMINE_API_KEY 또는 REDMINE_USERNAME/REDMINE_PASSWORD를 확인하세요.");
            } else if (msg.contains("[403]")) {
                log.error("Redmine 프로젝트에 접근할 수 없습니다 (403). REDMINE_URL 또는 사용자 권한을 확인하세요.");
            } else if (msg.contains("[404]")) {
                log.error("Redmine 프로젝트를 찾을 수 없습니다 (404). REDMINE_PROJECT 또는 REDMINE_URL을 확인하세요.");
            } else if (msg.contains("호출 실패")) {
                log.error("Redmine 서버에 연결할 수 없습니다. REDMINE_URL({})을 확인하세요. 원인: {}",
                        config.getRedmineUrl(), msg);
            } else {
                log.error("사용자 목록 조회 중 오류 발생: {}", msg);
            }
            return;
        }

        if (users.isEmpty()) {
            log.warn("조회된 사용자가 없습니다. REDMINE_PROJECT 설정을 확인하거나 Redmine 관리자에게 문의하세요.");
        }

        Map<String, Object> mapping = new LinkedHashMap<>();
        Map<String, String> userMap = new LinkedHashMap<>();
        users.forEach(u -> userMap.put(u.getLogin(), ""));
        mapping.put("users", userMap);

        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            mapper.writeValue(new File(output), mapping);
            log.info("user-mapping.yml 초안 생성 완료: {}", output);
            log.info("GitHub 계정을 직접 입력한 뒤 마이그레이션을 실행하세요.");
            if (!users.isEmpty() && users.stream().allMatch(u -> !u.getFirstname().isEmpty() || u.getLogin().contains(" "))) {
                log.warn("관리자 권한 없이 생성된 매핑입니다. 키가 Redmine 표시 이름(display name)으로 채워져 있습니다.");
                log.warn("각 항목의 키를 실제 Redmine login 이름으로 변경한 뒤 사용하세요.");
            }
        } catch (Exception e) {
            log.error("파일 저장 실패: {}", e.getMessage(), e);
        }
    }
}
