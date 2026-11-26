package com.sep490.vsa.feature.chat;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.live.LiveEvents;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.feature.chat.ChatDtos.FileUploaded;
import com.sep490.vsa.feature.chat.ChatRepository.Account;
import com.sep490.vsa.feature.chat.ChatRepository.ConversationRow;
import com.sep490.vsa.feature.chat.ChatRepository.FileRow;
import com.sep490.vsa.feature.chat.ChatRepository.KeyRow;
import com.sep490.vsa.feature.chat.ChatRepository.MemberRow;
import com.sep490.vsa.feature.chat.ChatRepository.MessageRow;
import com.sep490.vsa.feature.media.MediaStorage;
import com.sep490.vsa.feature.moderation.ContentFilter;
import com.sep490.vsa.feature.report.ContentReportService;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the files of "Tin nhắn" in {@link ChatService}: uploading a photo, video or voice message that the page
 * has already encrypted ({@code upload}: E2EE conversations only, size per kind, files a Japan day, stored as
 * {@code chat/yyyy/MM/<uuid>.bin} and removed again on a rollback) and reading it back ({@code file}: the two members
 * only, under the rules of CLAUDE.md "Tin nhắn" and ChatFilesFlowTest).
 * <p>
 * The database and the storage are not used: {@link ChatRepository}, {@link MediaStorage}, {@link ContentFilter},
 * {@link ContentReportService}, {@link SystemSettings}, {@link ChatTyping}, {@link ChatViewers},
 * {@link ApplicationEventPublisher} and {@link LiveEvents} are mocks; the clock is fixed at 20/11/2026 12:00 Japan
 * time. Transaction synchronization is switched on by hand so a commit or a rollback can be played.
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("ChatService – Tin nhắn: gửi và tải ảnh, video, ghi âm (CHAT)")
class ChatServiceFilesTest {

    private static final long ME_ID = 10L;
    private static final long PEER_ID = 20L;
    private static final long OUTSIDER_ID = 30L;
    private static final long CONV_ID = 500L;
    private static final long FILE_ID = 81L;
    private static final long MESSAGE_ID = 800L;
    /** 12:00 in Japan, 20/11/2026. */
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private static final String KEY_PATTERN = "chat/2026/11/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.bin";

    private static final ConversationRow CONVERSATION = new ConversationRow(CONV_ID, ME_ID, PEER_ID, ME_ID);
    private static final AppUserPrincipal ME = principal(ME_ID, "USER");
    private static final AppUserPrincipal PEER = principal(PEER_ID, "USER");
    private static final byte[] BYTES = {1, 2, 3};

    private static final String MSG18 = "Bạn không có quyền thực hiện thao tác này.";
    private static final String LOGIN = "Bạn cần đăng nhập để nhắn tin.";

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
        service = serviceAt(NOW);

        when(settings.getInt(SystemSettings.NEW_ACCOUNT_DAYS, 7)).thenReturn(7);
        when(repository.account(ME_ID)).thenReturn(Optional.of(member(ME_ID, "An", Duration.ofDays(30))));
        when(repository.account(PEER_ID)).thenReturn(Optional.of(member(PEER_ID, "Bình", Duration.ofDays(30))));
        when(repository.account(OUTSIDER_ID)).thenReturn(Optional.of(member(OUTSIDER_ID, "Người ngoài",
                Duration.ofDays(30))));
        when(repository.conversation(CONV_ID)).thenReturn(Optional.of(CONVERSATION));
        when(repository.lockConversation(CONV_ID)).thenReturn(Optional.of(CONVERSATION));
        when(repository.member(CONV_ID, ME_ID)).thenReturn(Optional.of(state("ACCEPTED", 0)));
        when(repository.member(CONV_ID, PEER_ID)).thenReturn(Optional.of(state("ACCEPTED", 0)));
        when(repository.currentKey(ME_ID)).thenReturn(Optional.of(key(41, ME_ID)));
        when(repository.currentKey(PEER_ID)).thenReturn(Optional.of(key(42, PEER_ID)));
        when(repository.shown(anyLong(), anyLong())).thenReturn(true);
        when(repository.insertFile(anyLong(), anyLong(), anyString(), anyString(), anyLong(), any())).thenReturn(77L);

        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ---------------------------------------------------------------- helpers

