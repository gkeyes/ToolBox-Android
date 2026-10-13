const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const Core = require(path.join(__dirname, '..', 'game.js'));

test('mergeLine merges each pair once and scores the merged values', () => {
  assert.deepEqual(Core.mergeLine([2, 2, 2, 2]), { values: [4, 4, 0, 0], mergedOffsets: [0, 1], scoreGain: 8 });
  assert.deepEqual(Core.mergeLine([2, 0, 2, 4]), { values: [4, 4, 0, 0], mergedOffsets: [0], scoreGain: 4 });
  assert.deepEqual(Core.mergeLine([4, 4, 8, 0]), { values: [8, 8, 0, 0], mergedOffsets: [0], scoreGain: 8 });
  assert.deepEqual(Core.mergeLine([2, 4, 8, 16]), { values: [2, 4, 8, 16], mergedOffsets: [], scoreGain: 0 });
});

test('move slides in every direction and reports merged board indices', () => {
  const board = [
    2, 2, 0, 0,
    0, 0, 0, 0,
    0, 0, 0, 0,
    2, 0, 0, 0,
  ];
  const left = Core.move(board, 'left');
  assert.equal(left.moved, true);
  assert.equal(left.scoreGain, 4);
  assert.deepEqual(left.board.slice(0, 4), [4, 0, 0, 0]);
  assert.deepEqual(left.mergedIndices, [0]);

  const right = Core.move(board, 'right');
  assert.deepEqual(right.board.slice(0, 4), [0, 0, 0, 4]);
  assert.deepEqual(right.board.slice(12, 16), [0, 0, 0, 2]);
  assert.deepEqual(right.mergedIndices, [3]);

  const up = Core.move(board, 'up');
  assert.equal(up.board[0], 4);
  assert.equal(up.board[1], 2);
  assert.equal(up.board[12], 0);
  assert.deepEqual(up.mergedIndices, [0]);

  const down = Core.move(board, 'down');
  assert.equal(down.board[12], 4);
  assert.equal(down.board[13], 2);
  assert.deepEqual(down.mergedIndices, [12]);
  assert.deepEqual(board.slice(0, 2), [2, 2], 'input board is not mutated');
});

test('move reports no change when tiles cannot slide', () => {
  const board = [
    2, 4, 0, 0,
    0, 0, 0, 0,
    0, 0, 0, 0,
    0, 0, 0, 0,
  ];
  const result = Core.move(board, 'left');
  assert.equal(result.moved, false);
  assert.equal(result.scoreGain, 0);
  assert.deepEqual(result.board, board);
  assert.throws(() => Core.move(board, 'sideways'), TypeError);
  assert.throws(() => Core.move([3, ...board.slice(1)], 'left'), TypeError);
});

test('canMove detects empty cells and adjacent equal tiles only', () => {
  const full = [
    2, 4, 2, 4,
    4, 2, 4, 2,
    2, 4, 2, 4,
    4, 2, 4, 2,
  ];
  assert.equal(Core.canMove(full), false);
  const horizontal = full.slice(); horizontal[1] = 2;
  assert.equal(Core.canMove(horizontal), true);
  const vertical = full.slice(); vertical[4] = 2;
  assert.equal(Core.canMove(vertical), true);
  const empty = full.slice(); empty[15] = 0;
  assert.equal(Core.canMove(empty), true);
});

test('spawn fills an empty cell deterministically and hasWon spots 2048', () => {
  const board = Array(16).fill(0);
  const spawned = Core.spawn(board, () => 0);
  assert.equal(spawned.index, 0);
  assert.equal(spawned.value, 2);
  assert.equal(Core.spawn(Array(16).fill(2)).index, -1);
  assert.equal(Core.createBoard(() => 0.5).filter(Boolean).length, 2);
  assert.equal(Core.hasWon([2048, ...Array(15).fill(0)]), true);
  assert.equal(Core.hasWon([1024, ...Array(15).fill(0)]), false);
});
