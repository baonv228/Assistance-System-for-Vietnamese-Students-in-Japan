package com.sep490.vsa.feature.moderation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sep490.vsa.feature.place.ApiUsageCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Picks the image scanner (OpenAI when a key is configured, the development stand-in otherwise) and the media reader
 * (Gemini when a key is configured, the development text or nothing otherwise).
 */
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

    @Bean
    public MediaReader mediaReader(@Value("${app.moderation.gemini.api-key:}") String apiKey,
                                   @Value("${app.moderation.gemini.model:gemini-3.5-flash-lite}") String model,
                                   @Value("${app.moderation.gemini.base-url:https://generativelanguage.googleapis.com}") String baseUrl,
                                   @Value("${app.moderation.gemini.requests-per-minute:10}") int requestsPerMinute,
                                   @Value("${app.moderation.dev-media-text:}") String devText,
                                   ObjectMapper json,
                                   ApiUsageCounter usage) {
        if (apiKey != null && !apiKey.isBlank()) {
            log.info("Photos and videos are also read by Gemini ({}, at most {} requests a minute).", model.strip(),
                    requestsPerMinute);
            return new GeminiMediaReader(apiKey.strip(), model, baseUrl, requestsPerMinute, json, usage);
        }
        if (devText != null && !devText.isBlank()) {
            log.warn("app.moderation.gemini.api-key is not set: photos and videos are not read, every file \"contains\""
                    + " the text of app.moderation.dev-media-text.");
            return new DevMediaReader(devText.strip());
        }
        log.warn("app.moderation.gemini.api-key is not set: the text in photos and videos is not read (photos are only"
                + " checked by the image scanner, videos are not checked).");
        return MediaReader.NONE;
    }
}
