package com.sep490.vsa.feature.chat;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.live.LiveEvents;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.common.text.VietnameseText;
import com.sep490.vsa.feature.chat.ChatDtos.BlockedMember;
import com.sep490.vsa.feature.chat.ChatDtos.Conversation;
import com.sep490.vsa.feature.chat.ChatDtos.ConversationItem;
import com.sep490.vsa.feature.chat.ChatDtos.ConversationList;
import com.sep490.vsa.feature.chat.ChatDtos.Counts;
import com.sep490.vsa.feature.chat.ChatDtos.EditRequest;
import com.sep490.vsa.feature.chat.ChatDtos.Encryption;
import com.sep490.vsa.feature.chat.ChatDtos.Envelope;
import com.sep490.vsa.feature.chat.ChatDtos.FileUploaded;
import com.sep490.vsa.feature.chat.ChatDtos.Latest;
import com.sep490.vsa.feature.chat.ChatDtos.Limits;
import com.sep490.vsa.feature.chat.ChatDtos.Message;
import com.sep490.vsa.feature.chat.ChatDtos.Notice;
import com.sep490.vsa.feature.chat.ChatDtos.OlderMessages;
import com.sep490.vsa.feature.chat.ChatDtos.Peer;
import com.sep490.vsa.feature.chat.ChatDtos.Reaction;
import com.sep490.vsa.feature.chat.ChatDtos.ReadRequest;
import com.sep490.vsa.feature.chat.ChatDtos.ReportRequest;
import com.sep490.vsa.feature.chat.ChatDtos.SendRequest;
import com.sep490.vsa.feature.chat.ChatDtos.Sent;
import com.sep490.vsa.feature.chat.ChatDtos.Status;
import com.sep490.vsa.feature.chat.ChatDtos.Tag;
import com.sep490.vsa.feature.chat.ChatDtos.Updates;
import com.sep490.vsa.feature.chat.ChatRepository.Account;
import com.sep490.vsa.feature.chat.ChatRepository.ConversationRow;
import com.sep490.vsa.feature.chat.ChatRepository.FileRow;
import com.sep490.vsa.feature.chat.ChatRepository.KeyRow;
import com.sep490.vsa.feature.chat.ChatRepository.ListRow;
import com.sep490.vsa.feature.chat.ChatRepository.MemberRow;
import com.sep490.vsa.feature.chat.ChatRepository.MessageRow;
import com.sep490.vsa.feature.chat.ChatRepository.ReactionRow;
import com.sep490.vsa.feature.media.MediaStorage;
import com.sep490.vsa.feature.moderation.ContentFilter;
import com.sep490.vsa.feature.report.ContentReportService;
import com.sep490.vsa.feature.report.ContentReportService.Target;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * "Tin nhắn": one-to-one chat between Members, so they can ask each other more than a comment allows. Only signed-in
 * ACTIVE Members (role USER) chat, with other ACTIVE Members; staff roles get MSG18, guests 401.
 * <p>
 * Strangers write through a request, because a private message from a stranger is the usual start of a scam ("gửi ảnh
 * thẻ cư trú để đăng ký việc nhẹ lương cao"):
 * <ul>
 *   <li>The first message to a member opens the conversation as a request for them ("Lời mời"). Until they accept or
 *       answer, the writer may send {@link #PENDING_MAX} messages in all, and does not see whether they were read.</li>
 *   <li>The member asked may accept, answer (which accepts), decline (the request leaves their lists and the writer
 *       cannot send more until the member writes back) or block.</li>
 *   <li>"Chặn": while either member blocks the other, neither can send. The blocked member only reads "Bạn không thể
 *       nhắn tin cho thành viên này.", the same as for a locked account.</li>
 * </ul>
 * End-to-end encryption ("Mã hóa sau khi chấp nhận"): once both members have ACCEPTED the conversation and both have
 * an identity key ({@link ChatKeyService}), the conversation is E2EE and the server takes only encrypted messages
 * (AES-GCM envelopes it cannot read; formats in {@link ChatCrypto}). Requests stay plain text, because that is where
 * scams start. An accepted conversation where a member has no key yet stays PLAIN, with a notice inviting them to
 * turn encryption on.
 * <ul>
 *   <li>Plain messages go through the same checks as a comment: banned keywords (MSG36) and the link and contact rule
 *       (BR-22: links, mobile numbers and Zalo/LINE/Telegram ids only from accounts with an identity tag, no short
 *       links), then the anti-spam limits ("cần chốt", see the constants): messages a minute, new conversations a day
 *       (fewer for new accounts), the same long text to several members.</li>
 *   <li>Encrypted messages are unreadable here, so keywords, links and repeated text are not checked (the agreed
 *       trade-off); the relationship checks and the per-minute limit still apply, and the key ids must be the two
 *       members' current keys (409 otherwise, the page reloads them and sends again). Only they carry photos (up to
 *       {@link #FILES_MAX}), one video or one voice message, uploaded encrypted beforehand ({@link #upload}).</li>
 *   <li>A reported encrypted message is checked against its franking tag before the revealed text is kept as
 *       evidence, so nobody can be framed with words they did not write.</li>
 * </ul>
 * Edit: own message, within {@link #EDIT_MINUTES} minutes, at most {@link #EDIT_MAX} times (earlier versions kept),
 * not while the conversation is cut off (deleted, blocked, unavailable). Recall ("Thu hồi"): own message, any time,
 * even after a block; its files are deleted at once, its text 30 days later ({@link ChatCleanup}). Reactions (one of
 * {@link #REACTIONS} per member and message) and "đang soạn tin" ({@link ChatTyping}) need both members ACCEPTED and
 * nobody blocked. A quoted reply names a message of the conversation the writer still sees and that is not recalled.
 * <p>
 * Messages of LOCKED or DELETED accounts are hidden from the other member like their posts ({@link ChatRepository}),
 * nothing is deleted. A message can be reported (a report case of target MESSAGE, for the Moderator queue).
 * <p>
 * Sending and uploading lock the writer's day row, then the conversation row; edit, recall, reactions and reports lock
 * the conversation row. They read with READ COMMITTED, so what an action reads after waiting includes what the action
 * before it saved: the request limit counts a message sent a moment earlier, and a block saved meanwhile stops the next
 * message. Every saved message publishes {@link ChatMessageSent} for the notifications.
 * <p>
 * Every change is also announced at once to the open pages of whom it shows for ({@link LiveEvents}, ids only, after
 * the commit): a message, an edit, recall or reaction, a read ("Đã xem" only once accepted), typing, a status change.
 * A decline or a block is announced to its author's own pages only: the other member is never told.
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    static final String NO_PERMISSION = "Bạn không có quyền thực hiện thao tác này.";          // MSG18
    static final String CONVERSATION_NOT_FOUND = "Không tìm thấy cuộc trò chuyện này.";
    static final String MEMBER_NOT_FOUND = "Không tìm thấy thành viên này.";
    static final String MESSAGE_NOT_FOUND = "Không tìm thấy tin nhắn này.";
    static final String FILE_NOT_FOUND = "Không tìm thấy tệp này.";
    static final String UNAVAILABLE = "Bạn không thể nhắn tin cho thành viên này.";
    static final String DELETED = "Tài khoản này đã bị xóa, không nhắn tin được nữa.";
    static final String TOO_FAST = "Bạn gửi tin nhắn hơi nhanh. Vui lòng đợi một lát rồi thử lại.";
    static final String REPEATED = "Bạn đã gửi nội dung này cho nhiều người. Hãy viết tin nhắn riêng cho từng người.";
    static final String FILES_NEED_E2EE = "Chỉ gửi được ảnh, video, ghi âm trong cuộc trò chuyện đã mã hóa đầu cuối.";
    static final String NOT_ENCRYPTED = "Cuộc trò chuyện này chưa được mã hóa đầu cuối. Hãy tải lại trang.";
    static final String ENCRYPTED_ONLY = "Cuộc trò chuyện này đã được mã hóa đầu cuối. Hãy tải lại trang.";
    static final String KEY_CHANGED = "Khóa mã hóa vừa thay đổi. Hãy gửi lại.";
    static final String BAD_ENVELOPE = "Tin nhắn mã hóa không hợp lệ. Hãy tải lại trang.";
    static final String BAD_FILES = "Tệp đính kèm không hợp lệ hoặc đã được gửi.";
    static final String REPLY_GONE = "Tin nhắn bạn trả lời không còn nữa.";
    static final String NOT_MINE = "Bạn chỉ sửa hoặc thu hồi được tin nhắn của chính mình.";
    static final String EDIT_TOO_LATE = "Chỉ sửa được tin nhắn trong 15 phút sau khi gửi.";
    static final String EDIT_LIMIT = "Mỗi tin nhắn chỉ sửa được tối đa 5 lần.";
    static final String RECALLED = "Tin nhắn này đã được thu hồi.";
    static final String REACT_NOT_ALLOWED = "Chỉ thả cảm xúc được khi cả hai đã nhận lời nhắn tin với nhau.";
    static final String BAD_REACTION = "Cảm xúc này không có trong danh sách.";
    static final String REPORT_UNVERIFIED = "Không xác minh được nội dung tin nhắn bị báo cáo.";

    static final String REQUEST = "REQUEST";
    static final String ACCEPTED = "ACCEPTED";
    static final String DECLINED = "DECLINED";
    static final String PLAIN = "PLAIN";
    static final String E2EE = "E2EE";

    static final int MESSAGE_MAX = 2000;
    /** "cần chốt": messages the writer may send before the member asked accepts or answers. */
    static final int PENDING_MAX = 3;
    /** "cần chốt": messages one member may send in any minute, all conversations together. */
    static final int MESSAGES_PER_MINUTE = 30;
    /** "cần chốt": new conversations (first messages to someone) a member may start in a Japan day. */
    static final int NEW_CHATS_PER_DAY = 20;
    /** "cần chốt": the same for an account younger than new_account_days. */
    static final int NEW_CHATS_PER_DAY_NEW_ACCOUNT = 5;
    /**
     * "cần chốt": the same text (compared after {@link VietnameseText#squash}) already sent in this many other
     * conversations in the last 24 hours is refused; only texts of at least 30 letters and digits count, so "Cảm ơn anh"
     * stays free. The newest 500 messages of the 24 hours are compared.
     */
    static final int REPEATED_TEXT_CHATS = 3;
    static final Duration REPEATED_TEXT_WINDOW = Duration.ofHours(24);
    static final int REPEATED_TEXT_MIN_CHARS = 30;
    static final int REPEATED_TEXT_SCAN = 500;

    /** A message can be edited this long after it was sent, at most {@link #EDIT_MAX} times. */
    static final int EDIT_MINUTES = 15;
    static final int EDIT_MAX = 5;
    /** Photos per message (or one video, or one voice message). */
    static final int FILES_MAX = 5;
    /** "cần chốt": sizes of the uploaded (already encrypted) bytes. */
    static final long IMAGE_MAX_BYTES = 10L * 1024 * 1024;
    static final long VIDEO_MAX_BYTES = 50L * 1024 * 1024;
    static final long AUDIO_MAX_BYTES = 5L * 1024 * 1024;
    /** Checked by the page before encrypting (the server cannot read the files). */
    static final int VIDEO_MAX_SECONDS = 30;
    static final int AUDIO_MAX_SECONDS = 120;
    /** "cần chốt": files a member may upload in a Japan day, and the same for an account younger than new_account_days. */
    static final int FILES_PER_DAY = 100;
    static final int FILES_PER_DAY_NEW_ACCOUNT = 20;
    /** An envelope: ciphertext (fk ‖ body + 16-byte GCM tag) of at most 32 KB decoded, iv of 12 bytes. */
    static final int CIPHERTEXT_MAX_BYTES = 32768;
    static final int CIPHERTEXT_MIN_BYTES = ChatCrypto.FRANKING_KEY_BYTES + 16;
    static final int IV_BYTES = 12;
    /** The reactions offered, as exact code points ("❤️" is U+2764 U+FE0F). */
    static final List<String> REACTIONS = List.of(emoji(0x1F44D), emoji(0x2764, 0xFE0F), emoji(0x1F602),
            emoji(0x1F62E), emoji(0x1F622), emoji(0x1F64F));

    static final int PAGE_SIZE = 30;
    static final int OLDER_MAX = 200;
    static final int LIST_PAGE_SIZE = 30;
    static final int UPDATES_MAX = 100;
    static final int CHANGED_MAX = 200;
    /**
     * {@code changed} also returns messages changed this long before {@code changedSince}: a change saved just before
     * the previous answer but committed after it would otherwise never reach the page. Repeats are harmless (the
     * page replaces messages by id).
     */
    static final Duration CHANGED_OVERLAP = Duration.ofSeconds(2);
    /** The oldest {@code changedSince} taken as it is (an older one asks for every change, as this one does). */
    private static final Instant EARLIEST_TIME = Instant.parse("2000-01-01T00:00:00Z");
    static final int PREVIEW_MAX = 120;
    private static final int MAX_PAGE = 1000;
    private static final Limits LIMITS = new Limits(MESSAGE_MAX, PENDING_MAX, EDIT_MINUTES, FILES_MAX,
            IMAGE_MAX_BYTES, VIDEO_MAX_BYTES, AUDIO_MAX_BYTES, VIDEO_MAX_SECONDS, AUDIO_MAX_SECONDS, REACTIONS);
    /** Notices after which the viewer may still send. */
    private static final Set<String> SENDABLE = Set.of("NEW", "WAITING", REQUEST);
    /** Notices that also stop editing: the conversation is cut off. */
    private static final Set<String> CUT_OFF = Set.of("DELETED", "BLOCKED_BY_ME", "UNAVAILABLE");
    private static final Pattern FRANKING_TAG = Pattern.compile("[0-9a-f]{64}");
    /** Base64 text longer than this cannot decode to a valid envelope or report (32 KB plus room). */
    private static final int BASE64_MAX_CHARS = 44000;
    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");
    private static final DateTimeFormatter MONTH_FOLDER = DateTimeFormatter.ofPattern("yyyy/MM");

    private final ChatRepository repository;
    private final ContentFilter filter;
    private final ContentReportService reports;
    private final SystemSettings settings;
    private final MediaStorage storage;
    private final ChatTyping typing;
    private final ChatViewers viewers;
    private final ApplicationEventPublisher events;
    private final LiveEvents live;
    private final Clock clock;

    public ChatService(ChatRepository repository, ContentFilter filter, ContentReportService reports,
                       SystemSettings settings, MediaStorage storage, ChatTyping typing, ChatViewers viewers,
                       ApplicationEventPublisher events, LiveEvents live, Clock clock) {
        this.repository = repository;
        this.filter = filter;
        this.reports = reports;
        this.settings = settings;
        this.storage = storage;
        this.typing = typing;
        this.viewers = viewers;
        this.events = events;
        this.live = live;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reading

    /** {@code box} INBOX (accepted conversations) or REQUESTS ("Lời mời"), newest message first. */
    @Transactional(readOnly = true)
    public ConversationList list(AppUserPrincipal me, String box, int page) {
        Account viewer = member(me);
        String state = switch (box == null ? "INBOX" : box.toUpperCase(Locale.ROOT)) {
            case "INBOX" -> ACCEPTED;
            case "REQUESTS" -> REQUEST;
            default -> throw ApiException.badRequest("Hộp thư không hợp lệ.");
        };
        int offset = Math.min(Math.max(0, page), MAX_PAGE) * LIST_PAGE_SIZE;
        List<ListRow> rows = repository.list(viewer.id(), state, offset, LIST_PAGE_SIZE + 1);
        boolean hasMore = rows.size() > LIST_PAGE_SIZE;
        List<ListRow> shown = hasMore ? rows.subList(0, LIST_PAGE_SIZE) : rows;
        Map<Long, Message> last = messages(repository.messagesByIds(shown.stream().map(ListRow::lastId).toList()),
                viewer.id()).stream().collect(Collectors.toMap(Message::id, Function.identity()));
        Map<Long, List<Tag>> tags = repository.tagsOf(shown.stream().map(ListRow::peerId).collect(Collectors.toSet()));
        List<ConversationItem> items = shown.stream().map(r -> new ConversationItem(r.id(),
                new Peer(r.peerId(), r.peerName(), r.peerAvatar(), tags.getOrDefault(r.peerId(), List.of()),
                        "USER".equals(r.peerRole()) && "ACTIVE".equals(r.peerStatus())),
                r.state(), preview(last.get(r.lastId())), r.unread(), r.blockedByMe())).toList();
        return new ConversationList(items, hasMore, counts(viewer.id()));
    }

    /** The header badge: conversations with unread messages, requests waiting for an answer, the newest unread. */
    @Transactional(readOnly = true)
    public Counts counts(AppUserPrincipal me) {
        return counts(member(me).id());
    }

    @Transactional(readOnly = true)
    public Conversation open(AppUserPrincipal me, long conversationId) {
        Account viewer = member(me);
        return view(viewer, participant(conversationId, viewer.id()));
    }

    /**
     * The conversation with this member, opened from their name ("Nhắn tin"). Before the first message there is none
     * yet: the answer has no id and says whether a first message can be sent.
     */
    @Transactional(readOnly = true)
    public Conversation openWith(AppUserPrincipal me, long userId) {
        Account viewer = member(me);
        Account peer = peerToWrite(viewer, userId);
        Optional<ConversationRow> existing = repository.conversationOf(viewer.id(), peer.id());
        if (existing.isPresent()) {
            return view(viewer, existing.get());
        }
        if (peer.deleted()) {
            throw notFound(MEMBER_NOT_FOUND);
        }
        return new Conversation(null, peer(peer), status(viewer, peer, null, null, null), List.of(), false,
                reports.reasons(), LIMITS, clock.instant().truncatedTo(ChronoUnit.MILLIS));
    }

    /**
     * "Xem tin cũ hơn": the {@code limit} (1–{@value #OLDER_MAX}, default {@value #PAGE_SIZE}) shown messages before
     * {@code beforeId} (null: the newest), oldest first. The page asks for many at once when it searches.
     */
    @Transactional(readOnly = true)
    public OlderMessages older(AppUserPrincipal me, long conversationId, Long beforeId, Integer limit) {
        Account viewer = member(me);
        ConversationRow conversation = participant(conversationId, viewer.id());
        MemberRow mine = memberRow(conversation.id(), viewer.id());
        int size = limit == null ? PAGE_SIZE : Math.max(1, Math.min(OLDER_MAX, limit));
        List<MessageRow> rows = repository.messagesBefore(conversation.id(), mine.clearedId(), beforeId, size + 1);
        boolean hasOlder = rows.size() > size;
        return new OlderMessages(messages(hasOlder ? rows.subList(1, rows.size()) : rows, viewer.id()), hasOlder);
    }

    /**
     * What the open conversation asks every few seconds: messages after {@code afterId}, messages edited, recalled or
     * reacted to since {@code changedSince} (ISO time or epoch milliseconds, the {@code serverTime} of the answer
     * before; none without it), the status, and the server time to pass next.
     */
    @Transactional(readOnly = true)
    public Updates updates(AppUserPrincipal me, long conversationId, long afterId, String changedSince) {
        Instant serverTime = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Instant since = parseTime(changedSince);
        Account viewer = member(me);
        ConversationRow conversation = participant(conversationId, viewer.id());
        viewers.seen(conversation.id(), viewer.id());
        Account peer = account(conversation.other(viewer.id()));
        MemberRow mine = memberRow(conversation.id(), viewer.id());
        MemberRow theirs = memberRow(conversation.id(), peer.id());
        List<MessageRow> rows = repository.messagesAfter(conversation.id(), mine.clearedId(), Math.max(0, afterId),
                UPDATES_MAX);
        List<MessageRow> changed = since == null ? List.of()
                : repository.changedSince(conversation.id(), mine.clearedId(), since.minus(CHANGED_OVERLAP),
                CHANGED_MAX);
        return new Updates(messages(rows, viewer.id()), messages(changed, viewer.id()),
                status(viewer, peer, conversation, mine, theirs), serverTime);
    }

    /** One message shown to the viewer (a quote, a search result); anything else answers 404. */
    @Transactional(readOnly = true)
    public Message message(AppUserPrincipal me, long conversationId, long messageId) {
        Account viewer = member(me);
        ConversationRow conversation = participant(conversationId, viewer.id());
        MemberRow mine = memberRow(conversation.id(), viewer.id());
        return toMessage(shownMessage(conversation, mine, messageId), viewer.id());
    }

    // ---------------------------------------------------------------- sending

    /** What a member sends: text (plain), or an envelope with its files (encrypted). */
    private record Draft(String content, Envelope encrypted, List<Long> fileIds, Long replyToId) {
    }

    /** The two members' current keys; E2EE when both accepted the conversation and both have one. */
    private record Mode(KeyRow mine, KeyRow theirs, boolean e2ee) {
    }

    /**
     * The first message to a member (or the next one, when their conversation already exists). Plain text only:
     * an encrypted conversation is written to through its id.
     */
    public Sent sendTo(AppUserPrincipal me, long userId, SendRequest request) {
        Account viewer = member(me);
        Draft draft = new Draft(text(request == null ? null : request.content()), null, List.of(), null);
        return deliver(viewer, peerToWrite(viewer, userId), draft);
    }

    public Sent send(AppUserPrincipal me, long conversationId, SendRequest request) {
        Account viewer = member(me);
        Draft draft = draft(request);
        ConversationRow conversation = participant(conversationId, viewer.id());
        return deliver(viewer, account(conversation.other(viewer.id())), draft);
    }

    /** The form of a message (text length, envelope sizes) before anything is looked up. */
    private static Draft draft(SendRequest request) {
        if (request == null) {
            throw ApiException.badRequest("Hãy nhập tin nhắn.");
        }
        List<Long> fileIds = request.fileIds() == null ? List.of() : request.fileIds();
        if (request.encrypted() == null) {
            return new Draft(text(request.content()), null, fileIds, request.replyToId());
        }
        String content = request.content() == null || request.content().isBlank() ? null : request.content();
        return new Draft(content, envelope(request.encrypted()), fileIds, request.replyToId());
    }

    /**
     * Checks in this order and stops at the first that fails: may the viewer write to this member now (deleted,
     * blocked, locked, request limit); the form fits the conversation's mode (plain text in PLAIN, an envelope with the
     * current key ids in E2EE, files only there); the quoted message; for plain text the banned keywords, links and
     * contacts (BR-22) and the new-conversation limit of the day; the per-minute limit; for plain text the same text to
     * several members. Then the message is saved; writing accepts a request the viewer had received (or declined).
     */
    private Sent deliver(Account viewer, Account peer, Draft draft) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, JAPAN);
        repository.lockUserDay(viewer.id(), today);
        ConversationRow conversation = repository.lockConversationOf(viewer.id(), peer.id()).orElse(null);
        if (conversation == null && peer.deleted()) {
            throw notFound(MEMBER_NOT_FOUND);
        }
        MemberRow mine = conversation == null ? null : memberRow(conversation.id(), viewer.id());
        MemberRow theirs = conversation == null ? null : memberRow(conversation.id(), peer.id());
        Notice notice = notice(viewer, peer, conversation, mine, theirs,
                repository.blocked(viewer.id(), peer.id()));
        if (notice != null && !SENDABLE.contains(notice.code())) {
            throw refusal(notice);
        }

        Mode mode = mode(viewer.id(), peer.id(), conversation, mine, theirs);
        List<FileRow> files = List.of();
        if (mode.e2ee()) {
            if (draft.content() != null) {
                throw ApiException.badRequest(ENCRYPTED_ONLY);
            }
            checkKeys(draft.encrypted(), mode);
            files = attachable(conversation.id(), viewer.id(), draft.fileIds());
        } else {
            if (!draft.fileIds().isEmpty()) {
                throw ApiException.badRequest(FILES_NEED_E2EE);
            }
            if (draft.encrypted() != null) {
                throw ApiException.badRequest(NOT_ENCRYPTED);
            }
        }
        Long replyToId = replyTarget(conversation, mine, draft.replyToId());

        String content = draft.content();
        if (!mode.e2ee()) {
            checkText(content, viewer.id());
            if (conversation == null) {
                checkNewConversations(viewer, now, today);
            }
        }
        if (repository.countMessagesSince(viewer.id(), now.minus(Duration.ofMinutes(1))) >= MESSAGES_PER_MINUTE) {
            throw ApiException.tooManyRequests(TOO_FAST);
        }
        if (!mode.e2ee()) {
            checkRepeatedText(viewer.id(), conversation == null ? null : conversation.id(), content, now);
        }

        long conversationId;
        if (conversation != null) {
            conversationId = conversation.id();
        } else {
            try {
                conversationId = repository.insertConversation(viewer.id(), peer.id(), now);
            } catch (DuplicateKeyException e) {
                // The other member wrote first at the same moment: this message answers their request
                conversationId = repository.lockConversationOf(viewer.id(), peer.id())
                        .orElseThrow(() -> e).id();
            }
        }
        long messageId = repository.insertMessage(conversationId, viewer.id(), content, draft.encrypted(), replyToId,
                now);
        List<Long> fileIds = files.stream().map(FileRow::id).toList();
        if (!fileIds.isEmpty()) {
            repository.attachFiles(messageId, fileIds);
        }
        if (!ACCEPTED.equals(memberRow(conversationId, viewer.id()).state())) {
            repository.setState(conversationId, viewer.id(), ACCEPTED, now);
        }
        repository.markRead(conversationId, viewer.id(), messageId);
        typing.stop(conversationId, viewer.id());

        ConversationRow saved = repository.conversation(conversationId).orElseThrow();
        MemberRow peerSide = memberRow(conversationId, peer.id());
        events.publishEvent(new ChatMessageSent(conversationId, messageId, viewer.id(), viewer.displayName(),
                peer.id(), draft.encrypted() != null, REQUEST.equals(peerSide.state())));
        announce(conversationId, "message", messageId, viewer.id(), peer.id());
        Status status = status(viewer, peer, saved, memberRow(conversationId, viewer.id()), peerSide);
        return new Sent(conversationId, new Message(messageId, true, content, draft.encrypted(), replyToId, false,
                false, List.of(), fileIds, now), status);
    }

    /** Banned keywords (MSG36), then links and contacts (BR-22): plain messages and their edits. */
    private void checkText(String content, long authorId) {
        filter.bannedKeyword(content).ifPresent(keyword -> {
            throw ApiException.badRequest("Chưa gửi được: tin nhắn chứa từ khóa không được phép “" + keyword
                    + "”. Hãy sửa rồi gửi lại.");                                            // MSG36
        });
        filter.linkProblem(content, authorId).ifPresent(problem -> {
            throw ApiException.badRequest(problem.message());
        });
    }

    /** The envelope names the two members' current keys; otherwise a key changed since the page loaded them. */
    private static void checkKeys(Envelope envelope, Mode mode) {
        if (mode.mine() == null || mode.theirs() == null
                || !Objects.equals(envelope.senderKeyId(), mode.mine().id())
                || !Objects.equals(envelope.recipientKeyId(), mode.theirs().id())) {
            throw ApiException.conflict(KEY_CHANGED);
        }
    }

    /**
     * The files of a new encrypted message: the sender's own, not yet sent, not deleted files of this conversation;
     * 1–{@value #FILES_MAX} photos, or one video, or one voice message.
     */
    private List<FileRow> attachable(long conversationId, long uploaderId, List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        if (ids.size() > FILES_MAX || ids.contains(null) || new HashSet<>(ids).size() != ids.size()) {
            throw ApiException.badRequest(BAD_FILES);
        }
        List<FileRow> files = repository.lockAttachableFiles(conversationId, uploaderId, ids);
        if (files.size() != ids.size()) {
            throw ApiException.badRequest(BAD_FILES);
        }
        Set<String> kinds = files.stream().map(FileRow::kind).collect(Collectors.toSet());
        boolean photos = kinds.equals(Set.of("IMAGE"));
        boolean oneClip = files.size() == 1 && (kinds.contains("VIDEO") || kinds.contains("AUDIO"));
        if (!photos && !oneClip) {
            throw ApiException.badRequest(BAD_FILES);
        }
        return files;
    }

    /** A quoted message: of this conversation, shown to the viewer and not recalled. */
    private Long replyTarget(ConversationRow conversation, MemberRow mine, Long replyToId) {
        if (replyToId == null) {
            return null;
        }
        Optional<MessageRow> target = conversation == null ? Optional.empty() : repository.message(replyToId);
        if (target.isEmpty() || target.get().conversationId() != conversation.id() || target.get().recalled()
                || !repository.shown(target.get().id(), mine.clearedId())) {
            throw ApiException.badRequest(REPLY_GONE);
        }
        return target.get().id();
    }

    /** New conversations of the day (Japan time): fewer for an account younger than new_account_days. */
    private void checkNewConversations(Account viewer, Instant now, LocalDate today) {
        int newDays = settings.getInt(SystemSettings.NEW_ACCOUNT_DAYS, 7);
        boolean newAccount = isNewAccount(viewer, now, newDays);
        int max = newAccount ? NEW_CHATS_PER_DAY_NEW_ACCOUNT : NEW_CHATS_PER_DAY;
        Instant dayStart = today.atStartOfDay(JAPAN).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(JAPAN).toInstant();
        if (repository.countStartedBetween(viewer.id(), dayStart, dayEnd) >= max) {
            throw ApiException.tooManyRequests(newAccount
                    ? "Bạn đã bắt đầu đủ " + max + " cuộc trò chuyện mới hôm nay, giới hạn của tài khoản mới (dưới "
                    + newDays + " ngày). Vui lòng thử lại vào ngày mai."
                    : "Bạn đã bắt đầu đủ " + max + " cuộc trò chuyện mới hôm nay. Vui lòng thử lại vào ngày mai.");
        }
    }

    private static boolean isNewAccount(Account account, Instant now, int newDays) {
        return account.createdAt() != null && account.createdAt().isAfter(now.minus(Duration.ofDays(newDays)));
    }

    private void checkRepeatedText(long userId, Long conversationId, String content, Instant now) {
        String text = VietnameseText.squash(content);
        if (text.length() < REPEATED_TEXT_MIN_CHARS) {
            return;
        }
        Set<Long> conversations = new HashSet<>();
        for (MessageRow earlier : repository.recentMessagesElsewhere(userId, conversationId,
                now.minus(REPEATED_TEXT_WINDOW), REPEATED_TEXT_SCAN)) {
            if (text.equals(VietnameseText.squash(earlier.content()))) {
                conversations.add(earlier.conversationId());
            }
        }
        if (conversations.size() >= REPEATED_TEXT_CHATS) {
            throw ApiException.badRequest(REPEATED);
        }
    }

    // ---------------------------------------------------------------- edit, recall, reactions, typing

    /**
     * "Sửa": the viewer's own message, shown and not recalled, within {@value #EDIT_MINUTES} minutes of sending and
     * edited fewer than {@value #EDIT_MAX} times, while the conversation is not cut off (deleted, blocked,
     * unavailable). A plain message takes new text (length, keywords and links checked again), an encrypted one a new
     * envelope with the current key ids. The version before goes to {@code chat_message_edits}.
     */
    public Message edit(AppUserPrincipal me, long conversationId, long messageId, EditRequest request) {
        Account viewer = member(me);
        ConversationRow conversation = lockedParticipant(conversationId, viewer.id());
        MemberRow mine = memberRow(conversation.id(), viewer.id());
        MessageRow message = ownMessage(conversation, mine, messageId, viewer.id());
        Account peer = account(conversation.other(viewer.id()));
        MemberRow theirs = memberRow(conversation.id(), peer.id());
        Notice notice = notice(viewer, peer, conversation, mine, theirs, repository.blocked(viewer.id(), peer.id()));
        if (notice != null && CUT_OFF.contains(notice.code())) {
            throw refusal(notice);
        }
        Instant now = clock.instant();
        if (message.createdAt().plus(Duration.ofMinutes(EDIT_MINUTES)).isBefore(now)) {
            throw ApiException.badRequest(EDIT_TOO_LATE);
        }
        if (repository.countEdits(message.id()) >= EDIT_MAX) {
            throw ApiException.badRequest(EDIT_LIMIT);
        }
        String content = null;
        Envelope envelope = null;
        if (message.encrypted()) {
            if (request == null || request.encrypted() == null) {
                throw ApiException.badRequest(ENCRYPTED_ONLY);
            }
            envelope = envelope(request.encrypted());
            checkKeys(envelope, mode(viewer.id(), peer.id(), conversation, mine, theirs));
        } else {
            if (request != null && request.encrypted() != null) {
                throw ApiException.badRequest(NOT_ENCRYPTED);
            }
            content = text(request == null ? null : request.content());
            checkText(content, viewer.id());
        }
        repository.insertEdit(message, now);
        repository.updateBody(message.id(), content, envelope, now);
        announceChanged(conversation, message.id(), viewer.id(), theirs);
        return toMessage(repository.message(message.id()).orElseThrow(), viewer.id());
    }

    /**
     * "Thu hồi": the viewer's own message, at any time and even after a block. Both members then see "Tin nhắn đã được
     * thu hồi"; its reactions go, its files are deleted from storage once this commits, and its text and earlier
     * versions are emptied 30 days later ({@link ChatCleanup}).
     */
    public Message recall(AppUserPrincipal me, long conversationId, long messageId) {
        Account viewer = member(me);
        ConversationRow conversation = lockedParticipant(conversationId, viewer.id());
        MemberRow mine = memberRow(conversation.id(), viewer.id());
        MessageRow message = ownMessage(conversation, mine, messageId, viewer.id());
        Instant now = clock.instant();
        repository.recall(message.id(), now);
        repository.removeReactions(message.id());
        List<FileRow> files = repository.filesOf(message.id());
        repository.markFilesDeleted(files.stream().map(FileRow::id).toList(), now);
        deleteAfterCommit(files.stream().map(FileRow::storageKey).toList());
        announceChanged(conversation, message.id(), viewer.id(),
                memberRow(conversation.id(), conversation.other(viewer.id())));
        return toMessage(repository.message(message.id()).orElseThrow(), viewer.id());
    }

    /**
     * "Thả cảm xúc" ({@code emoji} one of {@link #REACTIONS}; null takes the viewer's reaction back): both members
     * accepted, nobody blocked, the other member an active Member, the message shown and not recalled. A new reaction
     * replaces the viewer's one before.
     */
    public Message react(AppUserPrincipal me, long conversationId, long messageId, String emoji) {
        Account viewer = member(me);
        ConversationRow conversation = lockedParticipant(conversationId, viewer.id());
        MemberRow mine = memberRow(conversation.id(), viewer.id());
        MessageRow message = shownMessage(conversation, mine, messageId);
        if (message.recalled()) {
            throw ApiException.badRequest(RECALLED);
        }
        Account peer = account(conversation.other(viewer.id()));
        MemberRow theirs = memberRow(conversation.id(), peer.id());
        Notice notice = notice(viewer, peer, conversation, mine, theirs, repository.blocked(viewer.id(), peer.id()));
        if (notice != null && CUT_OFF.contains(notice.code())) {
            throw refusal(notice);
        }
        if (!together(notice, mine, theirs)) {
            throw ApiException.forbidden(REACT_NOT_ALLOWED);
        }
        if (emoji != null && !REACTIONS.contains(emoji)) {
            throw ApiException.badRequest(BAD_REACTION);
        }
        Instant now = clock.instant();
        if (emoji == null) {
            repository.removeReaction(message.id(), viewer.id());
        } else {
            repository.setReaction(message.id(), viewer.id(), emoji, now);
        }
        repository.touch(message.id(), now);
        announceChanged(conversation, message.id(), viewer.id(), theirs);
        return toMessage(repository.message(message.id()).orElseThrow(), viewer.id());
    }

    /**
     * "đang soạn tin": counted only while both members accepted and nobody blocked; the answer is the same either way,
     * so it tells nothing.
     */
    @Transactional(readOnly = true)
    public void typing(AppUserPrincipal me, long conversationId) {
        Account viewer = member(me);
        ConversationRow conversation = participant(conversationId, viewer.id());
        Account peer = account(conversation.other(viewer.id()));
        MemberRow mine = memberRow(conversation.id(), viewer.id());
        MemberRow theirs = memberRow(conversation.id(), peer.id());
        Notice notice = notice(viewer, peer, conversation, mine, theirs, repository.blocked(viewer.id(), peer.id()));
        if (together(notice, mine, theirs)) {
            typing.ping(conversation.id(), viewer.id());
            announce(conversation.id(), "typing", null, peer.id());
        }
    }

    // ---------------------------------------------------------------- files

    /**
     * A photo, video or voice message for the next encrypted message, already encrypted by the page: the conversation
     * is E2EE and the viewer may write; the bytes are within the size of their kind and the day's number of files
     * ("cần chốt"). Stored as {@code chat/yyyy/MM/<uuid>.bin}; removed again when this rolls back, and by
     * {@link ChatCleanup} when no message carries it within a day.
     */
    public FileUploaded upload(AppUserPrincipal me, long conversationId, String kind, MultipartFile file) {
        Account viewer = member(me);
        String fileKind = kind == null ? "" : kind.strip().toUpperCase(Locale.ROOT);
        long maxBytes = switch (fileKind) {
            case "IMAGE" -> IMAGE_MAX_BYTES;
            case "VIDEO" -> VIDEO_MAX_BYTES;
            case "AUDIO" -> AUDIO_MAX_BYTES;
            default -> throw ApiException.badRequest("Loại tệp không hợp lệ.");
        };
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Hãy chọn tệp để gửi.");
        }
        if (file.getSize() > maxBytes) {
            throw ApiException.badRequest(switch (fileKind) {
                case "IMAGE" -> "Mỗi ảnh tối đa 10 MB.";
                case "VIDEO" -> "Video tối đa 50 MB.";
                default -> "Ghi âm tối đa 5 MB.";
            });
        }
        ConversationRow found = participant(conversationId, viewer.id());
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("Không đọc được tệp tải lên. Vui lòng thử lại.");
        }

        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, JAPAN);
        repository.lockUserDay(viewer.id(), today);
        ConversationRow conversation = lockedParticipant(found.id(), viewer.id());
        Account peer = account(conversation.other(viewer.id()));
        MemberRow mine = memberRow(conversation.id(), viewer.id());
        MemberRow theirs = memberRow(conversation.id(), peer.id());
        Notice notice = notice(viewer, peer, conversation, mine, theirs, repository.blocked(viewer.id(), peer.id()));
        if (notice != null && !SENDABLE.contains(notice.code())) {
            throw refusal(notice);
        }
        if (!mode(viewer.id(), peer.id(), conversation, mine, theirs).e2ee()) {
            throw ApiException.badRequest(FILES_NEED_E2EE);
        }
        int newDays = settings.getInt(SystemSettings.NEW_ACCOUNT_DAYS, 7);
        boolean newAccount = isNewAccount(viewer, now, newDays);
        int max = newAccount ? FILES_PER_DAY_NEW_ACCOUNT : FILES_PER_DAY;
        if (repository.countFilesBetween(viewer.id(), today.atStartOfDay(JAPAN).toInstant(),
                today.plusDays(1).atStartOfDay(JAPAN).toInstant()) >= max) {
            throw ApiException.tooManyRequests(newAccount
                    ? "Bạn đã gửi đủ " + max + " tệp hôm nay, giới hạn của tài khoản mới (dưới " + newDays
                    + " ngày). Vui lòng thử lại vào ngày mai."
                    : "Bạn đã gửi đủ " + max + " tệp hôm nay. Vui lòng thử lại vào ngày mai.");
        }

        String key = "chat/" + MONTH_FOLDER.format(now.atZone(ZoneOffset.UTC)) + "/" + UUID.randomUUID() + ".bin";
        storage.put(key, bytes);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteQuietly(key);
                }
            }
        });
        return new FileUploaded(repository.insertFile(conversation.id(), viewer.id(), fileKind, key, bytes.length,
                now));
    }

    /**
     * The encrypted bytes of a file, for a member of its conversation: attached to a message shown to them and not
     * recalled, or uploaded by them. Anything else (another conversation, deleted, a recalled or hidden message)
     * answers 404.
     */
    @Transactional(readOnly = true)
    public Resource file(AppUserPrincipal me, long conversationId, long fileId) {
        Account viewer = member(me);
        ConversationRow conversation = participant(conversationId, viewer.id());
        MemberRow mine = memberRow(conversation.id(), viewer.id());
        FileRow file = repository.file(fileId)
                .filter(f -> f.conversationId() == conversation.id() && !f.deleted())
                .orElseThrow(() -> notFound(FILE_NOT_FOUND));
        boolean allowed = file.uploaderId() == viewer.id();
        if (!allowed && file.messageId() != null) {
            allowed = repository.message(file.messageId())
                    .filter(m -> !m.recalled() && repository.shown(m.id(), mine.clearedId()))
                    .isPresent();
        }
        if (!allowed) {
            throw notFound(FILE_NOT_FOUND);
        }
        Resource resource = storage.resource(file.storageKey());
        if (resource == null) {
            throw notFound(FILE_NOT_FOUND);
        }
        return resource;
    }

    // ---------------------------------------------------------------- the viewer's side of a conversation

    /** The viewer has read the conversation up to this message; returns the badge counts left. */
    public Counts markRead(AppUserPrincipal me, long conversationId, ReadRequest request) {
        Account viewer = member(me);
        ConversationRow conversation = participant(conversationId, viewer.id());
        Long upTo = request == null ? null : request.upToId();
        repository.markRead(conversation.id(), viewer.id(), upTo == null ? Long.MAX_VALUE : Math.max(0, upTo));
        // "Đã xem" shows for the other member only once the viewer accepted (as in status()): before that they are not
        // even told when the viewer read
        boolean seen = ACCEPTED.equals(memberRow(conversation.id(), viewer.id()).state());
        if (seen) {
            announce(conversation.id(), "read", null, viewer.id(), conversation.other(viewer.id()));
        } else {
            announce(conversation.id(), "read", null, viewer.id());
        }
        return counts(viewer.id());
    }

    /** "Chấp nhận" a request: the conversation moves to the inbox and both members write freely. */
    public Status accept(AppUserPrincipal me, long conversationId) {
        Account viewer = member(me);
        ConversationRow conversation = lockedParticipant(conversationId, viewer.id());
        if (!ACCEPTED.equals(memberRow(conversation.id(), viewer.id()).state())) {
            repository.setState(conversation.id(), viewer.id(), ACCEPTED, clock.instant());
        }
        Account peer = account(conversation.other(viewer.id()));
        announce(conversation.id(), "status", null, viewer.id(), peer.id());
        return status(viewer, peer, conversation, memberRow(conversation.id(), viewer.id()),
                memberRow(conversation.id(), peer.id()));
    }

    /**
     * "Từ chối" a request: it leaves the viewer's lists, and its writer cannot send more until the viewer writes
     * back. The writer is not told.
     */
    public Counts decline(AppUserPrincipal me, long conversationId) {
        Account viewer = member(me);
        ConversationRow conversation = lockedParticipant(conversationId, viewer.id());
        if (!REQUEST.equals(memberRow(conversation.id(), viewer.id()).state())) {
            throw ApiException.badRequest("Chỉ từ chối được lời mời nhắn tin chưa trả lời.");
        }
        decline(conversation.id(), viewer.id(), clock.instant());
        announce(conversation.id(), "status", null, viewer.id());           // never the writer: they are not told
        return counts(viewer.id());
    }

    /**
     * "Xóa cuộc trò chuyện" for the viewer only: the messages so far are no longer shown to them (the other member
     * keeps theirs). A new message brings the conversation back. Deleting a request declines it.
     */
    public Counts clear(AppUserPrincipal me, long conversationId) {
        Account viewer = member(me);
        ConversationRow conversation = lockedParticipant(conversationId, viewer.id());
        Instant now = clock.instant();
        if (REQUEST.equals(memberRow(conversation.id(), viewer.id()).state())) {
            decline(conversation.id(), viewer.id(), now);
        } else {
            repository.clear(conversation.id(), viewer.id(), now);
        }
        announce(conversation.id(), "status", null, viewer.id());
        return counts(viewer.id());
    }

    private void decline(long conversationId, long userId, Instant now) {
        repository.setState(conversationId, userId, DECLINED, now);
        repository.clear(conversationId, userId, now);
    }

    // ---------------------------------------------------------------- blocking and reporting

    /**
     * "Chặn": while the block lasts, neither member can send the other a message. A request from the blocked member
     * is declined at the same time. The conversation row is locked first, so a message sent at that moment is either
     * saved before the block or refused.
     */
    public void block(AppUserPrincipal me, long userId) {
        Account viewer = member(me);
        if (userId == viewer.id()) {
            throw ApiException.badRequest("Bạn không thể chặn chính mình.");
        }
        Account other = repository.account(userId).orElseThrow(() -> notFound(MEMBER_NOT_FOUND));
        block(viewer.id(), other.id(), clock.instant());
    }

    public void unblock(AppUserPrincipal me, long userId) {
        Account viewer = member(me);
        repository.unblock(viewer.id(), userId);
        announce(repository.conversationOf(viewer.id(), userId).map(ConversationRow::id).orElse(null), "status", null,
                viewer.id());
    }

    /** "Người đã chặn", newest first. */
    @Transactional(readOnly = true)
    public List<BlockedMember> blocks(AppUserPrincipal me) {
        Account viewer = member(me);
        var rows = repository.blocks(viewer.id());
        Map<Long, List<Tag>> tags = repository.tagsOf(rows.stream().map(r -> r.userId()).collect(Collectors.toSet()));
        return rows.stream().map(r -> new BlockedMember(new Peer(r.userId(), r.displayName(), r.avatarUrl(),
                tags.getOrDefault(r.userId(), List.of()), "USER".equals(r.role()) && "ACTIVE".equals(r.status())),
                r.blockedAt())).toList();
    }

    /**
     * Report Content (UC-16) for a message the viewer received and that is not recalled: a report case of target
     * MESSAGE for the Moderator queue (a message has one possible reporter, so it never reaches the hiding threshold).
     * For an encrypted message the reporter also reveals its body P and franking key fk; the server recomputes the
     * franking tag, and only when it matches the stored one is P kept as evidence ({@code chat_report_evidence}).
     * {@code block} also blocks its writer. The conversation row is locked first, as ContentReportService asks.
     */
    public void reportMessage(AppUserPrincipal me, long messageId, ReportRequest request) {
        Account viewer = member(me);
        MessageRow found = repository.message(messageId).orElseThrow(() -> notFound(MESSAGE_NOT_FOUND));
        ConversationRow conversation = repository.lockConversation(found.conversationId())
                .filter(c -> c.has(viewer.id()))
                .orElseThrow(() -> notFound(MESSAGE_NOT_FOUND));
        MemberRow mine = memberRow(conversation.id(), viewer.id());
        MessageRow message = repository.message(messageId).orElseThrow(() -> notFound(MESSAGE_NOT_FOUND));
        if (message.recalled() || !repository.shown(messageId, mine.clearedId())) {
            throw notFound(MESSAGE_NOT_FOUND);
        }
        String evidence = message.encrypted() ? verifiedPlaintext(message, request) : null;
        reports.report(new Target("MESSAGE", messageId, message.senderId()), viewer.id(), request.reasonId(),
                request.note());
        Instant now = clock.instant();
        if (evidence != null) {
            long reportId = repository.reportIdOf(messageId, viewer.id()).orElseThrow();
            repository.insertEvidence(reportId, messageId, evidence, now);
        }
        if (request.block()) {
            block(viewer.id(), message.senderId(), now);
        }
    }

    /** The body of a reported encrypted message, once its franking tag proves the writer sent exactly that. */
    private static String verifiedPlaintext(MessageRow message, ReportRequest request) {
        String plaintext = request.plaintext();
        String frankingKey = request.frankingKey();
        if (plaintext == null || frankingKey == null || plaintext.length() > BASE64_MAX_CHARS
                || frankingKey.length() > 64) {
            throw ApiException.badRequest(REPORT_UNVERIFIED);
        }
        byte[] body = ChatCrypto.base64(plaintext);
        byte[] fk = ChatCrypto.base64(frankingKey);
        if (body == null || fk == null || fk.length != ChatCrypto.FRANKING_KEY_BYTES
                || body.length > CIPHERTEXT_MAX_BYTES) {
            throw ApiException.badRequest(REPORT_UNVERIFIED);
        }
        String tag = ChatCrypto.frankingTag(fk, message.conversationId(), message.senderId(), body);
        String text = ChatCrypto.utf8(body);
        if (!ChatCrypto.sameTag(message.frankingTag(), tag) || text == null) {
            throw ApiException.badRequest(REPORT_UNVERIFIED);
        }
        return text;
    }

    private void block(long blockerId, long blockedId, Instant now) {
        Optional<ConversationRow> conversation = repository.lockConversationOf(blockerId, blockedId);
        repository.block(blockerId, blockedId, now);
        conversation.ifPresent(c -> {
            if (REQUEST.equals(memberRow(c.id(), blockerId).state())) {
                decline(c.id(), blockerId, now);
            }
        });
        // only the blocker's own pages: the blocked member finds out only by trying to write
        announce(conversation.map(ConversationRow::id).orElse(null), "status", null, blockerId);
    }

    // ---------------------------------------------------------------- status

    private Conversation view(Account viewer, ConversationRow conversation) {
        // Taken before the messages are read, so a change saved meanwhile is in the page's first "changed"
        Instant serverTime = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Account peer = account(conversation.other(viewer.id()));
        MemberRow mine = memberRow(conversation.id(), viewer.id());
        MemberRow theirs = memberRow(conversation.id(), peer.id());
        List<MessageRow> rows = repository.messagesBefore(conversation.id(), mine.clearedId(), null, PAGE_SIZE + 1);
        boolean hasOlder = rows.size() > PAGE_SIZE;
        return new Conversation(conversation.id(), peer(peer), status(viewer, peer, conversation, mine, theirs),
                messages(hasOlder ? rows.subList(1, rows.size()) : rows, viewer.id()), hasOlder, reports.reasons(),
                LIMITS, serverTime);
    }

    private Status status(Account viewer, Account peer, ConversationRow conversation, MemberRow mine,
                          MemberRow theirs) {
        boolean blockedByMe = repository.blocked(viewer.id(), peer.id());
        Notice notice = notice(viewer, peer, conversation, mine, theirs, blockedByMe);
        boolean canSend = notice == null || SENDABLE.contains(notice.code());
        long peerRead = theirs != null && ACCEPTED.equals(theirs.state()) ? theirs.lastReadId() : 0;
        Mode mode = mode(viewer.id(), peer.id(), conversation, mine, theirs);
        boolean together = together(notice, mine, theirs);
        boolean peerTyping = together && typing.typing(conversation.id(), peer.id());
        return new Status(mine == null ? null : mine.state(), canSend, notice, blockedByMe, peerRead,
                encryption(peer, mine, theirs, mode, canSend), canAttach(canSend, mode), together, peerTyping);
    }

    private static boolean canAttach(boolean canSend, Mode mode) {
        return canSend && mode.e2ee();
    }

    /**
     * Both members accepted, and nothing cuts the conversation off (no block, the other member an active Member):
     * reactions and "đang soạn tin" work.
     */
    private static boolean together(Notice notice, MemberRow mine, MemberRow theirs) {
        return notice == null && bothAccepted(mine, theirs);
    }

    private static boolean bothAccepted(MemberRow mine, MemberRow theirs) {
        return mine != null && theirs != null && ACCEPTED.equals(mine.state()) && ACCEPTED.equals(theirs.state());
    }

    /**
     * The members' current keys (the other member's only once a conversation exists) and the mode: E2EE when both
     * accepted and both have a key.
     */
    private Mode mode(long viewerId, long peerId, ConversationRow conversation, MemberRow mine, MemberRow theirs) {
        KeyRow myKey = repository.currentKey(viewerId).orElse(null);
        KeyRow peerKey = conversation == null ? null : repository.currentKey(peerId).orElse(null);
        return new Mode(myKey, peerKey, bothAccepted(mine, theirs) && myKey != null && peerKey != null);
    }

    /**
     * The encryption part of the status. Its notice, while the viewer may write: REQUEST_PLAIN before both accepted,
     * NO_KEY_ME / NO_KEY_PEER while a member has no key yet.
     */
    private static Encryption encryption(Account peer, MemberRow mine, MemberRow theirs, Mode mode, boolean canSend) {
        Notice notice = null;
        if (canSend) {
            String name = peer.displayName();
            if (!bothAccepted(mine, theirs)) {
                notice = new Notice("REQUEST_PLAIN", "Lời mời nhắn tin chưa được mã hóa và chỉ gửi được chữ. Khi hai bạn"
                        + " đã nhận lời và cùng bật mã hóa, tin nhắn sẽ được mã hóa đầu cuối.");
            } else if (mode.mine() == null) {
                notice = new Notice("NO_KEY_ME", "Bạn chưa bật mã hóa đầu cuối. Hãy bật để gửi được ảnh, video, ghi âm"
                        + " và để chỉ hai bạn đọc được tin nhắn.");
            } else if (mode.theirs() == null) {
                notice = new Notice("NO_KEY_PEER", name + " chưa bật mã hóa đầu cuối. Tin nhắn vẫn gửi được dạng chữ"
                        + " và chưa được mã hóa cho đến khi " + name + " bật.");
            }
        }
        return new Encryption(mode.e2ee() ? E2EE : PLAIN, mode.mine() == null ? null : mode.mine().id(),
                mode.theirs() == null ? null : mode.theirs().id(),
                mode.theirs() == null ? null : mode.theirs().publicKey(), notice);
    }

    /**
     * What stops the viewer from writing, or what they should know first; null when they write freely. The first
     * that applies: the other account is deleted; the viewer blocked them; they blocked the viewer, are locked or are
     * not a Member (one wording for all three, so a block is not given away); the viewer was asked and has not
     * answered; this is the first message; the viewer's request is not accepted yet (a few messages left, or none).
     */
    private Notice notice(Account viewer, Account peer, ConversationRow conversation, MemberRow mine, MemberRow theirs,
                          boolean blockedByMe) {
        String name = peer.displayName();
        if (peer.deleted()) {
            return new Notice("DELETED", DELETED);
        }
        if (blockedByMe) {
            return new Notice("BLOCKED_BY_ME", "Bạn đã chặn " + name + ". Bỏ chặn để nhắn tin tiếp.");
        }
        if (!peer.member() || repository.blocked(peer.id(), viewer.id())) {
            return new Notice("UNAVAILABLE", UNAVAILABLE);
        }
        if (mine != null && REQUEST.equals(mine.state())) {
            return new Notice(REQUEST, name + " muốn nhắn tin với bạn. Bạn có thể chấp nhận, trả lời hoặc từ chối; "
                    + name + " chưa biết bạn đã đọc tin cho đến khi bạn chấp nhận.");
        }
        if (conversation == null || theirs == null) {
            return new Notice("NEW", "Tin nhắn đầu tiên sẽ đến " + name + " dưới dạng lời mời. Trước khi " + name
                    + " trả lời, bạn gửi được tối đa " + PENDING_MAX + " tin nhắn.");
        }
        if (!ACCEPTED.equals(theirs.state())) {
            int left = PENDING_MAX - repository.countMessagesBy(conversation.id(), viewer.id());
            if (left <= 0 || DECLINED.equals(theirs.state())) {
                return new Notice("WAITING_FULL", name + " chưa nhận lời mời nhắn tin của bạn. Bạn gửi thêm được khi "
                        + name + " trả lời.");
            }
            return new Notice("WAITING", name + " chưa nhận lời mời nhắn tin của bạn. Bạn còn gửi được " + left
                    + " tin nhắn trước khi " + name + " trả lời.");
        }
        return null;
    }

    /** The error of a notice that stops sending. */
    private static ApiException refusal(Notice notice) {
        return switch (notice.code()) {
            case "DELETED", "BLOCKED_BY_ME" -> ApiException.badRequest(notice.text());
            default -> new ApiException(HttpStatus.FORBIDDEN, notice.text());
        };
    }

    // ---------------------------------------------------------------- helpers

    /** The signed-in Member. Guests get 401; staff roles and locked accounts get MSG18. */
    private Account member(AppUserPrincipal me) {
        if (me == null) {
            throw ApiException.unauthorized("Bạn cần đăng nhập để nhắn tin.");
        }
        Account account = repository.account(me.id())
                .orElseThrow(() -> ApiException.unauthorized("Bạn chưa đăng nhập."));
        if (!account.member()) {
            throw new ApiException(HttpStatus.FORBIDDEN, NO_PERMISSION);
        }
        return account;
    }

    /** The member the viewer wants to write to: not themselves, and an existing account. */
    private Account peerToWrite(Account viewer, long userId) {
        if (userId == viewer.id()) {
            throw ApiException.badRequest("Bạn không thể nhắn tin cho chính mình.");
        }
        return repository.account(userId).orElseThrow(() -> notFound(MEMBER_NOT_FOUND));
    }

    /** A conversation of the viewer; anyone else's answers "not found". */
    private ConversationRow participant(long conversationId, long userId) {
        return repository.conversation(conversationId).filter(c -> c.has(userId))
                .orElseThrow(() -> notFound(CONVERSATION_NOT_FOUND));
    }

    /** {@link #participant}, with the conversation row locked until the action ends. */
    private ConversationRow lockedParticipant(long conversationId, long userId) {
        return repository.lockConversation(conversationId).filter(c -> c.has(userId))
                .orElseThrow(() -> notFound(CONVERSATION_NOT_FOUND));
    }

    private MemberRow memberRow(long conversationId, long userId) {
        return repository.member(conversationId, userId).orElseThrow(() -> notFound(CONVERSATION_NOT_FOUND));
    }

    private Account account(long userId) {
        return repository.account(userId).orElseThrow(() -> notFound(CONVERSATION_NOT_FOUND));
    }

    /** A message of the conversation shown to the viewer (recalled ones too); anything else answers 404. */
    private MessageRow shownMessage(ConversationRow conversation, MemberRow mine, long messageId) {
        return repository.message(messageId)
                .filter(m -> m.conversationId() == conversation.id() && repository.shown(m.id(), mine.clearedId()))
                .orElseThrow(() -> notFound(MESSAGE_NOT_FOUND));
    }

    /** {@link #shownMessage}, written by the viewer and not recalled. */
    private MessageRow ownMessage(ConversationRow conversation, MemberRow mine, long messageId, long viewerId) {
        MessageRow message = shownMessage(conversation, mine, messageId);
        if (message.senderId() != viewerId) {
            throw ApiException.forbidden(NOT_MINE);
        }
        if (message.recalled()) {
            throw ApiException.badRequest(RECALLED);
        }
        return message;
    }

    private Peer peer(Account account) {
        return new Peer(account.id(), account.displayName(), account.avatarUrl(),
                repository.tagsOf(List.of(account.id())).getOrDefault(account.id(), List.of()), account.member());
    }

    private Counts counts(long userId) {
        int[] counts = repository.counts(userId);
        Latest latest = repository.latestUnread(userId).map(r -> new Latest(r.conversationId(), r.messageId(),
                r.peerName(), REQUEST.equals(r.state()), r.createdAt())).orElse(null);
        return new Counts(counts[0], counts[1], latest);
    }

    /** The stripped text of a message: not empty, at most 2,000 characters. */
    private static String text(String raw) {
        String content = raw == null ? "" : raw.strip();
        if (content.isEmpty()) {
            throw ApiException.badRequest("Hãy nhập tin nhắn.");
        }
        if (content.length() > MESSAGE_MAX) {
            throw ApiException.badRequest("Tin nhắn tối đa 2.000 ký tự.");
        }
        return content;
    }

    /**
     * An envelope in the agreed form, stored with canonical base64: ciphertext of {@value #CIPHERTEXT_MIN_BYTES}–
     * {@value #CIPHERTEXT_MAX_BYTES} bytes, iv of 12 bytes, both key ids, a 64-character lower-case hex franking tag.
     */
    private static Envelope envelope(Envelope given) {
        if (given.ciphertext() == null || given.ciphertext().length() > BASE64_MAX_CHARS || given.iv() == null
                || given.iv().length() > 32) {
            throw ApiException.badRequest(BAD_ENVELOPE);
        }
        byte[] ciphertext = ChatCrypto.base64(given.ciphertext());
        byte[] iv = ChatCrypto.base64(given.iv());
        if (ciphertext == null || ciphertext.length < CIPHERTEXT_MIN_BYTES || ciphertext.length > CIPHERTEXT_MAX_BYTES
                || iv == null || iv.length != IV_BYTES || given.senderKeyId() == null || given.recipientKeyId() == null
                || given.frankingTag() == null || !FRANKING_TAG.matcher(given.frankingTag()).matches()) {
            throw ApiException.badRequest(BAD_ENVELOPE);
        }
        return new Envelope(ChatCrypto.base64(ciphertext), ChatCrypto.base64(iv), given.senderKeyId(),
                given.recipientKeyId(), given.frankingTag());
    }

    private Message toMessage(MessageRow row, long viewerId) {
        return messages(List.of(row), viewerId).get(0);
    }

    /** The rows as the viewer sees them, with their reactions and files (none for a recalled message). */
    private List<Message> messages(List<MessageRow> rows, long viewerId) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> live = rows.stream().filter(r -> !r.recalled()).map(MessageRow::id).toList();
        Map<Long, List<ReactionRow>> reactions = repository.reactionsOf(live);
        Map<Long, List<Long>> files = repository.fileIdsOf(live);
        return rows.stream().map(r -> {
            boolean mine = r.senderId() == viewerId;
            if (r.recalled()) {
                return new Message(r.id(), mine, null, null, null, false, true, List.of(), List.of(), r.createdAt());
            }
            return new Message(r.id(), mine, r.content(), r.envelope(), r.replyToId(), r.editedAt() != null, false,
                    reactions(reactions.getOrDefault(r.id(), List.of()), viewerId),
                    files.getOrDefault(r.id(), List.of()), r.createdAt());
        }).toList();
    }

    /** Counts per emoji, in the order of {@link #REACTIONS}. */
    private static List<Reaction> reactions(List<ReactionRow> rows, long viewerId) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Reaction> result = new ArrayList<>();
        for (String emoji : REACTIONS) {
            int count = 0;
            boolean mine = false;
            for (ReactionRow row : rows) {
                if (emoji.equals(row.emoji())) {
                    count++;
                    mine |= row.userId() == viewerId;
                }
            }
            if (count > 0) {
                result.add(new Reaction(emoji, count, mine));
            }
        }
        return result;
    }

    /**
     * The last message of a list row: plain text on one line and cut to {@value #PREVIEW_MAX} characters; an
     * encrypted one keeps its envelope (the page decrypts the preview), a recalled one says so.
     */
    private static Message preview(Message message) {
        if (message == null || message.content() == null) {
            return message;
        }
        String line = message.content().replaceAll("\\s+", " ").strip();
        if (line.length() > PREVIEW_MAX) {
            line = line.substring(0, PREVIEW_MAX - 1) + "…";
        }
        return new Message(message.id(), message.mine(), line, null, message.replyToId(), message.edited(), false,
                message.reactions(), message.fileIds(), message.createdAt());
    }

    /** {@code changedSince}: an ISO instant ("2026-10-28T01:02:03.456Z") or epoch milliseconds; blank = none. */
    private static Instant parseTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.strip();
        Instant time;
        try {
            time = value.chars().allMatch(Character::isDigit) ? Instant.ofEpochMilli(Long.parseLong(value))
                    : Instant.parse(value);
        } catch (DateTimeException | NumberFormatException e) {
            throw ApiException.badRequest("Thời điểm không hợp lệ.");
        }
        // A year like -1000000000 parses, but subtracting the overlap from it would overflow (a 500)
        return time.isBefore(EARLIEST_TIME) ? EARLIEST_TIME : time;
    }

    /**
     * Tells the open pages of these members at once that the conversation changed (common/live/LiveEvents, after the
     * commit): {@code kind} message, changed, read, typing or status, ids only. The page then asks with the usual API.
     */
    private void announce(Long conversationId, String kind, Long messageId, long... userIds) {
        Map<String, Object> data = new HashMap<>();
        data.put("kind", kind);
        if (conversationId != null) {
            data.put("conversationId", conversationId);
        }
        if (messageId != null) {
            data.put("messageId", messageId);
        }
        for (long userId : userIds) {
            live.publish(userId, "chat", data);
        }
    }

    /** An edit, recall or reaction: the actor's other pages, and the other member when the message shows for them. */
    private void announceChanged(ConversationRow conversation, long messageId, long actorId, MemberRow theirs) {
        if (messageId > theirs.clearedId()) {
            announce(conversation.id(), "changed", messageId, actorId, conversation.other(actorId));
        } else {
            announce(conversation.id(), "changed", messageId, actorId);
        }
    }

    private void deleteAfterCommit(List<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                keys.forEach(ChatService.this::deleteQuietly);
            }
        });
    }

    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete chat file {}: {}", key, e.getMessage());
        }
    }

    private static String emoji(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    private static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }
}
