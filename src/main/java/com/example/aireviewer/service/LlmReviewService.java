package com.example.aireviewer.service;

import com.example.aireviewer.domain.FileChunk;
import com.example.aireviewer.domain.FileReviewResult;
import com.example.aireviewer.domain.LlmUsage;
import com.example.aireviewer.domain.MrContext;
import com.example.aireviewer.domain.ReviewResponse;
import com.example.aireviewer.service.context.ContextStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
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
            EXACT REVISION UNDER REVIEW:
              - getFile(path): read the full source of a file at this exact revision.
              - lookupSymbol(symbol): find where a class, method, or field is defined or used.

            MANDATORY TOOL USE: if the diff calls a method, reads a field, or uses a type that is
            DEFINED IN A DIFFERENT FILE (not shown in this diff), and you are about to make a claim
            about its nullability, return type, thrown exceptions, or behavior, you MUST call
            getFile or lookupSymbol on that symbol BEFORE including the finding. Do not guess from
            naming conventions. An unverified assumption about external code is worse than not
            reporting the finding at all — either verify it or drop it.

            Examples that REQUIRE a tool call before you may state them as findings:
              - "X.getY() may return null" — confirm Y's actual declared type first.
              - Any claim about a superclass, interface, or injected dependency's behavior.

            GO ONE LEVEL DEEPER WHEN NEEDED: verifying a method's return type is often not the
            end of the trail. If that return type is itself a class you have not seen, and your
            finding depends on one of ITS fields (e.g. a boxed type that can be null, a mutable
            collection, a missing validation), fetch that class too before stating the finding.
            Stopping after the first fetch when the real risk is one hop further is the same as
            not verifying at all.

            You do NOT need to fetch the file already shown in the diff — you have its content.
            Be frugal beyond the mandatory cases above: do not fetch files out of curiosity.

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
    public FileReviewResult review(FileChunk chunk, MrContext mrContext, ContextStrategy strategy) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            String injectedContext = strategy.injectedContextFor(chunk);
            Object tools           = strategy.tools();

            String systemPrompt = SYSTEM_PROMPT.formatted(chunk.validLines())
                    + (tools != null ? TOOLS_HINT : "");
            String userPrompt   = USER_PROMPT.formatted(
                    mrContext.title(),
                    mrContext.description() != null ? mrContext.description() : "",
                    chunk.filePath(),
                    chunk.diffText()
            ) + (injectedContext.isBlank() ? "" : "\n\n" + injectedContext);

            ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt);

            ReviewResponse response;
            ChatResponse  chatResponse;   // carries usage metadata in both paths
            if (tools != null) {
                // With tool calling, the model frequently wraps its final answer in prose or a
                // ```json fence, which BeanOutputConverter (.entity) rejects. Take the raw content
                // and extract the JSON ourselves — the prompt already pins the exact shape.
                chatResponse = spec.tools(tools).call().chatResponse();   // may pull repo context at the reviewed SHA
                response = parseLenient(chatResponse.getResult().getOutput().getText());
            } else {
                // responseEntity gives us the parsed entity AND the ChatResponse (for token usage).
                ResponseEntity<ChatResponse, ReviewResponse> re =
                        spec.call().responseEntity(ReviewResponse.class);
                response     = re.entity();
                chatResponse = re.response();
            }

            List<ReviewResponse.FindingDto> findings =
                    (response == null || response.reviews() == null) ? List.of() : response.reviews();
            return new FileReviewResult(findings, extractUsage(chatResponse));

        } catch (Exception e) {
            log.warn("LLM call failed for file {}: {}", chunk.filePath(), e.getMessage());
            throw e;   // let @Retryable decide whether to retry
        } finally {
            sample.stop(meterRegistry.timer("reviewer.llm.request.duration"));
        }
    }

    /** Null-safe conversion of Spring AI's provider-agnostic {@link Usage} into {@link LlmUsage}. */
    private LlmUsage extractUsage(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getMetadata() == null
                || chatResponse.getMetadata().getUsage() == null) {
            return LlmUsage.ZERO;
        }
        Usage u = chatResponse.getMetadata().getUsage();
        int prompt     = u.getPromptTokens()     != null ? u.getPromptTokens()     : 0;
        int completion = u.getCompletionTokens() != null ? u.getCompletionTokens() : 0;
        Integer total  = u.getTotalTokens();
        return new LlmUsage(prompt, completion, total != null ? total : prompt + completion);
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
