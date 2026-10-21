package com.sep490.vsa.common.text;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Text normalisation used by search and by the banned-keyword filter. */
public final class VietnameseText {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern NOT_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern NOT_LETTER_MARK_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{M}\\p{N}]+");
    /** Combining marks except the Japanese voicing marks dakuten (U+3099) and handakuten (U+309A). */
    private static final Pattern MARKS_BUT_KANA_VOICING = Pattern.compile("[\\p{M}&&[^\\u3099\\u309A]]+");

    private VietnameseText() {
    }

    /**
     * Lower case without Vietnamese diacritics, single spaces: "Phân loại RÁC  ở Đà" → "phan loai rac o da".
     */
    public static String fold(String text) {
        if (text == null) {
            return "";
        }
        String noMarks = MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        return SPACES.matcher(noMarks.replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    /**
     * {@link #fold(String)} and then only letters and digits are kept, so "Việc  nhẹ, lương-cao" and
     * "viecnheluongcao" compare equal. Used to match banned keywords that people try to disguise.
     */
    public static String squash(String text) {
        return NOT_LETTER_OR_DIGIT.matcher(fold(text)).replaceAll("");
    }

    /**
     * Lower case with only letters, accent marks and digits, the accents kept: "Vay-Nóng!" and "vay nóng" both give
     * "vaynóng", while "vậy nóng" gives "vậynóng" and "vay nong" gives "vaynong". Tells whether two banned keywords are
     * the same keyword for the filter, which also tells words with other accents apart.
     */
    public static String squashKeepingMarks(String text) {
        if (text == null) {
            return "";
        }
        String lower = Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return Normalizer.normalize(NOT_LETTER_MARK_OR_DIGIT.matcher(lower).replaceAll(""), Normalizer.Form.NFC);
    }

    /**
     * {@link #fold(String)}, except that the Japanese voicing marks stay with their kana, so "バイト" and "ハイト",
     * or "パン", "バン" and "ハン", stay different: "Việc BÀN パン" → "viec ban パン". Used by the banned-keyword
     * filter ({@code fold} itself, used by search, is unchanged).
     */
    public static String foldKeepingKanaMarks(String text) {
        if (text == null) {
            return "";
        }
        String noMarks = MARKS_BUT_KANA_VOICING.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        String folded = SPACES.matcher(noMarks.replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT))
                .replaceAll(" ").strip();
        return Normalizer.normalize(folded, Normalizer.Form.NFC);
    }

    /**
     * True when the text carries a Vietnamese diacritic (tone or vowel mark, or đ): "vậy" and "đi" are true,
     * "vay" and "di" are false. Used by the banned-keyword filter to tell "vậy" from "vay". The Japanese voicing marks
     * are not diacritics here: "バイト" is false.
     */
    public static boolean hasDiacritics(String text) {
        if (text == null) {
            return false;
        }
        String nfd = Normalizer.normalize(text, Normalizer.Form.NFD);
        return MARKS_BUT_KANA_VOICING.matcher(nfd).find() || nfd.indexOf('đ') >= 0 || nfd.indexOf('Đ') >= 0;
    }
}
