package com.redmine2github.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Redmine API 응답 원본 JSON을 cache/ 디렉터리에 저장·로드한다.
 * <p>
 * 저장 형식: {@code cache/{key}.json} (JSON 배열)
 * <p>
 * 캐시가 존재하면 API 호출 없이 로컬 파일에서 데이터를 반환하므로
 * --resume 실행 시 불필요한 네트워크 요청을 줄인다.
 */
public class CacheManager {

    private static final Logger log = LoggerFactory.getLogger(CacheManager.class);

    private final Path cacheDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public CacheManager(String cacheDir) {
        this.cacheDir = Path.of(cacheDir);
    }

    /** 배열 노드를 {@code cache/{key}.json}에 저장한다. */
    public void saveArray(String key, ArrayNode data) {
        try {
            Files.createDirectories(cacheDir);
            Path file = cacheDir.resolve(key + ".json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
            log.info("캐시 저장: {} ({}건) → {}", key, data.size(), file);
        } catch (IOException e) {
            log.warn("캐시 저장 실패 [{}]: {}", key, e.getMessage());
        }
    }

    /**
     * {@code cache/{key}.json}이 존재하면 ArrayNode로 로드해 반환한다.
     * 파일이 없거나 파싱 오류 시 {@link Optional#empty()}를 반환한다.
     */
    public Optional<ArrayNode> loadArray(String key) {
        Path file = cacheDir.resolve(key + ".json");
        if (!Files.exists(file)) return Optional.empty();
        try {
            JsonNode parsed = mapper.readTree(file.toFile());
            if (!(parsed instanceof ArrayNode node)) {
                log.warn("캐시 파일이 배열 형식이 아닙니다 — 재수집합니다 [{}]: {}", key, file);
                return Optional.empty();
            }
            log.info("캐시 로드: {} ({}건) ← {}", key, node.size(), file);
            return Optional.of(node);
        } catch (IOException e) {
            log.warn("캐시 로드 실패 [{}]: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /** 단일 JSON 오브젝트를 {@code cache/{key}.json}에 저장한다. */
    public void saveNode(String key, JsonNode data) {
        try {
            Files.createDirectories(cacheDir);
            Path file = cacheDir.resolve(key + ".json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
        } catch (IOException e) {
            log.warn("캐시 저장 실패 [{}]: {}", key, e.getMessage());
        }
    }

    /**
     * {@code cache/{key}.json}이 존재하면 JsonNode로 로드해 반환한다.
     * 파일이 없거나 파싱 오류 시 {@link Optional#empty()}를 반환한다.
     */
    public Optional<JsonNode> loadNode(String key) {
        Path file = cacheDir.resolve(key + ".json");
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(mapper.readTree(file.toFile()));
        } catch (IOException e) {
            log.warn("캐시 로드 실패 [{}]: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /** 캐시 파일 존재 여부를 반환한다. */
    public boolean exists(String key) {
        return Files.exists(cacheDir.resolve(key + ".json"));
    }
}
