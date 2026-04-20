package com.redmine2github.converter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

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
    void emailAtSignNotCorrupted() {
        // 이메일 주소의 @가 인라인 코드 패턴으로 오변환되면 안 됨
        String input = "담당자: igshin@fsb.or.kr, do929@fsb.or.kr";
        String result = converter.convert(input).trim();
        assertTrue(result.contains("igshin@fsb.or.kr"), "이메일 주소가 보존되어야 함");
        assertTrue(result.contains("do929@fsb.or.kr"), "두 번째 이메일도 보존되어야 함");
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

    @Test
    void nestedListItemWithHyphenNotStrikethrough() {
        // GFM 리스트 마커 "- " 와 내용 중 하이픈이 취소선으로 오변환되면 안 됨
        String result = converter.convert("** 담당자 : LG CNS - 강동석 수석(010-2277-0251)").stripTrailing();
        assertFalse(result.contains("~~"), "리스트 항목이 취소선으로 변환되면 안 됨");
        assertTrue(result.contains("LG CNS - 강동석"), "내용 중 하이픈이 보존되어야 함");
    }

    // ── 다단계 목록 ───────────────────────────────────────────────────────────

    @Test
    void nestedList_singleLevel() {
        assertEquals("- 항목", converter.convert("* 항목").trim());
    }

    @Test
    void nestedList_secondLevel() {
        assertEquals("  - 담당자", converter.convert("** 담당자").stripTrailing());
    }

    @Test
    void nestedList_fifthLevel() {
        assertEquals("        - Online 서비스", converter.convert("***** Online 서비스").stripTrailing());
    }

    @Test
    void nestedList_multipleItems() {
        String input = "* 정보\n** 담당자 : 홍길동\n***** 세부내용";
        String result = converter.convert(input).trim();
        String[] lines = result.split("\n");
        assertEquals("- 정보", lines[0]);
        assertEquals("  - 담당자 : 홍길동", lines[1]);
        assertEquals("        - 세부내용", lines[2]);
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

    @Test
    void table_textileHeadingPrefixOnHeaderRow_stripped() {
        // Redmine Textile: "h4. |컬럼1|컬럼2|"
        String input = "h4. | 고객사 | 프로젝트명 |\n| 저축은행 | Framework 개발 |";
        String result = converter.convert(input).trim();
        String[] lines = result.split("\n");
        assertEquals("| 고객사 | 프로젝트명 |", lines[0], "Textile heading 마커가 제거되어야 함");
        assertEquals("| --- | --- |", lines[1]);
        assertEquals("| 저축은행 | Framework 개발 |", lines[2]);
    }

    @Test
    void table_gfmHeadingPrefixOnHeaderRow_stripped() {
        // Redmine에 GFM 형식으로 직접 작성: "#### |컬럼1|컬럼2|"
        String input = "#### | 고객사 | 프로젝트명 |\n| --- | --- |\n| 저축은행 | Framework 개발 |";
        String result = converter.convert(input).trim();
        String[] lines = result.split("\n");
        assertEquals("| 고객사 | 프로젝트명 |", lines[0], "GFM heading 마커가 제거되어야 함");
        assertEquals("| --- | --- |", lines[1]);
        assertEquals("| 저축은행 | Framework 개발 |", lines[2]);
    }

    @Test
    void table_multilineCellContent_mergedWithBr() {
        // 표 행 사이에 연속 텍스트가 있으면 이전 행 셀에 <br>로 병합되어야 함
        String input = "| 호스트 | 서버명 | 포트 |\n"
                + "| srv01 | WAS | O |\n"
                + "\n"
                + "계정계-28090(WEB)\n"
                + "통합리스크-28200(WEB)\n"
                + "| srv02 | DB  | O |";
        String result = converter.convert(input);
        // 표가 하나로 합쳐져야 함 (두 개로 분리되지 않아야 함)
        long tableCount = Arrays.stream(result.split("\n"))
                .filter(l -> l.startsWith("| ---")).count();
        assertEquals(1, tableCount, "표가 하나로 합쳐져야 함");
        assertTrue(result.contains("계정계-28090(WEB)"), "연속 텍스트가 보존되어야 함");
        assertTrue(result.contains("| srv02 |"), "후속 행이 같은 표에 있어야 함");
    }

    @Test
    void table_continuationWithTrailingPipes_cleaned() {
        // 연속 텍스트 끝의 |..| 잔재가 제거되어야 함
        String input = "| 서버 | 포트 |\n"
                + "| srv01 | O |\n"
                + "계정계-28090|~~|~~|O|\n"
                + "| srv02 | O |";
        String result = converter.convert(input);
        assertFalse(result.contains("~~"), "연속 텍스트의 |~~|~~| 잔재가 제거되어야 함");
        assertTrue(result.contains("계정계-28090"), "연속 텍스트 내용은 유지되어야 함");
    }
}
