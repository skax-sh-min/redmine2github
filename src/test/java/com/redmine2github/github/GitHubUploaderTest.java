package com.redmine2github.github;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.*;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * GitHubUploader 단위 테스트.
 * GitHub API는 Mockito로 목킹하여 실제 서버 연결 없이 검증한다.
 */
class GitHubUploaderTest {

    private GHRepository repo;
    private RateLimitAwareExecutor rateLimiter;
    private GitHubUploader uploader;

    @BeforeEach
    void setUp() throws IOException {
        repo        = mock(GHRepository.class);
        rateLimiter = mock(RateLimitAwareExecutor.class);

        // rateLimiter.execute() → Callable 즉시 실행
        when(rateLimiter.execute(any())).thenAnswer(inv -> {
            java.util.concurrent.Callable<?> callable = inv.getArgument(0);
            try { return callable.call(); }
            catch (Exception e) { throw new IOException(e); }
        });
        // rateLimiter.run() → Runnable 즉시 실행
        doAnswer(inv -> {
            RateLimitAwareExecutor.RunnableWithIOException action = inv.getArgument(0);
            action.run();
            return null;
        }).when(rateLimiter).run(any());

        uploader = new GitHubUploader(repo, rateLimiter);
    }

    // ── Label ─────────────────────────────────────────────────────────────────

    @Test
    void createLabel_callsRepoCreateLabel() throws Exception {
        uploader.createLabel("tracker:Bug", "ee0701", "버그 트래커");
        verify(repo).createLabel("tracker:Bug", "ee0701", "버그 트래커");
    }

    @Test
    void createLabel_doesNotThrowOnExistingLabel() throws Exception {
        when(repo.createLabel(anyString(), anyString(), anyString()))
            .thenThrow(new IOException("already exists"));

        // 예외가 밖으로 전파되지 않아야 함 (warn 로그로 처리)
        assertDoesNotThrow(() -> uploader.createLabel("tracker:Bug", "ee0701", ""));
    }

    // ── Milestone ─────────────────────────────────────────────────────────────

    @Test
    void createMilestone_returnsNumber() throws Exception {
        GHMilestone milestone = mock(GHMilestone.class);
        when(milestone.getNumber()).thenReturn(3);
        when(repo.createMilestone(eq("v1.0"), eq("First release"))).thenReturn(milestone);

        int number = uploader.createMilestone("v1.0", "First release", null);

        assertEquals(3, number);
    }

    @Test
    void createMilestone_recordsInMap() throws Exception {
        GHMilestone milestone = mock(GHMilestone.class);
        when(milestone.getNumber()).thenReturn(7);
        when(repo.createMilestone(anyString(), anyString())).thenReturn(milestone);

        uploader.createMilestone("v2.0", "", null);

        Map<String, Integer> map = uploader.getMilestoneIdMap();
        assertEquals(7, map.get("v2.0"));
    }

    // ── Issue ─────────────────────────────────────────────────────────────────

    @Test
    void createIssue_withLabelsAndAssignee() throws Exception {
        GHIssueBuilder builder = mock(GHIssueBuilder.class);
        GHIssue issue = mock(GHIssue.class);
        when(issue.getNumber()).thenReturn(10);

        when(repo.createIssue("Fix login bug")).thenReturn(builder);
        when(builder.body(anyString())).thenReturn(builder);
        when(builder.label(anyString())).thenReturn(builder);
        when(builder.assignee(anyString())).thenReturn(builder);
        when(builder.create()).thenReturn(issue);

        GHIssue result = uploader.createIssue(
            "Fix login bug", "description",
            new String[]{"tracker:Bug", "priority:High"},
            null, "github-user"
        );

        assertNotNull(result);
        assertEquals(10, result.getNumber());
        verify(builder).label("tracker:Bug");
        verify(builder).label("priority:High");
        verify(builder).assignee("github-user");
    }

    @Test
    void createIssue_withMilestone() throws Exception {
        GHIssueBuilder builder = mock(GHIssueBuilder.class);
        GHIssue issue = mock(GHIssue.class);
        GHMilestone milestone = mock(GHMilestone.class);

        when(repo.createIssue(anyString())).thenReturn(builder);
        when(builder.body(anyString())).thenReturn(builder);
        when(builder.milestone(any())).thenReturn(builder);
        when(builder.create()).thenReturn(issue);
        when(repo.getMilestone(5)).thenReturn(milestone);

        uploader.createIssue("Issue with milestone", "", null, 5, null);

        verify(builder).milestone(milestone);
    }

    @Test
    void createIssue_nullAssigneeSkipsAssign() throws Exception {
        GHIssueBuilder builder = mock(GHIssueBuilder.class);
        GHIssue issue = mock(GHIssue.class);

        when(repo.createIssue(anyString())).thenReturn(builder);
        when(builder.body(anyString())).thenReturn(builder);
        when(builder.create()).thenReturn(issue);

        uploader.createIssue("No assignee", "", null, null, null);

        verify(builder, never()).assignee(anyString());
    }

    // ── Comment ───────────────────────────────────────────────────────────────

    @Test
    void addComment_callsIssueComment() throws Exception {
        GHIssue issue = mock(GHIssue.class);

        uploader.addComment(issue, "This is a comment");

        verify(issue).comment("This is a comment");
    }

    @Test
    void addComment_doesNotThrowOnFailure() throws Exception {
        GHIssue issue = mock(GHIssue.class);
        doThrow(new IOException("network error")).when(issue).comment(anyString());

        assertDoesNotThrow(() -> uploader.addComment(issue, "comment"));
    }

    // ── Close Issue ───────────────────────────────────────────────────────────

    @Test
    void closeIssue_callsClose() throws Exception {
        GHIssue issue = mock(GHIssue.class);
        when(issue.getNumber()).thenReturn(42);

        uploader.closeIssue(issue);

        verify(issue).close();
    }

    // ── Rate Limit ────────────────────────────────────────────────────────────

    @Test
    void getRateLimitRemaining_delegatesToRateLimiter() {
        when(rateLimiter.getRemainingRequests()).thenReturn(4500);

        assertEquals(4500, uploader.getRateLimitRemaining());
    }
}
