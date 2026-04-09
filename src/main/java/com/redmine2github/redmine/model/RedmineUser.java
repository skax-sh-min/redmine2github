package com.redmine2github.redmine.model;

import com.fasterxml.jackson.databind.JsonNode;

public class RedmineUser {

    private final int id;
    private final String login;
    private final String firstname;
    private final String lastname;

    public RedmineUser(int id, String login, String firstname, String lastname) {
        this.id        = id;
        this.login     = login;
        this.firstname = firstname;
        this.lastname  = lastname;
    }

    public static RedmineUser from(JsonNode node) {
        return new RedmineUser(
            node.path("id").asInt(),
            node.path("login").asText(),
            node.path("firstname").asText(""),
            node.path("lastname").asText("")
        );
    }

    public int getId()          { return id; }
    public String getLogin()    { return login; }
    public String getFirstname(){ return firstname; }
    public String getLastname() { return lastname; }
}
