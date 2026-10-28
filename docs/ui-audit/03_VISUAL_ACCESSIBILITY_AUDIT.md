# SleepSense 2.0 — Visual & Accessibility Audit (WCAG 2.2 AA)

## 1. Root Cause Analysis: The "Black-on-Black" Contrast Defect

The prompt highlighted a known visual defect in the `Automations & Rules` screen:
> "Nearly black text on a dark background; extremely low contrast for headings and descriptions; weak visual hierarchy; an underdeveloped empty state; a screen that looks unfinished despite the underlying rule engine being implemented."

Our code inspection and live emulator verification on branch `codex/production-hardening-2026-10-27` confirmed this defect and revealed its exact architectural root causes.

### Code Proof: The Three Compounding Defects

```
[MainActivity.kt:295-300]
AppOverlay.AUTOMATIONS -> {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NightBlue) // NightBlue = Color(0xFF0A0E17) - Near pitch black
    ) {
        ...
        SmartScenariosScreen(...)
    }
}

[SmartScenariosScreen.kt:44-48, 69]
Column(modifier.fillMaxSize().verticalScroll(...).padding(20.dp)) {
    Text("Smart Scenarios")                                                <-- NO color passed!
    Text("Persistent rules evaluate real accelerometer and TFLite state.")  <-- NO color passed!
    Button(onClick = { creating = true }) { Text("Add rule") }
    ...
    if (state.rules.isEmpty()) Text("No saved rules")                      <-- NO color passed!
}
```

#### Defect Cause 1: Absence of `Surface` / `Scaffold` Container
In Jetpack Compose Material 3, the `Text` composable defaults its color to `LocalContentColor.current`.
- The `MaterialTheme` composable defines the `colorScheme`, but **does NOT provide `LocalContentColor`**.
- `LocalContentColor` is only provided by container composables like `Surface`, `Scaffold`, `Card`, or `TopAppBar`, which inspect the container's background color and invoke `CompositionLocalProvider(LocalContentColor provides contentColorFor(backgroundColor))`.
- When `MainActivity.kt` renders `AppOverlay.AUTOMATIONS`, it places `SmartScenariosScreen` inside a bare `Column` with `.background(NightBlue)`.
- Because a bare `Column` does not set `LocalContentColor`, it remains at the system root default: **`Color(0xFF000000)` (Pure Black)**.
- Pure black text (`#000000`) is drawn directly over `NightBlue` (`#0A0E17`).

#### Defect Cause 2: Incomplete Material 3 `DarkColors` Color Scheme
In `Theme.kt`:
```kotlin
private val DarkColors = darkColorScheme(
    background = NightBlue,
    primary = MoonBlue,
    secondary = CalmGreen,
    tertiary = MotionAmber,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceRaised,
    onBackground = MistBlue,
    onSurface = MistBlue,
)
```
Notice what is missing from `DarkColors`:
- `onSurfaceVariant` is **NOT defined**. Material 3 cards default to `surfaceVariant` (`DarkSurfaceRaised` = `#1F2937`). When Compose determines the content color for `DarkSurfaceRaised`, it checks `onSurfaceVariant`. Because it is missing, it falls back to the ambient `LocalContentColor` (Pure Black).
- `onPrimary` is **NOT defined**. Buttons using `primary = MoonBlue` (`#6366F1`) fall back to default black text on purple!
- `outline` and `outlineVariant` are **NOT defined**. Outlined buttons have dark grey borders with poor contrast.

#### Defect Cause 3: The "Hardcoded MistBlue" Band-Aid Antipattern
When other screens (e.g. `TodayScreen.kt`, `HistoryScreen.kt`, `InsightsScreen.kt`) were authored, the developers noticed the black text issue and manually added `color = MistBlue` to individual `Text` calls:
```kotlin
// TodayScreen.kt line 121
Text(text = "SleepSense", ..., color = MistBlue)

// TodayScreen.kt line 153
Text(text = "Last Session Summary", ..., color = MistBlue)
```
When `SmartScenariosScreen.kt` was written, the developer relied on Compose defaults, exposing the fundamental theming breakdown.

---

## 2. WCAG 2.2 AA Contrast Ratio Audit

WCAG 2.2 Criterion 1.4.3 (Contrast Minimum) mandates:
- **Normal text (< 18pt or < 14pt bold)**: Minimum **4.5:1** contrast ratio.
- **Large text (>= 18pt or >= 14pt bold)**: Minimum **3.0:1** contrast ratio.
- **UI Components and Graphical Objects (Criterion 1.4.11)**: Minimum **3.0:1** contrast ratio.

### Contrast Measurements Table

