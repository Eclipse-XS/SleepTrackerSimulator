# SleepSense 2.0 — Prioritized Issue Backlog

This backlog catalogs all identified usability, visual, accessibility, and architectural defects discovered during the Phase 1 & 2 audit of SleepSense 2.0 (`codex/production-hardening-2026-10-27`).

Issues are organized by severity:
- **P0**: Broken UI, completely unreadable text, system crashes, or dangerous/misleading interactions.
- **P1**: Major usability flaws, broken navigation flows, inaccessible controls, irreversible data loss risks.
- **P2**: Visual inconsistencies, weak empty states, suboptimal layout density, confusing terminology.
- **P3**: Cosmetic polish, spacing harmonization, micro-animations, minor typography adjustments.

---

## 1. Severity P0 Issues (Critical / Blockers)

### ISSUE-P0-01: Invisible Text on Automations Screen (Black on Pitch Dark Blue)
- **Screen**: Automations & Rules (`SmartScenariosScreen.kt`)
- **Evidence**: Screenshot `09_automations.png`, `live_automations.png`. Contrast ratio: **1.14:1**.
- **Root Cause**: `MainActivity.kt:295` wraps `SmartScenariosScreen` in a bare `Column(Modifier.background(NightBlue))` instead of a `Scaffold` or `Surface`. Compose does not set `LocalContentColor`, defaulting all unstyled `Text` composables to `Color.Black` against `#0A0E17`.
- **User Impact**: Screen appears completely blank/broken. Headings, descriptions, and empty state are unreadable.
- **Recommended Solution**: Wrap the screen in a Material 3 `Scaffold(containerColor = MaterialTheme.colorScheme.background)`, fully populate `DarkColors` in `Theme.kt` with `onBackground`, `onSurface`, and `onSurfaceVariant`.
- **Relevant File**: `app/src/main/java/com/example/sleeptrackersimulator/ui/smart/SmartScenariosScreen.kt`, `MainActivity.kt`, `Theme.kt`.
- **Estimated Effort**: 2 Story Points (~2 hours).
- **Regression Risk**: Low. Visual and container hierarchy change only.
- **Acceptance Criteria**: Text contrast on all headings, body, and empty state text achieves >= 7:1 against background.

---

### ISSUE-P0-02: Status Bar and Window Insets Collision on All Modal Overlays
- **Screen**: Automations (`SmartScenarios`), Developer Lab (`DeveloperLabScreen`), Settings (`SettingsScreen`), Lab View (`SensorScreen`).
- **Evidence**: Screenshots `09_automations.png`, `10_developer_lab.png`, `14_actuator_inspector.png`, `live_automations.png`. Top bar headers overlap with Android clock (`2:11`), Wi-Fi, and battery icons.
- **Root Cause**: `MainActivity.kt:238` calls `enableEdgeToEdge()` with transparent system bars, but overlay composables start layout at `y = 0` without applying `.statusBarsPadding()` or utilizing standard `Scaffold(contentWindowInsets = ...)`.
- **User Impact**: Back buttons are difficult or impossible to tap without pulling down the Android notification shade. Header text is visually corrupted by system icons.
- **Recommended Solution**: Implement standard Material 3 `TopAppBar` within a `Scaffold` for each overlay screen, which handles window insets automatically, or apply `Modifier.statusBarsPadding()` to top header rows.
- **Relevant File**: `app/src/main/java/com/example/sleeptrackersimulator/app/MainActivity.kt`, `DeveloperLabScreen.kt`, `SettingsScreen.kt`.
- **Estimated Effort**: 3 Story Points (~3 hours).
- **Regression Risk**: Low. Ensures proper layout padding across all devices and display cutouts.
- **Acceptance Criteria**: All top headers and back buttons maintain at least 8dp clearance below the physical status bar icons across all emulator cutouts.

---

## 2. Severity P1 Issues (Major Usability & Navigation Defects)

