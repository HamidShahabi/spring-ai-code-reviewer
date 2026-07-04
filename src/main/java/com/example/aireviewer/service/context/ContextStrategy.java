package com.example.aireviewer.service.context;

import com.example.aireviewer.domain.FileChunk;

/**
 * Pluggable strategy for how much repository context the model receives when reviewing a file,
 * always resolved at the exact revision under review (no pre-indexing). Selected by config —
 * the same spirit as provider selection (ADR-001), so business logic never branches on the model.
 *
 * <p>A strategy instance is created <b>per Merge Request</b> (see {@code ContextStrategyFactory}),
 * so it may hold per-MR state such as a fetch cache or a shared tool-call budget.
 *
 * <p>Two orthogonal levers:
 * <ul>
 *   <li>{@link #injectedContextFor(FileChunk)} — deterministic context the application fetches
 *       and appends to the prompt (the "injected floor").</li>
 *   <li>{@link #tools()} — a tool object the model may call to pull context itself (the
 *       "agentic ceiling").</li>
 * </ul>
 * A strategy populates either, both, or neither.
 */
public interface ContextStrategy {

    /** Identifier for logging/metrics, e.g. {@code none}, {@code injected}, {@code agentic}. */
    String name();

    /** Extra context block to append to the user prompt for this file. Empty string = none. */
    default String injectedContextFor(FileChunk chunk) {
        return "";
    }

    /** Tool object the model may call to gather context on demand. {@code null} = no tools. */
    default Object tools() {
        return null;
    }
}
