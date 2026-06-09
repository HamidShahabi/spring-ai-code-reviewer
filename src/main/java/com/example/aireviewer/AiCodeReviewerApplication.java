package com.example.aireviewer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication
@EnableRetry
public class AiCodeReviewerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiCodeReviewerApplication.class, args);
    }
}
