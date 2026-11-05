package com.sep490.vsa.feature.moderation;

import java.util.List;

/**
 * Reads what a photo or video shows beyond the scores of {@link ImageModerator}: the text in it (typed on it, a
 * screenshot, a flyer…), the words spoken in a video, and the violations the Community Rules name. {@link MediaScanService}
 * asks it after a photo passed the {@link ImageModerator}, and for every video (which that scanner cannot read); the text
 * then goes through {@link ContentFilter}, so the Admin's keyword list stays the only list.
 * <p>
 * {@code GeminiMediaReader} is used when {@code app.moderation.gemini.api-key} is set; without a key, {@link #NONE}
 * (the step is skipped, as before) or {@code DevMediaReader} ({@code app.moderation.dev-media-text}).
 */
public interface MediaReader {

    /** How the reading went. */
    enum Outcome {
        /** Read: the texts, {@code qrCode} and flags of the {@link Reading} are filled. */
        READ,
        /**
         * The service refused to look at the file because it judged it unsafe: counts as a violation. The detail holds
         * {@code "finishReason":"SPII"} when the service stopped because the file shows sensitive personal data.
         */
        BLOCKED,
        /** Could not read it (network, quota, server error, an answer that cannot be parsed): try again after a pause. */
        ERROR,
        /**
         * The service answered but could not read this file (recitation of a song or text, too much text for one
         * answer, a file it could not process). Counted as an error for this file; after a few tries the file is
         * refused.
         */
        UNREADABLE,
        /**
         * Not sent: this server's own request budget is used up for now, or the service failed moments ago. Try again
         * at the next run, without a pause.
         */
        BUSY
    }

    /** Violations the reader may report; all of them reject the file. */
    enum Flag {
        /** Visible genitals, female nipples or bare buttocks, or sexual activity. */
        SEXUAL,
        /** A person who looks under 18 shown in a sexual or sexually suggestive way. */
        MINOR_SEXUAL,
        /** Gore: blood, wounds, mutilation, dead bodies. */
        GRAPHIC_VIOLENCE,
        /** Self-harm or suicide shown or encouraged. */
        SELF_HARM,
        /** Gambling itself: a game played for money or chips, a casino table, pachinko being played, betting slips. */
        GAMBLING,
        /** Advertising or inviting people to gambling or betting. */
        GAMBLING_PROMOTION,
        /** Offering or seeking paid sex, escort or "sugar dating" (bao nuôi, パパ活). */
        SEX_SOLICITATION,
        /** Offering to sell or buy illegal drugs. */
        DRUG_SALE
    }

    /**
     * @param messageText text that carries the uploader's message: typed or drawn on the photo or video (captions,
     *                    subtitles, stickers, watermarks), the content of a screenshot (not the phone, browser or app
     *                    interface), a card, flyer, note or sign held up to the camera, shown on a screen or lying on a
     *                    table as the subject of the photo; one line per sentence, even when it wraps in the picture.
     *                    Identity-document, bank-account and card numbers are written "[số]". Checked against the
     *                    keyword and link rules. Never null.
     * @param sceneText   text that belongs to a real place or object (shop signs, street and station signs, posters and
     *                    menus where they hang or are on display, packaging, documents). Stored, not checked. Never
     *                    null.
     * @param speech      words spoken in a video by the people filmed or a voice-over, in their own language, without
     *                    song lyrics or background sound. Never null.
     * @param qrCode      a QR code can be seen anywhere in the file, on a screen, a card, a poster or any other object
     *                    (treated like a link)
     * @param flags       violations seen; empty when none. Never null.
     * @param detail      small JSON object for {@code moderation_scans.detail} (model, finish or block reason, tokens,
     *                    error), without the texts
     */
    record Reading(Outcome outcome, String messageText, String sceneText, String speech, boolean qrCode,
                   List<Flag> flags, String detail) {

        public Reading {
            messageText = messageText == null ? "" : messageText;
            sceneText = sceneText == null ? "" : sceneText;
            speech = speech == null ? "" : speech;
            flags = flags == null ? List.of() : List.copyOf(flags);
        }

        public static Reading read(String messageText, String sceneText, String speech, boolean qrCode,
                                   List<Flag> flags, String detail) {
            return new Reading(Outcome.READ, messageText, sceneText, speech, qrCode, flags, detail);
        }

        public static Reading blocked(String detail) {
            return new Reading(Outcome.BLOCKED, "", "", "", false, List.of(), detail);
        }

        public static Reading error(String detail) {
            return new Reading(Outcome.ERROR, "", "", "", false, List.of(), detail);
        }

        public static Reading unreadable(String detail) {
            return new Reading(Outcome.UNREADABLE, "", "", "", false, List.of(), detail);
        }

        public static Reading busy(String detail) {
            return new Reading(Outcome.BUSY, "", "", "", false, List.of(), detail);
        }
    }

    /** The {@code moderation_scans.engine} value written with each result. */
    String engine();

    /** False when nothing reads the files: {@link MediaScanService} then skips this step. */
    boolean enabled();

    /**
     * Never throws: any failure is an {@link Outcome#ERROR} (or {@link Outcome#UNREADABLE}) reading.
     *
     * @param bytes    the stored file
     * @param mimeType {@code media.mime_type}: image/jpeg, video/mp4 or video/quicktime
     */
    Reading read(byte[] bytes, String mimeType);

    /** Nothing reads the files (no Gemini key and no development text). */
    MediaReader NONE = new MediaReader() {
        @Override
        public String engine() {
            return "GEMINI";
        }

        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public Reading read(byte[] bytes, String mimeType) {
            return Reading.error("{\"error\":\"no media reader configured\"}");
        }
    };
}
