package com.redmine2github.github;

import com.redmine2github.config.AppConfig;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Wiki, 첨부파일, CSV 등 파일성 데이터를 GitHub Repository에 push한다.
 * 방안 1(API) 또는 방안 2(JGit) 중 AppConfig.uploadMethod로 선택한다.
 *
 * <p>JGit 배치 업로드는 {@link #beginJGitSession()}으로 세션을 열어
 * 여러 파일을 staging한 뒤 {@code commit()} + {@code push()}를 한 번만 호출한다.
 * N 파일 = 1 commit + 1 push.</p>
 */
public class GitHubFileUploader {

    private static final Logger log = LoggerFactory.getLogger(GitHubFileUploader.class);
    private static final String TMP_REPO = "tmp/repo";

    private final AppConfig config;
    private final GHRepository repo;
    private final RateLimitAwareExecutor rateLimiter;

    public GitHubFileUploader(AppConfig config, GitHubUploader uploader) {
        this.config      = config;
        this.repo        = uploader.getRepo();
        this.rateLimiter = uploader.getRateLimiter();
    }

    // ── 단일 파일 업로드 ─────────────────────────────────────────────────

    public void uploadFile(Path localFile, String repoPath, String commitMessage) {
        long maxKb = config.getUploadMaxFileSizeKb();
        if (maxKb > 0) {
            try {
                long fileSizeKb = Files.size(localFile) / 1024;
                if (fileSizeKb > maxKb) {
                    log.warn("[SKIP] 파일 크기 초과 ({}KB > 제한 {}KB): {}", fileSizeKb, maxKb, repoPath);
                    throw new IllegalArgumentException(
                        "size_exceeded: " + fileSizeKb + "KB > " + maxKb + "KB [" + repoPath + "]");
                }
            } catch (IOException e) {
                log.warn("파일 크기 확인 실패 [{}]: {}", repoPath, e.getMessage());
            }
        }

        if ("JGIT".equalsIgnoreCase(config.getUploadMethod())) {
            uploadViaJGit(localFile, repoPath, commitMessage);
        } else {
            uploadViaApi(localFile, repoPath, commitMessage);
        }
    }

    /** 현재 설정이 JGit 업로드 모드인지 반환한다. */
    public boolean isJGitMode() {
        return "JGIT".equalsIgnoreCase(config.getUploadMethod());
    }

    // ── JGit 배치 세션 ────────────────────────────────────────────────────

    /**
     * JGit 배치 업로드 세션을 시작한다.
     * N 파일을 staging한 뒤 {@code commit()} + {@code push()}를 한 번 호출해
     * 1 commit + 1 push 로 처리한다.
     *
     * <pre>{@code
     * try (var session = fileUploader.beginJGitSession()) {
     *     for (Path f : files) {
     *         try { session.stage(f, repoPath); }
     *         catch (Exception e) { // 파일별 실패 처리 }
     *     }
     *     if (session.hasStaged()) {
     *         session.commit("migrate: " + session.getStagedCount() + " files");
     *         session.push();
     *     }
     * }
     * }</pre>
     */
    public JGitSession beginJGitSession() throws Exception {
        File tmpDir = new File(TMP_REPO);
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
        return new JGitSession(git, tmpDir.toPath(), creds, config.getUploadMaxFileSizeKb());
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
                } catch (GHFileNotFoundException e) {
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
            throw new RuntimeException("[API] 업로드 실패 [" + repoPath + "]: " + e.getMessage(), e);
        }
    }

    // ── 방안 2: JGit 단일 파일 ───────────────────────────────────────────

    private void uploadViaJGit(Path localFile, String repoPath, String commitMessage) {
        try (JGitSession session = beginJGitSession()) {
            session.stage(localFile, repoPath);
            session.commit(commitMessage);
            session.push();
            log.info("[JGit] 업로드: {}", repoPath);
        } catch (Exception e) {
            throw new RuntimeException("[JGit] 업로드 실패 [" + repoPath + "]: " + e.getMessage(), e);
        }
    }

    // ── JGit 배치 세션 클래스 ─────────────────────────────────────────────

    public static final class JGitSession implements AutoCloseable {

        private static final Logger log = LoggerFactory.getLogger(JGitSession.class);

        private final Git git;
        private final Path repoRoot;
        private final UsernamePasswordCredentialsProvider creds;
        private final long maxFileSizeKb;
        private final List<String> stagedPaths = new ArrayList<>();

        JGitSession(Git git, Path repoRoot, UsernamePasswordCredentialsProvider creds, long maxFileSizeKb) {
            this.git           = git;
            this.repoRoot      = repoRoot;
            this.creds         = creds;
            this.maxFileSizeKb = maxFileSizeKb;
        }

        /**
         * 파일을 로컬 git 인덱스에 추가한다.
         *
         * @throws IllegalArgumentException 파일 크기 제한 초과 시
         * @throws Exception                JGit / IO 오류 시
         */
        public void stage(Path localFile, String repoPath) throws Exception {
            if (maxFileSizeKb > 0) {
                long kb = Files.size(localFile) / 1024;
                if (kb > maxFileSizeKb) {
                    throw new IllegalArgumentException(
                        "size_exceeded: " + kb + "KB > " + maxFileSizeKb + "KB [" + repoPath + "]");
                }
            }
            Path dest = repoRoot.resolve(repoPath);
            Files.createDirectories(dest.getParent());
            Files.copy(localFile, dest, StandardCopyOption.REPLACE_EXISTING);
            git.add().addFilepattern(repoPath).call();
            stagedPaths.add(repoPath);
            log.debug("[JGit] staged: {}", repoPath);
        }

        /** staged 파일이 있을 때만 commit한다. */
        public void commit(String message) throws Exception {
            if (stagedPaths.isEmpty()) return;
            git.commit().setMessage(message).call();
            log.info("[JGit] committed {} files: {}", stagedPaths.size(), message);
        }

        public void push() throws Exception {
            git.push().setCredentialsProvider(creds).call();
            log.info("[JGit] pushed");
        }

        public boolean hasStaged() {
            return !stagedPaths.isEmpty();
        }

        public int getStagedCount() {
            return stagedPaths.size();
        }

        public List<String> getStagedPaths() {
            return Collections.unmodifiableList(stagedPaths);
        }

        @Override
        public void close() {
            git.close();
        }
    }
}
