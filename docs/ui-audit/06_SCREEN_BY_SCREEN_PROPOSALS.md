# SleepSense 2.0 — Screen-by-Screen Redesign Proposals

This document details the concrete UX/UI redesign proposals for all primary screens and diagnostic consoles in SleepSense 2.0.

---

## 1. Today / Dashboard (`TodayScreen.kt`)

### Current State & Observed Deficiencies
- The main recording card is clear, but lacks controls to select device placement (Mattress vs Nightstand vs Body) or source mode (Phone only vs Wearable) prior to recording, despite `TodayViewModel` supporting both.
- The `Smart Automation Rules` shortcut is an outlined button with very dark text (`#6366F1` on `#0A0E17`), making it hard to see and easy to miss.
- The `Start Recording` button has poor text contrast (`#111827` on `#6366F1`, failing WCAG AA).
- When a session is active, the red `Finish` button can be tapped accidentally, terminating the recording without confirmation.

### Proposed Redesign
```
+-------------------------------------------------------------+
| WEDNESDAY, 28 OCTOBER                                  (⚙)  |
| SleepSense                                                  |
+-------------------------------------------------------------+
| [ HERO RECORDING CARD ]                                     |
| [ READY TO RECORD ]                    [ PHONE ACCELEROMETER]
|                                                             |
| Tonight's Observation                                       |
| Monitor bedtime movement patterns with on-device machine    |
| learning and review your activity timeline in the morning.  |
|                                                             |
| Device Placement:                                           |
| (•) Mattress / Near Pillow   ( ) Nightstand   ( ) On Body   |
|                                                             |
| Data Source:                                                |
| [ Phone Sensor: Ready (20 Hz) ] [ Wearable: None Connected ]|
|                                                             |
| [ ▶ START OVERNIGHT RECORDING ]                             |
+-------------------------------------------------------------+
| Last Session Summary                                        |
| +---------------------------+ +---------------------------+ |
| | Observation Duration      | | Low-Motion Restfulness    | |
| | 7h 15m                    | | 82%                       | |
| | Data coverage: 99%        | | 3 movement episodes       | |
| +---------------------------+ +---------------------------+ |
+-------------------------------------------------------------+
| Smart Automation Scenarios                            [ > ] |
| 1 active rule: "Restless movement pulse"                    |
+-------------------------------------------------------------+
```

### Key Improvements:
1. **Interactive Placement Selector**: Exposes `DevicePlacement` chips directly in the hero card so the user can declare whether the phone is resting on the mattress, nightstand, or worn.
2. **Accessible Primary CTA**: Large `54dp` button with high-contrast text (`#0B0F19` on `#818CF8`, contrast 8.2:1).
3. **Active Recording Safety**: During recording, the `Finish` button requires a 1.5-second hold-to-confirm interaction (with circular fill animation) to prevent accidental stoppage in the morning.
4. **Automations Summary Card**: Replaces the low-contrast outlined button with an informative card displaying how many rules are active and the latest execution status.

---

## 2. Active Recording Experience (`TodayScreen.kt` In-Progress State)

### Current State & Observed Deficiencies
- Active recording displays timer, pause button, and finish button.
- Lacks a live signal quality indicator to reassure the user that the phone's accelerometer is receiving valid movement data.
- Lacks a dimmed "Bedside Mode" to save battery and avoid disturbing the bedroom with bright screen light.

### Proposed Redesign
```
+-------------------------------------------------------------+
| [ RECORDING IN PROGRESS ]             [ PHONE ACCELEROMETER ]
|                                                             |
|   06 : 42 : 19                                              |
|                                                             |
| Signal Quality: Optimal (20 Hz) · Device Stable on Mattress |
| Live Classification: REST (94% confidence)                  |
|                                                             |
|  [ ❚❚ Pause Session ]          [ ■ Hold to Finish (1.5s) ]  |
|                                                             |
| [ 🌙 Enable Dimmed Bedside Display ]                        |
+-------------------------------------------------------------+
```

### Key Improvements:
1. **Live Signal Quality Telemetry**: Reassures the user that the sensor is capturing data cleanly (validating finite non-NaN numbers and expected gravity vector).
2. **Current Activity State Feedback**: Shows live on-device inference state without clinical claims.
3. **Hold-to-Stop Mechanism**: The finish button requires a sustained press to stop, protecting hours of recorded sleep.
4. **Bedside Dim Mode**: A low-luminance black screen showing only a faint clock that wakes on tap.

