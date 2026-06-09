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

    // ─── Getters ────────────────────────────────────────────────────────────

    public String getMinSeverity()                       { return minSeverity; }
    public int getMaxFilesPerReview()                    { return maxFilesPerReview; }
    public int getRequestTimeoutSeconds()                { return requestTimeoutSeconds; }
    public int getRetryMaxAttempts()                     { return retryMaxAttempts; }
    public long getRetryBackoffSeconds()                 { return retryBackoffSeconds; }
    public List<String> getIgnoreExtensions()            { return ignoreExtensions; }

    // ─── Setters ────────────────────────────────────────────────────────────

    public void setMinSeverity(String minSeverity)                   { this.minSeverity = minSeverity; }
    public void setMaxFilesPerReview(int maxFilesPerReview)          { this.maxFilesPerReview = maxFilesPerReview; }
    public void setRequestTimeoutSeconds(int requestTimeoutSeconds)   { this.requestTimeoutSeconds = requestTimeoutSeconds; }
    public void setRetryMaxAttempts(int retryMaxAttempts)            { this.retryMaxAttempts = retryMaxAttempts; }
    public void setRetryBackoffSeconds(long retryBackoffSeconds)     { this.retryBackoffSeconds = retryBackoffSeconds; }
    public void setIgnoreExtensions(List<String> ignoreExtensions)   { this.ignoreExtensions = ignoreExtensions; }
}
