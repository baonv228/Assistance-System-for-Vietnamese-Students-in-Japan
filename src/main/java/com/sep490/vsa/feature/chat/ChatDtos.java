package com.sep490.vsa.feature.chat;

import com.sep490.vsa.feature.report.ContentReportService.Reason;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * JSON shapes of "Tin nhắn" (/api/me/chats): the conversation list, one conversation, sending, editing, reactions,
 * files, encryption keys, blocking, reporting.
 */
public final class ChatDtos {

    private ChatDtos() {
    }

    public record Tag(String name, String color) {
    }

    /**
     * The other member of a conversation.
     *
     * @param active an ACTIVE Member (role USER): the only kind of account that can be messaged
     */
    public record Peer(Long id, String displayName, String avatarUrl, List<Tag> tags, boolean active) {
    }

    /**
     * An end-to-end encrypted message as stored: AES-GCM ciphertext (with its tag) of fk ‖ body, the two identity keys
     * it was written with, and the franking tag that lets the recipient prove what it said when reporting it.
     */
    public record Envelope(String ciphertext, String iv, Long senderKeyId, Long recipientKeyId, String frankingTag) {
    }

    /** One emoji of a message: how many members chose it, and whether the viewer is one of them. */
    public record Reaction(String emoji, int count, boolean mine) {
    }

    /**
     * @param mine      written by the viewer
     * @param content   the text of a plain message; null when it is encrypted or recalled
     * @param encrypted the envelope of an encrypted message (the page decrypts it); null otherwise
     * @param recalled  "Tin nhắn đã được thu hồi": no text, envelope, reactions or files any more
     * @param fileIds   photos, video or voice message sent with it (read with GET /{id}/files/{fileId})
     */
    public record Message(long id, boolean mine, String content, Envelope encrypted, Long replyToId, boolean edited,
                          boolean recalled, List<Reaction> reactions, List<Long> fileIds, Instant createdAt) {
    }

    /**
     * Why the viewer cannot send now, or what they should know before sending (shown above the message box).
     *
     * @param code NEW (first message to this member), WAITING (the request is not accepted yet; a few messages left),
     *             WAITING_FULL (no message left until the other member answers), REQUEST (the other member wrote first:
     *             accept, decline or answer), BLOCKED_BY_ME, UNAVAILABLE (blocked by them, locked or not a member),
     *             DELETED; in {@link Encryption}: NO_KEY_ME, NO_KEY_PEER, REQUEST_PLAIN
     */
    public record Notice(String code, String text) {
    }

    /**
     * Whether the conversation is end-to-end encrypted for the viewer, and the keys the page encrypts with.
     *
     * @param mode          E2EE when both members accepted the conversation and both have a key; else PLAIN
     * @param peerPublicKey base64 raw P-256 point of the other member's current key (null before the first message)
     * @param notice        NO_KEY_ME, NO_KEY_PEER, REQUEST_PLAIN, or null
     */
    public record Encryption(String mode, Long myKeyId, Long peerKeyId, String peerPublicKey, Notice notice) {
    }

    /**
     * What the viewer can do in a conversation now.
     *
     * @param state        the viewer's own state: ACCEPTED, REQUEST or DECLINED; null before the first message
     * @param peerReadUpTo the newest of the viewer's messages the other member has read ("Đã xem"); 0 while they have
     *                     not accepted the conversation
     * @param canAttach    photos, video and voice messages can be sent (encrypted conversation, sending allowed)
     * @param canReact     reactions and "đang soạn tin" work (both accepted, nobody blocked)
     * @param peerTyping   the other member is typing ("đang soạn tin")
     */
    public record Status(String state, boolean canSend, Notice notice, boolean blockedByMe, long peerReadUpTo,
                         Encryption encryption, boolean canAttach, boolean canReact, boolean peerTyping) {
    }

    /** One row of the list. {@code unread}: the other member's messages the viewer has not read. */
    public record ConversationItem(long id, Peer peer, String state, Message lastMessage, int unread,
                                   boolean blockedByMe) {
    }

