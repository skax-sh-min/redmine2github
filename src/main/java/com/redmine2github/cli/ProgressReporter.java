package com.redmine2github.cli;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 마이그레이션 진행 상황을 콘솔(System.out)에 실시간으로 출력한다.
 *
 * <p>로그(SLF4J)는 파일/상세용이고, 이 클래스는 사용자가 터미널에서
 * 한눈에 파악할 수 있는 요약 진행 표시를 담당한다.</p>
 */
public class ProgressReporter {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String section;   // "Wiki", "Issues", "Time Entries"
    private int total   = 0;
    private int done    = 0;
    private int skipped = 0;
    private int failed  = 0;

    public ProgressReporter(String section) {
        this.section = section;
    }

    /** 처리 대상 총 건수를 설정하고 시작 메시지를 출력한다. */
    public void start(int total) {
        this.total = total;
        print(String.format("[%s] 시작 — 총 %d건", section, total));
    }

    /** 항목 하나 처리 완료 시 호출한다. */
    public void itemDone(String name) {
        done++;
        printProgress("✓", name, null);
    }

    /** 항목 하나 스킵(이미 완료) 시 호출한다. */
    public void itemSkipped(String name) {
        skipped++;
        // 스킵은 verbose하게 출력하지 않음 (배치에서 불필요한 노이즈 방지)
    }

    /** 항목 처리 실패 시 호출한다. */
    public void itemFailed(String name, String reason) {
        failed++;
        printProgress("✗", name, "실패: " + reason);
    }

    /**
     * Rate Limit 잔여 횟수를 인라인으로 표시한다.
     * {@code remaining == -1}이면 조회 불가 상태로 표시한다.
     */
    public void reportRateLimit(int remaining) {
        if (remaining < 0) {
            print(String.format("[%s] GitHub Rate Limit: 조회 불가", section));
        } else {
            print(String.format("[%s] GitHub Rate Limit 잔여: %d회", section, remaining));
        }
    }

    /** 섹션 완료 요약을 출력한다. */
    public void finish() {
        print(String.format("[%s] 완료 — 성공: %d / 스킵: %d / 실패: %d / 전체: %d",
                section, done, skipped, failed, total));
    }

    // ── 통계 조회 ─────────────────────────────────────────────────────────

    public String getSection() { return section; }
    public int    getTotal()   { return total; }
    public int    getDone()    { return done; }
    public int    getFailed()  { return failed; }
    public int    getSkipped() { return skipped; }

    // ── 내부 ──────────────────────────────────────────────────────────────

    private void printProgress(String icon, String name, String extra) {
        String base = String.format("[%s] %s (%d/%d) %s",
                section, icon, done + failed, total, name);
        if (extra != null) base += " — " + extra;
        print(base);
    }

    private void print(String msg) {
        System.out.printf("%s  %s%n", LocalTime.now().format(TIME_FMT), msg);
    }
}
