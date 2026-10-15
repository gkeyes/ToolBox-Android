(() => {
  'use strict';
  // CSP connect-src and request interception never see ICE/STUN/TURN or data-channel traffic,
  // so WebRTC would let a tool without the network permission send data out. Chromium exposes
  // these interfaces on Window only; tool workers have no WebRTC constructors to remove.
  // Installed for every frame ("*"), so it must be idempotent and must not assume the tool origin.
  // There is no "already done" early return: a parent page could pre-set any marker on a child
  // window, and every step below is safe to repeat (identical non-configurable redefinitions succeed).
  //
  // The frame accessors below run later, after page code; capture what they use now so a page
  // cannot swap Reflect/Object methods to observe or skip the lock.
  const apply = Reflect.apply;
  const getOwn = Object.getOwnPropertyDescriptor;
  const define = Object.defineProperty;
  const protoOf = Object.getPrototypeOf;
  const MARK = '__toolboxWebRtcBlocked';
  const names = [
    'RTCPeerConnection', 'webkitRTCPeerConnection', 'RTCDataChannel', 'RTCRtpSender', 'RTCRtpReceiver',
    'RTCRtpTransceiver', 'RTCIceTransport', 'RTCDtlsTransport', 'RTCSctpTransport', 'RTCRtpScriptTransform'
  ];
  const frameTypes = ['HTMLIFrameElement', 'HTMLFrameElement', 'HTMLObjectElement'];
  // window[i], frames[i] and window.<frame name> hand out a child window without any frame-element
  // accessor, so every DOM call that can insert a frame locks this document's child windows before
  // it returns. A page can replace window.length ([Replaceable]), so the original getter is kept.
  const accessorOf = (owner, key) => { const d = owner && getOwn(owner, key); return d && d.get; };
  const protoOfType = type => { const d = getOwn(globalThis, type); return d && d.value && d.value.prototype; };
  const lengthOf = accessorOf(globalThis, 'length');
  const ownerDocumentOf = accessorOf(protoOfType('Node'), 'ownerDocument');
  const defaultViewOf = accessorOf(protoOfType('Document'), 'defaultView');
  const startContainerOf = accessorOf(protoOfType('Range'), 'startContainer');
  const insertMethods = [
    ['Node', ['appendChild', 'insertBefore', 'replaceChild']],
    ['Element', ['append', 'prepend', 'before', 'after', 'replaceWith', 'replaceChildren', 'moveBefore',
      'insertAdjacentElement', 'insertAdjacentHTML', 'setHTMLUnsafe']],
    ['CharacterData', ['before', 'after', 'replaceWith']],
    ['DocumentType', ['before', 'after', 'replaceWith']],
    ['DocumentFragment', ['append', 'prepend', 'replaceChildren', 'moveBefore']],
    ['ShadowRoot', ['setHTMLUnsafe']],
    ['Document', ['append', 'prepend', 'replaceChildren', 'moveBefore', 'write', 'writeln', 'execCommand']],
    ['Range', ['insertNode', 'surroundContents']]
  ];
  const insertSetters = [['Element', ['innerHTML', 'outerHTML']], ['ShadowRoot', ['innerHTML']], ['Document', ['body']]];
  // WeakSet methods are captured too: a page that made `has` always true would skip a child realm's wrapping.
  const weakHas = WeakSet.prototype.has;
  const weakAdd = WeakSet.prototype.add;
  const ownWrappers = new WeakSet();
  // Frames this copy has already locked once; only orders the work, never skips a lock.
  const seenFrames = new WeakSet();
  // An interface's prototype without a [[Get]] that a page-replaced constructor could turn into a getter.
  const interfaceProto = (scope, type) => {
    const ctor = getOwn(scope, type);
    const value = ctor && ctor.value;
    if (typeof value !== 'function') return undefined;
    const descriptor = getOwn(value, 'prototype');
    return descriptor && descriptor.value;
  };
  const frameKeys = ['contentWindow', 'contentDocument'];
  // Null-prototype descriptors: a page-defined Object.prototype.get/set cannot leak into them.
  const lockedValue = enumerable =>
    ({ __proto__: null, value: undefined, writable: false, enumerable, configurable: false });

  const lockName = (scope, name) => {
    let cursor = scope;
    let ownLocked = false;
    // Walk up to (not including) the realm's root Object.prototype, whichever realm scope is in.
    while (cursor && protoOf(cursor) !== null) {
      const descriptor = getOwn(cursor, name);
      if (descriptor) {
        define(cursor, name, lockedValue(descriptor.enumerable));
        if (cursor === scope) ownLocked = true;
      }
      cursor = protoOf(cursor);
    }
    if (!ownLocked) define(scope, name, lockedValue(false));
  };

  // A same-origin child window is locked before a parent ever gets it. A cross-origin (opaque)
  // child cannot be touched from here and is locked by its own document-start copy of this script.
  // Returns false only for a same-origin child that could not be locked; the caller then hides it.
  const lockFrame = win => {
    if (win === null || win === undefined) return true;
    try {
      harden(win);
      return true;
    } catch (_) {}
    try { void win.document; } catch (_) { return true; }
    return false;
  };

  // The initial about:blank of an iframe appended in the same task may exist before the
  // document-start script reaches it, so a parent must not get its window or document unlocked.
  const wrapFrameAccessors = (scope, type) => {
    const proto = interfaceProto(scope, type);
    if (!proto) return;
    const windowDescriptor = getOwn(proto, 'contentWindow');
    if (!windowDescriptor || typeof windowDescriptor.get !== 'function') return;
    const originalWindow = windowDescriptor.get;
    for (let i = 0; i < frameKeys.length; i++) {
      const key = frameKeys[i];
      const descriptor = getOwn(proto, key);
      // Non-configurable means this realm's accessors were already wrapped (by this script here or
      // by a parent frame's copy); they cannot be swapped back, so there is nothing left to do.
      if (!descriptor || typeof descriptor.get !== 'function' || descriptor.configurable === false) continue;
      const original = descriptor.get;
      const holder = key === 'contentWindow'
        ? { get contentWindow() {
            const win = apply(originalWindow, this, []);
            return lockFrame(win) ? win : null;
          } }
        : { get contentDocument() {
            if (!lockFrame(apply(originalWindow, this, []))) return null;
            return apply(original, this, []);
          } };
      define(proto, key, {
        __proto__: null,
        get: getOwn(holder, key).get,
        set: descriptor.set,
        enumerable: descriptor.enumerable,
        configurable: false
      });
    }
  };

  // The window whose frames an insertion may have changed: the target node's (or range's) document's.
  const viewOf = target => {
    if (!ownerDocumentOf || !defaultViewOf) return globalThis;
    let node = target;
    if (startContainerOf) {
      try { node = apply(startContainerOf, target, []); } catch (_) {}
    }
    try {
      const doc = apply(ownerDocumentOf, node, []) || node;
      return apply(defaultViewOf, doc, []) || globalThis;
    } catch (_) {
      return globalThis;
    }
  };

  // Frames not seen before are locked first, so re-locking an older frame (which reads that realm's
  // page-controlled globals) cannot run page code while a new frame is still open.
  const lockChildFrames = view => {
    let count = 0;
    try { count = lengthOf ? apply(lengthOf, view, []) : 0; } catch (_) { return; }
    const children = [];
    for (let i = 0; i < count; i++) {
      try { children[i] = view[i]; } catch (_) {}
    }
    for (let pass = 0; pass < 2; pass++) {
      for (let i = 0; i < count; i++) {
        const child = children[i];
        if (child === undefined || child === null) continue;
        let seen = true;
        try { seen = apply(weakHas, seenFrames, [child]); } catch (_) {}
        if (seen !== (pass === 1)) continue;
        lockFrame(child);
        try { apply(weakAdd, seenFrames, [child]); } catch (_) {}
      }
    }
  };

  const afterInsert = target => {
    const view = viewOf(target);
    lockChildFrames(view);
    if (view !== globalThis) lockChildFrames(globalThis);
  };

  const wrapInsertions = scope => {
    const wrap = (type, keys, setter) => {
      const proto = interfaceProto(scope, type);
      if (!proto) return;
      for (let i = 0; i < keys.length; i++) {
        const key = keys[i];
        const descriptor = getOwn(proto, key);
        if (!descriptor || descriptor.configurable === false) continue;
        const original = setter ? descriptor.set : descriptor.value;
        if (typeof original !== 'function' || apply(weakHas, ownWrappers, [original])) continue;
        // Method shorthand: named like the original and not constructible.
        const wrapper = setter
          ? getOwn({ set [key](value) { try { apply(original, this, [value]); } finally { afterInsert(this); } } }, key).set
          : { [key](...args) { try { return apply(original, this, args); } finally { afterInsert(this); } } }[key];
        apply(weakAdd, ownWrappers, [wrapper]);
        define(proto, key, setter
          ? { __proto__: null, get: descriptor.get, set: wrapper, enumerable: descriptor.enumerable, configurable: true }
          : { __proto__: null, value: wrapper, writable: descriptor.writable, enumerable: descriptor.enumerable,
              configurable: true });
      }
    };
    for (let i = 0; i < insertMethods.length; i++) wrap(insertMethods[i][0], insertMethods[i][1], false);
    for (let i = 0; i < insertSetters.length; i++) wrap(insertSetters[i][0], insertSetters[i][1], true);
  };

  // The one lock, used for this frame and for every same-origin child reached through a frame element.
  const harden = scope => {
    for (let i = 0; i < names.length; i++) lockName(scope, names[i]);
    for (let i = 0; i < frameTypes.length; i++) wrapFrameAccessors(scope, frameTypes[i]);
    wrapInsertions(scope);
    define(scope, MARK, { __proto__: null, value: true, writable: false, enumerable: false, configurable: false });
  };

  try {
    harden(globalThis);
    // Frames the HTML parser creates: the parser runs microtasks before each of its scripts.
    const Observer = getOwn(globalThis, 'MutationObserver');
    if (Observer && typeof Observer.value === 'function' && typeof document !== 'undefined') {
      new Observer.value(() => lockChildFrames(globalThis)).observe(document, { childList: true, subtree: true });
    }
  } catch (_) {
    try { window.stop(); } catch (_) {}
    try { document.documentElement?.replaceChildren(); } catch (_) {}
    // The script also runs in about:blank frames; do not re-navigate one to itself in a loop.
    try { if (location.href !== 'about:blank') location.replace('about:blank'); } catch (_) {}
  }
})();
