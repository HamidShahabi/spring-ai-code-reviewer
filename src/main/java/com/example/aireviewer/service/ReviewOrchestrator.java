package com.example.aireviewer.service;

import com.example.aireviewer.domain.*;
import com.example.aireviewer.infrastructure.CommentPublisher;
import com.example.aireviewer.infrastructure.GitLabApiClient;
import com.example.aireviewer.repository.ReviewRepository;
import com.example.aireviewer.service.context.ContextStrategy;
import com.example.aireviewer.service.context.ContextStrategyFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

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
    private final ContextStrategyFactory contextStrategyFactory;

    /** Active LLM model name, resolved from Spring AI config for auditability and the summary comment. */
    private final String modelName;

    public ReviewOrchestrator(
            GitLabApiClient gitLabApiClient,
            DiffChunker diffChunker,
            LlmReviewService llmReviewService,
            RulesEngine rulesEngine,
            CommentPublisher commentPublisher,
            ReviewRepository reviewRepository,
            MeterRegistry meterRegistry,
            ContextStrategyFactory contextStrategyFactory,
            @Value("${spring.ai.model.chat:openai}") String provider,
            @Value("${spring.ai.anthropic.chat.model:unknown}") String anthropicModel,
            @Value("${spring.ai.openai.chat.options.model:unknown}") String openAiModel) {
        this.gitLabApiClient  = gitLabApiClient;
        this.diffChunker      = diffChunker;
        this.llmReviewService = llmReviewService;
        this.rulesEngine      = rulesEngine;
        this.commentPublisher = commentPublisher;
        this.reviewRepository = reviewRepository;
        this.meterRegistry    = meterRegistry;
        this.contextStrategyFactory = contextStrategyFactory;
        this.modelName        = "anthropic".equalsIgnoreCase(provider) ? anthropicModel : openAiModel;
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
            // One context strategy per MR (config-selected: none | injected | agentic). It may
            // hold per-MR state (fetch cache, shared tool budget), so it's shared across files.
            ContextStrategy strategy = contextStrategyFactory.create(ctx);
            log.info("Context strategy = {}", strategy.name());

            List<ReviewResponse.FindingDto> allFindings = new ArrayList<>();
            LlmUsage totalUsage = LlmUsage.ZERO;
            for (FileChunk chunk : chunks) {
                try {
                    FileReviewResult result = llmReviewService.review(chunk, ctx, strategy);
                    allFindings.addAll(result.findings());
                    totalUsage = totalUsage.add(result.usage());
                } catch (Exception e) {
                    log.warn("Skipping file {} after LLM failure: {}", chunk.filePath(), e.getMessage());
                }
            }

            // ── 4. Apply rules / filter ──────────────────────────────────
            List<ReviewResponse.FindingDto> filtered = rulesEngine.filter(allFindings);

            // ── 5. Publish ───────────────────────────────────────────────
            commentPublisher.deleteStaleNotes(projectId, mrIid);
            commentPublisher.postSummary(projectId, mrIid, filtered, modelName);
            List<ReviewResponse.FindingDto> fellBack =
                    commentPublisher.publishFindings(projectId, mrIid, filtered, ctx);

            // ── 5b. Tag the MR so reviewers can see it was AI-reviewed ────
            applyLabels(projectId, mrIid, filtered);

            // ── 6. Persist session + per-finding audit trail ─────────────
            long durationMs = System.currentTimeMillis() - startMs;
            reviewRepository.save(buildReviewEntity(ctx, chunks.size(), filtered, fellBack, durationMs, totalUsage));

            meterRegistry.counter("reviewer.mr.processed.total").increment();
            meterRegistry.counter("reviewer.finding.total",
                    "severity", "total").increment(filtered.size());
            meterRegistry.counter("reviewer.llm.tokens.total", "type", "prompt").increment(totalUsage.promptTokens());
            meterRegistry.counter("reviewer.llm.tokens.total", "type", "completion").increment(totalUsage.completionTokens());
            log.info("Review complete — mrIid={} findings={} tokens(p/c/t)={}/{}/{} duration={}ms",
                    mrIid, filtered.size(),
                    totalUsage.promptTokens(), totalUsage.completionTokens(), totalUsage.totalTokens(),
                    durationMs);

        } catch (Exception e) {
            log.error("Review failed — project={} mrIid={}: {}",
                    projectId, mrIid, e.getMessage(), e);
        } finally {
            sample.stop(meterRegistry.timer("reviewer.mr.review.duration"));
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Adds the {@code ai-reviewed} label to every reviewed MR, plus {@code ai-high-severity}
     * when any High finding was posted (FR-040/041). Label failures never fail the review.
     */
    private void applyLabels(long projectId, long mrIid,
                             List<ReviewResponse.FindingDto> findings) {
        try {
            gitLabApiClient.addLabel(projectId, mrIid, "ai-reviewed");
            boolean hasHigh = findings.stream()
                    .anyMatch(f -> "High".equalsIgnoreCase(f.severity()));
            if (hasHigh) {
                gitLabApiClient.addLabel(projectId, mrIid, "ai-high-severity");
            }
        } catch (Exception e) {
            log.warn("Failed to apply labels to MR {}: {}", mrIid, e.getMessage());
        }
    }

    private MrReview buildReviewEntity(MrContext ctx, int filesReviewed,
                                        List<ReviewResponse.FindingDto> findings,
                                        List<ReviewResponse.FindingDto> fellBack,
                                        long durationMs, LlmUsage usage) {
        MrReview review = new MrReview(ctx.projectId(), ctx.mrIid(), modelName);
        review.setFilesReviewed(filesReviewed);
        review.setDurationMs(durationMs);
        review.setPromptTokens(usage.promptTokens());
        review.setCompletionTokens(usage.completionTokens());
        review.setTotalTokens(usage.totalTokens());
        review.setFindingsHigh((int) findings.stream()
                .filter(f -> "High".equalsIgnoreCase(f.severity())).count());
        review.setFindingsMedium((int) findings.stream()
                .filter(f -> "Medium".equalsIgnoreCase(f.severity())).count());
        review.setFindingsLow((int) findings.stream()
                .filter(f -> "Low".equalsIgnoreCase(f.severity())).count());
        review.setFindingsNitpick((int) findings.stream()
                .filter(f -> "Nitpick".equalsIgnoreCase(f.severity())).count());

        // Per-finding audit rows. A finding was posted inline unless it fell back to a
        // general comment (identity match — fellBack holds the same DTO references).
        Set<ReviewResponse.FindingDto> fallbackSet =
                Collections.newSetFromMap(new IdentityHashMap<>());
        fallbackSet.addAll(fellBack);
        for (ReviewResponse.FindingDto f : findings) {
            Finding finding = new Finding(
                    review, f.file(), f.line(), Severity.fromString(f.severity()), f.comment());
            finding.setPostedInline(!fallbackSet.contains(f));
            review.addFinding(finding);
        }
        return review;
    }
}
