package com.redmine2github.redmine.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

public class RedmineWikiPage {

    private final String title;
    private final String text;
    private final String parentTitle;
    private final List<RedmineAttachment> attachments;

    public RedmineWikiPage(String title, String text, String parentTitle,
                           List<RedmineAttachment> attachments) {
        this.title       = title;
        this.text        = text;
        this.parentTitle = parentTitle;
        this.attachments = attachments;
    }

    public static RedmineWikiPage from(JsonNode node) {
        String title  = node.path("title").asText();
        String text   = node.path("text").asText("");
        String parent = node.path("parent").path("title").asText(null);

        List<RedmineAttachment> attachments = new ArrayList<>();
        for (JsonNode a : node.path("attachments")) {
            attachments.add(RedmineAttachment.from(a));
        }
        return new RedmineWikiPage(title, text, parent, attachments);
    }

    public String getTitle()            { return title; }
    public String getText()             { return text; }
    public String getParentTitle()      { return parentTitle; }
    public List<RedmineAttachment> getAttachments() { return attachments; }
}
