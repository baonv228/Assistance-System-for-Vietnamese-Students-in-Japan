package com.sep490.vsa.feature.search;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.common.text.VietnameseText;
import com.sep490.vsa.feature.blog.BlogDtos.BlogCard;
import com.sep490.vsa.feature.blog.BlogDtos.BlogPage;
import com.sep490.vsa.feature.blog.BlogFeedQuery;
import com.sep490.vsa.feature.blog.BlogFeedService;
import com.sep490.vsa.feature.event.EventDtos.EventCard;
import com.sep490.vsa.feature.event.EventService;
import com.sep490.vsa.feature.faq.FaqDtos.Entry;
import com.sep490.vsa.feature.faq.FaqService;
import com.sep490.vsa.feature.guide.GuideService;
import com.sep490.vsa.feature.phrase.PhraseDtos.PhraseCard;
import com.sep490.vsa.feature.phrase.PhraseService;
import com.sep490.vsa.feature.qa.QaDtos.QuestionCard;
import com.sep490.vsa.feature.qa.QaDtos.QuestionPage;
import com.sep490.vsa.feature.qa.QaListQuery;
import com.sep490.vsa.feature.qa.QaService;
import com.sep490.vsa.feature.search.SearchDtos.FaqHit;
import com.sep490.vsa.feature.search.SearchDtos.GuideHit;
import com.sep490.vsa.feature.search.SearchDtos.PlaceHit;
import com.sep490.vsa.feature.search.SearchDtos.Section;
import com.sep490.vsa.feature.search.SearchDtos.SiteSearch;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * "Tìm kiếm" (the prototype's "Tìm kiếm chung", reached from the Home): one text searched in every part of the site at
 * once. Each part is searched by its own feature, so what shows here is exactly what that part's page shows for the
 * same text (its "Xem tất cả" link), with the same rules on what a viewer may see: posts and questions of locked or
 * deleted accounts left out, the author's own posts still being scanned, published guides and FAQ entries, events
 * not deleted. Places are Senpai ơi places only ({@link SearchRepository}); the Map's search adds OpenStreetMap.
 */
@Service
@Transactional(readOnly = true)
public class SearchService {

    /** Items shown per part; the part's page lists the rest. */
    static final int SECTION_SIZE = 5;
    /** As the Blog and Hỏi-Đáp search. */
    static final int KEYWORD_MAX = 100;
    /** Letters or digits needed (accents, spaces and signs not counted), unless a Japanese character is typed. */
    static final int MIN_LETTERS = 2;
    private static final Pattern JAPANESE = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]");

    private final BlogFeedService blogs;
    private final QaService questions;
    private final EventService events;
    private final PhraseService phrases;
    private final GuideService guides;
    private final FaqService faqs;
    private final SearchRepository repository;

    public SearchService(BlogFeedService blogs, QaService questions, EventService events, PhraseService phrases,
                         GuideService guides, FaqService faqs, SearchRepository repository) {
        this.blogs = blogs;
        this.questions = questions;
        this.events = events;
        this.phrases = phrases;
        this.guides = guides;
        this.faqs = faqs;
        this.repository = repository;
    }

    /**
     * Searches {@code q} (spaces around it stripped, at most {@value #KEYWORD_MAX} characters, else 400). A blank or
     * too short text searches nothing ({@code tooShort} tells the page to ask for more).
     */
    public SiteSearch search(AppUserPrincipal me, String q) {
        String text = q == null ? "" : q.strip();
        if (text.length() > KEYWORD_MAX) {
            throw ApiException.badRequest("Từ khóa tìm kiếm tối đa " + KEYWORD_MAX + " ký tự.");
        }
        if (tooShort(text)) {
            return SiteSearch.nothing(text, !text.isEmpty());
        }
        Long viewerId = me == null ? null : me.id();

        String folded = VietnameseText.fold(text);
        Section<PlaceHit> places = new Section<>(repository.countPlaces(folded), repository.places(folded, SECTION_SIZE));

        BlogPage postPage = blogs.list(BlogFeedQuery.of(text, null, null, 0, SECTION_SIZE, viewerId));
        Section<BlogCard> posts = new Section<>(postPage.totalItems(), postPage.items());

        QuestionPage questionPage = questions.list(QaListQuery.of(text, null, null, null, 0, SECTION_SIZE, viewerId));
        Section<QuestionCard> asked = new Section<>(questionPage.totalItems(), questionPage.items());

        GuideService.BrowsePage guidePage = guides.browse(null, text, "NEWEST", 0, SECTION_SIZE);
        Section<GuideHit> guideHits = new Section<>(guidePage.total(), guidePage.items().stream()
                .map(g -> new GuideHit(g.id(), g.title(), excerpt(g.summary(), 160), g.stageName(), g.topicName(),
                        g.coverImageUrl()))
                .toList());

        // Happening now and coming first (soonest first), then past ones (latest first)
        List<EventCard> foundEvents = events.list(text).stream()
                .sorted(Comparator.comparing((EventCard e) -> "PAST".equals(e.state()))
                        .thenComparing((a, b) -> "PAST".equals(a.state())
                                ? b.endDate().compareTo(a.endDate())
                                : a.startDate().compareTo(b.startDate()))
                        .thenComparing(EventCard::id))
                .toList();
        Section<EventCard> eventHits = new Section<>(foundEvents.size(),
                foundEvents.subList(0, Math.min(SECTION_SIZE, foundEvents.size())));

        List<PhraseCard> foundPhrases = phrases.list(me, null, null, null, text);
        Section<PhraseCard> phraseHits = new Section<>(foundPhrases.size(),
                foundPhrases.subList(0, Math.min(SECTION_SIZE, foundPhrases.size())));

        var faqPage = faqs.list(text, null, "PUBLISHED", 0, SECTION_SIZE, false);
        Section<FaqHit> faqHits = new Section<>(faqPage.total(), faqPage.items().stream()
                .map((Entry f) -> new FaqHit(f.id(), f.question(), excerpt(f.answer(), 160), f.topicName()))
                .toList());

        long total = places.total() + posts.total() + asked.total() + guideHits.total() + eventHits.total()
                + phraseHits.total() + faqHits.total();
        return new SiteSearch(text, false, total, places, posts, asked, guideHits, eventHits, phraseHits, faqHits);
    }

    /** Fewer than {@value #MIN_LETTERS} letters or digits and no Japanese character ("駅" is enough, "ở" is not). */
    static boolean tooShort(String text) {
        return VietnameseText.squash(text).length() < MIN_LETTERS && !JAPANESE.matcher(text).find();
    }

    /** One line cut at a word, "…" when cut. */
    static String excerpt(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        if (flat.length() <= max) {
            return flat;
        }
        String cut = flat.substring(0, max);
        int space = cut.lastIndexOf(' ');
        return (space > max * 0.6 ? cut.substring(0, space) : cut).strip() + "…";
    }
}
