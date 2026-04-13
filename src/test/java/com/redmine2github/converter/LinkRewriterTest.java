package com.redmine2github.converter;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LinkRewriterTest {

    private final LinkRewriter rewriter = new LinkRewriter();

    @Test
    void topLevelToTopLevel() {
        Map<String, String> map = Map.of("GettingStarted", "GettingStarted.md");
        String result = rewriter.rewrite("[[GettingStarted]]", map, "");
        assertEquals("[GettingStarted](GettingStarted.md)", result);
    }

    @Test
    void linkWithLabel() {
        Map<String, String> map = Map.of("GettingStarted", "GettingStarted.md");
        String result = rewriter.rewrite("[[GettingStarted|시작하기]]", map, "");
        assertEquals("[시작하기](GettingStarted.md)", result);
    }

    @Test
    void subpageToTopLevel() {
        // Root/Child.md → Other.md: 상위 디렉터리로 나가야 함
        Map<String, String> map = Map.of("Other", "Other.md");
        String result = rewriter.rewrite("[[Other]]", map, "Root");
        assertEquals("[Other](../Other.md)", result);
    }

    @Test
    void subpageToSibling() {
        // Root/Child.md → Root/Sibling.md: 같은 디렉터리
        Map<String, String> map = Map.of("Sibling", "Root/Sibling.md");
        String result = rewriter.rewrite("[[Sibling]]", map, "Root");
        assertEquals("[Sibling](Sibling.md)", result);
    }

    @Test
    void topLevelToSubpage() {
        // Home.md → Root/Child.md
        Map<String, String> map = Map.of("Child", "Root/Child.md");
        String result = rewriter.rewrite("[[Child]]", map, "");
        assertEquals("[Child](Root/Child.md)", result);
    }

    @Test
    void unknownPageFallsBackToSlug() {
        Map<String, String> map = Map.of();
        String result = rewriter.rewrite("[[Missing Page]]", map, "");
        assertEquals("[Missing Page](Missing-Page.md)", result);
    }

    @Test
    void noLinkUnchanged() {
        Map<String, String> map = Map.of();
        String input = "일반 텍스트입니다.";
        assertEquals(input, rewriter.rewrite(input, map, ""));
    }

    @Test
    void anchorOnlyPage() {
        // [[PageName#section]] — 앵커가 파일 경로에 붙어야 함
        Map<String, String> map = Map.of("PageName", "PageName.md");
        String result = rewriter.rewrite("[[PageName#section]]", map, "");
        assertEquals("[PageName](PageName.md#section)", result);
    }

    @Test
    void anchorWithLabel() {
        // [[PageName#section|보러가기]]
        Map<String, String> map = Map.of("PageName", "PageName.md");
        String result = rewriter.rewrite("[[PageName#section|보러가기]]", map, "");
        assertEquals("[보러가기](PageName.md#section)", result);
    }

    @Test
    void anchorFromSubpage() {
        // Root/Child.md → Other.md#sec
        Map<String, String> map = Map.of("Other", "Other.md");
        String result = rewriter.rewrite("[[Other#sec]]", map, "Root");
        assertEquals("[Other](../Other.md#sec)", result);
    }

    @Test
    void anchorUnknownPageFallback() {
        Map<String, String> map = Map.of();
        String result = rewriter.rewrite("[[Missing Page#anchor]]", map, "");
        assertEquals("[Missing Page](Missing-Page.md#anchor)", result);
    }
}
