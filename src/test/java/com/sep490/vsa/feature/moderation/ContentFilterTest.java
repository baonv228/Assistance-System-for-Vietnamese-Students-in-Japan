package com.sep490.vsa.feature.moderation;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.text.VietnameseText;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentFilterTest {

    /** The keywords seeded with the database (V1__init_schema.sql). */
    private static final List<ContentFilter.Keyword> SEEDED =
            ContentFilter.prepare(List.of("vay nóng", "cá độ", "visa nhanh", "việc nhẹ lương cao"));

    private static Optional<String> keyword(String text) {
        return ContentFilter.findKeyword(text, SEEDED);
    }

    // ---------------------------------------------------------------- banned keywords (MSG36)

    @Test
    void harmlessTextIsNotTakenForABannedKeyword() {
        assertThat(keyword("Mua cá đông lạnh ở Gyomu rẻ lắm")).isEmpty();
        assertThat(keyword("Hôm nay ăn cá, đồ uống thì mua ở konbini")).isEmpty();
        assertThat(keyword("Sao hôm nay vậy nóng thế")).isEmpty();
        assertThat(keyword("Cà độc dược")).isEmpty();
        assertThat(keyword("Giá 1.000 yên")).isEmpty();
    }

    @Test
    void bannedKeywordsAreFoundHoweverTheyAreWritten() {
        List<String> vayNong = List.of("Cho vay nóng lãi thấp", "VAY NONG", "vay nong", "vay  nóng", "vay-nóng",
                "v.a.y n.ó.n.g", "vaynong", "v4y n0ng",
                "\uFF56\uFF41\uFF59 \uFF4Eóng",       // fullwidth "ｖａｙ ｎóng"
                "v\u0430y nóng",                              // Cyrillic а
                "v@y nóng", "va\u200By nóng");                // "@" for "a", a zero-width space inside
        for (String text : vayNong) {
            assertThat(keyword(text)).as(text).contains("vay nóng");
        }
        for (String text : List.of("cá độ bóng đá", "ca do bong da", "c.á đ.ộ b.ó.n.g đ.á")) {
            assertThat(keyword(text)).as(text).contains("cá độ");
        }
        for (String text : List.of("Tuyển việc nhẹ, lương-cao", "viecnheluongcao", "tuyenviecnheluongcaongay")) {
            assertThat(keyword(text)).as(text).contains("việc nhẹ lương cao");
        }
    }

    @Test
    void visaNhanhInsideAnOrdinarySentenceStillMatchesBecauseItIsTheAdminsPhrase() {
        // "visa nhanh" is in the sentence word for word: the Admin chose the phrase, the filter does not guess intent
        assertThat(keyword("Cách gia hạn visa nhanh nhất")).contains("visa nhanh");
    }

    @Test
    void accentsTellWordsApartOnlyWhenBothSidesHaveThem() {
        // A keyword typed without any accent matches every accented form
        assertThat(ContentFilter.findKeyword("Sao hôm nay vậy nóng thế", ContentFilter.prepare(List.of("vay nong"))))
                .contains("vay nong");
        // One accented word: "lúa" (rice) is not "lừa", but "lua" typed without accents may be
        List<ContentFilter.Keyword> lua = ContentFilter.prepare(List.of("lừa"));
        assertThat(ContentFilter.findKeyword("Gạo lúa mới", lua)).isEmpty();
        assertThat(ContentFilter.findKeyword("bi lua roi", lua)).contains("lừa");
        assertThat(VietnameseText.hasDiacritics("vậy")).isTrue();
        assertThat(VietnameseText.hasDiacritics("đi")).isTrue();
        assertThat(VietnameseText.hasDiacritics("vay")).isFalse();
    }

    @Test
    void leetspeakIsReadOnlyInsideWordsThatMixLettersAndDigits() {
        assertThat(ContentFilter.leet("n0ng")).isEqualTo("nong");
        assertThat(ContentFilter.leet("v4y")).isEqualTo("vay");
        assertThat(ContentFilter.leet("1000")).isEqualTo("1000");
        assertThat(ContentFilter.leet("2026")).isEqualTo("2026");
        assertThat(ContentFilter.leet("yên")).isEqualTo("yên");
    }

    @Test
    void japaneseKeywordsAreFoundWithoutSpaces() {
        List<ContentFilter.Keyword> yami = ContentFilter.prepare(List.of("闇バイト"));
        assertThat(ContentFilter.findKeyword("高収入の闇バイト募集", yami)).contains("闇バイト");
        assertThat(ContentFilter.findKeyword("コンビニのバイト募集", yami)).isEmpty();
    }

    @Test
    void japaneseVoicingMarksAreNotAccents() {
        List<ContentFilter.Keyword> baito = ContentFilter.prepare(List.of("バイト"));
        assertThat(ContentFilter.findKeyword("ハイト", baito)).isEmpty();
        assertThat(ContentFilter.findKeyword("バイト", baito)).contains("バイト");
        assertThat(ContentFilter.findKeyword("ﾊﾞｲﾄ募集", baito)).contains("バイト");          // halfwidth katakana
        List<ContentFilter.Keyword> pan = ContentFilter.prepare(List.of("パン"));
        assertThat(ContentFilter.findKeyword("ハンバーガー", pan)).isEmpty();
        assertThat(ContentFilter.findKeyword("バンを借りる", pan)).isEmpty();
        assertThat(ContentFilter.findKeyword("パンを買う", pan)).contains("パン");
        // A word whose only marks are dakuten/handakuten has no Vietnamese diacritics but is still compared exactly
        ContentFilter.Word word = ContentFilter.Word.of("バイト", false);
        assertThat(word.marked()).isFalse();
        assertThat(word.folded()).isEqualTo("バイト").isNotEqualTo(ContentFilter.Word.of("ハイト", false).folded());
        assertThat(ContentFilter.Word.of("パン", false).folded()).isNotEqualTo(ContentFilter.Word.of("バン", false).folded());
        assertThat(VietnameseText.hasDiacritics("パン")).isFalse();
        assertThat(VietnameseText.foldKeepingKanaMarks("Việc BÀN パン")).isEqualTo("viec ban パン");
        // fold (search) is unchanged
        assertThat(VietnameseText.fold("バイト")).isEqualTo(VietnameseText.fold("ハイト"));
    }

    @Test
    void keywordsWithoutLettersAreIgnored() {
        assertThat(ContentFilter.prepare(List.of("!!!", " ", "vay nóng"))).hasSize(1);
        assertThat(ContentFilter.findKeyword("vay nóng", List.of())).isEmpty();
        assertThat(keyword("   ")).isEmpty();
        assertThat(keyword(null)).isEmpty();
    }

    // ---------------------------------------------------------------- links (BR-22)

    @Test
    void findsLinks() {
        assertThat(ContentFilter.links("Xem https://example.com/a?b=1 nhé")).containsExactly("https://example.com/a?b=1");
        assertThat(ContentFilter.links("vào www.google.co.jp xem")).hasSize(1);
        assertThat(ContentFilter.links("trang mercari.jp bán đồ cũ")).containsExactly("mercari.jp");
    }

    @Test
    void ordinaryTextIsNotALink() {
        assertThat(ContentFilter.links("Cơm phần 1.000 yên, mua vé ở máy. Ga Nippori v.v.")).isEmpty();
        assertThat(ContentFilter.links("Tầng 1.5 ở toà nhà")).isEmpty();
        for (String text : List.of("ga Ueno.Sau đó", "TP.HCM", "Q.1", "Ga Nippori v.v.", "ga Ueno.In vé ở máy",
                "Quán này ngon.Top 1 luôn", "Lịch nghỉ T7.CN", "hàng Nhật.VN không có", "Xong rồi. com ngon lắm",
                "polka dot top")) {
            assertThat(ContentFilter.links(text)).as(text).isEmpty();
        }
    }

    @Test
    void vietnameseSentencesWithChamOrAMissingSpaceAreNotLinks() {
        for (String text : List.of("Cô giáo chấm so với năm ngoái thì khó hơn", "Dấu chấm to quá", "Tôi chấm in bài",
                "Cô chấm my bài", "giá rẻ hơn.so với tuần trước", "ăn ở nhà.me nấu", "cho em hỏi.in ở đâu",
                "đi chợ.to lắm", "Thầy Nam chấm so với năm ngoái", "Linh chấm to hơn", "dấu chấm in đậm",
                "gửi mình . link nhé", "Nam chấm online cho lớp")) {
            assertThat(ContentFilter.links(text)).as(text).isEmpty();
        }
    }

    @Test
    void bareDomainsAreMadeOfAsciiLabelsFullAddressesOfAnyLetters() {
        String cyrillicScam = new String(new int[] {0x0455, 0x0441, 0x0430, 'm'}, 0, 4) + ".com";     // "ѕсаm.com"
        assertThat(ContentFilter.links(cyrillicScam)).containsExactly("scam.com");
        assertThat(ContentFilter.links("ｓｃａｍ.com")).containsExactly("scam.com");                   // fullwidth
        // A Vietnamese word before the dot is a sentence missing a space; the ASCII part after it can still be a domain
        assertThat(ContentFilter.links("nhà.abc.com")).containsExactly("abc.com");
        assertThat(ContentFilter.links("nhậnvisa.com")).isEmpty();
        assertThat(ContentFilter.links("Xem https://nhậnvisa.com/x nhé")).containsExactly("https://nhậnvisa.com/x");
        assertThat(ContentFilter.links("www.nhậnvisa.com")).hasSize(1);
    }

    @Test
    void findsNewEndingsAndDisguisedDots() {
        for (String text : List.of("shop-uy-tin.vip/abc", "nhanvisa.online", "bom.so/x", "linktr.ee/x", "abc[.]com",
                "bit . ly/abc", "zalo chấm me/0901", "abc(.)com", "abc . com", "abc dot com", "abc chấm com",
                "abc chấm vn", "abc[.]me", "abc(dot)so", "abc{.}in", "nhanvisa chấm io", "abc cham net",
                "abc chấm com chấm vn", "ABC.COM", "nhanvisa.co", "t.me/scamvn", "ａｂｃ．ｃｏｍ")) {
            assertThat(ContentFilter.links(text)).as(text).isNotEmpty();
        }
        assertThat(ContentFilter.links("bit . ly/abc")).containsExactly("bit.ly/abc");
        assertThat(ContentFilter.links("zalo chấm me/0901")).containsExactly("zalo.me/0901");
        assertThat(ContentFilter.links("abc chấm com chấm vn")).containsExactly("abc.com.vn");
        // A dotted run longer than one match (21 labels) is read in pieces: the domain at its end is still found
        assertThat(ContentFilter.links("a.".repeat(30) + "scam.com/x")).singleElement().asString().endsWith("scam.com/x");
    }

    @Test
    void recognisesShortenedLinks() {
        assertThat(ContentFilter.isShortened("https://bit.ly/abc")).isTrue();
        assertThat(ContentFilter.isShortened("tinyurl.com/x")).isTrue();
        assertThat(ContentFilter.isShortened("https://www.city.arakawa.tokyo.jp/")).isFalse();
        // youtu.be only opens YouTube, a Google Maps share link only opens Google Maps: allowed
        assertThat(ContentFilter.isShortened("https://youtu.be/abc")).isFalse();
        assertThat(ContentFilter.isShortened("https://maps.app.goo.gl/AbC123")).isFalse();
        assertThat(ContentFilter.links("Quán ở maps.app.goo.gl/AbC123 nhé"))
                .singleElement().satisfies(link -> assertThat(ContentFilter.isShortened(link)).isFalse());
        // The host is read like a browser reads it: user info, port, trailing dot, case, subdomains
        for (String link : List.of("https://bit.ly:443/x", "https://user@bit.ly/x", "https://bit.ly./x",
                "HTTPS://BIT.LY/x", "https://www.bit.ly/x", "https://x.bit.ly/abc", "https://bit.ly?x=1",
                "https://bit.ly#x", "https://a:b@goo.gl:80/x", "x.short.gy/abc")) {
            assertThat(ContentFilter.isShortened(link)).as(link).isTrue();
        }
        for (String link : List.of("https://bit.ly.example.com/x", "https://notbit.ly/x", "https://shopee.vn/x",
                "https://example.com/?u=bit.ly", "https://example.com/bit.ly")) {
            assertThat(ContentFilter.isShortened(link)).as(link).isFalse();
        }
        // Found in a sentence as well, and refused as a short link
        for (String text : List.of("Xem https://user@bit.ly/x nhé", "Xem https://bit.ly:443/x", "Xem https://bit.ly./x",
                "Xem x.short.gy/abc nhé")) {
            assertThat(ContentFilter.links(text)).as(text).anySatisfy(link -> assertThat(ContentFilter.isShortened(link)).isTrue());
        }
        // Every shortener is found as a link in a sentence and refused, even with an ending not in TLDS ("short.gy")
        for (String shortener : ContentFilter.SHORTENERS) {
            assertThat(ContentFilter.links("Xem " + shortener + "/abc nhé")).as(shortener)
                    .singleElement().satisfies(link -> assertThat(ContentFilter.isShortened(link)).isTrue());
        }
    }

    // ---------------------------------------------------------------- contact details (BR-22)

    @Test
    void findsMobileNumbersAndChatIds() {
        for (String text : List.of("ib zalo 0901 234 567", "Zalo: 0901.234.567", "+84 901 234 567", "84901234567",
                "090-1234-5678", "080 1234 5678", "liên hệ 0352345678", "LINE ID: scam123", "line: @abc_123",
                "telegram @scamvn", "tele: @scamvn", "sđt0901234567", "(+84) 901 234 567", "+81 90-1234-5678",
                "090-123-4567", "zalo: 092-711-4111", "０９０１２３４５６７")) {
            assertThat(ContentFilter.contacts(text)).as(text).isNotEmpty();
        }
    }

    @Test
    void landlinesPricesCodesAndOrdinaryWordsAreNotContactDetails() {
        for (String text : List.of("Văn phòng quận: 03-3802-3111", "0120-123-456", "Giá 1.200.000 đồng",
                "Mã bưu điện 116-0013", "Năm 2026 tháng 10", "Mã đơn 12345678901234", "Mình dùng Zalo để gọi về nhà",
                "Đi tuyến Yamanote line", "line 3 ga Ueno", "Tòa thị chính Fukuoka 092-711-4111",
                "Cục xuất nhập cảnh 0570-013904", "Gọi miễn phí 0800-123-4567", "tg: 10h30 sáng", "online 24/7")) {
            assertThat(ContentFilter.contacts(text)).as(text).isEmpty();
        }
    }

    // ---------------------------------------------------------------- messages, identity tag, cache

    @Test
    void linksAndContactsNeedAnIdentityTagAndShortLinksNeverPass() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(1L))).thenReturn(0L);     // no tag
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(2L))).thenReturn(1L);     // Mentor
        ContentFilter filter = new ContentFilter(jdbc, Clock.systemUTC());

        assertThatThrownBy(() -> filter.checkLinks("ib zalo 0901 234 567", 1)).isInstanceOf(ApiException.class)
                .hasMessage("Chỉ tài khoản đã có tag định danh mới được ghi số điện thoại hoặc tài khoản Zalo, LINE, Telegram.");
        assertThatThrownBy(() -> filter.checkLinks("Xem nhanvisa.online", 1))
                .hasMessage("Chỉ tài khoản đã có tag định danh mới được chèn link.");
        assertThatThrownBy(() -> filter.checkLinks("bit . ly/abc", 2))
                .hasMessage("Không dùng link rút gọn (bit.ly, tinyurl…). Hãy dán link đầy đủ.");
        assertThatCode(() -> filter.checkLinks("ib zalo 0901 234 567, LINE ID: scam123", 2)).doesNotThrowAnyException();
        assertThatCode(() -> filter.checkLinks("Văn phòng quận: 03-3802-3111", 1)).doesNotThrowAnyException();
    }

    @Test
    void theKeywordListIsReadOnceAMinuteAndRightAfterTheAdminChangesIt() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doReturn(List.of("vay nóng")).when(jdbc).query(anyString(), any(RowMapper.class));
        MovableClock clock = new MovableClock();
        ContentFilter filter = new ContentFilter(jdbc, clock);

        assertThat(filter.bannedKeyword("vay nong")).contains("vay nóng");
        assertThat(filter.bannedKeyword("Mua cá đông lạnh")).isEmpty();
        assertThatThrownBy(() -> filter.checkKeywords("Cho vay-nóng"))
                .hasMessage("Chưa đăng được: nội dung chứa từ khóa không được phép “vay nóng”. Hãy sửa rồi gửi lại.");
        verify(jdbc, times(1)).query(anyString(), any(RowMapper.class));

        clock.advance(Duration.ofSeconds(59));
        filter.bannedKeyword("abc");
        verify(jdbc, times(1)).query(anyString(), any(RowMapper.class));
        clock.advance(Duration.ofSeconds(2));
        filter.bannedKeyword("abc");
        verify(jdbc, times(2)).query(anyString(), any(RowMapper.class));

        // The Admin adds a keyword: the very next check reads the list again
        doReturn(List.of("vay nóng", "cá độ")).when(jdbc).query(anyString(), any(RowMapper.class));
        filter.keywordsChanged();
        assertThat(filter.bannedKeyword("ca do bong da")).contains("cá độ");
        verify(jdbc, times(3)).query(anyString(), any(RowMapper.class));
        // A blank text needs no list
        filter.keywordsChanged();
        assertThat(filter.bannedKeyword("  ")).isEmpty();
        verify(jdbc, times(3)).query(anyString(), any(RowMapper.class));
    }

    // ---------------------------------------------------------------- long input

    @Test
    void longInputNeitherOverflowsTheStackNorTakesLong() {
        // About 20,000 characters each (4 × the content limit), checked on a 1 MB stack like a request thread: no
        // pattern may recurse once per repetition (StackOverflowError → 500) or backtrack its way through them
        List<String> inputs = List.of("a.".repeat(10_000), "1 ".repeat(10_000), "0 9 ".repeat(5_000),
                "a-".repeat(10_000), "0.".repeat(10_000), "a . ".repeat(5_000), "zalo ".repeat(4_000),
                "zalo " + "1 ".repeat(10_000), "line: " + "0 9 ".repeat(5_000), "tele @" + "a.".repeat(10_000),
                "v.a.y ".repeat(3_334), "@a".repeat(10_000), "www.".repeat(5_000), "a chấm ".repeat(2_858),
                "09 ".repeat(6_667), "a".repeat(20_000));
        for (String input : inputs) {
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                long nanos = onOneMegabyteStack(() -> {
                    for (int i = 0; i < 3; i++) {
                        runAllChecks(input);                           // warm up first (JIT)
                    }
                    long start = System.nanoTime();
                    runAllChecks(input);
                    return System.nanoTime() - start;
                });
                assertThat(Duration.ofNanos(nanos)).as("time on " + input.substring(0, 12) + "…")
                        .isLessThan(Duration.ofMillis(100));
            });
        }
    }

    @Test
    void fiveThousandCharactersOfDottedTextDoNotOverflowTheStack() throws Exception {
        // The old link pattern recursed once per ".a" and threw StackOverflowError here
        assertThat(onOneMegabyteStack(() -> ContentFilter.links("a.".repeat(2_500)))).isEmpty();
        assertThat(onOneMegabyteStack(() -> ContentFilter.contacts("zalo " + "1 ".repeat(2_500)))).hasSize(1);
    }

    private static void runAllChecks(String text) {
        ContentFilter.links(text);
        ContentFilter.contacts(text);
        ContentFilter.findKeyword(text, SEEDED);
    }

    /** Runs {@code work} on a thread with a 1 MB stack (a server request thread) and returns its result. */
    private static <T> T onOneMegabyteStack(Callable<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(null, () -> {
            try {
                result.set(work.call());
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "one-megabyte-stack", 1L << 20);
        thread.setDaemon(true);
        thread.start();
        thread.join();
        if (failure.get() != null) {
            throw new AssertionError("failed on a 1 MB stack", failure.get());
        }
        return result.get();
    }

    /** A clock the test moves forward by hand. */
    private static final class MovableClock extends Clock {

        private Instant now = Instant.parse("2026-10-20T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
