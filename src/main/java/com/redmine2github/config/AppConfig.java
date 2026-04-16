package com.redmine2github.config;

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

    /** 특정 필드를 직접 주입하는 내부 생성자 (withProject 등에서 사용). */
    private AppConfig(String redmineUrl, String redmineApiKey, String redmineUsername,
                      String redminePassword, String redmineProject,
                      String githubToken, String githubRepo,
                      String outputDir, String cacheDir, String uploadMethod,
                      Map<String, String> userMapping, int requestDelayMs,
                      List<String> redmineProjects, List<String[]> urlRewrites,
                      boolean issueMdFetch, long uploadMaxFileSizeKb) {
        this.redmineUrl          = redmineUrl;
        this.redmineApiKey       = redmineApiKey;
        this.redmineUsername     = redmineUsername;
        this.redminePassword     = redminePassword;
        this.redmineProject      = redmineProject;
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
    }

    private AppConfig(Dotenv env, Map<String, String> userMapping) {
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
    }

    public static AppConfig load() {
        Dotenv env = Dotenv.configure().ignoreIfMissing().load();
        Map<String, String> userMapping = loadUserMapping();
        return new AppConfig(env, userMapping);
    }

    @SuppressWarnings("unchecked")
    private static List<String[]> loadUrlRewrites() {
        File file = new File("url-rewrites.yml");
        if (!file.exists()) return Collections.emptyList();
        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            Map<String, Object> root = mapper.readValue(file, Map.class);
            Object rawList = root.get("rewrites");
            if (rawList instanceof List<?> list) {
                List<String[]> result = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        String oldVal = (String) m.get("old");
                        String newVal = (String) m.get("new");
                        if (oldVal != null && newVal != null) {
                            result.add(new String[]{oldVal, newVal});
                        }
                    }
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("url-rewrites.yml 로드 실패: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> loadUserMapping() {
        File file = new File("user-mapping.yml");
        if (!file.exists()) return Collections.emptyMap();
        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            Map<String, Object> root = mapper.readValue(file, Map.class);
            Object users = root.get("users");
            if (users instanceof Map<?, ?> m) {
                return (Map<String, String>) m;
            }
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
        return new AppConfig(redmineUrl, redmineApiKey, redmineUsername, redminePassword,
                projectIdentifier, githubToken, githubRepo, outputDir, cacheDir,
                uploadMethod, userMapping, requestDelayMs, Collections.emptyList(), urlRewrites,
                issueMdFetch, uploadMaxFileSizeKb);
    }
}
