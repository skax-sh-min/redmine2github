package com.redmine2github.service;

import com.redmine2github.cli.ProgressReporter;
import com.redmine2github.config.AppConfig;
import com.redmine2github.converter.AttachmentPathRewriter;
import com.redmine2github.converter.LinkRewriter;
import com.redmine2github.converter.RedmineUrlRewriter;
import com.redmine2github.converter.TextileConverter;
import com.redmine2github.github.GitHubFileUploader;
import com.redmine2github.github.GitHubUploader;
import com.redmine2github.redmine.RedmineClient;
import com.redmine2github.redmine.model.RedmineAttachment;
import com.redmine2github.redmine.model.RedmineWikiPage;
import com.redmine2github.state.MigrationState;
import com.redmine2github.state.MigrationStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Wiki 마이그레이션 서비스 — 2단계 구조.
 *
 * <h3>출력 디렉터리 구조</h3>
 * <pre>
 * output/{project}/
 *   wiki/
 *     Root.md
 *     Root/
 *       Child.md
 *       Child/
 *         GrandChild.md
 *   attachments/
 *     image.png
 * </pre>
 *
 * <ul>
 *   <li>{@link #fetch}: Phase 1 — Redmine Wiki 수집 → 로컬 저장</li>
 *   <li>{@link #upload}: Phase 2 — 로컬 파일 → GitHub Repository push</li>
 *   <li>{@link #run}: fetch + upload 전체 파이프라인</li>
 * </ul>
 */
public class WikiMigrationService {

    private static final Logger log = LoggerFactory.getLogger(WikiMigrationService.class);

    private final AppConfig config;
    private final TextileConverter converter            = new TextileConverter();
    private final LinkRewriter linkRewriter             = new LinkRewriter();
    private final AttachmentPathRewriter attachRewriter = new AttachmentPathRewriter();
    private final RedmineUrlRewriter redmineUrlRewriter;

    public WikiMigrationService(AppConfig config) {
        this.config = config;
        this.redmineUrlRewriter = new RedmineUrlRewriter(config.getRedmineUrl(), config.getUrlRewrites());
    }

    // ── Phase 1: Redmine → 로컬 ───────────────────────────────────────────────

    public void fetch(boolean resume, boolean retryFailed) {
        ProgressReporter progress = new ProgressReporter("Wiki[fetch]");
        MigrationStateManager stateMgr = new MigrationStateManager(resume, config.getProjectCacheDir());
        MigrationState state = stateMgr.getState();

        RedmineClient redmine = new RedmineClient(config);
        List<RedmineWikiPage> pages;
        try {
            pages = redmine.fetchAllWikiPages();
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("[404]")) {
                log.info("[Wiki] 프로젝트 '{}' 에 Wiki가 없습니다 — 건너뜁니다.", config.getProjectSlug());
                System.out.println("  [Wiki] Wiki 없음 — 건너뜁니다.");
                return;
            }
            if (msg != null && msg.contains("[403]")) {
                log.warn("[Wiki] 프로젝트 '{}' Wiki 접근 권한이 없습니다 — 건너뜁니다.", config.getProjectSlug());
                System.out.println("  [Wiki] Wiki 접근 권한 없음 — 건너뜁니다.");
                return;
            }
            throw e;
        }

        // 제목 → 페이지 맵 (ancestor 체인 조회용)
        Map<String, RedmineWikiPage> pageMap = pages.stream()
                .collect(Collectors.toMap(RedmineWikiPage::getTitle, p -> p, (a, b) -> a));

        // 제목 → wiki 루트 기준 파일 경로 맵 (링크 재작성용)
        Map<String, String> titleToWikiPath = new LinkedHashMap<>();
        for (RedmineWikiPage page : pages) {
            titleToWikiPath.put(page.getTitle(), computeWikiPath(page, pageMap));
        }

        // 빈 페이지(제목만 있고 본문 없음)를 제외한 실제 처리 대상
        List<RedmineWikiPage> nonEmpty = pages.stream()
                .filter(p -> p.getText() != null && !p.getText().isBlank())
                .collect(Collectors.toList());

        int skippedEmpty = pages.size() - nonEmpty.size();
        if (skippedEmpty > 0) {
            log.info("빈 페이지 {} 건 건너뜀 (본문 없음)", skippedEmpty);
        }

        progress.start(nonEmpty.size());

        for (RedmineWikiPage page : nonEmpty) {
            if (!retryFailed && state.isWikiPageFetched(page.getTitle())) {
                progress.itemSkipped(page.getTitle());
                continue;
            }
            try {
                fetchPage(page, titleToWikiPath, redmine);
                state.markWikiPageFetched(page.getTitle());
                stateMgr.save();
                progress.itemDone(page.getTitle());
            } catch (Exception e) {
                log.error("Wiki 페이지 수집 실패 [{}]: {}", page.getTitle(), e.getMessage(), e);
                progress.itemFailed(page.getTitle(), e.getMessage());
            }
        }

        progress.finish();
    }

    /**
     * 페이지의 ancestor 체인을 따라 wiki 루트 기준 파일 경로를 계산한다.
     *
     * <ul>
     *   <li>최상위: {@code "Root.md"}</li>
     *   <li>1단계 하위: {@code "Root/Child.md"}</li>
     *   <li>2단계 하위: {@code "Root/Child/GrandChild.md"}</li>
     * </ul>
     */
    private String computeWikiPath(RedmineWikiPage page, Map<String, RedmineWikiPage> pageMap) {
        LinkedList<String> parts = new LinkedList<>();
        parts.addFirst(slugify(page.getTitle()));

        Set<String> visited = new HashSet<>();
        visited.add(page.getTitle());

        String parentTitle = page.getParentTitle();
        while (parentTitle != null && !visited.contains(parentTitle)) {
            visited.add(parentTitle);
            parts.addFirst(slugify(parentTitle));
            RedmineWikiPage parent = pageMap.get(parentTitle);
            parentTitle = parent != null ? parent.getParentTitle() : null;
        }

        // 마지막 요소가 파일명, 앞 요소들이 디렉터리
        String filename = parts.removeLast() + ".md";
        if (parts.isEmpty()) return filename;
        return String.join("/", parts) + "/" + filename;
    }

    private void fetchPage(RedmineWikiPage page,
                           Map<String, String> titleToWikiPath,
                           RedmineClient redmine) throws IOException {
        // ① 첨부파일 먼저 다운로드 — 실제 저장 파일명을 확정한다
        Path attachDir = Path.of(config.getProjectOutputDir(), "attachments");
        // 원본 파일명 → 실제 저장 파일명 매핑 (이름 충돌 시 id 접두사 붙음)
        Map<String, String> attNameMapping = new LinkedHashMap<>();
        for (RedmineAttachment att : page.getAttachments()) {
            try {
                Path stored = redmine.downloadAttachment(att, attachDir);
                attNameMapping.put(att.getFilename(), stored.getFileName().toString());
            } catch (IOException e) {
                log.warn("첨부파일 다운로드 실패 [name={}, url={}]: {}",
                        att.getFilename(), att.getContentUrl(), e.getMessage(), e);
                attNameMapping.put(att.getFilename(), att.getFilename()); // 실패 시 원본명 유지
            }
        }

        // ② Markdown 변환 및 링크 재작성
        String markdown = converter.convert(page.getText());

        String wikiPath = titleToWikiPath.get(page.getTitle());

        // 현재 파일의 wiki 루트 기준 디렉터리 (링크 상대 경로 계산용)
        int lastSlash = wikiPath.lastIndexOf('/');
        String currentWikiDir = lastSlash >= 0 ? wikiPath.substring(0, lastSlash) : "";

        markdown = linkRewriter.rewrite(markdown, titleToWikiPath, config.getProjectSlug(), currentWikiDir);

        // 첨부파일 경로: wiki 파일에서 ../attachments/ 로 이동할 깊이 계산
        long depth = wikiPath.chars().filter(c -> c == '/').count();
        String attachBasePath = "../".repeat((int)(depth + 1)) + "attachments/";

        // 원본명 → 저장명 매핑으로 링크 재작성 (충돌 파일도 올바른 저장명으로 연결)
        markdown = attachRewriter.rewrite(markdown, attNameMapping, attachBasePath);

        // ③ 외부 Redmine URL / URL 치환 규칙 적용 + attachments-ext 다운로드
        Path attachExtDir = Path.of(config.getProjectOutputDir(), "attachments-ext");
        BiConsumer<String, Path> extDownloader = (url, destFile) -> {
            try {
                redmine.downloadToFile(url, destFile);
            } catch (IOException e) {
                log.warn("외부 첨부파일 다운로드 실패 [url={}, dest={}]: {}",
                        url, destFile, e.getMessage(), e);
            }
        };
        markdown = redmineUrlRewriter.rewrite(
                markdown, config.getProjectSlug(), currentWikiDir, attachExtDir, extDownloader);

        Path localPath = Path.of(config.getProjectOutputDir(), "wiki", wikiPath);
        Files.createDirectories(localPath.getParent());
        Files.writeString(localPath, markdown);
        log.info("로컬 저장: {}", localPath);
    }

    // ── Phase 2: 로컬 → GitHub ────────────────────────────────────────────────

    public void upload(boolean resume, boolean retryFailed) {
        Path wikiDir = Path.of(config.getProjectOutputDir(), "wiki");
        if (!Files.exists(wikiDir)) {
            log.warn("Wiki 출력 디렉터리가 없습니다. 먼저 fetch를 실행하세요: {}", wikiDir);
            System.out.println("  [Wiki] 출력 디렉터리 없음 — fetch를 먼저 실행하세요.");
            return;
        }

        ProgressReporter progress = new ProgressReporter("Wiki[upload]");
        MigrationStateManager stateMgr = new MigrationStateManager(resume, config.getProjectCacheDir());
        MigrationState state = stateMgr.getState();

        GitHubUploader ghUploader = new GitHubUploader(config);
        GitHubFileUploader fileUploader = new GitHubFileUploader(config, ghUploader);

        List<Path> mdFiles;
        try {
            mdFiles = Files.walk(wikiDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md"))
                    .toList();
        } catch (IOException e) {
            log.error("Wiki 디렉터리 읽기 실패: {}", e.getMessage(), e);
            return;
        }

        progress.start(mdFiles.size());
        int processedSinceRateCheck = 0;
        String projectSlug = config.getProjectSlug();

        for (Path mdFile : mdFiles) {
            String repoPath = projectSlug + "/wiki/" + wikiDir.relativize(mdFile).toString().replace('\\', '/');
            if (!retryFailed && state.isWikiPageDone(repoPath)) {
                progress.itemSkipped(repoPath);
                continue;
            }
            try {
                fileUploader.uploadFile(mdFile, repoPath, "migrate: " + repoPath);
                state.markWikiPageDone(repoPath);
                stateMgr.save();
                progress.itemDone(repoPath);
            } catch (Exception e) {
                log.error("Wiki 업로드 실패 [{}]: {}", repoPath, e.getMessage(), e);
                progress.itemFailed(repoPath, e.getMessage());
            }

            if (++processedSinceRateCheck >= 10) {
                progress.reportRateLimit(ghUploader.getRateLimitRemaining());
                processedSinceRateCheck = 0;
            }
        }

        // 첨부파일 업로드
        Path attachDir = Path.of(config.getProjectOutputDir(), "attachments");
        if (Files.exists(attachDir)) {
            try {
                Files.walk(attachDir)
                        .filter(Files::isRegularFile)
                        .forEach(f -> {
                            String rp = projectSlug + "/attachments/" + attachDir.relativize(f).toString().replace('\\', '/');
                            try {
                                fileUploader.uploadFile(f, rp, "migrate: " + rp);
                                log.info("첨부파일 업로드: {}", rp);
                            } catch (Exception e) {
                                log.warn("첨부파일 업로드 실패 [{}]: {}", rp, e.getMessage());
                            }
                        });
            } catch (IOException e) {
                log.error("첨부파일 디렉터리 읽기 실패: {}", e.getMessage(), e);
            }
        }

        // 외부 첨부파일(attachments-ext) 업로드
        Path attachExtDir = Path.of(config.getProjectOutputDir(), "attachments-ext");
        if (Files.exists(attachExtDir)) {
            try {
                Files.walk(attachExtDir)
                        .filter(Files::isRegularFile)
                        .forEach(f -> {
                            String rp = projectSlug + "/attachments-ext/" + attachExtDir.relativize(f).toString().replace('\\', '/');
                            try {
                                fileUploader.uploadFile(f, rp, "migrate: " + rp);
                                log.info("외부 첨부파일 업로드: {}", rp);
                            } catch (Exception e) {
                                log.warn("외부 첨부파일 업로드 실패 [{}]: {}", rp, e.getMessage());
                            }
                        });
            } catch (IOException e) {
                log.error("외부 첨부파일 디렉터리 읽기 실패: {}", e.getMessage(), e);
            }
        }

        progress.reportRateLimit(ghUploader.getRateLimitRemaining());
        progress.finish();
    }

    // ── 전체 파이프라인 ────────────────────────────────────────────────────────

    public void run(boolean resume, boolean retryFailed) {
        fetch(resume, retryFailed);
        upload(resume, retryFailed);
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    /** 페이지 제목을 파일시스템 안전 슬러그로 변환한다. */
    private static String slugify(String title) {
        return title.replace(" ", "-");
    }
}
