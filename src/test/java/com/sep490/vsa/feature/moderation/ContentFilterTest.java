package com.sep490.vsa.feature.moderation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContentFilterTest {

    @Test
    void findsLinks() {
        assertThat(ContentFilter.links("Xem https://example.com/a?b=1 nhé")).containsExactly("https://example.com/a?b=1");
        assertThat(ContentFilter.links("vào www.google.co.jp xem")).hasSize(1);
        assertThat(ContentFilter.links("trang mercari.jp bán đồ cũ")).containsExactly("mercari.jp");
    }

    @Test
    void ordinaryTextIsNotALink() {
        assertThat(ContentFilter.links("Cơm phần 1.000 yên, mua vé ở máy. Ga Nippori v.v.")).isEmpty();
        assertThat(ContentFilter.links("Tầng 1.5 ở toà nhà")).isEmpty();
    }

    @Test
    void recognisesShortenedLinks() {
        assertThat(ContentFilter.isShortened("https://bit.ly/abc")).isTrue();
        assertThat(ContentFilter.isShortened("tinyurl.com/x")).isTrue();
        assertThat(ContentFilter.isShortened("https://www.city.arakawa.tokyo.jp/")).isFalse();
    }
}
