package com.redmine2github.converter;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
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
        // 대소문자·공백 불일치 폴백용 정규화 맵 (첫 번째 항목 우선)
        Map<String, String> normalizedMap = buildNormalizedMap(titleToWikiPath);

        return WIKI_LINK.matcher(markdown).replaceAll(match -> {
            String raw   = match.group(1).trim();
            String label = match.group(2) != null ? match.group(2).trim() : null;

            // 앵커(#section) 분리
            int hashIdx = raw.indexOf('#');
            String pageRef = hashIdx >= 0 ? raw.substring(0, hashIdx).trim() : raw;
            String anchor  = hashIdx >= 0 ? raw.substring(hashIdx) : "";

            // 크로스 프로젝트 여부 확인: [[OtherProject:PageName]]
            // Redmine 프로젝트 식별자는 소문자·숫자·하이픈·언더스코어만 허용 ([a-z][a-z0-9_-]*)
            // 이 패턴에 맞지 않으면 콜론 포함 페이지 제목으로 처리 (예: [[API: 개요]])
            int colonIdx = pageRef.indexOf(':');
            if (colonIdx >= 0 && pageRef.substring(0, colonIdx).trim().matches("[a-z][a-z0-9_-]*")) {
                String targetProject = pageRef.substring(0, colonIdx).trim();
                String pageName      = pageRef.substring(colonIdx + 1).trim();
                String displayLabel  = label != null ? label : pageName;
                String relPath = crossProjectRelPath(currentProject, currentWikiDir,
                                                     targetProject, pageName) + anchor;
                return "[" + displayLabel + "](" + relPath + ")";
            }

            // 현재 프로젝트 내 링크
            String displayLabel = label != null ? label : pageRef;
            // 1차: 정확한 제목 일치
            String targetPath = titleToWikiPath.get(pageRef);
            // 2차: 대소문자·공백 정규화 후 일치
            if (targetPath == null) {
                targetPath = normalizedMap.get(normalizeKey(pageRef));
            }
            // 3차: 제목 기반 추정 경로로 폴백
            if (targetPath == null) {
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

    /**
     * 제목→경로 맵의 정규화 키(소문자·연속 공백 단일화·트림) → 경로 역방향 맵을 반환한다.
     * 동일 정규화 키를 가진 항목이 여럿이면 첫 번째 항목이 우선한다.
     */
    private static Map<String, String> buildNormalizedMap(Map<String, String> titleToWikiPath) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, String> e : titleToWikiPath.entrySet()) {
            result.putIfAbsent(normalizeKey(e.getKey()), e.getValue());
        }
        return result;
    }

    /** 소문자 변환 + 언더스코어→공백 + 연속 공백을 단일 공백으로 + 트림.
     *  Redmine 제목은 '_'로 저장되지만 [[링크]] 텍스트는 공백으로 쓰이므로 동일하게 처리한다. */
    private static String normalizeKey(String title) {
        return title.replace('_', ' ').toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
