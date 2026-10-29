package com.sep490.vsa.feature.chat;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.chat.ChatDtos.BlockedMember;
import com.sep490.vsa.feature.chat.ChatDtos.Conversation;
import com.sep490.vsa.feature.chat.ChatDtos.ConversationList;
import com.sep490.vsa.feature.chat.ChatDtos.Counts;
import com.sep490.vsa.feature.chat.ChatDtos.EditRequest;
import com.sep490.vsa.feature.chat.ChatDtos.FileUploaded;
import com.sep490.vsa.feature.chat.ChatDtos.KeyBackup;
import com.sep490.vsa.feature.chat.ChatDtos.KeyRequest;
import com.sep490.vsa.feature.chat.ChatDtos.Message;
import com.sep490.vsa.feature.chat.ChatDtos.MyKey;
import com.sep490.vsa.feature.chat.ChatDtos.OlderMessages;
import com.sep490.vsa.feature.chat.ChatDtos.PublicKey;
import com.sep490.vsa.feature.chat.ChatDtos.ReactionRequest;
import com.sep490.vsa.feature.chat.ChatDtos.ReadRequest;
import com.sep490.vsa.feature.chat.ChatDtos.ReportRequest;
import com.sep490.vsa.feature.chat.ChatDtos.SendRequest;
import com.sep490.vsa.feature.chat.ChatDtos.Sent;
import com.sep490.vsa.feature.chat.ChatDtos.Status;
import com.sep490.vsa.feature.chat.ChatDtos.Updates;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.List;

/**
 * "Tin nhắn" between Members (page /messages). /api/me/** requires login; ChatService allows signed-in Members only.
 */
@RestController
@RequestMapping("/api/me/chats")
public class ChatController {

    /** Files are encrypted and never change, so the browser may keep them for a day (private: not in shared caches). */
    private static final CacheControl FILE_CACHE = CacheControl.maxAge(Duration.ofDays(1)).cachePrivate();

    private final ChatService service;
    private final ChatKeyService keys;

    public ChatController(ChatService service, ChatKeyService keys) {
        this.service = service;
        this.keys = keys;
    }

    /** {@code box}: INBOX or REQUESTS ("Lời mời"). */
    @GetMapping
    public ApiResponse<ConversationList> list(@AuthenticationPrincipal AppUserPrincipal me,
                                              @RequestParam(defaultValue = "INBOX") String box,
                                              @RequestParam(defaultValue = "0") int page) {
        return ApiResponse.ok("OK", service.list(me, box, page));
    }

    @GetMapping("/counts")
    public ApiResponse<Counts> counts(@AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", service.counts(me));
    }

    @GetMapping("/{id:\\d+}")
    public ApiResponse<Conversation> open(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable long id) {
        return ApiResponse.ok("OK", service.open(me, id));
    }

    /** {@code limit} 1–200 (default 30); without {@code before}, the newest messages. */
    @GetMapping("/{id:\\d+}/messages")
    public ApiResponse<OlderMessages> older(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable long id,
                                            @RequestParam(required = false) Long before,
                                            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok("OK", service.older(me, id, before, limit));
    }

    /** {@code changedSince}: the {@code serverTime} of the answer before. */
    @GetMapping("/{id:\\d+}/updates")
    public ApiResponse<Updates> updates(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable long id,
                                        @RequestParam(defaultValue = "0") long after,
                                        @RequestParam(required = false) String changedSince) {
        return ApiResponse.ok("OK", service.updates(me, id, after, changedSince));
    }

    @PostMapping("/{id:\\d+}/messages")
    public ApiResponse<Sent> send(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable long id,
                                  @RequestBody SendRequest body) {
        return ApiResponse.ok("Đã gửi.", service.send(me, id, body));
    }

    @GetMapping("/{id:\\d+}/messages/{messageId:\\d+}")
    public ApiResponse<Message> message(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable long id,
                                        @PathVariable long messageId) {
        return ApiResponse.ok("OK", service.message(me, id, messageId));
    }

    @PutMapping("/{id:\\d+}/messages/{messageId:\\d+}")
    public ApiResponse<Message> edit(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable long id,
                                     @PathVariable long messageId, @RequestBody EditRequest body) {
        return ApiResponse.ok("Đã sửa tin nhắn.", service.edit(me, id, messageId, body));
    }

    /** "Thu hồi" for both members. */
    @DeleteMapping("/{id:\\d+}/messages/{messageId:\\d+}")
    public ApiResponse<Message> recall(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable long id,
                                       @PathVariable long messageId) {
        return ApiResponse.ok("Đã thu hồi tin nhắn.", service.recall(me, id, messageId));
    }

    @PutMapping("/{id:\\d+}/messages/{messageId:\\d+}/reaction")
    public ApiResponse<Message> react(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable long id,
                                      @PathVariable long messageId, @RequestBody ReactionRequest body) {
        String emoji = body == null || body.emoji() == null ? "" : body.emoji();
        return ApiResponse.ok("OK", service.react(me, id, messageId, emoji));
    }

