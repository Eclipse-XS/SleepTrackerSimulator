"""Leakage-resistant evaluation helpers for real or consented SleepSense datasets."""
from __future__ import annotations

from dataclasses import dataclass
import numpy as np


@dataclass(frozen=True)
class GroupSplit:
    train_indices: np.ndarray
    test_indices: np.ndarray


def subject_wise_split(subject_ids, test_fraction: float = 0.2, seed: int = 4319) -> GroupSplit:
    subjects = np.asarray(subject_ids)
    if not 0.0 < test_fraction < 1.0:
        raise ValueError("test_fraction must be between zero and one")
    unique = np.unique(subjects)
    if unique.size < 2:
        raise ValueError("At least two independent subjects are required")
    rng = np.random.default_rng(seed)
    shuffled = rng.permutation(unique)
    test_count = max(1, int(round(unique.size * test_fraction)))
    test_subjects = set(shuffled[:test_count].tolist())
    test_mask = np.array([subject in test_subjects for subject in subjects])
    return GroupSplit(np.flatnonzero(~test_mask), np.flatnonzero(test_mask))


def assert_no_recording_leakage(train_recording_ids, test_recording_ids) -> None:
    overlap = set(train_recording_ids).intersection(test_recording_ids)
    if overlap:
        raise ValueError(f"Recording leakage detected for {len(overlap)} recording(s)")


def expected_calibration_error(probabilities, labels, bins: int = 10) -> float:
    probs = np.asarray(probabilities, dtype=np.float64)
    labels = np.asarray(labels)
    if probs.ndim != 2 or probs.shape[0] != labels.size:
        raise ValueError("probabilities and labels have incompatible shapes")
    if not np.all(np.isfinite(probs)) or np.any(probs < 0):
        raise ValueError("probabilities must be finite and non-negative")
    confidence = probs.max(axis=1)
    predictions = probs.argmax(axis=1)
    edges = np.linspace(0.0, 1.0, bins + 1)
    ece = 0.0
    for index in range(bins):
        mask = (confidence > edges[index]) & (confidence <= edges[index + 1])
        if np.any(mask):
            accuracy = np.mean(predictions[mask] == labels[mask])
            ece += np.mean(mask) * abs(accuracy - np.mean(confidence[mask]))
    return float(ece)
