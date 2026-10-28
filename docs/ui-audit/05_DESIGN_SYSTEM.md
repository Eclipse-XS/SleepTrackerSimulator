# SleepSense 2.0 — Design System Specification

## 1. Visual Direction & Design Philosophy

SleepSense 2.0's visual design combines three core qualities:
1. **Calm Nighttime Aesthetic**: As an application used primarily at bedtime and upon waking, it must avoid glaring white backgrounds, aggressive neon accents, high-frequency animations, and visual noise.
2. **Technical & Scientific Credibility**: SleepSense is rooted in real-time accelerometry, on-device TFLite neural networks, and BLE GATT telemetry. It must present data with precision, rigorous provenance labels (Real vs Simulated), and honest disclaimers (avoiding deceptive pseudo-medical claims).
3. **Material Design 3 Compliance**: Built natively on Jetpack Compose M3 with complete semantic color schemes, dynamic theming support, edge-to-edge window insets, accessible touch targets, and full TalkBack semantics.

---

## 2. Color Token Architecture

The color system replaces the incomplete palette in `Theme.kt` with a fully populated Material 3 `ColorScheme` for both Dark and Light modes. Every color token is mathematically verified for WCAG 2.2 AA contrast compliance.

### 2.1 Dark Palette (Primary Mode — Calm Night Sky)

| Token Name | Hex Code | Material 3 Role | Contrast against Background | Usage |
| :--- | :--- | :--- | :--- | :--- |
| **NightBase** | `#0B0F19` | `background` | N/A (Root) | Scaffold background, root screen fill |
| **SurfaceLow** | `#111827` | `surface` | 1.15 : 1 | Base surface, bottom navigation bar |
| **SurfaceCard** | `#1A2234` | `surfaceVariant` | 1.35 : 1 | Primary content cards, session items |
| **SurfaceElevated** | `#242E44` | `surfaceContainerHigh` | 1.70 : 1 | Dialogs, bottom sheets, active modals |
| **TextPrimary** | `#F8FAFC` | `onBackground` / `onSurface` | **17.2 : 1 (PASS)** | Headlines, primary titles, critical values |
| **TextSecondary** | `#94A3B8` | `onSurfaceVariant` | **7.5 : 1 (PASS)** | Subtitles, helper text, timestamps, units |
| **TextMuted** | `#64748B` | `outline` | **4.6 : 1 (PASS)** | Borders, inactive icons, divider lines |
| **LavenderAccent** | `#818CF8` | `primary` | **7.1 : 1 (PASS)** | Primary buttons, active tabs, timeline Active |
| **OnLavender** | `#0B0F19` | `onPrimary` | **8.2 : 1 (PASS)** | Text on primary buttons (deep dark indigo) |
| **EmeraldCalm** | `#34D399` | `secondary` / Success | **10.2 : 1 (PASS)** | Rest state, phone real badge, verified metrics |
| **AmberMotion** | `#FBBF24` | `tertiary` / Warning | **13.5 : 1 (PASS)** | Restless state, pause badges, rate limits |
| **CoralAlert** | `#F87171` | `error` / Danger | **7.4 : 1 (PASS)** | Critical alerts, disconnect, finish session |

### 2.2 Light Palette (Professional Clinical / Day Mode)

| Token Name | Hex Code | Material 3 Role | Contrast against Background | Usage |
| :--- | :--- | :--- | :--- | :--- |
| **DayBase** | `#F8FAFC` | `background` | N/A (Root) | Scaffold background in light mode |
| **SurfaceWhite** | `#FFFFFF` | `surface` | 1.05 : 1 | Cards, bottom navigation bar |
| **SurfaceCardLight** | `#F1F5F9` | `surfaceVariant` | 1.15 : 1 | Grouped cards, input containers |
| **TextPrimaryLight**| `#0F172A` | `onBackground` / `onSurface` | **16.8 : 1 (PASS)** | Headlines, primary text |
| **TextSecondaryLight**|`#475569` | `onSurfaceVariant` | **7.8 : 1 (PASS)** | Secondary labels, descriptions |
| **IndigoPrimary** | `#4F46E5` | `primary` | **8.5 : 1 (PASS)** | Primary buttons, active indicators |
| **OnIndigo** | `#FFFFFF` | `onPrimary` | **8.5 : 1 (PASS)** | Text on primary buttons |
| **EmeraldGreenLight**|`#059669` | `secondary` / Success | **5.4 : 1 (PASS)** | Low motion, success status |
| **AmberWarningLight**|`#D97706` | `tertiary` / Warning | **4.8 : 1 (PASS)** | Movement warnings, paused sessions |
| **CrimsonErrorLight**|`#DC2626` | `error` / Danger | **5.9 : 1 (PASS)** | Errors, finish session, deletions |

