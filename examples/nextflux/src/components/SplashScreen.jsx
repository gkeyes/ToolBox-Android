import { useLayoutEffect } from "react";

// Mounted with the destination page. Release the input-blocking splash in the
// same paint as the real content, including login and route errors.
export default function SplashScreen() {
  useLayoutEffect(() => {
    document.getElementById("splash-screen")?.remove();
  }, []);
  return null;
}
