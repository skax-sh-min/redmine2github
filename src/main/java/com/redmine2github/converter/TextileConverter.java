package com.redmine2github.converter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Textile 문법을 GitHub Flavored Markdown(GFM)으로 변환한다.
 * 기본 규칙은 정규식으로 처리하고, 복잡한 구조는 추후 pandoc 연동으로 확장한다.
 */
public class TextileConverter {

    private static final Logger log = LoggerFactory.getLogger(TextileConverter.class);

    // [^*\r\n]+ : 종결자(*)와 줄바꿈을 제외 → 인라인 범위 밖으로 역추적 불가 (ReDoS 방지)
    private static final Pattern BOLD          = Pattern.compile("\\*([^*\\r\\n]+)\\*");
    // Unicode word-boundary: (?<!\w) / (?!\w) 로 ASCII·한글 등 단어 문자 직후의 오탐 방지
    private static final Pattern ITALIC        = Pattern.compile(
            "(?<![\\w_])_([^_\\r\\n]+)_(?![\\w_])", Pattern.UNICODE_CHARACTER_CLASS);
    // "- " (목록 마커) 또는 " - " (단어 구분 하이픈) 오탐 방지: - 바로 뒤가 공백이면 취소선 아님
    private static final Pattern STRIKETHROUGH = Pattern.compile(
            "(?<!\\w)-(?!\\s)([^-\\r\\n]+)-(?!\\w)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern H1            = Pattern.compile("(?m)^h1\\.\\s+(.+)$");
    private static final Pattern H2            = Pattern.compile("(?m)^h2\\.\\s+(.+)$");
    private static final Pattern H3            = Pattern.compile("(?m)^h3\\.\\s+(.+)$");
    private static final Pattern H4            = Pattern.compile("(?m)^h4\\.\\s+(.+)$");
    // 이메일 주소의 @ 오탐 방지: @ 바로 앞이 단어 문자(영숫자·_)이면 코드 시작이 아님
    private static final Pattern CODE_INLINE   = Pattern.compile("(?<![\\w@])@([^@\\s]+)@");
    private static final Pattern CODE_BLOCK    = Pattern.compile("(?s)<pre>(.+?)</pre>");
    private static final Pattern LINK          = Pattern.compile("\"([^\"]+)\":([^\\s]+)");
    // 단일·다단계 불릿: * item, ** item, ... ****** item
    private static final Pattern UL_ITEM_ML    = Pattern.compile("(?m)^(\\*{1,6})\\s+(.+)$");
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

    // 표 행 앞에 붙은 heading 마커 제거용 — Textile "h4. " 및 GFM "#### " 모두 처리
    private static final Pattern HEADING_PREFIX = Pattern.compile("^(?:h[1-6]\\.\\s+|#{1,6}\\s+)");
    // 최종 안전망: 변환 후에도 남아있는 "#### |..." 형태 제거
    private static final Pattern HEADING_BEFORE_TABLE_ROW = Pattern.compile("(?m)^#{1,6}\\s+(\\|.*)$");

    public String convert(String textile) {
        if (textile == null || textile.isBlank()) return "";

        String md = textile;
        // 매크로 먼저 처리 — 이후 패턴과 충돌 방지
        md = convertMacros(md);
        // 이미지/첨부 먼저 변환 — 표 셀 내에서도 정상 동작하도록 표 변환 전에 실행
        md = convertImages(md);
        md = convertAttachmentLinks(md);
        // 표 변환 — 셀 내 인라인 포매팅 포함. 변환된 표 행(|로 시작)은 이후 패스에서 제외
        md = convertTables(md);
        // 블록 수준 변환 (라인 시작 앵커 — 표 행에 해당 없음)
        md = convertListItems(md);
        md = replace(md, OL_ITEM,    "1. $1");
        md = replace(md, H1,         "# $1");
        md = replace(md, H2,         "## $1");
        md = replace(md, H3,         "### $1");
        md = replace(md, H4,         "#### $1");
        md = replace(md, CODE_BLOCK, "\n```\n$1\n```\n");
        // 인라인 포매팅 — |로 시작하는 표 행은 이미 처리되었으므로 제외
        md = applyInline(md);
        // 최종 안전망: "#### |..." 처럼 heading 마커가 표 행 앞에 남아있으면 제거
        md = HEADING_BEFORE_TABLE_ROW.matcher(md).replaceAll("$1");

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

    /**
     * Textile 표를 GFM 표로 변환한다.
     *
     * <p>Textile 표 형식:
     * <pre>
     * |_.Header 1|_.Header 2|
     * |Cell 1|Cell 2|
     * </pre>
     *
     * <p>지원하는 셀 수식어: {@code _.} (헤더), {@code <.} {@code >.} {@code =.} (정렬),
     * {@code \N.} {@code /N.} (병합 — GFM 미지원이므로 제거), CSS 스타일/클래스 제거.
     *
     * <p>셀 내용에 인라인 포매팅(BOLD/ITALIC/CODE 등)을 적용한다.
     * 생성된 GFM 표 행(| 시작)은 이후 {@link #applyInline} 패스에서 건너뛴다.
     */
    private String convertTables(String input) {
        String normalized = input.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalized.split("\n", -1);

        StringBuilder result = new StringBuilder();
        List<String> tableBuffer = new ArrayList<>();

        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String stripped = stripHeadingPrefixIfTable(line);
            boolean isTableRow = !stripped.trim().isEmpty()
                    && stripped.trim().startsWith("|")
                    && !isGfmSeparatorRow(stripped);

            boolean isGfmSep = !stripped.trim().isEmpty() && isGfmSeparatorRow(stripped);

            if (isTableRow) {
                // 표 행이 |로 끝나지 않으면 셀 내용이 다음 줄로 이어지는 경우 — 줄을 병합
                String tableRow = stripped;
                while (!tableRow.trim().endsWith("|") && i + 1 < lines.length) {
                    String nextRaw      = lines[i + 1];
                    if (nextRaw.trim().isEmpty()) break;
                    String nextStripped = stripHeadingPrefixIfTable(nextRaw);
                    boolean nextIsTable = !nextStripped.trim().isEmpty()
                            && nextStripped.trim().startsWith("|")
                            && !isGfmSeparatorRow(nextStripped);
                    if (nextIsTable || isSectionDivider(nextRaw)) break;
                    tableRow = tableRow + "\n" + nextRaw;
                    i++;
                }
                tableBuffer.add(tableRow);
                i++;
            } else if (isGfmSep && !tableBuffer.isEmpty()) {
                // 이미 존재하는 GFM 구분선은 건너뜀 — renderGfmTable이 재생성
                i++;
            } else if (!tableBuffer.isEmpty()) {
                // 표 컨텍스트에서 비-표 행: continuation 수집 후 다음 표 행 또는 섹션 경계 탐색
                List<String> contLines = new ArrayList<>();
                List<String> rawLines  = new ArrayList<>();
                int j = i;
                int nextTableIdx = -1;
                boolean hitSectionDivider = false;
                while (j < lines.length) {
                    String raw = lines[j];
                    String s = stripHeadingPrefixIfTable(raw);
                    if (!s.trim().isEmpty() && s.trim().startsWith("|") && !isGfmSeparatorRow(s)) {
                        nextTableIdx = j;
                        break;
                    }
                    if (isSectionDivider(raw)) { hitSectionDivider = true; break; }
                    if (!s.trim().isEmpty() && isGfmSeparatorRow(s)) { j++; continue; }
                    rawLines.add(raw);
                    String cleaned = cleanContinuationLine(raw);
                    if (!cleaned.isEmpty()) contLines.add(cleaned);
                    j++;
                }
                if (nextTableIdx >= 0 || hitSectionDivider) {
                    // 다음 표 행이나 섹션 경계가 있으면 continuation을 마지막 셀에 병합
                    if (!contLines.isEmpty()) {
                        appendContinuationToLastRow(tableBuffer, String.join("<br>", contLines));
                    }
                    if (nextTableIdx >= 0) {
                        i = nextTableIdx; // 표 계속
                    } else {
                        result.append(renderGfmTable(tableBuffer));
                        tableBuffer.clear();
                        i = j; // j는 섹션 경계 행 — 다음 루프에서 일반 텍스트로 처리
                    }
                } else {
                    // 섹션 경계/후속 표 행 없음 → 표 종료, 수집한 행은 일반 텍스트로 출력
                    result.append(renderGfmTable(tableBuffer));
                    tableBuffer.clear();
                    for (String raw : rawLines) result.append(raw).append('\n');
                    i = j;
                }
            } else {
                result.append(line).append('\n');
                i++;
            }
        }
        if (!tableBuffer.isEmpty()) {
            result.append(renderGfmTable(tableBuffer));
        }

        String output = result.toString();
        if (!normalized.endsWith("\n") && output.endsWith("\n")) {
            output = output.substring(0, output.length() - 1);
        }
        return output;
    }

    /** * item, ** item, - item, # item 등 섹션 경계 마커인지 확인한다. */
    private boolean isSectionDivider(String line) {
        String t = line.trim();
        return t.matches("\\*+\\s+.*") || t.matches("-\\s+.*") || t.matches("#+\\s+.*");
    }

    /** 연속 텍스트 행에서 꼬리의 |..| 잔재(Redmine 멀티라인 셀 구조)를 제거한다. */
    private String cleanContinuationLine(String line) {
        String s = line.trim();
        s = s.replaceAll("(\\|[^|]*)+\\s*$", "").trim();
        return s;
    }

    /** 마지막 표 행의 마지막 비-공백 셀 뒤에 continuation 텍스트를 &lt;br&gt;로 붙인다. */
    private void appendContinuationToLastRow(List<String> tableBuffer, String cont) {
        if (tableBuffer.isEmpty() || cont.isEmpty()) return;
        int last = tableBuffer.size() - 1;
        String row = tableBuffer.get(last).trim();
        if (!row.endsWith("|")) return;
        String inner = row.substring(1, row.length() - 1);
        String[] cells = inner.split("\\|", -1);
        int targetIdx = cells.length - 1;
        for (int i = cells.length - 1; i >= 0; i--) {
            if (!cells[i].trim().isEmpty()) { targetIdx = i; break; }
        }
        cells[targetIdx] = " " + cells[targetIdx].trim() + "<br>" + cont + " ";
        tableBuffer.set(last, "|" + String.join("|", cells) + "|");
    }

    private String renderGfmTable(List<String> rawLines) {
        List<String[]> rows = new ArrayList<>();
        int headerEnd = -1;

        for (int i = 0; i < rawLines.size(); i++) {
            rows.add(parseTableRow(rawLines.get(i)));
            // |_. 을 포함하는 줄이 상단부터 연속으로 있으면 헤더 행으로 취급
            if (rawLines.get(i).contains("|_.")) {
                if (headerEnd == i - 1 || headerEnd == -1) {
                    headerEnd = i;
                }
            }
        }
        // 헤더 행이 없으면 첫 행을 헤더로 (GFM 필수 요건)
        if (headerEnd == -1) headerEnd = 0;

        // 최대 열 개수
        int cols = rows.stream().mapToInt(r -> r.length).max().orElse(1);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            sb.append("| ");
            String[] padded = padRow(rows.get(i), cols);
            // 셀 내용에 인라인 포매팅 적용 (이 시점에는 이미지/첨부는 변환 완료)
            String[] formatted = Arrays.stream(padded)
                    .map(this::applyInlineToCell)
                    .toArray(String[]::new);
            sb.append(String.join(" | ", formatted));
            sb.append(" |\n");
            if (i == headerEnd) {
                // 구분선 — 코드로 생성되므로 이후 인라인 변환 패스에서 제외됨
                sb.append("|");
                for (int c = 0; c < cols; c++) sb.append(" --- |");
                sb.append("\n");
            }
        }
        sb.insert(0, '\n');
        sb.append('\n');
        return sb.toString();
    }

