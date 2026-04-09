package com.redmine2github.redmine.model;

import com.fasterxml.jackson.databind.JsonNode;

public class RedmineJournal {

    private final int id;
    private final String authorLogin;
    private final String notes;
    private final String createdOn;

    public RedmineJournal(int id, String authorLogin, String notes, String createdOn) {
        this.id          = id;
        this.authorLogin = authorLogin;
        this.notes       = notes;
        this.createdOn   = createdOn;
    }

    public static RedmineJournal from(JsonNode node) {
        return new RedmineJournal(
            node.path("id").asInt(),
            node.path("user").path("login").asText("unknown"),
            node.path("notes").asText(""),
            node.path("created_on").asText()
        );
    }

    public int getId()            { return id; }
    public String getAuthorLogin(){ return authorLogin; }
    public String getNotes()      { return notes; }
    public String getCreatedOn()  { return createdOn; }
}
