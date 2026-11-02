"""Controlled Sensor Fault and BLE Impairment Simulator.

Injects reproducible fault scenarios into synthetic sensor streams, including
temporary and extended BLE disconnections, accelerometer packet dropouts,
orientation shifts, and sensor glitches, while producing structured data
quality event telemetry.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence

import numpy as np


@dataclass(frozen=True)
class DataQualityEvent:
    """An observable data quality or sensor failure event."""

    event_id: str
    session_id: str
    event_type: str  # "BLE_DISCONNECT", "ACCEL_DROPOUT", "SENSOR_OUTLIER", "ORIENTATION_SHIFT"
    severity: str    # "INFO", "WARNING", "ERROR"
    timestamp_utc_ms: int
    details: str


class FailureSimulator:
    """Injects controlled faults and logs quality events into synthetic sessions."""

    def __init__(self, seed: int = 42):
        self.rng = np.random.default_rng(seed)

    def inject_faults(
        self,
        session_id: str,
        accel_samples: list[dict[str, any]],
        hr_samples: list[dict[str, any]],
        scenario: str = "standard",  # "standard", "ble_dropout", "noisy_sensors", "heavy_dropout"
    ) -> tuple[list[dict[str, any]], list[dict[str, any]], list[DataQualityEvent]]:
        """Applies fault injection scenario and returns modified samples and event log."""
        events: list[DataQualityEvent] = []
        mod_accel = list(accel_samples)
        mod_hr = list(hr_samples)

        if not mod_accel or not mod_hr:
            return mod_accel, mod_hr, events

        start_time_ms = mod_accel[0]["utc_millis"]
        end_time_ms = mod_accel[-1]["utc_millis"]
        duration_ms = end_time_ms - start_time_ms

        event_counter = 0

        # Scenario: BLE Dropout (temporary 30-90 second disconnection)
        if scenario in ("ble_dropout", "heavy_dropout") or (scenario == "standard" and self.rng.random() < 0.25):
            dropout_duration_ms = int(self.rng.uniform(30_000, 90_000))
            if duration_ms > dropout_duration_ms + 10_000:
                dropout_start_ms = start_time_ms + int(self.rng.uniform(10_000, duration_ms - dropout_duration_ms))
                dropout_end_ms = dropout_start_ms + dropout_duration_ms

                # Remove HR samples in dropout window
                initial_hr_count = len(mod_hr)
                mod_hr = [
                    h for h in mod_hr
                    if not (dropout_start_ms <= h["utc_millis"] <= dropout_end_ms)
                ]
                dropped_count = initial_hr_count - len(mod_hr)

                events.append(
                    DataQualityEvent(
                        event_id=f"{session_id}-dqe-{event_counter:03d}",
                        session_id=session_id,
                        event_type="BLE_DISCONNECT",
                        severity="WARNING",
                        timestamp_utc_ms=dropout_start_ms,
                        details=f"Temporary BLE GATT disconnect for {dropout_duration_ms // 1000}s ({dropped_count} packets lost)",
                    )
                )
                event_counter += 1

        # Scenario: Accelerometer Packet Loss / Buffer Stall
        if scenario in ("noisy_sensors", "heavy_dropout") or (scenario == "standard" and self.rng.random() < 0.15):
            gap_dur_ms = int(self.rng.uniform(500, 2500))
            if duration_ms > gap_dur_ms + 5000:
                gap_start_ms = start_time_ms + int(self.rng.uniform(5000, duration_ms - gap_dur_ms))
                gap_end_ms = gap_start_ms + gap_dur_ms

                initial_accel_count = len(mod_accel)
                mod_accel = [
                    s for s in mod_accel
                    if not (gap_start_ms <= s["utc_millis"] <= gap_end_ms)
                ]
                dropped_accel = initial_accel_count - len(mod_accel)

                events.append(
                    DataQualityEvent(
                        event_id=f"{session_id}-dqe-{event_counter:03d}",
                        session_id=session_id,
                        event_type="ACCEL_DROPOUT",
                        severity="WARNING",
                        timestamp_utc_ms=gap_start_ms,
                        details=f"Sensor hardware FIFO drop for {gap_dur_ms}ms ({dropped_accel} samples omitted)",
                    )
                )
                event_counter += 1

        # Scenario: Sensor Outlier Glitch (rare saturation spike)
        if scenario in ("noisy_sensors",) or (scenario == "standard" and self.rng.random() < 0.10):
            if len(mod_accel) > 20:
                spike_idx = self.rng.integers(10, len(mod_accel) - 10)
                # Saturate accelerometer reading to sensor dynamic range limit (+/- 78.4 m/s^2)
                mod_accel[spike_idx] = dict(mod_accel[spike_idx])
                mod_accel[spike_idx]["x"] = 78.48  # 8G clipping
                mod_accel[spike_idx]["accuracy"] = 0  # Unreliable accuracy

                events.append(
                    DataQualityEvent(
                        event_id=f"{session_id}-dqe-{event_counter:03d}",
                        session_id=session_id,
                        event_type="SENSOR_OUTLIER",
                        severity="INFO",
                        timestamp_utc_ms=mod_accel[spike_idx]["utc_millis"],
                        details="Transient 8G sensor saturation outlier injected",
                    )
                )
                event_counter += 1

        return mod_accel, mod_hr, events
