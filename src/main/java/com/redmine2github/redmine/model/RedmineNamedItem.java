package com.redmine2github.redmine.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Redmine의 트래커(Tracker), 카테고리(IssueCategory), 우선순위(IssuePriority) 등
 * id + name 구조의 단순 열거형 항목을 공통으로 표현한다.
 */
public class RedmineNamedItem {

    private final int id;
    private final String name;

    public RedmineNamedItem(int id, String name) {
        this.id   = id;
        this.name = name;
    }

    public static RedmineNamedItem from(JsonNode node) {
        return new RedmineNamedItem(
            node.path("id").asInt(),
            node.path("name").asText()
        );
    }

    public int getId()      { return id; }
    public String getName() { return name; }

    @Override
    public String toString() { return name + "(" + id + ")"; }
}
