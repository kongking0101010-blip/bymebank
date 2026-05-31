/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  darkMode: "class",
  theme: {
    extend: {
      fontFamily: {
        sans: ["Geist", "Inter", "ui-sans-serif", "system-ui", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "Helvetica Neue", "Arial", "sans-serif"],
        mono: ["JetBrains Mono", "ui-monospace", "SFMono-Regular", "Menlo", "Consolas", "monospace"],
        display: ["Geist", "Inter", "system-ui", "sans-serif"],
      },
      colors: {
        // KhmerBank brand palette — deep indigo + electric mint
        brand: {
          50:  "#f0f4ff",
          100: "#dde6ff",
          200: "#bccfff",
          300: "#8eaaff",
          400: "#5e7dff",
          500: "#3a55ff",
          600: "#2535f5",
          700: "#1f28d4",
          800: "#1c25a8",
          900: "#1c2585",
          950: "#11154d",
        },
        accent: {
          50:  "#ecfff7",
          100: "#d1ffeb",
          200: "#a7fcd6",
          300: "#6ff5bb",
          400: "#34e69b",
          500: "#0ecf81",
          600: "#04a868",
          700: "#078555",
          800: "#0a6945",
          900: "#0a563b",
        },
        ink: {
          50:  "#f7f8fa",
          100: "#eef0f4",
          200: "#dadfe6",
          300: "#b6bdc9",
          400: "#8a93a4",
          500: "#646e80",
          600: "#4d5666",
          700: "#3c4453",
          800: "#262d3a",
          900: "#161a23",
          950: "#0a0d14",
        },
      },
      boxShadow: {
        soft: "0 1px 2px rgba(15,23,42,.04), 0 4px 16px rgba(15,23,42,.06)",
        glow: "0 0 0 1px rgba(58,85,255,.18), 0 8px 32px rgba(58,85,255,.18)",
      },
      backgroundImage: {
        "grid-light":
          "linear-gradient(to right, rgba(15,23,42,.04) 1px, transparent 1px), linear-gradient(to bottom, rgba(15,23,42,.04) 1px, transparent 1px)",
        "hero-gradient":
          "radial-gradient(1200px 600px at 0% 0%, #2535f5 0%, transparent 60%), radial-gradient(800px 600px at 100% 100%, #0ecf81 0%, transparent 55%)",
      },
      keyframes: {
        shimmer: {
          "0%":   { backgroundPosition: "-1000px 0" },
          "100%": { backgroundPosition: "1000px 0" },
        },
        floatY: {
          "0%, 100%": { transform: "translateY(0)" },
          "50%":      { transform: "translateY(-6px)" },
        },
      },
      animation: {
        shimmer: "shimmer 2s linear infinite",
        floatY: "floatY 4s ease-in-out infinite",
      },
    },
  },
  plugins: [],
};
