package com.example.aireviewer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Application-level tuning knobs.
 * Bind via {@code reviewer.*} in {@code application.yml}.
 */
@ConfigurationProperties(prefix = "reviewer")
public class ReviewerProperties {

    /** Minimum severity level to post ({@code High}, {@code Medium}, {@code Low}, {@code Nitpick}). */
    private String minSeverity = "Low";

    /** Maximum number of files sent for review in a single MR run. */
    private int maxFilesPerReview = 30;

    /** Per-request timeout for LLM API calls, in seconds. */
    private int requestTimeoutSeconds = 120;

    /** Maximum attempts for Spring Retry on LLM/GitLab calls. */
    private int retryMaxAttempts = 3;

    /** Base backoff delay in seconds between retry attempts. */
    private long retryBackoffSeconds = 2;

    /** File suffixes / names that are always skipped during review. */
    private List<String> ignoreExtensions = List.of(
            ".lock", "go.sum", "go.mod", ".pb.go",
            "swagger.yaml", ".svg", ".png", ".md"
    );

    /** Context-augmentation settings (bound under {@code reviewer.context}). */
    private Context context = new Context();

    // ─── Getters ────────────────────────────────────────────────────────────

    public String getMinSeverity()                       { return minSeverity; }
    public int getMaxFilesPerReview()                    { return maxFilesPerReview; }
    public int getRequestTimeoutSeconds()                { return requestTimeoutSeconds; }
    public int getRetryMaxAttempts()                     { return retryMaxAttempts; }
    public long getRetryBackoffSeconds()                 { return retryBackoffSeconds; }
    public List<String> getIgnoreExtensions()            { return ignoreExtensions; }
    public Context getContext()                          { return context; }

    // ─── Setters ────────────────────────────────────────────────────────────

    public void setMinSeverity(String minSeverity)                   { this.minSeverity = minSeverity; }
    public void setMaxFilesPerReview(int maxFilesPerReview)          { this.maxFilesPerReview = maxFilesPerReview; }
    public void setRequestTimeoutSeconds(int requestTimeoutSeconds)   { this.requestTimeoutSeconds = requestTimeoutSeconds; }
    public void setRetryMaxAttempts(int retryMaxAttempts)            { this.retryMaxAttempts = retryMaxAttempts; }
    public void setRetryBackoffSeconds(long retryBackoffSeconds)     { this.retryBackoffSeconds = retryBackoffSeconds; }
    public void setIgnoreExtensions(List<String> ignoreExtensions)   { this.ignoreExtensions = ignoreExtensions; }
    public void setContext(Context context)                         { this.context = context; }

    /**
     * How much repo context to give the model, at the exact revision under review (no
     * pre-indexing). A config-selected strategy, in the spirit of provider selection (ADR-001):
     * <ul>
     *   <li>{@code none}     — diff only (cheapest; the original baseline).</li>
     *   <li>{@code injected} — also inject the full changed file, fetched at the head SHA
     *       (deterministic floor; works on any model). <b>Recommended default.</b></li>
     *   <li>{@code agentic}  — expose tools so the model pulls context on demand
     *       (higher ceiling; needs a tool-capable model). <b>Experimental.</b></li>
     * </ul>
     */
    public static class Context {

        /** Active strategy: {@code none} | {@code injected} | {@code agentic}. */
        private String strategy = "none";

        /** A fetched file is truncated to this many lines (applies to injected and agentic). */
        private int maxFileLines = 400;

        /** Agentic only: max tool calls per MR (shared across files). Bounds tokens/latency. */
        private int agenticCallBudget = 6;

        public String getStrategy()                      { return strategy; }
        public int getMaxFileLines()                     { return maxFileLines; }
        public int getAgenticCallBudget()                { return agenticCallBudget; }

        public void setStrategy(String strategy)         { this.strategy = strategy; }
        public void setMaxFileLines(int maxFileLines)    { this.maxFileLines = maxFileLines; }
        public void setAgenticCallBudget(int budget)     { this.agenticCallBudget = budget; }
    }
}
