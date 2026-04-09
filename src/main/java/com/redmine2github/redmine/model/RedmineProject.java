package com.redmine2github.redmine.model;

import com.fasterxml.jackson.databind.JsonNode;

/** Redmine 프로젝트 기본 정보 (id, identifier, name). */
public class RedmineProject {

    private final int    id;
    private final String identifier;
    private final String name;
    private final String description;

    public RedmineProject(int id, String identifier, String name, String description) {
        this.id          = id;
        this.identifier  = identifier;
        this.name        = name;
        this.description = description;
    }

    public static RedmineProject from(JsonNode node) {
        return new RedmineProject(
            node.path("id").asInt(),
            node.path("identifier").asText(),
            node.path("name").asText(),
            node.path("description").asText("")
        );
    }

    public int    getId()          { return id; }
    public String getIdentifier()  { return identifier; }
    public String getName()        { return name; }
    public String getDescription() { return description; }

    @Override
    public String toString() { return identifier + " (" + name + ")"; }
}
