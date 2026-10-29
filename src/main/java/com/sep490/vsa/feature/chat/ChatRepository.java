package com.sep490.vsa.feature.chat;

import com.sep490.vsa.feature.chat.ChatDtos.Envelope;
import com.sep490.vsa.feature.chat.ChatDtos.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Plain SQL over {@code chat_conversations}, {@code chat_members}, {@code chat_messages}, {@code chat_blocks} and the
 * phase-2 tables: {@code chat_keys}, {@code chat_message_edits}, {@code chat_reactions}, {@code chat_files},
 * {@code chat_report_evidence}.
 * <p>
 * A message is shown only when its sender's account is ACTIVE ({@link #SHOWN}): like posts and comments, the messages
 * of a LOCKED or DELETED account are hidden from the other member and come back when the account is unlocked. A
 * member also no longer sees the messages up to their own {@code cleared_id} ("Xóa cuộc trò chuyện"). A recalled
 * message is still shown, as "Tin nhắn đã được thu hồi".
 */
@Repository
public class ChatRepository {

    /** SQL condition: the sender of the {@code chat_messages} row {@code m} is an ACTIVE account. */
    static final String SHOWN = "EXISTS (SELECT 1 FROM users su WHERE su.id = m.sender_id AND su.status = 'ACTIVE')";

    public record Account(long id, String displayName, String avatarUrl, String role, String status,
                          Instant createdAt) {

        /** An ACTIVE Member (role USER): the only kind of account that chats. */
        boolean member() {
            return "USER".equals(role) && "ACTIVE".equals(status);
        }

        boolean deleted() {
            return "DELETED".equals(status);
        }
    }

    public record ConversationRow(long id, long userLowId, long userHighId, long startedBy) {

        boolean has(long userId) {
            return userLowId == userId || userHighId == userId;
        }

        long other(long userId) {
            return userLowId == userId ? userHighId : userLowId;
        }
    }

    /** One member's side of a conversation. */
    public record MemberRow(String state, long lastReadId, long clearedId) {
    }

    /**
     * A message: {@code content} for a plain one, {@code ciphertext} … {@code frankingTag} for an encrypted one (both
     * may be emptied 30 days after a recall).
     */
    public record MessageRow(long id, long conversationId, long senderId, String content, String ciphertext,
                             String iv, Long senderKeyId, Long recipientKeyId, String frankingTag, Long replyToId,
                             Instant editedAt, Instant recalledAt, Instant createdAt) {

        boolean encrypted() {
            return ciphertext != null || frankingTag != null;
        }

        boolean recalled() {
            return recalledAt != null;
        }

        Envelope envelope() {
            return ciphertext == null ? null : new Envelope(ciphertext, iv, senderKeyId, recipientKeyId, frankingTag);
        }
    }

    /** One conversation of the list, before its last message and tags are added. */
    public record ListRow(long id, String state, long peerId, String peerName, String peerAvatar, String peerRole,
                          String peerStatus, long lastId, int unread, boolean blockedByMe) {
    }

    public record BlockRow(long userId, String displayName, String avatarUrl, String role, String status,
                           Instant blockedAt) {
    }

    /** An identity key; the current one of a member is their newest row without {@code revokedAt}. */
    public record KeyRow(long id, long userId, String publicKey, String wrappedPrivateKey, String wrapSalt,
                         String wrapIv, int wrapIterations, Instant createdAt, Instant revokedAt) {
    }

    /** A file of an encrypted conversation; {@code messageId} is null until the message carrying it is sent. */
    public record FileRow(long id, long conversationId, long uploaderId, Long messageId, String kind,
                          String storageKey, long sizeBytes, Instant createdAt, boolean deleted) {
    }

    public record ReactionRow(long messageId, long userId, String emoji) {
    }

    /** The newest unread message of a member (header notice). */
    public record LatestRow(long conversationId, long messageId, String peerName, String state, Instant createdAt) {
    }

