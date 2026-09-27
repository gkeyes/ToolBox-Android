// Pure JS picker regressions with a small DOM test double. Not a real WebView/UI test.
const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.resolve(__dirname, '../../app/src/main/assets/browser/element-picker.js'), 'utf8');
function fixture() {
  const nodes = [];
  class Element {
    constructor(tag, id = '', classes = []) { this.localName = tag; this.id = id; this.classList = classes; this.children = []; this.parentElement = null; this.style = {}; this.listeners = {}; this.textContent = ''; nodes.push(this); }
    get isConnected() { return this === root || !!this.parentElement?.isConnected; }
    append(...elements) { for (const el of elements) { el.remove(); this.children.push(el); el.parentElement = this; } }
    remove() { if (this.parentElement) this.parentElement.children = this.parentElement.children.filter(el => el !== this); this.parentElement = null; }
    setAttribute(name, value) { this[name] = value; }
    addEventListener(name, fn) { this.listeners[name] = fn; }
    getBoundingClientRect() { return { left: 10, top: 20, width: 100, height: 50 }; }
  }
  const root = new Element('html'), body = new Element('body'); root.append(body);
  const article = new Element('article', 'content'), ad = new Element('div', 'advert'), link = new Element('a', 'adlink'), text = new Element('p', 'story');
  body.append(article); article.append(ad, text); ad.append(link);
  function match(node, token) {
    const nth = token.match(/:nth-of-type\((\d+)\)/); token = token.replace(/:nth-of-type\(\d+\)/, '');
    if (nth && node.parentElement?.children.filter(el => el.localName === node.localName).indexOf(node) !== Number(nth[1]) - 1) return false;
    if (token === '*') return true;
    if (token.startsWith('#')) return node.id === token.slice(1);
    const [tag, ...classes] = token.split('.');
    return (!tag || node.localName === tag) && classes.every(cls => node.classList.includes(cls));
  }
  function query(selector) {
    if (selector === '[') throw new Error('Invalid selector');
    return nodes.filter(node => node.isConnected && selector.split(',').some(part => {
      const chain = part.trim().split(/\s*>\s*/).reverse(); let current = node;
      return chain.every(token => { if (!current || !match(current, token)) return false; current = current.parentElement; return true; });
    }));
  }
  let hit = link;
  const listeners = new Set();
  const window = { addEventListener: (name, fn) => listeners.add(fn), removeEventListener: (name, fn) => listeners.delete(fn) }; window.top = window;
  const document = { documentElement: root, body, createElement: tag => new Element(tag), querySelectorAll: query, elementFromPoint: () => hit };
  const context = vm.createContext({ window, document, location: { protocol: 'https:', hostname: 'news.example' }, CSS: { escape: value => value }, requestAnimationFrame: () => 1, cancelAnimationFrame() {} });
  const install = vm.runInContext(source, context);
  const loaded = install('picker', [], 'news.example');
  function tap(node) {
    hit = node;
    const shield = root.children.find(el => el['aria-label']);
    const event = { clientX: 10, clientY: 10, stopImmediatePropagation() {}, preventDefault() {} };
    shield.listeners.pointerdown(event); shield.listeners.pointerup(event);
    return window.picker.state();
  }
  return { window, root, body, article, ad, link, text, nodes, Element, install, context, loaded, tap, listeners, api: window.picker, query };
}

test('install is main-document/host restricted and idempotent', () => {
  const f = fixture(); assert.equal(f.loaded, true);
  assert.equal(f.install('other', [], 'other.example'), false);
  assert.equal(f.install('picker', ['#advert'], 'news.example'), true);
  assert.equal(f.root.children.filter(el => el.localName === 'style').length, 2);
  f.window.top = {}; assert.equal(f.install('child', [], 'news.example'), false);
});
test('tap chooses exact element, expand and shrink follow ancestors', () => {
  const f = fixture(); f.api.start();
  assert.equal(f.tap(f.link).selector, '#adlink');
  assert.equal(f.api.adjust(1).selector, '#advert');
  assert.equal(f.api.adjust(-1).selector, '#adlink');
  f.api.adjust(100); assert.equal(f.api.state().selector, '#content');
  assert.equal(f.api.state().canExpand, false);
});
test('positional fallback actually targets selected nested sibling', () => {
  const f = fixture();
  f.link.id = ''; f.ad.id = ''; f.article.id = '';
  const another = new f.Element('article'), anotherAd = new f.Element('div'), anotherLink = new f.Element('a');
  f.body.append(another); another.append(anotherAd); anotherAd.append(anotherLink);
  f.api.start(); const state = f.tap(anotherLink);
  assert.deepEqual(f.query(state.selector), [anotherLink]);
  assert.match(state.warning, /位置/);
});
test('preview cancel restores temporary style while preserving permanent rules', () => {
  const f = fixture(); f.api.apply(['#advert']); f.api.start(); f.tap(f.text);
  assert.equal(f.api.preview().preview, true);
  const styles = f.root.children.filter(el => el.localName === 'style');
  assert.match(styles[1].textContent, /#story/);
  f.api.stop(); assert.equal(styles[1].textContent, ''); assert.match(styles[0].textContent, /#advert/);
  assert.equal(f.root.children.some(el => el['aria-label']), false); assert.equal(f.listeners.size, 0);
});
test('roots, CSS injection and invalid selectors cannot be previewed', () => {
  const f = fixture();
  for (const selector of ['html', 'body', '*', '#story,body', '.ad{color:red}', '.ad;body', '@import url(x)', '[']) {
    assert.equal(f.api.inspect(selector).valid, false, selector);
  }
  f.api.start(); f.api.edit('body'); assert.equal(f.api.preview().preview, false);
});
test('dynamic future matches retain CSS and clearing rules restores stylesheet', () => {
  const f = fixture(); f.api.apply(['.future-ad']);
  const sheet = f.root.children.find(el => el.localName === 'style');
  assert.match(sheet.textContent, /\.future-ad/); assert.match(sheet.textContent, /:not\(html\):not\(body\)/);
  f.api.apply([]); assert.equal(sheet.textContent, '');
});
test('removed selection cannot be saved as stale preview', () => {
  const f = fixture(); f.api.start(); f.tap(f.link); f.api.preview(); f.link.remove();
  const state = f.api.state(); assert.equal(state.preview, false); assert.equal(state.selector, ''); assert.equal(state.count, 0);
});
test('editing a selector clears preview and prevents a stale save', () => {
  const f = fixture(); f.api.start(); f.tap(f.link); f.api.preview();
  const state = f.api.edit('#story'); assert.equal(state.preview, false); assert.equal(state.count, 1);
});
test('repeated picker activation leaves a single shield and cancels navigation clicks', () => {
  const f = fixture(); f.api.start(); f.api.start();
  const shields = f.root.children.filter(el => el['aria-label']); assert.equal(shields.length, 1);
  let prevented = false, stopped = false;
  shields[0].listeners.click({ preventDefault() { prevented = true; }, stopImmediatePropagation() { stopped = true; } });
  assert.equal(prevented, true); assert.equal(stopped, true);
});
