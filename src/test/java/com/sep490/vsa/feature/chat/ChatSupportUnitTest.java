package com.sep490.vsa.feature.chat;

import com.sep490.vsa.feature.chat.ChatRepository.FileRow;
import com.sep490.vsa.feature.media.MediaStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.InOrder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the small pieces "Tin nhắn" is built on, all without a database or a network:
 * <ul>
 *   <li>{@link ChatCrypto}: base64 in the agreed form, the P-256 point check of public keys, the franking tag of an
 *       encrypted message (the shared test vector of ChatEncryptionFlowTest / chat-crypto.test.mjs), the constant-time
 *       tag comparison and the UTF-8 check of a revealed body.</li>
 *   <li>{@link ChatTyping} ("đang soạn tin", 6 s) and {@link ChatViewers} (reading a conversation, 10 s): in-memory
 *       maps, read with a clock the test moves.</li>
 *   <li>{@link ChatCleanup}: the 03:50 Japan time job; {@link ChatRepository} and {@link MediaStorage} are mocks and the
 *       transactions run on a mocked {@link PlatformTransactionManager}.</li>
 * </ul>
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("ChatCrypto, ChatTyping, ChatViewers, ChatCleanup – Tin nhắn: phần hỗ trợ (CHAT)")
class ChatSupportUnitTest {

