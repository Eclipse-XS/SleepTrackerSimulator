"""Virtual Subject Population and Biomechanical Profile Generator.

Generates heterogeneous virtual individuals with reproducible physiological,
behavioral, and sensor characteristics for nocturnal movement simulation.
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from typing import Sequence

import numpy as np


@dataclass(frozen=True)
class VirtualSubject:
    """Biomechanical and physiological profile of a simulated subject."""

    subject_id: str
    baseline_hr: float
    hr_response_amplitude: float
    hr_tau_rise_sec: float
    hr_tau_recovery_sec: float
    hr_variability_std: float
    movement_scale: float
    restlessness_tendency: float
    preferred_placement: str  # "MATTRESS", "NIGHTSTAND", "BODY_CHEST"
    device_orientation_euler: tuple[float, float, float]  # pitch, roll, yaw in degrees
    sensor_noise_std: float
    ble_packet_loss_rate: float
    typical_duration_min: float
    generator_version: str = "2.0.0"

    def to_dict(self) -> dict[str, any]:
        data = asdict(self)
        data["device_orientation_euler"] = list(self.device_orientation_euler)
        return data

    @classmethod
    def from_dict(cls, data: dict[str, any]) -> VirtualSubject:
        euler = tuple(data["device_orientation_euler"])
        clean_data = dict(data)
        clean_data["device_orientation_euler"] = euler
        return cls(**clean_data)


class SubjectPopulationGenerator:
    """Generates reproducible cohorts of virtual subjects with heterogeneous attributes."""

    @staticmethod
    def generate_population(
        count: int = 12,
        seed: int = 42,
        domain_shift: bool = False,
    ) -> list[VirtualSubject]:
        """Generates a cohort of virtual subjects.

        Args:
            count: Number of unique virtual individuals.
            seed: Master random seed.
            domain_shift: If True, shifts distribution toward nightstand placement,
                          sluggish HR recovery, higher restlessness, and noisy sensors.
        """
        rng = np.random.default_rng(seed)
        subjects: list[VirtualSubject] = []

        placements_standard = ["MATTRESS", "MATTRESS", "MATTRESS", "BODY_CHEST", "NIGHTSTAND"]
        placements_shifted = ["NIGHTSTAND", "NIGHTSTAND", "NIGHTSTAND", "MATTRESS"]

        for i in range(count):
            sub_id = f"SUBJ-{i + 1:03d}"

            if not domain_shift:
                baseline_hr = float(rng.uniform(50.0, 72.0))
                hr_response_amplitude = float(rng.uniform(14.0, 32.0))
                hr_tau_rise = float(rng.uniform(5.0, 14.0))
                hr_tau_recovery = float(rng.uniform(18.0, 42.0))
                hr_var_std = float(rng.uniform(1.2, 2.8))
                movement_scale = float(rng.uniform(0.75, 1.35))
                restlessness_tendency = float(rng.uniform(0.12, 0.32))
                placement = str(rng.choice(placements_standard))
                noise_std = float(rng.uniform(0.020, 0.045))
                ble_loss = float(rng.uniform(0.01, 0.06))
            else:
                # Distribution Shift: higher baseline HR, sluggish recovery, elevated restlessness,
                # predominantly decoupled nightstand placement, noisy accelerometer, higher BLE dropouts
                baseline_hr = float(rng.uniform(58.0, 80.0))
                hr_response_amplitude = float(rng.uniform(10.0, 24.0))
                hr_tau_rise = float(rng.uniform(8.0, 22.0))
                hr_tau_recovery = float(rng.uniform(30.0, 75.0))
                hr_var_std = float(rng.uniform(2.5, 5.0))
                movement_scale = float(rng.uniform(0.50, 1.60))
                restlessness_tendency = float(rng.uniform(0.25, 0.55))
                placement = str(rng.choice(placements_shifted))
                noise_std = float(rng.uniform(0.045, 0.095))
                ble_loss = float(rng.uniform(0.08, 0.22))

            pitch = float(rng.uniform(-15.0, 15.0))
            roll = float(rng.uniform(-15.0, 15.0))
            yaw = float(rng.uniform(-180.0, 180.0))

            typical_duration = float(rng.uniform(30.0, 60.0))

            subjects.append(
                VirtualSubject(
                    subject_id=sub_id,
                    baseline_hr=round(baseline_hr, 1),
                    hr_response_amplitude=round(hr_response_amplitude, 1),
                    hr_tau_rise_sec=round(hr_tau_rise, 1),
                    hr_tau_recovery_sec=round(hr_tau_recovery, 1),
                    hr_variability_std=round(hr_var_std, 2),
                    movement_scale=round(movement_scale, 2),
                    restlessness_tendency=round(restlessness_tendency, 3),
                    preferred_placement=placement,
                    device_orientation_euler=(round(pitch, 1), round(roll, 1), round(yaw, 1)),
                    sensor_noise_std=round(noise_std, 4),
                    ble_packet_loss_rate=round(ble_loss, 3),
                    typical_duration_min=round(typical_duration, 1),
                    generator_version="2.0.0" if not domain_shift else "2.0.0-shifted",
                )
            )

        return subjects
