package com.sep490.vsa.feature.moderation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stand-in used when no OpenAI key is configured (local development, tests). It does not look at the photo:
 * it answers {@code app.moderation.dev-result} (PASS by default; set FLAG to try the rejected flow).
 */
public class DevImageModerator implements ImageModerator {

    private static final Logger log = LoggerFactory.getLogger(DevImageModerator.class);

    private final String result;

    public DevImageModerator(String result) {
        this.result = "FLAG".equalsIgnoreCase(result) ? "FLAG" : "PASS";
    }

    @Override
    public String engine() {
        // moderation_scans only accepts KEYWORD, OPENAI or GEMINI; the detail says this was the stand-in
        return "OPENAI";
    }

    @Override
    public Verdict check(byte[] jpeg) {
        log.info("No OpenAI key configured: image scan answered {} without checking (app.moderation.dev-result)", result);
        return new Verdict(result, "{\"mode\":\"dev\",\"note\":\"No OpenAI key configured; photo not checked\"}");
    }
}
