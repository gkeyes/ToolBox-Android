// Executed as an installed bundle script in each actual WebView/Worker context.
// evaluateJavascript is used by the Android harness only to read the final report.
globalThis.runWasmChecks = async function ({ remoteOrigin, otherOrigin, includeLargeResource }) {
  const results = [];
  const violations = [];
  const onViolation = event => violations.push({ directive: event.effectiveDirective, uri: event.blockedURI });
  globalThis.addEventListener('securitypolicyviolation', onViolation);
  const check = async (name, action) => {
    try {
      const detail = await action();
      results.push({ name, pass: true, detail: detail ?? null });
    } catch (error) {
      results.push({ name, pass: false, error: String(error) });
    }
  };
  const equal = (actual, expected) => {
    if (actual !== expected) throw new Error(`Expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
  };
  const rejectsAs = async (action, name) => {
    try { await action(); } catch (error) { equal(error.name, name); return error.name; }
    throw new Error(`Expected ${name}`);
  };
  const evidence = async (directive, uri) => {
    for (let attempt = 0; attempt < 100; attempt += 1) {
      const violation = violations.find(item => item.directive === directive &&
        (item.uri === uri || item.uri === uri + ':' || item.uri.startsWith(uri + '/') ||
          (uri === 'blob' && item.uri.startsWith('blob:'))));
      if (violation) return violation;
      await new Promise(resolve => setTimeout(resolve, 20));
    }
    throw new Error(`Missing CSP evidence for ${directive} ${uri}: ${JSON.stringify(violations)}`);
  };
  const addBytes = new Uint8Array([
    0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
    0x01, 0x07, 0x01, 0x60, 0x02, 0x7f, 0x7f, 0x01, 0x7f,
    0x03, 0x02, 0x01, 0x00,
    0x07, 0x07, 0x01, 0x03, 0x61, 0x64, 0x64, 0x00, 0x00,
    0x0a, 0x09, 0x01, 0x07, 0x00, 0x20, 0x00, 0x20, 0x01, 0x6a, 0x0b,
  ]);
  await check('streaming', async () => {
    const { instance } = await WebAssembly.instantiateStreaming(fetch('add.wasm'));
    equal(instance.exports.add(20, 22), 42);
  });
  await check('array-buffer', async () => {
    const bytes = await (await fetch('add.wasm')).arrayBuffer();
    const { instance } = await WebAssembly.instantiate(bytes);
    equal(instance.exports.add(-13, 55), 42);
  });
  await check('memory-bytes', async () => {
    const module = await WebAssembly.compile(addBytes);
    equal(new WebAssembly.Instance(module).exports.add(19, 23), 42);
  });
  for (const file of ['ADD.WASM', 'module.so', 'module-bytes']) {
    await check(`mime-and-streaming:${file}`, async () => {
      const response = await fetch(file);
      equal(response.status, 200);
      equal(response.headers.get('content-type'), 'application/wasm');
      equal(response.headers.get('x-content-type-options'), 'nosniff');
      const bytes = new Uint8Array(await response.clone().arrayBuffer());
      equal(Array.from(bytes).join(','), Array.from(addBytes).join(','));
      const { instance } = await WebAssembly.instantiateStreaming(response);
      equal(instance.exports.add(40, 2), 42);
    });
  }
  for (const file of ['companion.data', 'companion.bin', 'companion', 'empty.data', 'short.data']) {
    await check(`binary:${file}`, async () => {
      const response = await fetch(file);
      equal(response.status, 200);
      equal(response.headers.get('content-type'), 'application/octet-stream');
      const expected = file === 'empty.data' ? '' : file === 'short.data' ? '0,255' : '0,255,1,254,2,253';
      equal(Array.from(new Uint8Array(await response.arrayBuffer())).join(','), expected);
    });
  }
  if (includeLargeResource) await check('resource-above-old-20-mib-limit', async () => {
    const response = await fetch('large.data');
    equal(response.status, 200);
    const reader = response.body.getReader();
    let length = 0;
    let first;
    let last;
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      if (value.length) { first ??= value[0]; last = value[value.length - 1]; length += value.length; }
    }
    equal(length, 20 * 1024 * 1024 + 1);
    equal(first, 17);
    equal(last, 29);
  });
  await check('missing-resource', async () => {
    const response = await fetch('absent.wasm');
    equal(response.status, 404);
    return rejectsAs(() => WebAssembly.instantiateStreaming(Promise.resolve(response)), 'TypeError');
  });
  await check('malformed-module', () => rejectsAs(async () => {
    await WebAssembly.instantiateStreaming(fetch('malformed.wasm'));
  }, 'CompileError'));
  await check('missing-imports', () => rejectsAs(async () => {
    const bytes = await (await fetch('imports.wasm')).arrayBuffer();
    await WebAssembly.instantiate(bytes);
  }, 'TypeError'));
  await check('missing-import-function', () => rejectsAs(async () => {
    const bytes = await (await fetch('imports.wasm')).arrayBuffer();
    await WebAssembly.instantiate(bytes, { env: {} });
  }, 'LinkError'));
  await check('javascript-eval-denied', () => rejectsAs(() => eval('1 + 1'), 'EvalError'));
  await check('javascript-function-denied', () => rejectsAs(() => new Function('return 2')(), 'EvalError'));
  for (const [name, origin] of [['external', remoteOrigin], ['cross-tool', otherOrigin]]) {
    await check(`${name}-fetch-denied-with-csp`, async () => {
      await rejectsAs(() => fetch(origin + '/add.wasm'), 'TypeError');
      return evidence('connect-src', origin);
    });
  }
  await check('blob-worker-denied-with-csp', async () => {
    const url = URL.createObjectURL(new Blob(['postMessage("unexpected")'], { type: 'text/javascript' }));
    try {
      await new Promise((resolve, reject) => {
        let worker;
        const timer = setTimeout(() => { worker?.terminate(); reject(new Error('Blob Worker did not reject')); }, 2500);
        try {
          worker = new Worker(url);
          worker.onmessage = () => { clearTimeout(timer); worker.terminate(); reject(new Error('Blob Worker executed')); };
          worker.onerror = event => { event.preventDefault(); clearTimeout(timer); worker.terminate(); resolve(); };
        } catch (error) {
          clearTimeout(timer);
          if (error.name === 'SecurityError') resolve(); else reject(error);
        }
      });
      return await evidence('worker-src', 'blob');
    } finally { URL.revokeObjectURL(url); }
  });
  globalThis.removeEventListener('securitypolicyviolation', onViolation);
  return { results, violations };
};
