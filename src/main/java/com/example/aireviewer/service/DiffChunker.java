package com.example.aireviewer.service;

import com.example.aireviewer.domain.FileChunk;
import com.example.aireviewer.infrastructure.GitLabClient;

import java.util.List;

/**
 * Converts raw GitLab diff files into {@link FileChunk} objects.
 *
 * <p>Implementations are expected to:
 * <ol>
 *   <li>Skip deleted files and files matching the ignore-extensions list.</li>
 *   <li>Parse valid new-file line numbers from the diff.</li>
 *   <li>Limit the total number of chunks to {@code reviewer.max-files-per-review}.</li>
 * </ol>
 */
public interface DiffChunker {

    List<FileChunk> split(List<GitLabClient.DiffFile> diffFiles);

    /** Parses the valid (commentable) new-file line numbers out of a single file's diff text. */
    List<Integer> parseValidLines(String diffText);
}
