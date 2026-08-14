package com.example.aireviewer.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link GitLabClient} backed by the GitLab REST API via Spring's {@link RestClient}, using the
 * pre-configured base URL and auth header. Transient failures are retried up to 3 times with
 * 2-second exponential backoff.
 */
@Component
public class GitLabApiClient implements GitLabClient {

    private static final Logger log = LoggerFactory.getLogger(GitLabApiClient.class);

    private final RestClient restClient;

    /** Cached id of the account this client authenticates as (the bot). Resolved once. */
    private volatile Long currentUserId;

    public GitLabApiClient(RestClient gitLabRestClient) {
        this.restClient = gitLabRestClient;
    }

    @Override
    public long currentUserId() {
        Long id = currentUserId;
        if (id == null) {
            CurrentUser user = restClient.get().uri("/api/v4/user").retrieve().body(CurrentUser.class);
            if (user == null) {
                throw new IllegalStateException("Could not resolve current GitLab user (check gitlab.bot-token)");
            }
            id = user.id();
            currentUserId = id;
        }
        return id;
    }

    // ─── Diff and versions ───────────────────────────────────────────────────

    @Override
    @Retryable(retryFor = Exception.class, maxAttempts = 3, backoff = @Backoff(delay = 2000, multiplier = 2))
    public List<DiffFile> fetchDiff(long projectId, long mrIid) {
        log.debug("Fetching diff for project={} mrIid={}", projectId, mrIid);
        MrChangesResponse response = restClient.get()
                .uri("/api/v4/projects/{p}/merge_requests/{m}/changes", projectId, mrIid)
                .retrieve()
                .body(MrChangesResponse.class);
        return response != null && response.changes() != null ? response.changes() : List.of();
    }

    @Override
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

    @Override
    public List<GitLabNote> listNotes(long projectId, long mrIid) {
        List<GitLabNote> notes = restClient.get()
                .uri("/api/v4/projects/{p}/merge_requests/{m}/notes?per_page=100", projectId, mrIid)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return notes != null ? notes : List.of();
    }

