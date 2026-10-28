# SleepSense 2.0 — Automations & Rules Redesign Specification

## 1. Executive Summary & Problem Definition

The `Automations & Rules` screen (implementing Laboratory 7: Smart Scenarios) is currently the most problematic visual and interaction interface in the application:
1. **Severe Contrast Failure**: Headings, body text, and empty states render as invisible black text on a near-black background (`#000000` on `#0A0E17`).
2. **System Insets Clipping**: The top app bar directly overlaps the Android system status bar icons (clock, battery, Wi-Fi).
3. **Underdeveloped Empty State**: The screen displays a single raw text string `No saved rules` with no context, guidance, or educational onboarding.
4. **Bizarre "CycleButton" Anti-Pattern**: The creation dialog forces users to repeatedly click an outlined button to cycle blindly through enums (`RuleSource`, `RuleOperator`, `RuleAction`).
5. **Accidental Deletion**: Tapping `Delete` destroys rules immediately without confirmation.
6. **Disjointed Safety Model**: There is no direct link between automated triggers and the actuator safety guards (emergency stop, cooldown limits).

This specification provides a complete, modern Material Design 3 redesign that honors the **exact capabilities of the existing `SmartRuleEngine` without inventing unsupported controls**.

---

## 2. Supported Rule Engine Capabilities Contract

To ensure strict engineering fidelity, every proposed UI element maps directly to the verified backend domain models (`SmartRuleModels.kt` & `SmartRuleEngine.kt`):

| Domain Dimension | Supported Backend Values | Validation Rules & Constraints |
| :--- | :--- | :--- |
| **Rule Sources** | `ACCELEROMETER_X`<br>`ACCELEROMETER_Y`<br>`ACCELEROMETER_Z`<br>`MOVEMENT_INTENSITY`<br>`ML_CLASS` | • If `ML_CLASS`, operator must be `EQUALS`<br>• If Accelerometer, operator must NOT be `EQUALS` |
| **Operators** | `GREATER_THAN` (`>`)<br>`GREATER_OR_EQUAL` (`>=`)<br>`LESS_THAN` (`<`)<br>`LESS_OR_EQUAL` (`<=`)<br>`EQUALS` (`=`) | • `EQUALS` is reserved exclusively for `ML_CLASS`<br>• Threshold must be a finite float for numeric operators |
| **Target Classes** | `"REST"`, `"RESTLESS"`, `"ACTIVE"` | Valid only when source is `ML_CLASS` |
| **Actions** | `VIBRATION_PULSE`<br>`MOCK_BLE_ALERT`<br>`VISIBLE_ALERT`<br>`CRITICAL_ALERT` | • `VIBRATION_PULSE`: Triggers phone haptic pulse<br>• `MOCK_BLE_ALERT`: Sends GATT vendor command<br>• `VISIBLE_ALERT`: Displays in-app dialog<br>• `CRITICAL_ALERT`: Triggers vibration + BLE + Dialog |
| **Consecutive Matches**| Integer `1` to `100` | Requires N sequential windows before triggering |
| **Safety Cooldown** | Long `0` to `3,600,000` ms (0s to 60m) | Minimum elapsed real time between triggers |

---

## 3. Redesigned Screen Architecture

The redesigned screen replaces the raw `Column` with a standard Material 3 `Scaffold` featuring:
- An edge-to-edge compliant `TopAppBar` with back navigation.
- A prominent status & safety banner indicating hardware availability and rate limits.
- A rich educational empty state when `rules.isEmpty()`.
- An expressive, accessible card list when rules exist.
- A multi-step bottom-sheet or guided dialog for rule creation/editing.
- Floating Action Button (FAB) or prominent primary CTA.

```
+-------------------------------------------------------------+
| [<-]  Smart Scenarios & Automations                   [?]   |
+-------------------------------------------------------------+
| [SAFETY BANNER]                                             |
| Active Hardware Guard: 15-min Cooldown Active | Manual Stop |
+-------------------------------------------------------------+
|                                                             |
|  [EMPTY STATE / HERO ILLUSTRATION]                          |
|   🌙 Autonomous Nighttime Monitoring                        |
|                                                             |
|   Automations continuously evaluate live accelerometry      |
|   and on-device TFLite sleep states. When conditions        |
|   match, physical haptics or BLE alerts trigger safely.     |
|                                                             |
|   Available Inputs:                                         |
|    • Movement Intensity (m/s²)  • XYZ Accelerometer         |
|    • ML Sleep State (Rest, Restless, Active)                |
|                                                             |
|   Available Actions:                                        |
|    • Haptic Vibration Pulse     • Bluetooth Band Alert      |
|    • On-Screen Notification     • Critical Multi-Alert      |
|                                                             |
|   [ + CREATE YOUR FIRST SCENARIO ]                          |
|                                                             |
|   Quick-Start Templates:                                    |
|   [Restless Sleep Pulse]  [High Motion Alert]  [Awake Alert]|
|                                                             |
+-------------------------------------------------------------+
```

---

## 4. Empty State Specification

