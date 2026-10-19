package com.sep490.vsa.feature.moderation;

/**
 * Automatic image check (the "image moderation" half of GBR-18). {@link OpenAiImageModerator} is used when
 * {@code app.moderation.openai.api-key} is set; otherwise {@link DevImageModerator} answers without calling
 * anything, like the console mail sender does for e-mail.
 */
public interface ImageModerator {

    /**
     * @param result PASS, FLAG (violates the rules) or ERROR (could not check; try again later)
     * @param detail JSON stored in {@code moderation_scans.detail}
     */
    record Verdict(String result, String detail) {
    }

    /** The {@code moderation_scans.engine} value written with each result. */
    String engine();

    Verdict check(byte[] jpeg);
}
