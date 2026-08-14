package com.example.aireviewer.tools;

import com.example.aireviewer.domain.MrContext;
import com.example.aireviewer.infrastructure.GitLabClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Tool-calling context provider for a single Merge Request review.
 *
 * <p>The reviewed revision ({@code headCommitSha}) is <b>pinned in this object</b>, not
 * chosen by the model — the model only ever supplies a file path or a symbol name, and we
 * resolve it against that exact SHA. This makes branch state irrelevant: whether the MR is
 * on {@code staging}, {@code master}, or a stale feature branch, the model always reads the
 * precise revision under review, with zero re-indexing.
 *
 * <p>Guardrails (token efficiency):
 * <ul>
 *   <li><b>Call budget</b> — shared across the whole MR; once exhausted, tools return a hint
 *       to proceed instead of fetching more.</li>
 *   <li><b>Truncation</b> — fetched files are capped to {@code maxLines}.</li>
 *   <li><b>Cache</b> — identical fetches within one MR are served from memory (de-dupes the
 *       shared interface/util that several changed files all reference).</li>
 * </ul>
 *
 * <p>A fresh instance is created per MR (see {@code RepoContextToolsFactory}); it is therefore
 * confined to one review and safe to share across that review's per-file calls.
 */
public class RepoContextTools {

    private static final Logger log = LoggerFactory.getLogger(RepoContextTools.class);

    private final GitLabClient gitlab;
    private final long projectId;
    private final String ref;                 // pinned headCommitSha
    private final int maxLines;
    private final AtomicInteger budget;
    private final Map<String, String> fileCache = new ConcurrentHashMap<>();

    public RepoContextTools(GitLabClient gitlab, MrContext ctx, int callBudget, int maxLines) {
        this.gitlab    = gitlab;
        this.projectId = ctx.projectId();
        this.ref       = ctx.headCommitSha();
        this.maxLines  = maxLines;
        this.budget    = new AtomicInteger(callBudget);
    }

    @Tool(description = """
            Get the full source of a file at the exact revision under review. Use this when the
            diff alone is not enough to confirm a finding (e.g. you need the rest of a method, a
            field declaration, an import, or the class being changed). Be frugal — do not fetch
            files you can already reason about from the diff.""")
    public String getFile(
            @ToolParam(description = "Repository-relative path, exactly as it appears in the diff") String path) {
        if (!claim()) return "Context budget exhausted — review with the information you already have.";
        String clean = path == null ? "" : path.trim();
        if (clean.isEmpty() || clean.startsWith("/") || clean.contains("..")) {
            return "Invalid path. Provide a repository-relative path with no leading slash or '..'.";
        }
        return fileCache.computeIfAbsent(clean, p -> {
            String content = gitlab.fetchFileAtRef(projectId, p, ref);
            if (content == null) return "FILE_NOT_FOUND at the reviewed revision: " + p;
            log.info("tool getFile served '{}' @ {}", p, ref);
            return truncate(content);
        });
    }

    @Tool(description = """
            Find where a class, method, or symbol is defined or used, at the revision under review.
            Returns matching 'path:line' locations. Use to assess the impact of a change (callers,
            implementations, related config) before deciding severity.""")
    public String lookupSymbol(
            @ToolParam(description = "The class / method / identifier to search for") String symbol) {
        if (!claim()) return "Context budget exhausted — review with the information you already have.";
        String term = symbol == null ? "" : symbol.trim();
        if (term.isEmpty()) return "Provide a non-empty symbol to search for.";
        String hits = gitlab.searchBlobs(projectId, term, ref).stream()
                .limit(10)
                .map(h -> h.path() + ":" + h.startLine())
                .collect(Collectors.joining("\n"));
        log.info("tool lookupSymbol served '{}' @ {}", term, ref);
        return hits.isEmpty() ? "No matches for: " + term : hits;
    }

    /** @return true if a call slot was available (and consumed), false if the budget is spent. */
    private boolean claim() {
        boolean available = budget.getAndDecrement() > 0;
        if (!available) log.info("tool call budget exhausted @ {}", ref);
        return available;
    }

    private String truncate(String content) {
        String[] lines = content.split("\n", -1);
        if (lines.length <= maxLines) return content;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) sb.append(lines[i]).append('\n');
        sb.append("… [truncated — file has ").append(lines.length)
          .append(" lines; first ").append(maxLines).append(" shown]");
        return sb.toString();
    }
}
