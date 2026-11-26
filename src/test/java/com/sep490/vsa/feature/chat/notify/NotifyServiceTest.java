package com.sep490.vsa.feature.chat.notify;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.chat.notify.NotifyDtos.EndpointRequest;
import com.sep490.vsa.feature.chat.notify.NotifyDtos.Keys;
import com.sep490.vsa.feature.chat.notify.NotifyDtos.PushKey;
import com.sep490.vsa.feature.chat.notify.NotifyDtos.Settings;
import com.sep490.vsa.feature.chat.notify.NotifyDtos.SettingsUpdate;
import com.sep490.vsa.feature.chat.notify.NotifyDtos.SubscriptionRequest;
import com.sep490.vsa.feature.chat.notify.NotifyRepository.Account;
import com.sep490.vsa.feature.chat.notify.WebPushKeyStore.VapidKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the member's choices for new-message notifications of "Tin nhắn" and of their browsers' push
 * subscriptions ({@link NotifyService}), plus the Java side of {@link NotifyRepository} (no row = both on) and
 * {@link PushSubscriptionRepository} (one row per endpoint), from CLAUDE.md "Tin nhắn" → "New-message notifications"
 * and {@code NotifyFlowTest}.
 * <p>
 * The database is not used: both repositories (or their {@link JdbcTemplate}) and {@link WebPushKeyStore} are mocks;
 * {@link PushEndpointPolicy} is the real class with its default list of push services (no I/O). The browser key used
 * is the generator point of P-256 (a valid public key, no key generation); the clock is fixed at 20/11/2026 03:00 UTC.
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("NotifyService – Cài đặt thông báo tin nhắn và đăng ký Web Push")
class NotifyServiceTest {

    private static final long MEMBER_ID = 10L;
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");

    private static final String MSG_LOGIN = "Bạn chưa đăng nhập.";
    private static final String MSG18 = "Bạn không có quyền thực hiện thao tác này.";
    private static final String BAD_ENDPOINT = "Trình duyệt này gửi địa chỉ nhận thông báo không hợp lệ.";
    private static final String BAD_KEY = "Khóa nhận thông báo của trình duyệt không hợp lệ.";
    private static final String BAD_AUTH = "Mã xác thực nhận thông báo của trình duyệt không hợp lệ.";

    /** The generator point G of P-256, uncompressed: a valid browser public key. */
    private static final byte[] G = HexFormat.of().parseHex("04"
            + "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"
            + "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5");
    private static final String KEY = b64url(G);
    private static final String AUTH = b64url(bytes(16));
    private static final String FCM = "https://fcm.googleapis.com/fcm/send/dXk3abc:APA91bHxyz";

    private static final AppUserPrincipal MEMBER = principal(MEMBER_ID, "USER");

    private NotifyRepository repository;
    private PushSubscriptionRepository subscriptions;
    private WebPushKeyStore keys;
    private NotifyService service;

    @BeforeEach
    void setUp() {
        repository = mock(NotifyRepository.class);
        subscriptions = mock(PushSubscriptionRepository.class);
        keys = mock(WebPushKeyStore.class);
        service = new NotifyService(repository, subscriptions, keys,
                new PushEndpointPolicy(PushEndpointPolicy.DEFAULT_HOSTS), Clock.fixed(NOW, ZoneOffset.UTC));
        account(MEMBER_ID, "USER", "ACTIVE");
        when(repository.settings(MEMBER_ID)).thenReturn(new Settings(true, true));
        when(subscriptions.idsNewestFirst(MEMBER_ID)).thenReturn(List.of(1L));
    }

    private static AppUserPrincipal principal(long id, String role) {
        return new AppUserPrincipal(id, "user" + id + "@example.org", "User " + id, role, "/");
    }

    private void account(long id, String role, String status) {
        when(repository.account(id)).thenReturn(Optional.of(new Account(id, "user" + id + "@example.org",
                "User " + id, role, status)));
    }

