package com.sep490.vsa.feature.faq;

import com.sep490.vsa.common.text.VietnameseText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds the FAQ entries that look like a question someone is typing ("Đặt câu hỏi") or searching for (Hỏi-Đáp), even
 * when the words are not one exact phrase of the entry. Accents and case are ignored ({@link VietnameseText#fold}).
 * <p>
 * Each word of the text that is not a common question word ({@link #COMMON}) scores 2 when the FAQ question holds it
 * and 1 when only the answer does; each pair of neighbouring words (not both common) scores 3 in the question and 1 in
 * the answer, so "tài khoản" or "làm thêm" weigh more than their words apart. An entry is related from
 * {@link #needed} points: half of what the text can reach, at least 1 and at most 4, so a single word found anywhere
 * counts while a whole sentence needs more than one shared word. Japanese runs (no spaces) count when they appear
 * anywhere in the text.
 */
final class FaqMatcher {

    /** Question and filler words that say nothing about the subject (folded). "cần chốt": tuned on the V19 FAQs. */
    static final Set<String> COMMON = Set.of(
            "a", "ah", "ai", "anh", "ban", "bao", "bi", "biet", "bn", "cac", "can", "chi", "cho", "chu", "chua", "co",
            "cua", "cung", "da", "dang", "dau", "day", "dc", "de", "den", "di", "do", "du", "duoc", "em", "gi", "giup",
            "hay", "hoc", "hoi", "j", "k", "khi", "khong", "ko", "la", "lam", "lau", "len", "ma", "minh", "moi", "mot",
            "nao", "nay", "nen", "neu", "nguoi", "nha", "nhat", "nhe", "nhieu", "nhu", "nhung", "ntn", "o", "oi",
            "phai", "ra", "roi", "sang", "sao", "se", "sinh", "the", "thi", "thoi", "toi", "tu", "va", "vao", "vay",
            "ve", "vi", "viet", "voi", "z");

    private static final Pattern SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern JAPANESE = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]");

    static final int WORD_IN_QUESTION = 2;
    static final int PAIR_IN_QUESTION = 3;
    static final int IN_ANSWER = 1;
    static final int MAX_NEEDED = 4;

    /** One FAQ entry to compare; {@code answer} may be cut short by the caller. */
    record Candidate(long id, String question, String answer) {
    }

    private final List<String> words;
    private final List<String> pairs;

    FaqMatcher(String text) {
        List<String> tokens = tokens(text);
        Set<String> wordSet = new LinkedHashSet<>();
        Set<String> pairSet = new LinkedHashSet<>();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (meaningful(token)) {
                wordSet.add(token);
            }
            if (i > 0 && (meaningful(tokens.get(i - 1)) || meaningful(token))) {
                pairSet.add(tokens.get(i - 1) + " " + token);
            }
        }
        this.words = List.copyOf(wordSet);
        this.pairs = List.copyOf(pairSet);
    }

    /** True when the text holds no word that could match anything ("làm sao", "?"). */
    boolean isEmpty() {
        return words.isEmpty() && pairs.isEmpty();
    }

    /** Points an entry needs to count as related to this text. */
    int needed() {
        int reachable = words.size() * WORD_IN_QUESTION + pairs.size() * PAIR_IN_QUESTION;
        return Math.min(MAX_NEEDED, Math.max(1, (reachable + 1) / 2));
    }

    int score(Candidate entry) {
        String question = spaced(entry.question());
        String answer = spaced(entry.answer());
        int score = 0;
        for (String word : words) {
            score += found(question, word) ? WORD_IN_QUESTION : found(answer, word) ? IN_ANSWER : 0;
        }
        for (String pair : pairs) {
            score += found(question, pair) ? PAIR_IN_QUESTION : found(answer, pair) ? IN_ANSWER : 0;
        }
        return score;
    }

    /** The ids of the related entries, best first; equal scores keep the order of {@code entries}. */
    List<Long> rank(List<Candidate> entries, int limit) {
        if (isEmpty() || limit < 1) {
            return List.of();
        }
        int needed = needed();
        record Scored(long id, int score, int order) {
        }
        List<Scored> related = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            int score = score(entries.get(i));
            if (score >= needed) {
                related.add(new Scored(entries.get(i).id(), score, i));
            }
        }
        return related.stream()
                .sorted(Comparator.comparingInt(Scored::score).reversed().thenComparingInt(Scored::order))
                .limit(limit)
                .map(Scored::id)
                .toList();
    }

    private static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        for (String token : SPLIT.split(VietnameseText.fold(text))) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static boolean meaningful(String token) {
        return japanese(token) || token.length() >= 2 && !COMMON.contains(token);
    }

    private static boolean japanese(String token) {
        return JAPANESE.matcher(token).find();
    }

    /** " word word … " so a word is found whole: "sim" is not inside "simple". */
    private static String spaced(String text) {
        return " " + String.join(" ", tokens(text)) + " ";
    }

    private static boolean found(String spacedText, String words) {
        if (japanese(words) && !words.contains(" ")) {
            return spacedText.contains(words);
        }
        return spacedText.contains(" " + words + " ");
    }
}