When `state.rules.isEmpty()`, the screen must guide the user with clear mental models:

### 1. Visual Header & Hero
- **Icon / Graphic**: Nighttime icon with dynamic signal rings (`Icons.Rounded.AutoAwesome` in `MoonBlue` container).
- **Headline**: `Autonomous Nighttime Monitoring` (`titleLarge`, `MistBlue`, bold).
- **Body**: `Smart Scenarios let SleepSense react to physical movement and sleep patterns while you rest. The on-device engine evaluates sensor streams locally with strict safety rate limits.`

### 2. Capabilities Grid (What can it do?)
Two clear side-by-side informational panels:
- **Triggers (Inputs)**:
  - `Motion Intensity`: Triggers when overall bed restlessness exceeds a threshold.
  - `TFLite Sleep State`: Triggers when model classifies `RESTLESS` or `ACTIVE`.
  - `Raw XYZ Axes`: Detects specific device tilt or bed orientation shifts.
- **Actions (Outputs)**:
  - `Vibration Pulse`: Gentle physical haptic feedback on the smartphone.
  - `Wearable Alert`: Dispatches an over-the-air alert to your connected smart band.
  - `Visual Alert`: Prompts an on-screen dialog when the phone is unlocked.

### 3. Primary Call-to-Action (CTA)
- Full-width elevated button: `+ Create Your First Scenario` (`containerColor = MoonBlue`, height `52dp`, bold text).

### 4. 1-Tap Scenario Templates (Pre-fills Rule Editor)
Three pre-configured quick-start template cards to eliminate blank-canvas paralysis:
1. **"Restless Sleep Pulse" Template**:
   - Source: `ML_CLASS` = `RESTLESS`
   - Consecutive: `3 windows` (approx. 3 seconds)
   - Action: `VIBRATION_PULSE`
   - Cooldown: `15 seconds`
2. **"Excessive Bed Motion" Template**:
   - Source: `MOVEMENT_INTENSITY` > `2.5 m/s²`
   - Consecutive: `5 windows`
   - Action: `VIBRATION_PULSE`
   - Cooldown: `30 seconds`
3. **"Wake-Up Alert" Template**:
   - Source: `ML_CLASS` = `ACTIVE`
   - Consecutive: `2 windows`
   - Action: `VISIBLE_ALERT`
   - Cooldown: `60 seconds`

---

## 5. Rule Creation Journey Redesign

The current `AlertDialog` with cycling buttons is replaced with a **structured, segmented modal sheet** that groups the 8 engine parameters into 4 logical steps:

```
+-------------------------------------------------------------+
| New Smart Scenario                                      [X] |
+-------------------------------------------------------------+
| STEP 1: NAME                                                |
| [ Restless movement alert                                 ] |
+-------------------------------------------------------------+
| STEP 2: TRIGGER CONDITION                                   |
| Source Type:                                                |
| (•) AI Sleep Classification     ( ) Sensor Movement         |
|                                                             |
| Target Sleep State:                                         |
| [  REST  ]  [ *RESTLESS* ]  [  ACTIVE  ]                    |
+-------------------------------------------------------------+
| STEP 3: ACTION TO PERFORM                                   |
| [ *Vibration Pulse* ] [ Wearable Alert ] [ Screen Alert ]   |
+-------------------------------------------------------------+
| STEP 4: TIMING & SAFETY GUARDS                              |
| Consecutive Confirmations:                                  |
| [ - ]  3 windows (~3 sec)  [ + ]                            |
|                                                             |
| Minimum Safety Cooldown:                                    |
| [  5 sec  ]  [ *15 sec* ]  [  30 sec  ]  [  1 min  ]        |
+-------------------------------------------------------------+
| [ Cancel ]                                [ Save Scenario ] |
+-------------------------------------------------------------+
```

### Step 1: Identification
- `OutlinedTextField` for scenario name with helpful placeholder (e.g. `Bed motion haptic alert`).
- Automatic name generation based on selected trigger and action.

### Step 2: Trigger Source & Condition Selection
- **Segmented Control** between `AI Sleep Classification` and `Phone Accelerometer`.
- **If AI Sleep Classification (`ML_CLASS`)**:
  - Displays a 3-way toggle chip group: `REST`, `RESTLESS`, `ACTIVE`.
  - Operator is locked to `=` (no operator selector needed).
  - Threshold field is automatically hidden.
- **If Phone Accelerometer**:
  - Dropdown menu for specific signal:
    - `Overall Movement Intensity` (`MOVEMENT_INTENSITY`)
    - `X-Axis Lateral Motion` (`ACCELEROMETER_X`)
    - `Y-Axis Longitudinal Motion` (`ACCELEROMETER_Y`)
    - `Z-Axis Vertical Motion` (`ACCELEROMETER_Z`)
  - Operator selector: Segmented button for `>`, `>=`, `<`, `<=`.
  - Numeric input field for threshold with `m/s²` suffix and a helper slider ranging from `0.5` to `15.0 m/s²`.

