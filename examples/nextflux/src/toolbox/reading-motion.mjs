// Position is px, velocity is px/s and time is seconds. Both reading gestures
// use the same critically damped response, independent of display frame rate.
export const READING_SPRING = Object.freeze({
  mass: 1,
  stiffness: 1024,
  damping: 64,
  restDistance: 0.5,
  restSpeed: 8,
});

export function animateReadingValue({ from, to, velocity = 0, onUpdate, onDone, reduceMotion = false }) {
  const start = Number.isFinite(from) ? from : 0;
  const target = Number.isFinite(to) ? to : start;
  const distance = target - start;
  const omega = Math.sqrt(READING_SPRING.stiffness / READING_SPRING.mass);
  const direction = Math.sign(distance);
  // Keep release momentum toward the target without crossing it or recoiling.
  const speed = Number.isFinite(velocity) ? velocity : 0;
  const initialVelocity = direction * Math.min(Math.max(0, direction * speed), omega * Math.abs(distance));
  const displacement = start - target;
  const coefficient = initialVelocity + omega * displacement;
  const startedAt = performance.now();
  let cancelled = false;
  let frame = null;

  const cancel = () => {
    cancelled = true;
    if (frame !== null) cancelAnimationFrame(frame);
    frame = null;
  };
  if (reduceMotion || distance === 0) {
    onUpdate?.(target);
    onDone?.();
    return cancel;
  }
  onUpdate?.(start);
  const tick = (now) => {
    if (cancelled) return;
    const elapsed = Math.max(0, (now - startedAt) / 1000);
    const decay = Math.exp(-omega * elapsed);
    const offset = (displacement + coefficient * elapsed) * decay;
    const currentVelocity = (initialVelocity - omega * coefficient * elapsed) * decay;
    if (Math.abs(offset) <= READING_SPRING.restDistance && Math.abs(currentVelocity) <= READING_SPRING.restSpeed) {
      frame = null;
      onUpdate?.(target);
      if (!cancelled) onDone?.();
      return;
    }
    onUpdate?.(target + offset);
    if (!cancelled) frame = requestAnimationFrame(tick);
  };
  if (!cancelled) frame = requestAnimationFrame(tick);
  return cancel;
}


export const READING_ENTRANCE = Object.freeze({
  duration: 340,
  x1: 0.32,
  y1: 0.72,
  x2: 0,
  y2: 1,
});

function bezierCoordinate(t, a1, a2) {
  const inverse = 1 - t;
  return 3 * inverse * inverse * t * a1 + 3 * inverse * t * t * a2 + t * t * t;
}

export function readingEntranceEase(progress) {
  const target = Math.max(0, Math.min(1, Number(progress) || 0));
  if (target === 0 || target === 1) return target;
  let low = 0;
  let high = 1;
  let parameter = target;
  for (let index = 0; index < 14; index += 1) {
    const x = bezierCoordinate(parameter, READING_ENTRANCE.x1, READING_ENTRANCE.x2);
    if (x < target) low = parameter;
    else high = parameter;
    parameter = (low + high) / 2;
  }
  return bezierCoordinate(parameter, READING_ENTRANCE.y1, READING_ENTRANCE.y2);
}

export function animateReadingEntrance({
  from,
  to,
  onUpdate,
  onDone,
  reduceMotion = false,
  duration = READING_ENTRANCE.duration,
}) {
  const start = Number.isFinite(from) ? from : 0;
  const target = Number.isFinite(to) ? to : start;
  const distance = target - start;
  const total = Math.max(1, Number(duration) || READING_ENTRANCE.duration);
  let cancelled = false;
  let frame = null;

  const cancel = () => {
    cancelled = true;
    if (frame !== null) cancelAnimationFrame(frame);
    frame = null;
  };
  if (reduceMotion || distance === 0) {
    onUpdate?.(target);
    onDone?.();
    return cancel;
  }

  const startedAt = performance.now();
  onUpdate?.(start);
  const tick = (now) => {
    if (cancelled) return;
    const elapsed = Math.max(0, now - startedAt);
    if (elapsed >= total) {
      frame = null;
      onUpdate?.(target);
      if (!cancelled) onDone?.();
      return;
    }
    const progress = readingEntranceEase(elapsed / total);
    onUpdate?.(start + distance * progress);
    if (!cancelled) frame = requestAnimationFrame(tick);
  };
  if (!cancelled) frame = requestAnimationFrame(tick);
  return cancel;
}