---

## 3. Connected Sources & Devices (`DevicesScreen.kt`)

### Current State & Observed Deficiencies
- Fragmented controls: Mode toggle (`REAL BLE` vs `DEMO`), duplicate scan buttons, and unformatted technical state strings (`State: CONNECTED`).
- Discovered devices are shown as raw outlined buttons with developer-style strings.
- Ambiguity regarding how the in-app loopback advertiser interacts with the external `VirtualSleepBand` app.

### Proposed Redesign
```
+-------------------------------------------------------------+
| Connected Sources & Wearables                               |
| Manage phone sensors, smart bands, and simulated devices.   |
+-------------------------------------------------------------+
| INTERNAL PHONE SENSORS                                      |
| +---------------------------------------------------------+ |
| | [Phone Icon] Android Accelerometer             [ACTIVE] | |
| | Hardware 3-axis motion sensor · Sampling rate: 20 Hz     | |
| +---------------------------------------------------------+ |
|                                                             |
| BLUETOOTH WEARABLES                                         |
| Mode: [ (•) Physical BLE Hardware ] [ ( ) Simulated Mock ]  |
|                                                             |
| +---------------------------------------------------------+ |
| | [Watch Icon] Virtual Sleep Band          [CONNECTED]    | |
| | GATT Service: Heart Rate (0x180D)                       | |
| | Live Pulse: ♥ 68 BPM (Simulated Waveform)               | |
| | RSSI: -48 dBm · Transmission: 1000ms                    | |
| |                                                         | |
| | [ Disconnect Band ]               [ Test Alert Command ]| |
| +---------------------------------------------------------+ |
|                                                             |
| Available Devices                                           |
| +---------------------------------------------------------+ |
| | SleepBand_Lab2 (0x180D) · -62 dBm             [Connect] | |
| +---------------------------------------------------------+ |
| [ 🔍 Scan for Nearby Bluetooth Devices ]                    |
|                                                             |
| [🛡 Data Integrity Assurance]                                |
| Real phone motion is always isolated from simulated HR     |
| packets. Simulated signals never contaminate baselines.     |
+-------------------------------------------------------------+
```

### Key Improvements:
1. **Clear Mode Segmentation**: Physical BLE hardware scanning vs Simulated mock testing are explicitly separated at the top.
2. **Unified Device Cards**: Discovered devices appear as clean list cards with signal strength bars and an explicit `Connect` button.
3. **Live Heart Rate Visualizer**: When connected, heart rate pulses gently with clear provenance tag (`SIMULATED WAVEFORM`).
4. **Single Primary Scan Action**: Removed redundant scan buttons in favor of one clear action button with spinning indicator during active scanning.

---

## 4. Session History & Details (`HistoryScreen.kt`)

### Current State & Observed Deficiencies
- **Severe Bug**: In `SessionDetailsView`, pressing Android back exits the entire app because there is no `BackHandler`.
- **Bottom Navigation Leak**: The bottom navigation bar remains visible while inspecting a single session.
- **Accidental Deletion**: Tapping the trash icon in the header deletes the session permanently from Room with no warning.
- **Inaccessible Timeline**: The color-coded activity timeline canvas has no text summary or screen reader semantics.

### Proposed Redesign (Details View)
```
+-------------------------------------------------------------+
| [<-]  Session Details                                  [🗑]  |
+-------------------------------------------------------------+
| Tuesday, 27 October 2026 · 22:45 to 06:12                   |
| [ COMPLETED ]  [ MATTRESS / PILLOW ]  [ PHONE (REAL SENSOR)] |
+-------------------------------------------------------------+
| METRICS OVERVIEW                                            |
| +---------------------------+ +---------------------------+ |
| | Total Observation         | | Low-Motion Restfulness    | |
| | 7h 27m                    | | 84%                       | |
| | Data Coverage: 98%        | | 2 movement episodes       | |
| +---------------------------+ +---------------------------+ |
+-------------------------------------------------------------+
| MOVEMENT ACTIVITY TIMELINE                                  |
| [█████████████████████▒▒▒▒█████████████████████████████████] |
| • Low motion (REST): 6h 15m (84%)                           |
| • Moderate movement (RESTLESS): 52m (12%)                   |
| • Active movement (ACTIVE): 20m (4%)                        |
+-------------------------------------------------------------+
| ℹ Data Interpretation Notice                                |
| These metrics describe observed device movement and rest    |
| patterns from smartphone accelerometry. They do not         |
| constitute medical polysomnography (PSG) or sleep staging.  |
+-------------------------------------------------------------+
```

