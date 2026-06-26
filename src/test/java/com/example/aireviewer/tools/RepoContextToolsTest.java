package com.example.aireviewer.tools;

import com.example.aireviewer.domain.MrContext;
import com.example.aireviewer.infrastructure.GitLabApiClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RepoContextToolsTest {

    private static final String HEAD = "headsha123";

    private MrContext ctx() {
        return new MrContext(2L, 4L, "t", "d", "base", HEAD, "start");
    }

    @Test
    void getFile_resolvesAtPinnedHeadSha_andCachesRepeatCalls() {
        GitLabApiClient gitlab = mock(GitLabApiClient.class);
        when(gitlab.fetchFileAtRef(eq(2L), eq("src/Foo.java"), eq(HEAD))).thenReturn("class Foo {}");
        RepoContextTools tools = new RepoContextTools(gitlab, ctx(), 6, 400);

        assertThat(tools.getFile("src/Foo.java")).isEqualTo("class Foo {}");
        assertThat(tools.getFile("src/Foo.java")).isEqualTo("class Foo {}"); // served from cache

        // ref is pinned to headCommitSha, and the second call did not hit the API again
        verify(gitlab, times(1)).fetchFileAtRef(2L, "src/Foo.java", HEAD);
    }

    @Test
    void getFile_rejectsTraversalAndAbsolutePaths_withoutCallingApi() {
        GitLabApiClient gitlab = mock(GitLabApiClient.class);
        RepoContextTools tools = new RepoContextTools(gitlab, ctx(), 6, 400);

        assertThat(tools.getFile("../etc/passwd")).contains("Invalid path");
        assertThat(tools.getFile("/etc/passwd")).contains("Invalid path");
        verify(gitlab, never()).fetchFileAtRef(anyLong(), any(), any());
    }

    @Test
    void getFile_returnsNotFoundMessage_whenMissingAtRef() {
        GitLabApiClient gitlab = mock(GitLabApiClient.class);
        when(gitlab.fetchFileAtRef(anyLong(), any(), any())).thenReturn(null);
        RepoContextTools tools = new RepoContextTools(gitlab, ctx(), 6, 400);

        assertThat(tools.getFile("src/New.java")).contains("FILE_NOT_FOUND");
    }

    @Test
    void budget_isSharedAndExhausts() {
        GitLabApiClient gitlab = mock(GitLabApiClient.class);
        when(gitlab.fetchFileAtRef(anyLong(), any(), any())).thenAnswer(i -> "content of " + i.getArgument(1));
        RepoContextTools tools = new RepoContextTools(gitlab, ctx(), 2, 400);

        assertThat(tools.getFile("a.java")).isEqualTo("content of a.java");
        assertThat(tools.getFile("b.java")).isEqualTo("content of b.java");
        assertThat(tools.getFile("c.java")).contains("budget exhausted"); // 3rd call blocked
        verify(gitlab, times(2)).fetchFileAtRef(anyLong(), any(), any());
    }

    @Test
    void getFile_truncatesLongFiles() {
        GitLabApiClient gitlab = mock(GitLabApiClient.class);
        String big = "line\n".repeat(1000);
        when(gitlab.fetchFileAtRef(anyLong(), any(), any())).thenReturn(big);
        RepoContextTools tools = new RepoContextTools(gitlab, ctx(), 6, 50);

        String out = tools.getFile("Big.java");
        assertThat(out).contains("truncated").contains("1001 lines"); // 1000 \n -> 1001 split parts
        assertThat(out.lines().count()).isLessThan(60);
    }
}
