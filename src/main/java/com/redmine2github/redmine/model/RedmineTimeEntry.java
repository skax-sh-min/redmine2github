package com.redmine2github.redmine.model;

import com.fasterxml.jackson.databind.JsonNode;

public class RedmineTimeEntry {

    private final int id;
    private final int issueId;
    private final String userLogin;
    private final double hours;
    private final String activity;
    private final String comment;
    private final String spentOn;

    public RedmineTimeEntry(int id, int issueId, String userLogin, double hours,
                            String activity, String comment, String spentOn) {
        this.id        = id;
        this.issueId   = issueId;
        this.userLogin = userLogin;
        this.hours     = hours;
        this.activity  = activity;
        this.comment   = comment;
        this.spentOn   = spentOn;
    }

    public static RedmineTimeEntry from(JsonNode node) {
        return new RedmineTimeEntry(
            node.path("id").asInt(),
            node.path("issue").path("id").asInt(),
            node.path("user").path("login").asText("unknown"),
            node.path("hours").asDouble(),
            node.path("activity").path("name").asText(""),
            node.path("comments").asText(""),
            node.path("spent_on").asText()
        );
    }

    public int getId()          { return id; }
    public int getIssueId()     { return issueId; }
    public String getUserLogin(){ return userLogin; }
    public double getHours()    { return hours; }
    public String getActivity() { return activity; }
    public String getComment()  { return comment; }
    public String getSpentOn()  { return spentOn; }
}
