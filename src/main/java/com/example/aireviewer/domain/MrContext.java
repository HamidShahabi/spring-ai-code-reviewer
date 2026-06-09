package com.example.aireviewer.domain;

/**
 * Immutable snapshot of the Merge Request metadata needed throughout a single review run.
 * The three commit SHAs are required by the GitLab Discussions API for inline comments.
 */
public record MrContext(
        long projectId,
        long mrIid,
        String title,
        String description,
        String baseCommitSha,
        String headCommitSha,
        String startCommitSha
) {}
