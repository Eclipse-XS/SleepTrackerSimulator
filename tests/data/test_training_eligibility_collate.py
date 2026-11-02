"""Tests for Authoritative Training Eligibility Filtering, Collate Policies, and Exclusion Auditing."""

from __future__ import annotations

import numpy as np
import pytest

from ml.data.window_dataset import CLASSES, WindowDataset


@pytest.fixture
def sample_dataset_with_ineligible_windows():
    """Builds a WindowDataset with 10 windows: 7 eligible and 3 ineligible."""
    dataset = WindowDataset()

    # Subject 1: 5 windows (4 eligible, 1 ineligible due to LOW_COVERAGE)
    dataset.data_by_subject["SUBJ-001"] = {
        "subject_id": "SUBJ-001",
        "placement": "CHEST",
        "sessions": ["S1", "S1", "S1", "S1", "S1"],
        "accel_windows": [np.zeros((20, 3), dtype=np.float32) for _ in range(5)],
        "hr_features": [[0.1, 1.0] for _ in range(5)],
        "tabular_features": [[0.05, 0.001, 0.1, 0.1, 0.1, 1.0] for _ in range(5)],
        "labels": [0, 0, 1, 2, 0],  # REST, REST, RESTLESS, ACTIVE, REST
        "window_ids": [f"W1-{i}" for i in range(5)],
        "training_eligible": [True, True, False, True, True],
        "quality_flags": ["", "", "LOW_COVERAGE", "", ""],
    }

    # Subject 2: 5 windows (3 eligible, 2 ineligible: 1 LARGE_GAP, 1 STALE_HEART_RATE)
    dataset.data_by_subject["SUBJ-002"] = {
        "subject_id": "SUBJ-002",
        "placement": "WRIST",
        "sessions": ["S2", "S2", "S2", "S2", "S2"],
        "accel_windows": [np.zeros((20, 3), dtype=np.float32) for _ in range(5)],
        "hr_features": [[0.2, 1.0] for _ in range(5)],
        "tabular_features": [[0.06, 0.002, 0.1, 0.1, 0.2, 1.0] for _ in range(5)],
        "labels": [0, 1, 0, 2, 1],  # REST, RESTLESS, REST, ACTIVE, RESTLESS
        "window_ids": [f"W2-{i}" for i in range(5)],
        "training_eligible": [True, False, True, True, False],
        "quality_flags": ["", "LARGE_GAP", "", "", "STALE_HEART_RATE,LOW_COVERAGE"],
    }

    dataset.total_windows_loaded = 10
    dataset.training_eligible_count = 7
    dataset.rejected_count = 3
    return dataset


def test_collate_subjects_training_strict_excludes_ineligible(sample_dataset_with_ineligible_windows):
    """TRAINING_STRICT policy must never include training_eligible=False windows."""
    ds = sample_dataset_with_ineligible_windows
    X_acc, X_hr, X_tab, y, wids = ds.collate_subjects(["SUBJ-001", "SUBJ-002"], policy="TRAINING_STRICT")

    # Total eligible = 4 from SUBJ-001 + 3 from SUBJ-002 = 7
    assert len(y) == 7
    assert len(X_acc) == 7
    assert len(wids) == 7

    # Ineligible window IDs must not be present
    assert "W1-2" not in wids
    assert "W2-1" not in wids
    assert "W2-4" not in wids


def test_collate_subjects_test_accepted_only(sample_dataset_with_ineligible_windows):
    """TEST_ACCEPTED_ONLY policy filters identically to TRAINING_STRICT."""
    ds = sample_dataset_with_ineligible_windows
    X_acc, X_hr, X_tab, y, wids = ds.collate_subjects(["SUBJ-001"], policy="TEST_ACCEPTED_ONLY")
    assert len(y) == 4
    assert "W1-2" not in wids


def test_collate_subjects_test_full_population_retains_all(sample_dataset_with_ineligible_windows):
    """TEST_FULL_POPULATION must include all windows and return the eligibility mask."""
    ds = sample_dataset_with_ineligible_windows
    X_acc, X_hr, X_tab, y, wids, sids, mask = ds.collate_subjects(
        ["SUBJ-001", "SUBJ-002"], policy="TEST_FULL_POPULATION", return_metadata=True
    )
    assert len(y) == 10
    assert len(wids) == 10
    assert len(mask) == 10

    # Exactly 3 windows have mask False
    assert np.sum(mask) == 7
    assert np.sum(~mask) == 3


def test_exclusion_audit_report(sample_dataset_with_ineligible_windows):
    """get_exclusion_audit accurately aggregates exclusions by subject, class, and reason."""
    ds = sample_dataset_with_ineligible_windows
    audit = ds.get_exclusion_audit()

    assert audit["total_windows"] == 10
    assert audit["training_eligible_count"] == 7
    assert audit["ineligible_count"] == 3

    # Breakdown by subject
    assert audit["by_subject"]["SUBJ-001"] == 1
    assert audit["by_subject"]["SUBJ-002"] == 2

    # Breakdown by class:
    # W1-2 has label 1 (RESTLESS)
    # W2-1 has label 1 (RESTLESS)
    # W2-4 has label 1 (RESTLESS)
    assert audit["by_class"]["RESTLESS"] == 3
    assert audit["by_class"]["REST"] == 0
    assert audit["by_class"]["ACTIVE"] == 0

    # Breakdown by reason:
    # W1-2: LOW_COVERAGE
    # W2-1: LARGE_GAP
    # W2-4: STALE_HEART_RATE, LOW_COVERAGE
    assert audit["by_reason"]["LOW_COVERAGE"] == 2
    assert audit["by_reason"]["LARGE_GAP"] == 1
    assert audit["by_reason"]["STALE_HEART_RATE"] == 1
