package com.redmine2github.redmine.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

public class RedmineIssue {

    private final int id;
    private final String subject;
    private final String description;
    private final String status;
    private final String tracker;
    private final String priority;
    private final String category;
    private final String assigneeLogin;
    private final Integer versionId;
    private final String createdOn;
    private final List<RedmineJournal> journals;
    private final List<RedmineAttachment> attachments;

    public RedmineIssue(int id, String subject, String description, String status,
                        String tracker, String priority, String category,
                        String assigneeLogin, Integer versionId, String createdOn,
                        List<RedmineJournal> journals, List<RedmineAttachment> attachments) {
        this.id            = id;
        this.subject       = subject;
        this.description   = description;
        this.status        = status;
        this.tracker       = tracker;
        this.priority      = priority;
        this.category      = category;
        this.assigneeLogin = assigneeLogin;
        this.versionId     = versionId;
        this.createdOn     = createdOn;
        this.journals      = journals;
        this.attachments   = attachments;
    }

    public static RedmineIssue from(JsonNode node) {
        List<RedmineJournal> journals = new ArrayList<>();
        for (JsonNode j : node.path("journals")) journals.add(RedmineJournal.from(j));

        List<RedmineAttachment> attachments = new ArrayList<>();
        for (JsonNode a : node.path("attachments")) attachments.add(RedmineAttachment.from(a));

        Integer versionId = node.path("fixed_version").isMissingNode() ? null
                          : node.path("fixed_version").path("id").asInt();

        return new RedmineIssue(
            node.path("id").asInt(),
            node.path("subject").asText(),
            node.path("description").asText(""),
            node.path("status").path("name").asText(),
            node.path("tracker").path("name").asText(),
            node.path("priority").path("name").asText(),
            node.path("category").path("name").asText(null),
            node.path("assigned_to").path("login").asText(null),
            versionId,
            node.path("created_on").asText(),
            journals,
            attachments
        );
    }

    public int getId()                { return id; }
    public String getSubject()        { return subject; }
    public String getDescription()    { return description; }
    public String getStatus()         { return status; }
    public String getTracker()        { return tracker; }
    public String getPriority()       { return priority; }
    public String getCategory()       { return category; }
    public String getAssigneeLogin()  { return assigneeLogin; }
    public Integer getVersionId()     { return versionId; }
    public String getCreatedOn()      { return createdOn; }
    public List<RedmineJournal> getJournals()         { return journals; }
    public List<RedmineAttachment> getAttachments()   { return attachments; }
}
