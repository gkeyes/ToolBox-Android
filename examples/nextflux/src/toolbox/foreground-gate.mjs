export function createNetworkPriorityGate() {
  let foreground = 0;
  const waiters = new Set();

  const wake = () => {
    if (foreground !== 0) return;
    for (const resolve of waiters) resolve();
    waiters.clear();
  };

  return {
    beginForeground() {
      foreground += 1;
      let done = false;
      return () => {
        if (done) return;
        done = true;
        foreground = Math.max(0, foreground - 1);
        wake();
      };
    },
    async waitForForeground(check = async () => {}) {
      while (foreground > 0) {
        await check();
        await new Promise((resolve) => {
          const timer = setTimeout(() => {
            waiters.delete(resume);
            resolve();
          }, 50);
          const resume = () => {
            clearTimeout(timer);
            waiters.delete(resume);
            resolve();
          };
          waiters.add(resume);
        });
      }
      await check();
    },
    get foregroundCount() { return foreground; },
  };
}
