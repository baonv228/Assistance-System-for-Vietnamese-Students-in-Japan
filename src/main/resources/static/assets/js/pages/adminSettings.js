// =========================================================
// ASVSJ - Admin: Cấu hình & Từ khóa (ghép ba thẻ: tham số chống spam, từ khóa cấm, tham số khác)
// =========================================================
import { initAdminPage } from "../components/admin-layout.js";
import { adminClient } from "../api/admin-client.js";
import { esc, ic } from "../lib/ui.js";
import { guardLeavingPage, mountSafety } from "./adminSafety.js";
import { mountKeywords } from "./adminKeywords.js";
import { mountOtherParams } from "./adminOtherParams.js";

async function main() {
    const user = await initAdminPage("config");
    if (!user) return;
    const mainEl = document.getElementById("adminMainContent");
    mainEl.innerHTML = `
        <div class="admin-page-header">
            <div>
                <h2>Cấu hình &amp; Từ khóa</h2>
                <p>Giới hạn chống spam, ngưỡng báo cáo, từ khóa cấm và các tham số vận hành của hệ thống.</p>
            </div>
        </div>
        <div id="safetyArea"></div>
        <div id="keywordArea"></div>
        <div id="otherArea"></div>`;
    guardLeavingPage();
    try {
        await mountSafety(document.getElementById("safetyArea"));
        await Promise.all([
            mountKeywords(document.getElementById("keywordArea")),
            mountOtherParams(document.getElementById("otherArea"), await safetyKeys())
        ]);
    } catch (err) {
        mainEl.innerHTML = `
            <div class="alert error">${ic("error")} Không tải được dữ liệu. Vui lòng thử lại sau.</div>
            <div><button type="button" class="btn sm" id="retryBtn">${ic("refresh")} Thử lại</button></div>
            <p class="note">${esc(err.message)}</p>`;
        document.getElementById("retryBtn")?.addEventListener("click", () => location.reload());
    }
}

/** Khóa các tham số đã có ở hai thẻ chống spam (để bảng "khác" không lặp lại chúng). */
async function safetyKeys() {
    const groups = await adminClient.getSafetyConfig();
    return groups.flatMap((g) => g.params.map((p) => p.key));
}

main();
