# SleepSense 2.0 — Screen Inventory & Codebase Discovery

## 1. Executive Summary

This inventory documents all screens, sub-views, dialogs, overlays, and companion components in the **SleepSense 2.0** Android application (repository: `SleepTrackerSimulator`, branch: `codex/production-hardening-2026-10-27`).

The codebase is built with **Kotlin 2.0.21** and **Jetpack Compose (Material 3)**. It integrates seven distinct university laboratory workflows (Sensor Ingestion, BLE GATT, Cloud Sync, Actuators, Room Persistence, On-device TFLite ML, and Smart Scenarios). The project consists of two modules:
1. `:app` — The primary user-facing wellness application with embedded developer diagnostic tools.
2. `:virtualsleepband` — A standalone companion application acting as a BLE GATT Peripheral simulator (Heart Rate Service `0x180D`, Battery Service `0x180F`).

---

## 2. Global Architecture & Navigation Model

Navigation in `MainActivity.kt` uses a **hybrid state-driven architecture** rather than Jetpack Navigation Component:
- **Bottom Navigation Bar**: 4 persistent primary tabs (`AppTab.TODAY`, `AppTab.HISTORY`, `AppTab.INSIGHTS`, `AppTab.DEVICES`).
- **Modal Overlay System**: Managed via `activeOverlay` enum (`NONE`, `SETTINGS`, `AUTOMATIONS`, `DEVELOPER_LAB`, `LEGACY_DASHBOARD`).
- **Sub-screen State**: Managed within individual tab viewmodels (e.g., `selectedSessionDetails` in `HistoryViewModel`).

```
+-----------------------------------------------------------------------------------+
|                                  MainActivity                                     |
+-----------------------------------------------------------------------------------+
| Overlays:                                                                         |
|  - AppOverlay.SETTINGS          -> SettingsScreen.kt                              |
|  - AppOverlay.AUTOMATIONS       -> SmartScenariosScreen.kt (Embedded)             |
|  - AppOverlay.DEVELOPER_LAB     -> DeveloperLabScreen.kt (Tabs L1-L7)             |
|  - AppOverlay.LEGACY_DASHBOARD  -> SensorScreen.kt (Connected / Disconnected)     |
|                                                                                   |
| Primary Scaffold (AppOverlay.NONE):                                               |
|  - Tab 0: TODAY                 -> TodayScreen.kt                                 |
|  - Tab 1: HISTORY               -> HistoryScreen.kt / SessionDetailsView          |
|  - Tab 2: INSIGHTS              -> InsightsScreen.kt                              |
|  - Tab 3: DEVICES               -> DevicesScreen.kt                               |
+-----------------------------------------------------------------------------------+
| BottomNavigationBar: [ Today ] [ History ] [ Insights ] [ Devices ]               |
+-----------------------------------------------------------------------------------+
```

---

## 3. Screen-by-Screen Inventory

---

### Screen 01: Today / Dashboard (Hero Session Hub)
- **Location**: `app/src/main/java/com/example/sleeptrackersimulator/ui/today/TodayScreen.kt`
- **ViewModel**: `TodayViewModel.kt`
- **State Model**: `TodayUiState` (`activeSession`, `sessionStatus`, `lastSummary`, `activeDurationSeconds`, `selectedSourceMode`, `selectedPlacement`, `errorMessage`)
- **User Goal**: Check today's date, view device readiness, initiate or manage a sleep recording session, review last session summary, and quickly access automations or settings.

#### Component Structure:
1. **Header Row**:
   - Formatted Date (e.g. `WEDNESDAY, 28 OCTOBER`)
   - Brand Title: `SleepSense`
   - Settings Gear Icon Button (`Icons.Rounded.Settings`)
