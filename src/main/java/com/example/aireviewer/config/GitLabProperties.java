package com.example.aireviewer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GitLab connection settings.
 * Bind via {@code gitlab.*} in {@code application.yml}.
 * Secrets ({@code token}, {@code webhookSecret}) must come from environment variables
 * or Kubernetes Secrets — never committed to source control.
 */
@ConfigurationProperties(prefix = "gitlab")
public class GitLabProperties {

    private String url           = "https://gitlab.example.com";
    private String token         = "";
    private String webhookSecret = "";

    public String getUrl()             { return url; }
    public void setUrl(String url)     { this.url = url; }

    public String getToken()                   { return token; }
    public void setToken(String token)         { this.token = token; }

    public String getWebhookSecret()                       { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret)     { this.webhookSecret = webhookSecret; }
}
