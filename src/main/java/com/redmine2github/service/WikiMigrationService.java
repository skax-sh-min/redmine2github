package com.redmine2github.service;

import com.redmine2github.cli.MigrationReport;
import com.redmine2github.cli.ProgressReporter;
import com.redmine2github.config.AppConfig;
import com.redmine2github.state.FailureLog;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    /** h2 이상 heading 파싱용 — 목차 생성에 사용 */
    private static final Pattern HEADING_LINE =
            Pattern.compile("^(#{1,6})\\s+(.+)$");

    private final AppConfig config;
    private final MigrationReport report;
    private final FailureLog failureLog;
    private final TextileConverter converter            = new TextileConverter();
    private final LinkRewriter linkRewriter             = new LinkRewriter();
    private final AttachmentPathRewriter attachRewriter = new AttachmentPathRewriter();
    private final RedmineUrlRewriter redmineUrlRewriter;

    public WikiMigrationService(AppConfig config) {
        this(config, new MigrationReport(config.getProjectSlug()),
             new FailureLog(Path.of(config.getProjectOutputDir())));
    }

    public WikiMigrationService(AppConfig config, MigrationReport report) {
        this(config, report, new FailureLog(Path.of(config.getProjectOutputDir())));
    }

    public WikiMigrationService(AppConfig config, MigrationReport report, FailureLog failureLog) {
        this.config = config;
        this.report = report;
        this.failureLog = failureLog;
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
                report.addDataMissing("Wiki: 403 Forbidden — wiki 페이지가 마이그레이션에 포함되지 않습니다.");
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

        Set<String> nonRetryableWikiFetch = retryFailed
                ? failureLog.loadNonRetryableIds("wiki", "fetch")
                : Collections.emptySet();

        for (RedmineWikiPage page : nonEmpty) {
            if (!retryFailed && state.isWikiPageFetched(page.getTitle())) {
                progress.itemSkipped(page.getTitle());
                continue;
            }
            if (retryFailed && nonRetryableWikiFetch.contains(page.getTitle())) {
                progress.itemSkipped(page.getTitle());
                continue;
            }
            try {
                fetchPage(page, titleToWikiPath, redmine);
                state.markWikiPageFetched(page.getTitle());
                stateMgr.save();
                progress.itemDone(page.getTitle());
                for (RedmineAttachment att : page.getAttachments()) {
                    report.addAttachmentStats(1, att.getFilesize());
                }
            } catch (Exception e) {
                log.error("Wiki 페이지 수집 실패 [{}]: {}", page.getTitle(), e.getMessage(), e);
                progress.itemFailed(page.getTitle(), e.getMessage());
                report.addFailure("wiki", page.getTitle(), e.getMessage());
                failureLog.append("wiki", page.getTitle(), "fetch", e.getMessage());
            }
        }

        progress.finish();
        report.recordSection(progress.getSection(), progress.getTotal(),
                progress.getDone(), progress.getFailed(), progress.getSkipped());
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
                failureLog.append("attachment-download", att.getFilename(), "fetch", e.getMessage());
            }
        }

        // ② Markdown 변환 및 링크 재작성
        String markdown = converter.convert(page.getText());

        // 본문이 제목만 반복하거나 실질적으로 비어 있으면 파일 생성 생략
        if (isBodyEffectivelyEmpty(markdown)) {
            log.info("빈 페이지 건너뜀 (실질 본문 없음): {}", page.getTitle());
            return;
        }

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

        // ④ 첨부 파일 목록 — 본문에서 참조되지 않은 파일도 하단에 나열
        if (!page.getAttachments().isEmpty()) {
            markdown = appendAttachmentList(markdown, page.getAttachments(), attNameMapping, attachBasePath);
        }

        // ⑤ 제목 + 메타 정보 블록 (작성자·날짜·수정메모) 앞에 삽입
        markdown = prependPageMeta(page, markdown);

        // ⑥ 70줄 초과 페이지에 목차 자동 삽입
        if (markdown.lines().count() > 70) {
            markdown = insertTableOfContents(markdown);
        }

        Path localPath = Path.of(config.getProjectOutputDir(), "wiki", wikiPath);
        Files.createDirectories(localPath.getParent());
        Files.writeString(localPath, markdown);
        log.info("로컬 저장: {}", localPath);
    }

    /**
     * Wiki 페이지 MD 파일 상단에 제목과 메타 정보 블록을 삽입한다.
     *
     * <pre>
     * # Page Title
     *
     * &lt;small&gt;작성자: Alice &amp;nbsp;·&amp;nbsp; 작성일: 2023-09-07 &amp;nbsp;·&amp;nbsp; 수정일: 2025-01-08&lt;/small&gt;
     *
     * ---
     *
     * (본문)
     * </pre>
     */
    private static String prependPageMeta(RedmineWikiPage page, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(page.getTitle()).append("\n\n");

        List<String> parts = new ArrayList<>();
        if (page.getAuthorName() != null && !page.getAuthorName().isBlank())
            parts.add("작성자: " + page.getAuthorName());
        if (page.getCreatedOn() != null && !page.getCreatedOn().isBlank())
            parts.add("작성일: " + dateOnly(page.getCreatedOn()));
        if (page.getUpdatedOn() != null && !page.getUpdatedOn().isBlank())
            parts.add("수정일: " + dateOnly(page.getUpdatedOn()));

        if (page.getComments() != null && !page.getComments().isBlank())
            parts.add("수정 메모: " + page.getComments());

        if (!parts.isEmpty()) {
            sb.append("<small>")
              .append(String.join(" &nbsp;·&nbsp; ", parts))
              .append("</small>\n\n");
            sb.append("---\n\n");
        }

        sb.append(body);
        return sb.toString();
    }

    private static String appendAttachmentList(String markdown,
                                               List<RedmineAttachment> attachments,
                                               Map<String, String> attNameMapping,
                                               String attachBasePath) {
        List<String> lines = new ArrayList<>();
        for (RedmineAttachment att : attachments) {
            String storedName = attNameMapping.get(att.getFilename());
            if (storedName == null) continue; // 다운로드 실패 항목 제외
            lines.add("- [" + att.getFilename() + "](" + attachBasePath + storedName + ")");
        }
        if (lines.isEmpty()) return markdown;

        StringBuilder sb = new StringBuilder(markdown);
        if (!markdown.endsWith("\n")) sb.append('\n');
        sb.append("\n## 첨부 파일\n\n");
        for (String line : lines) sb.append(line).append('\n');
        return sb.toString();
    }

    private static String dateOnly(String iso) {
        int t = iso.indexOf('T');
        return t > 0 ? iso.substring(0, t) : iso;
    }

    // ── 빈 페이지 판별 ─────────────────────────────────────────────────────────

    /**
     * 변환된 마크다운 본문이 제목 행({@code # ...})과 공백 줄만으로 구성되어
     * 실질적인 내용이 없으면 {@code true}를 반환한다.
     */
    private static boolean isBodyEffectivelyEmpty(String bodyMd) {
        if (bodyMd == null || bodyMd.isBlank()) return true;
        return bodyMd.lines()
                     .map(String::strip)
                     .filter(l -> !l.isEmpty())
                     .filter(l -> !l.startsWith("#"))
                     .findAny()
                     .isEmpty();
    }

    // ── 목차 자동 생성 ──────────────────────────────────────────────────────────

    /**
     * 마크다운에서 h2 이상 헤딩을 추출하여 {@code ## 목차} 블록을 생성하고
     * 메타 구분선({@code ---}) 바로 뒤에 삽입한다.
     *
     * <ul>
     *   <li>코드 블록({@code ```}) 내부의 헤딩은 무시한다.</li>
     *   <li>헤딩이 2개 미만이면 삽입하지 않는다.</li>
     * </ul>
     */
    private static String insertTableOfContents(String markdown) {
        String[] lines = markdown.split("\n", -1);

        List<Integer> levels = new ArrayList<>();
        List<String>  texts  = new ArrayList<>();
        boolean inCode       = false;
        int dividerLine      = -1;  // --- 메타 구분선 위치

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("```")) inCode = !inCode;
            if (inCode) continue;
            // 앞 10줄 이내의 --- 를 메타 구분선으로 인식
            if (i < 10 && line.equals("---")) dividerLine = i;

            Matcher m = HEADING_LINE.matcher(line);
            if (m.matches()) {
                int level = m.group(1).length();
                if (level >= 2) {   // h1(페이지 제목)은 목차에서 제외
                    levels.add(level);
                    texts.add(m.group(2).strip());
                }
            }
        }

        if (texts.size() < 2) return markdown;  // 헤딩 2개 미만이면 목차 불필요

        // TOC 블록 생성
        StringBuilder toc = new StringBuilder("## 목차\n\n");
        for (int i = 0; i < texts.size(); i++) {
            int    level  = levels.get(i);
            String text   = texts.get(i);
            String anchor = headingToAnchor(text);
            toc.append("  ".repeat(Math.max(0, level - 2)))
               .append("- [").append(text).append("](#").append(anchor).append(")\n");
        }
        toc.append("\n");

        // 삽입 위치: --- 다음 줄, 없으면 첫 번째 빈 줄 이후
        int insertAfterIdx = dividerLine >= 0 ? dividerLine : 1;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= insertAfterIdx; i++) {
            sb.append(lines[i]).append("\n");
        }
        sb.append("\n").append(toc);
        for (int i = insertAfterIdx + 1; i < lines.length; i++) {
            sb.append(lines[i]);
            if (i < lines.length - 1) sb.append("\n");
        }
        if (markdown.endsWith("\n") && !sb.toString().endsWith("\n")) sb.append("\n");
        return sb.toString();
    }

    /**
     * GitHub 앵커 생성 규칙:
     * 소문자 변환 → 특수문자 제거(유니코드 글자·숫자·공백·하이픈 유지) → 공백을 하이픈으로.
     */
    private static String headingToAnchor(String text) {
        return text.toLowerCase(java.util.Locale.ROOT)
                   .replaceAll("[^\\p{L}\\p{N}\\s\\-]", "")
                   .trim()
                   .replaceAll("\\s+", "-");
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
        try (Stream<Path> stream = Files.walk(wikiDir)) {
            mdFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md"))
                    .toList();
        } catch (IOException e) {
            log.error("Wiki 디렉터리 읽기 실패: {}", e.getMessage(), e);
            return;
        }

        progress.start(mdFiles.size());
        String projectSlug = config.getProjectSlug();

        Set<String> nonRetryableWikiUpload = retryFailed
                ? failureLog.loadNonRetryableIds("wiki-upload", "upload")
                : Collections.emptySet();

        // ── Wiki .md 파일 업로드 ────────────────────────────────────────────
        if (fileUploader.isJGitMode()) {
            uploadWikiMdJGit(mdFiles, wikiDir, fileUploader, state, stateMgr, progress,
                             projectSlug, retryFailed, nonRetryableWikiUpload);
        } else {
            int processedSinceRateCheck = 0;
            for (Path mdFile : mdFiles) {
                String repoPath = projectSlug + "/wiki/" + wikiDir.relativize(mdFile).toString().replace('\\', '/');
                if (!retryFailed && state.isWikiPageDone(repoPath)) {
                    progress.itemSkipped(repoPath);
                    continue;
                }
                if (retryFailed && nonRetryableWikiUpload.contains(repoPath)) {
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
                    report.addFailure("wiki-upload", repoPath, e.getMessage());
                    failureLog.append("wiki-upload", repoPath, "upload", e.getMessage());
                }
                if (++processedSinceRateCheck >= 10) {
                    progress.reportRateLimit(ghUploader.getRateLimitRemaining());
                    processedSinceRateCheck = 0;
                }
            }
        }

        // ── 첨부파일 업로드 ─────────────────────────────────────────────────
        Set<String> nonRetryableAttach = retryFailed
                ? failureLog.loadNonRetryableIds("attachment-upload", "upload")
                : Collections.emptySet();

        Path attachDir    = Path.of(config.getProjectOutputDir(), "attachments");
        Path attachExtDir = Path.of(config.getProjectOutputDir(), "attachments-ext");

        if (fileUploader.isJGitMode()) {
            uploadAttachmentsJGit(attachDir, attachExtDir, fileUploader, state, stateMgr,
                                   projectSlug, retryFailed, nonRetryableAttach);
        } else {
            if (Files.exists(attachDir)) {
                List<Path> files;
                try (Stream<Path> stream = Files.walk(attachDir)) {
                    files = stream.filter(Files::isRegularFile).toList();
                } catch (IOException e) {
                    log.error("첨부파일 디렉터리 읽기 실패: {}", e.getMessage(), e);
                    files = Collections.emptyList();
                }
                for (Path f : files) {
                    String rp = projectSlug + "/attachments/"
                            + attachDir.relativize(f).toString().replace('\\', '/');
                    if (!retryFailed && state.isAttachmentDone(rp)) { log.debug("첨부파일 스킵 (완료): {}", rp); continue; }
                    if (retryFailed && nonRetryableAttach.contains(rp)) continue;
                    try {
                        fileUploader.uploadFile(f, rp, "migrate: " + rp);
                        state.markAttachmentDone(rp);
                        stateMgr.save();
                        log.info("첨부파일 업로드: {}", rp);
                    } catch (Exception e) {
                        log.warn("첨부파일 업로드 실패 [{}]: {}", rp, e.getMessage());
                        failureLog.append("attachment-upload", rp, "upload", e.getMessage());
                    }
                }
            }
            if (Files.exists(attachExtDir)) {
                List<Path> files;
                try (Stream<Path> stream = Files.walk(attachExtDir)) {
                    files = stream.filter(Files::isRegularFile).toList();
                } catch (IOException e) {
                    log.error("외부 첨부파일 디렉터리 읽기 실패: {}", e.getMessage(), e);
                    files = Collections.emptyList();
                }
                for (Path f : files) {
                    String rp = projectSlug + "/attachments-ext/"
                            + attachExtDir.relativize(f).toString().replace('\\', '/');
                    if (!retryFailed && state.isAttachmentDone(rp)) { log.debug("외부 첨부파일 스킵 (완료): {}", rp); continue; }
                    if (retryFailed && nonRetryableAttach.contains(rp)) continue;
                    try {
                        fileUploader.uploadFile(f, rp, "migrate: " + rp);
                        state.markAttachmentDone(rp);
                        stateMgr.save();
                        log.info("외부 첨부파일 업로드: {}", rp);
                    } catch (Exception e) {
                        log.warn("외부 첨부파일 업로드 실패 [{}]: {}", rp, e.getMessage());
                        failureLog.append("attachment-upload", rp, "upload", e.getMessage());
                    }
                }
            }
        }

        progress.reportRateLimit(ghUploader.getRateLimitRemaining());
        progress.finish();
        report.recordSection(progress.getSection(), progress.getTotal(),
                progress.getDone(), progress.getFailed(), progress.getSkipped());
    }

    // ── JGit 배치 헬퍼 ────────────────────────────────────────────────────────

    private void uploadWikiMdJGit(List<Path> mdFiles, Path wikiDir,
                                   GitHubFileUploader fileUploader,
                                   MigrationState state, MigrationStateManager stateMgr,
                                   ProgressReporter progress, String projectSlug,
                                   boolean retryFailed, Set<String> nonRetryable) {
        try (var session = fileUploader.beginJGitSession()) {
            for (Path mdFile : mdFiles) {
                String repoPath = projectSlug + "/wiki/"
                        + wikiDir.relativize(mdFile).toString().replace('\\', '/');
                if (!retryFailed && state.isWikiPageDone(repoPath)) {
                    progress.itemSkipped(repoPath);
                    continue;
                }
                if (retryFailed && nonRetryable.contains(repoPath)) {
                    progress.itemSkipped(repoPath);
                    continue;
                }
                try {
                    session.stage(mdFile, repoPath);
                } catch (Exception e) {
                    log.error("Wiki 스테이징 실패 [{}]: {}", repoPath, e.getMessage(), e);
                    progress.itemFailed(repoPath, e.getMessage());
                    report.addFailure("wiki-upload", repoPath, e.getMessage());
                    failureLog.append("wiki-upload", repoPath, "upload", e.getMessage());
                }
            }
            if (session.hasStaged()) {
                session.commit("migrate: wiki pages (" + session.getStagedCount() + " files)");
                session.push();
                for (String rp : session.getStagedPaths()) {
                    state.markWikiPageDone(rp);
                    progress.itemDone(rp);
                }
                stateMgr.save();
            }
        } catch (Exception e) {
            log.error("Wiki JGit 배치 업로드 실패: {}", e.getMessage(), e);
        }
    }

    private void uploadAttachmentsJGit(Path attachDir, Path attachExtDir,
                                        GitHubFileUploader fileUploader,
                                        MigrationState state, MigrationStateManager stateMgr,
                                        String projectSlug,
                                        boolean retryFailed, Set<String> nonRetryable) {
        try (var session = fileUploader.beginJGitSession()) {
            stageAttachDir(session, attachDir,    projectSlug + "/attachments/",     state, retryFailed, nonRetryable);
            stageAttachDir(session, attachExtDir, projectSlug + "/attachments-ext/", state, retryFailed, nonRetryable);
            if (session.hasStaged()) {
                session.commit("migrate: attachments (" + session.getStagedCount() + " files)");
                session.push();
                for (String rp : session.getStagedPaths()) {
                    state.markAttachmentDone(rp);
                }
                stateMgr.save();
            }
        } catch (Exception e) {
            log.error("Attachment JGit 배치 업로드 실패: {}", e.getMessage(), e);
        }
    }

    private void stageAttachDir(GitHubFileUploader.JGitSession session, Path dir,
                                 String repoPrefix, MigrationState state,
                                 boolean retryFailed, Set<String> nonRetryable) {
        if (!Files.exists(dir)) return;
        List<Path> files;
        try (Stream<Path> stream = Files.walk(dir)) {
            files = stream.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            log.error("첨부파일 디렉터리 읽기 실패 [{}]: {}", dir, e.getMessage(), e);
            return;
        }
        for (Path f : files) {
            String rp = repoPrefix + dir.relativize(f).toString().replace('\\', '/');
            if (!retryFailed && state.isAttachmentDone(rp)) { log.debug("첨부파일 스킵 (완료): {}", rp); continue; }
            if (retryFailed && nonRetryable.contains(rp)) continue;
            try {
                session.stage(f, rp);
            } catch (Exception e) {
                log.warn("첨부파일 스테이징 실패 [{}]: {}", rp, e.getMessage());
                failureLog.append("attachment-upload", rp, "upload", e.getMessage());
            }
        }
    }

    // ── 전체 파이프라인 ────────────────────────────────────────────────────────

    public void run(boolean resume, boolean retryFailed) {
        fetch(resume, retryFailed);
        upload(resume, retryFailed);
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    /** 페이지 제목을 파일시스템 안전 슬러그로 변환한다. */
    private static String slugify(String title) {
        // Windows 파일시스템 불허 문자(\ : * ? " < > |)를 제거한 뒤 공백을 하이픈으로 치환
        return title.replaceAll("[\\\\:*?\"<>|]", "")
                    .replace(" ", "-");
    }
}
