"""Model Promotion and Staging Engine for SleepSense 2.0.

Provides controlled, audited, and rollback-capable promotion of candidate TFLite models
from 'ml/artifacts/candidates/' into 'app/src/main/assets/'.
Prevents accidental overwriting of production assets during automated training runs.
"""

from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
from typing import Any

import numpy as np
import tensorflow as tf

ROOT = Path(__file__).resolve().parents[2]
ASSETS_DIR = ROOT / "app" / "src" / "main" / "assets"
CANDIDATE_DIR = ROOT / "ml" / "artifacts" / "candidates"
BACKUP_DIR = ROOT / "ml" / "artifacts" / "backups"
PROMOTIONS_LOG = ROOT / "ml" / "artifacts" / "promotions.jsonl"


def compute_sha256(path: Path) -> str:
    """Computes SHA-256 hash of a file."""
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


class ModelPromoter:
    """Safely promotes staged candidate models to Android production assets."""

    def __init__(
        self,
        candidate_dir: Path | str = CANDIDATE_DIR,
        assets_dir: Path | str = ASSETS_DIR,
        backup_dir: Path | str = BACKUP_DIR,
        promotions_log: Path | str = PROMOTIONS_LOG,
    ):
        self.candidate_dir = Path(candidate_dir)
        self.assets_dir = Path(assets_dir)
        self.backup_dir = Path(backup_dir)
        self.promotions_log = Path(promotions_log)

    def verify_candidate(self, candidate_filename: str) -> dict[str, Any]:
        """Validates candidate model existence, manifest integrity, and TFLite loadability."""
        model_path = self.candidate_dir / candidate_filename
        if not model_path.exists():
            raise FileNotFoundError(f"Candidate model not found at: {model_path}")

        sha256 = compute_sha256(model_path)
        manifest_path = self.candidate_dir / "candidate_manifest.json"

        manifest_data = {}
        if manifest_path.exists():
            manifest_data = json.loads(manifest_path.read_text(encoding="utf-8"))
            # If SHA256 is recorded in manifest, verify it matches
            if "tflite_sha256" in manifest_data and candidate_filename.startswith("multimodal"):
                expected_sha = manifest_data["tflite_sha256"]
                if sha256 != expected_sha:
                    raise ValueError(
                        f"Candidate SHA256 mismatch for {candidate_filename}! File={sha256}, Manifest={expected_sha}"
                    )
            elif "dense_tflite_sha256" in manifest_data and candidate_filename.startswith("activity"):
                expected_sha = manifest_data["dense_tflite_sha256"]
                if sha256 != expected_sha:
                    raise ValueError(
                        f"Candidate SHA256 mismatch for {candidate_filename}! File={sha256}, Manifest={expected_sha}"
                    )

        # Verify TFLite loadability via Interpreter
        try:
            interpreter = tf.lite.Interpreter(model_path=str(model_path))
            interpreter.allocate_tensors()
            input_details = interpreter.get_input_details()
            output_details = interpreter.get_output_details()

            # Execute dummy test inference
            for detail in input_details:
                shape = detail["shape"]
                # Replace None or 0 batch dimension with 1
                concrete_shape = [s if s is not None and s > 0 else 1 for s in shape]
                dummy_tensor = np.zeros(concrete_shape, dtype=detail["dtype"])
                interpreter.set_tensor(detail["index"], dummy_tensor)

            interpreter.invoke()
            out_tensor = interpreter.get_tensor(output_details[0]["index"])
            if out_tensor is None or len(out_tensor) == 0:
                raise ValueError("TFLite inference produced empty output")

        except Exception as e:
            raise RuntimeError(f"Candidate TFLite verification failed for {model_path}: {e}") from e

        return {
            "status": "VERIFIED",
            "candidate_file": candidate_filename,
            "sha256": sha256,
            "size_bytes": model_path.stat().st_size,
            "inputs": [{"name": d["name"], "shape": d["shape"].tolist(), "dtype": str(d["dtype"])} for d in input_details],
            "outputs": [{"name": d["name"], "shape": d["shape"].tolist(), "dtype": str(d["dtype"])} for d in output_details],
            "manifest_version": manifest_data.get("model_version", "unknown"),
        }

    def backup_production_assets(self) -> tuple[Path, dict[str, str]]:
        """Creates timestamped backup of current production assets."""
        ts = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
        target_backup_dir = self.backup_dir / ts
        target_backup_dir.mkdir(parents=True, exist_ok=True)

        backed_up_hashes = {}
        for asset_file in self.assets_dir.glob("*.tflite"):
            dest = target_backup_dir / asset_file.name
            shutil.copy2(asset_file, dest)
            backed_up_hashes[asset_file.name] = compute_sha256(asset_file)

        manifest_file = target_backup_dir / "backup_manifest.json"
        manifest_file.write_text(
            json.dumps({
                "backup_timestamp_utc": ts,
                "backed_up_files": backed_up_hashes,
            }, indent=2),
            encoding="utf-8",
        )
        return target_backup_dir, backed_up_hashes

    def promote(
        self,
        candidate_filename: str,
        target_filename: str,
        notes: str = "Automated promotion via ModelPromoter",
    ) -> dict[str, Any]:
        """Safely promotes candidate model to production asset directory after verification and backup."""
        # 1. Verify candidate model
        verification = self.verify_candidate(candidate_filename)

        # 2. Backup existing assets
        backup_dir, pre_promotion_hashes = self.backup_production_assets()

        # 3. Copy candidate to production asset location
        candidate_path = self.candidate_dir / candidate_filename
        dest_path = self.assets_dir / target_filename
        shutil.copy2(candidate_path, dest_path)

        post_promotion_sha = compute_sha256(dest_path)

        receipt = {
            "promoted_at_utc": datetime.now(timezone.utc).isoformat(),
            "candidate_source": str(candidate_path),
            "target_destination": str(dest_path),
            "candidate_filename": candidate_filename,
            "target_filename": target_filename,
            "sha256": post_promotion_sha,
            "pre_promotion_sha": pre_promotion_hashes.get(target_filename, "none"),
            "backup_directory": str(backup_dir),
            "verification_summary": verification,
            "notes": notes,
        }

        # Append to audit log
        self.promotions_log.parent.mkdir(parents=True, exist_ok=True)
        with open(self.promotions_log, "a", encoding="utf-8") as f:
            f.write(json.dumps(receipt) + "\n")

        return receipt

    def list_backups(self) -> list[dict[str, Any]]:
        """Lists all existing production asset backups."""
        if not self.backup_dir.exists():
            return []
        backups = []
        for d in sorted(self.backup_dir.iterdir(), reverse=True):
            if d.is_dir():
                manifest = d / "backup_manifest.json"
                if manifest.exists():
                    backups.append(json.loads(manifest.read_text(encoding="utf-8")))
        return backups
