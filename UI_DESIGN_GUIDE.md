# UI/UX Design Style Guide

> Applied to the CameraGate Android app (pure Java, minSdk 16 / Android 4.x+).
> Differences from the original (Flutter) guide are marked **[Android]**.

## Design Philosophy

The application follows an **ultra-minimal, dark, sophisticated** aesthetic.
It is designed to feel professional and technical, prioritizing clarity and
functionality.

**Key Principles**:
- **Minimalism**: No unnecessary decorations, gradients, or shadows.
- **Hierarchy through Opacity**: Use varying opacity levels of white to
  establish visual hierarchy.
- **Micro-interactions**: Subtle feedback for every user action.
- **Technical Precision**: Use monospace fonts for all raw data and
  identifiers.

---

## Color System

Implemented in `app/src/main/res/values/colors.xml`.

### Base Palette
| Role | Color | Hex Code |
| :--- | :--- | :--- |
| **Background** | Pure Black | `#000000` |
| **Surface** | Near-Black | `#0A0A0A` |
| **Accent / Primary** | Pure White | `#FFFFFF` |

### Semantic Colors
| Role | Color | Hex Code |
| :--- | :--- | :--- |
| **Success** | Neon Green | `#00FF88` |
| **Warning** | Caution Yellow | `#FFCC00` |
| **Error / Destructive** | Pink-Red | `#FF3366` |

### Text & Elements
- **Primary Text**: Pure white (`#FFFFFF`)
- **Secondary Text**: Medium gray (`#666666`)
- **Dividers**: Very dark gray (`#1A1A1A`)

---

## Typography

Implemented in `app/src/main/res/values/styles.xml`; all labels are Android
string resources (uppercase enforced in the strings themselves, so the
capitalization works on every API level).

### Font Families
- **Primary**: `sans-serif` (System default; **[Android]** the original
  "Inter" font is omitted to keep the APK tiny and the app working on
  Android 4.x — the system Roboto family is visually equivalent).
- **Monospace**: `monospace` — used for IP addresses, URLs, and server
  configuration details.

### Text Styles
| Element | Size | Weight | Letter Spacing | Case | Style Name |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **App Title** | 20 | 900 | — | Uppercase | `AppTitle` |
| **Status Labels** | 10 | 600 | — | Uppercase | `StatusLabel` |
| **Section Headers** | 12 | 900 | — | Uppercase | `SectionHeader` |
| **Body (Strong)** | 14–16 | 600 | Normal | Mixed | `BodyStrong` |
| **Body (Secondary)** | 11–12 | 400 | Normal | Mixed | `BodySecondary` |
| **Button Labels** | 14 | 900 | — | Uppercase | `BtnPrimary`/`BtnDanger` |

> [!TIP] **[Android]** `letterSpacing` on `TextView` only exists since
> API 21. Since CameraGate must run on Android 4.x, letter spacing is
> **not** applied; uppercase + weight carries the premium look on 4.x.
> On API 21+ devices the same styles are used (no spacing) for consistency.

---

## Component Specifications

### Cards & Containers (`bg_card`)
- **Background**: `#0A0A0A` (Surface)
- **Border**: 1px solid `#1A1A1A`
- **Corner Radius**: 12dp
- **Elevation**: Always 0 (No shadows)

### Buttons

#### Main Action Button — CTA (`btn_primary`)
- **Height**: 56dp (64px)
- **Corner Radius**: 16dp
- **Idle Color**: Background: White | Text: Black
- **Active/Connected Color**: Background: Error Red | Text: Black
  (the START SERVER button switches to a red STOP SERVER state)
- **Disabled**: White at 5% opacity (`btn_disabled`)

#### Secondary / Outlined Button (`btn_outline`)
- **Border**: 1px White at 10% opacity
- **Text**: White
- **Corner Radius**: 12dp

### Form Inputs (`bg_input` / `input_bg`)
- **Fill**: `#0A0A0A`
- **Border**: 1px White at 10% opacity (Focused: 1px Pure White)
- **Corner Radius**: 12dp

**[Android]** all of the above are selector drawables with `_pressed`
variants (opacity bumps) — the InkWell ripple only exists on API 21+.
Press feedback comes from the drawable state list on every API level.

---

## Layout & Spacing

### Grid & Alignment
- **Screen Padding**: 24px horizontal, 32px vertical (24dp top / 32dp sides)
- **Inter-card Spacing**: 8dp (vertical)
- **Section Spacing**: 24–32dp

### Standard Units
- **Micro**: 4dp
- **Base**: 8dp
- **Double**: 16dp
- **Quad**: 32dp

---

## Interaction Patterns

### Visual Feedback
- **Taps**: selector drawables with `_pressed` states (white/red opacity
  bump) — the InkWell equivalent on all API levels.
- **Loading States**: rendered by swapping button content in `MainActivity`
  (e.g., START → transitioning → running).
- **Selection**: button state swap (START SERVER ↔ STOP SERVER) with
  semantic colors.

---

## Iconography
- **Preferred Style**: Outlined/Linear icons.
- **Size**: 18–20dp for list actions, 24dp for primary UI controls
  (notification icon 24dp).
- **Opacity**: 30% for decorative/secondary, 100% for active.

### App Icon
Camera + WiFi waves mark (see `scripts/generate_icon.py`): a camera body
drawn from WiFi-signal arcs on a pure-black rounded square. Applied to
launcher, round, adaptive+monochrome, splash, and notification icons.

---

## Development Guidelines

1. **Strictly No Shadows**: Use subtle borders (`1px`/`1dp`) to define depth
   and boundaries.
2. **Opacity Control**: Use white with varying opacity for hierarchy rather
   than different shades of gray.
   - `0.05`: Surface tints / Disabled backgrounds
   - `0.10`: Subtle borders
   - `0.30`: Hint text / Secondary icons
   - `0.50`: Secondary text
   - `1.00`: Primary text
3. **Consistency**: Always use the predefined styles in
   `app/src/main/res/values/styles.xml` and colors from `colors.xml`.
4. **Monospace for Technical Data**: Ensure all network-related strings
   (IP addresses, URLs, ports) use the `MonoData` style.
5. **[Android] Strings are pre-uppercased** in `strings.xml` —
   `textAllCaps` behaves inconsistently below API 21.

---

**This design system ensures a technical, professional aesthetic that is
modern and purpose-built for high-performance software.**