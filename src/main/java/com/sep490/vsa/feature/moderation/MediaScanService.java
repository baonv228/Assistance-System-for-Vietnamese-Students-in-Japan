package com.sep490.vsa.feature.moderation;

import com.sep490.vsa.feature.media.MediaService;
import com.sep490.vsa.feature.media.MediaService.MediaRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Scans one uploaded file and records the result in {@code moderation_scans} (target MEDIA), then moves
 * {@code media.scan_status} from PROCESSING to PASSED or REJECTED. Reusable by reviews and events.
 * <p>
 * The call to the scanner happens outside any database transaction (it can take seconds); only the result is
 * written in a short transaction.
 * <p>
 * Videos: the free OpenAI endpoint reads images only, so video frames are not scanned. A video passes once
 * the upload checks (MP4/MOV, size, ≤ 30 s) have passed.
 * <p>
 * When the scanner cannot answer (network, quota, wrong key), the file stays PROCESSING and is tried again
 * later with a growing pause (1, 2, 4… up to 60 minutes after the last error), so an outage does not turn
 * into a flood of calls and {@code moderation_scans} rows. It is never given up: once the scanner works
 * again, the waiting posts go through.
 */
@Service
public class MediaScanService {

    private static final Logger log = LoggerFactory.getLogger(MediaScanService.class);
    private static final long MAX_PAUSE_MINUTES = 60;

    private final MediaService media;
    private final ImageModerator moderator;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public MediaScanService(MediaService media, ImageModerator moderator, JdbcTemplate jdbc,
                            PlatformTransactionManager transactionManager, Clock clock) {
        this.media = media;
        this.moderator = moderator;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @return the media's scan status after this run: PASSED, REJECTED, or PROCESSING when the scanner could
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
        if ("VIDEO".equals(row.type())) {
            setStatus(mediaId, "PASSED");
            return "PASSED";
        }
        if (waitingAfterError(mediaId)) {
            return "PROCESSING";
        }

        ImageModerator.Verdict verdict;
        try {
            verdict = moderator.check(media.read(row.storageKey()));
        } catch (RuntimeException e) {
            log.warn("Could not scan media {}: {}", mediaId, e.getMessage());
            verdict = new ImageModerator.Verdict("ERROR", "{\"error\":\"file could not be read\"}");
        }
        ImageModerator.Verdict result = verdict;
        String status = switch (result.result()) {
            case "PASS" -> "PASSED";
            case "FLAG" -> "REJECTED";
            default -> "PROCESSING";
        };
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO moderation_scans (target_type, target_id, engine, result, detail, created_at)
                    VALUES ('MEDIA', ?, ?, ?, ?, ?)""",
                    mediaId, moderator.engine(), result.result(), result.detail(), Timestamp.from(clock.instant()));
            if (!"PROCESSING".equals(status)) {
                setStatus(mediaId, status);
            }
        });
        return status;
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