    private ChatService serviceAt(Instant instant) {
        return new ChatService(repository, filter, reports, settings, storage, typing, viewers, events, live,
                Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static AppUserPrincipal principal(long id, String role) {
        return new AppUserPrincipal(id, "user" + id + "@example.org", "User " + id, role, "/");
    }

    private static Account account(long id, String name, String role, String status, Instant createdAt) {
        return new Account(id, name, null, role, status, createdAt);
    }

    private static Account member(long id, String name, Duration age) {
        return account(id, name, "USER", "ACTIVE", NOW.minus(age));
    }

    private static MemberRow state(String state, long clearedId) {
        return new MemberRow(state, 0, clearedId);
    }

    private static KeyRow key(long id, long userId) {
        return new KeyRow(id, userId, "BPub" + id, "wrapped", "salt", "iv", 600_000, NOW.minus(Duration.ofDays(3)),
                null);
    }

    private static MockMultipartFile blob(byte[] bytes) {
        return new MockMultipartFile("file", "blob", "application/octet-stream", bytes);
    }

    /** A multipart file that says it has {@code size} bytes (the bytes themselves stay small). */
    private static MultipartFile sized(long size) {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(size);
        try {
            when(file.getBytes()).thenReturn(BYTES);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return file;
    }

    private static FileRow fileRow(long uploaderId, Long messageId, boolean deleted) {
        return new FileRow(FILE_ID, CONV_ID, uploaderId, messageId, "IMAGE", "chat/2026/11/f.bin", 1000,
                NOW.minus(Duration.ofMinutes(10)), deleted);
    }

    private static MessageRow message(boolean recalled) {
        return new MessageRow(MESSAGE_ID, CONV_ID, ME_ID, null, "Y2lwaGVy", "aXY=", 41L, 42L, "ab".repeat(32), null,
                null, recalled ? NOW.minus(Duration.ofMinutes(1)) : null, NOW.minus(Duration.ofMinutes(9)));
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

    private String storedKey() {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storage).put(key.capture(), any());
        return key.getValue();
    }

    private void verifyNothingStored() {
        verify(storage, never()).put(anyString(), any());
        verify(repository, never()).insertFile(anyLong(), anyLong(), anyString(), anyString(), anyLong(), any());
    }

    private static void playCommit() {
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
            s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
    }

    private static void playRollback() {
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. upload – Tải lên ảnh, video, ghi âm đã mã hóa (UTC-372 → UTC-410)")
    class Upload {

        @Test
        @DisplayName("UTC-372 Tải ảnh trong cuộc trò chuyện đã mã hóa: lưu vào kho chat/yyyy/MM/<uuid>.bin và ghi dòng chat_files")
        void utc372_imageIsStoredAndRecorded() {
            FileUploaded result = service.upload(ME, CONV_ID, "IMAGE", blob(BYTES));

            String key = storedKey();
            assertThat(key).matches(KEY_PATTERN);
            verify(storage).put(key, BYTES);
            verify(repository).insertFile(CONV_ID, ME_ID, "IMAGE", key, 3L, NOW);
            assertThat(result.fileId()).isEqualTo(77L);
        }

        @Test
        @DisplayName("UTC-373 Loại tệp viết thường hoặc có khoảng trắng (\" image \", \"video\", \"Audio\"): được chuẩn hóa thành chữ hoa")
        void utc373_kindIsNormalised() {
            service.upload(ME, CONV_ID, " image ", blob(BYTES));
            service.upload(ME, CONV_ID, "video", blob(BYTES));
            service.upload(ME, CONV_ID, "Audio", blob(BYTES));

            verify(repository).insertFile(eq(CONV_ID), eq(ME_ID), eq("IMAGE"), anyString(), eq(3L), eq(NOW));
            verify(repository).insertFile(eq(CONV_ID), eq(ME_ID), eq("VIDEO"), anyString(), eq(3L), eq(NOW));
            verify(repository).insertFile(eq(CONV_ID), eq(ME_ID), eq("AUDIO"), anyString(), eq(3L), eq(NOW));
        }

        @Test
        @DisplayName("UTC-374 Loại tệp không hợp lệ (\"PDF\", \"\", null, \"IMAGES\"): lỗi \"Loại tệp không hợp lệ.\"")
        void utc374_unknownKindIsRefused() {
            for (String kind : new String[] {"PDF", "", null, "IMAGES"}) {
                assertApiError(() -> service.upload(ME, CONV_ID, kind, blob(BYTES)), HttpStatus.BAD_REQUEST,
                        "Loại tệp không hợp lệ.");
            }
            verifyNothingStored();
        }

        @Test
        @DisplayName("UTC-375 Không chọn tệp hoặc tệp rỗng (0 byte): lỗi \"Hãy chọn tệp để gửi.\"")
        void utc375_missingOrEmptyFileIsRefused() {
            assertApiError(() -> service.upload(ME, CONV_ID, "IMAGE", null), HttpStatus.BAD_REQUEST,
                    "Hãy chọn tệp để gửi.");
            assertApiError(() -> service.upload(ME, CONV_ID, "IMAGE", blob(new byte[0])), HttpStatus.BAD_REQUEST,
                    "Hãy chọn tệp để gửi.");
            verifyNothingStored();
        }

        @Test
        @DisplayName("UTC-376 Ảnh đúng 10 MB: được nhận")
        void utc376_imageOfExactlyTenMegabytesIsAccepted() {
            byte[] tenMb = new byte[(int) ChatService.IMAGE_MAX_BYTES];

            service.upload(ME, CONV_ID, "IMAGE", blob(tenMb));

            verify(repository).insertFile(eq(CONV_ID), eq(ME_ID), eq("IMAGE"), anyString(), eq(10_485_760L), eq(NOW));
        }

        @Test
        @DisplayName("UTC-377 Ảnh 10 MB + 1 byte: lỗi \"Mỗi ảnh tối đa 10 MB.\"")
        void utc377_imageOverTenMegabytesIsRefused() {
            assertApiError(() -> service.upload(ME, CONV_ID, "IMAGE", sized(ChatService.IMAGE_MAX_BYTES + 1)),
                    HttpStatus.BAD_REQUEST, "Mỗi ảnh tối đa 10 MB.");
            verifyNothingStored();
        }

        @Test
        @DisplayName("UTC-378 Video đúng 50 MB: được nhận")
        void utc378_videoOfExactlyFiftyMegabytesIsAccepted() {
            service.upload(ME, CONV_ID, "VIDEO", sized(ChatService.VIDEO_MAX_BYTES));

            verify(repository).insertFile(eq(CONV_ID), eq(ME_ID), eq("VIDEO"), anyString(), anyLong(), eq(NOW));
            assertThat(ChatService.VIDEO_MAX_BYTES).isEqualTo(52_428_800L);
        }

        @Test
        @DisplayName("UTC-379 Video 50 MB + 1 byte: lỗi \"Video tối đa 50 MB.\"")
        void utc379_videoOverFiftyMegabytesIsRefused() {
            assertApiError(() -> service.upload(ME, CONV_ID, "VIDEO", sized(ChatService.VIDEO_MAX_BYTES + 1)),
                    HttpStatus.BAD_REQUEST, "Video tối đa 50 MB.");
            verifyNothingStored();
        }

        @Test
        @DisplayName("UTC-380 Ghi âm đúng 5 MB: được nhận")
        void utc380_audioOfExactlyFiveMegabytesIsAccepted() {
            service.upload(ME, CONV_ID, "AUDIO", sized(ChatService.AUDIO_MAX_BYTES));

            verify(repository).insertFile(eq(CONV_ID), eq(ME_ID), eq("AUDIO"), anyString(), anyLong(), eq(NOW));
            assertThat(ChatService.AUDIO_MAX_BYTES).isEqualTo(5_242_880L);
        }

        @Test
        @DisplayName("UTC-381 Ghi âm 5 MB + 1 byte: lỗi \"Ghi âm tối đa 5 MB.\"")
        void utc381_audioOverFiveMegabytesIsRefused() {
            assertApiError(() -> service.upload(ME, CONV_ID, "AUDIO", sized(ChatService.AUDIO_MAX_BYTES + 1)),
                    HttpStatus.BAD_REQUEST, "Ghi âm tối đa 5 MB.");
            verifyNothingStored();
        }

        @Test
        @DisplayName("UTC-382 Tệp 12 MB: quá cỡ khi là ảnh nhưng được nhận khi là video (giới hạn theo loại)")
        void utc382_sizeLimitDependsOnTheKind() {
            long twelveMb = 12L * 1024 * 1024;

            assertApiError(() -> service.upload(ME, CONV_ID, "IMAGE", sized(twelveMb)), HttpStatus.BAD_REQUEST,
                    "Mỗi ảnh tối đa 10 MB.");
            service.upload(ME, CONV_ID, "VIDEO", sized(twelveMb));

            verify(repository).insertFile(eq(CONV_ID), eq(ME_ID), eq("VIDEO"), anyString(), anyLong(), eq(NOW));
        }

        @Test
        @DisplayName("UTC-383 Kích thước được kiểm tra trước khi đọc cuộc trò chuyện (người ngoài gửi tệp quá cỡ vẫn nhận lỗi kích thước)")
        void utc383_sizeIsCheckedBeforeTheConversation() {
            assertApiError(() -> service.upload(principal(OUTSIDER_ID, "USER"), CONV_ID, "AUDIO",
                    sized(ChatService.AUDIO_MAX_BYTES + 1)), HttpStatus.BAD_REQUEST, "Ghi âm tối đa 5 MB.");
            verify(repository, never()).conversation(anyLong());
            verify(repository, never()).lockUserDay(anyLong(), any());
        }

        @Test
        @DisplayName("UTC-384 Cuộc trò chuyện không tồn tại hoặc người gửi không thuộc cuộc trò chuyện: 404 \"Không tìm thấy cuộc trò chuyện này.\"")
        void utc384_unknownConversationIsNotFound() {
            assertApiError(() -> service.upload(ME, 999L, "IMAGE", blob(BYTES)), HttpStatus.NOT_FOUND,
                    ChatService.CONVERSATION_NOT_FOUND);
            assertApiError(() -> service.upload(principal(OUTSIDER_ID, "USER"), CONV_ID, "IMAGE", blob(BYTES)),
                    HttpStatus.NOT_FOUND, ChatService.CONVERSATION_NOT_FOUND);
            verifyNothingStored();
        }

        @Test
        @DisplayName("UTC-385 Không đọc được tệp tải lên (IOException): lỗi \"Không đọc được tệp tải lên. Vui lòng thử lại.\"")
        void utc385_unreadableUploadIsRefused() throws IOException {
            MultipartFile broken = mock(MultipartFile.class);
            when(broken.isEmpty()).thenReturn(false);
            when(broken.getSize()).thenReturn(100L);
            when(broken.getBytes()).thenThrow(new IOException("stream closed"));

            assertApiError(() -> service.upload(ME, CONV_ID, "IMAGE", broken), HttpStatus.BAD_REQUEST,
                    "Không đọc được tệp tải lên. Vui lòng thử lại.");
            verify(repository, never()).lockUserDay(anyLong(), any());
            verifyNothingStored();
        }

        @Test
        @DisplayName("UTC-386 Khách chưa đăng nhập: 401 \"Bạn cần đăng nhập để nhắn tin.\"")
        void utc386_guestIsUnauthorized() {
            assertApiError(() -> service.upload(null, CONV_ID, "IMAGE", blob(BYTES)), HttpStatus.UNAUTHORIZED, LOGIN);
            verifyNoInteractions(repository, storage);
        }

        @Test
        @DisplayName("UTC-387 Moderator, BTV tiếng Nhật, Admin: 403 MSG18")
        void utc387_staffIsForbidden() {
            for (String role : new String[] {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"}) {
                when(repository.account(ME_ID)).thenReturn(Optional.of(account(ME_ID, "An", role, "ACTIVE",
                        NOW.minus(Duration.ofDays(30)))));
                assertApiError(() -> service.upload(principal(ME_ID, role), CONV_ID, "IMAGE", blob(BYTES)),
                        HttpStatus.FORBIDDEN, MSG18);
            }
            verifyNothingStored();
        }

        @Test
        @DisplayName("UTC-388 Tài khoản thành viên bị khóa: 403 MSG18")
        void utc388_lockedMemberIsForbidden() {
            when(repository.account(ME_ID)).thenReturn(Optional.of(account(ME_ID, "An", "USER", "LOCKED",
                    NOW.minus(Duration.ofDays(30)))));

            assertApiError(() -> service.upload(ME, CONV_ID, "IMAGE", blob(BYTES)), HttpStatus.FORBIDDEN, MSG18);
            verifyNothingStored();
        }

        @Test
        @DisplayName("UTC-389 Lời mời chưa được nhận (còn lượt gửi): lỗi \"Chỉ gửi được ảnh, video, ghi âm trong cuộc trò chuyện đã mã hóa đầu cuối.\"")
        void utc389_requestNotAcceptedYetNeedsEncryption() {
            when(repository.member(CONV_ID, PEER_ID)).thenReturn(Optional.of(state("REQUEST", 0)));
            when(repository.countMessagesBy(CONV_ID, ME_ID)).thenReturn(1);

            assertApiError(() -> service.upload(ME, CONV_ID, "IMAGE", blob(BYTES)), HttpStatus.BAD_REQUEST,
                    ChatService.FILES_NEED_E2EE);
            verifyNothingStored();
        }

        @Test
        @DisplayName("UTC-390 Người gửi là người được mời và chưa trả lời: lỗi FILES_NEED_E2EE")
        void utc390_unansweredRequestNeedsEncryption() {
            when(repository.member(CONV_ID, ME_ID)).thenReturn(Optional.of(state("REQUEST", 0)));

            assertApiError(() -> service.upload(ME, CONV_ID, "IMAGE", blob(BYTES)), HttpStatus.BAD_REQUEST,
                    ChatService.FILES_NEED_E2EE);
            verifyNothingStored();
        }

        @Test
        @DisplayName("UTC-391 Cả hai đã nhận lời nhưng người gửi hoặc người kia chưa bật mã hóa: lỗi FILES_NEED_E2EE")
        void utc391_missingKeyNeedsEncryption() {
            when(repository.currentKey(ME_ID)).thenReturn(Optional.empty());
            assertApiError(() -> service.upload(ME, CONV_ID, "IMAGE", blob(BYTES)), HttpStatus.BAD_REQUEST,
                    ChatService.FILES_NEED_E2EE);
            when(repository.currentKey(ME_ID)).thenReturn(Optional.of(key(41, ME_ID)));
            when(repository.currentKey(PEER_ID)).thenReturn(Optional.empty());
            assertApiError(() -> service.upload(ME, CONV_ID, "IMAGE", blob(BYTES)), HttpStatus.BAD_REQUEST,
                    ChatService.FILES_NEED_E2EE);
            verifyNothingStored();
        }

        @Test
        @DisplayName("UTC-392 Đã gửi đủ 3 tin trong lời mời (WAITING_FULL): 403 với thông báo chờ trả lời, kiểm tra trước chế độ mã hóa")
        void utc392_waitingFullIsForbiddenBeforeModeCheck() {
            when(repository.member(CONV_ID, PEER_ID)).thenReturn(Optional.of(state("REQUEST", 0)));
            when(repository.countMessagesBy(CONV_ID, ME_ID)).thenReturn(ChatService.PENDING_MAX);

            assertApiError(() -> service.upload(ME, CONV_ID, "IMAGE", blob(BYTES)), HttpStatus.FORBIDDEN,
                    "Bình chưa nhận lời mời nhắn tin của bạn. Bạn gửi thêm được khi Bình trả lời.");
            verifyNothingStored();
        }

        @Test
        @DisplayName("UTC-393 Người kia đã chặn người gửi: 403 \"Bạn không thể nhắn tin cho thành viên này.\"")
        void utc393_blockedByPeerIsForbidden() {
            when(repository.blocked(PEER_ID, ME_ID)).thenReturn(true);

            assertApiError(() -> service.upload(ME, CONV_ID, "IMAGE", blob(BYTES)), HttpStatus.FORBIDDEN,
                    ChatService.UNAVAILABLE);
            verifyNothingStored();
        }

        @Test
        @DisplayName("UTC-394 Người gửi đã chặn người kia: lỗi \"Bạn đã chặn Bình. Bỏ chặn để nhắn tin tiếp.\"")
        void utc394_blockedByMeIsRefused() {
            when(repository.blocked(ME_ID, PEER_ID)).thenReturn(true);

            assertApiError(() -> service.upload(ME, CONV_ID, "IMAGE", blob(BYTES)), HttpStatus.BAD_REQUEST,
                    "Bạn đã chặn Bình. Bỏ chặn để nhắn tin tiếp.");
            verifyNothingStored();
        }

        @Test
        @DisplayName("UTC-395 Tài khoản người kia đã xóa: lỗi \"Tài khoản này đã bị xóa, không nhắn tin được nữa.\"")
        void utc395_deletedPeerIsRefused() {
            when(repository.account(PEER_ID)).thenReturn(Optional.of(account(PEER_ID, "Bình", "USER", "DELETED",
                    NOW.minus(Duration.ofDays(30)))));

            assertApiError(() -> service.upload(ME, CONV_ID, "IMAGE", blob(BYTES)), HttpStatus.BAD_REQUEST,
                    ChatService.DELETED);
            verifyNothingStored();
        }

        @Test
        @DisplayName("UTC-396 Tài khoản người kia bị khóa: 403 \"Bạn không thể nhắn tin cho thành viên này.\"")
        void utc396_lockedPeerIsForbidden() {
            when(repository.account(PEER_ID)).thenReturn(Optional.of(account(PEER_ID, "Bình", "USER", "LOCKED",
                    NOW.minus(Duration.ofDays(30)))));

            assertApiError(() -> service.upload(ME, CONV_ID, "IMAGE", blob(BYTES)), HttpStatus.FORBIDDEN,
                    ChatService.UNAVAILABLE);
            verifyNothingStored();
        }

        @Test
        @DisplayName("UTC-397 Tài khoản lâu năm đã gửi 99 tệp hôm nay: tệp thứ 100 được nhận")
        void utc397_hundredthFileIsAccepted() {
            when(repository.countFilesBetween(anyLong(), any(), any())).thenReturn(99);

            service.upload(ME, CONV_ID, "IMAGE", blob(BYTES));

            verify(storage).put(anyString(), eq(BYTES));
        }

        @Test
        @DisplayName("UTC-398 Tài khoản lâu năm đã gửi 100 tệp hôm nay: 429 \"Bạn đã gửi đủ 100 tệp hôm nay…\"")
        void utc398_hundredAndFirstFileIsRefused() {
            when(repository.countFilesBetween(anyLong(), any(), any())).thenReturn(100);

            assertApiError(() -> service.upload(ME, CONV_ID, "IMAGE", blob(BYTES)), HttpStatus.TOO_MANY_REQUESTS,
                    "Bạn đã gửi đủ 100 tệp hôm nay. Vui lòng thử lại vào ngày mai.");
            verifyNothingStored();
        }

        @Test
        @DisplayName("UTC-399 Tài khoản mới (2 ngày) đã gửi 19 tệp hôm nay: tệp thứ 20 được nhận")
        void utc399_newAccountTwentiethFileIsAccepted() {
            when(repository.account(ME_ID)).thenReturn(Optional.of(member(ME_ID, "An", Duration.ofDays(2))));
            when(repository.countFilesBetween(anyLong(), any(), any())).thenReturn(19);

            service.upload(ME, CONV_ID, "IMAGE", blob(BYTES));

            verify(storage).put(anyString(), eq(BYTES));
        }

        @Test
        @DisplayName("UTC-400 Tài khoản mới (2 ngày) đã gửi 20 tệp hôm nay: 429 \"…giới hạn của tài khoản mới (dưới 7 ngày)…\"")
        void utc400_newAccountTwentyFirstFileIsRefused() {
            when(repository.account(ME_ID)).thenReturn(Optional.of(member(ME_ID, "An", Duration.ofDays(2))));
            when(repository.countFilesBetween(anyLong(), any(), any())).thenReturn(20);

            assertApiError(() -> service.upload(ME, CONV_ID, "IMAGE", blob(BYTES)), HttpStatus.TOO_MANY_REQUESTS,
                    "Bạn đã gửi đủ 20 tệp hôm nay, giới hạn của tài khoản mới (dưới 7 ngày). Vui lòng thử lại vào"
                            + " ngày mai.");
            verifyNothingStored();
        }

        @Test
        @DisplayName("UTC-401 Tài khoản tạo đúng 7 ngày trước: không còn là tài khoản mới, 20 tệp hôm nay vẫn gửi tiếp được")
        void utc401_accountOfExactlySevenDaysUsesTheNormalLimit() {
            when(repository.account(ME_ID)).thenReturn(Optional.of(member(ME_ID, "An", Duration.ofDays(7))));
            when(repository.countFilesBetween(anyLong(), any(), any())).thenReturn(20);

            service.upload(ME, CONV_ID, "IMAGE", blob(BYTES));

            verify(storage).put(anyString(), eq(BYTES));
        }

        @Test
        @DisplayName("UTC-402 Cài đặt new_account_days = 3: tài khoản 5 ngày dùng giới hạn thường (20 tệp vẫn gửi được)")
        void utc402_newAccountDaysSettingIsRead() {
            when(settings.getInt(SystemSettings.NEW_ACCOUNT_DAYS, 7)).thenReturn(3);
            when(repository.account(ME_ID)).thenReturn(Optional.of(member(ME_ID, "An", Duration.ofDays(5))));
            when(repository.countFilesBetween(anyLong(), any(), any())).thenReturn(20);

            service.upload(ME, CONV_ID, "IMAGE", blob(BYTES));

            verify(storage).put(anyString(), eq(BYTES));
        }

        @Test
        @DisplayName("UTC-403 Đếm tệp trong ngày theo giờ Nhật (20/11: 19/11 15:00 → 20/11 15:00 UTC) và khóa dòng user_daily_usage của ngày đó")
        void utc403_dailyCountUsesTheJapanDay() {
            service.upload(ME, CONV_ID, "IMAGE", blob(BYTES));

            verify(repository).lockUserDay(ME_ID, LocalDate.of(2026, 11, 20));
            verify(repository).countFilesBetween(ME_ID, Instant.parse("2026-11-19T15:00:00Z"),
                    Instant.parse("2026-11-20T15:00:00Z"));
        }

        @Test
        @DisplayName("UTC-404 30/11 16:00 UTC (01:00 ngày 1/12 ở Nhật): đếm theo ngày 1/12, thư mục lưu theo tháng UTC chat/2026/11")
        void utc404_japanDayAndUtcMonthFolderAtMonthEnd() {
            Instant late = Instant.parse("2026-11-30T16:00:00Z");
            ChatService atMonthEnd = serviceAt(late);

            atMonthEnd.upload(ME, CONV_ID, "IMAGE", blob(BYTES));

            verify(repository).lockUserDay(ME_ID, LocalDate.of(2026, 12, 1));
            verify(repository).countFilesBetween(ME_ID, Instant.parse("2026-11-30T15:00:00Z"),
                    Instant.parse("2026-12-01T15:00:00Z"));
            assertThat(storedKey()).startsWith("chat/2026/11/");
            verify(repository).insertFile(eq(CONV_ID), eq(ME_ID), eq("IMAGE"), anyString(), eq(3L), eq(late));
        }

        @Test
        @DisplayName("UTC-405 Thứ tự: khóa ngày của người gửi, rồi khóa cuộc trò chuyện, lưu tệp vào kho, rồi ghi chat_files")
        void utc405_locksThenStoresThenRecords() {
            service.upload(ME, CONV_ID, "IMAGE", blob(BYTES));

            InOrder order = inOrder(repository, storage);
            order.verify(repository).conversation(CONV_ID);
            order.verify(repository).lockUserDay(eq(ME_ID), any());
            order.verify(repository).lockConversation(CONV_ID);
            order.verify(storage).put(anyString(), eq(BYTES));
            order.verify(repository).insertFile(anyLong(), anyLong(), anyString(), anyString(), anyLong(), any());
        }

        @Test
        @DisplayName("UTC-406 Giao dịch bị hủy (rollback): tệp vừa lưu bị xóa khỏi kho")
        void utc406_rollbackDeletesTheStoredBytes() {
            service.upload(ME, CONV_ID, "VIDEO", blob(BYTES));
            String key = storedKey();

            playRollback();

            verify(storage).delete(key);
        }

        @Test
        @DisplayName("UTC-407 Giao dịch commit: tệp được giữ trong kho")
        void utc407_commitKeepsTheStoredBytes() {
            service.upload(ME, CONV_ID, "VIDEO", blob(BYTES));

            playCommit();

            verify(storage, never()).delete(anyString());
        }

        @Test
        @DisplayName("UTC-408 Rollback mà xóa tệp khỏi kho bị lỗi: chỉ ghi log, không ném lỗi")
        void utc408_failedCleanUpAfterRollbackIsSwallowed() {
            doThrow(new IllegalStateException("R2 down")).when(storage).delete(anyString());
            service.upload(ME, CONV_ID, "AUDIO", blob(BYTES));

            assertThatCode(ChatServiceFilesTest::playRollback).doesNotThrowAnyException();
            verify(storage).delete(anyString());
        }

        @Test
        @DisplayName("UTC-409 Hai lần tải lên: mỗi tệp có khóa lưu trữ riêng (UUID khác nhau)")
        void utc409_everyUploadGetsItsOwnKey() {
            service.upload(ME, CONV_ID, "IMAGE", blob(BYTES));
            service.upload(ME, CONV_ID, "IMAGE", blob(BYTES));

            ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
            verify(storage, times(2)).put(keys.capture(), any());
            assertThat(keys.getAllValues()).doesNotHaveDuplicates().allMatch(k -> k.matches(KEY_PATTERN));
        }

        @Test
        @DisplayName("UTC-410 Tải tệp lên chưa gửi tin: không báo trực tiếp, không tạo thông báo, không lọc nội dung")
        void utc410_uploadAnnouncesNothing() {
            service.upload(ME, CONV_ID, "IMAGE", blob(BYTES));

            verifyNoInteractions(live, events, filter, reports, typing, viewers);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. file – Tải về tệp của tin nhắn (UTC-411 → UTC-424)")
    class ReadFile {

        private final Resource bytes = new ByteArrayResource(new byte[] {9, 8, 7});

        @BeforeEach
        void stored() {
            when(storage.resource("chat/2026/11/f.bin")).thenReturn(bytes);
        }

        @Test
        @DisplayName("UTC-411 Người tải lên đọc tệp chưa gửi của mình: nhận được nội dung đã mã hóa")
        void utc411_uploaderReadsOwnUnsentFile() {
            when(repository.file(FILE_ID)).thenReturn(Optional.of(fileRow(ME_ID, null, false)));

            Resource result = service.file(ME, CONV_ID, FILE_ID);

            assertThat(result).isSameAs(bytes);
        }

        @Test
        @DisplayName("UTC-412 Người kia đọc tệp chưa được gửi kèm tin nào: 404 \"Không tìm thấy tệp này.\"")
        void utc412_peerCannotReadUnsentFile() {
            when(repository.file(FILE_ID)).thenReturn(Optional.of(fileRow(ME_ID, null, false)));

            assertApiError(() -> service.file(PEER, CONV_ID, FILE_ID), HttpStatus.NOT_FOUND, ChatService.FILE_NOT_FOUND);
            verify(storage, never()).resource(anyString());
        }

        @Test
        @DisplayName("UTC-413 Người kia đọc tệp của tin nhắn đang hiện với họ: nhận được nội dung")
        void utc413_peerReadsFileOfShownMessage() {
            when(repository.member(CONV_ID, PEER_ID)).thenReturn(Optional.of(state("ACCEPTED", 300)));
            when(repository.file(FILE_ID)).thenReturn(Optional.of(fileRow(ME_ID, MESSAGE_ID, false)));
            when(repository.message(MESSAGE_ID)).thenReturn(Optional.of(message(false)));

            Resource result = service.file(PEER, CONV_ID, FILE_ID);

            assertThat(result).isSameAs(bytes);
            verify(repository).shown(MESSAGE_ID, 300L);
        }

        @Test
        @DisplayName("UTC-414 Tin nhắn mang tệp đã bị thu hồi: người kia nhận 404")
        void utc414_fileOfRecalledMessageIsNotFound() {
            when(repository.file(FILE_ID)).thenReturn(Optional.of(fileRow(ME_ID, MESSAGE_ID, false)));
            when(repository.message(MESSAGE_ID)).thenReturn(Optional.of(message(true)));

            assertApiError(() -> service.file(PEER, CONV_ID, FILE_ID), HttpStatus.NOT_FOUND, ChatService.FILE_NOT_FOUND);
        }

        @Test
        @DisplayName("UTC-415 Tin nhắn không còn hiện với người đọc (đã xóa cuộc trò chuyện, người gửi bị khóa): 404")
        void utc415_fileOfHiddenMessageIsNotFound() {
            when(repository.member(CONV_ID, PEER_ID)).thenReturn(Optional.of(state("ACCEPTED", 900)));
            when(repository.file(FILE_ID)).thenReturn(Optional.of(fileRow(ME_ID, MESSAGE_ID, false)));
            when(repository.message(MESSAGE_ID)).thenReturn(Optional.of(message(false)));
            when(repository.shown(MESSAGE_ID, 900L)).thenReturn(false);

            assertApiError(() -> service.file(PEER, CONV_ID, FILE_ID), HttpStatus.NOT_FOUND, ChatService.FILE_NOT_FOUND);
        }

        @Test
        @DisplayName("UTC-416 Tệp thuộc cuộc trò chuyện khác: 404 (kể cả với người tải lên)")
        void utc416_fileOfAnotherConversationIsNotFound() {
            when(repository.file(FILE_ID)).thenReturn(Optional.of(new FileRow(FILE_ID, 501L, ME_ID, null, "IMAGE",
                    "chat/2026/11/f.bin", 1000, NOW, false)));

            assertApiError(() -> service.file(ME, CONV_ID, FILE_ID), HttpStatus.NOT_FOUND, ChatService.FILE_NOT_FOUND);
        }

        @Test
        @DisplayName("UTC-417 Tệp đã bị đánh dấu xóa: 404 (kể cả với người tải lên)")
        void utc417_deletedFileIsNotFound() {
            when(repository.file(FILE_ID)).thenReturn(Optional.of(fileRow(ME_ID, MESSAGE_ID, true)));

            assertApiError(() -> service.file(ME, CONV_ID, FILE_ID), HttpStatus.NOT_FOUND, ChatService.FILE_NOT_FOUND);
            assertApiError(() -> service.file(PEER, CONV_ID, FILE_ID), HttpStatus.NOT_FOUND,
                    ChatService.FILE_NOT_FOUND);
            verify(storage, never()).resource(anyString());
        }

        @Test
        @DisplayName("UTC-418 Mã tệp không tồn tại: 404 \"Không tìm thấy tệp này.\"")
        void utc418_missingFileIsNotFound() {
            assertApiError(() -> service.file(ME, CONV_ID, 999L), HttpStatus.NOT_FOUND, ChatService.FILE_NOT_FOUND);
        }

        @Test
        @DisplayName("UTC-419 Kho lưu trữ không còn dữ liệu của tệp: 404 \"Không tìm thấy tệp này.\"")
        void utc419_missingStoredBytesIsNotFound() {
            when(repository.file(FILE_ID)).thenReturn(Optional.of(fileRow(ME_ID, null, false)));
            when(storage.resource("chat/2026/11/f.bin")).thenReturn(null);

            assertApiError(() -> service.file(ME, CONV_ID, FILE_ID), HttpStatus.NOT_FOUND, ChatService.FILE_NOT_FOUND);
        }

        @Test
        @DisplayName("UTC-420 Người ngoài cuộc trò chuyện: 404 \"Không tìm thấy cuộc trò chuyện này.\", không đọc tệp")
        void utc420_outsiderGetsConversationNotFound() {
            assertApiError(() -> service.file(principal(OUTSIDER_ID, "USER"), CONV_ID, FILE_ID), HttpStatus.NOT_FOUND,
                    ChatService.CONVERSATION_NOT_FOUND);
            verify(repository, never()).file(anyLong());
            verifyNoInteractions(storage);
        }

        @Test
        @DisplayName("UTC-421 Khách chưa đăng nhập: 401")
        void utc421_guestIsUnauthorized() {
            assertApiError(() -> service.file(null, CONV_ID, FILE_ID), HttpStatus.UNAUTHORIZED, LOGIN);
            verifyNoInteractions(repository, storage);
        }

        @Test
        @DisplayName("UTC-422 Tài khoản Moderator: 403 MSG18")
        void utc422_staffIsForbidden() {
            when(repository.account(ME_ID)).thenReturn(Optional.of(account(ME_ID, "An", "MODERATOR", "ACTIVE",
                    NOW.minus(Duration.ofDays(30)))));

            assertApiError(() -> service.file(principal(ME_ID, "MODERATOR"), CONV_ID, FILE_ID), HttpStatus.FORBIDDEN,
                    MSG18);
            verifyNoInteractions(storage);
        }

        @Test
        @DisplayName("UTC-423 Tệp trỏ tới tin nhắn không còn tồn tại: người kia nhận 404")
        void utc423_fileOfMissingMessageIsNotFound() {
            when(repository.file(FILE_ID)).thenReturn(Optional.of(fileRow(ME_ID, MESSAGE_ID, false)));

            assertApiError(() -> service.file(PEER, CONV_ID, FILE_ID), HttpStatus.NOT_FOUND, ChatService.FILE_NOT_FOUND);
        }

        @Test
        @DisplayName("UTC-424 Người tải lên đọc tệp đã gửi của mình: không cần kiểm tra tin nhắn mang tệp")
        void utc424_uploaderReadsSentFileWithoutMessageCheck() {
            when(repository.file(FILE_ID)).thenReturn(Optional.of(fileRow(ME_ID, MESSAGE_ID, false)));

            Resource result = service.file(ME, CONV_ID, FILE_ID);

            assertThat(result).isSameAs(bytes);
            verify(repository, never()).message(anyLong());
        }
    }
}
