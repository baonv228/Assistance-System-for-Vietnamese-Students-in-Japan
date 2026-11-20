package com.sep490.vsa.feature.quest;

import com.sep490.vsa.feature.quest.QuestDtos.CompletedQuest;
import com.sep490.vsa.feature.quest.QuestDtos.QuestChange;
import com.sep490.vsa.feature.quest.QuestDtos.StreakBrief;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The text a flash answer or an event adds to its message for the quests it completed (the app reads the data). */
class QuestCompletionNoteTest {

    @Test
    void eachCompletedQuestIsNamedInQuotesThatNeverNest() {
        QuestChange change = new QuestChange(List.of(), List.of(
                new CompletedQuest(1, "FLASH_REVIEW", "Ôn 10 thẻ ghi nhớ", 10),
                new CompletedQuest(2, "TOPIC", "Ôn 5 thẻ chủ đề “Thanh toán”", 0)),
                false, 0, new StreakBrief(0, false), null, 10, 10, 50, null);

        assertThat(QuestService.completionNote(change)).isEqualTo(
                " Xong nhiệm vụ “Ôn 10 thẻ ghi nhớ” · +10 xu. Xong nhiệm vụ: Ôn 5 thẻ chủ đề “Thanh toán” · +0 xu.");
    }

    @Test
    void nothingCompletedAddsNothing() {
        QuestChange change = new QuestChange(List.of(), List.of(), false, 0, new StreakBrief(0, false), null, 0, 0, 50,
                null);
        assertThat(QuestService.completionNote(change)).isEmpty();
        assertThat(QuestService.completionNote(null)).isEmpty();
    }
}