| Element / Location | Foreground | Background | Luminance (FG) | Luminance (BG) | Contrast Ratio | WCAG 2.2 AA Status | Severity |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **SmartScenarios: Headings & Subtitles** | `#000000` (Pure Black) | `#0A0E17` (NightBlue) | 0.0000 | 0.0068 | **1.14 : 1** | **FAIL** (Requires 4.5:1) | **P0** |
| **SmartScenarios: Empty state text** | `#000000` (Pure Black) | `#0A0E17` (NightBlue) | 0.0000 | 0.0068 | **1.14 : 1** | **FAIL** (Requires 4.5:1) | **P0** |
| **TodayScreen: "Smart Automation Rules"** | `#6366F1` (MoonBlue) | `#0A0E17` (NightBlue) | 0.1772 | 0.0068 | **3.52 : 1** | **FAIL** (Requires 4.5:1 for normal text) | **P1** |
| **TodayScreen: "Start Recording" Button Text**| `#111827` (DarkSurface) | `#6366F1` (MoonBlue) | 0.0125 | 0.1772 | **3.71 : 1** | **FAIL** (Requires 4.5:1 for 16sp bold) | **P1** |
| **SmartScenarios: Rule Card "Delete" Button** | `#6366F1` (MoonBlue) | `#1F2937` (DarkSurfaceRaised)| 0.1772 | 0.0245 | **3.05 : 1** | **FAIL** (Requires 4.5:1 for normal text) | **P1** |
| **SmartScenarios: Rule Editor "Cancel" Button**| `#6366F1` (MoonBlue) | `#2A2F3D` (Dialog Surface) | 0.1772 | 0.0381 | **2.58 : 1** | **FAIL** (Requires 4.5:1) | **P1** |
| **SessionDetails: Data Interpretation Notice** | `#6366F1` (MoonBlue) | `#1F2937` (Card Surface) | 0.1772 | 0.0245 | **3.05 : 1** | **FAIL** (Requires 4.5:1 for 12sp text) | **P1** |
| **DevicesScreen: Heart Rate Text** | `#EF4444` (HeartCoral) | `#1F2937` (Card Surface) | 0.1550 | 0.0245 | **2.75 : 1** | **FAIL** (Requires 4.5:1 for 14sp text) | **P1** |
| **TodayScreen: Header Date** | `MistBlue 60%` (`#9BA3B0`)| `#0A0E17` (NightBlue) | 0.3621 | 0.0068 | **7.25 : 1** | **PASS** | — |
| **TodayScreen: "SleepSense" Headline** | `#F8FAFC` (MistBlue) | `#0A0E17` (NightBlue) | 0.9500 | 0.0068 | **17.60 : 1** | **PASS** | — |
| **StatusBadge: READY TO RECORD** | `#10B981` (CalmGreen)| `#0E2922` (Green container) | 0.3800 | 0.0210 | **6.05 : 1** | **PASS** | — |
| **StatusBadge: SIMULATED** | `#EF4444` (HeartCoral)| `#2B1517` (Red container) | 0.1550 | 0.0150 | **3.15 : 1** | **PASS** (Large/Badge) | — |

---

## 3. Edge-to-Edge & System Insets Collision Audit

In `MainActivity.kt:238-241`, edge-to-edge layout is enabled:
```kotlin
enableEdgeToEdge(
    statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
    navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
)
```
This renders the app under Android system bars (status bar and gesture navigation bar).

### Confirmed Insets Defect
While `AnalyticsScreen.kt` correctly utilized `Modifier.statusBarsPadding()`, **none of the main overlay screens include status bar padding**:
- `AppOverlay.AUTOMATIONS` (`MainActivity.kt:295`): A bare `Column(Modifier.fillMaxSize().background(NightBlue))` starting at `y = 0`.
- `AppOverlay.DEVELOPER_LAB` (`DeveloperLabScreen.kt:113`): Top bar starts at `y = 0`.
- `AppOverlay.LEGACY_DASHBOARD` (`MainActivity.kt:361`): Header row starts at `y = 0`.
- `AppOverlay.SETTINGS` (`SettingsScreen.kt:94`): Header row starts at `y = 0`.

### Visual Evidence from Screenshots:
1. `09_automations.png` and `live_automations.png`: The back arrow and title `Automations & Rules` are rendered directly underneath the status bar clock (`2:11` / `9:28`) and battery percentage icons.
2. `10_developer_lab.png`: The `Developer Lab` header and `Lab View` button are overlapped by the Android battery icon, wifi icon, and clock.
3. `14_actuator_inspector.png`: The back icon button is physically obscured by the status bar clock, making it difficult to tap without triggering the Android notification shade.

---

## 4. Touch Targets & Component Sizing Audit

WCAG 2.2 Criterion 2.5.8 (Target Size - Minimum) requires at least **24x24 CSS pixels**, while Google Material Design 3 guidelines require a minimum touch target of **48x48dp**.

