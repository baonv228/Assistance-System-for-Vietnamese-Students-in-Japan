// Web Push for new chat messages on this browser (feature/chat/notify): register the service worker /sw.js,
// ask the permission, subscribe with the server's VAPID key and hand the subscription to the server.
//
// Needs a secure context (https, or http://localhost): on the LAN address (http://192.168.x.x) the browser has no
// service worker or Push API, and pushSupported() is false. iPhone/iPad Safari has Web Push only inside a
// home-screen app (iOS 16.4+, "Thêm vào MH chính"), so it is false in a Safari tab there.
//
// pushState(): "unsupported" (cannot here), "denied" (the member blocked notifications for this site: only the
// browser's site settings can undo it), "off" (not subscribed on this browser), "on".
// The member-wide switch (`pushEnabled` of /api/me/chats/notify/settings) is separate: enablePush() turns it on too,
// disablePush() only stops this browser.

import { apiClient } from "../api/client.js";
import { appleMobile, homeScreenApp } from "./device.js";

const WORKER = "/sw.js";

export function pushSupported() {
    if (typeof window === "undefined" || !window.isSecureContext) {
        return false;
    }
    if (!("serviceWorker" in navigator) || !("PushManager" in window) || !("Notification" in window)) {
        return false;
    }
    return !(appleMobile() && !homeScreenApp());
}

async function currentSubscription() {
    const registration = await navigator.serviceWorker.getRegistration("/");
    return registration ? registration.pushManager.getSubscription() : null;
}

export async function pushState() {
    if (!pushSupported()) {
        return "unsupported";
    }
    if (Notification.permission === "denied") {
        return "denied";
    }
    if (Notification.permission !== "granted") {
        return "off";
    }
    try {
        return (await currentSubscription()) ? "on" : "off";
    } catch {
        return "off";
    }
}

/**
 * Call it from a click: the permission prompt needs the click (Safari refuses it otherwise), so it is asked first.
 * Returns the new state; an API failure is thrown as the ApiError of client.js.
 */
export async function enablePush() {
    if (!pushSupported()) {
        return "unsupported";
    }
    const permission = Notification.permission === "default"
        ? await Notification.requestPermission()
        : Notification.permission;
    if (permission !== "granted") {
        return permission === "denied" ? "denied" : "off";
    }
    await navigator.serviceWorker.register(WORKER, { scope: "/" });
    const registration = await navigator.serviceWorker.ready;
    const { publicKey } = await apiClient.pushPublicKey();
    const serverKey = fromBase64Url(publicKey);

    let subscription = await registration.pushManager.getSubscription();
    // Made with another key (the server's keys were replaced): the push services would refuse it
    if (subscription && !sameBytes(subscription.options?.applicationServerKey, serverKey)) {
        await subscription.unsubscribe().catch(() => false);
        subscription = null;
    }
    if (!subscription) {
        subscription = await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: serverKey });
    }
    await apiClient.savePushSubscription(subscription.toJSON());
    const settings = await apiClient.chatNotifySettings();
    if (settings && settings.pushEnabled === false) {
        await apiClient.updateChatNotifySettings({ pushEnabled: true });
    }
    return "on";
}

/**
 * Stops push on this browser only; never throws. The browser's subscription is cancelled even when the server cannot
 * be told: the push service then answers 410 to the server's next push, and the server forgets it.
 */
export async function disablePush() {
    if (!pushSupported()) {
        return "unsupported";
    }
    const subscription = await currentSubscription().catch(() => null);
    if (subscription) {
        await apiClient.deletePushSubscription(subscription.endpoint).catch(() => null);
        await subscription.unsubscribe().catch(() => false);
    }
    return Notification.permission === "denied" ? "denied" : "off";
}

function fromBase64Url(text) {
    const base64 = text.replace(/-/g, "+").replace(/_/g, "/");
    const padded = base64 + "=".repeat((4 - (base64.length % 4)) % 4);
    const binary = atob(padded);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
        bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
}

/** Unknown (older browsers do not expose the key of a subscription) counts as the same. */
function sameBytes(buffer, bytes) {
    if (!buffer) {
        return true;
    }
    const other = new Uint8Array(buffer);
    return other.length === bytes.length && other.every((value, i) => value === bytes[i]);
}
