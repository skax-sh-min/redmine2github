package com.redmine2github.state;

import java.util.HashSet;
import java.util.Set;

/**
 * 마이그레이션 진행 상태를 나타내는 데이터 모델.
 * migration-state.json으로 직렬화/역직렬화된다.
 *
 * <p>2단계 구조:
 * <ul>
 *   <li><b>fetch 단계</b>: Redmine → 로컬 파일 (fetched* / *Fetched 필드)</li>
 *   <li><b>upload 단계</b>: 로컬 파일 → GitHub (completed* / *Done 필드)</li>
 * </ul>
 */
public class MigrationState {

    // ── fetch 단계 ────────────────────────────────────────────────────────────

    private Set<String>  fetchedWikiPages  = new HashSet<>();
    private Set<Integer> fetchedIssueIds   = new HashSet<>();
    private boolean timeEntriesFetched     = false;
    private boolean labelsFetched          = false;
    private boolean milestonesFetched      = false;

    // ── upload 단계 ───────────────────────────────────────────────────────────

    /** upload 단계: 업로드 완료된 wiki 파일 경로 (예: wiki/GettingStarted.md) */
    private Set<String>  completedWikiPages = new HashSet<>();
    private Set<Integer> completedIssueIds  = new HashSet<>();
    private Set<Integer> failedIssueIds     = new HashSet<>();
    private boolean timeEntriesDone         = false;
    private boolean labelsDone              = false;
    private boolean milestonesDone          = false;

    // ── fetch 단계 메서드 ──────────────────────────────────────────────────────

    public boolean isWikiPageFetched(String title)   { return fetchedWikiPages.contains(title); }
    public void markWikiPageFetched(String title)    { fetchedWikiPages.add(title); }

    public boolean isIssueFetched(int id)            { return fetchedIssueIds.contains(id); }
    public void markIssueFetched(int id)             { fetchedIssueIds.add(id); }

    public boolean isTimeEntriesFetched()            { return timeEntriesFetched; }
    public void markTimeEntriesFetched()             { this.timeEntriesFetched = true; }

    public boolean isLabelsFetched()                 { return labelsFetched; }
    public void markLabelsFetched()                  { this.labelsFetched = true; }

    public boolean isMilestonesFetched()             { return milestonesFetched; }
    public void markMilestonesFetched()              { this.milestonesFetched = true; }

    // ── upload 단계 메서드 ─────────────────────────────────────────────────────

    public boolean isWikiPageDone(String repoPath)   { return completedWikiPages.contains(repoPath); }
    public void markWikiPageDone(String repoPath)    { completedWikiPages.add(repoPath); }

    public boolean isIssueDone(int id)               { return completedIssueIds.contains(id); }
    public void markIssueDone(int id)                { completedIssueIds.add(id); failedIssueIds.remove(id); }
    public void markIssueFailed(int id)              { failedIssueIds.add(id); }
    public Set<Integer> getFailedIssueIds()          { return failedIssueIds; }

    public boolean isTimeEntriesDone()               { return timeEntriesDone; }
    public void markTimeEntriesDone()                { this.timeEntriesDone = true; }

    public boolean isLabelsDone()                    { return labelsDone; }
    public void markLabelsDone()                     { this.labelsDone = true; }

    public boolean isMilestonesDone()                { return milestonesDone; }
    public void markMilestonesDone()                 { this.milestonesDone = true; }

    // ── Jackson 직렬화용 getter/setter ────────────────────────────────────────

    public Set<String>  getFetchedWikiPages()              { return fetchedWikiPages; }
    public void setFetchedWikiPages(Set<String> s)         { fetchedWikiPages = s; }

    public Set<Integer> getFetchedIssueIds()               { return fetchedIssueIds; }
    public void setFetchedIssueIds(Set<Integer> s)         { fetchedIssueIds = s; }

    public boolean getTimeEntriesFetched()                 { return timeEntriesFetched; }
    public void setTimeEntriesFetched(boolean b)           { timeEntriesFetched = b; }

    public boolean getLabelsFetched()                      { return labelsFetched; }
    public void setLabelsFetched(boolean b)                { labelsFetched = b; }

    public boolean getMilestonesFetched()                  { return milestonesFetched; }
    public void setMilestonesFetched(boolean b)            { milestonesFetched = b; }

    public Set<String>  getCompletedWikiPages()            { return completedWikiPages; }
    public void setCompletedWikiPages(Set<String> s)       { completedWikiPages = s; }

    public Set<Integer> getCompletedIssueIds()             { return completedIssueIds; }
    public void setCompletedIssueIds(Set<Integer> s)       { completedIssueIds = s; }

    public Set<Integer> getFailedIssueIds2()               { return failedIssueIds; }
    public void setFailedIssueIds(Set<Integer> s)          { failedIssueIds = s; }

    public boolean getTimeEntriesDone()                    { return timeEntriesDone; }
    public void setTimeEntriesDone(boolean b)              { timeEntriesDone = b; }

    public boolean getLabelsDone()                         { return labelsDone; }
    public void setLabelsDone(boolean b)                   { labelsDone = b; }

    public boolean getMilestonesDone()                     { return milestonesDone; }
    public void setMilestonesDone(boolean b)               { milestonesDone = b; }
}
