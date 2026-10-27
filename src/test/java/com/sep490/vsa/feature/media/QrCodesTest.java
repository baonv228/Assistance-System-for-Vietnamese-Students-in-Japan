package com.sep490.vsa.feature.media;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.EAN13Writer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The QR codes the server reads in a photo (ZXing). The codes are made by ZXing's own writer and drawn into photos the
 * way users send them: a whole screenshot of a QR with a logo in the middle (Zalo, LINE), a small code on a flyer in a
 * bigger photo, a light code on a dark background, a tilted one. Photos without a QR code, ordinary barcodes included,
 * give nothing.
 */
class QrCodesTest {

    private static final String ZALO = "https://zalo.me/g/abcdef123";

    /** A QR code of {@code size} pixels, black on white, with a quiet zone, high error correction (room for a logo). */
    static BufferedImage qr(String text, int size) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H, EncodeHintType.MARGIN, 2));
            BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < matrix.getWidth(); x++) {
                for (int y = 0; y < matrix.getHeight(); y++) {
                    image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
                }
            }
            return image;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A street-like photo: sky, buildings with lit windows, a sign with text. No QR code. */
    static BufferedImage scene(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(40, 50, 90));
        g.fillRect(0, 0, width, height);
        for (int b = 0; b < 6; b++) {
            int x = b * width / 6;
            g.setColor(new Color(90 + b * 10, 85, 100));
            g.fillRect(x + 10, height / 3 + (b % 3) * 40, width / 6 - 20, height);
            g.setColor(new Color(255, 236, 150));
            for (int wy = height / 3 + 80; wy < height - 60; wy += 70) {
                for (int wx = x + 30; wx < x + width / 6 - 40; wx += 60) {
                    g.fillRect(wx, wy, 28, 36);
                }
            }
        }
        g.setColor(new Color(200, 40, 60));
        g.fillRect(width / 3, height / 4, width / 4, 80);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 48));
        g.drawString("RAMEN 24H", width / 3 + 20, height / 4 + 58);
        g.dispose();
        return image;
    }

    /** One of the three square corners of a QR code (7 × 7 modules: dark ring, light ring, dark centre). */
    static void cornerSquare(BufferedImage image, int x, int y, int module) {
        for (int i = 0; i < 7; i++) {
            for (int j = 0; j < 7; j++) {
                boolean dark = i == 0 || i == 6 || j == 0 || j == 6 || (i >= 2 && i <= 4 && j >= 2 && j <= 4);
                for (int a = 0; a < module; a++) {
                    for (int b = 0; b < module; b++) {
                        image.setRGB(x + i * module + a, y + j * module + b, dark ? 0x000000 : 0xFFFFFF);
                    }
                }
            }
        }
    }

    /** A crafted photo: {@code side} × {@code side} corner squares, as an attacker would draw to slow the reader. */
    static BufferedImage cornerGrid(int side, int module) {
        int step = 9 * module;
        BufferedImage grid = new BufferedImage(side * step + 20, side * step + 20, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = grid.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, grid.getWidth(), grid.getHeight());
        g.dispose();
        for (int i = 0; i < side; i++) {
            for (int j = 0; j < side; j++) {
                cornerSquare(grid, 10 + i * step, 10 + j * step, module);
            }
        }
        return grid;
    }

    /** Corner squares of three sizes side by side, so ZXing never sees three alike and stops early. */
    static BufferedImage mixedCornerGrid(int side) {
        int step = 9 * 3;
        BufferedImage grid = new BufferedImage(side * step + 20, side * step + 20, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = grid.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, grid.getWidth(), grid.getHeight());
        g.dispose();
        for (int i = 0; i < side; i++) {
            for (int j = 0; j < side; j++) {
                cornerSquare(grid, 10 + i * step, 10 + j * step, 1 + (i + j) % 3);
            }
        }
        return grid;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void aQrCodeFillingThePhotoIsRead() {
        assertThat(QrCodes.read(qr(ZALO, 600))).containsExactly(ZALO);
    }

    @Test
    void aQrCodeWithALogoInTheMiddleIsRead() {
        BufferedImage image = qr(ZALO, 800);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // A round logo over the centre, about a fifth of the code wide, as on Zalo and LINE friend codes
        g.setColor(Color.WHITE);
        g.fillOval(320, 320, 160, 160);
        g.setColor(new Color(0, 104, 255));
        g.fillOval(330, 330, 140, 140);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 40));
        g.drawString("Zalo", 355, 415);
        g.dispose();
        assertThat(QrCodes.read(image)).containsExactly(ZALO);
    }

    @Test
    void aSmallQrCodeOnAFlyerInABigPhotoIsRead() {
        BufferedImage photo = scene(1600, 1200);
        Graphics2D g = photo.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(1180, 760, 300, 380);                               // the flyer
        g.drawImage(qr("https://line.me/ti/p/scam123", 220), 1220, 900, null);
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
        g.drawString("Việc nhẹ", 1210, 820);
        g.dispose();
        assertThat(QrCodes.read(photo)).containsExactly("https://line.me/ti/p/scam123");
    }

    @Test
    void aLightQrCodeOnADarkBackgroundIsRead() {
        BufferedImage image = qr(ZALO, 500);
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                image.setRGB(x, y, image.getRGB(x, y) == 0xFF000000 ? 0xFFFFFF : 0x101010);
            }
        }
        assertThat(QrCodes.read(image)).containsExactly(ZALO);
    }

    @Test
    void aTiltedQrCodeIsRead() {
        BufferedImage tilted = new BufferedImage(900, 900, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = tilted.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 900, 900);
        g.rotate(Math.toRadians(25), 450, 450);
        g.drawImage(qr(ZALO, 500), 200, 200, null);
        g.dispose();
        assertThat(QrCodes.read(tilted)).containsExactly(ZALO);
    }

    @Test
    void everyQrCodeOfAPhotoIsReadOnce() {
        BufferedImage photo = new BufferedImage(1400, 700, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = photo.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1400, 700);
        g.drawImage(qr("https://zalo.me/a", 400), 100, 150, null);
        g.drawImage(qr("https://zalo.me/b", 400), 800, 150, null);
        g.dispose();
        assertThat(QrCodes.read(photo)).containsExactlyInAnyOrder("https://zalo.me/a", "https://zalo.me/b");
    }

    @Test
    void aPhotoWithoutAQrCodeGivesNothingNotEvenForAnOrdinaryBarcode() throws Exception {
        assertThat(QrCodes.read(scene(1600, 1200))).isEmpty();

        // The barcode on a product (EAN-13) is not a QR code
        BitMatrix ean = new EAN13Writer().encode("4901234567894", BarcodeFormat.EAN_13, 600, 200);
        BufferedImage product = new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = product.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 800, 400);
        g.dispose();
        for (int x = 0; x < ean.getWidth(); x++) {
            for (int y = 0; y < ean.getHeight(); y++) {
                if (ean.get(x, y)) {
                    product.setRGB(100 + x, 100 + y, 0x000000);
                }
            }
        }
        assertThat(QrCodes.read(product)).isEmpty();

        // A chessboard and a grid of squares look a bit like modules but are no code
        BufferedImage pattern = new BufferedImage(800, 800, BufferedImage.TYPE_INT_RGB);
        Graphics2D p = pattern.createGraphics();
        p.setColor(Color.WHITE);
        p.fillRect(0, 0, 800, 800);
        p.setColor(Color.BLACK);
        p.setStroke(new BasicStroke(6));
        for (int i = 0; i < 800; i += 50) {
            p.drawLine(i, 0, i, 800);
            p.drawLine(0, i, 800, i);
        }
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                if ((x + y) % 2 == 0) {
                    p.fillRect(x * 100, y * 100, 50, 50);
                }
            }
        }
        p.dispose();
        assertThat(QrCodes.read(pattern)).isEmpty();
        assertThat(QrCodes.read(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB))).isEmpty();
    }

    @Test
    void aLongTextIsKeptToFiveHundredCharacters() {
        String text = "https://example.com/" + "a".repeat(900);
        assertThat(QrCodes.read(qr(text, 900))).singleElement()
                .satisfies(t -> assertThat(t).hasSize(QrCodes.TEXT_MAX).startsWith("https://example.com/aaa"));
    }

    @Test
    void aCodePhotographedAtAnAngleIsRead() {
        BufferedImage photo = new BufferedImage(1400, 1000, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = photo.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1400, 1000);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        AffineTransform slanted = new AffineTransform();
        slanted.translate(300, 150);
        slanted.shear(0.3, 0);                                         // a poster seen from the side
        g.drawImage(qr(ZALO, 500), slanted, null);
        g.dispose();
        assertThat(QrCodes.read(photo)).containsExactly(ZALO);
    }

    @Test
    void aLightCodeIsReadEvenWithCornerLikeSquaresElsewhereInThePhoto() {
        BufferedImage code = qr(ZALO, 500);
        for (int x = 0; x < code.getWidth(); x++) {
            for (int y = 0; y < code.getHeight(); y++) {
                code.setRGB(x, y, (code.getRGB(x, y) & 0xFFFFFF) == 0 ? 0xFFFFFF : 0x101010);
            }
        }
        BufferedImage photo = new BufferedImage(1400, 700, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = photo.createGraphics();
        g.setColor(new Color(16, 16, 16));
        g.fillRect(0, 0, 1400, 700);
        g.setColor(Color.WHITE);
        g.fillRect(40, 40, 600, 600);                                  // a label with three square "logo" marks
        g.drawImage(code, 800, 100, null);
        g.dispose();
        cornerSquare(photo, 100, 100, 10);
        cornerSquare(photo, 400, 100, 10);
        cornerSquare(photo, 100, 400, 10);
        assertThat(QrCodes.read(photo)).containsExactly(ZALO);
    }

    @Test
    void aPhotoMadeOfHundredsOfCornerSquaresIsDealtWithQuicklyInsteadOfBlockingTheServer() throws IOException {
        // ZXing's multi-code finder needs minutes (or runs out of memory) on such grids; read stays fast: it answers
        // nothing or refuses the photo (TooComplex), within the time budget
        MediaService service = new MediaService(null, null, Clock.systemUTC(), 50);
        for (BufferedImage crafted : List.of(cornerGrid(20, 2), cornerGrid(40, 2), cornerGrid(100, 1),
                cornerGrid(60, 3), mixedCornerGrid(50))) {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                try {
                    assertThat(QrCodes.read(crafted)).isEmpty();
                } catch (QrCodes.TooComplex refused) {
                    // refused: fine too
                }
            });
            // Through the upload: a short answer, a photo without a code or the refusal message, never a 500
            byte[] png = png(crafted);
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                try {
                    assertThat(service.prepareImage(new MockMultipartFile("images", "grid.png", "image/png", png))
                            .qrCodes()).isEmpty();
                } catch (InvalidMediaException refused) {
                    assertThat(refused).hasMessage(MediaService.QR_UNCHECKED);
                }
            });
        }
    }

    @Test
    void theSearchStopsWhenItsBudgetIsUsedUp() {
        BufferedImage wall = new BufferedImage(1000, 500, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = wall.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1000, 500);
        g.drawImage(qr("https://example.com/a", 400), 50, 50, null);
        g.drawImage(qr("https://example.com/b", 400), 550, 50, null);
        g.dispose();
        assertThat(QrCodes.read(wall)).isNotEmpty();
        // Too many possible corners for the budget, or no time left: refused
        assertThatThrownBy(() -> QrCodes.read(wall, 2, QrCodes.TIME_MAX_MS)).isInstanceOf(QrCodes.TooComplex.class);
        assertThatThrownBy(() -> QrCodes.read(wall, QrCodes.CANDIDATES_MAX, -1)).isInstanceOf(QrCodes.TooComplex.class);
    }

    @Test
    void manyRealCodesInOnePhotoAreStillRead() {
        BufferedImage wall = new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = wall.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1600, 1200);
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 3; j++) {
                g.drawImage(qr("https://example.com/" + i + j, 300), 50 + i * 380, 50 + j * 380, null);
            }
        }
        g.dispose();
        // Not a crafted image: no refusal, and the codes are read (ZXing's region-by-region search may miss one or two)
        assertThat(QrCodes.read(wall)).hasSizeGreaterThanOrEqualTo(8)
                .allSatisfy(text -> assertThat(text).startsWith("https://example.com/"));
    }

    @Test
    void theUploadedPhotoCarriesItsQrCodesAndTheStoredJpegStillHoldsTheCode() throws IOException {
        MediaService service = new MediaService(null, null, Clock.systemUTC(), 50);
        BufferedImage big = scene(3200, 2400);
        Graphics2D g = big.createGraphics();
        g.drawImage(qr(ZALO, 900), 1100, 700, null);
        g.dispose();

        MediaService.Prepared photo = service.prepareImage(new MockMultipartFile("images", "qr.png", "image/png",
                png(big)));
        assertThat(photo.width()).isEqualTo(1600);
        assertThat(photo.qrCodes()).containsExactly(ZALO);
        // What viewers get (the stored JPEG) still holds the code: reading the stored file finds it too
        assertThat(QrCodes.read(ImageIO.read(new ByteArrayInputStream(photo.bytes())))).containsExactly(ZALO);

        MediaService.Prepared plain = service.prepareImage(new MockMultipartFile("images", "street.png", "image/png",
                png(scene(1200, 900))));
        assertThat(plain.qrCodes()).isEmpty();
        assertThat(MediaService.qrCodesOfPhotos(List.of(photo, plain))).containsExactly(
                List.of(ZALO), List.of());
    }
}
