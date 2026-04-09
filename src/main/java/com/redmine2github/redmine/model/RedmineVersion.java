package com.redmine2github.redmine.model;

import com.fasterxml.jackson.databind.JsonNode;

public class RedmineVersion {

    private final int id;
    private final String name;
    private final String description;
    private final String dueDate;
    private final String status;

    public RedmineVersion(int id, String name, String description, String dueDate, String status) {
        this.id          = id;
        this.name        = name;
        this.description = description;
        this.dueDate     = dueDate;
        this.status      = status;
    }

    public static RedmineVersion from(JsonNode node) {
        return new RedmineVersion(
            node.path("id").asInt(),
            node.path("name").asText(),
            node.path("description").asText(""),
            node.path("due_date").asText(null),
            node.path("status").asText("open")
        );
    }

    public int getId()            { return id; }
    public String getName()       { return name; }
    public String getDescription(){ return description; }
    public String getDueDate()    { return dueDate; }
    public String getStatus()     { return status; }
}
