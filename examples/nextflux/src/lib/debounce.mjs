// Trailing-edge debounce with cancel() and flush(); replaces lodash/debounce.
export function debounce(fn, wait) {
  let timer = null;
  let lastArgs = null;
  const run = () => {
    timer = null;
    const args = lastArgs;
    lastArgs = null;
    return fn(...args);
  };
  const debounced = (...args) => {
    lastArgs = args;
    if (timer !== null) clearTimeout(timer);
    timer = setTimeout(run, wait);
  };
  debounced.cancel = () => {
    if (timer !== null) clearTimeout(timer);
    timer = null;
    lastArgs = null;
  };
  debounced.flush = () => {
    if (timer === null) return undefined;
    clearTimeout(timer);
    return run();
  };
  debounced.pending = () => timer !== null;
  return debounced;
}
