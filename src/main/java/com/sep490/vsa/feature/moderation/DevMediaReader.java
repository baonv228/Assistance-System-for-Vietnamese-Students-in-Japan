package com.sep490.vsa.feature.moderation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Stand-in used when no Gemini key is configured but {@code app.moderation.dev-media-text} is set (local
 * development). It does not look at the file: every photo and video "contains" that text as its message, so the
 * keyword and link rejections can be tried without a key.
 */
public class DevMediaReader implements MediaReader {

    private static final Logger log = LoggerFactory.getLogger(DevMediaReader.class);

    private final String text;

    public DevMediaReader(String text) {
        this.text = text == null ? "" : text;
    }

    @Override
    public String engine() {
        return "GEMINI";
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Reading read(byte[] bytes, String mimeType) {
        log.info("No Gemini key configured: media reading answered \"{}\" without reading the file"
                + " (app.moderation.dev-media-text)", text);
        return Reading.read(text, "", "", false, List.of(),
                "{\"mode\":\"dev\",\"note\":\"No Gemini key configured; file not read\"}");
    }
}
