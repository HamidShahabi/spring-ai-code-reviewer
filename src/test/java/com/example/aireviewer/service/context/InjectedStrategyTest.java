package com.example.aireviewer.service.context;

import com.example.aireviewer.domain.FileChunk;
import com.example.aireviewer.domain.MrContext;
import com.example.aireviewer.infrastructure.GitLabClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class InjectedStrategyTest {

    private static final String HEAD = "headsha999";

    private MrContext ctx() {
        return new MrContext(2L, 4L, "t", "d", "base", HEAD, "start");
    }

    private FileChunk chunk(String path) {
        return new FileChunk(path, "@@ -1 +1 @@\n+code", List.of(1));
    }

    @Test
    void injectsFullFileAtHeadSha_andCaches() {
        GitLabClient gitlab = mock(GitLabClient.class);
        when(gitlab.fetchFileAtRef(eq(2L), eq("src/Foo.java"), eq(HEAD))).thenReturn("class Foo { int x; }");
        InjectedStrategy s = new InjectedStrategy(gitlab, ctx(), 400);

        String out1 = s.injectedContextFor(chunk("src/Foo.java"));
        String out2 = s.injectedContextFor(chunk("src/Foo.java"));

        assertThat(out1).contains("Full content of `src/Foo.java`").contains("class Foo { int x; }");
        assertThat(out2).isEqualTo(out1);
        verify(gitlab, times(1)).fetchFileAtRef(2L, "src/Foo.java", HEAD); // second call cached
        assertThat(s.tools()).isNull();      // injected never attaches tools
        assertThat(s.name()).isEqualTo("injected");
    }

    @Test
    void returnsEmpty_whenFileMissingAtRef() {
        GitLabClient gitlab = mock(GitLabClient.class);
        when(gitlab.fetchFileAtRef(anyLong(), anyString(), anyString())).thenReturn(null);
        InjectedStrategy s = new InjectedStrategy(gitlab, ctx(), 400);

        assertThat(s.injectedContextFor(chunk("src/New.java"))).isEmpty();
    }

    @Test
    void truncatesLongFiles() {
        GitLabClient gitlab = mock(GitLabClient.class);
        when(gitlab.fetchFileAtRef(anyLong(), anyString(), anyString())).thenReturn("x\n".repeat(1000));
        InjectedStrategy s = new InjectedStrategy(gitlab, ctx(), 50);

        assertThat(s.injectedContextFor(chunk("Big.java"))).contains("truncated").contains("1001 lines");
    }
}