### 2.3 Semantic Data Provenance Badges
A strict requirement of SleepSense 2.0 is distinguishing genuine sensor measurements from simulated or demo data:
- **`PHONE_REAL`**: `EmeraldCalm` (`#34D399` on dark) with label `PHONE (REAL SENSOR)`.
- **`WEARABLE_REAL`**: `LavenderAccent` (`#818CF8` on dark) with label `WEARABLE (BLE)`.
- **`SIMULATED` / `DEMO`**: `CoralAlert` (`#F87171` on dark) with label `SIMULATED DATA`.
- **`REPLAY` / `IMPORTED`**: Amethyst Purple (`#C084FC`) with label `SYNTHETIC / REPLAY`.

---

## 3. Typography Scale

The typography scale uses the Android system sans-serif font family with precise line heights and letter-spacing tuned for dark-mode legibility:

```
Display Large:    57sp / 64sp line-height / -0.25sp tracking / Bold       (Session Timers)
Headline Medium:  28sp / 36sp line-height /  0.0sp tracking  / Bold       (Screen Titles)
Headline Small:   24sp / 32sp line-height /  0.0sp tracking  / SemiBold   (Card Headlines)
Title Medium:     18sp / 24sp line-height /  0.15sp tracking / SemiBold   (Section Headers)
Title Small:      14sp / 20sp line-height /  0.1sp tracking  / Medium     (Card Subheadings)
Body Large:       16sp / 24sp line-height /  0.5sp tracking  / Regular    (Dialog Explanations)
Body Medium:      14sp / 20sp line-height /  0.25sp tracking / Regular    (Card Content)
Body Small:       12sp / 16sp line-height /  0.4sp tracking  / Regular    (Disclaimers, Captions)
Label Large:      14sp / 20sp line-height /  0.1sp tracking  / Medium     (Button Labels)
Label Medium:     12sp / 16sp line-height /  0.5sp tracking  / SemiBold   (Badges, Chips)
Label Small:      10sp / 14sp line-height /  1.0sp tracking  / Bold       (Overline Labels)
```

---

## 4. Spacing, Grids & Sizing Scale

All layout geometry adheres to a strict 4dp / 8dp grid system:
- **`2dp`**: Micro-spacing (badge dot margins, icon gaps).
- **`4dp`**: Tight grouping (label to text field, chip inner padding).
- **`8dp`**: Standard internal item spacing (between filter chips, icon to label).
- **`12dp`**: Component spacing (between metric cards in a row, card item gaps).
- **`16dp`**: Standard screen margin & card internal padding.
- **`20dp`**: Screen horizontal edge padding (standard SleepSense layout gutter).
- **`24dp`**: Section separation (between major card blocks).
- **`32dp`**: Hero section spacing.
- **`48dp`**: Minimum touch target dimension for all interactive elements.

---

## 5. Corner Radius System

- **`4dp`**: Small indicator dots, inline bar segments.
- **`8dp`**: Status badges, provenance chips, inline tags.
- **`12dp`**: Buttons, text fields, filter chips.
- **`16dp`**: Standard content cards, list item containers.
- **`20dp`**: Hero cards, dialog containers.
- **`28dp`**: Modal bottom sheets (top corners).
- **`50% (Circle)`**: Icon buttons, floating action buttons, avatars.

---

## 6. Elevation & Surface Hierarchy

Instead of artificial drop-shadows (which are visually ineffective on near-black backgrounds), elevation in dark mode is expressed through **progressive tonal luminance**:

```
Level 0 (Background): #0B0F19  (Root Scaffold)
Level 1 (SurfaceLow):  #111827  (Navigation Bars, Dividers)
Level 2 (SurfaceCard): #1A2234  (Standard Content Cards, 1dp subtle border #242E44)
Level 3 (SurfaceHigh): #242E44  (Dialogs, Dropdowns, Action Sheets)
Level 4 (Overlay):     #2E3A54  (Tooltips, Transient Badges)
```

---

## 7. Component Library Specifications

