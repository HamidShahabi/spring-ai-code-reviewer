package com.example.aireviewer.service;

import com.example.aireviewer.domain.FileChunk;
import com.example.aireviewer.domain.MrContext;
import com.example.aireviewer.domain.ReviewResponse;
import com.example.aireviewer.tools.RepoContextTools;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    /**
     * Appended to the system prompt only when context tools are enabled. Steers the model to
     * pull context on demand, but frugally — most files need no extra fetch.
     */
    private static final String TOOLS_HINT = """

            You have tools to fetch the full file, a related file, or a symbol's definition AT THE
            EXACT REVISION UNDER REVIEW. Before flagging a *possible* issue you cannot confirm from
            the diff alone, fetch the context to verify it. Be frugal: only fetch what you genuinely
            need, and do not fetch files you can already reason about from the diff.

            OUTPUT FORMAT — STRICT: You may call tools first to gather context, but your FINAL
            message must be ONLY the JSON object specified above. No analysis, no prose, no markdown
            headings, no ``` fences, no commentary. The final message MUST start with '{' and end
            with '}'. Any text outside the JSON object is a failure.""";

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
    private final ObjectMapper  objectMapper;

    public LlmReviewService(ChatClient.Builder chatClientBuilder,
                             MeterRegistry meterRegistry,
                             ObjectMapper objectMapper) {
        this.chatClient    = chatClientBuilder.build();
        this.meterRegistry = meterRegistry;
        this.objectMapper  = objectMapper;
    }

    /**
     * Sends {@code chunk} to the configured LLM and returns the parsed findings.
     * Retried up to 3 times on any exception (covers transient network errors and
     * malformed responses that Spring AI cannot parse).
     */
    @Retryable(
            retryFor  = Exception.class,
            noRetryFor = IllegalStateException.class,   // parse failure is deterministic; replaying the tool loop won't help
            maxAttempts = 3,
            backoff   = @Backoff(delay = 2000, multiplier = 2)
    )
    public List<ReviewResponse.FindingDto> review(FileChunk chunk, MrContext mrContext, RepoContextTools tools) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            String systemPrompt = SYSTEM_PROMPT.formatted(chunk.validLines())
                    + (tools != null ? TOOLS_HINT : "");
            String userPrompt   = USER_PROMPT.formatted(
                    mrContext.title(),
                    mrContext.description() != null ? mrContext.description() : "",
                    chunk.filePath(),
                    chunk.diffText()
            );

            ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt);

            ReviewResponse response;
            if (tools != null) {
                // With tool calling, the model frequently wraps its final answer in prose or a
                // ```json fence, which BeanOutputConverter (.entity) rejects. Take the raw content
                // and extract the JSON ourselves — the prompt already pins the exact shape.
                String raw = spec.tools(tools).call().content();   // model may pull repo context at the reviewed SHA
                response = parseLenient(raw);
            } else {
                response = spec.call().entity(ReviewResponse.class);
            }

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

    /**
     * Tolerantly parses a (possibly fenced or prose-wrapped) model response into a
     * {@link ReviewResponse}. Strips {@code ```json} fences and extracts the outermost JSON
     * object, so tool-calling responses like {@code "Based on my review: ```json {…}```"} parse.
     */
    private ReviewResponse parseLenient(String content) {
        if (content == null || content.isBlank()) {
            return new ReviewResponse(List.of());
        }
        String stripped = content.replace("```json", "").replace("```", "");
        int begin = stripped.indexOf('{');
        int end   = stripped.lastIndexOf('}');
        String json = (begin >= 0 && end > begin) ? stripped.substring(begin, end + 1) : stripped;
        try {
            return objectMapper.readValue(json, ReviewResponse.class);
        } catch (Exception e) {
            log.warn("RAW tool-mode content (len={}): >>>{}<<<", content.length(),
                    content.length() > 1200 ? content.substring(0, 1200) + "…[truncated]" : content);
            throw new IllegalStateException("Could not parse tool-mode LLM response as JSON: " + e.getMessage(), e);
        }
    }
}
