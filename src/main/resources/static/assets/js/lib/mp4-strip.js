// Removes the metadata a phone writes into an MP4/MOV video (where it was filmed, the phone model, software, names)
// before the video is encrypted for "Tin nhắn": the server cannot do it for encrypted files. Port of the server's
// Mp4Duration (read + stripMetadata) with the same reading rules: a box size of 0 runs to the end, 1 is followed by a
// 64-bit size, a size past the end is read as "up to the end", so every offset stays inside the buffer and crafted
// sizes cannot loop.

/** Boxes that only hold metadata: a phone's location, model and software, names, XMP. */
const METADATA_BOXES = new Set(["udta", "meta", "uuid"]);
const FREE = [0x66, 0x72, 0x65, 0x65];                 // "free"

/**
 * A copy of the video whose `udta`, `meta` and `uuid` boxes (of the file, of `moov` and of each track) are zero-filled
 * `free` boxes of the same size (players skip them; the offsets the player needs are unchanged, so it plays as before).
 * Same length as the input, which is left untouched. Null when the file cannot be read as the server would read it
 * (not MP4/MOV, no duration, fragmented) or a box inside `moov` or a track has a broken size, so metadata after it
 * could not be found; the page then says "Không đọc được video". Never throws.
 */
export function stripMp4Metadata(bytes) {
    try {
        const data = copyOf(bytes);
        if (!data || !readMp4Info(data)) {
            return null;
        }
        const top = boxes(data, 0, data.length);
        if (!top) {
            return null;
        }
        for (const box of top) {
            const type = typeAt(data, box.start + 4);
            if (METADATA_BOXES.has(type)) {
                empty(data, box);
            } else if (type === "moov" && !stripMoov(data, box)) {
                return null;
            }
        }
        return data;
    } catch {
        return null;
    }
}

/**
 * The video's length from `moov/mvhd`, as `{ seconds, quickTime }` (quickTime = major brand "qt  "), or null like the
 * server's Mp4Duration.read: not an MP4/MOV, no readable duration, or fragmented (`moof`: the header then does not
 * cover the whole video). Lets the page check the 30-second limit even when the browser cannot play the codec.
 */
export function readMp4Info(bytes) {
    try {
        const data = bytes instanceof Uint8Array ? bytes : copyOf(bytes);
        return data ? info(data) : null;
    } catch {
        return null;
    }
}

function info(data) {
    if (data.length < 16) {
        return null;
    }
    let quickTime = false;
    let moov = null;
    let pos = 0;
    while (pos + 8 <= data.length) {
        const box = boxAt(data, pos, data.length);
        const type = typeAt(data, pos + 4);
        if (!box || !isBoxType(type)) {
            return null;
        }
        if (type === "ftyp" && box.body + 4 <= data.length) {
            quickTime = typeAt(data, box.body) === "qt  ";
        } else if (type === "moov" && !moov) {
            moov = box;
        } else if (type === "moof") {
            return null;
        }
        pos = box.end;
    }
    const seconds = moov ? mvhdSeconds(data, moov.body, moov.end) : null;
    return seconds != null && seconds > 0 ? { seconds, quickTime } : null;
}

/** duration / timescale of the movie header inside `moov`, or null. */
function mvhdSeconds(data, start, end) {
    let pos = start;
    while (pos + 8 <= end) {
        const box = boxAt(data, pos, end);
        if (!box) {
            return null;
        }
        if (typeAt(data, pos + 4) === "mvhd") {
            const body = box.body;
            if (body + 4 > end) {
                return null;
            }
            let timescale;
            let duration;
            if (data[body] === 1) {
                // version/flags 4, creation 8, modification 8, timescale 4, duration 8
                if (body + 32 > end) {
                    return null;
                }
                timescale = u32(data, body + 20);
                const high = u32(data, body + 24);
                if (high >= 0x80000000) {
                    return null;                        // negative (or -1 = unknown) as a signed 64-bit number
                }
                duration = high * 0x100000000 + u32(data, body + 28);
            } else {
                // version/flags 4, creation 4, modification 4, timescale 4, duration 4
                if (body + 20 > end) {
                    return null;
                }
                timescale = u32(data, body + 12);
                duration = u32(data, body + 16);
                if (duration === 0xFFFFFFFF) {
                    return null;                        // unknown duration
                }
            }
            return timescale > 0 ? duration / timescale : null;
        }
        pos = box.end;
    }
    return null;
}

/** Empties the metadata boxes of `moov` and of each `trak`; false when one of those levels has a broken box. */
function stripMoov(data, moov) {
    const children = boxes(data, moov.body, moov.end);
    if (!children) {
        return false;
    }
    for (const child of children) {
        const type = typeAt(data, child.start + 4);
        if (METADATA_BOXES.has(type)) {
            empty(data, child);
        } else if (type === "trak") {
            const parts = boxes(data, child.body, child.end);
            if (!parts) {
                return false;
            }
            for (const part of parts) {
                if (METADATA_BOXES.has(typeAt(data, part.start + 4))) {
                    empty(data, part);
                }
            }
        }
    }
    return true;
}

/** Turns the box into a "free" box of the same size whose body is all zeros. */
function empty(data, box) {
    data.set(FREE, box.start + 4);
    data.fill(0, box.body, box.end);
}

/** The boxes in [start, end), or null at the first broken size. Fewer than 8 bytes left at the end are ignored. */
function boxes(data, start, end) {
    const list = [];
    let pos = start;
    while (pos + 8 <= end) {
        const box = boxAt(data, pos, end);
        if (!box) {
            return null;
        }
        list.push(box);
        pos = box.end;
    }
    return list;
}

/**
 * The box at `pos` as { start, body, end }, its end cut at `end`, or null when its size is broken: smaller than its
 * header, a 64-bit size without room for it, or a 64-bit size with the top bit set (negative for the server's Java).
 * end > pos always, so a walk moves forward at every box.
 */
function boxAt(data, pos, end) {
    let size = u32(data, pos);
    let header = 8;
    if (size === 1) {
        if (pos + 16 > end) {
            return null;
        }
        const high = u32(data, pos + 8);
        if (high >= 0x80000000) {
            return null;
        }
        size = high * 0x100000000 + u32(data, pos + 12);   // above 2^53 inexact, but then far past any end anyway
        header = 16;
    } else if (size === 0) {
        size = end - pos;                                   // the box runs to the end of its parent
    }
    if (size < header) {
        return null;
    }
    return { start: pos, body: pos + header, end: pos + Math.min(size, end - pos) };
}

function isBoxType(type) {
    if (type.length !== 4) {
        return false;
    }
    for (let i = 0; i < 4; i++) {
        const c = type.charCodeAt(i);
        if (c < 0x20 || c > 0x7E) {
            return false;
        }
    }
    return true;
}

/** The four characters at `pos` (ISO-8859-1), or "" past the end. */
function typeAt(data, pos) {
    if (pos + 4 > data.length) {
        return "";
    }
    return String.fromCharCode(data[pos], data[pos + 1], data[pos + 2], data[pos + 3]);
}

function u32(data, pos) {
    return data[pos] * 0x1000000 + ((data[pos + 1] << 16) | (data[pos + 2] << 8) | data[pos + 3]);
}

/** A fresh Uint8Array copy of an ArrayBuffer or a typed array / DataView, else null. */
function copyOf(bytes) {
    if (bytes instanceof ArrayBuffer) {
        return new Uint8Array(bytes.slice(0));
    }
    if (ArrayBuffer.isView(bytes)) {
        return new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength).slice();
    }
    return null;
}
