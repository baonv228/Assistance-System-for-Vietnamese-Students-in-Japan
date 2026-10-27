package com.sep490.vsa.feature.moderation;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.text.VietnameseText;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Automatic text checks run before user content is stored (GBR-18, BR-22):
 * <ul>
 *   <li>Banned keywords (Admin list, MSG36), compared word by word ({@link #findKeyword}): accents may be left out
 *       ("vay nong" matches "vay nóng") but another accented word does not match ("vậy nóng", "cá đông"); spaces and
 *       punctuation between the words do not matter ("Việc nhẹ, lương-cao"), and disguises are read through
 *       ("v.a.y n.ó.n.g", "vaynong", "v4y n0ng", fullwidth or Cyrillic letters). A keyword standing only inside an
 *       everyday phrase is not counted ("co bac nao biet" is "có bác nào biết", {@link #EVERYDAY_PHRASES}). Also
 *       checked: display names ({@link #checkDisplayName}).</li>
 *   <li>Links: only accounts with an identity tag may post links, and shortened links are never allowed.</li>
 *   <li>Contact details, same rule as links: Vietnamese and Japanese mobile numbers and Zalo, LINE, Telegram… ids
 *       only from accounts with an identity tag. Landlines and toll-free numbers (a ward office) stay allowed.</li>
 * </ul>
 * The checks read a normalised copy of the text; the text that is stored never changes. A failed check throws a 400
 * with the message shown to the user; the input is kept on the page.
 */
@Service
public class ContentFilter {

    static final String SHORT_LINK = "Không dùng link rút gọn (bit.ly, tinyurl…). Hãy dán link đầy đủ.";
    static final String LINK_NEEDS_TAG = "Chỉ tài khoản đã có tag định danh mới được chèn link.";
    static final String CONTACT_NEEDS_TAG =
            "Chỉ tài khoản đã có tag định danh mới được ghi số điện thoại hoặc tài khoản Zalo, LINE, Telegram.";

    /** What the link and contact rule (BR-22) refuses in a text, with the message shown to the user. */
    public enum LinkProblem {
        /** A shortened link: nobody may post one. */
        SHORT_LINK(ContentFilter.SHORT_LINK),
        /** A link from an account without an identity tag. */
        LINK_NEEDS_TAG(ContentFilter.LINK_NEEDS_TAG),
        /** A mobile number or chat-app id (and no link) from an account without an identity tag. */
        CONTACT_NEEDS_TAG(ContentFilter.CONTACT_NEEDS_TAG);

        private final String message;

        LinkProblem(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    // ---------------------------------------------------------------- normalised copy

    /** Cyrillic and Greek letters that look like Latin ones ("vаy" with a Cyrillic а), read as that letter. Cần chốt. */
    private static final Map<Character, Character> LOOKALIKES = lookalikes(
            // Cyrillic capitals А В Е К М Н О Р С Т У Х Ѕ І Ј Ү Һ Ӏ Ԁ
            "\u0410\u0412\u0415\u041A\u041C\u041D\u041E\u0420\u0421\u0422\u0423\u0425\u0405\u0406\u0408\u04AE\u04BA\u04C0\u0500",
            "ABEKMHOPCTYXSIJYHID",
            // Cyrillic small а е к м о р с у х ѕ і ј ү һ ӏ ԁ ԛ ԝ
            "\u0430\u0435\u043A\u043C\u043E\u0440\u0441\u0443\u0445\u0455\u0456\u0458\u04AF\u04BB\u04CF\u0501\u051B\u051D",
            "aekmopcyxsijyhldqw",
            // Greek capitals Α Β Ε Ζ Η Ι Κ Μ Ν Ο Ρ Τ Υ Χ
            "\u0391\u0392\u0395\u0396\u0397\u0399\u039A\u039C\u039D\u039F\u03A1\u03A4\u03A5\u03A7",
            "ABEZHIKMNOPTYX",
            // Greek small α β γ ε η ι κ μ ν ο ρ τ υ χ ω, Latin dotless ı and script ɡ
            "\u03B1\u03B2\u03B3\u03B5\u03B7\u03B9\u03BA\u03BC\u03BD\u03BF\u03C1\u03C4\u03C5\u03C7\u03C9\u0131\u0261",
            "abyenikuvoptuxwig");

    // ---------------------------------------------------------------- banned keywords (MSG36)

    /** The keyword list is read again after this long; an Admin change clears it at once. Cần chốt. */
    static final Duration KEYWORD_CACHE_TTL = Duration.ofSeconds(60);
    /**
     * A keyword whose letters, run together, are at least this many is also found inside one longer word
     * ("tuyenviecnheluongcaongay"); a shorter one would turn up inside ordinary words. Cần chốt.
     */
    static final int CONTAINS_MIN_LETTERS = 8;
    /** Digits read as letters inside a word that mixes both ("v4y n0ng"); a plain number is never changed. Cần chốt. */
    private static final String LEET_DIGITS = "013457";
    private static final String LEET_LETTERS = "oieast";
    /** Scripts written without spaces between words: a keyword in them is found anywhere in the text. */
    private static final Set<Character.UnicodeScript> UNSPACED_SCRIPTS = EnumSet.of(Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.THAI,
            Character.UnicodeScript.LAO, Character.UnicodeScript.KHMER, Character.UnicodeScript.MYANMAR);
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}\\p{N}]+");
    /**
     * Everyday phrases that hold a banned keyword, or are one once their accents are left out: a keyword found only
     * inside one of them is not counted ({@link Everyday}). "co bac nao biet" is "có bác nào biết", not "cờ bạc";
     * "bạn gái gọi điện" holds no "gái gọi"; "支援交流" no "援交". Compared like keywords (accents may be left out).
     * As long as needed and no longer: a longer keyword around the same words is still found ("chơi lô đề" in
     * "choi lo de", although "lo để" is an everyday phrase). Cần chốt.
     */
    static final List<String> EVERYDAY_PHRASES = List.of(
            // gambling words: cờ bạc, cá cược, đặt cược, lô đề, số đề, đánh đề, ghi đề, nổ hũ, đá gà, bầu cua, nhà cái
            "có bác nào", "có bác sĩ", "có bác ai", "các cô bác", "cả cuộc đời", "cả cuộc sống", "đặt cuộc hẹn",
            "đặt cuộc gọi", "lo để", "số để", "dành để", "ghi đè", "nó hư", "da gà", "bầu của", "nhà cái gì",
            "nhà cái nào cũng",
            // prostitution words: mại dâm, bán dâm, mua dâm, gái điếm, nhà thổ, bao nuôi, gái bao, trai bao, gái gọi,
            // đi khách, 援交, 風俗
            "mai đám cưới", "mai đám giỗ", "mai đám ma", "bàn đàm phán", "bận đám cưới", "bận đám giỗ", "mưa dầm",
            "gái điểm cao", "gái điểm thấp", "nhà thờ", "bảo nuôi", "gái bao nhiêu", "gái bao giờ", "trai bao nhiêu",
            "trai bao giờ", "bạn gái gọi", "con gái gọi", "cô gái gọi", "em gái gọi", "chị gái gọi", "gái gọi điện",
            "đi khách sạn", "支援交", "応援交", "救援交", "風俗習慣",
            // the work permit on every working student's residence card: 「資格外活動許可…風俗営業等の従事を除く」
            "風俗営業等の従事");
    private static final List<Keyword> EVERYDAY = prepare(EVERYDAY_PHRASES);

    // ---------------------------------------------------------------- links (BR-22)

    /** Endings that make "name.ending" a link. Not "ga": it is the Vietnamese word for a station. Cần chốt. */
    static final Set<String> TLDS = Set.of(
            "com", "net", "org", "info", "biz", "vn", "jp", "xyz", "icu", "io", "co", "me", "ly", "gl", "to", "gg",
            "cc", "id", "at", "so", "ee", "in", "tv", "ws", "pw", "cn", "ru", "kr", "th", "ph", "my", "sg", "tw",
            "hk", "us", "uk", "de", "fr", "eu", "site", "link", "click", "shop", "top", "vip", "online", "store",
            "app", "dev", "club", "live", "fun", "work", "asia", "pro", "mobi");
    /**
     * Endings that are never an ordinary word or abbreviation, so they make a link whatever their case ("ABC.COM").
     * The other endings make a link only in lower case or before a path ("abc.in", "abc.IN/x"), so a sentence that
     * misses a space ("ga Ueno.In vé…", "ngon.Top 1") or an abbreviation ("T7.CN", "hàng Nhật.VN") is not one. Cần chốt.
     */
    static final Set<String> ANY_CASE_TLDS = Set.of("com", "net", "org", "biz", "xyz", "icu");
    /** Shortened links hide where they lead: nobody may post them. youtu.be is not one (it only opens YouTube). */
    static final Set<String> SHORTENERS = Set.of(
            "bit.ly", "tinyurl.com", "goo.gl", "t.co", "ow.ly", "is.gd", "cutt.ly", "rb.gy", "shorturl.at",
            "tiny.cc", "s.id", "buff.ly", "rebrand.ly", "t.ly", "v.gd",
            "bom.so", "bom.to", "shorten.asia", "lnkd.in", "linktr.ee", "shope.ee", "s.shopee.vn", "qrco.de",
            "tiny.one", "short.gy", "cutt.us", "link1s.com", "link4m.com", "link4m.co", "yeumoney.com", "megaurl.in");
    /**
     * Short links that only open their own service, like youtu.be, are allowed even under a shortener's domain:
     * Google Maps share links (maps.app.goo.gl) are how members point to a place. Cần chốt.
     */
    static final Set<String> NOT_SHORTENED = Set.of("maps.app.goo.gl");
    /** How many leading labels of a dotted run are tried as a shortener's host ("x.s.shopee.vn" has 4). */
    private static final int SHORTENER_SCAN_LABELS =
            SHORTENERS.stream().mapToInt(s -> s.split("\\.").length).max().orElse(2) + 2;
    /**
     * Endings that are also common Vietnamese (or Vietnamese-English) words, mostly typed without accents: "chấm so với",
     * "dấu chấm to", "chấm in bài", "gửi . link". After a dot written as a word or with spaces ({@link #WORDED_DOT})
     * they count only before a path ("zalo chấm me/0901"). Cần chốt.
     */
    static final Set<String> WORD_ENDINGS = Set.of(
            "so", "to", "in", "my", "me", "co", "ly", "ru", "de", "top", "shop", "live", "fun", "pro",
            "app", "link", "online", "tv", "vip", "club", "store", "click", "site", "work",
            "th", "ph", "cn", "id", "at", "us", "gg", "cc");
    /**
     * A bare domain (no "http(s)://" or "www.") is a link only when its labels are ASCII letters, digits and hyphens
     * once {@link #unmask} has undone fullwidth and look-alike letters: "nhà.me", "chợ.to", "hỏi.in" are sentences
     * missing a space, while "ｓｃａｍ.com" and "ѕсаm.com" (Cyrillic) are still links. Cần chốt.
     */
    private static final Pattern BARE_HOST_LABEL = Pattern.compile("[A-Za-z0-9-]+");
    private static final String TLD_ALTERNATIVES = alternatives(TLDS);
    /** "abc[.]com", "abc(.)me", "abc(dot)com", "abc{.}vn": a dot in brackets, before any known ending. */
    private static final Pattern BRACKETED_DOT = Pattern.compile(
            "(?<=[\\p{L}\\p{N}])\\s{0,3}[\\[({]\\s{0,2}(?:\\.|dot|chấm|cham)\\s{0,2}[\\])}]\\s{0,3}"
                    + "(?=(?:" + TLD_ALTERNATIVES + ")(?![\\p{L}\\p{N}-]))",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /**
     * "abc chấm vn", "abc . com", "abc dot com", "bit . ly/x": a dot written as a word or with spaces. These also occur
     * in ordinary sentences ("Thầy Nam chấm so với…", "Linh chấm to hơn"), so they count before an ending that is not
     * a common word ({@link #WORD_ENDINGS}), or before any ending followed by a path.
     */
    private static final Pattern WORDED_DOT = Pattern.compile(
            "(?<=[\\p{L}\\p{N}])(?:\\s{1,3}\\.\\s{0,3}|\\s{1,3}(?:chấm|cham|dot)\\s{1,3}|\\s{0,3}。\\s{0,3})"
                    + "(?=(?:" + alternatives(TLDS.stream().filter(t -> !WORD_ENDINGS.contains(t))
                    .collect(Collectors.toSet())) + ")(?![\\p{L}\\p{N}-])|(?:" + TLD_ALTERNATIVES + ")/)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /**
     * A full address, or a run of dotted words that may hold a domain. Possessive and bounded: no backtracking and no
     * recursion per ".label" on long input ("a.a.a…" overflowed the stack before). A longer run is matched in pieces of
     * at most 21 labels, so a domain at its end is still found.
     */
    private static final Pattern URL_OR_HOST = Pattern.compile(
            "(?<url>(?<![\\p{L}\\p{N}])(?:https?://|www\\.)\\S+)"
                    + "|(?<host>(?<![\\p{L}\\p{N}-])[\\p{L}\\p{N}-]++(?:\\.[\\p{L}\\p{N}-]++){1,20}+)",
            Pattern.CASE_INSENSITIVE);

    // ---------------------------------------------------------------- contact details (BR-22)

    /** Spaces, dots and dashes people put between the digits of a phone number. */
    private static final String SEP = "[ \\t.\\-\\u2010-\\u2015\\u2212\\u30FC]";
    private static final String HYPHEN = "[\\-\\u2010-\\u2015\\u2212\\u30FC]";
    /** Words people glue to a phone number ("sđt0901234567"). Cần chốt. */
    private static final String GLUED = "(?:sđt|sdt|đt|dt|tel|zalo|zl|ib|lh|line|call|phone|hotline)";
    /** Vietnamese mobile: 0, 84 or +84, then 3/5/7/8/9 and 8 more digits. No digit right before or after. Cần chốt. */
    private static final Pattern VN_MOBILE = Pattern.compile("(?<![\\p{L}\\p{N}])" + GLUED + "?(?<number>"
            + "(?:\\(?\\+?84\\)?" + SEP + "{0,3}0?|0)" + SEP + "{0,3}[35789](?:" + SEP + "{0,3}\\d){8})(?!\\d)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Japanese mobile: 070, 080 or 090 (or +81 70…), then 8 digits; 0800 is toll-free. Cần chốt. */
    private static final Pattern JP_MOBILE = Pattern.compile("(?<![\\p{L}\\p{N}])" + GLUED + "?(?<number>"
            + "(?:\\(?\\+?81\\)?" + SEP + "{0,3}0?|0)[789]" + SEP + "{0,3}0" + SEP + "{0,3}[1-9](?:" + SEP
            + "{0,3}\\d){7})(?!\\d)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /**
     * A Japanese landline as it is written, which can look like a Vietnamese mobile: area code, hyphen, exchange,
     * hyphen, 4 digits ("03-3802-3111", "092-711-4111"). An area code like 090 or 080 is a mobile, never a landline.
     */
    private static final Pattern JP_LANDLINE = Pattern.compile(
            "(?!0\\d0" + HYPHEN + ")0\\d{1,4}" + HYPHEN + "\\d{1,4}" + HYPHEN + "\\d{4}");
    /** Japanese service numbers that look like Vietnamese mobiles: navi-dial 0570 (immigration, Japan Post…). */
    private static final List<String> JP_SERVICE_PREFIXES = List.of("0570");
    /** Chat apps people move a conversation to, to scam out of sight. Cần chốt. */
    private static final String MESSENGERS = "zalo|line|telegram|tele|tg|wechat|viber|kakao(?:talk)?|whatsapp";
    private static final String CHAT_SEP = "[\\s:=\\-\\u2013\\u2014]";
    /**
     * A chat app followed by an id: "LINE ID: scam123", "line: @abc_123", "telegram @scamvn", "zalo 0901…". The id is
     * an @handle, a name with a digit or underscore (a plain word is not: "Zalo để gọi", "Yamanote line"), or a number
     * of at least 8 digits. Every repeated group is bounded: Java matches an unbounded one by recursing once per
     * repetition, and a long input would overflow the stack (StackOverflowError, a 500).
     */
    private static final Pattern CHAT_ID = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:" + MESSENGERS + ")(?![\\p{L}\\p{N}])"
                    + "(?:" + CHAT_SEP + "{0,4}id(?![\\p{L}\\p{N}]))?" + CHAT_SEP + "{0,4}"
                    + "(?:@[\\p{L}\\p{N}._]{3,}"
                    + "|[a-z](?=[a-z0-9._-]{0,31}[0-9_])[a-z0-9._-]{3,31}(?![\\p{L}\\p{N}._-])"
                    + "|[+(]{0,2}\\d(?:[\\s.\\-()]{0,3}\\d){7,14})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    /** Bumped when the Admin changes the keyword list; a cached list of an older version is read again. */
    private final AtomicLong keywordVersion = new AtomicLong();
    private volatile KeywordList keywordList;

    public ContentFilter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Runs every check on {@code text} written by {@code authorId}: keywords first, then links and contacts. */
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

    /**
     * A display name is shown on every post and comment, so a banned keyword is refused there too ("Gái gọi Shinjuku").
     * Throws 400 with the message under the name field.
     */
    public void checkDisplayName(String displayName) {
        bannedKeyword(displayName).ifPresent(keyword -> {
            String message = "Tên hiển thị chứa từ khóa không được phép “" + keyword + "”. Hãy chọn tên khác.";
            throw ApiException.invalid(message, Map.of("displayName", message));
        });
    }

    /**
     * Link and contact rule (BR-22): no shortened links for anyone; links, mobile numbers and chat-app ids only from
     * accounts with an identity tag. Throws 400 with {@link LinkProblem#message()}.
     */
    public void checkLinks(String text, long authorId) {
        linkProblem(text, authorId).ifPresent(problem -> {
            throw ApiException.badRequest(problem.message());
        });
    }

    /**
     * {@link #checkLinks} without throwing, for text that is not typed by the user (the text read from a photo or
     * video). The identity tag is looked up only when a link or contact detail was found.
     */
    public Optional<LinkProblem> linkProblem(String text, long authorId) {
        List<String> links = links(text);
        if (links.stream().anyMatch(ContentFilter::isShortened)) {
            return Optional.of(LinkProblem.SHORT_LINK);
        }
        if ((!links.isEmpty() || !contacts(text).isEmpty()) && !hasIdentityTag(authorId)) {
            return Optional.of(links.isEmpty() ? LinkProblem.CONTACT_NEEDS_TAG : LinkProblem.LINK_NEEDS_TAG);
        }
        return Optional.empty();
    }

    /** The first banned keyword found in the text, as the Admin wrote it. */
    public Optional<String> bannedKeyword(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        return findKeyword(text, keywords());
    }

    /**
     * Called when the Admin adds or deletes a keyword: the next check reads the list again. Inside a transaction the
     * list is dropped once more after it ends, so a check that read the old list meanwhile does not keep it.
     */
    public void keywordsChanged() {
        keywordVersion.incrementAndGet();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    keywordVersion.incrementAndGet();
                }
            });
        }
    }

    /** The prepared keyword list, read from the database at most once a minute (no lock held while reading). */
    private List<Keyword> keywords() {
        long version = keywordVersion.get();
        Instant now = clock.instant();
        KeywordList cached = keywordList;
        if (cached != null && cached.version() == version && !now.isBefore(cached.loadedAt())
                && now.isBefore(cached.loadedAt().plus(KEYWORD_CACHE_TTL))) {
            return cached.keywords();
        }
        List<Keyword> fresh = prepare(jdbc.query("SELECT keyword FROM banned_keywords ORDER BY id",
                (rs, i) -> rs.getString(1)));
        if (keywordVersion.get() == version) {
            keywordList = new KeywordList(fresh, now, version);
        }
        return fresh;
    }

    private record KeywordList(List<Keyword> keywords, Instant loadedAt, long version) {
    }

    /** True when the user holds at least one approved, not revoked identity tag (may post links). */
    public boolean hasIdentityTag(long userId) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM user_tags ut JOIN tag_types t ON t.id = ut.tag_type_id
                WHERE ut.user_id = ? AND ut.revoked_at IS NULL AND t.is_active""", Long.class, userId);
        return n != null && n > 0;
    }

    // ---------------------------------------------------------------- keyword matching

    /**
     * The first of {@code keywords} found in {@code text}, as the Admin wrote it. Pure (no database), so the rules
     * can be tested on their own:
     * <ul>
     *   <li>Both sides are split into words (runs of letters and digits); a keyword of several words must appear as
     *       consecutive words, whatever spaces or punctuation stand between them.</li>
     *   <li>Two words are the same when their letters without accents are equal and the accents do not tell them
     *       apart: the text word has none ("vay nong"), the keyword has none at all ("visa nhanh" was typed without
     *       accents), or both carry the same ones. So "vay nóng" does not match "vậy nóng", nor "cá độ" "cá, đồ".</li>
     *   <li>Disguises: the keyword run together as one word ("vaynong"), spelled out one character at a time
     *       ("v.a.y n.ó.n.g", "c.á đ.ộ b.ó.n.g"), or, when it has at least {@link #CONTAINS_MIN_LETTERS} letters,
     *       inside a longer word ("tuyenviecnheluongcaongay").</li>
     *   <li>Both sides are read through {@link #keywordCopy}: fullwidth and look-alike letters, "v4y", "v@y".</li>
     *   <li>A keyword in Japanese or Chinese (no spaces between words) is found anywhere in the text.</li>
     *   <li>A keyword standing only inside an everyday phrase ({@link #EVERYDAY_PHRASES}: "có bác", "bạn gái gọi") is
     *       not counted ({@link Everyday}).</li>
     * </ul>
     */
    static Optional<String> findKeyword(String text, List<Keyword> keywords) {
        if (text == null || text.isBlank() || keywords.isEmpty()) {
            return Optional.empty();
        }
        Words words = Words.of(text);
        Everyday everyday = Everyday.in(words);
        for (Keyword keyword : keywords) {
            if (keyword.foundIn(words, everyday)) {
                return Optional.of(keyword.text());
            }
        }
        return Optional.empty();
    }

    /**
     * Where the everyday phrases ({@link #EVERYDAY_PHRASES}) stand in a text: ranges of words, and of characters in
     * the words run together (for Japanese and Chinese). A phrase written as it is ("bạn gái gọi") always counts; one
     * found only because accents were left out ("co bac" for "có bác", "bầu cua" for "bầu của") counts only when the
     * whole text is typed without accents: someone who writes with accents and leaves them out of just "co bac" is
     * hiding "cờ bạc".
     */
    record Everyday(List<int[]> wordRanges, List<int[]> joinedRanges) {

        static Everyday in(Words text) {
            List<int[]> wordRanges = new ArrayList<>();
            List<int[]> joinedRanges = new ArrayList<>();
            for (Keyword phrase : EVERYDAY) {
                if (phrase.unspaced()) {
                    for (int at = text.joined().indexOf(phrase.joined()); at >= 0;
                         at = text.joined().indexOf(phrase.joined(), at + 1)) {
                        joinedRanges.add(new int[]{at, at + phrase.joined().length()});
                    }
                    continue;
                }
                int n = phrase.words().size();
                for (int start : phrase.starts(text.words())) {
                    boolean asWritten = true;
                    for (int j = 0; j < n && asWritten; j++) {
                        asWritten = phrase.words().get(j).exact().equals(text.words().get(start + j).exact());
                    }
                    if (asWritten || !text.anyMarked()) {
                        wordRanges.add(new int[]{start, start + n});
                    }
                }
            }
            return new Everyday(wordRanges, joinedRanges);
        }

        /** True when the words {@code [from, to)} lie inside one everyday phrase. */
        boolean coversWords(int from, int to) {
            return wordRanges.stream().anyMatch(r -> r[0] <= from && to <= r[1]);
        }

        /** True when the characters {@code [from, to)} of the joined words lie inside one everyday phrase. */
        boolean coversJoined(int from, int to) {
            return joinedRanges.stream().anyMatch(r -> r[0] <= from && to <= r[1]);
        }
    }

    /** Keywords as {@link #findKeyword} needs them; one without any letter or digit is left out. */
    static List<Keyword> prepare(Collection<String> keywords) {
        return keywords.stream().map(Keyword::of).flatMap(Optional::stream).toList();
    }

    /**
     * One word as the keyword filter compares it: as written (lower case), without accents, whether it had any, and
     * whether it was spelled out one character at a time ("v.a.y" read as "vay").
     */
    record Word(String exact, String folded, boolean marked, boolean spelled) {

        static Word of(String token, boolean spelled) {
            String word = leet(token);
            if (word.chars().allMatch(c -> c < 0x80)) {
                return new Word(word, word, false, spelled);        // plain ASCII (already lower case): no accents
            }
            // Japanese voicing marks stay: "バイト" is not "ハイト"
            return new Word(word, VietnameseText.foldKeepingKanaMarks(word), VietnameseText.hasDiacritics(word),
                    spelled);
        }
    }

    /**
     * The words of a text: as split by spaces and punctuation ({@code words}), the same with every run of single
     * characters joined into one spelled word ({@code glued}: "v.a.y nóng" → "vay", "nóng"; glued word {@code g} is
     * words {@code [gluedFrom[g], gluedTo[g])}), all words run together ({@code joined}) for keywords in Japanese or
     * Chinese, and whether any word carries accents ({@code anyMarked}).
     */
    record Words(List<Word> words, List<Word> glued, int[] gluedFrom, int[] gluedTo, String joined, boolean anyMarked) {

        static Words of(String text) {
            List<String> tokens = new ArrayList<>();
            Matcher m = WORD.matcher(keywordCopy(text));
            while (m.find()) {
                tokens.add(m.group());
            }
            List<Word> words = new ArrayList<>(tokens.size());
            StringBuilder joined = new StringBuilder();
            for (String token : tokens) {
                Word word = Word.of(token, false);
                words.add(word);
                joined.append(word.exact());
            }
            List<Word> glued = new ArrayList<>();
            int[] gluedFrom = new int[tokens.size()];
            int[] gluedTo = new int[tokens.size()];
            int i = 0;
            while (i < tokens.size()) {
                int j = i;
                while (j < tokens.size() && isSingle(tokens.get(j))) {
                    j++;
                }
                gluedFrom[glued.size()] = i;
                if (j - i >= 2) {
                    glued.add(Word.of(String.join("", tokens.subList(i, j)), true));
                    i = j;
                } else {
                    glued.add(words.get(i));
                    i++;
                }
                gluedTo[glued.size() - 1] = i;
            }
            return new Words(words, glued, gluedFrom, gluedTo, joined.toString(),
                    words.stream().anyMatch(Word::marked));
        }
    }

    /**
     * A banned keyword ready for {@link #findKeyword}: the Admin's text, its words, its letters run together without
     * accents ({@code squashed}) and as written ({@code joined}), whether it carries accents, and whether it is
     * written in a script without spaces.
     */
    record Keyword(String text, List<Word> words, String squashed, String joined, int letters, boolean marked,
                   boolean unspaced) {

        static Optional<Keyword> of(String keyword) {
            if (keyword == null) {
                return Optional.empty();
            }
            List<Word> words = Words.of(keyword).words();
            if (words.isEmpty()) {
                return Optional.empty();
            }
            String squashed = words.stream().map(Word::folded).collect(Collectors.joining());
            String joined = words.stream().map(Word::exact).collect(Collectors.joining());
            return Optional.of(new Keyword(keyword, words, squashed, joined,
                    (int) squashed.codePoints().filter(Character::isLetter).count(),
                    words.stream().anyMatch(Word::marked),
                    joined.codePoints().anyMatch(cp -> UNSPACED_SCRIPTS.contains(Character.UnicodeScript.of(cp)))));
        }

        boolean foundIn(Words in, Everyday everyday) {
            if (unspaced) {
                for (int at = in.joined().indexOf(joined); at >= 0; at = in.joined().indexOf(joined, at + 1)) {
                    if (!everyday.coversJoined(at, at + joined.length())) {
                        return true;
                    }
                }
                return false;
            }
            int n = words.size();
            for (int start : starts(in.words())) {
                if (!everyday.coversWords(start, start + n)) {
                    return true;
                }
            }
            for (int start : starts(in.glued())) {
                if (!everyday.coversWords(in.gluedFrom()[start], in.gluedTo()[start + n - 1])) {
                    return true;
                }
            }
            for (Word word : in.glued()) {
                // Written as one word ("vaynong"), spelled out ("c.á đ.ộ b.ó.n.g") or inside a longer word
                if (words.size() > 1 && word.folded().equals(squashed)
                        || (word.spelled() || letters >= CONTAINS_MIN_LETTERS) && word.folded().contains(squashed)) {
                    return true;
                }
            }
            return false;
        }

        /** Every place in {@code text} where the keyword's words stand one after another, by its first word. */
        List<Integer> starts(List<Word> text) {
            List<Integer> starts = new ArrayList<>();
            int n = words.size();
            for (int i = 0; i + n <= text.size(); i++) {
                int j = 0;
                while (j < n && same(words.get(j), text.get(i + j))) {
                    j++;
                }
                if (j == n) {
                    starts.add(i);
                }
            }
            return starts;
        }

        /**
         * Same letters once accents are removed, and the accents do not tell them apart: the text word has none, the
         * keyword has none at all, or both carry the same ones.
         */
        private boolean same(Word keywordWord, Word textWord) {
            return keywordWord.folded().equals(textWord.folded())
                    && (!marked || !textWord.marked() || keywordWord.exact().equals(textWord.exact()));
        }
    }

    /** One letter or digit, with its accents ("ộ" counts as one). */
    private static boolean isSingle(String token) {
        return token.codePoints().filter(cp -> !isMark(cp)).count() == 1;
    }

    private static boolean isMark(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK;
    }

    /** "v4y" → "vay", "n0ng" → "nong"; a word without letters ("1.000", "2026") or without digits is kept. */
    static String leet(String word) {
        boolean letter = false;
        boolean digit = false;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c >= '0' && c <= '9') {
                digit = true;
            } else if (Character.isLetter(c)) {
                letter = true;
            }
        }
        if (!letter || !digit) {
            return word;
        }
        StringBuilder b = new StringBuilder(word.length());
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            int k = LEET_DIGITS.indexOf(c);
            b.append(k >= 0 ? LEET_LETTERS.charAt(k) : c);
        }
        return b.toString();
    }

    /** {@link #unmask(String)} in lower case, with "@" and "$" next to a letter read as "a" and "s" ("v@y"). */
    static String keywordCopy(String text) {
        char[] c = unmask(text).toCharArray();
        for (int i = 0; i < c.length; i++) {
            if ((c[i] == '@' || c[i] == '$')
                    && (i > 0 && Character.isLetter(c[i - 1]) || i + 1 < c.length && Character.isLetter(c[i + 1]))) {
                c[i] = c[i] == '@' ? 'a' : 's';
            }
        }
        return new String(c).toLowerCase(Locale.ROOT);
    }

    /**
     * The copy of a text that the checks read: compatibility forms undone (fullwidth "ｖａｙ" → "vay", "①" → "1"),
     * invisible format characters (zero-width space, soft hyphen) removed, and Cyrillic or Greek look-alikes read as
     * Latin letters. Case and Vietnamese accents are kept.
     */
    static String unmask(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD);
        StringBuilder b = new StringBuilder(decomposed.length());
        decomposed.codePoints().forEach(cp -> {
            if (Character.getType(cp) == Character.FORMAT) {
                return;
            }
            Character latin = cp <= Character.MAX_VALUE ? LOOKALIKES.get((char) cp) : null;
            if (latin != null) {
                b.append(latin.charValue());
            } else {
                b.appendCodePoint(cp);
            }
        });
        return Normalizer.normalize(b, Normalizer.Form.NFC);
    }

    // ---------------------------------------------------------------- link and contact detection

    /**
     * Links in the text: full addresses ("https://…", "www.…", whatever their letters) and bare "name.ending" with a
     * known ending ({@link #TLDS}, {@link #ANY_CASE_TLDS}) or a shortener's domain, made of plain ASCII labels
     * ({@link #BARE_HOST_LABEL}). Disguised dots before a known ending are read as dots ("abc[.]com", "abc chấm vn",
     * "bit . ly/x"; see {@link #WORD_ENDINGS}). Returns the links as read, so {@link #isShortened} can check them.
     */
    static List<String> links(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String copy = WORDED_DOT.matcher(BRACKETED_DOT.matcher(unmask(text)).replaceAll(".")).replaceAll(".");
        Matcher m = URL_OR_HOST.matcher(copy);
        List<String> found = new ArrayList<>();
        while (m.find()) {
            if (m.group("url") != null) {
                found.add(m.group("url"));
            } else {
                bareHosts(copy, m.start("host"), m.end("host"), found);
            }
        }
        return found;
    }

    /**
     * The bare domains in a run of dotted words. A label with other letters (Vietnamese "nhà", "hỏi") cannot be part of
     * one, so the run is split there: "hỏi.in" has none, "nhà.abc.com" has "abc.com".
     */
    private static void bareHosts(String copy, int start, int end, List<String> found) {
        String[] labels = copy.substring(start, end).split("\\.");
        int from = 0;
        for (int i = 0; i <= labels.length; i++) {
            if (i == labels.length || !BARE_HOST_LABEL.matcher(labels[i]).matches()) {
                boolean runEnd = i == labels.length;
                host(Arrays.copyOfRange(labels, from, i), copy, runEnd ? end : -1).ifPresent(found::add);
                from = i + 1;
            }
        }
    }

    /**
     * The link in dotted ASCII labels ("mercari.jp", "abc.com.Sau" → "abc.com"), with its path when the labels end
     * where {@code end} says (-1: not at the end of the run, so no path).
     */
    private static Optional<String> host(String[] labels, String copy, int end) {
        boolean pathFollows = end >= 0 && end < copy.length() && copy.charAt(end) == '/';
        int last = -1;
        for (int i = 1; i < labels.length; i++) {
            if (endsLink(labels[i], pathFollows && i == labels.length - 1)) {
                last = i;
            }
        }
        for (int i = 1; i < Math.min(labels.length, SHORTENER_SCAN_LABELS); i++) {
            if (isShortened(String.join(".", Arrays.copyOf(labels, i + 1)))) {
                last = Math.max(last, i);
            }
        }
        if (last < 0) {
            return Optional.empty();
        }
        String link = String.join(".", Arrays.copyOf(labels, last + 1));
        if (last == labels.length - 1 && pathFollows) {
            int stop = end;
            while (stop < copy.length() && !Character.isWhitespace(copy.charAt(stop))) {
                stop++;
            }
            link += copy.substring(end, stop);
        }
        return Optional.of(link);
    }

    private static boolean endsLink(String label, boolean beforePath) {
        String lower = label.toLowerCase(Locale.ROOT);
        return TLDS.contains(lower) && (ANY_CASE_TLDS.contains(lower) || label.equals(lower) || beforePath);
    }

    /**
     * True when the link leads to a shortener or to a subdomain of one ("www.bit.ly"). The host is read the way a
     * browser reads it, so "https://user@bit.ly:443/x", "https://bit.ly./x" and "BIT.LY/x" are all bit.ly.
     */
    static boolean isShortened(String link) {
        String host = link.toLowerCase(Locale.ROOT).replaceFirst("^[a-z][a-z0-9+.-]*://", "");
        host = host.split("[/?#\\\\]", 2)[0];                      // up to the path, query or fragment
        host = host.substring(host.lastIndexOf('@') + 1);          // without the user info
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);                       // without the port
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);           // "bit.ly." is bit.ly
        }
        for (String domain = host; !domain.isEmpty(); domain = domain.substring(domain.indexOf('.') + 1)) {
            if (NOT_SHORTENED.contains(domain)) {
                return false;
            }
            if (SHORTENERS.contains(domain)) {
                return true;
            }
            if (domain.indexOf('.') < 0) {
                break;
            }
        }
        return false;
    }

    /**
     * Contact details in the text: Vietnamese and Japanese mobile numbers (spaces, dots and dashes between the
     * digits are allowed, "+84 901 234 567", "090-1234-5678") and chat-app ids ({@link #CHAT_ID}). Japanese landlines
     * written with hyphens ("03-3802-3111"), navi-dial and toll-free numbers are not contact details.
     */
    static List<String> contacts(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String copy = unmask(text);
        List<String> found = new ArrayList<>();
        Matcher m = VN_MOBILE.matcher(copy);
        while (m.find()) {
            if (!landlineOrService(m.group("number"))) {
                found.add(m.group());
            }
        }
        for (Pattern pattern : List.of(JP_MOBILE, CHAT_ID)) {
            m = pattern.matcher(copy);
            while (m.find()) {
                found.add(m.group());
            }
        }
        return found;
    }

    /** A number shaped like a Vietnamese mobile that is a Japanese landline or service number instead. */
    private static boolean landlineOrService(String number) {
        String digits = number.replaceAll("\\D", "");
        if (!digits.startsWith("0")) {
            return false;                       // +84 / 84: a Vietnamese mobile for sure
        }
        return JP_SERVICE_PREFIXES.stream().anyMatch(digits::startsWith) || JP_LANDLINE.matcher(number).matches();
    }

    // ---------------------------------------------------------------- helpers

    private static Map<Character, Character> lookalikes(String... fromAndTo) {
        Map<Character, Character> map = new HashMap<>();
        for (int i = 0; i < fromAndTo.length; i += 2) {
            String from = fromAndTo[i];
            String to = fromAndTo[i + 1];
            if (from.length() != to.length()) {
                throw new IllegalStateException("Look-alike lists differ in length: " + to);
            }
            for (int k = 0; k < from.length(); k++) {
                map.put(from.charAt(k), to.charAt(k));
            }
        }
        return Map.copyOf(map);
    }

    /** "com|net|…", longest first. */
    private static String alternatives(Set<String> endings) {
        return endings.stream().sorted(Comparator.comparingInt(String::length).reversed().thenComparing(s -> s))
                .collect(Collectors.joining("|"));
    }
}
