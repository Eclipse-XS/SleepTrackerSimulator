# SleepSense 2.0 — Before / After Acceptance Criteria

## 1. Quality Gates & Non-Negotiable Standards

Before any redesign pull request is approved, the implementation must satisfy these six global quality gates:

1. **Accessibility Gate (WCAG 2.2 AA)**:
   - Every text element must achieve a contrast ratio of at least **4.5:1** against its background (or **3.0:1** for text >= 18pt / 14pt bold).
   - Zero instances of `Color.Black` text rendered over `#0A0E17` or `#111827`.
   - All interactive touch targets must meet or exceed **48x48dp**.
   - All custom Canvas charts must supply descriptive TalkBack semantics.
2. **Layout & System Insets Gate**:
   - Zero visual collision between top app bar headers / back buttons and the Android system status bar (clock, battery, Wi-Fi).
   - Zero clipping of bottom action buttons by the system gesture navigation bar.
3. **Navigation & Gesture Gate**:
   - Zero unintended application exits on back gesture. Pressing back from any secondary or detail view must return to the immediate parent list or dashboard.
4. **Data Safety Gate**:
   - Every destructive operation (Delete Session, Delete Rule, Delete All Data, Reset Demo) must be protected by an explicit confirmation dialog.
5. **Data Provenance Gate**:
   - Real measurements (`PHONE_REAL`) and simulated signals (`SIMULATED`) must remain strictly isolated. Synthetic data must never be mixed into real baseline comparisons.
6. **Laboratory Subsystem Integrity Gate**:
   - All 7 university laboratory features in Developer Lab and Lab View must remain 100% operational and verifiable.

---

## 2. Screen-by-Screen Before / After Criteria

---

### Screen 01: Today / Dashboard (`TodayScreen.kt`)

| Inspection Item | Before (Current Defect) | After (Acceptance Criteria) | Verification Method |
| :--- | :--- | :--- | :--- |
| **Placement Selection** | No UI controls to select Mattress, Nightstand, or Wrist. Implicitly defaulted. | Hero card features interactive chips for `Mattress / Near Pillow`, `Nightstand`, and `On Body`. Selected placement is passed to `RecordingService`. | Tap placement chips; verify selected placement appears in active notification and saved session. |
| **Primary CTA Contrast**| "Start Recording" has dark text on `#6366F1` (contrast 3.71:1, failing WCAG AA). | Button uses `LavenderAccent` (`#818CF8`) with deep navy text (`#0B0F19`, contrast 8.2:1). Text is crisp and legible in dim light. | Color picker contrast calculation. |
| **Automations Shortcut**| Low-contrast outlined button (`MoonBlue` on `#0A0E17`, contrast 3.52:1). | Replaced with an informative card showing active rule count (e.g. `1 active scenario: Restless pulse`) and forward chevron. | Visual inspection; contrast >= 7:1. |
| **Error Feedback** | Snackbar dismisses automatically without clear recovery hints. | Error snackbar includes an action button (e.g. `Check Permissions` or `Retry`). | Trigger missing permission; verify actionable snackbar. |

---

### Screen 02: Active Recording Experience

| Inspection Item | Before (Current Defect) | After (Acceptance Criteria) | Verification Method |
| :--- | :--- | :--- | :--- |
| **Session Finish Safety**| Tapping `Finish` immediately terminates foreground recording. Accidental tap stops session prematurely. | `Finish` button requires a 1.5-second sustained hold with animated circular progress indicator before stopping. | Tap quickly -> No stop; Hold for 1.5s -> Stops session cleanly. |
| **Signal Quality Feedback**| Telemetry reads static text: `Phone sensor active · 20 Hz`. No reassurance that data is being recorded. | Displays dynamic signal quality badge (`Optimal (20 Hz) · Stable on Mattress`) that reflects real-time valid sample rate. | Inspect UI while shaking device; verify live telemetry updates. |
| **Bedside Display** | Vibrant bright blue buttons remain fully lit if screen stays on. | Optional `Bedside Display` mode renders a pure black screen with faint clock, waking on tap. | Tap Bedside Mode; verify OLED-friendly dark clock. |

---

### Screen 03: Session History & Details (`HistoryScreen.kt`)

