package com.redmine2github.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 실패 항목을 {@code _migration/failures.jsonl} 에 append-only로 기록하고 읽어오는 관리자.
 *
 * <p>{@link #append}는 호출 즉시 파일에 기록하므로 프로세스 중단 시에도 손실되지 않는다.
 * {@link #loadNonRetryableIds}는 {@code --retry-failed} 실행 시 재시도가 무의미한
 * 항목을 건너뛰는 데 활용된다.
 */
public class FailureLog {

    private static final Logger log = LoggerFactory.getLogger(FailureLog.class);
    private static final String FILENAME = "failures.jsonl";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path filePath;

    /** @param outputDir 프로젝트 출력 루트 디렉터리 (예: {@code output/proj-a}) */
    public FailureLog(Path outputDir) {
        this.filePath = outputDir.resolve("_migration").resolve(FILENAME);
    }

    /**
     * 실패 항목을 JSONL 형식으로 한 줄 추가한다. detail에서 reason을 자동 분류한다.
     *
     * @param type   실패 유형 (예: "wiki", "issue-upload", "attachment-download")
     * @param id     항목 식별자 (페이지 제목, "#1234", 파일 경로 등)
     * @param phase  실행 단계 ("fetch" 또는 "upload")
     * @param detail 원시 예외 메시지
     */
    public void append(String type, String id, String phase, String detail) {
        String ts = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        FailureEntry entry = new FailureEntry(ts, type, id, phase, classifyReason(detail), detail, 1);
        try {
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath,
                    MAPPER.writeValueAsString(entry) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("failures.jsonl 기록 실패 [type={}, id={}]: {}", type, id, e.getMessage());
        }
    }

    /** JSONL 파일에서 모든 실패 항목을 읽어 반환한다. */
    public List<FailureEntry> load() {
        if (!Files.exists(filePath)) return Collections.emptyList();
        List<FailureEntry> entries = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(filePath)) {
                if (!line.isBlank()) {
                    entries.add(MAPPER.readValue(line, FailureEntry.class));
                }
            }
        } catch (IOException e) {
            log.warn("failures.jsonl 읽기 실패: {}", e.getMessage());
        }
        return entries;
    }

    /**
     * 특정 type+phase에서 재시도가 무의미한 항목의 ID 집합을 반환한다.
     * {@code --retry-failed} 실행 시 이 집합에 포함된 항목은 건너뛴다.
     */
    public Set<String> loadNonRetryableIds(String type, String phase) {
        Set<String> ids = new HashSet<>();
        for (FailureEntry entry : load()) {
            if (type.equals(entry.getType())
                    && phase.equals(entry.getPhase())
                    && !entry.isRetryable()) {
                ids.add(entry.getId());
            }
        }
        return ids;
    }

    public Path getFilePath() { return filePath; }

    /**
     * 예외 메시지에서 실패 원인을 분류한다.
     * 분류 결과는 {@link FailureEntry#isRetryable()} 판단의 기준이 된다.
     */
    static String classifyReason(String detail) {
        if (detail == null) return "unknown";
        String d = detail.toLowerCase();
        if (d.contains("403") || d.contains("forbidden"))                        return "http_403";
        if (d.contains("404") || d.contains("not found"))                        return "http_404";
        if (d.contains("429") || d.contains("rate limit"))                       return "rate_limit";
        if (d.contains("500") || d.contains("502") || d.contains("503")
                || d.contains("server error"))                                   return "http_5xx";
        if (d.contains("illegal char") || d.contains("invalid path")
                || d.contains("invalid char"))                                   return "path_invalid";
        if (d.contains("too large") || d.contains("size exceeded")
                || d.contains("exceeds"))                                        return "size_exceeded";
        if (d.contains("connect") || d.contains("timeout")
                || d.contains("socket"))                                         return "network_error";
        if (d.contains("parse") || d.contains("malformed")
                || d.contains("jsonprocessing"))                                 return "parse_error";
        return "unknown";
    }
}
