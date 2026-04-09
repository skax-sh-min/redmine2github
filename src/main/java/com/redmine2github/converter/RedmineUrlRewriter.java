package com.redmine2github.converter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 마크다운 본문 내 Redmine 절대 URL과 SVN IP URL을 상대 경로 또는 정식 URL로 변환한다.
 *
 * <ol>
 *   <li>Wiki URL ({@code {baseUrl}/projects/{proj}/wiki/{page}})
 *       → 현재 파일 위치 기준 상대 경로 {@code .md} 링크</li>
 *   <li>첨부파일 URL ({@code {baseUrl}/attachments/{id}/{file}})
 *       → 로컬 {@code attachments-ext/} 에 다운로드 후 상대 경로 링크</li>
 * </ol>
 *
 * <h3>GitHub 리포지터리 내 경로 기준</h3>
 * <pre>
 * {project}/
 *   wiki/
 *     Root.md                     ← wikiDepth 0
 *     Root/Child.md               ← wikiDepth 1
 *   attachments/
 *   attachments-ext/              ← 이 클래스가 생성
 * </pre>
 */
public class RedmineUrlRewriter {

    private static final Logger log = LoggerFactory.getLogger(RedmineUrlRewriter.class);

    /** url-rewrites.yml 에서 로드한 URL 치환 규칙. 각 원소는 {old, new} 쌍. */
    private final List<String[]> urlRewrites;

    /**
     * [label](wikiUrl) — 그룹: 1=label, 2=fullUrl, 3=project, 4=page
     */
    private final Pattern mdWikiLink;
    /**
     * bare wikiUrl — 그룹: 1=fullUrl, 2=project, 3=page
     */
    private final Pattern bareWikiUrl;
    /**
     * [label](attachUrl) — 그룹: 1=label, 2=fullUrl, 3=filename
     */
    private final Pattern mdAttLink;
    /**
     * bare attachUrl — 그룹: 1=fullUrl, 2=filename
     */
    private final Pattern bareAttUrl;

    public RedmineUrlRewriter(String redmineBaseUrl, List<String[]> urlRewrites) {
        this.urlRewrites = urlRewrites != null ? urlRewrites : Collections.emptyList();
        String base = Pattern.quote(redmineBaseUrl);

        // 외부 URL 특수문자 제외: space ) " < >
        String urlChars = "[^\\s)\"<>]";

        // wiki URL 전체 캡처: (1)=fullUrl, (2)=project, (3)=page
        String fullWikiUrl = "(" + base + "/projects/(" + urlChars + "+)/wiki/(" + urlChars + "+))";
        // 첨부파일 URL 전체 캡처: (1)=fullUrl, (2)=filename (id는 스킵)
        String fullAttUrl  = "(" + base + "/attachments/\\d+/(" + urlChars + "+))";

        this.mdWikiLink  = Pattern.compile("\\[([^\\]]*)]\\(" + fullWikiUrl + "\\)");
        this.bareWikiUrl = Pattern.compile(fullWikiUrl);
        this.mdAttLink   = Pattern.compile("\\[([^\\]]*)]\\(" + fullAttUrl  + "\\)");
        this.bareAttUrl  = Pattern.compile(fullAttUrl);
    }

    /**
     * @param markdown        변환할 마크다운 텍스트
     * @param currentProject  현재 파일의 프로젝트 슬러그 (e.g. "nexcore_home")
     * @param currentWikiDir  현재 파일의 wiki 루트 기준 디렉터리 (e.g. "", "Root", "Root/Child")
     * @param attachExtDir    외부 첨부파일을 저장할 로컬 디렉터리 (null 이면 다운로드 스킵)
     * @param downloader      {@code (url, destFile) -> void} 콜백; null 이면 스킵
     * @return 변환된 마크다운
     */
    public String rewrite(String markdown,
                          String currentProject,
                          String currentWikiDir,
                          Path attachExtDir,
                          BiConsumer<String, Path> downloader) {
        if (markdown == null || markdown.isBlank()) return markdown == null ? "" : markdown;

        // 1. URL 치환 규칙 적용 (url-rewrites.yml)
        for (String[] rule : urlRewrites) {
            markdown = markdown.replace(rule[0], rule[1]);
        }

        // 2. Wiki URL 처리
        // Pass A: [label](wikiUrl) — 기존 label 유지
        markdown = replaceWikiMd(markdown, currentProject, currentWikiDir);
        // Pass B: 남은 bare URL → [page](relative) 로 변환
        markdown = replaceWikiBare(markdown, currentProject, currentWikiDir);

        // 3. 첨부파일 URL 처리
        // Pass A: [label](attachUrl) — 기존 label 유지
        markdown = replaceAttMd(markdown, currentWikiDir, attachExtDir, downloader);
        // Pass B: 남은 bare URL → [filename](relative) 로 변환
        markdown = replaceAttBare(markdown, currentWikiDir, attachExtDir, downloader);

        return markdown;
    }

