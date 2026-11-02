"""Stateful Semi-Markov Process for Nocturnal Movement and Rest Simulation.

Models duration-aware latent state transitions (REST, RESTLESS, ACTIVE)
with realistic temporal persistence, subject-specific restlessness tendencies,
and episodic movement bursts.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence

import numpy as np

from ml.simulation.subject_profile import VirtualSubject

STATE_REST = 0
STATE_RESTLESS = 1
STATE_ACTIVE = 2

STATE_NAMES = {
    STATE_REST: "REST",
    STATE_RESTLESS: "RESTLESS",
    STATE_ACTIVE: "ACTIVE",
}


@dataclass(frozen=True)
class LatentStateInterval:
    """A ground truth contiguous interval of a single latent activity state."""

    interval_id: str
    session_id: str
    start_sec: float
    end_sec: float
    state: int
    state_name: str

    @property
    def duration_sec(self) -> float:
        return self.end_sec - self.start_sec


class SemiMarkovStateSimulator:
    """Generates continuous-time latent state sequences using semi-Markov dynamics."""

    def __init__(self, subject: VirtualSubject, seed: int = 42):
        self.subject = subject
        self.rng = np.random.default_rng(seed)

    def _sample_duration(self, state: int) -> float:
        """Samples state duration in seconds from realistic duration distributions."""
        if state == STATE_REST:
            # REST: Log-normal distribution with median ~150s, scaled down for restless subjects
            # Restlessness tendency (0.1 - 0.5) reduces median rest duration
            median = 180.0 * (1.0 - 0.8 * self.subject.restlessness_tendency)
            sigma = 0.65
            mu = np.log(max(20.0, median))
            dur = self.rng.lognormal(mean=mu, sigma=sigma)
            return float(np.clip(dur, 20.0, 900.0))

        elif state == STATE_RESTLESS:
            # RESTLESS: Positional adjustment, micro-awakening (5s to 45s, median ~14s)
            shape = 2.5
            scale = 5.5 * self.subject.movement_scale
            dur = self.rng.gamma(shape=shape, scale=scale)
            return float(np.clip(dur, 4.0, 60.0))

        else:
            # ACTIVE: Vigorous turn, sitting up, limb movement (4s to 35s, median ~10s)
            shape = 2.0
            scale = 4.5 * self.subject.movement_scale
            dur = self.rng.gamma(shape=shape, scale=scale)
            return float(np.clip(dur, 3.0, 45.0))

    def _next_state(self, current_state: int) -> int:
        """Transitions to the next state according to subject-biased transition matrix."""
        p_restless = 0.80 + 0.15 * self.subject.restlessness_tendency
        p_active_from_rest = 1.0 - p_restless

        if current_state == STATE_REST:
            return STATE_RESTLESS if self.rng.random() < p_restless else STATE_ACTIVE

        elif current_state == STATE_RESTLESS:
            # From restless, 75% return to rest, 25% escalate to active
            p_to_rest = 0.78 - 0.20 * self.subject.restlessness_tendency
            return STATE_REST if self.rng.random() < p_to_rest else STATE_ACTIVE

        else:
            # From active, usually de-escalates to restless (70%), or straight to rest (30%)
            return STATE_RESTLESS if self.rng.random() < 0.70 else STATE_REST

    def simulate_timeline(
        self,
        session_id: str,
        total_duration_sec: float,
        initial_state: int = STATE_REST,
    ) -> list[LatentStateInterval]:
        """Simulates full timeline of non-overlapping contiguous latent state intervals.

        Args:
            session_id: Unique identifier for the recording session.
            total_duration_sec: Desired session length in seconds.
            initial_state: Starting state (default: REST).

        Returns:
            Ordered list of LatentStateInterval instances spanning [0, total_duration_sec].
        """
        intervals: list[LatentStateInterval] = []
        current_time = 0.0
        current_state = initial_state
        interval_idx = 0

        while current_time < total_duration_sec:
            duration = self._sample_duration(current_state)
            end_time = min(total_duration_sec, current_time + duration)

            intervals.append(
                LatentStateInterval(
                    interval_id=f"{session_id}-state-{interval_idx:04d}",
                    session_id=session_id,
                    start_sec=round(current_time, 3),
                    end_sec=round(end_time, 3),
                    state=current_state,
                    state_name=STATE_NAMES[current_state],
                )
            )

            current_time = end_time
            interval_idx += 1
            current_state = self._next_state(current_state)

        return intervals
