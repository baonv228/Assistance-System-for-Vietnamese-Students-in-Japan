package com.sep490.vsa.feature.media;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Reads the length of an MP4 or MOV video from its {@code moov/mvhd} box, in plain Java, so the server can
 * enforce the 30-second limit without installing ffprobe. Both formats use the ISO base media file layout
 * that phones record (Android MP4, iPhone MOV, H.264 or HEVC alike).
 */
public final class Mp4Duration {

    /** @param quickTime true for a MOV file (major brand "qt  ") */
    public record Info(double seconds, boolean quickTime) {
    }

    private Mp4Duration() {
    }

    /**
     * The video's length, or empty when the bytes are not an MP4/MOV file, have no readable duration (0), or
     * are fragmented ({@code moof} boxes: the movie header then does not cover the whole video, so its length
     * cannot be trusted; phone cameras do not write such files).
     */
    public static Optional<Info> read(byte[] data) {
        if (data == null || data.length < 16) {
            return Optional.empty();
        }
        boolean quickTime = false;
        long moovStart = -1;
        long moovEnd = -1;
        long pos = 0;
        while (pos + 8 <= data.length) {
            long size = u32(data, pos);
            String type = type(data, pos + 4);
            long header = 8;
            if (size == 1) {
                if (pos + 16 > data.length) {
                    return Optional.empty();
                }
                size = u64(data, pos + 8);
                header = 16;
            } else if (size == 0) {
                size = data.length - pos;           // the box runs to the end of the file
            }
            if (size < header || !isBoxType(type)) {
                return Optional.empty();
            }
            if ("ftyp".equals(type) && pos + header + 4 <= data.length) {
                quickTime = "qt  ".equals(type(data, pos + header));
            } else if ("moov".equals(type) && moovStart < 0) {
                moovStart = pos + header;
                moovEnd = Math.min(pos + size, data.length);
            } else if ("moof".equals(type)) {
                return Optional.empty();
            }
            pos += size;
        }
        if (moovStart < 0) {
            return Optional.empty();
        }
        boolean qt = quickTime;
        return mvhdSeconds(data, moovStart, moovEnd).filter(s -> s > 0).map(s -> new Info(s, qt));
    }

    /** Looks for the movie header inside {@code moov} and returns duration / timescale. */
    private static Optional<Double> mvhdSeconds(byte[] data, long start, long end) {
        long pos = start;
        while (pos + 8 <= end) {
            long size = u32(data, pos);
            String type = type(data, pos + 4);
            long header = 8;
            if (size == 1) {
                if (pos + 16 > end) {
                    return Optional.empty();
                }
                size = u64(data, pos + 8);
                header = 16;
            } else if (size == 0) {
                size = end - pos;
            }
            if (size < header) {
                return Optional.empty();
            }
            if ("mvhd".equals(type)) {
                long body = pos + header;
                if (body + 4 > end) {
                    return Optional.empty();
                }
                int version = data[(int) body] & 0xFF;
                long timescale;
                long duration;
                if (version == 1) {
                    // version/flags 4, creation 8, modification 8, timescale 4, duration 8
                    if (body + 32 > end) {
                        return Optional.empty();
                    }
                    timescale = u32(data, body + 20);
                    duration = u64(data, body + 24);
                    if (duration == -1L) {
                        return Optional.empty();
                    }
                } else {
                    // version/flags 4, creation 4, modification 4, timescale 4, duration 4
                    if (body + 20 > end) {
                        return Optional.empty();
                    }
                    timescale = u32(data, body + 12);
                    duration = u32(data, body + 16);
                    if (duration == 0xFFFFFFFFL) {
                        return Optional.empty();
                    }
                }
                if (timescale <= 0 || duration < 0) {
                    return Optional.empty();
                }
                return Optional.of((double) duration / timescale);
            }
            pos += size;
        }
        return Optional.empty();
    }

    private static boolean isBoxType(String type) {
        for (int i = 0; i < type.length(); i++) {
            char c = type.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    private static String type(byte[] data, long pos) {
        if (pos + 4 > data.length) {
            return "";
        }
        return new String(data, (int) pos, 4, StandardCharsets.ISO_8859_1);
    }

    private static long u32(byte[] data, long pos) {
        int p = (int) pos;
        return ((long) (data[p] & 0xFF) << 24) | ((data[p + 1] & 0xFF) << 16) | ((data[p + 2] & 0xFF) << 8)
                | (data[p + 3] & 0xFF);
    }

    private static long u64(byte[] data, long pos) {
        return (u32(data, pos) << 32) | u32(data, pos + 4);
    }
}
