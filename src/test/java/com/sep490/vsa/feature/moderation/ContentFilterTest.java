package com.sep490.vsa.feature.moderation;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.text.VietnameseText;
import com.sep490.vsa.feature.moderation.ContentFilter.LinkProblem;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // ---------------------------------------------------------------- prostitution and gambling lists (V5, V6)

    /** The keywords a migration inserts into banned_keywords, as written there. */
    private static List<String> migrationKeywords(String file) throws Exception {
        try (var in = ContentFilterTest.class.getResourceAsStream("/db/migration/" + file)) {
            assertThat(in).as(file + " on the classpath").isNotNull();
            String sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.strip().startsWith("--"))
                    .collect(java.util.stream.Collectors.joining("\n"));
            Matcher m = Pattern.compile("\\('([^']+)'\\)").matcher(sql.substring(sql.indexOf("VALUES")));
            List<String> keywords = new ArrayList<>();
            while (m.find()) {
                keywords.add(m.group(1));
            }
            return keywords;
        }
    }

    private static List<String> v5Keywords() throws Exception {
        return migrationKeywords("V5__prostitution_gambling_keywords.sql");
    }

    private static List<String> v6Keywords() throws Exception {
        return migrationKeywords("V6__general_prostitution_gambling_keywords.sql");
    }

    /** Every keyword the database starts with, in id order (V1, V5, V6), as the server checks a post. */
    private static List<ContentFilter.Keyword> allSeeded() throws Exception {
        List<String> all = new ArrayList<>(List.of("vay nóng", "cá độ", "visa nhanh", "việc nhẹ lương cao"));
        all.addAll(v5Keywords());
        all.addAll(v6Keywords());
        return ContentFilter.prepare(all);
    }

    @Test
    void theProstitutionAndGamblingListsAreUsableAsAdminKeywords() throws Exception {
        List<String> keywords = new ArrayList<>(v5Keywords());
        assertThat(keywords).hasSizeGreaterThan(80);
        assertThat(v6Keywords()).hasSizeGreaterThan(45);
        keywords.addAll(v6Keywords());
        List<ContentFilter.Keyword> prepared = ContentFilter.prepare(keywords);
        assertThat(prepared).hasSameSizeAs(keywords);
        for (String keyword : keywords) {
            // Written the way the Admin page stores keywords (and accepts them), and each one finds itself
            assertThat(keyword).isEqualTo(keyword.strip().toLowerCase(java.util.Locale.ROOT));
            assertThat(keyword.length()).isLessThanOrEqualTo(100);
            assertThat(VietnameseText.squash(keyword).codePointCount(0, VietnameseText.squash(keyword).length()))
                    .as(keyword).isGreaterThanOrEqualTo(keyword.codePoints().anyMatch(cp -> cp > 0x3000) ? 2 : 3);
            assertThat(ContentFilter.findKeyword(keyword, prepared)).as(keyword).isPresent();
        }
        // No two are the same once accents are left out, like the column's UNIQUE key (utf8mb4_0900_ai_ci): INSERT
        // IGNORE would drop the second one without a word. With the keywords of V1 too.
        List<String> withV1 = new ArrayList<>(List.of("vay nóng", "cá độ", "visa nhanh", "việc nhẹ lương cao"));
        withV1.addAll(keywords);
        assertThat(withV1.stream().map(VietnameseText::fold).distinct()).hasSameSizeAs(withV1);
    }

    @Test
    void generalProstitutionAndGamblingWordsAreFoundHoweverTheyAreWritten() throws Exception {
        List<ContentFilter.Keyword> list = allSeeded();
        Map<String, String> texts = new java.util.LinkedHashMap<>();
        texts.put("mại dâm", "mại dâm");                                          // the post that went through before
        texts.put("MẠI DÂM", "mại dâm");
        texts.put("Dịch vụ mai dam giá rẻ", "mại dâm");                           // accents left out of one word only
        texts.put("dich vu mai dam gia re", "mại dâm");                           // typed without any accents
        texts.put("m.ạ.i d.â.m", "mại dâm");
        texts.put("maidam", "mại dâm");
        texts.put("Mình muốn bán dâm", "bán dâm");
        texts.put("Pachinko có phải là cờ bạc không?", "cờ bạc");
        texts.put("Chơi co bac online uy tín", "cờ bạc");
        texts.put("choi co bac online", "cờ bạc");
        texts.put("Có ai biết chỗ chơi co bac nao uy tín không", "cờ bạc");     // "co bac nao" in an accented text
        texts.put("Ở Nhật đánh bạc bị phạt thế nào?", "đánh bạc");
        texts.put("Rủ nhau cá cược bóng chày", "cá cược");
        texts.put("Nhà cái này trả thưởng nhanh", "nhà cái");
        texts.put("Hôm nay ra lô đề con gì", "lô đề");
        texts.put("Tết này chơi bầu cua không", "bầu cua");
        texts.put("Đi CASINO ở Hàn Quốc", "casino");
        texts.put("Tối nay chơi poker", "poker");
        texts.put("Mình làm thêm ở quán pachinko được không?", "pachinko");
        texts.put("パチンコ店でバイト", "パチンコ");
        texts.put("Du học sinh không được làm thêm ở quán 風俗", "風俗");
        texts.put("風俗店で働く", "風俗");
        texts.put("風俗画像を集めています", "風俗");
        texts.put("Tuyển nữ làm 風俗営業, lương cao", "風俗");
        texts.put("援交の募集", "援交");
        texts.put("売春は違法", "売春");
        texts.put("Gái gọi Shinjuku", "gái gọi");
        texts.put("Con gái bao đêm", "gái bao");
        texts.put("Ai cần bao nuôi không", "bao nuôi");
        texts.put("Tối nay đi khách", "đi khách");
        texts.put("Tuyển gái điếm", "gái điếm");
        texts.forEach((text, keyword) -> assertThat(ContentFilter.findKeyword(text, list)).as(text).contains(keyword));
    }

    @Test
    void prostitutionAndGamblingAdsAreFoundHoweverTheyAreWritten() throws Exception {
        List<ContentFilter.Keyword> list = allSeeded();
        Map<String, String> ads = new java.util.LinkedHashMap<>();
        ads.put("Nhà cái uy tín số 1, nạp lần đầu tặng 100%", "nhà cái uy tín");
        ads.put("nha cai uy tin nhat 2026", "nhà cái uy tín");
        ads.put("Soi kèo Ngoại hạng Anh tối nay", "soi kèo");
        ads.put("Link vào FB88 mới nhất", "fb88");
        ads.put("Đăng ký tại 188BET.com", "188bet");
        ads.put("f.b.8.8 nạp rút nhanh", "fb88");
        ads.put("Chơi taixiu online kiếm tiền", "tài xỉu");
        ads.put("Game bài đổi thưởng uy tín", "game bài đổi thưởng");
        ads.put("ban ca doi thuong", "bắn cá đổi thưởng");
        ads.put("Chơi lô đề online tỷ lệ 1 ăn 99", "lô đề online");
        ads.put("Xem đá gà trực tiếp", "đá gà trực tiếp");
        ads.put("c4sino online", "casino online");
        ads.put("オンライン カジノで稼ぐ", "オンラインカジノ");
        ads.put("Nhận bao nuôi sinh viên", "nhận bao nuôi");
        ads.put("Tìm sugar daddy ở Tokyo", "sugar daddy");
        ads.put("Gái gọi cao cấp khu Shinjuku", "gái gọi cao cấp");
        ads.put("Massage kích dục tại nhà", "kích dục");
        ads.put("パパ活募集中", "パパ活");
        ads.put("Làm papakatsu kiếm tiền nhanh", "papa katsu");
        ads.put("ﾃﾞﾘﾍﾙ求人", "デリヘル");
        ads.put("JKビジネス", "jkビジネス");
        ads.forEach((text, keyword) -> assertThat(ContentFilter.findKeyword(text, list)).as(text).contains(keyword));
    }

    @Test
    void everydayPhrasesAreNotTakenForProstitutionOrGambling() throws Exception {
        List<ContentFilter.Keyword> list = allSeeded();
        List<String> ordinary = List.of(
                // Everyday phrases that look like a banned word once accents are left out or words run together
                "Có bác nào biết chỗ đổi bằng lái không?", "Co bac nao biet cho doi bang lai khong",
                "co bac si nao noi tieng Viet khong", "Chao cac co bac a",
                "Bạn gái gọi điện mãi mà mình không nghe", "ban gai goi dien mai", "Con gái gọi về nhà",
                "Em gái gọi mình dậy", "Con gái bao nhiêu tuổi thì được đi du học?", "con gai bao nhieu tuoi",
                "Bạn trai bao nhiêu tuổi rồi?", "Con gái bao giờ cũng thích đi chợ",
                "Mình xem 3 căn nhà, cái nào cũng xa ga", "Ở Nhật nhà cái gì cũng nhỏ",
                "Phải lo để dành tiền đóng học phí", "phai lo de danh tien dong hoc phi",
                "Cho mình xin số để liên lạc", "cho minh xin so de lien lac", "Tiền dành để đóng học phí",
                "tien danh de dong hoc phi", "File bị ghi đè mất rồi", "file bi ghi de mat roi",
                "Cái máy giặt nó hư rồi", "may giat no hu roi", "Da gà chiên giòn ở quán này ngon", "da ga chien",
                "Chị ấy có bầu của anh", "chi ay co bau cua anh", "Mình là con gái, dịch vụ cắt tóc ở đâu rẻ?",
                "Cuối tuần có kèo bóng đá ở công viên Yoyogi", "Cả cuộc đời mình chưa thấy tuyết", "ca cuoc doi",
                "ca cuoc song o Nhat", "Mình đặt cuộc hẹn ở văn phòng quận", "minh dat cuoc hen o van phong quan",
                "Mưa dầm cả tuần", "mua dam ca tuan", "Mai đám cưới bạn mình", "mai dam cuoi ban minh",
                "ban dam phan hop dong", "minh ban dam cuoi", "Con gái điểm cao", "con gai diem cao",
                "Chủ nhật đi nhà thờ", "chu nhat di nha tho", "Mẹ bảo nuôi chó", "me bao nuoi cho",
                "Mình đi khách sạn ở Osaka", "minh di khach san o Osaka", "Đi xe khách về quê",
                "Bản trình bày của mình", "Bạn thân mình mới sang Nhật", "Tài xế xe buýt rất lịch sự",
                "Lần nạp đầu tiên vào thẻ Suica", "Tiền cước điện thoại được hoàn trả", "Mình ngủ khách sạn qua đêm",
                "Massage ở Nhật giá bao nhiêu?", "Cảnh báo: có người gạ tình trong nhóm",
                "Kích thước vali bao nhiêu thì mang lên máy bay?", "Vé xổ số ở Nhật mua ở đâu?",
                "Chơi Pokémon GO ở Ueno", "Bác sĩ khám bệnh lây qua đường tình dục ở đâu?",
                "留学生支援交流会に参加しました", "日本の風俗習慣を学ぶ", "ボディソープを買う", "SIMスロットが壊れた",
                // The work permit printed on the back of a working student's residence card
                "資格外活動許可 許可：原則週28時間以内・風俗営業等の従事を除く",
                "Thẻ ngoại kiều ghi 資格外活動許可（風俗営業等の従事を除く）");
        for (String text : ordinary) {
            assertThat(ContentFilter.findKeyword(text, list)).as(text).isEmpty();
        }
    }

    @Test
    void anEverydayPhraseNeverHidesALongerBannedPhraseAroundIt() throws Exception {
        List<ContentFilter.Keyword> list = allSeeded();
        // "lo để" is an everyday phrase, "chơi lô đề" and "lô đề online" are not
        assertThat(ContentFilter.findKeyword("choi lo de", list)).contains("chơi lô đề");
        assertThat(ContentFilter.findKeyword("lo de online", list)).contains("lô đề online");
        // "bạn gái gọi" is, "gái gọi cao cấp" is not
        assertThat(ContentFilter.findKeyword("Có bạn gái gọi cao cấp", list)).contains("gái gọi cao cấp");
        // The same words elsewhere in the text are still found
        assertThat(ContentFilter.findKeyword("Bạn gái gọi điện. Gái gọi Shinjuku", list)).contains("gái gọi");
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
    void linkProblemNamesWhatCheckLinksRefusesWithoutThrowing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(1L))).thenReturn(0L);     // no tag
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(2L))).thenReturn(1L);     // Mentor
        ContentFilter filter = new ContentFilter(jdbc, Clock.systemUTC());

        assertThat(filter.linkProblem("ib zalo 0901 234 567", 1)).contains(LinkProblem.CONTACT_NEEDS_TAG);
        assertThat(filter.linkProblem("Gọi 090-1234-5678 nhé", 1)).contains(LinkProblem.CONTACT_NEEDS_TAG);
        assertThat(filter.linkProblem("Xem nhanvisa.online", 1)).contains(LinkProblem.LINK_NEEDS_TAG);
        // A link and a phone number: the link is named, as checkLinks does
        assertThat(filter.linkProblem("Xem nhanvisa.online, zalo 0901 234 567", 1))
                .contains(LinkProblem.LINK_NEEDS_TAG);
        // Short links: nobody, tagged or not
        assertThat(filter.linkProblem("bit . ly/abc", 1)).contains(LinkProblem.SHORT_LINK);
        assertThat(filter.linkProblem("bit . ly/abc", 2)).contains(LinkProblem.SHORT_LINK);
        assertThat(filter.linkProblem("ib zalo 0901 234 567, LINE ID: scam123, https://example.com", 2)).isEmpty();
        assertThat(filter.linkProblem("Văn phòng quận: 03-3802-3111", 1)).isEmpty();
        assertThat(filter.linkProblem("", 1)).isEmpty();

        // The messages are the ones checkLinks throws
        assertThat(LinkProblem.SHORT_LINK.message())
                .isEqualTo("Không dùng link rút gọn (bit.ly, tinyurl…). Hãy dán link đầy đủ.");
        assertThat(LinkProblem.LINK_NEEDS_TAG.message())
                .isEqualTo("Chỉ tài khoản đã có tag định danh mới được chèn link.");
        assertThat(LinkProblem.CONTACT_NEEDS_TAG.message()).isEqualTo(
                "Chỉ tài khoản đã có tag định danh mới được ghi số điện thoại hoặc tài khoản Zalo, LINE, Telegram.");
    }

    @Test
    void theIdentityTagIsLookedUpOnlyWhenALinkOrContactIsFound() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(1L))).thenReturn(0L);
        ContentFilter filter = new ContentFilter(jdbc, Clock.systemUTC());

        assertThat(filter.linkProblem("Hoa anh đào ở Ueno nở rồi", 1)).isEmpty();
        assertThat(filter.linkProblem("Xem https://bit.ly/abc", 1)).contains(LinkProblem.SHORT_LINK);
        assertThatCode(() -> filter.checkLinks("Hoa anh đào ở Ueno nở rồi", 1)).doesNotThrowAnyException();
        verify(jdbc, times(0)).queryForObject(anyString(), eq(Long.class), eq(1L));

        assertThat(filter.linkProblem("LINE ID: scam123", 1)).contains(LinkProblem.CONTACT_NEEDS_TAG);
        verify(jdbc, times(1)).queryForObject(anyString(), eq(Long.class), eq(1L));
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
    void longInputNeitherOverflowsTheStackNorTakesLong() throws Exception {
        // About 20,000 characters each (4 × the content limit), checked on a 1 MB stack like a request thread: no
        // pattern may recurse once per repetition (StackOverflowError → 500) or backtrack its way through them.
        // Against every keyword the database starts with (V1, V5, V6), as the server checks a post
        List<ContentFilter.Keyword> keywords = allSeeded();
        List<String> inputs = List.of("a.".repeat(10_000), "1 ".repeat(10_000), "0 9 ".repeat(5_000),
                "a-".repeat(10_000), "0.".repeat(10_000), "a . ".repeat(5_000), "zalo ".repeat(4_000),
                "zalo " + "1 ".repeat(10_000), "line: " + "0 9 ".repeat(5_000), "tele @" + "a.".repeat(10_000),
                "v.a.y ".repeat(3_334), "@a".repeat(10_000), "www.".repeat(5_000), "a chấm ".repeat(2_858),
                "09 ".repeat(6_667), "a".repeat(20_000), "Mình mới sang Nhật, cần tìm nhà gần ga ".repeat(500),
                "nha cai uy tin ".repeat(1_400), "パパ活".repeat(6_000));
        for (String input : inputs) {
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                long nanos = onOneMegabyteStack(() -> {
                    for (int i = 0; i < 3; i++) {
                        runAllChecks(input, keywords);                 // warm up first (JIT)
                    }
                    long start = System.nanoTime();
                    runAllChecks(input, keywords);
                    return System.nanoTime() - start;
                });
                // Backtracking or recursion would take seconds; well under that is fine on a busy test machine too
                assertThat(Duration.ofNanos(nanos)).as("time on " + input.substring(0, 12) + "…")
                        .isLessThan(Duration.ofMillis(300));
            });
        }
    }

    @Test
    void fiveThousandCharactersOfDottedTextDoNotOverflowTheStack() throws Exception {
        // The old link pattern recursed once per ".a" and threw StackOverflowError here
        assertThat(onOneMegabyteStack(() -> ContentFilter.links("a.".repeat(2_500)))).isEmpty();
        assertThat(onOneMegabyteStack(() -> ContentFilter.contacts("zalo " + "1 ".repeat(2_500)))).hasSize(1);
    }

    private static void runAllChecks(String text, List<ContentFilter.Keyword> keywords) {
        ContentFilter.links(text);
        ContentFilter.contacts(text);
        ContentFilter.findKeyword(text, keywords);
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
