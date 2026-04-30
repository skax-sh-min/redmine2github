package com.redmine2github.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 전체 프로젝트 인덱스 파일을 생성한다.
 *
 * <p>fetch-all 완료 후 {@code output/} 디렉터리를 스캔하여 두 파일을 생성한다:
 * <ul>
 *   <li>{@code output/all_projects_wiki.md} — 프로젝트별 Wiki 페이지 목록 인덱스</li>
 *   <li>{@code output/all_projects_issue.md} — 프로젝트별 Issue 목록 인덱스</li>
 * </ul>
 *
 * <p>정렬 기준:
 * <ol>
 *   <li>해당 항목 수 내림차순 (wiki 페이지 수 / issue 수)</li>
 *   <li>그다음 항목 수 내림차순 (issue 수 / wiki 페이지 수)</li>
 *   <li>프로젝트 표시 이름 오름차순</li>
 * </ol>
 *
 * <p>프로젝트 이름은 {@code _project.json}에서 읽어오며, 없으면 폴더명을 사용한다.
 */
public class AllProjectsIndexGenerator {

    private static final Logger log = LoggerFactory.getLogger(AllProjectsIndexGenerator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** {@link #generate(String)} 진입점. */
    public static void generate(String outputDir) {
        Path root = Path.of(outputDir);
        if (!Files.isDirectory(root)) {
            log.warn("[AllProjectsIndex] output 디렉터리가 없습니다: {}", root);
            return;
        }

        List<ProjectStats> stats = collectStats(root);
        if (stats.isEmpty()) {
            log.info("[AllProjectsIndex] 수집된 프로젝트가 없어 인덱스를 생성하지 않습니다.");
            return;
        }

        writeWikiIndex(root, stats);
        writeIssueIndex(root, stats);

        System.out.printf(
                "  → 전체 프로젝트 인덱스 생성 완료 (%d개 프로젝트): " +
                "all_projects_wiki.md / all_projects_issue.md%n",
                stats.size());
    }

    // ── 통계 수집 ──────────────────────────────────────────────────────────────

    private static List<ProjectStats> collectStats(Path root) {
        List<ProjectStats> result = new ArrayList<>();
        try (Stream<Path> entries = Files.list(root)) {
            entries.filter(Files::isDirectory)
                   .filter(d -> !d.getFileName().toString().startsWith("."))
                   .forEach(dir -> {
                       ProjectStats s = buildStats(dir);
                       if (s != null) result.add(s);
                   });
        } catch (IOException e) {
            log.error("[AllProjectsIndex] output 디렉터리 스캔 실패: {}", e.getMessage(), e);
        }
        return result;
    }

    private static ProjectStats buildStats(Path projectDir) {
        String slug = projectDir.getFileName().toString();

        // 프로젝트 이름 — _project.json 우선, 없으면 slug
        String name = slug;
        String description = "";
        Path metaFile = projectDir.resolve("_project.json");
        if (Files.exists(metaFile)) {
            try {
                Map<String, String> meta = MAPPER.readValue(
                        metaFile.toFile(), new TypeReference<>() {});
                String n = meta.get("name");
                if (n != null && !n.isBlank()) name = n;
                String d = meta.get("description");
                if (d != null && !d.isBlank()) description = d;
            } catch (IOException e) {
                log.warn("[AllProjectsIndex] _project.json 읽기 실패 [{}]: {}", slug, e.getMessage());
            }
        }

        // Wiki 페이지 수 — wiki/ 디렉터리 내 .md 파일 개수
        int wikiCount = countFiles(projectDir.resolve("wiki"), ".md");

        // Issue 수 — issues-json/ 디렉터리 내 숫자.json 파일 개수
        int issueCount = countIssueJsons(projectDir.resolve("issues-json"));

        // 양쪽 모두 0이고 _project.json도 없으면 유효하지 않은 폴더로 간주
        if (wikiCount == 0 && issueCount == 0 && !Files.exists(metaFile)) {
            return null;
        }

        // issues.md 위치
        boolean hasIssueIndex = Files.exists(projectDir.resolve("issues.md"));

        // Wiki 메인 페이지 후보 (루트 .md 파일 중 첫 번째)
        String wikiMainPage = findWikiMainPage(projectDir.resolve("wiki"));

        return new ProjectStats(slug, name, description,
                wikiCount, issueCount, wikiMainPage, hasIssueIndex);
    }

    /** 지정 디렉터리를 재귀 탐색하여 특정 확장자 파일 수를 반환한다. */
    private static int countFiles(Path dir, String ext) {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            return (int) walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(ext))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    /** issues-json/ 내 숫자.json 파일(실제 이슈) 수를 반환한다. _*.json 은 제외. */
    private static int countIssueJsons(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> list = Files.list(dir)) {
            return (int) list
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().matches("\\d+\\.json"))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * wiki 루트 디렉터리에서 메인 페이지를 추정한다.
     * Wiki.md → 첫 번째 루트 .md 파일 순으로 시도한다.
     *
     * @return 상대 경로 문자열 (예: {@code "wiki/Wiki.md"}), 없으면 {@code null}
     */
    private static String findWikiMainPage(Path wikiDir) {
        if (!Files.isDirectory(wikiDir)) return null;

        // 우선순위: Wiki.md
        Path wiki = wikiDir.resolve("Wiki.md");
        if (Files.exists(wiki)) return "wiki/Wiki.md";

        // 루트의 첫 번째 .md 파일
        try (Stream<Path> list = Files.list(wikiDir)) {
            return list.filter(Files::isRegularFile)
                       .filter(p -> p.getFileName().toString().endsWith(".md"))
                       .sorted()
                       .map(p -> "wiki/" + p.getFileName())
                       .findFirst()
                       .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    // ── Wiki 인덱스 출력 ───────────────────────────────────────────────────────

    private static void writeWikiIndex(Path root, List<ProjectStats> stats) {
        // 정렬: Wiki 수 DESC → Issue 수 DESC → 이름 ASC
        List<ProjectStats> sorted = stats.stream()
                .sorted(Comparator
                        .comparingInt(ProjectStats::wikiCount).reversed()
                        .thenComparing(Comparator.comparingInt(ProjectStats::issueCount).reversed())
                        .thenComparing(ProjectStats::name))
                .toList();

        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        StringBuilder sb = new StringBuilder();
        sb.append("# 전체 프로젝트 — Wiki 인덱스\n\n");
        sb.append("> 생성 시각: ").append(now).append("  \n");
        sb.append("> 총 ").append(sorted.size()).append("개 프로젝트 / ")
          .append(sorted.stream().mapToInt(ProjectStats::wikiCount).sum()).append("개 Wiki 페이지\n\n");

        sb.append("| 프로젝트 | Wiki 페이지 | Issue | 설명 |\n");
        sb.append("|---|---:|---:|---|\n");

        for (ProjectStats s : sorted) {
            String displayName = s.name() + " (" + s.wikiCount() + "개)";
            String wikiLink;
            if (s.wikiMainPage() != null) {
                wikiLink = "[" + displayName + "](" + s.slug() + "/" + s.wikiMainPage() + ")";
            } else {
                wikiLink = displayName;
            }
            String descCell = s.description().length() > 60
                    ? s.description().substring(0, 57) + "..."
                    : s.description();
            sb.append("| ").append(wikiLink)
              .append(" | ").append(s.wikiCount())
              .append(" | ").append(s.issueCount())
              .append(" | ").append(escapeTable(descCell))
              .append(" |\n");
        }

        writeSafe(root.resolve("all_projects_wiki.md"), sb.toString(), "all_projects_wiki.md");
    }

    // ── Issue 인덱스 출력 ──────────────────────────────────────────────────────

    private static void writeIssueIndex(Path root, List<ProjectStats> stats) {
        // 정렬: Issue 수 DESC → Wiki 수 DESC → 이름 ASC
        List<ProjectStats> sorted = stats.stream()
                .sorted(Comparator
                        .comparingInt(ProjectStats::issueCount).reversed()
                        .thenComparing(Comparator.comparingInt(ProjectStats::wikiCount).reversed())
                        .thenComparing(ProjectStats::name))
                .toList();

        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        StringBuilder sb = new StringBuilder();
        sb.append("# 전체 프로젝트 — Issue 인덱스\n\n");
        sb.append("> 생성 시각: ").append(now).append("  \n");
        sb.append("> 총 ").append(sorted.size()).append("개 프로젝트 / ")
          .append(sorted.stream().mapToInt(ProjectStats::issueCount).sum()).append("개 Issue\n\n");

        sb.append("| 프로젝트 | Issue | Wiki 페이지 | 설명 |\n");
        sb.append("|---|---:|---:|---|\n");

        for (ProjectStats s : sorted) {
            String displayName = s.name() + " (" + s.issueCount() + "개)";
            String issueLink;
            if (s.hasIssueIndex()) {
                issueLink = "[" + displayName + "](" + s.slug() + "/issues.md)";
            } else {
                issueLink = displayName;
            }
            String descCell = s.description().length() > 60
                    ? s.description().substring(0, 57) + "..."
                    : s.description();
            sb.append("| ").append(issueLink)
              .append(" | ").append(s.issueCount())
              .append(" | ").append(s.wikiCount())
              .append(" | ").append(escapeTable(descCell))
              .append(" |\n");
        }

        writeSafe(root.resolve("all_projects_issue.md"), sb.toString(), "all_projects_issue.md");
    }

    // ── 유틸 ──────────────────────────────────────────────────────────────────

    private static void writeSafe(Path path, String content, String label) {
        try {
            Files.writeString(path, content);
            log.info("[AllProjectsIndex] {} 생성 완료: {}", label, path);
        } catch (IOException e) {
            log.error("[AllProjectsIndex] {} 생성 실패: {}", label, e.getMessage(), e);
        }
    }

    private static String escapeTable(String s) {
        return s.replace("|", "\\|").replace("\n", " ").replace("\r", "");
    }

    // ── 내부 데이터 모델 ───────────────────────────────────────────────────────

    private record ProjectStats(
            String slug,
            String name,
            String description,
            int wikiCount,
            int issueCount,
            String wikiMainPage,
            boolean hasIssueIndex
    ) {}
}
