package com.example.aireviewer.domain;

import java.util.List;

/**
 * Structured output contract between the LLM and the application.
 * Spring AI's {@code BeanOutputConverter} serialises the JSON Schema for this record
 * into the system prompt so the LLM returns a response that matches it exactly.
 */
public record ReviewResponse(List<FindingDto> reviews) {

    /**
     * A single review finding returned by the LLM.
     *
     * @param file     File path as it appears in the diff.
     * @param line     New-file line number (must be within the valid line ranges).
     * @param severity One of: {@code High}, {@code Medium}, {@code Low}, {@code Nitpick}.
     * @param comment  Full Markdown-formatted review comment.
     */
    public record FindingDto(
            String file,
            int line,
            String severity,
            String comment
    ) {}
}