    private static final String MESSAGE_COLUMNS = """
            m.id, m.conversation_id, m.sender_id, m.content, m.ciphertext, m.iv, m.sender_key_id, m.recipient_key_id,
            m.franking_tag, m.reply_to_id, m.edited_at, m.recalled_at, m.created_at""";
    private static final String KEY_COLUMNS = """
            id, user_id, public_key, wrapped_private_key, wrap_salt, wrap_iv, wrap_iterations, created_at, revoked_at""";
    private static final String FILE_COLUMNS = """
            id, conversation_id, uploader_id, message_id, kind, storage_key, size_bytes, created_at,
            deleted_at IS NOT NULL""";

    private static final RowMapper<Account> ACCOUNT = (rs, i) -> new Account(rs.getLong(1), rs.getString(2),
            rs.getString(3), rs.getString(4), rs.getString(5), instant(rs.getTimestamp(6)));
    private static final RowMapper<ConversationRow> CONVERSATION = (rs, i) -> new ConversationRow(rs.getLong(1),
            rs.getLong(2), rs.getLong(3), rs.getLong(4));
    private static final RowMapper<MessageRow> MESSAGE = (rs, i) -> new MessageRow(rs.getLong(1), rs.getLong(2),
            rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6), longOrNull(rs, 7), longOrNull(rs, 8),
            rs.getString(9), longOrNull(rs, 10), instant(rs.getTimestamp(11)), instant(rs.getTimestamp(12)),
            instant(rs.getTimestamp(13)));
    private static final RowMapper<KeyRow> KEY = (rs, i) -> new KeyRow(rs.getLong(1), rs.getLong(2), rs.getString(3),
            rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7), instant(rs.getTimestamp(8)),
            instant(rs.getTimestamp(9)));
    private static final RowMapper<FileRow> FILE = (rs, i) -> new FileRow(rs.getLong(1), rs.getLong(2), rs.getLong(3),
            longOrNull(rs, 4), rs.getString(5), rs.getString(6), rs.getLong(7), instant(rs.getTimestamp(8)),
            rs.getBoolean(9));

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public ChatRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    // ---------------------------------------------------------------- accounts

    public Optional<Account> account(long userId) {
        return jdbc.query("SELECT id, display_name, avatar_url, role, status, created_at FROM users WHERE id = ?",
                ACCOUNT, userId).stream().findFirst();
    }

    /** Approved, not revoked identity tags of each user. */
    public Map<Long, List<Tag>> tagsOf(Collection<Long> userIds) {
        Map<Long, List<Tag>> result = new HashMap<>();
        if (userIds.isEmpty()) {
            return result;
        }
        named.query("""
                SELECT ut.user_id, t.name, t.color FROM user_tags ut JOIN tag_types t ON t.id = ut.tag_type_id
                WHERE ut.user_id IN (:ids) AND ut.revoked_at IS NULL AND t.is_active ORDER BY ut.granted_at""",
                Map.of("ids", userIds), (ResultSet rs) -> {
                    while (rs.next()) {
                        result.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                                .add(new Tag(rs.getString(2), rs.getString(3)));
                    }
                    return null;
                });
        return result;
    }

    /**
     * Locks the user's {@code user_daily_usage} row of {@code day} (made when missing) until the transaction ends, as
     * comments and Write Post do: one member's messages, uploads and new keys are then checked and counted one after
     * the other.
     */
    public void lockUserDay(long userId, LocalDate day) {
        jdbc.update("""
                INSERT INTO user_daily_usage (user_id, usage_date) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE posts = posts""", userId, Date.valueOf(day));
    }

    // ---------------------------------------------------------------- conversations

    public Optional<ConversationRow> conversation(long id) {
        return jdbc.query("SELECT id, user_low_id, user_high_id, started_by FROM chat_conversations WHERE id = ?",
                CONVERSATION, id).stream().findFirst();
    }

    /** The conversation row, locked until the transaction ends: actions on one conversation run one after another. */
    public Optional<ConversationRow> lockConversation(long id) {
        return jdbc.query("""
                SELECT id, user_low_id, user_high_id, started_by FROM chat_conversations WHERE id = ? FOR UPDATE""",
                CONVERSATION, id).stream().findFirst();
    }

    public Optional<ConversationRow> conversationOf(long userA, long userB) {
        return jdbc.query("""
                SELECT id, user_low_id, user_high_id, started_by FROM chat_conversations
                WHERE user_low_id = ? AND user_high_id = ?""",
                CONVERSATION, Math.min(userA, userB), Math.max(userA, userB)).stream().findFirst();
    }

    /** {@link #conversationOf}, locked until the transaction ends. */
    public Optional<ConversationRow> lockConversationOf(long userA, long userB) {
        return jdbc.query("""
                SELECT id, user_low_id, user_high_id, started_by FROM chat_conversations
                WHERE user_low_id = ? AND user_high_id = ? FOR UPDATE""",
                CONVERSATION, Math.min(userA, userB), Math.max(userA, userB)).stream().findFirst();
    }

    /**
     * A new conversation started by {@code starter}: the starter is ACCEPTED, the other member gets a REQUEST.
     * Throws {@code DuplicateKeyException} when the two members' conversation was made meanwhile.
     */
    public long insertConversation(long starter, long other, Instant now) {
        Timestamp at = Timestamp.from(now);
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO chat_conversations (user_low_id, user_high_id, started_by, created_at)
                    VALUES (?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, Math.min(starter, other));
            ps.setLong(2, Math.max(starter, other));
            ps.setLong(3, starter);
            ps.setTimestamp(4, at);
            return ps;
        }, key);
        long id = key.getKey().longValue();
        jdbc.update("""
                INSERT INTO chat_members (conversation_id, user_id, state, updated_at)
                VALUES (?, ?, 'ACCEPTED', ?), (?, ?, 'REQUEST', ?)""", id, starter, at, id, other, at);
        return id;
    }

    public Optional<MemberRow> member(long conversationId, long userId) {
        return jdbc.query("""
                SELECT state, last_read_id, cleared_id FROM chat_members WHERE conversation_id = ? AND user_id = ?""",
                (rs, i) -> new MemberRow(rs.getString(1), rs.getLong(2), rs.getLong(3)), conversationId, userId)
                .stream().findFirst();
    }

    public void setState(long conversationId, long userId, String state, Instant now) {
        jdbc.update("UPDATE chat_members SET state = ?, updated_at = ? WHERE conversation_id = ? AND user_id = ?",
                state, Timestamp.from(now), conversationId, userId);
    }

    /** Read up to {@code upToId} (never past the newest message, never back). */
    public void markRead(long conversationId, long userId, long upToId) {
        jdbc.update("""
                UPDATE chat_members SET last_read_id = GREATEST(last_read_id, LEAST(?,
                    (SELECT COALESCE(MAX(id), 0) FROM chat_messages WHERE conversation_id = ?)))
                WHERE conversation_id = ? AND user_id = ?""", upToId, conversationId, conversationId, userId);
    }

    /** "Xóa cuộc trò chuyện" for this member: every message so far is hidden from them and counted as read. */
    public void clear(long conversationId, long userId, Instant now) {
        jdbc.update("""
                UPDATE chat_members m
                JOIN (SELECT COALESCE(MAX(id), 0) AS newest FROM chat_messages WHERE conversation_id = ?) x
                SET m.cleared_id = x.newest, m.last_read_id = GREATEST(m.last_read_id, x.newest), m.updated_at = ?
                WHERE m.conversation_id = ? AND m.user_id = ?""",
                conversationId, Timestamp.from(now), conversationId, userId);
    }

    // ---------------------------------------------------------------- messages

    /** A plain message ({@code content}) or an encrypted one ({@code envelope}). */
    public long insertMessage(long conversationId, long senderId, String content, Envelope envelope, Long replyToId,
                              Instant now) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO chat_messages (conversation_id, sender_id, content, ciphertext, iv, sender_key_id,
                                               recipient_key_id, franking_tag, reply_to_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, conversationId);
            ps.setLong(2, senderId);
            ps.setString(3, content);
            ps.setString(4, envelope == null ? null : envelope.ciphertext());
            ps.setString(5, envelope == null ? null : envelope.iv());
            ps.setObject(6, envelope == null ? null : envelope.senderKeyId(), Types.BIGINT);
            ps.setObject(7, envelope == null ? null : envelope.recipientKeyId(), Types.BIGINT);
            ps.setString(8, envelope == null ? null : envelope.frankingTag());
            ps.setObject(9, replyToId, Types.BIGINT);
            ps.setTimestamp(10, Timestamp.from(now));
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    public Optional<MessageRow> message(long id) {
        return jdbc.query("SELECT " + MESSAGE_COLUMNS + " FROM chat_messages m WHERE m.id = ?", MESSAGE, id)
                .stream().findFirst();
    }

    /** Is the message shown to a member whose messages up to {@code clearedId} are hidden? */
    public boolean shown(long messageId, long clearedId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages m WHERE m.id = ? AND m.id > ? AND " + SHOWN,
                Long.class, messageId, clearedId);
        return n != null && n > 0;
    }

    /** The newest {@code limit} shown messages before {@code beforeId} (null: the newest), oldest first. */
    public List<MessageRow> messagesBefore(long conversationId, long clearedId, Long beforeId, int limit) {
        List<MessageRow> rows = new ArrayList<>(jdbc.query("""
                SELECT %s FROM chat_messages m
                WHERE m.conversation_id = ? AND m.id > ? AND m.id < ? AND %s
                ORDER BY m.id DESC LIMIT ?""".formatted(MESSAGE_COLUMNS, SHOWN),
                MESSAGE, conversationId, clearedId, beforeId == null ? Long.MAX_VALUE : beforeId, limit));
        Collections.reverse(rows);
        return rows;
    }

    /** At most {@code limit} shown messages after {@code afterId}, oldest first. */
    public List<MessageRow> messagesAfter(long conversationId, long clearedId, long afterId, int limit) {
        return jdbc.query("""
                SELECT %s FROM chat_messages m
                WHERE m.conversation_id = ? AND m.id > ? AND %s
                ORDER BY m.id LIMIT ?""".formatted(MESSAGE_COLUMNS, SHOWN),
                MESSAGE, conversationId, Math.max(clearedId, afterId), limit);
    }

    /** At most {@code limit} shown messages edited, recalled or reacted to at or after {@code since}, id order. */
    public List<MessageRow> changedSince(long conversationId, long clearedId, Instant since, int limit) {
        return jdbc.query("""
                SELECT %s FROM chat_messages m
                WHERE m.conversation_id = ? AND m.changed_at >= ? AND m.id > ? AND %s
                ORDER BY m.id LIMIT ?""".formatted(MESSAGE_COLUMNS, SHOWN),
                MESSAGE, conversationId, Timestamp.from(since), clearedId, limit);
    }

    public List<MessageRow> messagesByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return named.query("SELECT " + MESSAGE_COLUMNS + " FROM chat_messages m WHERE m.id IN (:ids)",
                Map.of("ids", ids), MESSAGE);
    }

    /** Every message {@code senderId} wrote in the conversation (shown, recalled or not). */
    public int countMessagesBy(long conversationId, long senderId) {
        return count("SELECT COUNT(*) FROM chat_messages WHERE conversation_id = ? AND sender_id = ?",
                conversationId, senderId);
    }

    /** Messages the user wrote at or after {@code since}, in any conversation. */
    public int countMessagesSince(long senderId, Instant since) {
        return count("SELECT COUNT(*) FROM chat_messages WHERE sender_id = ? AND created_at >= ?",
                senderId, Timestamp.from(since));
    }

    /** Conversations the user started in [from, to). */
    public int countStartedBetween(long userId, Instant from, Instant to) {
        return count("SELECT COUNT(*) FROM chat_conversations WHERE started_by = ? AND created_at >= ? AND created_at < ?",
                userId, Timestamp.from(from), Timestamp.from(to));
    }

    /**
     * The user's newest {@code limit} plain messages since {@code since} in other conversations than this one
     * (recalled ones too, so recalling does not get round the repeated-text rule).
     */
    public List<MessageRow> recentMessagesElsewhere(long senderId, Long conversationId, Instant since, int limit) {
        return jdbc.query("""
                SELECT %s FROM chat_messages m
                WHERE m.sender_id = ? AND m.conversation_id <> ? AND m.created_at >= ? AND m.content IS NOT NULL
                ORDER BY m.id DESC LIMIT ?""".formatted(MESSAGE_COLUMNS),
                MESSAGE, senderId, conversationId == null ? 0 : conversationId, Timestamp.from(since), limit);
    }

    /** Earlier versions of the message kept in {@code chat_message_edits}. */
    public int countEdits(long messageId) {
        return count("SELECT COUNT(*) FROM chat_message_edits WHERE message_id = ?", messageId);
    }

    /** Keeps the message as it is now as an earlier version. */
    public void insertEdit(MessageRow message, Instant now) {
        jdbc.update("""
                INSERT INTO chat_message_edits (message_id, content, ciphertext, iv, franking_tag, replaced_at)
                VALUES (?, ?, ?, ?, ?, ?)""", message.id(), message.content(), message.ciphertext(), message.iv(),
                message.frankingTag(), Timestamp.from(now));
    }

    /** The new text (plain) or envelope (encrypted) of an edited message. */
    public void updateBody(long messageId, String content, Envelope envelope, Instant now) {
        jdbc.update("""
                UPDATE chat_messages SET content = ?, ciphertext = ?, iv = ?, sender_key_id = ?, recipient_key_id = ?,
                    franking_tag = ?, edited_at = ?, changed_at = ? WHERE id = ?""",
                content, envelope == null ? null : envelope.ciphertext(), envelope == null ? null : envelope.iv(),
                envelope == null ? null : envelope.senderKeyId(), envelope == null ? null : envelope.recipientKeyId(),
                envelope == null ? null : envelope.frankingTag(), Timestamp.from(now), changedAt(now), messageId);
    }

    public void recall(long messageId, Instant now) {
        jdbc.update("UPDATE chat_messages SET recalled_at = ?, changed_at = ? WHERE id = ? AND recalled_at IS NULL",
                Timestamp.from(now), changedAt(now), messageId);
    }

    /** The open page asks for messages changed since its last look ({@code changed_at}, milliseconds). */
    public void touch(long messageId, Instant now) {
        jdbc.update("UPDATE chat_messages SET changed_at = ? WHERE id = ?", changedAt(now), messageId);
    }

    /**
     * Recalled more than 30 days ago (before {@code before}): text, envelope and earlier versions are removed; the
     * row stays as "Tin nhắn đã được thu hồi". Returns the messages emptied.
     */
    public int emptyRecalledBefore(Instant before) {
        Timestamp at = Timestamp.from(before);
        jdbc.update("""
                DELETE e FROM chat_message_edits e JOIN chat_messages m ON m.id = e.message_id
                WHERE m.recalled_at < ?""", at);
        return jdbc.update("""
                UPDATE chat_messages SET content = NULL, ciphertext = NULL, iv = NULL, franking_tag = NULL
                WHERE recalled_at < ?
                  AND (content IS NOT NULL OR ciphertext IS NOT NULL OR iv IS NOT NULL OR franking_tag IS NOT NULL)""",
                at);
    }

    // ---------------------------------------------------------------- reactions

    /** The member's reaction to the message: a new one replaces the one before. */
    public void setReaction(long messageId, long userId, String emoji, Instant now) {
        jdbc.update("""
                INSERT INTO chat_reactions (message_id, user_id, emoji, created_at) VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE emoji = ?, created_at = ?""",
                messageId, userId, emoji, Timestamp.from(now), emoji, Timestamp.from(now));
    }

    public int removeReaction(long messageId, long userId) {
        return jdbc.update("DELETE FROM chat_reactions WHERE message_id = ? AND user_id = ?", messageId, userId);
    }

    public void removeReactions(long messageId) {
        jdbc.update("DELETE FROM chat_reactions WHERE message_id = ?", messageId);
    }

    public Map<Long, List<ReactionRow>> reactionsOf(Collection<Long> messageIds) {
        Map<Long, List<ReactionRow>> result = new HashMap<>();
        if (messageIds.isEmpty()) {
            return result;
        }
        named.query("""
                SELECT message_id, user_id, emoji FROM chat_reactions WHERE message_id IN (:ids)
                ORDER BY created_at, user_id""", Map.of("ids", messageIds), (ResultSet rs) -> {
                    while (rs.next()) {
                        result.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                                .add(new ReactionRow(rs.getLong(1), rs.getLong(2), rs.getString(3)));
                    }
                    return null;
                });
        return result;
    }

    // ---------------------------------------------------------------- files

    public long insertFile(long conversationId, long uploaderId, String kind, String storageKey, long size,
                           Instant now) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO chat_files (conversation_id, uploader_id, kind, storage_key, size_bytes, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, conversationId);
            ps.setLong(2, uploaderId);
            ps.setString(3, kind);
            ps.setString(4, storageKey);
            ps.setLong(5, size);
            ps.setTimestamp(6, Timestamp.from(now));
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    public Optional<FileRow> file(long id) {
        return jdbc.query("SELECT " + FILE_COLUMNS + " FROM chat_files WHERE id = ?", FILE, id).stream().findFirst();
    }

    /** Files the uploader may still attach in this conversation (not sent, not deleted), locked, id order. */
    public List<FileRow> lockAttachableFiles(long conversationId, long uploaderId, Collection<Long> ids) {
        return named.query("""
                SELECT %s FROM chat_files
                WHERE id IN (:ids) AND conversation_id = :conversation AND uploader_id = :uploader
                  AND message_id IS NULL AND deleted_at IS NULL
                ORDER BY id FOR UPDATE""".formatted(FILE_COLUMNS),
                Map.of("ids", ids, "conversation", conversationId, "uploader", uploaderId), FILE);
    }

    public void attachFiles(long messageId, Collection<Long> ids) {
        named.update("UPDATE chat_files SET message_id = :message WHERE id IN (:ids)",
                Map.of("message", messageId, "ids", ids));
    }

    /** The not deleted files of the message. */
    public List<FileRow> filesOf(long messageId) {
        return jdbc.query("SELECT " + FILE_COLUMNS + " FROM chat_files WHERE message_id = ? AND deleted_at IS NULL"
                + " ORDER BY id", FILE, messageId);
    }

    /** Not deleted file ids of each message, id order. */
    public Map<Long, List<Long>> fileIdsOf(Collection<Long> messageIds) {
        Map<Long, List<Long>> result = new HashMap<>();
        if (messageIds.isEmpty()) {
            return result;
        }
        named.query("""
                SELECT message_id, id FROM chat_files WHERE message_id IN (:ids) AND deleted_at IS NULL ORDER BY id""",
                Map.of("ids", messageIds), (ResultSet rs) -> {
                    while (rs.next()) {
                        result.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getLong(2));
                    }
                    return null;
                });
        return result;
    }

    public void markFilesDeleted(Collection<Long> ids, Instant now) {
        if (ids.isEmpty()) {
            return;
        }
        named.update("UPDATE chat_files SET deleted_at = :now WHERE id IN (:ids) AND deleted_at IS NULL",
                Map.of("now", Timestamp.from(now), "ids", ids));
    }

    /** Files the user uploaded in [from, to), sent or not. */
    public int countFilesBetween(long uploaderId, Instant from, Instant to) {
        return count("SELECT COUNT(*) FROM chat_files WHERE uploader_id = ? AND created_at >= ? AND created_at < ?",
                uploaderId, Timestamp.from(from), Timestamp.from(to));
    }

    /** Files never attached to a message and uploaded before {@code before}, locked, oldest first. */
    public List<FileRow> lockUnattachedBefore(Instant before, int limit) {
        return jdbc.query("""
                SELECT %s FROM chat_files WHERE message_id IS NULL AND created_at < ?
                ORDER BY id LIMIT ? FOR UPDATE""".formatted(FILE_COLUMNS), FILE, Timestamp.from(before), limit);
    }

    public void deleteFiles(Collection<Long> ids) {
        if (!ids.isEmpty()) {
            named.update("DELETE FROM chat_files WHERE id IN (:ids)", Map.of("ids", ids));
        }
    }

    // ---------------------------------------------------------------- keys

    public Optional<KeyRow> currentKey(long userId) {
        return jdbc.query("SELECT " + KEY_COLUMNS + " FROM chat_keys WHERE user_id = ? AND revoked_at IS NULL"
                + " ORDER BY id DESC LIMIT 1", KEY, userId).stream().findFirst();
    }

    public Optional<KeyRow> key(long id) {
        return jdbc.query("SELECT " + KEY_COLUMNS + " FROM chat_keys WHERE id = ?", KEY, id).stream().findFirst();
    }

    /** Keys the user made in [from, to). */
    public int countKeysBetween(long userId, Instant from, Instant to) {
        return count("SELECT COUNT(*) FROM chat_keys WHERE user_id = ? AND created_at >= ? AND created_at < ?",
                userId, Timestamp.from(from), Timestamp.from(to));
    }

    public void revokeKeys(long userId, Instant now) {
        jdbc.update("UPDATE chat_keys SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL",
                Timestamp.from(now), userId);
    }

    public long insertKey(long userId, String publicKey, String wrappedPrivateKey, String wrapSalt, String wrapIv,
                          int wrapIterations, Instant now) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO chat_keys (user_id, public_key, wrapped_private_key, wrap_salt, wrap_iv, wrap_iterations,
                                           created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, publicKey);
            ps.setString(3, wrappedPrivateKey);
            ps.setString(4, wrapSalt);
            ps.setString(5, wrapIv);
            ps.setInt(6, wrapIterations);
            ps.setTimestamp(7, Timestamp.from(now));
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    // ---------------------------------------------------------------- reports

    /** The id of this reporter's report of the message. */
    public Optional<Long> reportIdOf(long messageId, long reporterId) {
        return jdbc.query("""
                SELECT r.id FROM reports r JOIN report_cases c ON c.id = r.case_id
                WHERE c.target_type = 'MESSAGE' AND c.target_id = ? AND r.reporter_id = ?
                ORDER BY r.id DESC LIMIT 1""", (rs, i) -> rs.getLong(1), messageId, reporterId).stream().findFirst();
    }

    public void insertEvidence(long reportId, long messageId, String plaintext, Instant now) {
        jdbc.update("""
                INSERT INTO chat_report_evidence (report_id, message_id, plaintext, created_at) VALUES (?, ?, ?, ?)""",
                reportId, messageId, plaintext, Timestamp.from(now));
    }

    // ---------------------------------------------------------------- the list

    /**
     * The user's conversations in {@code state} that still show a message to them, newest message first. A request of
     * a member whose account is locked shows no message, so it is not listed.
     */
    public List<ListRow> list(long userId, String state, int offset, int limit) {
        return jdbc.query("""
                SELECT x.* FROM (
                    SELECT c.id, me.state, p.id AS peer_id, p.display_name, p.avatar_url, p.role, p.status,
                           (SELECT MAX(m.id) FROM chat_messages m
                            WHERE m.conversation_id = c.id AND m.id > me.cleared_id AND %1$s) AS last_id,
                           (SELECT COUNT(*) FROM chat_messages m
                            WHERE m.conversation_id = c.id AND m.sender_id <> me.user_id
                              AND m.id > GREATEST(me.last_read_id, me.cleared_id) AND %1$s) AS unread,
                           EXISTS (SELECT 1 FROM chat_blocks b WHERE b.blocker_id = me.user_id AND b.blocked_id = p.id)
                               AS blocked
                    FROM chat_members me
                    JOIN chat_conversations c ON c.id = me.conversation_id
                    JOIN chat_members pm ON pm.conversation_id = me.conversation_id AND pm.user_id <> me.user_id
                    JOIN users p ON p.id = pm.user_id
                    WHERE me.user_id = ? AND me.state = ?
                ) x
                WHERE x.last_id IS NOT NULL
                ORDER BY x.last_id DESC
                LIMIT ? OFFSET ?""".formatted(SHOWN),
                (rs, i) -> new ListRow(rs.getLong("id"), rs.getString("state"), rs.getLong("peer_id"),
                        rs.getString("display_name"), rs.getString("avatar_url"), rs.getString("role"),
                        rs.getString("status"), rs.getLong("last_id"), rs.getInt("unread"), rs.getBoolean("blocked")),
                userId, state, limit, offset);
    }

    /** [conversations with unread messages, requests waiting for an answer] of the user, inbox and requests. */
    public int[] counts(long userId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(x.unread > 0), 0), COALESCE(SUM(x.state = 'REQUEST'), 0) FROM (
                    SELECT me.state,
                           (SELECT MAX(m.id) FROM chat_messages m
                            WHERE m.conversation_id = me.conversation_id AND m.id > me.cleared_id AND %1$s) AS last_id,
                           (SELECT COUNT(*) FROM chat_messages m
                            WHERE m.conversation_id = me.conversation_id AND m.sender_id <> me.user_id
                              AND m.id > GREATEST(me.last_read_id, me.cleared_id) AND %1$s) AS unread
                    FROM chat_members me
                    WHERE me.user_id = ? AND me.state IN ('ACCEPTED', 'REQUEST')
                ) x
                WHERE x.last_id IS NOT NULL""".formatted(SHOWN),
                (rs, i) -> new int[] {rs.getInt(1), rs.getInt(2)}, userId);
    }

    /**
     * The newest shown, unread, not recalled message from the other member in the user's inbox and requests (the
     * header's "Tin nhắn mới từ …").
     */
    public Optional<LatestRow> latestUnread(long userId) {
        return jdbc.query("""
                SELECT m.conversation_id, m.id, su.display_name, me.state, m.created_at
                FROM chat_members me
                JOIN chat_messages m ON m.conversation_id = me.conversation_id
                JOIN users su ON su.id = m.sender_id
                WHERE me.user_id = ? AND me.state IN ('ACCEPTED', 'REQUEST') AND m.sender_id <> me.user_id
                  AND m.id > GREATEST(me.last_read_id, me.cleared_id) AND m.recalled_at IS NULL AND su.status = 'ACTIVE'
                ORDER BY m.id DESC LIMIT 1""",
                (rs, i) -> new LatestRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        instant(rs.getTimestamp(5))), userId).stream().findFirst();
    }

    // ---------------------------------------------------------------- blocks

    public boolean blocked(long blockerId, long blockedId) {
        return count("SELECT COUNT(*) FROM chat_blocks WHERE blocker_id = ? AND blocked_id = ?",
                blockerId, blockedId) > 0;
    }

    public void block(long blockerId, long blockedId, Instant now) {
        jdbc.update("INSERT IGNORE INTO chat_blocks (blocker_id, blocked_id, created_at) VALUES (?, ?, ?)",
                blockerId, blockedId, Timestamp.from(now));
    }

    public void unblock(long blockerId, long blockedId) {
        jdbc.update("DELETE FROM chat_blocks WHERE blocker_id = ? AND blocked_id = ?", blockerId, blockedId);
    }

    /** The members the user blocked, newest first. */
    public List<BlockRow> blocks(long blockerId) {
        return jdbc.query("""
                SELECT u.id, u.display_name, u.avatar_url, u.role, u.status, b.created_at
                FROM chat_blocks b JOIN users u ON u.id = b.blocked_id
                WHERE b.blocker_id = ? ORDER BY b.created_at DESC, u.id DESC""",
                (rs, i) -> new BlockRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), instant(rs.getTimestamp(6))), blockerId);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    /** {@code changed_at} has milliseconds; the instant is cut to them so MySQL does not round it up. */
    private static Timestamp changedAt(Instant now) {
        return Timestamp.from(now.truncatedTo(ChronoUnit.MILLIS));
    }

    private static Long longOrNull(ResultSet rs, int column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
