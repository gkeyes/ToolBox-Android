import { useFontLoader } from "@/hooks/useFontLoader.js";

// Keep reading-content/font subscriptions outside the application shell.
export default function FontLoader() {
  useFontLoader();
  return null;
}
