package com.sep490.vsa.feature.chat;

import com.jayway.jsonpath.JsonPath;
import com.sep490.vsa.feature.moderation.ImageModerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Edit, recall, reactions, quoted replies, "đang soạn tin", single messages, older pages, the open page's updates,
 * the header's newest unread message, and the {@link ChatMessageSent} event, against MySQL.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"              // ASVSJ places only: no calls to OpenStreetMap from the tests
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@RecordApplicationEvents
class ChatUtilitiesFlowTest extends ChatTestBase {

    private static final String THUMBS_UP = "👍";
    private static final String HEART = "❤️";
    private static final String LAUGH = "😂";

    @MockBean
    private ImageModerator moderator;
    @Autowired
    private ChatTyping typing;
    @Autowired
    private Clock clock;
    @Autowired
    private ApplicationEvents events;

    // ---------------------------------------------------------------- edit

    @Test
    void ownMessagesAreEditedWithinFifteenMinutesAtMostFiveTimes() throws Exception {
        long a = user("An");
        long b = user("Bình");
        long conversation = accepted(a, b);
        long message = id(sendPlain(a, conversation, "Mai 7 giờ gặp ở ga nhé"), "$.data.message.id");
        String url = API + "/" + conversation + "/messages/" + message;

        call(put(url), b, Map.of("content", "Sửa tin người khác")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(ChatService.NOT_MINE));
        call(put(url), user("Người ngoài"), Map.of("content", "Xen vào")).andExpect(status().isNotFound());
        call(put(url), a, Map.of("content", "  ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Hãy nhập tin nhắn."));
        call(put(url), a, Map.of("content", "Bên mình có việc nhẹ lương cao")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("việc nhẹ lương cao")));
        call(put(url), a, Map.of("content", "Xem ở https://example.com nhé")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Chỉ tài khoản đã có tag định danh mới được chèn link."));
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("encrypted", Map.of("ciphertext", b64(random(64)), "iv", b64(random(12)), "senderKeyId", 1,
                "recipientKeyId", 2, "frankingTag", "a".repeat(64)));
        call(put(url), a, envelope).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ChatService.NOT_ENCRYPTED));
        assertThat(count("SELECT COUNT(*) FROM chat_message_edits WHERE message_id = ?", message)).isZero();

        for (int i = 1; i <= ChatService.EDIT_MAX; i++) {
            call(put(url), a, Map.of("content", "Mai " + (7 + i) + " giờ gặp ở ga nhé")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").value("Mai " + (7 + i) + " giờ gặp ở ga nhé"))
                    .andExpect(jsonPath("$.data.edited").value(true));
        }
        call(put(url), a, Map.of("content", "Lần thứ sáu")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ChatService.EDIT_LIMIT));
        assertThat(jdbc.queryForList("SELECT content FROM chat_message_edits WHERE message_id = ? ORDER BY id",
                String.class, message)).containsExactly("Mai 7 giờ gặp ở ga nhé", "Mai 8 giờ gặp ở ga nhé",
                "Mai 9 giờ gặp ở ga nhé", "Mai 10 giờ gặp ở ga nhé", "Mai 11 giờ gặp ở ga nhé");
        read(API + "/" + conversation, b).andExpect(jsonPath("$.data.messages[1].content").value("Mai 12 giờ gặp ở ga nhé"))
                .andExpect(jsonPath("$.data.messages[1].edited").value(true));
        assertThat(jdbc.queryForObject("SELECT changed_at IS NOT NULL AND edited_at IS NOT NULL FROM chat_messages"
                + " WHERE id = ?", Boolean.class, message)).isTrue();

        // Fifteen minutes after sending it is too late
        long late = id(sendPlain(a, conversation, "Tin gửi lâu rồi"), "$.data.message.id");
        jdbc.update("UPDATE chat_messages SET created_at = created_at - INTERVAL 16 MINUTE WHERE id = ?", late);
        call(put(API + "/" + conversation + "/messages/" + late), a, Map.of("content", "Sửa muộn"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(ChatService.EDIT_TOO_LATE));

        // Not while the other member blocks the writer
        long fresh = id(sendPlain(a, conversation, "Tin mới"), "$.data.message.id");
        mvc.perform(withCsrf(post(API + "/blocks/" + a), b)).andExpect(status().isOk());
        call(put(API + "/" + conversation + "/messages/" + fresh), a, Map.of("content", "Sửa sau khi bị chặn"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(ChatService.UNAVAILABLE));
    }

    // ---------------------------------------------------------------- recall

    @Test
    void aRecalledMessageBecomesAPlaceholderForBoth() throws Exception {
        long a = user("An");
        long b = user("Bình");
        long conversation = accepted(a, b);
        long message = id(sendPlain(a, conversation, "Số tài khoản của mình là..."), "$.data.message.id");
        String url = API + "/" + conversation + "/messages/" + message;
        call(put(url + "/reaction"), b, Map.of("emoji", THUMBS_UP)).andExpect(status().isOk());

        mvc.perform(withCsrf(delete(url), b)).andExpect(status().isForbidden());
        mvc.perform(withCsrf(delete(url), a)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value((int) message))
                .andExpect(jsonPath("$.data.recalled").value(true))
                .andExpect(jsonPath("$.data.content").value(nullValue()))
                .andExpect(jsonPath("$.data.encrypted").value(nullValue()))
                .andExpect(jsonPath("$.data.reactions", empty()))
                .andExpect(jsonPath("$.data.fileIds", empty()));
        assertThat(count("SELECT COUNT(*) FROM chat_messages WHERE id = ? AND recalled_at IS NOT NULL"
                + " AND changed_at IS NOT NULL", message)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM chat_reactions WHERE message_id = ?", message)).isZero();
        read(API + "/" + conversation, b)
                .andExpect(jsonPath("$.data.messages[1].recalled").value(true))
                .andExpect(jsonPath("$.data.messages[1].content").value(nullValue()));
        read(API, b).andExpect(jsonPath("$.data.items[0].lastMessage.recalled").value(true))
                .andExpect(jsonPath("$.data.items[0].lastMessage.content").value(nullValue()));
        read(url, b).andExpect(status().isOk()).andExpect(jsonPath("$.data.recalled").value(true));

        // Nothing more can be done with it
        mvc.perform(withCsrf(delete(url), a)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ChatService.RECALLED));
        call(put(url), a, Map.of("content", "Sửa lại")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ChatService.RECALLED));
        call(put(url + "/reaction"), b, Map.of("emoji", HEART)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ChatService.RECALLED));
        sendBody(b, conversation, Map.of("content", "Trả lời", "replyToId", message)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ChatService.REPLY_GONE));
        long reason = jdbc.queryForObject("SELECT MIN(id) FROM reason_options WHERE scope = 'REPORT' AND is_active",
                Long.class);
        call(post(API + "/messages/" + message + "/report"), b, Map.of("reasonId", reason))
                .andExpect(status().isNotFound());

        // Recalling works even after a block
        long other = id(sendPlain(a, conversation, "Tin thứ hai"), "$.data.message.id");
        mvc.perform(withCsrf(post(API + "/blocks/" + a), b)).andExpect(status().isOk());
        mvc.perform(withCsrf(delete(API + "/" + conversation + "/messages/" + other), a)).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- reactions

    @Test
    void eachMemberHasOneReactionPerMessage() throws Exception {
        long a = user("An");
        long b = user("Bình");
        long conversation = accepted(a, b);
        long message = id(sendPlain(a, conversation, "Mình đỗ JLPT N2 rồi!"), "$.data.message.id");
        String url = API + "/" + conversation + "/messages/" + message + "/reaction";

        call(put(url), b, Map.of("emoji", THUMBS_UP)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reactions", hasSize(1)))
                .andExpect(jsonPath("$.data.reactions[0].emoji").value(THUMBS_UP))
                .andExpect(jsonPath("$.data.reactions[0].count").value(1))
                .andExpect(jsonPath("$.data.reactions[0].mine").value(true));
        call(put(url), a, Map.of("emoji", HEART)).andExpect(status().isOk());
        read(API + "/" + conversation + "/messages/" + message, a)
                .andExpect(jsonPath("$.data.reactions[*].emoji").value(contains(THUMBS_UP, HEART)))
                .andExpect(jsonPath("$.data.reactions[0].mine").value(false))
                .andExpect(jsonPath("$.data.reactions[1].mine").value(true));
        // A new one replaces the one before; both on the same emoji count 2
        call(put(url), b, Map.of("emoji", HEART)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reactions", hasSize(1)))
                .andExpect(jsonPath("$.data.reactions[0].count").value(2));
        call(put(url), b, Map.of("emoji", LAUGH)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT emoji FROM chat_reactions WHERE message_id = ? AND user_id = ?",
                String.class, message, b)).isEqualTo(LAUGH);
        mvc.perform(withCsrf(delete(url), b)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reactions[*].emoji").value(contains(HEART)));
        assertThat(count("SELECT COUNT(*) FROM chat_messages WHERE id = ? AND changed_at IS NOT NULL", message))
                .isEqualTo(1);

        // Only the fixed set, with its exact code points
        for (String wrong : List.of("🔥", "❤", "", "like")) {
            call(put(url), b, Map.of("emoji", wrong)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(ChatService.BAD_REACTION));
        }

        // Not in a request, not after a block
        long c = user("Chi");
        long request = request(c, a, "Chào An");
        long asked = jdbc.queryForObject("SELECT MAX(id) FROM chat_messages WHERE conversation_id = ?", Long.class,
                request);
        call(put(API + "/" + request + "/messages/" + asked + "/reaction"), a, Map.of("emoji", THUMBS_UP))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(ChatService.REACT_NOT_ALLOWED));
        call(put(API + "/" + request + "/messages/" + asked + "/reaction"), c, Map.of("emoji", THUMBS_UP))
                .andExpect(status().isForbidden());
        read(API + "/" + request, a).andExpect(jsonPath("$.data.status.canReact").value(false));
        mvc.perform(withCsrf(post(API + "/blocks/" + b), a)).andExpect(status().isOk());
        call(put(url), b, Map.of("emoji", THUMBS_UP)).andExpect(status().isForbidden());
        call(put(url), a, Map.of("emoji", THUMBS_UP)).andExpect(status().isBadRequest());
        read(API + "/" + conversation, b).andExpect(jsonPath("$.data.status.canReact").value(false));
    }

    // ---------------------------------------------------------------- quoted replies

    @Test
    void aReplyQuotesAMessageOfTheSameConversation() throws Exception {
        long a = user("An");
        long b = user("Bình");
        long conversation = accepted(a, b);
        long question = id(sendPlain(a, conversation, "Thẻ My Number làm ở đâu?"), "$.data.message.id");
        long elsewhere = id(sendPlain(a, accepted(a, user("Chi")), "Tin ở chỗ khác"), "$.data.message.id");

        for (long bad : List.of(elsewhere, 999999999L)) {
            sendBody(b, conversation, Map.of("content", "Trả lời", "replyToId", bad)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(ChatService.REPLY_GONE));
        }
        long answer = id(sendBody(b, conversation, Map.of("content", "Ở phường nhé", "replyToId", question))
                .andExpect(jsonPath("$.data.message.replyToId").value((int) question)), "$.data.message.id");
        read(API + "/" + conversation + "/messages/" + answer, a)
                .andExpect(jsonPath("$.data.replyToId").value((int) question));

        // A message the writer cleared from their side cannot be quoted by them
        mvc.perform(withCsrf(delete(API + "/" + conversation), b)).andExpect(status().isOk());
        sendBody(b, conversation, Map.of("content", "Trả lời lại", "replyToId", question))
                .andExpect(status().isBadRequest());
        sendBody(a, conversation, Map.of("content", "Mình hỏi lại", "replyToId", question)).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- typing

    @Test
    void typingShowsForSixSecondsBetweenAcceptedMembers() throws Exception {
        long a = user("An");
        long b = user("Bình");
        long conversation = accepted(a, b);
        try {
            mvc.perform(withCsrf(post(API + "/" + conversation + "/typing"), a)).andExpect(status().isOk());
            read(API + "/" + conversation + "/updates", b).andExpect(jsonPath("$.data.status.peerTyping").value(true));
            read(API + "/" + conversation + "/updates", a).andExpect(jsonPath("$.data.status.peerTyping").value(false));

            typing.useClock(Clock.offset(clock, ChatTyping.TTL.plusSeconds(1)));
            read(API + "/" + conversation + "/updates", b).andExpect(jsonPath("$.data.status.peerTyping").value(false));
            typing.useClock(clock);

            // A sent message ends it at once
            mvc.perform(withCsrf(post(API + "/" + conversation + "/typing"), a)).andExpect(status().isOk());
            sendPlain(a, conversation, "Xong rồi").andExpect(status().isOk());
            read(API + "/" + conversation + "/updates", b).andExpect(jsonPath("$.data.status.peerTyping").value(false));

            // In a request it is not counted, though the answer is the same
            long c = user("Chi");
            long request = request(c, a, "Chào An");
            mvc.perform(withCsrf(post(API + "/" + request + "/typing"), c)).andExpect(status().isOk());
            read(API + "/" + request, a).andExpect(jsonPath("$.data.status.peerTyping").value(false));
            mvc.perform(withCsrf(post(API + "/" + request + "/typing"), b)).andExpect(status().isNotFound());
        } finally {
            typing.useClock(clock);
        }
    }

    // ---------------------------------------------------------------- reading

    @Test
    void updatesBringChangedMessagesAndTheServerTime() throws Exception {
        long a = user("An");
        long b = user("Bình");
        long conversation = accepted(a, b);
        long message = id(sendPlain(a, conversation, "Tin sẽ được thả tim"), "$.data.message.id");
        String updates = API + "/" + conversation + "/updates";

        String first = body(read(updates, b).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed", empty())));
        String serverTime = JsonPath.read(first, "$.data.serverTime");
        Instant at = Instant.parse(serverTime);
        assertThat(at).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(1));

        call(put(API + "/" + conversation + "/messages/" + message + "/reaction"), b, Map.of("emoji", HEART))
                .andExpect(status().isOk());
        mvc.perform(get(updates).param("after", String.valueOf(message)).param("changedSince", serverTime)
                        .session(session(a)))
                .andExpect(jsonPath("$.data.messages", empty()))
                .andExpect(jsonPath("$.data.changed[*].id").value(contains((int) message)))
                .andExpect(jsonPath("$.data.changed[0].reactions[0].emoji").value(HEART));
        mvc.perform(get(updates).param("changedSince", String.valueOf(at.toEpochMilli())).session(session(a)))
                .andExpect(jsonPath("$.data.changed", hasSize(1)));

        // Changed long before changedSince: not sent again
        jdbc.update("UPDATE chat_messages SET changed_at = ? WHERE id = ?",
                java.sql.Timestamp.from(at.minus(Duration.ofMinutes(1))), message);
        mvc.perform(get(updates).param("changedSince", serverTime).session(session(a)))
                .andExpect(jsonPath("$.data.changed", empty()));
        mvc.perform(get(updates).param("changedSince", "hôm qua").session(session(a)))
                .andExpect(status().isBadRequest());
        // A far-away year parses: taken as "every change", never a 500
        mvc.perform(get(updates).param("changedSince", "-1000000000-01-01T00:00:00Z").session(session(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed[*].id").value(contains((int) message)));
        mvc.perform(get(updates).param("changedSince", "99999999999999999999").session(session(a)))
                .andExpect(status().isBadRequest());

        // Opening the conversation gives the page its first changedSince, from the server's clock
        String opened = body(read(API + "/" + conversation, a).andExpect(status().isOk()));
        assertThat(Instant.parse(JsonPath.read(opened, "$.data.serverTime")))
                .isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(1));
    }

    @Test
    void olderMessagesTakeALimit() throws Exception {
        long a = user("An");
        long b = user("Bình");
        long conversation = accepted(a, b);
        for (int i = 2; i <= 50; i++) {
            jdbc.update("INSERT INTO chat_messages (conversation_id, sender_id, content) VALUES (?, ?, ?)",
                    conversation, i % 2 == 0 ? b : a, "Tin " + i);
        }
        long newest = jdbc.queryForObject("SELECT MAX(id) FROM chat_messages WHERE conversation_id = ?", Long.class,
                conversation);
        String older = API + "/" + conversation + "/messages";
        mvc.perform(get(older).param("before", String.valueOf(newest + 1)).param("limit", "10").session(session(a)))
                .andExpect(jsonPath("$.data.messages", hasSize(10)))
                .andExpect(jsonPath("$.data.messages[9].content").value("Tin 50"))
                .andExpect(jsonPath("$.data.hasOlder").value(true));
        mvc.perform(get(older).param("before", String.valueOf(newest + 1)).param("limit", "500").session(session(a)))
                .andExpect(jsonPath("$.data.messages", hasSize(50)))
                .andExpect(jsonPath("$.data.hasOlder").value(false));
        mvc.perform(get(older).param("before", String.valueOf(newest)).param("limit", "0").session(session(a)))
                .andExpect(jsonPath("$.data.messages[*].content").value(contains("Tin 49")));
        mvc.perform(get(older).param("before", String.valueOf(newest + 1)).session(session(a)))
                .andExpect(jsonPath("$.data.messages", hasSize(ChatService.PAGE_SIZE)));
    }

    @Test
    void oneMessageIsShownToTheTwoMembersOnly() throws Exception {
        long a = user("An");
        long b = user("Bình");
        long conversation = accepted(a, b);
        long message = id(sendPlain(a, conversation, "Chuyện riêng"), "$.data.message.id");
        long otherConversation = accepted(a, user("Chi"));

        read(API + "/" + conversation + "/messages/" + message, b).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("Chuyện riêng"))
                .andExpect(jsonPath("$.data.mine").value(false));
        read(API + "/" + conversation + "/messages/" + message, user("Người lạ")).andExpect(status().isNotFound());
        read(API + "/" + otherConversation + "/messages/" + message, a).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(ChatService.MESSAGE_NOT_FOUND));
        mvc.perform(withCsrf(delete(API + "/" + conversation), b)).andExpect(status().isOk());
        read(API + "/" + conversation + "/messages/" + message, b).andExpect(status().isNotFound());
    }

    @Test
    void theHeaderGetsTheNewestUnreadMessage() throws Exception {
        long a = user("An");
        long b = user("Bình");
        long conversation = request(a, b, "Chào Bình");
        long first = jdbc.queryForObject("SELECT MAX(id) FROM chat_messages WHERE conversation_id = ?", Long.class,
                conversation);
        read(API + "/counts", b)
                .andExpect(jsonPath("$.data.latest.conversationId").value((int) conversation))
                .andExpect(jsonPath("$.data.latest.messageId").value((int) first))
                .andExpect(jsonPath("$.data.latest.peerName").value("An"))
                .andExpect(jsonPath("$.data.latest.request").value(true))
                .andExpect(jsonPath("$.data.latest.createdAt").isString());
        read(API + "/counts", a).andExpect(jsonPath("$.data.latest").value(nullValue()));

        mvc.perform(withCsrf(post(API + "/" + conversation + "/read"), b).contentType("application/json")
                .content("{}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.latest").value(nullValue()));
        long answer = id(sendPlain(b, conversation, "Chào An"), "$.data.message.id");
        read(API + "/counts", a).andExpect(jsonPath("$.data.latest.messageId").value((int) answer))
                .andExpect(jsonPath("$.data.latest.request").value(false));
        // A recalled message is not announced
        mvc.perform(withCsrf(delete(API + "/" + conversation + "/messages/" + answer), b)).andExpect(status().isOk());
        read(API + "/counts", a).andExpect(jsonPath("$.data.latest").value(nullValue()));
    }

    // ---------------------------------------------------------------- the event for notifications

    @Test
    void everySavedMessagePublishesOneEvent() throws Exception {
        long a = user("An");
        long b = user("Bình");
        long conversation = request(a, b, "Chào Bình");
        sendPlain(a, conversation, "Bên mình có việc nhẹ lương cao").andExpect(status().isBadRequest());
        sendPlain(b, conversation, "Chào An").andExpect(status().isOk());
        long keyA = newKey(a);
        long keyB = newKey(b);
        long encrypted = sendSealed(new Encrypted(conversation, a, b, keyA, keyB), a, "Bí mật", List.of());

        List<ChatMessageSent> sent = events.stream(ChatMessageSent.class).toList();
        assertThat(sent).hasSize(3);
        assertThat(sent.get(0)).isEqualTo(new ChatMessageSent(conversation, sent.get(0).messageId(), a, "An", b,
                false, true));
        assertThat(sent.get(1)).extracting(ChatMessageSent::senderId, ChatMessageSent::recipientId,
                ChatMessageSent::senderName, ChatMessageSent::encrypted, ChatMessageSent::request)
                .containsExactly(b, a, "Bình", false, false);
        assertThat(sent.get(2)).isEqualTo(new ChatMessageSent(conversation, encrypted, a, "An", b, true, false));
        assertThat(sent.stream().map(ChatMessageSent::messageId).distinct()).hasSize(3);
    }
}