2. **Session Hero Card** (`SessionHeroCard`):
   - **Idle / Completed State**:
     - Status badges: `READY TO RECORD`, `LOCAL ONLY`
     - Title: `Tonight's Session`
     - Subtitle: `Monitor device movement and view your activity timeline afterward.`
     - Sensor status chip: `PHONE Phone Accelerometer · Ready`
     - Primary Action Button: `Start Recording` (`Icons.Rounded.PlayArrow`, `MoonBlue` background)
   - **Active Recording / Paused State**:
     - Status badge: `RECORDING IN PROGRESS` (Green) or `SESSION PAUSED` (Amber)
     - Provenance chip: `PHONE`
     - Large Monospace Ticker: `HH:MM:SS` (`activeDurationSeconds`)
     - Telemetry subtitle: `Phone sensor active · 20 Hz · On-device TFLite model`
     - Action Buttons: `Pause` / `Resume` toggle + `Finish` (Red `#EF4444`)
   - **Interrupted / Recoverable State**:
     - Status badge: `RECORDING INTERRUPTED`
     - Preserved session explanation message
     - Action Button: `Finish preserved session`
3. **Last Session Summary Section**:
   - If session summary exists: Two `MetricCard` items side-by-side:
     - `Observation`: Duration `Xh Ym`, coverage `%`
     - `Low motion`: Percentage `X%`, movement episodes observed
   - If empty: `Card` showing `No recorded sessions yet`
4. **Automations Shortcut**:
   - `OutlinedButton`: `Smart Automation Rules` (contentColor = `MoonBlue`)
5. **Snackbar Host**:
   - Displays transient error messages from `state.errorMessage`.

#### Interactions:
- Tap Settings icon -> Opens `AppOverlay.SETTINGS`.
- Tap `Start Recording` -> Requests `ACTIVITY_RECOGNITION` permission if missing, then launches `RecordingService`.
- Tap `Pause` -> Sends pause intent to `RecordingService`.
- Tap `Resume` -> Sends resume intent to `RecordingService`.
- Tap `Finish` -> Sends stop intent to `RecordingService`, computes summaries, updates `lastSummary`.
- Tap `Smart Automation Rules` -> Opens `AppOverlay.AUTOMATIONS`.

#### UX, Visual & Accessibility Findings:
- **UX Issue**: No UI selector for `selectedPlacement` (Mattress vs Nightstand vs Wrist) or `selectedSourceMode` (Phone only vs Wearable), even though `TodayViewModel` supports both. User cannot choose device placement before starting.
- **Visual Issue**: `Smart Automation Rules` outlined button uses `contentColor = MoonBlue` (`#6366F1`) on dark background (`#0A0E17`), resulting in poor contrast (~3.5:1).
- **Visual Issue**: `Start Recording` button has dark text (`DarkSurface` `#111827`) on `MoonBlue` (`#6366F1`), yielding 3.7:1 contrast ratio (failing WCAG AA normal text requirement).
- **Accessibility**: Missing content descriptions on timer digits; screen readers announce `00:00:04` as raw text rather than "4 seconds elapsed".

---

### Screen 02: Session History & Details
- **Location**: `app/src/main/java/com/example/sleeptrackersimulator/ui/history/HistoryScreen.kt`
- **ViewModel**: `HistoryViewModel.kt`
- **State Model**: `HistoryUiState` (`sessions`, `provenanceBySessionId`, `selectedFilter`, `selectedSessionDetails`, `isLoading`), `SessionDetailsUiState`
- **User Goal**: Browse past recording sessions, filter between real and simulated/demo data, view session breakdown, and inspect movement timelines.

#### Component Structure (List View):
1. **Header**: `Session History` headline.
2. **Filter Chips**:
   - `All (count)` (`HistoryFilter.ALL`)
   - `Real Only` (`HistoryFilter.REAL_ONLY`)
   - `Demo` (`HistoryFilter.DEMO_ONLY`)
3. **Session List** (`LazyColumn`):
   - `SessionListItem` cards:
     - Date & time formatted (`EEE, d MMM yyyy · HH:mm`)
     - `ProvenanceChip` badge (`PHONE` green, `SIMULATED` red, `REPLAY` amber)
     - Duration (`Xh Ym`)
     - Coverage percentage

#### Component Structure (Details View — `SessionDetailsView`):
1. **Header Row**:
   - Back arrow button (`Icons.AutoMirrored.Rounded.ArrowBack`)
   - Title: `Session Details`
   - Delete trash button (`Icons.Rounded.Delete`)
2. **Metadata**:
   - Session date/time headline
   - Badges: Session status (`COMPLETED`), device placement label (`Mattress / Near Pillow`), `ProvenanceChip`
