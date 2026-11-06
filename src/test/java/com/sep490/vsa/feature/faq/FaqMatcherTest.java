package com.sep490.vsa.feature.faq;

import com.sep490.vsa.feature.faq.FaqMatcher.Candidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** "Câu hỏi thường gặp liên quan": which FAQ entries a typed question or a search brings up (no database). */
class FaqMatcherTest {

    /** The ten FAQ entries of V19 (questions as written there, answers shortened to their key words). */
    private static final List<Candidate> V19 = List.of(
            new Candidate(1, "Nhập cảnh tại sân bay Nhật Bản cần làm gì?",
                    "Xuất trình hộ chiếu, visa và giấy tờ nhập học. Nhận thẻ cư trú tại sân bay lớn."),
            new Candidate(2, "Trước khi sang Nhật cần chuẩn bị những giấy tờ gì?",
                    "Bạn nên chuẩn bị hộ chiếu còn hiệu lực, visa phù hợp, giấy tờ liên quan đến tư cách lưu trú."),
            new Candidate(3, "Khi mới sang Nhật nên mang theo bao nhiêu tiền mặt?",
                    "Nên mang đủ tiền mặt cho vài tuần đầu: tiền nhà, đi lại, ăn uống."),
            new Candidate(4, "Làm thế nào để mua SIM điện thoại khi mới sang Nhật?",
                    "Bạn có thể đăng ký SIM tại cửa hàng của nhà mạng hoặc qua website. Nên kiểm tra điện thoại có hỗ trợ băng tần."),
            new Candidate(5, "Phân loại và đổ rác tại Nhật như thế nào?",
                    "Mỗi khu vực có lịch đổ rác riêng: rác cháy được, rác không cháy, chai nhựa."),
            new Candidate(6, "Du học sinh tại Nhật có được làm thêm không?",
                    "Du học sinh có thể làm thêm nếu được cấp phép hoạt động ngoài tư cách lưu trú (資格外活動許可). Giới hạn 28 giờ mỗi tuần."),
            new Candidate(7, "Sau khi đến Nhật bao lâu phải đăng ký địa chỉ cư trú?",
                    "Trong vòng 14 ngày sau khi có chỗ ở, đăng ký tại văn phòng quận và mang theo thẻ cư trú."),
            new Candidate(8, "Nếu làm mất thẻ cư trú tại Nhật thì phải làm gì?",
                    "Báo cảnh sát để lấy giấy xác nhận, rồi xin cấp lại tại cục xuất nhập cảnh trong 14 ngày, mang hộ chiếu."),
            new Candidate(9, "Khi gặp tình huống khẩn cấp tại Nhật cần gọi số nào?",
                    "Gọi 110 cho cảnh sát, 119 cho cứu hỏa và cấp cứu."),
            new Candidate(10, "Nếu bị mất hộ chiếu tại Nhật phải làm gì?",
                    "Báo cảnh sát, liên hệ Đại sứ quán Việt Nam để làm lại hộ chiếu."));

    private static List<Long> related(String text) {
        return new FaqMatcher(text).rank(V19, 5);
    }

    @Test
    void aQuestionBeingTypedFindsTheEntryAskingTheSameThing() {
        assertThat(related("Mới sang Nhật thì mua sim ở đâu?")).containsExactly(4L);
        assertThat(related("Du học sinh có được đi làm thêm không ạ?")).containsExactly(6L);
        assertThat(related("Phân loại rác ở Tokyo thế nào")).containsExactly(5L);
        assertThat(related("Mất thẻ ngoại kiều phải làm sao?")).containsExactly(8L);
        assertThat(related("Khẩn cấp thì gọi số mấy")).containsExactly(9L);
    }

    @Test
    void accentsCaseAndPunctuationDoNotMatter() {
        assertThat(related("MUA SIM DIEN THOAI")).containsExactly(4L);
        assertThat(related("mua-sim, dien.thoai!!")).containsExactly(4L);
        assertThat(related("đổ   rác")).containsExactly(5L);
        assertThat(related("do rac")).containsExactly(5L);
    }

    @Test
    void theBestMatchComesFirstAndAnswersCountLessThanQuestions() {
        // Both entries are about a lost document; the one asking about the passport itself comes first
        assertThat(related("Mình bị mất hộ chiếu")).startsWith(10L).contains(8L);
        var matcher = new FaqMatcher("mất hộ chiếu");
        assertThat(matcher.score(V19.get(9))).isGreaterThan(matcher.score(V19.get(7)));
    }

    @Test
    void oneWordCountsWhereverItIsButASentenceNeedsMoreThanOneSharedWord() {
        // "visa" is only in two answers: still related to a search for it
        assertThat(related("visa")).containsExactlyInAnyOrder(1L, 2L);
        // A sentence sharing a single everyday word with an entry ("tiền" of FAQ 3) is not related to it
        assertThat(related("Gửi tiền về Việt Nam qua ngân hàng nào rẻ?")).doesNotContain(3L);
        assertThat(related("Làm thêm có cần xin giấy phép không?")).containsExactly(6L);
    }

    @Test
    void questionWordsAloneMatchNothing() {
        for (String text : new String[]{"", "   ", "?", "làm sao", "Có ai biết không ạ", "ở Nhật thì làm thế nào", "du học sinh"}) {
            assertThat(new FaqMatcher(text).isEmpty()).as(text).isTrue();
            assertThat(related(text)).as(text).isEmpty();
        }
    }

    @Test
    void wordsAreMatchedWholeNotInsideOtherWords() {
        var simple = List.of(new Candidate(1, "A simple question", "Simulation"));
        assertThat(new FaqMatcher("sim").rank(simple, 5)).isEmpty();
        assertThat(new FaqMatcher("simple").rank(simple, 5)).containsExactly(1L);
    }

    @Test
    void japaneseIsFoundInsideLongerJapaneseText() {
        assertThat(related("資格外活動")).containsExactly(6L);
        assertThat(related("在留カード")).isEmpty();
    }

    @Test
    void neededPointsGrowWithTheTextUpToFour() {
        assertThat(new FaqMatcher("visa").needed()).isEqualTo(1);
        assertThat(new FaqMatcher("mua sim").needed()).isEqualTo(4);
        assertThat(new FaqMatcher("Mới sang Nhật thì mua sim điện thoại ở đâu rẻ nhất").needed()).isEqualTo(FaqMatcher.MAX_NEEDED);
    }

    @Test
    void theLimitIsKeptAndEqualScoresKeepTheGivenOrder() {
        var same = List.of(new Candidate(7, "Gia hạn visa", ""), new Candidate(3, "Gia hạn visa", ""),
                new Candidate(5, "Gia hạn visa", ""));
        assertThat(new FaqMatcher("gia hạn visa").rank(same, 2)).containsExactly(7L, 3L);
        assertThat(new FaqMatcher("gia hạn visa").rank(same, 0)).isEmpty();
    }
}
