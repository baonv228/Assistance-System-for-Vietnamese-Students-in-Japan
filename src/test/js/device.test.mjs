// Tests of the browser guesses behind the chat pages' phone advice (assets/js/lib/device.js), with real user agents.
// Run: node --test src/test/js/

import { test } from "node:test";
import assert from "node:assert/strict";
import { appleMobile, inAppBrowser } from "../../main/resources/static/assets/js/lib/device.js";

const UA = {
    iphoneSafari: "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
        + "Version/17.5 Mobile/15E148 Safari/604.1",
    iphoneChrome: "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
        + "CriOS/126.0.6478.54 Mobile/15E148 Safari/604.1",
    ipadDesktopMode: "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) "
        + "Version/17.5 Safari/605.1.15",
    androidChrome: "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) "
        + "Chrome/126.0.6478.71 Mobile Safari/537.36",
    windowsEdge: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
        + "Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0",
    facebookIos: "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
        + "Mobile/15E148 [FBAN/FBIOS;FBAV/470.0.0.40.97;FBBV/618139093;FBDV/iPhone15,2;FBMD/iPhone;FBSN/iOS]",
    messengerAndroid: "Mozilla/5.0 (Linux; Android 14; SM-S918B Build/UP1A; wv) AppleWebKit/537.36 (KHTML, like Gecko) "
        + "Version/4.0 Chrome/126.0.6478.71 Mobile Safari/537.36 [FB_IAB/Orca-Android;FBAV/465.0.0.38.109;]",
    zaloIos: "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
        + "Mobile/15E148 Zalo iOS/583 ZaloTheme/light ZaloLanguage/vn",
    lineIos: "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
        + "Mobile/15E148 Safari Line/14.9.0",
    instagram: "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
        + "Mobile/15E148 Instagram 337.0.3.23.54 (iPhone15,2; iOS 17_5; vi_VN; vi)"
};

test("in-app browsers of Facebook, Messenger, Zalo, LINE and Instagram are recognised; real browsers are not", () => {
    for (const name of ["facebookIos", "messengerAndroid", "zaloIos", "lineIos", "instagram"]) {
        assert.equal(inAppBrowser(UA[name]), true, name);
    }
    for (const name of ["iphoneSafari", "iphoneChrome", "ipadDesktopMode", "androidChrome", "windowsEdge"]) {
        assert.equal(inAppBrowser(UA[name]), false, name);
    }
    // a word that merely contains "Line/" is no LINE app
    assert.equal(inAppBrowser("Mozilla/5.0 Outline/1.0"), false);
    assert.equal(inAppBrowser(""), false);
});

test("iPhones, and iPads that show themselves as a Mac with a touch screen, are Apple mobile devices", () => {
    assert.equal(appleMobile(UA.iphoneSafari, 5), true);
    assert.equal(appleMobile(UA.iphoneChrome, 5), true);
    assert.equal(appleMobile(UA.ipadDesktopMode, 5), true);
    assert.equal(appleMobile(UA.ipadDesktopMode, 0), false);          // a real Mac
    assert.equal(appleMobile(UA.androidChrome, 5), false);
    assert.equal(appleMobile(UA.windowsEdge, 0), false);
});