    /** The newest unread message, for the header's in-app notice. {@code request}: it is a "Lời mời". */
    public record Latest(long conversationId, long messageId, String peerName, boolean request, Instant createdAt) {
    }

    /**
     * The header badge and the tab counts.
     *
     * @param unread   conversations (inbox and requests) with unread messages
     * @param requests requests waiting for the viewer's answer ("Lời mời")
     * @param latest   the newest unread message, or null
     */
    public record Counts(int unread, int requests, Latest latest) {
    }

    public record ConversationList(List<ConversationItem> items, boolean hasMore, Counts counts) {
    }

    /** The limits the page shows next to the message box and checks before uploading. */
    public record Limits(int messageMax, int pendingMax, int editMinutes, int filesMax, long imageMaxBytes,
                         long videoMaxBytes, long audioMaxBytes, int videoMaxSeconds, int audioMaxSeconds,
                         List<String> reactions) {
    }

    /**
     * One conversation, its newest messages (oldest first) and what the viewer may do. {@code id} is null when the
     * viewer opens a member they have never written to: the conversation is made by the first message.
     * {@code serverTime}: the page's first {@code changedSince} (the device's own clock may be wrong).
     */
    public record Conversation(Long id, Peer peer, Status status, List<Message> messages, boolean hasOlder,
                               List<Reason> reportReasons, Limits limits, Instant serverTime) {
    }

    /** Older messages ("Xem tin cũ hơn"), oldest first. */
    public record OlderMessages(List<Message> messages, boolean hasOlder) {
    }

    /**
     * What the open conversation asks every few seconds: the messages after the newest one it has, the messages
     * edited, recalled or reacted to since {@code changedSince}, the status, and the time to pass as the next
     * {@code changedSince}.
     */
    public record Updates(List<Message> messages, List<Message> changed, Status status, Instant serverTime) {
    }

    /**
     * A new message: {@code content} in a plain conversation, {@code encrypted} (+ {@code fileIds}) in an encrypted
     * one; {@code replyToId} quotes an earlier message.
     */
    public record SendRequest(String content, Envelope encrypted, List<Long> fileIds, Long replyToId) {

        public SendRequest(String content) {
            this(content, null, null, null);
        }
    }

    public record Sent(long conversationId, Message message, Status status) {
    }

    /** The new text of a plain message, or the new envelope of an encrypted one. */
    public record EditRequest(String content, Envelope encrypted) {
    }

    public record ReactionRequest(String emoji) {
    }

    public record FileUploaded(long fileId) {
    }

    /** Marks the conversation read up to this message (the newest the page has shown; null: all of it). */
    public record ReadRequest(Long upToId) {
    }

    /**
     * @param block       also block the member who wrote the message
     * @param plaintext   encrypted message only: base64 of the decrypted body P the reporter reveals
     * @param frankingKey encrypted message only: base64 of the franking key fk found inside the message
     */
    public record ReportRequest(@NotNull(message = "Hãy chọn lý do báo cáo.") Long reasonId, String note,
                                boolean block, String plaintext, String frankingKey) {

        public ReportRequest(Long reasonId, String note, boolean block) {
            this(reasonId, note, block, null, null);
        }
    }

    public record BlockedMember(Peer peer, Instant blockedAt) {
    }

    /**
     * A member's identity key with its private half wrapped by their "mật khẩu khôi phục" (the server cannot unwrap it).
     */
    public record KeyBackup(long id, String publicKey, String wrappedPrivateKey, String wrapSalt, String wrapIv,
                            int wrapIterations, Instant createdAt) {
    }

    /** The viewer's current key, or null before they turn encryption on. */
    public record MyKey(KeyBackup key) {
    }

    public record KeyRequest(String publicKey, String wrappedPrivateKey, String wrapSalt, String wrapIv,
                             Integer wrapIterations) {
    }

    /** A public key, for checking who wrote an old message ("Mã an toàn"). */
    public record PublicKey(long id, long userId, String publicKey, boolean revoked) {
    }
}
