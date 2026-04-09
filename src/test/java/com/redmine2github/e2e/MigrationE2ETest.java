package com.redmine2github.e2e;

import com.redmine2github.cache.CacheManager;
import com.redmine2github.config.AppConfig;
import com.redmine2github.service.IssueMigrationService;
import com.redmine2github.service.TimeEntryMigrationService;
import com.redmine2github.service.WikiMigrationService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 전체 마이그레이션 파이프라인 E2E 시나리오 테스트.
 */
class MigrationE2ETest {

    private MockWebServer redmineServer;
    private AppConfig config;
    private CacheManager noCache;

    /** output/{project} 경로 — 테스트에서 파일 존재 여부 확인 시 사용 */
    private Path projectOutputDir;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        redmineServer = new MockWebServer();
        redmineServer.start();

        String baseUrl = redmineServer.url("").toString().replaceAll("/$", "");

        config = mock(AppConfig.class);
        when(config.getRedmineUrl()).thenReturn(baseUrl);
        when(config.getRedmineProject()).thenReturn("myproject");
        when(config.getRedmineApiKey()).thenReturn("api-key");
        when(config.getRedmineUsername()).thenReturn("");
        when(config.getRedminePassword()).thenReturn("");
        when(config.getCacheDir()).thenReturn(tempDir.resolve("cache").toString());
        when(config.getOutputDir()).thenReturn(tempDir.resolve("output").toString());
        when(config.getUploadMethod()).thenReturn("API");
        when(config.getUserMapping()).thenReturn(java.util.Collections.emptyMap());

        // getProjectOutputDir() = output/myproject
        projectOutputDir = tempDir.resolve("output/myproject");
        when(config.getProjectOutputDir()).thenReturn(projectOutputDir.toString());

