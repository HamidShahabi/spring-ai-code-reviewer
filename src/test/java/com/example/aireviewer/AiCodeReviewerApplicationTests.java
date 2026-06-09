package com.example.aireviewer;

import com.example.aireviewer.service.DiffChunker;
import com.example.aireviewer.config.ReviewerProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for pure-function components.
 *
 * Integration tests (full Spring context + PostgreSQL + LLM) require
 * Testcontainers and real API credentials — run with: {@code mvn test -Pintegration}
 */
class AiCodeReviewerApplicationTests {

    @Test
    void diffChunker_parsesValidLinesFromHunkHeaders() {
        ReviewerProperties props = new ReviewerProperties();
        DiffChunker chunker = new DiffChunker(props);

        String diff = """
                @@ -10,4 +10,6 @@ public class Foo {
                 unchanged
                +added line 1
                +added line 2
                -removed
                 unchanged
                @@ -30,3 +32,4 @@ public void bar() {
                +added line at 32
                 other line
                """;

        List<Integer> lines = chunker.parseValidLines(diff);

        assertTrue(lines.contains(10), "Should include start of first hunk");
        assertTrue(lines.contains(15), "Should include last line of first hunk");
        assertTrue(lines.contains(32), "Should include start of second hunk");
    }

    @Test
    void diffChunker_returnsEmptyForNoDiffHunks() {
        ReviewerProperties props = new ReviewerProperties();
        DiffChunker chunker = new DiffChunker(props);

        List<Integer> lines = chunker.parseValidLines("no hunk headers here");
        assertTrue(lines.isEmpty());
    }
}
