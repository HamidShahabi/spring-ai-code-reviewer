package com.example.aireviewer.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * All outbound HTTP calls to the GitLab API.
 * Uses Spring's {@link RestClient} with the pre-configured base URL and auth header.
 * Transient failures are retried up to 3 times with 2-second exponential backoff.
 */
@Component
public class GitLabApiClient {

    private static final Logger log = LoggerFactory.getLogger(GitLabApiClient.class);

    /** Marker string included in every AI-authored comment so stale ones can be found. */
    public static final String AI_REVIEWER_MARKER = "🤖 AI Code Review";

    private final RestClient restClient;

    public GitLabApiClient(RestClient gitLabRestClient) {
        this.restClient = gitLabRestClient;
    }

    // ─── Diff and versions ───────────────────────────────────────────────────

    @Retryable(retryFor = Exception.class, maxAttempts = 3, backoff = @Backoff(delay = 2000, multiplier = 2))
    public List<DiffFile> fetchDiff(long projectId, long mrIid) {
        log.debug("Fetching diff for project={} mrIid={}", projectId, mrIid);
        MrChangesResponse response = restClient.get()
                .uri("/api/v4/projects/{p}/merge_requests/{m}/changes", projectId, mrIid)
                .retrieve()
                .body(MrChangesResponse.class);
        return response != null && response.changes() != null ? response.changes() : List.of();
    }

    @Retryable(retryFor = Exception.class, maxAttempts = 3, backoff = @Backoff(delay = 2000, multiplier = 2))
    public MrVersion fetchLatestVersion(long projectId, long mrIid) {
        List<MrVersion> versions = restClient.get()
                .uri("/api/v4/projects/{p}/merge_requests/{m}/versions", projectId, mrIid)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (versions == null || versions.isEmpty()) {
            throw new IllegalStateException(
                    "No diff versions found for project=%d mrIid=%d".formatted(projectId, mrIid));
        }
        return versions.get(0);
    }

    // ─── Notes ───────────────────────────────────────────────────────────────

    public List<GitLabNote> listNotes(long projectId, long mrIid) {
        List<GitLabNote> notes = restClient.get()
                .uri("/api/v4/projects/{p}/merge_requests/{m}/notes?per_page=100", projectId, mrIid)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return notes != null ? notes : List.of();
    }

    public void deleteNote(long projectId, long mrIid, long noteId) {
        restClient.delete()
                .uri("/api/v4/projects/{p}/merge_requests/{m}/notes/{n}", projectId, mrIid, noteId)
                .retrieve()
                .toBodilessEntity();
    }

    public Long postNote(long projectId, long mrIid, String body) {
        GitLabNote note = restClient.post()
                .uri("/api/v4/projects/{p}/merge_requests/{m}/notes", projectId, mrIid)
                .body(Map.of("body", body))
                .retrieve()
                .body(GitLabNote.class);
        return note != null ? note.id() : null;
    }

    // ─── Inline comments ─────────────────────────────────────────────────────

    /**
     * Attempts to post an inline comment anchored to a specific line in the diff.
     * Returns the GitLab note ID on success, or {@code null} on failure
     * (so callers can fall back to a general comment without throwing).
     */
    public Long postInlineComment(long projectId, long mrIid,
                                   String body, String filePath, int line,
                                   String baseSha, String headSha, String startSha) {
        Map<String, Object> payload = Map.of(
                "body", body,
                "position", Map.of(
                        "base_sha",      baseSha,
                        "head_sha",      headSha,
                        "start_sha",     startSha,
                        "position_type", "text",
                        "new_path",      filePath,
                        "new_line",      line
                )
        );
        DiscussionResponse discussion = restClient.post()
                .uri("/api/v4/projects/{p}/merge_requests/{m}/discussions", projectId, mrIid)
                .body(payload)
                .retrieve()
                .body(DiscussionResponse.class);

        if (discussion != null
                && discussion.notes() != null
                && !discussion.notes().isEmpty()) {
            return discussion.notes().get(0).id();
        }
        return null;
    }

    // ─── Labels ──────────────────────────────────────────────────────────────

    public void addLabel(long projectId, long mrIid, String label) {
        restClient.put()
                .uri("/api/v4/projects/{p}/merge_requests/{m}", projectId, mrIid)
                .body(Map.of("add_labels", label))
                .retrieve()
                .toBodilessEntity();
    }

    // ─── Response types ───────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DiffFile(
            @JsonProperty("old_path")      String oldPath,
            @JsonProperty("new_path")      String newPath,
            String diff,
            @JsonProperty("deleted_file")  boolean deletedFile,
            @JsonProperty("new_file")      boolean newFile
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MrChangesResponse(List<DiffFile> changes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MrVersion(
            @JsonProperty("base_commit_sha")  String baseCommitSha,
            @JsonProperty("head_commit_sha")  String headCommitSha,
            @JsonProperty("start_commit_sha") String startCommitSha
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitLabNote(long id, String body) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DiscussionResponse(List<GitLabNote> notes) {}
}
