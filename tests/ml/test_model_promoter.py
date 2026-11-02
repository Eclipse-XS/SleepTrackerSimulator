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
    promoter = ModelPromoter(
        candidate_dir=setup["candidates_dir"],
        assets_dir=setup["assets_dir"],
        backup_dir=setup["backups_dir"],
    )
    with pytest.raises(FileNotFoundError):
        promoter.verify_candidate("nonexistent_candidate.tflite")


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
