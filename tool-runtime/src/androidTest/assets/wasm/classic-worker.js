importScripts('common.js');
onmessage = async event => {
  const report = await runWasmChecks(event.data);
  report.results.push({ name: 'no-native-worker-bridge', pass: typeof ToolBox === 'undefined' });
  postMessage(report);
};
