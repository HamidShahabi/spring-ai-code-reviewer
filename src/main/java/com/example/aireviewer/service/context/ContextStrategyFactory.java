package com.example.aireviewer.service.context;

import com.example.aireviewer.config.ReviewerProperties;
import com.example.aireviewer.domain.MrContext;
import com.example.aireviewer.infrastructure.GitLabApiClient;
import com.example.aireviewer.tools.RepoContextTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Builds the configured {@link ContextStrategy} for a single Merge Request. Reading the strategy
 * from config here keeps the selection in one place and the rest of the app model-agnostic.
 */
@Component
public class ContextStrategyFactory {

    private static final Logger log = LoggerFactory.getLogger(ContextStrategyFactory.class);

    private final GitLabApiClient gitlab;
    private final ReviewerProperties props;

    public ContextStrategyFactory(GitLabApiClient gitlab, ReviewerProperties props) {
        this.gitlab = gitlab;
        this.props  = props;
    }

    public ContextStrategy create(MrContext ctx) {
        ReviewerProperties.Context cfg = props.getContext();
        String strategy = cfg.getStrategy() == null ? "none" : cfg.getStrategy().trim().toLowerCase();
        return switch (strategy) {
            case "injected" -> new InjectedStrategy(gitlab, ctx, cfg.getMaxFileLines());
            case "agentic"  -> new AgenticStrategy(
                    new RepoContextTools(gitlab, ctx, cfg.getAgenticCallBudget(), cfg.getMaxFileLines()));
            case "none"     -> NoneStrategy.INSTANCE;
            default -> {
                log.warn("Unknown context strategy '{}' — falling back to 'none'", strategy);
                yield NoneStrategy.INSTANCE;
            }
        };
    }
}
