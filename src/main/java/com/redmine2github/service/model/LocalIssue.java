package com.redmine2github.service.model;

import java.util.ArrayList;
import java.util.List;

/**
 * fetch 단계에서 로컬 파일(output/issues/{id}.json)로 직렬화되는 Issue DTO.
 * upload 단계에서 이 파일을 읽어 GitHub Issue를 생성한다.
 */
public class LocalIssue {

    private int redmineId;
    private String subject;
    private String body;           // Textile → GFM 변환된 본문
    private List<String> labels = new ArrayList<>();
    private String assignee;       // GitHub 계정명 (null이면 미지정)
    private List<String> comments = new ArrayList<>();  // GFM 변환된 댓글 목록
    private boolean closed;

    // Jackson 역직렬화용 기본 생성자
    public LocalIssue() {}

    public LocalIssue(int redmineId, String subject, String body,
                      List<String> labels, String assignee,
                      List<String> comments, boolean closed) {
        this.redmineId = redmineId;
        this.subject   = subject;
        this.body      = body;
        this.labels    = labels;
        this.assignee  = assignee;
        this.comments  = comments;
        this.closed    = closed;
    }

    public int getRedmineId()          { return redmineId; }
    public void setRedmineId(int v)    { this.redmineId = v; }

    public String getSubject()          { return subject; }
    public void setSubject(String v)    { this.subject = v; }

    public String getBody()             { return body; }
    public void setBody(String v)       { this.body = v; }

    public List<String> getLabels()            { return labels; }
    public void setLabels(List<String> v)      { this.labels = v; }

    public String getAssignee()         { return assignee; }
    public void setAssignee(String v)   { this.assignee = v; }

    public List<String> getComments()          { return comments; }
    public void setComments(List<String> v)    { this.comments = v; }

    public boolean isClosed()           { return closed; }
    public void setClosed(boolean v)    { this.closed = v; }
}
