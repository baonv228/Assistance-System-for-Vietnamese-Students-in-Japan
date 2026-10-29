package com.sep490.vsa.feature.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.chat.ChatDtos.SendRequest;
import com.sep490.vsa.feature.moderation.ImageModerator;
import com.sep490.vsa.feature.moderation.ImageModerator.Verdict;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Tin nhắn" end to end against MySQL (own database {@code asvsj_test}, like SavedFlowTest): requests, accepting,
 * declining, blocking, the content rules, the limits, hidden accounts, reports, and messages sent at the same moment.
 * Every test uses fresh members, removed again afterwards.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"              // ASVSJ places only: no calls to OpenStreetMap from the tests
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ChatFlowTest {

    private static final String CSRF = "test-csrf-token";
    private static final String API = "/api/me/chats";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ChatService chats;
    @MockBean
    private ImageModerator moderator;

    private final List<Long> users = new ArrayList<>();
    private long reason;

    @BeforeEach
    void setUp() {
        when(moderator.engine()).thenReturn("OPENAI");
        when(moderator.check(any(byte[].class))).thenReturn(new Verdict("PASS", "{}"));
        reason = jdbc.queryForObject("SELECT MIN(id) FROM reason_options WHERE scope = 'REPORT' AND is_active", Long.class);
    }

    @AfterEach
    void cleanUp() {
        for (long id : users) {
            jdbc.update("""
                    DELETE FROM report_cases WHERE target_type = 'MESSAGE'
                    AND target_id IN (SELECT id FROM chat_messages WHERE sender_id = ?)""", id);
        }
        for (long id : users) {
            jdbc.update("DELETE FROM chat_conversations WHERE user_low_id = ? OR user_high_id = ?", id, id);
            jdbc.update("DELETE FROM user_tags WHERE user_id = ? OR granted_by = ?", id, id);
        }
        for (long id : users) {
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    // ---------------------------------------------------------------- who may chat

    @Test
    void onlySignedInMembersChat() throws Exception {
        long member = user("Minh");
        long other = user("Lan");

        mvc.perform(get(API)).andExpect(status().isUnauthorized());
        mvc.perform(get(API + "/counts")).andExpect(status().isUnauthorized());
        mvc.perform(post(API + "/with/" + other + "/messages").cookie(new Cookie("XSRF-TOKEN", CSRF))
                        .header("X-XSRF-TOKEN", CSRF).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Chào\"}"))
                .andExpect(status().isUnauthorized());

        for (String role : List.of("MODERATOR", "ACADEMIC_MANAGER", "ADMIN")) {
            long staff = user("Staff", role, 30);
            mvc.perform(get(API).session(session(staff, role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(ChatService.NO_PERMISSION));
            send(staff, role, other, "Chào bạn").andExpect(status().isForbidden());
        }

        mvc.perform(get(API).session(session(member, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", empty()))
                .andExpect(jsonPath("$.data.counts.unread").value(0))
                .andExpect(jsonPath("$.data.counts.requests").value(0));
        mvc.perform(get(API).param("box", "SPAM").session(session(member, "USER"))).andExpect(status().isBadRequest());
    }

    @Test
    void whoCanBeMessaged() throws Exception {
        long me = user("Minh");
        long staff = user("Kiểm duyệt", "MODERATOR", 30);
        long locked = user("Khóa");
        jdbc.update("UPDATE users SET status = 'LOCKED' WHERE id = ?", locked);
        long deleted = user("Xóa");
        jdbc.update("UPDATE users SET status = 'DELETED', display_name = 'Tài khoản đã xóa' WHERE id = ?", deleted);

        mvc.perform(get(API + "/with/" + me).session(session(me, "USER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Bạn không thể nhắn tin cho chính mình."));
        send(me, me, "Tự nhắn").andExpect(status().isBadRequest());
        mvc.perform(get(API + "/with/999999999").session(session(me, "USER"))).andExpect(status().isNotFound());
        send(me, 999999999L, "Chào").andExpect(status().isNotFound());
        mvc.perform(get(API + "/with/" + deleted).session(session(me, "USER"))).andExpect(status().isNotFound());
        send(me, deleted, "Chào").andExpect(status().isNotFound());

        for (long peer : List.of(staff, locked)) {
            mvc.perform(get(API + "/with/" + peer).session(session(me, "USER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(nullValue()))
                    .andExpect(jsonPath("$.data.peer.active").value(false))
                    .andExpect(jsonPath("$.data.status.canSend").value(false))
                    .andExpect(jsonPath("$.data.status.notice.code").value("UNAVAILABLE"));
            send(me, peer, "Chào").andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(ChatService.UNAVAILABLE));
        }
        assertThat(count("SELECT COUNT(*) FROM chat_conversations WHERE started_by = ?", me)).isZero();
    }

    // ---------------------------------------------------------------- requests

    @Test
    void theFirstMessageIsARequestUntilItIsAnswered() throws Exception {
        long minh = user("Minh");
        long tien = user("Tiến");

        // Before the first message: no conversation yet, a first message is allowed
        mvc.perform(get(API + "/with/" + tien).session(session(minh, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(nullValue()))
                .andExpect(jsonPath("$.data.peer.displayName").value("Tiến"))
                .andExpect(jsonPath("$.data.peer.active").value(true))
                .andExpect(jsonPath("$.data.status.canSend").value(true))
                .andExpect(jsonPath("$.data.status.notice.code").value("NEW"))
                .andExpect(jsonPath("$.data.messages", empty()))
                .andExpect(jsonPath("$.data.limits.pendingMax").value(ChatService.PENDING_MAX))
                .andExpect(jsonPath("$.data.reportReasons", hasSize(4)));

        long conversation = sentConversation(send(minh, tien, "  Anh Tiến ơi, quanh ga Nippori ăn ở đâu rẻ ạ?  "));
        assertThat(jdbc.queryForObject("SELECT content FROM chat_messages WHERE conversation_id = ?", String.class,
                conversation)).isEqualTo("Anh Tiến ơi, quanh ga Nippori ăn ở đâu rẻ ạ?");

        // The writer has it in the inbox; the member asked sees a request ("Lời mời"), unread
        mvc.perform(get(API).session(session(minh, "USER")))
                .andExpect(jsonPath("$.data.items[*].id").value(contains((int) conversation)))
                .andExpect(jsonPath("$.data.items[0].lastMessage.mine").value(true))
                .andExpect(jsonPath("$.data.items[0].unread").value(0))
                .andExpect(jsonPath("$.data.counts.unread").value(0));
        mvc.perform(get(API).session(session(tien, "USER"))).andExpect(jsonPath("$.data.items", empty()));
        mvc.perform(get(API).param("box", "REQUESTS").session(session(tien, "USER")))
                .andExpect(jsonPath("$.data.items[*].id").value(contains((int) conversation)))
                .andExpect(jsonPath("$.data.items[0].state").value("REQUEST"))
                .andExpect(jsonPath("$.data.items[0].peer.displayName").value("Minh"))
                .andExpect(jsonPath("$.data.items[0].lastMessage.mine").value(false))
                .andExpect(jsonPath("$.data.items[0].unread").value(1))
                .andExpect(jsonPath("$.data.counts.unread").value(1))
                .andExpect(jsonPath("$.data.counts.requests").value(1));
        mvc.perform(get(API + "/" + conversation).session(session(tien, "USER")))
                .andExpect(jsonPath("$.data.status.state").value("REQUEST"))
                .andExpect(jsonPath("$.data.status.canSend").value(true))
                .andExpect(jsonPath("$.data.status.notice.code").value("REQUEST"))
                .andExpect(jsonPath("$.data.messages[0].mine").value(false));

        // Two more messages, then the writer waits; reading the request does not show "Đã xem" yet
        mvc.perform(get(API + "/" + conversation).session(session(minh, "USER")))
                .andExpect(jsonPath("$.data.status.notice.code").value("WAITING"))
                .andExpect(jsonPath("$.data.status.notice.text").value(containsString("còn gửi được 2 tin nhắn")));
        sendIn(minh, conversation, "Em mới sang được 1 ngày").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status.notice.code").value("WAITING"));
        sendIn(minh, conversation, "Em cảm ơn anh").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status.canSend").value(false))
                .andExpect(jsonPath("$.data.status.notice.code").value("WAITING_FULL"));
        sendIn(minh, conversation, "Anh ơi?").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Tiến chưa nhận lời mời nhắn tin của bạn. Bạn gửi thêm được khi"
                        + " Tiến trả lời."));
        read(tien, conversation, null).andExpect(jsonPath("$.data.unread").value(0))
                .andExpect(jsonPath("$.data.requests").value(1));
        mvc.perform(get(API + "/" + conversation).session(session(minh, "USER")))
                .andExpect(jsonPath("$.data.status.peerReadUpTo").value(0));

        // Answering accepts: it moves to the inbox, both write freely and "Đã xem" shows
        sendIn(tien, conversation, "Gần ga có quán cơm Hana, cơm phần 700 yên nhé.").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status.state").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.status.notice").value(nullValue()));
        mvc.perform(get(API).param("box", "REQUESTS").session(session(tien, "USER")))
                .andExpect(jsonPath("$.data.items", empty()))
                .andExpect(jsonPath("$.data.counts.requests").value(0));
        mvc.perform(get(API).session(session(tien, "USER")))
                .andExpect(jsonPath("$.data.items[*].id").value(contains((int) conversation)))
                .andExpect(jsonPath("$.data.items[0].lastMessage.mine").value(true));
        long answer = latestMessage(conversation);
        sendIn(minh, conversation, "Dạ em cảm ơn anh nhiều ạ!").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status.canSend").value(true))
                .andExpect(jsonPath("$.data.status.notice").value(nullValue()))
                .andExpect(jsonPath("$.data.status.peerReadUpTo").value((int) answer));
        mvc.perform(get(API + "/" + conversation).session(session(minh, "USER")))
                .andExpect(jsonPath("$.data.messages", hasSize(5)))
                .andExpect(jsonPath("$.data.messages[3].mine").value(false))
                .andExpect(jsonPath("$.data.messages[4].mine").value(true));
        mvc.perform(get(API + "/counts").session(session(tien, "USER")))
                .andExpect(jsonPath("$.data.unread").value(1));
    }

    @Test
    void aRequestCanBeAcceptedOrDeclined() throws Exception {
        long writer = user("Người viết");
        long accepter = user("Người nhận");
        long accepted = sentConversation(send(writer, accepter, "Chào bạn, mình hỏi chút về nhà ở"));
        mvc.perform(withCsrf(post(API + "/" + accepted + "/accept"), accepter))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.canSend").value(true));
        mvc.perform(get(API).session(session(accepter, "USER")))
                .andExpect(jsonPath("$.data.items[*].id").value(contains((int) accepted)));
        mvc.perform(get(API + "/" + accepted).session(session(writer, "USER")))
                .andExpect(jsonPath("$.data.status.notice").value(nullValue()));
        // Only a request waiting for an answer can be declined
        mvc.perform(withCsrf(post(API + "/" + accepted + "/decline"), accepter)).andExpect(status().isBadRequest());
        mvc.perform(withCsrf(post(API + "/" + accepted + "/decline"), writer)).andExpect(status().isBadRequest());

        long decliner = user("Người từ chối");
        long declined = sentConversation(send(writer, decliner, "Chào bạn, cho mình làm quen nhé"));
        mvc.perform(withCsrf(post(API + "/" + declined + "/decline"), decliner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requests").value(0))
                .andExpect(jsonPath("$.data.unread").value(0));
        mvc.perform(get(API).param("box", "REQUESTS").session(session(decliner, "USER")))
                .andExpect(jsonPath("$.data.items", empty()));
        // The writer is not told, but cannot send more (one message of three used)
        mvc.perform(get(API + "/" + declined).session(session(writer, "USER")))
                .andExpect(jsonPath("$.data.status.canSend").value(false))
                .andExpect(jsonPath("$.data.status.notice.code").value("WAITING_FULL"));
        sendIn(writer, declined, "Bạn ơi?").andExpect(status().isForbidden());
        assertThat(count("SELECT COUNT(*) FROM chat_messages WHERE conversation_id = ?", declined)).isEqualTo(1);

        // Writing back opens it again, without the old messages for the one who declined
        send(decliner, writer, "Xin lỗi, giờ mình mới rảnh").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conversationId").value((int) declined))
                .andExpect(jsonPath("$.data.status.state").value("ACCEPTED"));
        mvc.perform(get(API + "/" + declined).session(session(decliner, "USER")))
                .andExpect(jsonPath("$.data.messages", hasSize(1)))
                .andExpect(jsonPath("$.data.messages[0].mine").value(true));
        sendIn(writer, declined, "Không sao, cảm ơn bạn").andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- blocking

    @Test
    void aBlockStopsBothSidesUntilItIsLifted() throws Exception {
        long a = user("An");
        long b = user("Bình");
        long conversation = sentConversation(send(a, b, "Chào Bình"));
        sendIn(b, conversation, "Chào An").andExpect(status().isOk());

        mvc.perform(withCsrf(post(API + "/blocks/" + a), b)).andExpect(status().isOk());
        mvc.perform(withCsrf(post(API + "/blocks/" + a), b)).andExpect(status().isOk());          // twice: no error
        sendIn(a, conversation, "Sao vậy?").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(ChatService.UNAVAILABLE));
        send(a, b, "Sao vậy?").andExpect(status().isForbidden());
        sendIn(b, conversation, "Thôi nhé").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Bạn đã chặn An. Bỏ chặn để nhắn tin tiếp."));
        mvc.perform(get(API + "/" + conversation).session(session(b, "USER")))
                .andExpect(jsonPath("$.data.status.blockedByMe").value(true))
                .andExpect(jsonPath("$.data.status.notice.code").value("BLOCKED_BY_ME"));
        // The blocked member reads the same as for a locked account
        mvc.perform(get(API + "/" + conversation).session(session(a, "USER")))
                .andExpect(jsonPath("$.data.status.blockedByMe").value(false))
                .andExpect(jsonPath("$.data.status.canSend").value(false))
                .andExpect(jsonPath("$.data.status.notice.code").value("UNAVAILABLE"));
        mvc.perform(get(API).session(session(b, "USER")))
                .andExpect(jsonPath("$.data.items[0].blockedByMe").value(true));
        mvc.perform(get(API + "/blocks").session(session(b, "USER")))
                .andExpect(jsonPath("$.data[*].peer.id").value(contains((int) a)));
        mvc.perform(withCsrf(post(API + "/blocks/" + b), b)).andExpect(status().isBadRequest());
        mvc.perform(withCsrf(post(API + "/blocks/999999999"), b)).andExpect(status().isNotFound());

        mvc.perform(withCsrf(delete(API + "/blocks/" + a), b)).andExpect(status().isOk());
        mvc.perform(get(API + "/blocks").session(session(b, "USER"))).andExpect(jsonPath("$.data", empty()));
        sendIn(a, conversation, "Mình hỏi tiếp nhé").andExpect(status().isOk());
        sendIn(b, conversation, "Ừ").andExpect(status().isOk());
    }

    @Test
    void blockingFromARequestDeclinesIt() throws Exception {
        long stranger = user("Người lạ");
        long member = user("Thành viên");
        long conversation = sentConversation(send(stranger, member, "Chào em, bên chị đang tuyển người"));

        mvc.perform(withCsrf(post(API + "/blocks/" + stranger), member)).andExpect(status().isOk());
        mvc.perform(get(API).param("box", "REQUESTS").session(session(member, "USER")))
                .andExpect(jsonPath("$.data.items", empty()))
                .andExpect(jsonPath("$.data.counts.requests").value(0));
        assertThat(jdbc.queryForObject("SELECT state FROM chat_members WHERE conversation_id = ? AND user_id = ?",
                String.class, conversation, member)).isEqualTo("DECLINED");
        sendIn(stranger, conversation, "Em ơi").andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- content rules

    @Test
    void messagesGoThroughTheKeywordAndLinkRules() throws Exception {
        long plain = user("Chưa có tag");
        long tagged = user("Có tag");
        long peer = user("Người nhận");
        long mentorTag = jdbc.queryForObject("SELECT id FROM tag_types WHERE name = 'Mentor'", Long.class);
        jdbc.update("INSERT INTO user_tags (user_id, tag_type_id, granted_by) VALUES (?, ?, ?)", tagged, mentorTag, tagged);

        send(plain, peer, "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Hãy nhập tin nhắn."));
        send(plain, peer, "   \n  ").andExpect(status().isBadRequest());
        send(plain, peer, "a".repeat(ChatService.MESSAGE_MAX + 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Tin nhắn tối đa 2.000 ký tự."));
        send(plain, peer, "Bên mình có việc nhẹ lương cao, em làm không?").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Chưa gửi được: tin nhắn chứa từ khóa không được phép “việc nhẹ"
                        + " lương cao”. Hãy sửa rồi gửi lại."));
        send(plain, peer, "Xem ở https://example.com/viec-lam nhé").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Chỉ tài khoản đã có tag định danh mới được chèn link."));
        send(plain, peer, "Nhắn Zalo cho chị 0901234567 nhé").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("số điện thoại hoặc tài khoản Zalo")));
        send(tagged, peer, "Đăng ký ở bit.ly/abc123").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("link rút gọn")));
        assertThat(count("SELECT COUNT(*) FROM chat_conversations WHERE started_by IN (?, ?)", plain, tagged)).isZero();

        // A refused message keeps the request count free; ordinary text and a tagged member's link go through
        send(plain, peer, "a".repeat(ChatService.MESSAGE_MAX)).andExpect(status().isOk());
        send(plain, peer, "Phường Arakawa: 03-3802-3111").andExpect(status().isOk());
        send(tagged, peer, "Hướng dẫn ở https://www.city.arakawa.tokyo.jp nhé").andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- limits

    @Test
    void aNewAccountStartsAFewConversationsADay() throws Exception {
        long newcomer = user("Mới", "USER", 1);
        for (int i = 0; i < ChatService.NEW_CHATS_PER_DAY_NEW_ACCOUNT; i++) {
            send(newcomer, user("Người " + i), "Chào bạn " + i).andExpect(status().isOk());
        }
        send(newcomer, user("Thêm một người"), "Chào bạn").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(containsString("giới hạn của tài khoản mới")));
        // A conversation already started is not a new one
        long first = jdbc.queryForObject("SELECT MIN(id) FROM chat_conversations WHERE started_by = ?", Long.class, newcomer);
        sendIn(newcomer, first, "Bạn ơi").andExpect(status().isOk());
    }

    @Test
    void aMemberSendsAtMostThirtyMessagesAMinute() throws Exception {
        long talker = user("Nói nhiều");
        long friend = user("Bạn");
        long conversation = sentConversation(send(friend, talker, "Chào"));
        for (int i = 1; i <= ChatService.MESSAGES_PER_MINUTE; i++) {
            sendIn(talker, conversation, "Tin số " + i).andExpect(status().isOk());
        }
        sendIn(talker, conversation, "Thêm nữa").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(ChatService.TOO_FAST));
        // A minute later it is free again
        jdbc.update("UPDATE chat_messages SET created_at = created_at - INTERVAL 61 SECOND WHERE sender_id = ?", talker);
        sendIn(talker, conversation, "Giờ thì được").andExpect(status().isOk());
    }

    @Test
    void theSameLongTextGoesToAFewMembersOnly() throws Exception {
        long spammer = user("Rải tin");
        String text = "Chào bạn, mình có khóa học tiếng Nhật online giá rẻ, bạn quan tâm không?";
        for (int i = 0; i < ChatService.REPEATED_TEXT_CHATS; i++) {
            send(spammer, user("Người " + i), text).andExpect(status().isOk());
        }
        send(spammer, user("Người nữa"), "  " + text.toUpperCase() + " ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ChatService.REPEATED));
        // Short everyday texts stay free
        long friend = user("Bạn thân");
        long conversation = sentConversation(send(friend, spammer, "Chào"));
        for (int i = 0; i < 5; i++) {
            sendIn(spammer, conversation, "Cảm ơn bạn nhiều").andExpect(status().isOk());
        }
    }

    // ---------------------------------------------------------------- hidden accounts

    @Test
    void messagesOfALockedAccountAreHiddenUntilItIsUnlocked() throws Exception {
        long scammer = user("Chị tuyển dụng");
        long member = user("Thành viên");
        long friend = user("Bạn quen");
        long request = sentConversation(send(scammer, member, "Chào em, bên chị tuyển người"));
        long talk = sentConversation(send(scammer, friend, "Chào bạn"));
        sendIn(friend, talk, "Chào anh").andExpect(status().isOk());

        jdbc.update("UPDATE users SET status = 'LOCKED' WHERE id = ?", scammer);
        // The request shows no message any more, so it leaves the list; the friend keeps their own message
        mvc.perform(get(API).param("box", "REQUESTS").session(session(member, "USER")))
                .andExpect(jsonPath("$.data.items", empty()))
                .andExpect(jsonPath("$.data.counts.unread").value(0))
                .andExpect(jsonPath("$.data.counts.requests").value(0));
        mvc.perform(get(API + "/" + talk).session(session(friend, "USER")))
                .andExpect(jsonPath("$.data.messages", hasSize(1)))
                .andExpect(jsonPath("$.data.messages[0].mine").value(true))
                .andExpect(jsonPath("$.data.peer.active").value(false))
                .andExpect(jsonPath("$.data.status.notice.code").value("UNAVAILABLE"));
        mvc.perform(get(API).session(session(friend, "USER")))
                .andExpect(jsonPath("$.data.items[0].lastMessage.mine").value(true));
        sendIn(friend, talk, "Anh ơi").andExpect(status().isForbidden());
        long hidden = jdbc.queryForObject("SELECT MIN(id) FROM chat_messages WHERE conversation_id = ?", Long.class, talk);
        mvc.perform(withCsrf(post(API + "/messages/" + hidden + "/report"), friend).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonId\":" + reason + "}")).andExpect(status().isNotFound());

        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", scammer);
        mvc.perform(get(API).param("box", "REQUESTS").session(session(member, "USER")))
                .andExpect(jsonPath("$.data.items[*].id").value(contains((int) request)));
        mvc.perform(get(API + "/" + talk).session(session(friend, "USER")))
                .andExpect(jsonPath("$.data.messages", hasSize(2)));

        jdbc.update("UPDATE users SET status = 'DELETED', display_name = 'Tài khoản đã xóa' WHERE id = ?", scammer);
        mvc.perform(get(API + "/" + talk).session(session(friend, "USER")))
                .andExpect(jsonPath("$.data.peer.displayName").value("Tài khoản đã xóa"))
                .andExpect(jsonPath("$.data.status.notice.code").value("DELETED"));
        sendIn(friend, talk, "Anh ơi").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ChatService.DELETED));
        mvc.perform(get(API + "/with/" + scammer).session(session(friend, "USER")))
                .andExpect(jsonPath("$.data.id").value((int) talk));
    }

    // ---------------------------------------------------------------- reading, clearing, privacy

    @Test
    void unreadCountsFollowWhatWasRead() throws Exception {
        long a = user("An");
        long b = user("Bình");
        long c = user("Chi");
        long ab = sentConversation(send(b, a, "Chào An"));
        sendIn(a, ab, "Chào Bình").andExpect(status().isOk());
        long ac = sentConversation(send(c, a, "An ơi"));
        sendIn(a, ac, "Ơi").andExpect(status().isOk());
        sendIn(b, ab, "Tin 1").andExpect(status().isOk());
        long second = sentId(sendIn(b, ab, "Tin 2"));
        sendIn(c, ac, "Tin của Chi").andExpect(status().isOk());

        mvc.perform(get(API).session(session(a, "USER")))
                .andExpect(jsonPath("$.data.items[*].id").value(contains((int) ac, (int) ab)))
                .andExpect(jsonPath("$.data.items[0].unread").value(1))
                .andExpect(jsonPath("$.data.items[1].unread").value(2))
                .andExpect(jsonPath("$.data.counts.unread").value(2));
        read(a, ab, second - 1).andExpect(jsonPath("$.data.unread").value(2));
        mvc.perform(get(API).session(session(a, "USER"))).andExpect(jsonPath("$.data.items[1].unread").value(1));
        read(a, ab, second).andExpect(jsonPath("$.data.unread").value(1));
        read(a, ac, null).andExpect(jsonPath("$.data.unread").value(0));
        // Reading never goes back, nor past the newest message
        read(a, ab, 1L).andExpect(jsonPath("$.data.unread").value(0));
        read(a, ab, Long.MAX_VALUE / 2).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT last_read_id FROM chat_members WHERE conversation_id = ? AND user_id = ?",
                Long.class, ab, a)).isEqualTo(second);
        // "Đã xem" for B
        mvc.perform(get(API + "/" + ab + "/updates").param("after", String.valueOf(second)).session(session(b, "USER")))
                .andExpect(jsonPath("$.data.messages", empty()))
                .andExpect(jsonPath("$.data.status.peerReadUpTo").value((int) second));
    }

    @Test
    void deletingAConversationHidesItForThatMemberOnly() throws Exception {
        long a = user("An");
        long b = user("Bình");
        long conversation = sentConversation(send(a, b, "Chào Bình"));
        sendIn(b, conversation, "Chào An").andExpect(status().isOk());

        mvc.perform(withCsrf(delete(API + "/" + conversation), b)).andExpect(status().isOk());
        mvc.perform(get(API).session(session(b, "USER"))).andExpect(jsonPath("$.data.items", empty()));
        mvc.perform(get(API + "/" + conversation).session(session(b, "USER")))
                .andExpect(jsonPath("$.data.messages", empty()))
                .andExpect(jsonPath("$.data.status.canSend").value(true));
        mvc.perform(get(API + "/" + conversation).session(session(a, "USER")))
                .andExpect(jsonPath("$.data.messages", hasSize(2)));

        // A new message brings it back, with that message only
        sendIn(a, conversation, "Bình còn đó không?").andExpect(status().isOk());
        mvc.perform(get(API).session(session(b, "USER")))
                .andExpect(jsonPath("$.data.items[*].id").value(contains((int) conversation)))
                .andExpect(jsonPath("$.data.items[0].unread").value(1));
        mvc.perform(get(API + "/" + conversation).session(session(b, "USER")))
                .andExpect(jsonPath("$.data.messages", hasSize(1)))
                .andExpect(jsonPath("$.data.messages[0].content").value("Bình còn đó không?"));

        // Deleting a request declines it
        long c = user("Chi");
        long request = sentConversation(send(c, b, "Chào Bình, mình là Chi"));
        mvc.perform(withCsrf(delete(API + "/" + request), b)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT state FROM chat_members WHERE conversation_id = ? AND user_id = ?",
                String.class, request, b)).isEqualTo("DECLINED");
    }

    @Test
    void otherMembersCannotReachAConversation() throws Exception {
        long a = user("An");
        long b = user("Bình");
        long outsider = user("Người ngoài");
        long conversation = sentConversation(send(a, b, "Chuyện riêng"));
        long message = latestMessage(conversation);

        mvc.perform(get(API + "/" + conversation).session(session(outsider, "USER"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(ChatService.CONVERSATION_NOT_FOUND));
        mvc.perform(get(API + "/" + conversation + "/updates").session(session(outsider, "USER")))
                .andExpect(status().isNotFound());
        mvc.perform(get(API + "/" + conversation + "/messages").param("before", "999999999")
                .session(session(outsider, "USER"))).andExpect(status().isNotFound());
        sendIn(outsider, conversation, "Xen vào").andExpect(status().isNotFound());
        mvc.perform(withCsrf(post(API + "/" + conversation + "/read"), outsider)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
        for (String action : List.of("/accept", "/decline")) {
            mvc.perform(withCsrf(post(API + "/" + conversation + action), outsider)).andExpect(status().isNotFound());
        }
        mvc.perform(withCsrf(delete(API + "/" + conversation), outsider)).andExpect(status().isNotFound());
        mvc.perform(withCsrf(post(API + "/messages/" + message + "/report"), outsider)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reasonId\":" + reason + "}"))
                .andExpect(status().isNotFound());
        assertThat(count("SELECT COUNT(*) FROM chat_messages WHERE conversation_id = ?", conversation)).isEqualTo(1);
    }

    @Test
    void olderMessagesComeAPageAtATime() throws Exception {
        long a = user("An");
        long b = user("Bình");
        long conversation = sentConversation(send(a, b, "Tin 1"));
        sendIn(b, conversation, "Tin 2").andExpect(status().isOk());
        for (int i = 3; i <= 35; i++) {
            jdbc.update("INSERT INTO chat_messages (conversation_id, sender_id, content) VALUES (?, ?, ?)",
                    conversation, i % 2 == 0 ? b : a, "Tin " + i);
        }
        String body = mvc.perform(get(API + "/" + conversation).session(session(a, "USER")))
                .andExpect(jsonPath("$.data.messages", hasSize(ChatService.PAGE_SIZE)))
                .andExpect(jsonPath("$.data.messages[0].content").value("Tin 6"))
                .andExpect(jsonPath("$.data.messages[29].content").value("Tin 35"))
                .andExpect(jsonPath("$.data.hasOlder").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long oldestShown = ((Number) JsonPath.read(body, "$.data.messages[0].id")).longValue();
        long newest = ((Number) JsonPath.read(body, "$.data.messages[29].id")).longValue();
        mvc.perform(get(API + "/" + conversation + "/messages").param("before", String.valueOf(oldestShown))
                        .session(session(a, "USER")))
                .andExpect(jsonPath("$.data.messages[*].content").value(contains("Tin 1", "Tin 2", "Tin 3", "Tin 4", "Tin 5")))
                .andExpect(jsonPath("$.data.hasOlder").value(false));

        long fresh = sentId(sendIn(b, conversation, "Tin mới"));
        mvc.perform(get(API + "/" + conversation + "/updates").param("after", String.valueOf(newest))
                        .session(session(a, "USER")))
                .andExpect(jsonPath("$.data.messages[*].id").value(contains((int) fresh)))
                .andExpect(jsonPath("$.data.messages[0].mine").value(false));
    }

    // ---------------------------------------------------------------- reports

    @Test
    void aReceivedMessageCanBeReportedOnceAndTheWriterBlocked() throws Exception {
        long scammer = user("Chị tuyển dụng");
        long member = user("Thành viên");
        long conversation = sentConversation(send(scammer, member, "Em gửi chị ảnh thẻ cư trú để đăng ký nhé"));
        long message = latestMessage(conversation);
        long second = sentId(sendIn(scammer, conversation, "Gửi luôn số tài khoản ngân hàng cho chị"));

        report(member, message, "{}").andExpect(status().isBadRequest());
        report(scammer, message, "{\"reasonId\":" + reason + "}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Bạn không thể báo cáo nội dung của chính mình."));
        report(member, message, "{\"reasonId\":" + reason + ",\"note\":\"Xin ảnh thẻ cư trú\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Cảm ơn bạn. Báo cáo đã được gửi."));
        Map<String, Object> reportCase = jdbc.queryForMap("""
                SELECT handler_role, report_count, priority, status FROM report_cases
                WHERE target_type = 'MESSAGE' AND target_id = ?""", message);
        assertThat(reportCase).containsEntry("handler_role", "MODERATOR").containsEntry("report_count", 1)
                .containsEntry("priority", "NORMAL").containsEntry("status", "OPEN");
        assertThat(jdbc.queryForObject("""
                SELECT r.note FROM reports r JOIN report_cases c ON c.id = r.case_id
                WHERE c.target_type = 'MESSAGE' AND c.target_id = ? AND r.reporter_id = ?""",
                String.class, message, member)).isEqualTo("Xin ảnh thẻ cư trú");
        report(member, message, "{\"reasonId\":" + reason + "}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Bạn đã báo cáo nội dung này rồi."));
        assertThat(count("SELECT COUNT(*) FROM chat_blocks WHERE blocker_id = ?", member)).isZero();

        // "block": the writer is blocked and the request declined; the messages are kept for the Moderator
        report(member, second, "{\"reasonId\":" + reason + ",\"block\":true}").andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM chat_blocks WHERE blocker_id = ? AND blocked_id = ?", member, scammer))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM chat_members WHERE conversation_id = ? AND user_id = ?",
                String.class, conversation, member)).isEqualTo("DECLINED");
        assertThat(count("SELECT COUNT(*) FROM chat_messages WHERE id = ?", message)).isEqualTo(1);
        sendIn(scammer, conversation, "Em ơi").andExpect(status().isForbidden());
        report(member, 999999999L, "{\"reasonId\":" + reason + "}").andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- at the same moment

    @Test
    void twoMembersWritingFirstAtOnceShareOneConversation() throws Exception {
        for (int round = 0; round < 5; round++) {
            long a = user("An");
            long b = user("Bình");
            List<Object> results = together(
                    () -> chats.sendTo(principal(a), b, new SendRequest("Chào Bình")),
                    () -> chats.sendTo(principal(b), a, new SendRequest("Chào An")));
            assertThat(results).allMatch(r -> r instanceof ChatDtos.Sent);
            assertThat(count("""
                    SELECT COUNT(*) FROM chat_conversations WHERE user_low_id = LEAST(?, ?) AND user_high_id = GREATEST(?, ?)""",
                    a, b, a, b)).isEqualTo(1);
            long conversation = conversationOf(a, b);
            assertThat(count("SELECT COUNT(*) FROM chat_messages WHERE conversation_id = ?", conversation)).isEqualTo(2);
            assertThat(jdbc.queryForList("SELECT state FROM chat_members WHERE conversation_id = ?", String.class,
                    conversation)).containsOnly("ACCEPTED");
        }
    }

    @Test
    void messagesSentAtOnceStillKeepTheRequestLimit() throws Exception {
        for (int round = 0; round < 3; round++) {
            long writer = user("Người viết");
            long peer = user("Người nhận");
            chats.sendTo(principal(writer), peer, new SendRequest("Tin đầu"));
            List<Object> results = together(
                    () -> chats.sendTo(principal(writer), peer, new SendRequest("Tin A")),
                    () -> chats.sendTo(principal(writer), peer, new SendRequest("Tin B")),
                    () -> chats.sendTo(principal(writer), peer, new SendRequest("Tin C")),
                    () -> chats.sendTo(principal(writer), peer, new SendRequest("Tin D")));
            assertThat(results.stream().filter(r -> r instanceof ChatDtos.Sent)).hasSize(ChatService.PENDING_MAX - 1);
            assertThat(results.stream().filter(r -> r instanceof ApiException e
                    && e.getStatus() == HttpStatus.FORBIDDEN)).hasSize(4 - (ChatService.PENDING_MAX - 1));
            assertThat(count("SELECT COUNT(*) FROM chat_messages WHERE sender_id = ?", writer))
                    .isEqualTo(ChatService.PENDING_MAX);
        }
    }

    @Test
    void aBlockAndAMessageAtOnceNeverLetAMessageInAfterTheBlock() throws Exception {
        for (int round = 0; round < 5; round++) {
            long a = user("An");
            long b = user("Bình");
            long conversation = chats.sendTo(principal(a), b, new SendRequest("Chào")).conversationId();
            chats.send(principal(b), conversation, new SendRequest("Chào An"));
            List<Object> results = together(
                    () -> {
                        chats.block(principal(b), a);
                        return Instant.now();
                    },
                    () -> chats.send(principal(a), conversation, new SendRequest("Tin cuối")));
            Instant blockedBy = (Instant) results.get(0);
            if (results.get(1) instanceof ChatDtos.Sent sent) {
                assertThat(sent.message().createdAt()).isBefore(blockedBy);
            } else {
                assertThat(results.get(1)).isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private long user(String name) {
        return user(name, "USER", 30);
    }

    private long user(String name, String role, int daysOld) {
        String email = "chat-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, ?, ?, 'ACTIVE', TRUE, ?)""",
                email, name, role, Timestamp.from(Instant.now().minus(Duration.ofDays(daysOld))));
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        users.add(id);
        return id;
    }

    private ResultActions send(long from, long to, String text) throws Exception {
        return send(from, "USER", to, text);
    }

    private ResultActions send(long from, String role, long to, String text) throws Exception {
        return mvc.perform(withCsrf(post(API + "/with/" + to + "/messages"), from, role)
                .contentType(MediaType.APPLICATION_JSON).content(body(text)));
    }

    /** A message in an existing conversation (the id is the conversation's, not a member's). */
    private ResultActions sendIn(long from, long conversation, String text) throws Exception {
        return mvc.perform(withCsrf(post(API + "/" + conversation + "/messages"), from)
                .contentType(MediaType.APPLICATION_JSON).content(body(text)));
    }

    private ResultActions read(long user, long conversation, Long upTo) throws Exception {
        return mvc.perform(withCsrf(post(API + "/" + conversation + "/read"), user)
                .contentType(MediaType.APPLICATION_JSON).content("{\"upToId\":" + upTo + "}"))
                .andExpect(status().isOk());
    }

    private ResultActions report(long user, long message, String content) throws Exception {
        return mvc.perform(withCsrf(post(API + "/messages/" + message + "/report"), user)
                .contentType(MediaType.APPLICATION_JSON).content(content));
    }

    private String body(String text) throws Exception {
        return json.writeValueAsString(Map.of("content", text));
    }

    private static long sentConversation(ResultActions result) throws Exception {
        String body = result.andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(body, "$.data.conversationId")).longValue();
    }

    private static long sentId(ResultActions result) throws Exception {
        String body = result.andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(body, "$.data.message.id")).longValue();
    }

    private long latestMessage(long conversation) {
        return jdbc.queryForObject("SELECT MAX(id) FROM chat_messages WHERE conversation_id = ?", Long.class, conversation);
    }

    private long conversationOf(long a, long b) {
        return jdbc.queryForObject("""
                SELECT id FROM chat_conversations WHERE user_low_id = LEAST(?, ?) AND user_high_id = GREATEST(?, ?)""",
                Long.class, a, b, a, b);
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private static AppUserPrincipal principal(long userId) {
        return new AppUserPrincipal(userId, "u" + userId + "@test.local", "Chat Test", "USER", "/");
    }

    private static MockHttpSession session(long userId, String role) {
        AppUserPrincipal me = new AppUserPrincipal(userId, "u" + userId + "@test.local", "Chat Test", role, "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))));
        return session;
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request, long userId) {
        return withCsrf(request, userId, "USER");
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request, long userId,
                                                          String role) {
        return request.session(session(userId, role)).cookie(new Cookie("XSRF-TOKEN", CSRF))
                .header("X-XSRF-TOKEN", CSRF);
    }

    /** Runs the calls on separate threads at the same moment; each result is the call's value or its exception. */
    @SafeVarargs
    private static List<Object> together(Callable<Object>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return call.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