| Screen / Component | Current Visual Size | Measured Touch Target | Issue | Remediation |
| :--- | :--- | :--- | :--- | :--- |
| **DeveloperLab (ML Tab): Experiment FilterChips** | ~80 x 28dp | ~80 x 32dp | Target height is below 48dp; text wraps awkwardly (`STATIC_` / `BENCH`) | Expand chip padding, enable horizontal scrolling row with minimum 48dp touch bounds |
| **DeveloperLab: "Lab View" Button** | 76 x 32dp | 76 x 32dp | Height below 48dp, overlaps system battery icon | Use standard TopAppBar actions with 48x48dp touch target and insets |
| **History (Details): Trash Icon** | 24 x 24dp | 48 x 48dp (IconButton) | Size adequate, but dangerously close to edge without confirmation | Add confirmation dialog and position with clear margin |
| **Automations: "Add rule" Button** | 100 x 36dp | 100 x 36dp | Height below 48dp; isolated in top left | Redesign as a prominent full-width CTA or Floating Action Button (FAB) |
| **TodayScreen: "Pause" / "Finish" Buttons** | Weight(1f) x 48dp | 48dp height | Meets minimum, but `Finish` lacks hold-to-confirm | Maintain 52dp height; add confirmation for session stop |

---

## 5. Screen Reader (TalkBack) & Semantic Accessibility Audit

1. **Non-Text Content (WCAG 1.1.1)**:
   - `BandIllustration` (`SensorScreen.kt:225`): Canvas drawings of smart band have no `contentDescription` or semantics. TalkBack announces nothing or focuses an empty box.
   - `HorizontalActivityTimeline` (`HistoryScreen.kt:309`): Color-coded horizontal timeline canvas is completely opaque to screen readers. A blind user cannot know the distribution of REST vs ACTIVE intervals.
   - `Nightly low-motion trend` (`InsightsScreen.kt:107`): Canvas polyline lacks semantic description. Needs an accessible fallback (e.g. `semantics { contentDescription = "Trend line showing average low motion of X% over N nights" }`).
2. **Name, Role, Value (WCAG 4.1.2)**:
   - `CycleButton` (`SmartScenariosScreen.kt:132`): An OutlinedButton that changes its text upon clicking violates role expectations. Screen reader users expect a menu or radio dialog, not a button that mutates its own label silently.
3. **Status Changes (WCAG 4.1.3)**:
   - In `TodayScreen.kt`, active recording duration increments every second without announcing to screen readers (which is correct to avoid spam), but state transitions (`READY TO RECORD` -> `RECORDING IN PROGRESS` -> `SESSION PAUSED`) lack `accessibilityLiveRegion`, so users are not automatically informed when recording starts.

---

## 6. Light Mode & Theme Consistency Audit

Inspection of `Theme.kt` revealed that light mode is completely non-functional:
1. `SleepTrackerTheme` hardcodes `darkTheme: Boolean = true` as default, and `MainActivity.kt` never passes `isSystemInDarkTheme()`.
2. The `LightColors` definition in `Theme.kt` specifies only 4 colors (`primary`, `secondary`, `tertiary`, `surface`), omitting `background`, `onPrimary`, `onBackground`, `onSurface`, and all variants.
3. Screens across the app hardcode `containerColor = NightBlue` (`#0A0E17`) and `color = MistBlue` (`#F8FAFC`). Even if `darkTheme = false` were passed, screens would render an unusable mixture of hardcoded black backgrounds with light theme system components.

---

## 7. Findings Summary & Action Items

| Priority | Category | Finding | Impact | Immediate Solution |
| :--- | :--- | :--- | :--- | :--- |
| **P0** | Contrast | Black text on dark blue background in `SmartScenariosScreen` | Screen is completely unreadable | Wrap in `Scaffold`, supply proper `LocalContentColor`, complete `DarkColors` in `Theme.kt` |
| **P0** | Layout | System status bar overlaps headers on all overlay screens | Top app bars and back buttons collide with clock/battery | Add `Scaffold` with `WindowInsets` or apply `.statusBarsPadding()` |
| **P1** | Contrast | Button text (`DarkSurface` on `MoonBlue`, `MoonBlue` on dark surfaces) fails 4.5:1 | Fails WCAG AA, causes eye strain | Elevate accent color to `#818CF8` on dark, use pure white or dark navy text with 7:1+ contrast |
| **P1** | Usability | Destructive actions (Delete Session, Delete Rule) execute without confirmation | Irreversible data loss | Add `AlertDialog` confirmation with explicit action button |
| **P1** | Usability | "CycleButton" enum cycling in Rule Editor | Confusing, error-prone interaction | Replace with segmented controls, dropdown menus, or radio bottom sheets |
| **P2** | Accessibility| Custom Canvas charts (Timeline, Trend, Accelerometer) lack semantic content | Inaccessible to assistive tech | Add `Modifier.semantics { contentDescription = ... }` with computed statistical summaries |
| **P2** | Theming | Light mode broken and disabled | System light mode ignored | Build a complete Material 3 `LightColorScheme` and remove hardcoded colors |