    @DeleteMapping("/{id:\\d+}/messages/{messageId:\\d+}/reaction")
    public ApiResponse<Message> unreact(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable long id,
                                        @PathVariable long messageId) {
        return ApiResponse.ok("OK", service.react(me, id, messageId, null));
    }

    /** "đang soạn tin": always 200 for a member of the conversation. */
    @PostMapping("/{id:\\d+}/typing")
    public ApiResponse<Void> typing(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable long id) {
        service.typing(me, id);
        return ApiResponse.ok("OK", null);
    }

    /** Multipart {@code kind} (IMAGE, VIDEO, AUDIO) + {@code file} (the encrypted bytes). */
    @PostMapping("/{id:\\d+}/files")
    public ApiResponse<FileUploaded> upload(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable long id,
                                            @RequestParam(value = "kind", required = false) String kind,
                                            @RequestParam(value = "file", required = false) MultipartFile file) {
        return ApiResponse.ok("OK", service.upload(me, id, kind, file));
    }

    /** The encrypted bytes, for the two members only; the page decrypts them. */
    @GetMapping("/{id:\\d+}/files/{fileId:\\d+}")
    public ResponseEntity<Resource> file(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable long id,
                                         @PathVariable long fileId) {
        Resource file = service.file(me, id, fileId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .cacheControl(FILE_CACHE)
                .header("X-Content-Type-Options", "nosniff")
                .body(file);
    }

    @PostMapping("/{id:\\d+}/read")
    public ApiResponse<Counts> read(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable long id,
                                    @RequestBody(required = false) ReadRequest body) {
        return ApiResponse.ok("OK", service.markRead(me, id, body));
    }

    @PostMapping("/{id:\\d+}/accept")
    public ApiResponse<Status> accept(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable long id) {
        return ApiResponse.ok("Đã chấp nhận lời mời nhắn tin.", service.accept(me, id));
    }

    @PostMapping("/{id:\\d+}/decline")
    public ApiResponse<Counts> decline(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable long id) {
        return ApiResponse.ok("Đã từ chối lời mời nhắn tin.", service.decline(me, id));
    }

    /** "Xóa cuộc trò chuyện" for the signed-in member only. */
    @DeleteMapping("/{id:\\d+}")
    public ApiResponse<Counts> clear(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable long id) {
        return ApiResponse.ok("Đã xóa cuộc trò chuyện.", service.clear(me, id));
    }

    /** The conversation with a member, opened from their name; no id before the first message. */
    @GetMapping("/with/{userId:\\d+}")
    public ApiResponse<Conversation> openWith(@AuthenticationPrincipal AppUserPrincipal me,
                                              @PathVariable long userId) {
        return ApiResponse.ok("OK", service.openWith(me, userId));
    }

    @PostMapping("/with/{userId:\\d+}/messages")
    public ApiResponse<Sent> sendTo(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable long userId,
                                    @RequestBody SendRequest body) {
        return ApiResponse.ok("Đã gửi.", service.sendTo(me, userId, body));
    }

    /** For an encrypted message the body also carries {@code plaintext} and {@code frankingKey}. */
    @PostMapping("/messages/{messageId:\\d+}/report")
    public ApiResponse<Void> report(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable long messageId,
                                    @Valid @RequestBody ReportRequest body) {
        service.reportMessage(me, messageId, body);
        return ApiResponse.ok("Cảm ơn bạn. Báo cáo đã được gửi.", null);                   // MSG38
    }

    @GetMapping("/blocks")
    public ApiResponse<List<BlockedMember>> blocks(@AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", service.blocks(me));
    }

    @PostMapping("/blocks/{userId:\\d+}")
    public ApiResponse<Void> block(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable long userId) {
        service.block(me, userId);
        return ApiResponse.ok("Đã chặn. Hai bạn không nhắn tin được cho nhau nữa.", null);
    }

    @DeleteMapping("/blocks/{userId:\\d+}")
    public ApiResponse<Void> unblock(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable long userId) {
        service.unblock(me, userId);
        return ApiResponse.ok("Đã bỏ chặn.", null);
    }

    // ---------------------------------------------------------------- end-to-end encryption keys

    @GetMapping("/keys/me")
    public ApiResponse<MyKey> myKey(@AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", keys.myKey(me));
    }

    @PostMapping("/keys")
    public ApiResponse<KeyBackup> createKey(@AuthenticationPrincipal AppUserPrincipal me,
                                            @RequestBody KeyRequest body) {
        return ApiResponse.ok("Đã bật mã hóa đầu cuối.", keys.create(me, body));
    }

    @GetMapping("/keys/{keyId:\\d+}")
    public ApiResponse<PublicKey> key(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable long keyId) {
        return ApiResponse.ok("OK", keys.key(me, keyId));
    }
}