### ISSUE-P1-01: Missing System Back-Press Handler on Session Details Exits App
- **Screen**: Session Details View (`HistoryScreen.kt:205-337`)
- **Evidence**: Code inspection of `HistoryScreen.kt:61-67`. `SessionDetailsView` is rendered conditionally when `selectedSessionDetails != null`.
- **Root Cause**: There is no `BackHandler` composable registered inside `SessionDetailsView`. When the user swipes back or taps the system back key, the event is unhandled and caught by `MainActivity`, which exits the application!
- **User Impact**: Severe frustration. Users attempting to return from session details to the history list exit the app completely.
- **Recommended Solution**: Insert `BackHandler(enabled = details != null) { onBack() }` inside `SessionDetailsView`.
- **Relevant File**: `app/src/main/java/com/example/sleeptrackersimulator/ui/history/HistoryScreen.kt`.
- **Estimated Effort**: 1 Story Point (~30 minutes).
- **Regression Risk**: Zero.
- **Acceptance Criteria**: Pressing system back key or gesture in `SessionDetailsView` returns immediately to the history list without exiting the app.

---

### ISSUE-P1-02: Irreversible Session Deletion Without Confirmation Dialog
- **Screen**: Session Details (`HistoryScreen.kt:246`)
- **Evidence**: Line 246: `IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, ...) }`. Clicking immediately calls `sessionRepository.deleteSession(sessionId)`, performing a CASCADE delete on all sensor samples, feature windows, and predictions in Room v7.
- **Root Cause**: No confirmation state or `AlertDialog` guarding the delete action.
- **User Impact**: Catastrophic accidental data loss. A single mistap on the trash icon permanently destroys a full night of sleep data.
- **Recommended Solution**: Add a `var showDeleteDialog by remember { mutableStateOf(false) }` and display an M3 `AlertDialog` requiring explicit user confirmation before calling `onDelete`.
- **Relevant File**: `app/src/main/java/com/example/sleeptrackersimulator/ui/history/HistoryScreen.kt`.
- **Estimated Effort**: 1 Story Point (~1 hour).
- **Regression Risk**: Zero.
- **Acceptance Criteria**: Tapping the trash icon displays a confirmation dialog. Session is deleted only after tapping "Delete Session" in the dialog.

---

### ISSUE-P1-03: Irreversible Rule Deletion Without Confirmation Dialog
- **Screen**: Automations & Rules (`SmartScenariosScreen.kt:64`)
- **Evidence**: Line 64: `TextButton(onClick = { onDelete(rule) }) { Text("Delete") }`. Clicking immediately deletes the rule from the Room database.
- **Root Cause**: Direct invocation of `onDelete` with no intermediate confirmation state.
- **User Impact**: Accidental deletion of carefully calibrated automation rules.
- **Recommended Solution**: Add an `AlertDialog` confirming rule deletion with the rule's name displayed.
- **Relevant File**: `app/src/main/java/com/example/sleeptrackersimulator/ui/smart/SmartScenariosScreen.kt`.
- **Estimated Effort**: 1 Story Point (~1 hour).
- **Regression Risk**: Zero.
- **Acceptance Criteria**: Rule deletion requires explicit confirmation in an alert dialog.

---

### ISSUE-P1-04: Non-Standard "CycleButton" Interaction in Rule Editor
- **Screen**: Smart Scenario Rule Editor (`SmartScenariosScreen.kt:80-132`)
- **Evidence**: Screenshot `live_rule_editor.png`. Trigger source, operator, and action are configured via buttons that cycle forward through an enum array on click.
- **Root Cause**: Developer implemented a temporary cycling helper `CycleButton` instead of proper Material 3 selection components.
- **User Impact**: Highly confusing interaction. Users cannot see available choices without clicking blindly. Reversible choices require cycling through the entire list again. Completely inaccessible to screen readers.
- **Recommended Solution**: Replace with standard Material 3 components: SegmentedButtons for binary/ternary choices, DropdownMenu or ExposedDropdownMenuBox for sensor sources, and clear radio lists for actions.
- **Relevant File**: `app/src/main/java/com/example/sleeptrackersimulator/ui/smart/SmartScenariosScreen.kt`.
- **Estimated Effort**: 3 Story Points (~4 hours).
- **Regression Risk**: Low. Must preserve the exact validation logic in `SmartRule.validate()`.
- **Acceptance Criteria**: Users can see all available options at once and select their desired source, operator, and action in a single tap.

