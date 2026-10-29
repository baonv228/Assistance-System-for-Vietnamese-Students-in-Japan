package com.sep490.vsa.feature.chat.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.sep490.vsa.common.mail.EmailSender;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.chat.ChatMessageSent;
import com.sep490.vsa.feature.chat.notify.PushSubscriptionRepository.Subscription;
import com.sep490.vsa.feature.moderation.ImageModerator;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * New-message notifications against MySQL (own database {@code asvsj_test}, like ChatFlowTest): the settings and
 * push-subscription API, the e-mail digest's rules (conversations and messages written with SQL, their times moved
 * back), and the push listener after a message is committed (the HTTP sender replaced by a mock). Every test uses fresh
 * members, removed again afterwards.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false",             // ASVSJ places only: no calls to OpenStreetMap from the tests
        "app.chat.notify.email-initial-delay-ms=3600000"   // the digest runs only when a test calls it
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class NotifyFlowTest {

    private static final String CSRF = "test-csrf-token";
    private static final String API = "/api/me/chats/notify";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ChatEmailNotifier digest;
    @Autowired
    private PushSubscriptionRepository subscriptions;
    @Autowired
    private ApplicationEventPublisher events;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @MockBean
    private ImageModerator moderator;
    @MockBean
    private EmailSender mail;
    @MockBean
    private WebPushSender push;

    private final List<Long> users = new ArrayList<>();
    private final Map<Long, String> emails = new HashMap<>();
    private final SecureRandom random = new SecureRandom();

    @AfterEach
    void cleanUp() {
        for (long id : users) {
            jdbc.update("DELETE FROM chat_conversations WHERE user_low_id = ? OR user_high_id = ?", id, id);
        }
        for (long id : users) {
            jdbc.update("DELETE FROM users WHERE id = ?", id);           // settings, subscriptions, blocks cascade
        }
    }

    // ---------------------------------------------------------------- settings and subscriptions API

    @Test
    void onlyMembersChooseTheirNotifications() throws Exception {
        long member = user("Minh");

        mvc.perform(get(API + "/settings")).andExpect(status().isUnauthorized());
        mvc.perform(get(API + "/push-key")).andExpect(status().isUnauthorized());
        mvc.perform(put(API + "/settings").cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                .contentType(MediaType.APPLICATION_JSON).content("{\"emailEnabled\":false}"))
                .andExpect(status().isUnauthorized());
        for (String role : List.of("MODERATOR", "ACADEMIC_MANAGER", "ADMIN")) {
            long staff = user("Staff", role);
            mvc.perform(get(API + "/settings").session(session(staff, role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(NotifyService.NO_PERMISSION));
            mvc.perform(get(API + "/push-key").session(session(staff, role))).andExpect(status().isForbidden());
            subscribe(staff, role, validSubscription(fcmEndpoint())).andExpect(status().isForbidden());
            assertThat(count("SELECT COUNT(*) FROM push_subscriptions WHERE user_id = ?", staff)).isZero();
        }
        long locked = user("Khóa");
        jdbc.update("UPDATE users SET status = 'LOCKED' WHERE id = ?", locked);
        mvc.perform(get(API + "/settings").session(session(locked, "USER"))).andExpect(status().isUnauthorized());

        // No row: both on
        mvc.perform(get(API + "/settings").session(session(member, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emailEnabled").value(true))
                .andExpect(jsonPath("$.data.pushEnabled").value(true));
        putSettings(member, "{\"emailEnabled\":false}")
                .andExpect(jsonPath("$.data.emailEnabled").value(false))
                .andExpect(jsonPath("$.data.pushEnabled").value(true));
        putSettings(member, "{\"pushEnabled\":false}")
                .andExpect(jsonPath("$.data.emailEnabled").value(false))
                .andExpect(jsonPath("$.data.pushEnabled").value(false));
        putSettings(member, "{}")
                .andExpect(jsonPath("$.data.emailEnabled").value(false))
                .andExpect(jsonPath("$.data.pushEnabled").value(false));
        putSettings(member, "{\"emailEnabled\":true,\"pushEnabled\":true}")
                .andExpect(jsonPath("$.data.emailEnabled").value(true))
                .andExpect(jsonPath("$.data.pushEnabled").value(true));
        mvc.perform(get(API + "/settings").session(session(member, "USER")))
                .andExpect(jsonPath("$.data.emailEnabled").value(true));
    }

    @Test
    void thePushKeyIsMadeOnceAndKept() throws Exception {
        long member = user("Minh");
        String first = pushKey(member);
        String second = pushKey(user("Lan"));

        assertThat(second).isEqualTo(first);
        assertThat(P256.decode(first)).hasSize(65).startsWith(0x04);
        assertThat(first).doesNotContain("=", "+", "/");
        assertThat(jdbc.queryForObject("SELECT public_key FROM web_push_keys WHERE id = 1", String.class))
                .isEqualTo(first);
        assertThat(count("SELECT COUNT(*) FROM web_push_keys")).isEqualTo(1);
    }

    @Test
    void subscriptionsAreCheckedBeforeTheyAreKept() throws Exception {
        long member = user("Minh");
        String key = browserKey();
        String auth = P256.encode(randomBytes(16));

        Map<String, String> badEndpoints = new HashMap<>();
        for (String endpoint : List.of("http://fcm.googleapis.com/fcm/send/x", "https://evil.example.com/push",
                "https://169.254.169.254/latest/meta-data", "https://127.0.0.1:8090/api/admin/users",
                "https://fcm.googleapis.com:8443/fcm/send/x", "javascript:alert(1)", "", "https://fcm.googleapis.com/"
                        + "x".repeat(1000))) {
            badEndpoints.put(endpoint, NotifyService.BAD_ENDPOINT);
        }
        for (Map.Entry<String, String> bad : badEndpoints.entrySet()) {
            subscribe(member, body(bad.getKey(), key, auth)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(bad.getValue()));
        }
        subscribe(member, "{\"keys\":{\"p256dh\":\"" + key + "\",\"auth\":\"" + auth + "\"}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(NotifyService.BAD_ENDPOINT));

        byte[] offCurve = P256.decode(key);
        offCurve[64] ^= 1;
        for (String badKey : List.of(P256.encode(new byte[64]), P256.encode(offCurve), "không-phải-base64", "")) {
            subscribe(member, body(fcmEndpoint(), badKey, auth)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(NotifyService.BAD_KEY));
        }
        subscribe(member, "{\"endpoint\":\"" + fcmEndpoint() + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(NotifyService.BAD_KEY));
        for (String badAuth : List.of(P256.encode(randomBytes(15)), P256.encode(randomBytes(32)), "@@@", "")) {
            subscribe(member, body(fcmEndpoint(), key, badAuth)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(NotifyService.BAD_AUTH));
        }
        assertThat(count("SELECT COUNT(*) FROM push_subscriptions WHERE user_id = ?", member)).isZero();

        // A real one, as PushSubscription.toJSON() gives it (expirationTime ignored)
        String endpoint = fcmEndpoint();
        subscribe(member, "{\"endpoint\":\"" + endpoint + "\",\"expirationTime\":null,\"keys\":{\"p256dh\":\"" + key
                + "\",\"auth\":\"" + auth + "\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã bật thông báo trên trình duyệt này."));
        List<Subscription> saved = subscriptions.ofUser(member);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).endpoint()).isEqualTo(endpoint);
        assertThat(saved.get(0).p256dh()).isEqualTo(key);
        assertThat(saved.get(0).auth()).isEqualTo(auth);
        assertThat(jdbc.queryForObject("SELECT endpoint_hash FROM push_subscriptions WHERE id = ?", String.class,
                saved.get(0).id())).isEqualTo(PushSubscriptionRepository.hash(endpoint)).hasSize(64);

        // The same browser again: still one row, with its new keys
        String newKey = browserKey();
        subscribe(member, body(endpoint, newKey, auth)).andExpect(status().isOk());
        assertThat(subscriptions.ofUser(member)).singleElement()
                .satisfies(s -> assertThat(s.p256dh()).isEqualTo(newKey));
    }

    @Test
    void aMemberKeepsTenBrowsersAndRemovesTheirOwn() throws Exception {
        long minh = user("Minh");
        long lan = user("Lan");
        List<String> endpoints = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            String endpoint = i % 2 == 0 ? fcmEndpoint()
                    : "https://updates.push.services.mozilla.com/wpush/v2/" + UUID.randomUUID();
            endpoints.add(endpoint);
            subscribe(minh, validSubscription(endpoint)).andExpect(status().isOk());
        }
        assertThat(subscriptions.ofUser(minh)).hasSize(NotifyService.MAX_SUBSCRIPTIONS)
                .extracting(Subscription::endpoint).doesNotContain(endpoints.get(0))
                .contains(endpoints.get(1), endpoints.get(10));

        // Another account signs in on that browser: the browser is theirs now
        subscribe(lan, validSubscription(endpoints.get(10))).andExpect(status().isOk());
        assertThat(subscriptions.ofUser(lan)).extracting(Subscription::endpoint).containsExactly(endpoints.get(10));
        assertThat(subscriptions.ofUser(minh)).hasSize(9);

        // Removing: only one's own; an unknown endpoint is fine; no endpoint is not
        unsubscribe(lan, endpoints.get(5)).andExpect(status().isOk());
        assertThat(subscriptions.ofUser(minh)).extracting(Subscription::endpoint).contains(endpoints.get(5));
        unsubscribe(minh, endpoints.get(5)).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã tắt thông báo trên trình duyệt này."));
        assertThat(subscriptions.ofUser(minh)).hasSize(8).extracting(Subscription::endpoint)
                .doesNotContain(endpoints.get(5));
        unsubscribe(minh, fcmEndpoint()).andExpect(status().isOk());
        mvc.perform(withCsrf(delete(API + "/push-subscriptions"), minh, "USER")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(NotifyService.BAD_ENDPOINT));
    }

    @Test
    void theSubscriptionBookkeepingOfTheSender() throws Exception {
        long member = user("Minh");
        subscribe(member, validSubscription(fcmEndpoint())).andExpect(status().isOk());
        long id = subscriptions.ofUser(member).get(0).id();

        assertThat(subscriptions.recordFailure(id)).isEqualTo(1);
        assertThat(subscriptions.recordFailure(id)).isEqualTo(2);
        subscriptions.markSent(id, Instant.now());
        assertThat(count("SELECT failure_count FROM push_subscriptions WHERE id = ?", id)).isZero();
        assertThat(count("SELECT COUNT(*) FROM push_subscriptions WHERE id = ? AND last_used_at IS NOT NULL", id))
                .isEqualTo(1);
        assertThat(subscriptions.recordFailure(id)).isEqualTo(1);
        subscriptions.delete(id);
        assertThat(subscriptions.recordFailure(id)).isZero();
        assertThat(subscriptions.ofUser(member)).isEmpty();
    }

    // ---------------------------------------------------------------- e-mail digest

    @Test
    void anUnreadMessageIsEmailedOnceAfterTenMinutes() throws Exception {
        long minh = user("Minh");
        long lan = user("Lan");
        long conversation = conversation(minh, lan, "ACCEPTED");
        long first = message(conversation, lan, "Mã bí mật của mình là 4455", Duration.ofMinutes(15));
        long second = message(conversation, lan, "Bạn còn đó không?", Duration.ofMinutes(2));

        digest.sendDigests();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(mail, times(1)).send(eq(emails.get(minh)), eq(ChatEmailNotifier.SUBJECT), text.capture());
        verify(mail, never()).send(eq(emails.get(lan)), anyString(), anyString());
        assertThat(text.getValue()).contains("Chào Minh,", "từ Lan.", "/messages", "Thông báo qua e-mail")
                .doesNotContain("4455", "bí mật", "còn đó");
        // Both messages were announced by this e-mail
        assertThat(notifiedUpTo(conversation, minh)).isEqualTo(second).isGreaterThan(first);
        assertThat(emailedAt(conversation, minh)).isNotNull();

        // The next runs: nothing new to say
        digest.sendDigests();
        long third = message(conversation, lan, "Tin thứ ba", Duration.ofMinutes(11));
        digest.sendDigests();
        verify(mail, times(1)).send(eq(emails.get(minh)), anyString(), anyString());

        // Six hours after the e-mail, the new message is announced
        jdbc.update("UPDATE chat_members SET emailed_at = ? WHERE conversation_id = ? AND user_id = ?",
                ago(Duration.ofHours(6).plusMinutes(1)), conversation, minh);
        digest.sendDigests();
        verify(mail, times(2)).send(eq(emails.get(minh)), eq(ChatEmailNotifier.SUBJECT), anyString());
        assertThat(notifiedUpTo(conversation, minh)).isEqualTo(third);

        // Read meanwhile: nothing to announce, however long ago the last e-mail was
        jdbc.update("UPDATE chat_members SET emailed_at = ?, notified_up_to_id = 0, last_read_id = ? "
                + "WHERE conversation_id = ? AND user_id = ?", ago(Duration.ofHours(7)), third, conversation, minh);
        digest.sendDigests();
        verify(mail, times(2)).send(eq(emails.get(minh)), anyString(), anyString());
        verify(mail, never()).send(eq(emails.get(lan)), anyString(), anyString());
    }

    @Test
    void messagesThatAreNotAnnounced() {
        Duration old = Duration.ofMinutes(30);
        // Too young
        long young = user("Mới");
        message(conversation(young, user("Gửi 1"), "ACCEPTED"), lastUser(), "Chào", Duration.ofMinutes(9));
        // Read
        long read = user("Đã đọc");
        long readConversation = conversation(read, user("Gửi 2"), "ACCEPTED");
        long readMessage = message(readConversation, lastUser(), "Chào", old);
        jdbc.update("UPDATE chat_members SET last_read_id = ? WHERE conversation_id = ? AND user_id = ?",
                readMessage, readConversation, read);
        // Cleared ("Xóa cuộc trò chuyện")
        long cleared = user("Đã xóa");
        long clearedConversation = conversation(cleared, user("Gửi 3"), "ACCEPTED");
        long clearedMessage = message(clearedConversation, lastUser(), "Chào", old);
        jdbc.update("UPDATE chat_members SET cleared_id = ? WHERE conversation_id = ? AND user_id = ?",
                clearedMessage, clearedConversation, cleared);
        // The writer's account is locked: the message is hidden
        long hidden = user("Người nhận");
        long lockedWriter = user("Bị khóa");
        message(conversation(hidden, lockedWriter, "ACCEPTED"), lockedWriter, "Chào", old);
        jdbc.update("UPDATE users SET status = 'LOCKED' WHERE id = ?", lockedWriter);
        // The recipient is locked
        long lockedRecipient = user("Khóa nhận");
        message(conversation(lockedRecipient, user("Gửi 4"), "ACCEPTED"), lastUser(), "Chào", old);
        jdbc.update("UPDATE users SET status = 'LOCKED' WHERE id = ?", lockedRecipient);
        // E-mail turned off
        long off = user("Tắt e-mail");
        message(conversation(off, user("Gửi 5"), "ACCEPTED"), lastUser(), "Chào", old);
        jdbc.update("INSERT INTO chat_notify_settings (user_id, email_enabled, push_enabled) VALUES (?, FALSE, TRUE)",
                off);
        // A declined request
        long declined = user("Từ chối");
        message(conversation(declined, user("Gửi 6"), "DECLINED"), lastUser(), "Chào", old);
        // A recalled message
        long recalled = user("Thu hồi");
        long recalledMessage = message(conversation(recalled, user("Gửi 7"), "ACCEPTED"), lastUser(), "Chào", old);
        jdbc.update("UPDATE chat_messages SET recalled_at = ? WHERE id = ?", ago(Duration.ofMinutes(20)),
                recalledMessage);
        // A member the recipient blocked
        long blocker = user("Đã chặn");
        long blockedWriter = user("Gửi 8");
        message(conversation(blocker, blockedWriter, "ACCEPTED"), blockedWriter, "Chào", old);
        jdbc.update("INSERT INTO chat_blocks (blocker_id, blocked_id) VALUES (?, ?)", blocker, blockedWriter);
        // E-mailed two hours ago about this conversation
        long spaced = user("Vừa nhắc");
        long spacedConversation = conversation(spaced, user("Gửi 9"), "ACCEPTED");
        message(spacedConversation, lastUser(), "Chào", old);
        jdbc.update("UPDATE chat_members SET emailed_at = ? WHERE conversation_id = ? AND user_id = ?",
                ago(Duration.ofHours(2)), spacedConversation, spaced);
        // Only their own messages
        long writer = user("Chỉ mình viết");
        message(conversation(writer, user("Gửi 10"), "ACCEPTED"), writer, "Chào", old);
        // A request waiting for an answer: that one is announced
        long asked = user("Được mời");
        long askedConversation = conversation(asked, user("Người lạ"), "REQUEST");
        long requestMessage = message(askedConversation, lastUser(), "Chào bạn, cho mình hỏi", old);

        digest.sendDigests();

        for (long quiet : List.of(young, read, cleared, hidden, lockedRecipient, off, declined, recalled, blocker,
                spaced, writer)) {
            verify(mail, never()).send(eq(emails.get(quiet)), anyString(), anyString());
        }
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq(emails.get(asked)), eq(ChatEmailNotifier.SUBJECT), text.capture());
        assertThat(text.getValue()).contains("từ Người lạ (lời mời nhắn tin).").doesNotContain("cho mình hỏi");
        assertThat(notifiedUpTo(askedConversation, asked)).isEqualTo(requestMessage);
        assertThat(count("SELECT COUNT(*) FROM chat_members WHERE user_id = ? AND emailed_at IS NOT NULL", young))
                .isZero();
    }

    @Test
    void oneEmailNamesUpToFivePeople() {
        long minh = user("Minh");
        List<Long> conversations = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            long writer = user("Người gửi " + i);
            long conversation = conversation(minh, writer, "ACCEPTED");
            conversations.add(conversation);
            message(conversation, writer, "Tin số " + i, Duration.ofMinutes(60 - i));
        }

        digest.sendDigests();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(mail, times(1)).send(eq(emails.get(minh)), eq(ChatEmailNotifier.SUBJECT), text.capture());
        // Newest first, five names, then how many more
        assertThat(text.getValue())
                .contains("từ Người gửi 7, Người gửi 6, Người gửi 5, Người gửi 4, Người gửi 3 và 2 người khác.")
                .doesNotContain("Người gửi 2", "Người gửi 1", "Tin số");
        // Every conversation is marked, the two not named too
        for (long conversation : conversations) {
            assertThat(emailedAt(conversation, minh)).isNotNull();
        }
        digest.sendDigests();
        verify(mail, times(1)).send(eq(emails.get(minh)), anyString(), anyString());

        assertThat(digest.text("Minh", List.of(row("Lan", false)))).contains("từ Lan.");
        assertThat(digest.text("Minh", List.of(row("Lan", false), row("Tiến", true))))
                .contains("từ Lan và Tiến (lời mời nhắn tin).");
    }

    @Test
    void aFailedEmailIsTriedAgainNextTime() {
        long minh = user("Minh");
        long lan = user("Lan");
        long conversation = conversation(minh, lan, "ACCEPTED");
        message(conversation, lan, "Chào", Duration.ofMinutes(15));
        doThrow(new MailSendException("SMTP down")).doNothing()
                .when(mail).send(eq(emails.get(minh)), anyString(), anyString());

        digest.sendDigests();
        assertThat(emailedAt(conversation, minh)).isNull();
        assertThat(notifiedUpTo(conversation, minh)).isZero();

        digest.sendDigests();
        verify(mail, times(2)).send(eq(emails.get(minh)), anyString(), anyString());
        assertThat(emailedAt(conversation, minh)).isNotNull();
    }

    // ---------------------------------------------------------------- Web Push

    @Test
    void aCommittedMessageIsPushedOnceEveryThirtySeconds() throws Exception {
        long minh = user("Minh");
        long lan = user("Lan");
        long conversation = conversation(minh, lan, "ACCEPTED");
        String endpoint = fcmEndpoint();
        subscribe(minh, validSubscription(endpoint)).andExpect(status().isOk());
        long message = message(conversation, lan, "Nội dung riêng tư", Duration.ZERO);

        publish(new ChatMessageSent(conversation, message, lan, "Lan", minh, false, false));

        ArgumentCaptor<Subscription> to = ArgumentCaptor.forClass(Subscription.class);
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(push, timeout(5000)).send(to.capture(), payload.capture());
        assertThat(to.getValue().endpoint()).isEqualTo(endpoint);
        JsonNode body = json.readTree(payload.getValue());
        assertThat(body.get("title").asText()).isEqualTo("Senpai ơi");
        assertThat(body.get("body").asText()).isEqualTo("Tin nhắn mới từ Lan");
        assertThat(body.get("url").asText()).isEqualTo("/messages/" + conversation);
        assertThat(body.get("tag").asText()).isEqualTo("chat-" + conversation);
        assertThat(body.size()).isEqualTo(4);
        assertThat(new String(payload.getValue(), StandardCharsets.UTF_8)).doesNotContain("riêng tư");
        assertThat(count("""
                SELECT COUNT(*) FROM chat_members WHERE conversation_id = ? AND user_id = ? AND pushed_at IS NOT NULL""",
                conversation, minh)).isEqualTo(1);

        // A second message right after: no second push
        publish(new ChatMessageSent(conversation, message + 1, lan, "Lan", minh, false, false));
        verify(push, after(1000).times(1)).send(any(), any());

        // 30 seconds later: pushed again, to every browser of the member
        subscribe(minh, validSubscription(fcmEndpoint())).andExpect(status().isOk());
        jdbc.update("UPDATE chat_members SET pushed_at = ? WHERE conversation_id = ? AND user_id = ?",
                ago(Duration.ofSeconds(31)), conversation, minh);
        publish(new ChatMessageSent(conversation, message + 2, lan, "Lan", minh, true, false));
        verify(push, timeout(5000).times(3)).send(any(), any());
    }

    @Test
    void aRequestIsPushedWithItsOwnWording() throws Exception {
        long minh = user("Minh");
        long stranger = user("Người lạ");
        long conversation = conversation(minh, stranger, "REQUEST");
        subscribe(minh, validSubscription(fcmEndpoint())).andExpect(status().isOk());

        publish(new ChatMessageSent(conversation, 1, stranger, "Người lạ", minh, false, true));

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(push, timeout(5000)).send(any(), payload.capture());
        assertThat(json.readTree(payload.getValue()).get("body").asText())
                .isEqualTo("Người lạ muốn nhắn tin với bạn.");
    }

    @Test
    void noPushWhenItIsOffOrNobodyCanReceiveIt() throws Exception {
        long lan = user("Lan");
        // Push turned off
        long off = user("Tắt");
        long offConversation = conversation(off, lan, "ACCEPTED");
        subscribe(off, validSubscription(fcmEndpoint())).andExpect(status().isOk());
        putSettings(off, "{\"pushEnabled\":false}").andExpect(status().isOk());
        // Locked since the browser was saved
        long locked = user("Khóa");
        long lockedConversation = conversation(locked, lan, "ACCEPTED");
        subscribe(locked, validSubscription(fcmEndpoint())).andExpect(status().isOk());
        jdbc.update("UPDATE users SET status = 'LOCKED' WHERE id = ?", locked);
        // No browser
        long none = user("Không trình duyệt");
        long noneConversation = conversation(none, lan, "ACCEPTED");
        // A message that was rolled back
        long rolledBack = user("Hoàn tác");
        long rolledBackConversation = conversation(rolledBack, lan, "ACCEPTED");
        subscribe(rolledBack, validSubscription(fcmEndpoint())).andExpect(status().isOk());

        publish(new ChatMessageSent(offConversation, 1, lan, "Lan", off, false, false));
        publish(new ChatMessageSent(lockedConversation, 2, lan, "Lan", locked, false, false));
        publish(new ChatMessageSent(noneConversation, 3, lan, "Lan", none, false, false));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            events.publishEvent(new ChatMessageSent(rolledBackConversation, 4, lan, "Lan", rolledBack, false, false));
            status.setRollbackOnly();
        });
        // Published outside a transaction: a TransactionalEventListener ignores it
        events.publishEvent(new ChatMessageSent(rolledBackConversation, 5, lan, "Lan", rolledBack, false, false));

        verify(push, after(1500).never()).send(any(), any());
        assertThat(count("SELECT COUNT(*) FROM chat_members WHERE conversation_id IN (?, ?, ?, ?) AND pushed_at IS NOT NULL",
                offConversation, lockedConversation, noneConversation, rolledBackConversation)).isZero();
    }

    @Test
    void noPushWhileTheMemberIsReadingThatConversation() throws Exception {
        long minh = user("Minh");
        long lan = user("Lan");
        long reading = conversation(minh, lan, "ACCEPTED");
        long other = conversation(minh, user("Bình"), "ACCEPTED");
        subscribe(minh, validSubscription(fcmEndpoint())).andExpect(status().isOk());

        // Minh's page asks for this conversation's updates (it does every 4 s while it is shown)
        mvc.perform(get("/api/me/chats/" + reading + "/updates").session(session(minh, "USER")))
                .andExpect(status().isOk());
        publish(new ChatMessageSent(reading, 1, lan, "Lan", minh, true, false));
        verify(push, after(1000).never()).send(any(), any());
        assertThat(count("SELECT COUNT(*) FROM chat_members WHERE conversation_id = ? AND pushed_at IS NOT NULL",
                reading)).isZero();

        // Another conversation of Minh is still pushed
        publish(new ChatMessageSent(other, 2, lan, "Bình", minh, true, false));
        verify(push, timeout(5000).times(1)).send(any(), any());
    }

    // ---------------------------------------------------------------- helpers

    private long user(String name) {
        return user(name, "USER");
    }

    private long user(String name, String role) {
        String email = "notify-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, ?, ?, 'ACTIVE', TRUE, ?)""",
                email, name, role, Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        users.add(id);
        emails.put(id, email);
        return id;
    }

    private long lastUser() {
        return users.get(users.size() - 1);
    }

    /** A conversation the writer started: the writer ACCEPTED, the recipient in {@code recipientState}. */
    private long conversation(long recipient, long writer, String recipientState) {
        jdbc.update("""
                INSERT INTO chat_conversations (user_low_id, user_high_id, started_by, created_at) VALUES (?, ?, ?, ?)""",
                Math.min(recipient, writer), Math.max(recipient, writer), writer, ago(Duration.ofDays(1)));
        long id = jdbc.queryForObject("""
                SELECT id FROM chat_conversations WHERE user_low_id = ? AND user_high_id = ?""",
                Long.class, Math.min(recipient, writer), Math.max(recipient, writer));
        jdbc.update("""
                INSERT INTO chat_members (conversation_id, user_id, state) VALUES (?, ?, 'ACCEPTED'), (?, ?, ?)""",
                id, writer, id, recipient, recipientState);
        return id;
    }

    private long message(long conversation, long sender, String text, Duration ago) {
        jdbc.update("INSERT INTO chat_messages (conversation_id, sender_id, content, created_at) VALUES (?, ?, ?, ?)",
                conversation, sender, text, ago(ago));
        return jdbc.queryForObject("SELECT MAX(id) FROM chat_messages WHERE conversation_id = ?", Long.class,
                conversation);
    }

    private long notifiedUpTo(long conversation, long user) {
        return count("SELECT notified_up_to_id FROM chat_members WHERE conversation_id = ? AND user_id = ?",
                conversation, user);
    }

    private Timestamp emailedAt(long conversation, long user) {
        return jdbc.queryForObject("SELECT emailed_at FROM chat_members WHERE conversation_id = ? AND user_id = ?",
                Timestamp.class, conversation, user);
    }

    private static NotifyRepository.DigestRow row(String peer, boolean request) {
        return new NotifyRepository.DigestRow(1, "x@test.local", "Minh", 1, request, peer, 1);
    }

    private void publish(ChatMessageSent event) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> events.publishEvent(event));
    }

    private String pushKey(long user) throws Exception {
        String body = mvc.perform(get(API + "/push-key").session(session(user, "USER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.data.publicKey");
    }

    private String browserKey() {
        return P256.encode(P256.raw((ECPublicKey) P256.generate().getPublic()));
    }

    private String validSubscription(String endpoint) throws Exception {
        return body(endpoint, browserKey(), P256.encode(randomBytes(16)));
    }

    private String body(String endpoint, String p256dh, String auth) throws Exception {
        return json.writeValueAsString(Map.of("endpoint", endpoint, "keys", Map.of("p256dh", p256dh, "auth", auth)));
    }

    private static String fcmEndpoint() {
        return "https://fcm.googleapis.com/fcm/send/" + UUID.randomUUID() + ":APA91b" + UUID.randomUUID();
    }

    private ResultActions subscribe(long user, String body) throws Exception {
        return subscribe(user, "USER", body);
    }

    private ResultActions subscribe(long user, String role, String body) throws Exception {
        return mvc.perform(withCsrf(post(API + "/push-subscriptions"), user, role)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions unsubscribe(long user, String endpoint) throws Exception {
        return mvc.perform(withCsrf(delete(API + "/push-subscriptions"), user, "USER")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("endpoint", endpoint))));
    }

    private ResultActions putSettings(long user, String body) throws Exception {
        return mvc.perform(withCsrf(put(API + "/settings"), user, "USER")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        random.nextBytes(bytes);
        return bytes;
    }

    private static Timestamp ago(Duration duration) {
        return Timestamp.from(Instant.now().minus(duration));
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private static MockHttpSession session(long userId, String role) {
        AppUserPrincipal me = new AppUserPrincipal(userId, "u" + userId + "@test.local", "Notify Test", role, "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))));
        return session;
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request, long userId,
                                                          String role) {
        return request.session(session(userId, role)).cookie(new Cookie("XSRF-TOKEN", CSRF))
                .header("X-XSRF-TOKEN", CSRF);
    }
}
