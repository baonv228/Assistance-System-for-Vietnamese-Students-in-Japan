package com.sep490.vsa.feature.moderation;

import com.sep490.vsa.feature.moderation.MediaReader.Outcome;
import com.sep490.vsa.feature.moderation.MediaReader.Reading;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DevMediaReaderTest {

    @Test
    void everyFileContainsTheDevelopmentTextWithoutBeingRead() {
        DevMediaReader reader = new DevMediaReader("fb88");

        Reading reading = reader.read(new byte[]{1, 2, 3}, "video/mp4");

        assertThat(reader.engine()).isEqualTo("GEMINI");
        assertThat(reader.enabled()).isTrue();
        assertThat(reading.outcome()).isEqualTo(Outcome.READ);
        assertThat(reading.messageText()).isEqualTo("fb88");
        assertThat(reading.sceneText()).isEmpty();
        assertThat(reading.speech()).isEmpty();
        assertThat(reading.qrCode()).isFalse();
        assertThat(reading.flags()).isEmpty();
        assertThat(reading.detail()).isEqualTo("{\"mode\":\"dev\",\"note\":\"No Gemini key configured; file not read\"}");
    }
}
