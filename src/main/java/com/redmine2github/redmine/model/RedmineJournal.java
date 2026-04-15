package com.redmine2github.redmine.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

public class RedmineJournal {

    private final int id;
    private final String authorLogin;
    private final String notes;
    private final String createdOn;
    private final List<RedmineJournalDetail> details;

    public RedmineJournal(int id, String authorLogin, String notes, String createdOn,
                          List<RedmineJournalDetail> details) {
        this.id          = id;
        this.authorLogin = authorLogin;
        this.notes       = notes;
        this.createdOn   = createdOn;
        this.details     = details;
    }

    public static RedmineJournal from(JsonNode node) {
        List<RedmineJournalDetail> details = new ArrayList<>();
        for (JsonNode d : node.path("details")) {
            details.add(RedmineJournalDetail.from(d));
        }
        return new RedmineJournal(
            node.path("id").asInt(),
            node.path("user").path("login").asText("unknown"),
            node.path("notes").asText(""),
            node.path("created_on").asText(),
            details
        );
    }

    public int getId()                              { return id; }
    public String getAuthorLogin()                  { return authorLogin; }
    public String getNotes()                        { return notes; }
    public String getCreatedOn()                    { return createdOn; }
    public List<RedmineJournalDetail> getDetails()  { return details; }

    /** 표시할 내용이 있으면 true (노트 또는 필드 변경 이력). */
    public boolean hasContent() {
        return !notes.isBlank() || !details.isEmpty();
    }
}
