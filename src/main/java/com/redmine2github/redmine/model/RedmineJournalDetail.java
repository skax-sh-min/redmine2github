package com.redmine2github.redmine.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Redmine Journal의 필드 변경 이력 항목.
 * property: "attr" (속성), "attachment" (첨부파일), "cf" (커스텀 필드) 등
 */
public class RedmineJournalDetail {

    private final String property;
    private final String name;
    private final String oldValue;
    private final String newValue;

    public RedmineJournalDetail(String property, String name, String oldValue, String newValue) {
        this.property = property;
        this.name     = name;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public static RedmineJournalDetail from(JsonNode node) {
        return new RedmineJournalDetail(
            node.path("property").asText(""),
            node.path("name").asText(""),
            node.path("old_value").isNull() ? null : node.path("old_value").asText(null),
            node.path("new_value").isNull() ? null : node.path("new_value").asText(null)
        );
    }

    public String getProperty() { return property; }
    public String getName()     { return name; }
    public String getOldValue() { return oldValue; }
    public String getNewValue() { return newValue; }
}
