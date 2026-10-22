package com.sep490.vsa.feature.place;

import com.sep490.vsa.common.text.VietnameseText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Words for a kind of place typed before or after a name: "công viên Ueno", "chùa Sensoji", "Ueno park". The map
 * search (Photon) does not know Vietnamese words and ranks bike rentals and guest houses called "Ueno Park" above the
 * park itself, so such a query is asked as "Ueno" among places with the matching OSM tags (as "ga Ueno" is asked among
 * stations, {@link PlaceService#stationName}), and also as English text ("Ueno park").
 */
final class PlaceWords {

    private PlaceWords() {
    }

    /**
     * @param rest    the typed text without the kind words, as typed ("Ueno"); empty when only the kind was typed
     * @param tags    OSM tags as Photon filters them ("leisure:park")
     * @param english the kind in English ("park")
     */
    record Kind(String rest, List<String> tags, String english) {
    }

    private record Rule(List<String> words, List<String> tags, String english) {
    }

    private static Rule rule(String words, String english, String... tags) {
        return new Rule(Arrays.stream(words.split("\\|")).toList(), List.of(tags), english);
    }

    /** Longest words first within a rule; the first rule that matches wins. Words are folded (no accents). */
    private static final List<Rule> RULES = List.of(
            rule("cua hang tien loi|konbini|convenience store", "convenience store", "shop:convenience"),
            rule("cong vien|vuon hoa|park|garden", "park", "leisure:park", "leisure:garden"),
            rule("chua|den tho|den|mieu|temple|shrine", "temple", "amenity:place_of_worship"),
            rule("benh vien|hospital", "hospital", "amenity:hospital", "healthcare:hospital"),
            rule("phong kham|nha khoa|clinic|dentist", "clinic", "amenity:clinic", "amenity:doctors",
                    "amenity:dentist", "healthcare:clinic"),
            rule("hieu thuoc|nha thuoc|pharmacy|drugstore", "pharmacy", "amenity:pharmacy", "shop:chemist"),
            rule("sieu thi|supermarket", "supermarket", "shop:supermarket", "shop:department_store"),
            rule("truong dai hoc|dai hoc|university", "university", "amenity:university", "amenity:college"),
            rule("truong nhat ngu|truong tieng|truong hoc|truong|school", "school", "amenity:school",
                    "amenity:language_school", "amenity:college", "amenity:university"),
            rule("buu dien|post office", "post office", "amenity:post_office"),
            rule("ngan hang|bank", "bank", "amenity:bank"),
            rule("quan ca phe|ca phe|cafe|coffee", "cafe", "amenity:cafe"),
            rule("nha hang|quan an|quan com|quan pho|restaurant", "restaurant", "amenity:restaurant", "amenity:fast_food",
                    "amenity:food_court"),
            rule("khach san|nha nghi|hotel|hostel", "hotel", "tourism:hotel", "tourism:hostel", "tourism:guest_house"),
            rule("bao tang|museum", "museum", "tourism:museum"),
            rule("so thu|vuon thu|zoo", "zoo", "tourism:zoo"),
            rule("cho|market", "market", "amenity:marketplace"),
            rule("van phong quan|toa thi chinh|uy ban|city hall|ward office", "city hall", "amenity:townhall",
                    "office:government"),
            rule("tiem giat|giat say|laundry", "laundry", "shop:laundry", "shop:dry_cleaning"),
            rule("thu vien|library", "library", "amenity:library"));

    /** The kind of place typed at the start or the end of the query, if any. */
    static Optional<Kind> parse(String query) {
        String[] typed = query == null ? new String[0] : query.strip().split("\\s+");
        if (typed.length == 0 || typed[0].isEmpty()) {
            return Optional.empty();
        }
        List<String> folded = Arrays.stream(typed).map(VietnameseText::fold).toList();
        for (Rule r : RULES) {
            for (String word : r.words()) {
                String[] parts = word.split(" ");
                int n = parts.length;
                if (n > typed.length) {
                    continue;
                }
                if (folded.subList(0, n).equals(List.of(parts))) {
                    return Optional.of(kind(r, Arrays.asList(typed).subList(n, typed.length)));
                }
                if (folded.subList(typed.length - n, typed.length).equals(List.of(parts))) {
                    return Optional.of(kind(r, Arrays.asList(typed).subList(0, typed.length - n)));
                }
            }
        }
        return Optional.empty();
    }

    private static Kind kind(Rule r, List<String> rest) {
        return new Kind(String.join(" ", new ArrayList<>(rest)), r.tags(), r.english());
    }
}
