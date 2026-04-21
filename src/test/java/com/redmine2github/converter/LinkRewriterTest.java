package com.redmine2github.converter;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LinkRewriterTest {

    private final LinkRewriter rewriter = new LinkRewriter();

    // ── 현재 프로젝트 내 링크 ──────────────────────────────────────────────────

    @Test
    void topLevelToTopLevel() {
        Map<String, String> map = Map.of("GettingStarted", "GettingStarted.md");
        String result = rewriter.rewrite("[[GettingStarted]]", map, "proj", "");
        assertEquals("[GettingStarted](GettingStarted.md)", result);
    }

    @Test
    void linkWithLabel() {
        Map<String, String> map = Map.of("GettingStarted", "GettingStarted.md");
        String result = rewriter.rewrite("[[GettingStarted|시작하기]]", map, "proj", "");
        assertEquals("[시작하기](GettingStarted.md)", result);
    }

    @Test
    void subpageToTopLevel() {
        Map<String, String> map = Map.of("Other", "Other.md");
        String result = rewriter.rewrite("[[Other]]", map, "proj", "Root");
        assertEquals("[Other](../Other.md)", result);
    }

    @Test
    void subpageToSibling() {
        Map<String, String> map = Map.of("Sibling", "Root/Sibling.md");
        String result = rewriter.rewrite("[[Sibling]]", map, "proj", "Root");
        assertEquals("[Sibling](Sibling.md)", result);
    }

    @Test
    void topLevelToSubpage() {
        Map<String, String> map = Map.of("Child", "Root/Child.md");
        String result = rewriter.rewrite("[[Child]]", map, "proj", "");
        assertEquals("[Child](Root/Child.md)", result);
    }

    @Test
    void unknownPageFallsBackToSlug() {
        Map<String, String> map = Map.of();
        String result = rewriter.rewrite("[[Missing Page]]", map, "proj", "");
        assertEquals("[Missing Page](Missing-Page.md)", result);
    }

    @Test
    void noLinkUnchanged() {
        Map<String, String> map = Map.of();
        String input = "일반 텍스트입니다.";
        assertEquals(input, rewriter.rewrite(input, map, "proj", ""));
    }

    // ── 앵커(#section) ────────────────────────────────────────────────────────

    @Test
    void anchorOnlyPage() {
        Map<String, String> map = Map.of("PageName", "PageName.md");
        String result = rewriter.rewrite("[[PageName#section]]", map, "proj", "");
        assertEquals("[PageName](PageName.md#section)", result);
    }

    @Test
    void anchorWithLabel() {
        Map<String, String> map = Map.of("PageName", "PageName.md");
        String result = rewriter.rewrite("[[PageName#section|보러가기]]", map, "proj", "");
        assertEquals("[보러가기](PageName.md#section)", result);
    }

    @Test
    void anchorFromSubpage() {
        Map<String, String> map = Map.of("Other", "Other.md");
        String result = rewriter.rewrite("[[Other#sec]]", map, "proj", "Root");
        assertEquals("[Other](../Other.md#sec)", result);
    }

    @Test
    void anchorUnknownPageFallback() {
        Map<String, String> map = Map.of();
        String result = rewriter.rewrite("[[Missing Page#anchor]]", map, "proj", "");
        assertEquals("[Missing Page](Missing-Page.md#anchor)", result);
    }

    // ── 콜론 포함 페이지 제목 (크로스 프로젝트 오탐 방지) ──────────────────────

    @Test
    void colonInTitleNotCrossProject() {
        // "API: 개요" → 크로스 프로젝트가 아닌 일반 페이지
        Map<String, String> map = Map.of("API: 개요", "API:-개요.md");
        String result = rewriter.rewrite("[[API: 개요]]", map, "proj", "");
        assertEquals("[API: 개요](API:-개요.md)", result);
    }

    @Test
    void colonWithoutSpaceIsCrossProject() {
        // "other:Page" → 크로스 프로젝트 (prefix에 공백 없음)
        Map<String, String> map = Map.of();
        String result = rewriter.rewrite("[[other:Page]]", map, "proj", "");
        assertEquals("[Page](../../other/wiki/Page.md)", result);
    }

    // ── 대소문자/공백 불일치 폴백 ─────────────────────────────────────────────

    @Test
    void caseInsensitiveFallback() {
        // 실제 제목: "Page Name", 링크: [[page name]]
        Map<String, String> map = Map.of("Page Name", "Page-Name.md");
        String result = rewriter.rewrite("[[page name]]", map, "proj", "");
        assertEquals("[page name](Page-Name.md)", result);
    }

    @Test
    void whitespaceNormalizationFallback() {
        // 실제 제목에 이중 공백, 링크에는 단일 공백
        Map<String, String> map = Map.of("Page  Name", "Page--Name.md");
        String result = rewriter.rewrite("[[Page Name]]", map, "proj", "");
        assertEquals("[Page Name](Page--Name.md)", result);
    }

    @Test
    void underscoreInTitleMatchesSpaceInLink() {
        // 제목은 '_' 저장, 링크는 공백 사용 — 둘이 같은 페이지로 해석되어야 함
        Map<String, String> map = Map.of("기술지원-OO산업_차세대_시스템",
                "REOCN_기술지원/기술지원-OO산업_차세대_시스템.md");
        String result = rewriter.rewrite("[[기술지원-OO산업 차세대 시스템]]", map, "proj", "");
        assertEquals("[기술지원-OO산업 차세대 시스템](REOCN_기술지원/기술지원-OO산업_차세대_시스템.md)", result);
    }

    @Test
    void underscoreInTitleMatchesSpaceInLink_fromSubdir() {
        // 하위 디렉터리에서 링크할 때도 올바른 상대 경로를 계산해야 함
        Map<String, String> map = Map.of("기술지원-OO산업_차세대_시스템",
                "REOCN_기술지원/기술지원-OO산업_차세대_시스템.md");
        String result = rewriter.rewrite("[[기술지원-OO산업 차세대 시스템]]", map, "proj", "REOCN_기술지원");
        assertEquals("[기술지원-OO산업 차세대 시스템](기술지원-OO산업_차세대_시스템.md)", result);
    }

    // ── 크로스 프로젝트 링크 [[OtherProject:PageName]] ────────────────────────

    @Test
    void crossProject_topLevel() {
        // proj/wiki/ → other/wiki/Page.md : ../../other/wiki/Page.md
        Map<String, String> map = Map.of();
        String result = rewriter.rewrite("[[other:Page]]", map, "proj", "");
        assertEquals("[Page](../../other/wiki/Page.md)", result);
    }

    @Test
    void crossProject_withLabel() {
        Map<String, String> map = Map.of();
        String result = rewriter.rewrite("[[other:Page|다른 프로젝트]]", map, "proj", "");
        assertEquals("[다른 프로젝트](../../other/wiki/Page.md)", result);
    }

    @Test
    void crossProject_withAnchor() {
        Map<String, String> map = Map.of();
        String result = rewriter.rewrite("[[other:Page#section]]", map, "proj", "");
        assertEquals("[Page](../../other/wiki/Page.md#section)", result);
    }

    @Test
    void crossProject_fromSubpage() {
        // proj/wiki/Root/ → other/wiki/Page.md : ../../../other/wiki/Page.md
        Map<String, String> map = Map.of();
        String result = rewriter.rewrite("[[other:Page]]", map, "proj", "Root");
        assertEquals("[Page](../../../other/wiki/Page.md)", result);
    }

    @Test
    void crossProject_spaceInPageName() {
        Map<String, String> map = Map.of();
        String result = rewriter.rewrite("[[other:My Page]]", map, "proj", "");
        assertEquals("[My Page](../../other/wiki/My-Page.md)", result);
    }
}
