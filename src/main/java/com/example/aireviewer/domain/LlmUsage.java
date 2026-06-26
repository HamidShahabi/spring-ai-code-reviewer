package com.example.aireviewer.domain;

/**
 * Provider-agnostic token usage for one or more LLM calls. Sourced from Spring AI's
 * {@code ChatResponseMetadata.getUsage()}, so it works identically across Anthropic,
 * OpenAI, Ollama, etc. — no model-specific handling.
 */
public record LlmUsage(int promptTokens, int completionTokens, int totalTokens) {

    public static final LlmUsage ZERO = new LlmUsage(0, 0, 0);

    /** Returns the element-wise sum — used to aggregate per-file calls into a per-MR total. */
    public LlmUsage add(LlmUsage other) {
        return new LlmUsage(
                promptTokens + other.promptTokens,
                completionTokens + other.completionTokens,
                totalTokens + other.totalTokens);
    }
}
