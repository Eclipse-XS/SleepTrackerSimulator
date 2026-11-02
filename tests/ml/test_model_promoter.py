"""Tests for Model Promotion and Staging Engine."""

from __future__ import annotations

import json
from pathlib import Path
import tempfile

import numpy as np
import pytest
import tensorflow as tf

from ml.export.model_promoter import ModelPromoter, compute_sha256


@pytest.fixture
def dummy_tflite_setup(tmp_path: Path):
    candidates_dir = tmp_path / "candidates"
    assets_dir = tmp_path / "assets"
    backups_dir = tmp_path / "backups"
    candidates_dir.mkdir()
    assets_dir.mkdir()
    backups_dir.mkdir()

    # Create a small valid Keras model and convert to TFLite
    model = tf.keras.Sequential([
        tf.keras.layers.Input(shape=(20, 3)),
        tf.keras.layers.Flatten(),
        tf.keras.layers.Dense(3, activation="softmax"),
    ])
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tflite_bytes = converter.convert()

    # Write initial production asset
    prod_model_path = assets_dir / "activity_classifier.tflite"
    prod_model_path.write_bytes(tflite_bytes)

    # Write candidate model
    cand_model_path = candidates_dir / "activity_classifier_candidate.tflite"
    cand_model_path.write_bytes(tflite_bytes)
    cand_sha = compute_sha256(cand_model_path)

    # Write candidate manifest
    manifest_path = candidates_dir / "candidate_manifest.json"
    manifest_path.write_text(json.dumps({
        "model_version": "2.1.0",
        "dense_tflite_sha256": cand_sha,
    }))

    return {
        "candidates_dir": candidates_dir,
        "assets_dir": assets_dir,
        "backups_dir": backups_dir,
        "tflite_bytes": tflite_bytes,
        "cand_sha": cand_sha,
    }


def test_verify_candidate_success(dummy_tflite_setup):
    setup = dummy_tflite_setup
    promoter = ModelPromoter(
        candidate_dir=setup["candidates_dir"],
        assets_dir=setup["assets_dir"],
        backup_dir=setup["backups_dir"],
    )
    report = promoter.verify_candidate("activity_classifier_candidate.tflite")
    assert report["status"] == "VERIFIED"
    assert report["sha256"] == setup["cand_sha"]
    assert report["manifest_version"] == "2.1.0"


def test_verify_candidate_rejects_missing_file(dummy_tflite_setup):
    setup = dummy_tflite_setup
    cand_path = setup["candidates_dir"] / "activity_classifier_candidate.tflite"
    if cand_path.exists():
        cand_path.unlink()

    promoter = ModelPromoter(
        candidate_dir=setup["candidates_dir"],
        assets_dir=setup["assets_dir"],
        backup_dir=setup["backups_dir"],
    )
    with pytest.raises(FileNotFoundError):
        promoter.verify_candidate("activity_classifier_candidate.tflite")


def test_verify_candidate_rejects_tampered_sha(dummy_tflite_setup):
    setup = dummy_tflite_setup
    manifest_path = setup["candidates_dir"] / "candidate_manifest.json"
    manifest_path.write_text(json.dumps({
        "model_version": "2.1.0",
        "dense_tflite_sha256": "0000000000000000000000000000000000000000000000000000000000000000",
    }))

    promoter = ModelPromoter(
        candidate_dir=setup["candidates_dir"],
        assets_dir=setup["assets_dir"],
        backup_dir=setup["backups_dir"],
    )
    with pytest.raises(ValueError, match="Candidate SHA256 mismatch"):
        promoter.verify_candidate("activity_classifier_candidate.tflite")


def test_verify_candidate_rejects_legacy_six_feature_shape(dummy_tflite_setup):
    """Android consumes temporal windows; the legacy [1, 6] feature model is incompatible."""
    setup = dummy_tflite_setup
    legacy_model = tf.keras.Sequential([
        tf.keras.layers.Input(shape=(6,)),
        tf.keras.layers.Dense(3, activation="softmax"),
    ])
    legacy_bytes = tf.lite.TFLiteConverter.from_keras_model(legacy_model).convert()
    candidate = setup["candidates_dir"] / "activity_classifier_candidate.tflite"
    candidate.write_bytes(legacy_bytes)
    (setup["candidates_dir"] / "candidate_manifest.json").write_text(json.dumps({
        "model_version": "legacy",
        "dense_tflite_sha256": compute_sha256(candidate),
    }))

    promoter = ModelPromoter(
        candidate_dir=setup["candidates_dir"],
        assets_dir=setup["assets_dir"],
        backup_dir=setup["backups_dir"],
    )
    with pytest.raises(RuntimeError, match=r"requires Android input \[1, 20, 3\]"):
        promoter.verify_candidate("activity_classifier_candidate.tflite")


