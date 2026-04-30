package com.redmine2github.github;

import org.kohsuke.github.GitHub;
import org.kohsuke.github.GHRateLimit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.Callable;

/**
 * GitHub API 호출에 Rate Limit 감지 및 exponential backoff 재시도를 적용한다.
 *
 * <h3>동작 방식</h3>
 * <ol>
 *   <li>API 호출 전 남은 요청 수({@code X-RateLimit-Remaining})를 확인해
 *       임계값({@value #REMAINING_THRESHOLD}) 이하이면 리셋 시각까지 대기한다.</li>
 *   <li>호출 중 예외가 발생하고 메시지에 "rate limit"이 포함되면
 *       exponential backoff({@code baseDelay × 2^attempt})로 최대 {@value #MAX_RETRIES}회 재시도한다.</li>
 *   <li>Rate Limit 무관 예외는 즉시 재던진다.</li>
 * </ol>
 */
public class RateLimitAwareExecutor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAwareExecutor.class);

    /** 남은 요청 수가 이 값 이하이면 사전 대기한다. */
    private static final int REMAINING_THRESHOLD = 10;
    /** 최대 재시도 횟수 */
    private static final int MAX_RETRIES = 5;
    /** 초기 대기 시간 (ms) — 재시도마다 2배 */
    private static final long BASE_DELAY_MS = 1_000L;
    /** 최대 대기 시간 (ms) */
    private static final long MAX_DELAY_MS = 60_000L;

    private final GitHub github;
    private final long requestDelayMs;

    public RateLimitAwareExecutor(GitHub github) {
        this(github, 0L);
    }

    public RateLimitAwareExecutor(GitHub github, long requestDelayMs) {
        this.github = github;
        this.requestDelayMs = requestDelayMs;
    }

    /**
     * {@code action}을 실행한다. Rate Limit 초과 시 대기 후 재시도한다.
     *
     * @param <T>    반환 타입
     * @param action 실행할 GitHub API 호출
     * @return action의 반환값
     * @throws IOException Rate Limit 외의 I/O 오류
     */
    public <T> T execute(Callable<T> action) throws IOException {
        throttle();
        checkRateLimitBefore();

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return action.call();
            } catch (Exception e) {
                if (!isRateLimitException(e)) {
                    if (e instanceof IOException ioe) throw ioe;
                    throw new IOException(e.getMessage(), e);
                }

                if (attempt == MAX_RETRIES) {
                    throw new IOException("Rate Limit 재시도 횟수 초과 (" + MAX_RETRIES + "회)", e);
                }

                long waitMs = Math.min(BASE_DELAY_MS * (1L << attempt), MAX_DELAY_MS);
                log.warn("Rate Limit 감지 — {}ms 후 재시도 ({}/{})", waitMs, attempt + 1, MAX_RETRIES);
                sleep(waitMs);
            }
        }
        throw new IOException("execute: unreachable");
    }

    /**
     * 반환값이 없는 API 호출용 편의 메서드.
     */
    public void run(RunnableWithIOException action) throws IOException {
        execute(() -> { action.run(); return null; });
    }

    // ── 내부 ──────────────────────────────────────────────────────────────

    private void throttle() {
        if (requestDelayMs > 0) {
            sleep(requestDelayMs);
        }
    }

    /**
     * 호출 전 남은 요청 수를 확인해 임계값 이하이면 리셋 시각까지 대기한다.
     */
    private void checkRateLimitBefore() {
        try {
            GHRateLimit rateLimit = github.getRateLimit();
            int remaining = rateLimit.getCore().getRemaining();

            if (remaining <= REMAINING_THRESHOLD) {
                long resetEpoch  = rateLimit.getCore().getResetDate().getTime();
                long nowMs       = System.currentTimeMillis();
                long waitMs      = Math.max(0, resetEpoch - nowMs) + 1_000L; // 1초 여유

                log.warn("Rate Limit 임박 (남은 횟수: {}) — {}초 대기 중...",
                        remaining, waitMs / 1000);
                sleep(waitMs);
            }
        } catch (IOException e) {
            log.warn("Rate Limit 조회 실패 (무시): {}", e.getMessage());
        }
    }

    private boolean isRateLimitException(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("rate limit")
            || lower.contains("api rate")
            || lower.contains("429");
    }

    /**
     * 현재 남은 API 요청 횟수를 반환한다. 조회 실패 시 {@code -1}을 반환한다.
     */
    public int getRemainingRequests() {
        try {
            return github.getRateLimit().getCore().getRemaining();
        } catch (IOException e) {
            log.warn("Rate Limit 조회 실패: {}", e.getMessage());
            return -1;
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    public interface RunnableWithIOException {
        void run() throws IOException;
    }
}
