package com.example.aireviewer.service;

import com.example.aireviewer.domain.ReviewResponse;

import java.util.List;

/**
 * Applies company-specific filtering rules to raw LLM findings before publication.
 *
 * <p>Extension point: additional rules (file-path allow/deny lists, deduplication against
 * previous sessions, …) can be added as further implementations without touching callers.
 */
public interface RulesEngine {

    List<ReviewResponse.FindingDto> filter(List<ReviewResponse.FindingDto> findings);
}
