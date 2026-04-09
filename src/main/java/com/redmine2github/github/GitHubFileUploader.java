package com.redmine2github.github;

import com.redmine2github.config.AppConfig;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Wiki, 첨부파일, CSV 등 파일성 데이터를 GitHub Repository에 push한다.
 * 방안 1(API) 또는 방안 2(JGit) 중 AppConfig.uploadMethod로 선택한다.
 */
public class GitHubFileUploader {

    private static final Logger log = LoggerFactory.getLogger(GitHubFileUploader.class);

    private final AppConfig config;
    private final GHRepository repo;
    private final RateLimitAwareExecutor rateLimiter;

    public GitHubFileUploader(AppConfig config, GitHubUploader uploader) {
        this.config      = config;
        this.repo        = uploader.getRepo();
        this.rateLimiter = uploader.getRateLimiter();
    }

    public void uploadFile(Path localFile, String repoPath, String commitMessage) {
        if ("JGIT".equalsIgnoreCase(config.getUploadMethod())) {
            uploadViaJGit(localFile, repoPath, commitMessage);
        } else {
            uploadViaApi(localFile, repoPath, commitMessage);
        }
    }

    // ── 방안 1: GitHub Contents API ──────────────────────────────────────

    private void uploadViaApi(Path localFile, String repoPath, String commitMessage) {
        try {
            byte[] content = Files.readAllBytes(localFile);
            rateLimiter.run(() -> {
                try {
                    GHContent existing = repo.getFileContent(repoPath);
                    repo.createContent()
                            .path(repoPath)
                            .message(commitMessage)
                            .content(content)
                            .sha(existing.getSha())
                            .commit();
                } catch (Exception e) {
                    // 파일이 없으면 신규 생성
                    repo.createContent()
                            .path(repoPath)
                            .message(commitMessage)
                            .content(content)
                            .commit();
                }
                log.info("[API] 업로드: {}", repoPath);
            });
        } catch (IOException e) {
            log.error("[API] 업로드 실패 [{}]: {}", repoPath, e.getMessage(), e);
        }
    }

    // ── 방안 2: JGit ─────────────────────────────────────────────────────

    private void uploadViaJGit(Path localFile, String repoPath, String commitMessage) {
        File tmpDir = new File("tmp/repo");
        try {
            UsernamePasswordCredentialsProvider creds =
                new UsernamePasswordCredentialsProvider(config.getGithubToken(), "");

            Git git = tmpDir.exists()
                ? Git.open(tmpDir)
                : Git.cloneRepository()
                      .setURI("https://github.com/" + config.getGithubRepo() + ".git")
                      .setDirectory(tmpDir)
                      .setDepth(1)
                      .setCredentialsProvider(creds)
                      .call();

            Path dest = tmpDir.toPath().resolve(repoPath);
            Files.createDirectories(dest.getParent());
            Files.copy(localFile, dest, StandardCopyOption.REPLACE_EXISTING);

            git.add().addFilepattern(repoPath).call();
            git.commit().setMessage(commitMessage).call();
            git.push().setCredentialsProvider(creds).call();

            log.info("[JGit] 업로드: {}", repoPath);
        } catch (Exception e) {
            log.error("[JGit] 업로드 실패 [{}]: {}", repoPath, e.getMessage(), e);
        }
    }
}
