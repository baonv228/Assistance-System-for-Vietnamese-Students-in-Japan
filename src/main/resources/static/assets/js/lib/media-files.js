// Photos and videos picked in a form, before upload (Write Post, Đặt câu hỏi, event reviews, event covers).
// The server checks everything again; this only shrinks photos and catches obvious mistakes early.

export const IMAGE_TYPES = ["image/jpeg", "image/png", "image/heic", "image/heif"];
export const IMAGE_EXT = /\.(jpe?g|png|heic|heif)$/i;
export const VIDEO_TYPES = ["video/mp4", "video/quicktime"];
export const VIDEO_EXT = /\.(mp4|mov)$/i;
export const MAX_SIDE = 1600;
export const MB = 1024 * 1024;

export const isImageFile = (file) => IMAGE_TYPES.includes(file.type.toLowerCase()) || IMAGE_EXT.test(file.name);
export const isVideoFile = (file) => VIDEO_TYPES.includes(file.type.toLowerCase()) || VIDEO_EXT.test(file.name);

/** The message for a photo this browser could not open (HEIC outside Safari, or a broken file). */
export function unreadableImage(file) {
    const heic = /hei[cf]/i.test(file.type) || /\.hei[cf]$/i.test(file.name);
    return heic
        ? `Trình duyệt này không mở được ảnh HEIC “${file.name}”. Hãy chọn ảnh JPG hoặc PNG, hoặc dùng Safari.`
        : `Không mở được ảnh “${file.name}”. Tệp có thể bị hỏng; hãy chọn ảnh khác.`;
}

/** Draws the photo on a canvas at most MAX_SIDE px wide or tall and saves it as JPG (no EXIF, no GPS). */
export async function shrinkToJpeg(file) {
    const { source, width, height, release } = await openImage(file);
    try {
        const scale = Math.min(1, MAX_SIDE / Math.max(width, height));
        const canvas = document.createElement("canvas");
        canvas.width = Math.max(1, Math.round(width * scale));
        canvas.height = Math.max(1, Math.round(height * scale));
        const ctx = canvas.getContext("2d");
        ctx.fillStyle = "#fff";                         // transparent PNG areas become white, as on the server
        ctx.fillRect(0, 0, canvas.width, canvas.height);
        ctx.imageSmoothingQuality = "high";
        ctx.drawImage(source, 0, 0, canvas.width, canvas.height);
        return await new Promise((resolve, reject) => canvas.toBlob(
            (blob) => (blob ? resolve(blob) : reject(new Error("encode"))), "image/jpeg", 0.85));
    } finally {
        release();
    }
}

/** createImageBitmap applies the EXIF rotation; an <img> is the fallback (Safari opens HEIC this way). */
async function openImage(file) {
    if ("createImageBitmap" in window) {
        try {
            const bitmap = await createImageBitmap(file, { imageOrientation: "from-image" });
            return { source: bitmap, width: bitmap.width, height: bitmap.height, release: () => bitmap.close() };
        } catch {
            // fall through to <img>
        }
    }
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.src = url;
    try {
        await img.decode();
    } catch (error) {
        URL.revokeObjectURL(url);
        throw error;
    }
    return { source: img, width: img.naturalWidth, height: img.naturalHeight, release: () => URL.revokeObjectURL(url) };
}

/** Length in seconds, or null when this browser cannot open the video (the server still checks it). */
export function videoLength(url) {
    return new Promise((resolve) => {
        const video = document.createElement("video");
        const done = (value) => {
            clearTimeout(timer);
            resolve(value);
        };
        const timer = setTimeout(() => done(null), 8000);
        video.preload = "metadata";
        video.muted = true;
        video.onloadedmetadata = () => done(Number.isFinite(video.duration) ? video.duration : null);
        video.onerror = () => done(null);
        video.src = url;
    });
}