---

### ISSUE-P1-05: Missing Device Placement & Source Mode Selectors on Today Screen
- **Screen**: Today / Dashboard (`TodayScreen.kt`)
- **Evidence**: `TodayViewModel` has `selectedSourceMode` (`PHONE_ONLY`, `WEARABLE_ONLY`, `COMBINED`) and `selectedPlacement` (`MATTRESS`, `NIGHTSTAND`, `BODY_WRIST`), but `TodayScreen.kt` provides no UI controls to view or modify them before tapping `Start Recording`.
- **Root Cause**: The UI omitted the input chips, defaulting implicitly to `PHONE_ONLY` and `MATTRESS`.
- **User Impact**: Users cannot tell the app they placed the phone on a nightstand or are wearing a smart band, degrading data provenance and personal baseline comparisons.
- **Recommended Solution**: Add a clear Placement and Source selection section to `SessionHeroCard`.
- **Relevant File**: `app/src/main/java/com/example/sleeptrackersimulator/ui/today/TodayScreen.kt`, `TodayViewModel.kt`.
- **Estimated Effort**: 2 Story Points (~2 hours).
- **Regression Risk**: Low. Passes selected values to `RecordingService.start()`.
- **Acceptance Criteria**: User can tap chips to choose Mattress, Nightstand, or Wrist, and the chosen placement is saved with the recorded session.

---

### ISSUE-P1-06: Color Contrast Failures on Primary Buttons & Chips
- **Screen**: Today (`TodayScreen.kt`), History (`HistoryScreen.kt`), Devices (`DevicesScreen.kt`).
- **Evidence**:
  - `TodayScreen`: "Smart Automation Rules" text `#6366F1` on `#0A0E17` = **3.52:1** (Fails 4.5:1).
  - `TodayScreen`: "Start Recording" button text `#111827` on `#6366F1` = **3.71:1** (Fails 4.5:1).
  - `DevicesScreen`: Heart rate text `#EF4444` on `#1F2937` = **2.75:1** (Fails 4.5:1).
- **Root Cause**: Hardcoded dark text on medium-luminance purple buttons, and low-luminance accent colors on dark surfaces.
- **User Impact**: Text is difficult to read under normal bedroom lighting and impossible for users with low vision.
- **Recommended Solution**: Adopt the new Design System tokens: `#818CF8` for accent with deep dark navy text (`#0B0F19`, contrast 8.2:1), and high-contrast coral `#F87171` for badges.
- **Relevant File**: `TodayScreen.kt`, `DevicesScreen.kt`, `Theme.kt`, `SleepSenseComponents.kt`.
- **Estimated Effort**: 2 Story Points (~2 hours).
- **Regression Risk**: Low.
- **Acceptance Criteria**: All interactive buttons and text labels achieve >= 4.5:1 contrast ratio against their immediate background.

---

## 3. Severity P2 Issues (Visual Inconsistencies & Weak Ergonomics)

### ISSUE-P2-01: Persistent Bottom Navigation Leaks into Session Details View
- **Screen**: Session Details (`MainActivity.kt:412-520`)
- **Evidence**: Screenshot `06_session_details.png`. Bottom bar is visible on details screen.
- **Root Cause**: `MainActivity` nests the entire navigation body (including `HistoryScreen`) inside a single outer `Scaffold` with a persistent `bottomBar`.
- **User Impact**: Screen feels cramped; violates Material Design hierarchical drill-down rules.
- **Recommended Solution**: Conditionally hide `bottomBar` when `selectedSessionDetails != null`, or elevate session details to a full modal route.
- **Relevant File**: `MainActivity.kt`, `HistoryScreen.kt`.
- **Estimated Effort**: 2 Story Points (~2 hours).
- **Regression Risk**: Low.
- **Acceptance Criteria**: Bottom navigation bar slides away or is hidden when viewing session details.

