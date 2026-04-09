package com.redmine2github.redmine.model;

import com.fasterxml.jackson.databind.JsonNode;

public class RedmineAttachment {

    private final int id;
    private final String filename;
    private final String contentUrl;
    private final String contentType;

    public RedmineAttachment(int id, String filename, String contentUrl, String contentType) {
        this.id          = id;
        this.filename    = filename;
        this.contentUrl  = contentUrl;
        this.contentType = contentType;
    }

    public static RedmineAttachment from(JsonNode node) {
        return new RedmineAttachment(
            node.path("id").asInt(),
            node.path("filename").asText(),
            node.path("content_url").asText(),
            node.path("content_type").asText()
        );
    }

    public int getId()            { return id; }
    public String getFilename()   { return filename; }
    public String getContentUrl() { return contentUrl; }
    public String getContentType(){ return contentType; }
}
