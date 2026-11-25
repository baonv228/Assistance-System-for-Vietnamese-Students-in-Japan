package com.sep490.vsa.usecase.blog;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.sep490.vsa.usecase.UseCaseTestBase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Helpers shared by the tests of Create Post, Update Post and Delete Post (UC 1.5 – 1.7): requests to
 * /api/blogs, reading the rows a post leaves behind, and the files of a post.
 */
abstract class PostWriteTestSupport extends UseCaseTestBase {

    /** MSG34 */
    static final String MSG34 = "Bài viết của bạn đã được đăng.";
    /** MSG37 */
    static final String MSG37 = "Hãy nhập tiêu đề và nội dung cho bài viết.";
    static final String SENT_FOR_SCAN = "Đã gửi. Ảnh và video đang được quét, trong lúc này chỉ bạn thấy bài.";
    static final String SAVED = "Đã lưu thay đổi.";
    static final String SAVED_FOR_SCAN = "Đã lưu thay đổi. Ảnh và video đang được quét, trong lúc này chỉ bạn thấy bài.";
    static final String TOPIC_REQUIRED = "Hãy chọn ít nhất 1 chủ đề. Không có chủ đề phù hợp thì chọn “Khác”.";
    static final String IMAGE_RULE = "Tối đa 5 ảnh, định dạng JPG, PNG hoặc HEIC, mỗi ảnh không quá 20 MB.";
    static final String LINK_NEEDS_TAG = "Chỉ tài khoản đã có tag định danh mới được chèn link.";
    static final String CONTACT_NEEDS_TAG =
            "Chỉ tài khoản đã có tag định danh mới được ghi số điện thoại hoặc tài khoản Zalo, LINE, Telegram.";
    static final String SHORT_LINK = "Không dùng link rút gọn (bit.ly, tinyurl…). Hãy dán link đầy đủ.";
    /** MSG44 for posts, with the default settings (7 days, 3 posts or reviews a day) */
    static final String MSG44_POSTS = "Bạn đã đạt giới hạn đăng bài trong ngày của tài khoản mới (dưới 7 ngày: tối đa "
            + "3 bài viết hoặc review mỗi ngày). Vui lòng thử lại vào ngày mai.";
    static final String VIDEO_LIMIT = "Bạn đã đạt giới hạn video trong ngày của tài khoản mới (tối đa 1 video mỗi "
            + "ngày). Hãy bỏ video hoặc thử lại vào ngày mai.";
    static final String PLACE_GONE = "Có địa điểm không còn trên bản đồ. Hãy chọn lại địa điểm.";

    @Value("${app.media.local-dir}")
    String mediaDir;

    /** MSG36 naming the keyword. */
    static String msg36(String keyword) {
        return "Chưa đăng được: nội dung chứa từ khóa không được phép “" + keyword + "”. Hãy sửa rồi gửi lại.";
    }

    // ---------------------------------------------------------------- accounts

