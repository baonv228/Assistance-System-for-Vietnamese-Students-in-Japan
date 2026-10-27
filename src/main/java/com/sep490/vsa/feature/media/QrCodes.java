package com.sep490.vsa.feature.media;

import com.google.zxing.Binarizer;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.ResultPointCallback;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import com.google.zxing.qrcode.QRCodeReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Finds the QR codes in a photo and reads what they hold, on the server and without any outside service (ZXing). A QR
 * code is a link the viewer only has to point a phone at, so the link rule (BR-22) applies to it
 * ({@code ContentFilter.checkQrCodes}): wherever it stands in the photo (a screenshot, a flyer, a poster in the street).
 * <p>
 * Only codes ZXing can actually read count; one too small, blurred or cut off to be read is left to the Gemini reading
 * of the scan. The photo is searched as it will be stored (at most 1600 px), several codes at a time and at an angle
 * too, with two ways of telling dark from light and once more with the colours inverted (light code on a dark
 * background); the first way that finds a code gives the answer.
 * <p>
 * The search is bounded: a photo made to look like hundreds of codes (a grid of the codes' corner squares) would keep
 * ZXing busy for minutes or exhaust the memory, so after {@value #CANDIDATES_MAX} possible corners, or
 * {@value #TIME_MAX_MS} ms, the search stops with {@link TooComplex} and the photo is refused. Ordinary photos take a
 * few tens of milliseconds and a few dozen corners. Any other reader error counts as no code for that way of looking.
 */
final class QrCodes {

    private static final Logger log = LoggerFactory.getLogger(QrCodes.class);
    /** Each text read is kept to this many characters (a code can hold about 3000). */
    static final int TEXT_MAX = 500;
    /** Possible corner squares one photo may show (12 real codes in one photo give about 300). */
    static final int CANDIDATES_MAX = 1_000;
    /** Time one photo may take, all ways of looking together. */
    static final long TIME_MAX_MS = 3_000;

    /** The photo shows too many possible codes to be checked (a crafted image): it is refused. */
    static final class TooComplex extends RuntimeException {
        TooComplex() {
            super("too many possible QR codes to check", null, false, false);
        }
    }

    private QrCodes() {
    }

    /**
     * The texts of the QR codes found in the photo, without repeats; empty when there is none.
     *
     * @throws TooComplex when the photo cannot be checked within the limits
     */
    static List<String> read(BufferedImage image) {
        return read(image, CANDIDATES_MAX, TIME_MAX_MS);
    }

    /** {@link #read(BufferedImage)} with other limits (tests). */
    static List<String> read(BufferedImage image, int candidatesMax, long timeMaxMs) {
        int width = image.getWidth();
        int height = image.getHeight();
        LuminanceSource source = new RGBLuminanceSource(width, height, pixels(image));
        long deadline = System.nanoTime() + timeMaxMs * 1_000_000;
        List<Function<LuminanceSource, Binarizer>> ways = List.of(HybridBinarizer::new, GlobalHistogramBinarizer::new,
                s -> new HybridBinarizer(s.invert()));
        for (Function<LuminanceSource, Binarizer> way : ways) {
            try {
                Set<String> texts = new LinkedHashSet<>();
                for (Result result : new GenericMultipleBarcodeReader(new QRCodeReader())
                        .decodeMultiple(new BinaryBitmap(way.apply(source)), hints(candidatesMax, deadline))) {
                    texts.add(cut(result.getText() == null ? "" : result.getText()));
                }
                if (!texts.isEmpty()) {
                    return new ArrayList<>(texts);
                }
            } catch (NotFoundException e) {
                // no code this way: try the next one
            } catch (TooComplex e) {
                throw e;
            } catch (RuntimeException e) {
                log.warn("Could not look for QR codes in a photo this way: {}", e.toString());
            }
        }
        return List.of();
    }

    /** ZXing's hints for one way of looking: try hard, and stop when the photo shows too many possible corners. */
    private static Map<DecodeHintType, Object> hints(int candidatesMax, long deadline) {
        int[] corners = {0};
        ResultPointCallback budget = point -> {
            if (++corners[0] > candidatesMax || System.nanoTime() > deadline) {
                throw new TooComplex();
            }
        };
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.NEED_RESULT_POINT_CALLBACK, budget);
        return hints;
    }

    /** The RGB pixels, straight from the image's own buffer when it is a plain RGB image (the stored photo is). */
    private static int[] pixels(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if ((image.getType() == BufferedImage.TYPE_INT_RGB || image.getType() == BufferedImage.TYPE_INT_ARGB)
                && image.getRaster().getDataBuffer() instanceof DataBufferInt buffer && buffer.getNumBanks() == 1
                && buffer.getOffset() == 0 && buffer.getData().length == width * height) {
            return buffer.getData();
        }
        return image.getRGB(0, 0, width, height, null, 0, width);
    }

    /** At most {@value #TEXT_MAX} characters, without splitting a surrogate pair. */
    private static String cut(String text) {
        if (text.length() <= TEXT_MAX) {
            return text;
        }
        return text.substring(0, Character.isHighSurrogate(text.charAt(TEXT_MAX - 1)) ? TEXT_MAX - 1 : TEXT_MAX);
    }
}
