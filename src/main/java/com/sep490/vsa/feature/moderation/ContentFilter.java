package com.sep490.vsa.feature.moderation;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.text.VietnameseText;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Automatic text checks run before user content is stored (GBR-18, BR-22):
 * <ul>
 *   <li>Banned keywords (Admin list), compared after removing accents, case, spaces and punctuation on both
 *       sides, so "Việc nhẹ, lương-cao" still matches "việc nhẹ lương cao".</li>
 *   <li>Links: only accounts with an identity tag may post links, and shortened links are never allowed.</li>
 * </ul>
 * A failed check throws a 400 with the message shown to the user; the input is kept on the page.
 */
@Service
public class ContentFilter {

    private static final Pattern LINK = Pattern.compile(
            "(?i)\\b(?:https?://|www\\.)\\S+|\\b[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:com|net|org|info|biz|vn|jp|ly|me|io|co|gl|to|gg|xyz|site|link|click|shop|top|cc|id|at)(?:/\\S*)?\\b");
    private static final List<String> SHORTENERS = List.of(
            "bit.ly", "tinyurl.com", "goo.gl", "t.co", "ow.ly", "is.gd", "cutt.ly", "rb.gy", "shorturl.at",
            "tiny.cc", "s.id", "buff.ly", "rebrand.ly", "t.ly", "v.gd");

    private final JdbcTemplate jdbc;

    public ContentFilter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Runs every check on {@code text} written by {@code authorId}: keywords first, then links. */
    public void check(String text, long authorId) {
        checkKeywords(text);
        checkLinks(text, authorId);
    }

    /** Banned keywords only (MSG36). Throws 400 naming the keyword; the page keeps what was typed. */
    public void checkKeywords(String text) {
        bannedKeyword(text).ifPresent(keyword -> {
            throw ApiException.badRequest(
                    "Chưa đăng được: nội dung chứa từ khóa không được phép “" + keyword + "”. Hãy sửa rồi gửi lại.");
        });
    }

    /** Link rule only (BR-22): no shortened links for anyone, and links only from accounts with an identity tag. */
    public void checkLinks(String text, long authorId) {
        List<String> links = links(text);
        if (links.isEmpty()) {
            return;
        }
        if (links.stream().anyMatch(ContentFilter::isShortened)) {
            throw ApiException.badRequest("Không dùng link rút gọn (bit.ly, tinyurl…). Hãy dán link đầy đủ.");
        }
        if (!hasIdentityTag(authorId)) {
            throw ApiException.badRequest("Chỉ tài khoản đã có tag định danh mới được chèn link.");
        }
    }

    /** The first banned keyword found in the text, as the Admin wrote it. */
    public Optional<String> bannedKeyword(String text) {
        String squashed = VietnameseText.squash(text);
        if (squashed.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.query("SELECT keyword FROM banned_keywords", (rs, i) -> rs.getString(1)).stream()
                .filter(k -> {
                    String key = VietnameseText.squash(k);
                    return !key.isEmpty() && squashed.contains(key);
                })
                .findFirst();
    }

    static List<String> links(String text) {
        if (text == null) {
            return List.of();
        }
        Matcher m = LINK.matcher(text);
        java.util.ArrayList<String> found = new java.util.ArrayList<>();
        while (m.find()) {
            found.add(m.group());
        }
        return found;
    }

    static boolean isShortened(String link) {
        String host = link.toLowerCase(Locale.ROOT).replaceFirst("^https?://", "").replaceFirst("^www\\.", "");
        int slash = host.indexOf('/');
        String domain = slash < 0 ? host : host.substring(0, slash);
        return SHORTENERS.contains(domain);
    }

    /** True when the user holds at least one approved, not revoked identity tag (may post links). */
    public boolean hasIdentityTag(long userId) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM user_tags ut JOIN tag_types t ON t.id = ut.tag_type_id
                WHERE ut.user_id = ? AND ut.revoked_at IS NULL AND t.is_active""", Long.class, userId);
        return n != null && n > 0;
    }
}