    /** An ACTIVE Member created exactly {@code age} ago (for the 7-day new-account boundary). */
    long memberAged(Duration age) {
        String email = "uc-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, email_verified_at, created_at)
                VALUES (?, ?, 'USER', 'ACTIVE', TRUE, ?, ?)""",
                email, "UC Writer " + ThreadLocalRandom.current().nextInt(1000, 9999), Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now().minus(age)));
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    // ---------------------------------------------------------------- topics and places

    /** The first {@code n} active topics in the Blog's order. */
    List<Long> activeTopics(int n) {
        return jdbc.queryForList("SELECT id FROM blog_topics WHERE is_active = TRUE ORDER BY sort_order, id LIMIT ?",
                Long.class, n);
    }

    /** A topic switched off by the team, created for this test. */
    long inactiveTopic() {
        String name = "UC tắt " + token();
        jdbc.update("INSERT INTO blog_topics (name, is_active, sort_order) VALUES (?, FALSE, 999)", name);
        return jdbc.queryForObject("SELECT id FROM blog_topics WHERE name = ?", Long.class, name);
    }

    /** An ACTIVE place that came from OpenStreetMap: it has an {@code osm_id} such as "N123…". */
    String osmPlace(String name, String status) {
        String osmId = "N9" + ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L);
        long creator = account("MODERATOR", 30);
        jdbc.update("""
                INSERT INTO places (osm_id, name_vi, name_en, category_id, address, lat, lng, source, status, created_by)
                VALUES (?, ?, ?, (SELECT MIN(id) FROM place_categories), 'Tokyo', 35.7, 139.7, 'OSM', ?, ?)""",
                osmId, name, name, status, creator);
        return osmId;
    }

    long placeIdOfOsm(String osmId) {
        return jdbc.queryForObject("SELECT id FROM places WHERE osm_id = ?", Long.class, osmId);
    }

    // ---------------------------------------------------------------- requests

    /** The JSON part of a new or edited post. */
    static Map<String, Object> post(String title, String content, List<Long> topicIds, List<Long> placeIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("content", content);
        body.put("topicIds", topicIds);
        body.put("placeIds", placeIds);
        return body;
    }

    /** An edited post that keeps these attached files ({@code keepMediaIds}). */
    static Map<String, Object> edit(String title, String content, List<Long> topicIds, List<Long> placeIds,
                                    List<Long> keepMediaIds) {
        Map<String, Object> body = post(title, content, topicIds, placeIds);
        body.put("keepMediaIds", keepMediaIds);
        return body;
    }

    /** "Đăng bài": POST /api/blogs, multipart part "post" + files. */
    ResultActions create(long userId, Map<String, Object> body, MockMultipartFile... files) throws Exception {
        return multipartAs(userId, "POST", "/api/blogs", "post", body, files);
    }

    /** "Lưu thay đổi": PUT /api/blogs/{id}, multipart part "post" + new files. */
    ResultActions update(Long userId, long blogId, Map<String, Object> body, MockMultipartFile... files)
            throws Exception {
        return multipartAs(userId, "PUT", "/api/blogs/" + blogId, "post", body, files);
    }

    /** The id of the post the answer created (status 200 expected). */
    long createdId(ResultActions result) throws Exception {
        result.andExpect(status().isOk());
        return data(result).get("id").asLong();
    }

    /** A published text post written through the API (+10 points, counted in today's usage). */
    long writePost(long userId, String title) throws Exception {
        return createdId(create(userId, post(title, "Nội dung " + title, List.of(topic("Kinh nghiệm")), List.of())));
    }

    void awaitStatus(long blogId, String status) {
        await().atMost(Duration.ofSeconds(20)).until(() -> status.equals(blogStatus(blogId)));
    }

    // ---------------------------------------------------------------- rows

    long postsBy(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM blogs WHERE author_id = ?", Long.class, userId);
    }

    /** posts / videos of today (Japan date) in user_daily_usage; 0 when there is no row. */
    int usage(long userId, String column) {
        List<Integer> rows = jdbc.queryForList("SELECT " + column + " FROM user_daily_usage WHERE user_id = ? "
                + "AND usage_date = ?", Integer.class, userId, java.sql.Date.valueOf(japanToday()));
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    List<Long> topicsOf(long blogId) {
        return jdbc.queryForList("SELECT topic_id FROM blog_topic_links WHERE blog_id = ? ORDER BY topic_id",
                Long.class, blogId);
    }

    List<Long> placesOf(long blogId) {
        return jdbc.queryForList("SELECT place_id FROM blog_place_links WHERE blog_id = ? ORDER BY place_id",
                Long.class, blogId);
    }

    /** The media attached to a post, in the order they show. */
    List<Long> mediaOf(long blogId) {
        return jdbc.queryForList("SELECT media_id FROM blog_media WHERE blog_id = ? ORDER BY sort_order", Long.class,
                blogId);
    }

    String storageKey(long mediaId) {
        return jdbc.queryForObject("SELECT storage_key FROM media WHERE id = ?", String.class, mediaId);
    }

    boolean mediaDeleted(long mediaId) {
        return jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM media WHERE id = ?", Boolean.class, mediaId);
    }

    Path mediaFile(String storageKey) {
        return Path.of(mediaDir).resolve(storageKey);
    }

    boolean fileExists(String storageKey) {
        return Files.exists(mediaFile(storageKey));
    }

    /** How many times the post paid its +10 (point_transactions BLOG_CREATED). */
    int awards(long blogId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM point_transactions WHERE reason = 'BLOG_CREATED' "
                + "AND ref_type = 'BLOG' AND ref_id = ?", Integer.class, blogId);
    }

    /** The texts of the notifications of this type about this post. */
    List<String> notifications(long userId, String type, long blogId) {
        return jdbc.queryForList("SELECT content FROM notifications WHERE user_id = ? AND type = ? AND ref_id = ? "
                + "ORDER BY id", String.class, userId, type, blogId);
    }

    /** A reason of the "Báo cáo" dialog (Lừa đảo, Sai sự thật, Xúc phạm, Spam). */
    long reportReason() {
        return jdbc.queryForObject("SELECT MIN(id) FROM reason_options WHERE scope = 'REPORT' AND is_active = TRUE",
                Long.class);
    }

    // ---------------------------------------------------------------- files

    /** A photo holding a QR code that leads to the given text (a link counts as a link, BR-22). */
    static MockMultipartFile qrPhoto(String text) throws Exception {
        BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 400, 400,
                Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H, EncodeHintType.MARGIN, 2));
        BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < matrix.getWidth(); x++) {
            for (int y = 0; y < matrix.getHeight(); y++) {
                image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new MockMultipartFile("images", "qr.png", "image/png", out.toByteArray());
    }

    /** A file that claims to be a PNG photo but is not one. */
    static MockMultipartFile notAPhoto() {
        return new MockMultipartFile("images", "anh.png", "image/png", "đây không phải ảnh".getBytes());
    }
}