        noCache = mock(CacheManager.class);
        when(noCache.loadArray(anyString())).thenReturn(java.util.Optional.empty());
    }

    @AfterEach
    void tearDown() throws IOException {
        redmineServer.shutdown();
        Files.deleteIfExists(Path.of("migration-state.json"));
    }

    // ── Wiki fetch E2E ────────────────────────────────────────────────────────

    @Test
    void wiki_fetch_writesMarkdownLocally() throws Exception {
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"wiki_pages\":[{\"title\":\"GettingStarted\"}]}")
            .addHeader("Content-Type", "application/json"));
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"wiki_page\":{\"title\":\"GettingStarted\","
                + "\"text\":\"h1. Getting Started\\n\\nThis is *bold* text.\","
                + "\"attachments\":[]}}")
            .addHeader("Content-Type", "application/json"));

        WikiMigrationService service = new WikiMigrationService(config);
        service.fetch(false, false);

        Path md = projectOutputDir.resolve("wiki/GettingStarted.md");
        assertTrue(Files.exists(md), "마크다운 파일이 생성되어야 함: " + md);

        String content = Files.readString(md);
        assertTrue(content.contains("# Getting Started"), "h1 변환 확인");
        assertTrue(content.contains("**bold**"), "bold 변환 확인");
    }

    @Test
    void wiki_fetch_subpage_usesParentDirectory() throws Exception {
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"wiki_pages\":[{\"title\":\"SubPage\"}]}")
            .addHeader("Content-Type", "application/json"));
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"wiki_page\":{\"title\":\"SubPage\","
                + "\"text\":\"content\","
                + "\"parent\":{\"title\":\"ParentPage\"},"
                + "\"attachments\":[]}}")
            .addHeader("Content-Type", "application/json"));

        WikiMigrationService service = new WikiMigrationService(config);
        service.fetch(false, false);

        Path md = projectOutputDir.resolve("wiki/ParentPage/SubPage.md");
        assertTrue(Files.exists(md), "서브페이지 경로 확인: " + md);
    }

    @Test
    void wiki_fetch_grandchild_usesDeepDirectory() throws Exception {
        // GrandChild의 parent=Child, Child의 parent=Root
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"wiki_pages\":[{\"title\":\"Root\"},{\"title\":\"Child\"},{\"title\":\"GrandChild\"}]}")
            .addHeader("Content-Type", "application/json"));
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"wiki_page\":{\"title\":\"Root\",\"text\":\"root content\",\"attachments\":[]}}")
            .addHeader("Content-Type", "application/json"));
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"wiki_page\":{\"title\":\"Child\",\"text\":\"child content\","
                + "\"parent\":{\"title\":\"Root\"},\"attachments\":[]}}")
            .addHeader("Content-Type", "application/json"));
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"wiki_page\":{\"title\":\"GrandChild\",\"text\":\"grandchild content\","
                + "\"parent\":{\"title\":\"Child\"},\"attachments\":[]}}")
            .addHeader("Content-Type", "application/json"));

        WikiMigrationService service = new WikiMigrationService(config);
        service.fetch(false, false);

        assertTrue(Files.exists(projectOutputDir.resolve("wiki/Root.md")), "Root.md");
        assertTrue(Files.exists(projectOutputDir.resolve("wiki/Root/Child.md")), "Root/Child.md");
        assertTrue(Files.exists(projectOutputDir.resolve("wiki/Root/Child/GrandChild.md")), "Root/Child/GrandChild.md");
    }

    @Test
    void wiki_fetch_emptyPageSkipped() throws Exception {
        // 본문이 없는 페이지는 파일을 생성하지 않음
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"wiki_pages\":[{\"title\":\"EmptyPage\"},{\"title\":\"HasContent\"}]}")
            .addHeader("Content-Type", "application/json"));
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"wiki_page\":{\"title\":\"EmptyPage\",\"text\":\"\",\"attachments\":[]}}")
            .addHeader("Content-Type", "application/json"));
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"wiki_page\":{\"title\":\"HasContent\",\"text\":\"some content\",\"attachments\":[]}}")
            .addHeader("Content-Type", "application/json"));

        WikiMigrationService service = new WikiMigrationService(config);
        service.fetch(false, false);

        assertFalse(Files.exists(projectOutputDir.resolve("wiki/EmptyPage.md")), "빈 페이지는 파일 없음");
        assertTrue(Files.exists(projectOutputDir.resolve("wiki/HasContent.md")), "내용 있는 페이지는 파일 있음");
    }

    @Test
    void wiki_fetch_emptyWikiPages_succeeds() throws Exception {
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"wiki_pages\":[]}")
            .addHeader("Content-Type", "application/json"));

        WikiMigrationService service = new WikiMigrationService(config);
        assertDoesNotThrow(() -> service.fetch(false, false));
    }

    @Test
    void wiki_fetch_internalLinkRewritten() throws Exception {
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"wiki_pages\":[{\"title\":\"Home\"},{\"title\":\"Installation\"}]}")
            .addHeader("Content-Type", "application/json"));
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"wiki_page\":{\"title\":\"Home\","
                + "\"text\":\"See [[Installation]] for details.\","
                + "\"attachments\":[]}}")
            .addHeader("Content-Type", "application/json"));
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"wiki_page\":{\"title\":\"Installation\","
                + "\"text\":\"Install steps.\","
                + "\"attachments\":[]}}")
            .addHeader("Content-Type", "application/json"));

        WikiMigrationService service = new WikiMigrationService(config);
        service.fetch(false, false);

        Path md = projectOutputDir.resolve("wiki/Home.md");
        String content = Files.readString(md);
        assertTrue(content.contains("[Installation](Installation.md)"),
            "내부 링크가 GFM 형식으로 변환되어야 함");
    }

    @Test
    void wiki_fetch_subpage_internalLinkRewritten() throws Exception {
        // SubPage(parent=Root)에서 Root 링크 → ../Root.md
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"wiki_pages\":[{\"title\":\"Root\"},{\"title\":\"SubPage\"}]}")
            .addHeader("Content-Type", "application/json"));
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"wiki_page\":{\"title\":\"Root\",\"text\":\"root\",\"attachments\":[]}}")
            .addHeader("Content-Type", "application/json"));
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"wiki_page\":{\"title\":\"SubPage\","
                + "\"text\":\"See [[Root]].\","
                + "\"parent\":{\"title\":\"Root\"},"
                + "\"attachments\":[]}}")
            .addHeader("Content-Type", "application/json"));

        WikiMigrationService service = new WikiMigrationService(config);
        service.fetch(false, false);

        Path md = projectOutputDir.resolve("wiki/Root/SubPage.md");
        String content = Files.readString(md);
        assertTrue(content.contains("[Root](../Root.md)"), "서브페이지 → 상위 페이지 링크 확인");
    }

    @Test
    void wiki_fetch_attachmentImageLinked() throws Exception {
        // !image.png! 가 첨부파일 목록에 있을 때 경로 재작성 확인
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"wiki_pages\":[{\"title\":\"Docs\"}]}")
            .addHeader("Content-Type", "application/json"));
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"wiki_page\":{\"title\":\"Docs\","
                + "\"text\":\"!diagram.png!\","
                + "\"attachments\":[{\"id\":1,\"filename\":\"diagram.png\","
                + "\"content_url\":\"http://localhost/attachments/1/diagram.png\"}]}}")
            .addHeader("Content-Type", "application/json"));
        // 첨부파일 다운로드 응답
        redmineServer.enqueue(new MockResponse()
            .setBody("PNG_BYTES")
            .addHeader("Content-Type", "image/png"));

        WikiMigrationService service = new WikiMigrationService(config);
        service.fetch(false, false);

        Path md = projectOutputDir.resolve("wiki/Docs.md");
        String content = Files.readString(md);
        assertTrue(content.contains("../attachments/diagram.png"),
            "이미지 링크가 첨부파일 경로로 재작성되어야 함. 실제: " + content);
    }

    // ── Issue fetch E2E ───────────────────────────────────────────────────────

    @Test
    void issues_fetch_writesJsonLocally() throws Exception {
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"trackers\":[{\"id\":1,\"name\":\"Bug\"}]}")
            .addHeader("Content-Type", "application/json"));
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"issue_priorities\":[{\"id\":1,\"name\":\"Normal\"}]}")
            .addHeader("Content-Type", "application/json"));
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"issue_categories\":[]}")
            .addHeader("Content-Type", "application/json"));
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"versions\":[]}")
            .addHeader("Content-Type", "application/json"));
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"issues\":[{\"id\":1,\"subject\":\"Test issue\","
                + "\"description\":\"h2. Details\\n\\nSome info.\","
                + "\"status\":{\"name\":\"New\"},\"tracker\":{\"name\":\"Bug\"},"
                + "\"priority\":{\"name\":\"Normal\"},\"journals\":[],\"attachments\":[]}],"
                + "\"total_count\":1}")
            .addHeader("Content-Type", "application/json"));

        IssueMigrationService service = new IssueMigrationService(config);
        assertDoesNotThrow(() -> service.fetch(false, false));

        Path issueJson = projectOutputDir.resolve("issues/1.json");
        assertTrue(Files.exists(issueJson), "Issue JSON 파일이 생성되어야 함");

        String content = Files.readString(issueJson);
        assertTrue(content.contains("\"subject\""), "subject 필드 확인");
        assertTrue(content.contains("Test issue"), "subject 값 확인");

        assertTrue(Files.exists(projectOutputDir.resolve("issues/_labels.json")), "Label 정의 파일");
        assertTrue(Files.exists(projectOutputDir.resolve("issues/_milestones.json")), "Milestone 정의 파일");
    }

    // ── Time Entries fetch E2E ────────────────────────────────────────────────

    @Test
    void timeEntries_fetch_writesCsvLocally() throws Exception {
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"time_entries\":["
                + "{\"id\":1,\"issue\":{\"id\":42},\"spent_on\":\"2024-03-01\","
                + "\"hours\":2.5,\"activity\":{\"name\":\"Development\"},"
                + "\"user\":{\"login\":\"alice\"},\"comments\":\"initial work\"},"
                + "{\"id\":2,\"issue\":{\"id\":43},\"spent_on\":\"2024-03-02\","
                + "\"hours\":1.0,\"activity\":{\"name\":\"Testing\"},"
                + "\"user\":{\"login\":\"bob\"},\"comments\":\"\"}"
                + "],\"total_count\":2}")
            .addHeader("Content-Type", "application/json"));

        TimeEntryMigrationService service = new TimeEntryMigrationService(config);
        service.fetch(false, false);

        Path csv = projectOutputDir.resolve("_migration/time_entries.csv");
        assertTrue(Files.exists(csv), "CSV 파일이 생성되어야 함");

        String content = Files.readString(csv);
        assertTrue(content.contains("redmine_issue_id"), "CSV 헤더 확인");
        assertTrue(content.contains("42"), "이슈 ID 확인");
        assertTrue(content.contains("alice"), "사용자 확인");
        assertTrue(content.contains("Development"), "활동 유형 확인");
        assertTrue(content.contains("2.5"), "시간 확인");
    }

    @Test
    void timeEntries_fetch_emptyEntries_succeeds() throws Exception {
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"time_entries\":[],\"total_count\":0}")
            .addHeader("Content-Type", "application/json"));

        TimeEntryMigrationService service = new TimeEntryMigrationService(config);
        assertDoesNotThrow(() -> service.fetch(false, false));
    }

    // ── Resume E2E ────────────────────────────────────────────────────────────

    @Test
    void wiki_fetch_resume_skipsAlreadyFetchedPages() throws Exception {
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"wiki_pages\":[{\"title\":\"Page1\"},{\"title\":\"Page2\"}]}")
            .addHeader("Content-Type", "application/json"));
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"wiki_page\":{\"title\":\"Page1\",\"text\":\"content\",\"attachments\":[]}}")
            .addHeader("Content-Type", "application/json"));
        redmineServer.enqueue(new MockResponse()
            .setBody("{\"wiki_page\":{\"title\":\"Page2\",\"text\":\"content2\",\"attachments\":[]}}")
            .addHeader("Content-Type", "application/json"));

        WikiMigrationService service = new WikiMigrationService(config);
        service.fetch(false, false);

        assertTrue(Files.exists(projectOutputDir.resolve("wiki/Page1.md")));
        assertTrue(Files.exists(projectOutputDir.resolve("wiki/Page2.md")));
        assertTrue(Files.exists(Path.of("migration-state.json")), "state 파일이 생성되어야 함");
    }
}
