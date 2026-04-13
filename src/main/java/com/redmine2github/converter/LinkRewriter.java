package com.redmine2github.converter;

import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Redmine 내부 위키 링크 {@code [[PageName]]} 을 GitHub 상대 경로 링크로 변환한다.
 *
 * <p>페이지 제목 → wiki 루트 기준 상대 경로 맵({@code titleToWikiPath})을 사용하여
 * 현재 파일 위치로부터의 정확한 상대 경로를 계산한다.
 */
public class LinkRewriter {

    private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[([^|\\]]+)(?:\\|([^\\]]+))?\\]\\]");

    /**
     * @param markdown       변환할 Markdown 텍스트
     * @param titleToWikiPath 제목 → wiki 루트 기준 파일 경로 (예: "Root.md", "Root/Child.md")
     * @param currentWikiDir  현재 파일의 wiki 루트 기준 디렉터리 (예: "", "Root", "Root/Child")
     */
    public String rewrite(String markdown, Map<String, String> titleToWikiPath, String currentWikiDir) {
        return WIKI_LINK.matcher(markdown).replaceAll(match -> {
            String raw = match.group(1).trim();

            // 앵커(#section) 분리
            int hashIdx = raw.indexOf('#');
            String pageName = hashIdx >= 0 ? raw.substring(0, hashIdx).trim() : raw;
            String anchor   = hashIdx >= 0 ? raw.substring(hashIdx) : "";

            String label = match.group(2) != null ? match.group(2).trim() : pageName;

            String targetPath = titleToWikiPath.get(pageName);
            if (targetPath == null) {
                // 대상 페이지 정보 없음 — 제목 기반 추정 경로로 폴백
                targetPath = pageName.replace(" ", "-") + ".md";
            }

            String relPath = computeRelativePath(currentWikiDir, targetPath) + anchor;
            return "[" + label + "](" + relPath + ")";
        });
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
