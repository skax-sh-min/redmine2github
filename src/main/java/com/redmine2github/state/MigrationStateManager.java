package com.redmine2github.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

/**
 * migration-state.json을 읽고 쓰는 관리자.
 * resume 및 멱등성 처리에 사용된다.
 */
public class MigrationStateManager {

    private static final Logger log = LoggerFactory.getLogger(MigrationStateManager.class);
    private static final String STATE_FILE = "migration-state.json";

    private final ObjectMapper mapper = new ObjectMapper();
    private final MigrationState state;

    public MigrationStateManager(boolean resume) {
        this.state = resume ? load() : new MigrationState();
    }

    private MigrationState load() {
        File file = new File(STATE_FILE);
        if (!file.exists()) {
            log.info("migration-state.json 없음 — 처음부터 시작합니다.");
            return new MigrationState();
        }
        try {
            log.info("이전 진행 상태를 불러옵니다: {}", STATE_FILE);
            return mapper.readValue(file, MigrationState.class);
        } catch (IOException e) {
            log.warn("상태 파일 로드 실패, 새로 시작합니다: {}", e.getMessage());
            return new MigrationState();
        }
    }

    public void save() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(STATE_FILE), state);
        } catch (IOException e) {
            log.error("상태 파일 저장 실패: {}", e.getMessage(), e);
        }
    }

    public MigrationState getState() {
        return state;
    }
}
