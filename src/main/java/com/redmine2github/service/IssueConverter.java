package com.redmine2github.service;

import com.redmine2github.config.AppConfig;
import com.redmine2github.converter.AttachmentPathRewriter;
import com.redmine2github.converter.LinkRewriter;
import com.redmine2github.converter.RedmineUrlRewriter;
import com.redmine2github.converter.TextileConverter;
import com.redmine2github.redmine.RedmineClient;
import com.redmine2github.redmine.model.RedmineAttachment;
import com.redmine2github.redmine.model.RedmineIssue;
import com.redmine2github.redmine.model.RedmineJournal;
import com.redmine2github.service.model.LocalIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * RedmineIssue → LocalIssue 변환을 담당한다.
 * 첨부파일 다운로드, Textile→GFM 변환, 코멘트 빌드를 포함한다.
 */
class IssueConverter {

    private static final Logger log = LoggerFactory.getLogger(IssueConverter.class);

    private final AppConfig config;
    private final TextileConverter converter            = new TextileConverter();
    private final LinkRewriter linkRewriter             = new LinkRewriter();
    private final AttachmentPathRewriter attachRewriter = new AttachmentPathRewriter();
    private final RedmineUrlRewriter redmineUrlRewriter;

    IssueConverter(AppConfig config) {
        this.config = config;
        this.redmineUrlRewriter = new RedmineUrlRewriter(config.getRedmineUrl(), config.getUrlRewrites());
    }

    LocalIssue convert(RedmineIssue issue, RedmineClient redmine) {
        Map<String, String> userMap = config.getUserMapping();

        Path attachIssueDir = Path.of(config.getProjectOutputDir(), "attachments-issue");
        Map<String, String> attNameMapping = downloadAttachments(issue.getAttachments(), redmine, attachIssueDir);

        String body     = buildIssueBody(issue, userMap, attNameMapping, redmine, attachIssueDir);
        List<String> labels  = buildLabels(issue);
        String author   = issue.getAuthorLogin() != null
                        ? userMap.getOrDefault(issue.getAuthorLogin(), issue.getAuthorLogin())
                        : "unknown";
        String assignee = issue.getAssigneeLogin() != null
                        ? userMap.getOrDefault(issue.getAssigneeLogin(), null)
                        : null;

        List<String> comments = issue.getJournals().stream()
                .filter(RedmineJournal::hasContent)
                .map(j -> buildCommentBody(j, userMap, attNameMapping))
                .collect(Collectors.toList());

        return new LocalIssue(
                issue.getId(), issue.getSubject(), body,
                labels, author, assignee, issue.getCreatedOn(),
                comments, isClosed(issue.getStatus())
        );
    }

    private String buildIssueBody(RedmineIssue issue, Map<String, String> userMap,
                                   Map<String, String> attNameMapping, RedmineClient redmine,
                                   Path attachIssueDir) {
        String author = userMap.getOrDefault(
                issue.getAssigneeLogin(),
                issue.getAssigneeLogin() != null ? issue.getAssigneeLogin() : "unknown"
        );

        String md = converter.convert(issue.getDescription());
        md = linkRewriter.rewrite(md, Collections.emptyMap(), config.getProjectSlug(), "");
        md = attachRewriter.rewrite(md, attNameMapping, "../attachments-issue/");

        BiConsumer<String, Path> extDownloader = (url, destFile) -> {
            try {
                redmine.downloadToFile(url, destFile);
            } catch (IOException e) {
                log.warn("Issue 외부 첨부파일 다운로드 실패 [url={}, dest={}]: {}", url, destFile, e.getMessage());
            }
        };
        md = redmineUrlRewriter.rewrite(md, config.getProjectSlug(), "", attachIssueDir, extDownloader);

        if (!attNameMapping.isEmpty()) {
            StringBuilder attSection = new StringBuilder("\n\n## 첨부파일\n\n");
            attNameMapping.forEach((origName, storedName) ->
                attSection.append("- [").append(origName).append("](../attachments-issue/")
                          .append(storedName).append(")\n"));
            md = md + attSection;
        }

        return String.format("""
                > **[Redmine #%d]** | 프로젝트: `%s` | 작성: %s | 날짜: %s

                %s
                """, issue.getId(), config.getProjectSlug(), author, issue.getCreatedOn(), md);
    }