---

### ISSUE-P2-02: Underdeveloped Empty State on Automations Screen
- **Screen**: Automations & Rules (`SmartScenariosScreen.kt:69`)
- **Evidence**: Screen renders only `No saved rules`.
- **Root Cause**: No onboarding composable was authored for the empty list state.
- **User Impact**: Users do not know what smart rules do, which sensors can trigger them, or what actions they can perform.
- **Recommended Solution**: Implement the rich educational empty state specified in `docs/ui-audit/04_AUTOMATIONS_RULES_REDESIGN.md`.
- **Relevant File**: `app/src/main/java/com/example/sleeptrackersimulator/ui/smart/SmartScenariosScreen.kt`.
- **Estimated Effort**: 2 Story Points (~2 hours).
- **Regression Risk**: Zero.
- **Acceptance Criteria**: Empty state displays hero card, capabilities breakdown, and quick-start templates.

---

### ISSUE-P2-03: Bare, Unlabeled Polyline Chart on Insights Screen
- **Screen**: Personal Insights (`InsightsScreen.kt:107-120`)
- **Evidence**: Screenshot `insights-demo-90.png`. 96dp Canvas with a single thin blue zigzag line.
- **Root Cause**: Raw `Canvas.drawLine` loop with no axes, labels, or point markers.
- **User Impact**: Chart provides zero actionable insight; users cannot read dates or values.
- **Recommended Solution**: Redesign trend chart using Vico or enhanced Canvas with date axis, percentage markers, gradient fill, and touch exploration.
- **Relevant File**: `app/src/main/java/com/example/sleeptrackersimulator/ui/insights/InsightsScreen.kt`.
- **Estimated Effort**: 3 Story Points (~4 hours).
- **Regression Risk**: Low.
- **Acceptance Criteria**: Trend chart displays X-axis dates, Y-axis percentages, and a clear baseline line.

---

### ISSUE-P2-04: Non-Functional Light Mode & Hardcoded Themes
- **Screen**: Global Application
- **Evidence**: `Theme.kt:28` hardcodes `darkTheme = true`. `LightColors` is missing core color tokens.
- **Root Cause**: Application was developed dark-mode-first; light mode was abandoned midway.
- **User Impact**: Light mode users are forced into dark mode, and dynamic color is ignored.
- **Recommended Solution**: Complete `LightColors` according to `docs/ui-audit/05_DESIGN_SYSTEM.md` and hook `darkTheme` to `isSystemInDarkTheme()`.
- **Relevant File**: `app/src/main/java/com/example/sleeptrackersimulator/ui/theme/Theme.kt`, `Color.kt`.
- **Estimated Effort**: 2 Story Points (~2 hours).
- **Regression Risk**: Low.
- **Acceptance Criteria**: App switches cleanly between dark and light themes based on Android system setting.

---

### ISSUE-P2-05: Missing Screen Reader Semantics on Custom Canvases
- **Screen**: History Timeline (`HorizontalActivityTimeline`), Insights Trend Chart (`InsightsScreen`), Accelerometer Strip (`SensorScreen`).
- **Evidence**: TalkBack skips custom Canvas drawings or reads empty boxes.
- **Root Cause**: Custom drawing code lacks `Modifier.semantics { contentDescription = ... }`.
- **User Impact**: Visually impaired users have no access to activity timelines or rest trends.
- **Recommended Solution**: Add calculated textual summaries to the semantics tree for all visual charts.
- **Relevant File**: `SleepSenseComponents.kt`, `InsightsScreen.kt`, `SensorScreen.kt`.
- **Estimated Effort**: 2 Story Points (~2 hours).
- **Regression Risk**: Zero.
- **Acceptance Criteria**: TalkBack speaks a concise statistical summary when focusing on any chart or timeline.