3. **Metric Cards**:
   - `Observation`: `Xh Ym`, Valid coverage `%`
   - `Low motion`: `X%`, movement episodes count
4. **Movement Activity Timeline**:
   - Segmented color strip (`HorizontalActivityTimeline`):
     - Green (`CalmGreen`) = Low motion (REST)
     - Amber (`MotionAmber`) = Movement (RESTLESS)
     - Indigo (`MoonBlue`) = Active (ACTIVE)
     - Gray = Data gap
   - Legend row
5. **Scientific Disclaimer Card**:
   - Notice that metrics are smartphone accelerometry estimates, not polysomnography (PSG).

#### Interactions:
- Tap Filter Chip -> Switches filter in `HistoryViewModel`.
- Tap Session Card -> Loads session details from Room database, transitions to `SessionDetailsView`.
- Tap Back Arrow in Details -> Clears `selectedSessionDetails`, returns to list.
- Tap Trash Icon in Details -> Immediately calls `deleteSession()` with zero confirmation!

#### UX, Visual & Accessibility Findings:
- **Critical Navigation Bug (P1)**: `SessionDetailsView` lacks `BackHandler`. Pressing Android system back gesture exits the entire application rather than closing the details view!
- **Critical Safety Bug (P1)**: Tapping the delete icon immediately executes permanent CASCADE deletion in Room with NO confirmation dialog.
- **UI Inconsistency**: `SessionDetailsView` contains its own `Scaffold` nested inside the `MainActivity` root `Scaffold`, keeping the main bottom navigation bar visible over a detail screen.
- **Accessibility**: Timeline canvas has no semantic description. Screen reader users cannot discern how much time was spent in low motion vs active motion.

---

### Screen 03: Personal Insights
- **Location**: `app/src/main/java/com/example/sleeptrackersimulator/ui/insights/InsightsScreen.kt`
- **ViewModel**: `InsightsViewModel.kt`
- **State Model**: `InsightsUiState` (`summaries`, `selectedMode`, `realMetrics`, `demoMetrics`, `comparableSessionCount`, `baseline`, `latestComparison`, `isQualityGateSatisfied`, `isLoading`, `errorMessage`)
- **User Goal**: Understand longitudinal trends, view personal baseline comparisons, and analyze movement patterns.

#### Component Structure:
1. **Header**: `Personal Insights` headline.
2. **Subtitle**: `Full retained history · N completed session(s)`.
3. **Mode Filters**: Filter chips for `Real` vs `Demo (N)`.
4. **Metric Cards Grid**:
   - `Avg Duration`: Hours & minutes, session count
   - `Avg Low-Motion`: Percentage, `Descriptive only` tag
   - `Movement`: Average episodes/night
   - `Coverage`: Percentage, average heart rate (if available)
5. **Nightly Low-Motion Trend**:
   - Raw Canvas line drawing (`points.forEach drawLine`).
6. **Quality Gate Notice** (shown when real sessions < 3):
   - Warning banner: `Personal baseline in progress. You currently have X comparable session(s). A reliable baseline requires at least 3-5 consistent observations.`
7. **Baseline Comparison Card** (shown when quality gate satisfied):
   - Baseline difference percentage points (`+X.X pp`)
   - Normal variation range assessment.

#### Interactions:
- Tap `Real` / `Demo` chip -> Toggles metric dataset in `InsightsViewModel`.

#### UX, Visual & Accessibility Findings:
- **Visual Issue**: The trend chart is a bare 96dp Canvas with a single thin blue polyline (`strokeWidth = 3f`). It has no axes, no grid, no min/max markers, no date labels, no point indicators, and no touch exploration.
- **Accessibility Issue**: Chart has no accessibility semantics. TalkBack ignores it entirely.
- **UX Issue**: When switched to `Real` with 0 sessions, an empty state card appears, but does not provide a call-to-action button to navigate to `TodayScreen` to record.

---

