import "@/components/Settings/settings.css";

// Design system v2 row icon: 36px tonal tile, radius 10. Legacy variant names
// map onto the semantic tones (plus three local hues) so callers stay unchanged.
const TONES = {
  default: "neutral",
  blue: "accent",
  purple: "purple",
  green: "success",
  red: "danger",
  pink: "pink",
  amber: "warning",
  orange: "warning",
  cyan: "cyan",
};

export default function SettingIcon({ variant = "default", children }) {
  return (
    <span className="nf-tile" data-tone={TONES[variant] || "neutral"} aria-hidden="true">
      {children}
    </span>
  );
}
