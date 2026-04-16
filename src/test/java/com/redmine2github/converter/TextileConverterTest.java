package com.redmine2github.converter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextileConverterTest {

    private TextileConverter converter;

    @BeforeEach
    void setUp() {
        converter = new TextileConverter();
    }

    @Test
    void heading() {
        assertEquals("# 제목", converter.convert("h1. 제목").trim());
        assertEquals("## 소제목", converter.convert("h2. 소제목").trim());
    }

    @Test
    void bold() {
        assertEquals("**굵게**", converter.convert("*굵게*").trim());
    }

    @Test
    void italic() {
        assertEquals("*기울임*", converter.convert("_기울임_").trim());
    }

    @Test
    void codeInline() {
        assertEquals("`code`", converter.convert("@code@").trim());
    }

    @Test
    void link() {
        assertEquals("[Redmine](https://example.com)", converter.convert("\"Redmine\":https://example.com").trim());
    }

    @Test
    void imageSimple() {
        assertEquals("![](diagram.png)", converter.convert("!diagram.png!").trim());
    }

    @Test
    void imageWithAlt() {
        assertEquals("![시스템 구조](arch.png)", converter.convert("!arch.png(시스템 구조)!").trim());
    }

    @Test
    void imageWithAlignment() {
        assertEquals("![](logo.png)", converter.convert("!>logo.png!").trim());
    }

    @Test
    void imageWithThumbnail() {
        assertEquals("![](photo.jpg)", converter.convert("!photo.jpg|thumbnail!").trim());
    }

    @Test
    void attachmentLink() {
        String result = converter.convert("attachment:spec.pdf");
        assertEquals("[spec.pdf](attachment:spec.pdf)", result.trim());
    }

    @Test
    void nullOrBlankReturnsEmpty() {
        assertEquals("", converter.convert(null));
        assertEquals("", converter.convert(""));
        assertEquals("", converter.convert("   "));
    }

    // ── Redmine 매크로 ────────────────────────────────────────────────────────

    @Test
    void macroTocRemoved() {
        assertEquals("", converter.convert("{{toc}}").trim());
    }

    @Test
    void macroTocAlignedRemoved() {
        assertEquals("", converter.convert("{{>toc}}").trim());
    }

    @Test
    void macroChildPagesRemoved() {
        assertEquals("", converter.convert("{{child_pages}}").trim());
    }

    @Test
    void macroChildPagesWithArgsRemoved() {
        assertEquals("", converter.convert("{{child_pages(depth=2)}}").trim());
    }

    @Test
    void macroIncludeConvertsToWikiLink() {
        // LinkRewriter 가 후속으로 [[...]] 를 처리한다
        assertEquals("[[Installation Guide]]", converter.convert("{{include(Installation Guide)}}").trim());
    }

    @Test
    void macroUnknownRemoved() {
        assertEquals("", converter.convert("{{unknown_macro(arg)}}").trim());
    }

    // ── 오탐 방지 (regression) ────────────────────────────────────────────────

    @Test
    void headingWithDateNotCorrupted() {
        // '-03-' 가 strikethrough 로 처리되면 안 됨
        assertEquals("# 2025-03-17", converter.convert("h1. 2025-03-17").trim());
    }

    @Test
    void imageFilenameWithUnderscoreNotCorrupted() {
        // 파일명 내 '_'가 italic 으로 처리되면 안 됨
        assertEquals("![](파일_이름.png)", converter.convert("!파일_이름.png!").trim());
    }

    @Test
    void imageFilenameWithHyphenNotCorrupted() {
        // 파일명 내 '-'가 strikethrough 로 처리되면 안 됨
        assertEquals("![](to-be-1.png)", converter.convert("!to-be-1.png!").trim());
    }

    @Test
    void strikethroughStillWorksInText() {
        // 정상 strikethrough (공백으로 둘러싸인 경우) 는 변환되어야 함
        assertEquals("~~삭제된 내용~~", converter.convert("-삭제된 내용-").trim());
    }

    // ── Table ─────────────────────────────────────────────────────────────────

    @Test
    void table_headerAndDataRows() {
        String input = "|_.이름|_.나이|\n|Alice|30|\n|Bob|25|";
        String result = converter.convert(input).trim();
        String[] lines = result.split("\n");
        assertEquals("| 이름 | 나이 |", lines[0]);
        assertEquals("| --- | --- |", lines[1]);
        assertEquals("| Alice | 30 |", lines[2]);
        assertEquals("| Bob | 25 |", lines[3]);
    }

    @Test
    void table_noHeaderRow_firstRowBecomesHeader() {
        // 헤더 마커 없는 표 — GFM 필수 요건상 첫 행이 헤더가 됨
        String input = "|Cell 1|Cell 2|\n|Cell 3|Cell 4|";
        String result = converter.convert(input).trim();
        String[] lines = result.split("\n");
        assertEquals("| Cell 1 | Cell 2 |", lines[0]);
        assertEquals("| --- | --- |", lines[1]);
        assertEquals("| Cell 3 | Cell 4 |", lines[2]);
    }

    @Test
    void table_alignmentModifiersStripped() {
        // <. >. =. 정렬 수식어 제거
        String input = "|_. 이름|_. 나이|\n|<. Alice|>. 30|";
        String result = converter.convert(input).trim();
        String[] lines = result.split("\n");
        assertEquals("| 이름 | 나이 |", lines[0]);
        assertEquals("| --- | --- |", lines[1]);
        assertEquals("| Alice | 30 |", lines[2]);
    }

    @Test
    void table_colspanModifierStripped() {
        // \2. colspan 수식어 제거
        String input = "|_.이름|_.나이|_.점수|\n|\\2.Alice|100|";
        String result = converter.convert(input).trim();
        String[] lines = result.split("\n");
        assertEquals("| 이름 | 나이 | 점수 |", lines[0]);
        assertEquals("| --- | --- | --- |", lines[1]);
        // \2. 제거 후 Alice만 남음 (열 수 맞추기 위해 빈 셀 추가)
        assertTrue(lines[2].startsWith("| Alice |"));
    }

    @Test
    void table_surroundedByText() {
        // 표 앞뒤에 일반 텍스트가 있는 경우
        String input = "서문\n\n|_.A|_.B|\n|1|2|\n\n후문";
        String result = converter.convert(input);
        assertTrue(result.contains("| A | B |"));
        assertTrue(result.contains("| --- | --- |"));
        assertTrue(result.contains("| 1 | 2 |"));
        assertTrue(result.contains("서문"));
        assertTrue(result.contains("후문"));
    }
}
