/* Bounded chunk hashing and transfer; no file-sized arrayBuffer is used. */
(() => {
"use strict";
class ControlSha256 {
    constructor() { this.h = new Uint32Array([0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19]); this.b = new Uint8Array(64); this.n = 0; this.length = 0n; }
    update(bytes) {
        this.length += BigInt(bytes.length);
        for (let offset = 0; offset < bytes.length;) {
            const size = Math.min(64 - this.n, bytes.length - offset);
            this.b.set(bytes.subarray(offset, offset + size), this.n); this.n += size; offset += size;
            if (this.n === 64) { this.block(); this.n = 0; }
        }
        return this;
    }
    block() {
        const k = ControlSha256.k, w = new Uint32Array(64), view = new DataView(this.b.buffer);
        const r = (x, n) => (x >>> n) | (x << (32 - n));
        for (let i = 0; i < 16; i++) w[i] = view.getUint32(i * 4);
        for (let i = 16; i < 64; i++) { const a = w[i - 15], b = w[i - 2]; w[i] = (w[i - 16] + (r(a,7)^r(a,18)^(a>>>3)) + w[i - 7] + (r(b,17)^r(b,19)^(b>>>10))) >>> 0; }
        let [a,b,c,d,e,f,g,h] = this.h;
        for (let i = 0; i < 64; i++) {
            const t = (h + (r(e,6)^r(e,11)^r(e,25)) + ((e&f)^(~e&g)) + k[i] + w[i]) >>> 0;
            const u = ((r(a,2)^r(a,13)^r(a,22)) + ((a&b)^(a&c)^(b&c))) >>> 0;
            h=g; g=f; f=e; e=(d+t)>>>0; d=c; c=b; b=a; a=(t+u)>>>0;
        }
        [a,b,c,d,e,f,g,h].forEach((x,i) => this.h[i] = (this.h[i] + x) >>> 0);
    }
    hex() {
        const bits = this.length * 8n, padding = new Uint8Array(this.n < 56 ? 64 - this.n : 128 - this.n);
        padding[0] = 128;
        new DataView(padding.buffer).setBigUint64(padding.length - 8, bits);
        this.update(padding);
        return Array.from(this.h, x => x.toString(16).padStart(8, "0")).join("");
    }
}
ControlSha256.k = new Uint32Array([0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2]);

if (typeof module !== "undefined" && module.exports) module.exports = ControlSha256;
if (typeof document !== "undefined") {
    const panel = document.getElementById("p2-control"); if (!panel) return;
    const byId = id => document.getElementById(id), status = byId("p2-status"), output = byId("p2-result");
    const api = "/api/control/v1", chunkSize = 4 * 1024 * 1024, csrf = panel.querySelector('input[name="__RequestVerificationToken"]').value;
    let profiles = [], nextCursor = null, nextBackup = null;
    let active = true, busy = false;
    const controllers = new Set(), listeners = [];
    const listen = (element, type, handler) => { element.addEventListener(type, handler); listeners.push([element, type, handler]); };
    const setStatus = value => { if (active) status.textContent = value; };
    const beforeNavigate = event => {
        if (!busy) return;
        event.preventDefault();
        setStatus("処理中です。完了するまで画面を移動できません。");
    };
    const stop = () => {
        if (!active) return;
        active = false;
        for (const controller of controllers) controller.abort();
        for (const [element, type, handler] of listeners) element.removeEventListener(type, handler);
        listeners.length = 0;
        window.removeEventListener("control:before-navigate", beforeNavigate);
        window.removeEventListener("control:before-page-change", stop);
        window.removeEventListener("pagehide", stop);
    };
    window.addEventListener("control:before-navigate", beforeNavigate);
    window.addEventListener("control:before-page-change", stop);
    window.addEventListener("pagehide", stop);
    async function request(path, method = "GET", body, extra = {}) {
        if (!active) throw new Error("画面が切り替わりました。");
        const headers = { ...extra };
        if (method !== "GET") headers["X-CSRF-TOKEN"] = csrf;
        if (body !== undefined && !(body instanceof Blob) && !(body instanceof Uint8Array)) { headers["Content-Type"] = "application/json"; body = JSON.stringify(body); }
        const controller = new AbortController();
        controllers.add(controller);
        try {
            const response = await fetch(api + path, { method, headers, body, cache: "no-store", credentials: "same-origin", redirect: "error", signal: controller.signal });
            if (!response.ok) { let error; try { error = await response.json(); } catch { error = {}; } throw new Error(error.errorCode || error.title || `HTTP ${response.status}`); }
            if (response.status === 204) return null;
            const result = await response.json();
            if (!active) throw new Error("画面が切り替わりました。");
            const check = value => { if (typeof value === "number" && !Number.isSafeInteger(value)) throw new Error("安全に表示できる整数範囲を超えています。"); if (value && typeof value === "object") Object.values(value).forEach(check); };
            check(result); return result;
        } finally { controllers.delete(controller); }
    }
    const keyHeaders = id => ({ "Idempotency-Key": id });
    function target(value = byId("file-path")?.value.trim() || "") { return { managedRootId: panel.dataset.rootId, relativePath: panel.dataset.directory + (value ? "/" + value : "") }; }
    async function operate(kind, payload, id = crypto.randomUUID()) {
        const serverId = panel.dataset.serverId;
        const [server, runtime] = await Promise.all([request(`/servers/${serverId}`), request(`/servers/${serverId}/runtime`)]);
        if (server.desiredRevision !== server.appliedRevision) throw new Error("StaleRevision: 設定の適用を待ってください。");
        const input = { kind, payload, configRevision: server.appliedRevision, expectedStateRevision: runtime.stateRevision, expectedServerSessionId: runtime.currentSessionId };
        const accepted = await request(`/servers/${serverId}/operations`, "POST", input, keyHeaders(id));
        const operationId = accepted.operationId;
        setStatus(`操作 ${operationId} を受け付けました。`);
        let operation;
        do {
            operation = await request(`/operations/${operationId}`);
            setStatus(`${operation.kind}: ${operation.status} (${operationId})`);
            if (["Failed", "Rejected", "Expired", "Cancelled", "NeedsAttention"].includes(operation.status)) throw new Error(`${operation.errorCode || operation.status} / 操作 ${operationId}。保守履歴を確認してください。`);
            if (operation.status !== "Succeeded") await new Promise(resolve => setTimeout(resolve, 1000));
        } while (operation.status !== "Succeeded");
        const result = await request(`/operations/${operationId}/result`);
        output.textContent = JSON.stringify(result, null, 2).slice(0, 8192);
        setStatus(`完了: ${operationId}`);
        return result;
    }
    async function exportArtifact(backupId = null) {
        const operationId = crypto.randomUUID();
        const transfer = await request("/transfers", "POST", { sourceHostId: panel.dataset.hostId, destinationHostId: null, operationId, serverId: panel.dataset.serverId, sizeBytes: null, sha256: null }, keyHeaders(crypto.randomUUID()));
        await operate("ExportFiles", { target: backupId ? null : target(), backupId, transferId: transfer.transferId }, operationId);
        setStatus(`ダウンロード準備完了。転送一覧で取得できます: ${transfer.transferId}`);
    }
    async function hashFile(file) {
        const hash = new ControlSha256();
        for (let offset = 0; offset < file.size; offset += chunkSize) {
            hash.update(new Uint8Array(await file.slice(offset, offset + chunkSize).arrayBuffer()));
            setStatus(`SHA-256確認 ${Math.min(offset + chunkSize, file.size)} / ${file.size} bytes`);
        }
        return hash.hex();
    }
    async function upload(file, existing = null) {
        if (file.size > 20 * 1024 ** 3) throw new Error("転送上限は20GiBです。");
        const sha256 = await hashFile(file);
        let transfer = existing;
        if (transfer && (transfer.sizeBytes !== file.size || transfer.sha256 !== sha256)) throw new Error("再開するファイルのサイズまたはSHA-256が異なります。");
        if (!transfer) {
            const cacheKey = `control-upload:${panel.dataset.serverId}:${sha256}`;
            const cached = localStorage.getItem(cacheKey);
            if (cached) { try { transfer = await request(`/transfers/${cached}`); } catch { /* An expired stage can be replaced. */ } }
            if (transfer) {
                let operation = null;
                try { operation = await request(`/operations/${transfer.operationId}`); } catch (error) { if (!error.message.includes("NotFound")) throw error; }
                if (operation) {
                    if (["Succeeded", "Failed", "Rejected", "Expired", "Cancelled"].includes(operation.status)) { localStorage.removeItem(cacheKey); transfer = null; }
                    else throw new Error(`対応する操作 ${operation.operationId}: ${operation.status}。保守履歴で確認してください。`);
                }
            }
            if (!transfer || !["Uploading", "Verified"].includes(transfer.status)) {
                transfer = await request("/transfers", "POST", { sourceHostId: null, destinationHostId: panel.dataset.hostId, operationId: crypto.randomUUID(), serverId: panel.dataset.serverId, sizeBytes: file.size, sha256 }, keyHeaders(crypto.randomUUID()));
                localStorage.setItem(cacheKey, transfer.transferId);
            }
        }
        while (transfer.committedOffset < file.size) {
            const offset = transfer.committedOffset, bytes = new Uint8Array(await file.slice(offset, offset + chunkSize).arrayBuffer());
            transfer = await request(`/transfers/${transfer.transferId}/chunks/${offset}`, "PUT", bytes, { "Content-Type": "application/octet-stream", "X-Chunk-Sha256": new ControlSha256().update(bytes).hex() });
            setStatus(`転送 ${transfer.committedOffset} / ${file.size} bytes · ${transfer.transferId}`);
        }
        if (transfer.status !== "Verified") transfer = await request(`/transfers/${transfer.transferId}/complete`, "POST", { sizeBytes: file.size, sha256 });
        return transfer;
    }
    function selectedBackup() { const selected = panel.querySelector('input[name="backupId"]:checked'); if (!selected) throw new Error("バックアップ世代を選択してください。"); return selected.value; }
    async function loadProfiles() {
        profiles = await request(`/backup-profiles?serverId=${panel.dataset.serverId}`);
        const select = byId("backup-profile"), old = select.value;
        select.replaceChildren(new Option("新しい設定", ""));
        for (const p of profiles) select.add(new Option(`${p.backupProfileId} (版 ${p.revision})`, p.backupProfileId));
        select.value = old;
    }
    function currentProfile() { const p = profiles.find(p => p.backupProfileId === byId("backup-profile").value); if (!p) throw new Error("保存済みのバックアップ設定を選択してください。"); return p; }
    async function loadBackups(append = false) {
        const data = await request(`/servers/${panel.dataset.serverId}/backups?limit=100${append && nextBackup ? `&cursor=${nextBackup}` : ""}`);
        const table = byId("backup-entries"); if (!append) table.replaceChildren();
        for (const b of data.rows) {
            const tr = table.insertRow(), cell = tr.insertCell(), radio = document.createElement("input"); radio.type = "radio"; radio.name = "backupId"; radio.value = b.backupId; radio.setAttribute("aria-label", b.backupId); cell.append(radio);
            for (const value of [new Date(b.createdAtUtc).toLocaleString(), b.state, `${b.sizeBytes} bytes`, b.protected ? "保護" : "なし"]) tr.insertCell().textContent = value;
        }
        nextBackup = data.nextCursor; byId("backups-next").hidden = !nextBackup;
    }
    async function listFiles(append = false) {
        const basePath = byId("file-path").value.trim().replace(/\/$/, "");
        const data = await operate("ListFiles", { target: target(), cursor: append ? nextCursor : null, limit: 200 });
        const table = byId("file-entries"); if (!append) table.replaceChildren();
        for (const entry of data.entries) {
            const tr = table.insertRow(), cell = tr.insertCell(), button = document.createElement("button"); button.type = "button"; button.textContent = entry.name;
            button.addEventListener("click", () => { byId("file-path").value = basePath + (basePath ? "/" : "") + entry.name; byId("file-etag").value = entry.etag || "absent"; }); cell.append(button);
            tr.insertCell().textContent = entry.isDirectory ? "フォルダ" : "ファイル"; tr.insertCell().textContent = `${entry.bytes} bytes`;
        }
        nextCursor = data.nextCursor; byId("list-next").hidden = !nextCursor;
    }
    const actions = {
        list: () => listFiles(), "list-next": () => listFiles(true),
        read: async () => { const data = await operate("ReadFile", { target: target() }); byId("file-content").value = data.contentUtf8; byId("file-content").readOnly = !data.encoding.startsWith("utf-8") || new TextEncoder().encode(data.contentUtf8).length > 65536; byId("file-etag").value = data.etag; setStatus(`文字コード ${data.encoding} / 改行 ${data.newline}`); },
        write: async () => { const result = await operate("WriteFile", { target: target(), expectedEtag: byId("file-etag").value, contentUtf8: byId("file-content").value }); byId("file-etag").value = result.etag; },
        "new-file": () => { byId("file-etag").value = "absent"; byId("file-content").value = ""; byId("file-content").readOnly = false; },
        mkdir: () => operate("FileMutation", { action: "CreateDirectory", source: target(), destination: null, expectedEtag: "absent" }),
        copy: () => mutate("Copy"), move: () => mutate("Move"), delete: () => mutate("Delete"), export: () => exportArtifact(),
        upload: async () => {
            const file = byId("upload-file").files[0]; if (!file) throw new Error("ファイルを選択してください。");
            if (byId("import-mode").value === "Provision" && !byId("eula-accepted").checked) throw new Error("EULAへの同意が必要です。");
            const transfer = await upload(file);
            const artifact = { transferId: transfer.transferId, sha256: transfer.sha256, sizeBytes: transfer.sizeBytes };
            if (byId("import-mode").value === "Provision") await operate("ProvisionServer", { artifact, templateId: panel.dataset.kind, eulaAccepted: true }, transfer.operationId);
            else await operate("ImportArtifact", { artifact, target: target(), mode: byId("import-mode").value, expectedEtag: byId("file-etag").value }, transfer.operationId);
        },
        "save-profile": async () => {
            const old = profiles.find(p => p.backupProfileId === byId("backup-profile").value), id = old?.backupProfileId || crypto.randomUUID();
            const profile = { backupProfileId: id, serverId: panel.dataset.serverId, revision: (old?.revision || 0) + 1, destinationRootId: byId("backup-destination").value, format: byId("backup-format").value, retentionCount: Number(byId("retention-count").value), retentionDays: Number(byId("retention-days").value), excludePatterns: byId("exclude-patterns").value.split(/\r?\n/).map(x => x.trim()).filter(Boolean), restartIfPreviouslyRunning: byId("backup-restart").checked };
            await request(`/backup-profiles${old ? "/" + id : ""}`, old ? "PUT" : "POST", profile, { ...keyHeaders(crypto.randomUUID()), ...(old ? { "If-Match": `"${old.revision}"` } : { "If-None-Match": "*" }) });
            await loadProfiles(); byId("backup-profile").value = id; setStatus("設定を保存しました。Agentの適用完了後に取得できます。");
        },
        "create-backup": async () => { const p = currentProfile(); await operate("CreateBackup", { backupId: crypto.randomUUID(), backupProfileId: p.backupProfileId, profileRevision: p.revision }); await loadBackups(); },
        "prune-backups": async () => { await operate("PruneBackups", { backupProfileId: currentProfile().backupProfileId }); await loadBackups(); },
        "refresh-backups": () => loadBackups(), "backups-next": () => loadBackups(true),
        protect: async () => { await operate("SetBackupProtection", { backupId: selectedBackup(), protected: true }); await loadBackups(); },
        unprotect: async () => { await operate("SetBackupProtection", { backupId: selectedBackup(), protected: false }); await loadBackups(); },
        restore: async () => { if (!byId("restore-confirm").checked) throw new Error("復元対象と範囲を確認してください。"); await operate("RestoreBackup", { backupId: selectedBackup(), mode: byId("restore-mode").value, restartAfterRestore: byId("restore-restart").checked }); await loadBackups(); },
        "delete-backup": async () => { if (!byId("backup-delete-confirm").checked) throw new Error("削除の確認が必要です。"); await operate("DeleteBackup", { backupId: selectedBackup(), reason: byId("backup-delete-reason").value }); await loadBackups(); },
        "export-backup": () => exportArtifact(selectedBackup())
    };
    function mutate(action) { if (action === "Delete" && !confirm(`退避削除する対象: ${byId("file-path").value}`)) return; return operate("FileMutation", { action, source: target(), destination: action === "Delete" ? null : target(byId("file-destination").value.trim()), expectedEtag: byId("file-etag").value }); }
    async function run(action) {
        if (!active || busy) return;
        busy = true;
        const buttons = panel.querySelectorAll("button"); buttons.forEach(b => b.disabled = true);
        try { await action(); } catch (error) { setStatus(error.message); } finally {
            busy = false;
            if (active) buttons.forEach(b => b.disabled = panel.dataset.enabled === "false");
        }
    }
    for (const button of panel.querySelectorAll("[data-action]")) listen(button, "click", () => run(actions[button.dataset.action]));
    if (byId("backup-profile")) listen(byId("backup-profile"), "change", () => { const p = profiles.find(p => p.backupProfileId === byId("backup-profile").value); if (!p) return; byId("backup-destination").value = p.destinationRootId; byId("backup-format").value = p.format; byId("retention-count").value = p.retentionCount; byId("retention-days").value = p.retentionDays; byId("exclude-patterns").value = p.excludePatterns.join("\n"); byId("backup-restart").checked = p.restartIfPreviouslyRunning; });
    if (panel.dataset.enabled === "false") panel.querySelectorAll("button").forEach(b => b.disabled = true);
    else if (panel.dataset.mode === "backups") loadProfiles().then(() => loadBackups()).catch(error => setStatus(error.message));
    else if (panel.dataset.mode === "transfers") initializeTransfers();

    async function initializeTransfers() {
        const refresh = async () => {
            const transfers = await request("/transfers"), table = byId("transfer-entries"); table.replaceChildren();
            for (const t of transfers) {
                const row = table.insertRow();
                for (const value of [t.transferId, t.status, `${t.committedOffset} / ${t.sizeBytes ?? "未確定"}`, new Date(t.expiresAtUtc).toLocaleString()]) row.insertCell().textContent = value;
                const cell = row.insertCell();
                if (t.destinationHostId === null && t.status === "Verified") { const b = document.createElement("button"); b.textContent = "保存"; b.addEventListener("click", () => run(() => download(t))); cell.append(b); }
                if (t.sourceHostId === null && t.status === "Uploading") { const input = document.createElement("input"); input.type = "file"; input.setAttribute("aria-label", "再開する同じファイル"); const b = document.createElement("button"); b.textContent = "転送を再開"; b.addEventListener("click", () => run(async () => { if (!input.files[0]) throw new Error("同じファイルを選択してください。"); await upload(input.files[0], t); await refresh(); })); cell.append(input, b); }
                if (t.destinationHostId && t.status === "Verified") { const link = document.createElement("a"); link.href = `/Servers/${t.serverId}/Files`; link.textContent = "取り込み先を開く（同じファイルの選択で再開）"; cell.append(link); }
            }
        };
        listen(byId("transfers-refresh"), "click", () => run(refresh));
        try { await refresh(); } catch (error) { setStatus(error.message); }
    }
    async function download(transfer) {
        let writer = null;
        try {
            // The picker is opened within the click gesture, before network awaits.
            if (window.showSaveFilePicker) writer = await (await window.showSaveFilePicker({ suggestedName: `${transfer.transferId}.bin` })).createWritable();
            else if (transfer.sizeBytes > 64 * 1024 * 1024) throw new Error("64MiBを超える保存はファイル保存APIに対応したブラウザで開いてください。");
            const parts = [], wholeHash = new ControlSha256();
            for (let offset = 0; offset < transfer.sizeBytes; offset += chunkSize) {
                const end = Math.min(transfer.sizeBytes - 1, offset + chunkSize - 1);
                const response = await fetch(`${api}/transfers/${transfer.transferId}/content`, { headers: { Range: `bytes=${offset}-${end}` }, cache: "no-store", redirect: "error" });
                if (response.status !== 206) throw new Error(`HTTP ${response.status}`);
                const bytes = new Uint8Array(await response.arrayBuffer());
                if (bytes.length !== end - offset + 1 || new ControlSha256().update(bytes).hex() !== response.headers.get("X-Chunk-Sha256")) throw new Error("HashMismatch");
                wholeHash.update(bytes); if (writer) await writer.write(bytes); else parts.push(bytes);
                setStatus(`保存 ${end + 1} / ${transfer.sizeBytes} bytes`);
            }
            if (wholeHash.hex() !== transfer.sha256) throw new Error("HashMismatch");
            if (writer) { await writer.close(); writer = null; }
            else { const url = URL.createObjectURL(new Blob(parts)); const a = document.createElement("a"); a.href = url; a.download = `${transfer.transferId}.bin`; a.click(); setTimeout(() => URL.revokeObjectURL(url), 10000); }
            setStatus("SHA-256を確認して保存しました。");
        } catch (error) { if (writer) await writer.abort(); setStatus(error.message); }
    }
}
})();
