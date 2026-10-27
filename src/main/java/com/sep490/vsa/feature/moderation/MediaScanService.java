package com.sep490.vsa.feature.moderation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sep490.vsa.feature.media.MediaService;
import com.sep490.vsa.feature.media.MediaService.MediaRow;
import com.sep490.vsa.feature.moderation.ContentFilter.LinkProblem;
import com.sep490.vsa.feature.moderation.MediaReader.Flag;
import com.sep490.vsa.feature.moderation.MediaReader.Outcome;
import com.sep490.vsa.feature.moderation.MediaReader.Reading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Scans one uploaded file and records each result in {@code moderation_scans} (target MEDIA), then moves
 * {@code media.scan_status} from PROCESSING to PASSED or REJECTED. Reusable by reviews and events.
 * <p>
 * Two steps, each written as its own row:
 * <ol>
 *   <li>Photos: the {@link ImageModerator} (OpenAI) scores nudity, violence, self-harm…; FLAG rejects the photo. A
 *       photo it passed in an earlier run (only the next step failed) is not sent again.</li>
 *   <li>Photos and videos: the {@link MediaReader} (Gemini), when one is configured, reads the text in the file and
 *       the words spoken in a video. A violation it reports, or its refusal to look at the file, rejects it. The text
 *       that carries the uploader's message (line by line: each line is a separate text) and the speech then go
 *       through the banned keywords, and each of them on its own through the link and contact rule (BR-22) of
 *       {@link ContentFilter} for the file's owner; a QR code seen anywhere in the file counts as a link (photos are
 *       also read for QR codes on the server before they are saved, {@code ContentFilter.checkQrCodes}). Text that
 *       only belongs to the filmed place (shop signs, menus) is never checked: Japanese streets are full of パチンコ
 *       signs. The reason shown to the author (the keyword, link or contact detail found, the kind of violation,
 *       sensitive personal data) is stored with the FLAG row ({@link #rejectionReason}).</li>
 * </ol>
 * Without a reader, photos pass on the image scan alone, and videos pass once the upload checks (MP4/MOV, size,
 * ≤ 30 s) have passed, without a row: the free OpenAI endpoint reads images only.
 * <p>
 * The scanners are called outside any database transaction (they can take seconds); each result row and the status
 * change it brings are written in one short transaction. The file is read once per run, only when a step needs it.
 * A PASS row of the reader keeps only the lengths of the texts it read; a FLAG row keeps the texts and the reason for
 * an appeal, and {@link #forgetOldTexts} removes them after {@value #TEXT_KEEP_DAYS} days.
 * <p>
 * When a scanner cannot answer (network, quota, wrong key, a file that cannot be read: ERROR), the file stays
 * PROCESSING and is tried again later with a growing pause (1, 2, 4… up to 60 minutes after the last error), so an
 * outage does not turn into a flood of calls and {@code moderation_scans} rows. It is never given up: once the
 * scanner works again, the waiting posts go through. Only a file the reader says it cannot read (UNREADABLE: too much
 * text, a song) is refused, once it has said so {@value #UNREADABLE_TRIES} times. When the reader did not send the
 * file because this server's own request budget was used up for the minute, or because Gemini failed moments ago
 * (BUSY), nothing is written and the next run tries again without a pause.
 */
@Service
public class MediaScanService {

    private static final Logger log = LoggerFactory.getLogger(MediaScanService.class);
    private static final long MAX_PAUSE_MINUTES = 60;
    /** Each text the reader found is stored cut to this many characters (FLAG rows only). */
    static final int STORED_TEXT_MAX = 1_000;
    /** A link or contact detail named in a reason is cut to this many characters. */
    static final int NAMED_MAX = 100;
    /** The reader saying this many times that it cannot read the file (UNREADABLE) refuses it. */
    static final int UNREADABLE_TRIES = 3;
    /** The texts and reason of a FLAG row are kept this long for an appeal (as removed content), then removed. */
    static final int TEXT_KEEP_DAYS = 30;
    private static final String STORAGE_ERROR = "{\"error\":\"file could not be read\"}";

    // Why the reader's reading rejected a file: shown to the author after "vì" in the rejection notification
    static final String KEYWORD_REASON = "ảnh hoặc video có từ khóa không được phép “%s”";
    static final String SHORT_LINK_REASON = "ảnh hoặc video có link rút gọn “%s” (không ai được đăng link rút gọn)";
    static final String LINK_REASON =
            "ảnh hoặc video có link “%s” (chỉ tài khoản đã có tag định danh mới được đăng link)";
    static final String CONTACT_REASON = "ảnh hoặc video có số di động hoặc tài khoản Zalo, LINE, Telegram “%s”"
            + " (chỉ tài khoản đã có tag định danh mới được đăng)";
    static final String QR_REASON = "ảnh hoặc video có mã QR (mã QR được tính như link, chỉ tài khoản đã có tag định"
            + " danh mới được đăng)";
    static final String SPII_REASON = "ảnh hoặc video có thông tin cá nhân nhạy cảm (số giấy tờ, số thẻ, số tài khoản"
            + " ngân hàng), hãy che đi";
    static final String UNREADABLE_REASON = "hệ thống không đọc được ảnh hoặc video (có thể vì có quá nhiều chữ hoặc"
            + " có lời bài hát), hãy thay bằng ảnh hoặc video khác";
    /** The violations the reader saw that have their own reason; the others get the generic text. */
    static final Map<Flag, String> FLAG_REASONS = Map.of(
            Flag.GAMBLING_PROMOTION, "ảnh hoặc video quảng cáo cờ bạc, cá cược",
            Flag.SEX_SOLICITATION, "ảnh hoặc video mời chào mua bán dâm hoặc “bao nuôi”",
            Flag.DRUG_SALE, "ảnh hoặc video mua bán ma túy");

    private final MediaService media;
    private final ImageModerator moderator;
    private final MediaReader reader;
    private final ContentFilter filter;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public MediaScanService(MediaService media, ImageModerator moderator, MediaReader reader, ContentFilter filter,
                            ObjectMapper json, JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                            Clock clock) {
        this.media = media;
        this.moderator = moderator;
        this.reader = reader;
        this.filter = filter;
        this.json = json;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @return the media's scan status after this run: PASSED, REJECTED, or PROCESSING when a scanner could
     * not answer (it will be tried again)
     */
    public String scan(long mediaId) {
        MediaRow row = media.find(mediaId).orElse(null);
        if (row == null || row.deleted()) {
            return "REJECTED";
        }
        if (!"PROCESSING".equals(row.scanStatus())) {
            return row.scanStatus();
        }
        boolean image = "IMAGE".equals(row.type());
        boolean readerEnabled = reader.enabled();
        if (!image && !readerEnabled) {
            setStatus(mediaId, "PASSED");
            return "PASSED";
        }
        if (waitingAfterError(mediaId)) {
            return "PROCESSING";
        }

        // Step 1, photos: the image scanner
        byte[] bytes = null;
        if (image && !imageScanPassed(mediaId)) {
            bytes = readFile(mediaId, row.storageKey());
            ImageModerator.Verdict verdict = bytes == null
                    ? new ImageModerator.Verdict("ERROR", STORAGE_ERROR) : check(mediaId, bytes);
            boolean readerNext = "PASS".equals(verdict.result()) && readerEnabled;
            String status = readerNext ? "PROCESSING" : status(verdict.result());
            record(mediaId, moderator.engine(), verdict.result(), verdict.detail(), status);
            if (!readerNext) {
                return status;
            }
        }
        if (!readerEnabled) {
            // A photo the image scanner passed in an earlier run, while a reader was still configured
            setStatus(mediaId, "PASSED");
            return "PASSED";
        }

        // Step 2, photos and videos: the reader
        if (bytes == null) {
            bytes = readFile(mediaId, row.storageKey());
            if (bytes == null) {
                record(mediaId, reader.engine(), "ERROR", STORAGE_ERROR, "PROCESSING");
                return "PROCESSING";
            }
        }
        Reading reading = read(mediaId, bytes, row.mimeType());
        if (reading.outcome() == Outcome.BUSY) {
            // Not sent: the request budget is full or Gemini just failed. No row, so the next run tries without a pause
            return "PROCESSING";
        }
        Judgement judgement = switch (reading.outcome()) {
            case READ -> judge(reading, row.ownerId());
            case BLOCKED -> Judgement.flag(sensitiveData(reading) ? SPII_REASON : null);
            // An error of this file that trying again does not change: refused at the last try
            case UNREADABLE -> unreadableTries(mediaId) + 1 >= UNREADABLE_TRIES
                    ? Judgement.flag(UNREADABLE_REASON) : Judgement.ERROR;
            default -> Judgement.ERROR;
        };
        String status = status(judgement.result());
        record(mediaId, reader.engine(), judgement.result(), detail(reading, judgement), status);
        return status;
    }

    /**
     * The reason stored with the earliest FLAG of these files that has one (the text read from a photo or video held a
     * banned keyword, a link, a contact detail or a QR code; the reader saw gambling ads, sex solicitation or drug
     * sales; it refused a file showing sensitive personal data; it could not read the file), for the rejection
     * notification. Empty when none has one (the image scanner, another violation or refusal of the reader): the
     * notification then names the Community Rules.
     */
    public Optional<String> rejectionReason(Collection<Long> mediaIds) {
        if (mediaIds.isEmpty()) {
            return Optional.empty();
        }
        String marks = String.join(",", mediaIds.stream().map(x -> "?").toList());
        return jdbc.queryForList("""
                SELECT detail->>'$.reason' FROM moderation_scans
                WHERE target_type = 'MEDIA' AND target_id IN (%s) AND result = 'FLAG'
                  AND detail->>'$.reason' IS NOT NULL
                ORDER BY id LIMIT 1""".formatted(marks), String.class, mediaIds.toArray()).stream().findFirst();
    }

    /**
     * Every day at 03:40 Japan time: the texts read from photos and videos ({@code messageText}, {@code sceneText},
     * {@code speech}) and the reason ({@code reason}, which may name the phone number, link or chat id found) are
     * removed from the reader's rows older than {@value #TEXT_KEEP_DAYS} days. Only FLAG rows hold them: they are kept
     * as long as removed content is kept for an appeal, and are personal data that is not needed afterwards (the
     * author's notification keeps the reason). The flags and the rest of the detail stay.
     */
    @Scheduled(cron = "0 40 3 * * *", zone = "Asia/Tokyo")
    public void forgetOldTexts() {
        Timestamp before = Timestamp.from(clock.instant().minus(Duration.ofDays(TEXT_KEEP_DAYS)));
        int rows = jdbc.update("""
                UPDATE moderation_scans
                SET detail = JSON_REMOVE(detail, '$.messageText', '$.sceneText', '$.speech', '$.reason')
                WHERE target_type = 'MEDIA' AND engine = ? AND created_at < ?
                  AND JSON_CONTAINS_PATH(detail, 'one', '$.messageText', '$.sceneText', '$.speech', '$.reason')""",
                reader.engine(), before);
        log.info("Media scan clean-up: texts and reasons read from files removed from {} rows older than {} days",
                rows, TEXT_KEEP_DAYS);
    }

    // ---------------------------------------------------------------- the reading

    /** PASS, FLAG with the reason shown to the author (null: the generic text), or ERROR. */
    private record Judgement(String result, String reason) {

        static final Judgement PASS = new Judgement("PASS", null);
        static final Judgement ERROR = new Judgement("ERROR", null);

        static Judgement flag(String reason) {
            return new Judgement("FLAG", reason);
        }
    }

    /**
     * In this order, the first hit wins: a violation the reader saw (the reason of the first one that has its own); a
     * banned keyword in a line of the message text or in the speech; the link and contact rule (BR-22) in the message
     * text, then in the speech; a QR code (anywhere in the file) from an account without an identity tag. The scene
     * text is never checked.
     */
    private Judgement judge(Reading reading, long ownerId) {
        if (!reading.flags().isEmpty()) {
            return Judgement.flag(reading.flags().stream().map(FLAG_REASONS::get).filter(Objects::nonNull)
                    .findFirst().orElse(null));
        }
        Optional<String> keyword = bannedKeyword(reading);
        if (keyword.isPresent()) {
            return Judgement.flag(KEYWORD_REASON.formatted(keyword.get()));
        }
        // Each text on its own: glued, "… zalo" ending one and "minhanh99" starting the other would read as a chat id
        for (String text : List.of(reading.messageText(), reading.speech())) {
            Optional<LinkProblem> problem = filter.linkProblem(text, ownerId);
            if (problem.isPresent()) {
                return Judgement.flag(linkReason(problem.get(), text));
            }
        }
        if (reading.qrCode() && !filter.hasIdentityTag(ownerId)) {
            return Judgement.flag(QR_REASON);
        }
        return Judgement.PASS;
    }

    /**
     * The first banned keyword in a line of the message text, each line on its own (glued together, "Về nhà" and
     * "Cái này…" on the next line would read "nhà cái"), then in the speech as a whole.
     */
    private Optional<String> bannedKeyword(Reading reading) {
        return Stream.concat(reading.messageText().lines(), Stream.of(reading.speech()))
                .filter(text -> !text.isBlank())
                .map(filter::bannedKeyword)
                .flatMap(Optional::stream)
                .findFirst();
    }

    /** The reason naming the first link (the first shortened one) or contact detail the rule refused in the text. */
    private static String linkReason(LinkProblem problem, String text) {
        return switch (problem) {
            case SHORT_LINK -> SHORT_LINK_REASON.formatted(
                    named(ContentFilter.links(text).stream().filter(ContentFilter::isShortened)));
            case LINK_NEEDS_TAG -> LINK_REASON.formatted(named(ContentFilter.links(text).stream()));
            case CONTACT_NEEDS_TAG -> CONTACT_REASON.formatted(named(ContentFilter.contacts(text).stream()));
        };
    }

    private static String named(Stream<String> found) {
        return cut(found.findFirst().orElse("").strip(), NAMED_MAX);
    }

    /** A refusal because the file shows sensitive personal data (identity, card or bank-account numbers). */
    private boolean sensitiveData(Reading reading) {
        return "SPII".equals(readerDetail(reading.detail()).path("finishReason").asText(""));
    }

    /**
     * The row's detail: the reader's own detail object (anything else is kept under "reader"), plus for a reading the
     * QR code, the flags and either the texts of a FLAG (each cut to {@value #STORED_TEXT_MAX} characters, kept for an
     * appeal until {@link #forgetOldTexts}) or only their lengths for a PASS (nothing it read is kept); "unreadable"
     * when the reader could not read this file; the reason of a FLAG.
     */
    private String detail(Reading reading, Judgement judgement) {
        ObjectNode detail = readerDetail(reading.detail());
        if (reading.outcome() == Outcome.READ) {
            if ("FLAG".equals(judgement.result())) {
                detail.put("messageText", cut(reading.messageText(), STORED_TEXT_MAX));
                detail.put("sceneText", cut(reading.sceneText(), STORED_TEXT_MAX));
                detail.put("speech", cut(reading.speech(), STORED_TEXT_MAX));
            } else {
                detail.put("messageLength", length(reading.messageText()));
                detail.put("sceneLength", length(reading.sceneText()));
                detail.put("speechLength", length(reading.speech()));
            }
            detail.put("qrCode", reading.qrCode());
            ArrayNode flags = detail.putArray("flags");
            reading.flags().forEach(flag -> flags.add(flag.name()));
        }
        if (reading.outcome() == Outcome.UNREADABLE) {
            detail.put("unreadable", true);
        }
        if (judgement.reason() != null) {
            detail.put("reason", judgement.reason());
        }
        return detail.toString();
    }

    /** Characters, an emoji counting as one. */
    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    private ObjectNode readerDetail(String raw) {
        if (raw == null || raw.isBlank()) {
            return json.createObjectNode();
        }
        try {
            JsonNode node = json.readTree(raw);
            if (node != null && node.isObject()) {
                return (ObjectNode) node;
            }
        } catch (JsonProcessingException e) {
            // not JSON: kept as text below
        }
        ObjectNode detail = json.createObjectNode();
        detail.put("reader", raw);
        return detail;
    }

    /** At most {@code max} characters, without splitting a surrogate pair. */
    private static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max);
    }

    // ---------------------------------------------------------------- calls and rows

    /** The stored file, or null when it cannot be read (the step that needed it records an ERROR). */
    private byte[] readFile(long mediaId, String storageKey) {
        try {
            return media.read(storageKey);
        } catch (RuntimeException e) {
            log.warn("Could not scan media {}: {}", mediaId, e.getMessage());
            return null;
        }
    }

    /** Like {@link #read}: a scanner that throws or answers nothing is an error, and the file is tried again later. */
    private ImageModerator.Verdict check(long mediaId, byte[] bytes) {
        try {
            return Objects.requireNonNull(moderator.check(bytes), "no verdict");
        } catch (RuntimeException e) {
            log.warn("Could not scan media {}: {}", mediaId, e.getMessage());
            return new ImageModerator.Verdict("ERROR", "{\"error\":\"image scan failed\"}");
        }
    }

    /** {@link MediaReader#read} never throws; should it anyway, or answer nothing, the file is tried again later. */
    private Reading read(long mediaId, byte[] bytes, String mimeType) {
        try {
            return Objects.requireNonNull(reader.read(bytes, mimeType), "no reading");
        } catch (RuntimeException e) {
            log.warn("Could not read media {} with the media reader: {}", mediaId, e.getClass().getSimpleName());
            return Reading.error("{\"error\":\"media reader failed\"}");
        }
    }

    /** One result row, and the status it brings unless that is PROCESSING, in one short transaction. */
    private void record(long mediaId, String engine, String result, String detail, String status) {
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO moderation_scans (target_type, target_id, engine, result, detail, created_at)
                    VALUES ('MEDIA', ?, ?, ?, ?, ?)""",
                    mediaId, engine, result, detail, Timestamp.from(clock.instant()));
            if (!"PROCESSING".equals(status)) {
                setStatus(mediaId, status);
            }
        });
    }

    private static String status(String result) {
        return switch (result) {
            case "PASS" -> "PASSED";
            case "FLAG" -> "REJECTED";
            default -> "PROCESSING";
        };
    }

    /** True when the image scanner already passed this photo (in an earlier run whose reader step did not finish). */
    private boolean imageScanPassed(long mediaId) {
        Long passed = jdbc.queryForObject("""
                SELECT COUNT(*) FROM moderation_scans
                WHERE target_type = 'MEDIA' AND target_id = ? AND engine = ? AND result = 'PASS'""",
                Long.class, mediaId, moderator.engine());
        return passed != null && passed > 0;
    }

    /** How many times the reader already answered that it cannot read this file (ERROR rows marked "unreadable"). */
    private long unreadableTries(long mediaId) {
        Long tries = jdbc.queryForObject("""
                SELECT COUNT(*) FROM moderation_scans
                WHERE target_type = 'MEDIA' AND target_id = ? AND engine = ? AND result = 'ERROR'
                  AND detail->>'$.unreadable' = 'true'""",
                Long.class, mediaId, reader.engine());
        return tries == null ? 0 : tries;
    }

    /** True while the pause after this file's last scanner error has not run out yet. */
    private boolean waitingAfterError(long mediaId) {
        Map.Entry<Long, Timestamp> errors = jdbc.queryForObject("""
                SELECT COUNT(*), MAX(created_at) FROM moderation_scans
                WHERE target_type = 'MEDIA' AND target_id = ? AND result = 'ERROR'""",
                (rs, i) -> new java.util.AbstractMap.SimpleEntry<>(rs.getLong(1), rs.getTimestamp(2)), mediaId);
        if (errors == null || errors.getKey() == 0 || errors.getValue() == null) {
            return false;
        }
        Instant lastError = errors.getValue().toInstant();
        return clock.instant().isBefore(lastError.plus(pauseAfter(errors.getKey())));
    }

    /** 1 minute after the first error, then doubling, at most an hour. */
    static Duration pauseAfter(long errorCount) {
        long minutes = errorCount >= 7 ? MAX_PAUSE_MINUTES : Math.min(MAX_PAUSE_MINUTES, 1L << (errorCount - 1));
        return Duration.ofMinutes(minutes);
    }

    private void setStatus(long mediaId, String status) {
        jdbc.update("UPDATE media SET scan_status = ? WHERE id = ? AND scan_status = 'PROCESSING'", status, mediaId);
    }
}
