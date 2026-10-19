package com.sep490.vsa.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Turns on {@code @Async} (e.g. the photo scan that starts after a post is saved, in feature/blog).
 * Uses Spring Boot's default task executor ({@code spring.task.execution.*}).
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