| Inspection Item | Before (Current Defect) | After (Acceptance Criteria) | Verification Method |
| :--- | :--- | :--- | :--- |
| **System Back Handling**| Pressing system back key in `SessionDetailsView` exits the app completely! | System back key / gesture cleanly returns from `SessionDetailsView` to the session history list. | Open a session in history; swipe back on screen edge; verify list is displayed. |
| **Bottom Navigation Leak**| Bottom navigation bar remains visible while viewing session details. | Bottom navigation bar is hidden while viewing session details to maximize vertical canvas space. | Open session details; verify bottom bar is not drawn. |
| **Delete Confirmation** | Tapping the trash icon deletes the session immediately with CASCADE delete. | Tapping trash icon displays an `AlertDialog`: `Delete this session? This action cannot be undone.` | Tap trash icon; verify dialog appears; tap Cancel -> session preserved; tap Delete -> session removed. |
| **Timeline Accessibility**| Color timeline has zero text summary or accessibility semantics. | Below timeline, displays text summary: `Rest: 84% (6h 15m), Restless: 12% (52m), Active: 4% (20m)`. TalkBack announces full distribution. | Turn on TalkBack; focus timeline; verify speech output. |

---

### Screen 04: Personal Insights & Baselines (`InsightsScreen.kt`)

| Inspection Item | Before (Current Defect) | After (Acceptance Criteria) | Verification Method |
| :--- | :--- | :--- | :--- |
| **Trend Chart Clarity** | Bare, unlabeled zigzag line on a 96dp Canvas with no axes, dates, or values. | Chart displays labeled X-axis dates (`Mon 26`, `Tue 27`), Y-axis percentages (`0%`, `50%`, `100%`), and touch selection of individual nights. | Inspect trend chart; tap a point; verify tooltip shows date and percentage. |
| **Baseline Quality Gate**| When real sessions < 3, displays a dense text card explaining baseline in progress. | Displays a visual step indicator (`2 of 3 sessions recorded to establish your baseline`) with a direct CTA to record tonight. | Inspect with 1 real session; verify progress bar. |
| **Empty Real Analytics**| When real sessions = 0, displays raw text card with no action. | Empty state includes a prominent `Start Recording Tonight` button. | Tap button from empty state; verify navigation to Today tab. |

---

### Screen 05: Connected Sources & Devices (`DevicesScreen.kt`)

| Inspection Item | Before (Current Defect) | After (Acceptance Criteria) | Verification Method |
| :--- | :--- | :--- | :--- |
| **Mode Separation** | `REAL BLE` vs `DEMO` buttons mixed awkwardly above the device list. | Cleanly segmented toggle at top of Bluetooth section: `Physical BLE Hardware` vs `Simulated Mock`. | Switch between modes; verify device scanning behavior adapts immediately. |
| **Redundant Scan Actions**| Two different scan buttons (`Search for devices` and `Scan`) doing the exact same thing. | Single, prominent scan button with animated circular progress indicator during active scanning. | Tap scan; verify single button shows loading state and disables until scan completes. |
| **Heart Rate Contrast** | Red text `#EF4444` on `#1F2937` has contrast **2.75:1** (failing WCAG AA). | High-contrast coral `#F87171` on `#1A2234` achieves **5.2:1** contrast. Accompanied by `SIMULATED` badge. | Color picker contrast verification. |

---

### Screen 06: Automations & Rules (`SmartScenariosScreen.kt`)

| Inspection Item | Before (Current Defect) | After (Acceptance Criteria) | Verification Method |
| :--- | :--- | :--- | :--- |
| **Text Contrast Defect**| Headings, descriptions, and empty state are `Color.Black` on `#0A0E17` (**1.14:1** contrast). | All text renders with high-contrast Material 3 tokens (`#F8FAFC`, contrast >= **17:1**). | Visual inspection and contrast measurement. |
| **Status Bar Collision**| Top bar title and back arrow directly overlap with status bar clock and battery icons. | Standard `TopAppBar` with proper window insets ensures at least 8dp clearance below status bar icons. | Inspect on emulator with camera notch/cutout. |
| **Empty State Design** | Bare string `No saved rules`. | Rich educational hero card explaining inputs, outputs, and safety limits, with 3 quick-start templates. | Open automations with no rules; verify rich empty state. |
| **Delete Confirmation** | Tapping `Delete` removes rule instantly with no confirmation. | Displays an `AlertDialog` confirming deletion of the specific rule. | Tap Delete; verify dialog appears; tap Cancel -> rule remains; tap Confirm -> rule deleted. |
| **Hardware Safety Status**| No indication of rate limits or emergency controls. | Header displays global actuator safety pill (`Rate Limiter: Active · 15m Cooldown`) and one-tap emergency stop. | Inspect header; verify safety status matches `ActuatorUiState`. |

