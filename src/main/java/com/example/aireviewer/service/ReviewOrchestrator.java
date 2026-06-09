package com.example.aireviewer.service;

import com.example.aireviewer.domain.*;
import com.example.aireviewer.infrastructure.CommentPublisher;
import com.example.aireviewer.infrastructure.GitLabApiClient;
import com.example.aireviewer.repository.ReviewRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates the full review workflow for a single Merge Request event.
 *
 * <p>Execution is asynchronous (the webhook controller returns 202 immediately).
 * Each file is reviewed independently; a failure on one file is logged and skipped
 * so the rest of the MR still gets reviewed (partial review preferred over total failure).
 *
 * <p>Data flow (see ARCHITECTURE.md §5):
 * <pre>
 *   fetchDiff → fetchVersions → split → [per-file LLM call] → filter → persist → publish
 * </pre>
 */
@Service
public class ReviewOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ReviewOrchestrator.class);

    private final GitLabApiClient  gitLabApiClient;
    private final DiffChunker      diffChunker;
    private final LlmReviewService llmReviewService;
    private final RulesEngine      rulesEngine;
    private final CommentPublisher commentPublisher;
    private final ReviewRepository reviewRepository;
    private final MeterRegistry    meterRegistry;

    public ReviewOrchestrator(
            GitLabApiClient gitLabApiClient,
            DiffChunker diffChunker,
            LlmReviewService llmReviewService,
            RulesEngine rulesEngine,
            CommentPublisher commentPublisher,
            ReviewRepository reviewRepository,
            MeterRegistry meterRegistry) {
        this.gitLabApiClient  = gitLabApiClient;
        this.diffChunker      = diffChunker;
        this.llmReviewService = llmReviewService;
        this.rulesEngine      = rulesEngine;
        this.commentPublisher = commentPublisher;
        this.reviewRepository = reviewRepository;
        this.meterRegistry    = meterRegistry;
    }

    @Async("reviewTaskExecutor")
    public void triggerReview(MrEvent event) {
        long projectId = event.project().id();
        long mrIid     = event.objectAttributes().iid();
        log.info("Review started — project={} mrIid={}", projectId, mrIid);

        Timer.Sample sample = Timer.start(meterRegistry);
        long startMs = System.currentTimeMillis();

        try {
            // ── 1. Fetch diff and commit SHAs ─────────────────────────────
            List<GitLabApiClient.DiffFile> diffFiles =
                    gitLabApiClient.fetchDiff(projectId, mrIid);
            GitLabApiClient.MrVersion version =
                    gitLabApiClient.fetchLatestVersion(projectId, mrIid);

            MrContext ctx = new MrContext(
                    projectId, mrIid,
                    event.objectAttributes().title(),
                    event.objectAttributes().description() != null
                            ? event.objectAttributes().description() : "",
                    version.baseCommitSha(),
                    version.headCommitSha(),
                    version.startCommitSha()
            );

            // ── 2. Chunk the diff ────────────────────────────────────────
            List<FileChunk> chunks = diffChunker.split(diffFiles);
            if (chunks.isEmpty()) {
                log.info("No reviewable files — skipping MR {}", mrIid);
                return;
            }

            // ── 3. Review each file independently ────────────────────────
            List<ReviewResponse.FindingDto> allFindings = new ArrayList<>();
            for (FileChunk chunk : chunks) {
                try {
                    allFindings.addAll(llmReviewService.review(chunk, ctx));
                } catch (Exception e) {
                    log.warn("Skipping file {} after LLM failure: {}", chunk.filePath(), e.getMessage());
                }
            }

            // ── 4. Apply rules / filter ──────────────────────────────────
            List<ReviewResponse.FindingDto> filtered = rulesEngine.filter(allFindings);

            // ── 5. Publish ───────────────────────────────────────────────
            commentPublisher.deleteStaleNotes(projectId, mrIid);
            commentPublisher.postSummary(projectId, mrIid, filtered, resolveModelName());
            commentPublisher.publishFindings(projectId, mrIid, filtered, ctx);

            // ── 6. Persist session ───────────────────────────────────────
            long durationMs = System.currentTimeMillis() - startMs;
            reviewRepository.save(buildReviewEntity(ctx, chunks.size(), filtered, durationMs));

            meterRegistry.counter("reviewer.mr.processed.total").increment();
            meterRegistry.counter("reviewer.finding.total",
                    "severity", "total").increment(filtered.size());
            log.info("Review complete — mrIid={} findings={} duration={}ms",
                    mrIid, filtered.size(), durationMs);

        } catch (Exception e) {
            log.error("Review failed — project={} mrIid={}: {}",
                    projectId, mrIid, e.getMessage(), e);
        } finally {
            sample.stop(meterRegistry.timer("reviewer.mr.review.duration"));
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Model name is stored in the review entity for auditability.
     * Resolved from the active Spring AI auto-configuration at runtime.
     * TODO: inject the model name from Spring AI ChatOptions when wiring configurations.
     */
    private String resolveModelName() {
        return "configured-model";
    }

    private MrReview buildReviewEntity(MrContext ctx, int filesReviewed,
                                        List<ReviewResponse.FindingDto> findings,
                                        long durationMs) {
        MrReview review = new MrReview(ctx.projectId(), ctx.mrIid(), resolveModelName());
        review.setFilesReviewed(filesReviewed);
        review.setDurationMs(durationMs);
        review.setFindingsHigh((int) findings.stream()
                .filter(f -> "High".equalsIgnoreCase(f.severity())).count());
        review.setFindingsMedium((int) findings.stream()
                .filter(f -> "Medium".equalsIgnoreCase(f.severity())).count());
        review.setFindingsLow((int) findings.stream()
                .filter(f -> "Low".equalsIgnoreCase(f.severity())).count());
        review.setFindingsNitpick((int) findings.stream()
                .filter(f -> "Nitpick".equalsIgnoreCase(f.severity())).count());
        return review;
    }
}
