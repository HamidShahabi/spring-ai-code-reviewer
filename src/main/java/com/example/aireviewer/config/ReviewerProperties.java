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

    /** Tool-calling context settings (bound under {@code reviewer.context-tools}). */
    private ContextTools contextTools = new ContextTools();

    // ─── Getters ────────────────────────────────────────────────────────────

    public String getMinSeverity()                       { return minSeverity; }
    public int getMaxFilesPerReview()                    { return maxFilesPerReview; }
    public int getRequestTimeoutSeconds()                { return requestTimeoutSeconds; }
    public int getRetryMaxAttempts()                     { return retryMaxAttempts; }
    public long getRetryBackoffSeconds()                 { return retryBackoffSeconds; }
    public List<String> getIgnoreExtensions()            { return ignoreExtensions; }
    public ContextTools getContextTools()                { return contextTools; }

    // ─── Setters ────────────────────────────────────────────────────────────

    public void setMinSeverity(String minSeverity)                   { this.minSeverity = minSeverity; }
    public void setMaxFilesPerReview(int maxFilesPerReview)          { this.maxFilesPerReview = maxFilesPerReview; }
    public void setRequestTimeoutSeconds(int requestTimeoutSeconds)   { this.requestTimeoutSeconds = requestTimeoutSeconds; }
    public void setRetryMaxAttempts(int retryMaxAttempts)            { this.retryMaxAttempts = retryMaxAttempts; }
    public void setRetryBackoffSeconds(long retryBackoffSeconds)     { this.retryBackoffSeconds = retryBackoffSeconds; }
    public void setIgnoreExtensions(List<String> ignoreExtensions)   { this.ignoreExtensions = ignoreExtensions; }
    public void setContextTools(ContextTools contextTools)           { this.contextTools = contextTools; }

    /**
     * Lets the LLM pull extra repo context on demand (full files, symbol lookups) at the
     * exact revision under review, instead of pre-indexing. Disabled by default.
     */
    public static class ContextTools {

        /** Master switch — when false, reviews run diff-only (the baseline behavior). */
        private boolean enabled = false;

        /** Max number of tool calls per MR (shared across all files). Bounds tokens/latency. */
        private int callBudget = 6;

        /** A fetched file is truncated to this many lines before being sent to the model. */
        private int maxFileLines = 400;

        public boolean isEnabled()                       { return enabled; }
        public int getCallBudget()                       { return callBudget; }
        public int getMaxFileLines()                     { return maxFileLines; }

        public void setEnabled(boolean enabled)          { this.enabled = enabled; }
        public void setCallBudget(int callBudget)        { this.callBudget = callBudget; }
        public void setMaxFileLines(int maxFileLines)    { this.maxFileLines = maxFileLines; }
    }
}
