package com.sep490.vsa.feature.chat;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.live.LiveEvents;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.common.text.VietnameseText;
import com.sep490.vsa.feature.chat.ChatDtos.Envelope;
import com.sep490.vsa.feature.chat.ChatDtos.Message;
import com.sep490.vsa.feature.chat.ChatDtos.Notice;
import com.sep490.vsa.feature.chat.ChatDtos.SendRequest;
import com.sep490.vsa.feature.chat.ChatDtos.Sent;
import com.sep490.vsa.feature.chat.ChatRepository.Account;
import com.sep490.vsa.feature.chat.ChatRepository.ConversationRow;
import com.sep490.vsa.feature.chat.ChatRepository.FileRow;
import com.sep490.vsa.feature.chat.ChatRepository.KeyRow;
import com.sep490.vsa.feature.chat.ChatRepository.MemberRow;
import com.sep490.vsa.feature.chat.ChatRepository.MessageRow;
import com.sep490.vsa.feature.media.MediaStorage;
import com.sep490.vsa.feature.moderation.ContentFilter;
import com.sep490.vsa.feature.moderation.ContentFilter.LinkProblem;
import com.sep490.vsa.feature.report.ContentReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of sending in "Tin nhắn" ({@link ChatService#sendTo} — the first message to a member, or the next one —
 * and {@link ChatService#send} — a message in a conversation), written from CLAUDE.md "Tin nhắn": who may write to whom
 * (ACTIVE Members only; locked, deleted and staff accounts and blocks either way refuse), one conversation per pair, the
 * first message as a request with {@link ChatService#PENDING_MAX} messages until the other member accepts or answers,
 * a decline seen as WAITING_FULL, text of at most 2,000 characters, banned keywords (MSG36), links and contacts
 * (BR-22), new conversations a Japan day (20, or 5 for accounts younger than {@code new_account_days}), 30 messages a
 * minute, the same long text in at most 3 other conversations in 24 hours, the order of these checks, the end-to-end
 * encrypted mode (envelopes only, current key ids, photos / one video / one voice message), quoted replies, the
 * {@link ChatMessageSent} event and the live {@code chat} announcements.
 * <p>
 * The database is not used: {@link ChatRepository}, {@link ContentFilter}, {@link ContentReportService},
 * {@link SystemSettings}, {@link MediaStorage}, {@link ApplicationEventPublisher} and {@link LiveEvents} are mocks;
 * {@link ChatTyping} and {@link ChatViewers} are the real in-memory classes; the clock is fixed at 20/11/2026 12:00
 * Japan time. Member Minh (id 10) writes to member Lan (id 20); their conversation is 500 and a new message gets id 901.
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("ChatService – Tin nhắn: gửi tin nhắn, lời mời, lọc nội dung, giới hạn, mã hóa đầu cuối (CHAT)")
class ChatServiceSendTest {

    private static final long ME_ID = 10L;
    private static final long PEER_ID = 20L;
    private static final long OUTSIDER_ID = 30L;
    private static final long CONV_ID = 500L;
    private static final long NEW_MSG = 901L;
    private static final long MY_KEY = 71L;
    private static final long PEER_KEY = 72L;
    /** 12:00 in Japan, 20/11/2026: the Japan day runs from 19/11 15:00 UTC to 20/11 15:00 UTC. */
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);

    private static final String ACCEPTED = "ACCEPTED";
    private static final String REQUEST = "REQUEST";
    private static final String DECLINED = "DECLINED";

    private static final String GUEST = "Bạn cần đăng nhập để nhắn tin.";
    private static final String MSG18 = "Bạn không có quyền thực hiện thao tác này.";
    private static final String EMPTY = "Hãy nhập tin nhắn.";
    private static final String TOO_LONG = "Tin nhắn tối đa 2.000 ký tự.";
    private static final String CONVERSATION_NOT_FOUND = "Không tìm thấy cuộc trò chuyện này.";
    private static final String MEMBER_NOT_FOUND = "Không tìm thấy thành viên này.";
    private static final String UNAVAILABLE = "Bạn không thể nhắn tin cho thành viên này.";
    private static final String WAITING_FULL = "Lan chưa nhận lời mời nhắn tin của bạn. Bạn gửi thêm được khi Lan trả lời.";
    private static final String TOO_FAST = "Bạn gửi tin nhắn hơi nhanh. Vui lòng đợi một lát rồi thử lại.";
    private static final String REPEATED =
            "Bạn đã gửi nội dung này cho nhiều người. Hãy viết tin nhắn riêng cho từng người.";
    private static final String FILES_NEED_E2EE =
            "Chỉ gửi được ảnh, video, ghi âm trong cuộc trò chuyện đã mã hóa đầu cuối.";
    private static final String NOT_ENCRYPTED = "Cuộc trò chuyện này chưa được mã hóa đầu cuối. Hãy tải lại trang.";
    private static final String ENCRYPTED_ONLY = "Cuộc trò chuyện này đã được mã hóa đầu cuối. Hãy tải lại trang.";
    private static final String KEY_CHANGED = "Khóa mã hóa vừa thay đổi. Hãy gửi lại.";
    private static final String BAD_ENVELOPE = "Tin nhắn mã hóa không hợp lệ. Hãy tải lại trang.";
    private static final String BAD_FILES = "Tệp đính kèm không hợp lệ hoặc đã được gửi.";
    private static final String REPLY_GONE = "Tin nhắn bạn trả lời không còn nữa.";
    private static final String KEYWORD = "việc nhẹ lương cao";
    private static final String KEYWORD_TEXT = "Bên mình có việc nhẹ lương cao, em làm không?";
    private static final String MSG36 =
            "Chưa gửi được: tin nhắn chứa từ khóa không được phép “việc nhẹ lương cao”. Hãy sửa rồi gửi lại.";
    /** 55 letters and digits once squashed: long enough for the repeated-text rule. */
    private static final String AD = "Chào bạn, mình có khóa học tiếng Nhật online giá rẻ, bạn quan tâm không?";
    private static final List<String> STAFF_ROLES = List.of("MODERATOR", "ACADEMIC_MANAGER", "ADMIN");

    private static final AppUserPrincipal ME = principal(ME_ID, "USER");
    private static final ConversationRow CONVERSATION = new ConversationRow(CONV_ID, ME_ID, PEER_ID, ME_ID);

    private ChatRepository repository;
    private ContentFilter filter;
    private ContentReportService reports;
    private SystemSettings settings;
    private MediaStorage storage;
    private ChatTyping typing;
    private ChatViewers viewers;
    private ApplicationEventPublisher events;
    private LiveEvents live;
    private ChatService service;

    @BeforeEach
    void setUp() {
        repository = mock(ChatRepository.class);
        filter = mock(ContentFilter.class);
        reports = mock(ContentReportService.class);
        settings = mock(SystemSettings.class);
        storage = mock(MediaStorage.class);
        events = mock(ApplicationEventPublisher.class);
        live = mock(LiveEvents.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        typing = new ChatTyping(clock);
        viewers = new ChatViewers(clock);
        service = new ChatService(repository, filter, reports, settings, storage, typing, viewers, events, live, clock);

        givenAccount(account(ME_ID, "Minh", "USER", "ACTIVE", NOW.minus(Duration.ofDays(30))));
        givenPeer("USER", "ACTIVE");
        when(repository.conversation(CONV_ID)).thenReturn(Optional.of(CONVERSATION));
        when(repository.lockConversation(CONV_ID)).thenReturn(Optional.of(CONVERSATION));
        when(repository.conversationOf(ME_ID, PEER_ID)).thenReturn(Optional.of(CONVERSATION));
        when(repository.lockConversationOf(ME_ID, PEER_ID)).thenReturn(Optional.of(CONVERSATION));
        givenStates(ACCEPTED, ACCEPTED);
        when(repository.insertMessage(anyLong(), anyLong(), any(), any(), any(), any())).thenReturn(NEW_MSG);
        when(repository.counts(anyLong())).thenReturn(new int[] {0, 0});
        when(settings.getInt(SystemSettings.NEW_ACCOUNT_DAYS, 7)).thenReturn(7);
    }

    // ---------------------------------------------------------------- helpers

    private static AppUserPrincipal principal(long id, String role) {
        return new AppUserPrincipal(id, "user" + id + "@example.org", "User " + id, role, "/");
    }

    private static Account account(long id, String name, String role, String status, Instant createdAt) {
        return new Account(id, name, null, role, status, createdAt);
    }

    private void givenAccount(Account account) {
        when(repository.account(account.id())).thenReturn(Optional.of(account));
    }

    /** Minh's account, {@code age} old. */
    private void givenMyAge(Duration age) {
        givenAccount(account(ME_ID, "Minh", "USER", "ACTIVE", age == null ? null : NOW.minus(age)));
    }

    private void givenPeer(String role, String status) {
        givenAccount(account(PEER_ID, "Lan", role, status, NOW.minus(Duration.ofDays(100))));
    }

    private static MemberRow row(String state) {
        return new MemberRow(state, 0, 0);
    }

    private void givenStates(String mine, String theirs) {
        when(repository.member(CONV_ID, ME_ID)).thenReturn(Optional.of(row(mine)));
        when(repository.member(CONV_ID, PEER_ID)).thenReturn(Optional.of(row(theirs)));
    }

    /** Minh has never written to Lan: the first message makes conversation 500, Minh ACCEPTED and Lan REQUEST. */
    private void givenNewConversation() {
        when(repository.lockConversationOf(ME_ID, PEER_ID)).thenReturn(Optional.empty());
        when(repository.conversationOf(ME_ID, PEER_ID)).thenReturn(Optional.empty());
        when(repository.insertConversation(ME_ID, PEER_ID, NOW)).thenReturn(CONV_ID);
        givenStates(ACCEPTED, REQUEST);
        when(repository.countMessagesBy(CONV_ID, ME_ID)).thenReturn(1);
    }

    /** Both accepted and both have a current key: the conversation is E2EE. */
    private void givenEncrypted() {
        givenStates(ACCEPTED, ACCEPTED);
        givenKeys(true, true);
    }

    private void givenKeys(boolean mine, boolean theirs) {
        if (mine) {
            when(repository.currentKey(ME_ID)).thenReturn(Optional.of(key(MY_KEY, ME_ID)));
        }
        if (theirs) {
            when(repository.currentKey(PEER_ID)).thenReturn(Optional.of(key(PEER_KEY, PEER_ID)));
        }
    }

    private static KeyRow key(long id, long userId) {
        return new KeyRow(id, userId, "BPublicKey" + id, "wrapped", "salt", "iv", 600_000,
                NOW.minus(Duration.ofDays(2)), null);
    }

    private static String b64(int bytes) {
        return Base64.getEncoder().encodeToString(new byte[bytes]);
    }

    /** An envelope in the agreed form: 64-byte ciphertext, 12-byte iv, lower-case hex franking tag. */
    private static Envelope envelope(long senderKey, long recipientKey) {
        return new Envelope(b64(64), b64(12), senderKey, recipientKey, "ab".repeat(32));
    }

    private static SendRequest sealed(Envelope envelope, List<Long> fileIds) {
        return new SendRequest(null, envelope, fileIds, null);
    }

    private static FileRow file(long id, String kind) {
        return new FileRow(id, CONV_ID, ME_ID, null, kind, "chat/2026/11/" + id + ".bin", 2048,
                NOW.minusSeconds(30), false);
    }

    private void givenAttachable(FileRow... files) {
        when(repository.lockAttachableFiles(eq(CONV_ID), eq(ME_ID), any())).thenReturn(List.of(files));
    }

    /** A plain message Minh sent earlier in another conversation. */
    private static MessageRow earlier(long id, long conversationId, String content) {
        return new MessageRow(id, conversationId, ME_ID, content, null, null, null, null, null, null, null, null,
                NOW.minus(Duration.ofHours(3)));
    }

    private static MessageRow message(long id, long conversationId, long sender, Instant recalledAt) {
        return new MessageRow(id, conversationId, sender, "Quán nào gần ga Nippori?", null, null, null, null, null,
                null, null, recalledAt, NOW.minus(Duration.ofHours(1)));
    }

    private Sent sendTo(String text) {
        return service.sendTo(ME, PEER_ID, new SendRequest(text));
    }

    private Sent sendIn(String text) {
        return service.send(ME, CONV_ID, new SendRequest(text));
    }

    private static Map<String, Object> event(String kind, Long conversationId, Long messageId) {
        Map<String, Object> data = new HashMap<>();
        data.put("kind", kind);
        if (conversationId != null) {
            data.put("conversationId", conversationId);
        }
        if (messageId != null) {
            data.put("messageId", messageId);
        }
        return data;
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

    /** Staff roles (MODERATOR, ACADEMIC_MANAGER, ADMIN) and a LOCKED Member: 403 MSG18 each time. */
    private void assertStaffAndLockedRefused(java.util.function.Consumer<AppUserPrincipal> call) {
        for (String role : STAFF_ROLES) {
            givenAccount(account(ME_ID, "Staff", role, "ACTIVE", NOW.minus(Duration.ofDays(30))));
            AppUserPrincipal staff = principal(ME_ID, role);
            assertApiError(() -> call.accept(staff), HttpStatus.FORBIDDEN, MSG18);
        }
        givenAccount(account(ME_ID, "Minh", "USER", "LOCKED", NOW.minus(Duration.ofDays(30))));
        assertApiError(() -> call.accept(ME), HttpStatus.FORBIDDEN, MSG18);
    }

    /** Nothing was written, published or announced. */
    private void verifyNothingSent() {
        verify(repository, never()).insertConversation(anyLong(), anyLong(), any());
        verify(repository, never()).insertMessage(anyLong(), anyLong(), any(), any(), any(), any());
        verify(repository, never()).attachFiles(anyLong(), any());
        verify(repository, never()).setState(anyLong(), anyLong(), anyString(), any());
        verify(repository, never()).markRead(anyLong(), anyLong(), anyLong());
        verifyNoInteractions(events);
        verify(live, never()).publish(anyLong(), anyString(), any());
    }

    private String savedContent() {
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(repository).insertMessage(eq(CONV_ID), eq(ME_ID), content.capture(), any(), any(), eq(NOW));
        return content.getValue();
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("08. sendTo – CHAT Tin nhắn đầu tiên / lời mời, ai nhắn được cho ai (UTC-106 → UTC-130)")
    class FirstMessage {

        @Test
        @DisplayName("UTC-106 Tin đầu tiên cho Lan: tạo cuộc trò chuyện 500, lưu chữ đã cắt khoảng trắng, còn gửi được 2 tin")
        void utc106_firstMessageStartsARequest() {
            givenNewConversation();

            Sent sent = sendTo("  Chào Lan, quanh ga Nippori ăn ở đâu rẻ ạ?  ");

            assertThat(sent.conversationId()).isEqualTo(CONV_ID);
            assertThat(sent.message()).isEqualTo(new Message(NEW_MSG, true, "Chào Lan, quanh ga Nippori ăn ở đâu rẻ ạ?",
                    null, null, false, false, List.of(), List.of(), NOW));
            assertThat(sent.status().state()).isEqualTo(ACCEPTED);
            assertThat(sent.status().canSend()).isTrue();
            assertThat(sent.status().notice()).isEqualTo(new Notice("WAITING", "Lan chưa nhận lời mời nhắn tin của bạn."
                    + " Bạn còn gửi được 2 tin nhắn trước khi Lan trả lời."));
            verify(repository).insertConversation(ME_ID, PEER_ID, NOW);
            verify(repository).insertMessage(CONV_ID, ME_ID, "Chào Lan, quanh ga Nippori ăn ở đâu rẻ ạ?", null, null,
                    NOW);
        }

        @Test
        @DisplayName("UTC-107 Thứ tự ghi: khóa dòng ngày của Minh → khóa cặp hội thoại → tạo hội thoại → lưu tin → đánh dấu đã đọc")
        void utc107_dayRowIsLockedBeforeTheConversation() {
            givenNewConversation();

            sendTo("Chào Lan, quanh ga Nippori ăn ở đâu rẻ ạ?");

            InOrder order = inOrder(repository);
            order.verify(repository).lockUserDay(ME_ID, TODAY);
            order.verify(repository).lockConversationOf(ME_ID, PEER_ID);
            order.verify(repository).insertConversation(ME_ID, PEER_ID, NOW);
            order.verify(repository).insertMessage(CONV_ID, ME_ID, "Chào Lan, quanh ga Nippori ăn ở đâu rẻ ạ?", null,
                    null, NOW);
            order.verify(repository).markRead(CONV_ID, ME_ID, NEW_MSG);
            verify(repository, never()).setState(anyLong(), anyLong(), anyString(), any());
        }

        @Test
        @DisplayName("UTC-108 Phát sự kiện ChatMessageSent cho thông báo: người gửi Minh, người nhận Lan, chưa mã hóa, là lời mời")
        void utc108_firstMessagePublishesTheEvent() {
            givenNewConversation();

            sendTo("Chào Lan, quanh ga Nippori ăn ở đâu rẻ ạ?");

            verify(events).publishEvent(new ChatMessageSent(CONV_ID, NEW_MSG, ME_ID, "Minh", PEER_ID, false, true));
        }

        @Test
        @DisplayName("UTC-109 Báo \"message\" (chỉ id) tới các trang của Minh và của Lan")
        void utc109_firstMessageIsAnnouncedToBothMembers() {
            givenNewConversation();

            sendTo("Chào Lan, quanh ga Nippori ăn ở đâu rẻ ạ?");

            verify(live).publish(ME_ID, "chat", event("message", CONV_ID, NEW_MSG));
            verify(live).publish(PEER_ID, "chat", event("message", CONV_ID, NEW_MSG));
        }

        @Test
        @DisplayName("UTC-110 Thân rỗng, nội dung null, \"\", chỉ khoảng trắng: 400 \"Hãy nhập tin nhắn.\", không khóa gì (4 lần)")
        void utc110_emptyTextIsRefused() {
            assertApiError(() -> service.sendTo(ME, PEER_ID, null), HttpStatus.BAD_REQUEST, EMPTY);
            for (String text : Arrays.asList(null, "", "  \n\t ")) {
                assertApiError(() -> sendTo(text), HttpStatus.BAD_REQUEST, EMPTY);
            }

            verify(repository, never()).lockUserDay(anyLong(), any());
            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-111 Tin đúng 2.000 ký tự: gửi được")
        void utc111_twoThousandCharactersAreAllowed() {
            sendTo("a".repeat(2000));

            assertThat(savedContent()).hasSize(2000);
        }

        @Test
        @DisplayName("UTC-112 Tin 2.001 ký tự: 400 \"Tin nhắn tối đa 2.000 ký tự.\"")
        void utc112_twoThousandAndOneCharactersAreRefused() {
            assertApiError(() -> sendTo("a".repeat(2001)), HttpStatus.BAD_REQUEST, TOO_LONG);

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-113 2.000 ký tự kèm khoảng trắng hai đầu: cắt khoảng trắng rồi mới đếm, gửi được")
        void utc113_surroundingSpacesAreNotCounted() {
            sendTo("   " + "b".repeat(2000) + "\n ");

            assertThat(savedContent()).isEqualTo("b".repeat(2000));
        }

        @Test
        @DisplayName("UTC-114 Nhắn cho chính mình: 400 \"Bạn không thể nhắn tin cho chính mình.\"")
        void utc114_selfIsRefused() {
            assertApiError(() -> service.sendTo(ME, ME_ID, new SendRequest("Tự nhắn")), HttpStatus.BAD_REQUEST,
                    "Bạn không thể nhắn tin cho chính mình.");

            verify(repository, never()).lockUserDay(anyLong(), any());
            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-115 Thành viên không tồn tại (id 999): 404 \"Không tìm thấy thành viên này.\"")
        void utc115_unknownMemberIsNotFound() {
            assertApiError(() -> service.sendTo(ME, 999L, new SendRequest("Chào")), HttpStatus.NOT_FOUND,
                    MEMBER_NOT_FOUND);

            verify(repository, never()).lockUserDay(anyLong(), any());
            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-116 Tài khoản đã xóa, chưa từng nhắn: 404 \"Không tìm thấy thành viên này.\", không tạo hội thoại")
        void utc116_deletedMemberWithoutConversationIsNotFound() {
            givenNewConversation();
            givenPeer("USER", "DELETED");

            assertApiError(() -> sendTo("Chào Lan"), HttpStatus.NOT_FOUND, MEMBER_NOT_FOUND);

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-117 Tài khoản đã xóa, đã có cuộc trò chuyện: 400 \"Tài khoản này đã bị xóa, không nhắn tin được nữa.\"")
        void utc117_deletedMemberWithConversationIsRefused() {
            givenPeer("USER", "DELETED");

            assertApiError(() -> sendTo("Lan ơi"), HttpStatus.BAD_REQUEST,
                    "Tài khoản này đã bị xóa, không nhắn tin được nữa.");

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-118 Tin đầu tiên cho thành viên bị khóa hoặc cho Moderator: 403 UNAVAILABLE (2 lần)")
        void utc118_lockedOrStaffMemberIsUnavailable() {
            givenNewConversation();
            for (String[] peer : new String[][] {{"USER", "LOCKED"}, {"MODERATOR", "ACTIVE"}}) {
                givenPeer(peer[0], peer[1]);
                assertApiError(() -> sendTo("Chào bạn"), HttpStatus.FORBIDDEN, UNAVAILABLE);
            }

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-119 Lan đã chặn Minh: 403 UNAVAILABLE (cùng câu như tài khoản bị khóa)")
        void utc119_blockedByThePeerIsUnavailable() {
            when(repository.blocked(PEER_ID, ME_ID)).thenReturn(true);

            assertApiError(() -> sendTo("Sao vậy?"), HttpStatus.FORBIDDEN, UNAVAILABLE);

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-120 Minh đã chặn Lan: 400 \"Bạn đã chặn Lan. Bỏ chặn để nhắn tin tiếp.\"")
        void utc120_blockedByMeIsRefused() {
            when(repository.blocked(ME_ID, PEER_ID)).thenReturn(true);

            assertApiError(() -> sendTo("Thôi nhé"), HttpStatus.BAD_REQUEST, "Bạn đã chặn Lan. Bỏ chặn để nhắn tin tiếp.");

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-121 Khách: 401, không khóa gì")
        void utc121_guestIsRefused() {
            assertApiError(() -> service.sendTo(null, PEER_ID, new SendRequest("Chào")), HttpStatus.UNAUTHORIZED, GUEST);

            verify(repository, never()).lockUserDay(anyLong(), any());
            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-122 Người gửi là nhân viên (3 vai trò) hoặc thành viên bị khóa: 403 MSG18 (4 lần)")
        void utc122_staffAndLockedSendersAreRefused() {
            assertStaffAndLockedRefused(me -> service.sendTo(me, PEER_ID, new SendRequest("Chào bạn")));

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-123 Tin thứ 2 trong lời mời của mình (đã gửi 1): gửi được, không tạo hội thoại mới, còn 1 tin")
        void utc123_secondMessageOfOwnRequest() {
            givenStates(ACCEPTED, REQUEST);
            when(repository.countMessagesBy(CONV_ID, ME_ID)).thenReturn(1, 2);

            Sent sent = sendTo("Em mới sang được 1 ngày");

            assertThat(sent.conversationId()).isEqualTo(CONV_ID);
            assertThat(sent.status().notice().code()).isEqualTo("WAITING");
            assertThat(sent.status().notice().text()).contains("Bạn còn gửi được 1 tin nhắn");
            verify(repository, never()).insertConversation(anyLong(), anyLong(), any());
            verify(repository, never()).countStartedBetween(anyLong(), any(), any());
        }

        @Test
        @DisplayName("UTC-124 Tin thứ 3 trong lời mời (đã gửi 2): gửi được, sau đó WAITING_FULL, không gửi được nữa")
        void utc124_thirdMessageFillsTheRequest() {
            givenStates(ACCEPTED, REQUEST);
            when(repository.countMessagesBy(CONV_ID, ME_ID)).thenReturn(2, 3);

            Sent sent = sendTo("Em cảm ơn anh");

            assertThat(sent.message().id()).isEqualTo(NEW_MSG);
            assertThat(sent.status().canSend()).isFalse();
            assertThat(sent.status().notice()).isEqualTo(new Notice("WAITING_FULL", WAITING_FULL));
        }

        @Test
        @DisplayName("UTC-125 Tin thứ 4 khi Lan chưa trả lời (đã gửi 3): 403 WAITING_FULL")
        void utc125_fourthMessageIsRefused() {
            givenStates(ACCEPTED, REQUEST);
            when(repository.countMessagesBy(CONV_ID, ME_ID)).thenReturn(3);

            assertApiError(() -> sendTo("Anh ơi?"), HttpStatus.FORBIDDEN, WAITING_FULL);

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-126 Lan đã từ chối (Minh mới gửi 1 tin): 403 WAITING_FULL, Minh không biết bị từ chối")
        void utc126_declinedRequestStopsTheWriter() {
            givenStates(ACCEPTED, DECLINED);
            when(repository.countMessagesBy(CONV_ID, ME_ID)).thenReturn(1);

            assertApiError(() -> sendTo("Bạn ơi?"), HttpStatus.FORBIDDEN, WAITING_FULL);

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-127 Trả lời lời mời Lan gửi (Minh REQUEST): gửi được và tự chấp nhận lời mời")
        void utc127_answeringARequestAcceptsIt() {
            when(repository.member(CONV_ID, ME_ID)).thenReturn(Optional.of(row(REQUEST)), Optional.of(row(REQUEST)),
                    Optional.of(row(ACCEPTED)));

            Sent sent = sendTo("Gần ga có quán cơm Hana, cơm phần 700 yên nhé.");

            verify(repository).setState(CONV_ID, ME_ID, ACCEPTED, NOW);
            verify(events).publishEvent(new ChatMessageSent(CONV_ID, NEW_MSG, ME_ID, "Minh", PEER_ID, false, false));
            assertThat(sent.status().state()).isEqualTo(ACCEPTED);
            assertThat(sent.status().notice()).isNull();
        }

        @Test
        @DisplayName("UTC-128 Minh đã từ chối lời mời của Lan rồi nhắn lại: gửi được, trở lại ACCEPTED")
        void utc128_writingBackAfterDecliningAcceptsAgain() {
            when(repository.member(CONV_ID, ME_ID)).thenReturn(Optional.of(row(DECLINED)), Optional.of(row(DECLINED)),
                    Optional.of(row(ACCEPTED)));

            Sent sent = sendTo("Xin lỗi, giờ mình mới rảnh");

            assertThat(sent.conversationId()).isEqualTo(CONV_ID);
            verify(repository).setState(CONV_ID, ME_ID, ACCEPTED, NOW);
        }

        @Test
        @DisplayName("UTC-129 Lan nhắn cho Minh cùng lúc (trùng khóa cặp): tin của Minh vào chung cuộc trò chuyện 500")
        void utc129_bothWritingFirstAtOnceShareOneConversation() {
            when(repository.lockConversationOf(ME_ID, PEER_ID)).thenReturn(Optional.empty(),
                    Optional.of(CONVERSATION));
            when(repository.insertConversation(ME_ID, PEER_ID, NOW))
                    .thenThrow(new DuplicateKeyException("uq_chat_pair"));
            when(repository.member(CONV_ID, ME_ID)).thenReturn(Optional.of(row(REQUEST)), Optional.of(row(ACCEPTED)));

            Sent sent = sendTo("Chào Lan");

            assertThat(sent.conversationId()).isEqualTo(CONV_ID);
            verify(repository, times(2)).lockConversationOf(ME_ID, PEER_ID);
            verify(repository).insertMessage(CONV_ID, ME_ID, "Chào Lan", null, null, NOW);
            verify(repository).setState(CONV_ID, ME_ID, ACCEPTED, NOW);
        }

        @Test
        @DisplayName("UTC-130 Cuộc trò chuyện đã mã hóa đầu cuối: tin chữ thường qua \"Nhắn tin\" bị từ chối (400)")
        void utc130_plainTextToAnEncryptedConversationIsRefused() {
            givenEncrypted();

            assertApiError(() -> sendTo("Tin chữ thường"), HttpStatus.BAD_REQUEST, ENCRYPTED_ONLY);

            verifyNothingSent();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("09. sendTo – CHAT Lọc nội dung, giới hạn chống spam và thứ tự kiểm tra (UTC-131 → UTC-155)")
    class ContentAndLimits {

        @Test
        @DisplayName("UTC-131 Tin có từ khóa cấm \"việc nhẹ lương cao\": 400 MSG36, không tạo gì")
        void utc131_bannedKeywordIsRefused() {
            givenNewConversation();
            when(filter.bannedKeyword(KEYWORD_TEXT)).thenReturn(Optional.of(KEYWORD));

            assertApiError(() -> sendTo(KEYWORD_TEXT), HttpStatus.BAD_REQUEST, MSG36);

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-132 Link khi chưa có tag, số điện thoại/Zalo khi chưa có tag, link rút gọn: 400 BR-22 (3 lần)")
        void utc132_linksAndContactsFollowBr22() {
            givenNewConversation();
            Map<String, LinkProblem> cases = Map.of(
                    "Xem ở https://example.com/viec-lam nhé", LinkProblem.LINK_NEEDS_TAG,
                    "Nhắn Zalo cho chị 0901234567 nhé", LinkProblem.CONTACT_NEEDS_TAG,
                    "Đăng ký ở bit.ly/abc123", LinkProblem.SHORT_LINK);
            Map<LinkProblem, String> messages = Map.of(
                    LinkProblem.LINK_NEEDS_TAG, "Chỉ tài khoản đã có tag định danh mới được chèn link.",
                    LinkProblem.CONTACT_NEEDS_TAG, "Chỉ tài khoản đã có tag định danh mới được ghi số điện thoại hoặc"
                            + " tài khoản Zalo, LINE, Telegram.",
                    LinkProblem.SHORT_LINK, "Không dùng link rút gọn (bit.ly, tinyurl…). Hãy dán link đầy đủ.");
            cases.forEach((text, problem) -> {
                when(filter.linkProblem(text, ME_ID)).thenReturn(Optional.of(problem));
                assertApiError(() -> sendTo(text), HttpStatus.BAD_REQUEST, messages.get(problem));
            });

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-133 Vừa có từ khóa cấm vừa có link: báo từ khóa trước, không xét link")
        void utc133_keywordsAreCheckedBeforeLinks() {
            String text = "Việc nhẹ lương cao, xem https://example.com";
            when(filter.bannedKeyword(text)).thenReturn(Optional.of(KEYWORD));
            when(filter.linkProblem(text, ME_ID)).thenReturn(Optional.of(LinkProblem.LINK_NEEDS_TAG));

            assertApiError(() -> sendTo(text), HttpStatus.BAD_REQUEST, MSG36);

            verify(filter, never()).linkProblem(anyString(), anyLong());
        }

        @Test
        @DisplayName("UTC-134 Lan đã chặn Minh và tin có từ khóa cấm: báo UNAVAILABLE trước, không lọc nội dung")
        void utc134_relationshipIsCheckedBeforeContent() {
            when(repository.blocked(PEER_ID, ME_ID)).thenReturn(true);
            when(filter.bannedKeyword(KEYWORD_TEXT)).thenReturn(Optional.of(KEYWORD));

            assertApiError(() -> sendTo(KEYWORD_TEXT), HttpStatus.FORBIDDEN, UNAVAILABLE);

            verifyNoInteractions(filter);
        }

        @Test
        @DisplayName("UTC-135 Đã bắt đầu 19 cuộc trò chuyện mới hôm nay (ngày Nhật 19/11 15:00 – 20/11 15:00 UTC): gửi được")
        void utc135_nineteenNewConversationsToday() {
            givenNewConversation();
            when(repository.countStartedBetween(eq(ME_ID), any(), any())).thenReturn(19);

            sendTo("Chào Lan");

            ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
            ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
            verify(repository).countStartedBetween(eq(ME_ID), from.capture(), to.capture());
            assertThat(from.getValue()).isEqualTo(Instant.parse("2026-11-19T15:00:00Z"));
            assertThat(to.getValue()).isEqualTo(Instant.parse("2026-11-20T15:00:00Z"));
            verify(repository).insertConversation(ME_ID, PEER_ID, NOW);
        }

        @Test
        @DisplayName("UTC-136 Đã bắt đầu 20 cuộc trò chuyện mới hôm nay: 429")
        void utc136_twentyNewConversationsTodayIsTheLimit() {
            givenNewConversation();
            when(repository.countStartedBetween(eq(ME_ID), any(), any())).thenReturn(20);

            assertApiError(() -> sendTo("Chào Lan"), HttpStatus.TOO_MANY_REQUESTS,
                    "Bạn đã bắt đầu đủ 20 cuộc trò chuyện mới hôm nay. Vui lòng thử lại vào ngày mai.");

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-137 Tài khoản mới (3 ngày), đã bắt đầu 4 cuộc hôm nay: gửi được")
        void utc137_newAccountWithFourNewConversations() {
            givenNewConversation();
            givenMyAge(Duration.ofDays(3));
            when(repository.countStartedBetween(eq(ME_ID), any(), any())).thenReturn(4);

            sendTo("Chào Lan");

            verify(repository).insertConversation(ME_ID, PEER_ID, NOW);
        }

        @Test
        @DisplayName("UTC-138 Tài khoản mới (3 ngày), đã bắt đầu 5 cuộc hôm nay: 429, giới hạn của tài khoản mới (dưới 7 ngày)")
        void utc138_newAccountWithFiveNewConversationsIsRefused() {
            givenNewConversation();
            givenMyAge(Duration.ofDays(3));
            when(repository.countStartedBetween(eq(ME_ID), any(), any())).thenReturn(5);

            assertApiError(() -> sendTo("Chào Lan"), HttpStatus.TOO_MANY_REQUESTS, "Bạn đã bắt đầu đủ 5 cuộc trò chuyện"
                    + " mới hôm nay, giới hạn của tài khoản mới (dưới 7 ngày). Vui lòng thử lại vào ngày mai.");

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-139 Tài khoản tạo đúng 7 ngày trước, đã bắt đầu 5 cuộc: không còn là tài khoản mới, gửi được")
        void utc139_accountOfExactlySevenDaysIsNotNew() {
            givenNewConversation();
            givenMyAge(Duration.ofDays(7));
            when(repository.countStartedBetween(eq(ME_ID), any(), any())).thenReturn(5);

            sendTo("Chào Lan");

            verify(repository).insertConversation(ME_ID, PEER_ID, NOW);
        }

        @Test
        @DisplayName("UTC-140 Tài khoản tạo 6 ngày 23:59:59 trước, đã bắt đầu 5 cuộc: vẫn là tài khoản mới, 429")
        void utc140_accountOneSecondYoungerThanSevenDaysIsNew() {
            givenNewConversation();
            givenMyAge(Duration.ofDays(7).minusSeconds(1));
            when(repository.countStartedBetween(eq(ME_ID), any(), any())).thenReturn(5);

            ApiException e = apiError(() -> sendTo("Chào Lan"));

            assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(e.getMessage()).contains("giới hạn của tài khoản mới (dưới 7 ngày)");
        }

        @Test
        @DisplayName("UTC-141 Admin đặt new_account_days = 14, tài khoản 10 ngày, đã bắt đầu 5 cuộc: 429 (dưới 14 ngày)")
        void utc141_newAccountDaysComesFromTheSettings() {
            givenNewConversation();
            when(settings.getInt(SystemSettings.NEW_ACCOUNT_DAYS, 7)).thenReturn(14);
            givenMyAge(Duration.ofDays(10));
            when(repository.countStartedBetween(eq(ME_ID), any(), any())).thenReturn(5);

            assertApiError(() -> sendTo("Chào Lan"), HttpStatus.TOO_MANY_REQUESTS, "Bạn đã bắt đầu đủ 5 cuộc trò chuyện"
                    + " mới hôm nay, giới hạn của tài khoản mới (dưới 14 ngày). Vui lòng thử lại vào ngày mai.");
        }

        @Test
        @DisplayName("UTC-142 Tài khoản không có ngày tạo, đã bắt đầu 5 cuộc: tính như tài khoản thường, gửi được")
        void utc142_accountWithoutCreationDateIsNotNew() {
            givenNewConversation();
            givenMyAge(null);
            when(repository.countStartedBetween(eq(ME_ID), any(), any())).thenReturn(5);

            sendTo("Chào Lan");

            verify(repository).insertConversation(ME_ID, PEER_ID, NOW);
        }

        @Test
        @DisplayName("UTC-143 Nhắn vào cuộc trò chuyện đã có (đã bắt đầu 20 cuộc hôm nay): không tính là cuộc mới, gửi được")
        void utc143_existingConversationIsNotANewOne() {
            when(repository.countStartedBetween(eq(ME_ID), any(), any())).thenReturn(20);

            sendTo("Lan ơi, mai đi Ueno không?");

            verify(repository, never()).countStartedBetween(anyLong(), any(), any());
            verify(repository).insertMessage(CONV_ID, ME_ID, "Lan ơi, mai đi Ueno không?", null, null, NOW);
        }

        @Test
        @DisplayName("UTC-144 Vừa có từ khóa cấm vừa hết lượt cuộc mới: báo từ khóa trước, không đếm cuộc mới")
        void utc144_keywordsAreCheckedBeforeTheNewConversationLimit() {
            givenNewConversation();
            when(filter.bannedKeyword(KEYWORD_TEXT)).thenReturn(Optional.of(KEYWORD));
            when(repository.countStartedBetween(eq(ME_ID), any(), any())).thenReturn(20);

            assertApiError(() -> sendTo(KEYWORD_TEXT), HttpStatus.BAD_REQUEST, MSG36);

            verify(repository, never()).countStartedBetween(anyLong(), any(), any());
        }

        @Test
        @DisplayName("UTC-145 29 tin trong 1 phút qua: gửi được (đếm từ 02:59:00 UTC)")
        void utc145_twentyNineMessagesInTheLastMinute() {
            when(repository.countMessagesSince(eq(ME_ID), any())).thenReturn(29);

            sendTo("Tin số 30");

            verify(repository).countMessagesSince(ME_ID, NOW.minusSeconds(60));
            verify(repository).insertMessage(CONV_ID, ME_ID, "Tin số 30", null, null, NOW);
        }

        @Test
        @DisplayName("UTC-146 30 tin trong 1 phút qua: 429 \"Bạn gửi tin nhắn hơi nhanh…\"")
        void utc146_thirtyMessagesInTheLastMinuteIsTheLimit() {
            when(repository.countMessagesSince(eq(ME_ID), any())).thenReturn(30);

            assertApiError(() -> sendTo("Thêm nữa"), HttpStatus.TOO_MANY_REQUESTS, TOO_FAST);

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-147 Hết lượt cuộc mới và gửi quá nhanh: báo giới hạn cuộc mới trước, không đếm tin trong phút")
        void utc147_newConversationLimitIsCheckedBeforeThePerMinuteLimit() {
            givenNewConversation();
            when(repository.countStartedBetween(eq(ME_ID), any(), any())).thenReturn(20);
            when(repository.countMessagesSince(eq(ME_ID), any())).thenReturn(30);

            ApiException e = apiError(() -> sendTo("Chào Lan"));

            assertThat(e.getMessage()).startsWith("Bạn đã bắt đầu đủ 20 cuộc trò chuyện mới hôm nay.");
            verify(repository, never()).countMessagesSince(anyLong(), any());
        }

        @Test
        @DisplayName("UTC-148 Cùng một quảng cáo đã gửi ở 3 cuộc trò chuyện khác (khác hoa thường, dấu): 400 \"Bạn đã gửi nội dung này cho nhiều người…\"")
        void utc148_sameTextInThreeOtherConversationsIsRefused() {
            givenNewConversation();
            when(repository.recentMessagesElsewhere(eq(ME_ID), any(), any(), anyInt())).thenReturn(List.of(
                    earlier(801, 601, AD.toUpperCase()),
                    earlier(802, 602, "chao ban, minh co khoa hoc tieng nhat online gia re, ban quan tam khong?"),
                    earlier(803, 603, "  " + AD + " ")));

            assertApiError(() -> sendTo(AD), HttpStatus.BAD_REQUEST, REPEATED);

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-149 Cùng quảng cáo mới gửi ở 2 cuộc khác: gửi được (cuộc thứ 3)")
        void utc149_sameTextInTwoOtherConversationsIsAllowed() {
            givenNewConversation();
            when(repository.recentMessagesElsewhere(eq(ME_ID), any(), any(), anyInt())).thenReturn(List.of(
                    earlier(801, 601, AD), earlier(802, 602, AD)));

            sendTo(AD);

            assertThat(savedContent()).isEqualTo(AD);
        }

        @Test
        @DisplayName("UTC-150 Cùng quảng cáo 3 lần nhưng chỉ trong 2 cuộc khác: gửi được (đếm theo cuộc trò chuyện)")
        void utc150_repeatsAreCountedPerConversation() {
            givenNewConversation();
            when(repository.recentMessagesElsewhere(eq(ME_ID), any(), any(), anyInt())).thenReturn(List.of(
                    earlier(801, 601, AD), earlier(802, 601, AD), earlier(803, 602, AD)));

            sendTo(AD);

            assertThat(savedContent()).isEqualTo(AD);
        }

        @Test
        @DisplayName("UTC-151 Tin đúng 30 chữ/số (sau khi bỏ dấu, dấu câu) đã gửi ở 3 cuộc khác: bị từ chối")
        void utc151_thirtyLettersAreEnoughForTheRepeatRule() {
            String text = "Mình muốn hỏi về việc làm thêm ở Tokyo";
            assertThat(VietnameseText.squash(text)).hasSize(30);
            givenNewConversation();
            when(repository.recentMessagesElsewhere(eq(ME_ID), any(), any(), anyInt())).thenReturn(List.of(
                    earlier(801, 601, text), earlier(802, 602, text), earlier(803, 603, text)));

            assertApiError(() -> sendTo(text), HttpStatus.BAD_REQUEST, REPEATED);
        }

        @Test
        @DisplayName("UTC-152 Tin 29 chữ/số: không xét lặp lại, gửi được")
        void utc152_twentyNineLettersAreNotChecked() {
            String text = "Mình muốn hỏi về việc làm thêm Tokyo";
            assertThat(VietnameseText.squash(text)).hasSize(29);
            givenNewConversation();
            when(repository.recentMessagesElsewhere(eq(ME_ID), any(), any(), anyInt())).thenReturn(List.of(
                    earlier(801, 601, text), earlier(802, 602, text), earlier(803, 603, text)));

            sendTo(text);

            verify(repository, never()).recentMessagesElsewhere(anyLong(), any(), any(), anyInt());
            assertThat(savedContent()).isEqualTo(text);
        }

        @Test
        @DisplayName("UTC-153 Tin đầu tiên: so với 500 tin chữ mới nhất trong 24 giờ qua ở mọi cuộc khác")
        void utc153_repeatWindowOfANewConversation() {
            givenNewConversation();

            sendTo(AD);

            verify(repository).recentMessagesElsewhere(ME_ID, null, NOW.minus(Duration.ofHours(24)), 500);
        }

        @Test
        @DisplayName("UTC-154 Gửi quá nhanh và lặp lại: báo gửi quá nhanh trước, không xét lặp lại")
        void utc154_perMinuteLimitIsCheckedBeforeRepeats() {
            when(repository.countMessagesSince(eq(ME_ID), any())).thenReturn(30);
            when(repository.recentMessagesElsewhere(eq(ME_ID), any(), any(), anyInt())).thenReturn(List.of(
                    earlier(801, 601, AD), earlier(802, 602, AD), earlier(803, 603, AD)));

            assertApiError(() -> sendTo(AD), HttpStatus.TOO_MANY_REQUESTS, TOO_FAST);

            verify(repository, never()).recentMessagesElsewhere(anyLong(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("UTC-155 Ba cuộc khác có tin dài nhưng khác nội dung: gửi được")
        void utc155_otherTextsDoNotCount() {
            givenNewConversation();
            when(repository.recentMessagesElsewhere(eq(ME_ID), any(), any(), anyInt())).thenReturn(List.of(
                    earlier(801, 601, "Chào bạn, mình có khóa học tiếng Hàn online giá rẻ, bạn quan tâm không?"),
                    earlier(802, 602, "Chào bạn, mình có lớp học tiếng Nhật offline giá rẻ, bạn quan tâm không?"),
                    earlier(803, 603, "Chào bạn, mình có khóa học tiếng Nhật online miễn phí, bạn quan tâm nhé?")));

            sendTo(AD);

            assertThat(savedContent()).isEqualTo(AD);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("10. send – CHAT Gửi tin trong một cuộc trò chuyện (UTC-156 → UTC-174)")
    class SendInConversation {

        @Test
        @DisplayName("UTC-156 Cuộc trò chuyện đã nhận lời: lưu tin, gửi tiếp được, không còn thông báo")
        void utc156_messageInAnAcceptedConversation() {
            Sent sent = sendIn("Mai gặp ở ga Ueno nhé");

            assertThat(sent).isEqualTo(new Sent(CONV_ID, new Message(NEW_MSG, true, "Mai gặp ở ga Ueno nhé", null, null,
                    false, false, List.of(), List.of(), NOW), sent.status()));
            assertThat(sent.status().canSend()).isTrue();
            assertThat(sent.status().notice()).isNull();
            assertThat(sent.status().encryption().mode()).isEqualTo("PLAIN");
            InOrder order = inOrder(repository);
            order.verify(repository).lockUserDay(ME_ID, TODAY);
            order.verify(repository).lockConversationOf(ME_ID, PEER_ID);
            order.verify(repository).insertMessage(CONV_ID, ME_ID, "Mai gặp ở ga Ueno nhé", null, null, NOW);
            verify(repository, never()).insertConversation(anyLong(), anyLong(), any());
            verify(repository, never()).countStartedBetween(anyLong(), any(), any());
            verify(repository, never()).setState(anyLong(), anyLong(), anyString(), any());
        }

        @Test
        @DisplayName("UTC-157 Phát ChatMessageSent (không phải lời mời) và báo \"message\" tới cả hai")
        void utc157_messageIsPublishedAndAnnounced() {
            sendIn("Mai gặp ở ga Ueno nhé");

            verify(events).publishEvent(new ChatMessageSent(CONV_ID, NEW_MSG, ME_ID, "Minh", PEER_ID, false, false));
            verify(live).publish(ME_ID, "chat", event("message", CONV_ID, NEW_MSG));
            verify(live).publish(PEER_ID, "chat", event("message", CONV_ID, NEW_MSG));
        }

        @Test
        @DisplayName("UTC-158 Minh đang soạn tin rồi gửi: hết \"đang soạn tin\" ngay")
        void utc158_sendingEndsTyping() {
            typing.ping(CONV_ID, ME_ID);

            sendIn("Mai gặp ở ga Ueno nhé");

            assertThat(typing.typing(CONV_ID, ME_ID)).isFalse();
        }

        @Test
        @DisplayName("UTC-159 Xét lặp lại: chỉ so với các cuộc trò chuyện khác cuộc 500")
        void utc159_repeatCheckLeavesThisConversationOut() {
            sendIn("Mai mình gặp nhau ở ga Ueno lúc bảy giờ nhé");

            verify(repository).recentMessagesElsewhere(ME_ID, CONV_ID, NOW.minus(Duration.ofHours(24)), 500);
        }

        @Test
        @DisplayName("UTC-160 Thân rỗng (null): 400 \"Hãy nhập tin nhắn.\", không đọc cuộc trò chuyện")
        void utc160_missingBodyIsRefused() {
            assertApiError(() -> service.send(ME, CONV_ID, null), HttpStatus.BAD_REQUEST, EMPTY);

            verify(repository, never()).conversation(anyLong());
            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-161 Nội dung \"\" hoặc chỉ khoảng trắng: 400 \"Hãy nhập tin nhắn.\" (2 lần)")
        void utc161_blankTextIsRefused() {
            for (String text : List.of("", "   \n ")) {
                assertApiError(() -> sendIn(text), HttpStatus.BAD_REQUEST, EMPTY);
            }

            verify(repository, never()).lockUserDay(anyLong(), any());
            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-162 Cuộc trò chuyện của người khác: 404, không khóa gì")
        void utc162_someoneElsesConversationIsNotFound() {
            when(repository.conversation(CONV_ID)).thenReturn(Optional.of(
                    new ConversationRow(CONV_ID, PEER_ID, OUTSIDER_ID, PEER_ID)));

            assertApiError(() -> sendIn("Xen vào"), HttpStatus.NOT_FOUND, CONVERSATION_NOT_FOUND);

            verify(repository, never()).lockUserDay(anyLong(), any());
            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-163 Cuộc trò chuyện không tồn tại: 404")
        void utc163_missingConversationIsNotFound() {
            when(repository.conversation(CONV_ID)).thenReturn(Optional.empty());

            assertApiError(() -> sendIn("Chào"), HttpStatus.NOT_FOUND, CONVERSATION_NOT_FOUND);

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-164 Trả lời tin 850 đang hiện với mình: lưu replyToId = 850")
        void utc164_quotedReplyIsSaved() {
            when(repository.member(CONV_ID, ME_ID)).thenReturn(Optional.of(new MemberRow(ACCEPTED, 40, 40)));
            when(repository.message(850)).thenReturn(Optional.of(message(850, CONV_ID, PEER_ID, null)));
            when(repository.shown(850, 40)).thenReturn(true);

            Sent sent = service.send(ME, CONV_ID, new SendRequest("Quán Hana nhé", null, null, 850L));

            assertThat(sent.message().replyToId()).isEqualTo(850L);
            verify(repository).insertMessage(CONV_ID, ME_ID, "Quán Hana nhé", null, 850L, NOW);
            verify(repository).shown(850, 40);
        }

        @Test
        @DisplayName("UTC-165 Trả lời tin ở cuộc khác / đã thu hồi / không còn hiện / không tồn tại: 400 \"Tin nhắn bạn trả lời không còn nữa.\" (4 lần)")
        void utc165_goneQuotedMessageIsRefused() {
            when(repository.message(851)).thenReturn(Optional.of(message(851, 777, PEER_ID, null)));
            when(repository.message(852)).thenReturn(Optional.of(message(852, CONV_ID, PEER_ID, NOW.minusSeconds(5))));
            when(repository.message(853)).thenReturn(Optional.of(message(853, CONV_ID, PEER_ID, null)));
            when(repository.shown(anyLong(), anyLong())).thenReturn(true);
            when(repository.shown(853, 0)).thenReturn(false);
            for (long replyTo : new long[] {851, 852, 853, 854}) {
                assertApiError(() -> service.send(ME, CONV_ID, new SendRequest("Ý mình là vậy", null, null, replyTo)),
                        HttpStatus.BAD_REQUEST, REPLY_GONE);
            }

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-166 Tin trả lời không còn và có từ khóa cấm: báo tin trả lời trước, không lọc nội dung")
        void utc166_quotedMessageIsCheckedBeforeTheText() {
            when(filter.bannedKeyword(KEYWORD_TEXT)).thenReturn(Optional.of(KEYWORD));

            assertApiError(() -> service.send(ME, CONV_ID, new SendRequest(KEYWORD_TEXT, null, null, 854L)),
                    HttpStatus.BAD_REQUEST, REPLY_GONE);

            verifyNoInteractions(filter);
        }

        @Test
        @DisplayName("UTC-167 Gửi kèm tệp trong cuộc trò chuyện chưa mã hóa: 400 \"Chỉ gửi được ảnh, video, ghi âm…\"")
        void utc167_filesNeedAnEncryptedConversation() {
            assertApiError(() -> service.send(ME, CONV_ID, new SendRequest("Có ảnh", null, List.of(61L), null)),
                    HttpStatus.BAD_REQUEST, FILES_NEED_E2EE);

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-168 Gửi phong bì mã hóa khi còn là lời mời (dù cả hai có khóa): 400 \"…chưa được mã hóa đầu cuối…\"")
        void utc168_envelopeInARequestIsRefused() {
            givenStates(ACCEPTED, REQUEST);
            givenKeys(true, true);
            when(repository.countMessagesBy(CONV_ID, ME_ID)).thenReturn(1);

            assertApiError(() -> service.send(ME, CONV_ID, sealed(envelope(MY_KEY, PEER_KEY), null)),
                    HttpStatus.BAD_REQUEST, NOT_ENCRYPTED);

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-169 Gửi phong bì mã hóa khi Lan chưa có khóa: 400 \"…chưa được mã hóa đầu cuối…\"")
        void utc169_envelopeWhileThePeerHasNoKeyIsRefused() {
            givenKeys(true, false);

            assertApiError(() -> service.send(ME, CONV_ID, sealed(envelope(MY_KEY, PEER_KEY), null)),
                    HttpStatus.BAD_REQUEST, NOT_ENCRYPTED);

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-170 Lan chưa có khóa: tin chữ thường vẫn gửi được và vẫn qua bộ lọc từ khóa, link")
        void utc170_plainTextWhileThePeerHasNoKeyIsFiltered() {
            givenKeys(true, false);

            Sent sent = sendIn("Vẫn nhắn chữ được");

            assertThat(sent.message().content()).isEqualTo("Vẫn nhắn chữ được");
            assertThat(sent.status().encryption().notice().code()).isEqualTo("NO_KEY_PEER");
            verify(filter).bannedKeyword("Vẫn nhắn chữ được");
            verify(filter).linkProblem("Vẫn nhắn chữ được", ME_ID);
        }

        @Test
        @DisplayName("UTC-171 Lan bị khóa sau khi đã nhắn: 403 UNAVAILABLE")
        void utc171_lockedPeerIsUnavailable() {
            givenPeer("USER", "LOCKED");

            assertApiError(() -> sendIn("Anh ơi"), HttpStatus.FORBIDDEN, UNAVAILABLE);

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-172 Trả lời lời mời trong cuộc trò chuyện (Minh REQUEST): tự chấp nhận, sự kiện không còn là lời mời")
        void utc172_answeringARequestInTheConversationAcceptsIt() {
            when(repository.member(CONV_ID, ME_ID)).thenReturn(Optional.of(row(REQUEST)), Optional.of(row(REQUEST)),
                    Optional.of(row(ACCEPTED)));

            sendIn("Chào Lan, mình nhận được tin rồi");

            verify(repository).setState(CONV_ID, ME_ID, ACCEPTED, NOW);
            verify(events).publishEvent(new ChatMessageSent(CONV_ID, NEW_MSG, ME_ID, "Minh", PEER_ID, false, false));
        }

        @Test
        @DisplayName("UTC-173 Khách: 401")
        void utc173_guestIsRefused() {
            assertApiError(() -> service.send(null, CONV_ID, new SendRequest("Chào")), HttpStatus.UNAUTHORIZED, GUEST);

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-174 Người gửi là nhân viên (3 vai trò) hoặc thành viên bị khóa: 403 MSG18 (4 lần)")
        void utc174_staffAndLockedSendersAreRefused() {
            assertStaffAndLockedRefused(me -> service.send(me, CONV_ID, new SendRequest("Chào bạn")));

            verifyNothingSent();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("11. send – CHAT Cuộc trò chuyện mã hóa đầu cuối: phong bì, khóa, tệp (UTC-175 → UTC-194)")
    class SendEncrypted {

        @Test
        @DisplayName("UTC-175 Phong bì với đúng khóa hiện tại 71/72: lưu phong bì, không có chữ, trạng thái E2EE")
        void utc175_envelopeIsSaved() {
            givenEncrypted();
            Envelope envelope = envelope(MY_KEY, PEER_KEY);

            Sent sent = service.send(ME, CONV_ID, sealed(envelope, null));

            verify(repository).insertMessage(CONV_ID, ME_ID, null, envelope, null, NOW);
            assertThat(sent.message().content()).isNull();
            assertThat(sent.message().encrypted()).isEqualTo(envelope);
            assertThat(sent.message().fileIds()).isEmpty();
            assertThat(sent.status().encryption().mode()).isEqualTo("E2EE");
            assertThat(sent.status().canAttach()).isTrue();
        }

        @Test
        @DisplayName("UTC-176 Sự kiện ChatMessageSent ghi là đã mã hóa (thông báo không chứa chữ), báo \"message\" tới cả hai")
        void utc176_encryptedMessageIsPublishedAsEncrypted() {
            givenEncrypted();

            service.send(ME, CONV_ID, sealed(envelope(MY_KEY, PEER_KEY), null));

            verify(events).publishEvent(new ChatMessageSent(CONV_ID, NEW_MSG, ME_ID, "Minh", PEER_ID, true, false));
            verify(live).publish(ME_ID, "chat", event("message", CONV_ID, NEW_MSG));
            verify(live).publish(PEER_ID, "chat", event("message", CONV_ID, NEW_MSG));
        }

        @Test
        @DisplayName("UTC-177 Tin mã hóa: không lọc từ khóa/link, không xét lặp lại, chỉ còn giới hạn mỗi phút")
        void utc177_encryptedMessagesSkipTheTextChecks() {
            givenEncrypted();

            service.send(ME, CONV_ID, sealed(envelope(MY_KEY, PEER_KEY), null));

            verifyNoInteractions(filter);
            verify(repository, never()).recentMessagesElsewhere(anyLong(), any(), any(), anyInt());
            verify(repository, never()).countStartedBetween(anyLong(), any(), any());
            verify(repository).countMessagesSince(ME_ID, NOW.minusSeconds(60));
        }

        @Test
        @DisplayName("UTC-178 Tin chữ thường trong cuộc đã mã hóa: 400 \"…đã được mã hóa đầu cuối…\"")
        void utc178_plainTextIsRefused() {
            givenEncrypted();

            assertApiError(() -> sendIn("Tin chữ thường"), HttpStatus.BAD_REQUEST, ENCRYPTED_ONLY);

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-179 Phong bì kèm chữ thường \"lộ chữ\": 400 \"…đã được mã hóa đầu cuối…\"")
        void utc179_envelopeWithPlainTextIsRefused() {
            givenEncrypted();

            assertApiError(() -> service.send(ME, CONV_ID,
                            new SendRequest("lộ chữ", envelope(MY_KEY, PEER_KEY), null, null)),
                    HttpStatus.BAD_REQUEST, ENCRYPTED_ONLY);

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-180 Phong bì kèm chữ chỉ có khoảng trắng: bỏ chữ, lưu phong bì")
        void utc180_blankTextBesideAnEnvelopeIsDropped() {
            givenEncrypted();
            Envelope envelope = envelope(MY_KEY, PEER_KEY);

            service.send(ME, CONV_ID, new SendRequest("   ", envelope, null, null));

            verify(repository).insertMessage(CONV_ID, ME_ID, null, envelope, null, NOW);
        }

        @Test
        @DisplayName("UTC-181 Khóa người gửi trong phong bì (70) không còn là khóa hiện tại (71): 409 \"Khóa mã hóa vừa thay đổi…\"")
        void utc181_staleSenderKeyIsAConflict() {
            givenEncrypted();

            assertApiError(() -> service.send(ME, CONV_ID, sealed(envelope(70, PEER_KEY), null)), HttpStatus.CONFLICT,
                    KEY_CHANGED);

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-182 Khóa người nhận trong phong bì (73) không phải khóa hiện tại của Lan (72): 409")
        void utc182_staleRecipientKeyIsAConflict() {
            givenEncrypted();

            assertApiError(() -> service.send(ME, CONV_ID, sealed(envelope(MY_KEY, 73), null)), HttpStatus.CONFLICT,
                    KEY_CHANGED);

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-183 Bản mã 48 byte (nhỏ nhất) và 32.768 byte (lớn nhất): lưu được (2 lần)")
        void utc183_ciphertextSizeLimitsAreAllowed() {
            givenEncrypted();
            for (int bytes : new int[] {48, 32_768}) {
                Envelope envelope = new Envelope(b64(bytes), b64(12), MY_KEY, PEER_KEY, "ab".repeat(32));
                service.send(ME, CONV_ID, sealed(envelope, null));
                verify(repository).insertMessage(CONV_ID, ME_ID, null, envelope, null, NOW);
            }
        }

        @Test
        @DisplayName("UTC-184 Bản mã 47 byte và 32.769 byte: 400 \"Tin nhắn mã hóa không hợp lệ…\" (2 lần)")
        void utc184_ciphertextOutsideTheLimitsIsRefused() {
            givenEncrypted();
            for (int bytes : new int[] {47, 32_769}) {
                Envelope envelope = new Envelope(b64(bytes), b64(12), MY_KEY, PEER_KEY, "ab".repeat(32));
                assertApiError(() -> service.send(ME, CONV_ID, sealed(envelope, null)), HttpStatus.BAD_REQUEST,
                        BAD_ENVELOPE);
            }

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-185 Phong bì sai dạng (iv 11 byte, iv null, bản mã không phải base64 hoặc null, thẻ chữ hoa hoặc 62 ký tự, thiếu id khóa): 400, không đọc cuộc trò chuyện (8 lần)")
        void utc185_malformedEnvelopesAreRefused() {
            givenEncrypted();
            String tag = "ab".repeat(32);
            List<Envelope> bad = List.of(
                    new Envelope(b64(64), b64(11), MY_KEY, PEER_KEY, tag),
                    new Envelope(b64(64), null, MY_KEY, PEER_KEY, tag),
                    new Envelope("***", b64(12), MY_KEY, PEER_KEY, tag),
                    new Envelope(null, b64(12), MY_KEY, PEER_KEY, tag),
                    new Envelope(b64(64), b64(12), MY_KEY, PEER_KEY, "AB".repeat(32)),
                    new Envelope(b64(64), b64(12), MY_KEY, PEER_KEY, "ab".repeat(31)),
                    new Envelope(b64(64), b64(12), null, PEER_KEY, tag),
                    new Envelope(b64(64), b64(12), MY_KEY, null, tag));
            for (Envelope envelope : bad) {
                assertApiError(() -> service.send(ME, CONV_ID, sealed(envelope, null)), HttpStatus.BAD_REQUEST,
                        BAD_ENVELOPE);
            }

            verify(repository, never()).conversation(anyLong());
            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-186 Tin mã hóa khi đã gửi 30 tin trong phút qua: 429")
        void utc186_perMinuteLimitAppliesToEncryptedMessages() {
            givenEncrypted();
            when(repository.countMessagesSince(eq(ME_ID), any())).thenReturn(30);

            assertApiError(() -> service.send(ME, CONV_ID, sealed(envelope(MY_KEY, PEER_KEY), null)),
                    HttpStatus.TOO_MANY_REQUESTS, TOO_FAST);

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-187 Kèm 2 ảnh đã tải lên (61, 62): khóa 2 tệp, gắn vào tin 901, trả về fileIds")
        void utc187_twoPhotosAreAttached() {
            givenEncrypted();
            givenAttachable(file(61, "IMAGE"), file(62, "IMAGE"));

            Sent sent = service.send(ME, CONV_ID, sealed(envelope(MY_KEY, PEER_KEY), Arrays.asList(61L, 62L)));

            verify(repository).lockAttachableFiles(CONV_ID, ME_ID, Arrays.asList(61L, 62L));
            verify(repository).attachFiles(NEW_MSG, List.of(61L, 62L));
            assertThat(sent.message().fileIds()).containsExactly(61L, 62L);
        }

        @Test
        @DisplayName("UTC-188 Kèm 1 video hoặc 1 ghi âm: gửi được (2 lần)")
        void utc188_oneVideoOrOneVoiceMessage() {
            givenEncrypted();
            for (String kind : List.of("VIDEO", "AUDIO")) {
                givenAttachable(file(63, kind));
                Sent sent = service.send(ME, CONV_ID, sealed(envelope(MY_KEY, PEER_KEY), Arrays.asList(63L)));
                assertThat(sent.message().fileIds()).containsExactly(63L);
            }

            verify(repository, times(2)).attachFiles(NEW_MSG, List.of(63L));
        }

        @Test
        @DisplayName("UTC-189 Kèm đúng 5 ảnh: gửi được")
        void utc189_fivePhotosAreAllowed() {
            givenEncrypted();
            givenAttachable(file(61, "IMAGE"), file(62, "IMAGE"), file(63, "IMAGE"), file(64, "IMAGE"),
                    file(65, "IMAGE"));

            Sent sent = service.send(ME, CONV_ID, sealed(envelope(MY_KEY, PEER_KEY),
                    Arrays.asList(61L, 62L, 63L, 64L, 65L)));

            assertThat(sent.message().fileIds()).containsExactly(61L, 62L, 63L, 64L, 65L);
        }

        @Test
        @DisplayName("UTC-190 Kèm 6 ảnh: 400 \"Tệp đính kèm không hợp lệ hoặc đã được gửi.\", không khóa tệp")
        void utc190_sixPhotosAreRefused() {
            givenEncrypted();

            assertApiError(() -> service.send(ME, CONV_ID, sealed(envelope(MY_KEY, PEER_KEY),
                    Arrays.asList(61L, 62L, 63L, 64L, 65L, 66L))), HttpStatus.BAD_REQUEST, BAD_FILES);

            verify(repository, never()).lockAttachableFiles(anyLong(), anyLong(), any());
            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-191 Id tệp trùng (61, 61) hoặc có id null: 400 BAD_FILES (2 lần)")
        void utc191_repeatedOrNullFileIdsAreRefused() {
            givenEncrypted();
            for (List<Long> ids : List.of(Arrays.asList(61L, 61L), Arrays.asList(61L, null))) {
                assertApiError(() -> service.send(ME, CONV_ID, sealed(envelope(MY_KEY, PEER_KEY), ids)),
                        HttpStatus.BAD_REQUEST, BAD_FILES);
            }

            verify(repository, never()).lockAttachableFiles(anyLong(), anyLong(), any());
            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-192 Tệp 62 đã gửi, đã xóa, của người khác hoặc cuộc khác (kho chỉ khóa được 61): 400 BAD_FILES")
        void utc192_fileThatCannotBeAttachedIsRefused() {
            givenEncrypted();
            givenAttachable(file(61, "IMAGE"));

            assertApiError(() -> service.send(ME, CONV_ID, sealed(envelope(MY_KEY, PEER_KEY),
                    Arrays.asList(61L, 62L))), HttpStatus.BAD_REQUEST, BAD_FILES);

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-193 Ảnh + video, 2 video, 2 ghi âm: 400 BAD_FILES (3 lần)")
        void utc193_mixedOrSeveralClipsAreRefused() {
            givenEncrypted();
            List<FileRow[]> sets = List.of(
                    new FileRow[] {file(61, "IMAGE"), file(62, "VIDEO")},
                    new FileRow[] {file(61, "VIDEO"), file(62, "VIDEO")},
                    new FileRow[] {file(61, "AUDIO"), file(62, "AUDIO")});
            for (FileRow[] set : sets) {
                givenAttachable(set);
                assertApiError(() -> service.send(ME, CONV_ID, sealed(envelope(MY_KEY, PEER_KEY),
                        Arrays.asList(61L, 62L))), HttpStatus.BAD_REQUEST, BAD_FILES);
            }

            verifyNothingSent();
        }

        @Test
        @DisplayName("UTC-194 Khóa cũ và 6 tệp: báo khóa đã đổi (409) trước khi xét tệp")
        void utc194_keysAreCheckedBeforeFiles() {
            givenEncrypted();

            assertApiError(() -> service.send(ME, CONV_ID, sealed(envelope(70, PEER_KEY),
                    Arrays.asList(61L, 62L, 63L, 64L, 65L, 66L))), HttpStatus.CONFLICT, KEY_CHANGED);

            verifyNothingSent();
        }
    }
}
