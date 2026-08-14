package com.example.aireviewer.service;

import com.example.aireviewer.domain.FileChunk;
import com.example.aireviewer.domain.FileReviewResult;
import com.example.aireviewer.domain.MrContext;
import com.example.aireviewer.service.context.ContextStrategy;

/**
 * Reviews a single {@link FileChunk} with the configured LLM and returns the parsed findings.
 *
 * <p>The provider (OpenAI, Anthropic, Ollama, …) is fully controlled by {@code application.yml} —
 * no code changes are required to switch models.
 */
public interface LlmReviewService {

    FileReviewResult review(FileChunk chunk, MrContext mrContext, ContextStrategy strategy);
}
