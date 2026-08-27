---
name: ToolBox Android
description: A compact, trustworthy native host that keeps real tools in the foreground.
colors:
  action-blue: "#3482FF"
  action-on-blue: "#FFFFFF"
  canvas-light: "#F3F6FB"
  surface-light: "#FFFFFF"
  ink-light: "#111827"
  secondary-ink-light: "#737B8C"
  divider-light: "#E8EDF5"
  success: "#34C759"
  warning: "#FF9500"
  danger: "#FF3B30"
  canvas-dark: "#10141B"
  surface-dark: "#1A1F29"
  ink-dark: "#E8EDF5"
  secondary-ink-dark: "#B3BBCB"
typography:
  display:
    fontFamily: "Android system sans"
    fontSize: "26sp"
    fontWeight: 700
    lineHeight: 1.2
  headline:
    fontFamily: "Android system sans"
    fontSize: "18sp"
    fontWeight: 600
    lineHeight: 1.3
  body:
    fontFamily: "Android system sans"
    fontSize: "16sp"
    fontWeight: 400
    lineHeight: 1.45
  metadata:
    fontFamily: "Android system sans"
    fontSize: "13sp"
    fontWeight: 400
    lineHeight: 1.4
  label:
    fontFamily: "Android system sans"
    fontSize: "12sp"
    fontWeight: 500
    lineHeight: 1.3
rounded:
  badge: "12dp"
  dense-surface: "16dp"
  card: "22dp"
  full: "999dp"
spacing:
  half: "4dp"
  one: "8dp"
  one-half: "12dp"
  two: "16dp"
  two-half: "20dp"
  three: "24dp"
components:
  button-primary:
    backgroundColor: "{colors.action-blue}"
    textColor: "{colors.action-on-blue}"
    rounded: "{rounded.dense-surface}"
    height: "48dp"
    padding: "12dp 20dp"
  card-primary:
    backgroundColor: "{colors.surface-light}"
    textColor: "{colors.ink-light}"
    rounded: "{rounded.card}"
    padding: "16dp"
  input-search:
    backgroundColor: "{colors.surface-light}"
    textColor: "{colors.ink-light}"
    rounded: "{rounded.dense-surface}"
    height: "52dp"
    padding: "0 16dp"
  runtime-topbar:
    backgroundColor: "{colors.surface-light}"
    textColor: "{colors.ink-light}"
    height: "52dp"
    padding: "0 8dp"
  runtime-dock:
    backgroundColor: "{colors.surface-light}"
    textColor: "{colors.ink-light}"
    rounded: "{rounded.full}"
    height: "48dp"
    padding: "0 4dp"
---

# Design System: ToolBox Android

## Overview

**Creative North Star: "The Trusted Instrument Tray"**

ToolBox is an instrument tray, not a showroom. The user reaches for one utility, sees its result quickly, and can inspect the host's security facts without losing the work surface. Density is deliberate: repeated shells disappear, related facts group together, and whitespace separates decisions instead of padding every row.

The light board leads because the reference use is a phone in changing ambient light; dark and Monet modes preserve the same hierarchy. The system explicitly rejects fake installed content, static or no-op controls, a card wall, oversized spacing, janky nested scrolling, screen-consuming runtime chrome, and security theater.

**Key Characteristics:**

- Compact native hierarchy with an 8dp rhythm.
- One dominant action blue; semantic colors only communicate verified state.
- Tool content dominates runtime; host controls are compact and recoverable.
- Every interaction is reachable at 48dp and remains usable at 2.0 font scale.
- Motion is short, stateful and removable, never ornamental.

**The Content-First Rule.** Normal runtime chrome may identify and control the tool, but must never compete with or obscure it. Only exceptions and permission confirmations expand.

**The One-Owner Rule.** Each axis has one scroll owner and each window inset has one layout owner.

## Colors

The palette uses a cool neutral canvas, clean surfaces and one precise blue action voice; green, orange and red appear only when state earns them.

### Primary

- **Action Blue:** The only default accent for selected navigation, primary actions, focus and ToolBox-owned affordances.

### Secondary

- **Verified Green:** Successful integrity, trusted state and completed operations.
- **Caution Orange:** Unsigned, medium-risk or user-attention states.
- **Blocking Red:** Invalid, destructive or high-risk states that need explicit action.

### Neutral

- **Cool Canvas:** Separates the app background from white/light surfaces without ornamental gradients.
- **Instrument Ink:** High-contrast primary text and icon color.
- **Quiet Metadata:** Secondary copy only; never use it where contrast falls below 4.5:1.
- **Hairline Divider:** Groups dense rows when an extra card would be noise.

