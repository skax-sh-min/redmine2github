package com.redmine2github.converter;

import org.junit.jupiter.api.Test;

import java.util.List;

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
}
