package com.redmine2github.converter;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class RedmineUrlRewriterTest {

    private static final String BASE = "http://redmine.example.com";

    private final RedmineUrlRewriter rewriter =
            new RedmineUrlRewriter(BASE, Collections.emptyList());

    // ── Wiki URL ──────────────────────────────────────────────────────────────

    @Test
    void wikiUrl_labeled() {
        String input = "[설치 가이드](" + BASE + "/projects/proj/wiki/Installation)";
        String result = rewriter.rewrite(input, "proj", "", null, null);
        assertEquals("[설치 가이드](Installation.md)", result);
    }

    @Test
    void wikiUrl_bare() {
        String input = BASE + "/projects/proj/wiki/Installation";
        String result = rewriter.rewrite(input, "proj", "", null, null);
        assertEquals("[Installation](Installation.md)", result);
    }

    @Test
    void wikiUrl_withAnchor() {
        String input = BASE + "/projects/proj/wiki/Installation#section";
        String result = rewriter.rewrite(input, "proj", "", null, null);
        assertEquals("[Installation](Installation.md#section)", result);
    }

    @Test
    void wikiUrl_crossProject() {
        String input = BASE + "/projects/other/wiki/Page";
        String result = rewriter.rewrite(input, "proj", "", null, null);
        assertEquals("[Page](../../other/wiki/Page.md)", result);
    }

    // ── Issue URL ─────────────────────────────────────────────────────────────

    @Test
    void issueUrl_labeled() {
        String input = "[이슈 #42](" + BASE + "/issues/42)";
        String result = rewriter.rewrite(input, "proj", "", null, null);
        assertEquals("[이슈 #42](#42)", result);
    }

    @Test
    void issueUrl_bare() {
        String input = BASE + "/issues/42";
        String result = rewriter.rewrite(input, "proj", "", null, null);
        assertEquals("#42", result);
    }

    @Test
    void issueUrl_bare_notMatchedWhenFollowedBySlash() {
        // {base}/issues/42/edit 는 단순 이슈 URL이 아니므로 변환하지 않음
        String input = BASE + "/issues/42/edit";
        String result = rewriter.rewrite(input, "proj", "", null, null);
        assertEquals(input, result);
    }

    @Test
    void issueUrl_bare_notMatchedWhenFollowedByHash() {
        // {base}/issues/42#note-5 (노트 앵커) → 변환하지 않음
        String input = BASE + "/issues/42#note-5";
        String result = rewriter.rewrite(input, "proj", "", null, null);
        assertEquals(input, result);
    }

    @Test
    void issueUrl_bare_inSentence() {
        // 문장 중간의 이슈 URL → 변환됨
        String input = "자세한 내용은 " + BASE + "/issues/99 를 참고하세요.";
        String result = rewriter.rewrite(input, "proj", "", null, null);
        assertEquals("자세한 내용은 #99 를 참고하세요.", result);
    }
}