    // ── Wiki ──────────────────────────────────────────────────────────────────

    private String replaceWikiMd(String markdown, String currentProject, String currentWikiDir) {
        Matcher m = mdWikiLink.matcher(markdown);
        if (!m.find()) return markdown;
        m.reset();
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String label   = m.group(1);
            String project = m.group(3);
            String page    = decode(m.group(4));
            String rel     = wikiRelPath(currentProject, currentWikiDir, project, page);
            m.appendReplacement(sb, Matcher.quoteReplacement("[" + label + "](" + rel + ")"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String replaceWikiBare(String markdown, String currentProject, String currentWikiDir) {
        Matcher m = bareWikiUrl.matcher(markdown);
        if (!m.find()) return markdown;
        m.reset();
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String project = m.group(2);
            String page    = decode(m.group(3));
            String rel     = wikiRelPath(currentProject, currentWikiDir, project, page);
            m.appendReplacement(sb, Matcher.quoteReplacement("[" + page + "](" + rel + ")"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ── Attachment ────────────────────────────────────────────────────────────

    private String replaceAttMd(String markdown, String currentWikiDir,
                                 Path attachExtDir, BiConsumer<String, Path> downloader) {
        Matcher m = mdAttLink.matcher(markdown);
        if (!m.find()) return markdown;
        m.reset();
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String label    = m.group(1);
            String fullUrl  = m.group(2);
            String filename = decode(m.group(3));
            doDownload(downloader, fullUrl, filename, attachExtDir);
            String rel = attachExtRelPath(currentWikiDir, filename);
            m.appendReplacement(sb, Matcher.quoteReplacement("[" + label + "](" + rel + ")"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String replaceAttBare(String markdown, String currentWikiDir,
                                   Path attachExtDir, BiConsumer<String, Path> downloader) {
        Matcher m = bareAttUrl.matcher(markdown);
        if (!m.find()) return markdown;
        m.reset();
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String fullUrl  = m.group(1);
            String filename = decode(m.group(2));
            doDownload(downloader, fullUrl, filename, attachExtDir);
            String rel = attachExtRelPath(currentWikiDir, filename);
            m.appendReplacement(sb, Matcher.quoteReplacement("[" + filename + "](" + rel + ")"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ── 경로 계산 ─────────────────────────────────────────────────────────────

    /**
     * 현재 파일({@code currentProject}/wiki/{currentWikiDir}/)에서
     * 대상 wiki 파일({@code targetProject}/wiki/{slugifiedPage}.md)까지의 상대 경로.
     *
     * <pre>
     * 예) currentProject="nexcore_home", currentWikiDir="",   targetProject="nexcore_fwk_rnd", page="위키사용방법"
     *     → "../../nexcore_fwk_rnd/wiki/위키사용방법.md"
     *
     *     currentProject="nexcore_home", currentWikiDir="Root", targetProject="nexcore_fwk_rnd", page="Page"
     *     → "../../../nexcore_fwk_rnd/wiki/Page.md"
     * </pre>
     */
    private String wikiRelPath(String currentProject, String currentWikiDir,
                                String targetProject, String page) {
        String slug = slugify(page);
        Path from = currentWikiDir.isEmpty()
                ? Path.of(currentProject, "wiki")
                : Path.of(currentProject, "wiki", currentWikiDir);
        Path to = Path.of(targetProject, "wiki", slug + ".md");
        try {
            return from.relativize(to).toString().replace('\\', '/');
        } catch (Exception e) {
            return targetProject + "/wiki/" + slug + ".md";
        }
    }

    /**
     * wiki 디렉터리 깊이를 기준으로 {@code attachments-ext/} 상대 경로를 반환한다.
     *
     * <pre>
     * currentWikiDir=""         → "../attachments-ext/filename"
     * currentWikiDir="Root"     → "../../attachments-ext/filename"
     * currentWikiDir="Root/Sub" → "../../../attachments-ext/filename"
     * </pre>
     */
    private String attachExtRelPath(String currentWikiDir, String filename) {
        int depth = currentWikiDir.isEmpty()
                ? 0
                : (int) currentWikiDir.chars().filter(c -> c == '/').count() + 1;
        return "../".repeat(depth + 1) + "attachments-ext/" + filename;
    }

    // ── 유틸 ──────────────────────────────────────────────────────────────────

    private void doDownload(BiConsumer<String, Path> downloader, String url,
                             String filename, Path attachExtDir) {
        if (downloader == null || attachExtDir == null) return;
        try {
            downloader.accept(url, attachExtDir.resolve(filename));
        } catch (Exception e) {
            log.warn("외부 첨부파일 다운로드 실패 [{}]: {}", filename, e.getMessage());
        }
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private static String slugify(String title) {
        return title.replace(" ", "-");
    }
}
