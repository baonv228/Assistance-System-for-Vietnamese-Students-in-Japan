package com.sep490.vsa.feature.moderation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Picks the image scanner: OpenAI when a key is configured, the development stand-in otherwise. */
@Configuration
public class ModerationConfig {

    private static final Logger log = LoggerFactory.getLogger(ModerationConfig.class);

    @Bean
    public ImageModerator imageModerator(@Value("${app.moderation.openai.api-key:}") String apiKey,
                                         @Value("${app.moderation.openai.base-url:https://api.openai.com}") String baseUrl,
                                         @Value("${app.moderation.dev-result:PASS}") String devResult,
                                         ObjectMapper json) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("app.moderation.openai.api-key is not set: photos are not really scanned (dev result {}).", devResult);
            return new DevImageModerator(devResult);
        }
        return new OpenAiImageModerator(apiKey.strip(), baseUrl, json);
    }
}
