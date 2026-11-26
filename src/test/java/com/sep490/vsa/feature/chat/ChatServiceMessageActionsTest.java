package com.sep490.vsa.feature.chat;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.live.LiveEvents;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.feature.chat.ChatDtos.EditRequest;
import com.sep490.vsa.feature.chat.ChatDtos.Envelope;
import com.sep490.vsa.feature.chat.ChatDtos.Message;
import com.sep490.vsa.feature.chat.ChatDtos.Reaction;
import com.sep490.vsa.feature.chat.ChatDtos.ReportRequest;
import com.sep490.vsa.feature.chat.ChatRepository.Account;
import com.sep490.vsa.feature.chat.ChatRepository.ConversationRow;
import com.sep490.vsa.feature.chat.ChatRepository.FileRow;
import com.sep490.vsa.feature.chat.ChatRepository.KeyRow;
import com.sep490.vsa.feature.chat.ChatRepository.MemberRow;
import com.sep490.vsa.feature.chat.ChatRepository.MessageRow;
import com.sep490.vsa.feature.chat.ChatRepository.ReactionRow;
import com.sep490.vsa.feature.media.MediaStorage;
import com.sep490.vsa.feature.moderation.ContentFilter;
import com.sep490.vsa.feature.moderation.ContentFilter.LinkProblem;
import com.sep490.vsa.feature.report.ContentReportService;
import com.sep490.vsa.feature.report.ContentReportService.Outcome;
import com.sep490.vsa.feature.report.ContentReportService.Target;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the message actions of "Tin nhắn" in {@link ChatService}: "Sửa" ({@code edit}), "Thu hồi"
 * ({@code recall}), "Thả cảm xúc" ({@code react}), "Báo cáo tin nhắn" ({@code reportMessage}) and the two calls the
 * Moderator's report queue makes ({@code lockForReport}, {@code removeReported}), with the live announcements of these
 * changes. Rules from CLAUDE.md ("Tin nhắn") and the flow tests ChatUtilitiesFlowTest and ChatEncryptionFlowTest.
 * <p>
 * The database is not used: {@link ChatRepository}, {@link ContentFilter}, {@link ContentReportService},
 * {@link SystemSettings}, {@link MediaStorage}, {@link ChatTyping}, {@link ChatViewers},
 * {@link ApplicationEventPublisher} and {@link LiveEvents} are mocks; the clock is fixed at 20/11/2026 12:00 Japan
 * time. Transaction synchronization is switched on by hand, so the "after commit" work (deleting stored files) can be
 * fired or left out like a commit or a rollback.
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("ChatService – Tin nhắn: sửa, thu hồi, thả cảm xúc, báo cáo tin nhắn (CHAT)")
class ChatServiceMessageActionsTest {

    private static final long ME_ID = 10L;
    private static final long PEER_ID = 20L;
    private static final long OUTSIDER_ID = 30L;
    private static final long CONV_ID = 500L;
    private static final long MY_MSG = 700L;
    private static final long PEER_MSG = 701L;
    private static final long REASON_ID = 3L;
    /** 12:00 in Japan, 20/11/2026. */
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");

    private static final ConversationRow CONVERSATION = new ConversationRow(CONV_ID, ME_ID, PEER_ID, ME_ID);
    private static final AppUserPrincipal ME = principal(ME_ID, "USER");

    private static final String THUMBS_UP = Character.toString(0x1F44D);
    private static final String HEART = "❤️";
    private static final String LAUGH = Character.toString(0x1F602);
    private static final String WOW = Character.toString(0x1F62E);
    private static final String SAD = Character.toString(0x1F622);
    private static final String PRAY = Character.toString(0x1F64F);

    private static final String MSG18 = "Bạn không có quyền thực hiện thao tác này.";
    private static final String LOGIN = "Bạn cần đăng nhập để nhắn tin.";
    private static final String BLOCKED_BY_ME = "Bạn đã chặn Bình. Bỏ chặn để nhắn tin tiếp.";

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
        typing = mock(ChatTyping.class);
        viewers = mock(ChatViewers.class);
        events = mock(ApplicationEventPublisher.class);
        live = mock(LiveEvents.class);
        service = new ChatService(repository, filter, reports, settings, storage, typing, viewers, events, live,
                Clock.fixed(NOW, ZoneOffset.UTC));

        when(settings.getInt(SystemSettings.NEW_ACCOUNT_DAYS, 7)).thenReturn(7);
        when(repository.account(ME_ID)).thenReturn(Optional.of(member(ME_ID, "An")));
        when(repository.account(PEER_ID)).thenReturn(Optional.of(member(PEER_ID, "Bình")));
        when(repository.account(OUTSIDER_ID)).thenReturn(Optional.of(member(OUTSIDER_ID, "Người ngoài")));
        when(repository.conversation(CONV_ID)).thenReturn(Optional.of(CONVERSATION));
        when(repository.lockConversation(CONV_ID)).thenReturn(Optional.of(CONVERSATION));
        when(repository.member(CONV_ID, ME_ID)).thenReturn(Optional.of(state("ACCEPTED", 0)));
        when(repository.member(CONV_ID, PEER_ID)).thenReturn(Optional.of(state("ACCEPTED", 0)));
        when(repository.shown(anyLong(), anyLong())).thenReturn(true);
        when(repository.message(MY_MSG)).thenReturn(Optional.of(myPlain()));
        when(repository.message(PEER_MSG)).thenReturn(Optional.of(peerPlain()));
        when(repository.lockConversationOf(ME_ID, PEER_ID)).thenReturn(Optional.of(CONVERSATION));
        when(repository.reportIdOf(anyLong(), anyLong())).thenReturn(Optional.of(91L));

        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ---------------------------------------------------------------- helpers

    private static AppUserPrincipal principal(long id, String role) {
        return new AppUserPrincipal(id, "user" + id + "@example.org", "User " + id, role, "/");
    }

    private static Account account(long id, String name, String role, String status) {
        return new Account(id, name, null, role, status, NOW.minus(Duration.ofDays(30)));
    }

    private static Account member(long id, String name) {
        return account(id, name, "USER", "ACTIVE");
    }

    private static MemberRow state(String state, long clearedId) {
        return new MemberRow(state, 0, clearedId);
    }

    private static MessageRow plain(long id, long senderId, String content, Instant createdAt) {
        return new MessageRow(id, CONV_ID, senderId, content, null, null, null, null, null, null, null, null,
                createdAt);
    }

    private static MessageRow myPlain() {
        return plain(MY_MSG, ME_ID, "Mai 7 giờ gặp ở ga nhé", NOW.minus(Duration.ofMinutes(5)));
    }

    private static MessageRow peerPlain() {
        return plain(PEER_MSG, PEER_ID, "Mình đỗ JLPT N2 rồi!", NOW.minus(Duration.ofMinutes(5)));
    }

    private static MessageRow edited(MessageRow r, String content, Envelope e) {
        return new MessageRow(r.id(), r.conversationId(), r.senderId(), content, e == null ? null : e.ciphertext(),
                e == null ? null : e.iv(), e == null ? null : e.senderKeyId(), e == null ? null : e.recipientKeyId(),
                e == null ? null : e.frankingTag(), r.replyToId(), NOW, null, r.createdAt());
    }

    private static MessageRow recalled(MessageRow r) {
        return new MessageRow(r.id(), r.conversationId(), r.senderId(), r.content(), r.ciphertext(), r.iv(),
                r.senderKeyId(), r.recipientKeyId(), r.frankingTag(), r.replyToId(), r.editedAt(), NOW, r.createdAt());
    }

    private static MessageRow inConversation(MessageRow r, long conversationId) {
        return new MessageRow(r.id(), conversationId, r.senderId(), r.content(), r.ciphertext(), r.iv(),
                r.senderKeyId(), r.recipientKeyId(), r.frankingTag(), r.replyToId(), r.editedAt(), r.recalledAt(),
                r.createdAt());
    }

    /** An encrypted message with the envelope given (keys 41 → 42 unless the envelope says otherwise). */
    private static MessageRow encrypted(long id, long senderId, Envelope e, Instant createdAt) {
        return new MessageRow(id, CONV_ID, senderId, null, e.ciphertext(), e.iv(), e.senderKeyId(),
                e.recipientKeyId(), e.frankingTag(), null, null, null, createdAt);
    }

