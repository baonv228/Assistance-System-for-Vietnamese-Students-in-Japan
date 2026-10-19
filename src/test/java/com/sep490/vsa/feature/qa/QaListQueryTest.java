package com.sep490.vsa.feature.qa;

import com.sep490.vsa.common.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QaListQueryTest {

    @Test
    void defaultsWhenNothingIsGiven() {
        QaListQuery q = QaListQuery.of(null, null, null, null, null, null, null);
        assertThat(q.keyword()).isNull();
        assertThat(q.status()).isEqualTo(QaListQuery.Status.ALL);
        assertThat(q.sort()).isEqualTo(QaListQuery.Sort.NEWEST);
        assertThat(q.page()).isZero();
        assertThat(q.size()).isEqualTo(QaListQuery.DEFAULT_SIZE);
        assertThat(q.filtered()).isFalse();
    }

    @Test
    void statusIsAFilterSortIsNot() {
        assertThat(QaListQuery.of(null, null, "unanswered", null, null, null, null).status())
                .isEqualTo(QaListQuery.Status.UNANSWERED);
        assertThat(QaListQuery.of(null, null, "SOLVED", null, null, null, null).filtered()).isTrue();
        QaListQuery byAnswers = QaListQuery.of(null, null, null, "answers", null, null, null);
        assertThat(byAnswers.sort()).isEqualTo(QaListQuery.Sort.ANSWERS);
        assertThat(byAnswers.filtered()).isFalse();
    }

    @Test
    void badValuesAreRefused() {
        assertThatThrownBy(() -> QaListQuery.of(null, null, "CLOSED", null, null, null, null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> QaListQuery.of("x".repeat(101), null, null, null, null, null, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void foldsKeywordClampsPagingAndCleansTopics() {
        QaListQuery q = QaListQuery.of("  Gia HẠN  visa ", List.of(4L, 4L, 0L, 9L), null, null, -1, 999, 3L);
        assertThat(q.keyword()).isEqualTo("gia han visa");
        assertThat(q.topicIds()).containsExactly(4L, 9L);
        assertThat(q.page()).isZero();
        assertThat(q.size()).isEqualTo(QaListQuery.MAX_SIZE);
        assertThat(q.viewerId()).isEqualTo(3L);
    }

    @Test
    void likeWildcardsAreEscaped() {
        assertThat(QaRepository.escapeLike("100%_ok\\")).isEqualTo("100\\%\\_ok\\\\");
    }
}
