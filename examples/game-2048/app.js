(() => {
  "use strict";

  const Core = window.Game2048Core;
  const SAVE_KEY = "game-state-v1";
  const DIRECTION_LABELS = { left: "左", right: "右", up: "上", down: "下" };
  const KEY_DIRECTIONS = {
    ArrowLeft: "left",
    ArrowRight: "right",
    ArrowUp: "up",
    ArrowDown: "down",
    a: "left",
    A: "left",
    d: "right",
    D: "right",
    w: "up",
    W: "up",
    s: "down",
    S: "down"
  };
  const $ = (id) => document.getElementById(id);

  let state = {
    board: Array(Core.CELL_COUNT).fill(0),
    score: 0,
    best: 0,
    moveCount: 0,
    wonAcknowledged: false
  };
  let previousState = null;
  let ready = false;
  let storageAvailable = false;
  let saveQueue = Promise.resolve();
  let gesture = null;

  function toolbox() {
    return window.ToolBox;
  }

  function snapshot(value = state) {
    return {
      board: value.board.slice(),
      score: value.score,
      best: value.best,
      moveCount: value.moveCount,
      wonAcknowledged: value.wonAcknowledged
    };
  }

  function safeInteger(value, fallback = 0) {
    return Number.isSafeInteger(value) && value >= 0 ? value : fallback;
  }

  function sanitizeSaved(value) {
    if (!value || typeof value !== "object" || !Core.isBoard(value.board)) return null;
    const score = safeInteger(value.score);
    return {
      board: value.board.slice(),
      score,
      best: Math.max(score, safeInteger(value.best)),
      moveCount: safeInteger(value.moveCount),
      wonAcknowledged: value.wonAcknowledged === true
    };
  }

  async function restoreSavedGame() {
    const api = toolbox();
    if (!api?.ready || !api?.storage?.get) return null;
    try {
      await api.ready();
      const saved = await api.storage.get(SAVE_KEY);
      storageAvailable = true;
      return sanitizeSaved(saved);
    } catch (_) {
      storageAvailable = false;
      return null;
    }
  }

  function queueSave() {
    if (!storageAvailable) return;
    const value = snapshot();
    saveQueue = saveQueue
      .catch(() => undefined)
      .then(async () => {
        try {
          await toolbox().storage.set(SAVE_KEY, value);
        } catch (_) {
          storageAvailable = false;
          renderMeta();
        }
      });
  }

  async function haptic(effect, fromTouch) {
    if (!fromTouch || !toolbox()?.haptics?.perform) return;
    try { await toolbox().haptics.perform(effect); } catch (_) {}
  }

  function freshState(best = state.best) {
    return {
      board: Core.createBoard(),
      score: 0,
      best,
      moveCount: 0,
      wonAcknowledged: false
    };
  }

  function valueClass(value) {
    return value <= 2048 ? `tile-v${value}` : "tile-super";
  }

  function boardSummary() {
    const rows = [];
    for (let row = 0; row < Core.SIZE; row += 1) {
      const values = state.board
        .slice(row * Core.SIZE, (row + 1) * Core.SIZE)
        .map((value) => value || "空");
      rows.push(`第 ${row + 1} 行：${values.join("，")}`);
    }
    return `2048 棋盘。${rows.join("；")}`;
  }

  function renderBoard(markers = {}) {
    const born = new Set(markers.bornIndices || []);
    const merged = new Set(markers.mergedIndices || []);
    const grid = $("board-grid");
    grid.replaceChildren();

    for (let row = 0; row < Core.SIZE; row += 1) {
      const rowElement = document.createElement("div");
      rowElement.className = "board-row";
      rowElement.setAttribute("role", "row");
      for (let column = 0; column < Core.SIZE; column += 1) {
        const index = row * Core.SIZE + column;
        const value = state.board[index];
        const cell = document.createElement("div");
        cell.className = "board-cell";
        cell.setAttribute("role", "gridcell");
        cell.setAttribute("aria-label", value ? String(value) : "空");

        if (value) {
          const tile = document.createElement("div");
          tile.className = `tile ${valueClass(value)}`;
          if (born.has(index)) tile.classList.add("tile-born");
          if (merged.has(index)) tile.classList.add("tile-merged");
          tile.textContent = String(value);
          tile.setAttribute("aria-hidden", "true");
          cell.append(tile);
        }
        rowElement.append(cell);
      }
      grid.append(rowElement);
    }
    $("board").setAttribute("aria-label", boardSummary());
  }

  function renderMeta() {
    $("score").textContent = state.score.toLocaleString("zh-CN");
    $("best-score").textContent = state.best.toLocaleString("zh-CN");
    $("move-count").textContent = state.moveCount.toLocaleString("zh-CN");
    $("save-state").textContent = storageAvailable ? "自动保存" : "当前会话";
    $("undo").disabled = previousState === null;
  }

  function render(markers) {
    renderBoard(markers);
    renderMeta();
  }

  function announce(message) {
    $("move-status").textContent = message;
  }

  function hideResult() {
    $("result-overlay").hidden = true;
  }

  function showResult(type) {
    const isWin = type === "win";
    const overlay = $("result-overlay");
    $("result-kicker").textContent = isWin ? "目标达成" : "本局结束";
    $("result-title").textContent = isWin ? "合出了 2048" : "棋盘已满";
    $("result-message").textContent = isWin
      ? `当前得分 ${state.score.toLocaleString("zh-CN")}，可以继续挑战更大的数字。`
      : `共走了 ${state.moveCount.toLocaleString("zh-CN")} 步，得分 ${state.score.toLocaleString("zh-CN")}。`;

    const primary = $("result-primary");
    const secondary = $("result-secondary");
    primary.textContent = isWin ? "继续挑战" : "再来一局";
    primary.dataset.action = isWin ? "continue" : "restart";
    secondary.textContent = isWin ? "重新开始" : "撤销一步";
    secondary.dataset.action = isWin ? "restart" : "undo";
    secondary.hidden = !isWin && previousState === null;
    overlay.hidden = false;
    window.requestAnimationFrame(() => primary.focus({ preventScroll: true }));
  }

  function evaluateTerminal() {
    if (Core.hasWon(state.board) && !state.wonAcknowledged) {
      showResult("win");
      return true;
    }
    if (!Core.canMove(state.board)) {
      showResult("over");
      return true;
    }
    return false;
  }

  function pulseBlocked() {
    const board = $("board");
    board.classList.remove("board-blocked");
    window.requestAnimationFrame(() => {
      board.classList.add("board-blocked");
      window.setTimeout(() => board.classList.remove("board-blocked"), 180);
    });
  }

  function move(direction, fromTouch = false) {
    if (!ready || !$("result-overlay").hidden) return;
    const result = Core.move(state.board, direction);
    if (!result.moved) {
      announce("这个方向无法移动");
      pulseBlocked();
      void haptic("reject", fromTouch);
      return;
    }

    previousState = snapshot();
    const spawned = Core.spawn(result.board);
    state.board = spawned.board;
    state.score += result.scoreGain;
    state.best = Math.max(state.best, state.score);
    state.moveCount += 1;
    render({ bornIndices: [spawned.index], mergedIndices: result.mergedIndices });
    announce(result.scoreGain > 0
      ? `合并得分 +${result.scoreGain.toLocaleString("zh-CN")}`
      : `已向${DIRECTION_LABELS[direction]}移动`);
    queueSave();
    void haptic(result.scoreGain > 0 ? "confirm" : "click", fromTouch);
    evaluateTerminal();
  }

  function startNewGame() {
    const best = state.best;
    state = freshState(best);
    previousState = null;
    hideResult();
    render({ bornIndices: state.board.map((value, index) => value ? index : -1).filter((index) => index >= 0) });
    announce("新游戏开始，先把相同数字合在一起");
    queueSave();
    $("board").focus({ preventScroll: true });
  }

  function undo() {
    if (!previousState) return;
    const retainedBest = state.best;
    state = snapshot(previousState);
    state.best = Math.max(retainedBest, state.best);
    previousState = null;
    hideResult();
    render();
    announce("已撤销上一步");
    queueSave();
    $("board").focus({ preventScroll: true });
  }

  function askForNewGame() {
    if (state.moveCount === 0) {
      startNewGame();
      return;
    }
    $("new-game-dialog").showModal();
  }

  function directionFromGesture(start, end) {
    const deltaX = end.x - start.x;
    const deltaY = end.y - start.y;
    if (Math.max(Math.abs(deltaX), Math.abs(deltaY)) < 24) return null;
    return Math.abs(deltaX) > Math.abs(deltaY)
      ? (deltaX > 0 ? "right" : "left")
      : (deltaY > 0 ? "down" : "up");
  }

  function bindInteractions() {
    $("new-game").addEventListener("click", askForNewGame);
    $("undo").addEventListener("click", undo);
    $("confirm-new-game").addEventListener("click", startNewGame);

    $("result-primary").addEventListener("click", (event) => {
      if (event.currentTarget.dataset.action === "continue") {
        state.wonAcknowledged = true;
        hideResult();
        announce("继续挑战，看看还能合出多大的数字");
        queueSave();
        $("board").focus({ preventScroll: true });
      } else {
        startNewGame();
      }
    });
    $("result-secondary").addEventListener("click", (event) => {
      if (event.currentTarget.dataset.action === "undo") undo();
      else startNewGame();
    });

    const board = $("board");
    board.addEventListener("pointerdown", (event) => {
      if (!event.isPrimary || !ready || !$("result-overlay").hidden) return;
      gesture = { id: event.pointerId, x: event.clientX, y: event.clientY };
      try { board.setPointerCapture(event.pointerId); } catch (_) {}
    });
    board.addEventListener("pointerup", (event) => {
      if (!gesture || gesture.id !== event.pointerId) return;
      const direction = directionFromGesture(gesture, { x: event.clientX, y: event.clientY });
      gesture = null;
      if (direction) move(direction, event.pointerType === "touch" || event.pointerType === "pen");
    });
    board.addEventListener("pointercancel", () => { gesture = null; });

    window.addEventListener("keydown", (event) => {
      if ($("new-game-dialog").open) return;
      const direction = KEY_DIRECTIONS[event.key];
      if (!direction) return;
      event.preventDefault();
      move(direction, false);
    });
  }

  async function initialize() {
    bindInteractions();
    const restored = await restoreSavedGame();
    state = restored || freshState(0);
    ready = true;
    render(restored ? undefined : {
      bornIndices: state.board.map((value, index) => value ? index : -1).filter((index) => index >= 0)
    });
    announce(restored ? "已恢复上次棋局" : "滑动棋盘或使用方向键开始");
    evaluateTerminal();
  }

  void initialize();
})();
