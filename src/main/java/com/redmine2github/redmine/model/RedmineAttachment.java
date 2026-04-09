package com.redmine2github.redmine.model;

import com.fasterxml.jackson.databind.JsonNode;

public class RedmineAttachment {

    private final int id;
    private final String filename;
    private final String contentUrl;
    private final String contentType;
    private final long filesize;
    /** Redmine이 제공하는 MD5 digest (없으면 빈 문자열). */
    private final String digest;

    public RedmineAttachment(int id, String filename, String contentUrl,
                             String contentType, long filesize, String digest) {
        this.id          = id;
        this.filename    = filename;
        this.contentUrl  = contentUrl;
        this.contentType = contentType;
        this.filesize    = filesize;
        this.digest      = digest != null ? digest : "";
    }

    public static RedmineAttachment from(JsonNode node) {
        return new RedmineAttachment(
            node.path("id").asInt(),
            node.path("filename").asText(),
            node.path("content_url").asText(),
            node.path("content_type").asText(),
            node.path("filesize").asLong(0),
            node.path("digest").asText("")
        );
    }

    public int getId()            { return id; }
    public String getFilename()   { return filename; }
    public String getContentUrl() { return contentUrl; }
    public String getContentType(){ return contentType; }
    public long getFilesize()     { return filesize; }
    public String getDigest()     { return digest; }
}
