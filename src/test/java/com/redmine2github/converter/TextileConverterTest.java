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
}
