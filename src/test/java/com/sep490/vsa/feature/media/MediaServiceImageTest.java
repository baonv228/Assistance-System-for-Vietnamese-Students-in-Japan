package com.sep490.vsa.feature.media;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaServiceImageTest {

    private final MediaService service = new MediaService(null, null, Clock.systemUTC(), 50);

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    @Test
    void bigPhotoIsShrunkToSixteenHundredPixelsAndSavedAsJpeg() throws IOException {
        BufferedImage wide = new BufferedImage(4000, 2000, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = wide.createGraphics();
        g.setColor(Color.PINK);
        g.fillRect(0, 0, 4000, 2000);
        g.dispose();

        MediaService.Prepared p = service.prepareImage(new MockMultipartFile("images", "a.png", "image/png", encode(wide, "png")));
        assertThat(p.type()).isEqualTo("IMAGE");
        assertThat(p.mimeType()).isEqualTo("image/jpeg");
        assertThat(p.width()).isEqualTo(1600);
        assertThat(p.height()).isEqualTo(800);
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(p.bytes()));
        assertThat(back.getWidth()).isEqualTo(1600);
    }

    @Test
    void transparentPngGetsAWhiteBackground() throws IOException {
        BufferedImage clear = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        MediaService.Prepared p = service.prepareImage(new MockMultipartFile("images", "c.png", "image/png", encode(clear, "png")));
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(p.bytes()));
        assertThat(new Color(back.getRGB(5, 5)).getRed()).isGreaterThan(240);
        assertThat(p.width()).isEqualTo(10);                 // small photos are not enlarged
    }

    @Test
    void anythingThatIsNotJpegOrPngIsRefused() throws IOException {
        BufferedImage img = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        assertThatThrownBy(() -> service.prepareImage(new MockMultipartFile("images", "a.gif", "image/gif", encode(img, "gif"))))
                .isInstanceOf(InvalidMediaException.class).hasMessage(MediaService.IMAGE_RULE);
        assertThatThrownBy(() -> service.prepareImage(new MockMultipartFile("images", "x.jpg", "image/jpeg", "not an image".getBytes())))
                .isInstanceOf(InvalidMediaException.class);
        assertThatThrownBy(() -> service.prepareImage(new MockMultipartFile("images", "e.jpg", "image/jpeg", new byte[0])))
                .isInstanceOf(InvalidMediaException.class);
    }

    @Test
    void videoMustBeMp4OrMovOfAtMostThirtySeconds() {
        assertThatThrownBy(() -> service.prepareVideo(new MockMultipartFile("video", "v.mp4", "video/mp4", "nope".getBytes())))
                .isInstanceOf(InvalidMediaException.class)
                .hasMessage("Không đọc được video. Hãy dùng video MP4 hoặc MOV.");
    }
}
