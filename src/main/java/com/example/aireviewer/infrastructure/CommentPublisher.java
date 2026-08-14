package com.example.aireviewer.infrastructure;

import com.example.aireviewer.domain.MrContext;
import com.example.aireviewer.domain.ReviewResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Posts findings back to GitLab as comments.
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
@Component
public class CommentPublisher {

    private static final Logger log = LoggerFactory.getLogger(CommentPublisher.class);

    private final GitLabApiClient gitLabApiClient;
    private final Counter inlineSuccess;
    private final Counter inlineFallback;

    public CommentPublisher(GitLabApiClient gitLabApiClient, MeterRegistry registry) {
        this.gitLabApiClient = gitLabApiClient;
        this.inlineSuccess   = registry.counter("reviewer.comment.inline.success");
        this.inlineFallback  = registry.counter("reviewer.comment.inline.fallback");
    }

    /** Deletes all previously published notes written by this reviewer. */
    public void deleteStaleNotes(long projectId, long mrIid) {
        try {
            gitLabApiClient.listNotes(projectId, mrIid).stream()
                    .filter(n -> n.body() != null
                              && n.body().contains(GitLabApiClient.AI_REVIEWER_MARKER))
                    .forEach(n -> {
                        try {
                            gitLabApiClient.deleteNote(projectId, mrIid, n.id());
                        } catch (Exception e) {
                            log.warn("Could not delete stale note {}: {}", n.id(), e.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.warn("Failed to fetch notes for stale deletion: {}", e.getMessage());
        }
    }

    /**
     * Discards any leftover draft notes from a previous run that crashed between staging
     * and publishing — otherwise they'd get swept into this run's bulk-publish alongside
     * the fresh review.
     */
    public void discardStaleDrafts(long projectId, long mrIid) {
        try {
            for (Long id : gitLabApiClient.listOwnDraftNoteIds(projectId, mrIid)) {
                try {
                    gitLabApiClient.deleteDraftNote(projectId, mrIid, id);
                } catch (Exception e) {
                    log.warn("Could not delete stale draft note {}: {}", id, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list draft notes for stale cleanup: {}", e.getMessage());
        }
    }

    /**
     * Stages the summary and every finding as draft notes, then publishes them all at once
     * (GitLab's "Submit review" action) so the whole review appears atomically.
     *
     * @return the subset of findings that fell back to a general (non-inline) draft note
     */
    public List<ReviewResponse.FindingDto> submitReview(
            long projectId, long mrIid,
            List<ReviewResponse.FindingDto> findings,
            String modelUsed,
            MrContext mrContext) {

        gitLabApiClient.createDraftNote(projectId, mrIid, buildSummary(findings, modelUsed));

        List<ReviewResponse.FindingDto> fallback = new ArrayList<>();
        for (ReviewResponse.FindingDto f : findings) {
            boolean staged = tryDraftInline(projectId, mrIid, f, mrContext);
            if (staged) {
                inlineSuccess.increment();
            } else {
                inlineFallback.increment();
                fallback.add(f);
            }
        }

        if (!fallback.isEmpty()) {
            gitLabApiClient.createDraftNote(projectId, mrIid, buildFallback(fallback));
        }

        gitLabApiClient.bulkPublishDraftNotes(projectId, mrIid);
        return fallback;
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private boolean tryDraftInline(long projectId, long mrIid,
                                    ReviewResponse.FindingDto finding,
                                    MrContext ctx) {
        try {
            gitLabApiClient.createDraftInlineNote(
                    projectId, mrIid,
                    buildInline(finding),
                    finding.file(), finding.line(),
                    ctx.baseCommitSha(), ctx.headCommitSha(), ctx.startCommitSha()
            );
            return true;
        } catch (Exception e) {
            log.warn("Inline draft note failed for {}:{} — falling back. Reason: {}",
                    finding.file(), finding.line(), e.getMessage());
            return false;
        }
    }

    private String buildSummary(List<ReviewResponse.FindingDto> findings, String modelUsed) {
        Map<String, Long> counts = findings.stream()
                .collect(Collectors.groupingBy(
                        f -> f.severity().toUpperCase(), Collectors.counting()));

        long high    = counts.getOrDefault("HIGH",    0L);
        long medium  = counts.getOrDefault("MEDIUM",  0L);
        long low     = counts.getOrDefault("LOW",     0L);
        long nitpick = counts.getOrDefault("NITPICK", 0L);

        String verdict = high > 0
                ? "🔴 **Request Changes** — high severity issues require attention"
                : medium > 0
                ? "🟠 **Review Needed** — medium severity issues found"
                : findings.isEmpty()
                ? "✅ **LGTM** — no issues found"
                : "🔵 **Minor Issues** — low severity or nitpick findings only";

        return """
                ## %s — `%s`

                | Severity | Count |
                |----------|-------|
                | 🔴 High     | %d |
                | 🟠 Medium   | %d |
                | 🔵 Low      | %d |
                | ◻️ Nitpick  | %d |

                **Verdict:** %s

                *Inline comments follow below.*
                """.formatted(GitLabApiClient.AI_REVIEWER_MARKER, modelUsed,
                        high, medium, low, nitpick, verdict);
    }

    private String buildInline(ReviewResponse.FindingDto f) {
        String icon = switch (f.severity().toUpperCase()) {
            case "HIGH"    -> "🔴";
            case "MEDIUM"  -> "🟠";
            case "LOW"     -> "🔵";
            default        -> "◻️";
        };
        return "**%s AI Review [%s]** · `%s:%d`\n\n%s"
                .formatted(icon, f.severity(), f.file(), f.line(), f.comment());
    }

    private String buildFallback(List<ReviewResponse.FindingDto> findings) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(GitLabApiClient.AI_REVIEWER_MARKER)
          .append(" — Additional Findings\n\n");
        sb.append("*(These findings could not be posted as inline comments)*\n\n");
        for (ReviewResponse.FindingDto f : findings) {
            sb.append("---\n");
            sb.append("**`").append(f.file()).append(':').append(f.line()).append("`**");
            sb.append(" [").append(f.severity()).append("]\n\n");
            sb.append(f.comment()).append("\n\n");
        }
        return sb.toString();
    }
}