### Key Improvements:
1. **Back Gesture Handling**: Added `BackHandler { onCloseDetails() }` to safely return to the session list.
2. **Full-Screen Drilldown**: Hides the bottom navigation bar while viewing details to maximize readability.
3. **Delete Confirmation Dialog**: Tapping the trash icon opens an `AlertDialog` confirming permanent deletion.
4. **Detailed Timeline Breakdown**: In addition to the visual color bar, displays exact time and percentage spent in each classification category, complete with TalkBack accessibility semantics.

---

## 5. Personal Insights & Trends (`InsightsScreen.kt`)

### Current State & Observed Deficiencies
- The nightly low-motion trend chart is a bare, unlabeled polyline drawn on a 96dp Canvas with no axes, dates, or touch interactions.
- When `Real` mode has 0 sessions, an empty card appears with no guidance on how to populate it.
- Lack of clarity on how personal baselines are established.

### Proposed Redesign
```
+-------------------------------------------------------------+
| Personal Insights & Baselines                               |
| Long-term restfulness trends and individual baselines.      |
+-------------------------------------------------------------+
| Dataset Mode:  [ (•) Real Data (3) ]  [ ( ) Demo History (90) ]
|                                                             |
| 30-Day Averages                                             |
| +---------------------------+ +---------------------------+ |
| | Avg Observation           | | Avg Low-Motion            | |
| | 7h 38m                    | | 83% (Baseline: 80%)       | |
| +---------------------------+ +---------------------------+ |
| +---------------------------+ +---------------------------+ |
| | Movement Episodes         | | Sensor Data Coverage      | |
| | 3.2 / night               | | 97%                       | |
| +---------------------------+ +---------------------------+ |
+-------------------------------------------------------------+
| NIGHTLY RESTFULNESS TREND                                   |
| 100% |       •---•                                          |
|  80% |   •---     •---•---•                                 |
|  60% |                                                      |
|   0% +----------------------------------                    |
|       Oct 22  Oct 24  Oct 26  Oct 28                        |
|   Average: 83% Low Motion · Touch a point for details       |
+-------------------------------------------------------------+
| COMPARISON WITH YOUR PERSONAL BASELINE                      |
| +---------------------------------------------------------+ |
| | [TrendingUp] Restfulness is consistent with baseline    | |
| | Your recent 83% low-motion average is +3.0 pp compared  | |
| | to your established 80% baseline. Within normal bounds. | |
| +---------------------------------------------------------+ |
+-------------------------------------------------------------+
```

### Key Improvements:
1. **Interactive, Labeled Trend Chart**: Implemented with Vico or structured Canvas featuring labeled axes (dates on X, percentages on Y), subtle grid lines, and interactive point selection.
2. **Clear Baseline Progress Bar**: When fewer than 3 real sessions exist, display a visual step indicator (`Step 2 of 3 sessions recorded`) to encourage completion.
3. **Accessible Data Summary**: The chart provides a full textual description for screen readers.

---

## 6. Machine Learning / Laboratory 6 (`DeveloperLabScreen.kt` ML Tab)

### Current State & Observed Deficiencies
- Chips for experiment protocol and device placement have small touch targets and wrap text awkwardly (e.g., `STATIC_` / `BENCH`).
- Medical disclaimer is a dense text card at the bottom.
- Live Hz telemetry is buried under multiple button rows.

### Proposed Redesign
```
+-------------------------------------------------------------+
| TensorFlow Lite On-Device Classifier (Lab 6)                |
+-------------------------------------------------------------+
| MODEL ARCHITECTURE & TENSOR CONTRACT                        |
| • Model: activity_classifier.tflite (Float32, seed 4319)    |
| • Input: [1, 20, 3] (20 samples x 3 axes = 1-sec window)   |
| • Classes: REST, RESTLESS, ACTIVE                           |
| • Golden-vector verification: VERIFIED                      |
+-------------------------------------------------------------+
| LIVE CLASSIFICATION MONITOR                                 |
| Window Class: [ REST (92.4%) ]   Latency: 0.38 ms           |
| Probabilities:                                              |
| REST      [██████████████████████████████] 92.4%            |
| RESTLESS  [██                              ]  6.1%          |
| ACTIVE    [█                               ]  1.5%          |
+-------------------------------------------------------------+
| GROUND-TRUTH DATASET COLLECTION                             |
| Protocol:  [ Controlled Rest ] [ Reading in Bed ] [ Static ]|
| Placement: [ Mattress ] [ Nightstand ] [ Wrist ]            |
| Label:     [ RESTING ] [ READING ] [ OUT OF BED ]           |
|                                                             |
| Telemetry: 1,420 valid samples · 0 rejected · Rate: 20.1 Hz |
|                                                             |
| [ ■ Stop Recording ]                  [ 📄 Export CSV File ] |
+-------------------------------------------------------------+
| ⚠ Clinical Validation Notice: Not a PSG diagnostic tool.     |
+-------------------------------------------------------------+
```

