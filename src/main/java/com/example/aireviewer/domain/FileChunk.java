package com.example.aireviewer.domain;

import java.util.List;

/**
 * A single file's diff text, together with the set of valid new-file line numbers
 * parsed from the {@code @@} hunk headers. Only these lines are accepted by the
 * GitLab inline-comment API.
 */
public record FileChunk(
        String filePath,
        String diffText,
        List<Integer> validLines
) {}
