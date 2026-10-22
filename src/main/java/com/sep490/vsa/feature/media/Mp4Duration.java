package com.sep490.vsa.feature.media;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the length of an MP4 or MOV video from its {@code moov/mvhd} box, in plain Java, so the server can
 * enforce the 30-second limit without installing ffprobe. Both formats use the ISO base media file layout
 * that phones record (Android MP4, iPhone MOV, H.264 or HEVC alike). Also removes the metadata boxes (location,
 * phone model) before the file is stored ({@link #stripMetadata}).
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
     * cannot be trusted; phone cameras do not write such files). A box size past the end of the file is read as
     * "up to the end", so every offset stays between 0 and {@code data.length} and crafted sizes cannot loop.
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
            // A 64-bit size can be up to 2^63 - 1: without this cap pos would overflow, wrap back into the file and
            // loop forever (or reach a negative offset). pos + header <= data.length was checked, so size >= header.
            size = Math.min(size, data.length - pos);
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
            size = Math.min(size, end - pos);       // same overflow guard as in read(): pos never passes end
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

    /** Boxes that only hold metadata: a phone's location, model and software, names, XMP. */
    private static final Set<String> METADATA_BOXES = Set.of("udta", "meta", "uuid");
    private static final byte[] FREE = "free".getBytes(StandardCharsets.ISO_8859_1);

    /**
     * Removes the metadata a phone writes into a video, like the EXIF a photo loses when it is re-encoded: where it was
     * filmed (iPhone {@code moov/meta} "com.apple.quicktime.location.ISO6709", {@code ©xyz} in {@code moov/udta} on
     * iPhone and Android), the phone model, software and names. The {@code udta}, {@code meta} and {@code uuid} boxes of
     * the file, of {@code moov} and of each track become {@code free} boxes (which players skip) filled with zeros, so
     * the values are gone from the file, not only hidden. Their sizes stay the same, so the offsets the player needs
     * ({@code stco}/{@code co64}) still point at the right bytes and the video plays as before. Changes {@code data} in
     * place; returns how many boxes it emptied.
     */
    public static int stripMetadata(byte[] data) {
        if (data == null) {
            return 0;
        }
        int stripped = 0;
        for (long[] box : children(data, 0, data.length)) {
            String type = type(data, box[0] + 4);
            if (METADATA_BOXES.contains(type)) {
                stripped += empty(data, box);
            } else if ("moov".equals(type)) {
                for (long[] child : children(data, box[1], box[2])) {
                    String childType = type(data, child[0] + 4);
                    if (METADATA_BOXES.contains(childType)) {
                        stripped += empty(data, child);
                    } else if ("trak".equals(childType)) {
                        for (long[] part : children(data, child[1], child[2])) {
                            if (METADATA_BOXES.contains(type(data, part[0] + 4))) {
                                stripped += empty(data, part);
                            }
                        }
                    }
                }
            }
        }
        return stripped;
    }

    /** Turns the box {start, body, end} into a "free" box of the same size whose body is all zeros. */
    private static int empty(byte[] data, long[] box) {
        System.arraycopy(FREE, 0, data, (int) box[0] + 4, 4);
        Arrays.fill(data, (int) box[1], (int) box[2], (byte) 0);
        return 1;
    }

    /**
     * The boxes in {@code [start, end)} as {start, body, end}, read like {@link #read}: a 64-bit size is followed, size 0
     * runs to the end, a size past the end is cut there, and the walk stops at the first box with a broken size.
     */
    private static List<long[]> children(byte[] data, long start, long end) {
        List<long[]> boxes = new ArrayList<>();
        long pos = start;
        while (pos + 8 <= end) {
            long size = u32(data, pos);
            long header = 8;
            if (size == 1) {
                if (pos + 16 > end) {
                    break;
                }
                size = u64(data, pos + 8);
                header = 16;
            } else if (size == 0) {
                size = end - pos;
            }
            if (size < header) {
                break;
            }
            size = Math.min(size, end - pos);
            boxes.add(new long[]{pos, pos + header, pos + size});
            pos += size;
        }
        return boxes;
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
