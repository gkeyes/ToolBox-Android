(async () => {
  try {
    const options = {
      remoteOrigin: 'https://example.invalid',
      otherOrigin: document.documentElement.dataset.otherOrigin,
      includeLargeResource: true,
    };
    const page = await runWasmChecks(options);
    page.results.push({ name: 'profile-inline-policy', pass: (globalThis.inlineRan === true) === (document.documentElement.dataset.profile === 'compat') });
    // These tests run without a declared or granted network capability.
    page.results.push({ name: 'toolbox-main-page-present', pass: typeof ToolBox === 'object' });
    const runWorker = type => new Promise((resolve, reject) => {
      const worker = new Worker(type === 'module' ? 'module-worker.mjs' : 'classic-worker.js', { type });
      const timer = setTimeout(() => { worker.terminate(); reject(new Error(`${type} Worker timed out`)); }, 15000);
      worker.onmessage = event => { clearTimeout(timer); worker.terminate(); resolve(event.data); };
      worker.onerror = event => { clearTimeout(timer); worker.terminate(); reject(new Error(`${type} Worker: ${event.message}`)); };
      worker.postMessage({ ...options, includeLargeResource: false });
    });
    const [classic, module] = await Promise.all([runWorker('classic'), runWorker('module')]);
    globalThis.wasmReport = { page, classic, module };
  } catch (error) {
    globalThis.wasmReport = { fatal: String(error) };
  }
})();
