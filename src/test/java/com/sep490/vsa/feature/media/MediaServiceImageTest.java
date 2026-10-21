package com.sep490.vsa.feature.media;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

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
    void aSixteenBitRgbaPngNearFortyMegapixelsIsReadWithinTheMemoryBudget() throws IOException {
        // 311 KB on disk, 8 bytes a pixel decoded: every 2nd pixel (the old rule) would still take 76 MB
        byte[] png = sixteenBitRgbaPng(6324, 6324);
        assertThat(png.length).isLessThan(400 * 1024);
        assertThat(MediaService.decodedBytes(6324, 6324, 2, 8)).isGreaterThan(MediaService.DECODE_MAX_BYTES);
        assertThat(MediaService.subsampling(6324, 6324, 8)).isEqualTo(3);

        BufferedImage decoded = MediaService.decode(png);
        assertThat(decoded.getWidth()).isEqualTo(2108);
        assertThat(decoded.getHeight()).isEqualTo(2108);
        DataBuffer pixels = decoded.getRaster().getDataBuffer();
        assertThat((long) pixels.getSize() * DataBuffer.getDataTypeSize(pixels.getDataType()) / 8)
                .isEqualTo(2108L * 2108 * 8).isLessThanOrEqualTo(MediaService.DECODE_MAX_BYTES);

        MediaService.Prepared p = service.prepareImage(new MockMultipartFile("images", "big.png", "image/png", png));
        assertThat(p.width()).isEqualTo(1600);
        assertThat(p.height()).isEqualTo(1600);
    }

    @Test
    void eachPhotoIsReadAtAStepThatKeepsItWithinTheBudget() {
        assertThat(MediaService.subsampling(4000, 2000, 3)).isEqualTo(2);          // an ordinary photo: as before
        assertThat(MediaService.subsampling(3000, 2000, 4)).isEqualTo(1);
        assertThat(MediaService.subsampling(6324, 6324, 4)).isEqualTo(2);          // 40 MB
        assertThat(MediaService.subsampling(6000, 6000, 16)).isEqualTo(4);         // 144, 64, then 36 MB
        assertThat(MediaService.subsampling(40_000_000, 1, 16)).isEqualTo(12_500);
        for (int[] size : new int[][]{{6324, 6324, 8}, {6000, 6000, 16}, {7000, 5714, 16}, {1, 40_000_000, 8}}) {
            int step = MediaService.subsampling(size[0], size[1], size[2]);
            assertThat(MediaService.decodedBytes(size[0], size[1], step, size[2]))
                    .isLessThanOrEqualTo(MediaService.DECODE_MAX_BYTES);
        }
        assertThat(bytesPerPixel(BufferedImage.TYPE_INT_RGB)).isEqualTo(4);          // one int a pixel
        assertThat(bytesPerPixel(BufferedImage.TYPE_3BYTE_BGR)).isEqualTo(3);
        assertThat(bytesPerPixel(BufferedImage.TYPE_BYTE_GRAY)).isEqualTo(1);
        assertThat(bytesPerPixel(BufferedImage.TYPE_USHORT_GRAY)).isEqualTo(2);
        assertThat(MediaService.bytesPerPixel(ImageTypeSpecifier.createInterleaved(
                ColorSpace.getInstance(ColorSpace.CS_sRGB), new int[]{0, 1, 2, 3}, DataBuffer.TYPE_USHORT, true, false)))
                .isEqualTo(8);
    }

    private static int bytesPerPixel(int bufferedImageType) {
        return MediaService.bytesPerPixel(ImageTypeSpecifier.createFromBufferedImageType(bufferedImageType));
    }

    @Test
    void whenEveryDecodeSlotIsTakenAPhotoWaitsThenIsRefused() throws Exception {
        MediaService quick = new MediaService(null, null, Clock.systemUTC(), 50, Duration.ofMillis(300));
        MockMultipartFile photo = new MockMultipartFile("images", "a.png", "image/png",
                encode(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "png"));
        assertThat(MediaService.DECODES.tryAcquire(MediaService.DECODE_SLOTS, 5, TimeUnit.SECONDS)).isTrue();
        try {
            long start = System.nanoTime();
            assertThatThrownBy(() -> quick.prepareImage(photo))
                    .isInstanceOf(InvalidMediaException.class)
                    .hasMessage("Máy chủ đang xử lý nhiều ảnh, vui lòng thử lại sau ít phút.");
            assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(250);

            // A slot that frees up while the photo waits lets it through
            MediaService patient = new MediaService(null, null, Clock.systemUTC(), 50, Duration.ofSeconds(10));
            CompletableFuture<MediaService.Prepared> waiting =
                    CompletableFuture.supplyAsync(() -> patient.prepareImage(photo));
            Thread.sleep(300);
            assertThat(waiting).isNotDone();
            MediaService.DECODES.release();
            assertThat(waiting.get(10, TimeUnit.SECONDS).width()).isEqualTo(40);
            assertThat(MediaService.DECODES.tryAcquire(5, TimeUnit.SECONDS)).isTrue();   // its slot was given back
        } finally {
            MediaService.DECODES.release(MediaService.DECODE_SLOTS);
        }
        // Slots are given back after a refused file too
        assertThatThrownBy(() -> quick.prepareImage(new MockMultipartFile("images", "x.jpg", "image/jpeg",
                "not an image".getBytes()))).hasMessage(MediaService.IMAGE_RULE);
        assertThat(MediaService.DECODES.availablePermits()).isEqualTo(MediaService.DECODE_SLOTS);
        assertThat(quick.prepareImage(photo).width()).isEqualTo(40);
    }

    /** A PNG of this size, 16 bits per channel RGBA, every pixel zero: tiny on disk, huge once decoded. */
    private static byte[] sixteenBitRgbaPng(int width, int height) throws IOException {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        chunk(png, "IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height)
                .put((byte) 16).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0).array());
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try (DeflaterOutputStream z = new DeflaterOutputStream(data, deflater, 1 << 16)) {
            byte[] row = new byte[1 + width * 8];                 // filter type 0, then the samples
            for (int y = 0; y < height; y++) {
                z.write(row);
            }
        }
        chunk(png, "IDAT", data.toByteArray());
        chunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] name = type.getBytes(StandardCharsets.ISO_8859_1);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(name);
        out.writeBytes(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    @Test
    void videoMustBeMp4OrMovOfAtMostThirtySeconds() {
        assertThatThrownBy(() -> service.prepareVideo(new MockMultipartFile("video", "v.mp4", "video/mp4", "nope".getBytes())))
                .isInstanceOf(InvalidMediaException.class)
                .hasMessage("Không đọc được video. Hãy dùng video MP4 hoặc MOV.");
    }

    @Test
    void aCraftedVideoGetsTheVideoMessageQuickly() {
        // ftyp + a box whose 64-bit size overflows the reader's position (see Mp4DurationTest)
        byte[] crafted = ByteBuffer.allocate(32).putInt(16).put("ftypisom".getBytes(StandardCharsets.ISO_8859_1)).putInt(0)
                .putInt(1).put("free".getBytes(StandardCharsets.ISO_8859_1)).putLong(0x7FFFFFFFFFFFFFF0L).array();
        assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                assertThatThrownBy(() -> service.prepareVideo(new MockMultipartFile("video", "v.mp4", "video/mp4", crafted)))
                        .isInstanceOf(InvalidMediaException.class)
                        .hasMessage("Không đọc được video. Hãy dùng video MP4 hoặc MOV."));
    }

    @Test
    void aParserErrorBecomesTheVideoMessageNotAServerError() {
        try (MockedStatic<Mp4Duration> parser = mockStatic(Mp4Duration.class)) {
            parser.when(() -> Mp4Duration.read(any())).thenThrow(new ArrayIndexOutOfBoundsException(-4));
            assertThatThrownBy(() -> service.prepareVideo(new MockMultipartFile("video", "v.mp4", "video/mp4", new byte[64])))
                    .isInstanceOf(InvalidMediaException.class)
                    .hasMessage("Không đọc được video. Hãy dùng video MP4 hoặc MOV.");
        }
    }
}
