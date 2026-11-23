const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { test } = require('node:test');
const { runInNewContext } = require('node:vm');

const script = readFileSync(join(__dirname, '../app.js'), 'utf8');

function previewFixture() {
  class Element extends EventTarget {
    replaceChildren() {}

    close() {
      this.open = false;
      // Dialog close events are queued. The test decides when to deliver them.
    }
  }
  const elements = new Map();
  function element(id) {
    if (!elements.has(id)) elements.set(id, new Element());
    return elements.get(id);
  }
  const dialog = element('zoom-dialog');
  const canvas = element('zoom-canvas');
  const document = Object.assign(new EventTarget(), {
    getElementById: element,
    querySelectorAll: (selector) => selector === 'dialog' ? [dialog] : [],
  });
  const window = Object.assign(new EventTarget(), {
    StitchRuntime: { init: () => new Promise(() => {}) },
  });
  runInNewContext(script, { document, window });
  function openPreview() {
    dialog.open = true;
    canvas.width = 1080;
    canvas.height = 12000;
  }
  openPreview();
  return {
    dialog,
    openPreview,
    clickClose: () => element('close-zoom').dispatchEvent(new Event('click')),
    deliverClose: () => dialog.dispatchEvent(new Event('close')),
    dimensions: () => [canvas.width, canvas.height],
  };
}

test('both the close button and the native dialog close path clear the preview', () => {
  for (const close of [fixture => fixture.clickClose(), fixture => fixture.dialog.close()]) {
    const fixture = previewFixture();
    close(fixture);
    assert.equal(fixture.dialog.open, false);
    fixture.deliverClose();
    assert.deepEqual(fixture.dimensions(), [1, 1]);
  }
});

test('a queued close event preserves a preview that has already reopened', () => {
  const fixture = previewFixture();
  fixture.dialog.close();
  fixture.openPreview();
  fixture.deliverClose();
  assert.deepEqual(fixture.dimensions(), [1080, 12000]);
  fixture.dialog.close();
  fixture.deliverClose();
  assert.deepEqual(fixture.dimensions(), [1, 1]);
});