### Key Improvements:
1. **Live Probability Bars**: Replaces the single text prediction with visual confidence bars for all 3 classes (`REST`, `RESTLESS`, `ACTIVE`).
2. **Proper Chip Sizing**: Horizontal scrollable container for experiment and placement chips with minimum `48dp` touch height, eliminating awkward line wrapping.
3. **Latency Benchmarking**: Clearly displays on-device inference duration in milliseconds.

---

## 7. Actuators & Safety Governance / Laboratory 4 (`DeveloperLabScreen.kt` Actuators Tab)

### Current State & Observed Deficiencies
- Manual actuator controls are stacked as an unorganized grid of 6 buttons (`Vibrate Pulse`, `Persistent ON`, `Persistent OFF`, `Flashlight ON`, `Flashlight OFF`, `Emergency OFF`).
- Hard to distinguish logical state from observed hardware response.
- Missing a clear visual link to the 15-minute rate limiter.

### Proposed Redesign
```
+-------------------------------------------------------------+
| Actuator Controls & Safety Governance (Lab 4)               |
+-------------------------------------------------------------+
| [CRITICAL SAFETY BANNER]                                    |
| 🛡 Outcome Policy: SAFE (Unknown on crash)                   |
| ⏱ 15-Minute Cooldown Guard: ACTIVE                         |
| [ 🛑 EMERGENCY SHUTDOWN (ALL OFF) ]                         |
+-------------------------------------------------------------+
| SMARTPHONE HAPTIC VIBRATOR                                  |
| Hardware Status: Available (Amplitude control: True)        |
| Persistent State: OFF                                       |
| [ Test Vibration Pulse ]      [ Toggle Persistent ON/OFF ]  |
+-------------------------------------------------------------+
| REAR CAMERA FLASHLIGHT (TORCH)                              |
| Hardware Status: Available                                  |
| TorchCallback Observed: OFF                                 |
| [ Turn Flashlight ON ]        [ Turn Flashlight OFF ]       |
+-------------------------------------------------------------+
| AUTOMATIC COOLDOWN & REMOTE FIREBASE CONTROL                |
| Automatic Alert on Movement:                       ( ON )   |
| Firebase Remote Control Listener:                  CONNECTED|
| Last Action: VIBRATION_PULSE · Source: MANUAL               |
+-------------------------------------------------------------+
```

### Key Improvements:
1. **Prominent Emergency Shutdown**: High-contrast, full-width red button at the top that immediately disables vibration, flashlight, and automatic triggers.
2. **Hardware Feedback Separation**: Explicitly differentiates *requested state* from *observed hardware confirmation* (`TorchCallback`).
3. **Card-Based Subsystem Grouping**: Groups Vibrator, Torch, and Automatic Governance into clean visual containers.

---

## 8. Automations & Rules (`SmartScenariosScreen.kt`)
*(Fully specified in detail in `docs/ui-audit/04_AUTOMATIONS_RULES_REDESIGN.md`)*.

---

## 9. Developer Lab Unified Hub (`DeveloperLabScreen.kt`)

### Proposed Structure:
- Fix status bar overlap with `TopAppBarDefaults` and `statusBarsPadding()`.
- Modernize tab styling with clear badge counts and smooth scrolling.
- Preserve 100% of the underlying bindings and verification functions for Labs 1 through 7.

---

## 10. VirtualSleepBand Companion (`VirtualBandScreen.kt`)

### Proposed Structure:
- Connect to standard Material 3 dark/light color schemes.
- Add scenario descriptions (e.g. explains what `Arrhythmia` or `Deep Sleep` transmits over GATT).
- Enhance packet transmission interval selector with descriptive frequency labels (`4 Hz`, `2 Hz`, `1 Hz`, `0.5 Hz`).
