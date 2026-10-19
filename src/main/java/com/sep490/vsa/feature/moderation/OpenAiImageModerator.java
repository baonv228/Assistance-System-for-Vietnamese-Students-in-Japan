package com.sep490.vsa.feature.moderation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Checks photos with the OpenAI Moderation endpoint (model {@code omni-moderation-latest}), which is free to
 * use and accepts images. A photo is rejected when OpenAI marks it {@code flagged}. Network or quota problems
 * return ERROR so the post stays in PROCESSING and the retry job tries again.
 */
public class OpenAiImageModerator implements ImageModerator {

    private static final Logger log = LoggerFactory.getLogger(OpenAiImageModerator.class);
    private static final String MODEL = "omni-moderation-latest";

    private final RestClient client;
    private final ObjectMapper json;

    public OpenAiImageModerator(String apiKey, String baseUrl, ObjectMapper json) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(30_000);
        this.client = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        this.json = json;
    }

    @Override
    public String engine() {
        return "OPENAI";
    }

    @Override
    public Verdict check(byte[] jpeg) {
        Map<String, Object> body = Map.of(
                "model", MODEL,
                "input", List.of(Map.of(
                        "type", "image_url",
                        "image_url", Map.of("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg)))));
        try {
            JsonNode response = client.post().uri("/v1/moderations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode result = response == null ? null : response.path("results").path(0);
            if (result == null || result.isMissingNode()) {
                return new Verdict("ERROR", error("empty response"));
            }
            boolean flagged = result.path("flagged").asBoolean(false);
            return new Verdict(flagged ? "FLAG" : "PASS", detail(response, result, flagged));
        } catch (RuntimeException e) {
            log.warn("OpenAI image moderation failed: {}", e.getMessage());
            return new Verdict("ERROR", error(e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    /** Keeps the flagged categories and the highest scores, not the whole response. */
    private String detail(JsonNode response, JsonNode result, boolean flagged) {
        ObjectNode detail = json.createObjectNode();
        detail.put("model", response.path("model").asText(MODEL));
        detail.put("flagged", flagged);
        List<String> categories = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = result.path("categories").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getValue().asBoolean(false)) {
                categories.add(e.getKey());
            }
        }
        detail.set("categories", json.valueToTree(categories));
        ObjectNode scores = detail.putObject("scores");
        for (Iterator<Map.Entry<String, JsonNode>> it = result.path("category_scores").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getValue().asDouble() >= 0.01) {
                scores.put(e.getKey(), Math.round(e.getValue().asDouble() * 1000) / 1000.0);
            }
        }
        return detail.toString();
    }

    private String error(String message) {
        ObjectNode detail = json.createObjectNode();
        detail.put("error", message.length() > 300 ? message.substring(0, 300) : message);
        return detail.toString();
    }
}
