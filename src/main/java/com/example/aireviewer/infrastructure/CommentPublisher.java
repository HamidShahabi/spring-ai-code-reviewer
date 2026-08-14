package com.example.aireviewer.infrastructure;

import com.example.aireviewer.domain.MrContext;
import com.example.aireviewer.domain.ReviewResponse;

import java.util.List;

/**
 * Posts a Merge Request's findings back to GitLab as review comments.
 *
 * Strategy (ADR-003):
 * <ol>
 *   <li>Delete all previous AI review notes (deduplication).</li>
 *   <li>Stage the summary and every finding as draft notes — invisible on the MR so far.</li>
 *   <li>For each finding attempt an inline draft; collect failures in a fallback draft.</li>
 *   <li>Bulk-publish every draft at once — the API equivalent of clicking GitLab's
 *       "Submit review" button, so the whole review lands atomically instead of
 *       trickling in comment-by-comment.</li>
 * </ol>
 */
public interface CommentPublisher {

    /** Deletes all previously published notes written by this reviewer. */
    void deleteStaleNotes(long projectId, long mrIid);

    /**
     * Discards any leftover draft notes from a previous run that crashed between staging
     * and publishing — otherwise they'd get swept into this run's bulk-publish alongside
     * the fresh review.
     */
    void discardStaleDrafts(long projectId, long mrIid);

    /**
     * Stages the summary and every finding as draft notes, then publishes them all at once
     * (GitLab's "Submit review" action) so the whole review appears atomically.
     *
     * @return the subset of findings that fell back to a general (non-inline) draft note
     */
    List<ReviewResponse.FindingDto> submitReview(
            long projectId, long mrIid,
            List<ReviewResponse.FindingDto> findings,
            String modelUsed,
            MrContext mrContext);
}
