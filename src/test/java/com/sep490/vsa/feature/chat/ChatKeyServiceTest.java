package com.sep490.vsa.feature.chat;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.chat.ChatDtos.KeyBackup;
import com.sep490.vsa.feature.chat.ChatDtos.KeyRequest;
import com.sep490.vsa.feature.chat.ChatDtos.MyKey;
import com.sep490.vsa.feature.chat.ChatDtos.PublicKey;
import com.sep490.vsa.feature.chat.ChatRepository.Account;
import com.sep490.vsa.feature.chat.ChatRepository.ConversationRow;
import com.sep490.vsa.feature.chat.ChatRepository.KeyRow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the identity keys of end-to-end encrypted chat ({@link ChatKeyService}): reading one's own key with its
 * backup ({@code myKey}), turning encryption on or making a new key ({@code create}: a real P-256 point, a backup of
 * the sizes the browser makes, at most 5 new keys a Japan day, the key before revoked) and reading a public key
 * ({@code key}: one's own, or of a member one shares a conversation with). Rules from CLAUDE.md ("Tin nhắn", end-to-end
 * encryption) and ChatEncryptionFlowTest.
 * <p>
 * The database is not used: {@link ChatRepository} is a mock; the clock is fixed at 20/11/2026 12:00 Japan time. Real
 * P-256 public keys are made with the JDK's {@code KeyPairGenerator("EC")}.
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("ChatKeyService – Tin nhắn: khóa mã hóa đầu cuối (CHAT)")
class ChatKeyServiceTest {

    private static final long ME_ID = 10L;
    private static final long PEER_ID = 20L;
    private static final long STRANGER_ID = 40L;
    /** 12:00 in Japan, 20/11/2026. */
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private static final AppUserPrincipal ME = principal(ME_ID, "USER");
    private static final BigInteger P = new BigInteger(
            "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16);

    private static final String MSG18 = "Bạn không có quyền thực hiện thao tác này.";
    private static final String LOGIN = "Bạn cần đăng nhập để nhắn tin.";

    private static byte[] point;

    private ChatRepository repository;
    private ChatKeyService service;

    @BeforeAll
    static void makeKey() throws Exception {
        point = newPublicPoint();
    }

    @BeforeEach
    void setUp() {
        repository = mock(ChatRepository.class);
        service = serviceAt(NOW);
        when(repository.account(ME_ID)).thenReturn(Optional.of(account(ME_ID, "USER", "ACTIVE")));
        when(repository.insertKey(anyLong(), anyString(), anyString(), anyString(), anyString(), anyInt(), any()))
                .thenReturn(41L);
    }

    // ---------------------------------------------------------------- helpers

    private ChatKeyService serviceAt(Instant instant) {
        return new ChatKeyService(repository, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static AppUserPrincipal principal(long id, String role) {
        return new AppUserPrincipal(id, "user" + id + "@example.org", "User " + id, role, "/");
    }

    private static Account account(long id, String role, String status) {
        return new Account(id, "User " + id, null, role, status, NOW.minus(Duration.ofDays(30)));
    }

    /** A real P-256 public key as the browser exports it: 0x04 ‖ x ‖ y. */
    private static byte[] newPublicPoint() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        ECPublicKey key = (ECPublicKey) generator.generateKeyPair().getPublic();
        byte[] raw = new byte[65];
        raw[0] = 0x04;
        System.arraycopy(fixed(key.getW().getAffineX()), 0, raw, 1, 32);
        System.arraycopy(fixed(key.getW().getAffineY()), 0, raw, 33, 32);
        return raw;
    }

    private static byte[] fixed(BigInteger value) {
        byte[] bytes = value.toByteArray();
        byte[] out = new byte[32];
        int length = Math.min(bytes.length, 32);
        System.arraycopy(bytes, bytes.length - length, out, 32 - length, length);
        return out;
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i * 13 + 5);
        }
        return b;
    }

    /** A valid "Bật mã hóa" request: a real point, a 154-byte wrapped key, salt 16, iv 12, 600,000 iterations. */
    private static KeyRequest valid() {
        return new KeyRequest(b64(point), b64(bytes(154)), b64(bytes(16)), b64(bytes(12)), 600_000);
    }

    private static KeyRequest withPublicKey(String publicKey) {
        KeyRequest r = valid();
        return new KeyRequest(publicKey, r.wrappedPrivateKey(), r.wrapSalt(), r.wrapIv(), r.wrapIterations());
    }

