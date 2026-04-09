package com.redmine2github.redmine;

import com.redmine2github.cache.CacheManager;
import com.redmine2github.config.AppConfig;
import com.redmine2github.redmine.model.RedmineIssue;
import com.redmine2github.redmine.model.RedmineTimeEntry;
import com.redmine2github.redmine.model.RedmineVersion;
import com.redmine2github.redmine.model.RedmineWikiPage;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RedmineClientTest {

    private MockWebServer server;
    private AppConfig config;
    private CacheManager noCache;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        String baseUrl = server.url("").toString().replaceAll("/$", "");

        config = mock(AppConfig.class);
        when(config.getRedmineUrl()).thenReturn(baseUrl);
        when(config.getRedmineProject()).thenReturn("testproject");
        when(config.getRedmineApiKey()).thenReturn("test-api-key");
        when(config.getRedmineUsername()).thenReturn("");
        when(config.getRedminePassword()).thenReturn("");
        when(config.getCacheDir()).thenReturn("./cache-test");

        // 캐시 비활성 (매 테스트마다 실제 HTTP 요청 확인)
        noCache = mock(CacheManager.class);
        when(noCache.loadArray(anyString())).thenReturn(java.util.Optional.empty());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    // ── Wiki ─────────────────────────────────────────────────────────────────

    @Test
    void fetchAllWikiPages_returnsPages() throws Exception {
        // 1) 목록 응답
        server.enqueue(new MockResponse()
            .setBody("{\"wiki_pages\":[{\"title\":\"GettingStarted\"}]}")
            .addHeader("Content-Type", "application/json"));
        // 2) 상세 응답
        server.enqueue(new MockResponse()
            .setBody("{\"wiki_page\":{\"title\":\"GettingStarted\",\"text\":\"h1. Hello\",\"attachments\":[]}}")
            .addHeader("Content-Type", "application/json"));

        RedmineClient client = new RedmineClient(config, noCache);
        List<RedmineWikiPage> pages = client.fetchAllWikiPages();

        assertEquals(1, pages.size());
        assertEquals("GettingStarted", pages.get(0).getTitle());
        assertEquals("h1. Hello", pages.get(0).getText());
    }

    @Test
    void fetchAllWikiPages_sendsApiKeyHeader() throws Exception {
        server.enqueue(new MockResponse()
            .setBody("{\"wiki_pages\":[{\"title\":\"Page1\"}]}")
            .addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse()
            .setBody("{\"wiki_page\":{\"title\":\"Page1\",\"text\":\"\",\"attachments\":[]}}")
            .addHeader("Content-Type", "application/json"));

        RedmineClient client = new RedmineClient(config, noCache);
        client.fetchAllWikiPages();

        RecordedRequest req = server.takeRequest();
        assertEquals("test-api-key", req.getHeader("X-Redmine-API-Key"));
    }

    @Test
    void fetchAllWikiPages_parentTitle() throws Exception {
        server.enqueue(new MockResponse()
            .setBody("{\"wiki_pages\":[{\"title\":\"SubPage\"}]}")
            .addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse()
            .setBody("{\"wiki_page\":{\"title\":\"SubPage\",\"text\":\"\","
                + "\"parent\":{\"title\":\"ParentPage\"},\"attachments\":[]}}")
            .addHeader("Content-Type", "application/json"));

        RedmineClient client = new RedmineClient(config, noCache);
        List<RedmineWikiPage> pages = client.fetchAllWikiPages();

        assertEquals("ParentPage", pages.get(0).getParentTitle());
    }

    // ── Issues ────────────────────────────────────────────────────────────────

    @Test
    void fetchAllIssues_paginates() throws Exception {
        // 첫 페이지 (1건, total=2 → 다음 페이지 요청)
        server.enqueue(new MockResponse()
            .setBody("{\"issues\":[{\"id\":1,\"subject\":\"First\",\"description\":\"\","
                + "\"status\":{\"name\":\"New\"},\"tracker\":{\"name\":\"Bug\"},"
                + "\"priority\":{\"name\":\"Normal\"},\"journals\":[],\"attachments\":[]}],"
                + "\"total_count\":2}")
            .addHeader("Content-Type", "application/json"));
        // 두 번째 페이지
        server.enqueue(new MockResponse()
            .setBody("{\"issues\":[{\"id\":2,\"subject\":\"Second\",\"description\":\"\","
                + "\"status\":{\"name\":\"Closed\"},\"tracker\":{\"name\":\"Feature\"},"
                + "\"priority\":{\"name\":\"High\"},\"journals\":[],\"attachments\":[]}],"
                + "\"total_count\":2}")
            .addHeader("Content-Type", "application/json"));

        RedmineClient client = new RedmineClient(config, noCache);
        List<RedmineIssue> issues = client.fetchAllIssues();

        assertEquals(2, issues.size());
        assertEquals(1, issues.get(0).getId());
        assertEquals("First", issues.get(0).getSubject());
        assertEquals(2, issues.get(1).getId());
        assertEquals("Closed", issues.get(1).getStatus());
    }

    @Test
    void fetchAllIssues_emptyResponse() throws Exception {
        server.enqueue(new MockResponse()
            .setBody("{\"issues\":[],\"total_count\":0}")
            .addHeader("Content-Type", "application/json"));

        RedmineClient client = new RedmineClient(config, noCache);
        List<RedmineIssue> issues = client.fetchAllIssues();

        assertTrue(issues.isEmpty());
    }

    // ── Time Entries ──────────────────────────────────────────────────────────

    @Test
    void fetchAllTimeEntries_returnsEntries() throws Exception {
        server.enqueue(new MockResponse()
            .setBody("{\"time_entries\":[{\"id\":10,\"issue\":{\"id\":1},"
                + "\"spent_on\":\"2024-01-15\",\"hours\":3.5,"
                + "\"activity\":{\"name\":\"Development\"},"
                + "\"user\":{\"login\":\"jdoe\"},\"comments\":\"fix bug\"}],"
                + "\"total_count\":1}")
            .addHeader("Content-Type", "application/json"));

        RedmineClient client = new RedmineClient(config, noCache);
        List<RedmineTimeEntry> entries = client.fetchAllTimeEntries();

        assertEquals(1, entries.size());
        assertEquals(1, entries.get(0).getIssueId());
        assertEquals(3.5, entries.get(0).getHours());
        assertEquals("Development", entries.get(0).getActivity());
    }

    // ── Versions ──────────────────────────────────────────────────────────────

    @Test
    void fetchVersions_returnsVersions() throws Exception {
        server.enqueue(new MockResponse()
            .setBody("{\"versions\":[{\"id\":5,\"name\":\"v1.0\",\"description\":\"First\","
                + "\"due_date\":\"2024-06-01\",\"status\":\"open\"}]}")
            .addHeader("Content-Type", "application/json"));

        RedmineClient client = new RedmineClient(config, noCache);
        List<RedmineVersion> versions = client.fetchVersions();

        assertEquals(1, versions.size());
        assertEquals("v1.0", versions.get(0).getName());
        assertEquals("2024-06-01", versions.get(0).getDueDate());
    }

    // ── Auth: BASIC ───────────────────────────────────────────────────────────

    @Test
    void basicAuth_sendsAuthorizationHeader() throws Exception {
        when(config.getRedmineApiKey()).thenReturn("");
        when(config.getRedmineUsername()).thenReturn("admin");
        when(config.getRedminePassword()).thenReturn("secret");

        server.enqueue(new MockResponse()
            .setBody("{\"issues\":[],\"total_count\":0}")
            .addHeader("Content-Type", "application/json"));

        RedmineClient client = new RedmineClient(config, noCache);
        client.fetchAllIssues();

        RecordedRequest req = server.takeRequest();
        String auth = req.getHeader("Authorization");
        assertNotNull(auth);
        assertTrue(auth.startsWith("Basic "), "Basic Auth 헤더 누락: " + auth);
        assertNull(req.getHeader("X-Redmine-API-Key"), "API Key 헤더가 없어야 함");
    }

    // ── Cache ─────────────────────────────────────────────────────────────────

    @Test
    void fetchAllIssues_usesCacheWhenAvailable() throws Exception {
        // 캐시 히트 시 HTTP 호출 없어야 함
        CacheManager cacheWithData = mock(CacheManager.class);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ArrayNode arr = mapper.createArrayNode();
        com.fasterxml.jackson.databind.node.ObjectNode issueNode = mapper.createObjectNode();
        issueNode.put("id", 99);
        issueNode.put("subject", "Cached Issue");
        issueNode.put("description", "");
        issueNode.putObject("status").put("name", "New");
        issueNode.putObject("tracker").put("name", "Bug");
        issueNode.putObject("priority").put("name", "Normal");
        issueNode.putArray("journals");
        issueNode.putArray("attachments");
        arr.add(issueNode);
        when(cacheWithData.loadArray("issues")).thenReturn(java.util.Optional.of(arr));
        when(cacheWithData.loadArray(anyString())).thenReturn(java.util.Optional.empty());
        when(cacheWithData.loadArray("issues")).thenReturn(java.util.Optional.of(arr));

        RedmineClient client = new RedmineClient(config, cacheWithData);
        List<RedmineIssue> issues = client.fetchAllIssues();

        assertEquals(1, issues.size());
        assertEquals(99, issues.get(0).getId());
        assertEquals(0, server.getRequestCount(), "캐시 히트 시 HTTP 요청 없어야 함");
    }

    // ── 인증 실패 처리 ────────────────────────────────────────────────────────

    @Test
    void missingCredentials_throwsIllegalStateException() {
        when(config.getRedmineApiKey()).thenReturn("");
        when(config.getRedmineUsername()).thenReturn("");
        when(config.getRedminePassword()).thenReturn("");

        assertThrows(IllegalStateException.class, () -> new RedmineClient(config, noCache));
    }
}
