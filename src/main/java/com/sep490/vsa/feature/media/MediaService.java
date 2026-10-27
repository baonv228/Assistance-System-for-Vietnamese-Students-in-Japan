package com.sep490.vsa.feature.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.SampleModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Photos and videos attached to posts (and later reviews, events, tag proofs): checks the file, stores it and
 * keeps the {@code media} row.
 * <ul>
 *   <li>Photos: JPG or PNG (HEIC is converted to JPG in the browser), at most 20 MB. Every photo is decoded and
 *       written again as JPG, at most 1600 px on the long side, which also removes EXIF and GPS data. Decoding is
 *       bounded: at most {@value #DECODE_SLOTS} photos at a time on the server, each within
 *       {@link #DECODE_MAX_BYTES} of memory.</li>
 *   <li>Videos: MP4 or MOV, at most {@code app.media.video-max-mb} (50 MB) and 30 seconds; the length is read
 *       from the file itself ({@link Mp4Duration}).</li>
 * </ul>
 * A new row starts in {@code scan_status = PROCESSING}; the automatic scan moves it to PASSED or REJECTED.
 */
@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    public static final int IMAGE_MAX_MB = 20;
    public static final int IMAGE_MAX_SIDE = 1600;
    public static final int VIDEO_MAX_SECONDS = 30;
    /** Phones often record 30.0x seconds for "30 seconds"; half a second of slack avoids refusing those. */
    private static final double VIDEO_SLACK_SECONDS = 0.5;
    /** Larger photos are refused (the page already sends ≤ 1600 px; this only stops crafted files). */
    private static final long MAX_PIXELS = 40_000_000L;
    /** Big photos are read at reduced resolution so decoding never holds more than about 3200 px per side. */
    private static final int DECODE_MAX_SIDE = IMAGE_MAX_SIDE * 2;
    /**
     * Memory one decoded photo may take (cần chốt). Pixels count by their real size: a 16-bit RGBA PNG takes 8 bytes a
     * pixel, so it is read at a lower resolution than an ordinary photo of the same size.
     */
    static final long DECODE_MAX_BYTES = 48L * 1024 * 1024;
    /** Photos decoded at the same time on the whole server (cần chốt). */
    static final int DECODE_SLOTS = 2;
    /** How long a photo waits for a free slot before the upload is refused with {@link #BUSY} (cần chốt). */
    static final Duration DECODE_WAIT = Duration.ofSeconds(20);
    /** Shared by every photo on the server; fair, so the photos are decoded in the order they arrived. */
    static final Semaphore DECODES = new Semaphore(DECODE_SLOTS, true);
    private static final Set<String> IMAGE_FORMATS = Set.of("jpeg", "png");
    private static final DateTimeFormatter MONTH_FOLDER = DateTimeFormatter.ofPattern("yyyy/MM");

    public static final String IMAGE_RULE = "Tối đa 5 ảnh, định dạng JPG, PNG hoặc HEIC, mỗi ảnh không quá 20 MB.";
    public static final String BUSY = "Máy chủ đang xử lý nhiều ảnh, vui lòng thử lại sau ít phút.";

    /** A checked file ready to be stored. {@code type} is IMAGE or VIDEO. */
    public record Prepared(String type, byte[] bytes, String mimeType, String extension, Integer width, Integer height,
                           Integer durationSec) {
    }

    public record MediaRow(Long id, Long ownerId, String type, String storageKey, String mimeType, String scanStatus,
                           boolean deleted) {
    }

    private final JdbcTemplate jdbc;
    private final MediaStorage storage;
    private final Clock clock;
    private final int videoMaxMb;
    private final Duration decodeWait;

    @Autowired
    public MediaService(JdbcTemplate jdbc, MediaStorage storage, Clock clock,
                        @Value("${app.media.video-max-mb:50}") int videoMaxMb) {
        this(jdbc, storage, clock, videoMaxMb, DECODE_WAIT);
    }

    /** @param decodeWait how long a photo waits for a decode slot (tests use a short wait) */
    MediaService(JdbcTemplate jdbc, MediaStorage storage, Clock clock, int videoMaxMb, Duration decodeWait) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.clock = clock;
        this.videoMaxMb = videoMaxMb;
        this.decodeWait = decodeWait;
    }

    public int videoMaxMb() {
        return videoMaxMb;
    }

    // ---------------------------------------------------------------- checks (nothing is written)

    /**
     * Decodes, shrinks and re-encodes one photo. Throws {@link InvalidMediaException} with the rule to show.
     * At most {@value #DECODE_SLOTS} photos are worked on at the same time on the whole server; one that finds no free
     * slot within {@link #DECODE_WAIT} is refused with {@link #BUSY}.
     */
    public Prepared prepareImage(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > IMAGE_MAX_MB * 1024L * 1024L) {
            throw new InvalidMediaException(IMAGE_RULE);
        }
        boolean slot;
        try {
            slot = DECODES.tryAcquire(decodeWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            slot = false;
        }
        if (!slot) {
            throw new InvalidMediaException(BUSY);
        }
        try {
            BufferedImage source;
            try {
                source = decode(file.getBytes());
            } catch (IOException e) {
                source = null;
            }
            if (source == null) {
                throw new InvalidMediaException(IMAGE_RULE);
            }
            BufferedImage fitted = fit(source, IMAGE_MAX_SIDE);
            try {
                return new Prepared("IMAGE", jpeg(fitted), "image/jpeg", "jpg", fitted.getWidth(), fitted.getHeight(),
                        null);
            } catch (IOException e) {
                throw new InvalidMediaException(IMAGE_RULE);
            }
        } finally {
            DECODES.release();
        }
    }

    /**
     * Checks type, size and length (≤ 30 s) of one video. The file is stored as uploaded, without its metadata: where
     * it was filmed, the phone model… ({@link Mp4Duration#stripMetadata}), as photos lose their EXIF.
     */
    public Prepared prepareVideo(MultipartFile file) {
        String rule = "Video phải là MP4 hoặc MOV, dài tối đa " + VIDEO_MAX_SECONDS + " giây và không quá "
                + videoMaxMb + " MB.";
        if (file == null || file.isEmpty() || file.getSize() > videoMaxMb * 1024L * 1024L) {
            throw new InvalidMediaException(rule);
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new InvalidMediaException(rule);
        }
        Optional<Mp4Duration.Info> read;
        try {
            read = Mp4Duration.read(bytes);
        } catch (RuntimeException e) {
            // A file the parser cannot cope with is an unreadable video for the user, never a server error (500)
            log.warn("Could not read the length of an uploaded video: {}", e.toString());
            read = Optional.empty();
        }
        Mp4Duration.Info info = read
                .orElseThrow(() -> new InvalidMediaException("Không đọc được video. Hãy dùng video MP4 hoặc MOV."));
        if (info.seconds() > VIDEO_MAX_SECONDS + VIDEO_SLACK_SECONDS) {
            throw new InvalidMediaException("Video dài " + Math.round(info.seconds()) + " giây. Video tối đa "
                    + VIDEO_MAX_SECONDS + " giây.");
        }
        int seconds = (int) Math.min(VIDEO_MAX_SECONDS, Math.round(info.seconds()));
        try {
            Mp4Duration.stripMetadata(bytes);
        } catch (RuntimeException e) {
            // The length was read, so the boxes are sound; a file the walk still trips on is refused, never kept as is
            log.warn("Could not remove the metadata of an uploaded video: {}", e.toString());
            throw new InvalidMediaException("Không đọc được video. Hãy dùng video MP4 hoặc MOV.");
        }
        return info.quickTime()
                ? new Prepared("VIDEO", bytes, "video/quicktime", "mov", null, null, seconds)
                : new Prepared("VIDEO", bytes, "video/mp4", "mp4", null, null, seconds);
    }

    // ---------------------------------------------------------------- writes

    /**
     * Stores the file under {@code folder/yyyy/MM/<uuid>.<ext>} and inserts its {@code media} row
     * (PROCESSING). Runs inside the caller's transaction: if that transaction rolls back, the file is deleted.
     *
     * @return the new media id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long save(long ownerId, String folder, Prepared file) {
        String key = folder + "/" + MONTH_FOLDER.format(clock.instant().atZone(ZoneOffset.UTC)) + "/"
                + UUID.randomUUID() + "." + file.extension();
        storage.put(key, file.bytes());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteQuietly(key);
                }
            }
        });
        Timestamp now = Timestamp.from(clock.instant());
        KeyHolder id = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO media (owner_id, media_type, storage_key, mime_type, size_bytes, duration_sec, width,
                                       height, scan_status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PROCESSING', ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, ownerId);
            ps.setString(2, file.type());
            ps.setString(3, key);
            ps.setString(4, file.mimeType());
            ps.setLong(5, file.bytes().length);
            ps.setObject(6, file.durationSec());
            ps.setObject(7, file.width());
            ps.setObject(8, file.height());
            ps.setTimestamp(9, now);
            return ps;
        }, id);
        return id.getKey().longValue();
    }

    /**
     * Marks media as deleted and removes their files once the transaction commits (content deleted by its
     * author, or photos that failed the scan). Rows are kept so moderation history still points somewhere.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void discard(Collection<Long> mediaIds) {
        if (mediaIds.isEmpty()) {
            return;
        }
        List<String> keys = new ArrayList<>();
        for (Long id : mediaIds) {
            find(id).filter(m -> !m.deleted()).ifPresent(m -> keys.add(m.storageKey()));
        }
        String marks = String.join(",", mediaIds.stream().map(x -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(clock.instant()));
        args.addAll(mediaIds);
        jdbc.update("UPDATE media SET deleted_at = ? WHERE deleted_at IS NULL AND id IN (" + marks + ")", args.toArray());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                keys.forEach(MediaService.this::deleteQuietly);
            }
        });
    }

    // ---------------------------------------------------------------- reads

    public Optional<MediaRow> find(long id) {
        return jdbc.query("""
                SELECT id, owner_id, media_type, storage_key, mime_type, scan_status, deleted_at IS NOT NULL
                FROM media WHERE id = ?""",
                (rs, i) -> new MediaRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getBoolean(7)), id).stream().findFirst();
    }

    public byte[] read(String storageKey) {
        return storage.get(storageKey);
    }

    // ---------------------------------------------------------------- image helpers

    /**
     * JPG or PNG only; refuses huge pixel counts before decoding them (decompression bombs) and reads big photos at a
     * reduced resolution ({@link #subsampling}), so the decoded image stays within {@link #DECODE_MAX_BYTES}.
     */
    static BufferedImage decode(byte[] bytes) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                if (!IMAGE_FORMATS.contains(reader.getFormatName().toLowerCase(Locale.ROOT))) {
                    return null;
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long) width * height;
                if (pixels <= 0 || pixels > MAX_PIXELS) {
                    return null;
                }
                int bytesPerPixel = bytesPerPixel(reader);
                if (bytesPerPixel <= 0) {
                    return null;                      // no pixel layout to read the photo into
                }
                // Read every n-th pixel of very large photos: memory stays small and fit() still has detail to spare
                int step = subsampling(width, height, bytesPerPixel);
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;                              // corrupt file or unsupported colour model
        }
    }

    /**
     * Read every {@code step}-th pixel: at most about {@value #DECODE_MAX_SIDE} px per side, and the decoded image
     * (ceil(w / step) × ceil(h / step) × bytes per pixel) within {@link #DECODE_MAX_BYTES}.
     */
    static int subsampling(int width, int height, int bytesPerPixel) {
        int step = Math.max(1, (int) Math.ceil((double) Math.max(width, height) / DECODE_MAX_SIDE));
        while (decodedBytes(width, height, step, bytesPerPixel) > DECODE_MAX_BYTES) {
            step++;
        }
        return step;
    }

    static long decodedBytes(int width, int height, int step, int bytesPerPixel) {
        return (long) ((width + step - 1) / step) * ((height + step - 1) / step) * bytesPerPixel;
    }

    /**
     * Bytes one decoded pixel takes: the larger of the file's own layout ({@code getRawImageType}) and the type the
     * reader decodes into by default (the first of {@code getImageTypes}); 0 when the reader knows neither.
     */
    private static int bytesPerPixel(ImageReader reader) throws IOException {
        int most = 0;
        ImageTypeSpecifier raw = reader.getRawImageType(0);
        if (raw != null) {
            most = bytesPerPixel(raw);
        }
        Iterator<ImageTypeSpecifier> types = reader.getImageTypes(0);
        if (types != null && types.hasNext()) {
            most = Math.max(most, bytesPerPixel(types.next()));
        }
        return most;
    }

    /** Data elements per pixel × their size: 4 for packed 8-bit RGB(A) in an int, 8 for 16-bit RGBA, 1 for grey. */
    static int bytesPerPixel(ImageTypeSpecifier type) {
        SampleModel model = type.getSampleModel(1, 1);
        int elementBytes = Math.max(1, DataBuffer.getDataTypeSize(model.getDataType()) / 8);
        return Math.max(1, model.getNumDataElements()) * elementBytes;
    }

    /** Scales down to fit {@code maxSide} (never up) onto a white RGB canvas, halving in steps for quality. */
    static BufferedImage fit(BufferedImage source, int maxSide) {
        int w = source.getWidth();
        int h = source.getHeight();
        double scale = Math.min(1.0, (double) maxSide / Math.max(w, h));
        int targetW = Math.max(1, (int) Math.round(w * scale));
        int targetH = Math.max(1, (int) Math.round(h * scale));

        BufferedImage current = source;
        while (current.getWidth() / 2 >= targetW && current.getHeight() / 2 >= targetH) {
            current = draw(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        return draw(current, targetW, targetH);
    }

    private static BufferedImage draw(BufferedImage source, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(source, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    static byte[] jpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.85f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete media file {}: {}", key, e.getMessage());
        }
    }
}