    private static KeyRequest withWrapped(String wrapped) {
        KeyRequest r = valid();
        return new KeyRequest(r.publicKey(), wrapped, r.wrapSalt(), r.wrapIv(), r.wrapIterations());
    }

    private static KeyRequest withSalt(String salt) {
        KeyRequest r = valid();
        return new KeyRequest(r.publicKey(), r.wrappedPrivateKey(), salt, r.wrapIv(), r.wrapIterations());
    }

    private static KeyRequest withIv(String iv) {
        KeyRequest r = valid();
        return new KeyRequest(r.publicKey(), r.wrappedPrivateKey(), r.wrapSalt(), iv, r.wrapIterations());
    }

    private static KeyRequest withIterations(Integer iterations) {
        KeyRequest r = valid();
        return new KeyRequest(r.publicKey(), r.wrappedPrivateKey(), r.wrapSalt(), r.wrapIv(), iterations);
    }

    private static KeyRow keyRow(long id, long userId, Instant revokedAt) {
        return new KeyRow(id, userId, b64(point), "d3JhcHBlZA==", "c2FsdA==", "aXY=", 600_000,
                NOW.minus(Duration.ofDays(2)), revokedAt);
    }

    private static ApiException apiError(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(ApiException.class);
        return (ApiException) thrown;
    }

    private static void assertApiError(Runnable call, HttpStatus status, String message) {
        ApiException e = apiError(call);
        assertThat(e.getStatus()).isEqualTo(status);
        assertThat(e.getMessage()).isEqualTo(message);
    }

