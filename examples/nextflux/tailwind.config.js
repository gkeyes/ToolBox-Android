/** @type {import('tailwindcss').Config} */
import typography from "@tailwindcss/typography";
import safe from "tailwindcss-safe-area";

export default {
  content: ["./index.html", "./src/**/*.{js,jsx,ts,tsx}"],
  theme: {
    extend: {
      colors: {},
      boxShadow: {
        custom: "var(--shadow-custom)",
        "custom-md": "var(--shadow-custom-md)",
        "custom-inner": "var(--shadow-custom-inner)",
        "custom-btn": "var(--shadow-custom-btn)",
      },
      keyframes: {
        "collapsible-down": {
          from: {
            height: "0",
            opacity: "0.3",
          },
          to: {
            height: "var(--radix-collapsible-content-height)",
            opacity: "1",
          },
        },
        "collapsible-up": {
          from: {
            height: "var(--radix-collapsible-content-height)",
            opacity: "1",
          },
          to: {
            height: "0",
            opacity: "0.3",
          },
        },
      },
      animation: {
        // Design system v2: expand on the spatial spring, collapse on the 220ms exit curve.
        "collapsible-down": "collapsible-down var(--ds-dur-spatial, 0.2s) var(--ds-ease-spatial, ease-out)",
        "collapsible-up": "collapsible-up var(--ds-dur-exit, 0.2s) var(--ds-ease-exit, ease-out)",
      },
    },
  },

  darkMode: ["selector", '[class$="dark"]'],
  plugins: [typography, safe],
};
