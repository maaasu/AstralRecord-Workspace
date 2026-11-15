import { createRenderer } from "./renderer.js";

const canvas = document.querySelector("#scene");
const status = document.querySelector("#status");
const ticketId = document.querySelector("#ticket-id");
const revision = document.querySelector("#revision");
const changedCount = document.querySelector("#changed-count");
const viewMessage = document.querySelector("#view-message");
const resetButton = document.querySelector("#reset-view");
const numberFormat = new Intl.NumberFormat("ja-JP");
const connectionLabels = {
  loading: "接続中",
  connected: "ライブ接続中",
  reconnecting: "再接続中",
  error: "表示エラー",
};

function setConnection(connection) {
  if (status.dataset.connection !== connection) {
    status.dataset.connection = connection;
    status.textContent = connectionLabels[connection];
  }
}

function showMessage(message) {
  viewMessage.textContent = message;
  viewMessage.hidden = message === "";
}

function showProgress(scene) {
  const materials = scene.minecraftVersion ? `Java ${scene.minecraftVersion}` : "簡易表示";
  ticketId.textContent = `${scene.ticketId} · ${materials}`;
  ticketId.hidden = false;
  status.dataset.revision = String(scene.revision);
  revision.textContent = numberFormat.format(scene.revision);
  changedCount.textContent = numberFormat.format(scene.changedBlockCount);
}

function isIntegerInRange(value, min, max) {
  return Number.isSafeInteger(value) && value >= min && value <= max;
}

function decodeScene(data) {
  const { size, origin, palette, runs, revision: sequence, changedBlockCount } = data;
  if (!Array.isArray(size) || size.length !== 3 || !size.every(
    (value) => Number.isSafeInteger(value) && value > 0,
  ) || !Array.isArray(origin) || origin.length !== 3 || !origin.every(Number.isSafeInteger)
    || !Array.isArray(palette) || palette.length === 0 || !Array.isArray(runs)) {
    throw new Error("Invalid live scene");
  }
  const volume = size[0] * size[1] * size[2];
  if (!Number.isSafeInteger(volume) || !isIntegerInRange(sequence, 0, Number.MAX_SAFE_INTEGER)
    || !isIntegerInRange(changedBlockCount, 0, volume)) {
    throw new Error("Invalid live scene revision");
  }
  const blocks = new Uint32Array(volume);
  let start = 0;
  for (const [id, count] of runs) {
    if (!isIntegerInRange(id, 0, palette.length - 1)
      || !isIntegerInRange(count, 1, volume - start)) {
      throw new Error("Invalid block run");
    }
    blocks.fill(id, start, start + count);
    start += count;
  }
  if (start !== volume) {
    throw new Error("Incomplete live scene");
  }
  return {
    ticketId: data.ticketId, minecraftVersion: data.minecraftVersion,
    revision: sequence, changedBlockCount, size, origin, palette, blocks,
  };
}

function applyUpdates(scene, response) {
  const affected = new Set();
  for (const update of response.updates) {
    if (update.revision !== scene.revision + 1 || !Array.isArray(update.palette)
      || !Array.isArray(update.changes)
      || !isIntegerInRange(update.changedBlockCount, 0, scene.blocks.length)) {
      throw new Error("Invalid live edit");
    }
    for (const model of update.palette) {
      scene.palette.push(model);
    }
    for (const [index, id] of update.changes) {
      if (!isIntegerInRange(index, 0, scene.blocks.length - 1)
        || !isIntegerInRange(id, 0, scene.palette.length - 1)) {
        throw new Error("Invalid changed block");
      }
      scene.blocks[index] = id;
      affected.add(index);
    }
    scene.revision = update.revision;
    scene.changedBlockCount = update.changedBlockCount;
  }
  if (response.revision !== scene.revision) {
    throw new Error("Live update sequence is incomplete");
  }
  return affected;
}

async function readJson(path, signal) {
  const response = await fetch(path, {
    signal,
    cache: "no-store",
    mode: "same-origin",
    headers: { Accept: "application/json" },
  });
  if (!response.ok) {
    throw new Error(`Live session returned HTTP ${response.status}`);
  }
  return response.json();
}

function wait(delay) {
  return new Promise((resolve) => window.setTimeout(resolve, delay));
}

function startViewer() {
  let renderer;
  try {
    renderer = createRenderer(canvas);
  } catch (error) {
    console.error(error);
    setConnection("error");
    showMessage("3D表示を開始できません。ブラウザーの WebGL 2 が有効か確認してください。");
    return;
  }

  const controller = new AbortController();
  const { signal } = controller;
  let scene;
  let needsSnapshot = true;
  let contextLost = false;
  let connection = "loading";

  function updateConnection(next) {
    connection = next;
    if (!contextLost) {
      setConnection(connection);
    }
  }

  function render(action) {
    try {
      action();
      return true;
    } catch (error) {
      console.error(error);
      controller.abort();
      setConnection("error");
      showMessage("3D表示を続けられません。ページを再読み込みしてください。");
      resetButton.disabled = true;
      return false;
    }
  }

  canvas.addEventListener("webglcontextlost", () => {
    contextLost = true;
    setConnection("error");
    showMessage("3D表示を復旧しています。");
    resetButton.disabled = true;
  });

  canvas.addEventListener("webglcontextrestored", () => {
    if (signal.aborted) {
      return;
    }
    contextLost = false;
    updateConnection(connection);
    showMessage(scene ? "" : "建築データを取得しています");
    resetButton.disabled = !scene;
  });

  resetButton.addEventListener("click", () => {
    if (render(() => renderer.resetView())) {
      canvas.focus({ preventScroll: true });
    }
  });

  async function follow() {
    while (!signal.aborted) {
      try {
        if (needsSnapshot) {
          scene = decodeScene(await readJson("/api/state", signal));
          if (!render(() => renderer.setScene(scene))) {
            return;
          }
          showProgress(scene);
          if (!contextLost) {
            showMessage("");
            resetButton.disabled = false;
          }
          needsSnapshot = false;
        }
        updateConnection("connected");
        const response = await readJson(`/api/changes?since=${scene.revision}`, signal);
        if (response.reset) {
          needsSnapshot = true;
          continue;
        }
        const affected = applyUpdates(scene, response);
        if (affected.size && !render(() => renderer.update([...affected]))) {
          return;
        }
        showProgress(scene);
      } catch {
        if (signal.aborted) {
          return;
        }
        updateConnection("reconnecting");
        if (!scene && !contextLost) {
          showMessage("ライブセッションに再接続しています。");
        }
        needsSnapshot = true;
        await wait(1000);
      }
    }
  }

  void follow();
}

startViewer();
