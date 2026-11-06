package com.sep490.vsa.feature.search;

import com.sep490.vsa.feature.blog.BlogDtos.BlogCard;
import com.sep490.vsa.feature.event.EventDtos.EventCard;
import com.sep490.vsa.feature.phrase.PhraseDtos.PhraseCard;
import com.sep490.vsa.feature.qa.QaDtos.QuestionCard;

import java.util.List;

public final class SearchDtos {

    private SearchDtos() {
    }

    /**
     * Page "Tìm kiếm" ({@code /search}): what one text found in every part of the site, at most
     * {@value SearchService#SECTION_SIZE} items a part with the part's whole count, so the page can link to that
     * part's own list ("Xem tất cả"). {@code q} is the text as searched (spaces stripped); {@code tooShort} when it
     * holds fewer than 2 letters or digits and no Japanese character (nothing is searched then); {@code total} adds the
     * parts up.
     */
    public record SiteSearch(String q, boolean tooShort, long total, Section<PlaceHit> places, Section<BlogCard> posts,
                             Section<QuestionCard> questions, Section<GuideHit> guides, Section<EventCard> events,
                             Section<PhraseCard> phrases, Section<FaqHit> faqs) {

        static SiteSearch nothing(String q, boolean tooShort) {
            return new SiteSearch(q, tooShort, 0, Section.empty(), Section.empty(), Section.empty(), Section.empty(),
                    Section.empty(), Section.empty(), Section.empty());
        }
    }

    /** The first items of one part and how many it holds in all. */
    public record Section<T>(long total, List<T> items) {

        static <T> Section<T> empty() {
            return new Section<>(0, List.of());
        }
    }

    /**
     * A Senpai ơi place (opens on the Map): {@code areaName} "Arakawa-ku, Tokyo · 東京都荒川区" (null outside Japan),
     * {@code reviewCount} and {@code rating} of its published reviews by active accounts (rating null without one).
     */
    public record PlaceHit(long id, String name, String nameJa, String nameEn, String categoryName, String categoryIcon,
                           String areaName, String address, boolean closed, long reviewCount, Double rating) {
    }

    /** A published guide of "Cẩm nang" ({@code /guides/{id}}); {@code summary} cut to one short paragraph. */
    public record GuideHit(long id, String title, String summary, String stageName, String topicName,
                           String coverImageUrl) {
    }

    /** A published FAQ entry ({@code /qa?tab=faq&id=}); {@code answer} cut to one short paragraph. */
    public record FaqHit(long id, String question, String answer, String topicName) {
    }
}
