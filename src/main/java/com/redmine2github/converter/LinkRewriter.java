package com.redmine2github.converter;

import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Redmine 내부 위키 링크 {@code [[PageName]]} 을 GitHub 상대 경로 링크로 변환한다.
 *
 * <p>지원 형식:
 * <ul>
 *   <li>{@code [[PageName]]} — 현재 프로젝트 내 링크</li>
 *   <li>{@code [[PageName#section]]} — 앵커 포함</li>
 *   <li>{@code [[PageName|Label]]} — 커스텀 레이블</li>
 *   <li>{@code [[OtherProject:PageName]]} — 크로스 프로젝트 링크</li>
 *   <li>{@code [[OtherProject:PageName#section|Label]]} — 조합</li>
 * </ul>
 *
 * <p>크로스 프로젝트 링크의 경로 기준:
 * <pre>
 * GitHub 리포지터리 내 구조:
 *   {project}/wiki/{page}.md
 *
 * currentProject/wiki/{currentWikiDir}/ 에서
 * targetProject/wiki/{page}.md 까지의 상대 경로를 계산한다.
 * </pre>
 */
public class LinkRewriter {

    private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[([^|\\]]+)(?:\\|([^\\]]+))?\\]\\]");

    /**
     * @param markdown        변환할 Markdown 텍스트
     * @param titleToWikiPath 제목 → wiki 루트 기준 파일 경로 (예: "Root.md", "Root/Child.md")
     * @param currentProject  현재 파일의 프로젝트 슬러그 (크로스 프로젝트 상대 경로 계산용)
     * @param currentWikiDir  현재 파일의 wiki 루트 기준 디렉터리 (예: "", "Root", "Root/Child")
     */
    public String rewrite(String markdown, Map<String, String> titleToWikiPath,
                          String currentProject, String currentWikiDir) {
        return WIKI_LINK.matcher(markdown).replaceAll(match -> {
            String raw   = match.group(1).trim();
            String label = match.group(2) != null ? match.group(2).trim() : null;

            // 앵커(#section) 분리
            int hashIdx = raw.indexOf('#');
            String pageRef = hashIdx >= 0 ? raw.substring(0, hashIdx).trim() : raw;
            String anchor  = hashIdx >= 0 ? raw.substring(hashIdx) : "";

            // 크로스 프로젝트 여부 확인: [[OtherProject:PageName]]
            int colonIdx = pageRef.indexOf(':');
            if (colonIdx >= 0) {
                String targetProject = pageRef.substring(0, colonIdx).trim();
                String pageName      = pageRef.substring(colonIdx + 1).trim();
                String displayLabel  = label != null ? label : pageName;
                String relPath = crossProjectRelPath(currentProject, currentWikiDir,
                                                     targetProject, pageName) + anchor;
                return "[" + displayLabel + "](" + relPath + ")";
            }

            // 현재 프로젝트 내 링크
            String displayLabel = label != null ? label : pageRef;
            String targetPath = titleToWikiPath.get(pageRef);
            if (targetPath == null) {
                // 대상 페이지 정보 없음 — 제목 기반 추정 경로로 폴백
                targetPath = pageRef.replace(" ", "-") + ".md";
            }
            String relPath = computeRelativePath(currentWikiDir, targetPath) + anchor;
            return "[" + displayLabel + "](" + relPath + ")";
        });
    }

    /**
     * 현재 파일({@code currentProject}/wiki/{@code currentWikiDir}/)에서
     * 다른 프로젝트({@code targetProject}/wiki/{@code page}.md)까지의 상대 경로.
     *
     * <pre>
     * currentProject="proj1", currentWikiDir="",     targetProject="proj2", page="Page"
     *   → "../../proj2/wiki/Page.md"
     *
     * currentProject="proj1", currentWikiDir="Root", targetProject="proj2", page="Page"
     *   → "../../../proj2/wiki/Page.md"
     * </pre>
     */
    private String crossProjectRelPath(String currentProject, String currentWikiDir,
                                       String targetProject, String page) {
        String slug = page.replace(" ", "-");
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
     * wiki 루트 기준 디렉터리({@code fromDir})에서 wiki 루트 기준 파일({@code toPath})까지의
     * 상대 경로를 반환한다.
     *
     * <ul>
     *   <li>{@code fromDir=""}, {@code toPath="Other.md"} → {@code "Other.md"}</li>
     *   <li>{@code fromDir="Root"}, {@code toPath="Other.md"} → {@code "../Other.md"}</li>
     *   <li>{@code fromDir="Root"}, {@code toPath="Root/Child.md"} → {@code "Child.md"}</li>
     * </ul>
     */
    private String computeRelativePath(String fromDir, String toPath) {
        if (fromDir == null || fromDir.isEmpty()) {
            return toPath;
        }
        try {
            return Path.of(fromDir).relativize(Path.of(toPath))
                       .toString().replace('\\', '/');
        } catch (Exception e) {
            return toPath;
        }
    }
}