### Screen 04: Connected Sources / Devices
- **Location**: `app/src/main/java/com/example/sleeptrackersimulator/ui/devices/DevicesScreen.kt`
- **ViewModel**: `DevicesViewModel.kt`
- **State Model**: `DevicesUiState` (`phoneSensorAvailable`, `mode`, `connectionState`, `heartRate`, `lastPacketUtcMillis`, `connectedDevice`, `discoveredDevices`, `errorMessage`)
- **User Goal**: Inspect internal phone sensor status, configure Bluetooth wearable connection (Real BLE vs Demo Mock), scan for devices, and observe heart rate telemetry.

#### Component Structure:
1. **Header**: `Connected Sources` headline.
2. **Section 1: THIS PHONE**:
   - Card showing `Android Accelerometer`, `3-axis continuous hardware · 20 Hz`, and `PHONE` provenance chip.
3. **Section 2: BLUETOOTH WEARABLES**:
   - Mode toggles: `REAL BLE` vs `DEMO` buttons.
   - Connection state label: `State: DISCONNECTED` / `SCANNING` / `CONNECTED`.
   - Action Button: `Search for devices`.
   - Discovered devices list (each device rendered as an OutlinedButton).
   - Wearable details card:
     - Device display name (`Virtual Sleep Band` or discovered name)
     - Service info: `GATT Service 0x180D · 0x2A37`
     - Live Heart Rate indicator: Heart icon + `XX BPM` (Red `#EF4444`)
     - Action button: `Scan` (when disconnected) or `Disconnect` (when connected).
     - Informational footer explaining the `VirtualSleepBand` companion package.
4. **Section 3: Data Integrity Policy**:
   - Explanation card stating real phone measurements take priority and simulated signals are strictly isolated from baselines.

