package com.redmine2github.service.model;

/**
 * fetch 단계에서 output/issues/_milestones.json으로 직렬화되는 Milestone 정의 DTO.
 * upload 단계에서 GitHub Milestone 생성 시 사용한다.
 */
public class LocalMilestone {

    private String name;
    private String description;
    private String dueDate;

    public LocalMilestone() {}

    public LocalMilestone(String name, String description, String dueDate) {
        this.name        = name;
        this.description = description;
        this.dueDate     = dueDate;
    }

    public String getName()               { return name; }
    public void setName(String v)         { this.name = v; }

    public String getDescription()        { return description; }
    public void setDescription(String v)  { this.description = v; }

    public String getDueDate()            { return dueDate; }
    public void setDueDate(String v)      { this.dueDate = v; }
}
