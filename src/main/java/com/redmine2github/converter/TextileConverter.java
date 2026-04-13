package com.redmine2github.converter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Textile 문법을 GitHub Flavored Markdown(GFM)으로 변환한다.
 * 기본 규칙은 정규식으로 처리하고, 복잡한 구조는 추후 pandoc 연동으로 확장한다.
 */
public class TextileConverter {

    private static final Logger log = LoggerFactory.getLogger(TextileConverter.class);

    private static final Pattern BOLD          = Pattern.compile("\\*(.+?)\\*");
    // Unicode word-boundary: (?<!\w) / (?!\w) 로 ASCII·한글 등 단어 문자 직후의 오탐 방지
    private static final Pattern ITALIC        = Pattern.compile(
            "(?<![\\w_])_(.+?)_(?![\\w_])", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern STRIKETHROUGH = Pattern.compile(
            "(?<!\\w)-(.+?)-(?!\\w)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern H1            = Pattern.compile("(?m)^h1\\.\\s+(.+)$");
    private static final Pattern H2            = Pattern.compile("(?m)^h2\\.\\s+(.+)$");
    private static final Pattern H3            = Pattern.compile("(?m)^h3\\.\\s+(.+)$");
    private static final Pattern H4            = Pattern.compile("(?m)^h4\\.\\s+(.+)$");
    private static final Pattern CODE_INLINE   = Pattern.compile("@(.+?)@");
    private static final Pattern CODE_BLOCK    = Pattern.compile("(?s)<pre>(.+?)</pre>");
    private static final Pattern LINK          = Pattern.compile("\"([^\"]+)\":([^\\s]+)");
    private static final Pattern UL_ITEM       = Pattern.compile("(?m)^\\*\\s+(.+)$");
    private static final Pattern OL_ITEM       = Pattern.compile("(?m)^#\\s+(.+)$");

    // Redmine 이미지 문법: !filename!, !>filename!, !filename(alt)!, !filename|thumbnail!
    // group(1) = 파일명, group(2) = alt 텍스트(선택)
    private static final Pattern IMAGE = Pattern.compile(
            "![<>~]?([^!|() \\t\\r\\n]+)(?:\\(([^)]*)\\))?(?:\\|[^!]*)?!");

    // attachment:filename 단독 참조 — 마크다운 링크/URL 내부는 제외
    private static final Pattern ATTACHMENT = Pattern.compile(
            "(?<![(/\"'])\\battachment:([^\\s\\])<>\"]+)");

    // Redmine 매크로
    private static final Pattern MACRO_TOC = Pattern.compile(
            "\\{\\{>?toc(?:\\([^)]*\\))?\\}\\}");
    private static final Pattern MACRO_CHILD_PAGES = Pattern.compile(
            "\\{\\{child_pages(?:\\([^)]*\\))?\\}\\}");
    // {{include(PageName)}} or {{include(PageName, extra)}} → [[PageName]]
    private static final Pattern MACRO_INCLUDE = Pattern.compile(
            "\\{\\{include\\(([^,)]+)(?:,[^)]*)?\\)\\}\\}");
    // 나머지 알 수 없는 매크로
    private static final Pattern MACRO_GENERIC = Pattern.compile(
            "\\{\\{[^{}]+\\}\\}");

    public String convert(String textile) {
        if (textile == null || textile.isBlank()) return "";

        String md = textile;
        // 매크로 먼저 처리 — 이후 패턴과 충돌 방지
        md = convertMacros(md);
        // 이미지/첨부 먼저 변환 — 파일명이 이후 inline 포매팅(italic, strikethrough 등)에 오염되지 않도록
        md = convertImages(md);
        md = convertAttachmentLinks(md);
        // 리스트 먼저 변환 (헤딩 변환 후 # 기호와 충돌 방지)
        md = replace(md, UL_ITEM,       "- $1");
        md = replace(md, OL_ITEM,       "1. $1");
        md = replace(md, H1,            "# $1");
        md = replace(md, H2,            "## $1");
        md = replace(md, H3,            "### $1");
        md = replace(md, H4,            "#### $1");
        md = replace(md, CODE_BLOCK,    "\n```\n$1\n```\n");
        md = replace(md, CODE_INLINE,   "`$1`");
        md = replace(md, BOLD,          "**$1**");
        md = replace(md, ITALIC,        "*$1*");
        md = replace(md, STRIKETHROUGH, "~~$1~~");
        md = replace(md, LINK,          "[$1]($2)");

        return md;
    }

    /**
     * Redmine 매크로를 변환하거나 제거한다.
     * <ul>
     *   <li>{@code {{toc}}} / {@code {{>toc}}} → 제거 (GitHub은 헤딩에서 자동 TOC 생성)</li>
     *   <li>{@code {{child_pages}}} → 제거</li>
     *   <li>{@code {{include(PageName)}}} → {@code [[PageName]]} (LinkRewriter가 경로 확정)</li>
     *   <li>나머지 알 수 없는 매크로 → 제거</li>
     * </ul>
     */
    private String convertMacros(String input) {
        input = MACRO_TOC.matcher(input).replaceAll("");
        input = MACRO_CHILD_PAGES.matcher(input).replaceAll("");
        input = MACRO_INCLUDE.matcher(input).replaceAll(m ->
                Matcher.quoteReplacement("[[" + m.group(1).trim() + "]]"));
        input = MACRO_GENERIC.matcher(input).replaceAll("");
        return input;
    }

    /** Redmine 이미지 문법을 GFM 이미지로 변환한다. alt 텍스트가 있으면 포함한다. */
    private String convertImages(String input) {
        try {
            return IMAGE.matcher(input).replaceAll(m -> {
                String filename = m.group(1);
                String alt = m.group(2) != null ? m.group(2) : "";
                return Matcher.quoteReplacement("![" + alt + "](" + filename + ")");
            });
        } catch (Exception e) {
            log.warn("이미지 변환 실패: {}", e.getMessage());
            return input;
        }
    }

    /**
     * Redmine {@code attachment:filename} 단독 참조를 마크다운 링크로 변환한다.
     * 이후 {@link AttachmentPathRewriter}에서 경로가 보정된다.
     */
    private String convertAttachmentLinks(String input) {
        try {
            return ATTACHMENT.matcher(input).replaceAll(m -> {
                String filename = m.group(1);
                return Matcher.quoteReplacement("[" + filename + "](attachment:" + filename + ")");
            });
        } catch (Exception e) {
            log.warn("attachment 링크 변환 실패: {}", e.getMessage());
            return input;
        }
    }

    private String replace(String input, Pattern pattern, String replacement) {
        try {
            return pattern.matcher(input).replaceAll(replacement);
        } catch (Exception e) {
            log.warn("변환 패턴 적용 실패 [{}]: {}", pattern.pattern(), e.getMessage());
            return input;
        }
    }
}
