package com.sep490.vsa.feature.chat;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.live.LiveEvents;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.feature.chat.ChatDtos.BlockedMember;
import com.sep490.vsa.feature.chat.ChatDtos.Conversation;
import com.sep490.vsa.feature.chat.ChatDtos.ConversationItem;
import com.sep490.vsa.feature.chat.ChatDtos.ConversationList;
import com.sep490.vsa.feature.chat.ChatDtos.Counts;
import com.sep490.vsa.feature.chat.ChatDtos.Encryption;
import com.sep490.vsa.feature.chat.ChatDtos.Envelope;
import com.sep490.vsa.feature.chat.ChatDtos.Latest;
import com.sep490.vsa.feature.chat.ChatDtos.Limits;
import com.sep490.vsa.feature.chat.ChatDtos.Message;
import com.sep490.vsa.feature.chat.ChatDtos.Notice;
import com.sep490.vsa.feature.chat.ChatDtos.OlderMessages;
import com.sep490.vsa.feature.chat.ChatDtos.Peer;
import com.sep490.vsa.feature.chat.ChatDtos.Reaction;
import com.sep490.vsa.feature.chat.ChatDtos.ReadRequest;
import com.sep490.vsa.feature.chat.ChatDtos.Status;
import com.sep490.vsa.feature.chat.ChatDtos.Tag;
import com.sep490.vsa.feature.chat.ChatDtos.Updates;
import com.sep490.vsa.feature.chat.ChatRepository.Account;
import com.sep490.vsa.feature.chat.ChatRepository.BlockRow;
import com.sep490.vsa.feature.chat.ChatRepository.ConversationRow;
import com.sep490.vsa.feature.chat.ChatRepository.KeyRow;
import com.sep490.vsa.feature.chat.ChatRepository.LatestRow;
import com.sep490.vsa.feature.chat.ChatRepository.ListRow;
import com.sep490.vsa.feature.chat.ChatRepository.MemberRow;
import com.sep490.vsa.feature.chat.ChatRepository.MessageRow;
import com.sep490.vsa.feature.chat.ChatRepository.ReactionRow;
import com.sep490.vsa.feature.media.MediaStorage;
import com.sep490.vsa.feature.moderation.ContentFilter;
import com.sep490.vsa.feature.report.ContentReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
 * Unit tests of "Tin nhắn" (one-to-one chat between Members, {@link ChatService}): reading — the conversation list
 * ("Hộp thư" / "Lời mời"), the header counts, one conversation and its status, a conversation opened from a member's
 * name, older messages, the updates the open page asks for, one message — and the viewer's own side — marking read,
 * accepting, declining and deleting a conversation, blocking, unblocking, the block list and "đang soạn tin". Sending is
 * tested in {@link ChatServiceSendTest}. The rules are those of CLAUDE.md "Tin nhắn": ACTIVE Members only (staff MSG18,
 * guests 401), requests with {@link ChatService#PENDING_MAX} messages, "Đã xem" only once accepted, decline and block
 * never told to the other member, the end-to-end encryption mode, live {@code chat} events with ids only.
 * <p>
 * The database is not used: {@link ChatRepository}, {@link ContentFilter}, {@link ContentReportService},
 * {@link SystemSettings}, {@link MediaStorage}, {@link ApplicationEventPublisher} and {@link LiveEvents} are mocks;
 * {@link ChatTyping} and {@link ChatViewers} are the real in-memory classes on the same fixed clock (20/11/2026 12:00
 * Japan time). Member Minh (id 10) is the viewer, member Lan (id 20) the other member of conversation 500.
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("ChatService – Tin nhắn: danh sách, cuộc trò chuyện, đã đọc, lời mời, xóa, chặn, đang soạn tin (CHAT)")
class ChatServiceConversationTest {

    private static final long ME_ID = 10L;
    private static final long PEER_ID = 20L;
    private static final long OUTSIDER_ID = 30L;
    private static final long CONV_ID = 500L;
    private static final long MY_KEY = 71L;
    private static final long PEER_KEY = 72L;
    /** 12:00 in Japan, 20/11/2026. */
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");

    private static final String ACCEPTED = "ACCEPTED";
    private static final String REQUEST = "REQUEST";
    private static final String DECLINED = "DECLINED";

    private static final String GUEST = "Bạn cần đăng nhập để nhắn tin.";
    private static final String MSG18 = "Bạn không có quyền thực hiện thao tác này.";
    private static final String CONVERSATION_NOT_FOUND = "Không tìm thấy cuộc trò chuyện này.";
    private static final String MEMBER_NOT_FOUND = "Không tìm thấy thành viên này.";
    private static final String MESSAGE_NOT_FOUND = "Không tìm thấy tin nhắn này.";
    private static final String UNAVAILABLE = "Bạn không thể nhắn tin cho thành viên này.";
    private static final List<String> STAFF_ROLES = List.of("MODERATOR", "ACADEMIC_MANAGER", "ADMIN");

    private static final AppUserPrincipal ME = principal(ME_ID, "USER");
    private static final Account MINH = account(ME_ID, "Minh", "USER", "ACTIVE");
    private static final Account LAN = account(PEER_ID, "Lan", "USER", "ACTIVE");
    private static final ConversationRow CONVERSATION = new ConversationRow(CONV_ID, ME_ID, PEER_ID, ME_ID);
    private static final List<ContentReportService.Reason> REASONS = List.of(
            new ContentReportService.Reason(1, "Spam"), new ContentReportService.Reason(2, "Lừa đảo"));

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

        givenAccount(MINH);
        givenAccount(LAN);
        when(repository.conversation(CONV_ID)).thenReturn(Optional.of(CONVERSATION));
        when(repository.lockConversation(CONV_ID)).thenReturn(Optional.of(CONVERSATION));
        when(repository.conversationOf(ME_ID, PEER_ID)).thenReturn(Optional.of(CONVERSATION));
        when(repository.lockConversationOf(ME_ID, PEER_ID)).thenReturn(Optional.of(CONVERSATION));
        givenStates(ACCEPTED, ACCEPTED);
        when(repository.counts(anyLong())).thenReturn(new int[] {0, 0});
        when(reports.reasons()).thenReturn(REASONS);
        when(settings.getInt(SystemSettings.NEW_ACCOUNT_DAYS, 7)).thenReturn(7);
    }

    // ---------------------------------------------------------------- helpers

    private static AppUserPrincipal principal(long id, String role) {
        return new AppUserPrincipal(id, "user" + id + "@example.org", "User " + id, role, "/");
    }

    private static Account account(long id, String name, String role, String status) {
        return new Account(id, name, "/media/avatar/2026/11/" + id + ".jpg", role, status,
                NOW.minus(Duration.ofDays(30)));
    }

    private void givenAccount(Account account) {
        when(repository.account(account.id())).thenReturn(Optional.of(account));
    }

    private void givenMe(String role, String status) {
        givenAccount(account(ME_ID, "Minh", role, status));
    }

    private void givenPeer(String role, String status) {
        givenAccount(account(PEER_ID, "Lan", role, status));
    }

    private static MemberRow row(String state) {
        return new MemberRow(state, 0, 0);
    }

    private void givenStates(String mine, String theirs) {
        givenMyRow(row(mine));
        givenPeerRow(row(theirs));
    }

    private void givenMyRow(MemberRow row) {
        when(repository.member(CONV_ID, ME_ID)).thenReturn(Optional.of(row));
    }

    private void givenPeerRow(MemberRow row) {
        when(repository.member(CONV_ID, PEER_ID)).thenReturn(Optional.of(row));
    }

    private void givenNoConversation() {
        when(repository.conversationOf(ME_ID, PEER_ID)).thenReturn(Optional.empty());
        when(repository.lockConversationOf(ME_ID, PEER_ID)).thenReturn(Optional.empty());
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

    /** An outsider's conversation: members 20 and 30, not the viewer. */
    private void givenOutsidersConversation() {
        ConversationRow theirs = new ConversationRow(CONV_ID, PEER_ID, OUTSIDER_ID, PEER_ID);
        when(repository.conversation(CONV_ID)).thenReturn(Optional.of(theirs));
        when(repository.lockConversation(CONV_ID)).thenReturn(Optional.of(theirs));
    }

    private void givenNoSuchConversation() {
        when(repository.conversation(CONV_ID)).thenReturn(Optional.empty());
        when(repository.lockConversation(CONV_ID)).thenReturn(Optional.empty());
    }

    private static MessageRow plain(long id, long sender, String content) {
        return new MessageRow(id, CONV_ID, sender, content, null, null, null, null, null, null, null, null,
                NOW.minusSeconds(600 - id % 100));
    }

    private static MessageRow encrypted(long id, long sender) {
        return new MessageRow(id, CONV_ID, sender, null, "Q2lwaGVy", "SVY=", MY_KEY, PEER_KEY, "ab".repeat(32), null,
                null, null, NOW.minusSeconds(300));
    }

    private static MessageRow recalled(long id, long sender, String oldContent) {
        return new MessageRow(id, CONV_ID, sender, oldContent, null, null, null, null, null, null, null,
                NOW.minusSeconds(30), NOW.minusSeconds(300));
    }

    private static MessageRow editedReply(long id, long sender, String content, Long replyTo) {
        return new MessageRow(id, CONV_ID, sender, content, null, null, null, null, null, replyTo,
                NOW.minusSeconds(60), null, NOW.minusSeconds(300));
    }

    private static List<MessageRow> plainRows(int count) {
        List<MessageRow> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            rows.add(plain(i, i % 2 == 0 ? ME_ID : PEER_ID, "Tin " + i));
        }
        return rows;
    }

    private static ListRow listRow(long id, String state, long peerId, String peerName, String role, String status,
                                   long lastId, int unread, boolean blocked) {
        return new ListRow(id, state, peerId, peerName, "/media/avatar/2026/11/" + peerId + ".jpg", role, status, lastId,
                unread, blocked);
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
            givenMe(role, "ACTIVE");
            AppUserPrincipal staff = principal(ME_ID, role);
            assertApiError(() -> call.accept(staff), HttpStatus.FORBIDDEN, MSG18);
        }
        givenMe("USER", "LOCKED");
        assertApiError(() -> call.accept(ME), HttpStatus.FORBIDDEN, MSG18);
    }

    private void verifyNothingAnnounced() {
        verify(live, never()).publish(anyLong(), anyString(), any());
    }

    private void verifyNoStateWritten() {
        verify(repository, never()).setState(anyLong(), anyLong(), anyString(), any());
        verify(repository, never()).clear(anyLong(), anyLong(), any());
        verify(repository, never()).markRead(anyLong(), anyLong(), anyLong());
        verify(repository, never()).block(anyLong(), anyLong(), any());
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. list – CHAT Danh sách cuộc trò chuyện: Hộp thư / Lời mời (UTC-001 → UTC-022)")
    class ListConversations {

        @Test
        @DisplayName("UTC-001 Hộp thư mặc định, \"INBOX\", \"inbox\": đọc các cuộc trò chuyện đã nhận lời, trang 0 (3 lần)")
        void utc001_inboxIsTheDefaultBoxInAnyCase() {
            for (String box : new String[] {null, "INBOX", "inbox"}) {
                service.list(ME, box, 0);
            }

            verify(repository, times(3)).list(ME_ID, ACCEPTED, 0, 31);
        }

        @Test
        @DisplayName("UTC-002 Hộp \"REQUESTS\" / \"requests\": đọc các lời mời nhắn tin (2 lần)")
        void utc002_requestsBoxReadsRequests() {
            service.list(ME, "REQUESTS", 0);
            service.list(ME, "requests", 0);

            verify(repository, times(2)).list(ME_ID, REQUEST, 0, 31);
        }

        @Test
        @DisplayName("UTC-003 Hộp thư không có (\"SPAM\", \"\", \"ARCHIVE\"): 400 \"Hộp thư không hợp lệ.\" (3 lần)")
        void utc003_unknownBoxIsRefused() {
            for (String box : List.of("SPAM", "", "ARCHIVE")) {
                assertApiError(() -> service.list(ME, box, 0), HttpStatus.BAD_REQUEST, "Hộp thư không hợp lệ.");
            }

            verify(repository, never()).list(anyLong(), anyString(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("UTC-004 Trang 2: bỏ qua 60 cuộc trò chuyện, đọc 31 để biết còn trang sau")
        void utc004_pageTwoSkipsSixtyConversations() {
            service.list(ME, "INBOX", 2);

            verify(repository).list(ME_ID, ACCEPTED, 60, 31);
        }

        @Test
        @DisplayName("UTC-005 Trang âm (-5): đọc trang đầu")
        void utc005_negativePageReadsTheFirstPage() {
            service.list(ME, "INBOX", -5);

            verify(repository).list(ME_ID, ACCEPTED, 0, 31);
        }

        @Test
        @DisplayName("UTC-006 Trang 1000 và 5000: giới hạn ở trang 1000 (bỏ qua 30.000)")
        void utc006_pageIsCappedAtOneThousand() {
            service.list(ME, "INBOX", 1000);
            service.list(ME, "INBOX", 5000);

            verify(repository, times(2)).list(ME_ID, ACCEPTED, 30_000, 31);
        }

        @Test
        @DisplayName("UTC-007 Kho trả 31 dòng: hiện 30, hasMore = true")
        void utc007_thirtyOneRowsShowThirtyAndMore() {
            List<ListRow> rows = new ArrayList<>();
            for (int i = 1; i <= 31; i++) {
                rows.add(listRow(1000 + i, ACCEPTED, 100 + i, "Bạn " + i, "USER", "ACTIVE", 5000 + i, 0, false));
            }
            when(repository.list(ME_ID, ACCEPTED, 0, 31)).thenReturn(rows);

            ConversationList list = service.list(ME, "INBOX", 0);

            assertThat(list.items()).hasSize(30);
            assertThat(list.items().get(0).id()).isEqualTo(1001L);
            assertThat(list.items().get(29).id()).isEqualTo(1030L);
            assertThat(list.hasMore()).isTrue();
        }

        @Test
        @DisplayName("UTC-008 Kho trả đúng 30 dòng: hiện 30, hasMore = false")
        void utc008_exactlyThirtyRowsMeanNoMore() {
            List<ListRow> rows = new ArrayList<>();
            for (int i = 1; i <= 30; i++) {
                rows.add(listRow(1000 + i, ACCEPTED, 100 + i, "Bạn " + i, "USER", "ACTIVE", 5000 + i, 0, false));
            }
            when(repository.list(ME_ID, ACCEPTED, 0, 31)).thenReturn(rows);

            ConversationList list = service.list(ME, "INBOX", 0);

            assertThat(list.items()).hasSize(30);
            assertThat(list.hasMore()).isFalse();
        }

        @Test
        @DisplayName("UTC-009 Chưa có cuộc trò chuyện: danh sách rỗng, hasMore = false, kèm số đếm 0")
        void utc009_noConversationGivesAnEmptyList() {
            ConversationList list = service.list(ME, "INBOX", 0);

            assertThat(list.items()).isEmpty();
            assertThat(list.hasMore()).isFalse();
            assertThat(list.counts()).isEqualTo(new Counts(0, 0, null));
        }

        @Test
        @DisplayName("UTC-010 Mỗi dòng có id, trạng thái, số tin chưa đọc, đã chặn, người kia (tên, ảnh, tag, active)")
        void utc010_itemCarriesPeerStateUnreadAndBlock() {
            when(repository.list(ME_ID, ACCEPTED, 0, 31)).thenReturn(List.of(
                    listRow(CONV_ID, ACCEPTED, PEER_ID, "Lan", "USER", "ACTIVE", 905, 2, true)));
            when(repository.messagesByIds(any())).thenReturn(List.of(plain(905, PEER_ID, "Chào Minh")));
            when(repository.tagsOf(any())).thenReturn(Map.of(PEER_ID, List.of(new Tag("Mentor", "#2E7D32"))));

            ConversationItem item = service.list(ME, "INBOX", 0).items().get(0);

            assertThat(item.id()).isEqualTo(CONV_ID);
            assertThat(item.state()).isEqualTo(ACCEPTED);
            assertThat(item.unread()).isEqualTo(2);
            assertThat(item.blockedByMe()).isTrue();
            assertThat(item.peer()).isEqualTo(new Peer(PEER_ID, "Lan", "/media/avatar/2026/11/20.jpg",
                    List.of(new Tag("Mentor", "#2E7D32")), true));
            assertThat(item.lastMessage().id()).isEqualTo(905L);
            verify(repository).tagsOf(Set.of(PEER_ID));
        }

        @Test
        @DisplayName("UTC-011 Người kia bị khóa, đã xóa hoặc là nhân viên: peer.active = false (3 dòng)")
        void utc011_peerIsNotActiveWhenLockedDeletedOrStaff() {
            when(repository.list(ME_ID, ACCEPTED, 0, 31)).thenReturn(List.of(
                    listRow(501, ACCEPTED, 21, "Khóa", "USER", "LOCKED", 901, 0, false),
                    listRow(502, ACCEPTED, 22, "Tài khoản đã xóa", "USER", "DELETED", 902, 0, false),
                    listRow(503, ACCEPTED, 23, "Kiểm duyệt", "MODERATOR", "ACTIVE", 903, 0, false)));

            List<ConversationItem> items = service.list(ME, "INBOX", 0).items();

            assertThat(items).extracting(i -> i.peer().active()).containsExactly(false, false, false);
        }

        @Test
        @DisplayName("UTC-012 Tin cuối của người kia: gộp thành một dòng (xuống dòng, nhiều dấu cách), mine = false")
        void utc012_previewIsOneLine() {
            when(repository.list(ME_ID, ACCEPTED, 0, 31)).thenReturn(List.of(
                    listRow(CONV_ID, ACCEPTED, PEER_ID, "Lan", "USER", "ACTIVE", 905, 1, false)));
            when(repository.messagesByIds(any())).thenReturn(List.of(
                    plain(905, PEER_ID, "Chào Minh,\n\n  mai   đi   Ueno nhé  ")));

            Message last = service.list(ME, "INBOX", 0).items().get(0).lastMessage();

            assertThat(last.content()).isEqualTo("Chào Minh, mai đi Ueno nhé");
            assertThat(last.mine()).isFalse();
            verify(repository).messagesByIds(List.of(905L));
        }

        @Test
        @DisplayName("UTC-013 Tin cuối đúng 120 ký tự: giữ nguyên")
        void utc013_previewOf120CharactersIsKept() {
            when(repository.list(ME_ID, ACCEPTED, 0, 31)).thenReturn(List.of(
                    listRow(CONV_ID, ACCEPTED, PEER_ID, "Lan", "USER", "ACTIVE", 905, 0, false)));
            when(repository.messagesByIds(any())).thenReturn(List.of(plain(905, PEER_ID, "a".repeat(120))));

            assertThat(service.list(ME, "INBOX", 0).items().get(0).lastMessage().content()).isEqualTo("a".repeat(120));
        }

        @Test
        @DisplayName("UTC-014 Tin cuối 121 ký tự: cắt còn 119 ký tự + \"…\" (120)")
        void utc014_previewOf121CharactersIsCut() {
            when(repository.list(ME_ID, ACCEPTED, 0, 31)).thenReturn(List.of(
                    listRow(CONV_ID, ACCEPTED, PEER_ID, "Lan", "USER", "ACTIVE", 905, 0, false)));
            when(repository.messagesByIds(any())).thenReturn(List.of(plain(905, PEER_ID, "a".repeat(121))));

            String preview = service.list(ME, "INBOX", 0).items().get(0).lastMessage().content();

            assertThat(preview).isEqualTo("a".repeat(119) + "…").hasSize(120);
        }

        @Test
        @DisplayName("UTC-015 Tin cuối đã mã hóa: không có chữ, giữ nguyên phong bì để trang tự giải mã")
        void utc015_encryptedLastMessageKeepsItsEnvelope() {
            when(repository.list(ME_ID, ACCEPTED, 0, 31)).thenReturn(List.of(
                    listRow(CONV_ID, ACCEPTED, PEER_ID, "Lan", "USER", "ACTIVE", 906, 1, false)));
            when(repository.messagesByIds(any())).thenReturn(List.of(encrypted(906, PEER_ID)));

            Message last = service.list(ME, "INBOX", 0).items().get(0).lastMessage();

            assertThat(last.content()).isNull();
            assertThat(last.encrypted()).isEqualTo(new Envelope("Q2lwaGVy", "SVY=", MY_KEY, PEER_KEY, "ab".repeat(32)));
            assertThat(last.recalled()).isFalse();
        }

        @Test
        @DisplayName("UTC-016 Tin cuối đã thu hồi: recalled = true, không có chữ, không có phong bì")
        void utc016_recalledLastMessageShowsAsRecalled() {
            when(repository.list(ME_ID, ACCEPTED, 0, 31)).thenReturn(List.of(
                    listRow(CONV_ID, ACCEPTED, PEER_ID, "Lan", "USER", "ACTIVE", 907, 0, false)));
            when(repository.messagesByIds(any())).thenReturn(List.of(recalled(907, PEER_ID, "Nội dung cũ")));

            Message last = service.list(ME, "INBOX", 0).items().get(0).lastMessage();

            assertThat(last.recalled()).isTrue();
            assertThat(last.content()).isNull();
            assertThat(last.encrypted()).isNull();
        }

        @Test
        @DisplayName("UTC-017 Tin cuối do chính mình gửi: mine = true")
        void utc017_ownLastMessageIsMine() {
            when(repository.list(ME_ID, ACCEPTED, 0, 31)).thenReturn(List.of(
                    listRow(CONV_ID, ACCEPTED, PEER_ID, "Lan", "USER", "ACTIVE", 908, 0, false)));
            when(repository.messagesByIds(any())).thenReturn(List.of(plain(908, ME_ID, "Cảm ơn Lan nhé")));

            assertThat(service.list(ME, "INBOX", 0).items().get(0).lastMessage().mine()).isTrue();
        }

        @Test
        @DisplayName("UTC-018 Kèm số đếm: 2 cuộc có tin chưa đọc, 1 lời mời, tin mới nhất là một lời mời")
        void utc018_listCarriesTheCounts() {
            when(repository.counts(ME_ID)).thenReturn(new int[] {2, 1});
            when(repository.latestUnread(ME_ID)).thenReturn(Optional.of(
                    new LatestRow(501, 910, "Hùng", REQUEST, NOW.minusSeconds(300))));

            Counts counts = service.list(ME, "REQUESTS", 0).counts();

            assertThat(counts).isEqualTo(new Counts(2, 1, new Latest(501, 910, "Hùng", true, NOW.minusSeconds(300))));
        }

        @Test
        @DisplayName("UTC-019 Khách (chưa đăng nhập): 401, không đọc danh sách")
        void utc019_guestIsRefused() {
            assertApiError(() -> service.list(null, "INBOX", 0), HttpStatus.UNAUTHORIZED, GUEST);

            verify(repository, never()).list(anyLong(), anyString(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("UTC-020 Phiên còn nhưng tài khoản không còn trong CSDL: 401 \"Bạn chưa đăng nhập.\"")
        void utc020_sessionWithoutAccountIsRefused() {
            when(repository.account(ME_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.list(ME, "INBOX", 0), HttpStatus.UNAUTHORIZED, "Bạn chưa đăng nhập.");
        }

        @Test
        @DisplayName("UTC-021 Moderator, Biên tập viên tiếng Nhật, Admin: 403 MSG18 (3 lần)")
        void utc021_staffRolesAreRefused() {
            for (String role : STAFF_ROLES) {
                givenMe(role, "ACTIVE");
                AppUserPrincipal staff = principal(ME_ID, role);
                assertApiError(() -> service.list(staff, "INBOX", 0), HttpStatus.FORBIDDEN, MSG18);
            }

            verify(repository, never()).list(anyLong(), anyString(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("UTC-022 Thành viên bị khóa hoặc đã xóa (vai trò đọc lại từ CSDL): 403 MSG18 (2 lần)")
        void utc022_lockedOrDeletedMemberIsRefused() {
            for (String status : List.of("LOCKED", "DELETED")) {
                givenMe("USER", status);
                assertApiError(() -> service.list(ME, "INBOX", 0), HttpStatus.FORBIDDEN, MSG18);
            }

            verify(repository, never()).list(anyLong(), anyString(), anyInt(), anyInt());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. counts – CHAT Số tin chưa đọc trên thanh đầu trang (UTC-023 → UTC-027)")
    class HeaderCounts {

        @Test
        @DisplayName("UTC-023 3 cuộc có tin chưa đọc, 2 lời mời, tin mới nhất từ một lời mời: request = true")
        void utc023_countsWithLatestRequest() {
            when(repository.counts(ME_ID)).thenReturn(new int[] {3, 2});
            when(repository.latestUnread(ME_ID)).thenReturn(Optional.of(
                    new LatestRow(501, 910, "Lan", REQUEST, NOW.minusSeconds(60))));

            Counts counts = service.counts(ME);

            assertThat(counts).isEqualTo(new Counts(3, 2, new Latest(501, 910, "Lan", true, NOW.minusSeconds(60))));
        }

        @Test
        @DisplayName("UTC-024 Tin mới nhất ở cuộc trò chuyện đã nhận lời: request = false")
        void utc024_latestOfAnAcceptedConversationIsNotARequest() {
            when(repository.counts(ME_ID)).thenReturn(new int[] {1, 0});
            when(repository.latestUnread(ME_ID)).thenReturn(Optional.of(
                    new LatestRow(CONV_ID, 911, "Lan", ACCEPTED, NOW.minusSeconds(10))));

            Counts counts = service.counts(ME);

            assertThat(counts.unread()).isEqualTo(1);
            assertThat(counts.requests()).isZero();
            assertThat(counts.latest().request()).isFalse();
            assertThat(counts.latest().messageId()).isEqualTo(911L);
        }

        @Test
        @DisplayName("UTC-025 Không có tin chưa đọc: (0, 0, latest = null)")
        void utc025_nothingUnread() {
            assertThat(service.counts(ME)).isEqualTo(new Counts(0, 0, null));
        }

        @Test
        @DisplayName("UTC-026 Khách: 401, không đếm")
        void utc026_guestIsRefused() {
            assertApiError(() -> service.counts(null), HttpStatus.UNAUTHORIZED, GUEST);

            verify(repository, never()).counts(anyLong());
        }

        @Test
        @DisplayName("UTC-027 Nhân viên (3 vai trò) và thành viên bị khóa: 403 MSG18 (4 lần)")
        void utc027_staffAndLockedAreRefused() {
            assertStaffAndLockedRefused(me -> service.counts(me));

            verify(repository, never()).counts(anyLong());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("03. open – CHAT Mở một cuộc trò chuyện và trạng thái của nó (UTC-028 → UTC-057)")
    class OpenConversation {

        @Test
        @DisplayName("UTC-028 Cuộc trò chuyện đã nhận lời: tin cũ trước, người kia, gửi được, không có thông báo")
        void utc028_acceptedConversationOpens() {
            when(repository.messagesBefore(CONV_ID, 0, null, 31)).thenReturn(List.of(
                    plain(901, PEER_ID, "Chào Minh"), plain(902, ME_ID, "Chào Lan")));

            Conversation conversation = service.open(ME, CONV_ID);

            assertThat(conversation.id()).isEqualTo(CONV_ID);
            assertThat(conversation.peer()).isEqualTo(new Peer(PEER_ID, "Lan", "/media/avatar/2026/11/20.jpg",
                    List.of(), true));
            assertThat(conversation.messages()).extracting(Message::id).containsExactly(901L, 902L);
            assertThat(conversation.messages()).extracting(Message::mine).containsExactly(false, true);
            assertThat(conversation.messages()).extracting(Message::content).containsExactly("Chào Minh", "Chào Lan");
            assertThat(conversation.hasOlder()).isFalse();
            assertThat(conversation.status().state()).isEqualTo(ACCEPTED);
            assertThat(conversation.status().canSend()).isTrue();
            assertThat(conversation.status().notice()).isNull();
            assertThat(conversation.status().blockedByMe()).isFalse();
            assertThat(conversation.reportReasons()).isEqualTo(REASONS);
            assertThat(conversation.serverTime()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("UTC-029 Kèm giới hạn cho trang: 2.000 ký tự, 3 tin chờ, sửa 15 phút, 5 ảnh, 10/50/5 MB, 30 s / 120 s, 6 cảm xúc")
        void utc029_limitsAreSent() {
            Limits limits = service.open(ME, CONV_ID).limits();

            assertThat(limits.messageMax()).isEqualTo(2000);
            assertThat(limits.pendingMax()).isEqualTo(3);
            assertThat(limits.editMinutes()).isEqualTo(15);
            assertThat(limits.filesMax()).isEqualTo(5);
            assertThat(limits.imageMaxBytes()).isEqualTo(10L * 1024 * 1024);
            assertThat(limits.videoMaxBytes()).isEqualTo(50L * 1024 * 1024);
            assertThat(limits.audioMaxBytes()).isEqualTo(5L * 1024 * 1024);
            assertThat(limits.videoMaxSeconds()).isEqualTo(30);
            assertThat(limits.audioMaxSeconds()).isEqualTo(120);
            assertThat(limits.reactions()).containsExactly("👍", "❤️", "😂",
                    "😮", "😢", "🙏");
        }

        @Test
        @DisplayName("UTC-030 Đã \"Xóa cuộc trò chuyện\" tới tin 40: chỉ đọc 31 tin mới nhất sau tin 40")
        void utc030_readsOnlyAfterTheViewersClearedId() {
            givenMyRow(new MemberRow(ACCEPTED, 40, 40));

            service.open(ME, CONV_ID);

            verify(repository).messagesBefore(CONV_ID, 40, null, 31);
        }

        @Test
        @DisplayName("UTC-031 Kho trả 31 tin: bỏ tin cũ nhất, hiện 30, hasOlder = true")
        void utc031_thirtyOneMessagesShowThirtyAndOlder() {
            when(repository.messagesBefore(CONV_ID, 0, null, 31)).thenReturn(plainRows(31));

            Conversation conversation = service.open(ME, CONV_ID);

            assertThat(conversation.messages()).hasSize(30);
            assertThat(conversation.messages().get(0).id()).isEqualTo(2L);
            assertThat(conversation.messages().get(29).id()).isEqualTo(31L);
            assertThat(conversation.hasOlder()).isTrue();
        }

        @Test
        @DisplayName("UTC-032 Kho trả đúng 30 tin: hiện cả 30, hasOlder = false")
        void utc032_exactlyThirtyMessagesMeanNoOlder() {
            when(repository.messagesBefore(CONV_ID, 0, null, 31)).thenReturn(plainRows(30));

            Conversation conversation = service.open(ME, CONV_ID);

            assertThat(conversation.messages()).hasSize(30);
            assertThat(conversation.hasOlder()).isFalse();
        }

        @Test
        @DisplayName("UTC-033 Tin đã sửa, trả lời tin 850, có 2 tệp: edited = true, replyToId = 850, fileIds = [61, 62]")
        void utc033_messageCarriesEditReplyAndFiles() {
            when(repository.messagesBefore(CONV_ID, 0, null, 31)).thenReturn(List.of(
                    editedReply(901, PEER_ID, "Hẹn 8 giờ nhé", 850L)));
            when(repository.fileIdsOf(any())).thenReturn(Map.of(901L, List.of(61L, 62L)));

            Message message = service.open(ME, CONV_ID).messages().get(0);

            assertThat(message.edited()).isTrue();
            assertThat(message.replyToId()).isEqualTo(850L);
            assertThat(message.fileIds()).containsExactly(61L, 62L);
            assertThat(message.recalled()).isFalse();
            assertThat(message.createdAt()).isEqualTo(NOW.minusSeconds(300));
        }

        @Test
        @DisplayName("UTC-034 Cảm xúc được đếm theo thứ tự danh sách cảm xúc, đánh dấu cảm xúc của chính mình")
        void utc034_reactionsAreCountedInTheirOrder() {
            List<String> emoji = ChatService.REACTIONS;
            when(repository.messagesBefore(CONV_ID, 0, null, 31)).thenReturn(List.of(plain(901, PEER_ID, "Chào")));
            when(repository.reactionsOf(any())).thenReturn(Map.of(901L, List.of(
                    new ReactionRow(901, PEER_ID, emoji.get(5)), new ReactionRow(901, PEER_ID, emoji.get(1)),
                    new ReactionRow(901, ME_ID, emoji.get(0)), new ReactionRow(901, OUTSIDER_ID, emoji.get(0)))));

            List<Reaction> reactions = service.open(ME, CONV_ID).messages().get(0).reactions();

            assertThat(reactions).containsExactly(new Reaction(emoji.get(0), 2, true),
                    new Reaction(emoji.get(1), 1, false), new Reaction(emoji.get(5), 1, false));
        }

        @Test
        @DisplayName("UTC-035 Tin đã thu hồi: không chữ, không cảm xúc, không tệp; cảm xúc/tệp chỉ đọc cho tin còn lại")
        void utc035_recalledMessageShowsNothing() {
            when(repository.messagesBefore(CONV_ID, 0, null, 31)).thenReturn(List.of(
                    plain(901, PEER_ID, "Chào"), recalled(903, PEER_ID, "Gửi nhầm số tài khoản")));
            when(repository.reactionsOf(any())).thenReturn(Map.of(903L, List.of(
                    new ReactionRow(903, ME_ID, ChatService.REACTIONS.get(0)))));
            when(repository.fileIdsOf(any())).thenReturn(Map.of(903L, List.of(61L)));

            Message gone = service.open(ME, CONV_ID).messages().get(1);

            assertThat(gone.recalled()).isTrue();
            assertThat(gone.content()).isNull();
            assertThat(gone.encrypted()).isNull();
            assertThat(gone.reactions()).isEmpty();
            assertThat(gone.fileIds()).isEmpty();
            verify(repository).reactionsOf(List.of(901L));
            verify(repository).fileIdsOf(List.of(901L));
        }

        @Test
        @DisplayName("UTC-036 Mình là người được mời (REQUEST): thông báo REQUEST, vẫn gửi được, chưa mã hóa")
        void utc036_requestReceivedShowsTheRequestNotice() {
            givenStates(REQUEST, ACCEPTED);

            Status status = service.open(ME, CONV_ID).status();

            assertThat(status.state()).isEqualTo(REQUEST);
            assertThat(status.canSend()).isTrue();
            assertThat(status.notice()).isEqualTo(new Notice(REQUEST, "Lan muốn nhắn tin với bạn. Bạn có thể chấp nhận,"
                    + " trả lời hoặc từ chối; Lan chưa biết bạn đã đọc tin cho đến khi bạn chấp nhận."));
            assertThat(status.encryption().notice().code()).isEqualTo("REQUEST_PLAIN");
            assertThat(status.canReact()).isFalse();
        }

        @Test
        @DisplayName("UTC-037 Lời mời của mình chưa được nhận, đã gửi 1 tin: WAITING, còn gửi được 2 tin")
        void utc037_waitingWithOneMessageSent() {
            givenStates(ACCEPTED, REQUEST);
            when(repository.countMessagesBy(CONV_ID, ME_ID)).thenReturn(1);

            Status status = service.open(ME, CONV_ID).status();

            assertThat(status.canSend()).isTrue();
            assertThat(status.notice()).isEqualTo(new Notice("WAITING", "Lan chưa nhận lời mời nhắn tin của bạn. Bạn còn"
                    + " gửi được 2 tin nhắn trước khi Lan trả lời."));
            assertThat(status.peerReadUpTo()).isZero();
        }

        @Test
        @DisplayName("UTC-038 Đã gửi 2 tin trong lời mời: WAITING, còn gửi được 1 tin")
        void utc038_waitingWithTwoMessagesSent() {
            givenStates(ACCEPTED, REQUEST);
            when(repository.countMessagesBy(CONV_ID, ME_ID)).thenReturn(2);

            Status status = service.open(ME, CONV_ID).status();

            assertThat(status.canSend()).isTrue();
            assertThat(status.notice().code()).isEqualTo("WAITING");
            assertThat(status.notice().text()).contains("Bạn còn gửi được 1 tin nhắn");
        }

        @Test
        @DisplayName("UTC-039 Đã gửi đủ 3 tin trong lời mời: WAITING_FULL, không gửi được nữa")
        void utc039_waitingFullAfterThreeMessages() {
            givenStates(ACCEPTED, REQUEST);
            when(repository.countMessagesBy(CONV_ID, ME_ID)).thenReturn(3);

            Status status = service.open(ME, CONV_ID).status();

            assertThat(status.canSend()).isFalse();
            assertThat(status.notice()).isEqualTo(new Notice("WAITING_FULL", "Lan chưa nhận lời mời nhắn tin của bạn."
                    + " Bạn gửi thêm được khi Lan trả lời."));
            assertThat(status.encryption().notice()).isNull();
            assertThat(status.canAttach()).isFalse();
        }

        @Test
        @DisplayName("UTC-040 Người kia đã từ chối (mới gửi 1 tin): WAITING_FULL, không biết là bị từ chối")
        void utc040_declinedRequestLooksLikeWaitingFull() {
            givenStates(ACCEPTED, DECLINED);
            when(repository.countMessagesBy(CONV_ID, ME_ID)).thenReturn(1);

            Status status = service.open(ME, CONV_ID).status();

            assertThat(status.canSend()).isFalse();
            assertThat(status.notice().code()).isEqualTo("WAITING_FULL");
            assertThat(status.notice().text()).doesNotContain("từ chối");
        }

        @Test
        @DisplayName("UTC-041 Người kia đã nhận lời và đọc tới tin 77: peerReadUpTo = 77 (\"Đã xem\")")
        void utc041_peerReadShowsOnceAccepted() {
            givenPeerRow(new MemberRow(ACCEPTED, 77, 0));

            assertThat(service.open(ME, CONV_ID).status().peerReadUpTo()).isEqualTo(77L);
        }

        @Test
        @DisplayName("UTC-042 Người kia chưa nhận lời nhưng đã đọc tới tin 77: peerReadUpTo = 0")
        void utc042_peerReadIsHiddenBeforeAccepting() {
            givenMyRow(row(ACCEPTED));
            givenPeerRow(new MemberRow(REQUEST, 77, 0));
            when(repository.countMessagesBy(CONV_ID, ME_ID)).thenReturn(1);

            assertThat(service.open(ME, CONV_ID).status().peerReadUpTo()).isZero();
        }

        @Test
        @DisplayName("UTC-043 Tài khoản người kia đã xóa: thông báo DELETED, không gửi được, peer.active = false")
        void utc043_deletedPeer() {
            givenPeer("USER", "DELETED");

            Conversation conversation = service.open(ME, CONV_ID);

            assertThat(conversation.status().notice()).isEqualTo(new Notice("DELETED",
                    "Tài khoản này đã bị xóa, không nhắn tin được nữa."));
            assertThat(conversation.status().canSend()).isFalse();
            assertThat(conversation.peer().active()).isFalse();
        }

        @Test
        @DisplayName("UTC-044 Mình đã chặn Lan: BLOCKED_BY_ME \"Bạn đã chặn Lan. Bỏ chặn để nhắn tin tiếp.\"")
        void utc044_blockedByMe() {
            when(repository.blocked(ME_ID, PEER_ID)).thenReturn(true);

            Status status = service.open(ME, CONV_ID).status();

            assertThat(status.blockedByMe()).isTrue();
            assertThat(status.canSend()).isFalse();
            assertThat(status.canReact()).isFalse();
            assertThat(status.notice()).isEqualTo(new Notice("BLOCKED_BY_ME",
                    "Bạn đã chặn Lan. Bỏ chặn để nhắn tin tiếp."));
        }

        @Test
        @DisplayName("UTC-045 Lan đã chặn mình: UNAVAILABLE như tài khoản bị khóa, blockedByMe = false")
        void utc045_blockedByThePeerReadsUnavailable() {
            when(repository.blocked(PEER_ID, ME_ID)).thenReturn(true);

            Status status = service.open(ME, CONV_ID).status();

            assertThat(status.blockedByMe()).isFalse();
            assertThat(status.canSend()).isFalse();
            assertThat(status.notice()).isEqualTo(new Notice("UNAVAILABLE", UNAVAILABLE));
        }

        @Test
        @DisplayName("UTC-046 Người kia bị khóa hoặc là Moderator: UNAVAILABLE, peer.active = false (2 lần)")
        void utc046_lockedOrStaffPeerIsUnavailable() {
            for (String[] peer : new String[][] {{"USER", "LOCKED"}, {"MODERATOR", "ACTIVE"}}) {
                givenPeer(peer[0], peer[1]);

                Conversation conversation = service.open(ME, CONV_ID);

                assertThat(conversation.status().notice().code()).isEqualTo("UNAVAILABLE");
                assertThat(conversation.status().canSend()).isFalse();
                assertThat(conversation.peer().active()).isFalse();
            }
        }

        @Test
        @DisplayName("UTC-047 Cả hai đã nhận lời, cả hai có khóa: E2EE, khóa 71/72, gửi tệp và thả cảm xúc được")
        void utc047_bothAcceptedWithKeysIsEncrypted() {
            givenKeys(true, true);

            Status status = service.open(ME, CONV_ID).status();

            assertThat(status.encryption()).isEqualTo(new Encryption("E2EE", MY_KEY, PEER_KEY, "BPublicKey72", null));
            assertThat(status.canAttach()).isTrue();
            assertThat(status.canReact()).isTrue();
        }

        @Test
        @DisplayName("UTC-048 Mình chưa có khóa, Lan có: PLAIN, thông báo NO_KEY_ME, không gửi tệp được")
        void utc048_noKeyOfMine() {
            givenKeys(false, true);

            Status status = service.open(ME, CONV_ID).status();

            assertThat(status.encryption().mode()).isEqualTo("PLAIN");
            assertThat(status.encryption().myKeyId()).isNull();
            assertThat(status.encryption().peerKeyId()).isEqualTo(PEER_KEY);
            assertThat(status.encryption().notice()).isEqualTo(new Notice("NO_KEY_ME", "Bạn chưa bật mã hóa đầu cuối."
                    + " Hãy bật để gửi được ảnh, video, ghi âm và để chỉ hai bạn đọc được tin nhắn."));
            assertThat(status.canAttach()).isFalse();
            assertThat(status.canReact()).isTrue();
        }

        @Test
        @DisplayName("UTC-049 Mình có khóa, Lan chưa có: PLAIN, thông báo NO_KEY_PEER nêu tên Lan")
        void utc049_noKeyOfThePeer() {
            givenKeys(true, false);

            Encryption encryption = service.open(ME, CONV_ID).status().encryption();

            assertThat(encryption.mode()).isEqualTo("PLAIN");
            assertThat(encryption.myKeyId()).isEqualTo(MY_KEY);
            assertThat(encryption.peerKeyId()).isNull();
            assertThat(encryption.peerPublicKey()).isNull();
            assertThat(encryption.notice()).isEqualTo(new Notice("NO_KEY_PEER", "Lan chưa bật mã hóa đầu cuối. Tin nhắn"
                    + " vẫn gửi được dạng chữ và chưa được mã hóa cho đến khi Lan bật."));
        }

        @Test
        @DisplayName("UTC-050 Còn là lời mời dù cả hai có khóa: PLAIN, thông báo REQUEST_PLAIN")
        void utc050_requestStaysPlainEvenWithKeys() {
            givenStates(REQUEST, ACCEPTED);
            givenKeys(true, true);

            Status status = service.open(ME, CONV_ID).status();

            assertThat(status.encryption().mode()).isEqualTo("PLAIN");
            assertThat(status.encryption().notice()).isEqualTo(new Notice("REQUEST_PLAIN", "Lời mời nhắn tin chưa được mã"
                    + " hóa và chỉ gửi được chữ. Khi hai bạn đã nhận lời và cùng bật mã hóa, tin nhắn sẽ được mã hóa đầu"
                    + " cuối."));
            assertThat(status.canAttach()).isFalse();
        }

        @Test
        @DisplayName("UTC-051 Lan bấm phím cách đây 5 giây: peerTyping = true (\"đang soạn tin\")")
        void utc051_peerTypedFiveSecondsAgo() {
            typing.useClock(Clock.fixed(NOW.minusSeconds(5), ZoneOffset.UTC));
            typing.ping(CONV_ID, PEER_ID);
            typing.useClock(Clock.fixed(NOW, ZoneOffset.UTC));

            assertThat(service.open(ME, CONV_ID).status().peerTyping()).isTrue();
        }

        @Test
        @DisplayName("UTC-052 Lan bấm phím cách đây đúng 6 giây: peerTyping = false (hết hạn)")
        void utc052_peerTypedSixSecondsAgo() {
            typing.useClock(Clock.fixed(NOW.minusSeconds(6), ZoneOffset.UTC));
            typing.ping(CONV_ID, PEER_ID);
            typing.useClock(Clock.fixed(NOW, ZoneOffset.UTC));

            assertThat(service.open(ME, CONV_ID).status().peerTyping()).isFalse();
        }

        @Test
        @DisplayName("UTC-053 Lan đang soạn nhưng mình chưa nhận lời: peerTyping = false")
        void utc053_typingIsHiddenWhileNotBothAccepted() {
            givenStates(REQUEST, ACCEPTED);
            typing.ping(CONV_ID, PEER_ID);

            assertThat(service.open(ME, CONV_ID).status().peerTyping()).isFalse();
        }

        @Test
        @DisplayName("UTC-054 Cuộc trò chuyện của người khác: 404, không đọc tin nào")
        void utc054_someoneElsesConversationIsNotFound() {
            givenOutsidersConversation();

            assertApiError(() -> service.open(ME, CONV_ID), HttpStatus.NOT_FOUND, CONVERSATION_NOT_FOUND);

            verify(repository, never()).messagesBefore(anyLong(), anyLong(), any(), anyInt());
        }

        @Test
        @DisplayName("UTC-055 Cuộc trò chuyện không tồn tại: 404")
        void utc055_missingConversationIsNotFound() {
            givenNoSuchConversation();

            assertApiError(() -> service.open(ME, CONV_ID), HttpStatus.NOT_FOUND, CONVERSATION_NOT_FOUND);
        }

        @Test
        @DisplayName("UTC-056 Khách: 401")
        void utc056_guestIsRefused() {
            assertApiError(() -> service.open(null, CONV_ID), HttpStatus.UNAUTHORIZED, GUEST);

            verify(repository, never()).conversation(anyLong());
        }

        @Test
        @DisplayName("UTC-057 Nhân viên (3 vai trò) và thành viên bị khóa: 403 MSG18 (4 lần)")
        void utc057_staffAndLockedAreRefused() {
            assertStaffAndLockedRefused(me -> service.open(me, CONV_ID));

            verify(repository, never()).conversation(anyLong());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("04. openWith – CHAT \"Nhắn tin\" từ tên một thành viên (UTC-058 → UTC-069)")
    class OpenWithMember {

        @Test
        @DisplayName("UTC-058 Chưa từng nhắn: chưa có id, thông báo NEW (tối đa 3 tin), gửi được, chưa có tin")
        void utc058_neverWrittenGivesANewConversation() {
            givenNoConversation();

            Conversation conversation = service.openWith(ME, PEER_ID);

            assertThat(conversation.id()).isNull();
            assertThat(conversation.peer()).isEqualTo(new Peer(PEER_ID, "Lan", "/media/avatar/2026/11/20.jpg",
                    List.of(), true));
            assertThat(conversation.status().state()).isNull();
            assertThat(conversation.status().canSend()).isTrue();
            assertThat(conversation.status().notice()).isEqualTo(new Notice("NEW", "Tin nhắn đầu tiên sẽ đến Lan dưới"
                    + " dạng lời mời. Trước khi Lan trả lời, bạn gửi được tối đa 3 tin nhắn."));
            assertThat(conversation.status().canReact()).isFalse();
            assertThat(conversation.messages()).isEmpty();
            assertThat(conversation.hasOlder()).isFalse();
            assertThat(conversation.reportReasons()).isEqualTo(REASONS);
            assertThat(conversation.limits().pendingMax()).isEqualTo(3);
            assertThat(conversation.serverTime()).isEqualTo(NOW);
            verify(repository, never()).messagesBefore(anyLong(), anyLong(), any(), anyInt());
        }

        @Test
        @DisplayName("UTC-059 Chưa từng nhắn, cả hai có khóa: PLAIN, REQUEST_PLAIN, chỉ có khóa của mình, không đọc khóa Lan")
        void utc059_beforeTheFirstMessageOnlyMyKeyIsKnown() {
            givenNoConversation();
            givenKeys(true, true);

            Encryption encryption = service.openWith(ME, PEER_ID).status().encryption();

            assertThat(encryption.mode()).isEqualTo("PLAIN");
            assertThat(encryption.myKeyId()).isEqualTo(MY_KEY);
            assertThat(encryption.peerKeyId()).isNull();
            assertThat(encryption.peerPublicKey()).isNull();
            assertThat(encryption.notice().code()).isEqualTo("REQUEST_PLAIN");
            verify(repository, never()).currentKey(PEER_ID);
        }

        @Test
        @DisplayName("UTC-060 Đã có cuộc trò chuyện 500 với Lan: mở đúng cuộc đó kèm các tin")
        void utc060_existingConversationIsOpened() {
            when(repository.messagesBefore(CONV_ID, 0, null, 31)).thenReturn(List.of(plain(901, PEER_ID, "Chào Minh")));

            Conversation conversation = service.openWith(ME, PEER_ID);

            assertThat(conversation.id()).isEqualTo(CONV_ID);
            assertThat(conversation.messages()).extracting(Message::id).containsExactly(901L);
            assertThat(conversation.status().state()).isEqualTo(ACCEPTED);
        }

        @Test
        @DisplayName("UTC-061 Nhắn cho chính mình: 400 \"Bạn không thể nhắn tin cho chính mình.\"")
        void utc061_selfIsRefused() {
            assertApiError(() -> service.openWith(ME, ME_ID), HttpStatus.BAD_REQUEST,
                    "Bạn không thể nhắn tin cho chính mình.");

            verify(repository, never()).conversationOf(anyLong(), anyLong());
        }

        @Test
        @DisplayName("UTC-062 Thành viên không tồn tại (id 999): 404 \"Không tìm thấy thành viên này.\"")
        void utc062_unknownMemberIsNotFound() {
            assertApiError(() -> service.openWith(ME, 999L), HttpStatus.NOT_FOUND, MEMBER_NOT_FOUND);
        }

        @Test
        @DisplayName("UTC-063 Tài khoản đã xóa, chưa từng nhắn: 404 \"Không tìm thấy thành viên này.\"")
        void utc063_deletedMemberWithoutConversationIsNotFound() {
            givenNoConversation();
            givenPeer("USER", "DELETED");

            assertApiError(() -> service.openWith(ME, PEER_ID), HttpStatus.NOT_FOUND, MEMBER_NOT_FOUND);
        }

        @Test
        @DisplayName("UTC-064 Tài khoản đã xóa, đã có cuộc trò chuyện: mở được, thông báo DELETED")
        void utc064_deletedMemberWithConversationOpensIt() {
            givenPeer("USER", "DELETED");

            Conversation conversation = service.openWith(ME, PEER_ID);

            assertThat(conversation.id()).isEqualTo(CONV_ID);
            assertThat(conversation.status().notice().code()).isEqualTo("DELETED");
            assertThat(conversation.status().canSend()).isFalse();
        }

        @Test
        @DisplayName("UTC-065 Thành viên bị khóa hoặc Moderator, chưa từng nhắn: UNAVAILABLE, không gửi được (2 lần)")
        void utc065_lockedOrStaffMemberIsUnavailable() {
            givenNoConversation();
            for (String[] peer : new String[][] {{"USER", "LOCKED"}, {"MODERATOR", "ACTIVE"}}) {
                givenPeer(peer[0], peer[1]);

                Conversation conversation = service.openWith(ME, PEER_ID);

                assertThat(conversation.id()).isNull();
                assertThat(conversation.peer().active()).isFalse();
                assertThat(conversation.status().canSend()).isFalse();
                assertThat(conversation.status().notice()).isEqualTo(new Notice("UNAVAILABLE", UNAVAILABLE));
                assertThat(conversation.status().encryption().notice()).isNull();
            }
        }

        @Test
        @DisplayName("UTC-066 Lan đã chặn mình, chưa từng nhắn: UNAVAILABLE")
        void utc066_blockedByTheMemberIsUnavailable() {
            givenNoConversation();
            when(repository.blocked(PEER_ID, ME_ID)).thenReturn(true);

            Status status = service.openWith(ME, PEER_ID).status();

            assertThat(status.notice().code()).isEqualTo("UNAVAILABLE");
            assertThat(status.blockedByMe()).isFalse();
            assertThat(status.canSend()).isFalse();
        }

        @Test
        @DisplayName("UTC-067 Mình đã chặn Lan, chưa từng nhắn: BLOCKED_BY_ME")
        void utc067_blockedByMeBeforeTheFirstMessage() {
            givenNoConversation();
            when(repository.blocked(ME_ID, PEER_ID)).thenReturn(true);

            Status status = service.openWith(ME, PEER_ID).status();

            assertThat(status.notice().code()).isEqualTo("BLOCKED_BY_ME");
            assertThat(status.blockedByMe()).isTrue();
            assertThat(status.canSend()).isFalse();
        }

        @Test
        @DisplayName("UTC-068 Khách: 401")
        void utc068_guestIsRefused() {
            assertApiError(() -> service.openWith(null, PEER_ID), HttpStatus.UNAUTHORIZED, GUEST);
        }

        @Test
        @DisplayName("UTC-069 Nhân viên (3 vai trò) và thành viên bị khóa: 403 MSG18 (4 lần)")
        void utc069_staffAndLockedAreRefused() {
            assertStaffAndLockedRefused(me -> service.openWith(me, PEER_ID));

            verify(repository, never()).conversationOf(anyLong(), anyLong());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("05. older – CHAT \"Xem tin cũ hơn\" (UTC-070 → UTC-081)")
    class OlderMessagesPage {

        @Test
        @DisplayName("UTC-070 Trước tin 100, không nêu số lượng: đọc 30 (+1) tin, giữ thứ tự cũ trước")
        void utc070_defaultPageBeforeAMessage() {
            when(repository.messagesBefore(CONV_ID, 0, 100L, 31)).thenReturn(List.of(
                    plain(97, PEER_ID, "Tin 97"), plain(98, ME_ID, "Tin 98"), plain(99, PEER_ID, "Tin 99")));

            OlderMessages older = service.older(ME, CONV_ID, 100L, null);

            assertThat(older.messages()).extracting(Message::id).containsExactly(97L, 98L, 99L);
            assertThat(older.messages()).extracting(Message::mine).containsExactly(false, true, false);
            assertThat(older.hasOlder()).isFalse();
        }

        @Test
        @DisplayName("UTC-071 Đã xóa cuộc trò chuyện tới tin 40: chỉ đọc tin sau tin 40")
        void utc071_readsOnlyAfterTheViewersClearedId() {
            givenMyRow(new MemberRow(ACCEPTED, 40, 40));

            service.older(ME, CONV_ID, 100L, null);

            verify(repository).messagesBefore(CONV_ID, 40, 100L, 31);
        }

        @Test
        @DisplayName("UTC-072 limit = 50: đọc 51 tin")
        void utc072_limitFifty() {
            service.older(ME, CONV_ID, 100L, 50);

            verify(repository).messagesBefore(CONV_ID, 0, 100L, 51);
        }

        @Test
        @DisplayName("UTC-073 limit = 0 và -3: tối thiểu 1 tin (đọc 2) (2 lần)")
        void utc073_limitBelowOneBecomesOne() {
            service.older(ME, CONV_ID, 100L, 0);
            service.older(ME, CONV_ID, 100L, -3);

            verify(repository, times(2)).messagesBefore(CONV_ID, 0, 100L, 2);
        }

        @Test
        @DisplayName("UTC-074 limit = 200 (tối đa): đọc 201 tin")
        void utc074_limitTwoHundredIsAllowed() {
            service.older(ME, CONV_ID, 100L, 200);

            verify(repository).messagesBefore(CONV_ID, 0, 100L, 201);
        }

        @Test
        @DisplayName("UTC-075 limit = 201 và 1000: giới hạn ở 200 (đọc 201) (2 lần)")
        void utc075_limitAboveTwoHundredIsCapped() {
            service.older(ME, CONV_ID, 100L, 201);
            service.older(ME, CONV_ID, 100L, 1000);

            verify(repository, times(2)).messagesBefore(CONV_ID, 0, 100L, 201);
        }

        @Test
        @DisplayName("UTC-076 limit = 5, kho trả 6 tin: bỏ tin cũ nhất, hiện 5, hasOlder = true")
        void utc076_oneMoreThanTheLimitMeansOlder() {
            when(repository.messagesBefore(CONV_ID, 0, 100L, 6)).thenReturn(plainRows(6));

            OlderMessages older = service.older(ME, CONV_ID, 100L, 5);

            assertThat(older.messages()).extracting(Message::id).containsExactly(2L, 3L, 4L, 5L, 6L);
            assertThat(older.hasOlder()).isTrue();
        }

        @Test
        @DisplayName("UTC-077 limit = 5, kho trả đúng 5 tin: hiện 5, hasOlder = false")
        void utc077_exactlyTheLimitMeansNoOlder() {
            when(repository.messagesBefore(CONV_ID, 0, 100L, 6)).thenReturn(plainRows(5));

            OlderMessages older = service.older(ME, CONV_ID, 100L, 5);

            assertThat(older.messages()).hasSize(5);
            assertThat(older.hasOlder()).isFalse();
        }

        @Test
        @DisplayName("UTC-078 Không nêu beforeId: đọc các tin mới nhất")
        void utc078_noBeforeIdReadsTheNewest() {
            service.older(ME, CONV_ID, null, null);

            verify(repository).messagesBefore(CONV_ID, 0, null, 31);
        }

        @Test
        @DisplayName("UTC-079 Cuộc trò chuyện của người khác: 404")
        void utc079_someoneElsesConversationIsNotFound() {
            givenOutsidersConversation();

            assertApiError(() -> service.older(ME, CONV_ID, 100L, null), HttpStatus.NOT_FOUND, CONVERSATION_NOT_FOUND);

            verify(repository, never()).messagesBefore(anyLong(), anyLong(), any(), anyInt());
        }

        @Test
        @DisplayName("UTC-080 Khách: 401")
        void utc080_guestIsRefused() {
            assertApiError(() -> service.older(null, CONV_ID, 100L, null), HttpStatus.UNAUTHORIZED, GUEST);
        }

        @Test
        @DisplayName("UTC-081 Nhân viên (3 vai trò) và thành viên bị khóa: 403 MSG18 (4 lần)")
        void utc081_staffAndLockedAreRefused() {
            assertStaffAndLockedRefused(me -> service.older(me, CONV_ID, 100L, null));

            verify(repository, never()).messagesBefore(anyLong(), anyLong(), any(), anyInt());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("06. updates – CHAT Cập nhật của cuộc trò chuyện đang mở (UTC-082 → UTC-096)")
    class ConversationUpdates {

        @Test
        @DisplayName("UTC-082 Sau tin 50, không có changedSince: tin mới (tối đa 100), changed rỗng, giờ máy chủ, trạng thái")
        void utc082_newMessagesAfterAnId() {
            when(repository.messagesAfter(CONV_ID, 0, 50, 100)).thenReturn(List.of(plain(51, PEER_ID, "Tin mới")));

            Updates updates = service.updates(ME, CONV_ID, 50, null);

            assertThat(updates.messages()).extracting(Message::id).containsExactly(51L);
            assertThat(updates.messages().get(0).mine()).isFalse();
            assertThat(updates.changed()).isEmpty();
            assertThat(updates.serverTime()).isEqualTo(NOW);
            assertThat(updates.status().canSend()).isTrue();
            verify(repository, never()).changedSince(anyLong(), anyLong(), any(), anyInt());
        }

        @Test
        @DisplayName("UTC-083 afterId âm (-7): đọc từ 0")
        void utc083_negativeAfterIdBecomesZero() {
            service.updates(ME, CONV_ID, -7, null);

            verify(repository).messagesAfter(CONV_ID, 0, 0, 100);
        }

        @Test
        @DisplayName("UTC-084 changedSince dạng ISO: đọc tin đã đổi từ 2 giây trước thời điểm đó (tối đa 200)")
        void utc084_isoChangedSinceReadsTwoSecondsEarlier() {
            service.updates(ME, CONV_ID, 50, "2026-11-20T02:59:50.123Z");

            verify(repository).changedSince(CONV_ID, 0, Instant.parse("2026-11-20T02:59:48.123Z"), 200);
        }

        @Test
        @DisplayName("UTC-085 changedSince dạng mili giây (1795143590123): như dạng ISO")
        void utc085_epochMillisecondsChangedSince() {
            service.updates(ME, CONV_ID, 50, "1795143590123");

            verify(repository).changedSince(CONV_ID, 0, Instant.parse("2026-11-20T02:59:48.123Z"), 200);
        }

        @Test
        @DisplayName("UTC-086 changedSince rỗng hoặc chỉ dấu cách: không đọc tin đã đổi (2 lần)")
        void utc086_blankChangedSinceIsNone() {
            service.updates(ME, CONV_ID, 50, "");
            service.updates(ME, CONV_ID, 50, "   ");

            verify(repository, never()).changedSince(anyLong(), anyLong(), any(), anyInt());
        }

        @Test
        @DisplayName("UTC-087 changedSince sai (\"hôm qua\", \"2026-13-40T00:00:00Z\", \"12:00\"): 400 \"Thời điểm không hợp lệ.\" (3 lần)")
        void utc087_invalidChangedSinceIsRefused() {
            for (String since : List.of("hôm qua", "2026-13-40T00:00:00Z", "12:00")) {
                assertApiError(() -> service.updates(ME, CONV_ID, 50, since), HttpStatus.BAD_REQUEST,
                        "Thời điểm không hợp lệ.");
            }

            verify(repository, never()).conversation(anyLong());
            verify(repository, never()).messagesAfter(anyLong(), anyLong(), anyLong(), anyInt());
        }

        @Test
        @DisplayName("UTC-088 changedSince 20 chữ số (vượt kiểu long): 400 \"Thời điểm không hợp lệ.\"")
        void utc088_tooLargeNumberIsRefused() {
            assertApiError(() -> service.updates(ME, CONV_ID, 50, "99999999999999999999"), HttpStatus.BAD_REQUEST,
                    "Thời điểm không hợp lệ.");
        }

        @Test
        @DisplayName("UTC-089 changedSince năm 1999: coi như 01/01/2000 (đọc từ 31/12/1999 23:59:58)")
        void utc089_veryOldChangedSinceIsClamped() {
            service.updates(ME, CONV_ID, 50, "1999-01-01T00:00:00Z");

            verify(repository).changedSince(CONV_ID, 0, Instant.parse("1999-12-31T23:59:58Z"), 200);
        }

        @Test
        @DisplayName("UTC-090 Đã xóa cuộc trò chuyện tới tin 40: cả tin mới lẫn tin đã đổi chỉ tính sau tin 40")
        void utc090_clearedIdIsUsedForBothReads() {
            givenMyRow(new MemberRow(ACCEPTED, 40, 40));

            service.updates(ME, CONV_ID, 50, "2026-11-20T02:59:50.123Z");

            verify(repository).messagesAfter(CONV_ID, 40, 50, 100);
            verify(repository).changedSince(CONV_ID, 40, Instant.parse("2026-11-20T02:59:48.123Z"), 200);
        }

        @Test
        @DisplayName("UTC-091 Hỏi cập nhật: ghi nhận Minh đang xem cuộc trò chuyện (để không gửi push)")
        void utc091_viewerIsMarkedAsViewing() {
            assertThat(viewers.viewing(CONV_ID, ME_ID)).isFalse();

            service.updates(ME, CONV_ID, 50, null);

            assertThat(viewers.viewing(CONV_ID, ME_ID)).isTrue();
            assertThat(viewers.viewing(CONV_ID, PEER_ID)).isFalse();
        }

        @Test
        @DisplayName("UTC-092 Tin đã sửa và tin đã thu hồi nằm trong changed")
        void utc092_changedMessagesAreReturned() {
            when(repository.changedSince(eq(CONV_ID), eq(0L), any(), eq(200))).thenReturn(List.of(
                    editedReply(45, PEER_ID, "Hẹn 8 giờ nhé", null), recalled(46, PEER_ID, "Gửi nhầm")));

            Updates updates = service.updates(ME, CONV_ID, 50, "2026-11-20T02:59:50.123Z");

            assertThat(updates.changed()).extracting(Message::id).containsExactly(45L, 46L);
            assertThat(updates.changed()).extracting(Message::edited).containsExactly(true, false);
            assertThat(updates.changed()).extracting(Message::recalled).containsExactly(false, true);
        }

        @Test
        @DisplayName("UTC-093 Lan đã đọc tới tin 905: status.peerReadUpTo = 905")
        void utc093_statusShowsWhatThePeerRead() {
            givenPeerRow(new MemberRow(ACCEPTED, 905, 0));

            assertThat(service.updates(ME, CONV_ID, 905, null).status().peerReadUpTo()).isEqualTo(905L);
        }

        @Test
        @DisplayName("UTC-094 Cuộc trò chuyện của người khác: 404, không ghi nhận đang xem")
        void utc094_someoneElsesConversationIsNotFound() {
            givenOutsidersConversation();

            assertApiError(() -> service.updates(ME, CONV_ID, 50, null), HttpStatus.NOT_FOUND, CONVERSATION_NOT_FOUND);

            assertThat(viewers.viewing(CONV_ID, ME_ID)).isFalse();
            verify(repository, never()).messagesAfter(anyLong(), anyLong(), anyLong(), anyInt());
        }

        @Test
        @DisplayName("UTC-095 Khách: 401")
        void utc095_guestIsRefused() {
            assertApiError(() -> service.updates(null, CONV_ID, 50, null), HttpStatus.UNAUTHORIZED, GUEST);
        }

        @Test
        @DisplayName("UTC-096 Nhân viên (3 vai trò) và thành viên bị khóa: 403 MSG18 (4 lần)")
        void utc096_staffAndLockedAreRefused() {
            assertStaffAndLockedRefused(me -> service.updates(me, CONV_ID, 50, null));

            assertThat(viewers.viewing(CONV_ID, ME_ID)).isFalse();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("07. message – CHAT Một tin nhắn (trích dẫn, kết quả tìm) (UTC-097 → UTC-105)")
    class OneMessage {

        @Test
        @DisplayName("UTC-097 Tin của Lan trong cuộc trò chuyện, đang hiện: trả về tin, mine = false")
        void utc097_shownMessageOfThePeer() {
            when(repository.message(901)).thenReturn(Optional.of(plain(901, PEER_ID, "Gần ga có quán cơm Hana")));
            when(repository.shown(901, 0)).thenReturn(true);

            Message message = service.message(ME, CONV_ID, 901);

            assertThat(message.id()).isEqualTo(901L);
            assertThat(message.content()).isEqualTo("Gần ga có quán cơm Hana");
            assertThat(message.mine()).isFalse();
        }

        @Test
        @DisplayName("UTC-098 Tin của chính mình: mine = true")
        void utc098_ownMessageIsMine() {
            when(repository.message(902)).thenReturn(Optional.of(plain(902, ME_ID, "Cảm ơn Lan")));
            when(repository.shown(902, 0)).thenReturn(true);

            assertThat(service.message(ME, CONV_ID, 902).mine()).isTrue();
        }

        @Test
        @DisplayName("UTC-099 Tin đã thu hồi: vẫn trả về, recalled = true, không có chữ")
        void utc099_recalledMessageIsReturnedAsRecalled() {
            when(repository.message(903)).thenReturn(Optional.of(recalled(903, PEER_ID, "Số tài khoản")));
            when(repository.shown(903, 0)).thenReturn(true);

            Message message = service.message(ME, CONV_ID, 903);

            assertThat(message.recalled()).isTrue();
            assertThat(message.content()).isNull();
        }

        @Test
        @DisplayName("UTC-100 Tin thuộc cuộc trò chuyện khác: 404 \"Không tìm thấy tin nhắn này.\"")
        void utc100_messageOfAnotherConversationIsNotFound() {
            MessageRow other = new MessageRow(904, 777, PEER_ID, "Tin ở chỗ khác", null, null, null, null, null, null,
                    null, null, NOW);
            when(repository.message(904)).thenReturn(Optional.of(other));
            when(repository.shown(904, 0)).thenReturn(true);

            assertApiError(() -> service.message(ME, CONV_ID, 904), HttpStatus.NOT_FOUND, MESSAGE_NOT_FOUND);
        }

        @Test
        @DisplayName("UTC-101 Tin không còn hiện với mình (đã xóa tới tin 40, hoặc người gửi bị khóa): 404")
        void utc101_hiddenMessageIsNotFound() {
            givenMyRow(new MemberRow(ACCEPTED, 40, 40));
            when(repository.message(30)).thenReturn(Optional.of(plain(30, PEER_ID, "Tin cũ")));
            when(repository.shown(30, 40)).thenReturn(false);

            assertApiError(() -> service.message(ME, CONV_ID, 30), HttpStatus.NOT_FOUND, MESSAGE_NOT_FOUND);

            verify(repository).shown(30, 40);
        }

        @Test
        @DisplayName("UTC-102 Tin không tồn tại: 404")
        void utc102_missingMessageIsNotFound() {
            assertApiError(() -> service.message(ME, CONV_ID, 999), HttpStatus.NOT_FOUND, MESSAGE_NOT_FOUND);
        }

        @Test
        @DisplayName("UTC-103 Cuộc trò chuyện của người khác: 404 \"Không tìm thấy cuộc trò chuyện này.\"")
        void utc103_someoneElsesConversationIsNotFound() {
            givenOutsidersConversation();
            when(repository.message(901)).thenReturn(Optional.of(plain(901, PEER_ID, "Chuyện riêng")));

            assertApiError(() -> service.message(ME, CONV_ID, 901), HttpStatus.NOT_FOUND, CONVERSATION_NOT_FOUND);

            verify(repository, never()).message(anyLong());
        }

        @Test
        @DisplayName("UTC-104 Khách: 401")
        void utc104_guestIsRefused() {
            assertApiError(() -> service.message(null, CONV_ID, 901), HttpStatus.UNAUTHORIZED, GUEST);
        }

        @Test
        @DisplayName("UTC-105 Nhân viên (3 vai trò) và thành viên bị khóa: 403 MSG18 (4 lần)")
        void utc105_staffAndLockedAreRefused() {
            assertStaffAndLockedRefused(me -> service.message(me, CONV_ID, 901));

            verify(repository, never()).message(anyLong());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("12. markRead – CHAT Đánh dấu đã đọc (\"Đã xem\") (UTC-195 → UTC-202)")
    class MarkRead {

        @Test
        @DisplayName("UTC-195 Đọc tới tin 120: ghi đã đọc tới 120, trả về số đếm còn lại")
        void utc195_readsUpToAMessageAndReturnsTheCounts() {
            when(repository.counts(ME_ID)).thenReturn(new int[] {1, 0});

            Counts counts = service.markRead(ME, CONV_ID, new ReadRequest(120L));

            verify(repository).markRead(CONV_ID, ME_ID, 120L);
            assertThat(counts).isEqualTo(new Counts(1, 0, null));
        }

        @Test
        @DisplayName("UTC-196 Không gửi thân hoặc upToId = null: đọc hết (Long.MAX_VALUE) (2 lần)")
        void utc196_noIdReadsEverything() {
            service.markRead(ME, CONV_ID, null);
            service.markRead(ME, CONV_ID, new ReadRequest(null));

            verify(repository, times(2)).markRead(CONV_ID, ME_ID, Long.MAX_VALUE);
        }

        @Test
        @DisplayName("UTC-197 upToId âm (-5): ghi 0 (không lùi)")
        void utc197_negativeIdBecomesZero() {
            service.markRead(ME, CONV_ID, new ReadRequest(-5L));

            verify(repository).markRead(CONV_ID, ME_ID, 0L);
        }

        @Test
        @DisplayName("UTC-198 Mình đã nhận lời: báo \"read\" tới các trang của mình và của Lan (\"Đã xem\")")
        void utc198_acceptedReaderTellsBothMembers() {
            service.markRead(ME, CONV_ID, new ReadRequest(120L));

            verify(live).publish(ME_ID, "chat", event("read", CONV_ID, null));
            verify(live).publish(PEER_ID, "chat", event("read", CONV_ID, null));
        }

        @Test
        @DisplayName("UTC-199 Mình chưa nhận lời (REQUEST) hoặc đã từ chối: chỉ báo trang của mình, Lan không biết (2 lần)")
        void utc199_notAcceptedReaderTellsOnlyThemselves() {
            for (String state : List.of(REQUEST, DECLINED)) {
                givenStates(state, ACCEPTED);
                service.markRead(ME, CONV_ID, new ReadRequest(120L));
            }

            verify(live, times(2)).publish(ME_ID, "chat", event("read", CONV_ID, null));
            verify(live, never()).publish(eq(PEER_ID), anyString(), any());
            verify(repository, times(2)).markRead(CONV_ID, ME_ID, 120L);
        }

        @Test
        @DisplayName("UTC-200 Cuộc trò chuyện của người khác: 404, không ghi, không báo")
        void utc200_someoneElsesConversationIsNotFound() {
            givenOutsidersConversation();

            assertApiError(() -> service.markRead(ME, CONV_ID, new ReadRequest(120L)), HttpStatus.NOT_FOUND,
                    CONVERSATION_NOT_FOUND);

            verify(repository, never()).markRead(anyLong(), anyLong(), anyLong());
            verifyNothingAnnounced();
        }

        @Test
        @DisplayName("UTC-201 Khách: 401, không ghi")
        void utc201_guestIsRefused() {
            assertApiError(() -> service.markRead(null, CONV_ID, new ReadRequest(120L)), HttpStatus.UNAUTHORIZED, GUEST);

            verify(repository, never()).markRead(anyLong(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("UTC-202 Nhân viên (3 vai trò) và thành viên bị khóa: 403 MSG18 (4 lần), không ghi")
        void utc202_staffAndLockedAreRefused() {
            assertStaffAndLockedRefused(me -> service.markRead(me, CONV_ID, new ReadRequest(120L)));

            verify(repository, never()).markRead(anyLong(), anyLong(), anyLong());
            verifyNothingAnnounced();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("13. accept – CHAT \"Chấp nhận\" lời mời nhắn tin (UTC-203 → UTC-211)")
    class AcceptRequest {

        @Test
        @DisplayName("UTC-203 Lời mời đang chờ (REQUEST): chuyển sang ACCEPTED, cả hai gửi được, không còn thông báo")
        void utc203_requestBecomesAccepted() {
            when(repository.member(CONV_ID, ME_ID)).thenReturn(Optional.of(row(REQUEST)), Optional.of(row(ACCEPTED)));

            Status status = service.accept(ME, CONV_ID);

            verify(repository).setState(CONV_ID, ME_ID, ACCEPTED, NOW);
            assertThat(status.state()).isEqualTo(ACCEPTED);
            assertThat(status.canSend()).isTrue();
            assertThat(status.notice()).isNull();
            assertThat(status.canReact()).isTrue();
        }

        @Test
        @DisplayName("UTC-204 Khóa dòng cuộc trò chuyện trước khi đổi trạng thái")
        void utc204_locksTheConversationRowFirst() {
            when(repository.member(CONV_ID, ME_ID)).thenReturn(Optional.of(row(REQUEST)), Optional.of(row(ACCEPTED)));

            service.accept(ME, CONV_ID);

            InOrder order = inOrder(repository);
            order.verify(repository).lockConversation(CONV_ID);
            order.verify(repository).setState(CONV_ID, ME_ID, ACCEPTED, NOW);
            verify(repository, never()).conversation(anyLong());
        }

        @Test
        @DisplayName("UTC-205 Lời mời đã từ chối trước đó (DECLINED): chấp nhận lại được")
        void utc205_declinedRequestCanStillBeAccepted() {
            when(repository.member(CONV_ID, ME_ID)).thenReturn(Optional.of(row(DECLINED)), Optional.of(row(ACCEPTED)));

            assertThat(service.accept(ME, CONV_ID).state()).isEqualTo(ACCEPTED);

            verify(repository).setState(CONV_ID, ME_ID, ACCEPTED, NOW);
        }

        @Test
        @DisplayName("UTC-206 Đã ACCEPTED (người viết lời mời bấm lại): không ghi gì, trả trạng thái WAITING")
        void utc206_alreadyAcceptedWritesNothing() {
            givenStates(ACCEPTED, REQUEST);
            when(repository.countMessagesBy(CONV_ID, ME_ID)).thenReturn(1);

            Status status = service.accept(ME, CONV_ID);

            verify(repository, never()).setState(anyLong(), anyLong(), anyString(), any());
            assertThat(status.notice().code()).isEqualTo("WAITING");
        }

        @Test
        @DisplayName("UTC-207 Báo \"status\" tới các trang của cả hai thành viên")
        void utc207_acceptIsAnnouncedToBothMembers() {
            when(repository.member(CONV_ID, ME_ID)).thenReturn(Optional.of(row(REQUEST)), Optional.of(row(ACCEPTED)));

            service.accept(ME, CONV_ID);

            verify(live).publish(ME_ID, "chat", event("status", CONV_ID, null));
            verify(live).publish(PEER_ID, "chat", event("status", CONV_ID, null));
        }

        @Test
        @DisplayName("UTC-208 Cả hai có khóa: sau khi chấp nhận cuộc trò chuyện thành E2EE")
        void utc208_acceptingWithKeysTurnsOnEncryption() {
            when(repository.member(CONV_ID, ME_ID)).thenReturn(Optional.of(row(REQUEST)), Optional.of(row(ACCEPTED)));
            givenKeys(true, true);

            Status status = service.accept(ME, CONV_ID);

            assertThat(status.encryption().mode()).isEqualTo("E2EE");
            assertThat(status.canAttach()).isTrue();
        }

        @Test
        @DisplayName("UTC-209 Cuộc trò chuyện của người khác: 404, không ghi, không báo")
        void utc209_someoneElsesConversationIsNotFound() {
            givenOutsidersConversation();

            assertApiError(() -> service.accept(ME, CONV_ID), HttpStatus.NOT_FOUND, CONVERSATION_NOT_FOUND);

            verify(repository, never()).setState(anyLong(), anyLong(), anyString(), any());
            verifyNothingAnnounced();
        }

        @Test
        @DisplayName("UTC-210 Khách: 401")
        void utc210_guestIsRefused() {
            assertApiError(() -> service.accept(null, CONV_ID), HttpStatus.UNAUTHORIZED, GUEST);

            verify(repository, never()).lockConversation(anyLong());
        }

        @Test
        @DisplayName("UTC-211 Nhân viên (3 vai trò) và thành viên bị khóa: 403 MSG18 (4 lần)")
        void utc211_staffAndLockedAreRefused() {
            assertStaffAndLockedRefused(me -> service.accept(me, CONV_ID));

            verify(repository, never()).setState(anyLong(), anyLong(), anyString(), any());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("14. decline – CHAT \"Từ chối\" lời mời nhắn tin (UTC-212 → UTC-217)")
    class DeclineRequest {

        @Test
        @DisplayName("UTC-212 Lời mời đang chờ: DECLINED rồi ẩn các tin tới nay, trả về số đếm còn lại")
        void utc212_requestIsDeclinedAndCleared() {
            givenStates(REQUEST, ACCEPTED);
            when(repository.counts(ME_ID)).thenReturn(new int[] {0, 0});

            Counts counts = service.decline(ME, CONV_ID);

            InOrder order = inOrder(repository);
            order.verify(repository).lockConversation(CONV_ID);
            order.verify(repository).setState(CONV_ID, ME_ID, DECLINED, NOW);
            order.verify(repository).clear(CONV_ID, ME_ID, NOW);
            assertThat(counts).isEqualTo(new Counts(0, 0, null));
        }

        @Test
        @DisplayName("UTC-213 Chỉ báo \"status\" tới các trang của mình; người viết lời mời không được báo")
        void utc213_declineIsNeverAnnouncedToTheWriter() {
            givenStates(REQUEST, ACCEPTED);

            service.decline(ME, CONV_ID);

            verify(live).publish(ME_ID, "chat", event("status", CONV_ID, null));
            verify(live, never()).publish(eq(PEER_ID), anyString(), any());
        }

        @Test
        @DisplayName("UTC-214 Cuộc trò chuyện đã nhận lời hoặc đã từ chối: 400 \"Chỉ từ chối được lời mời nhắn tin chưa trả lời.\" (2 lần)")
        void utc214_onlyAWaitingRequestCanBeDeclined() {
            for (String state : List.of(ACCEPTED, DECLINED)) {
                givenStates(state, ACCEPTED);
                assertApiError(() -> service.decline(ME, CONV_ID), HttpStatus.BAD_REQUEST,
                        "Chỉ từ chối được lời mời nhắn tin chưa trả lời.");
            }

            verifyNoStateWritten();
            verifyNothingAnnounced();
        }

        @Test
        @DisplayName("UTC-215 Cuộc trò chuyện của người khác: 404, không ghi")
        void utc215_someoneElsesConversationIsNotFound() {
            givenOutsidersConversation();

            assertApiError(() -> service.decline(ME, CONV_ID), HttpStatus.NOT_FOUND, CONVERSATION_NOT_FOUND);

            verifyNoStateWritten();
        }

        @Test
        @DisplayName("UTC-216 Khách: 401")
        void utc216_guestIsRefused() {
            assertApiError(() -> service.decline(null, CONV_ID), HttpStatus.UNAUTHORIZED, GUEST);

            verifyNoStateWritten();
        }

        @Test
        @DisplayName("UTC-217 Nhân viên (3 vai trò) và thành viên bị khóa: 403 MSG18 (4 lần)")
        void utc217_staffAndLockedAreRefused() {
            assertStaffAndLockedRefused(me -> service.decline(me, CONV_ID));

            verifyNoStateWritten();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("15. clear – CHAT \"Xóa cuộc trò chuyện\" với riêng mình (UTC-218 → UTC-224)")
    class ClearConversation {

        @Test
        @DisplayName("UTC-218 Cuộc trò chuyện đã nhận lời: ẩn các tin tới nay với riêng mình, không đổi trạng thái")
        void utc218_acceptedConversationIsClearedForTheViewer() {
            when(repository.counts(ME_ID)).thenReturn(new int[] {0, 0});

            Counts counts = service.clear(ME, CONV_ID);

            verify(repository).clear(CONV_ID, ME_ID, NOW);
            verify(repository, never()).clear(CONV_ID, PEER_ID, NOW);
            verify(repository, never()).setState(anyLong(), anyLong(), anyString(), any());
            assertThat(counts).isEqualTo(new Counts(0, 0, null));
        }

        @Test
        @DisplayName("UTC-219 Chỉ báo \"status\" tới các trang của mình")
        void utc219_clearIsAnnouncedToTheViewerOnly() {
            service.clear(ME, CONV_ID);

            verify(live).publish(ME_ID, "chat", event("status", CONV_ID, null));
            verify(live, never()).publish(eq(PEER_ID), anyString(), any());
        }

        @Test
        @DisplayName("UTC-220 Xóa một lời mời đang chờ: là từ chối (DECLINED + ẩn tin)")
        void utc220_clearingARequestDeclinesIt() {
            givenStates(REQUEST, ACCEPTED);

            service.clear(ME, CONV_ID);

            InOrder order = inOrder(repository);
            order.verify(repository).setState(CONV_ID, ME_ID, DECLINED, NOW);
            order.verify(repository).clear(CONV_ID, ME_ID, NOW);
        }

        @Test
        @DisplayName("UTC-221 Cuộc trò chuyện đã từ chối: chỉ ẩn tin, không đổi trạng thái")
        void utc221_clearingADeclinedConversationOnlyHidesMessages() {
            givenStates(DECLINED, ACCEPTED);

            service.clear(ME, CONV_ID);

            verify(repository).clear(CONV_ID, ME_ID, NOW);
            verify(repository, never()).setState(anyLong(), anyLong(), anyString(), any());
        }

        @Test
        @DisplayName("UTC-222 Cuộc trò chuyện của người khác: 404, không xóa")
        void utc222_someoneElsesConversationIsNotFound() {
            givenOutsidersConversation();

            assertApiError(() -> service.clear(ME, CONV_ID), HttpStatus.NOT_FOUND, CONVERSATION_NOT_FOUND);

            verifyNoStateWritten();
            verifyNothingAnnounced();
        }

        @Test
        @DisplayName("UTC-223 Khách: 401")
        void utc223_guestIsRefused() {
            assertApiError(() -> service.clear(null, CONV_ID), HttpStatus.UNAUTHORIZED, GUEST);

            verifyNoStateWritten();
        }

        @Test
        @DisplayName("UTC-224 Nhân viên (3 vai trò) và thành viên bị khóa: 403 MSG18 (4 lần)")
        void utc224_staffAndLockedAreRefused() {
            assertStaffAndLockedRefused(me -> service.clear(me, CONV_ID));

            verifyNoStateWritten();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("16. block – CHAT \"Chặn\" một thành viên (UTC-225 → UTC-234)")
    class BlockMember {

        @Test
        @DisplayName("UTC-225 Chặn Lan (đã nhận lời): khóa dòng cuộc trò chuyện rồi ghi chặn, không đổi trạng thái")
        void utc225_blockLocksTheConversationThenSaves() {
            service.block(ME, PEER_ID);

            InOrder order = inOrder(repository);
            order.verify(repository).lockConversationOf(ME_ID, PEER_ID);
            order.verify(repository).block(ME_ID, PEER_ID, NOW);
            verify(repository, never()).setState(anyLong(), anyLong(), anyString(), any());
            verify(repository, never()).clear(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("UTC-226 Chỉ báo \"status\" tới các trang của mình; Lan không được báo")
        void utc226_blockIsNeverAnnouncedToTheBlockedMember() {
            service.block(ME, PEER_ID);

            verify(live).publish(ME_ID, "chat", event("status", CONV_ID, null));
            verify(live, never()).publish(eq(PEER_ID), anyString(), any());
        }

        @Test
        @DisplayName("UTC-227 Chặn từ một lời mời Lan gửi: lời mời bị từ chối (DECLINED + ẩn tin)")
        void utc227_blockingFromARequestDeclinesIt() {
            givenStates(REQUEST, ACCEPTED);

            service.block(ME, PEER_ID);

            verify(repository).block(ME_ID, PEER_ID, NOW);
            verify(repository).setState(CONV_ID, ME_ID, DECLINED, NOW);
            verify(repository).clear(CONV_ID, ME_ID, NOW);
        }

        @Test
        @DisplayName("UTC-228 Chưa từng nhắn với Lan: vẫn chặn được, báo \"status\" không kèm id cuộc trò chuyện")
        void utc228_blockingWithoutAConversation() {
            givenNoConversation();

            service.block(ME, PEER_ID);

            verify(repository).block(ME_ID, PEER_ID, NOW);
            verify(live).publish(ME_ID, "chat", event("status", null, null));
            verify(live, never()).publish(eq(PEER_ID), anyString(), any());
        }

        @Test
        @DisplayName("UTC-229 Chặn chính mình: 400 \"Bạn không thể chặn chính mình.\"")
        void utc229_selfCannotBeBlocked() {
            assertApiError(() -> service.block(ME, ME_ID), HttpStatus.BAD_REQUEST, "Bạn không thể chặn chính mình.");

            verify(repository, never()).block(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("UTC-230 Thành viên không tồn tại (id 999): 404 \"Không tìm thấy thành viên này.\"")
        void utc230_unknownMemberIsNotFound() {
            assertApiError(() -> service.block(ME, 999L), HttpStatus.NOT_FOUND, MEMBER_NOT_FOUND);

            verify(repository, never()).block(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("UTC-231 Tài khoản bị khóa, đã xóa hoặc là Moderator: vẫn chặn được (3 lần)")
        void utc231_anyExistingAccountCanBeBlocked() {
            for (String[] peer : new String[][] {{"USER", "LOCKED"}, {"USER", "DELETED"}, {"MODERATOR", "ACTIVE"}}) {
                givenPeer(peer[0], peer[1]);
                service.block(ME, PEER_ID);
            }

            verify(repository, times(3)).block(ME_ID, PEER_ID, NOW);
        }

        @Test
        @DisplayName("UTC-232 Chặn hai lần: không lỗi (lần hai bị bỏ qua ở CSDL)")
        void utc232_blockingTwiceIsNoError() {
            service.block(ME, PEER_ID);
            service.block(ME, PEER_ID);

            verify(repository, times(2)).block(ME_ID, PEER_ID, NOW);
        }

        @Test
        @DisplayName("UTC-233 Khách: 401")
        void utc233_guestIsRefused() {
            assertApiError(() -> service.block(null, PEER_ID), HttpStatus.UNAUTHORIZED, GUEST);

            verify(repository, never()).block(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("UTC-234 Nhân viên (3 vai trò) và thành viên bị khóa: 403 MSG18 (4 lần)")
        void utc234_staffAndLockedAreRefused() {
            assertStaffAndLockedRefused(me -> service.block(me, PEER_ID));

            verify(repository, never()).block(anyLong(), anyLong(), any());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("17. unblock – CHAT \"Bỏ chặn\" (UTC-235 → UTC-239)")
    class UnblockMember {

        @Test
        @DisplayName("UTC-235 Bỏ chặn Lan: xóa dòng chặn, chỉ báo \"status\" tới các trang của mình")
        void utc235_unblockRemovesTheBlock() {
            service.unblock(ME, PEER_ID);

            verify(repository).unblock(ME_ID, PEER_ID);
            verify(live).publish(ME_ID, "chat", event("status", CONV_ID, null));
            verify(live, never()).publish(eq(PEER_ID), anyString(), any());
        }

        @Test
        @DisplayName("UTC-236 Chưa từng nhắn với Lan: báo \"status\" không kèm id cuộc trò chuyện")
        void utc236_unblockWithoutAConversation() {
            givenNoConversation();

            service.unblock(ME, PEER_ID);

            verify(repository).unblock(ME_ID, PEER_ID);
            verify(live).publish(ME_ID, "chat", event("status", null, null));
        }

        @Test
        @DisplayName("UTC-237 Bỏ chặn người chưa từng chặn (id 999): không lỗi, không đổi trạng thái cuộc trò chuyện")
        void utc237_unblockingSomeoneNeverBlockedIsNoError() {
            service.unblock(ME, 999L);

            verify(repository).unblock(ME_ID, 999L);
            verify(repository, never()).setState(anyLong(), anyLong(), anyString(), any());
        }

        @Test
        @DisplayName("UTC-238 Khách: 401")
        void utc238_guestIsRefused() {
            assertApiError(() -> service.unblock(null, PEER_ID), HttpStatus.UNAUTHORIZED, GUEST);

            verify(repository, never()).unblock(anyLong(), anyLong());
        }

        @Test
        @DisplayName("UTC-239 Nhân viên (3 vai trò) và thành viên bị khóa: 403 MSG18 (4 lần)")
        void utc239_staffAndLockedAreRefused() {
            assertStaffAndLockedRefused(me -> service.unblock(me, PEER_ID));

            verify(repository, never()).unblock(anyLong(), anyLong());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("18. blocks – CHAT \"Người đã chặn\" (UTC-240 → UTC-244)")
    class BlockList {

        @Test
        @DisplayName("UTC-240 Hai người đã chặn: giữ thứ tự mới nhất trước, kèm tên, ảnh, tag, thời điểm chặn")
        void utc240_blockedMembersNewestFirst() {
            when(repository.blocks(ME_ID)).thenReturn(List.of(
                    new BlockRow(PEER_ID, "Lan", "/media/avatar/2026/11/20.jpg", "USER", "ACTIVE", NOW.minusSeconds(60)),
                    new BlockRow(OUTSIDER_ID, "Hùng", null, "USER", "ACTIVE", NOW.minus(Duration.ofDays(3)))));
            when(repository.tagsOf(any())).thenReturn(Map.of(PEER_ID, List.of(new Tag("Mentor", "#2E7D32"))));

            List<BlockedMember> blocks = service.blocks(ME);

            assertThat(blocks).containsExactly(
                    new BlockedMember(new Peer(PEER_ID, "Lan", "/media/avatar/2026/11/20.jpg",
                            List.of(new Tag("Mentor", "#2E7D32")), true), NOW.minusSeconds(60)),
                    new BlockedMember(new Peer(OUTSIDER_ID, "Hùng", null, List.of(), true),
                            NOW.minus(Duration.ofDays(3))));
            verify(repository).tagsOf(Set.of(PEER_ID, OUTSIDER_ID));
        }

        @Test
        @DisplayName("UTC-241 Người bị chặn đã bị khóa hoặc là Moderator: active = false")
        void utc241_lockedOrStaffBlockedMemberIsNotActive() {
            when(repository.blocks(ME_ID)).thenReturn(List.of(
                    new BlockRow(21, "Khóa", null, "USER", "LOCKED", NOW),
                    new BlockRow(22, "Kiểm duyệt", null, "MODERATOR", "ACTIVE", NOW)));

            assertThat(service.blocks(ME)).extracting(b -> b.peer().active()).containsExactly(false, false);
        }

        @Test
        @DisplayName("UTC-242 Chưa chặn ai: danh sách rỗng")
        void utc242_noBlockGivesAnEmptyList() {
            assertThat(service.blocks(ME)).isEmpty();

            verify(repository).tagsOf(Set.of());
        }

        @Test
        @DisplayName("UTC-243 Khách: 401")
        void utc243_guestIsRefused() {
            assertApiError(() -> service.blocks(null), HttpStatus.UNAUTHORIZED, GUEST);

            verify(repository, never()).blocks(anyLong());
        }

        @Test
        @DisplayName("UTC-244 Nhân viên (3 vai trò) và thành viên bị khóa: 403 MSG18 (4 lần)")
        void utc244_staffAndLockedAreRefused() {
            assertStaffAndLockedRefused(me -> service.blocks(me));

            verify(repository, never()).blocks(anyLong());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("19. typing – CHAT \"đang soạn tin\" (UTC-245 → UTC-253)")
    class Typing {

        @Test
        @DisplayName("UTC-245 Cả hai đã nhận lời: ghi nhận đang soạn, chỉ báo \"typing\" tới Lan")
        void utc245_typingReachesOnlyThePeer() {
            service.typing(ME, CONV_ID);

            assertThat(typing.typing(CONV_ID, ME_ID)).isTrue();
            verify(live).publish(PEER_ID, "chat", event("typing", CONV_ID, null));
            verify(live, never()).publish(eq(ME_ID), anyString(), any());
        }

        @Test
        @DisplayName("UTC-246 Lan mở cuộc trò chuyện ngay sau đó: thấy Minh \"đang soạn tin\" (peerTyping = true)")
        void utc246_thePeerSeesTheTyping() {
            service.typing(ME, CONV_ID);

            Status seenByLan = service.open(principal(PEER_ID, "USER"), CONV_ID).status();

            assertThat(seenByLan.peerTyping()).isTrue();
        }

        @Test
        @DisplayName("UTC-247 Chưa cả hai nhận lời (mình REQUEST / Lan REQUEST / Lan DECLINED): không ghi, không báo, không lỗi (3 lần)")
        void utc247_notBothAcceptedDoesNothing() {
            for (String[] states : new String[][] {{REQUEST, ACCEPTED}, {ACCEPTED, REQUEST}, {ACCEPTED, DECLINED}}) {
                givenStates(states[0], states[1]);
                service.typing(ME, CONV_ID);
            }

            assertThat(typing.typing(CONV_ID, ME_ID)).isFalse();
            verifyNothingAnnounced();
        }

        @Test
        @DisplayName("UTC-248 Mình đã chặn Lan: không ghi, không báo")
        void utc248_blockedByMeDoesNothing() {
            when(repository.blocked(ME_ID, PEER_ID)).thenReturn(true);

            service.typing(ME, CONV_ID);

            assertThat(typing.typing(CONV_ID, ME_ID)).isFalse();
            verifyNothingAnnounced();
        }

        @Test
        @DisplayName("UTC-249 Lan đã chặn mình: không ghi, không báo (Lan không bị lộ là đã chặn)")
        void utc249_blockedByThePeerDoesNothing() {
            when(repository.blocked(PEER_ID, ME_ID)).thenReturn(true);

            service.typing(ME, CONV_ID);

            assertThat(typing.typing(CONV_ID, ME_ID)).isFalse();
            verifyNothingAnnounced();
        }

        @Test
        @DisplayName("UTC-250 Lan bị khóa, đã xóa hoặc là Moderator: không ghi, không báo (3 lần)")
        void utc250_unavailablePeerDoesNothing() {
            for (String[] peer : new String[][] {{"USER", "LOCKED"}, {"USER", "DELETED"}, {"MODERATOR", "ACTIVE"}}) {
                givenPeer(peer[0], peer[1]);
                service.typing(ME, CONV_ID);
            }

            assertThat(typing.typing(CONV_ID, ME_ID)).isFalse();
            verifyNothingAnnounced();
        }

        @Test
        @DisplayName("UTC-251 Cuộc trò chuyện của người khác: 404")
        void utc251_someoneElsesConversationIsNotFound() {
            givenOutsidersConversation();

            assertApiError(() -> service.typing(ME, CONV_ID), HttpStatus.NOT_FOUND, CONVERSATION_NOT_FOUND);

            verifyNothingAnnounced();
        }

        @Test
        @DisplayName("UTC-252 Khách: 401")
        void utc252_guestIsRefused() {
            assertApiError(() -> service.typing(null, CONV_ID), HttpStatus.UNAUTHORIZED, GUEST);
        }

        @Test
        @DisplayName("UTC-253 Nhân viên (3 vai trò) và thành viên bị khóa: 403 MSG18 (4 lần)")
        void utc253_staffAndLockedAreRefused() {
            assertStaffAndLockedRefused(me -> service.typing(me, CONV_ID));

            verifyNothingAnnounced();
            verifyNoInteractions(events);
        }
    }
}