    private Map<String, String> downloadAttachments(List<RedmineAttachment> attachments,
                                                     RedmineClient redmine, Path destDir) {
        Map<String, String> nameMapping = new LinkedHashMap<>();
        if (attachments.isEmpty()) return nameMapping;
        try {
            Files.createDirectories(destDir);
        } catch (IOException e) {
            log.warn("attachments-issue 디렉터리 생성 실패: {}", e.getMessage());
            return nameMapping;
        }
        for (RedmineAttachment att : attachments) {
            try {
                Path stored = redmine.downloadAttachment(att, destDir);
                nameMapping.put(att.getFilename(), stored.getFileName().toString());
            } catch (IOException e) {
                log.warn("Issue 첨부파일 다운로드 실패 [name={}, url={}]: {}",
                        att.getFilename(), att.getContentUrl(), e.getMessage());
                nameMapping.put(att.getFilename(), att.getFilename());
            }
        }
        return nameMapping;
    }

    private String buildCommentBody(RedmineJournal journal, Map<String, String> userMap,
                                     Map<String, String> attNameMapping) {
        String author = userMap.getOrDefault(journal.getAuthorLogin(), journal.getAuthorLogin());
        StringBuilder sb = new StringBuilder();

        sb.append("> **").append(author).append("** (").append(journal.getCreatedOn()).append(")");

        if (!journal.getDetails().isEmpty()) {
            sb.append("\n>");
            for (var d : journal.getDetails()) {
                if ("attachment".equals(d.getProperty())) {
                    if (d.getOldValue() == null && d.getNewValue() != null) {
                        String storedName = attNameMapping.getOrDefault(d.getNewValue(), d.getNewValue());
                        sb.append("\n> - 첨부파일 추가: [").append(d.getNewValue())
                          .append("](../attachments-issue/").append(storedName).append(")");
                    } else if (d.getNewValue() == null) {
                        sb.append("\n> - 첨부파일 삭제: `").append(d.getOldValue()).append("`");
                    }
                } else {
                    sb.append("\n> - **").append(mapFieldName(d.getName())).append("**");
                    if (d.getOldValue() != null) sb.append(": `").append(d.getOldValue()).append("` → ");
                    else sb.append(": → ");
                    if (d.getNewValue() != null) sb.append("`").append(d.getNewValue()).append("`");
                    else sb.append("(삭제)");
                }
            }
        }

        if (!journal.getNotes().isBlank()) {
            String md = converter.convert(journal.getNotes());
            md = linkRewriter.rewrite(md, Collections.emptyMap(), config.getProjectSlug(), "");
            md = redmineUrlRewriter.rewrite(md, config.getProjectSlug(), "", null, null);
            sb.append("\n\n").append(md);
        }

        return sb.toString();
    }

    private static String mapFieldName(String name) {
        return switch (name) {
            case "status_id"         -> "상태";
            case "assigned_to_id"    -> "담당자";
            case "priority_id"       -> "우선순위";
            case "fixed_version_id"  -> "버전";
            case "tracker_id"        -> "트래커";
            case "category_id"       -> "분류";
            case "subject"           -> "제목";
            case "description"       -> "설명";
            case "done_ratio"        -> "진행률";
            case "due_date"          -> "마감일";
            case "start_date"        -> "시작일";
            case "estimated_hours"   -> "예상시간";
            default                  -> name;
        };
    }

    private List<String> buildLabels(RedmineIssue issue) {
        List<String> labels = new ArrayList<>();
        labels.add("project:" + config.getProjectSlug());
        labels.add("tracker:" + issue.getTracker());
        labels.add("priority:" + issue.getPriority());
        labels.add("status:" + issue.getStatus());
        if (issue.getCategory() != null) labels.add("category:" + issue.getCategory());
        return labels;
    }

    static boolean isClosed(String status) {
        return "Closed".equalsIgnoreCase(status) || "Resolved".equalsIgnoreCase(status);
    }
}
