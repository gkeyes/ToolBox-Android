(() => {
  'use strict';
  const nativeBridge = globalThis.__TOOLBOX_BRIDGE_OBJECT__;
  const pending = new Map();
  const listeners = new Map();
  // Native unacknowledged events and this per-document backlog have separate budgets.
  const earlyEvents = new Map();
  const queuedNames = new Map();
  const lostEvents = new Map();
  const stateListeners = new Map();
  const flushHandlers = new Set();
  const writeMethods = new Set(['storage.apply', 'storage.set', 'storage.remove', 'storage.clear', 'storage.secure.set', 'storage.secure.remove', 'background.stop']);
  let sequence = 0;
  let runtimeState = null;
  let readyPromise = null;
  let closeToken = null;
  let handledCloseToken = null;
  let flushFinishedToken = null;
  let disposed = false;
  let earlyBytes = 0;
  let replayIterator = null;
  let replayScheduled = false;
  let restartReplay = false;
  let receivedSequence = 0;
  let acknowledgedSequence = 0;
  let ackTimer = null;
  let ackBackoff = false;
  let ackInFlight = false;
  let ackFailed = false;
  const runtimeError = (code, message, extra = {}) => Object.assign(new Error(message), { code, ...extra });
  const reportError = error => {
    try { globalThis.dispatchEvent(new CustomEvent('toolbox:runtime.error', { detail: error })); } catch (_) {}
  };
  const dispatchEventPayload = (name, payload) => {
    const callbacks = listeners.get(name);
    callbacks?.forEach(callback => { try { callback(payload); } catch (_) {} });
  };
  const scheduleReplay = () => {
    if (disposed || replayScheduled) return;
    replayScheduled = true;
    setTimeout(replayEvents, 0);
  };
  const replayEvents = () => {
    replayScheduled = false;
    if (disposed) return;
    if (!replayIterator || restartReplay) {
      replayIterator = earlyEvents.entries();
      restartReplay = false;
    }
    const started = performance.now();
    let inspected = 0;
    while (inspected < 32 && performance.now() - started < 2) {
      const next = replayIterator.next();
      if (next.done) { replayIterator = null; break; }
      inspected++;
      const [id, entry] = next.value;
      if (!listeners.get(entry.name)?.size) continue;
      earlyEvents.delete(id);
      earlyBytes -= entry.charge;
      const remaining = queuedNames.get(entry.name) - 1;
      remaining ? queuedNames.set(entry.name, remaining) : queuedNames.delete(entry.name);
      const payload = JSON.parse(entry.encoded).data;
      dispatchEventPayload(entry.name, payload);
    }
    if (replayIterator || restartReplay) scheduleReplay();
  };
  const retainEvent = (encoded, response) => {
    const charge = encoded.length * 2 + 128;
    if (earlyEvents.size >= 4096 || charge > 1024 * 1024 - earlyBytes) {
      const droppedCount = (lostEvents.get(response.event) || 0) + 1;
      lostEvents.set(response.event, droppedCount);
      reportError(runtimeError('EVENT_BACKLOG_OVERFLOW', 'Unsubscribed tool events exceeded the backlog budget', { event: response.event, droppedCount }));
      return;
    }
    earlyEvents.set(response.sequence, { name: response.event, encoded, charge });
    earlyBytes += charge;
    queuedNames.set(response.event, (queuedNames.get(response.event) || 0) + 1);
    if (listeners.get(response.event)?.size) scheduleReplay();
  };
  const acknowledgeEvents = () => {
    if (ackTimer !== null) { clearTimeout(ackTimer); ackTimer = null; }
    ackBackoff = false;
    if (disposed || ackFailed || ackInFlight || receivedSequence <= acknowledgedSequence) return;
    const upTo = receivedSequence;
    ackInFlight = true;
    call('runtime.ackEvents', { sequence: upTo }).then(() => {
      acknowledgedSequence = upTo;
    }).catch(error => {
      if (error.code === 'BUSY') {
        ackBackoff = true;
        ackTimer = setTimeout(acknowledgeEvents, 50);
      }
      else { ackFailed = true; reportError(error); }
    }).finally(() => {
      ackInFlight = false;
      arrangeAck();
    });
  };
  const arrangeAck = () => {
    if (disposed || ackFailed || ackInFlight || ackBackoff || receivedSequence <= acknowledgedSequence) return;
    if (receivedSequence - acknowledgedSequence >= 32) acknowledgeEvents();
    else if (ackTimer === null) ackTimer = setTimeout(acknowledgeEvents, 50);
  };
  const replayState = listener => {
    if (!runtimeState || !stateListeners.has(listener) || stateListeners.get(listener) >= runtimeState.revision) return;
    stateListeners.set(listener, runtimeState.revision);
    try { listener(runtimeState); } catch (_) {}
  };
  const receiveState = state => {
    if (!state || state.generation !== __TOOLBOX_GENERATION__ || !Number.isSafeInteger(state.revision) ||
        state.revision < 0 || typeof state.foreground !== 'boolean' || typeof state.closing !== 'boolean' ||
        (runtimeState && state.revision <= runtimeState.revision)) return;
    runtimeState = Object.freeze({ generation: state.generation, revision: state.revision, foreground: state.foreground, closing: state.closing });
    closeToken = state.closing ? state.closeToken : null;
    stateListeners.forEach((_, listener) => replayState(listener));
    if (!state.closing || typeof closeToken !== 'string' || closeToken === handledCloseToken) return;
    const ticket = closeToken;
    handledCloseToken = ticket;
    for (const streamId of streams.keys()) cancelStream(streamId).catch(() => undefined);
    for (const sessionId of mediaSessions.keys()) closeMedia(sessionId).catch(() => undefined);
    Promise.allSettled([...flushHandlers].map(handler => Promise.resolve().then(handler))).then(results => {
      const saved = results.every(result => result.status === 'fulfilled');
      if (disposed || closeToken !== ticket) return;
      flushFinishedToken = ticket;
      return call('runtime.flushComplete', { closeToken: ticket, saved });
    }).catch(error => { if (error.code !== 'CANCELLED' && error.code !== 'INVALID_SESSION') reportError(error); });
  };
  nativeBridge.onmessage = event => {
    let response;
    try { response = JSON.parse(event.data); } catch (_) { return; }
    if (response.type === 'runtimeState') { receiveState(response.data); return; }
    if (response.type === 'event' && typeof response.event === 'string') {
      if (response.generation !== __TOOLBOX_GENERATION__ || !Number.isSafeInteger(response.sequence) || response.sequence <= receivedSequence) return;
      receivedSequence = response.sequence;
      if (listeners.get(response.event)?.size && earlyEvents.size === 0) dispatchEventPayload(response.event, response.data);
      else retainEvent(event.data, response);
      try { globalThis.dispatchEvent(new CustomEvent(`toolbox:${response.event}`, { detail: response.data })); } catch (_) {}
      arrangeAck();
      return;
    }
    const waiter = pending.get(response.id);
    if (!waiter) return;
    pending.delete(response.id);
    response.ok ? waiter.resolve(response.result) : waiter.reject(Object.assign(new Error(response.error.message), response.error));
  };
  const invalidStorageMutation = () => Object.assign(new Error('Storage mutation must contain JSON values'), { code: 'INVALID_REQUEST' });
  const storageJson = (_, value) => {
    if (typeof value === 'undefined' || typeof value === 'function' || typeof value === 'symbol' ||
        typeof value === 'bigint' || (typeof value === 'number' && !Number.isFinite(value))) throw invalidStorageMutation();
    return value;
  };
  const call = (method, params = {}) => {
    if (disposed) return Promise.reject(runtimeError('SESSION_ENDED', 'The tool document has ended'));
    const isWrite = writeMethods.has(method);
    const ticket = isWrite || method === 'runtime.flushComplete' ? closeToken : null;
    if (isWrite && ticket && flushFinishedToken === ticket) return Promise.reject(runtimeError('SESSION_ENDED', 'The final save has completed'));
    const id = `${Date.now().toString(36)}-${(++sequence).toString(36)}`;
    let encoded;
    try {
      encoded = JSON.stringify({ id, method, params, nonce: __TOOLBOX_NONCE__, toolId: __TOOLBOX_TOOL_ID__,
        versionCode: __TOOLBOX_VERSION_CODE__, generation: __TOOLBOX_GENERATION__
      }, method === 'storage.apply' ? storageJson : undefined);
    } catch (error) { return Promise.reject(error); }
    const attempt = () => new Promise((resolve, reject) => {
      if (disposed || (ticket && closeToken !== ticket)) { reject(runtimeError('CANCELLED', 'The close request was cancelled')); return; }
      pending.set(id, { resolve, reject });
      try { nativeBridge.postMessage(encoded); } catch (error) { pending.delete(id); reject(error); }
    }).catch(error => {
      // Retry final writes/acknowledgement while this close is current; keep native budgets unchanged.
      if (ticket && closeToken === ticket && error.code === 'BUSY') return new Promise(resolve => setTimeout(resolve, 50)).then(attempt);
      throw error;
    });
    return attempt();
  };
  const ensureReady = () => {
    if (!readyPromise) readyPromise = call('ready').then(result => {
      receiveState(result.runtimeState);
      return { ...result, runtimeState };
    }).catch(error => { readyPromise = null; throw error; });
    return readyPromise;
  };
  const subscribeState = listener => {
    if (typeof listener !== 'function') throw new TypeError('listener must be a function');
    stateListeners.set(listener, -1);
    queueMicrotask(() => replayState(listener));
    ensureReady().catch(reportError);
    return () => stateListeners.delete(listener);
  };
  // Bytes cross the bridge as one base64 string each way, never as a per-byte array.
  const toBase64 = value => {
    if (typeof value.toBase64 === 'function') return value.toBase64();
    let binary = '';
    for (let offset = 0; offset < value.length; offset += 0x8000) {
      binary += String.fromCharCode.apply(null, value.subarray(offset, offset + 0x8000));
    }
    return btoa(binary);
  };
  const fromBase64 = text => {
    if (typeof Uint8Array.fromBase64 === 'function') return Uint8Array.fromBase64(text);
    const binary = atob(text);
    const decoded = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index++) decoded[index] = binary.charCodeAt(index);
    return decoded;
  };
  const bytes = value => value instanceof Uint8Array ? { base64: toBase64(value) } : value;
  const networkRequest = request => {
    if (request?.maxResponseBytes !== undefined &&
        (!Number.isSafeInteger(request.maxResponseBytes) || request.maxResponseBytes < 1)) {
      throw Object.assign(new TypeError('maxResponseBytes must be a positive safe integer'), { code: 'INVALID_REQUEST' });
    }
    return request && request.body instanceof Uint8Array
      ? { ...request, body: bytes(request.body), bodyEncoding: 'bytes' } : request;
  };
  const streams = new Map();
  const streamCancelled = () => Object.assign(new Error('Network stream cancelled'), { code: 'CANCELLED' });
  const forgetStream = streamId => {
    const state = streams.get(streamId);
    if (state) state.signal?.removeEventListener('abort', state.abort);
    streams.delete(streamId);
  };
  const cancelStream = streamId => {
    const state = streams.get(streamId);
    if (state) state.cancelled = true;
    forgetStream(streamId);
    return call('network.cancelStream', { streamId });
  };
  globalThis.addEventListener('pagehide', () => {
    for (const streamId of streams.keys()) cancelStream(streamId).catch(() => undefined);
  });
  const openStream = async (request, options = {}) => {
    const streamId = 'stream-' + Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, '0')).join('');
    const state = { signal: options.signal, cancelled: false, abort: () => cancelStream(streamId).catch(() => undefined) };
    streams.set(streamId, state);
    state.signal?.addEventListener('abort', state.abort, { once: true });
    if (state.signal?.aborted) { forgetStream(streamId); throw streamCancelled(); }
    try {
      const opened = await call('network.openStream', { streamId, request: networkRequest(request) });
      if (state.cancelled) {
        cancelStream(streamId).catch(() => undefined);
        throw streamCancelled();
      }
      return opened;
    } catch (error) {
      forgetStream(streamId);
      if (state.cancelled) throw streamCancelled();
      throw error;
    }
  };
  const readStream = async (streamId, options = {}) => {
    const expectedChunkBytes = options.expectedChunkBytes;
    if (expectedChunkBytes !== undefined && (!Number.isSafeInteger(expectedChunkBytes) || expectedChunkBytes < 1)) {
      throw Object.assign(new TypeError('expectedChunkBytes must be a positive safe integer'), { code: 'INVALID_REQUEST' });
    }
    const state = streams.get(streamId);
    try {
      const chunk = await call('network.readStream', { streamId, ...(expectedChunkBytes === undefined ? {} : { expectedChunkBytes }) });
      if (state?.cancelled) throw streamCancelled();
      if (chunk.done) forgetStream(streamId);
      return { ...chunk, data: fromBase64(chunk.data) };
    } catch (error) {
      if (error.code !== 'BUSY') cancelStream(streamId).catch(() => undefined);
      throw error;
    }
  };
  const mediaSessions = new Map();
  const mediaCancelled = () => Object.assign(new Error('Network media cancelled'), { code: 'CANCELLED' });
  const forgetMedia = sessionId => {
    const state = mediaSessions.get(sessionId);
    if (state) state.signal?.removeEventListener('abort', state.abort);
    mediaSessions.delete(sessionId);
  };
  const closeMedia = sessionId => {
    const state = mediaSessions.get(sessionId);
    if (state) {
      state.cancelled = true;
      state.reject?.(mediaCancelled());
      state.reject = undefined;
    }
    forgetMedia(sessionId);
    return call('network.closeMedia', { sessionId }).then(() => undefined);
  };
  globalThis.addEventListener('pagehide', () => {
    for (const sessionId of mediaSessions.keys()) closeMedia(sessionId).catch(() => undefined);
  });
  const openMedia = async (request, options = {}) => {
    const signal = options.signal;
    if (signal?.aborted) throw mediaCancelled();
    const sessionId = 'media-' + Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, '0')).join('');
    const params = { sessionId, url: request?.url, ...(request?.kind === undefined ? {} : { kind: request.kind }) };
    const state = { signal, cancelled: false, reject: undefined, abort: () => closeMedia(sessionId).catch(() => undefined) };
    return new Promise((resolve, reject) => {
      state.reject = reject;
      mediaSessions.set(sessionId, state);
      try {
        signal?.addEventListener('abort', state.abort, { once: true });
        if (signal?.aborted) {
          forgetMedia(sessionId);
          state.reject = undefined;
          reject(mediaCancelled());
          return;
        }
        call('network.openMedia', params).then(opened => {
          state.reject = undefined;
          if (state.cancelled) {
            closeMedia(sessionId).catch(() => undefined);
            return;
          }
          resolve(opened);
        }, error => {
          state.reject = undefined;
          forgetMedia(sessionId);
          reject(state.cancelled ? mediaCancelled() : error);
        });
      } catch (error) {
        state.reject = undefined;
        forgetMedia(sessionId);
        reject(error);
      }
    });
  };
  const subscribe = (name, listener) => {
    if (typeof listener !== 'function') throw new TypeError('listener must be a function');
    if (lostEvents.has(name)) throw runtimeError('EVENT_BACKLOG_OVERFLOW', 'Some early events were lost', { event: name, droppedCount: lostEvents.get(name) });
    let callbacks = listeners.get(name);
    if (!callbacks) listeners.set(name, callbacks = new Set());
    callbacks.add(listener);
    if (queuedNames.has(name)) { restartReplay = true; scheduleReplay(); }
    ensureReady().catch(reportError);
    return () => { callbacks.delete(listener); if (!callbacks.size) listeners.delete(name); };
  };
  const api = {
    ready: ensureReady,
    runtime: {
      getState: () => call('runtime.getState').then(state => { receiveState(state); return runtimeState; }),
      onStateChanged: subscribeState,
      registerFlushHandler: handler => {
        if (typeof handler !== 'function') throw new TypeError('handler must be a function');
        flushHandlers.add(handler);
        ensureReady().catch(reportError);
        return () => flushHandlers.delete(handler);
      }
    },
    ui: { toast: message => call('ui.toast', { message }) },
    crypto: { sha256: value => call('crypto.sha256', { value: bytes(value) }) },
    storage: {
      get: key => call('storage.get', { key }),
      getMany: keys => call('storage.getMany', { keys }),
      apply: mutation => mutation && typeof mutation === 'object' && !Array.isArray(mutation)
        ? call('storage.apply', mutation) : Promise.reject(invalidStorageMutation()),
      set: (key, value) => call('storage.set', { key, value }),
      remove: key => call('storage.remove', { key }),
      keys: () => call('storage.keys'),
      clear: () => call('storage.clear'),
      secure: {
        get: key => call('storage.secure.get', { key }),
        set: (key, value) => call('storage.secure.set', { key, value }),
        remove: key => call('storage.secure.remove', { key })
      }
    },
    device: { getBasicInfo: () => call('device.getBasicInfo') },
    haptics: { perform: effect => call('haptics.perform', { effect }) },
    clipboard: {
      writeText: text => call('clipboard.writeText', { text }),
      readText: () => call('clipboard.readText')
    },
    network: { request: request => call('network.request', networkRequest(request)), openStream, readStream, cancelStream, openMedia, closeMedia },
    notifications: {
      post: (id, title, body) => call('notifications.post', { id, title, body }),
      update: (id, title, body) => call('notifications.update', { id, title, body }),
      cancel: id => call('notifications.cancel', { id }),
      onOpen: listener => subscribe('notifications.open', listener),
      live: {
        start: request => call('notifications.live.start', request),
        update: request => call('notifications.live.update', request),
        end: sessionId => call('notifications.live.end', { sessionId })
      }
    },
    background: {
      enqueue: spec => call('background.enqueue', spec),
      schedulePeriodic: spec => call('background.schedulePeriodic', spec),
      start: options => call('background.start', options === undefined ? {} : options),
      stop: sessionId => call('background.stop', { sessionId }),
      status: sessionId => call('background.status', { sessionId }),
      updateActivity: request => call('background.updateActivity', request),
      list: () => call('background.list'),
      listSessions: () => call('background.listSessions'),
      getResult: taskId => call('background.getResult', { taskId }),
      cancel: taskId => call('background.cancel', { taskId }),
      setTimer: (key, intervalMs) => call('background.setTimer', { key, intervalMs }),
      cancelTimer: key => call('background.cancelTimer', { key }),
      onRestore: listener => subscribe('background.restore', listener),
      onTimer: listener => subscribe('background.timer', listener)
    },
    share: { text: text => call('share.text', { text }) },
    browser: { open: url => call('browser.open', { url }).then(() => undefined) },
    files: {
      open: mimeTypes => call('files.open', mimeTypes === undefined ? {} : { mimeTypes }),
      save: (suggestedName, mimeType, content) => call('files.save', { suggestedName, mimeType, content: bytes(content) }),
      read: token => call('files.read', { token }).then(result => fromBase64(result.base64))
    },
    shortcuts: { pin: name => call('shortcuts.pin', name === undefined ? {} : { name }) },
    camera: { capture: () => call('camera.capture') },
    location: {
      getCurrent: (accuracy, timeoutMs) => call('location.getCurrent', {
        ...(accuracy === undefined ? {} : { accuracy }),
        ...(timeoutMs === undefined ? {} : { timeoutMs })
      }),
      watch: options => call('location.watch', options === undefined ? {} : options),
      clearWatch: watchId => call('location.clearWatch', { watchId }),
      onChanged: listener => subscribe('location.onChanged', listener)
    },
    alarms: {
      schedule: options => call('alarms.schedule', options),
      list: () => call('alarms.list'),
      cancel: id => call('alarms.cancel', { id }),
      onAlarm: listener => subscribe('alarm', listener)
    }
  };
  Object.defineProperty(globalThis, 'ToolBox', { value: Object.freeze(api), configurable: false, writable: false });
  globalThis.addEventListener('pagehide', () => {
    disposed = true;
    if (ackTimer !== null) clearTimeout(ackTimer);
    earlyEvents.clear(); queuedNames.clear(); lostEvents.clear();
    listeners.clear(); stateListeners.clear(); flushHandlers.clear();
    earlyBytes = 0;
    pending.forEach(waiter => waiter.reject(runtimeError('SESSION_ENDED', 'The tool document has ended')));
    pending.clear();
  });
  queueMicrotask(() => ensureReady().catch(reportError));
})();