**The Earned Color Rule.** Semantic color is derived from real inspection, permission or runtime state. Copy alone can never turn a state green.

**The One Voice Rule.** Action Blue is scarce enough to remain meaningful; it must not tint every card or icon.

## Typography

**Display Font:** Android system sans
**Body Font:** Android system sans
**Label/Mono Font:** Web tool content may choose its own local font; the native host does not impose one.

**Character:** Native, highly legible and quiet. Weight and spacing create hierarchy; novelty fonts never carry security or action meaning.

### Hierarchy

- **Display** (700, 26sp, 1.2): One route title or high-value empty-state statement.
- **Headline** (600, 18sp, 1.3): Section and grouped-decision titles.
- **Title** (500–600, 16sp, 1.35): Tool names, settings and permission rows.
- **Body** (400, 16sp, 1.45): Actions, explanations and editable values.
- **Metadata** (400, 13sp, 1.4): Versions, sizes, origins and recent activity; wrap rather than truncate security facts.
- **Label** (500, 12sp, 1.3): Badges and compact state, always paired with a semantic announcement.

**The Security Copy Rule.** Signature, origin, permission and blocking explanations may wrap; never ellipsize away the fact the user is deciding on.

## Elevation

ToolBox is flat by default. Canvas/surface contrast and dividers establish structure; Miuix elevation appears only where a floating action, dialog or transient dock must sit above content. Broad decorative shadows are forbidden.

**The Structural Elevation Rule.** If removing a shadow does not make ownership or interaction ambiguous, remove it.

## Components

### Buttons

- **Shape:** Clearly curved, not pill-shaped, with a 48dp minimum height.
- **Primary:** Action Blue with white text; one primary commitment per decision surface.
- **Pressed / Focus:** Miuix state layer plus visible focus semantics; no scale bounce.
- **Destructive:** Red is reserved for the named destructive action inside a confirmation flow.

### Chips

- **Style:** 12dp badge radius, compact visual height for labels, but interactive chips keep a 48dp hit region.
- **State:** Selected state includes text/semantics, not color alone. Permission labels are dense badges unless they are actionable.

### Cards / Containers

- **Corner Style:** 22dp only for meaningful standalone groups; dense grouped rows use 16dp and dividers.
- **Background:** Surface over canvas; no nested cards.
- **Shadow Strategy:** Flat unless interaction ownership requires lift.
- **Internal Padding:** Usually 16dp; dense audit/review rows use 12–14dp.

### Inputs / Fields

- **Style:** 52dp search field, 16dp radius, clear label and real editable state.
- **Focus:** Action Blue focus treatment and logical IME progression.
- **Error / Disabled:** Text explanation plus semantic state; never leave a fake editable field with a no-op callback.

### Navigation

- Compact phones use a system-inset-aware bottom destination bar; medium/expanded windows use a side destination surface. Selected state has icon, label and semantics. The shared scaffold consumes system/cutout/IME insets exactly once.

### Runtime Shell

- A 48–52dp top control carries Back, the real tool name and expandable verified security state.
- A 48dp floating action dock provides refresh, permissions, external opening, diagnostics policy and details. It may retract on tool scroll/inactivity, but tap, keyboard focus and TalkBack always restore it.
- The WebView is the dominant surface. The dock's exclusion or overlay region may never hide focused or critical bottom content.
- Normal security state stays compact; abnormal and permission-confirmation states may expand with a truthful reason and action.

## Do's and Don'ts

### Do:

- **Do** use one 8dp spacing rhythm and remove repeated containers before reducing readable padding.
- **Do** show a real, actionable empty state on a fresh install.
- **Do** derive tool counts, sizes, signature, risk, origin and permission state from production data.
- **Do** keep every action at least 48dp and verify TalkBack, RTL and 2.0 font scale.
- **Do** give progress, success, failure, confirmation, retry and renderer recovery distinct feedback.
- **Do** keep the runtime content-first and system-bar/cutout/navigation safe.

### Don't:

- **Don't** ship fake installed content, fixed sample counts or blank tools presented as real user state.
- **Don't** render static or no-op controls that look actionable but do nothing.
- **Don't** build a card wall, nested cards or oversized spacing that makes an everyday utility feel loose and slow.
- **Don't** use janky nested scrolling, ornamental motion or animation that delays feedback.
- **Don't** let top, bottom or system chrome ignore cutouts/navigation modes or crowd out tool content.
- **Don't** use security theater: reassuring copy that is not derived from verified runtime state.
- **Don't** prioritize a generic demo host mockup over the actual tool and its result.
- **Don't** use gradients, decorative glass, colored side-stripe borders or broad ghost-card shadows.
