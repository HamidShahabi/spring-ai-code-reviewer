package com.example.aireviewer.service;

import com.example.aireviewer.config.ReviewerProperties;
import com.example.aireviewer.domain.ReviewResponse;
import com.example.aireviewer.domain.Severity;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Applies company-specific filtering rules to raw LLM findings before publication.
 *
 * <p>Current rules:
 * <ul>
 *   <li>Severity threshold — drop findings below {@code reviewer.min-severity}.</li>
 * </ul>
 *
 * <p>Extension point: load additional rules (file-path allow/deny lists, deduplication
 * against previous sessions) from the database or a YAML file without code changes.
 */
@Component
public class RulesEngine {

    private final ReviewerProperties properties;

    public RulesEngine(ReviewerProperties properties) {
        this.properties = properties;
    }

    public List<ReviewResponse.FindingDto> filter(List<ReviewResponse.FindingDto> findings) {
        Severity minSeverity = Severity.fromString(properties.getMinSeverity());
        return findings.stream()
                .filter(f -> meetsThreshold(f.severity(), minSeverity))
                .toList();
    }

    private boolean meetsThreshold(String rawSeverity, Severity min) {
        try {
            return Severity.fromString(rawSeverity).getWeight() >= min.getWeight();
        } catch (Exception e) {
            return false;
        }
    }
}
