package com.sep490.vsa.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled} jobs (e.g. the hourly featured-post snapshot in feature/blog). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