    @Override
    public void deleteNote(long projectId, long mrIid, long noteId) {
        restClient.delete()
                .uri("/api/v4/projects/{p}/merge_requests/{m}/notes/{n}", projectId, mrIid, noteId)
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public Long postNote(long projectId, long mrIid, String body) {
        GitLabNote note = restClient.post()
                .uri("/api/v4/projects/{p}/merge_requests/{m}/notes", projectId, mrIid)
                .body(Map.of("body", body))
                .retrieve()
                .body(GitLabNote.class);
        return note != null ? note.id() : null;
    }

    // ─── Inline comments ─────────────────────────────────────────────────────

    @Override
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

    // ─── Repository reads (for tool-calling context) ─────────────────────────

    @Override
    @Retryable(retryFor = Exception.class, maxAttempts = 2, backoff = @Backoff(delay = 1000))
    public String fetchFileAtRef(long projectId, String filePath, String ref) {
        // GitLab wants the file path URL-encoded into a single segment (slashes -> %2F).
        // Build a pre-encoded URI and pass it as a URI object so RestClient does NOT re-encode
        // it (a templated String var would turn %2F into %252F, which GitLab 404s on).
        String encodedPath = URLEncoder.encode(filePath, StandardCharsets.UTF_8).replace("+", "%20");
        String encodedRef  = URLEncoder.encode(ref, StandardCharsets.UTF_8).replace("+", "%20");
        URI uri = URI.create("/api/v4/projects/" + projectId + "/repository/files/"
                + encodedPath + "/raw?ref=" + encodedRef);
        try {
            return restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    @Override
    public List<SearchHit> searchBlobs(long projectId, String term, String ref) {
        try {
            List<SearchHit> hits = restClient.get()
                    .uri("/api/v4/projects/{p}/search?scope=blobs&search={s}&ref={r}", projectId, term, ref)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return hits != null ? hits : List.of();
        } catch (Exception e) {
            log.debug("Blob search failed for '{}' @ {}: {}", term, ref, e.getMessage());
            return List.of();
        }
    }

    // ─── Labels ──────────────────────────────────────────────────────────────

    @Override
    public void addLabel(long projectId, long mrIid, String label) {
        restClient.put()
                .uri("/api/v4/projects/{p}/merge_requests/{m}", projectId, mrIid)
                .body(Map.of("add_labels", label))
                .retrieve()
                .toBodilessEntity();
    }

    // ─── Draft notes ("pending review", published together via Submit review) ─

    @Override
    public void createDraftNote(long projectId, long mrIid, String body) {
        restClient.post()
                .uri("/api/v4/projects/{p}/merge_requests/{m}/draft_notes", projectId, mrIid)
                .body(Map.of("note", body))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void createDraftInlineNote(long projectId, long mrIid, String body, String filePath, int line,
                                      String baseSha, String headSha, String startSha) {
        Map<String, Object> payload = Map.of(
                "note", body,
                "position", Map.of(
                        "base_sha",      baseSha,
                        "head_sha",      headSha,
                        "start_sha",     startSha,
                        "position_type", "text",
                        "new_path",      filePath,
                        "new_line",      line
                )
        );
        restClient.post()
                .uri("/api/v4/projects/{p}/merge_requests/{m}/draft_notes", projectId, mrIid)
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public List<Long> listOwnDraftNoteIds(long projectId, long mrIid) {
        List<DraftNote> notes = restClient.get()
                .uri("/api/v4/projects/{p}/merge_requests/{m}/draft_notes", projectId, mrIid)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return notes == null ? List.of() : notes.stream().map(DraftNote::id).toList();
    }

    @Override
    public void deleteDraftNote(long projectId, long mrIid, long draftNoteId) {
        restClient.delete()
                .uri("/api/v4/projects/{p}/merge_requests/{m}/draft_notes/{d}", projectId, mrIid, draftNoteId)
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void bulkPublishDraftNotes(long projectId, long mrIid) {
        restClient.post()
                .uri("/api/v4/projects/{p}/merge_requests/{m}/draft_notes/bulk_publish", projectId, mrIid)
                .retrieve()
                .toBodilessEntity();
    }

    // ─── Reviewer assignment / approval ──────────────────────────────────────

    @Override
    public void addReviewer(long projectId, long mrIid, long userId) {
        MrReviewers current = restClient.get()
                .uri("/api/v4/projects/{p}/merge_requests/{m}", projectId, mrIid)
                .retrieve()
                .body(MrReviewers.class);
        List<Long> existingIds = current != null && current.reviewers() != null
                ? current.reviewers().stream().map(MrReviewers.Reviewer::id).toList()
                : List.of();
        if (existingIds.contains(userId)) {
            return;
        }
        List<Long> updated = new ArrayList<>(existingIds);
        updated.add(userId);
        restClient.put()
                .uri("/api/v4/projects/{p}/merge_requests/{m}", projectId, mrIid)
                .body(Map.of("reviewer_ids", updated))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void approve(long projectId, long mrIid) {
        restClient.post()
                .uri("/api/v4/projects/{p}/merge_requests/{m}/approve", projectId, mrIid)
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void unapprove(long projectId, long mrIid) {
        try {
            restClient.post()
                    .uri("/api/v4/projects/{p}/merge_requests/{m}/unapprove", projectId, mrIid)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            // Bot had not approved — nothing to withdraw.
        }
    }

    // ─── Internal response types (never exposed in the GitLabClient contract) ─

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MrChangesResponse(List<DiffFile> changes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CurrentUser(long id, String username) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DraftNote(long id) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MrReviewers(List<Reviewer> reviewers) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Reviewer(long id) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DiscussionResponse(List<GitLabNote> notes) {}
}
