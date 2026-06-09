package com.example.aireviewer.service;

import com.example.aireviewer.domain.FileChunk;
import com.example.aireviewer.domain.MrContext;
import com.example.aireviewer.domain.ReviewResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Wraps Spring AI's {@link ChatClient} to review a single {@link FileChunk}.
 *
 * <p>The provider (OpenAI, Anthropic, Ollama, …) is fully controlled by
 * {@code application.yml} — no code changes are required to switch models.
 *
 * <p>Structured output is handled by Spring AI's {@code BeanOutputConverter}, which
 * appends a JSON Schema to the system prompt and deserialises the response into a
 * {@link ReviewResponse} record automatically.
 */
@Service
public class LlmReviewService {

    private static final Logger log = LoggerFactory.getLogger(LlmReviewService.class);

    /**
     * System prompt template.
     * The valid-line constraint is injected at runtime to reduce hallucinated line numbers.
     */
    private static final String SYSTEM_PROMPT = """
            You are a senior software engineer performing a code review.
            Analyse the provided diff for:
              - Security vulnerabilities (injection, auth issues, insecure defaults)
              - Performance problems (N+1, missing indexes, unnecessary allocations)
              - SOLID principle violations
              - Bugs and correctness issues

            CRITICAL RULE: You MUST only report findings on line numbers from the valid
            ranges listed below. Any line outside these ranges will cause the comment to
            be silently dropped by the GitLab API.

            Valid new-file line numbers for this review: %s

            Return a JSON object with this exact shape:
            {
              "reviews": [
                {
                  "file":     "<file path exactly as shown in the diff>",
                  "line":     <integer — must be in the valid ranges above>,
                  "severity": "<High|Medium|Low|Nitpick>",
                  "comment":  "<detailed Markdown comment with suggested fix>"
                }
              ]
            }

            If there are no findings worth reporting, return: {"reviews": []}
            Do not include any text outside the JSON object.
            """;

    /** User prompt template: MR context + diff. */
    private static final String USER_PROMPT = """
            MR Title:       %s
            MR Description: %s

            File: %s

            Diff:
            ```
            %s
            ```
            """;

    private final ChatClient    chatClient;
    private final MeterRegistry meterRegistry;

    public LlmReviewService(ChatClient.Builder chatClientBuilder,
                             MeterRegistry meterRegistry) {
        this.chatClient    = chatClientBuilder.build();
        this.meterRegistry = meterRegistry;
    }

    /**
     * Sends {@code chunk} to the configured LLM and returns the parsed findings.
     * Retried up to 3 times on any exception (covers transient network errors and
     * malformed responses that Spring AI cannot parse).
     */
    @Retryable(
            retryFor  = Exception.class,
            maxAttempts = 3,
            backoff   = @Backoff(delay = 2000, multiplier = 2)
    )
    public List<ReviewResponse.FindingDto> review(FileChunk chunk, MrContext mrContext) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            String systemPrompt = SYSTEM_PROMPT.formatted(chunk.validLines());
            String userPrompt   = USER_PROMPT.formatted(
                    mrContext.title(),
                    mrContext.description() != null ? mrContext.description() : "",
                    chunk.filePath(),
                    chunk.diffText()
            );

            ReviewResponse response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .entity(ReviewResponse.class);

            if (response == null || response.reviews() == null) {
                return List.of();
            }
            return response.reviews();

        } catch (Exception e) {
            log.warn("LLM call failed for file {}: {}", chunk.filePath(), e.getMessage());
            throw e;   // let @Retryable decide whether to retry
        } finally {
            sample.stop(meterRegistry.timer("reviewer.llm.request.duration"));
        }
    }
}
