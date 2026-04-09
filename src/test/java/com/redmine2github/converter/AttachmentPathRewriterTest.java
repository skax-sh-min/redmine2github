package com.redmine2github.converter;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentPathRewriterTest {

    private final AttachmentPathRewriter rewriter = new AttachmentPathRewriter();

    @Test
    void rewritesInlineImage() {
        String result = rewriter.rewrite(
            "![screenshot](screenshot.png)",
            List.of("screenshot.png"),
            "../attachments/"
        );
        assertEquals("![screenshot](../attachments/screenshot.png)", result);
    }

    @Test
    void rewritesFileLink() {
        String result = rewriter.rewrite(
            "[spec](spec.pdf)",
            List.of("spec.pdf"),
            "../attachments/"
        );
        assertEquals("[spec](../attachments/spec.pdf)", result);
    }

    @Test
    void rewritesAttachmentPrefixInImage() {
        // TextileConverter가 생성하는 ![](attachment:filename) 패턴 처리
        String result = rewriter.rewrite(
            "![](attachment:diagram.png)",
            List.of("diagram.png"),
            "../attachments/"
        );
        assertEquals("![](../attachments/diagram.png)", result);
    }

    @Test
    void rewritesAttachmentPrefixInLink() {
        // TextileConverter가 생성하는 [file](attachment:filename) 패턴 처리
        String result = rewriter.rewrite(
            "[report](attachment:report.pdf)",
            List.of("report.pdf"),
            "../attachments/"
        );
        assertEquals("[report](../attachments/report.pdf)", result);
    }

    @Test
    void skipsExternalUrl() {
        String input = "![logo](https://example.com/logo.png)";
        String result = rewriter.rewrite(input, List.of("logo.png"), "../attachments/");
        assertEquals(input, result);
    }

    @Test
    void skipsAlreadyRewritten() {
        String input = "![img](../attachments/img.png)";
        String result = rewriter.rewrite(input, List.of("img.png"), "../attachments/");
        assertEquals(input, result);
    }

    @Test
    void skipsNonAttachmentLink() {
        String input = "[other](OtherPage.md)";
        String result = rewriter.rewrite(input, List.of("screenshot.png"), "../attachments/");
        assertEquals(input, result);
    }

    @Test
    void emptyAttachmentListReturnsUnchanged() {
        String input = "![img](img.png)";
        assertEquals(input, rewriter.rewrite(input, List.of(), "../attachments/"));
    }

    @Test
    void rewritesWithIdPrefixedStoredName() {
        // 파일명 충돌로 id 접두사가 붙은 경우 — 원본명으로 참조하되 저장명으로 링크 교체
        Map<String, String> mapping = Map.of("report.pdf", "123_report.pdf");
        String result = rewriter.rewrite(
            "[보고서](report.pdf)",
            mapping,
            "../attachments/"
        );
        assertEquals("[보고서](../attachments/123_report.pdf)", result);
    }

    @Test
    void rewritesImageWithIdPrefixedStoredName() {
        Map<String, String> mapping = Map.of("diagram.png", "456_diagram.png");
        String result = rewriter.rewrite(
            "![다이어그램](diagram.png)",
            mapping,
            "../attachments/"
        );
        assertEquals("![다이어그램](../attachments/456_diagram.png)", result);
    }

    @Test
    void mapBasedRewritePreservesUnmatchedLinks() {
        Map<String, String> mapping = Map.of("known.pdf", "known.pdf");
        String input = "[unknown](unknown.pdf) [known](known.pdf)";
        String result = rewriter.rewrite(input, mapping, "../attachments/");
        assertEquals("[unknown](unknown.pdf) [known](../attachments/known.pdf)", result);
    }
}
