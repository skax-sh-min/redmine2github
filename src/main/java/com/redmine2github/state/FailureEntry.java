package com.redmine2github.state;

/** failures.jsonl 한 줄에 대응하는 실패 항목 모델. */
public class FailureEntry {

    private String ts;
    private String type;
    private String id;
    private String phase;
    private String reason;
    private String detail;
    private int    attempt;

    public FailureEntry() {}

    public FailureEntry(String ts, String type, String id, String phase,
                        String reason, String detail, int attempt) {
        this.ts      = ts;
        this.type    = type;
        this.id      = id;
        this.phase   = phase;
        this.reason  = reason;
        this.detail  = detail;
        this.attempt = attempt;
    }

    /**
     * 재시도해도 같은 오류가 반복되는 영구 실패 원인이면 false.
     * http_403(권한 없음), path_invalid(경로 불법 문자), size_exceeded(파일 크기 초과)는 재시도 무의미.
     */
    public boolean isRetryable() {
        return switch (reason == null ? "unknown" : reason) {
            case "http_403", "path_invalid", "size_exceeded" -> false;
            default -> true;
        };
    }

    public String getTs()     { return ts; }
    public void   setTs(String ts)         { this.ts = ts; }
    public String getType()   { return type; }
    public void   setType(String type)     { this.type = type; }
    public String getId()     { return id; }
    public void   setId(String id)         { this.id = id; }
    public String getPhase()  { return phase; }
    public void   setPhase(String phase)   { this.phase = phase; }
    public String getReason() { return reason; }
    public void   setReason(String reason) { this.reason = reason; }
    public String getDetail() { return detail; }
    public void   setDetail(String detail) { this.detail = detail; }
    public int    getAttempt(){ return attempt; }
    public void   setAttempt(int attempt)  { this.attempt = attempt; }
}