    private static byte[] bytes(int n) {
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) (i * 7 + 1);
        }
        return out;
    }

    private static String b64url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static SubscriptionRequest request(String endpoint, String p256dh, String auth) {
        return new SubscriptionRequest(endpoint, new Keys(p256dh, auth));
    }

    private static void assertApiError(Runnable call, HttpStatus status, String message) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ApiException.class)
                .hasMessage(message)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(status);
    }

    private void assertNothingSubscribed() {
        verify(repository, never()).lockSettings(anyLong());
        verify(subscriptions, never()).save(anyLong(), any(), any(), any(), any());
        verify(subscriptions, never()).delete(anyLong());
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. settings – Xem \"Cài đặt thông báo\" (UTC-083 → UTC-088)")
    class ReadSettings {

        @Test
        @DisplayName("UTC-083 Thành viên chưa chọn gì: e-mail và trình duyệt đều bật")
        void utc083_settings_memberWithoutChoiceHasBothOn() {
            assertThat(service.settings(MEMBER)).isEqualTo(new Settings(true, true));
            verify(repository).settings(MEMBER_ID);
        }

        @Test
        @DisplayName("UTC-084 Thành viên đã tắt e-mail: trả đúng lựa chọn đã lưu")
        void utc084_settings_memberSeesSavedChoice() {
            when(repository.settings(MEMBER_ID)).thenReturn(new Settings(false, true));

            assertThat(service.settings(MEMBER)).isEqualTo(new Settings(false, true));
        }

        @Test
        @DisplayName("UTC-085 Khách (chưa đăng nhập): 401")
        void utc085_settings_guestIs401() {
            assertApiError(() -> service.settings(null), HttpStatus.UNAUTHORIZED, MSG_LOGIN);
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("UTC-086 Phiên còn nhưng không còn tài khoản: 401")
        void utc086_settings_missingAccountIs401() {
            when(repository.account(MEMBER_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.settings(MEMBER), HttpStatus.UNAUTHORIZED, MSG_LOGIN);
            verify(repository, never()).settings(anyLong());
        }

        @ParameterizedTest(name = "[{index}] vai trò {0}")
        @ValueSource(strings = {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"})
        @DisplayName("UTC-087 Nhân viên: 403 MSG18")
        void utc087_settings_staffIs403(String role) {
            account(20L, role, "ACTIVE");

            assertApiError(() -> service.settings(principal(20L, role)), HttpStatus.FORBIDDEN, MSG18);
            verify(repository, never()).settings(anyLong());
        }

        @ParameterizedTest(name = "[{index}] trạng thái {0}")
        @ValueSource(strings = {"LOCKED", "DELETED"})
        @DisplayName("UTC-088 Tài khoản bị khóa / đã xóa: 403 MSG18")
        void utc088_settings_lockedOrDeletedIs403(String status) {
            account(MEMBER_ID, "USER", status);

            assertApiError(() -> service.settings(MEMBER), HttpStatus.FORBIDDEN, MSG18);
            verify(repository, never()).settings(anyLong());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. updateSettings – Lưu \"Thông báo qua e-mail\" / \"Thông báo trên trình duyệt\" (UTC-089 → UTC-098)")
    class UpdateSettings {

        @Test
        @DisplayName("UTC-089 Tắt e-mail: chỉ đổi e-mail, giữ trình duyệt; trả cài đặt mới")
        void utc089_updateSettings_turnsEmailOff() {
            when(repository.settings(MEMBER_ID)).thenReturn(new Settings(false, true));

            Settings saved = service.updateSettings(MEMBER, new SettingsUpdate(false, null));

            verify(repository).saveSettings(MEMBER_ID, false, null, NOW);
            assertThat(saved).isEqualTo(new Settings(false, true));
        }

        @Test
        @DisplayName("UTC-090 Tắt trình duyệt: chỉ đổi trình duyệt, giữ e-mail")
        void utc090_updateSettings_turnsPushOff() {
            when(repository.settings(MEMBER_ID)).thenReturn(new Settings(true, false));

            assertThat(service.updateSettings(MEMBER, new SettingsUpdate(null, false)))
                    .isEqualTo(new Settings(true, false));
            verify(repository).saveSettings(MEMBER_ID, null, false, NOW);
        }

        @Test
        @DisplayName("UTC-091 Đổi cả hai cùng lúc")
        void utc091_updateSettings_changesBoth() {
            when(repository.settings(MEMBER_ID)).thenReturn(new Settings(false, false));

            assertThat(service.updateSettings(MEMBER, new SettingsUpdate(false, false)))
                    .isEqualTo(new Settings(false, false));
            verify(repository).saveSettings(MEMBER_ID, false, false, NOW);
        }

        @Test
        @DisplayName("UTC-092 Bật lại cả hai")
        void utc092_updateSettings_turnsBothBackOn() {
            service.updateSettings(MEMBER, new SettingsUpdate(true, true));

            verify(repository).saveSettings(MEMBER_ID, true, true, NOW);
        }

        @Test
        @DisplayName("UTC-093 Không gửi trường nào: không ghi, trả cài đặt hiện tại")
        void utc093_updateSettings_noFieldSavesNothing() {
            when(repository.settings(MEMBER_ID)).thenReturn(new Settings(false, true));

            assertThat(service.updateSettings(MEMBER, new SettingsUpdate(null, null)))
                    .isEqualTo(new Settings(false, true));
            verify(repository, never()).saveSettings(anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("UTC-094 Body null: không ghi, trả cài đặt hiện tại")
        void utc094_updateSettings_nullBodySavesNothing() {
            assertThat(service.updateSettings(MEMBER, null)).isEqualTo(new Settings(true, true));
            verify(repository, never()).saveSettings(anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("UTC-095 Ghi trước rồi đọc lại cài đặt để trả về")
        void utc095_updateSettings_savesThenReadsBack() {
            service.updateSettings(MEMBER, new SettingsUpdate(false, null));

            InOrder order = inOrder(repository);
            order.verify(repository).saveSettings(MEMBER_ID, false, null, NOW);
            order.verify(repository).settings(MEMBER_ID);
        }

        @Test
        @DisplayName("UTC-096 Khách: 401, không ghi")
        void utc096_updateSettings_guestIs401() {
            assertApiError(() -> service.updateSettings(null, new SettingsUpdate(false, false)),
                    HttpStatus.UNAUTHORIZED, MSG_LOGIN);
            verify(repository, never()).saveSettings(anyLong(), any(), any(), any());
        }

        @ParameterizedTest(name = "[{index}] vai trò {0}")
        @ValueSource(strings = {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"})
        @DisplayName("UTC-097 Nhân viên: 403 MSG18, không ghi")
        void utc097_updateSettings_staffIs403(String role) {
            account(20L, role, "ACTIVE");

            assertApiError(() -> service.updateSettings(principal(20L, role), new SettingsUpdate(false, false)),
                    HttpStatus.FORBIDDEN, MSG18);
            verify(repository, never()).saveSettings(anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("UTC-098 Tài khoản bị khóa: 403 MSG18, không ghi")
        void utc098_updateSettings_lockedIs403() {
            account(MEMBER_ID, "USER", "LOCKED");

            assertApiError(() -> service.updateSettings(MEMBER, new SettingsUpdate(false, false)),
                    HttpStatus.FORBIDDEN, MSG18);
            verify(repository, never()).saveSettings(anyLong(), any(), any(), any());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("03. pushKey – Khóa công khai VAPID cho trình duyệt (UTC-099 → UTC-102)")
    class PushKeyOfServer {

        @Test
        @DisplayName("UTC-099 Thành viên: nhận khóa công khai dạng base64url, không có dấu \"=\"")
        void utc099_pushKey_memberGetsBase64UrlKey() {
            when(keys.keys()).thenReturn(new VapidKeys(null, null, G));

            PushKey key = service.pushKey(MEMBER);

            assertThat(key.publicKey()).isEqualTo(KEY).doesNotContain("=", "+", "/");
            assertThat(Base64.getUrlDecoder().decode(key.publicKey())).hasSize(65).startsWith(0x04);
        }

        @Test
        @DisplayName("UTC-100 Hỏi hai lần: cùng một khóa của máy chủ")
        void utc100_pushKey_sameKeyEachTime() {
            when(keys.keys()).thenReturn(new VapidKeys(null, null, G));

            assertThat(service.pushKey(MEMBER)).isEqualTo(service.pushKey(MEMBER));
        }

        @Test
        @DisplayName("UTC-101 Khách: 401, không tạo / đọc khóa")
        void utc101_pushKey_guestIs401() {
            assertApiError(() -> service.pushKey(null), HttpStatus.UNAUTHORIZED, MSG_LOGIN);
            verifyNoInteractions(keys);
        }

        @ParameterizedTest(name = "[{index}] vai trò {0}")
        @ValueSource(strings = {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"})
        @DisplayName("UTC-102 Nhân viên: 403 MSG18, không tạo / đọc khóa")
        void utc102_pushKey_staffIs403(String role) {
            account(20L, role, "ACTIVE");

            assertApiError(() -> service.pushKey(principal(20L, role)), HttpStatus.FORBIDDEN, MSG18);
            verifyNoInteractions(keys);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("04. subscribe – Bật thông báo trên trình duyệt này (UTC-103 → UTC-126)")
    class Subscribe {

        @Test
        @DisplayName("UTC-103 Đăng ký hợp lệ (Chrome / FCM): khóa dòng cài đặt rồi lưu trình duyệt")
        void utc103_subscribe_savesValidFcmSubscription() {
            service.subscribe(MEMBER, request(FCM, KEY, AUTH));

            verify(repository).lockSettings(MEMBER_ID);
            verify(subscriptions).save(MEMBER_ID, FCM, KEY, AUTH, NOW);
            verify(subscriptions, never()).delete(anyLong());
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {"https://updates.push.services.mozilla.com/wpush/v2/gAAAAABk",
                "https://wns2-par02p.notify.windows.com/w/?token=BQYAAAB",
                "https://web.push.apple.com/QGuQyavXutnMQZ"})
        @DisplayName("UTC-104 Firefox, Windows, Safari: được lưu")
        void utc104_subscribe_acceptsOtherKnownServices(String endpoint) {
            service.subscribe(MEMBER, request(endpoint, KEY, AUTH));

            verify(subscriptions).save(MEMBER_ID, endpoint, KEY, AUTH, NOW);
        }

        @Test
        @DisplayName("UTC-105 Địa chỉ có khoảng trắng hai đầu: bỏ khoảng trắng rồi lưu")
        void utc105_subscribe_stripsEndpoint() {
            service.subscribe(MEMBER, request("  " + FCM + "\n", KEY, AUTH));

            verify(subscriptions).save(MEMBER_ID, FCM, KEY, AUTH, NOW);
        }

        @Test
        @DisplayName("UTC-106 Khóa và auth dạng base64 chuẩn có \"=\": được lưu lại dạng base64url không \"=\"")
        void utc106_subscribe_normalisesStandardBase64() {
            String standardKey = Base64.getEncoder().encodeToString(G);
            String standardAuth = Base64.getEncoder().encodeToString(bytes(16));
            assertThat(standardAuth).endsWith("==");

            service.subscribe(MEMBER, request(FCM, standardKey, standardAuth));

            verify(subscriptions).save(MEMBER_ID, FCM, KEY, AUTH, NOW);
        }

        @Test
        @DisplayName("UTC-107 Thứ tự: khóa dòng cài đặt → lưu → đếm trình duyệt")
        void utc107_subscribe_locksSavesThenCounts() {
            service.subscribe(MEMBER, request(FCM, KEY, AUTH));

            InOrder order = inOrder(repository, subscriptions);
            order.verify(repository).lockSettings(MEMBER_ID);
            order.verify(subscriptions).save(MEMBER_ID, FCM, KEY, AUTH, NOW);
            order.verify(subscriptions).idsNewestFirst(MEMBER_ID);
        }

        @Test
        @DisplayName("UTC-108 Đủ 10 trình duyệt: không xóa cái nào")
        void utc108_subscribe_tenBrowsersKeepsAll() {
            when(subscriptions.idsNewestFirst(MEMBER_ID)).thenReturn(LongStream.rangeClosed(1, 10).boxed().toList());

            service.subscribe(MEMBER, request(FCM, KEY, AUTH));

            verify(subscriptions, never()).delete(anyLong());
            assertThat(NotifyService.MAX_SUBSCRIPTIONS).isEqualTo(10);
        }

        @Test
        @DisplayName("UTC-109 Trình duyệt thứ 11: xóa trình duyệt cũ nhất")
        void utc109_subscribe_eleventhDropsTheOldest() {
            // newest first: 111 (the one just saved) … 101 (the oldest)
            when(subscriptions.idsNewestFirst(MEMBER_ID))
                    .thenReturn(LongStream.iterate(111, i -> i - 1).limit(11).boxed().toList());

            service.subscribe(MEMBER, request(FCM, KEY, AUTH));

            verify(subscriptions).delete(101L);
            verify(subscriptions, times(1)).delete(anyLong());
        }

        @Test
        @DisplayName("UTC-110 12 trình duyệt: xóa hai trình duyệt cũ nhất")
        void utc110_subscribe_twelveDropsTwoOldest() {
            when(subscriptions.idsNewestFirst(MEMBER_ID))
                    .thenReturn(LongStream.iterate(112, i -> i - 1).limit(12).boxed().toList());

            service.subscribe(MEMBER, request(FCM, KEY, AUTH));

            verify(subscriptions).delete(102L);
            verify(subscriptions).delete(101L);
            verify(subscriptions, times(2)).delete(anyLong());
        }

        @Test
        @DisplayName("UTC-111 Body null: 400 địa chỉ không hợp lệ")
        void utc111_subscribe_nullBodyIsBadEndpoint() {
            assertApiError(() -> service.subscribe(MEMBER, null), HttpStatus.BAD_REQUEST, BAD_ENDPOINT);
            assertNothingSubscribed();
        }

        @ParameterizedTest(name = "[{index}] endpoint = \"{0}\"")
        @ValueSource(strings = {"", "   "})
        @DisplayName("UTC-112 Địa chỉ rỗng / chỉ khoảng trắng: 400")
        void utc112_subscribe_blankEndpointIsBad(String endpoint) {
            assertApiError(() -> service.subscribe(MEMBER, request(endpoint, KEY, AUTH)), HttpStatus.BAD_REQUEST,
                    BAD_ENDPOINT);
            assertNothingSubscribed();
        }

        @Test
        @DisplayName("UTC-113 Không có địa chỉ (chỉ có keys): 400")
        void utc113_subscribe_missingEndpointIsBad() {
            assertApiError(() -> service.subscribe(MEMBER, request(null, KEY, AUTH)), HttpStatus.BAD_REQUEST,
                    BAD_ENDPOINT);
            assertNothingSubscribed();
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {"http://fcm.googleapis.com/fcm/send/x", "https://evil.example.com/push",
                "https://169.254.169.254/latest/meta-data", "https://127.0.0.1:8090/api/admin/users",
                "https://fcm.googleapis.com:8443/fcm/send/x", "javascript:alert(1)"})
        @DisplayName("UTC-114 Địa chỉ không phải dịch vụ push đã biết (SSRF): 400, không lưu")
        void utc114_subscribe_unknownServiceIsRefused(String endpoint) {
            assertApiError(() -> service.subscribe(MEMBER, request(endpoint, KEY, AUTH)), HttpStatus.BAD_REQUEST,
                    BAD_ENDPOINT);
            assertNothingSubscribed();
        }

        @Test
        @DisplayName("UTC-115 Địa chỉ dài 1001 ký tự: 400")
        void utc115_subscribe_endpointOver1000CharsIsRefused() {
            String endpoint = "https://fcm.googleapis.com/fcm/send/" + "x".repeat(1001 - 36);
            assertThat(endpoint).hasSize(1001);

            assertApiError(() -> service.subscribe(MEMBER, request(endpoint, KEY, AUTH)), HttpStatus.BAD_REQUEST,
                    BAD_ENDPOINT);
            assertNothingSubscribed();
        }

        @Test
        @DisplayName("UTC-116 Địa chỉ dài đúng 1000 ký tự: được lưu")
        void utc116_subscribe_endpointOf1000CharsIsSaved() {
            String endpoint = "https://fcm.googleapis.com/fcm/send/" + "x".repeat(1000 - 36);
            assertThat(endpoint).hasSize(1000);

            service.subscribe(MEMBER, request(endpoint, KEY, AUTH));

            verify(subscriptions).save(MEMBER_ID, endpoint, KEY, AUTH, NOW);
        }

        @Test
        @DisplayName("UTC-117 Không có keys: 400 khóa không hợp lệ")
        void utc117_subscribe_missingKeysIsBadKey() {
            assertApiError(() -> service.subscribe(MEMBER, new SubscriptionRequest(FCM, null)),
                    HttpStatus.BAD_REQUEST, BAD_KEY);
            assertNothingSubscribed();
        }

        @Test
        @DisplayName("UTC-118 Không có p256dh: 400 khóa không hợp lệ")
        void utc118_subscribe_missingP256dhIsBadKey() {
            assertApiError(() -> service.subscribe(MEMBER, request(FCM, null, AUTH)), HttpStatus.BAD_REQUEST,
                    BAD_KEY);
            assertNothingSubscribed();
        }

        @Test
        @DisplayName("UTC-119 p256dh không phải điểm của P-256 / sai độ dài / không phải base64: 400")
        void utc119_subscribe_invalidP256dhIsBadKey() {
            byte[] offCurve = G.clone();
            offCurve[64] ^= 1;
            byte[] compressed = new byte[33];
            compressed[0] = 0x02;
            for (String badKey : List.of(b64url(new byte[64]), b64url(new byte[65]), b64url(offCurve),
                    b64url(compressed), "không-phải-base64", "")) {
                assertApiError(() -> service.subscribe(MEMBER, request(FCM, badKey, AUTH)), HttpStatus.BAD_REQUEST,
                        BAD_KEY);
            }
            assertNothingSubscribed();
        }

        @Test
        @DisplayName("UTC-120 Không có auth: 400 mã xác thực không hợp lệ")
        void utc120_subscribe_missingAuthIsBadAuth() {
            assertApiError(() -> service.subscribe(MEMBER, request(FCM, KEY, null)), HttpStatus.BAD_REQUEST,
                    BAD_AUTH);
            assertNothingSubscribed();
        }

        @ParameterizedTest(name = "[{index}] auth {0} byte")
        @ValueSource(ints = {0, 15, 17, 32})
        @DisplayName("UTC-121 auth không đúng 16 byte: 400")
        void utc121_subscribe_authNotSixteenBytesIsBad(int length) {
            assertApiError(() -> service.subscribe(MEMBER, request(FCM, KEY, b64url(bytes(length)))),
                    HttpStatus.BAD_REQUEST, BAD_AUTH);
            assertNothingSubscribed();
        }

        @Test
        @DisplayName("UTC-122 auth không phải base64: 400")
        void utc122_subscribe_authNotBase64IsBad() {
            assertApiError(() -> service.subscribe(MEMBER, request(FCM, KEY, "@@@")), HttpStatus.BAD_REQUEST,
                    BAD_AUTH);
            assertNothingSubscribed();
        }

        @Test
        @DisplayName("UTC-123 Thứ tự kiểm tra: địa chỉ trước khóa, khóa trước auth")
        void utc123_subscribe_checksEndpointThenKeyThenAuth() {
            assertApiError(() -> service.subscribe(MEMBER, request("https://evil.example.com/x", "xx", "@@@")),
                    HttpStatus.BAD_REQUEST, BAD_ENDPOINT);
            assertApiError(() -> service.subscribe(MEMBER, request(FCM, "xx", "@@@")), HttpStatus.BAD_REQUEST,
                    BAD_KEY);
        }

        @Test
        @DisplayName("UTC-124 Khách: 401, không lưu")
        void utc124_subscribe_guestIs401() {
            assertApiError(() -> service.subscribe(null, request(FCM, KEY, AUTH)), HttpStatus.UNAUTHORIZED,
                    MSG_LOGIN);
            assertNothingSubscribed();
        }

        @ParameterizedTest(name = "[{index}] vai trò {0}")
        @ValueSource(strings = {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"})
        @DisplayName("UTC-125 Nhân viên: 403 MSG18, không lưu")
        void utc125_subscribe_staffIs403(String role) {
            account(20L, role, "ACTIVE");

            assertApiError(() -> service.subscribe(principal(20L, role), request(FCM, KEY, AUTH)),
                    HttpStatus.FORBIDDEN, MSG18);
            assertNothingSubscribed();
        }

        @Test
        @DisplayName("UTC-126 Tài khoản bị khóa: 403 MSG18, không lưu")
        void utc126_subscribe_lockedIs403() {
            account(MEMBER_ID, "USER", "LOCKED");

            assertApiError(() -> service.subscribe(MEMBER, request(FCM, KEY, AUTH)), HttpStatus.FORBIDDEN, MSG18);
            assertNothingSubscribed();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("05. unsubscribe – Tắt thông báo trên trình duyệt này (UTC-127 → UTC-135)")
    class Unsubscribe {

        @Test
        @DisplayName("UTC-127 Xóa đăng ký của chính thành viên cho địa chỉ này")
        void utc127_unsubscribe_removesOwnSubscription() {
            when(subscriptions.deleteOwn(MEMBER_ID, FCM)).thenReturn(true);

            service.unsubscribe(MEMBER, new EndpointRequest(FCM));

            verify(subscriptions).deleteOwn(MEMBER_ID, FCM);
        }

        @Test
        @DisplayName("UTC-128 Địa chỉ có khoảng trắng hai đầu: bỏ khoảng trắng")
        void utc128_unsubscribe_stripsEndpoint() {
            service.unsubscribe(MEMBER, new EndpointRequest("  " + FCM + " "));

            verify(subscriptions).deleteOwn(MEMBER_ID, FCM);
        }

        @Test
        @DisplayName("UTC-129 Địa chỉ thành viên không có: không lỗi, không đổi gì")
        void utc129_unsubscribe_unknownEndpointIsFine() {
            when(subscriptions.deleteOwn(MEMBER_ID, FCM)).thenReturn(false);

            service.unsubscribe(MEMBER, new EndpointRequest(FCM));

            verify(subscriptions).deleteOwn(MEMBER_ID, FCM);
        }

        @Test
        @DisplayName("UTC-130 Địa chỉ không phải dịch vụ push: vẫn chỉ xóa của mình (không gọi ra ngoài)")
        void utc130_unsubscribe_anyEndpointOnlyDeletesOwn() {
            service.unsubscribe(MEMBER, new EndpointRequest("https://evil.example.com/x"));

            verify(subscriptions).deleteOwn(MEMBER_ID, "https://evil.example.com/x");
            verify(subscriptions, never()).delete(anyLong());
        }

        @Test
        @DisplayName("UTC-131 Body null / không có địa chỉ / địa chỉ trắng: 400, không xóa")
        void utc131_unsubscribe_missingEndpointIsBad() {
            assertApiError(() -> service.unsubscribe(MEMBER, null), HttpStatus.BAD_REQUEST, BAD_ENDPOINT);
            assertApiError(() -> service.unsubscribe(MEMBER, new EndpointRequest(null)), HttpStatus.BAD_REQUEST,
                    BAD_ENDPOINT);
            assertApiError(() -> service.unsubscribe(MEMBER, new EndpointRequest("   ")), HttpStatus.BAD_REQUEST,
                    BAD_ENDPOINT);
            verify(subscriptions, never()).deleteOwn(anyLong(), anyString());
        }

        @Test
        @DisplayName("UTC-132 Địa chỉ dài đúng 1000 ký tự: được xử lý")
        void utc132_unsubscribe_endpointOf1000CharsIsAccepted() {
            String endpoint = "https://fcm.googleapis.com/fcm/send/" + "y".repeat(1000 - 36);

            service.unsubscribe(MEMBER, new EndpointRequest(endpoint));

            verify(subscriptions).deleteOwn(MEMBER_ID, endpoint);
        }

        @Test
        @DisplayName("UTC-133 Địa chỉ dài 1001 ký tự: 400, không xóa")
        void utc133_unsubscribe_endpointOver1000CharsIsBad() {
            String endpoint = "https://fcm.googleapis.com/fcm/send/" + "y".repeat(1001 - 36);

            assertApiError(() -> service.unsubscribe(MEMBER, new EndpointRequest(endpoint)), HttpStatus.BAD_REQUEST,
                    BAD_ENDPOINT);
            verify(subscriptions, never()).deleteOwn(anyLong(), anyString());
        }

        @Test
        @DisplayName("UTC-134 Khách: 401, không xóa")
        void utc134_unsubscribe_guestIs401() {
            assertApiError(() -> service.unsubscribe(null, new EndpointRequest(FCM)), HttpStatus.UNAUTHORIZED,
                    MSG_LOGIN);
            verifyNoInteractions(subscriptions);
        }

        @ParameterizedTest(name = "[{index}] vai trò {0}")
        @ValueSource(strings = {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"})
        @DisplayName("UTC-135 Nhân viên: 403 MSG18, không xóa")
        void utc135_unsubscribe_staffIs403(String role) {
            account(20L, role, "ACTIVE");

            assertApiError(() -> service.unsubscribe(principal(20L, role), new EndpointRequest(FCM)),
                    HttpStatus.FORBIDDEN, MSG18);
            verifyNoInteractions(subscriptions);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("06. NotifyRepository – cài đặt mặc định, tài khoản Thành viên (UTC-136 → UTC-141)")
    class NotifyRepositoryRules {

        private JdbcTemplate jdbc;
        private NotifyRepository real;

        @BeforeEach
        void realRepository() {
            jdbc = mock(JdbcTemplate.class);
            real = new NotifyRepository(jdbc);
        }

        @Test
        @DisplayName("UTC-136 Chưa có dòng chat_notify_settings: cả hai đều bật")
        void utc136_repoSettings_noRowMeansBothOn() {
            assertThat(real.settings(MEMBER_ID)).isEqualTo(new Settings(true, true));
            verify(jdbc).query(contains("FROM chat_notify_settings WHERE user_id = ?"), any(RowMapper.class),
                    eq(MEMBER_ID));
        }

        @Test
        @DisplayName("UTC-137 Có dòng: đọc đúng hai cột email_enabled, push_enabled")
        void utc137_repoSettings_rowIsMapped() throws Exception {
            real.settings(MEMBER_ID);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<RowMapper<Settings>> mapper = ArgumentCaptor.forClass(RowMapper.class);
            verify(jdbc).query(anyString(), mapper.capture(), eq(MEMBER_ID));
            ResultSet rs = mock(ResultSet.class);
            when(rs.getBoolean(1)).thenReturn(false);
            when(rs.getBoolean(2)).thenReturn(true);

            assertThat(mapper.getValue().mapRow(rs, 0)).isEqualTo(new Settings(false, true));
        }

        @Test
        @DisplayName("UTC-138 Chỉ USER đang ACTIVE là Thành viên nhắn tin được")
        void utc138_repoAccount_onlyActiveUserIsMember() {
            assertThat(new Account(1, "a@x", "A", "USER", "ACTIVE").member()).isTrue();
            assertThat(new Account(1, "a@x", "A", "USER", "LOCKED").member()).isFalse();
            assertThat(new Account(1, "a@x", "A", "USER", "DELETED").member()).isFalse();
            assertThat(new Account(1, "a@x", "A", "MODERATOR", "ACTIVE").member()).isFalse();
            assertThat(new Account(1, "a@x", "A", "ADMIN", "ACTIVE").member()).isFalse();
            assertThat(new Account(1, "a@x", "A", null, null).member()).isFalse();
        }

        @Test
        @DisplayName("UTC-139 Không có tài khoản: Optional rỗng")
        void utc139_repoAccount_missingIsEmpty() {
            assertThat(real.account(999L)).isEmpty();
        }

        @Test
        @DisplayName("UTC-140 Lưu cài đặt: trường null giữ giá trị cũ (mặc định bật)")
        void utc140_repoSaveSettings_nullKeepsCurrentValue() {
            real.saveSettings(MEMBER_ID, false, null, NOW);

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(jdbc).update(sql.capture(), eq(MEMBER_ID), eq(false), isNull(), eq(Timestamp.from(NOW)),
                    eq(false), isNull());
            assertThat(sql.getValue()).contains("VALUES (?, COALESCE(?, TRUE), COALESCE(?, TRUE), ?)")
                    .contains("email_enabled = COALESCE(?, email_enabled)")
                    .contains("push_enabled = COALESCE(?, push_enabled)");
        }

        @Test
        @DisplayName("UTC-141 Khóa dòng cài đặt: tạo dòng mặc định nếu chưa có")
        void utc141_repoLockSettings_createsDefaultRow() {
            real.lockSettings(MEMBER_ID);

            verify(jdbc).update(contains("INSERT INTO chat_notify_settings (user_id) VALUES (?)"), eq(MEMBER_ID));
            verify(jdbc).update(contains("ON DUPLICATE KEY UPDATE user_id = user_id"), eq(MEMBER_ID));
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("07. PushSubscriptionRepository – mỗi trình duyệt một dòng (UTC-142 → UTC-145)")
    class SubscriptionStore {

        private JdbcTemplate jdbc;
        private PushSubscriptionRepository real;

        @BeforeEach
        void realRepository() {
            jdbc = mock(JdbcTemplate.class);
            real = new PushSubscriptionRepository(jdbc);
        }

        @Test
        @DisplayName("UTC-142 Mã băm địa chỉ: SHA-256 dạng hex 64 ký tự")
        void utc142_repoHash_isSha256Hex() {
            assertThat(PushSubscriptionRepository.hash("abc"))
                    .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
            assertThat(PushSubscriptionRepository.hash(FCM)).hasSize(64).matches("[0-9a-f]{64}")
                    .isEqualTo(PushSubscriptionRepository.hash(FCM))
                    .isNotEqualTo(PushSubscriptionRepository.hash(FCM + "x"));
        }

        @Test
        @DisplayName("UTC-143 Lưu: một dòng mỗi địa chỉ; lưu lại thì chuyển sang tài khoản mới, đếm lỗi về 0")
        void utc143_repoSave_upsertsByEndpointHash() {
            real.save(MEMBER_ID, FCM, KEY, AUTH, NOW);

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(jdbc).update(sql.capture(), eq(MEMBER_ID), eq(FCM), eq(PushSubscriptionRepository.hash(FCM)),
                    eq(KEY), eq(AUTH), eq(Timestamp.from(NOW)));
            assertThat(sql.getValue()).contains("ON DUPLICATE KEY UPDATE user_id = VALUES(user_id)")
                    .contains("failure_count = 0");
        }

        @Test
        @DisplayName("UTC-144 Xóa của mình: theo mã băm và user_id; true khi có xóa")
        void utc144_repoDeleteOwn_onlyOwnRow() {
            String hash = PushSubscriptionRepository.hash(FCM);
            when(jdbc.update(contains("WHERE endpoint_hash = ? AND user_id = ?"), eq(hash), eq(MEMBER_ID)))
                    .thenReturn(1, 0);

            assertThat(real.deleteOwn(MEMBER_ID, FCM)).isTrue();
            assertThat(real.deleteOwn(MEMBER_ID, FCM)).isFalse();
        }

        @Test
        @DisplayName("UTC-145 Danh sách id: mới nhất trước")
        void utc145_repoIds_newestFirst() {
            when(jdbc.queryForList(contains("ORDER BY created_at DESC, id DESC"), eq(Long.class), eq(MEMBER_ID)))
                    .thenReturn(List.of(3L, 2L, 1L));

            assertThat(real.idsNewestFirst(MEMBER_ID)).containsExactly(3L, 2L, 1L);
        }
    }
}
