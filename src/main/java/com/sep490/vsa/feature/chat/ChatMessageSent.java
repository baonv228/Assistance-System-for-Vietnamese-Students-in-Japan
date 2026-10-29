package com.sep490.vsa.feature.chat;

/**
 * Published by {@link ChatService} inside the transaction that saves a new message (plain or encrypted, requests
 * included). Listeners that tell the recipient (e-mail, Web Push: {@code feature/chat/notify}) use
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}, so nothing is announced for a message that was rolled back.
 *
 * @param senderName the writer's display name, as shown to the recipient ("Tin nhắn mới từ …")
 * @param encrypted  the message is end-to-end encrypted: its text must never be put in an e-mail or a push
 * @param request    the recipient has not accepted the conversation yet ("Lời mời")
 */
public record ChatMessageSent(long conversationId, long messageId, long senderId, String senderName, long recipientId,
                              boolean encrypted, boolean request) {
}
