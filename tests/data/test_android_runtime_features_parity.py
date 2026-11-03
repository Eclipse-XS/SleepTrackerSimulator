"""Numerical parity tests for ``android_runtime_features_v1`` (DE/ML-008).

Compares Python :func:`extract_android_runtime_features_v1` and :class:`FeatureExtractor` against the
committed golden vectors produced by Kotlin ``AndroidRuntimeFeatureGoldenTest`` across all 6 required
scenarios: stationary gravity, inverted device, controlled movement pulse, varying-axis motion,
timestamp jitter, and a 19-sample window with a missing observation.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from pipelines.features.feature_extractor import (
    ANDROID_RUNTIME_FEATURES_V1,
    CENTERED_AXIS_SYNTHETIC_V1,
    FeatureExtractor,
    extract_android_runtime_features_v1,
    extract_centered_axis_synthetic_v1,
)

GOLDEN_PATH = Path(__file__).resolve().parent / "golden" / "android_runtime_features_v1.json"


def _load_golden() -> dict:
    assert GOLDEN_PATH.is_file(), f"Missing golden file {GOLDEN_PATH}; run AndroidRuntimeFeatureGoldenTest"
    return json.loads(GOLDEN_PATH.read_text(encoding="utf-8"))


@pytest.mark.parametrize(
    "scenario_name",
    [
        "stationary_gravity",
        "inverted_device",
        "movement_pulse",
        "varying_axis_motion",
        "timestamp_jitter",
        "missing_sample",
    ],
)
def test_android_runtime_features_v1_matches_kotlin_golden(scenario_name: str) -> None:
    golden = _load_golden()
    assert golden["feature_contract"] == ANDROID_RUNTIME_FEATURES_V1
    by_name = {s["name"]: s for s in golden["scenarios"]}
    assert scenario_name in by_name

    scenario = by_name[scenario_name]
    samples = scenario["samples"]
    expected = scenario["expected"]

    xs = [s["x"] for s in samples]
    ys = [s["y"] for s in samples]
    zs = [s["z"] for s in samples]
    elapsed = [s["elapsed_ns"] for s in samples]
    accuracies = [s["accuracy"] for s in samples]

    actual = extract_android_runtime_features_v1(
        xs=xs,
        ys=ys,
        zs=zs,
        elapsed_nanos=elapsed,
        accuracies=accuracies,
        expected_sample_count=scenario["expected_sample_count"],
    )

    assert actual["feature_contract"] == ANDROID_RUNTIME_FEATURES_V1
    assert actual["sample_count"] == expected["sample_count"]
    assert actual["coverage_fraction"] == pytest.approx(expected["coverage_fraction"], rel=1e-5, abs=1e-5)
    assert actual["motion_rms"] == pytest.approx(expected["motion_rms"], rel=1e-4, abs=1e-5)
    assert actual["variance"] == pytest.approx(expected["variance"], rel=1e-4, abs=1e-5)
    assert actual["jerk"] == pytest.approx(expected["jerk"], rel=1e-4, abs=1e-4)
    assert actual["peak_to_peak"] == pytest.approx(expected["peak_to_peak"], rel=1e-4, abs=1e-5)
    assert actual["quality_score"] == pytest.approx(expected["quality_score"], rel=1e-4, abs=1e-5)

    # Also verify through FeatureExtractor.extract_window_features
    window = {
        "window_id": f"golden-{scenario_name}",
        "session_id": "golden",
        "start_utc_millis": 1_700_000_000_000,
        "end_utc_millis": 1_700_000_000_950,
        "accel_x": xs if len(xs) == 20 else xs + [xs[-1]],
        "accel_y": ys if len(ys) == 20 else ys + [ys[-1]],
        "accel_z": zs if len(zs) == 20 else zs + [zs[-1]],
        "raw_accel_x": xs,
        "raw_accel_y": ys,
        "raw_accel_z": zs,
        "raw_elapsed_nanos": elapsed,
        "raw_accuracies": accuracies,
        "raw_sample_count": len(xs),
        "model_tensor_sample_count": 20,
    }
    wf = FeatureExtractor.extract_window_features(window, feature_contract=ANDROID_RUNTIME_FEATURES_V1)
    assert wf["raw_sample_count"] == expected["sample_count"]
    assert wf["sample_count"] == expected["sample_count"]
    assert wf["model_tensor_sample_count"] == 20
    assert wf["motion_rms"] == pytest.approx(expected["motion_rms"], rel=1e-4, abs=1e-5)
    assert wf["variance"] == pytest.approx(expected["variance"], rel=1e-4, abs=1e-5)
    assert wf["jerk"] == pytest.approx(expected["jerk"], rel=1e-4, abs=1e-4)
    assert wf["peak_to_peak"] == pytest.approx(expected["peak_to_peak"], rel=1e-4, abs=1e-5)


def test_centered_axis_synthetic_v1_stays_distinct_and_versioned() -> None:
    """Ensure the synthetic centered-axis contract remains separately addressable."""
    # Constant vector with non-standard magnitude (e.g. rotated or 10.5 m/s^2):
    # centered-axis has RMS=0, whereas android_runtime_features_v1 measures |10.5 - 9.80665| > 0.
    xs = [0.0] * 20
    ys = [0.0] * 20
    zs = [10.5] * 20
    rt = extract_android_runtime_features_v1(xs, ys, zs)
    syn = extract_centered_axis_synthetic_v1(xs, ys, zs)
    assert rt["feature_contract"] == ANDROID_RUNTIME_FEATURES_V1
    assert syn["feature_contract"] == CENTERED_AXIS_SYNTHETIC_V1
    assert rt["motion_rms"] == pytest.approx(10.5 - 9.80665, abs=1e-4)
    assert syn["motion_rms"] == pytest.approx(0.0, abs=1e-6)
