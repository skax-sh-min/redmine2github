package com.redmine2github.redmine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.redmine2github.cache.CacheManager;
import com.redmine2github.config.AppConfig;
import com.redmine2github.redmine.model.*;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class RedmineClient {

    private static final Logger log = LoggerFactory.getLogger(RedmineClient.class);
    private static final int PAGE_SIZE = 100;

    /** 인증 방식 */
    private enum AuthMode { API_KEY, BASIC }

    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String project;
    private final AuthMode authMode;
    private final String apiKey;            // API_KEY 방식
    private final String basicCredential;  // BASIC 방식
    private final CacheManager cache;      // null이면 캐시 비활성
    private final long requestDelayMs;     // API 요청 간 지연(ms), 0이면 제한 없음

    public RedmineClient(AppConfig config) {
        this(config, new CacheManager(config.getProjectCacheDir()));
    }

    public RedmineClient(AppConfig config, CacheManager cache) {
        this.http    = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.mapper  = new ObjectMapper();
        this.baseUrl = config.getRedmineUrl();
        this.project = config.getRedmineProject();
        this.cache   = cache;
        this.requestDelayMs = config.getRequestDelayMs();

        if (!config.getRedmineApiKey().isBlank()) {
            this.authMode        = AuthMode.API_KEY;
            this.apiKey          = config.getRedmineApiKey();
            this.basicCredential = null;
            log.info("Redmine 인증: API Key 방식");
        } else if (!config.getRedmineUsername().isBlank()) {
            this.authMode        = AuthMode.BASIC;
            this.apiKey          = null;
            this.basicCredential = Credentials.basic(config.getRedmineUsername(), config.getRedminePassword());
            log.info("Redmine 인증: ID/PW (Basic Auth) 방식 - 사용자: {}", config.getRedmineUsername());
        } else {
            throw new IllegalStateException(
                "Redmine 인증 정보가 없습니다. REDMINE_API_KEY 또는 REDMINE_USERNAME/REDMINE_PASSWORD를 설정하세요.");
        }
    }

    // ── Wiki ──────────────────────────────────────────────────────────────

    public List<RedmineWikiPage> fetchAllWikiPages() {
        if (cache != null) {
            Optional<ArrayNode> cached = cache.loadArray("wiki_pages");
            if (cached.isPresent()) {
                List<RedmineWikiPage> list = new ArrayList<>();
                for (JsonNode n : cached.get()) list.add(RedmineWikiPage.from(n));
                return list;
            }
        }

        // 1) 목록 API → 제목 수집
        String indexUrl = baseUrl + "/projects/" + project + "/wiki/index.json";
        JsonNode root = get(indexUrl);

        // 2) 각 페이지 상세 수집 (text + parent + attachments 포함)
        ArrayNode detailArray = mapper.createArrayNode();
        List<RedmineWikiPage> pages = new ArrayList<>();
        for (JsonNode node : root.path("wiki_pages")) {
            String title = node.path("title").asText();
            String encodedTitle = URLEncoder.encode(title, StandardCharsets.UTF_8)
                    .replace("+", "%20");  // space → %20 (+ 는 path에서 literal)
            String detailUrl = baseUrl + "/projects/" + project + "/wiki/" + encodedTitle + ".json?include=attachments";
            JsonNode detail = get(detailUrl).path("wiki_page");
            detailArray.add(detail);
            pages.add(RedmineWikiPage.from(detail));
        }

        // 상세 노드 전체를 캐시에 저장 (인덱스 노드만 저장하던 기존 버그 수정)
        if (cache != null) cache.saveArray("wiki_pages", detailArray);
        return pages;
    }

    public RedmineWikiPage fetchWikiPage(String title) {
        String encodedTitle = URLEncoder.encode(title, StandardCharsets.UTF_8).replace("+", "%20");
        String url = baseUrl + "/projects/" + project + "/wiki/" + encodedTitle + ".json?include=attachments";
        JsonNode node = get(url).path("wiki_page");
        return RedmineWikiPage.from(node);
    }

    // ── Issues ────────────────────────────────────────────────────────────

    public List<RedmineIssue> fetchAllIssues() {
        if (cache != null) {
            Optional<ArrayNode> cached = cache.loadArray("issues");
            if (cached.isPresent()) {
                List<RedmineIssue> list = new ArrayList<>();
                for (JsonNode n : cached.get()) list.add(RedmineIssue.from(n));
                return list;
            }
        }

        // 하위 프로젝트 이슈 제외 (프로젝트별 중복 방지)
        List<JsonNode> nodes = fetchAllPages(
                baseUrl + "/issues.json?project_id=" + project
                        + "&subproject_id=!*&status_id=*&include=journals,attachments",
                "issues");
        ArrayNode rawArray = mapper.createArrayNode();
        List<RedmineIssue> issues = new ArrayList<>();
        for (JsonNode node : nodes) {
            rawArray.add(node);
            issues.add(RedmineIssue.from(node));
        }
        if (cache != null) cache.saveArray("issues", rawArray);
        return issues;
    }

    /**
     * 단건 이슈를 상세 조회한다 — journals.details(필드 변경 이력) 포함.
     *
     * <p>목록 API({@code /issues.json})는 {@code journals.details}를 반환하지 않는다.
     * 단건 API({@code /issues/{id}.json})만 전체 이력 데이터를 포함하므로,
     * 이력 정보가 필요한 fetch 단계에서 이슈별로 별도 호출한다.
     *
     * <p>결과는 {@code cache/issue_{id}.json}에 캐싱되어 재실행 시 API 호출을 생략한다.
     */
    public RedmineIssue fetchIssueDetail(int id) {
        String cacheKey = "issue_" + id;
        if (cache != null) {
            Optional<JsonNode> cached = cache.loadNode(cacheKey);
            if (cached.isPresent()) return RedmineIssue.from(cached.get());
        }

        String url = baseUrl + "/issues/" + id + ".json?include=journals,attachments";
        JsonNode issueNode = get(url).path("issue");

        if (cache != null) cache.saveNode(cacheKey, issueNode);
        return RedmineIssue.from(issueNode);
    }

    // ── Time Entries ──────────────────────────────────────────────────────

    public List<RedmineTimeEntry> fetchAllTimeEntries() {
        if (cache != null) {
            Optional<ArrayNode> cached = cache.loadArray("time_entries");
            if (cached.isPresent()) {
                List<RedmineTimeEntry> list = new ArrayList<>();
                for (JsonNode n : cached.get()) list.add(RedmineTimeEntry.from(n));
                return list;
            }
        }

        List<JsonNode> nodes;
        try {
            nodes = fetchAllPages(baseUrl + "/time_entries.json?project_id=" + project, "time_entries");
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("[403]")) {
                log.warn("작업 내역 조회 권한 없음 (403) — time entries 스킵 (데이터 누락됨). " +
                         "Redmine 관리자에게 'Log time' 권한을 요청하거나 --only 옵션으로 제외하세요.");
                log.warn("[데이터 누락] time_entries: 403 Forbidden — 마이그레이션 결과에 작업 내역이 포함되지 않습니다.");
                return Collections.emptyList();
            }
            throw e;
        }
        ArrayNode rawArray = mapper.createArrayNode();
        List<RedmineTimeEntry> entries = new ArrayList<>();
        for (JsonNode node : nodes) {
            rawArray.add(node);
            entries.add(RedmineTimeEntry.from(node));
        }
        if (cache != null) cache.saveArray("time_entries", rawArray);
        return entries;
    }

    // ── Projects ──────────────────────────────────────────────────────────

    /** 접근 가능한 전체 프로젝트 목록을 페이지네이션하여 수집한다. */
    public List<RedmineProject> fetchAllProjects() {
        List<RedmineProject> projects = new ArrayList<>();
        for (JsonNode node : fetchAllPages(baseUrl + "/projects.json", "projects")) {
            projects.add(RedmineProject.from(node));
        }
        return projects;
    }

    // ── Meta ──────────────────────────────────────────────────────────────

    public List<RedmineVersion> fetchVersions() {
        if (cache != null) {
            Optional<ArrayNode> cached = cache.loadArray("versions");
            if (cached.isPresent()) {
                List<RedmineVersion> list = new ArrayList<>();
                for (JsonNode n : cached.get()) list.add(RedmineVersion.from(n));
                return list;
            }
        }

        String url = baseUrl + "/projects/" + project + "/versions.json";
        JsonNode root = get(url);
        ArrayNode rawArray = mapper.createArrayNode();
        List<RedmineVersion> list = new ArrayList<>();
        for (JsonNode node : root.path("versions")) {
            rawArray.add(node);
            list.add(RedmineVersion.from(node));
        }

        if (cache != null) cache.saveArray("versions", rawArray);
        return list;
    }

    /** 트래커 목록 수집 — GET /trackers.json */
    public List<RedmineNamedItem> fetchTrackers() {
        if (cache != null) {
            Optional<ArrayNode> cached = cache.loadArray("trackers");
            if (cached.isPresent()) {
                List<RedmineNamedItem> list = new ArrayList<>();
                for (JsonNode n : cached.get()) list.add(RedmineNamedItem.from(n));
                return list;
            }
        }

        String url = baseUrl + "/trackers.json";
        JsonNode root = get(url);
        ArrayNode rawArray = mapper.createArrayNode();
        List<RedmineNamedItem> list = new ArrayList<>();
        for (JsonNode node : root.path("trackers")) {
            rawArray.add(node);
            list.add(RedmineNamedItem.from(node));
        }

        if (cache != null) cache.saveArray("trackers", rawArray);
        return list;
    }

    /** 이슈 카테고리 목록 수집 — GET /projects/{id}/issue_categories.json */
    public List<RedmineNamedItem> fetchIssueCategories() {
        if (cache != null) {
            Optional<ArrayNode> cached = cache.loadArray("issue_categories");
            if (cached.isPresent()) {
                List<RedmineNamedItem> list = new ArrayList<>();
                for (JsonNode n : cached.get()) list.add(RedmineNamedItem.from(n));
                return list;
            }
        }

        String url = baseUrl + "/projects/" + project + "/issue_categories.json";
        JsonNode root = get(url);
        ArrayNode rawArray = mapper.createArrayNode();
        List<RedmineNamedItem> list = new ArrayList<>();
        for (JsonNode node : root.path("issue_categories")) {
            rawArray.add(node);
            list.add(RedmineNamedItem.from(node));
        }

        if (cache != null) cache.saveArray("issue_categories", rawArray);
        return list;
    }

    /** 우선순위 목록 수집 — GET /enumerations/issue_priorities.json */
    public List<RedmineNamedItem> fetchIssuePriorities() {
        if (cache != null) {
            Optional<ArrayNode> cached = cache.loadArray("issue_priorities");
            if (cached.isPresent()) {
                List<RedmineNamedItem> list = new ArrayList<>();
                for (JsonNode n : cached.get()) list.add(RedmineNamedItem.from(n));
                return list;
            }
        }

        String url = baseUrl + "/enumerations/issue_priorities.json";
        JsonNode root = get(url);
        ArrayNode rawArray = mapper.createArrayNode();
        List<RedmineNamedItem> list = new ArrayList<>();
        for (JsonNode node : root.path("issue_priorities")) {
            rawArray.add(node);
            list.add(RedmineNamedItem.from(node));
        }

        if (cache != null) cache.saveArray("issue_priorities", rawArray);
        return list;
    }

    public List<RedmineUser> fetchUsers() {
        if (cache != null) {
            Optional<ArrayNode> cached = cache.loadArray("users");
            if (cached.isPresent()) {
                List<RedmineUser> list = new ArrayList<>();
                for (JsonNode n : cached.get()) list.add(RedmineUser.from(n));
                return list;
            }
        }

        String url = baseUrl + "/users.json?limit=" + PAGE_SIZE;
        JsonNode root;
        try {
            root = get(url);
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("[403]")) {
                log.warn("/users.json 접근 권한 없음 (403) — Redmine 관리자 권한이 필요합니다. " +
                         "프로젝트 구성원 목록으로 대체합니다 (login 필드 누락 주의).");
                log.warn("[데이터 누락] users: 403 Forbidden — user-mapping.yml 생성 시 login 대신 표시명이 사용됩니다.");
                return fetchMemberUsers();
            }
            throw e;
        }
        ArrayNode rawArray = mapper.createArrayNode();
        List<RedmineUser> list = new ArrayList<>();
        for (JsonNode node : root.path("users")) {
            rawArray.add(node);
            list.add(RedmineUser.from(node));
        }

        if (cache != null) cache.saveArray("users", rawArray);
        return list;
    }

    /**
     * /users.json 접근 불가(403) 시 대체 수단.
     * GET /projects/{project}/memberships.json 으로 프로젝트 구성원을 수집한다.
     * 멤버십 응답에는 login이 없으므로 display name을 login 자리에 사용한다.
     * 생성된 user-mapping.yml에서 직접 Redmine login으로 수정이 필요하다.
     */
    private List<RedmineUser> fetchMemberUsers() {
        if (project == null || project.isBlank()) {
            log.warn("REDMINE_PROJECT가 설정되지 않아 구성원 목록을 조회할 수 없습니다.");
            return Collections.emptyList();
        }

        List<RedmineUser> list = new ArrayList<>();
        try {
            for (JsonNode node : fetchAllPages(
                    baseUrl + "/projects/" + project + "/memberships.json", "memberships")) {
                // group 멤버십(user 필드 없음)은 건너뜀
                JsonNode userNode = node.path("user");
                if (userNode.isMissingNode()) continue;
                int id = userNode.path("id").asInt();
                String name = userNode.path("name").asText("");
                // login이 없으므로 display name을 임시 key로 사용
                list.add(new RedmineUser(id, name, "", ""));
            }
        } catch (RuntimeException e) {
            log.warn("프로젝트 구성원 목록 조회 실패: {}", e.getMessage());
        }
        return list;
    }

    // ── Attachments ───────────────────────────────────────────────────────

    /**
     * 첨부파일을 다운로드해 {@code destDir/filename}에 저장한다.
     * 이미 파일이 존재하면 건너뛴다.
     *
     * @param att     첨부파일 메타데이터
     * @param destDir 저장 대상 디렉터리
     * @return 저장된 파일 경로
     */
    public Path downloadAttachment(RedmineAttachment att, Path destDir) throws IOException {
        String safeName = sanitizeAttachmentFilename(att.getFilename());
        Path normalizedDir = destDir.normalize();

        Path dest = normalizedDir.resolve(safeName).normalize();
        if (!dest.startsWith(normalizedDir)) {
            throw new IOException("경로 탐색 시도 차단: " + att.getFilename());
        }

        if (Files.exists(dest)) {
            if (isSameFile(dest, att)) {
                log.debug("첨부파일 스킵 (동일 파일): {}", dest);
                return dest;
            }
            // 크기 또는 checksum이 다른 동일명 파일: id 접두사로 분리
            dest = normalizedDir.resolve(att.getId() + "_" + safeName).normalize();
            if (!dest.startsWith(normalizedDir)) {
                throw new IOException("경로 탐색 시도 차단 (id 접두사): " + att.getFilename());
            }
            if (Files.exists(dest)) {
                log.debug("첨부파일 스킵 (이미 존재, id접두사): {}", dest);
                return dest;
            }
            log.info("동일 파일명 충돌 — id 접두사 사용: {}", dest.getFileName());
        }

        Files.createDirectories(destDir);
        throttle();
        Request.Builder builder = new Request.Builder()
                .url(att.getContentUrl());

        if (authMode == AuthMode.API_KEY) {
            builder.header("X-Redmine-API-Key", apiKey);
        } else {
            builder.header("Authorization", basicCredential);
        }

        try (Response res = http.newCall(builder.build()).execute()) {
            if (!res.isSuccessful()) {
                throw new IOException("첨부파일 다운로드 실패 [" + res.code() + "]: " + att.getContentUrl());
            }
            var body = res.body();
            if (body == null) throw new IOException("응답 본문이 없습니다: " + att.getContentUrl());
            Files.write(dest, body.bytes());
            log.info("첨부파일 저장: {}", dest);
        }
        return dest;
    }

    /**
     * 기존 파일이 첨부파일 메타데이터와 동일한지 확인한다.
     * 1차: 파일 크기 비교 / 2차: MD5 digest 비교 (Redmine digest가 있는 경우)
     */
    private boolean isSameFile(Path existing, RedmineAttachment att) {
        try {
            if (att.getFilesize() > 0 && Files.size(existing) != att.getFilesize()) {
                return false;
            }
            if (!att.getDigest().isBlank()) {
                String existingMd5 = computeMd5(existing);
                return att.getDigest().equalsIgnoreCase(existingMd5);
            }
            return true; // 크기 같고 digest 없음 → 동일로 간주
        } catch (IOException e) {
            log.warn("파일 비교 실패 [{}]: {}", existing, e.getMessage());
            return false;
        }
    }

    /**
     * 첨부파일명에서 경로 성분을 제거하고 파일시스템 금지 문자를 '_'로 치환한다.
     * Path Traversal(../../../etc/passwd 등) 방어용.
     */
    private static String sanitizeAttachmentFilename(String filename) {
        if (filename == null || filename.isBlank()) return "_";
        // 경로 구분자 포함 시 파일명만 추출 (e.g. "../../evil" → "evil")
        String name = Path.of(filename).getFileName().toString();
        // Windows/Linux 금지 문자 치환
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.isBlank()) return "_";
        return name;
    }

    /** 파일의 MD5 hex digest를 계산한다. */
    private String computeMd5(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(Files.readAllBytes(file));
            byte[] hash = md.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("MD5 알고리즘 없음", e);
        }
    }

    /**
     * 지정 URL의 파일을 {@code destFile} 경로로 다운로드한다.
     * 이미 파일이 존재하면 건너뛴다.
     *
     * @param url      다운로드 URL
     * @param destFile 저장할 파일 경로 (디렉터리 포함하여 자동 생성)
     */
    public void downloadToFile(String url, Path destFile) throws IOException {
        if (Files.exists(destFile)) {
            log.debug("외부 파일 스킵 (이미 존재): {}", destFile);
            return;
        }

        Files.createDirectories(destFile.getParent());
        throttle();
        Request.Builder builder = new Request.Builder().url(url);

        if (authMode == AuthMode.API_KEY) {
            builder.header("X-Redmine-API-Key", apiKey);
        } else {
            builder.header("Authorization", basicCredential);
        }

        try (Response res = http.newCall(builder.build()).execute()) {
            if (!res.isSuccessful()) {
                throw new IOException("외부 파일 다운로드 실패 [" + res.code() + "]: " + url);
            }
            var body = res.body();
            if (body == null) throw new IOException("응답 본문이 없습니다: " + url);
            Files.write(destFile, body.bytes());
            log.info("외부 파일 저장: {}", destFile);
        }
    }

    // ── HTTP ──────────────────────────────────────────────────────────────

    /** API 요청 전 설정된 지연 시간만큼 대기한다. */
    private void throttle() {
        if (requestDelayMs > 0) {
            try {
                Thread.sleep(requestDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * offset/total_count 페이지네이션으로 전체 항목을 수집한다.
     * total_count가 없으면 빈 페이지가 올 때까지 계속 요청한다.
     */
    private List<JsonNode> fetchAllPages(String basePageUrl, String arrayField) {
        List<JsonNode> result = new ArrayList<>();
        int offset = 0;
        String connector = basePageUrl.contains("?") ? "&" : "?";
        while (true) {
            JsonNode root = get(basePageUrl + connector + "limit=" + PAGE_SIZE + "&offset=" + offset);
            JsonNode arr = root.path(arrayField);
            if (arr.isEmpty()) break;
            for (JsonNode node : arr) result.add(node);
            offset += arr.size();
            int total = root.path("total_count").asInt(-1);
            if (total >= 0 && offset >= total) break;
        }
        return result;
    }

    private JsonNode get(String url) {
        throttle();
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Accept", "application/json");

        if (authMode == AuthMode.API_KEY) {
            builder.header("X-Redmine-API-Key", apiKey);
        } else {
            builder.header("Authorization", basicCredential);
        }

        try (Response response = http.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("Redmine API 오류 [" + response.code() + "]: " + url);
            }
            var body = response.body();
            if (body == null) throw new IOException("응답 본문이 없습니다: " + url);
            return mapper.readTree(body.string());
        } catch (IOException e) {
            throw new RuntimeException("Redmine API 호출 실패: " + url, e);
        }
    }
}
