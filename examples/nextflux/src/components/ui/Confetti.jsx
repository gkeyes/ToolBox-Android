import confetti from "canvas-confetti";

export default function Confetti(buttonRef) {
  if (buttonRef.current) {
    const rect = buttonRef.current.getBoundingClientRect();
    const x = (rect.left + rect.width / 2) / window.innerWidth;
    const y = (rect.top + rect.height / 2) / window.innerHeight;

    confetti({
      angle: 90,
      particleCount: 20,
      startVelocity: 10,
      spread: 90,
      origin: { x, y },
      gravity: 2,
    });
  }
}