---

### Screen 07: Smart Scenario Rule Editor

| Inspection Item | Before (Current Defect) | After (Acceptance Criteria) | Verification Method |
| :--- | :--- | :--- | :--- |
| **CycleButton Anti-Pattern**| Cycling through enums by repeatedly clicking an OutlinedButton. | Replaced with SegmentedButtons for trigger type (`AI Sleep State` vs `Sensor Motion`), 3-way toggle chips for ML classes, and clean radio cards for actions. | Open editor; verify all options for a parameter are visible simultaneously. |
| **Input Validation** | Entering invalid numeric strings triggers raw exception or silent failure. | Form validates on the fly; inputs outside engine constraints (e.g. cooldown outside 0–3600000ms) show inline helper errors. | Enter `-5` in cooldown; verify inline error message appears and Save is disabled. |
| **Dialog Buttons Contrast**| "Cancel" and "Save" buttons have poor contrast against dialog background. | Standard Material 3 dialog buttons with high-contrast text and 48x48dp touch bounds. | Verify contrast >= 4.5:1. |

---

### Screen 08: Settings & Privacy (`SettingsScreen.kt`)

| Inspection Item | Before (Current Defect) | After (Acceptance Criteria) | Verification Method |
| :--- | :--- | :--- | :--- |
| **Status Bar Collision**| Header row overlaps status bar icons. | `TopAppBar` with insets padding ensures zero clipping. | Visual inspection on emulator. |
| **Data Export Redirection**| "Open Data Export Tools" bounces user to Developer Lab. | Provides an in-place export dialog that generates and shares the verified demo CSV file directly. | Tap Export; verify system document create launcher or share sheet opens. |

---

### Screen 09: Developer Lab Diagnostic Console (`DeveloperLabScreen.kt`)

| Inspection Item | Before (Current Defect) | After (Acceptance Criteria) | Verification Method |
| :--- | :--- | :--- | :--- |
| **Header Status Bar Overlap**| "Developer Lab" title and "Lab View" button overlap battery/wifi icons. | Header uses `TopAppBar` with proper window insets. | Visual inspection on emulator. |
| **ML Chips Line Wrapping**| FilterChips wrap text awkwardly (`STATIC_` on line 1, `BENCH` on line 2). | Chips are rendered in a horizontally scrollable container with minimum 48dp touch height. | Open ML Tab; verify all chip labels are single-line and legible. |
| **Subsystem Preservation**| Risk of breaking underlying university laboratory bindings during redesign. | 100% of diagnostic controls across all 7 tabs remain functional and pass automated verification. | Run all 7 lab verification routines; verify PASS status. |

---

### Screen 10: VirtualSleepBand Companion (`VirtualBandScreen.kt`)

| Inspection Item | Before (Current Defect) | After (Acceptance Criteria) | Verification Method |
| :--- | :--- | :--- | :--- |
| **Scenario Explanations**| Scenario chips (Normal, Deep, Restless, Arrhythmia) have no explanatory text. | Selecting a scenario displays a concise description of the simulated waveform and expected BPM range. | Tap Arrhythmia chip; verify explanatory card appears. |
| **Transmission Rates** | Chips show raw numbers (`250ms`, `500ms`, `1000ms`, `2000ms`). | Chips include transmission frequencies (`4 Hz`, `2 Hz`, `1 Hz`, `0.5 Hz`). | Inspect interval selector chips. |

---

## 3. Automated Test Verification Matrix

| Test Suite | Location | Verification Focus | Expected Outcome |
| :--- | :--- | :--- | :--- |
| **Unit Tests** | `app/src/test/java/...` | `SmartRuleEngineTest`, `ActuatorViewModelTest`, `ActivityClassifierTest`, `RoomMigrationTest` | 100% Tests Pass |
| **Compose UI Tests** | `app/src/androidTest/java/...` | `TodayScreenTest`, `SmartScenariosScreenTest`, `HistoryScreenTest`, `DevicesScreenTest` | 100% Tests Pass |
| **Accessibility Tests** | AndroidX Accessibility Test Framework | Contrast ratios, touch targets >= 48dp, TalkBack labels | Zero accessibility violations |
| **Screenshot Parity** | `docs/evidence/` | High-resolution capture of all 11 redesigned screens in Dark and Light modes | Documented visual acceptance |
