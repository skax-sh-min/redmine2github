package com.redmine2github.redmine.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

public class RedmineWikiPage {

    private final String title;
    private final String text;
    private final String parentTitle;
    private final List<RedmineAttachment> attachments;
    private final String authorName;
    private final String createdOn;
    private final String updatedOn;

    public RedmineWikiPage(String title, String text, String parentTitle,
                           List<RedmineAttachment> attachments,
                           String authorName, String createdOn, String updatedOn) {
        this.title       = title;
        this.text        = text;
        this.parentTitle = parentTitle;
        this.attachments = attachments;
        this.authorName  = authorName;
        this.createdOn   = createdOn;
        this.updatedOn   = updatedOn;
    }

    public static RedmineWikiPage from(JsonNode node) {
        String title      = node.path("title").asText();
        String text       = node.path("text").asText("");
        String parent     = node.path("parent").path("title").asText(null);
        String authorName = node.path("author").path("name").asText(null);
        String createdOn  = node.path("created_on").asText(null);
        String updatedOn  = node.path("updated_on").asText(null);

        List<RedmineAttachment> attachments = new ArrayList<>();
        for (JsonNode a : node.path("attachments")) {
            attachments.add(RedmineAttachment.from(a));
        }
        return new RedmineWikiPage(title, text, parent, attachments, authorName, createdOn, updatedOn);
    }

    public String getTitle()            { return title; }
    public String getText()             { return text; }
    public String getParentTitle()      { return parentTitle; }
    public List<RedmineAttachment> getAttachments() { return attachments; }
    public String getAuthorName()       { return authorName; }
    public String getCreatedOn()        { return createdOn; }
    public String getUpdatedOn()        { return updatedOn; }
}
