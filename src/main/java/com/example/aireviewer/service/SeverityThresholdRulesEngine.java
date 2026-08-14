package com.example.aireviewer.service;

import com.example.aireviewer.config.ReviewerProperties;
import com.example.aireviewer.domain.ReviewResponse;
import com.example.aireviewer.domain.Severity;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@link RulesEngine} that drops findings below {@code reviewer.min-severity}. */
@Component
public class SeverityThresholdRulesEngine implements RulesEngine {

    private final ReviewerProperties properties;

    public SeverityThresholdRulesEngine(ReviewerProperties properties) {
        this.properties = properties;
    }

    @Override
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
