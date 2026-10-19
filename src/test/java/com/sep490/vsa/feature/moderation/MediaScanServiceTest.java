package com.sep490.vsa.feature.moderation;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MediaScanServiceTest {

    @Test
    void pauseAfterScannerErrorsDoublesUpToAnHour() {
        assertThat(MediaScanService.pauseAfter(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(MediaScanService.pauseAfter(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(MediaScanService.pauseAfter(4)).isEqualTo(Duration.ofMinutes(8));
        assertThat(MediaScanService.pauseAfter(6)).isEqualTo(Duration.ofMinutes(32));
        assertThat(MediaScanService.pauseAfter(7)).isEqualTo(Duration.ofMinutes(60));
        assertThat(MediaScanService.pauseAfter(500)).isEqualTo(Duration.ofMinutes(60));
    }
}
