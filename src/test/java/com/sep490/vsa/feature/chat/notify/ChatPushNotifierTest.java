package com.sep490.vsa.feature.chat.notify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sep490.vsa.feature.chat.ChatMessageSent;
import com.sep490.vsa.feature.chat.ChatViewers;
import com.sep490.vsa.feature.chat.notify.NotifyDtos.Settings;
import com.sep490.vsa.feature.chat.notify.NotifyRepository.Account;
import com.sep490.vsa.feature.chat.notify.PushSubscriptionRepository.Subscription;
import org.junit.jupiter.api.AfterEach;
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
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the Web Push for a new chat message ({@link ChatPushNotifier}): sent after the commit to every
 * browser of an ACTIVE Member with push on, at most once per conversation every 30 s, never while the member's page is
 * reading that conversation, naming the writer only (never the text); plus {@link NotifyRepository#claimPush}, the
 * one-statement 30-second turn. From CLAUDE.md "Tin nhắn" → "New-message notifications" and {@code NotifyFlowTest}.
 * <p>
 * {@link NotifyRepository} (or its {@link JdbcTemplate}), {@link PushSubscriptionRepository}, {@link WebPushSender}
 * and {@link ChatViewers} are mocks, so no encryption and no request is made (the sender itself, including the removal
 * of expired subscriptions on 404/410, is covered by {@code WebPushSenderTest}); the JSON is written by a real
 * {@link ObjectMapper}; the clock is fixed at 20/11/2026 03:00 UTC.
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("ChatPushNotifier – Thông báo đẩy khi có tin nhắn mới")
class ChatPushNotifierTest {

    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private static final long CONVERSATION = 42L;
    private static final long MINH = 10L;
    private static final long LAN = 11L;

    private NotifyRepository repository;
    private PushSubscriptionRepository subscriptions;
    private WebPushSender sender;
    private ChatViewers viewers;
    private ObjectMapper json;
    private ChatPushNotifier notifier;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    private final Subscription phone = new Subscription(1, MINH, "https://fcm.googleapis.com/fcm/send/a", "k1", "a1");
    private final Subscription laptop = new Subscription(2, MINH, "https://web.push.apple.com/b", "k2", "a2");

    @BeforeEach
    void setUp() {
        repository = mock(NotifyRepository.class);
        subscriptions = mock(PushSubscriptionRepository.class);
        sender = mock(WebPushSender.class);
        viewers = mock(ChatViewers.class);
        json = new ObjectMapper();
        notifier = notifier(30);
        member(MINH, "USER", "ACTIVE");
        when(repository.settings(MINH)).thenReturn(new Settings(true, true));
        when(subscriptions.ofUser(MINH)).thenReturn(List.of(phone, laptop));
        when(repository.claimPush(eq(CONVERSATION), eq(MINH), any(), any())).thenReturn(true);
        when(sender.send(any(), any())).thenReturn(WebPushSender.Result.SENT);
        logger = (Logger) LoggerFactory.getLogger(ChatPushNotifier.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        logger.detachAppender(logs);
    }

    private ChatPushNotifier notifier(long spacingSeconds) {
        return new ChatPushNotifier(repository, subscriptions, sender, viewers, json, Clock.fixed(NOW, ZoneOffset.UTC),
                spacingSeconds);
    }

    private void member(long id, String role, String status) {
        when(repository.account(id)).thenReturn(Optional.of(new Account(id, "u" + id + "@example.org", "U" + id,
                role, status)));
    }

    private static ChatMessageSent message(String senderName, boolean encrypted, boolean request) {
        return new ChatMessageSent(CONVERSATION, 900L, LAN, senderName, MINH, encrypted, request);
    }

    private JsonNode sentPayload() throws Exception {
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(sender).send(eq(phone), payload.capture());
        return json.readTree(payload.getValue());
    }

    private void assertNothingPushed() {
        verify(repository, never()).claimPush(anyLong(), anyLong(), any(), any());
        verifyNoInteractions(sender);
    }

    private List<String> warnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. onMessageSent – Gửi Web Push cho tin nhắn mới (UTC-198 → UTC-218)")
    class OnMessageSent {

        @Test
        @DisplayName("UTC-198 Thành viên bật thông báo, có 2 trình duyệt: gửi tới cả hai cùng một nội dung")
        void utc198_push_sendsToEveryBrowser() {
            notifier.onMessageSent(message("Lan", false, false));

            ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
            verify(sender).send(eq(phone), payload.capture());
            verify(sender).send(eq(laptop), eq(payload.getValue()));
            verify(sender, times(2)).send(any(), any());
        }

        @Test
        @DisplayName("UTC-199 Nội dung đẩy: tiêu đề, \"Tin nhắn mới từ Lan\", liên kết, tag – chỉ 4 trường")
        void utc199_push_payloadNamesTheWriterOnly() throws Exception {
            notifier.onMessageSent(message("Lan", false, false));

            JsonNode body = sentPayload();
            assertThat(body.get("title").asText()).isEqualTo("Senpai ơi");
            assertThat(body.get("body").asText()).isEqualTo("Tin nhắn mới từ Lan");
            assertThat(body.get("url").asText()).isEqualTo("/messages/42");
            assertThat(body.get("tag").asText()).isEqualTo("chat-42");
            assertThat(body.size()).isEqualTo(4);
        }

        @Test
        @DisplayName("UTC-200 Lời mời nhắn tin: \"Lan muốn nhắn tin với bạn.\"")
        void utc200_push_requestHasItsOwnWording() throws Exception {
            notifier.onMessageSent(message("Lan", false, true));

            assertThat(sentPayload().get("body").asText()).isEqualTo("Lan muốn nhắn tin với bạn.");
        }

        @Test
        @DisplayName("UTC-201 Tin nhắn mã hóa hay không: nội dung đẩy như nhau (không bao giờ có chữ của tin nhắn)")
        void utc201_push_encryptedOrNotSamePayload() {
            notifier.onMessageSent(message("Lan", true, false));
            notifier.onMessageSent(message("Lan", false, false));

            ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
            verify(sender, times(2)).send(eq(phone), payload.capture());
            assertThat(payload.getAllValues().get(0)).isEqualTo(payload.getAllValues().get(1));
            assertThat(new String(payload.getValue(), StandardCharsets.UTF_8)).doesNotContain("900");
        }

        @Test
        @DisplayName("UTC-202 Lấy lượt đẩy: lần đẩy trước phải từ 30 giây trở lên")
        void utc202_push_claimsTurnWithThirtySecondSpacing() {
            notifier.onMessageSent(message("Lan", false, false));

            verify(repository).claimPush(CONVERSATION, MINH, NOW, NOW.minus(Duration.ofSeconds(30)));
        }

        @Test
        @DisplayName("UTC-203 Cấu hình 60 giây: lấy lượt với mốc 60 giây")
        void utc203_push_usesConfiguredSpacing() {
            notifier(60).onMessageSent(message("Lan", false, false));

            verify(repository).claimPush(CONVERSATION, MINH, NOW, NOW.minus(Duration.ofSeconds(60)));
        }

        @Test
        @DisplayName("UTC-204 Chưa đủ 30 giây từ lần đẩy trước (không lấy được lượt): không gửi")
        void utc204_push_withinSpacingSendsNothing() {
            when(repository.claimPush(eq(CONVERSATION), eq(MINH), any(), any())).thenReturn(false);

            notifier.onMessageSent(message("Lan", false, false));

            verifyNoInteractions(sender);
        }

        @Test
        @DisplayName("UTC-205 Người nhận không còn tài khoản: không làm gì")
        void utc205_push_missingRecipientNothing() {
            when(repository.account(MINH)).thenReturn(Optional.empty());

            notifier.onMessageSent(message("Lan", false, false));

            verify(repository, never()).settings(anyLong());
            verifyNoInteractions(subscriptions);
            assertNothingPushed();
        }

        @ParameterizedTest(name = "[{index}] vai trò {0}")
        @ValueSource(strings = {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"})
        @DisplayName("UTC-206 Người nhận là nhân viên: không gửi")
        void utc206_push_staffRecipientNothing(String role) {
            member(MINH, role, "ACTIVE");

            notifier.onMessageSent(message("Lan", false, false));

            verify(repository, never()).settings(anyLong());
            assertNothingPushed();
        }

        @ParameterizedTest(name = "[{index}] trạng thái {0}")
        @ValueSource(strings = {"LOCKED", "DELETED"})
        @DisplayName("UTC-207 Người nhận bị khóa / đã xóa từ khi lưu trình duyệt: không gửi")
        void utc207_push_lockedOrDeletedRecipientNothing(String status) {
            member(MINH, "USER", status);

            notifier.onMessageSent(message("Lan", false, false));

            assertNothingPushed();
        }

        @Test
        @DisplayName("UTC-208 Người nhận tắt \"Thông báo trên trình duyệt\": không gửi, không lấy lượt")
        void utc208_push_pushOffNothing() {
            when(repository.settings(MINH)).thenReturn(new Settings(true, false));

            notifier.onMessageSent(message("Lan", false, false));

            verifyNoInteractions(viewers, subscriptions);
            assertNothingPushed();
        }

        @Test
        @DisplayName("UTC-209 Người nhận tắt e-mail nhưng bật trình duyệt: vẫn gửi")
        void utc209_push_emailOffPushOnStillPushes() {
            when(repository.settings(MINH)).thenReturn(new Settings(false, true));

            notifier.onMessageSent(message("Lan", false, false));

            verify(sender, times(2)).send(any(), any());
        }

        @Test
        @DisplayName("UTC-210 Người nhận đang đọc đúng cuộc trò chuyện này: không gửi, không dùng lượt")
        void utc210_push_readingThatConversationNothing() {
            when(viewers.viewing(CONVERSATION, MINH)).thenReturn(true);

            notifier.onMessageSent(message("Lan", false, false));

            verify(viewers).viewing(CONVERSATION, MINH);
            verifyNoInteractions(subscriptions);
            assertNothingPushed();
        }

        @Test
        @DisplayName("UTC-211 Người nhận đang đọc cuộc trò chuyện khác: vẫn gửi")
        void utc211_push_readingAnotherConversationStillPushes() {
            when(viewers.viewing(7L, MINH)).thenReturn(true);

            notifier.onMessageSent(message("Lan", false, false));

            verify(sender, times(2)).send(any(), any());
        }

        @Test
        @DisplayName("UTC-212 Người nhận chưa bật thông báo trên trình duyệt nào: không gửi, không dùng lượt")
        void utc212_push_noBrowserNothing() {
            when(subscriptions.ofUser(MINH)).thenReturn(List.of());

            notifier.onMessageSent(message("Lan", false, false));

            assertNothingPushed();
        }

        @Test
        @DisplayName("UTC-213 Thứ tự kiểm tra: tài khoản → cài đặt → đang đọc → trình duyệt → lượt → gửi")
        void utc213_push_checksInOrder() {
            notifier.onMessageSent(message("Lan", false, false));

            InOrder order = inOrder(repository, viewers, subscriptions, sender);
            order.verify(repository).account(MINH);
            order.verify(repository).settings(MINH);
            order.verify(viewers).viewing(CONVERSATION, MINH);
            order.verify(subscriptions).ofUser(MINH);
            order.verify(repository).claimPush(eq(CONVERSATION), eq(MINH), any(), any());
            order.verify(sender).send(eq(phone), any());
        }

        @Test
        @DisplayName("UTC-214 Một trình duyệt lỗi: trình duyệt còn lại vẫn nhận, không ném lỗi, ghi cảnh báo")
        void utc214_push_oneBrowserFailingDoesNotStopOthers() {
            when(sender.send(eq(phone), any())).thenThrow(new DataAccessResourceFailureException("db down"));

            assertThatCode(() -> notifier.onMessageSent(message("Lan", false, false))).doesNotThrowAnyException();

            verify(sender).send(eq(laptop), any());
            assertThat(warnings()).containsExactly("Push to subscription 1 failed: db down");
        }

        @Test
        @DisplayName("UTC-215 Trình duyệt đã hết hạn (GONE, bộ gửi tự xóa): trình duyệt còn lại vẫn nhận")
        void utc215_push_goneSubscriptionDoesNotStopOthers() {
            when(sender.send(eq(phone), any())).thenReturn(WebPushSender.Result.GONE);

            notifier.onMessageSent(message("Lan", false, false));

            verify(sender).send(eq(laptop), any());
            verify(subscriptions, never()).delete(anyLong());
            assertThat(warnings()).isEmpty();
        }

        @Test
        @DisplayName("UTC-216 Lỗi cơ sở dữ liệu khi đọc người nhận: không ném lỗi, ghi cảnh báo")
        void utc216_push_databaseErrorIsLoggedNotThrown() {
            when(repository.account(MINH)).thenThrow(new DataAccessResourceFailureException("DB down"));

            assertThatCode(() -> notifier.onMessageSent(message("Lan", false, false))).doesNotThrowAnyException();

            assertThat(warnings()).containsExactly("Push for chat message 900 failed: DB down");
            verifyNoInteractions(sender);
        }

        @Test
        @DisplayName("UTC-217 Không tạo được JSON: không gửi, không ném lỗi, ghi cảnh báo")
        void utc217_push_jsonFailureIsLoggedNotThrown() throws Exception {
            ObjectMapper broken = mock(ObjectMapper.class);
            when(broken.writeValueAsBytes(any())).thenThrow(new JsonProcessingException("boom") {
            });
            ChatPushNotifier withBrokenJson = new ChatPushNotifier(repository, subscriptions, sender, viewers, broken,
                    Clock.fixed(NOW, ZoneOffset.UTC), 30);

            assertThatCode(() -> withBrokenJson.onMessageSent(message("Lan", false, false)))
                    .doesNotThrowAnyException();

            verifyNoInteractions(sender);
            assertThat(warnings()).singleElement().asString().startsWith("Push for chat message 900 failed:");
        }

        @Test
        @DisplayName("UTC-218 Chạy sau khi giao dịch lưu tin nhắn đã commit, trên luồng nền")
        void utc218_push_runsAfterCommitInBackground() throws Exception {
            Method method = ChatPushNotifier.class.getMethod("onMessageSent", ChatMessageSent.class);

            assertThat(method.getAnnotation(TransactionalEventListener.class).phase())
                    .isEqualTo(TransactionPhase.AFTER_COMMIT);
            assertThat(method.getAnnotation(Async.class)).isNotNull();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. payload – Tên người viết trong thông báo đẩy (UTC-219 → UTC-224)")
    class Payload {

        private JsonNode payload(ChatMessageSent event) throws Exception {
            return json.readTree(notifier.payload(event));
        }

        @Test
        @DisplayName("UTC-219 Tên có khoảng trắng hai đầu: bỏ khoảng trắng")
        void utc219_payload_stripsName() throws Exception {
            assertThat(payload(message("  Lan  ", false, false)).get("body").asText()).isEqualTo("Tin nhắn mới từ Lan");
        }

        @Test
        @DisplayName("UTC-220 Không có tên / tên rỗng / chỉ khoảng trắng: \"một thành viên\"")
        void utc220_payload_missingNameIsAMember() throws Exception {
            for (String name : new String[] {null, "", "   "}) {
                assertThat(payload(message(name, false, false)).get("body").asText())
                        .isEqualTo("Tin nhắn mới từ một thành viên");
            }
        }

        @Test
        @DisplayName("UTC-221 Lời mời từ người không có tên: \"một thành viên muốn nhắn tin với bạn.\"")
        void utc221_payload_requestWithoutName() throws Exception {
            assertThat(payload(message(null, false, true)).get("body").asText())
                    .isEqualTo("một thành viên muốn nhắn tin với bạn.");
        }

        @Test
        @DisplayName("UTC-222 Tên đúng 60 ký tự: giữ nguyên")
        void utc222_payload_nameOf60CharsKept() throws Exception {
            String name = "Ă".repeat(60);

            assertThat(payload(message(name, false, false)).get("body").asText()).isEqualTo("Tin nhắn mới từ " + name);
        }

        @Test
        @DisplayName("UTC-223 Tên 61 ký tự: cắt còn 59 ký tự + \"…\"")
        void utc223_payload_nameOf61CharsCut() throws Exception {
            String name = "N".repeat(59) + "XY";

            assertThat(payload(message(name, false, false)).get("body").asText())
                    .isEqualTo("Tin nhắn mới từ " + "N".repeat(59) + "…");
        }

        @Test
        @DisplayName("UTC-224 Liên kết và tag theo mã cuộc trò chuyện; nội dung là UTF-8")
        void utc224_payload_urlAndTagFollowConversation() throws Exception {
            byte[] bytes = notifier.payload(new ChatMessageSent(1234L, 1L, LAN, "Tiến", MINH, false, false));

            JsonNode body = json.readTree(bytes);
            assertThat(body.get("url").asText()).isEqualTo("/messages/1234");
            assertThat(body.get("tag").asText()).isEqualTo("chat-1234");
            assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("Tin nhắn mới từ Tiến");
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("03. NotifyRepository.claimPush – Một lượt đẩy mỗi 30 giây (UTC-225 → UTC-227)")
    class ClaimPush {

        private JdbcTemplate jdbc;
        private NotifyRepository real;

        @BeforeEach
        void realRepository() {
            jdbc = mock(JdbcTemplate.class);
            real = new NotifyRepository(jdbc);
        }

        @Test
        @DisplayName("UTC-225 Lần đẩy trước đủ cũ (cập nhật được 1 dòng): lấy được lượt, pushed_at = bây giờ")
        void utc225_repoClaim_trueWhenRowUpdated() {
            Instant lastAllowed = NOW.minusSeconds(30);
            when(jdbc.update(contains("UPDATE chat_members SET pushed_at = ?"), eq(Timestamp.from(NOW)),
                    eq(CONVERSATION), eq(MINH), eq(Timestamp.from(lastAllowed)))).thenReturn(1);

            assertThat(real.claimPush(CONVERSATION, MINH, NOW, lastAllowed)).isTrue();
        }

        @Test
        @DisplayName("UTC-226 Vừa đẩy trong 30 giây (không dòng nào cập nhật): không lấy được lượt")
        void utc226_repoClaim_falseWhenNoRowUpdated() {
            when(jdbc.update(contains("UPDATE chat_members SET pushed_at = ?"), eq(Timestamp.from(NOW)),
                    eq(CONVERSATION), eq(MINH), any())).thenReturn(0);

            assertThat(real.claimPush(CONVERSATION, MINH, NOW, NOW.minusSeconds(30))).isFalse();
        }

        @Test
        @DisplayName("UTC-227 Một câu lệnh duy nhất: chưa từng đẩy hoặc đẩy lần cuối <= mốc cho phép")
        void utc227_repoClaim_oneStatementCondition() {
            real.claimPush(CONVERSATION, MINH, NOW, NOW.minusSeconds(30));

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(jdbc, times(1)).update(sql.capture(), any(Object[].class));
            assertThat(sql.getValue())
                    .contains("WHERE conversation_id = ? AND user_id = ? AND (pushed_at IS NULL OR pushed_at <= ?)");
        }
    }
}
