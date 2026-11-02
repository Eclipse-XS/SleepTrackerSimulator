"""Model Promotion and Staging Engine for SleepSense 2.0.

Provides controlled, audited, atomic, and rollback-capable promotion of candidate TFLite models
from 'ml/artifacts/candidates/' into 'app/src/main/assets/'.
Guarantees strict candidate/target allowlists, path containment, mandatory manifest verification,
tensor contract validation, temporary staging, atomic replacement, post-promotion validation,
and automated rollback on failure.
"""

from __future__ import annotations

import contextlib
from datetime import datetime, timezone
import hashlib
import json
import os
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

ALLOWED_CANDIDATES = frozenset({
    "multimodal_classifier_candidate.tflite",
    "activity_classifier_candidate.tflite",
})

ALLOWED_TARGETS = frozenset({
    "multimodal_classifier.tflite",
    "activity_classifier.tflite",
})

ALLOWED_PAIRINGS = {
    "multimodal_classifier_candidate.tflite": "multimodal_classifier.tflite",
    "activity_classifier_candidate.tflite": "activity_classifier.tflite",
}


def compute_sha256(path: Path | str) -> str:
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
        self.candidate_dir = Path(candidate_dir).resolve()
        self.assets_dir = Path(assets_dir).resolve()
        self.backup_dir = Path(backup_dir).resolve()
        self.promotions_log = Path(promotions_log).resolve()

    def _verify_path_containment(self, file_path: Path, parent_dir: Path) -> None:
        """Enforces that file_path is strictly inside parent_dir without path traversal."""
        resolved = file_path.resolve()
        parent_resolved = parent_dir.resolve()
        if parent_resolved not in resolved.parents and resolved.parent != parent_resolved:
            raise PermissionError(
                f"Path traversal detected: '{resolved}' is not contained within '{parent_resolved}'"
            )

    def verify_candidate(
        self,
        candidate_filename: str,
        target_filename: str | None = None,
    ) -> dict[str, Any]:
        """Validates candidate model existence, manifest integrity, tensor contracts, and TFLite loadability."""
        # 1. Allowlist check
        if candidate_filename not in ALLOWED_CANDIDATES:
            raise ValueError(
                f"Candidate '{candidate_filename}' is not in allowed candidates: {sorted(ALLOWED_CANDIDATES)}"
            )

        expected_target = ALLOWED_PAIRINGS[candidate_filename]
        if target_filename is not None and target_filename != expected_target:
            raise ValueError(
                f"Incompatible candidate '{candidate_filename}' for target '{target_filename}'. "
                f"Expected target '{expected_target}'."
            )
        effective_target = target_filename or expected_target

        model_path = (self.candidate_dir / candidate_filename).resolve()
        self._verify_path_containment(model_path, self.candidate_dir)

        if not model_path.exists():
            raise FileNotFoundError(f"Candidate model not found at: {model_path}")

        sha256 = compute_sha256(model_path)

        # 2. Mandatory Manifest Verification
        manifest_path = (self.candidate_dir / "candidate_manifest.json").resolve()
        if not manifest_path.exists():
            raise FileNotFoundError(
                f"Mandatory candidate manifest not found at: {manifest_path}. "
                "Promotion requires candidate_manifest.json."
            )

        manifest_data = json.loads(manifest_path.read_text(encoding="utf-8"))

        # Verify candidate checksum against manifest
        if candidate_filename.startswith("multimodal"):
            expected_sha = manifest_data.get("tflite_sha256")
            if not expected_sha:
                raise ValueError("candidate_manifest.json is missing required 'tflite_sha256' key")
            if sha256 != expected_sha:
                raise ValueError(
                    f"Candidate SHA256 mismatch for {candidate_filename}! File={sha256}, Manifest={expected_sha}"
                )
        elif candidate_filename.startswith("activity"):
            expected_sha = manifest_data.get("dense_tflite_sha256") or manifest_data.get("tflite_sha256")
            if not expected_sha:
                raise ValueError("candidate_manifest.json is missing 'dense_tflite_sha256' or 'tflite_sha256' key")
            if sha256 != expected_sha:
                raise ValueError(
                    f"Candidate SHA256 mismatch for {candidate_filename}! File={sha256}, Manifest={expected_sha}"
                )

        # 3. Verify TFLite loadability, tensor contracts, and test inference
        try:
            interpreter = tf.lite.Interpreter(model_path=str(model_path))
            interpreter.allocate_tensors()
            input_details = interpreter.get_input_details()
            output_details = interpreter.get_output_details()

            # Exact tensor contract validation based on model target
            if effective_target == "multimodal_classifier.tflite":
                if len(input_details) != 2:
                    raise ValueError(f"Multimodal classifier requires exactly 2 input tensors, found {len(input_details)}")
                shapes = [list(d["shape"]) for d in input_details]
                has_accel = any(s == [1, 20, 3] for s in shapes)
                has_hr = any(s == [1, 2] for s in shapes)
                if not (has_accel and has_hr):
                    raise ValueError(f"Multimodal classifier requires inputs [1, 20, 3] and [1, 2], found {shapes}")
                if len(output_details) != 1 or list(output_details[0]["shape"]) != [1, 3]:
                    raise ValueError(f"Multimodal classifier requires output [1, 3], found {[d['shape'].tolist() for d in output_details]}")

            elif effective_target == "activity_classifier.tflite":
                if len(input_details) != 1:
                    raise ValueError(f"Activity classifier requires exactly 1 input tensor, found {len(input_details)}")
                in_shape = list(input_details[0]["shape"])
                if in_shape != [1, 20, 3]:
                    raise ValueError(f"Activity classifier requires Android input [1, 20, 3], found {in_shape}")
                if len(output_details) != 1 or list(output_details[0]["shape"]) != [1, 3]:
                    raise ValueError(f"Activity classifier requires output [1, 3], found {[d['shape'].tolist() for d in output_details]}")

            # Execute dummy test inference
            for detail in input_details:
                shape = detail["shape"]
                concrete_shape = [s if s is not None and s > 0 else 1 for s in shape]
                dummy_tensor = np.zeros(concrete_shape, dtype=detail["dtype"])
                interpreter.set_tensor(detail["index"], dummy_tensor)

            interpreter.invoke()
            out_tensor = interpreter.get_tensor(output_details[0]["index"])
            if out_tensor is None or len(out_tensor) == 0:
                raise ValueError("TFLite inference produced empty output")

            if not np.all(np.isfinite(out_tensor)):
                raise ValueError("TFLite inference produced non-finite values (NaN / Inf)")

        except Exception as e:
            raise RuntimeError(f"Candidate TFLite verification failed for {model_path}: {e}") from e

        return {
            "status": "VERIFIED",
            "candidate_file": candidate_filename,
            "target_file": effective_target,
            "sha256": sha256,
            "size_bytes": model_path.stat().st_size,
            "inputs": [{"name": d["name"], "shape": d["shape"].tolist(), "dtype": str(d["dtype"])} for d in input_details],
            "outputs": [{"name": d["name"], "shape": d["shape"].tolist(), "dtype": str(d["dtype"])} for d in output_details],
            "manifest_version": manifest_data.get("model_version", "unknown"),
        }

    def backup_production_assets(self) -> tuple[Path, dict[str, str]]:
        """Creates timestamped backup of current production assets."""
        ts = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
        target_backup_dir = (self.backup_dir / ts).resolve()
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
        """Safely promotes candidate model to production asset directory after verification and backup.

        Uses temporary staging, atomic file replacement, post-promotion verification,
        and automated rollback upon failure.
        """
        # 1. Target allowlist check and path containment
        if target_filename not in ALLOWED_TARGETS:
            raise ValueError(f"Target '{target_filename}' is not permitted. Allowed: {sorted(ALLOWED_TARGETS)}")

        dest_path = (self.assets_dir / target_filename).resolve()
        self._verify_path_containment(dest_path, self.assets_dir)

        # 2. Verify candidate model
        verification = self.verify_candidate(candidate_filename, target_filename=target_filename)

        # 3. Backup existing assets
        backup_dir, pre_promotion_hashes = self.backup_production_assets()

        # 4. Stage promotion into temporary file in assets directory (<target>.staging.<pid>)
        candidate_path = (self.candidate_dir / candidate_filename).resolve()
        pid = os.getpid()
        staging_dest = dest_path.with_name(f"{dest_path.name}.staging.{pid}")

        try:
            shutil.copy2(candidate_path, staging_dest)

            # Pre-flight check on staged asset
            staged_sha = compute_sha256(staging_dest)
            if staged_sha != verification["sha256"]:
                raise ValueError(
                    f"Staging copy corrupted! Expected {verification['sha256']}, got {staged_sha}"
                )

            # 5. Atomic replacement: swap staging file into production location
            os.replace(staging_dest, dest_path)

            # 6. Post-promotion validation
            post_interpreter = tf.lite.Interpreter(model_path=str(dest_path))
            post_interpreter.allocate_tensors()
            in_dets = post_interpreter.get_input_details()
            out_dets = post_interpreter.get_output_details()

            for detail in in_dets:
                shape = [s if s is not None and s > 0 else 1 for s in detail["shape"]]
                post_interpreter.set_tensor(detail["index"], np.zeros(shape, dtype=detail["dtype"]))
            post_interpreter.invoke()
            res = post_interpreter.get_tensor(out_dets[0]["index"])
            if res is None or len(res) == 0:
                raise ValueError("Post-promotion inference produced empty output")

            post_promotion_sha = compute_sha256(dest_path)

        except Exception as e:
            # Clean up staging file if present
            if staging_dest.exists():
                with contextlib.suppress(Exception):
                    staging_dest.unlink()

            # Automatic rollback from backup
            if target_filename in pre_promotion_hashes and (backup_dir / target_filename).exists():
                shutil.copy2(backup_dir / target_filename, dest_path)

            raise RuntimeError(f"Promotion failed and was rolled back for {target_filename}: {e}") from e

        receipt = {
            "status": "PROMOTED",
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

    def rollback(
        self,
        backup_dir: Path | str,
        target_filename: str | None = None,
        notes: str = "Rollback executed",
    ) -> dict[str, Any]:
        """Rolls back production asset(s) to a previously verified backup."""
        b_dir = Path(backup_dir).resolve()
        if not b_dir.exists():
            raise FileNotFoundError(f"Backup directory not found: {b_dir}")

        manifest_file = b_dir / "backup_manifest.json"
        if not manifest_file.exists():
            raise FileNotFoundError(f"Backup manifest not found: {manifest_file}")

        manifest_data = json.loads(manifest_file.read_text(encoding="utf-8"))
        backed_up_files = manifest_data.get("backed_up_files", {})

        files_to_restore = [target_filename] if target_filename else list(backed_up_files.keys())
        restored = {}

        for fname in files_to_restore:
            if fname not in backed_up_files:
                raise ValueError(f"File '{fname}' not found in backup manifest: {manifest_file}")

            backup_src = b_dir / fname
            if not backup_src.exists():
                raise FileNotFoundError(f"Backed up asset missing at: {backup_src}")

            # Verify backup integrity
            expected_sha = backed_up_files[fname]
            actual_sha = compute_sha256(backup_src)
            if actual_sha != expected_sha:
                raise ValueError(
                    f"Backup integrity compromised for {fname}! Expected {expected_sha}, got {actual_sha}"
                )

            dest_path = (self.assets_dir / fname).resolve()
            self._verify_path_containment(dest_path, self.assets_dir)

            pid = os.getpid()
            staging_path = dest_path.with_name(f"{dest_path.name}.rollback.staging.{pid}")
            shutil.copy2(backup_src, staging_path)
            os.replace(staging_path, dest_path)

            # Validate restored asset
            interp = tf.lite.Interpreter(model_path=str(dest_path))
            interp.allocate_tensors()
            restored[fname] = actual_sha

        receipt = {
            "status": "ROLLED_BACK",
            "rolled_back_at_utc": datetime.now(timezone.utc).isoformat(),
            "backup_directory": str(b_dir),
            "restored_files": restored,
            "notes": notes,
        }

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
