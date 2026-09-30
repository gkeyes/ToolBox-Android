const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

const source = fs.readFileSync(
  path.resolve(__dirname, '../../app/src/main/assets/browser/media-layout-compat.js'),
  'utf8',
);

function fixture({
  playerClass = 'dplayer',
  currentSrc = 'blob:https://example.test/stream',
  readyState = 4,
  videoWidth = 0,
  videoHeight = 0,
  videoAspect = 'auto',
} = {}) {
  class Style {
    constructor() {
      this.display = 'block';
      this.visibility = 'visible';
      this.opacity = '1';
      this.aspectRatio = 'auto';
      this.height = '';
      this.minHeight = '';
      this.values = new Map();
    }
    setProperty(name, value) {
      this.values.set(name, String(value));
      if (name === 'aspect-ratio') this.aspectRatio = String(value);
      if (name === 'height') this.height = String(value);
      if (name === 'min-height') this.minHeight = String(value);
    }
  }

  class Element {
    constructor(tag, classes = [], width = 384, height = 0) {
      this.localName = tag;
      this.id = '';
      this.classList = classes;
      this.parentElement = null;
      this.children = [];
      this.dataset = {};
      this.style = new Style();
      this.baseWidth = width;
      this.baseHeight = height;
      this.listeners = new Map();
    }
    append(child) {
      child.parentElement = this;
      this.children.push(child);
      return child;
    }
    get isConnected() {
      return this === root || Boolean(this.parentElement?.isConnected);
    }
    addEventListener(name, fn) {
      this.listeners.set(name, fn);
    }
    querySelector(selector) {
      if (selector === 'source[src]') return null;
      return null;
    }
    getBoundingClientRect() {
      const width = this.baseWidth;
      let height = this.baseHeight;
      if (this.style.height === '100%' && this.parentElement) {
        height = this.parentElement.getBoundingClientRect().height;
      } else if (this.style.height === 'auto' && this.style.aspectRatio !== 'auto') {
        const ratio = Number(this.style.aspectRatio);
        if (Number.isFinite(ratio) && ratio > 0) height = width / ratio;
      }
      return { left: 0, top: 0, width, height };
    }
  }

  const root = new Element('html', [], 384, 800);
  const body = root.append(new Element('body', [], 384, 800));
  const player = body.append(new Element('div', playerClass ? [playerClass] : []));
  const wrap = player.append(new Element('div', ['dplayer-video-wrap']));
  const video = wrap.append(new Element('video', ['dplayer-video', 'dplayer-video-current']));
  video.currentSrc = currentSrc;
  video.src = '';
  video.readyState = readyState;
  video.videoWidth = videoWidth;
  video.videoHeight = videoHeight;
  video.style.aspectRatio = videoAspect;
  video.getAttribute = name => {
    if (name === 'width' || name === 'height') return '';
    return null;
  };

  const document = {
    querySelectorAll(selector) {
      if (selector === 'video') return [video];
      return [];
    },
  };

  const context = vm.createContext({
    document,
    window: { innerHeight: 800 },
    getComputedStyle: node => node.style,
    Number,
    String,
    Math,
    Boolean,
  });

  const repair = vm.runInContext(source, context);
  return { repair, root, body, player, wrap, video };
}

test('repairs a loaded DPlayer that is 384x0 even when WebView exposes no intrinsic video size', () => {
  const f = fixture();
  assert.equal(f.player.getBoundingClientRect().height, 0);
  assert.equal(f.video.videoWidth, 0);
  assert.equal(f.video.videoHeight, 0);

  const repaired = f.repair();

  assert.equal(repaired, 1);
  assert.equal(f.player.dataset.toolboxMediaLayoutRepair, 'fallback-16:9:1.7778');
  assert.equal(f.video.dataset.toolboxLayoutRepairRatio, 'fallback-16:9');
  assert.ok(f.player.getBoundingClientRect().height > 200);
  assert.equal(f.wrap.getBoundingClientRect().height, f.player.getBoundingClientRect().height);
  assert.equal(f.video.getBoundingClientRect().height, f.player.getBoundingClientRect().height);
});

test('prefers real intrinsic media dimensions over the 16:9 fallback', () => {
  const f = fixture({ videoWidth: 1920, videoHeight: 800 });
  const repaired = f.repair();

  assert.equal(repaired, 1);
  assert.equal(f.player.dataset.toolboxMediaLayoutRepair, 'intrinsic:2.4000');
  assert.ok(Math.abs(f.player.getBoundingClientRect().height - 160) < 0.01);
});

test('uses an existing CSS aspect-ratio before fallback', () => {
  const f = fixture({ videoAspect: '4 / 3' });
  const repaired = f.repair();

  assert.equal(repaired, 1);
  assert.equal(f.player.dataset.toolboxMediaLayoutRepair, 'intrinsic:1.3333');
  assert.ok(Math.abs(f.player.getBoundingClientRect().height - 288) < 0.01);
});

test('does not invent a 16:9 height for generic collapsed video containers', () => {
  const f = fixture({ playerClass: '' });
  f.wrap.classList = [];
  const repaired = f.repair();

  assert.equal(repaired, 0);
  assert.equal(f.player.getBoundingClientRect().height, 0);
  assert.equal(f.player.dataset.toolboxMediaLayoutRepair, undefined);
});

test('does not repair empty media placeholders without a source', () => {
  const f = fixture({ currentSrc: '' });
  const repaired = f.repair();

  assert.equal(repaired, 0);
  assert.equal(f.player.getBoundingClientRect().height, 0);
});