#### Interactions:
- Tap `REAL BLE` / `DEMO` -> Switches BLE transport mode.
- Tap `Search for devices` / `Scan` -> Requests BLE permissions (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`) and starts scan.
- Tap Discovered Device -> Initiates GATT connection.
- Tap `Disconnect` -> Disconnects active BLE device.

#### UX, Visual & Accessibility Findings:
- **Redundant Controls**: Two different scan buttons on screen (`Search for devices` and `Scan` inside the card).
- **Developer-Centric UI**: Raw state strings like `State: DISCONNECTED`, `REAL BLE ✓`, `Virtual Sleep Band · SIMULATED · -45 dBm` displayed without friendly user-facing formatting.
- **Visual Clutter**: Buttons stacked directly below text labels with inconsistent margins (8dp, 14dp, 24dp).

---

### Screen 05: Automations & Rules (High-Priority Case Study)
- **Location**: `app/src/main/java/com/example/sleeptrackersimulator/ui/smart/SmartScenariosScreen.kt` & `MainActivity.kt` lines 293-328
- **ViewModel**: `SmartScenariosViewModel.kt`
- **State Model**: `SmartScenariosUiState` (`rules`, `recentExecutions`, `visibleAlert`, `lastError`)
- **User Goal**: Define, edit, enable/disable, and monitor autonomous rules that evaluate real-time sensor motion or ML activity states to trigger physical actuators or BLE alerts.

#### Component Structure (Main Screen):
1. **Top Bar** (in `MainActivity.kt`):
   - Back icon button + Title: `Automations & Rules` (MistBlue).
2. **Screen Content** (`SmartScenariosScreen`):
   - Header text: `Smart Scenarios` (Default content color -> Black!)
   - Subtitle text: `Persistent rules evaluate real accelerometer and TFLite state.` (Black!)
   - Primary Action: `Button(onClick = { creating = true }) { Text("Add rule") }`
   - Rules List (`Column`):
     - `Card` (RoundedCornerShape 18dp):
       - Title: `rule.name`
       - Description: `ruleDescription(rule)` (e.g., `ACCELEROMETER_X > 10.0 m/s² → VIBRATION_PULSE`)
       - Switch: `Switch(checked = rule.enabled, onCheckedChange = ...)`
       - Last execution summary: `Last: SUCCESS · ...`
       - Action buttons: `Edit` (`OutlinedButton`), `Delete` (`TextButton`)
   - Empty state: Raw text `No saved rules` (Black!)

#### Component Structure (Rule Editor Dialog — `RuleEditor`):
- `AlertDialog`:
  - Title: `Add Smart Scenario` or `Edit Smart Scenario`
  - Text fields & cycle buttons:
    - `OutlinedTextField` for `Name`
    - `CycleButton` for `Source`: Cycles through `ACCELEROMETER_X`, `ACCELEROMETER_Y`, `ACCELEROMETER_Z`, `MOVEMENT_INTENSITY`, `ML_CLASS`.
    - If `ML_CLASS`: `CycleButton` for `Class` (cycles `REST`, `RESTLESS`, `ACTIVE`). Operator locked to `=`.
    - If Numeric: `CycleButton` for `Operator` (cycles `>`, `>=`, `<`, `<=`). `OutlinedTextField` for `Threshold m/s²`.
    - `CycleButton` for `Action`: Cycles through `VIBRATION_PULSE`, `MOCK_BLE_ALERT`, `VISIBLE_ALERT`, `CRITICAL_ALERT`.
    - `OutlinedTextField` for `Consecutive matches`
    - `OutlinedTextField` for `Cooldown ms`
  - Action buttons: `Cancel` (`TextButton`), `Save` (`Button`).

#### Interactions:
- Tap Back Arrow -> Sets `activeOverlay = AppOverlay.NONE`.
- Tap `Add rule` -> Opens `RuleEditor` dialog.
- Tap Rule Switch -> Toggles rule enabled/disabled state in Room database.
- Tap `Edit` -> Opens `RuleEditor` pre-populated with existing rule.
- Tap `Delete` -> Immediately deletes rule without confirmation.
- In Editor: Tap Cycle buttons to rotate enum choices; input text into text fields; tap `Save` to validate and commit.

#### UX, Visual & Accessibility Findings:
- **SEVERITY P0 — UNREADABLE CONTRAST DEFECT**: Headings, descriptions, and empty state text render as `Color.Black` against `#0A0E17` dark background because the screen is hosted inside a bare `Column` with `.background(NightBlue)` that does not provide `LocalContentColor`.
- **SEVERITY P0 — STATUS BAR CLIPPING**: The top bar has no window insets or status bar padding, causing the back arrow and title to collide directly with the Android clock, battery, and wifi icons.
- **SEVERITY P1 — BIZARRE CYCLE BUTTON INTERACTION**: Trigger source, operators, and actions are configured by repeatedly clicking an OutlinedButton to cycle through an enum loop! No dropdown menu, bottom sheet, or radio group is provided.
- **SEVERITY P1 — ACCIDENTAL DATA LOSS**: Delete rule button removes rule instantly without confirmation.
- **SEVERITY P2 — LOW CONTRAST BUTTONS**: `Delete` text button uses `MoonBlue` on `#1F2937` (contrast 3.5:1, failing WCAG AA). `Cancel` button in dialog has similar low contrast.
- **SEVERITY P2 — UNINFORMATIVE EMPTY STATE**: Bare text `No saved rules` provides no explanation, no guidance on triggers/actions, and no onboarding.

---

### Screen 06: Settings & Privacy
- **Location**: `app/src/main/java/com/example/sleeptrackersimulator/ui/settings/SettingsScreen.kt`
- **User Goal**: Manage privacy and cloud synchronization options, export Room data to CSV, perform full local data reset, and navigate to advanced developer tools.

#### Component Structure:
1. **Header Row**: Back button + `Settings & Privacy` title.
2. **Section 1: PRIVACY & SYNC**:
   - Card showing `Firebase Cloud Sync (Opt-in)`, explanation of build configuration, and `BUILD CONFIG` status badge.
3. **Section 2: DATA MANAGEMENT**:
   - Action row: `Open Data Export Tools` (navigates to Developer Lab Room tab).
   - Action row: `Delete All Local Data` (Red icon and text `#EF4444`).
4. **Delete All Confirmation Dialog**:
   - Material 3 `AlertDialog` confirming deletion of all sessions, feature windows, and personal baselines.
5. **Section 3: ADVANCED / PORTFOLIO**:
   - Card explaining Labs 1–7 diagnostics.
   - Primary button: `Open Developer Lab` (`CalmGreen` container).
6. **Footer**: Version name and code (`BuildConfig.VERSION_NAME`).

#### Interactions:
- Tap Back Arrow -> Returns to main screen.
- Tap `Open Data Export Tools` -> Opens `AppOverlay.DEVELOPER_LAB`.
- Tap `Delete All Local Data` -> Shows confirmation dialog. Confirming executes `container.sessionRepository.clearAllLocalData()`.
- Tap `Open Developer Lab` -> Opens `AppOverlay.DEVELOPER_LAB`.

#### UX, Visual & Accessibility Findings:
- **Visual Issue**: Status bar collision on top header (no insets padding).
- **UX Issue**: `Open Data Export Tools` does not directly export data or show an export dialog; instead it bounces the user into the Developer Lab.
- **Good Pattern**: Proper `AlertDialog` with clear warning and explicit confirmation before deleting all local data.

---

### Screen 07: Developer Lab (Diagnostic Console for Labs 1–7)
- **Location**: `app/src/main/java/com/example/sleeptrackersimulator/ui/developer/DeveloperLabScreen.kt`
- **Sub-Views / Tabs**: 7 Scrollable tabs representing University Labs 1 to 7:
  1. `Sensors (L1)`: Requested Hz (20), hardware active state, sensor event contract.
  2. `BLE (L2)`: GATT status, Service UUID `0x180D`, dual-role warning, send alert command, start/stop in-app loopback peripheral advertiser.
  3. `Cloud (L3)`: Firebase outbox pending count, cloud opt-in status, Firestore security rules guarantee.
  4. `Actuators (L4)`: Cooldown guard, safety policy, manual vibration pulse, persistent vibration toggle, flashlight toggle, emergency OFF button, automatic control switch, diagnostics telemetry.
  5. `Room (L5)`: Database version (v7), migration path (1->7 PASS), 90-night synthetic demo dataset controls (`Generate`, `Import`, `Verify`, `Export CSV`, `Reset demo`), relational tables list.
  6. `ML (L6)`: TFLite input tensor `[1, 20, 3]`, 3 classes (`REST`, `RESTLESS`, `ACTIVE`), live ground-truth dataset recorder with Experiment protocol, Placement, and Ground Truth activity chips, live Hz telemetry, CSV exporter document launcher, medical disclaimer, and model registry specs.
  7. `Pipeline Trace (L7)`: End-to-end 8-stage pipeline visualization (`INGESTION` -> `VALIDATION` -> `WINDOWING` -> `FEATURES` -> `INFERENCE` -> `SMART RULES` -> `PERSISTENCE` -> `SYNC`).
- **Additional Control**: `Lab View` button in header opening `AppOverlay.LEGACY_DASHBOARD`.

#### UX, Visual & Accessibility Findings:
- **Visual Defect (P0)**: Top bar overlaps system status bar; `Lab View` button is positioned right under the battery/wifi icons.
- **Visual Defect (P1)**: In ML tab, FilterChips have small padding and wrap text awkwardly (e.g., `STATIC_` on line 1, `BENCH` on line 2).
- **Visual Defect (P2)**: ScrollableTabRow has high density and tabs require horizontal scrolling.
- **Engineering Value**: All 7 lab subsystems are fully functional and verifiable. Preserving this console without breaking underlying bindings is critical.

---

### Screen 08: Legacy Dashboard / Lab View (All 7 Subsystems)
- **Location**: `app/src/main/java/com/example/sleeptrackersimulator/ui/dashboard/SensorScreen.kt`
- **ViewModel Dependencies**: `SensorViewModel`, `BleViewModel`, `RestStateViewModel`, `MlViewModel`, `CloudSyncViewModel`, `ActuatorViewModel`, `AnalyticsViewModel`.
- **User Goal**: University demonstration view presenting all 7 laboratory subsystems concurrently on a single scrolling screen.
- **States**: `DisconnectedExperience` vs `ConnectedDashboard`.
- **Key Components**:
  - Live animated band illustration.
  - Heart rate hero (`BPM`).
  - Accelerometer live 3-axis dynamic graph (`Canvas`).
  - AI Insights confidence display (`REST`, `RESTLESS`, `ACTIVE`).
  - Cloud sync status card (`SYNCED`, `PENDING`, `ERROR`).
  - Actuators hardware panel (automatic alert switch, persistent vibration, camera flashlight, remote Firebase status).
  - Diagnostics modal bottom sheet with GATT details and raw BLE terminal log.

#### UX, Visual & Accessibility Findings:
- **Visual Defect (P0)**: Back row overlaps status bar.
- **UX Issue**: Screen combines user wellness visuals with deep developer debug switches.

---

### Screen 09: VirtualSleepBand Simulator Companion App
- **Location**: `virtualsleepband/src/main/java/com/example/virtualsleepband/ui/VirtualBandScreen.kt`
- **Module**: `:virtualsleepband`
- **User Goal**: Simulate an external BLE smart wristband on a second device or emulator, advertising GATT Heart Rate (`0x180D`) and Battery (`0x180F`) services.
- **Key Components**:
  1. `TopAppBar`: Title + version info.
  2. `SimulationScenario` selector chips: Normal Sleep, Deep Sleep, Restless, Arrhythmia, Low Battery, Rapid Tachycardia.
  3. `Advertising Status Card`: Start/Stop advertising button, connected centrals counter.
  4. `Heart Rate Simulation Card`: Pulse display (`68 BPM`), packet interval selector chips (`250ms`, `500ms`, `1000ms`, `2000ms`).
  5. `Battery Service Card`: Simulated battery level percentage (`88%`).
  6. `Received Commands Log Card`: Displays GATT write commands received from SleepSense (e.g. `0x2A38`).
  7. `Dual-Role BLE Hardware Note`: Instructions for physical multi-device testing.

#### UX, Visual & Accessibility Findings:
- **Visual Quality**: Well-structured card hierarchy.
- **Visual Issue**: TopAppBar does not use standard Material 3 colorScheme inheritance; colors are defined in custom local constants (`DarkBg`, `CardBg`, `VioletAccent`).

---

## 4. UI Inventory Summary Table

| Screen ID | Screen Name | Kotlin File Location | Primary ViewModel | Complexity | Contrast Defects | Insets Bugs | Destructive Action Risks |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **SCR-01** | Today / Dashboard | `ui/today/TodayScreen.kt` | `TodayViewModel` | High | High (buttons) | No | None |
| **SCR-02** | Session History | `ui/history/HistoryScreen.kt` | `HistoryViewModel` | Medium | Low | No | Delete session (No confirmation) |
| **SCR-03** | Session Details | `ui/history/HistoryScreen.kt` | `HistoryViewModel` | Medium | Medium (disclaimer) | Partial (Nested Scaffold) | Delete session (No confirmation) |
| **SCR-04** | Personal Insights | `ui/insights/InsightsScreen.kt` | `InsightsViewModel` | Medium | Medium (chart) | No | None |
| **SCR-05** | Connected Sources / Devices | `ui/devices/DevicesScreen.kt` | `DevicesViewModel` | Medium | Medium (buttons) | No | None |
| **SCR-06** | Automations & Rules | `ui/smart/SmartScenariosScreen.kt` | `SmartScenariosViewModel` | High | **Critical (P0 Black text)** | **Critical (P0 Status bar)** | Delete rule (No confirmation) |
| **SCR-07** | Smart Rule Editor Dialog | `ui/smart/SmartScenariosScreen.kt` | `SmartScenariosViewModel` | High | High (buttons) | N/A (Dialog) | None |
| **SCR-08** | Settings & Privacy | `ui/settings/SettingsScreen.kt` | `SessionRepository` | Medium | Low | **Critical (P0 Status bar)** | Delete all data (Has dialog) |
| **SCR-09** | Developer Lab (L1–L7) | `ui/developer/DeveloperLabScreen.kt` | Multi-ViewModel | Very High | Medium (chips) | **Critical (P0 Status bar)** | Reset demo dataset |
| **SCR-10** | Legacy Dashboard / Lab View | `ui/dashboard/SensorScreen.kt` | Multi-ViewModel | Very High | Low | **Critical (P0 Status bar)** | Emergency actuator stop |
| **SCR-11** | VirtualSleepBand Companion | `virtualsleepband/.../VirtualBandScreen.kt`| `VirtualBandService` | Medium | Low | Low | None |
