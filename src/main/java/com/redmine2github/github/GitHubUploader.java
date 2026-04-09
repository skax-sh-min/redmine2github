package com.redmine2github.github;

import com.redmine2github.config.AppConfig;
import org.kohsuke.github.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * GitHub Issues API를 통해 Label, Milestone, Issue, Comment를 생성한다.
 * 모든 API 호출은 {@link RateLimitAwareExecutor}를 통해 Rate Limit 감지 및
 * exponential backoff 재시도가 적용된다.
 */
public class GitHubUploader {

    private static final Logger log = LoggerFactory.getLogger(GitHubUploader.class);

    private final GHRepository repo;
    private final RateLimitAwareExecutor rateLimiter;
    private final Map<String, Integer> milestoneIdMap = new HashMap<>();

    public GitHubUploader(AppConfig config) {
        try {
            GitHub github = new GitHubBuilder()
                    .withOAuthToken(config.getGithubToken())
                    .build();
            this.repo        = github.getRepository(config.getGithubRepo());
            this.rateLimiter = new RateLimitAwareExecutor(github, config.getRequestDelayMs());
        } catch (IOException e) {
            throw new RuntimeException("GitHub 연결 실패: " + e.getMessage(), e);
        }
    }

    /** 테스트용: 의존성을 직접 주입한다. */
    GitHubUploader(GHRepository repo, RateLimitAwareExecutor rateLimiter) {
        this.repo        = repo;
        this.rateLimiter = rateLimiter;
    }

    public void createLabel(String name, String color, String description) {
        try {
            rateLimiter.run(() -> {
                repo.createLabel(name, color, description);
                log.info("Label 생성: {}", name);
            });
        } catch (IOException e) {
            log.warn("Label 이미 존재하거나 생성 실패 [{}]: {}", name, e.getMessage());
        }
    }

    public int createMilestone(String title, String description, String dueOn) {
        try {
            return rateLimiter.execute(() -> {
                GHMilestone ms = repo.createMilestone(title, description);
                milestoneIdMap.put(title, ms.getNumber());
                log.info("Milestone 생성: {}", title);
                return ms.getNumber();
            });
        } catch (IOException e) {
            throw new RuntimeException("Milestone 생성 실패: " + title, e);
        }
    }

    public GHIssue createIssue(String title, String body, String[] labels,
                               Integer milestoneNumber, String assignee) {
        try {
            return rateLimiter.execute(() -> {
                GHIssueBuilder builder = repo.createIssue(title).body(body);
                if (labels != null) for (String l : labels) builder.label(l);
                if (milestoneNumber != null) builder.milestone(repo.getMilestone(milestoneNumber));
                if (assignee != null && !assignee.isBlank()) builder.assignee(assignee);
                GHIssue issue = builder.create();
                log.info("Issue 생성: #{} {}", issue.getNumber(), title);
                return issue;
            });
        } catch (IOException e) {
            throw new RuntimeException("Issue 생성 실패: " + title, e);
        }
    }

    public void addComment(GHIssue issue, String body) {
        try {
            rateLimiter.run(() -> issue.comment(body));
        } catch (IOException e) {
            log.warn("Comment 추가 실패 [Issue #{}]: {}", issue.getNumber(), e.getMessage());
        }
    }

    public void closeIssue(GHIssue issue) {
        try {
            rateLimiter.run(issue::close);
        } catch (IOException e) {
            log.warn("Issue 닫기 실패 [#{}]: {}", issue.getNumber(), e.getMessage());
        }
    }

    /** 현재 GitHub API Rate Limit 잔여 횟수를 반환한다. 조회 실패 시 {@code -1}. */
    public int getRateLimitRemaining() { return rateLimiter.getRemainingRequests(); }

    public Map<String, Integer> getMilestoneIdMap() { return milestoneIdMap; }
    public GHRepository getRepo()                   { return repo; }
    public RateLimitAwareExecutor getRateLimiter()  { return rateLimiter; }
}
