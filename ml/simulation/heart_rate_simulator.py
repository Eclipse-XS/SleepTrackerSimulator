"""Physiological Heart-Rate Dynamics and BLE Telemetry Simulator.

Simulates continuous-time cardiac autonomic response with physiological
inertia (differential rise and recovery rates), respiratory sinus arrhythmia
(RSA), Mayer wave low-frequency oscillations, multimodal decoupling, and
1 Hz asynchronous BLE GATT packet emissions.
"""

from __future__ import annotations

from typing import Sequence

import numpy as np

from ml.simulation.state_machine import STATE_ACTIVE, STATE_REST, STATE_RESTLESS, LatentStateInterval
from ml.simulation.subject_profile import VirtualSubject


class HeartRateSimulator:
    """Simulates physiological heart rate trajectories with inertia and BLE cadence."""

    def __init__(self, subject: VirtualSubject, seed: int = 42):
        self.subject = subject
        self.rng = np.random.default_rng(seed)
        self.current_hr = subject.baseline_hr

    def simulate_session_hr(
        self,
        intervals: Sequence[LatentStateInterval],
        start_utc_ms: int,
        ble_packet_loss_rate: float | None = None,
    ) -> list[dict[str, any]]:
        """Simulates asynchronous ~1 Hz BLE heart rate telemetry across session intervals.

        Returns:
            List of dictionaries matching Android HeartRateSampleEntity schema:
            sample_id, session_id, device_id, bpm, utc_millis, timing_uncertainty_millis, source_kind
        """
        loss_rate = ble_packet_loss_rate if ble_packet_loss_rate is not None else self.subject.ble_packet_loss_rate

        samples: list[dict[str, any]] = []
        sample_idx = 0

        # We step time at ~1.0 second intervals (BLE Heart Rate Measurement standard cadence)
        # Total duration
        total_duration_sec = intervals[-1].end_sec if intervals else 0.0
        current_time_sec = 0.0

        # Build interval lookup
        current_int_idx = 0
        n_intervals = len(intervals)

        # Autonomic state variables
        hr = float(self.subject.baseline_hr)

        # Decoupling generator: 5% chance of uncoupled nocturnal autonomic surge without motion
        decoupling_surge = 0.0
        surge_countdown = 0.0

        while current_time_sec < total_duration_sec:
            # Step size in seconds (with small BLE transmission jitter: 0.95s to 1.05s)
            dt = float(self.rng.uniform(0.95, 1.05))

            # Find active latent state at current_time_sec
            while current_int_idx < n_intervals - 1 and current_time_sec >= intervals[current_int_idx].end_sec:
                current_int_idx += 1

            active_state = intervals[current_int_idx].state if current_int_idx < n_intervals else STATE_REST

            # Determine physiological target HR based on state
            if active_state == STATE_REST:
                target_hr = self.subject.baseline_hr
                tau = self.subject.hr_tau_recovery_sec
            elif active_state == STATE_RESTLESS:
                target_hr = self.subject.baseline_hr + 0.45 * self.subject.hr_response_amplitude
                tau = self.subject.hr_tau_rise_sec
            else:  # STATE_ACTIVE
                target_hr = self.subject.baseline_hr + self.subject.hr_response_amplitude
                tau = self.subject.hr_tau_rise_sec * 0.85

            # Handle multimodal decoupling: occasional sympathetic surge during rest
            if surge_countdown <= 0:
                if active_state == STATE_REST and self.rng.random() < 0.02:
                    decoupling_surge = self.rng.uniform(8.0, 18.0)
                    surge_countdown = self.rng.uniform(15.0, 45.0)
                else:
                    decoupling_surge = 0.0
            else:
                surge_countdown -= dt

            effective_target = target_hr + decoupling_surge

            # Differential equation with physiological inertia: d(HR)/dt = (target - HR) / tau
            d_hr = (effective_target - hr) * (dt / max(1.0, tau))
            hr += d_hr

            # Add Mayer wave (0.1 Hz vascular resonance) and RSA (respiratory sinus arrhythmia)
            mayer = 1.2 * np.sin(2.0 * np.pi * 0.10 * current_time_sec)
            rsa = 0.8 * np.sin(2.0 * np.pi * 0.25 * current_time_sec)
            stochastic_noise = self.rng.normal(0.0, self.subject.hr_variability_std)

            instant_bpm = hr + mayer + rsa + stochastic_noise
            bounded_bpm = int(round(np.clip(instant_bpm, 38.0, 210.0)))

            # Check for simulated BLE packet drop
            is_dropped = self.rng.random() < loss_rate

            if not is_dropped:
                utc_ms = start_utc_ms + int(round(current_time_sec * 1000.0))
                samples.append({
                    "sample_id": f"{intervals[0].session_id}-hr-{sample_idx:05d}",
                    "session_id": intervals[0].session_id,
                    "device_id": "ble_simulated_band",
                    "bpm": bounded_bpm,
                    "utc_millis": utc_ms,
                    "timing_uncertainty_millis": 50,
                    "source_kind": "SIMULATED",
                })
                sample_idx += 1

            current_time_sec += dt

        return samples
