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
    const ctor = getOwn(scope, type);
    const proto = ctor && ctor.value && ctor.value.prototype;
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

  // The one lock, used for this frame and for every same-origin child reached through a frame element.
  const harden = scope => {
    for (let i = 0; i < names.length; i++) lockName(scope, names[i]);
    for (let i = 0; i < frameTypes.length; i++) wrapFrameAccessors(scope, frameTypes[i]);
    define(scope, MARK, { __proto__: null, value: true, writable: false, enumerable: false, configurable: false });
  };

  try {
    harden(globalThis);
  } catch (_) {
    try { window.stop(); } catch (_) {}
    try { document.documentElement?.replaceChildren(); } catch (_) {}
    // The script also runs in about:blank frames; do not re-navigate one to itself in a loop.
    try { if (location.href !== 'about:blank') location.replace('about:blank'); } catch (_) {}
  }
})();
