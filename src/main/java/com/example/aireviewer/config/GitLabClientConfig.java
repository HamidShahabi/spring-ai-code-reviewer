package com.example.aireviewer.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

import java.util.concurrent.Executor;

/**
 * Infrastructure configuration.
 * Registers the {@link RestClient} used for all GitLab API calls and
 * exposes the async executor for the review worker threads.
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties({GitLabProperties.class, ReviewerProperties.class})
public class GitLabClientConfig {

    /**
     * RestClient pre-wired with the GitLab base URL and auth header.
     * All GitLabClient calls use this bean; no other code constructs HTTP clients.
     *
     * <p>Authenticates as the bot's own identity ({@code gitlab.bot-token}), so every
     * comment, reviewer assignment, and approval is correctly attributed to the reviewer
     * bot account rather than whatever admin token was used to set the app up.
     */
    @Bean
    public RestClient gitLabRestClient(GitLabProperties props) {
        return RestClient.builder()
                .baseUrl(props.getUrl())
                .defaultHeader("PRIVATE-TOKEN", props.getBotToken())
                .build();
    }

    /**
     * Thread pool for {@code @Async} review jobs.
     * Sized to handle up to {@code maxPoolSize} concurrent MR reviews.
     */
    @Bean(name = "reviewTaskExecutor")
    public Executor reviewTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("reviewer-");
        executor.initialize();
        return executor;
    }
}
