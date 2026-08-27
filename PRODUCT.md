# Product

## Register

Product. ToolBox is a native Android host whose interface serves the user's task; visual design must never compete with the imported tool itself.

## Users

ToolBox is for Android 13+ users who want to import small local HTML/CSS/JavaScript utilities, understand exactly what each package requests, and run it without surrendering broad device access. The primary reference is a one-handed HyperOS phone; tablet, foldable, landscape, large text and assistive-technology users are first-class.

## Purpose

ToolBox makes a `.tbx` package inspectable before trust, installs it atomically, and runs it inside a hardened exact-origin WebView behind capability grants the user can review and revoke. The host should disappear into the task while keeping security facts truthful and immediately reachable.

## Personality

Trustworthy, restrained, precise, efficient and native. The interface feels like a well-made instrument tray: compact enough for frequent use, calm under failure, and explicit whenever an action changes permissions, files, data or installed code.

## Anti-references

- Fake installed content, fixed sample counts or blank tools presented as real user state.
- Static or no-op controls that look actionable but do nothing.
- A card wall, oversized spacing or repeated shells that make an everyday utility feel loose and slow.
- Janky nested scrolling, ornamental motion or animation that delays feedback.
- Top, bottom or system chrome that ignores cutouts/navigation modes or crowds out tool content.
- Security theater: reassuring copy that is not derived from verified runtime state.
- Generic demo styling that prioritizes a host mockup over the actual tool and its result.

## Product principles

1. **Honest state before visual fullness.** A fresh install is an actionable empty state. Examples are real packages a user deliberately imports.
2. **Content first.** In the runtime, the imported tool owns nearly all usable space; host controls stay compact, recoverable and truthful.
3. **Secure by default, understandable by design.** Risk, signature, origin, permission and failure states are explicit, attributable and reversible.
4. **Dense with clarity.** Use one visual hierarchy, one scroll owner per axis and an 8dp rhythm; remove repeated containers before shrinking touch targets.
5. **Native and adaptive.** Miuix is wrapped behind ToolBox interfaces; edge-to-edge, cutouts, navigation modes, font scale, TalkBack, RTL and large windows are designed in.
6. **Motion communicates state.** Progress, completion, denial, retraction and recovery may animate; decorative choreography is forbidden and reduced-motion always works.
7. **Evidence earns release.** A green build is not a working product. Every visible action and every security branch needs fresh automated plus real-surface proof.

## Accessibility

- WCAG AA contrast for host text and controls.
- TalkBack labels, roles, state descriptions and logical focus order on every native route.
- At least 48dp for every interactive target, including compact runtime controls.
- Usable at font scale 2.0 and enlarged display size without clipping or unreachable actions.
- Status is never conveyed by color alone.
- Nonessential motion is disabled when system animator scale is off or reduced motion is requested.

## Release boundary

Store-ready means the exact signed artifact passes package, runtime, bridge, permission, network, accessibility, visual, performance and cleanup gates. Play publication additionally requires owner-provided signing/Play credentials, privacy/support URLs and any account-specific closed-testing eligibility; absence of those inputs must never be reported as publication.
