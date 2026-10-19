package com.sep490.vsa.common.text;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Text normalisation used by search and by the banned-keyword filter. */
public final class VietnameseText {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern NOT_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]+");

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
}