    /** GFM 구분선 행인지 확인한다. 예: "| --- | --- |", "|---|---|" */
    private boolean isGfmSeparatorRow(String line) {
        String content = line.trim();
        if (content.startsWith("|")) content = content.substring(1);
        if (content.endsWith("|"))   content = content.substring(0, content.length() - 1);
        for (String cell : content.split("\\|", -1)) {
            if (!cell.trim().matches(":?-{1,}:?")) return false;
        }
        return true;
    }

    /**
     * "h4. |col1|col2|" 처럼 heading 마커가 붙은 표 행에서 마커를 제거한다.
     * 나머지 부분이 "|"로 시작하지 않으면 원본을 그대로 반환한다.
     */
    private String stripHeadingPrefixIfTable(String line) {
        String trimmed = line.trim();
        Matcher m = HEADING_PREFIX.matcher(trimmed);
        if (m.find()) {
            String rest = trimmed.substring(m.end()).trim();
            if (rest.startsWith("|")) return rest;
        }
        return line;
    }

    /** Textile 표 행을 셀 배열로 파싱한다. */
    private String[] parseTableRow(String line) {
        String content = line.trim();
        if (content.startsWith("|")) content = content.substring(1);
        if (content.endsWith("|"))  content = content.substring(0, content.length() - 1);
        String[] parts = content.split("\\|", -1);
        String[] cells = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            cells[i] = cleanTableCell(parts[i]);
        }
        return cells;
    }

    /**
     * Textile 셀에서 수식어를 제거하고 내용만 반환한다.
     * <ul>
     *   <li>{@code _.} — 헤더 마커</li>
     *   <li>{@code <.} {@code >.} {@code =.} {@code ^.} {@code ~.} — 정렬/수직 정렬</li>
     *   <li>{@code \N.} {@code /N.} — colspan/rowspan (GFM 미지원, 제거)</li>
     *   <li>{@code {style}} {@code (class)} — CSS (제거)</li>
     * </ul>
     */
    private String cleanTableCell(String cell) {
        String s = cell.trim();
        // 셀 내 줄바꿈(다중 행 병합 후 남은 \n) → <br>
        s = s.replace("\n", "<br>");
        // colspan/rowspan: \2. /3. 등
        s = s.replaceFirst("^[/\\\\]\\d+\\.\\s*", "");
        // 헤더 마커: _.
        if (s.startsWith("_.")) s = s.substring(2).trim();
        // 정렬 수식어: <. >. =. ^. ~.
        if (s.length() >= 2 && "<>=^~".indexOf(s.charAt(0)) >= 0 && s.charAt(1) == '.') {
            s = s.substring(2).trim();
        }
        // CSS 스타일/클래스: {style}. (class).
        s = s.replaceFirst("^\\{[^}]*\\}\\.?\\s*", "");
        s = s.replaceFirst("^\\([^)]*\\)\\.?\\s*", "");
        return s.trim();
    }

    /** 셀 내용에만 인라인 포매팅을 적용한다 (표 구조 기호 `|` `---` 제외). */
    private String applyInlineToCell(String cell) {
        String s = cell;
        s = replace(s, CODE_INLINE,   "`$1`");
        s = replace(s, BOLD,          "**$1**");
        s = replace(s, ITALIC,        "*$1*");
        s = replace(s, STRIKETHROUGH, "~~$1~~");
        s = replace(s, LINK,          "[$1]($2)");
        return s;
    }

    /**
     * 비표(非表) 텍스트에 인라인 포매팅을 적용한다.
     *
     * <p>|로 시작하는 표 행은 {@link #renderGfmTable}에서 이미 처리되었으므로 건너뛴다.
     * 연속된 비표 줄은 하나의 블록으로 처리해 멀티라인 패턴도 올바르게 동작한다.
     */
    private String applyInline(String input) {
        String[] lines = input.split("\n", -1);
        StringBuilder result = new StringBuilder();
        StringBuilder textBlock = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean isTableLine = !line.trim().isEmpty() && line.trim().startsWith("|");
            if (isTableLine) {
                if (textBlock.length() > 0) {
                    result.append(applyInlineToText(textBlock.toString()));
                    textBlock.setLength(0);
                }
                result.append(line).append('\n');
            } else {
                textBlock.append(line).append('\n');
            }
        }
        if (textBlock.length() > 0) {
            result.append(applyInlineToText(textBlock.toString()));
        }

        // split("-1")로 마지막 빈 원소 포함 → 원본이 \n 미종료면 결과 끝 \n 제거
        String output = result.toString();
        if (!input.endsWith("\n") && output.endsWith("\n")) {
            output = output.substring(0, output.length() - 1);
        }
        return output;
    }

    private String applyInlineToText(String text) {
        String s = text;
        s = replace(s, CODE_INLINE,   "`$1`");
        s = replace(s, BOLD,          "**$1**");
        s = replace(s, ITALIC,        "*$1*");
        s = replace(s, STRIKETHROUGH, "~~$1~~");
        s = replace(s, LINK,          "[$1]($2)");
        return s;
    }

    private static String[] padRow(String[] row, int cols) {
        if (row.length >= cols) return row;
        String[] padded = Arrays.copyOf(row, cols);
        Arrays.fill(padded, row.length, cols, "");
        return padded;
    }

    /** Textile 다단계 불릿(*, **, ***...)을 GFM 들여쓰기 목록으로 변환한다. */
    private String convertListItems(String input) {
        return UL_ITEM_ML.matcher(input).replaceAll(m -> {
            int depth = m.group(1).length();
            String indent = "  ".repeat(depth - 1);
            return Matcher.quoteReplacement(indent + "- " + m.group(2));
        });
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
