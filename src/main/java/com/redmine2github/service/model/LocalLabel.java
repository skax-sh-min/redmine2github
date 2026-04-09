package com.redmine2github.service.model;

/**
 * fetch 단계에서 output/issues/_labels.json으로 직렬화되는 Label 정의 DTO.
 * upload 단계에서 GitHub Label 생성 시 사용한다.
 */
public class LocalLabel {

    private String name;
    private String color;
    private String description;

    public LocalLabel() {}

    public LocalLabel(String name, String color, String description) {
        this.name        = name;
        this.color       = color;
        this.description = description;
    }

    public String getName()               { return name; }
    public void setName(String v)         { this.name = v; }

    public String getColor()              { return color; }
    public void setColor(String v)        { this.color = v; }

    public String getDescription()        { return description; }
    public void setDescription(String v)  { this.description = v; }
}