---

## 4. Severity P3 Issues (Cosmetic Polish & Micro-Interactions)

### ISSUE-P3-01: Awkward Text Wrapping on ML FilterChips
- **Screen**: Developer Lab ML Tab (`DeveloperLabScreen.kt:686-749`)
- **Evidence**: Screenshot `16_ml_inspector.png`. Chips wrap text (`STATIC_` on line 1, `BENCH` on line 2).
- **Root Cause**: Insufficient chip width and rigid flex layout.
- **Recommended Solution**: Place chips in a scrollable row with proper horizontal padding.
- **Relevant File**: `DeveloperLabScreen.kt`.
- **Estimated Effort**: 1 Story Point (~30 minutes).

### ISSUE-P3-02: Instant Recording Stoppage Without Hold-to-Confirm
- **Screen**: Today Recording Active (`TodayScreen.kt:370`)
- **Evidence**: Tapping "Finish" immediately terminates foreground service.
- **Recommended Solution**: Add a 1.5-second hold-to-confirm gesture with circular progress animation.
- **Relevant File**: `TodayScreen.kt`.
- **Estimated Effort**: 2 Story Points (~2 hours).

### ISSUE-P3-03: Modal Overlays Lack Back-Stack History
- **Screen**: Navigation Architecture (`MainActivity.kt:252`)
- **Evidence**: Navigating Settings -> Dev Lab -> Back returns directly to Today rather than Settings.
- **Recommended Solution**: Use an internal `ArrayDeque<AppOverlay>` navigation stack.
- **Relevant File**: `MainActivity.kt`.
- **Estimated Effort**: 2 Story Points (~2 hours).

---

## 5. Backlog Summary Table

| Issue ID | Priority | Category | Component / Screen | Effort (Hours) | Risk |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **ISSUE-P0-01** | **P0** | Contrast Defect | Automations (`SmartScenariosScreen`) | 2h | Low |
| **ISSUE-P0-02** | **P0** | Insets Collision | Overlays (`MainActivity`, `DevLab`, `Settings`) | 3h | Low |
| **ISSUE-P1-01** | **P1** | Navigation Bug | Session Details (`HistoryScreen`) | 0.5h | Zero |
| **ISSUE-P1-02** | **P1** | Data Loss | Session Details (`HistoryScreen`) | 1h | Zero |
| **ISSUE-P1-03** | **P1** | Data Loss | Automations (`SmartScenariosScreen`) | 1h | Zero |
| **ISSUE-P1-04** | **P1** | Usability / A11y | Rule Editor (`SmartScenariosScreen`) | 4h | Low |
| **ISSUE-P1-05** | **P1** | Core UX | Today Screen (`TodayScreen`) | 2h | Low |
| **ISSUE-P1-06** | **P1** | Contrast / A11y | Primary Buttons & Badges | 2h | Low |
| **ISSUE-P2-01** | **P2** | Navigation Leak | Session Details / BottomBar | 2h | Low |
| **ISSUE-P2-02** | **P2** | Empty State | Automations (`SmartScenariosScreen`) | 2h | Zero |
| **ISSUE-P2-03** | **P2** | Data Viz | Trend Chart (`InsightsScreen`) | 4h | Low |
| **ISSUE-P2-04** | **P2** | Theming | Global Theme (`Theme.kt`, `Color.kt`) | 2h | Low |
| **ISSUE-P2-05** | **P2** | Accessibility | Custom Canvas Semantics | 2h | Zero |
| **ISSUE-P3-01** | **P3** | Layout Density | ML FilterChips (`DeveloperLabScreen`) | 0.5h | Zero |
| **ISSUE-P3-02** | **P3** | Safety Guard | Hold-to-Finish Button (`TodayScreen`) | 2h | Low |
| **ISSUE-P3-03** | **P3** | Navigation Polish| Overlay Stack (`MainActivity`) | 2h | Low |
| **TOTAL** | — | — | **16 Issues Identified** | **28h** | — |
