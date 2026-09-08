(function exposeGameCore(root, factory) {
  "use strict";
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  if (root) root.Game2048Core = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function createGameCore() {
  "use strict";

  const SIZE = 4;
  const CELL_COUNT = SIZE * SIZE;
  const DIRECTIONS = new Set(["left", "right", "up", "down"]);

  function isTileValue(value) {
    return value === 0 || (
      Number.isSafeInteger(value) &&
      value >= 2 &&
      Number.isInteger(Math.log2(value))
    );
  }

  function isBoard(board) {
    return Array.isArray(board) && board.length === CELL_COUNT && board.every(isTileValue);
  }

  function assertBoard(board) {
    if (!isBoard(board)) throw new TypeError("Board must contain 16 powers of two or zero.");
  }

  function lineIndices(direction, line) {
    if (!DIRECTIONS.has(direction)) throw new TypeError("Unknown move direction.");
    const forward = Array.from({ length: SIZE }, (_, offset) => (
      direction === "left" || direction === "right"
        ? line * SIZE + offset
        : offset * SIZE + line
    ));
    return direction === "right" || direction === "down" ? forward.reverse() : forward;
  }

  function mergeLine(line) {
    const compact = line.filter((value) => value !== 0);
    const values = [];
    const mergedOffsets = [];
    let scoreGain = 0;

    for (let index = 0; index < compact.length; index += 1) {
      if (compact[index] === compact[index + 1]) {
        const mergedValue = compact[index] * 2;
        values.push(mergedValue);
        mergedOffsets.push(values.length - 1);
        scoreGain += mergedValue;
        index += 1;
      } else {
        values.push(compact[index]);
      }
    }

    while (values.length < SIZE) values.push(0);
    return { values, mergedOffsets, scoreGain };
  }

  function move(board, direction) {
    assertBoard(board);
    if (!DIRECTIONS.has(direction)) throw new TypeError("Unknown move direction.");

    const next = board.slice();
    const mergedIndices = [];
    let moved = false;
    let scoreGain = 0;

    for (let line = 0; line < SIZE; line += 1) {
      const indices = lineIndices(direction, line);
      const current = indices.map((index) => board[index]);
      const merged = mergeLine(current);
      scoreGain += merged.scoreGain;

      indices.forEach((boardIndex, offset) => {
        if (current[offset] !== merged.values[offset]) moved = true;
        next[boardIndex] = merged.values[offset];
      });
      merged.mergedOffsets.forEach((offset) => mergedIndices.push(indices[offset]));
    }

    return { board: next, moved, scoreGain, mergedIndices };
  }

  function randomUnit(random) {
    const sample = Number(random());
    if (!Number.isFinite(sample)) return 0;
    return Math.min(Math.max(sample, 0), 1 - Number.EPSILON);
  }

  function spawn(board, random = Math.random) {
    assertBoard(board);
    const available = [];
    board.forEach((value, index) => { if (value === 0) available.push(index); });
    if (available.length === 0) return { board: board.slice(), index: -1, value: 0 };

    const target = available[Math.floor(randomUnit(random) * available.length)];
    const value = randomUnit(random) < 0.9 ? 2 : 4;
    const next = board.slice();
    next[target] = value;
    return { board: next, index: target, value };
  }

  function createBoard(random = Math.random) {
    const empty = Array(CELL_COUNT).fill(0);
    const first = spawn(empty, random);
    return spawn(first.board, random).board;
  }

  function canMove(board) {
    assertBoard(board);
    if (board.some((value) => value === 0)) return true;
    for (let row = 0; row < SIZE; row += 1) {
      for (let column = 0; column < SIZE; column += 1) {
        const index = row * SIZE + column;
        if (column + 1 < SIZE && board[index] === board[index + 1]) return true;
        if (row + 1 < SIZE && board[index] === board[index + SIZE]) return true;
      }
    }
    return false;
  }

  function hasWon(board) {
    assertBoard(board);
    return board.some((value) => value >= 2048);
  }

  return Object.freeze({
    SIZE,
    CELL_COUNT,
    isBoard,
    mergeLine,
    move,
    spawn,
    createBoard,
    canMove,
    hasWon
  });
});
