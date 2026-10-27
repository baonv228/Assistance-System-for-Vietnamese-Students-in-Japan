package com.sep490.vsa.feature.moderation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.blog.BlogPublishService;
import com.sep490.vsa.feature.media.MediaStorage;
import com.sep490.vsa.feature.moderation.ImageModerator.Verdict;
import com.sep490.vsa.feature.moderation.MediaReader.Flag;
import com.sep490.vsa.feature.moderation.MediaReader.Reading;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The media scan with a reader (Gemini) end to end against MySQL ({@code asvsj_test}, like BlogWriteFlowTest),
 * fresh accounts per test. The image scanner and the reader are mocks, so each test chooses what OpenAI answers and
 * what the reader "reads": the order of the steps, the rules run on the text read from a photo or video, the rows
 * written and the reason the author is told.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"              // ASVSJ places only: no calls to OpenStreetMap from the tests
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class MediaScanFlowTest {

    private static final String CSRF = "test-csrf-token";
    /** What a reader puts in its detail: no texts. */
    private static final String READ_DETAIL =
            "{\"model\":\"gemini-test\",\"finishReason\":\"STOP\",\"totalTokenCount\":42}";
    private static final String GENERIC = "ảnh hoặc video vi phạm Quy tắc cộng đồng";
    private static final String QR = "ảnh hoặc video có mã QR (mã QR được tính như link, chỉ tài khoản đã có tag định"
            + " danh mới được đăng)";
    private static final String SPII = "ảnh hoặc video có thông tin cá nhân nhạy cảm (số giấy tờ, số thẻ, số tài khoản"
            + " ngân hàng), hãy che đi";
    private static final String UNREADABLE = "hệ thống không đọc được ảnh hoặc video (có thể vì có quá nhiều chữ hoặc"
            + " có lời bài hát), hãy thay bằng ảnh hoặc video khác";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private BlogPublishService publisher;
    @Autowired
    private MediaScanService scanner;
    @Autowired
    private MediaStorage storage;
    @Autowired
    private ObjectMapper json;
    @MockBean
    private ImageModerator moderator;
    @MockBean
    private MediaReader reader;

    private long owner;        // 30 days old, no identity tag
    private long tagged;       // 30 days old, Mentor tag: may post links and contact details
    private long staff;        // Moderator
    private long topic;        // "Kinh nghiệm"

    @BeforeEach
    void setUp() {
        when(moderator.engine()).thenReturn("OPENAI");
        when(moderator.check(any(byte[].class))).thenReturn(new Verdict("PASS", "{\"flagged\":false}"));
        when(reader.engine()).thenReturn("GEMINI");
        when(reader.enabled()).thenReturn(true);
        reads(message(""));
        owner = user("USER", 30);
        tagged = user("USER", 30);
        staff = user("MODERATOR", 30);
        long mentorTag = jdbc.queryForObject("SELECT id FROM tag_types WHERE name = 'Mentor'", Long.class);
        jdbc.update("INSERT INTO user_tags (user_id, tag_type_id, granted_by) VALUES (?, ?, ?)", tagged, mentorTag, staff);
        topic = jdbc.queryForObject("SELECT id FROM blog_topics WHERE name = 'Kinh nghiệm'", Long.class);
    }

    // ---------------------------------------------------------------- photos: the image scanner, then the reader

    @Test
    void aPhotoTheImageScannerFlagsIsRejectedWithoutBeingRead() throws Exception {
        when(moderator.check(any(byte[].class))).thenReturn(new Verdict("FLAG", "{\"flagged\":true}"));
        long id = postWithPhoto(owner, "Ảnh bị chặn");
        awaitStatus(id, "REJECTED");

        verify(reader, never()).read(any(), any());
        assertThat(scans(id)).containsExactly("OPENAI FLAG");
        assertThat(notification(owner, id)).isEqualTo(rejected("Ảnh bị chặn", GENERIC));
    }

    @Test
    void aCleanPhotoIsReadAfterTheImageScanAndPublishedWithoutKeepingWhatWasRead() throws Exception {
        reads(Reading.read("Ngắm hoa ở Ueno 🌸", "上野公園 " + "x".repeat(1_200), "", false, List.of(), READ_DETAIL));
        long id = postWithPhoto(owner, "Hoa nở rồi");
        awaitStatus(id, "PUBLISHED");

        assertThat(points(owner)).isEqualTo(10);
        assertThat(scans(id)).containsExactly("OPENAI PASS", "GEMINI PASS");
        verify(moderator, times(1)).check(any(byte[].class));
        ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);
        verify(reader, times(1)).read(sent.capture(), eq("image/jpeg"));
        assertThat(sent.getValue()).isEqualTo(storage.get(storageKey(id)));

        // The reader's own detail, plus only the lengths of what it read (characters, the emoji counts as one)
        JsonNode detail = geminiDetail(id);
        assertThat(detail.path("model").asText()).isEqualTo("gemini-test");
        assertThat(detail.path("totalTokenCount").asInt()).isEqualTo(42);
        assertThat(detail.has("messageText")).isFalse();
        assertThat(detail.has("sceneText")).isFalse();
        assertThat(detail.has("speech")).isFalse();
        assertThat(detail.toString()).doesNotContain("Ngắm").doesNotContain("上野");
        assertThat(detail.path("messageLength").asInt()).isEqualTo(17);
        assertThat(detail.path("sceneLength").asInt()).isEqualTo(1_205);
        assertThat(detail.path("speechLength").isInt()).isTrue();
        assertThat(detail.path("speechLength").asInt()).isZero();
        assertThat(detail.path("qrCode").isBoolean()).isTrue();
        assertThat(detail.path("qrCode").asBoolean()).isFalse();
        assertThat(detail.path("flags").isArray()).isTrue();
        assertThat(detail.path("flags")).isEmpty();
        assertThat(detail.has("reason")).isFalse();
    }

    // ---------------------------------------------------------------- banned keywords in what the reader read

    @Test
    void aBannedKeywordInTheMessageTextRejectsThePostButShopSignsAreNotChecked() throws Exception {
        reads(message("Link vào FB88 mới nhất, nạp lần đầu tặng 100%"));
        long id = postWithPhoto(owner, "Ảnh có chữ");
        awaitStatus(id, "REJECTED");

        assertThat(notification(owner, id)).isEqualTo("Bài viết “Ảnh có chữ” chưa được đăng vì ảnh hoặc video có từ"
                + " khóa không được phép “fb88”. Bạn có thể sửa bài rồi gửi lại.");
        assertThat(scans(id)).containsExactly("OPENAI PASS", "GEMINI FLAG");
        JsonNode detail = geminiDetail(id);
        assertThat(detail.path("reason").asText()).isEqualTo(keyword("fb88"));
        // A FLAG row keeps what was read, for an appeal
        assertThat(detail.path("messageText").asText()).isEqualTo("Link vào FB88 mới nhất, nạp lần đầu tặng 100%");
        assertThat(detail.has("messageLength")).isFalse();
        assertThat(points(owner)).isZero();
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM media WHERE id = ?", Boolean.class,
                mediaOf(id))).isTrue();

        // The same words only on the street in the picture (a パチンコ sign): not checked, and not kept
        reads(Reading.read("", "パチンコ FB88 新台入替", "", false, List.of(), READ_DETAIL));
        long street = postWithPhoto(owner, "Phố đêm Shinjuku");
        awaitStatus(street, "PUBLISHED");
        assertThat(geminiDetail(street).has("sceneText")).isFalse();
        assertThat(geminiDetail(street).path("sceneLength").asInt()).isEqualTo(14);
    }

    @Test
    void eachLineOfTheMessageAndTheSpeechAreCheckedOnTheirOwn() throws Exception {
        // Glued together, "Về nhà" and "Cái này…" on the next line would read "nhà cái" (a banned word, V6)
        reads(message("Về nhà\nCái này bao nhiêu tiền?"));
        long lines = postWithPhoto(owner, "Hỏi giá");
        awaitStatus(lines, "PUBLISHED");

        // The same across the message text and the speech
        reads(Reading.read("Bữa tối ở nhà", "", "Cái này ngon lắm", false, List.of(), READ_DETAIL));
        long spoken = postWithPhoto(owner, "Bữa tối");
        awaitStatus(spoken, "PUBLISHED");

        // And for the link and contact rule: "zalo", a line break, then "minhanh99" would be a chat id
        assertThat(ContentFilter.contacts("Phòng trọ gần ga Ueno\nLiên hệ zalo\nminhanh99")).isNotEmpty();
        reads(Reading.read("Phòng trọ gần ga Ueno\nLiên hệ zalo", "", "minhanh99", false, List.of(), READ_DETAIL));
        long chatId = postWithPhoto(owner, "Phòng trọ Ueno");
        awaitStatus(chatId, "PUBLISHED");

        // A keyword on one line is still found
        reads(message("Hoa anh đào ở Ueno\n\nLink vào FB88 mới nhất"));
        long oneLine = postWithPhoto(owner, "Hoa anh đào");
        awaitStatus(oneLine, "REJECTED");
        assertThat(notification(owner, oneLine)).isEqualTo(rejected("Hoa anh đào", keyword("fb88")));

        // A phone number only in the speech is named
        reads(Reading.read("Phòng trọ gần ga", "", "Ai cần thì gọi mình số 090-1234-5678 nhé", false, List.of(),
                READ_DETAIL));
        long phone = postWithPhoto(owner, "Phòng trọ");
        awaitStatus(phone, "REJECTED");
        assertThat(notification(owner, phone)).isEqualTo(rejected("Phòng trọ", contact("090-1234-5678")));
    }

    @Test
    void aKeywordComesBeforeALinkAndALinkBeforeAQrCode() throws Exception {
        reads(Reading.read("Vào fb88 tại https://www.example.com/khuyen-mai", "", "", true, List.of(), READ_DETAIL));
        long both = postWithPhoto(owner, "Khuyến mãi");
        awaitStatus(both, "REJECTED");
        assertThat(notification(owner, both)).isEqualTo(rejected("Khuyến mãi", keyword("fb88")));

        reads(Reading.read("Đăng ký tại https://www.example.com/dang-ky", "", "", true, List.of(), READ_DETAIL));
        long linkAndQr = postWithPhoto(owner, "Đăng ký");
        awaitStatus(linkAndQr, "REJECTED");
        assertThat(notification(owner, linkAndQr)).isEqualTo(rejected("Đăng ký",
                link("https://www.example.com/dang-ky")));
    }

    // ---------------------------------------------------------------- links and contact details (BR-22)

    @Test
    void phoneNumbersAndChatIdsInAPhotoNeedAnIdentityTag() throws Exception {
        reads(message("Gọi mình 090-1234-5678"));
        long japanese = postWithPhoto(owner, "Hỏi đường");
        awaitStatus(japanese, "REJECTED");
        assertThat(notification(owner, japanese)).isEqualTo(rejected("Hỏi đường", contact("090-1234-5678")));
        assertThat(geminiDetail(japanese).path("reason").asText()).isEqualTo(contact("090-1234-5678"));

        reads(message("zalo: 0901 234 567"));
        long zalo = postWithPhoto(owner, "Liên hệ");
        awaitStatus(zalo, "REJECTED");
        assertThat(notification(owner, zalo)).isEqualTo(rejected("Liên hệ", contact("0901 234 567")));

        reads(message("LINE ID: senpai_hn"));
        long line = postWithPhoto(owner, "Kết bạn");
        awaitStatus(line, "REJECTED");
        assertThat(notification(owner, line)).isEqualTo(rejected("Kết bạn", contact("LINE ID: senpai_hn")));

        // The same from an account with an identity tag is published
        long fromTagged = postWithPhoto(tagged, "Liên hệ");
        awaitStatus(fromTagged, "PUBLISHED");
        assertThat(scans(fromTagged)).containsExactly("OPENAI PASS", "GEMINI PASS");
    }

    @Test
    void linksInAPhotoNeedAnIdentityTagAndShortLinksNeverPass() throws Exception {
        reads(message("Đăng ký tại https://www.example.com/dang-ky"));
        long withLink = postWithPhoto(owner, "Đăng ký");
        awaitStatus(withLink, "REJECTED");
        assertThat(notification(owner, withLink)).isEqualTo(rejected("Đăng ký",
                link("https://www.example.com/dang-ky")));

        // The shortened one is named, not the first link; a very long link is cut to 100 characters
        reads(message("Xem thêm: www.example.com/hoa và bit.ly/abc123"));
        long shortLink = postWithPhoto(tagged, "Xem thêm");
        awaitStatus(shortLink, "REJECTED");
        assertThat(notification(tagged, shortLink)).isEqualTo(rejected("Xem thêm", shortLink("bit.ly/abc123")));

        String longLink = "https://www.example.com/" + "a".repeat(200);
        reads(message("Đăng ký: " + longLink));
        long cut = postWithPhoto(owner, "Đăng ký dài");
        awaitStatus(cut, "REJECTED");
        assertThat(geminiDetail(cut).path("reason").asText()).isEqualTo(link(longLink.substring(0, 100)));

        reads(message("Đăng ký tại https://www.example.com/dang-ky"));
        long tagLink = postWithPhoto(tagged, "Đăng ký");
        awaitStatus(tagLink, "PUBLISHED");
    }

    @Test
    void aQrCodeInThePhotoCountsAsALink() throws Exception {
        reads(Reading.read("Quét mã để nhận quà", "", "", true, List.of(), READ_DETAIL));
        long untagged = postWithPhoto(owner, "Quà tặng");
        awaitStatus(untagged, "REJECTED");
        assertThat(notification(owner, untagged)).isEqualTo(rejected("Quà tặng", QR));
        assertThat(geminiDetail(untagged).path("qrCode").asBoolean()).isTrue();

        long fromTagged = postWithPhoto(tagged, "Quà tặng");
        awaitStatus(fromTagged, "PUBLISHED");
    }

    // ---------------------------------------------------------------- violations the reader saw, refusals

    @Test
    void aFlagOrARefusalOfTheReaderRejectsWithItsOwnReasonOrTheGenericOne() throws Exception {
        reads(Reading.read("Nạp lần đầu tặng 100%", "", "", false, List.of(Flag.GAMBLING_PROMOTION), READ_DETAIL));
        long gambling = postWithPhoto(owner, "Khuyến mãi");
        awaitStatus(gambling, "REJECTED");
        assertThat(notification(owner, gambling)).isEqualTo(rejected("Khuyến mãi",
                "ảnh hoặc video quảng cáo cờ bạc, cá cược"));
        JsonNode detail = geminiDetail(gambling);
        assertThat(detail.path("flags").get(0).asText()).isEqualTo("GAMBLING_PROMOTION");
        assertThat(detail.path("reason").asText()).isEqualTo("ảnh hoặc video quảng cáo cờ bạc, cá cược");

        reads(Reading.read("", "", "", false, List.of(Flag.SEX_SOLICITATION), READ_DETAIL));
        long solicitation = postWithPhoto(owner, "Làm quen");
        awaitStatus(solicitation, "REJECTED");
        assertThat(notification(owner, solicitation)).isEqualTo(rejected("Làm quen",
                "ảnh hoặc video mời chào mua bán dâm hoặc “bao nuôi”"));

        // The first flag that has a reason of its own
        reads(Reading.read("", "", "", false, List.of(Flag.SEXUAL, Flag.DRUG_SALE), READ_DETAIL));
        long drugs = postWithPhoto(owner, "Hàng mới");
        awaitStatus(drugs, "REJECTED");
        assertThat(notification(owner, drugs)).isEqualTo(rejected("Hàng mới", "ảnh hoặc video mua bán ma túy"));

        // The others keep the generic text
        reads(Reading.read("", "", "", false, List.of(Flag.SEXUAL), READ_DETAIL));
        long flagged = postWithPhoto(owner, "Ảnh nóng");
        awaitStatus(flagged, "REJECTED");
        assertThat(notification(owner, flagged)).isEqualTo(rejected("Ảnh nóng", GENERIC));
        assertThat(geminiDetail(flagged).has("reason")).isFalse();

        // Stopped because the file shows sensitive personal data (a residence card, a bank card)
        reads(Reading.blocked("{\"model\":\"gemini-test\",\"finishReason\":\"SPII\",\"totalTokenCount\":1702}"));
        long card = postWithPhoto(owner, "Thẻ ngoại kiều");
        awaitStatus(card, "REJECTED");
        assertThat(notification(owner, card)).isEqualTo(rejected("Thẻ ngoại kiều", SPII));
        assertThat(geminiDetail(card).path("finishReason").asText()).isEqualTo("SPII");

        reads(Reading.blocked("{\"model\":\"gemini-test\",\"blockReason\":\"PROHIBITED_CONTENT\"}"));
        long blocked = postWithPhoto(owner, "Ảnh lạ");
        awaitStatus(blocked, "REJECTED");
        assertThat(scans(blocked)).containsExactly("OPENAI PASS", "GEMINI FLAG");
        assertThat(notification(owner, blocked)).isEqualTo(rejected("Ảnh lạ", GENERIC));
        assertThat(geminiDetail(blocked).path("blockReason").asText()).isEqualTo("PROHIBITED_CONTENT");

        // A detail that is not a JSON object is kept under "reader"
        reads(Reading.blocked("blocked, no JSON"));
        long plain = postWithPhoto(owner, "Ảnh khác");
        awaitStatus(plain, "REJECTED");
        assertThat(geminiDetail(plain).path("reader").asText()).isEqualTo("blocked, no JSON");
    }

    @Test
    void theReasonOfAnotherPhotoIsGivenWhenTheFirstOneHasNone() throws Exception {
        // Whichever photo is scanned first is flagged by the image scanner, the other one holds a banned keyword
        when(moderator.check(any(byte[].class))).thenReturn(new Verdict("FLAG", "{\"flagged\":true}"),
                new Verdict("PASS", "{\"flagged\":false}"));
        reads(message("fb88"));
        long id = created(send(owner, post("Hai ảnh"), photo(), photo()));
        awaitStatus(id, "REJECTED");
        assertThat(notification(owner, id)).isEqualTo(rejected("Hai ảnh", keyword("fb88")));
    }

    // ---------------------------------------------------------------- reader errors and its own budget

    @Test
    void aReaderErrorKeepsThePostWaitingAndTheRetryDoesNotScanThePhotoAgain() throws Exception {
        reads(Reading.error("{\"model\":\"gemini-test\",\"error\":\"generateContent HTTP 429: quota\","
                + "\"httpStatus\":429}"), message("Hoa anh đào"));
        long id = postWithPhoto(owner, "Chờ đọc");
        long media = mediaOf(id);
        await().atMost(Duration.ofSeconds(15)).until(() -> scans(id).contains("GEMINI ERROR"));
        assertThat(blogStatus(id)).isEqualTo("PROCESSING");
        assertThat(scans(id)).containsExactly("OPENAI PASS", "GEMINI ERROR");
        assertThat(geminiDetail(id).path("httpStatus").asInt()).isEqualTo(429);

        // Right after the error the file waits (1 minute) instead of calling the reader again
        assertThat(scanner.scan(media)).isEqualTo("PROCESSING");
        verify(reader, times(1)).read(any(), any());

        // The pause is over: the retry reads it and publishes the post, without sending it to OpenAI again
        ageScans(id, 2);
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            publisher.process(id);
            return "PUBLISHED".equals(blogStatus(id));
        });
        verify(moderator, times(1)).check(any(byte[].class));
        verify(reader, times(2)).read(any(), any());
        assertThat(scans(id)).containsExactly("OPENAI PASS", "GEMINI ERROR", "GEMINI PASS");
        assertThat(points(owner)).isEqualTo(10);
    }

    @Test
    void aBusyReaderWritesNothingAndTheNextRunGoesOnWithoutAPause() throws Exception {
        reads(Reading.busy("{\"model\":\"gemini-test\",\"busy\":\"no free request\"}"), message("Hoa anh đào"));
        long id = postWithPhoto(owner, "Hàng chờ");
        // The image scan's row was written before the reader was asked; the reader's answer writes none
        verify(reader, timeout(15_000)).read(any(), any());
        assertThat(blogStatus(id)).isEqualTo("PROCESSING");
        assertThat(scans(id)).containsExactly("OPENAI PASS");

        // No pause: the next run reads it and publishes the post, without sending it to OpenAI again
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            publisher.process(id);
            return "PUBLISHED".equals(blogStatus(id));
        });
        verify(moderator, times(1)).check(any(byte[].class));
        assertThat(scans(id)).containsExactly("OPENAI PASS", "GEMINI PASS");
    }

    @Test
    void aFileThatCannotBeReadIsAnErrorOfTheStepThatNeededIt() {
        String photoKey = "blog/2026/10/" + UUID.randomUUID() + ".jpg";
        String videoKey = "blog/2026/10/" + UUID.randomUUID() + ".mp4";
        long photo = media("IMAGE", photoKey, "image/jpeg");
        long video = media("VIDEO", videoKey, "video/mp4");
        long passedPhoto = media("IMAGE", "blog/2026/10/" + UUID.randomUUID() + ".jpg", "image/jpeg");
        try {
            jdbc.update("""
                    INSERT INTO moderation_scans (target_type, target_id, engine, result, detail)
                    VALUES ('MEDIA', ?, 'OPENAI', 'PASS', '{}')""", passedPhoto);
            assertThat(scanner.scan(photo)).isEqualTo("PROCESSING");
            assertThat(scanner.scan(video)).isEqualTo("PROCESSING");
            assertThat(scanner.scan(passedPhoto)).isEqualTo("PROCESSING");

            assertThat(mediaScans(photo)).containsExactly("OPENAI ERROR");
            assertThat(mediaScans(video)).containsExactly("GEMINI ERROR");
            assertThat(mediaScans(passedPhoto)).containsExactly("OPENAI PASS", "GEMINI ERROR");
            verify(moderator, never()).check(any(byte[].class));
            verify(reader, never()).read(any(), any());
        } finally {
            jdbc.update("DELETE FROM moderation_scans WHERE target_type = 'MEDIA' AND target_id IN (?, ?, ?)",
                    photo, video, passedPhoto);
            jdbc.update("DELETE FROM media WHERE id IN (?, ?, ?)", photo, video, passedPhoto);
        }
    }

    @Test
    void anImageScannerErrorKeepsThePhotoWaitingWithoutAskingTheReader() throws Exception {
        when(moderator.check(any(byte[].class))).thenReturn(new Verdict("ERROR", "{\"error\":\"HTTP 503\"}"));
        long id = postWithPhoto(owner, "Chờ quét ảnh");
        await().atMost(Duration.ofSeconds(15)).until(() -> scans(id).contains("OPENAI ERROR"));

        assertThat(blogStatus(id)).isEqualTo("PROCESSING");
        assertThat(scans(id)).containsExactly("OPENAI ERROR");
        verify(reader, never()).read(any(), any());
    }

    @Test
    void aPhotoTheImageScannerPassedIsPassedOnceNoReaderIsConfiguredAnyMore() {
        // Passed by OpenAI while a reader was configured; the reader has been switched off since
        long photo = media("IMAGE", "blog/2026/10/" + UUID.randomUUID() + ".jpg", "image/jpeg");
        try {
            jdbc.update("""
                    INSERT INTO moderation_scans (target_type, target_id, engine, result, detail)
                    VALUES ('MEDIA', ?, 'OPENAI', 'PASS', '{}')""", photo);
            when(reader.enabled()).thenReturn(false);

            assertThat(scanner.scan(photo)).isEqualTo("PASSED");
            assertThat(jdbc.queryForObject("SELECT scan_status FROM media WHERE id = ?", String.class, photo))
                    .isEqualTo("PASSED");
            // Neither scanner is asked, and the file is not even read (it is not in the storage)
            assertThat(mediaScans(photo)).containsExactly("OPENAI PASS");
            verify(moderator, never()).check(any(byte[].class));
            verify(reader, never()).read(any(), any());
        } finally {
            jdbc.update("DELETE FROM moderation_scans WHERE target_type = 'MEDIA' AND target_id = ?", photo);
            jdbc.update("DELETE FROM media WHERE id = ?", photo);
        }
    }

    @Test
    void aFileTheReaderCannotReadIsRefusedTheThirdTimeButAnOutageIsNeverGivenUp() throws Exception {
        Reading unreadable = Reading.unreadable("{\"model\":\"gemini-test\","
                + "\"error\":\"generateContent: finishReason RECITATION\"}");
        Reading down = Reading.error("{\"model\":\"gemini-test\",\"error\":\"generateContent HTTP 503: overloaded\","
                + "\"httpStatus\":503}");
        reads(unreadable, down, unreadable, unreadable);
        long id = postWithPhoto(owner, "Bài hát");
        long media = mediaOf(id);
        await().atMost(Duration.ofSeconds(15)).until(() -> scans(id).contains("GEMINI ERROR"));
        assertThat(blogStatus(id)).isEqualTo("PROCESSING");
        JsonNode first = geminiDetail(id);
        assertThat(first.path("unreadable").asBoolean()).isTrue();
        assertThat(first.path("error").asText()).contains("RECITATION");
        assertThat(first.has("reason")).isFalse();

        // An outage in between is an ordinary error: it does not count as a try
        ageScans(id, 10);
        assertThat(scanner.scan(media)).isEqualTo("PROCESSING");
        assertThat(geminiDetail(id).has("unreadable")).isFalse();

        // The second time: still waiting, with the growing pause
        ageScans(id, 10);
        assertThat(scanner.scan(media)).isEqualTo("PROCESSING");
        assertThat(scanner.scan(media)).isEqualTo("PROCESSING");
        verify(reader, times(3)).read(any(), any());

        // The third time: refused, and the author is told why
        ageScans(id, 10);
        assertThat(scanner.scan(media)).isEqualTo("REJECTED");
        assertThat(scans(id)).containsExactly("OPENAI PASS", "GEMINI ERROR", "GEMINI ERROR", "GEMINI ERROR",
                "GEMINI FLAG");
        JsonNode last = geminiDetail(id);
        assertThat(last.path("reason").asText()).isEqualTo(UNREADABLE);
        assertThat(last.path("unreadable").asBoolean()).isTrue();
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            publisher.process(id);
            return "REJECTED".equals(blogStatus(id));
        });
        assertThat(notification(owner, id)).isEqualTo(rejected("Bài hát", UNREADABLE));
    }

    @Test
    void theTextsAndReasonOfARejectedFileAreForgottenAfter30Days() throws Exception {
        long photo = media("IMAGE", "blog/2026/10/" + UUID.randomUUID() + ".jpg", "image/jpeg");
        String flagged = """
                {"model":"gemini-test","messageText":"Link vào FB88","sceneText":"パチンコ","speech":"fb88",
                 "qrCode":false,"flags":[],"reason":"ảnh hoặc video có từ khóa không được phép “fb88”"}""";
        try {
            jdbc.update("""
                    INSERT INTO moderation_scans (target_type, target_id, engine, result, detail, created_at)
                    VALUES ('MEDIA', ?, 'GEMINI', 'FLAG', ?, ?)""",
                    photo, flagged, Timestamp.from(Instant.now().minus(Duration.ofDays(31))));
            jdbc.update("""
                    INSERT INTO moderation_scans (target_type, target_id, engine, result, detail, created_at)
                    VALUES ('MEDIA', ?, 'GEMINI', 'FLAG', ?, ?)""",
                    photo, flagged, Timestamp.from(Instant.now().minus(Duration.ofDays(29))));
            // A refusal for sensitive personal data: a reason and no texts
            jdbc.update("""
                    INSERT INTO moderation_scans (target_type, target_id, engine, result, detail, created_at)
                    VALUES ('MEDIA', ?, 'GEMINI', 'FLAG', ?, ?)""",
                    photo, "{\"model\":\"gemini-test\",\"finishReason\":\"SPII\",\"reason\":\"" + SPII + "\"}",
                    Timestamp.from(Instant.now().minus(Duration.ofDays(31))));
            List<Long> rows = jdbc.queryForList("""
                    SELECT id FROM moderation_scans WHERE target_type = 'MEDIA' AND target_id = ? ORDER BY id""",
                    Long.class, photo);

            scanner.forgetOldTexts();

            JsonNode old = json.readTree(jdbc.queryForObject("SELECT detail FROM moderation_scans WHERE id = ?",
                    String.class, rows.get(0)));
            assertThat(old.has("messageText")).isFalse();
            assertThat(old.has("sceneText")).isFalse();
            assertThat(old.has("speech")).isFalse();
            // The reason may name the phone number, link or chat id found (the notification keeps it)
            assertThat(old.has("reason")).isFalse();
            assertThat(old.path("model").asText()).isEqualTo("gemini-test");
            assertThat(old.path("flags").isArray()).isTrue();
            assertThat(old.path("qrCode").isBoolean()).isTrue();
            JsonNode recent = json.readTree(jdbc.queryForObject("SELECT detail FROM moderation_scans WHERE id = ?",
                    String.class, rows.get(1)));
            assertThat(recent).isEqualTo(json.readTree(flagged));
            JsonNode refusal = json.readTree(jdbc.queryForObject("SELECT detail FROM moderation_scans WHERE id = ?",
                    String.class, rows.get(2)));
            assertThat(refusal).isEqualTo(json.readTree("{\"model\":\"gemini-test\",\"finishReason\":\"SPII\"}"));
        } finally {
            jdbc.update("DELETE FROM moderation_scans WHERE target_type = 'MEDIA' AND target_id = ?", photo);
            jdbc.update("DELETE FROM media WHERE id = ?", photo);
        }
    }

    // ---------------------------------------------------------------- videos

    @Test
    void aVideoIsReadWithoutTheImageScannerAndItsSpeechIsChecked() throws Exception {
        long id = created(send(owner, post("Video hanami"), video(10)));
        awaitStatus(id, "PUBLISHED");
        verify(moderator, never()).check(any(byte[].class));
        ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);
        verify(reader, times(1)).read(sent.capture(), eq("video/mp4"));
        assertThat(sent.getValue()).isEqualTo(storage.get(storageKey(id)));
        assertThat(scans(id)).containsExactly("GEMINI PASS");

        // A banned keyword only spoken in the video
        reads(Reading.read("", "", "Vào fb88 nạp tiền nhận thưởng ngay", false, List.of(), READ_DETAIL));
        long spoken = created(send(owner, post("Video quảng cáo"), video(10)));
        awaitStatus(spoken, "REJECTED");
        assertThat(notification(owner, spoken)).isEqualTo(rejected("Video quảng cáo", keyword("fb88")));
        assertThat(geminiDetail(spoken).path("speech").asText()).isEqualTo("Vào fb88 nạp tiền nhận thưởng ngay");
    }

    @Test
    void withoutAReaderAVideoPassesWithoutARowAsBefore() throws Exception {
        when(reader.enabled()).thenReturn(false);
        long id = created(send(owner, post("Video không đọc"), video(10)));
        awaitStatus(id, "PUBLISHED");
        assertThat(scans(id)).isEmpty();
        verify(reader, never()).read(any(), any());
        verify(moderator, never()).check(any(byte[].class));

        // A photo passes on the image scanner alone
        long photo = postWithPhoto(owner, "Ảnh không đọc");
        awaitStatus(photo, "PUBLISHED");
        assertThat(scans(photo)).containsExactly("OPENAI PASS");
    }

    // ---------------------------------------------------------------- reviews get the reason too

    @Test
    void theReasonReachesTheNotificationOfAPlaceReview() throws Exception {
        String name = "Quán " + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO places (name_vi, category_id, lat, lng, source, status, created_by)
                VALUES (?, 'food', ?, ?, 'TEAM', 'ACTIVE', ?)""",
                name, 33 + ThreadLocalRandom.current().nextDouble(3), 131 + ThreadLocalRandom.current().nextDouble(8),
                staff);
        long place = jdbc.queryForObject("SELECT id FROM places WHERE name_vi = ?", Long.class, name);
        reads(message("zalo: 0901 234 567"));
        MockMultipartFile review = new MockMultipartFile("review", "", "application/json",
                ("{\"placeId\":" + place + ",\"rating\":5,\"content\":\"Quán sạch, có ảnh thực đơn.\"}")
                        .getBytes(StandardCharsets.UTF_8));
        long id = created(mvc.perform(withCsrf(multipart("/api/reviews").file(review).file(photo()))
                .session(session(owner, "USER"))).andExpect(jsonPath("$.data.status").value("PROCESSING")));
        await().atMost(Duration.ofSeconds(15)).until(() -> "REJECTED".equals(
                jdbc.queryForObject("SELECT status FROM reviews WHERE id = ?", String.class, id)));

        assertThat(notification(owner, id)).isEqualTo("Review về “" + name + "” chưa được đăng vì "
                + contact("0901 234 567") + ". Bạn có thể sửa review rồi gửi lại.");
    }

    @Test
    void theReasonReachesTheNotificationOfAnEventReview() throws Exception {
        String title = "Lễ hội thử " + UUID.randomUUID().toString().substring(0, 8);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Tokyo"));
        jdbc.update("""
                INSERT INTO events (title, start_date, end_date, location_name, created_by)
                VALUES (?, ?, ?, 'Công viên Ueno', ?)""",
                title, java.sql.Date.valueOf(today.minusDays(1)), java.sql.Date.valueOf(today.plusDays(1)), staff);
        long event = jdbc.queryForObject("SELECT id FROM events WHERE title = ?", Long.class, title);
        try {
            reads(message("Vé rẻ tại https://www.example.com/ve"));
            MockMultipartFile review = new MockMultipartFile("review", "", "application/json",
                    "{\"rating\":5,\"content\":\"Lễ hội rất vui, đông người.\"}".getBytes(StandardCharsets.UTF_8));
            long id = created(mvc.perform(withCsrf(multipart("/api/events/" + event + "/reviews").file(review)
                    .file(photo())).session(session(owner, "USER")))
                    .andExpect(jsonPath("$.data.status").value("PROCESSING")));
            await().atMost(Duration.ofSeconds(15)).until(() -> "REJECTED".equals(
                    jdbc.queryForObject("SELECT status FROM reviews WHERE id = ?", String.class, id)));

            assertThat(notification(owner, id)).isEqualTo("Review “" + title + "” chưa được đăng vì ảnh hoặc video"
                    + " có link “https://www.example.com/ve” (chỉ tài khoản đã có tag định danh mới được đăng link)."
                    + " Bạn có thể sửa review rồi gửi lại.");
        } finally {
            jdbc.update("UPDATE events SET deleted_at = UTC_TIMESTAMP() WHERE id = ?", event);
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The reader gives these answers in turn from now on (the last one repeats). A test that needs several answers
     * for one post stubs them all here up front: stubbing again while a background scan may be inside the mock is a
     * race.
     */
    private void reads(Reading first, Reading... then) {
        when(reader.read(any(), anyString())).thenReturn(first, then);
    }

    /** A reading with this message text and nothing else. */
    private static Reading message(String text) {
        return Reading.read(text, "", "", false, List.of(), READ_DETAIL);
    }

    private static String keyword(String keyword) {
        return "ảnh hoặc video có từ khóa không được phép “" + keyword + "”";
    }

    private static String link(String link) {
        return "ảnh hoặc video có link “" + link + "” (chỉ tài khoản đã có tag định danh mới được đăng link)";
    }

    private static String shortLink(String link) {
        return "ảnh hoặc video có link rút gọn “" + link + "” (không ai được đăng link rút gọn)";
    }

    private static String contact(String contact) {
        return "ảnh hoặc video có số di động hoặc tài khoản Zalo, LINE, Telegram “" + contact
                + "” (chỉ tài khoản đã có tag định danh mới được đăng)";
    }

    private static String rejected(String title, String reason) {
        return "Bài viết “" + title + "” chưa được đăng vì " + reason + ". Bạn có thể sửa bài rồi gửi lại.";
    }

    private long postWithPhoto(long userId, String title) throws Exception {
        return created(send(userId, post(title), photo()));
    }

    /** The JSON part of a new post in topic "Kinh nghiệm". */
    private MockMultipartFile post(String title) {
        return new MockMultipartFile("post", "", "application/json", ("{\"title\":\"" + title
                + "\",\"content\":\"Ảnh mình chụp\",\"topicIds\":[" + topic + "],\"placeIds\":[]}")
                .getBytes(StandardCharsets.UTF_8));
    }

    private ResultActions send(long userId, MockMultipartFile post, MockMultipartFile... files) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/blogs").file(post);
        for (MockMultipartFile f : files) {
            request.file(f);
        }
        return mvc.perform(withCsrf(request).session(session(userId, "USER")));
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
    }

    private static long created(ResultActions result) throws Exception {
        String body = result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"data\":\\{\"id\":(\\d+).*", "$1"));
    }

    private void awaitStatus(long blogId, String status) {
        await().atMost(Duration.ofSeconds(15)).until(() -> status.equals(blogStatus(blogId)));
    }

    private String blogStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM blogs WHERE id = ?", String.class, id);
    }

    /** The one file of the post. */
    private long mediaOf(long blogId) {
        return jdbc.queryForObject("SELECT media_id FROM blog_media WHERE blog_id = ?", Long.class, blogId);
    }

    private String storageKey(long blogId) {
        return jdbc.queryForObject("SELECT storage_key FROM media WHERE id = ?", String.class, mediaOf(blogId));
    }

    /** "ENGINE RESULT" of every scan row of the post's file, oldest first. */
    private List<String> scans(long blogId) {
        return mediaScans(mediaOf(blogId));
    }

    private List<String> mediaScans(long mediaId) {
        return jdbc.queryForList("""
                SELECT CONCAT(engine, ' ', result) FROM moderation_scans WHERE target_type = 'MEDIA' AND target_id = ?
                ORDER BY id""", String.class, mediaId);
    }

    /** The detail of the latest GEMINI row of the post's file. */
    private JsonNode geminiDetail(long blogId) throws Exception {
        return json.readTree(jdbc.queryForObject("""
                SELECT detail FROM moderation_scans WHERE target_type = 'MEDIA' AND target_id = ? AND engine = 'GEMINI'
                ORDER BY id DESC LIMIT 1""", String.class, mediaOf(blogId)));
    }

    /** Ends the pause after errors: the post's scan rows are made this many minutes older. */
    private void ageScans(long blogId, int minutes) {
        jdbc.update("""
                UPDATE moderation_scans SET created_at = created_at - INTERVAL ? MINUTE
                WHERE target_type = 'MEDIA' AND target_id = ?""", minutes, mediaOf(blogId));
    }

    private String notification(long userId, long refId) {
        return jdbc.queryForObject("""
                SELECT content FROM notifications WHERE user_id = ? AND type = 'CONTENT_REJECTED' AND ref_id = ?""",
                String.class, userId, refId);
    }

    /** A waiting media row of {@code owner} whose file is not in the storage. */
    private long media(String type, String key, String mimeType) {
        jdbc.update("""
                INSERT INTO media (owner_id, media_type, storage_key, mime_type, size_bytes, scan_status)
                VALUES (?, ?, ?, ?, 3, 'PROCESSING')""", owner, type, key, mimeType);
        return jdbc.queryForObject("SELECT id FROM media WHERE storage_key = ?", Long.class, key);
    }

    private int points(long userId) {
        return jdbc.queryForObject("SELECT points FROM users WHERE id = ?", Integer.class, userId);
    }

    private long user(String role, int daysOld) {
        String email = "scan-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, 'Scan Test', ?, 'ACTIVE', TRUE, ?)""",
                email, role, Timestamp.from(Instant.now().minus(Duration.ofDays(daysOld))));
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private static MockHttpSession session(long userId, String role) {
        AppUserPrincipal me = new AppUserPrincipal(userId, "u" + userId + "@test.local", "Scan Test", role, "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))));
        return session;
    }

    private static MockMultipartFile photo() throws Exception {
        BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return new MockMultipartFile("images", "anh.png", "image/png", out.toByteArray());
    }

    /** A minimal MP4: ftyp + moov/mvhd with the given length. */
    private static MockMultipartFile video(int seconds) {
        ByteBuffer ftyp = ByteBuffer.allocate(16).putInt(16).put("ftypisom".getBytes(StandardCharsets.ISO_8859_1)).putInt(0x200);
        ByteBuffer mvhd = ByteBuffer.allocate(108).putInt(108).put("mvhd".getBytes(StandardCharsets.ISO_8859_1))
                .putInt(0).putInt(0).putInt(0).putInt(1000).putInt(seconds * 1000);
        ByteBuffer moov = ByteBuffer.allocate(8 + 108).putInt(116).put("moov".getBytes(StandardCharsets.ISO_8859_1)).put(mvhd.array());
        ByteBuffer file = ByteBuffer.allocate(16 + 116).put(ftyp.array()).put(moov.array());
        return new MockMultipartFile("video", "clip.mp4", "video/mp4", file.array());
    }
}
