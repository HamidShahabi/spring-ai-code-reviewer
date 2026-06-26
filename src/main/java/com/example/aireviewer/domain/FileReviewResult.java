package com.example.aireviewer.domain;

import java.util.List;

/**
 * Result of reviewing a single file: the findings plus the token usage of the LLM call(s)
 * that produced them. Lets the orchestrator aggregate cost/usage across an MR.
 */
public record FileReviewResult(List<ReviewResponse.FindingDto> findings, LlmUsage usage) {

    public static final FileReviewResult EMPTY = new FileReviewResult(List.of(), LlmUsage.ZERO);
}
