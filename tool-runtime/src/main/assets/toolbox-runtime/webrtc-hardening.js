(() => {
  'use strict';
  // CSP connect-src and request interception never see ICE/STUN/TURN or data-channel traffic,
  // so WebRTC would let a tool without the network permission send data out. Chromium exposes
  // these interfaces on Window only; tool workers have no WebRTC constructors to remove.
  // Installed for every frame ("*"), so it must be idempotent and must not assume the tool origin.
  if (globalThis.__toolboxWebRtcBlocked === true) return;
  const names = [
    'RTCPeerConnection', 'webkitRTCPeerConnection', 'RTCDataChannel', 'RTCRtpSender', 'RTCRtpReceiver',
    'RTCRtpTransceiver', 'RTCIceTransport', 'RTCDtlsTransport', 'RTCSctpTransport', 'RTCRtpScriptTransform'
  ];
  const lock = (target, name) => {
    let cursor = target;
    let ownLocked = false;
    while (cursor && cursor !== Object.prototype) {
      const descriptor = Object.getOwnPropertyDescriptor(cursor, name);
      if (descriptor) {
        Object.defineProperty(cursor, name, {
          value: undefined,
          writable: false,
          enumerable: descriptor.enumerable,
          configurable: false
        });
        if (cursor === target) ownLocked = true;
      }
      cursor = Object.getPrototypeOf(cursor);
    }
    if (!ownLocked) {
      Object.defineProperty(target, name, { value: undefined, writable: false, enumerable: false, configurable: false });
    }
  };
  try {
    for (const name of names) lock(globalThis, name);
    Object.defineProperty(globalThis, '__toolboxWebRtcBlocked', {
      value: true,
      writable: false,
      enumerable: false,
      configurable: false
    });
  } catch (_) {
    try { window.stop(); } catch (_) {}
    try { document.documentElement?.replaceChildren(); } catch (_) {}
    // The script also runs in about:blank frames; do not re-navigate one to itself in a loop.
    try { if (location.href !== 'about:blank') location.replace('about:blank'); } catch (_) {}
  }
})();