### Step 3: Action Selection
- Expressive choice cards with distinct icons:
  1. `Vibration Pulse` (`Icons.Rounded.Vibration`): Haptic vibration on this phone.
  2. `Wearable Alert` (`Icons.Rounded.Watch`): Bluetooth GATT alert packet to connected band.
  3. `Screen Alert` (`Icons.Rounded.NotificationsActive`): In-app dialog when unlocked.
  4. `Critical Multi-Alert` (`Icons.Rounded.Warning`): Combines vibration, BLE, and dialog.

### Step 4: Timing & Verification Guards
- **Consecutive Matches (Noise Filter)**:
  - Stepper control (`-` / `+`) ranging from `1` to `10` windows (with direct numeric input up to `100`).
  - Helper text: `Requires N consecutive detection windows before triggering to prevent false positives.`
- **Cooldown Guard (Anti-Spam)**:
  - Quick-select chip row: `5s`, `15s`, `30s`, `1m`, `5m`, plus custom ms input.
  - Helper text: `After triggering, the scenario will sleep for this duration before re-arming.`

---

## 6. Saved Rules Card Specification

When rules exist, they are displayed in a clean vertical list of elevated cards with clear typography and strong contrast:

```
+-------------------------------------------------------------+
| [Icon] Restless sleep pulse                         ( ON )  |
| Trigger: AI State = RESTLESS (3 consecutive windows)        |
| Action: Phone Vibration Pulse (Cooldown: 15s)               |
|                                                             |
| Status: Armed & Ready · Last triggered 14m ago (SUCCESS)    |
|                                                             |
| [ Edit Scenario ]                     [ Delete Scenario ]   |
+-------------------------------------------------------------+
```

### Card Layout Details:
1. **Header Row**:
   - Trigger Icon: Lavender brain icon for `ML_CLASS`, Emerald sensor icon for `ACCELEROMETER`.
   - Rule Name: `MaterialTheme.typography.titleMedium`, `MistBlue`, bold.
   - Master Toggle: Material 3 `Switch` with accessible semantics (`rule.enabled`).
2. **Rule Description & Syntax**:
   - Plain English summary formatted with high-contrast labels:
     - `Trigger:` `TFLite Model detects RESTLESS for 3 consecutive windows`
     - `Action:` `Phone Vibration Pulse`
     - `Guard:` `15s cooldown`
3. **Execution Status Footer**:
   - Queries `state.recentExecutions.firstOrNull { it.ruleId == rule.id }`:
     - If triggered successfully: Green check badge + `Last executed: SUCCESS (10:42 PM)`.
     - If failed: Amber/Red badge + `Last execution failed: ${failureReason}` (e.g. `APP_NOT_IN_FOREGROUND` or `BLE_NOT_CONNECTED`).
     - If never triggered: Muted badge + `Armed · Awaiting conditions`.
4. **Action Row**:
   - `Edit Scenario`: Outlined button (`MistBlue` text and border).
   - `Delete Scenario`: Text button with Red accent (`#EF4444`).

### Destructive Action Confirmation:
Tapping `Delete Scenario` displays an `AlertDialog`:
- Title: `Delete "${rule.name}"?`
- Message: `This automation rule will be permanently deleted from local storage. Any active evaluation for this scenario will stop immediately.`
- Confirm Button: `Delete` (`ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF4444))`).
- Dismiss Button: `Cancel`.

---

## 7. Safety & Actuator Governance Integration

Automation rules trigger physical hardware (vibrator, Bluetooth radio). The UI must maintain strict safety boundaries:

1. **Global Safety Status Pill**: At the top of the screen, display the current state of the `ActuatorCoordinator`:
   - `Rate Limiter Active`: 15-minute persistent vibration guard is running.
   - `Emergency Stop Available`: If a vibration rule runs unexpectedly, a one-tap `EMERGENCY STOP` button is accessible.
2. **Foreground Safety Clarification**:
   - Note to user: `Android battery optimization restricts continuous background vibration. Smart rules execute in foreground or during active foreground recording sessions.`
3. **Hardware Availability Badge**:
   - If phone vibrator is missing: Vibration action is disabled with an explanatory tooltip: `Vibrator hardware unavailable on this device`.

---

## 8. State Mapping to Compose Implementation

```kotlin
// Proposed composable signature for the redesigned screen
@Composable
fun SmartScenariosScreen(
    state: SmartScenariosUiState,
    actuatorSafetyState: ActuatorUiState,
    onSaveRule: (SmartRule) -> Unit,
    onToggleRule: (SmartRule, Boolean) -> Unit,
    onDeleteRule: (SmartRule) -> Unit,
    onEmergencyStop: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
)
```
- **File target**: `app/src/main/java/com/example/sleeptrackersimulator/ui/smart/SmartScenariosScreen.kt`
- **Theme wrap**: Wrapped in full `Scaffold(containerColor = MaterialTheme.colorScheme.background)` with `TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))`.
- **Window insets**: Standard `windowInsets = ScaffoldDefaults.contentWindowInsets` applied automatically by Compose.
