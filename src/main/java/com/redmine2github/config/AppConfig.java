package com.redmine2github.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    private final String redmineUrl;
    private final String redmineApiKey;
    private final String redmineUsername;
    private final String redminePassword;
    private final String redmineProject;
    /** fetch-all 실행 시 Redmine에서 조회한 프로젝트 표시 이름 (선택, null 가능). */
    private final String projectName;
    private final String githubToken;
    private final String githubRepo;
    private final String outputDir;
    private final String cacheDir;
    private final String uploadMethod;
    private final Map<String, String> userMapping;
    private final int requestDelayMs;
    /** REDMINE_PROJECTS=a,b,c — 수집할 프로젝트 목록 (단일 프로젝트 대신 사용). */
    private final List<String> redmineProjects;
    /** url-rewrites.yml 에서 로드한 URL 치환 규칙 목록. 각 원소는 {old, new} 쌍. */
    private final List<String[]> urlRewrites;
    /** REDMINE_ISSUE_MD_FETCH=true — issue fetch 시 issues/{id}.md 파일도 저장할지 여부. */
    private final boolean issueMdFetch;
    /**
     * UPLOAD_MAX_FILE_SIZE_KB — 업로드 시 이 크기(KB)를 초과하는 파일은 건너뜁니다.
     * 0 이하이면 제한 없음.
     */
    private final long uploadMaxFileSizeKb;
    /** label-colors.yml 에서 로드한 Label 색상 맵. {카테고리 → {항목명 → hex색상}}. */
    private final Map<String, Map<String, String>> labelColors;

    /** 특정 필드를 직접 주입하는 내부 생성자 (withProject 등에서 사용). */
    private AppConfig(String redmineUrl, String redmineApiKey, String redmineUsername,
                      String redminePassword, String redmineProject, String projectName,
                      String githubToken, String githubRepo,
                      String outputDir, String cacheDir, String uploadMethod,
                      Map<String, String> userMapping, int requestDelayMs,
                      List<String> redmineProjects, List<String[]> urlRewrites,
                      boolean issueMdFetch, long uploadMaxFileSizeKb,
                      Map<String, Map<String, String>> labelColors) {
        this.redmineUrl          = redmineUrl;
        this.redmineApiKey       = redmineApiKey;
        this.redmineUsername     = redmineUsername;
        this.redminePassword     = redminePassword;
        this.redmineProject      = redmineProject;
        this.projectName         = projectName;
        this.githubToken         = githubToken;
        this.githubRepo          = githubRepo;
        this.outputDir           = outputDir;
        this.cacheDir            = cacheDir;
        this.uploadMethod        = uploadMethod;
        this.userMapping         = userMapping;
        this.requestDelayMs      = requestDelayMs;
        this.redmineProjects     = redmineProjects;
        this.urlRewrites         = urlRewrites;
        this.issueMdFetch        = issueMdFetch;
        this.uploadMaxFileSizeKb = uploadMaxFileSizeKb;
        this.labelColors         = labelColors;
    }

    private AppConfig(Dotenv env, Map<String, String> userMapping) {
        this.projectName = null;
        String rawUrl = env.get("REDMINE_URL");
        this.redmineUrl      = rawUrl != null ? rawUrl.replaceAll("/+$", "") : rawUrl;
        this.redmineApiKey   = env.get("REDMINE_API_KEY", "");
        this.redmineUsername = env.get("REDMINE_USERNAME", "");
        this.redminePassword = env.get("REDMINE_PASSWORD", "");
        this.redmineProject  = env.get("REDMINE_PROJECT");
        this.githubToken   = env.get("GITHUB_TOKEN");
        this.githubRepo    = env.get("GITHUB_REPO");
        this.outputDir     = env.get("OUTPUT_DIR", "./output");
        this.cacheDir      = env.get("CACHE_DIR", "./cache");
        this.uploadMethod  = env.get("GITHUB_UPLOAD_METHOD", "API");
        this.userMapping   = userMapping;
        this.requestDelayMs = Integer.parseInt(env.get("REQUEST_DELAY_MS", "10"));
        String projectsStr  = env.get("REDMINE_PROJECTS", "");
        this.redmineProjects = projectsStr.isBlank() ? Collections.emptyList()
                : Arrays.stream(projectsStr.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .toList();
        this.urlRewrites         = loadUrlRewrites();
        this.issueMdFetch        = Boolean.parseBoolean(env.get("REDMINE_ISSUE_MD_FETCH", "true"));
        this.uploadMaxFileSizeKb = Long.parseLong(env.get("UPLOAD_MAX_FILE_SIZE_KB", "0"));
        this.labelColors         = loadLabelColors();
    }

    public static AppConfig load() {
        Dotenv env = Dotenv.configure().ignoreIfMissing().load();
        Map<String, String> userMapping = loadUserMapping();
        return new AppConfig(env, userMapping);
    }

    private static List<String[]> loadUrlRewrites() {
        File file = new File("url-rewrites.yml");
        if (!file.exists()) return Collections.emptyList();
        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            Map<String, Object> root = mapper.readValue(file, new TypeReference<Map<String, Object>>() {});
            Object rawList = root.get("rewrites");
            if (!(rawList instanceof List<?> list)) return Collections.emptyList();
            List<String[]> result = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) continue;
                Object oldRaw = m.get("old");
                Object newRaw = m.get("new");
                if (!(oldRaw instanceof String oldVal) || !(newRaw instanceof String newVal)) {
                    log.warn("url-rewrites.yml: 유효하지 않은 항목 무시 — old={}, new={}", oldRaw, newRaw);
                    continue;
                }
                result.add(new String[]{oldVal, newVal});
            }
            return result;
        } catch (Exception e) {
            log.warn("url-rewrites.yml 로드 실패: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    private static Map<String, Map<String, String>> loadLabelColors() {
        File file = new File("label-colors.yml");
        if (!file.exists()) return Collections.emptyMap();
        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            Map<String, Object> root = mapper.readValue(file, new TypeReference<Map<String, Object>>() {});
            Map<String, Map<String, String>> result = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : root.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> inner)) continue;
                Map<String, String> colorMap = new java.util.LinkedHashMap<>();
                for (Map.Entry<?, ?> e : inner.entrySet()) {
                    if (e.getKey() instanceof String k && e.getValue() instanceof String v) {
                        colorMap.put(k, v);
                    }
                }
                result.put(entry.getKey(), colorMap);
            }
            log.info("label-colors.yml 로드: {}개 카테고리", result.size());
            return result;
        } catch (Exception e) {
            log.warn("label-colors.yml 로드 실패: {}", e.getMessage());
        }
        return Collections.emptyMap();
    }

    private static Map<String, String> loadUserMapping() {
        File file = new File("user-mapping.yml");
        if (!file.exists()) return Collections.emptyMap();
        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            Map<String, Object> root = mapper.readValue(file, new TypeReference<Map<String, Object>>() {});
            Object users = root.get("users");
            if (!(users instanceof Map<?, ?> m)) return Collections.emptyMap();
            Map<String, String> result = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : m.entrySet()) {
                if (entry.getKey() instanceof String k && entry.getValue() instanceof String v) {
                    result.put(k, v);
                } else {
                    log.warn("user-mapping.yml: 유효하지 않은 항목 무시 — key={}, value={}",
                             entry.getKey(), entry.getValue());
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("user-mapping.yml 로드 실패: {}", e.getMessage());
        }
        return Collections.emptyMap();
    }

    public String getRedmineUrl()      { return redmineUrl; }
    public String getRedmineApiKey()   { return redmineApiKey; }
    public String getRedmineUsername() { return redmineUsername; }
    public String getRedminePassword() { return redminePassword; }
    public String getRedmineProject()  { return redmineProject; }
    /** Redmine 프로젝트 표시 이름 (fetch-all 시 설정). null이면 slug를 이름으로 대체 사용. */
    public String getProjectName() { return projectName != null ? projectName : getProjectSlug(); }
    public String getGithubToken()    { return githubToken; }
    public String getGithubRepo()     { return githubRepo; }
    public String getOutputDir()      { return outputDir; }
    /** 원본 캐시 루트 경로 (환경 변수 CACHE_DIR 값). */
    public String getCacheDir()       { return cacheDir; }
    /**
     * 프로젝트별 캐시 디렉터리: {@code {cacheDir}/{projectSlug}}.
     * 프로젝트가 달라져도 캐시가 섞이지 않도록 분리한다.
     */
    public String getProjectCacheDir() { return cacheDir + "/" + getProjectSlug(); }
    public String getUploadMethod()         { return uploadMethod; }
    public Map<String, String> getUserMapping() { return userMapping; }
    public int getRequestDelayMs()          { return requestDelayMs; }
    public List<String> getRedmineProjects() { return redmineProjects; }
    public List<String[]> getUrlRewrites()    { return urlRewrites; }
    public boolean isIssueMdFetch()           { return issueMdFetch; }
    /** 업로드 파일 크기 상한 (KB). 0 이하이면 제한 없음. */
    public long getUploadMaxFileSizeKb()      { return uploadMaxFileSizeKb; }
    /** label-colors.yml 에서 로드한 색상 맵. {카테고리 → {항목명 → hex색상}}. */
    public Map<String, Map<String, String>> getLabelColors() { return labelColors; }

    /**
     * 프로젝트별 출력 디렉터리: {@code {outputDir}/{project}}.
     * Redmine 프로젝트 식별자는 URL-safe 슬러그이므로 그대로 사용하되,
     * 안전을 위해 알파벳·숫자·점·하이픈·언더스코어 외의 문자는 '_'로 치환한다.
     */
    /** 프로젝트 식별자를 파일시스템·GitHub 경로 안전 슬러그로 반환한다. */
    public String getProjectSlug() {
        return redmineProject.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public String getProjectOutputDir() {
        return outputDir + "/" + getProjectSlug();
    }

    /**
     * 프로젝트 식별자만 다른 새 AppConfig를 반환한다.
     * fetch-all-projects 에서 프로젝트별로 설정을 교체할 때 사용한다.
     */
    public AppConfig withProject(String projectIdentifier) {
        return withProject(projectIdentifier, null);
    }

    /**
     * 프로젝트 식별자와 표시 이름을 지정한 새 AppConfig를 반환한다.
     * fetch-all-projects 에서 Redmine 프로젝트명을 보존할 때 사용한다.
     */
    public AppConfig withProject(String projectIdentifier, String projectDisplayName) {
        return new AppConfig(redmineUrl, redmineApiKey, redmineUsername, redminePassword,
                projectIdentifier, projectDisplayName, githubToken, githubRepo, outputDir, cacheDir,
                uploadMethod, userMapping, requestDelayMs, Collections.emptyList(), urlRewrites,
                issueMdFetch, uploadMaxFileSizeKb, labelColors);
    }
}