    private static byte[] bytes(int n, int seed) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (seed * 31 + i * 7);
        }
        return b;
    }

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    private static Envelope envelope(int ciphertextBytes, int ivBytes, Long senderKey, Long recipientKey, String tag) {
        return new Envelope(b64(bytes(ciphertextBytes, 7)), b64(bytes(ivBytes, 8)), senderKey, recipientKey, tag);
    }

    /** A well-formed envelope written with the two current keys 41 (An) and 42 (Bình). */
    private static Envelope goodEnvelope() {
        return envelope(64, 12, 41L, 42L, "cd".repeat(32));
    }

    private static KeyRow key(long id, long userId) {
        return new KeyRow(id, userId, "BPub" + id, "wrapped", "salt", "iv", 600_000, NOW.minus(Duration.ofDays(3)),
                null);
    }

    private void bothHaveKeys() {
        when(repository.currentKey(ME_ID)).thenReturn(Optional.of(key(41, ME_ID)));
        when(repository.currentKey(PEER_ID)).thenReturn(Optional.of(key(42, PEER_ID)));
    }

    /** The contract's franking tag, computed with the JDK only (not with ChatCrypto). */
    private static String tagOf(byte[] fk, long conversationId, long senderId, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(fk, "HmacSHA256"));
            mac.update(("asvsj-frank-v1|" + conversationId + "|" + senderId + "|").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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

    private static Map<String, Object> liveData(String kind, Long messageId) {
        Map<String, Object> data = new HashMap<>();
        data.put("kind", kind);
        data.put("conversationId", CONV_ID);
        if (messageId != null) {
            data.put("messageId", messageId);
        }
        return data;
    }

    private static void commit() {
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.beforeCommit(false);
            s.afterCommit();
            s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
    }

    private static void rollback() {
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
    }

    private static FileRow file(long id, long messageId, String kind, String key) {
        return new FileRow(id, CONV_ID, ME_ID, messageId, kind, key, 1000, NOW.minus(Duration.ofMinutes(6)), false);
    }

    private void verifyNotEdited() {
        verify(repository, never()).insertEdit(any(), any());
        verify(repository, never()).updateBody(anyLong(), any(), any(), any());
        verifyNoInteractions(live);
    }

    private void verifyNotRecalled() {
        verify(repository, never()).recall(anyLong(), any());
        verify(repository, never()).removeReactions(anyLong());
        verify(repository, never()).markFilesDeleted(any(), any());
        verifyNoInteractions(live, storage);
    }

    private void verifyNoReaction() {
        verify(repository, never()).setReaction(anyLong(), anyLong(), any(), any());
        verify(repository, never()).removeReaction(anyLong(), anyLong());
        verify(repository, never()).touch(anyLong(), any());
        verifyNoInteractions(live);
    }

    private void verifyNotReported() {
        verify(reports, never()).report(any(), anyLong(), any(), any());
        verify(repository, never()).insertEvidence(anyLong(), anyLong(), any(), any());
        verify(repository, never()).block(anyLong(), anyLong(), any());
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. edit – Sửa tin nhắn (UTC-254 → UTC-297)")
    class EditMessage {

        @Test
        @DisplayName("UTC-254 Sửa tin nhắn chữ của mình trong 15 phút: bản cũ lưu vào lịch sử, nội dung mới được lưu")
        void utc254_ownPlainMessageIsEditedAndOldVersionKept() {
            MessageRow before = myPlain();
            when(repository.message(MY_MSG)).thenReturn(Optional.of(before),
                    Optional.of(edited(before, "Mai 8 giờ gặp ở ga nhé", null)));

            Message result = service.edit(ME, CONV_ID, MY_MSG, new EditRequest("Mai 8 giờ gặp ở ga nhé", null));

            InOrder order = inOrder(repository);
            order.verify(repository).insertEdit(before, NOW);
            order.verify(repository).updateBody(MY_MSG, "Mai 8 giờ gặp ở ga nhé", null, NOW);
            assertThat(result.id()).isEqualTo(MY_MSG);
            assertThat(result.content()).isEqualTo("Mai 8 giờ gặp ở ga nhé");
            assertThat(result.edited()).isTrue();
            assertThat(result.mine()).isTrue();
            assertThat(result.recalled()).isFalse();
        }

        @Test
        @DisplayName("UTC-255 Nội dung sửa được cắt khoảng trắng hai đầu trước khi kiểm tra và lưu")
        void utc255_newTextIsStripped() {
            service.edit(ME, CONV_ID, MY_MSG, new EditRequest("   Mai 8 giờ gặp ở ga nhé \n ", null));

            verify(filter).bannedKeyword("Mai 8 giờ gặp ở ga nhé");
            verify(filter).linkProblem("Mai 8 giờ gặp ở ga nhé", ME_ID);
            verify(repository).updateBody(MY_MSG, "Mai 8 giờ gặp ở ga nhé", null, NOW);
        }

        @Test
        @DisplayName("UTC-256 Nội dung mới đúng 2.000 ký tự: được lưu")
        void utc256_exactly2000CharactersAreSaved() {
            String text = "x".repeat(2000);

            service.edit(ME, CONV_ID, MY_MSG, new EditRequest(text, null));

            verify(repository).updateBody(MY_MSG, text, null, NOW);
        }

        @Test
        @DisplayName("UTC-257 Nội dung mới 2.001 ký tự: lỗi \"Tin nhắn tối đa 2.000 ký tự.\", không lưu gì")
        void utc257_2001CharactersAreRefused() {
            assertApiError(() -> service.edit(ME, CONV_ID, MY_MSG, new EditRequest("x".repeat(2001), null)),
                    HttpStatus.BAD_REQUEST, "Tin nhắn tối đa 2.000 ký tự.");
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-258 Nội dung rỗng, chỉ có khoảng trắng hoặc null: lỗi \"Hãy nhập tin nhắn.\"")
        void utc258_blankTextIsRefused() {
            for (String blank : new String[] {"", "   \n\t", null}) {
                assertApiError(() -> service.edit(ME, CONV_ID, MY_MSG, new EditRequest(blank, null)),
                        HttpStatus.BAD_REQUEST, "Hãy nhập tin nhắn.");
            }
            verifyNotEdited();
            verifyNoInteractions(filter);
        }

        @Test
        @DisplayName("UTC-259 Không gửi nội dung (request = null) cho tin nhắn chữ: lỗi \"Hãy nhập tin nhắn.\"")
        void utc259_missingRequestIsRefusedForPlainMessage() {
            assertApiError(() -> service.edit(ME, CONV_ID, MY_MSG, null), HttpStatus.BAD_REQUEST, "Hãy nhập tin nhắn.");
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-260 Nội dung mới chứa từ khóa cấm: lỗi MSG36 nêu từ khóa, không lưu gì")
        void utc260_bannedKeywordIsRefused() {
            when(filter.bannedKeyword("Bên mình có việc nhẹ lương cao")).thenReturn(Optional.of("việc nhẹ lương cao"));

            assertApiError(() -> service.edit(ME, CONV_ID, MY_MSG,
                            new EditRequest("Bên mình có việc nhẹ lương cao", null)), HttpStatus.BAD_REQUEST,
                    "Chưa gửi được: tin nhắn chứa từ khóa không được phép “việc nhẹ lương cao”. Hãy sửa rồi gửi lại.");
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-261 Nội dung mới vi phạm BR-22 (link rút gọn, link hoặc số điện thoại khi chưa có tag): báo đúng lỗi")
        void utc261_linkAndContactRuleIsApplied() {
            String text = "Xem ở https://example.com nhé";
            for (LinkProblem problem : LinkProblem.values()) {
                when(filter.linkProblem(text, ME_ID)).thenReturn(Optional.of(problem));

                assertApiError(() -> service.edit(ME, CONV_ID, MY_MSG, new EditRequest(text, null)),
                        HttpStatus.BAD_REQUEST, problem.message());
            }
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-262 Vừa có từ khóa cấm vừa có link: báo từ khóa trước, không kiểm tra link")
        void utc262_keywordIsCheckedBeforeLinks() {
            String text = "việc nhẹ lương cao https://example.com";
            when(filter.bannedKeyword(text)).thenReturn(Optional.of("việc nhẹ lương cao"));
            when(filter.linkProblem(text, ME_ID)).thenReturn(Optional.of(LinkProblem.LINK_NEEDS_TAG));

            ApiException e = apiError(() -> service.edit(ME, CONV_ID, MY_MSG, new EditRequest(text, null)));

            assertThat(e.getMessage()).contains("“việc nhẹ lương cao”");
            verify(filter, never()).linkProblem(anyString(), anyLong());
        }

        @Test
        @DisplayName("UTC-263 Tin nhắn chữ nhưng gửi bản mã hóa: lỗi \"chưa được mã hóa đầu cuối\", không lọc nội dung")
        void utc263_envelopeForPlainMessageIsRefused() {
            assertApiError(() -> service.edit(ME, CONV_ID, MY_MSG, new EditRequest(null, goodEnvelope())),
                    HttpStatus.BAD_REQUEST, ChatService.NOT_ENCRYPTED);
            verifyNotEdited();
            verifyNoInteractions(filter);
        }

        @Test
        @DisplayName("UTC-264 Sửa tin nhắn của người kia: 403 \"Bạn chỉ sửa hoặc thu hồi được tin nhắn của chính mình.\"")
        void utc264_someoneElsesMessageIsForbidden() {
            assertApiError(() -> service.edit(ME, CONV_ID, PEER_MSG, new EditRequest("Sửa tin người khác", null)),
                    HttpStatus.FORBIDDEN, ChatService.NOT_MINE);
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-265 Tin nhắn đã thu hồi: lỗi \"Tin nhắn này đã được thu hồi.\"")
        void utc265_recalledMessageCannotBeEdited() {
            when(repository.message(MY_MSG)).thenReturn(Optional.of(recalled(myPlain())));

            assertApiError(() -> service.edit(ME, CONV_ID, MY_MSG, new EditRequest("Sửa lại", null)),
                    HttpStatus.BAD_REQUEST, ChatService.RECALLED);
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-266 Tin nhắn không tồn tại: 404 \"Không tìm thấy tin nhắn này.\"")
        void utc266_missingMessageIsNotFound() {
            assertApiError(() -> service.edit(ME, CONV_ID, 999L, new EditRequest("Sửa", null)),
                    HttpStatus.NOT_FOUND, ChatService.MESSAGE_NOT_FOUND);
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-267 Tin nhắn thuộc cuộc trò chuyện khác: 404 \"Không tìm thấy tin nhắn này.\"")
        void utc267_messageOfAnotherConversationIsNotFound() {
            when(repository.message(MY_MSG)).thenReturn(Optional.of(inConversation(myPlain(), 501L)));

            assertApiError(() -> service.edit(ME, CONV_ID, MY_MSG, new EditRequest("Sửa", null)),
                    HttpStatus.NOT_FOUND, ChatService.MESSAGE_NOT_FOUND);
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-268 Tin nhắn không còn hiện với người sửa (đã xóa cuộc trò chuyện phía mình): 404")
        void utc268_messageNotShownToViewerIsNotFound() {
            when(repository.member(CONV_ID, ME_ID)).thenReturn(Optional.of(state("ACCEPTED", 750)));
            when(repository.shown(MY_MSG, 750L)).thenReturn(false);

            assertApiError(() -> service.edit(ME, CONV_ID, MY_MSG, new EditRequest("Sửa", null)),
                    HttpStatus.NOT_FOUND, ChatService.MESSAGE_NOT_FOUND);
            verify(repository).shown(MY_MSG, 750L);
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-269 Cuộc trò chuyện không tồn tại: 404 \"Không tìm thấy cuộc trò chuyện này.\"")
        void utc269_missingConversationIsNotFound() {
            assertApiError(() -> service.edit(ME, 999L, MY_MSG, new EditRequest("Sửa", null)),
                    HttpStatus.NOT_FOUND, ChatService.CONVERSATION_NOT_FOUND);
            verify(repository, never()).message(anyLong());
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-270 Người ngoài cuộc trò chuyện: 404, không đọc tin nhắn")
        void utc270_outsiderGetsNotFound() {
            assertApiError(() -> service.edit(principal(OUTSIDER_ID, "USER"), CONV_ID, MY_MSG,
                    new EditRequest("Xen vào", null)), HttpStatus.NOT_FOUND, ChatService.CONVERSATION_NOT_FOUND);
            verify(repository, never()).message(anyLong());
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-271 Khách chưa đăng nhập: 401 \"Bạn cần đăng nhập để nhắn tin.\"")
        void utc271_guestIsUnauthorized() {
            assertApiError(() -> service.edit(null, CONV_ID, MY_MSG, new EditRequest("Sửa", null)),
                    HttpStatus.UNAUTHORIZED, LOGIN);
            verifyNoInteractions(repository, filter, live);
        }

        @Test
        @DisplayName("UTC-272 Phiên đăng nhập trỏ tới tài khoản không còn: 401 \"Bạn chưa đăng nhập.\"")
        void utc272_sessionOfMissingAccountIsUnauthorized() {
            when(repository.account(ME_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.edit(ME, CONV_ID, MY_MSG, new EditRequest("Sửa", null)),
                    HttpStatus.UNAUTHORIZED, "Bạn chưa đăng nhập.");
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-273 Moderator, BTV tiếng Nhật, Admin: 403 MSG18")
        void utc273_staffIsForbidden() {
            for (String role : new String[] {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"}) {
                when(repository.account(ME_ID)).thenReturn(Optional.of(account(ME_ID, "An", role, "ACTIVE")));

                assertApiError(() -> service.edit(principal(ME_ID, role), CONV_ID, MY_MSG, new EditRequest("Sửa", null)),
                        HttpStatus.FORBIDDEN, MSG18);
            }
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-274 Tài khoản của người sửa đã bị khóa: 403 MSG18")
        void utc274_lockedAccountIsForbidden() {
            when(repository.account(ME_ID)).thenReturn(Optional.of(account(ME_ID, "An", "USER", "LOCKED")));

            assertApiError(() -> service.edit(ME, CONV_ID, MY_MSG, new EditRequest("Sửa", null)),
                    HttpStatus.FORBIDDEN, MSG18);
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-275 Tin nhắn gửi đúng 15 phút trước: vẫn sửa được")
        void utc275_exactlyFifteenMinutesIsStillAllowed() {
            when(repository.message(MY_MSG)).thenReturn(Optional.of(
                    plain(MY_MSG, ME_ID, "Mai 7 giờ", NOW.minus(Duration.ofMinutes(15)))));

            service.edit(ME, CONV_ID, MY_MSG, new EditRequest("Mai 8 giờ", null));

            verify(repository).updateBody(MY_MSG, "Mai 8 giờ", null, NOW);
        }

        @Test
        @DisplayName("UTC-276 Tin nhắn gửi 15 phút 1 giây trước: lỗi \"Chỉ sửa được tin nhắn trong 15 phút sau khi gửi.\"")
        void utc276_fifteenMinutesAndOneSecondIsTooLate() {
            when(repository.message(MY_MSG)).thenReturn(Optional.of(
                    plain(MY_MSG, ME_ID, "Mai 7 giờ", NOW.minus(Duration.ofMinutes(15).plusSeconds(1)))));

            assertApiError(() -> service.edit(ME, CONV_ID, MY_MSG, new EditRequest("Sửa muộn", null)),
                    HttpStatus.BAD_REQUEST, ChatService.EDIT_TOO_LATE);
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-277 Đã sửa 4 lần: lần sửa thứ 5 vẫn được")
        void utc277_fifthEditIsAllowed() {
            when(repository.countEdits(MY_MSG)).thenReturn(4);

            service.edit(ME, CONV_ID, MY_MSG, new EditRequest("Lần thứ năm", null));

            verify(repository).insertEdit(myPlain(), NOW);
            verify(repository).updateBody(MY_MSG, "Lần thứ năm", null, NOW);
        }

        @Test
        @DisplayName("UTC-278 Đã sửa 5 lần: lỗi \"Mỗi tin nhắn chỉ sửa được tối đa 5 lần.\"")
        void utc278_sixthEditIsRefused() {
            when(repository.countEdits(MY_MSG)).thenReturn(5);

            assertApiError(() -> service.edit(ME, CONV_ID, MY_MSG, new EditRequest("Lần thứ sáu", null)),
                    HttpStatus.BAD_REQUEST, ChatService.EDIT_LIMIT);
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-279 Người sửa đã chặn người kia: lỗi \"Bạn đã chặn Bình. Bỏ chặn để nhắn tin tiếp.\"")
        void utc279_blockedByMeIsRefused() {
            when(repository.blocked(ME_ID, PEER_ID)).thenReturn(true);

            assertApiError(() -> service.edit(ME, CONV_ID, MY_MSG, new EditRequest("Sửa", null)),
                    HttpStatus.BAD_REQUEST, BLOCKED_BY_ME);
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-280 Người kia đã chặn người sửa: 403 \"Bạn không thể nhắn tin cho thành viên này.\"")
        void utc280_blockedByPeerIsForbidden() {
            when(repository.blocked(PEER_ID, ME_ID)).thenReturn(true);

            assertApiError(() -> service.edit(ME, CONV_ID, MY_MSG, new EditRequest("Sửa sau khi bị chặn", null)),
                    HttpStatus.FORBIDDEN, ChatService.UNAVAILABLE);
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-281 Tài khoản người kia bị khóa hoặc là tài khoản nhân viên: 403 \"Bạn không thể nhắn tin…\"")
        void utc281_unavailablePeerIsForbidden() {
            for (Account peer : List.of(account(PEER_ID, "Bình", "USER", "LOCKED"),
                    account(PEER_ID, "Bình", "MODERATOR", "ACTIVE"))) {
                when(repository.account(PEER_ID)).thenReturn(Optional.of(peer));

                assertApiError(() -> service.edit(ME, CONV_ID, MY_MSG, new EditRequest("Sửa", null)),
                        HttpStatus.FORBIDDEN, ChatService.UNAVAILABLE);
            }
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-282 Tài khoản người kia đã bị xóa: lỗi \"Tài khoản này đã bị xóa, không nhắn tin được nữa.\"")
        void utc282_deletedPeerIsRefused() {
            when(repository.account(PEER_ID)).thenReturn(Optional.of(account(PEER_ID, "Bình", "USER", "DELETED")));

            assertApiError(() -> service.edit(ME, CONV_ID, MY_MSG, new EditRequest("Sửa", null)),
                    HttpStatus.BAD_REQUEST, ChatService.DELETED);
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-283 Lời mời chưa được nhận (còn lượt hoặc đã bị từ chối): người viết vẫn sửa được tin của mình")
        void utc283_pendingRequestStillAllowsEditing() {
            for (String peerState : new String[] {"REQUEST", "DECLINED"}) {
                when(repository.member(CONV_ID, PEER_ID)).thenReturn(Optional.of(state(peerState, 0)));
                when(repository.countMessagesBy(CONV_ID, ME_ID)).thenReturn(1);

                service.edit(ME, CONV_ID, MY_MSG, new EditRequest("Chào Bình, mình là An", null));
            }
            verify(repository, times(2)).updateBody(MY_MSG, "Chào Bình, mình là An", null, NOW);
        }

        @Test
        @DisplayName("UTC-284 Vừa bị chặn vừa quá 15 phút: báo lỗi chặn trước (thứ tự kiểm tra)")
        void utc284_cutOffIsCheckedBeforeTime() {
            when(repository.blocked(PEER_ID, ME_ID)).thenReturn(true);
            when(repository.message(MY_MSG)).thenReturn(Optional.of(
                    plain(MY_MSG, ME_ID, "Tin cũ", NOW.minus(Duration.ofHours(2)))));

            assertApiError(() -> service.edit(ME, CONV_ID, MY_MSG, new EditRequest("Sửa", null)),
                    HttpStatus.FORBIDDEN, ChatService.UNAVAILABLE);
        }

        @Test
        @DisplayName("UTC-285 Vừa quá 15 phút vừa đã sửa 5 lần: báo quá thời gian, không đếm số lần sửa")
        void utc285_timeIsCheckedBeforeEditCount() {
            when(repository.countEdits(MY_MSG)).thenReturn(5);
            when(repository.message(MY_MSG)).thenReturn(Optional.of(
                    plain(MY_MSG, ME_ID, "Tin cũ", NOW.minus(Duration.ofMinutes(20)))));

            assertApiError(() -> service.edit(ME, CONV_ID, MY_MSG, new EditRequest("Sửa", null)),
                    HttpStatus.BAD_REQUEST, ChatService.EDIT_TOO_LATE);
            verify(repository, never()).countEdits(anyLong());
        }

        @Test
        @DisplayName("UTC-286 Tin nhắn mã hóa sửa bằng bản mã hóa mới với khóa hiện tại: lưu bản mới, giữ bản cũ")
        void utc286_encryptedMessageIsEditedWithNewEnvelope() {
            bothHaveKeys();
            Envelope old = envelope(64, 12, 41L, 42L, "ab".repeat(32));
            MessageRow before = encrypted(MY_MSG, ME_ID, old, NOW.minus(Duration.ofMinutes(3)));
            Envelope fresh = goodEnvelope();
            when(repository.message(MY_MSG)).thenReturn(Optional.of(before), Optional.of(edited(before, null, fresh)));

            Message result = service.edit(ME, CONV_ID, MY_MSG, new EditRequest(null, fresh));

            verify(repository).insertEdit(before, NOW);
            verify(repository).updateBody(MY_MSG, null, fresh, NOW);
            assertThat(result.content()).isNull();
            assertThat(result.encrypted()).isEqualTo(fresh);
            assertThat(result.edited()).isTrue();
        }

        @Test
        @DisplayName("UTC-287 Bản mã hóa gửi base64 không có dấu '=': được lưu ở dạng base64 chuẩn")
        void utc287_envelopeIsStoredInCanonicalBase64() {
            bothHaveKeys();
            when(repository.message(MY_MSG)).thenReturn(Optional.of(
                    encrypted(MY_MSG, ME_ID, goodEnvelope(), NOW.minus(Duration.ofMinutes(3)))));
            String ciphertext = b64(bytes(64, 7));
            Envelope unpadded = new Envelope(ciphertext.replace("=", ""), b64(bytes(12, 8)), 41L, 42L,
                    "cd".repeat(32));

            service.edit(ME, CONV_ID, MY_MSG, new EditRequest(null, unpadded));

            assertThat(unpadded.ciphertext()).doesNotEndWith("=");
            verify(repository).updateBody(MY_MSG, null, new Envelope(ciphertext, b64(bytes(12, 8)), 41L, 42L,
                    "cd".repeat(32)), NOW);
        }

        @Test
        @DisplayName("UTC-288 Tin nhắn mã hóa nhưng gửi chữ thường hoặc không gửi gì: lỗi \"đã được mã hóa đầu cuối\"")
        void utc288_plainTextForEncryptedMessageIsRefused() {
            bothHaveKeys();
            when(repository.message(MY_MSG)).thenReturn(Optional.of(
                    encrypted(MY_MSG, ME_ID, goodEnvelope(), NOW.minus(Duration.ofMinutes(3)))));

            assertApiError(() -> service.edit(ME, CONV_ID, MY_MSG, new EditRequest("Hẹn 8 giờ", null)),
                    HttpStatus.BAD_REQUEST, ChatService.ENCRYPTED_ONLY);
            assertApiError(() -> service.edit(ME, CONV_ID, MY_MSG, null),
                    HttpStatus.BAD_REQUEST, ChatService.ENCRYPTED_ONLY);
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-289 Bản mã hóa ghi khóa cũ (của người gửi hoặc người nhận): 409 \"Khóa mã hóa vừa thay đổi. Hãy gửi lại.\"")
        void utc289_staleKeyIdsConflict() {
            bothHaveKeys();
            when(repository.message(MY_MSG)).thenReturn(Optional.of(
                    encrypted(MY_MSG, ME_ID, goodEnvelope(), NOW.minus(Duration.ofMinutes(3)))));

            for (Envelope stale : List.of(envelope(64, 12, 40L, 42L, "cd".repeat(32)),
                    envelope(64, 12, 41L, 39L, "cd".repeat(32)))) {
                assertApiError(() -> service.edit(ME, CONV_ID, MY_MSG, new EditRequest(null, stale)),
                        HttpStatus.CONFLICT, ChatService.KEY_CHANGED);
            }
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-290 Người kia không còn khóa hiện tại: 409 \"Khóa mã hóa vừa thay đổi. Hãy gửi lại.\"")
        void utc290_missingCurrentKeyConflicts() {
            when(repository.currentKey(ME_ID)).thenReturn(Optional.of(key(41, ME_ID)));
            when(repository.message(MY_MSG)).thenReturn(Optional.of(
                    encrypted(MY_MSG, ME_ID, goodEnvelope(), NOW.minus(Duration.ofMinutes(3)))));

            assertApiError(() -> service.edit(ME, CONV_ID, MY_MSG, new EditRequest(null, goodEnvelope())),
                    HttpStatus.CONFLICT, ChatService.KEY_CHANGED);
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-291 Bản mã hóa sai dạng (iv, độ dài ciphertext, base64, franking tag, thiếu khóa): lỗi \"Tin nhắn mã hóa không hợp lệ\"")
        void utc291_malformedEnvelopeIsRefused() {
            bothHaveKeys();
            when(repository.message(MY_MSG)).thenReturn(Optional.of(
                    encrypted(MY_MSG, ME_ID, goodEnvelope(), NOW.minus(Duration.ofMinutes(3)))));
            String tag = "cd".repeat(32);
            List<Envelope> bad = List.of(
                    envelope(64, 11, 41L, 42L, tag),                                        // iv 11 bytes
                    envelope(47, 12, 41L, 42L, tag),                                        // ciphertext 47 bytes
                    envelope(ChatService.CIPHERTEXT_MAX_BYTES + 1, 12, 41L, 42L, tag),       // 32,769 bytes
                    new Envelope("***", b64(bytes(12, 8)), 41L, 42L, tag),                  // not base64
                    envelope(64, 12, 41L, 42L, "AB".repeat(32)),                            // upper-case tag
                    envelope(64, 12, 41L, 42L, "ab"),                                       // short tag
                    envelope(64, 12, null, 42L, tag),                                       // no sender key
                    envelope(64, 12, 41L, null, tag),                                       // no recipient key
                    new Envelope(null, b64(bytes(12, 8)), 41L, 42L, tag),                   // no ciphertext
                    new Envelope(b64(bytes(64, 7)), null, 41L, 42L, tag),                   // no iv
                    new Envelope("A".repeat(44_004), b64(bytes(12, 8)), 41L, 42L, tag));     // text over 44,000
            for (Envelope envelope : bad) {
                assertApiError(() -> service.edit(ME, CONV_ID, MY_MSG, new EditRequest(null, envelope)),
                        HttpStatus.BAD_REQUEST, ChatService.BAD_ENVELOPE);
            }
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-292 Ciphertext đúng 48 byte và đúng 32.768 byte: hợp lệ")
        void utc292_ciphertextSizeBoundariesAreAccepted() {
            bothHaveKeys();
            when(repository.message(MY_MSG)).thenReturn(Optional.of(
                    encrypted(MY_MSG, ME_ID, goodEnvelope(), NOW.minus(Duration.ofMinutes(3)))));

            for (int size : new int[] {ChatService.CIPHERTEXT_MIN_BYTES, ChatService.CIPHERTEXT_MAX_BYTES}) {
                Envelope envelope = envelope(size, 12, 41L, 42L, "cd".repeat(32));
                service.edit(ME, CONV_ID, MY_MSG, new EditRequest(null, envelope));
                verify(repository).updateBody(MY_MSG, null, envelope, NOW);
            }
            assertThat(ChatService.CIPHERTEXT_MIN_BYTES).isEqualTo(48);
        }

        @Test
        @DisplayName("UTC-293 Sửa tin nhắn mã hóa: máy chủ không đọc được nên không lọc từ khóa và link")
        void utc293_encryptedEditSkipsTextFilters() {
            bothHaveKeys();
            when(repository.message(MY_MSG)).thenReturn(Optional.of(
                    encrypted(MY_MSG, ME_ID, goodEnvelope(), NOW.minus(Duration.ofMinutes(3)))));

            service.edit(ME, CONV_ID, MY_MSG, new EditRequest(null, goodEnvelope()));

            verifyNoInteractions(filter);
        }

        @Test
        @DisplayName("UTC-294 Báo trực tiếp \"changed\" cho các trang của người sửa và của người kia (người kia còn thấy tin)")
        void utc294_changeIsAnnouncedToBothMembers() {
            service.edit(ME, CONV_ID, MY_MSG, new EditRequest("Mai 8 giờ", null));

            verify(live).publish(ME_ID, "chat", liveData("changed", MY_MSG));
            verify(live).publish(PEER_ID, "chat", liveData("changed", MY_MSG));
        }

        @Test
        @DisplayName("UTC-295 Người kia đã xóa cuộc trò chuyện (không còn thấy tin): chỉ báo cho các trang của người sửa")
        void utc295_changeHiddenFromPeerIsAnnouncedToActorOnly() {
            when(repository.member(CONV_ID, PEER_ID)).thenReturn(Optional.of(state("ACCEPTED", MY_MSG)));

            service.edit(ME, CONV_ID, MY_MSG, new EditRequest("Mai 8 giờ", null));

            verify(live).publish(ME_ID, "chat", liveData("changed", MY_MSG));
            verify(live, never()).publish(eq(PEER_ID), anyString(), any());
        }

        @Test
        @DisplayName("UTC-296 Sửa tin không tạo thông báo tin nhắn mới (không phát ChatMessageSent)")
        void utc296_editPublishesNoNewMessageEvent() {
            service.edit(ME, CONV_ID, MY_MSG, new EditRequest("Mai 8 giờ", null));

            verifyNoInteractions(events, typing, viewers, storage, reports);
        }

        @Test
        @DisplayName("UTC-297 Khóa dòng cuộc trò chuyện (FOR UPDATE) trước khi đọc và ghi tin nhắn")
        void utc297_conversationRowIsLockedFirst() {
            service.edit(ME, CONV_ID, MY_MSG, new EditRequest("Mai 8 giờ", null));

            InOrder order = inOrder(repository);
            order.verify(repository).lockConversation(CONV_ID);
            order.verify(repository).message(MY_MSG);
            order.verify(repository).insertEdit(any(), eq(NOW));
            verify(repository, never()).conversation(anyLong());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. recall – Thu hồi tin nhắn (UTC-298 → UTC-314)")
    class RecallMessage {

        @Test
        @DisplayName("UTC-298 Thu hồi tin của mình: đánh dấu thu hồi, xóa cảm xúc, trả về \"Tin nhắn đã được thu hồi\"")
        void utc298_ownMessageIsRecalled() {
            when(repository.message(MY_MSG)).thenReturn(Optional.of(myPlain()), Optional.of(recalled(myPlain())));

            Message result = service.recall(ME, CONV_ID, MY_MSG);

            verify(repository).recall(MY_MSG, NOW);
            verify(repository).removeReactions(MY_MSG);
            assertThat(result.id()).isEqualTo(MY_MSG);
            assertThat(result.recalled()).isTrue();
            assertThat(result.content()).isNull();
            assertThat(result.encrypted()).isNull();
            assertThat(result.reactions()).isEmpty();
            assertThat(result.fileIds()).isEmpty();
            assertThat(result.mine()).isTrue();
        }

        @Test
        @DisplayName("UTC-299 Thu hồi không giới hạn thời gian: tin gửi 30 ngày trước vẫn thu hồi được")
        void utc299_recallHasNoTimeLimit() {
            when(repository.message(MY_MSG)).thenReturn(Optional.of(
                    plain(MY_MSG, ME_ID, "Số tài khoản của mình là...", NOW.minus(Duration.ofDays(30)))));

            service.recall(ME, CONV_ID, MY_MSG);

            verify(repository).recall(MY_MSG, NOW);
        }

        @Test
        @DisplayName("UTC-300 Vẫn thu hồi được sau khi bị chặn, khi đã chặn người kia hoặc khi tài khoản người kia đã xóa")
        void utc300_recallWorksWhenConversationIsCutOff() {
            when(repository.blocked(PEER_ID, ME_ID)).thenReturn(true);
            service.recall(ME, CONV_ID, MY_MSG);
            when(repository.blocked(ME_ID, PEER_ID)).thenReturn(true);
            service.recall(ME, CONV_ID, MY_MSG);
            when(repository.account(PEER_ID)).thenReturn(Optional.of(account(PEER_ID, "Bình", "USER", "DELETED")));
            service.recall(ME, CONV_ID, MY_MSG);

            verify(repository, times(3)).recall(MY_MSG, NOW);
        }

        @Test
        @DisplayName("UTC-301 Tin có 2 tệp: đánh dấu xóa tệp ngay, xóa dữ liệu khỏi kho lưu trữ sau khi commit")
        void utc301_filesAreDeletedAfterCommit() {
            when(repository.filesOf(MY_MSG)).thenReturn(List.of(file(31, MY_MSG, "IMAGE", "chat/2026/11/a.bin"),
                    file(32, MY_MSG, "IMAGE", "chat/2026/11/b.bin")));

            service.recall(ME, CONV_ID, MY_MSG);

            verify(repository).markFilesDeleted(List.of(31L, 32L), NOW);
            verifyNoInteractions(storage);
            commit();
            verify(storage).delete("chat/2026/11/a.bin");
            verify(storage).delete("chat/2026/11/b.bin");
        }

        @Test
        @DisplayName("UTC-302 Giao dịch bị hủy (rollback): tệp trong kho lưu trữ không bị xóa")
        void utc302_rollbackKeepsStoredFiles() {
            when(repository.filesOf(MY_MSG)).thenReturn(List.of(file(31, MY_MSG, "VIDEO", "chat/2026/11/v.bin")));

            service.recall(ME, CONV_ID, MY_MSG);
            rollback();

            verifyNoInteractions(storage);
        }

        @Test
        @DisplayName("UTC-303 Xóa tệp đầu tiên khỏi kho bị lỗi: vẫn xóa tệp thứ hai, không báo lỗi")
        void utc303_storageFailureDoesNotStopOtherDeletes() {
            when(repository.filesOf(MY_MSG)).thenReturn(List.of(file(31, MY_MSG, "IMAGE", "chat/2026/11/a.bin"),
                    file(32, MY_MSG, "IMAGE", "chat/2026/11/b.bin")));
            doThrow(new IllegalStateException("R2 down")).when(storage).delete("chat/2026/11/a.bin");

            service.recall(ME, CONV_ID, MY_MSG);

            assertThatCode(ChatServiceMessageActionsTest::commit).doesNotThrowAnyException();
            verify(storage).delete("chat/2026/11/b.bin");
        }

        @Test
        @DisplayName("UTC-304 Tin không có tệp: không đăng ký việc xóa sau commit")
        void utc304_noFilesRegistersNothing() {
            service.recall(ME, CONV_ID, MY_MSG);

            verify(repository).markFilesDeleted(List.of(), NOW);
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        }

        @Test
        @DisplayName("UTC-305 Thu hồi tin của người kia: 403 \"Bạn chỉ sửa hoặc thu hồi được tin nhắn của chính mình.\"")
        void utc305_someoneElsesMessageIsForbidden() {
            assertApiError(() -> service.recall(ME, CONV_ID, PEER_MSG), HttpStatus.FORBIDDEN, ChatService.NOT_MINE);
            verifyNotRecalled();
        }

        @Test
        @DisplayName("UTC-306 Tin đã thu hồi rồi: lỗi \"Tin nhắn này đã được thu hồi.\"")
        void utc306_alreadyRecalledIsRefused() {
            when(repository.message(MY_MSG)).thenReturn(Optional.of(recalled(myPlain())));

            assertApiError(() -> service.recall(ME, CONV_ID, MY_MSG), HttpStatus.BAD_REQUEST, ChatService.RECALLED);
            verifyNotRecalled();
        }

        @Test
        @DisplayName("UTC-307 Tin không tồn tại, thuộc cuộc trò chuyện khác hoặc không còn hiện: 404 \"Không tìm thấy tin nhắn này.\"")
        void utc307_unknownMessageIsNotFound() {
            assertApiError(() -> service.recall(ME, CONV_ID, 999L), HttpStatus.NOT_FOUND, ChatService.MESSAGE_NOT_FOUND);
            when(repository.message(MY_MSG)).thenReturn(Optional.of(inConversation(myPlain(), 501L)));
            assertApiError(() -> service.recall(ME, CONV_ID, MY_MSG), HttpStatus.NOT_FOUND,
                    ChatService.MESSAGE_NOT_FOUND);
            when(repository.message(MY_MSG)).thenReturn(Optional.of(myPlain()));
            when(repository.shown(MY_MSG, 0L)).thenReturn(false);
            assertApiError(() -> service.recall(ME, CONV_ID, MY_MSG), HttpStatus.NOT_FOUND,
                    ChatService.MESSAGE_NOT_FOUND);
            verifyNotRecalled();
        }

        @Test
        @DisplayName("UTC-308 Cuộc trò chuyện không tồn tại hoặc không phải của người gọi: 404 \"Không tìm thấy cuộc trò chuyện này.\"")
        void utc308_unknownConversationIsNotFound() {
            assertApiError(() -> service.recall(ME, 999L, MY_MSG), HttpStatus.NOT_FOUND,
                    ChatService.CONVERSATION_NOT_FOUND);
            assertApiError(() -> service.recall(principal(OUTSIDER_ID, "USER"), CONV_ID, MY_MSG), HttpStatus.NOT_FOUND,
                    ChatService.CONVERSATION_NOT_FOUND);
            verifyNotRecalled();
        }

        @Test
        @DisplayName("UTC-309 Khách chưa đăng nhập: 401 \"Bạn cần đăng nhập để nhắn tin.\"")
        void utc309_guestIsUnauthorized() {
            assertApiError(() -> service.recall(null, CONV_ID, MY_MSG), HttpStatus.UNAUTHORIZED, LOGIN);
            verifyNoInteractions(repository, live, storage);
        }

        @Test
        @DisplayName("UTC-310 Tài khoản nhân viên (Moderator, BTV, Admin): 403 MSG18")
        void utc310_staffIsForbidden() {
            for (String role : new String[] {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"}) {
                when(repository.account(ME_ID)).thenReturn(Optional.of(account(ME_ID, "An", role, "ACTIVE")));
                assertApiError(() -> service.recall(principal(ME_ID, role), CONV_ID, MY_MSG), HttpStatus.FORBIDDEN,
                        MSG18);
            }
            verifyNotRecalled();
        }

        @Test
        @DisplayName("UTC-311 Thu hồi tin mã hóa: chưa xóa nội dung ngay (dọn sau 30 ngày), không ghi lịch sử sửa")
        void utc311_encryptedMessageKeepsItsBodyUntilCleanUp() {
            MessageRow row = encrypted(MY_MSG, ME_ID, goodEnvelope(), NOW.minus(Duration.ofHours(1)));
            when(repository.message(MY_MSG)).thenReturn(Optional.of(row), Optional.of(recalled(row)));

            Message result = service.recall(ME, CONV_ID, MY_MSG);

            verify(repository).recall(MY_MSG, NOW);
            verify(repository, never()).updateBody(anyLong(), any(), any(), any());
            verify(repository, never()).insertEdit(any(), any());
            assertThat(result.recalled()).isTrue();
            assertThat(result.encrypted()).isNull();
        }

        @Test
        @DisplayName("UTC-312 Báo trực tiếp \"changed\" cho người thu hồi và người kia")
        void utc312_recallIsAnnouncedToBothMembers() {
            service.recall(ME, CONV_ID, MY_MSG);

            verify(live).publish(ME_ID, "chat", liveData("changed", MY_MSG));
            verify(live).publish(PEER_ID, "chat", liveData("changed", MY_MSG));
        }

        @Test
        @DisplayName("UTC-313 Người kia đã xóa cuộc trò chuyện phía mình: chỉ báo cho người thu hồi")
        void utc313_recallHiddenFromPeerIsAnnouncedToActorOnly() {
            when(repository.member(CONV_ID, PEER_ID)).thenReturn(Optional.of(state("ACCEPTED", 800)));

            service.recall(ME, CONV_ID, MY_MSG);

            verify(live).publish(ME_ID, "chat", liveData("changed", MY_MSG));
            verify(live, never()).publish(eq(PEER_ID), anyString(), any());
        }

        @Test
        @DisplayName("UTC-314 Thu hồi không tạo thông báo tin mới, không lọc nội dung, không mở báo cáo")
        void utc314_recallPublishesNoNewMessageEvent() {
            service.recall(ME, CONV_ID, MY_MSG);

            verifyNoInteractions(events, filter, reports, typing);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("03. react – Thả cảm xúc (UTC-315 → UTC-336)")
    class ReactToMessage {

        @Test
        @DisplayName("UTC-315 Thả 👍 vào tin của người kia: lưu cảm xúc, cập nhật changed_at, trả về 👍 1 (của mình)")
        void utc315_reactionIsSavedAndReturned() {
            when(repository.reactionsOf(List.of(PEER_MSG))).thenReturn(Map.of(PEER_MSG,
                    List.of(new ReactionRow(PEER_MSG, ME_ID, THUMBS_UP))));

            Message result = service.react(ME, CONV_ID, PEER_MSG, THUMBS_UP);

            verify(repository).setReaction(PEER_MSG, ME_ID, THUMBS_UP, NOW);
            verify(repository).touch(PEER_MSG, NOW);
            assertThat(result.reactions()).containsExactly(new Reaction(THUMBS_UP, 1, true));
            assertThat(result.mine()).isFalse();
        }

        @Test
        @DisplayName("UTC-316 Cả 6 cảm xúc trong danh sách 👍 ❤️ 😂 😮 😢 🙏 đều được nhận")
        void utc316_everyOfferedEmojiIsAccepted() {
            for (String emoji : List.of(THUMBS_UP, HEART, LAUGH, WOW, SAD, PRAY)) {
                service.react(ME, CONV_ID, PEER_MSG, emoji);
                verify(repository).setReaction(PEER_MSG, ME_ID, emoji, NOW);
            }
            assertThat(ChatService.REACTIONS).containsExactly(THUMBS_UP, HEART, LAUGH, WOW, SAD, PRAY);
        }

        @Test
        @DisplayName("UTC-317 Cảm xúc ngoài danh sách (🔥, ❤ thiếu U+FE0F, chuỗi rỗng, \"like\", 👍🏻): lỗi \"Cảm xúc này không có trong danh sách.\"")
        void utc317_otherEmojiAreRefused() {
            for (String wrong : List.of(Character.toString(0x1F525), "❤", "", "like",
                    THUMBS_UP + Character.toString(0x1F3FB))) {
                assertApiError(() -> service.react(ME, CONV_ID, PEER_MSG, wrong), HttpStatus.BAD_REQUEST,
                        ChatService.BAD_REACTION);
            }
            verifyNoReaction();
        }

        @Test
        @DisplayName("UTC-318 Bỏ cảm xúc (emoji = null): xóa cảm xúc của mình, không thêm mới")
        void utc318_nullTakesTheReactionBack() {
            Message result = service.react(ME, CONV_ID, PEER_MSG, null);

            verify(repository).removeReaction(PEER_MSG, ME_ID);
            verify(repository, never()).setReaction(anyLong(), anyLong(), any(), any());
            verify(repository).touch(PEER_MSG, NOW);
            assertThat(result.reactions()).isEmpty();
        }

        @Test
        @DisplayName("UTC-319 Thả cảm xúc vào tin của chính mình: được phép")
        void utc319_ownMessageCanBeReactedTo() {
            service.react(ME, CONV_ID, MY_MSG, HEART);

            verify(repository).setReaction(MY_MSG, ME_ID, HEART, NOW);
        }

        @Test
        @DisplayName("UTC-320 Kết quả đếm theo thứ tự danh sách cảm xúc và đánh dấu cảm xúc của người xem")
        void utc320_reactionsAreCountedInListOrder() {
            when(repository.reactionsOf(List.of(PEER_MSG))).thenReturn(Map.of(PEER_MSG, List.of(
                    new ReactionRow(PEER_MSG, PEER_ID, HEART), new ReactionRow(PEER_MSG, ME_ID, THUMBS_UP))));

            Message result = service.react(ME, CONV_ID, PEER_MSG, THUMBS_UP);

            assertThat(result.reactions()).containsExactly(new Reaction(THUMBS_UP, 1, true),
                    new Reaction(HEART, 1, false));
        }

        @Test
        @DisplayName("UTC-321 Cả hai cùng thả ❤️: hiện ❤️ 2, có của mình")
        void utc321_sameEmojiByBothCountsTwo() {
            when(repository.reactionsOf(List.of(PEER_MSG))).thenReturn(Map.of(PEER_MSG, List.of(
                    new ReactionRow(PEER_MSG, PEER_ID, HEART), new ReactionRow(PEER_MSG, ME_ID, HEART))));

            Message result = service.react(ME, CONV_ID, PEER_MSG, HEART);

            assertThat(result.reactions()).containsExactly(new Reaction(HEART, 2, true));
        }

        @Test
        @DisplayName("UTC-322 Tin đã thu hồi: lỗi \"Tin nhắn này đã được thu hồi.\", kiểm tra trước cả việc bị chặn")
        void utc322_recalledMessageIsRefusedFirst() {
            when(repository.message(PEER_MSG)).thenReturn(Optional.of(recalled(peerPlain())));
            when(repository.blocked(ME_ID, PEER_ID)).thenReturn(true);

            assertApiError(() -> service.react(ME, CONV_ID, PEER_MSG, HEART), HttpStatus.BAD_REQUEST,
                    ChatService.RECALLED);
            verify(repository, never()).blocked(anyLong(), anyLong());
            verifyNoReaction();
        }

        @Test
        @DisplayName("UTC-323 Người xem là người được mời và chưa trả lời lời mời: 403 \"Chỉ thả cảm xúc được khi cả hai đã nhận lời…\"")
        void utc323_unansweredRequestIsForbidden() {
            when(repository.member(CONV_ID, ME_ID)).thenReturn(Optional.of(state("REQUEST", 0)));

            assertApiError(() -> service.react(ME, CONV_ID, PEER_MSG, THUMBS_UP), HttpStatus.FORBIDDEN,
                    ChatService.REACT_NOT_ALLOWED);
            verifyNoReaction();
        }

        @Test
        @DisplayName("UTC-324 Người xem là người mời, người kia chưa nhận lời hoặc đã từ chối: 403 REACT_NOT_ALLOWED")
        void utc324_requestNotAcceptedByPeerIsForbidden() {
            for (String peerState : new String[] {"REQUEST", "DECLINED"}) {
                when(repository.member(CONV_ID, PEER_ID)).thenReturn(Optional.of(state(peerState, 0)));
                assertApiError(() -> service.react(ME, CONV_ID, MY_MSG, THUMBS_UP), HttpStatus.FORBIDDEN,
                        ChatService.REACT_NOT_ALLOWED);
            }
            verifyNoReaction();
        }

        @Test
        @DisplayName("UTC-325 Người xem đã chặn người kia: lỗi \"Bạn đã chặn Bình. Bỏ chặn để nhắn tin tiếp.\"")
        void utc325_blockedByMeIsRefused() {
            when(repository.blocked(ME_ID, PEER_ID)).thenReturn(true);

            assertApiError(() -> service.react(ME, CONV_ID, PEER_MSG, THUMBS_UP), HttpStatus.BAD_REQUEST,
                    BLOCKED_BY_ME);
            verifyNoReaction();
        }

        @Test
        @DisplayName("UTC-326 Người kia đã chặn người xem: 403 \"Bạn không thể nhắn tin cho thành viên này.\"")
        void utc326_blockedByPeerIsForbidden() {
            when(repository.blocked(PEER_ID, ME_ID)).thenReturn(true);

            assertApiError(() -> service.react(ME, CONV_ID, PEER_MSG, THUMBS_UP), HttpStatus.FORBIDDEN,
                    ChatService.UNAVAILABLE);
            verifyNoReaction();
        }

        @Test
        @DisplayName("UTC-327 Tài khoản người kia bị khóa: 403 \"Bạn không thể nhắn tin cho thành viên này.\"")
        void utc327_lockedPeerIsForbidden() {
            when(repository.account(PEER_ID)).thenReturn(Optional.of(account(PEER_ID, "Bình", "USER", "LOCKED")));

            assertApiError(() -> service.react(ME, CONV_ID, PEER_MSG, THUMBS_UP), HttpStatus.FORBIDDEN,
                    ChatService.UNAVAILABLE);
            verifyNoReaction();
        }

        @Test
        @DisplayName("UTC-328 Tài khoản người kia đã xóa: lỗi \"Tài khoản này đã bị xóa, không nhắn tin được nữa.\"")
        void utc328_deletedPeerIsRefused() {
            when(repository.account(PEER_ID)).thenReturn(Optional.of(account(PEER_ID, "Bình", "USER", "DELETED")));

            assertApiError(() -> service.react(ME, CONV_ID, PEER_MSG, THUMBS_UP), HttpStatus.BAD_REQUEST,
                    ChatService.DELETED);
            verifyNoReaction();
        }

        @Test
        @DisplayName("UTC-329 Trong lời mời mà gửi cảm xúc sai: báo REACT_NOT_ALLOWED trước lỗi cảm xúc")
        void utc329_relationshipIsCheckedBeforeEmoji() {
            when(repository.member(CONV_ID, ME_ID)).thenReturn(Optional.of(state("REQUEST", 0)));

            assertApiError(() -> service.react(ME, CONV_ID, PEER_MSG, "like"), HttpStatus.FORBIDDEN,
                    ChatService.REACT_NOT_ALLOWED);
        }

        @Test
        @DisplayName("UTC-330 Tin không tồn tại, thuộc cuộc trò chuyện khác hoặc không còn hiện: 404")
        void utc330_unknownMessageIsNotFound() {
            assertApiError(() -> service.react(ME, CONV_ID, 999L, THUMBS_UP), HttpStatus.NOT_FOUND,
                    ChatService.MESSAGE_NOT_FOUND);
            when(repository.message(PEER_MSG)).thenReturn(Optional.of(inConversation(peerPlain(), 501L)));
            assertApiError(() -> service.react(ME, CONV_ID, PEER_MSG, THUMBS_UP), HttpStatus.NOT_FOUND,
                    ChatService.MESSAGE_NOT_FOUND);
            when(repository.message(PEER_MSG)).thenReturn(Optional.of(peerPlain()));
            when(repository.shown(PEER_MSG, 0L)).thenReturn(false);
            assertApiError(() -> service.react(ME, CONV_ID, PEER_MSG, THUMBS_UP), HttpStatus.NOT_FOUND,
                    ChatService.MESSAGE_NOT_FOUND);
            verifyNoReaction();
        }

        @Test
        @DisplayName("UTC-331 Người ngoài cuộc trò chuyện: 404 \"Không tìm thấy cuộc trò chuyện này.\"")
        void utc331_outsiderGetsNotFound() {
            assertApiError(() -> service.react(principal(OUTSIDER_ID, "USER"), CONV_ID, PEER_MSG, THUMBS_UP),
                    HttpStatus.NOT_FOUND, ChatService.CONVERSATION_NOT_FOUND);
            verifyNoReaction();
        }

        @Test
        @DisplayName("UTC-332 Khách chưa đăng nhập: 401")
        void utc332_guestIsUnauthorized() {
            assertApiError(() -> service.react(null, CONV_ID, PEER_MSG, THUMBS_UP), HttpStatus.UNAUTHORIZED, LOGIN);
            verifyNoInteractions(repository, live);
        }

        @Test
        @DisplayName("UTC-333 Tài khoản Moderator: 403 MSG18")
        void utc333_staffIsForbidden() {
            when(repository.account(ME_ID)).thenReturn(Optional.of(account(ME_ID, "An", "MODERATOR", "ACTIVE")));

            assertApiError(() -> service.react(principal(ME_ID, "MODERATOR"), CONV_ID, PEER_MSG, THUMBS_UP),
                    HttpStatus.FORBIDDEN, MSG18);
            verifyNoReaction();
        }

        @Test
        @DisplayName("UTC-334 Báo trực tiếp \"changed\" cho người thả cảm xúc và người kia")
        void utc334_reactionIsAnnouncedToBothMembers() {
            service.react(ME, CONV_ID, PEER_MSG, THUMBS_UP);

            verify(live).publish(ME_ID, "chat", liveData("changed", PEER_MSG));
            verify(live).publish(PEER_ID, "chat", liveData("changed", PEER_MSG));
        }

        @Test
        @DisplayName("UTC-335 Người kia đã xóa cuộc trò chuyện phía mình (tin không còn hiện với họ): chỉ báo cho người thả")
        void utc335_reactionHiddenFromPeerIsAnnouncedToActorOnly() {
            when(repository.member(CONV_ID, PEER_ID)).thenReturn(Optional.of(state("ACCEPTED", 900)));

            service.react(ME, CONV_ID, MY_MSG, THUMBS_UP);

            verify(live).publish(ME_ID, "chat", liveData("changed", MY_MSG));
            verify(live, never()).publish(eq(PEER_ID), anyString(), any());
        }

        @Test
        @DisplayName("UTC-336 Đổi từ 👍 sang ❤️: chỉ ghi một cảm xúc mới (thay cái cũ), mỗi người một cảm xúc")
        void utc336_newReactionReplacesTheOldOne() {
            when(repository.reactionsOf(List.of(PEER_MSG))).thenReturn(Map.of(PEER_MSG,
                    List.of(new ReactionRow(PEER_MSG, ME_ID, HEART))));

            Message result = service.react(ME, CONV_ID, PEER_MSG, HEART);

            verify(repository).setReaction(PEER_MSG, ME_ID, HEART, NOW);
            verify(repository, never()).removeReaction(anyLong(), anyLong());
            assertThat(result.reactions()).containsExactly(new Reaction(HEART, 1, true));
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("04. reportMessage – Báo cáo tin nhắn (UTC-337 → UTC-363)")
    class ReportMessage {

        private static final byte[] FK = bytes(32, 5);
        private final byte[] body = "{\"v\":1,\"text\":\"Em gửi chị ảnh thẻ cư trú nhé\"}"
                .getBytes(StandardCharsets.UTF_8);

        /** Message 701 from Bình, encrypted, with the franking tag of {@code body} under {@code FK}. */
        private void encryptedFromPeer(byte[] plaintext) {
            Envelope e = envelope(96, 12, 42L, 41L, tagOf(FK, CONV_ID, PEER_ID, plaintext));
            when(repository.message(PEER_MSG)).thenReturn(Optional.of(
                    encrypted(PEER_MSG, PEER_ID, e, NOW.minus(Duration.ofMinutes(2)))));
        }

        private ReportRequest proof(byte[] plaintext, byte[] fk) {
            return new ReportRequest(REASON_ID, "Xin ảnh thẻ cư trú", false, b64(plaintext), b64(fk));
        }

        @Test
        @DisplayName("UTC-337 Báo cáo tin chữ nhận được: mở báo cáo MESSAGE nhắm vào người viết, không lưu bằng chứng")
        void utc337_plainMessageIsReported() {
            service.reportMessage(ME, PEER_MSG, new ReportRequest(REASON_ID, "Lừa đảo việc làm", false));

            verify(reports).report(new Target("MESSAGE", PEER_MSG, PEER_ID), ME_ID, REASON_ID, "Lừa đảo việc làm");
            verify(repository, never()).insertEvidence(anyLong(), anyLong(), any(), any());
            verify(repository, never()).block(anyLong(), anyLong(), any());
            verifyNoInteractions(live);
        }

        @Test
        @DisplayName("UTC-338 Khóa dòng cuộc trò chuyện trước khi tạo báo cáo")
        void utc338_conversationIsLockedBeforeReport() {
            service.reportMessage(ME, PEER_MSG, new ReportRequest(REASON_ID, null, false));

            InOrder order = inOrder(repository, reports);
            order.verify(repository).lockConversation(CONV_ID);
            order.verify(repository).shown(PEER_MSG, 0L);
            order.verify(reports).report(any(), eq(ME_ID), eq(REASON_ID), any());
        }

        @Test
        @DisplayName("UTC-339 Báo cáo kèm \"Chặn người này\": chặn người viết, chỉ báo cho các trang của người báo cáo")
        void utc339_reportCanAlsoBlockTheWriter() {
            service.reportMessage(ME, PEER_MSG, new ReportRequest(REASON_ID, null, true));

            InOrder order = inOrder(reports, repository);
            order.verify(reports).report(any(), eq(ME_ID), eq(REASON_ID), any());
            order.verify(repository).lockConversationOf(ME_ID, PEER_ID);
            order.verify(repository).block(ME_ID, PEER_ID, NOW);
            verify(live).publish(ME_ID, "chat", liveData("status", null));
            verify(live, never()).publish(eq(PEER_ID), anyString(), any());
            verify(repository, never()).setState(anyLong(), anyLong(), anyString(), any());
        }

        @Test
        @DisplayName("UTC-340 Báo cáo và chặn từ một lời mời chưa trả lời: lời mời bị từ chối (DECLINED) và ẩn khỏi danh sách")
        void utc340_blockingFromARequestDeclinesIt() {
            when(repository.member(CONV_ID, ME_ID)).thenReturn(Optional.of(state("REQUEST", 0)));

            service.reportMessage(ME, PEER_MSG, new ReportRequest(REASON_ID, null, true));

            verify(repository).block(ME_ID, PEER_ID, NOW);
            verify(repository).setState(CONV_ID, ME_ID, "DECLINED", NOW);
            verify(repository).clear(CONV_ID, ME_ID, NOW);
        }

        @Test
        @DisplayName("UTC-341 Báo cáo vượt ngưỡng ẩn: tin nhắn vẫn không bị ẩn hay thu hồi (chỉ có một người báo cáo được)")
        void utc341_messageIsNeverHiddenByReports() {
            when(reports.report(any(), anyLong(), any(), any())).thenReturn(new Outcome(9, true));

            service.reportMessage(ME, PEER_MSG, new ReportRequest(REASON_ID, null, false));

            verify(repository, never()).recall(anyLong(), any());
            verify(repository, never()).removeReactions(anyLong());
            verify(repository, never()).touch(anyLong(), any());
            verify(repository, never()).updateBody(anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("UTC-342 Báo cáo tin của chính mình: lỗi từ ContentReportService (mock) được trả nguyên, không chặn")
        void utc342_ownMessageRefusalIsPassedOn() {
            when(reports.report(new Target("MESSAGE", MY_MSG, ME_ID), ME_ID, REASON_ID, null))
                    .thenThrow(ApiException.badRequest("Bạn không thể báo cáo nội dung của chính mình."));

            assertApiError(() -> service.reportMessage(ME, MY_MSG, new ReportRequest(REASON_ID, null, true)),
                    HttpStatus.BAD_REQUEST, "Bạn không thể báo cáo nội dung của chính mình.");
            verify(repository, never()).block(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("UTC-343 Báo cáo lần hai: 409 MSG39 từ ContentReportService (mock), không lưu bằng chứng, không chặn")
        void utc343_duplicateReportIsConflict() {
            encryptedFromPeer(body);
            when(reports.report(any(), anyLong(), any(), any()))
                    .thenThrow(ApiException.conflict("Bạn đã báo cáo nội dung này rồi."));

            assertApiError(() -> service.reportMessage(ME, PEER_MSG, new ReportRequest(REASON_ID, null, true,
                    b64(body), b64(FK))), HttpStatus.CONFLICT, "Bạn đã báo cáo nội dung này rồi.");
            verify(repository, never()).insertEvidence(anyLong(), anyLong(), any(), any());
            verify(repository, never()).block(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("UTC-344 Tin không tồn tại: 404 \"Không tìm thấy tin nhắn này.\"")
        void utc344_missingMessageIsNotFound() {
            assertApiError(() -> service.reportMessage(ME, 999L, new ReportRequest(REASON_ID, null, false)),
                    HttpStatus.NOT_FOUND, ChatService.MESSAGE_NOT_FOUND);
            verify(repository, never()).lockConversation(anyLong());
            verifyNotReported();
        }

        @Test
        @DisplayName("UTC-345 Người ngoài báo cáo tin của cuộc trò chuyện khác: 404 \"Không tìm thấy tin nhắn này.\"")
        void utc345_outsiderGetsNotFound() {
            assertApiError(() -> service.reportMessage(principal(OUTSIDER_ID, "USER"), PEER_MSG,
                    new ReportRequest(REASON_ID, null, false)), HttpStatus.NOT_FOUND, ChatService.MESSAGE_NOT_FOUND);
            verifyNotReported();
        }

        @Test
        @DisplayName("UTC-346 Tin đã thu hồi: 404 \"Không tìm thấy tin nhắn này.\"")
        void utc346_recalledMessageIsNotFound() {
            when(repository.message(PEER_MSG)).thenReturn(Optional.of(recalled(peerPlain())));

            assertApiError(() -> service.reportMessage(ME, PEER_MSG, new ReportRequest(REASON_ID, null, false)),
                    HttpStatus.NOT_FOUND, ChatService.MESSAGE_NOT_FOUND);
            verifyNotReported();
        }

        @Test
        @DisplayName("UTC-347 Tin không còn hiện với người báo cáo (đã xóa cuộc trò chuyện hoặc người viết bị khóa): 404")
        void utc347_messageNotShownIsNotFound() {
            when(repository.member(CONV_ID, ME_ID)).thenReturn(Optional.of(state("ACCEPTED", 760)));
            when(repository.shown(PEER_MSG, 760L)).thenReturn(false);

            assertApiError(() -> service.reportMessage(ME, PEER_MSG, new ReportRequest(REASON_ID, null, false)),
                    HttpStatus.NOT_FOUND, ChatService.MESSAGE_NOT_FOUND);
            verifyNotReported();
        }

        @Test
        @DisplayName("UTC-348 Khách chưa đăng nhập: 401")
        void utc348_guestIsUnauthorized() {
            assertApiError(() -> service.reportMessage(null, PEER_MSG, new ReportRequest(REASON_ID, null, false)),
                    HttpStatus.UNAUTHORIZED, LOGIN);
            verifyNoInteractions(repository, reports);
        }

        @Test
        @DisplayName("UTC-349 Tài khoản nhân viên: 403 MSG18")
        void utc349_staffIsForbidden() {
            when(repository.account(ME_ID)).thenReturn(Optional.of(account(ME_ID, "An", "ADMIN", "ACTIVE")));

            assertApiError(() -> service.reportMessage(principal(ME_ID, "ADMIN"), PEER_MSG,
                    new ReportRequest(REASON_ID, null, false)), HttpStatus.FORBIDDEN, MSG18);
            verifyNotReported();
        }

        @Test
        @DisplayName("UTC-350 Báo cáo tin mã hóa kèm nội dung và khóa franking đúng: lưu nội dung làm bằng chứng")
        void utc350_verifiedPlaintextIsKeptAsEvidence() {
            encryptedFromPeer(body);

            service.reportMessage(ME, PEER_MSG, proof(body, FK));

            verify(reports).report(new Target("MESSAGE", PEER_MSG, PEER_ID), ME_ID, REASON_ID, "Xin ảnh thẻ cư trú");
            verify(repository).insertEvidence(91L, PEER_MSG, new String(body, StandardCharsets.UTF_8), NOW);
        }

        @Test
        @DisplayName("UTC-351 Tin mã hóa nhưng thiếu nội dung hoặc thiếu khóa franking: lỗi \"Không xác minh được…\", không tạo báo cáo")
        void utc351_missingProofIsRefused() {
            encryptedFromPeer(body);

            assertApiError(() -> service.reportMessage(ME, PEER_MSG, new ReportRequest(REASON_ID, null, false)),
                    HttpStatus.BAD_REQUEST, ChatService.REPORT_UNVERIFIED);
            assertApiError(() -> service.reportMessage(ME, PEER_MSG, new ReportRequest(REASON_ID, null, false,
                    b64(body), null)), HttpStatus.BAD_REQUEST, ChatService.REPORT_UNVERIFIED);
            verifyNotReported();
        }

        @Test
        @DisplayName("UTC-352 Nội dung bị sửa (đổi 1 bit): franking tag không khớp, lỗi \"Không xác minh được…\"")
        void utc352_forgedPlaintextIsRefused() {
            encryptedFromPeer(body);
            byte[] forged = body.clone();
            forged[forged.length - 3] ^= 1;

            assertApiError(() -> service.reportMessage(ME, PEER_MSG, proof(forged, FK)), HttpStatus.BAD_REQUEST,
                    ChatService.REPORT_UNVERIFIED);
            verifyNotReported();
        }

        @Test
        @DisplayName("UTC-353 Khóa franking khác (32 byte khác): lỗi \"Không xác minh được…\"")
        void utc353_wrongFrankingKeyIsRefused() {
            encryptedFromPeer(body);

            assertApiError(() -> service.reportMessage(ME, PEER_MSG, proof(body, bytes(32, 6))),
                    HttpStatus.BAD_REQUEST, ChatService.REPORT_UNVERIFIED);
            verifyNotReported();
        }

        @Test
        @DisplayName("UTC-354 Khóa franking 31 byte hoặc 33 byte: lỗi \"Không xác minh được…\"")
        void utc354_frankingKeyOfWrongSizeIsRefused() {
            encryptedFromPeer(body);

            for (int size : new int[] {31, 33}) {
                byte[] fk = java.util.Arrays.copyOf(FK, size);
                assertApiError(() -> service.reportMessage(ME, PEER_MSG, proof(body, fk)), HttpStatus.BAD_REQUEST,
                        ChatService.REPORT_UNVERIFIED);
            }
            verifyNotReported();
        }

        @Test
        @DisplayName("UTC-355 Nội dung hoặc khóa franking không phải base64: lỗi \"Không xác minh được…\"")
        void utc355_notBase64IsRefused() {
            encryptedFromPeer(body);

            assertApiError(() -> service.reportMessage(ME, PEER_MSG, new ReportRequest(REASON_ID, null, false, "@@@",
                    b64(FK))), HttpStatus.BAD_REQUEST, ChatService.REPORT_UNVERIFIED);
            assertApiError(() -> service.reportMessage(ME, PEER_MSG, new ReportRequest(REASON_ID, null, false,
                    b64(body), "%%%")), HttpStatus.BAD_REQUEST, ChatService.REPORT_UNVERIFIED);
            verifyNotReported();
        }

        @Test
        @DisplayName("UTC-356 Franking tag tính cho cuộc trò chuyện khác hoặc người gửi khác: lỗi \"Không xác minh được…\"")
        void utc356_tagOfAnotherConversationOrSenderIsRefused() {
            for (String tag : List.of(tagOf(FK, 501L, PEER_ID, body), tagOf(FK, CONV_ID, ME_ID, body))) {
                Envelope e = envelope(96, 12, 42L, 41L, tag);
                when(repository.message(PEER_MSG)).thenReturn(Optional.of(
                        encrypted(PEER_MSG, PEER_ID, e, NOW.minus(Duration.ofMinutes(2)))));
                assertApiError(() -> service.reportMessage(ME, PEER_MSG, proof(body, FK)), HttpStatus.BAD_REQUEST,
                        ChatService.REPORT_UNVERIFIED);
            }
            verifyNotReported();
        }

        @Test
        @DisplayName("UTC-357 Tag khớp nhưng nội dung không phải UTF-8 hợp lệ: lỗi \"Không xác minh được…\"")
        void utc357_invalidUtf8IsRefused() {
            byte[] notText = {(byte) 0xC3, (byte) 0x28, (byte) 0xFF, 0x41};
            encryptedFromPeer(notText);

            assertApiError(() -> service.reportMessage(ME, PEER_MSG, proof(notText, FK)), HttpStatus.BAD_REQUEST,
                    ChatService.REPORT_UNVERIFIED);
            verifyNotReported();
        }

        @Test
        @DisplayName("UTC-358 Nội dung đúng 32.768 byte, tag khớp: được nhận làm bằng chứng")
        void utc358_largestBodyIsAccepted() {
            byte[] largest = "a".repeat(ChatService.CIPHERTEXT_MAX_BYTES).getBytes(StandardCharsets.UTF_8);
            encryptedFromPeer(largest);

            service.reportMessage(ME, PEER_MSG, proof(largest, FK));

            verify(repository).insertEvidence(91L, PEER_MSG, "a".repeat(32768), NOW);
        }

        @Test
        @DisplayName("UTC-359 Nội dung 32.769 byte (tag khớp): lỗi \"Không xác minh được…\"")
        void utc359_bodyOverTheLimitIsRefused() {
            byte[] tooLarge = "a".repeat(ChatService.CIPHERTEXT_MAX_BYTES + 1).getBytes(StandardCharsets.UTF_8);
            encryptedFromPeer(tooLarge);

            assertApiError(() -> service.reportMessage(ME, PEER_MSG, proof(tooLarge, FK)), HttpStatus.BAD_REQUEST,
                    ChatService.REPORT_UNVERIFIED);
            verifyNotReported();
        }

        @Test
        @DisplayName("UTC-360 Chuỗi nội dung dài hơn 44.000 ký tự hoặc chuỗi khóa dài hơn 64 ký tự: bị từ chối trước khi giải mã base64")
        void utc360_overlongTextsAreRefused() {
            encryptedFromPeer(body);

            assertApiError(() -> service.reportMessage(ME, PEER_MSG, new ReportRequest(REASON_ID, null, false,
                    b64(bytes(33_001, 1)), b64(FK))), HttpStatus.BAD_REQUEST, ChatService.REPORT_UNVERIFIED);
            assertApiError(() -> service.reportMessage(ME, PEER_MSG, new ReportRequest(REASON_ID, null, false,
                    b64(body), b64(bytes(49, 1)))), HttpStatus.BAD_REQUEST, ChatService.REPORT_UNVERIFIED);
            assertThat(b64(bytes(33_001, 1)).length()).isGreaterThan(44_000);
            assertThat(b64(bytes(49, 1)).length()).isGreaterThan(64);
            verifyNotReported();
        }

        @Test
        @DisplayName("UTC-361 Báo cáo tin mã hóa kèm chặn: lưu bằng chứng rồi chặn người viết")
        void utc361_encryptedReportWithBlock() {
            encryptedFromPeer(body);

            service.reportMessage(ME, PEER_MSG, new ReportRequest(REASON_ID, null, true, b64(body), b64(FK)));

            InOrder order = inOrder(repository);
            order.verify(repository).insertEvidence(91L, PEER_MSG, new String(body, StandardCharsets.UTF_8), NOW);
            order.verify(repository).block(ME_ID, PEER_ID, NOW);
        }

        @Test
        @DisplayName("UTC-362 Tin chữ thường kèm nội dung và khóa franking: bỏ qua, không lưu bằng chứng")
        void utc362_proofIsIgnoredForPlainMessages() {
            service.reportMessage(ME, PEER_MSG, new ReportRequest(REASON_ID, null, false, b64(body), b64(FK)));

            verify(reports).report(new Target("MESSAGE", PEER_MSG, PEER_ID), ME_ID, REASON_ID, null);
            verify(repository, never()).reportIdOf(anyLong(), anyLong());
            verify(repository, never()).insertEvidence(anyLong(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("UTC-363 Bằng chứng được lưu sau khi tạo báo cáo, gắn với id báo cáo của người báo cáo")
        void utc363_evidenceIsLinkedToTheNewReport() {
            encryptedFromPeer(body);
            when(repository.reportIdOf(PEER_MSG, ME_ID)).thenReturn(Optional.of(4242L));

            service.reportMessage(ME, PEER_MSG, proof(body, FK));

            InOrder order = inOrder(reports, repository);
            order.verify(reports).report(any(), eq(ME_ID), eq(REASON_ID), any());
            order.verify(repository).reportIdOf(PEER_MSG, ME_ID);
            order.verify(repository).insertEvidence(4242L, PEER_MSG, new String(body, StandardCharsets.UTF_8), NOW);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("05. lockForReport – Khóa cuộc trò chuyện của tin bị báo cáo (UTC-364 → UTC-365)")
    class LockForReport {

        @Test
        @DisplayName("UTC-364 Tin bị báo cáo còn tồn tại: khóa dòng cuộc trò chuyện của nó")
        void utc364_conversationOfMessageIsLocked() {
            service.lockForReport(PEER_MSG);

            verify(repository).lockConversation(CONV_ID);
        }

        @Test
        @DisplayName("UTC-365 Tin không còn: không khóa gì, không lỗi")
        void utc365_missingMessageLocksNothing() {
            assertThatCode(() -> service.lockForReport(999L)).doesNotThrowAnyException();

            verify(repository, never()).lockConversation(anyLong());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("06. removeReported – Moderator gỡ tin nhắn bị báo cáo (UTC-366 → UTC-371)")
    class RemoveReported {

        @Test
        @DisplayName("UTC-366 Moderator gỡ tin: thu hồi với cả hai người, xóa cảm xúc, báo \"changed\" cho cả hai")
        void utc366_reportedMessageIsRecalledForBoth() {
            service.removeReported(PEER_MSG);

            verify(repository).recall(PEER_MSG, NOW);
            verify(repository).removeReactions(PEER_MSG);
            verify(repository).lockConversation(CONV_ID);
            verify(live).publish(PEER_ID, "chat", liveData("changed", PEER_MSG));
            verify(live).publish(ME_ID, "chat", liveData("changed", PEER_MSG));
        }

        @Test
        @DisplayName("UTC-367 Tin bị gỡ có tệp: đánh dấu xóa tệp, xóa khỏi kho lưu trữ sau khi commit")
        void utc367_filesAreDeletedAfterCommit() {
            when(repository.filesOf(PEER_MSG)).thenReturn(List.of(file(33, PEER_MSG, "AUDIO", "chat/2026/11/c.bin")));

            service.removeReported(PEER_MSG);

            verify(repository).markFilesDeleted(List.of(33L), NOW);
            verifyNoInteractions(storage);
            commit();
            verify(storage).delete("chat/2026/11/c.bin");
        }

        @Test
        @DisplayName("UTC-368 Quyết định của Moderator bị rollback: tệp không bị xóa khỏi kho")
        void utc368_rollbackKeepsFiles() {
            when(repository.filesOf(PEER_MSG)).thenReturn(List.of(file(33, PEER_MSG, "AUDIO", "chat/2026/11/c.bin")));

            service.removeReported(PEER_MSG);
            rollback();

            verifyNoInteractions(storage);
        }

        @Test
        @DisplayName("UTC-369 Tin đã được người viết thu hồi trước: không làm gì thêm")
        void utc369_alreadyRecalledDoesNothing() {
            when(repository.message(PEER_MSG)).thenReturn(Optional.of(recalled(peerPlain())));

            service.removeReported(PEER_MSG);

            verify(repository, never()).recall(anyLong(), any());
            verify(repository, never()).removeReactions(anyLong());
            verifyNoInteractions(live, storage);
        }

        @Test
        @DisplayName("UTC-370 Tin không còn tồn tại: không làm gì, không lỗi")
        void utc370_missingMessageDoesNothing() {
            assertThatCode(() -> service.removeReported(999L)).doesNotThrowAnyException();

            verify(repository, never()).recall(anyLong(), any());
            verifyNoInteractions(live, storage);
        }

        @Test
        @DisplayName("UTC-371 Gỡ tin không kiểm tra tài khoản (người viết đã bị khóa vẫn gỡ được), không tạo thông báo tin mới")
        void utc371_removalNeedsNoAccountCheck() {
            when(repository.account(PEER_ID)).thenReturn(Optional.of(account(PEER_ID, "Bình", "USER", "LOCKED")));
            clearInvocations(repository);

            service.removeReported(PEER_MSG);

            verify(repository).recall(PEER_MSG, NOW);
            verify(repository, never()).account(anyLong());
            verifyNoInteractions(events, filter, reports);
        }
    }

}
