package com.redmine2github.converter;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 마크다운 본문 내 첨부파일 경로 참조를 GitHub 리포지터리 경로로 갱신한다.
 *
 * <p>Redmine Textile에서 변환된 마크다운은 첨부파일을 파일명만으로 참조한다.
 * 이 클래스는 해당 참조를 실제 저장 경로({@code attachmentBasePath})로 재작성한다.
 *
 * <h3>처리 대상 패턴 (TextileConverter 변환 이후 기준)</h3>
 * <ul>
 *   <li>인라인 이미지: {@code ![alt](filename.png)} → {@code ![alt](../attachments/filename.png)}</li>
 *   <li>파일 링크:     {@code [label](filename.pdf)} → {@code [label](../attachments/filename.pdf)}</li>
 * </ul>
 *
 * <p>이미 {@code http://}, {@code https://}, {@code /} 등 외부/절대 경로로 시작하는
 * 참조는 변경하지 않는다.
 */
public class AttachmentPathRewriter {

    // ![alt](href) — 인라인 이미지
    private static final Pattern IMG_LINK  = Pattern.compile("(!\\[[^\\]]*]\\()([^)]+)(\\))");
    // [label](href) — 일반 링크 (이미지 제외)
    private static final Pattern TEXT_LINK = Pattern.compile("(?<!!)(\\ ?\\[[^\\]]*]\\()([^)]+)(\\))");

    /**
     * @param markdown           변환할 마크다운 텍스트
     * @param attachmentNames    해당 페이지에 첨부된 파일명 목록
     * @param attachmentBasePath GitHub 리포지터리 내 첨부파일 디렉터리 상대 경로
     *                           (예: {@code "../attachments/"})
     * @return 첨부파일 경로가 갱신된 마크다운
     */
    public String rewrite(String markdown, Collection<String> attachmentNames,
                          String attachmentBasePath) {
        if (markdown == null || markdown.isBlank() || attachmentNames.isEmpty()) {
            return markdown == null ? "" : markdown;
        }
        String result = replaceHrefs(markdown, IMG_LINK,  attachmentNames, attachmentBasePath);
        result        = replaceHrefs(result,   TEXT_LINK, attachmentNames, attachmentBasePath);
        return result;
    }

    private String replaceHrefs(String markdown, Pattern pattern,
                                 Collection<String> attachmentNames, String basePath) {
        Matcher m = pattern.matcher(markdown);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String prefix = m.group(1); // "![alt](" 또는 "[label]("
            String href   = m.group(2); // 현재 href 값
            String suffix = m.group(3); // ")"

            // Redmine attachment: 접두사 처리 — attachment:filename → basePath/filename
            String effectiveHref = href;
            if (href.startsWith("attachment:")) {
                effectiveHref = href.substring("attachment:".length());
            }

            // 외부 URL이나 이미 경로가 붙은 경우 건너뜀
            if (isAbsoluteOrRelativePath(effectiveHref)) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                continue;
            }

            // 첨부파일 목록에 포함된 파일명인 경우만 갱신
            if (attachmentNames.contains(effectiveHref)) {
                m.appendReplacement(sb,
                    Matcher.quoteReplacement(prefix + basePath + effectiveHref + suffix));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private boolean isAbsoluteOrRelativePath(String href) {
        return href.startsWith("http://")
            || href.startsWith("https://")
            || href.startsWith("/")
            || href.startsWith("./")
            || href.startsWith("../");
    }
}