def test_promote_creates_backup_and_copies_asset(dummy_tflite_setup):
    setup = dummy_tflite_setup
    promoter = ModelPromoter(
        candidate_dir=setup["candidates_dir"],
        assets_dir=setup["assets_dir"],
        backup_dir=setup["backups_dir"],
        promotions_log=setup["backups_dir"] / "test_promotions.jsonl",
    )

    receipt = promoter.promote(
        candidate_filename="activity_classifier_candidate.tflite",
        target_filename="activity_classifier.tflite",
        notes="Test unit promotion",
    )

    assert receipt["sha256"] == setup["cand_sha"]
    assert Path(receipt["backup_directory"]).exists()

    # Check backup directory contents
    backups = promoter.list_backups()
    assert len(backups) == 1
    assert "activity_classifier.tflite" in backups[0]["backed_up_files"]


def test_verify_candidate_rejects_missing_manifest(dummy_tflite_setup):
    """Missing candidate_manifest.json must raise FileNotFoundError."""
    setup = dummy_tflite_setup
    manifest_path = setup["candidates_dir"] / "candidate_manifest.json"
    manifest_path.unlink()

    promoter = ModelPromoter(
        candidate_dir=setup["candidates_dir"],
        assets_dir=setup["assets_dir"],
        backup_dir=setup["backups_dir"],
    )
    with pytest.raises(FileNotFoundError, match="Mandatory candidate manifest not found"):
        promoter.verify_candidate("activity_classifier_candidate.tflite")


def test_verify_candidate_rejects_disallowed_names(dummy_tflite_setup):
    """Candidate filename not on allowlist must be rejected."""
    setup = dummy_tflite_setup
    promoter = ModelPromoter(
        candidate_dir=setup["candidates_dir"],
        assets_dir=setup["assets_dir"],
        backup_dir=setup["backups_dir"],
    )
    with pytest.raises(ValueError, match="not in allowed candidates"):
        promoter.verify_candidate("malicious_model.tflite")


def test_promote_rejects_disallowed_target_name(dummy_tflite_setup):
    """Target filename not on allowlist must be rejected."""
    setup = dummy_tflite_setup
    promoter = ModelPromoter(
        candidate_dir=setup["candidates_dir"],
        assets_dir=setup["assets_dir"],
        backup_dir=setup["backups_dir"],
    )
    with pytest.raises(ValueError, match="not permitted"):
        promoter.promote(
            candidate_filename="activity_classifier_candidate.tflite",
            target_filename="malicious_target.tflite",
        )


def test_promote_rollback_method_restores_asset(dummy_tflite_setup):
    """Explicit rollback restores previous asset and validates it."""
    setup = dummy_tflite_setup
    promoter = ModelPromoter(
        candidate_dir=setup["candidates_dir"],
        assets_dir=setup["assets_dir"],
        backup_dir=setup["backups_dir"],
        promotions_log=setup["backups_dir"] / "test_promotions.jsonl",
    )

    # Initial asset content
    prod_model_path = setup["assets_dir"] / "activity_classifier.tflite"
    original_sha = compute_sha256(prod_model_path)

    # Execute promotion
    promo_receipt = promoter.promote(
        candidate_filename="activity_classifier_candidate.tflite",
        target_filename="activity_classifier.tflite",
    )
    backup_dir = promo_receipt["backup_directory"]

    # Deliberately mutate production asset
    prod_model_path.write_bytes(b"MUTATED_GARBAGE_BYTES")
    assert compute_sha256(prod_model_path) != original_sha

    # Rollback from backup
    rollback_receipt = promoter.rollback(backup_dir=backup_dir, target_filename="activity_classifier.tflite")
    assert rollback_receipt["status"] == "ROLLED_BACK"
    assert compute_sha256(prod_model_path) == original_sha


def test_promote_auto_rollback_on_post_validation_failure(dummy_tflite_setup, monkeypatch):
    """If post-promotion validation fails, promoter automatically rolls back destination to previous state."""
    setup = dummy_tflite_setup
    promoter = ModelPromoter(
        candidate_dir=setup["candidates_dir"],
        assets_dir=setup["assets_dir"],
        backup_dir=setup["backups_dir"],
        promotions_log=setup["backups_dir"] / "test_promotions.jsonl",
    )

    prod_model_path = setup["assets_dir"] / "activity_classifier.tflite"
    original_sha = compute_sha256(prod_model_path)

    # Inject failure during post-promotion verification
    orig_interpreter = tf.lite.Interpreter
    def failing_interpreter(*args, **kwargs):
        interp = orig_interpreter(*args, **kwargs)
        # Fail when loading from assets_dir (post-promotion)
        if "assets" in str(kwargs.get("model_path", "")):
            raise RuntimeError("Post-promotion hardware parity check failure!")
        return interp

    monkeypatch.setattr(tf.lite, "Interpreter", failing_interpreter)

    with pytest.raises(RuntimeError, match="Promotion failed and was rolled back"):
        promoter.promote(
            candidate_filename="activity_classifier_candidate.tflite",
            target_filename="activity_classifier.tflite",
        )

    # Production asset was restored to original_sha
    assert compute_sha256(prod_model_path) == original_sha
