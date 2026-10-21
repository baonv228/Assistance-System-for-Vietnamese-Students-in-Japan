package com.sep490.vsa.feature.media;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class Mp4DurationTest {

    private static byte[] box(String type, byte[] body) {
        ByteBuffer b = ByteBuffer.allocate(8 + body.length);
        b.putInt(8 + body.length).put(type.getBytes(StandardCharsets.ISO_8859_1)).put(body);
        return b.array();
    }

    private static byte[] ftyp(String brand) {
        return box("ftyp", ByteBuffer.allocate(8).put(brand.getBytes(StandardCharsets.ISO_8859_1)).putInt(0x200).array());
    }

    /** Movie header version 0: timescale and duration as 32-bit numbers. */
    private static byte[] mvhd0(int timescale, int duration) {
        ByteBuffer b = ByteBuffer.allocate(100);
        b.putInt(0).putInt(0).putInt(0).putInt(timescale).putInt(duration);
        return box("mvhd", b.array());
    }

    /** Movie header version 1: 64-bit times and duration. */
    private static byte[] mvhd1(int timescale, long duration) {
        ByteBuffer b = ByteBuffer.allocate(112);
        b.putInt(0x01000000).putLong(0).putLong(0).putInt(timescale).putLong(duration);
        return box("mvhd", b.array());
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    @Test
    void readsVersion0HeaderAfterTheMediaData() {
        // moov after mdat is what phones write when "fast start" is off
        byte[] file = concat(ftyp("isom"), box("mdat", new byte[2048]), box("moov", concat(mvhd0(1000, 12_500))));
        Mp4Duration.Info info = Mp4Duration.read(file).orElseThrow();
        assertThat(info.seconds()).isCloseTo(12.5, within(0.001));
        assertThat(info.quickTime()).isFalse();
    }

    @Test
    void readsVersion1HeaderAndQuickTimeBrand() {
        byte[] file = concat(ftyp("qt  "), box("moov", concat(box("udta", new byte[16]), mvhd1(600, 600L * 31))));
        Mp4Duration.Info info = Mp4Duration.read(file).orElseThrow();
        assertThat(info.seconds()).isCloseTo(31.0, within(0.001));
        assertThat(info.quickTime()).isTrue();
    }

    @Test
    void followsSixtyFourBitBoxSizes() {
        ByteBuffer large = ByteBuffer.allocate(16 + 64);
        large.putInt(1).put("mdat".getBytes(StandardCharsets.ISO_8859_1)).putLong(16 + 64);
        byte[] file = concat(ftyp("mp42"), large.array(), box("moov", mvhd0(90_000, 90_000 * 3)));
        assertThat(Mp4Duration.read(file).orElseThrow().seconds()).isCloseTo(3.0, within(0.001));
    }

    @Test
    void refusesFilesThatAreNotMp4() {
        assertThat(Mp4Duration.read("GIF89a not a video at all".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(Mp4Duration.read(new byte[0])).isEmpty();
        assertThat(Mp4Duration.read(null)).isEmpty();
        // An MP4 without a movie header has no duration
        assertThat(Mp4Duration.read(concat(ftyp("isom"), box("mdat", new byte[64])))).isEmpty();
        // A file cut off in the middle of the movie header must not throw
        byte[] whole = concat(ftyp("isom"), box("moov", mvhd0(1000, 5000)));
        assertThat(Mp4Duration.read(java.util.Arrays.copyOf(whole, 40))).isEmpty();
    }

    @Test
    void fragmentedOrZeroLengthVideosAreRefused() {
        // Fragmented MP4 (MediaRecorder, ffmpeg -movflags frag_keyframe+empty_moov): the header says 0 s
        byte[] fragmented = concat(ftyp("isom"), box("moov", mvhd0(1000, 0)), box("moof", new byte[32]),
                box("mdat", new byte[64]));
        assertThat(Mp4Duration.read(fragmented)).isEmpty();
        // Even with a duration in the header, fragments make it untrustworthy
        byte[] lying = concat(ftyp("isom"), box("moov", mvhd0(1000, 5000)), box("moof", new byte[32]));
        assertThat(Mp4Duration.read(lying)).isEmpty();
        assertThat(Mp4Duration.read(concat(ftyp("isom"), box("moov", mvhd0(1000, 0))))).isEmpty();
    }

    @Test
    void zeroTimescaleIsUnreadable() {
        assertThat(Mp4Duration.read(concat(ftyp("isom"), box("moov", mvhd0(0, 5000))))).isEmpty();
    }

    // ---------------------------------------------------------------- crafted box sizes (W1)

    /** A box with a 64-bit size: {@code 00000001 type size}. */
    private static byte[] largeBox(String type, long size) {
        return ByteBuffer.allocate(16).putInt(1).put(type.getBytes(StandardCharsets.ISO_8859_1)).putLong(size).array();
    }

    @Test
    void aHugeBoxSizeCannotWrapTheReaderBackToTheStart() {
        // 16 + 0x7FFFFFFFFFFFFFF0 overflows to Long.MIN_VALUE, whose int cast is 0: before the fix the reader went
        // back to the ftyp box and looped forever on a 32-byte upload
        byte[] file = concat(ftyp("isom"), largeBox("free", 0x7FFFFFFFFFFFFFF0L));
        assertThat(file).hasSize(32);
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThat(Mp4Duration.read(file)).isEmpty());
    }

    @Test
    void aHugeBoxSizeInsideTheMovieBoxCannotWrapEither() {
        // Same wrap inside moov: the second child jumps back to the first one, again and again
        byte[] file = concat(ftyp("isom"), box("moov", concat(box("free", new byte[8]), largeBox("free", 0x7FFFFFFFFFFFFFF0L))));
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThat(Mp4Duration.read(file)).isEmpty());
    }

    @Test
    void aBoxSizeLeadingToANegativeOffsetIsUnreadableNotAnError() {
        // The last box wraps the position to Long.MIN_VALUE + 8, where bytes 8..23 of the first box read as one more
        // 64-bit box that lands at -4: before the fix, ArrayIndexOutOfBoundsException (a 500 for the upload)
        byte[] file = concat(box("free", largeBox("skip", 0x7FFFFFFFFFFFFFF4L)), largeBox("free", 0x7FFFFFFFFFFFFFF0L));
        assertThat(file).hasSize(40);
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThat(Mp4Duration.read(file)).isEmpty());
    }
}