    /** 12:00 in Japan, 20/11/2026. */
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private static final BigInteger P = new BigInteger(
            "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16);
    private static final BigInteger GX = new BigInteger(
            "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16);
    private static final BigInteger GY = new BigInteger(
            "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16);
    /** The shared franking vector: fk = 01..20, conversation 42, sender 7, P = {"v":1,"text":"Xin chào"}. */
    private static final byte[] VECTOR_FK = Base64.getDecoder().decode("AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=");
    private static final byte[] VECTOR_BODY = "{\"v\":1,\"text\":\"Xin chào\"}".getBytes(StandardCharsets.UTF_8);
    private static final String VECTOR_TAG = "78d4be3ee84351509e736a57ae1fd15ac9a7890e456f08197b931a5b2ac980f0";

    // ---------------------------------------------------------------- helpers

    /** A clock the test moves by hand. */
    private static final class MovableClock extends Clock {

        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static byte[] point(BigInteger x, BigInteger y) {
        byte[] raw = new byte[65];
        raw[0] = 0x04;
        System.arraycopy(fixed(x), 0, raw, 1, 32);
        System.arraycopy(fixed(y), 0, raw, 33, 32);
        return raw;
    }

    private static byte[] fixed(BigInteger value) {
        byte[] bytes = value.toByteArray();
        byte[] out = new byte[32];
        int length = Math.min(bytes.length, 32);
        System.arraycopy(bytes, bytes.length - length, out, 32 - length, length);
        return out;
    }

    private static byte[] newPublicPoint() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        ECPublicKey key = (ECPublicKey) generator.generateKeyPair().getPublic();
        return point(key.getW().getAffineX(), key.getW().getAffineY());
    }

    private static int mapSize(Object owner, String field) throws Exception {
        Field f = owner.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return ((Map<?, ?>) f.get(owner)).size();
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. ChatCrypto – Kiểm tra dữ liệu mã hóa đầu cuối (UTC-461 → UTC-477)")
    class Crypto {

        @Test
        @DisplayName("UTC-461 Giải mã base64 chuẩn: trả về đúng các byte")
        void utc461_standardBase64IsDecoded() {
            assertThat(ChatCrypto.base64("eyJ2IjoxLCJ0ZXh0IjoiWGluIGNow6BvIn0=")).isEqualTo(VECTOR_BODY);
        }

        @Test
        @DisplayName("UTC-462 null, chuỗi rỗng, ký tự ngoài base64, chữ base64 kiểu URL (-, _), có khoảng trắng: trả về null")
        void utc462_invalidBase64GivesNull() {
            for (String bad : new String[] {null, "", "not base64!", "ab-_", "YWJj ZA=="}) {
                assertThat(ChatCrypto.base64(bad)).as(String.valueOf(bad)).isNull();
            }
        }

        @Test
        @DisplayName("UTC-463 Base64 thiếu dấu '=' vẫn giải mã được; khi mã hóa lại luôn có dấu '=' (dạng chuẩn)")
        void utc463_paddingIsOptionalOnReadAndAddedOnWrite() {
            assertThat(ChatCrypto.base64("YWI")).isEqualTo("ab".getBytes(StandardCharsets.US_ASCII));
            assertThat(ChatCrypto.base64("ab".getBytes(StandardCharsets.US_ASCII))).isEqualTo("YWI=");
        }

        @Test
        @DisplayName("UTC-464 Khóa công khai P-256 thật (0x04 ‖ x ‖ y) do JDK tạo: hợp lệ")
        void utc464_realPublicKeyIsOnTheCurve() throws Exception {
            assertThat(ChatCrypto.isP256Point(newPublicPoint())).isTrue();
        }

        @Test
        @DisplayName("UTC-465 Điểm sinh G của P-256: hợp lệ")
        void utc465_generatorIsOnTheCurve() {
            assertThat(ChatCrypto.isP256Point(point(GX, GY))).isTrue();
        }

        @Test
        @DisplayName("UTC-466 Đổi 1 bit của y: không còn nằm trên đường cong")
        void utc466_movedPointIsRefused() throws Exception {
            byte[] moved = newPublicPoint();
            moved[64] ^= 1;

            assertThat(ChatCrypto.isP256Point(moved)).isFalse();
        }

        @Test
        @DisplayName("UTC-467 Byte đầu 0x02, 0x03 (dạng nén) hoặc 0x00: không hợp lệ")
        void utc467_wrongPrefixIsRefused() {
            for (byte prefix : new byte[] {0x02, 0x03, 0x00}) {
                byte[] raw = point(GX, GY);
                raw[0] = prefix;
                assertThat(ChatCrypto.isP256Point(raw)).isFalse();
            }
        }

        @Test
        @DisplayName("UTC-468 Độ dài 64 byte, 66 byte, 0 byte hoặc null: không hợp lệ")
        void utc468_wrongLengthIsRefused() {
            byte[] g = point(GX, GY);
            assertThat(ChatCrypto.isP256Point(Arrays.copyOf(g, 64))).isFalse();
            assertThat(ChatCrypto.isP256Point(Arrays.copyOf(g, 66))).isFalse();
            assertThat(ChatCrypto.isP256Point(new byte[0])).isFalse();
            assertThat(ChatCrypto.isP256Point(null)).isFalse();
        }

        @Test
        @DisplayName("UTC-469 x = p hoặc y = p (đủ 32 byte nhưng ngoài trường số): không hợp lệ")
        void utc469_coordinatesOutsideTheFieldAreRefused() {
            assertThat(ChatCrypto.isP256Point(point(P, GY))).isFalse();
            assertThat(ChatCrypto.isP256Point(point(GX, P))).isFalse();
        }

        @Test
        @DisplayName("UTC-470 Điểm (0, 0): không hợp lệ")
        void utc470_originIsRefused() {
            assertThat(ChatCrypto.isP256Point(point(BigInteger.ZERO, BigInteger.ZERO))).isFalse();
        }

        @Test
        @DisplayName("UTC-471 Franking tag khớp véc-tơ kiểm thử chung của web và ứng dụng")
        void utc471_frankingTagMatchesTheSharedVector() {
            assertThat(VECTOR_FK).hasSize(32);
            assertThat(ChatCrypto.frankingTag(VECTOR_FK, 42, 7, VECTOR_BODY)).isEqualTo(VECTOR_TAG);
        }

        @Test
        @DisplayName("UTC-472 Đổi cuộc trò chuyện, người gửi, nội dung hoặc khóa franking: tag khác")
        void utc472_anyChangeGivesAnotherTag() {
            byte[] otherBody = VECTOR_BODY.clone();
            otherBody[otherBody.length - 3] ^= 1;
            byte[] otherKey = VECTOR_FK.clone();
            otherKey[0] ^= 1;

            assertThat(ChatCrypto.frankingTag(VECTOR_FK, 43, 7, VECTOR_BODY)).isNotEqualTo(VECTOR_TAG);
            assertThat(ChatCrypto.frankingTag(VECTOR_FK, 42, 8, VECTOR_BODY)).isNotEqualTo(VECTOR_TAG);
            assertThat(ChatCrypto.frankingTag(VECTOR_FK, 42, 7, otherBody)).isNotEqualTo(VECTOR_TAG);
            assertThat(ChatCrypto.frankingTag(otherKey, 42, 7, VECTOR_BODY)).isNotEqualTo(VECTOR_TAG);
        }

        @Test
        @DisplayName("UTC-473 Tag là 64 ký tự hex chữ thường (HMAC-SHA256)")
        void utc473_tagIsLowerCaseHex() {
            assertThat(ChatCrypto.frankingTag(new byte[32], 1, 2, new byte[0])).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("UTC-474 So sánh hai tag giống nhau: true")
        void utc474_equalTagsMatch() {
            assertThat(ChatCrypto.sameTag(VECTOR_TAG, new String(VECTOR_TAG))).isTrue();
        }

        @Test
        @DisplayName("UTC-475 Tag khác ký tự cuối, khác hoa/thường, ngắn hơn, hoặc một bên null: false")
        void utc475_differentTagsDoNotMatch() {
            String lastChanged = VECTOR_TAG.substring(0, 63) + "1";
            assertThat(ChatCrypto.sameTag(VECTOR_TAG, lastChanged)).isFalse();
            assertThat(ChatCrypto.sameTag(VECTOR_TAG, VECTOR_TAG.toUpperCase())).isFalse();
            assertThat(ChatCrypto.sameTag(VECTOR_TAG, VECTOR_TAG.substring(0, 63))).isFalse();
            assertThat(ChatCrypto.sameTag(null, VECTOR_TAG)).isFalse();
            assertThat(ChatCrypto.sameTag(VECTOR_TAG, null)).isFalse();
        }

        @Test
        @DisplayName("UTC-476 Byte UTF-8 hợp lệ (tiếng Việt, tiếng Nhật) và mảng rỗng: trả về đúng chuỗi")
        void utc476_validUtf8IsDecoded() {
            String text = "Xin chào, ảnh thẻ cư trú 在留カード";

            assertThat(ChatCrypto.utf8(text.getBytes(StandardCharsets.UTF_8))).isEqualTo(text);
            assertThat(ChatCrypto.utf8(new byte[0])).isEmpty();
        }

        @Test
        @DisplayName("UTC-477 Byte không phải UTF-8 (C3 28, FF, chuỗi 3 byte bị cắt E6 97): trả về null")
        void utc477_invalidUtf8GivesNull() {
            for (byte[] bad : List.of(new byte[] {(byte) 0xC3, 0x28}, new byte[] {(byte) 0xFF},
                    new byte[] {(byte) 0xE6, (byte) 0x97})) {
                assertThat(ChatCrypto.utf8(bad)).isNull();
            }
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. ChatTyping – \"đang soạn tin\" trong 6 giây (UTC-478 → UTC-487)")
    class Typing {

        private MovableClock clock;
        private ChatTyping typing;

        @BeforeEach
        void setUp() {
            clock = new MovableClock(NOW);
            typing = new ChatTyping(clock);
        }

        @Test
        @DisplayName("UTC-478 Vừa báo đang soạn: hiện \"đang soạn tin\" ngay")
        void utc478_pingShowsTyping() {
            typing.ping(500, 10);

            assertThat(typing.typing(500, 10)).isTrue();
        }

        @Test
        @DisplayName("UTC-479 5,999 giây sau lần báo: vẫn đang soạn")
        void utc479_stillTypingJustBeforeSixSeconds() {
            typing.ping(500, 10);
            clock.set(NOW.plusMillis(5999));

            assertThat(typing.typing(500, 10)).isTrue();
        }

        @Test
        @DisplayName("UTC-480 Đúng 6 giây sau lần báo: hết \"đang soạn tin\"")
        void utc480_typingEndsAtSixSeconds() {
            typing.ping(500, 10);
            clock.set(NOW.plus(ChatTyping.TTL));

            assertThat(typing.typing(500, 10)).isFalse();
            assertThat(ChatTyping.TTL).isEqualTo(Duration.ofSeconds(6));
        }

        @Test
        @DisplayName("UTC-481 Chưa từng báo đang soạn: false")
        void utc481_neverPingedIsNotTyping() {
            assertThat(typing.typing(500, 10)).isFalse();
        }

        @Test
        @DisplayName("UTC-482 Gửi tin xong (stop): hết \"đang soạn tin\" ngay")
        void utc482_stopEndsTypingAtOnce() {
            typing.ping(500, 10);
            typing.stop(500, 10);

            assertThat(typing.typing(500, 10)).isFalse();
        }

        @Test
        @DisplayName("UTC-483 Tính riêng theo cuộc trò chuyện và theo người: cuộc khác hoặc người kia không bị coi là đang soạn")
        void utc483_pingIsPerConversationAndMember() {
            typing.ping(500, 10);

            assertThat(typing.typing(501, 10)).isFalse();
            assertThat(typing.typing(500, 20)).isFalse();
        }

        @Test
        @DisplayName("UTC-484 Báo lại sau 4 giây: tính lại 6 giây từ lần báo mới (9 giây còn, 10 giây hết)")
        void utc484_newPingRestartsTheSixSeconds() {
            typing.ping(500, 10);
            clock.set(NOW.plusSeconds(4));
            typing.ping(500, 10);

            clock.set(NOW.plusSeconds(9));
            assertThat(typing.typing(500, 10)).isTrue();
            clock.set(NOW.plusSeconds(10));
            assertThat(typing.typing(500, 10)).isFalse();
        }

        @Test
        @DisplayName("UTC-485 stop cho người chưa từng báo: không lỗi")
        void utc485_stopWithoutPingIsHarmless() {
            assertThatCode(() -> typing.stop(500, 10)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("UTC-486 Đã có 5.000 lần báo hết hạn: lần báo kế tiếp dọn hết, bộ nhớ không tăng mãi")
        void utc486_expiredPingsArePrunedAtFiveThousand() throws Exception {
            for (int user = 0; user < 5000; user++) {
                typing.ping(500, user);
            }
            clock.set(NOW.plusSeconds(7));

            typing.ping(501, 10);

            assertThat(mapSize(typing, "pings")).isEqualTo(1);
            assertThat(typing.typing(501, 10)).isTrue();
        }

        @Test
        @DisplayName("UTC-487 5.000 lần báo còn hạn: không bị dọn khi có lần báo mới (5.001 mục)")
        void utc487_freshPingsAreKept() throws Exception {
            for (int user = 0; user < 5000; user++) {
                typing.ping(500, user);
            }
            clock.set(NOW.plusSeconds(1));

            typing.ping(501, 10);

            assertThat(mapSize(typing, "pings")).isEqualTo(5001);
            assertThat(typing.typing(500, 4999)).isTrue();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("03. ChatViewers – Ai đang đọc cuộc trò chuyện (10 giây) (UTC-488 → UTC-494)")
    class Viewers {

        private MovableClock clock;
        private ChatViewers viewers;

        @BeforeEach
        void setUp() {
            clock = new MovableClock(NOW);
            viewers = new ChatViewers(clock);
        }

        @Test
        @DisplayName("UTC-488 Trang vừa hỏi cập nhật của cuộc trò chuyện: đang đọc (không gửi push cho người này)")
        void utc488_seenMeansViewing() {
            viewers.seen(500, 10);

            assertThat(viewers.viewing(500, 10)).isTrue();
        }

        @Test
        @DisplayName("UTC-489 9,999 giây sau lần hỏi: vẫn đang đọc")
        void utc489_stillViewingJustBeforeTenSeconds() {
            viewers.seen(500, 10);
            clock.set(NOW.plusMillis(9999));

            assertThat(viewers.viewing(500, 10)).isTrue();
        }

        @Test
        @DisplayName("UTC-490 Đúng 10 giây sau lần hỏi: không còn đang đọc")
        void utc490_viewingEndsAtTenSeconds() {
            viewers.seen(500, 10);
            clock.set(NOW.plus(ChatViewers.FRESH));

            assertThat(viewers.viewing(500, 10)).isFalse();
            assertThat(ChatViewers.FRESH).isEqualTo(Duration.ofSeconds(10));
        }

        @Test
        @DisplayName("UTC-491 Chưa từng hỏi, cuộc trò chuyện khác hoặc người khác: không đang đọc")
        void utc491_otherConversationOrMemberIsNotViewing() {
            assertThat(viewers.viewing(500, 10)).isFalse();
            viewers.seen(500, 10);
            assertThat(viewers.viewing(501, 10)).isFalse();
            assertThat(viewers.viewing(500, 20)).isFalse();
        }

        @Test
        @DisplayName("UTC-492 Hỏi lại sau 8 giây: tính lại 10 giây (15 giây còn, 18 giây hết)")
        void utc492_newVisitRestartsTheWindow() {
            viewers.seen(500, 10);
            clock.set(NOW.plusSeconds(8));
            viewers.seen(500, 10);

            clock.set(NOW.plusSeconds(15));
            assertThat(viewers.viewing(500, 10)).isTrue();
            clock.set(NOW.plusSeconds(18));
            assertThat(viewers.viewing(500, 10)).isFalse();
        }

        @Test
        @DisplayName("UTC-493 Đã có 5.000 mục hết hạn: lần hỏi kế tiếp dọn hết")
        void utc493_expiredEntriesArePrunedAtFiveThousand() throws Exception {
            for (int user = 0; user < 5000; user++) {
                viewers.seen(500, user);
            }
            clock.set(NOW.plusSeconds(11));

            viewers.seen(501, 10);

            assertThat(mapSize(viewers, "seen")).isEqualTo(1);
        }

        @Test
        @DisplayName("UTC-494 5.000 mục còn hạn: không bị dọn (5.001 mục)")
        void utc494_freshEntriesAreKept() throws Exception {
            for (int user = 0; user < 5000; user++) {
                viewers.seen(500, user);
            }
            clock.set(NOW.plusSeconds(2));

            viewers.seen(501, 10);

            assertThat(mapSize(viewers, "seen")).isEqualTo(5001);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("04. ChatCleanup – Dọn tin thu hồi và tệp chưa gửi lúc 03:50 giờ Nhật (UTC-495 → UTC-503)")
    class Cleanup {

        private ChatRepository repository;
        private MediaStorage storage;
        private PlatformTransactionManager txManager;
        private ChatCleanup cleanup;

        @BeforeEach
        void setUp() {
            repository = mock(ChatRepository.class);
            storage = mock(MediaStorage.class);
            txManager = mock(PlatformTransactionManager.class);
            cleanup = new ChatCleanup(repository, storage, txManager, Clock.fixed(NOW, ZoneOffset.UTC));
        }

        private FileRow unsent(long id, String key) {
            return new FileRow(id, 500, 10, null, "IMAGE", key, 1000, NOW.minus(Duration.ofHours(25)), false);
        }

        @Test
        @DisplayName("UTC-495 Tin thu hồi quá 30 ngày: xóa nội dung và các bản sửa, kết quả đếm 3 tin")
        void utc495_oldRecallsAreEmptied() {
            when(repository.emptyRecalledBefore(NOW.minus(Duration.ofDays(30)))).thenReturn(3);

            ChatCleanup.Result result = cleanup.run();

            verify(repository).emptyRecalledBefore(Instant.parse("2026-10-21T03:00:00Z"));
            assertThat(result.messagesEmptied()).isEqualTo(3);
        }

        @Test
        @DisplayName("UTC-496 Tệp tải lên quá 24 giờ chưa gửi kèm tin: xóa dòng chat_files (tối đa 1.000) và dữ liệu trong kho")
        void utc496_oldUnsentFilesAreDeleted() {
            when(repository.lockUnattachedBefore(NOW.minus(Duration.ofHours(24)), 1000)).thenReturn(List.of(
                    unsent(5, "chat/2026/11/e.bin"), unsent(6, "chat/2026/11/f.bin")));

            ChatCleanup.Result result = cleanup.run();

            verify(repository).lockUnattachedBefore(Instant.parse("2026-11-19T03:00:00Z"), 1000);
            verify(repository).deleteFiles(List.of(5L, 6L));
            verify(storage).delete("chat/2026/11/e.bin");
            verify(storage).delete("chat/2026/11/f.bin");
            assertThat(result.filesDeleted()).isEqualTo(2);
        }

        @Test
        @DisplayName("UTC-497 Dữ liệu trong kho chỉ bị xóa sau khi giao dịch xóa dòng chat_files đã commit")
        void utc497_storageIsCleanedAfterTheCommit() {
            when(repository.lockUnattachedBefore(any(), anyInt())).thenReturn(List.of(unsent(5, "chat/2026/11/e.bin")));

            cleanup.run();

            InOrder order = inOrder(repository, txManager, storage);
            order.verify(repository).deleteFiles(List.of(5L));
            order.verify(txManager).commit(any());
            order.verify(storage).delete("chat/2026/11/e.bin");
        }

        @Test
        @DisplayName("UTC-498 Xóa một tệp trong kho bị lỗi: vẫn xóa tệp còn lại, vẫn đếm 2 tệp, không ném lỗi")
        void utc498_storageFailureIsLoggedAndSkipped() {
            when(repository.lockUnattachedBefore(any(), anyInt())).thenReturn(List.of(
                    unsent(5, "chat/2026/11/e.bin"), unsent(6, "chat/2026/11/f.bin")));
            doThrow(new IllegalStateException("R2 down")).when(storage).delete("chat/2026/11/e.bin");

            ChatCleanup.Result result = cleanup.run();

            verify(storage).delete("chat/2026/11/f.bin");
            assertThat(result.filesDeleted()).isEqualTo(2);
        }

        @Test
        @DisplayName("UTC-499 Không có gì để dọn: kết quả (0, 0), không đụng tới kho lưu trữ")
        void utc499_nothingToClean() {
            ChatCleanup.Result result = cleanup.run();

            assertThat(result).isEqualTo(new ChatCleanup.Result(0, 0));
            verify(repository).deleteFiles(List.of());
            verifyNoInteractions(storage);
        }

        @Test
        @DisplayName("UTC-500 Hai giao dịch ngắn riêng: mở 2 lần, commit 2 lần, không rollback")
        void utc500_twoShortTransactions() {
            cleanup.run();

            verify(txManager, times(2)).getTransaction(any());
            verify(txManager, times(2)).commit(any());
            verify(txManager, never()).rollback(any());
        }

        @Test
        @DisplayName("UTC-501 Lỗi khi khóa tệp chưa gửi: giao dịch thứ hai rollback, lỗi được ném ra, kho không bị đụng, phần dọn tin đã commit")
        void utc501_failureInTheFileStepRollsBack() {
            when(repository.lockUnattachedBefore(any(), anyInt())).thenThrow(new IllegalStateException("db down"));

            assertThatThrownBy(() -> cleanup.run()).isInstanceOf(IllegalStateException.class).hasMessage("db down");

            verify(txManager, times(1)).commit(any());
            verify(txManager).rollback(any());
            verify(repository).emptyRecalledBefore(any());
            verify(repository, never()).deleteFiles(any());
            verifyNoInteractions(storage);
        }

        @Test
        @DisplayName("UTC-502 Lịch chạy purge(): làm đúng các bước dọn của run()")
        void utc502_purgeRunsTheCleanUp() {
            when(repository.lockUnattachedBefore(any(), anyInt())).thenReturn(List.of(unsent(5, "chat/2026/11/e.bin")));

            cleanup.purge();

            verify(repository).emptyRecalledBefore(NOW.minus(ChatCleanup.RECALLED_KEEP));
            verify(repository).lockUnattachedBefore(NOW.minus(ChatCleanup.UNATTACHED_KEEP), ChatCleanup.FILE_BATCH);
            verify(storage).delete(anyString());
        }

        @Test
        @DisplayName("UTC-503 Lịch: mỗi ngày lúc 03:50 giờ Nhật (cron \"0 50 3 * * *\", Asia/Tokyo)")
        void utc503_scheduledAtTenToFourJapanTime() throws Exception {
            Scheduled scheduled = ChatCleanup.class.getMethod("purge").getAnnotation(Scheduled.class);

            assertThat(scheduled).isNotNull();
            assertThat(scheduled.cron()).isEqualTo("0 50 3 * * *");
            assertThat(scheduled.zone()).isEqualTo("Asia/Tokyo");
        }
    }
}
