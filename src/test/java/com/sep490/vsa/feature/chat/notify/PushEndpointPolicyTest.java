package com.sep490.vsa.feature.chat.notify;

import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests of {@link PushEndpointPolicy}: which push endpoints the server may POST to (protection against SSRF:
 * only https on the default port of the known push services, {@code app.push.allowed-hosts}). Pure logic, nothing
 * mocked; no request is ever sent.
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("PushEndpointPolicy – Chỉ gọi dịch vụ push đã biết (chống SSRF)")
class PushEndpointPolicyTest {

    private static final PushEndpointPolicy DEFAULTS = new PushEndpointPolicy(PushEndpointPolicy.DEFAULT_HOSTS);

    private static boolean allowed(PushEndpointPolicy policy, String endpoint) {
        return policy.check(endpoint).isPresent();
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. check – Danh sách mặc định: địa chỉ được phép (UTC-146 → UTC-150)")
    class AllowedByDefault {

        @Test
        @DisplayName("UTC-146 Chrome / Edge (FCM): được phép, trả đúng URI")
        void utc146_policy_fcmIsAllowedAsUri() {
            String endpoint = "https://fcm.googleapis.com/fcm/send/dXk3abc:APA91bHxyz";

            assertThat(DEFAULTS.check(endpoint)).contains(URI.create(endpoint));
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {"https://updates.push.services.mozilla.com/wpush/v2/gAAAAABk",
                "https://wns2-par02p.notify.windows.com/w/?token=BQYAAAB",
                "https://web.push.apple.com/QGuQyavXutnMQZ",
                "https://api.push.apple.com/3/device/abc"})
        @DisplayName("UTC-147 Firefox, Windows, Safari (tên miền con của dịch vụ đã biết): được phép")
        void utc147_policy_otherKnownServicesAllowed(String endpoint) {
            assertThat(allowed(DEFAULTS, endpoint)).isTrue();
        }

        @Test
        @DisplayName("UTC-148 Tên máy chủ viết hoa: vẫn được phép")
        void utc148_policy_hostIsCaseInsensitive() {
            assertThat(allowed(DEFAULTS, "HTTPS://FCM.GoogleApis.COM/fcm/send/x")).isTrue();
        }

        @Test
        @DisplayName("UTC-149 Cổng 443 ghi rõ: được phép")
        void utc149_policy_explicitPort443Allowed() {
            assertThat(allowed(DEFAULTS, "https://fcm.googleapis.com:443/fcm/send/x")).isTrue();
        }

        @Test
        @DisplayName("UTC-150 Địa chỉ dài đúng 1000 ký tự: được phép")
        void utc150_policy_exactly1000CharsAllowed() {
            String endpoint = "https://fcm.googleapis.com/fcm/send/" + "x".repeat(1000 - 36);

            assertThat(endpoint).hasSize(PushEndpointPolicy.MAX_LENGTH);
            assertThat(allowed(DEFAULTS, endpoint)).isTrue();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. check – Danh sách mặc định: địa chỉ bị từ chối (UTC-151 → UTC-160)")
    class RefusedByDefault {

        @Test
        @DisplayName("UTC-151 null, rỗng, chỉ khoảng trắng: từ chối")
        void utc151_policy_missingEndpointRefused() {
            assertThat(DEFAULTS.check(null)).isEmpty();
            assertThat(DEFAULTS.check("")).isEmpty();
            assertThat(DEFAULTS.check("   ")).isEmpty();
        }

        @Test
        @DisplayName("UTC-152 Dài 1001 ký tự: từ chối")
        void utc152_policy_over1000CharsRefused() {
            String endpoint = "https://fcm.googleapis.com/fcm/send/" + "x".repeat(1001 - 36);

            assertThat(allowed(DEFAULTS, endpoint)).isFalse();
        }

        @Test
        @DisplayName("UTC-153 http (không mã hóa) tới dịch vụ đã biết: từ chối")
        void utc153_policy_plainHttpRefused() {
            assertThat(allowed(DEFAULTS, "http://fcm.googleapis.com/fcm/send/x")).isFalse();
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {"https://fcm.googleapis.com:8443/fcm/send/x", "https://fcm.googleapis.com:80/x",
                "https://fcm.googleapis.com:1/x"})
        @DisplayName("UTC-154 Cổng khác 443: từ chối")
        void utc154_policy_otherPortRefused(String endpoint) {
            assertThat(allowed(DEFAULTS, endpoint)).isFalse();
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {"https://evil.example.com/push", "https://169.254.169.254/latest/meta-data",
                "https://127.0.0.1:8090/api/admin/users", "https://localhost/x", "https://[::1]/x",
                "https://10.0.0.5/push"})
        @DisplayName("UTC-155 Máy chủ lạ, địa chỉ nội bộ (SSRF): từ chối")
        void utc155_policy_unknownAndInternalHostsRefused(String endpoint) {
            assertThat(allowed(DEFAULTS, endpoint)).isFalse();
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {"https://fcm.googleapis.com.evil.com/x", "https://evilfcm.googleapis.com/x",
                "https://push.services.mozilla.com/x", "https://evilpush.services.mozilla.com/x",
                "https://fcm.googleapis.com./x"})
        @DisplayName("UTC-156 Tên giả dạng dịch vụ đã biết: từ chối")
        void utc156_policy_lookalikeHostsRefused(String endpoint) {
            assertThat(allowed(DEFAULTS, endpoint)).isFalse();
        }

        @Test
        @DisplayName("UTC-157 Có user:password trước tên máy chủ: từ chối")
        void utc157_policy_userInfoRefused() {
            assertThat(allowed(DEFAULTS, "https://user:pw@fcm.googleapis.com/fcm/send/x")).isFalse();
            assertThat(allowed(DEFAULTS, "https://evil.com@fcm.googleapis.com/fcm/send/x")).isFalse();
        }

        @Test
        @DisplayName("UTC-158 Có phần #fragment: từ chối")
        void utc158_policy_fragmentRefused() {
            assertThat(allowed(DEFAULTS, "https://fcm.googleapis.com/fcm/send/x#frag")).isFalse();
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {"javascript:alert(1)", "mailto:a@fcm.googleapis.com", "/fcm/send/x",
                "fcm.googleapis.com/fcm/send/x", "https:///fcm/send/x", "ftp://fcm.googleapis.com/x",
                "wss://fcm.googleapis.com/x", "file:///etc/passwd"})
        @DisplayName("UTC-159 Không phải URL https tuyệt đối có tên máy chủ: từ chối")
        void utc159_policy_notAbsoluteHttpsUrlRefused(String endpoint) {
            assertThat(allowed(DEFAULTS, endpoint)).isFalse();
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {"https://fcm.googleapis.com/a b", "https://fcm.googleapis.com/x\\y",
                "https://fcm.googleapis.com/%zz", "https://fcm.goo gleapis.com/x"})
        @DisplayName("UTC-160 URL sai cú pháp: từ chối, không lỗi")
        void utc160_policy_malformedUrlRefused(String endpoint) {
            assertThat(DEFAULTS.check(endpoint)).isEmpty();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("03. PushEndpointPolicy(allowedHosts) – Cấu hình app.push.allowed-hosts (UTC-161 → UTC-165)")
    class Configured {

        @Test
        @DisplayName("UTC-161 Danh sách riêng: chỉ các máy chủ đã khai báo, mặc định không còn")
        void utc161_policyConfig_customListReplacesDefaults() {
            PushEndpointPolicy policy = new PushEndpointPolicy("push.example.com,*.svc.example.org");

            assertThat(allowed(policy, "https://push.example.com/a")).isTrue();
            assertThat(allowed(policy, "https://eu.svc.example.org/a")).isTrue();
            assertThat(allowed(policy, "https://svc.example.org/a")).isFalse();
            assertThat(allowed(policy, "https://fcm.googleapis.com/fcm/send/x")).isFalse();
        }

        @Test
        @DisplayName("UTC-162 Khoảng trắng, chữ hoa, mục rỗng trong cấu hình: được bỏ qua")
        void utc162_policyConfig_entriesTrimmedLowercasedEmptyIgnored() {
            PushEndpointPolicy policy = new PushEndpointPolicy(" , PUSH.Example.com ,, ,*.SVC.example.org ");

            assertThat(allowed(policy, "https://push.example.com/a")).isTrue();
            assertThat(allowed(policy, "https://a.svc.example.org/a")).isTrue();
        }

        @Test
        @DisplayName("UTC-163 Cấu hình rỗng: không địa chỉ nào được phép")
        void utc163_policyConfig_emptyListAllowsNothing() {
            PushEndpointPolicy policy = new PushEndpointPolicy("");

            assertThat(allowed(policy, "https://fcm.googleapis.com/fcm/send/x")).isFalse();
        }

        @Test
        @DisplayName("UTC-164 Mục \"http://localhost\" (chỉ cho kiểm thử): http mọi cổng của đúng máy đó")
        void utc164_policyConfig_plainHttpEntryOnlyForThatHost() {
            PushEndpointPolicy policy = new PushEndpointPolicy("http://localhost");

            assertThat(allowed(policy, "http://localhost:8090/push/abc")).isTrue();
            assertThat(allowed(policy, "http://localhost/push/abc")).isTrue();
            assertThat(allowed(policy, "https://localhost/push/abc")).isFalse();
            assertThat(allowed(policy, "http://127.0.0.1:8090/push/abc")).isFalse();
            assertThat(allowed(policy, "http://localhost.evil.com/push")).isFalse();
        }

        @Test
        @DisplayName("UTC-165 Danh sách mặc định gồm đúng 5 dịch vụ (FCM, Mozilla, Windows, Apple)")
        void utc165_policyConfig_defaultListNamesTheFiveServices() {
            assertThat(PushEndpointPolicy.DEFAULT_HOSTS).isEqualTo(
                    "fcm.googleapis.com,*.push.services.mozilla.com,*.notify.windows.com,web.push.apple.com,"
                            + "*.push.apple.com");
            assertThat(PushEndpointPolicy.MAX_LENGTH).isEqualTo(1000);
        }
    }
}
