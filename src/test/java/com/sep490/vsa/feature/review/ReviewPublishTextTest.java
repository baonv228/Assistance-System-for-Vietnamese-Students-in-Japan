package com.sep490.vsa.feature.review;

import org.junit.jupiter.api.Test;

import static com.sep490.vsa.feature.review.ReviewPublishService.publishedText;
import static org.assertj.core.api.Assertions.assertThat;

/** The wording of "your review passed the photo/video scan", for each case ScanNotificationFlowTest does not reach. */
class ReviewPublishTextTest {

    @Test
    void firstPublicationEditAndPendingPin() {
        assertThat(publishedText(" về “Ramen Aoba”", true, false))
                .isEqualTo("Review về “Ramen Aoba” đã qua kiểm duyệt ảnh/video và đã được đăng. +10 điểm.");
        assertThat(publishedText(" về “Ramen Aoba”", false, false))
                .isEqualTo("Bản sửa review về “Ramen Aoba” đã qua kiểm duyệt ảnh/video và đã được đăng.");
        assertThat(publishedText(" về “Quán mới”", false, true))
                .isEqualTo("Bản sửa review đầu tiên về “Quán mới” đã qua kiểm duyệt ảnh/video. Review sẽ hiển thị cho"
                        + " mọi người khi ghim được Moderator duyệt.");
        // The place row is gone: no name
        assertThat(publishedText("", true, false))
                .isEqualTo("Review đã qua kiểm duyệt ảnh/video và đã được đăng. +10 điểm.");
    }
}
