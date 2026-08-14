package com.example.aireviewer.service.context;

import com.example.aireviewer.domain.MrContext;

/**
 * Builds the configured {@link ContextStrategy} for a single Merge Request. Reading the strategy
 * from config here keeps the selection in one place and the rest of the app model-agnostic.
 */
public interface ContextStrategyFactory {

    ContextStrategy create(MrContext ctx);
}
