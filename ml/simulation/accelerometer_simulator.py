"""Biomechanical Tri-Axial Accelerometer Time-Series Simulator.

Generates realistic 20 Hz raw accelerometer streams matching Android
Sensor.TYPE_ACCELEROMETER conventions, including Earth gravity, 3D device
orientation, placement mechanical attenuation, temporal noise correlation,
and movement dynamics.
"""

from __future__ import annotations

from typing import Sequence

import numpy as np

from ml.simulation.state_machine import STATE_ACTIVE, STATE_REST, STATE_RESTLESS, LatentStateInterval
from ml.simulation.subject_profile import VirtualSubject


class AccelerometerSimulator:
    """Simulates tri-axial accelerometer streams with physical placement dynamics."""

    def __init__(self, subject: VirtualSubject, seed: int = 42):
        self.subject = subject
        self.rng = np.random.default_rng(seed)
        self.sampling_hz = 20
        self.dt = 1.0 / self.sampling_hz  # 0.05 seconds

        # Precompute 3D orientation rotation matrix
        self.gravity_vector = self._compute_rotated_gravity()

        # Placement mechanical attenuation factors for mattress-transmitted vibration
        self.placement_attenuation = {
            "MATTRESS": 1.00,
            "BODY_CHEST": 1.35,
            "NIGHTSTAND": 0.14,  # Strong mechanical decoupling from bed!
        }.get(subject.preferred_placement, 1.00)

    def _compute_rotated_gravity(self) -> np.ndarray:
        """Rotates [0, 0, 9.80665] by subject's pitch, roll, yaw angles."""
        pitch_rad = np.radians(self.subject.device_orientation_euler[0])
        roll_rad = np.radians(self.subject.device_orientation_euler[1])
        yaw_rad = np.radians(self.subject.device_orientation_euler[2])

        # Rotation matrices
        rx = np.array([
            [1.0, 0.0, 0.0],
            [0.0, np.cos(pitch_rad), -np.sin(pitch_rad)],
            [0.0, np.sin(pitch_rad), np.cos(pitch_rad)],
        ])
        ry = np.array([
            [np.cos(roll_rad), 0.0, np.sin(roll_rad)],
            [0.0, 1.0, 0.0],
            [-np.sin(roll_rad), 0.0, np.cos(roll_rad)],
        ])
        rz = np.array([
            [np.cos(yaw_rad), -np.sin(yaw_rad), 0.0],
            [np.sin(yaw_rad), np.cos(yaw_rad), 0.0],
            [0.0, 0.0, 1.0],
        ])

        rot = rz @ ry @ rx
        base_g = np.array([0.0, 0.0, 9.80665], dtype=np.float32)
        return rot @ base_g

    def generate_interval_signal(
        self,
        interval: LatentStateInterval,
        start_utc_ms: int,
        start_elapsed_ns: int,
    ) -> list[dict[str, any]]:
        """Generates 20 Hz raw accelerometer samples for a single latent state interval.

        Returns:
            List of dictionaries matching Android SensorSampleEntity schema:
            session_id, elapsed_nanos, utc_millis, x, y, z, accuracy, source_kind
        """
        duration = interval.duration_sec
        n_samples = int(round(duration * self.sampling_hz))
        if n_samples <= 0:
            return []

        t = np.linspace(0.0, duration, n_samples, endpoint=False, dtype=np.float32)
        movement_signal = np.zeros((n_samples, 3), dtype=np.float32)

        # 1. Base noise (colored AR(1) noise)
        phi = 0.45  # temporal auto-correlation
        white_noise = self.rng.normal(0.0, self.subject.sensor_noise_std, (n_samples, 3)).astype(np.float32)
        ar_noise = np.zeros_like(white_noise)
        ar_noise[0] = white_noise[0]
        for i in range(1, n_samples):
            ar_noise[i] = phi * ar_noise[i - 1] + (1.0 - phi) * white_noise[i]

        # 2. State-specific biomechanical dynamics
        if interval.state == STATE_REST:
            # Subtle respiratory rhythm (0.22 - 0.32 Hz) with tiny amplitude
            resp_freq = self.rng.uniform(0.22, 0.30)
            resp_amp = self.rng.uniform(0.012, 0.028) * self.placement_attenuation
            resp_wave = (np.sin(2.0 * np.pi * resp_freq * t) * resp_amp)[:, None]
            # Respiratory direction primarily on Z axis in supine posture
            resp_dir = np.array([[0.1, 0.1, 0.98]], dtype=np.float32)
            movement_signal = resp_wave * resp_dir + ar_noise

        elif interval.state == STATE_RESTLESS:
            # Periodic oscillations (1.5 - 3.2 Hz) with decaying envelope or wave packets
            n_packets = max(1, int(round(duration / self.rng.uniform(6.0, 12.0))))
            packet_signal = np.zeros((n_samples, 3), dtype=np.float32)

            for _ in range(n_packets):
                center_t = self.rng.uniform(0.0, duration)
                packet_dur = self.rng.uniform(2.5, 6.0)
                freq = self.rng.uniform(1.8, 3.2)
                amp = self.rng.uniform(0.35, 0.85) * self.subject.movement_scale * self.placement_attenuation

                # Direction in 3D
                direction = self.rng.normal(size=(1, 3))
                direction /= np.linalg.norm(direction)

                # Gaussian envelope wave packet
                env = np.exp(-0.5 * ((t - center_t) / (packet_dur / 3.0)) ** 2)[:, None]
                wave = np.sin(2.0 * np.pi * freq * (t - center_t))[:, None]
                packet_signal += (wave * env * amp * direction).astype(np.float32)

            movement_signal = packet_signal + ar_noise

        else:  # STATE_ACTIVE
            # High-intensity movement bursts, multi-frequency (3.0 - 6.5 Hz), and transient shocks
            freq1 = self.rng.uniform(3.0, 4.5)
            freq2 = self.rng.uniform(5.0, 6.5)
            amp = self.rng.uniform(1.6, 3.5) * self.subject.movement_scale * self.placement_attenuation

            dir1 = self.rng.normal(size=(1, 3))
            dir1 /= np.linalg.norm(dir1)
            dir2 = self.rng.normal(size=(1, 3))
            dir2 /= np.linalg.norm(dir2)

            osc = (np.sin(2.0 * np.pi * freq1 * t)[:, None] * dir1 * 0.7 +
                   np.sin(2.0 * np.pi * freq2 * t)[:, None] * dir2 * 0.3) * amp

            # Transient shock spikes (e.g. bed creaks, limb impacts, rolling over)
            n_spikes = max(1, int(round(duration / 4.0)))
            spikes = np.zeros((n_samples, 3), dtype=np.float32)
            for _ in range(n_spikes):
                spike_idx = self.rng.integers(0, n_samples)
                spike_dir = self.rng.normal(size=3)
                spike_dir /= np.linalg.norm(spike_dir)
                spike_amp = self.rng.uniform(1.5, 4.5) * self.subject.movement_scale * self.placement_attenuation
                spikes[spike_idx] += (spike_dir * spike_amp).astype(np.float32)

            movement_signal = osc + spikes + ar_noise

        # 3. Add Earth gravity and static sensor bias
        sensor_bias = self.rng.normal(0.0, 0.015, 3).astype(np.float32)
        total_acceleration = movement_signal + self.gravity_vector + sensor_bias

        # 4. Generate timestamped samples
        dt_ms = int(round(self.dt * 1000.0))
        dt_ns = int(round(self.dt * 1e9))

        samples = []
        for i in range(n_samples):
            # Introduce small realistic timestamp jitter (+/- 2 ms)
            jitter_ms = int(self.rng.integers(-2, 3))
            utc_ms = start_utc_ms + i * dt_ms + jitter_ms
            elapsed_ns = start_elapsed_ns + i * dt_ns + jitter_ms * 1_000_000

            samples.append({
                "session_id": interval.session_id,
                "elapsed_nanos": int(elapsed_ns),
                "utc_millis": int(utc_ms),
                "x": float(total_acceleration[i, 0]),
                "y": float(total_acceleration[i, 1]),
                "z": float(total_acceleration[i, 2]),
                "accuracy": 3,
                "source_kind": "SIMULATED",
            })

        return samples
