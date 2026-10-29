// What kind of browser the page runs in, for the chat pages' advice and phone behaviour. These are guesses from the
// user agent and the screen: a feature is never switched off by them alone (the browser's own support is checked too).
// Importable in Node (tests): nothing is read until a function is called.

/**
 * The browsers built into Facebook, Messenger, Instagram, Zalo, LINE, TikTok, KakaoTalk, WeChat. Opened from a link
 * shared in those apps, Senpai ơi runs in them: no Web Push, often no microphone, and what the page keeps on the phone
 * (the chat encryption key) belongs to that app and may be cleared with it.
 */
const IN_APP = /\b(FBAN|FBAV|FB_IAB|FBIOS|Instagram|Zalo|Line\/|KAKAOTALK|musical_ly|BytedanceWebview|MicroMessenger)/;

const userAgent = () => globalThis.navigator?.userAgent || "";

/** iPhone, iPod, iPad (iPadOS shows itself as a Mac with a touch screen). */
export function appleMobile(ua = userAgent(), touchPoints = globalThis.navigator?.maxTouchPoints ?? 0) {
    return /iPhone|iPad|iPod/.test(ua) || (/Macintosh/.test(ua) && touchPoints > 1);
}

/** Opened from the home-screen icon ("Thêm vào MH chính"), not in a browser tab. */
export function homeScreenApp() {
    return globalThis.navigator?.standalone === true
        || Boolean(globalThis.matchMedia?.("(display-mode: standalone)").matches);
}

/** Inside another app's built-in browser (see IN_APP). */
export function inAppBrowser(ua = userAgent()) {
    return IN_APP.test(ua);
}

/** A touch screen without a mouse (phones, tablets): Enter then makes a new line, as in the phone's own apps. */
export function touchOnly() {
    return Boolean(globalThis.matchMedia?.("(hover: none) and (pointer: coarse)").matches);
}

/** Vietnamese advice for an in-app browser: what does not work there and how to leave it. */
export const IN_APP_ADVICE = "Bạn đang mở Senpai ơi trong trình duyệt của một ứng dụng (Facebook, Zalo, LINE…). Ở đây "
    + "thường không ghi âm, không nhận thông báo và không giữ được khóa mã hóa. Hãy bấm nút ⋯ (hoặc biểu tượng chia sẻ) "
    + "rồi chọn “Mở bằng trình duyệt” để mở trong Safari hoặc Chrome.";