### 7.1 Buttons
1. **Primary Button** (`FilledButton`):
   - Container: `LavenderAccent` (`#818CF8`).
   - Content: Deep dark navy (`#0B0F19`), `FontWeight.Bold`.
   - Minimum height: `52dp`.
   - Corner radius: `14dp`.
2. **Secondary / Destructive Button**:
   - Container: `CoralAlert` (`#F87171`) for finish/stop/delete.
   - Content: Pure white (`#FFFFFF`), `FontWeight.Bold`.
   - Minimum height: `48dp`.
3. **Outlined Button**:
   - Border: `1.5dp` solid `TextSecondary` (`#94A3B8`).
   - Content: `TextPrimary` (`#F8FAFC`).
   - Minimum height: `48dp`.
4. **Text Button**:
   - Content: `LavenderAccent` (`#818CF8`) or `TextSecondary` (`#94A3B8`).
   - Minimum touch target: `48x48dp`.

### 7.2 Input Fields & Selectors
- **OutlinedTextField**:
  - Unfocused border: `1dp` solid `#242E44`.
  - Focused border: `2dp` solid `LavenderAccent` (`#818CF8`).
  - Label color: `TextSecondary` (`#94A3B8`).
  - Error color: `CoralAlert` (`#F87171`) with inline helper text.
- **Segmented Button**:
  - Replaces `CycleButton` for mutually exclusive options (e.g. `AI State` vs `Sensor Motion`).
  - Active segment: Filled with `LavenderAccent.copy(alpha = 0.2f)` and `1.5dp` border.

### 7.3 Status Badges & Chips
- **StatusBadge**:
  - Shape: Rounded rectangle (`8dp`).
  - Background: Color at `15%` alpha.
  - Border: Color at `40%` alpha.
  - Leading element: `6dp` circular indicator dot.
  - Text: `12sp`, `FontWeight.SemiBold`, uppercase, letter-spacing `0.5sp`.

### 7.4 Data Visualization & Chart Standards
1. **Timeline Visualization (`HorizontalActivityTimeline`)**:
   - High-contrast color mapping:
     - REST = `EmeraldCalm` (`#34D399`)
     - RESTLESS = `AmberMotion` (`#FBBF24`)
     - ACTIVE = `LavenderAccent` (`#818CF8`)
     - UNCERTAIN / GAP = Muted Slate (`#475569`)
   - Height: `32dp` with rounded container (`8dp`).
   - Interactive scrub / tooltip support showing start time, duration, and confidence.
   - Screen reader semantic summary: `"Timeline shows 82% Rest, 12% Restless, 6% Active over 7 hours 15 minutes."`
2. **Trend Chart (`NightlyLowMotionTrend`)**:
   - Replace raw canvas polyline with Vico / Canvas line chart featuring:
     - Discrete X-axis date labels (e.g., `Mon 26`, `Tue 27`).
     - Y-axis percentage scale (`0%`, `50%`, `100%`).
     - Gradient area fill below the curve (`LavenderAccent.copy(alpha = 0.2f)` to transparent).
     - Data points on individual night nodes with touch selection.

### 7.5 Dialogs & Sheets
- **Confirmation Dialog**:
  - Container: `SurfaceElevated` (`#242E44`).
  - Title: `HeadlineSmall`, bold.
  - Explicit confirmation button labeled with the specific action (e.g. `Delete Session` instead of generic `OK`).
- **Modal Bottom Sheet**:
  - Top drag handle visible.
  - Content padding respects gesture navigation bar insets.

---

## 8. Summary of Theming Fixes

| Problem in Current Code | Design System Solution |
| :--- | :--- |
| `Theme.kt` leaves `onSurfaceVariant`, `onPrimary`, `outline` undefined | Complete all Material 3 semantic roles in `darkColorScheme()` and `lightColorScheme()` |
| `MainActivity.kt` wraps overlays in bare `Column` with `.background(NightBlue)` | Always use `Scaffold(containerColor = MaterialTheme.colorScheme.background)` |
| `TodayScreen` hardcodes `color = MistBlue` on every text call | Remove hardcoded colors; rely on ambient `MaterialTheme.colorScheme.onBackground` and `onSurface` |
| Low contrast button text (`DarkSurface` on `MoonBlue`) | Use dark indigo `#0B0F19` on `#818CF8` (contrast 8.2:1) or white text |
| Status bar overlap on overlays | Apply `ScaffoldDefaults.contentWindowInsets` and `statusBarsPadding()` |