    private void verifyNothingWritten() {
        verify(repository, never()).lockUserDay(anyLong(), any());
        verify(repository, never()).revokeKeys(anyLong(), any());
        verify(repository, never()).insertKey(anyLong(), anyString(), anyString(), anyString(), anyString(), anyInt(),
                any());
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. myKey – Xem khóa hiện tại của mình (UTC-425 → UTC-430)")
    class MyKeyTests {

        @Test
        @DisplayName("UTC-425 Chưa bật mã hóa: trả về key = null")
        void utc425_noKeyYet() {
            MyKey result = service.myKey(ME);

            assertThat(result.key()).isNull();
            verify(repository).currentKey(ME_ID);
        }

        @Test
        @DisplayName("UTC-426 Đã có khóa: trả về khóa công khai và bản sao lưu khóa bí mật (salt, iv, số vòng lặp)")
        void utc426_currentKeyWithBackupIsReturned() {
            KeyRow row = keyRow(41, ME_ID, null);
            when(repository.currentKey(ME_ID)).thenReturn(Optional.of(row));

            KeyBackup key = service.myKey(ME).key();

            assertThat(key).isEqualTo(new KeyBackup(41, b64(point), "d3JhcHBlZA==", "c2FsdA==", "aXY=", 600_000,
                    row.createdAt()));
        }

        @Test
        @DisplayName("UTC-427 Khách chưa đăng nhập: 401 \"Bạn cần đăng nhập để nhắn tin.\"")
        void utc427_guestIsUnauthorized() {
            assertApiError(() -> service.myKey(null), HttpStatus.UNAUTHORIZED, LOGIN);
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("UTC-428 Phiên trỏ tới tài khoản không còn: 401 \"Bạn chưa đăng nhập.\"")
        void utc428_missingAccountIsUnauthorized() {
            when(repository.account(ME_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.myKey(ME), HttpStatus.UNAUTHORIZED, "Bạn chưa đăng nhập.");
            verify(repository, never()).currentKey(anyLong());
        }

        @Test
        @DisplayName("UTC-429 Moderator, BTV tiếng Nhật, Admin: 403 MSG18")
        void utc429_staffIsForbidden() {
            for (String role : new String[] {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"}) {
                when(repository.account(ME_ID)).thenReturn(Optional.of(account(ME_ID, role, "ACTIVE")));
                assertApiError(() -> service.myKey(principal(ME_ID, role)), HttpStatus.FORBIDDEN, MSG18);
            }
            verify(repository, never()).currentKey(anyLong());
        }

        @Test
        @DisplayName("UTC-430 Tài khoản thành viên bị khóa hoặc đã xóa: 403 MSG18")
        void utc430_lockedOrDeletedMemberIsForbidden() {
            for (String status : new String[] {"LOCKED", "DELETED"}) {
                when(repository.account(ME_ID)).thenReturn(Optional.of(account(ME_ID, "USER", status)));
                assertApiError(() -> service.myKey(ME), HttpStatus.FORBIDDEN, MSG18);
            }
            verify(repository, never()).currentKey(anyLong());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. create – Bật mã hóa / tạo khóa mới (UTC-431 → UTC-453)")
    class CreateKey {

        @Test
        @DisplayName("UTC-431 Khóa P-256 hợp lệ và bản sao lưu đúng cỡ: thu hồi khóa cũ, lưu khóa mới, trả về bản sao lưu")
        void utc431_validKeyIsSaved() {
            KeyRequest request = valid();

            KeyBackup result = service.create(ME, request);

            verify(repository).revokeKeys(ME_ID, NOW);
            verify(repository).insertKey(ME_ID, request.publicKey(), request.wrappedPrivateKey(), request.wrapSalt(),
                    request.wrapIv(), 600_000, NOW);
            assertThat(result).isEqualTo(new KeyBackup(41L, request.publicKey(), request.wrappedPrivateKey(),
                    request.wrapSalt(), request.wrapIv(), 600_000, NOW));
        }

        @Test
        @DisplayName("UTC-432 Thứ tự: khóa dòng ngày (giờ Nhật), đếm khóa trong ngày, thu hồi khóa cũ, rồi lưu khóa mới")
        void utc432_dayIsLockedCountedThenKeyReplaced() {
            service.create(ME, valid());

            InOrder order = inOrder(repository);
            order.verify(repository).lockUserDay(ME_ID, LocalDate.of(2026, 11, 20));
            order.verify(repository).countKeysBetween(ME_ID, Instant.parse("2026-11-19T15:00:00Z"),
                    Instant.parse("2026-11-20T15:00:00Z"));
            order.verify(repository).revokeKeys(ME_ID, NOW);
            order.verify(repository).insertKey(eq(ME_ID), anyString(), anyString(), anyString(), anyString(), anyInt(),
                    eq(NOW));
        }

        @Test
        @DisplayName("UTC-433 Các trường base64 gửi không có dấu '=': được lưu và trả về ở dạng base64 chuẩn")
        void utc433_base64IsStoredCanonically() {
            KeyRequest padded = valid();
            KeyRequest unpadded = new KeyRequest(padded.publicKey().replace("=", ""),
                    padded.wrappedPrivateKey().replace("=", ""), padded.wrapSalt().replace("=", ""),
                    padded.wrapIv(), 600_000);

            KeyBackup result = service.create(ME, unpadded);

            assertThat(unpadded.wrapSalt()).doesNotEndWith("=");
            verify(repository).insertKey(ME_ID, padded.publicKey(), padded.wrappedPrivateKey(), padded.wrapSalt(),
                    padded.wrapIv(), 600_000, NOW);
            assertThat(result.wrapSalt()).isEqualTo(padded.wrapSalt());
        }

        @Test
        @DisplayName("UTC-434 Không gửi dữ liệu (request = null): lỗi \"Khóa công khai không hợp lệ.\"")
        void utc434_missingRequestIsRefused() {
            assertApiError(() -> service.create(ME, null), HttpStatus.BAD_REQUEST, ChatKeyService.PUBLIC_KEY_INVALID);
            verifyNothingWritten();
        }

        @Test
        @DisplayName("UTC-435 Khóa công khai null, rỗng, không phải base64, 64 byte, 66 byte, toàn số 0, dạng nén 0x02: lỗi \"Khóa công khai không hợp lệ.\"")
        void utc435_malformedPublicKeyIsRefused() {
            byte[] compressed = point.clone();
            compressed[0] = 0x02;
            byte[] zeros = new byte[65];
            for (String bad : new String[] {null, "", "not base64!", b64(Arrays.copyOf(point, 64)),
                    b64(Arrays.copyOf(point, 66)), b64(zeros), b64(compressed)}) {
                assertApiError(() -> service.create(ME, withPublicKey(bad)), HttpStatus.BAD_REQUEST,
                        ChatKeyService.PUBLIC_KEY_INVALID);
            }
            verifyNothingWritten();
        }

        @Test
        @DisplayName("UTC-436 Điểm không nằm trên đường cong P-256 (đổi 1 bit của y): lỗi \"Khóa công khai không hợp lệ.\"")
        void utc436_pointOffTheCurveIsRefused() {
            byte[] moved = point.clone();
            moved[64] ^= 1;

            assertApiError(() -> service.create(ME, withPublicKey(b64(moved))), HttpStatus.BAD_REQUEST,
                    ChatKeyService.PUBLIC_KEY_INVALID);
            verifyNothingWritten();
        }

        @Test
        @DisplayName("UTC-437 Tọa độ x = p (ngoài trường số dù đủ 32 byte): lỗi \"Khóa công khai không hợp lệ.\"")
        void utc437_coordinateOutsideTheFieldIsRefused() {
            byte[] tooBig = point.clone();
            System.arraycopy(fixed(P), 0, tooBig, 1, 32);

            assertApiError(() -> service.create(ME, withPublicKey(b64(tooBig))), HttpStatus.BAD_REQUEST,
                    ChatKeyService.PUBLIC_KEY_INVALID);
            verifyNothingWritten();
        }

        @Test
        @DisplayName("UTC-438 Chuỗi khóa công khai dài hơn 120 ký tự: lỗi \"Khóa công khai không hợp lệ.\"")
        void utc438_overlongPublicKeyTextIsRefused() {
            String text = b64(bytes(91));
            assertThat(text).hasSize(124);

            assertApiError(() -> service.create(ME, withPublicKey(text)), HttpStatus.BAD_REQUEST,
                    ChatKeyService.PUBLIC_KEY_INVALID);
            verifyNothingWritten();
        }

        @Test
        @DisplayName("UTC-439 Khóa bí mật đã bọc đúng 16 byte và đúng 600 byte: được nhận")
        void utc439_wrappedKeySizeBoundariesAreAccepted() {
            service.create(ME, withWrapped(b64(bytes(16))));
            service.create(ME, withWrapped(b64(bytes(600))));

            verify(repository).insertKey(eq(ME_ID), anyString(), eq(b64(bytes(16))), anyString(), anyString(),
                    anyInt(), eq(NOW));
            verify(repository).insertKey(eq(ME_ID), anyString(), eq(b64(bytes(600))), anyString(), anyString(),
                    anyInt(), eq(NOW));
        }

        @Test
        @DisplayName("UTC-440 Khóa bí mật đã bọc 15 byte, 601 byte, null, không phải base64, chuỗi dài hơn 1.000 ký tự: lỗi \"Bản sao lưu khóa bí mật không hợp lệ.\"")
        void utc440_badWrappedKeyIsRefused() {
            for (String bad : new String[] {b64(bytes(15)), b64(bytes(601)), null, "%%%", b64(bytes(760))}) {
                assertApiError(() -> service.create(ME, withWrapped(bad)), HttpStatus.BAD_REQUEST,
                        ChatKeyService.BACKUP_INVALID);
            }
            assertThat(b64(bytes(760)).length()).isGreaterThan(1000);
            verifyNothingWritten();
        }

        @Test
        @DisplayName("UTC-441 Salt đúng 16 byte và đúng 48 byte: được nhận")
        void utc441_saltSizeBoundariesAreAccepted() {
            service.create(ME, withSalt(b64(bytes(16))));
            service.create(ME, withSalt(b64(bytes(48))));

            verify(repository).insertKey(eq(ME_ID), anyString(), anyString(), eq(b64(bytes(48))), anyString(),
                    anyInt(), eq(NOW));
            assertThat(b64(bytes(48))).hasSize(64);
        }

        @Test
        @DisplayName("UTC-442 Salt 15 byte, 49 byte hoặc null: lỗi \"Bản sao lưu khóa bí mật không hợp lệ.\"")
        void utc442_badSaltIsRefused() {
            for (String bad : new String[] {b64(bytes(15)), b64(bytes(49)), null}) {
                assertApiError(() -> service.create(ME, withSalt(bad)), HttpStatus.BAD_REQUEST,
                        ChatKeyService.BACKUP_INVALID);
            }
            verifyNothingWritten();
        }

        @Test
        @DisplayName("UTC-443 IV 11 byte, 13 byte, null hoặc \"%%%\": lỗi \"Bản sao lưu khóa bí mật không hợp lệ.\" (chỉ nhận đúng 12 byte)")
        void utc443_badIvIsRefused() {
            for (String bad : new String[] {b64(bytes(11)), b64(bytes(13)), null, "%%%"}) {
                assertApiError(() -> service.create(ME, withIv(bad)), HttpStatus.BAD_REQUEST,
                        ChatKeyService.BACKUP_INVALID);
            }
            verifyNothingWritten();
        }

        @Test
        @DisplayName("UTC-444 Số vòng lặp PBKDF2 đúng 100.000 và đúng 5.000.000: được nhận")
        void utc444_iterationBoundariesAreAccepted() {
            service.create(ME, withIterations(100_000));
            service.create(ME, withIterations(5_000_000));

            verify(repository).insertKey(eq(ME_ID), anyString(), anyString(), anyString(), anyString(), eq(100_000),
                    eq(NOW));
            verify(repository).insertKey(eq(ME_ID), anyString(), anyString(), anyString(), anyString(), eq(5_000_000),
                    eq(NOW));
        }

        @Test
        @DisplayName("UTC-445 Số vòng lặp 99.999, 5.000.001 hoặc null: lỗi \"Bản sao lưu khóa bí mật không hợp lệ.\"")
        void utc445_badIterationsAreRefused() {
            for (Integer bad : new Integer[] {99_999, 5_000_001, null}) {
                assertApiError(() -> service.create(ME, withIterations(bad)), HttpStatus.BAD_REQUEST,
                        ChatKeyService.BACKUP_INVALID);
            }
            verifyNothingWritten();
        }

        @Test
        @DisplayName("UTC-446 Khóa công khai và bản sao lưu cùng sai: báo lỗi khóa công khai trước")
        void utc446_publicKeyIsCheckedBeforeBackup() {
            KeyRequest bothBad = new KeyRequest("not base64!", null, null, null, null);

            assertApiError(() -> service.create(ME, bothBad), HttpStatus.BAD_REQUEST,
                    ChatKeyService.PUBLIC_KEY_INVALID);
            verifyNothingWritten();
        }

        @Test
        @DisplayName("UTC-447 Đã tạo 4 khóa hôm nay: khóa thứ 5 vẫn được tạo")
        void utc447_fifthKeyOfTheDayIsAccepted() {
            when(repository.countKeysBetween(anyLong(), any(), any())).thenReturn(4);

            service.create(ME, valid());

            verify(repository).revokeKeys(ME_ID, NOW);
            verify(repository).insertKey(anyLong(), anyString(), anyString(), anyString(), anyString(), anyInt(), any());
        }

        @Test
        @DisplayName("UTC-448 Đã tạo 5 khóa hôm nay: 429 \"Bạn đã tạo đủ 5 khóa mã hóa hôm nay…\", khóa hiện tại giữ nguyên")
        void utc448_sixthKeyOfTheDayIsRefused() {
            when(repository.countKeysBetween(anyLong(), any(), any())).thenReturn(5);

            assertApiError(() -> service.create(ME, valid()), HttpStatus.TOO_MANY_REQUESTS,
                    "Bạn đã tạo đủ 5 khóa mã hóa hôm nay. Vui lòng thử lại vào ngày mai.");
            verify(repository, never()).revokeKeys(anyLong(), any());
            verify(repository, never()).insertKey(anyLong(), anyString(), anyString(), anyString(), anyString(),
                    anyInt(), any());
        }

        @Test
        @DisplayName("UTC-449 00:30 ngày 20/11 giờ Nhật (19/11 15:30 UTC): tính vào ngày 20/11 của Nhật")
        void utc449_justAfterMidnightInJapanCountsTheNewDay() {
            serviceAt(Instant.parse("2026-11-19T15:30:00Z")).create(ME, valid());

            verify(repository).lockUserDay(ME_ID, LocalDate.of(2026, 11, 20));
            verify(repository).countKeysBetween(ME_ID, Instant.parse("2026-11-19T15:00:00Z"),
                    Instant.parse("2026-11-20T15:00:00Z"));
        }

        @Test
        @DisplayName("UTC-450 23:59:59 ngày 19/11 giờ Nhật (19/11 14:59:59 UTC): vẫn tính vào ngày 19/11")
        void utc450_justBeforeMidnightInJapanCountsTheOldDay() {
            serviceAt(Instant.parse("2026-11-19T14:59:59Z")).create(ME, valid());

            verify(repository).lockUserDay(ME_ID, LocalDate.of(2026, 11, 19));
            verify(repository).countKeysBetween(ME_ID, Instant.parse("2026-11-18T15:00:00Z"),
                    Instant.parse("2026-11-19T15:00:00Z"));
        }

        @Test
        @DisplayName("UTC-451 Khách chưa đăng nhập: 401, không ghi gì")
        void utc451_guestIsUnauthorized() {
            assertApiError(() -> service.create(null, valid()), HttpStatus.UNAUTHORIZED, LOGIN);
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("UTC-452 Tài khoản Moderator: 403 MSG18, không ghi gì")
        void utc452_staffIsForbidden() {
            when(repository.account(ME_ID)).thenReturn(Optional.of(account(ME_ID, "MODERATOR", "ACTIVE")));

            assertApiError(() -> service.create(principal(ME_ID, "MODERATOR"), valid()), HttpStatus.FORBIDDEN, MSG18);
            verifyNothingWritten();
        }

        @Test
        @DisplayName("UTC-453 Tài khoản thành viên bị khóa: 403 MSG18, không ghi gì")
        void utc453_lockedMemberIsForbidden() {
            when(repository.account(ME_ID)).thenReturn(Optional.of(account(ME_ID, "USER", "LOCKED")));

            assertApiError(() -> service.create(ME, valid()), HttpStatus.FORBIDDEN, MSG18);
            verifyNothingWritten();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("03. key – Xem khóa công khai (UTC-454 → UTC-460)")
    class ReadPublicKey {

        @Test
        @DisplayName("UTC-454 Khóa của chính mình: trả về khóa công khai, chưa thu hồi, không cần kiểm tra cuộc trò chuyện")
        void utc454_ownKeyIsShown() {
            when(repository.key(41L)).thenReturn(Optional.of(keyRow(41, ME_ID, null)));

            PublicKey result = service.key(ME, 41L);

            assertThat(result).isEqualTo(new PublicKey(41L, ME_ID, b64(point), false));
            verify(repository, never()).conversationOf(anyLong(), anyLong());
        }

        @Test
        @DisplayName("UTC-455 Khóa cũ của mình đã bị thu hồi: vẫn xem được, revoked = true")
        void utc455_ownRevokedKeyIsShownAsRevoked() {
            when(repository.key(40L)).thenReturn(Optional.of(keyRow(40, ME_ID, NOW.minus(Duration.ofDays(1)))));

            assertThat(service.key(ME, 40L).revoked()).isTrue();
        }

        @Test
        @DisplayName("UTC-456 Khóa của thành viên có chung cuộc trò chuyện: xem được (để kiểm tra người viết tin cũ)")
        void utc456_keyOfConversationPeerIsShown() {
            when(repository.key(42L)).thenReturn(Optional.of(keyRow(42, PEER_ID, null)));
            when(repository.conversationOf(ME_ID, PEER_ID)).thenReturn(Optional.of(
                    new ConversationRow(500L, ME_ID, PEER_ID, PEER_ID)));

            PublicKey result = service.key(ME, 42L);

            assertThat(result).isEqualTo(new PublicKey(42L, PEER_ID, b64(point), false));
        }

        @Test
        @DisplayName("UTC-457 Khóa của người chưa từng nhắn tin với mình: 404 \"Không tìm thấy khóa mã hóa này.\"")
        void utc457_keyOfStrangerIsNotFound() {
            when(repository.key(43L)).thenReturn(Optional.of(keyRow(43, STRANGER_ID, null)));

            assertApiError(() -> service.key(ME, 43L), HttpStatus.NOT_FOUND, ChatKeyService.KEY_NOT_FOUND);
            verify(repository).conversationOf(ME_ID, STRANGER_ID);
        }

        @Test
        @DisplayName("UTC-458 Mã khóa không tồn tại: 404 \"Không tìm thấy khóa mã hóa này.\"")
        void utc458_missingKeyIsNotFound() {
            assertApiError(() -> service.key(ME, 999_999_999L), HttpStatus.NOT_FOUND, ChatKeyService.KEY_NOT_FOUND);
        }

        @Test
        @DisplayName("UTC-459 Khách chưa đăng nhập: 401")
        void utc459_guestIsUnauthorized() {
            assertApiError(() -> service.key(null, 41L), HttpStatus.UNAUTHORIZED, LOGIN);
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("UTC-460 Tài khoản Admin: 403 MSG18")
        void utc460_staffIsForbidden() {
            when(repository.account(ME_ID)).thenReturn(Optional.of(account(ME_ID, "ADMIN", "ACTIVE")));

            assertApiError(() -> service.key(principal(ME_ID, "ADMIN"), 41L), HttpStatus.FORBIDDEN, MSG18);
            verify(repository, never()).key(anyLong());
        }
    }
}
