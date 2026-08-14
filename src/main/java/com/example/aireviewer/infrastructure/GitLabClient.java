package com.example.aireviewer.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * All outbound calls to the GitLab API needed to review and report on a Merge Request:
 * fetching the diff, reading repository files at a pinned revision, and publishing the
 * review (comments, reviewer assignment, approval).
 */
public interface GitLabClient {

    /** Marker string included in every AI-authored comment so stale ones can be found. */
    String AI_REVIEWER_MARKER = "🤖 AI Code Review";

    /** The GitLab user id of the account this client authenticates as (self-lookup, cached). */
    long currentUserId();

    // ─── Diff and versions ───────────────────────────────────────────────────

    List<DiffFile> fetchDiff(long projectId, long mrIid);

    MrVersion fetchLatestVersion(long projectId, long mrIid);

    // ─── Notes ───────────────────────────────────────────────────────────────

    List<GitLabNote> listNotes(long projectId, long mrIid);

    void deleteNote(long projectId, long mrIid, long noteId);

    Long postNote(long projectId, long mrIid, String body);

    // ─── Inline comments ─────────────────────────────────────────────────────

    /**
     * Attempts to post an inline comment anchored to a specific line in the diff.
     * Returns the GitLab note ID on success, or {@code null} on failure
     * (so callers can fall back to a general comment without throwing).
     */
    Long postInlineComment(long projectId, long mrIid,
                            String body, String filePath, int line,
                            String baseSha, String headSha, String startSha);

    // ─── Repository reads (for tool-calling context) ─────────────────────────

    /**
     * Raw content of a file at a specific commit SHA / ref. Returns {@code null} when
     * the file does not exist at that ref (e.g. a newly added file in the MR), so the
     * caller can degrade gracefully instead of throwing.
     */
    String fetchFileAtRef(long projectId, String filePath, String ref);

    /**
     * Blob search scoped to a ref — used to locate where a symbol is defined or used.
     * Best-effort: returns an empty list if search is unavailable or errors.
     */
    List<SearchHit> searchBlobs(long projectId, String term, String ref);

    // ─── Labels ──────────────────────────────────────────────────────────────

    void addLabel(long projectId, long mrIid, String label);

    // ─── Draft notes ("pending review", published together via Submit review) ─

    /** Creates a draft (pending) general note — invisible on the MR until {@link #bulkPublishDraftNotes}. */
    void createDraftNote(long projectId, long mrIid, String body);

    /** Creates a draft (pending) inline note anchored to a diff line. Same position shape as {@link #postInlineComment}. */
    void createDraftInlineNote(long projectId, long mrIid, String body, String filePath, int line,
                                String baseSha, String headSha, String startSha);

    /** Lists this user's own pending draft notes on the MR (used to clean up after a crashed prior run). */
    List<Long> listOwnDraftNoteIds(long projectId, long mrIid);

    void deleteDraftNote(long projectId, long mrIid, long draftNoteId);

    /**
     * Publishes every pending draft note on this MR at once — the API equivalent of clicking
     * GitLab's "Submit review" button. Comments appear on the MR atomically as a single review,
     * rather than trickling in one at a time as each is created.
     */
    void bulkPublishDraftNotes(long projectId, long mrIid);

    // ─── Reviewer assignment / approval ──────────────────────────────────────

    /**
     * Adds {@code userId} to the MR's reviewers, preserving any reviewers already assigned
     * (GitLab's reviewer_ids PUT is a full replace, not additive — so any existing human
     * reviewers are fetched first and kept). No-op if the bot is already a reviewer.
     */
    void addReviewer(long projectId, long mrIid, long userId);

    /**
     * Approves the MR as the authenticated (bot) user. Merging remains a human decision —
     * this only records that the bot's review found nothing worth blocking on.
     */
    void approve(long projectId, long mrIid);

    /**
     * Withdraws the bot's own prior approval. Called when a re-review (new commits) now
     * finds issues that an earlier, cleaner pass didn't — an approval must reflect the
     * latest review, not a stale one. No-op if the bot never approved.
     */
    void unapprove(long projectId, long mrIid);

    // ─── Response types ───────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DiffFile(
            @JsonProperty("old_path")      String oldPath,
            @JsonProperty("new_path")      String newPath,
            String diff,
            @JsonProperty("deleted_file")  boolean deletedFile,
            @JsonProperty("new_file")      boolean newFile
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MrVersion(
            @JsonProperty("base_commit_sha")  String baseCommitSha,
            @JsonProperty("head_commit_sha")  String headCommitSha,
            @JsonProperty("start_commit_sha") String startCommitSha
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GitLabNote(long id, String body) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchHit(
            String path,
            @JsonProperty("startline") int startLine,
            String data
    ) {}
}
