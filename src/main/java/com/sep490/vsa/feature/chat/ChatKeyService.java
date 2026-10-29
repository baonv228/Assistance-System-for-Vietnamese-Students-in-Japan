package com.sep490.vsa.feature.chat;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.chat.ChatDtos.KeyBackup;
import com.sep490.vsa.feature.chat.ChatDtos.KeyRequest;
import com.sep490.vsa.feature.chat.ChatDtos.MyKey;
import com.sep490.vsa.feature.chat.ChatDtos.PublicKey;
import com.sep490.vsa.feature.chat.ChatRepository.Account;
import com.sep490.vsa.feature.chat.ChatRepository.KeyRow;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Identity keys for end-to-end encrypted chat ("Mã hóa đầu cuối"). Each Member has one current key pair (ECDH P-256)
 * made in their browser; the server keeps the public key and the private key wrapped with their "mật khẩu khôi phục"
 * (PBKDF2 + AES-GCM in the browser), so a new device can restore it but the server can never read it.
 * <ul>
 *   <li>A new key replaces the current one (it is revoked, not deleted: older messages name it and stay readable with
 *       the backup the member keeps). At most {@link #KEYS_PER_DAY} new keys a Japan day ("cần chốt"), because every
 *       new key makes the other members' pages fetch keys again.</li>
 *   <li>The public key must be a real P-256 point ({@link ChatCrypto#isP256Point}); the backup fields must have the
 *       sizes the browser makes (salt 16–48 bytes, iv 12, PBKDF2 iterations 100,000–5,000,000).</li>
 *   <li>A key is shown to its owner and to members who share a conversation with them; anyone else gets 404.</li>
 * </ul>
 * Creating a key locks the member's day row ({@code user_daily_usage}), so two keys made at once are counted and
 * revoked one after the other and the member always ends with exactly one current key.
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ChatKeyService {

    static final String PUBLIC_KEY_INVALID = "Khóa công khai không hợp lệ.";
    static final String BACKUP_INVALID = "Bản sao lưu khóa bí mật không hợp lệ.";
    static final String KEY_NOT_FOUND = "Không tìm thấy khóa mã hóa này.";

    /** "cần chốt": new keys a member may make in a Japan day. */
    static final int KEYS_PER_DAY = 5;
    static final int WRAPPED_MIN_BYTES = 16;
    static final int WRAPPED_MAX_BYTES = 600;
    static final int SALT_MIN_BYTES = 16;
    /** 48 bytes are 64 base64 characters, the size of {@code chat_keys.wrap_salt}. */
    static final int SALT_MAX_BYTES = 48;
    static final int WRAP_IV_BYTES = 12;
    static final int ITERATIONS_MIN = 100_000;
    static final int ITERATIONS_MAX = 5_000_000;
    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

    private final ChatRepository repository;
    private final Clock clock;

    public ChatKeyService(ChatRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** The viewer's current key with its backup, or {@code key: null} before they turn encryption on. */
    @Transactional(readOnly = true)
    public MyKey myKey(AppUserPrincipal me) {
        Account viewer = member(me);
        return new MyKey(repository.currentKey(viewer.id()).map(ChatKeyService::backup).orElse(null));
    }

    /** A new identity key; the current one is revoked. */
    public KeyBackup create(AppUserPrincipal me, KeyRequest request) {
        Account viewer = member(me);
        if (request == null) {
            throw ApiException.badRequest(PUBLIC_KEY_INVALID);
        }
        byte[] point = request.publicKey() == null || request.publicKey().length() > 120 ? null
                : ChatCrypto.base64(request.publicKey());
        if (!ChatCrypto.isP256Point(point)) {
            throw ApiException.badRequest(PUBLIC_KEY_INVALID);
        }
        byte[] wrapped = sized(request.wrappedPrivateKey(), 1000);
        byte[] salt = sized(request.wrapSalt(), 64);
        byte[] iv = sized(request.wrapIv(), 32);
        Integer iterations = request.wrapIterations();
        if (wrapped == null || wrapped.length < WRAPPED_MIN_BYTES || wrapped.length > WRAPPED_MAX_BYTES
                || salt == null || salt.length < SALT_MIN_BYTES || salt.length > SALT_MAX_BYTES
                || iv == null || iv.length != WRAP_IV_BYTES
                || iterations == null || iterations < ITERATIONS_MIN || iterations > ITERATIONS_MAX) {
            throw ApiException.badRequest(BACKUP_INVALID);
        }

        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, JAPAN);
        repository.lockUserDay(viewer.id(), today);
        if (repository.countKeysBetween(viewer.id(), today.atStartOfDay(JAPAN).toInstant(),
                today.plusDays(1).atStartOfDay(JAPAN).toInstant()) >= KEYS_PER_DAY) {
            throw ApiException.tooManyRequests("Bạn đã tạo đủ " + KEYS_PER_DAY + " khóa mã hóa hôm nay. Vui lòng thử lại"
                    + " vào ngày mai.");
        }
        repository.revokeKeys(viewer.id(), now);
        String publicKey = ChatCrypto.base64(point);
        String wrappedText = ChatCrypto.base64(wrapped);
        String saltText = ChatCrypto.base64(salt);
        String ivText = ChatCrypto.base64(iv);
        long id = repository.insertKey(viewer.id(), publicKey, wrappedText, saltText, ivText, iterations, now);
        return new KeyBackup(id, publicKey, wrappedText, saltText, ivText, iterations, now);
    }

    /** A public key of the viewer or of a member they share a conversation with (revoked ones too). */
    @Transactional(readOnly = true)
    public PublicKey key(AppUserPrincipal me, long keyId) {
        Account viewer = member(me);
        KeyRow key = repository.key(keyId)
                .filter(k -> k.userId() == viewer.id() || repository.conversationOf(viewer.id(), k.userId()).isPresent())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, KEY_NOT_FOUND));
        return new PublicKey(key.id(), key.userId(), key.publicKey(), key.revokedAt() != null);
    }

    private static KeyBackup backup(KeyRow key) {
        return new KeyBackup(key.id(), key.publicKey(), key.wrappedPrivateKey(), key.wrapSalt(), key.wrapIv(),
                key.wrapIterations(), key.createdAt());
    }

    /** Base64 no longer than {@code maxChars}, decoded; null otherwise. */
    private static byte[] sized(String text, int maxChars) {
        return text == null || text.length() > maxChars ? null : ChatCrypto.base64(text);
    }

    /** The signed-in Member, as {@link ChatService} checks it: guests 401, staff and locked accounts MSG18. */
    private Account member(AppUserPrincipal me) {
        if (me == null) {
            throw ApiException.unauthorized("Bạn cần đăng nhập để nhắn tin.");
        }
        Account account = repository.account(me.id())
                .orElseThrow(() -> ApiException.unauthorized("Bạn chưa đăng nhập."));
        if (!account.member()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ChatService.NO_PERMISSION);
        }
        return account;
    }
}
